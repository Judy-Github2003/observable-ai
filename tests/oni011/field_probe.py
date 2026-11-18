# Codex, 2026-11-18. Public controls for field decoding, semantic invariance and task-relevant readout.
"""PYTHONPATH=src; run field_probe.py cpu, then field_probe.py readout after DEV selection."""
import argparse
from dataclasses import asdict, replace
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import torch
from safetensors import safe_open
from safetensors.torch import save_file
from transformers import AutoTokenizer

from oni01.capsule import parse_prompt, render_prompt, solve
from oni01.runtime import MODEL, WEIGHT, Runtime
from oni011.codec import ROLE_NAMES, WIDTHS, StructuredCodec, memory_suffix
from oni011.injector import StructuralInjector


OUT = Path("build/oni-01r1/field_probes")
FILLER = "VerdAzur_703Q"
FIELDS = ("value", "source", "depth", "path", "status")
VALUE_SLICE = slice(sum(width for _, width in WIDTHS[:-1]), None)


def public_graph(source="willow", middle="finch", depth=2):
    # Keep the result identical even while the depth, evidence source or path changes.
    nodes = ["sprig", middle, source] if depth == 2 else ["sprig", source]
    facts = [f"PARENT {a} {b}" for a, b in zip(nodes, nodes[1:])]
    return facts + [f"PROPERTY {source} hue {FILLER}"]


def parse(facts):
    raw = render_prompt(facts, "PROPERTY sprig hue")
    context = parse_prompt(raw)
    assert context is not None
    return raw, context


def field_rows(target, field):
    if field == "value":
        return target[VALUE_SLICE]
    return target[ROLE_NAMES.index(field)]


def distance(a, b):
    return float(torch.linalg.vector_norm(a - b))


def field_options(cap):
    # Contrast only one field; this artificial counterfactual need not be a valid proof.
    return {
        "value": replace(cap, value="CrimViolet_39P"),
        "source": replace(cap, evidence_source="orchard"),
        "depth": replace(cap, inheritance_depth=3),
        "path": replace(cap, path=tuple(reversed(cap.path))),
        "status": replace(cap, status="UNKNOWN"),
    }


def write_json(name, payload):
    with (OUT / name).open("x", encoding="utf-8") as fp:
        json.dump(payload, fp, ensure_ascii=False, indent=2, allow_nan=False)


def cpu_checks():
    tokenizer = AutoTokenizer.from_pretrained(MODEL, local_files_only=True, trust_remote_code=False)
    with safe_open(WEIGHT, framework="pt", device="cpu") as disk:
        embedding = disk.get_tensor("model.embed_tokens.weight")
    runtime = SimpleNamespace(model=SimpleNamespace(get_input_embeddings=lambda: SimpleNamespace(weight=embedding)),
                              tokenizer=tokenizer)
    codec = StructuredCodec(runtime)
    raw, context = parse(public_graph())
    cap = solve(context)
    base, info = codec.encode(cap, "STRUCT_FORCED")
    assert cap.value == FILLER and len(info["value_token_ids"]) > 1
    # Independent nearest-template readout observes a field's residual direction before/after fusion.
    # This CPU fixture uses a shared frozen neutral embedding as its residual, not a decoder activation.
    neutral = codec.atoms("_")[0][-1].expand_as(base)
    alpha = 0.65
    public = {"capsule": asdict(cap), "prompt": raw, "value_tokens": info["value_token_ids"],
              "fixture_alpha": alpha, "encoder": codec.code_hash, "fields": {}}
    tensors = {"full": base.contiguous()}
    for role, alternate in field_options(cap).items():
        rival, _ = codec.encode(alternate, "STRUCT_FORCED")
        off, off_info = codec.encode(cap, "STRUCT_FORCED", drop=role)
        assert role not in off_info["active_fields"]
        assert distance(base, rival) > 0 and distance(base, off) > 0
        if role != "value":
            assert torch.equal(base[VALUE_SLICE], rival[VALUE_SLICE])
        predictions = []
        templates = [field_rows(neutral + alpha * codec.delta(neutral, t), role) for t in (base, rival)]
        for label, prototype in enumerate(templates):
            observed = prototype.to(torch.bfloat16).float()
            distances = [distance(observed, candidate) for candidate in templates]
            predicted = min(range(len(distances)), key=distances.__getitem__)
            assert predicted == label
            predictions.append(dict(expected=label, predicted=predicted, distances=distances))
        tensors["drop_" + role] = off.contiguous()
        public["fields"][role] = dict(code_l2=distance(base, rival), removed_code_l2=distance(base, off),
                                      expected_active=off_info["active_fields"], synthetic_readout=predictions)
    cases = {}
    for tag, facts in {"shuffled": public_graph()[::-1], "renamed": public_graph("alder", "robin"),
                       "shorter": public_graph(depth=1),
                       "cycle": public_graph() + ["PARENT willow sprig"],
                       "ambiguous": public_graph() + ["PROPERTY willow hue ochre"],
                       "depth4": ["PARENT sprig a", "PARENT a b", "PARENT b c", "PARENT c d",
                                  "PROPERTY d hue ochre"]}.items():
        _, other = parse(facts)
        changed = solve(other)
        target, _ = codec.encode(changed, "STRUCT_FORCED")
        cases[tag] = dict(status=changed.status, depth=changed.inheritance_depth, path=changed.path,
                          value=changed.value, code_l2=distance(base, target))
    assert cases["shuffled"]["code_l2"] == 0 and cases["cycle"]["code_l2"] == 0
    assert cases["renamed"]["depth"] == 2 and cases["renamed"]["value"] == cap.value
    assert cases["shorter"]["depth"] == 1 and cases["shorter"]["value"] == cap.value
    assert cases["ambiguous"]["status"] == "AMBIGUOUS" and cases["depth4"]["status"] == "UNKNOWN"
    public["public_graph_controls"] = cases
    public["value_subwords"] = tokenizer.convert_ids_to_tokens(info["value_token_ids"])
    write_json("cpu.json", public)
    save_file(tensors, OUT / "cpu_vectors.safetensors")


