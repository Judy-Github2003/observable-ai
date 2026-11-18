# Codex, 2026-11-18. Post-run diagnostic of recorded native residual tensors from public readout probes.
"""Compare the bfloat16 vectors received by the next block against public field alternatives."""
from dataclasses import replace
import json
from types import SimpleNamespace

import torch
from safetensors import safe_open
from safetensors.torch import load_file
from transformers import AutoTokenizer

from field_probe import OUT, FIELDS, distance, field_options, field_rows, write_json
from oni01.capsule import Capsule
from oni01.runtime import MODEL, WEIGHT
from oni011.codec import ROLE_NAMES, StructuredCodec


def main():
    with safe_open(WEIGHT, framework="pt", device="cpu") as weights:
        embedding = weights.get_tensor("model.embed_tokens.weight")
    codec = StructuredCodec(SimpleNamespace(
        model=SimpleNamespace(get_input_embeddings=lambda: SimpleNamespace(weight=embedding)),
        tokenizer=AutoTokenizer.from_pretrained(MODEL, local_files_only=True, trust_remote_code=False)))
    experiment = json.loads((OUT / "readout.json").read_text(encoding="utf-8"))
    records = []
    for row in experiment["rows"]:
        if row["arm"] == "BASE":
            continue
        edited = row["field"]
        cap = Capsule(**row["capsule"])
        alternatives = field_options(cap)
        # Source/depth alternatives match those assigned independently in the public GPU experiment.
        if edited == "source":
            alternatives["source"] = replace(cap, evidence_source="orchard")
        else:
            alternatives["depth"] = replace(cap, inheritance_depth=3)
        stem = f"readout_{row['case']}_{edited}_{row['arm']}"
        data = load_file(OUT / (stem + ".safetensors"))
        for step in (0, 1):
            if f"step{step}_consumed" not in data:
                continue
            before = data[f"step{step}_before"].float()
            after = data[f"step{step}_after"].float()
            consumed = data[f"step{step}_consumed"].float()
            assert torch.equal(after, consumed)
            for role in FIELDS:
                distances = []
                for variant in [cap, alternatives[role]]:
                    code, _ = codec.encode(variant, "STRUCT_FORCED")
                    intended = before + experiment["config"]["alpha"] * codec.delta(before, code)
                    distances.append(distance(field_rows(consumed, role), field_rows(intended, role)))
                choice = min(range(2), key=distances.__getitem__)
                expected = int(edited == role and row["arm"] == "SWAPPED")
                records.append(dict(case=row["case"], experiment_field=edited, arm=row["arm"],
                                    field=role, step=step, expected_candidate=expected, decoded_candidate=choice,
                                    distances=distances, correct=expected == choice))
    write_json("native_field_audit.json", {"method": "two public candidates on consumed field slice",
                                           "roles": ROLE_NAMES, "records": records})
    print(f"{sum(r['correct'] for r in records)}/{len(records)} native field/step readouts exact")


if __name__ == "__main__":
    with torch.inference_mode():
        main()
