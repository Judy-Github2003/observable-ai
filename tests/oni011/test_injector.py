# Codex, 2026-11-18: GPU regression of the previously validated block-27 path.
"""Run as a script using the project's pinned GPU interpreter and PYTHONPATH=src."""
import hashlib
import json
from pathlib import Path

import torch
from torch.nn.functional import normalize
from safetensors.torch import save_file
from oni01.capsule import render_prompt
from oni01.injection import activation_bank
from oni01.runtime import Runtime, WEIGHT, WEIGHT_SHA, digest
from oni011.injector import StructuralInjector


class RegressionAnchor:
    def __init__(self, bank):
        self.bank = bank

    def positions(self, _, offsets):
        return [len(offsets)-1]

    def name(self, _):
        return "T1_legacy_dev_fixture"

    def identity(self, _):
        return hashlib.sha256(self.bank["blue"].numpy().tobytes()).hexdigest()

    def encode(self, cap, *_):
        return self.bank[cap.value][None], {"active_fields":["value"]}

    def delta(self, h, v):
        return normalize(v.to(h.device), dim=-1)*h.norm(dim=-1,keepdim=True)-h


@torch.inference_mode()
def main():
    out = Path("build/oni-01r1/t1")
    out.mkdir(parents=True,exist_ok=True)
    runtime = Runtime(inspect_file=True)
    (out/"model_map.json").write_text(json.dumps(runtime.describe(),indent=2),encoding="utf-8")
    bank,_=activation_bank(runtime,["blue"],[27])
    codec=RegressionAnchor(bank[27])
    raw=render_prompt(["PARENT sparrow bird","PARENT bird animal","PROPERTY bird color blue",
                       "PROPERTY animal color brown"],"PROPERTY sparrow color")
    prompt,inputs,offsets=runtime.encode(raw)
    results={}
    for c in ("BASE","STRUCT_FORCED","STRUCT_ZERO","STRUCT_RESTORE"):
        logits=[]
        def record(_,args,x):
            if not logits: logits.append(x[0,-1].detach().cpu().clone())
        handle=runtime.model.get_output_embeddings().register_forward_hook(record)
        try:
            with StructuralInjector(runtime,raw,prompt,offsets,condition=c,layer=27,
                                    alpha=.35,codec=codec,trace=True) as hook:
                gen=runtime.generate(inputs,max_new=8)
        finally:
            handle.remove()
        row=hook.row()|gen
        row["first_step_logits_hash"]=hashlib.sha256(logits[0].view(torch.uint8).numpy().tobytes()).hexdigest()
        results[c]=row
        save_file(hook.tensors|{"logits":logits[0]},out/f"{c}.safetensors")
    with (out/"outputs.json").open("w",encoding="utf-8") as f: json.dump(results,f,indent=2)
    for a,b in (("BASE","STRUCT_ZERO"),("STRUCT_FORCED","STRUCT_RESTORE")):
        assert results[a]["generated_ids"]==results[b]["generated_ids"]
        assert results[a]["first_step_logits_hash"]==results[b]["first_step_logits_hash"]
    for c in ("STRUCT_FORCED","STRUCT_RESTORE"):
        assert len(results[c]["steps"])>=2
        assert all(s["next_consumed"] and s["delta_norm"]>0 for s in results[c]["steps"][:2])
    assert digest(WEIGHT)==WEIGHT_SHA
    print("T1 PASS. Two observed generation steps, exact ON=RESTORE, BASE=ZERO, unchanged BF16.")


if __name__=="__main__":
    main()