class SingleFieldCodec(StructuredCodec):
    """Diagnostic override of exactly one role during the native decoder hook."""
    def __init__(self, runtime, field, replacement):
        super().__init__(runtime)
        self.field, self.replacement = field, replacement

    def encode(self, cap, condition, drop=None, context=None):
        if self.replacement is not None:
            cap = replace(cap, **{self.field: self.replacement})
        return super().encode(cap, condition, drop, context)


def readout_controls():
    cfg = json.loads(Path("build/oni-01r1/development/config.json").read_text(encoding="utf-8"))
    rt = Runtime()
    rows = []
    for case, facts in enumerate([public_graph(), public_graph("maple", depth=1)]):
        raw, context = parse(facts)
        cap = solve(context)
        for role, attr, other in [("source", "evidence_source", "orchard"),
                                  ("depth", "inheritance_depth", 3)]:
            # This is a separate task readout on public inputs. Never use its results to choose the codec.
            slots = memory_suffix().split("\n", 2)[-1]
            suffix = (f"\nA memory record follows. Read the {role} field.\n{slots}\n"
                      f"Reply with only the content of the {role} field. Ignore underscores and blank padding.")
            rendered, encoded, offsets = rt.encode(raw + suffix)
            for arm in ("BASE", "ORIGINAL", "SWAPPED"):
                actual = getattr(cap, attr)
                codec = SingleFieldCodec(rt, attr, other if arm == "SWAPPED" else None)
                condition = "BASE" if arm == "BASE" else "STRUCT_FORCED"
                with StructuralInjector(rt, raw, rendered, offsets, condition=condition, layer=cfg["layer"],
                                        alpha=cfg["alpha"], codec=codec, trace=True) as hook:
                    generated = rt.generate(encoded, max_new=32)
                if hook.tensors:
                    save_file({k: t.contiguous().clone() for k, t in hook.tensors.items()},
                              OUT / f"readout_{case}_{role}_{arm}.safetensors")
                emitted = generated["generated_text"].strip().casefold().rstrip(".!?。！？").strip()
                expected = str(other if arm == "SWAPPED" else actual)
                rows.append(dict(case=case, field=role, arm=arm, expected=expected, exact=emitted==expected,
                                 other_field_values_constant=True, prompt_hash=hashlib.sha256(rendered.encode()).hexdigest(),
                                 capsule=asdict(cap), applied_field=expected if arm != "BASE" else None,
                                 generated=generated, injection=hook.row(), prompt=rendered))
    write_json("readout.json", dict(config=cfg, rows=rows))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("cpu", "readout"))
    mode = parser.parse_args().mode
    OUT.mkdir(parents=True, exist_ok=True)
    with torch.inference_mode():
        (cpu_checks if mode == "cpu" else readout_controls)()
