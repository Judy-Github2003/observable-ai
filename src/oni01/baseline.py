# Codex，2026-11-17：仅运行未注入的固定 BF16 模型，保存真实激活基线。
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from safetensors.torch import save_file

from .capsule import render_prompt
from .runtime import REVISION, WEIGHT, WEIGHT_SHA, Runtime, diff, digest, rows, write_json, write_rows

GENERAL = [
    {"id": "dev_control_1", "prompt": "Answer with one number. What is 7 + 5?", "expected": "12"},
    {"id": "dev_control_2", "prompt": "Reply with exactly the word maple.", "expected": "maple"},
    {"id": "dev_control_3", "prompt": "What is the opposite of hot? Use one word.", "expected": "cold"},
]


def baseline(out: Path, dev: Path):
    out.mkdir(parents=True, exist_ok=True)
    runtime = Runtime(inspect_file=True)
    mapped = runtime.describe()
    write_json(out / "model_map.json", mapped)
    print("MODEL", mapped["model_class"], mapped["blocks"], mapped["hidden_size"], flush=True)
    inputs = [
        {"id": row["id"], "prompt": render_prompt(row["facts"], row["query"]), "expected": row["expected"]}
        for row in rows(dev)
    ] + GENERAL
    trace = out / "ONI01_ACTIVATION_TRACE"
    trace.mkdir(exist_ok=True)
    reference = {}

    def collect():
        for index, sample in enumerate(inputs):
            metrics, captured, logits = runtime.original(sample["prompt"], runtime.depths)
            if index == 0:
                reference.update(captured)
            for layer, tensor in captured.items():
                metrics["activations"][str(layer)].update(diff(tensor, reference[layer]))
            target = runtime.first_token(sample["expected"])
            if index in (0, 3, 8, 12):
                save_file({f"layer_{layer}": tensor.cpu().contiguous() for layer, tensor in captured.items()},
                          trace / f"baseline_{sample['id']}.safetensors")
            item = sample | metrics | {"target_token_logprob": float(logits.log_softmax(-1)[target]),
                                       "target_token_id": target, "model_commit": REVISION,
                                       "model_weight_sha256": WEIGHT_SHA}
            print(sample["id"], repr(item["generated_text"]), flush=True)
            yield item

    write_rows(out / "baseline.jsonl", collect())
    if digest(WEIGHT) != WEIGHT_SHA:
        raise RuntimeError("Base model file changed during baseline")


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--out", required=True, type=Path)
    cli.add_argument("--dev", required=True, type=Path)
    args = cli.parse_args()
    baseline(args.out, args.dev)
