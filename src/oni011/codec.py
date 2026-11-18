# Codex, 2026-11-18: position- and role-bound structured memory using frozen input embeddings.
"""No token ID changes and no auxiliary language-model evaluation. The slot text is identical for all arms."""
import hashlib
from pathlib import Path
from dataclasses import replace

import torch
from torch.nn.functional import normalize
from oni01.capsule import Capsule


ROLE_NAMES = ("status","entity","key","source","depth","path","rule")
WIDTHS = (("record",7),("source",8),("depth",3),("value",16))
MARKERS = {"record":"Record roles:","source":"Source field:","depth":"Depth field:","value":"Value field:"}
VERSION = "oni011-ordered-embedding-memory-v1"


def memory_suffix():
    return ("\nAn optional verified memory record follows. When the value field is populated, "
            "copy only its contents as the answer. Ignore blank padding and underscores. "
            "If the record is empty, answer the question normally.\n"
            + "\n".join(MARKERS[k] + " _"*width for k,width in WIDTHS) + "\nEnd of record.")


def shifted(vector, role, order=0):
    # A fixed orthogonal coordinate permutation binds each role and each path position.
    salt = int.from_bytes(hashlib.sha256(f"{role}:{order}".encode()).digest()[:4],"big")
    return torch.roll(vector,salt % vector.shape[-1],dims=-1)


class StructuredCodec:
    def __init__(self,runtime,site="memory"):
        if site not in {"memory","readout"}:
            raise ValueError(site)
        self.site=site
        self.tokenizer=runtime.tokenizer
        self.embedding=runtime.model.get_input_embeddings().weight.detach()
        self.sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        self.code_hash=hashlib.sha256((self.sha+VERSION+site).encode()).hexdigest()
        self._cache={}

    def name(self,condition):
        if condition=="VALUE_NO_AUX":
            return "E1_VALUE"
        return "E3_FRONTIER" if condition=="STRUCTURE_FIRST" else "E2_STRUCT"

    def identity(self,condition):
        return hashlib.sha256((self.code_hash+self.name(condition)).encode()).hexdigest()

    def positions(self,prompt,offsets):
        if self.site=="readout":
            return [len(offsets)-1]
        positions=[]
        for label,width in WIDTHS:
            left=prompt.rindex(MARKERS[label]+" _"*width)+len(MARKERS[label])
            # Locate each literal underscore, never include adjacent space/marker tokens.
            for i in range(width):
                at=left+2*i+1
                matches=[j for j,(a,b) in enumerate(offsets) if a<=at<b]
                if len(matches)!=1:
                    raise ValueError(f"Slot token alignment failed for {label}/{i}")
                positions.append(matches[0])
        if len(set(positions))!=len(positions):
            raise ValueError("Tokenizer merged multiple slots")
        return positions

    def atoms(self, text):
        text=str(text)
        if text not in self._cache:
            # Include a leading word boundary just as at each labeled field.
            ids=self.tokenizer.encode(" "+text if text else " ",add_special_tokens=False)
            raw=self.embedding[ids].float()
            self._cache[text]=(raw,ids)
        return self._cache[text]

    def filler(self,text):
        tokens,_=self.atoms(text)
        vector=tokens.mean(0)
        # Retain order for multi-subword role fillers without assigning any generation times.
        ordered=torch.stack([shifted(row,"subword",i) for i,row in enumerate(tokens)]).mean(0)
        return normalize(vector+0.15*ordered,dim=0)

    def encode(self,cap,condition,drop=None,context=None):
        structured=condition!="VALUE_NO_AUX"
        if condition=="COUNTERFACTUAL_PATH":
            cap=replace(cap,evidence_source="other_source",inheritance_depth=3,
                        path=(cap.query_entity,"other_a","other_b","other_source"))
        status=cap.status
        resolved=cap.value if cap.value is not None else status
        fields=dict(status=status,entity=cap.query_entity,key=cap.property_key,source=cap.evidence_source,
                    depth=None if cap.inheritance_depth is None else str(cap.inheritance_depth),
                    path=cap.path,rule=cap.rule_id)
        if condition=="STRUCTURE_FIRST":
            # One graph transition only; no completed BFS, distance decision or selected candidate value.
            fields["status"]=cap.evidence_status
            fields["path"]=tuple(sorted(p for c,p in context.parents if c==context.query_entity))
            resolved=None
        ablate=drop.removeprefix("drop_") if drop else None
        blank=self.atoms("")[0][-1]
        role_slots=[]
        active=[]
        for role in ROLE_NAMES:
            text=fields[role]
            if not structured or role==ablate or text is None or text==():
                role_slots.append(None)
                continue
            if role=="path":
                item=torch.stack([shifted(self.filler(node),role,i) for i,node in enumerate(text)]).mean(0)
            else:
                item=shifted(self.filler(text),role)
            role_slots.append(normalize(item,dim=0))
            active.append(role)
        # A zero target marks a passthrough slot: drop-field tests remove this field's actual intervention.
        zero=torch.zeros_like(blank)
        memory=[item if item is not None else zero for item in role_slots]
        info={}
        for role,width in WIDTHS[1:]:
            value=resolved if role=="value" else fields[role] if structured else None
            if value is None or role==ablate or (role=="value" and cap.value is None and ablate=="status"):
                memory.extend([zero]*width)
                continue
            embeddings,ids=self.atoms(value)
            # Explicit finite capacity; never silently truncate an unseen long value.
            if len(ids)>width:
                info[f"{role}_overflow"]=len(ids)
                if role=="value":
                    raise ValueError(f"value needs {len(ids)} tokens, memory capacity is {width}")
                embeddings=embeddings[:width]
            memory.extend([blank]*(width-len(embeddings)))
            memory.extend(embeddings.unbind())
            info[f"{role}_token_ids"]=ids
            if role not in active:
                active.append(role)
        target=torch.stack(memory)
        if self.site=="readout":
            target=normalize(target,dim=-1).sum(0,keepdim=True)
        info.update(active_fields=active,codec_version=VERSION,aux_answer_forwards=0)
        if condition in {"COUNTERFACTUAL_PATH", "STRUCTURE_FIRST"}:
            # Preserve the actual transformed fields beside the unmodified factual capsule.
            info.update(encoded_fields=fields, encoded_value=resolved)
        return target,info

    @staticmethod
    def delta(h,code):
        # Within each assigned slot, put the frozen embedding direction on the local residual scale.
        active=code.norm(dim=-1,keepdim=True)>0
        return torch.where(active,normalize(code,dim=-1)*h.norm(dim=-1,keepdim=True)-h,0.)
