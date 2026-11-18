# Codex，2026-11-17：开发集标定后冻结层位/强度/门控并运行完整因果对照。
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

import torch
from safetensors.torch import load_file, save_file

from .baseline import GENERAL
from .capsule import parse_prompt, render_prompt, solve
from .injection import CODEC_VERSION, CONDITIONS, Injector, activation_bank, fit_gate
from .runtime import REVISION, WEIGHT, WEIGHT_SHA, Runtime, diff, digest, rows, stats, write_json, write_rows

ACCEPT = "oni01-prototype"
GRID = (0.35, 0.65, 1.0)
MODULES = ("capsule.py", "runtime.py", "baseline.py", "injection.py", "experiment.py")


def result_normalized(s):
    return re.sub(r"[.!?,。！？，]$", "", s.strip().casefold()).strip()


def context_value(raw):
    context = parse_prompt(raw)
    if context is None:
        return None
    capsule = solve(context)
    return capsule.value if capsule.status == "FOUND" else capsule.status


@torch.inference_mode()
def run_one(runtime, sample, condition, layer, alpha, bank, gate, *, generate=True):
    raw = sample["prompt"]
    prompt, inputs, offsets = runtime.encode(raw)
    with Injector(runtime, raw, prompt, offsets, bank, layer, alpha, gate, condition) as injection:
        # 单独保留首步分布用于因果诊断。生成仍调用原始、不受 logits 操作的解码流程。
        model_output = runtime.model(**inputs, use_cache=False, logits_to_keep=1)
        logits = model_output.logits[0, -1].detach().float().cpu()
        del model_output
        generation = runtime.generate(inputs) if generate else {}
    values, indices = logits.topk(5)
    out = sample | generation | injection.first | {
        "condition": condition, "model_commit": REVISION, "model_weight_sha256": WEIGHT_SHA,
        "prompt_hash": hashlib.sha256(prompt.encode()).hexdigest(), "codec_version": CODEC_VERSION,
        "layer_index": layer, "layer_module_name": f"{runtime.block_path}.{layer}", "alpha": alpha,
        "cache_enabled": False, "forward_hook_calls": injection.hits, "error": None,
        "top5_token_ids": indices.tolist(), "top5_logits": values.tolist(),
        "downstream_activation_summary": {name: stats(x) for name, x in injection.last_observed.items()},
    }
    if "expected" in sample:
        value = sample["expected"]
        token = runtime.first_token(value)
        out["target_token_logprob"] = float(logits.log_softmax(-1)[token])
        if generate:
            out["correct"] = result_normalized(out["generated_text"]) == result_normalized(value)
    return out, injection, logits


def dev_samples(dev):
    examples = [
        {"id": row["id"], "prompt": render_prompt(row["facts"], row["query"]), "expected": row["expected"]}
        for row in rows(dev)
    ]
    return examples + GENERAL + [
        {"id": "dev_conflict", "prompt": render_prompt(["PARENT vole meadow", "PARENT vole plain",
          "PROPERTY meadow coat gray", "PROPERTY plain coat ivory"], "PROPERTY vole coat"), "expected": "AMBIGUOUS"},
        {"id": "dev_malformed", "prompt": render_prompt(["PROPERTY willow shape round"], "PROPERTY willow"),
         "expected": "UNKNOWN"},
    ]


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class EventLog:
    def __init__(self, directory, phase):
        self.run_id = f"ONI-01/{phase}"
        self.count = 0
        self.events = (directory / "ONI01_EVENTS.jsonl").open("x", encoding="utf-8")
        self.steps = (directory / "ONI01_EVENTS.steps.log").open("x", encoding="utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.events.close()
        self.steps.close()

    def record(self, phase, **fields):
        self.count += 1
        event = {"event_id": f"{self.run_id}/{self.count}", "event_type": "step", "seq": self.count,
                 "timestamp": timestamp(), "run_id": self.run_id, "version": CODEC_VERSION,
                 "function": "transformer_forward", "phase": phase, **fields}
        self.events.write(json.dumps(event, ensure_ascii=False) + "\n")
        self.events.flush()
        self.steps.write(f"{event['timestamp']} {self.run_id} {self.count} {CODEC_VERSION} transformer_forward "
                         f"{phase} {fields.get('id')} {fields.get('condition')} layer={fields.get('layer_index')} "
                         f"delta_norm={fields.get('delta_norm')} error={fields.get('error')}\n")
        self.steps.flush()


def compare(out, injection, logits, original_injection, original_logits):
    if original_logits is None:
        return
    for name, reference in original_injection.last_observed.items():
        out["downstream_activation_summary"][name].update(diff(injection.last_observed[name], reference))
    out["logit_comparison"] = diff(logits, original_logits)
    out["logit_max_abs_change"] = float((logits - original_logits).abs().max())


def calibration(runtime, out, dev):
    directory = out / "development"
    directory.mkdir()
    samples = dev_samples(dev)
    active = samples[:12]
    values = {value for row in samples if (value := context_value(row["prompt"])) is not None}
    banks, origins = activation_bank(runtime, values, runtime.depths)
    write_json(directory / "anchor_provenance.json", origins)
    for layer, bank in banks.items():
        save_file({key: item.contiguous() for key, item in bank.items()}, directory / f"anchors_layer_{layer}.safetensors")
    summaries = []
    references = {}
    with EventLog(directory, "DEV") as log:
        for layer in runtime.depths:
            for sample in active:
                log.record("start", id=sample["id"], layer_index=layer, condition="BASE")
                row, capture, logits = run_one(runtime, sample, "BASE", layer, 0, banks[layer], None)
                references[layer, sample["id"]] = row, capture, logits
                log.record("success", **row)
            for alpha in GRID:
                matched = corrected = harmed = 0
                for sample in active:
                    baseline, before, base_logits = references[layer, sample["id"]]
                    log.record("start", id=sample["id"], layer_index=layer, condition="FORCED", alpha=alpha)
                    row, after, new_logits = run_one(runtime, sample, "FORCED", layer, alpha, banks[layer], None)
                    compare(row, after, new_logits, before, base_logits)
                    if row["delta_norm"] <= 0 or not row["next_consumed"] or not row["delta_finite"]:
                        raise RuntimeError("Forced injection did not reach the next decoder block")
                    log.record("success", **row)
                    matched += row["correct"]
                    corrected += row["correct"] and not baseline["correct"]
                    harmed += baseline["correct"] and not row["correct"]
                summaries.append({"layer": layer, "alpha": alpha, "correct": matched, "corrected": corrected, "harmed": harmed})
                print("DEV", summaries[-1], flush=True)
    selected = max(summaries, key=lambda row: (row["correct"], -row["harmed"], -row["alpha"], -row["layer"]))
    layer = selected["layer"]
    features, positive = [], []
    for sample in samples:
        _, capture, _ = run_one(runtime, sample, "BASE", layer, 0, banks[layer], None, generate=False)
        features.append(capture.samples["gate_input"])
        context = parse_prompt(sample["prompt"])
        positive.append(context is not None and solve(context).status == "FOUND")
    gate, scores = fit_gate(features, positive)
    gatefile = directory / "gate.safetensors"
    save_file({"direction": gate["direction"].contiguous()}, gatefile)
    write_json(directory / "gate_calibration.json", {"threshold": gate["threshold"], "dev_only": [
        {"id": sample["id"], "found": flag, "score": score} for sample, flag, score in zip(samples, positive, scores)]})
    write_json(directory / "calibration.json", {"grid": summaries, "selected": selected})
    freeze = {
        "revision": REVISION, "weight_sha256": WEIGHT_SHA,
        "tokenizer_sha256": runtime.describe()["tokenizer_sha256"], "code_version": ACCEPT,
        "dev_sha256": digest(dev), "selected": selected, "gate_threshold": gate["threshold"],
        "gate_sha256": digest(gatefile), "codec_version": CODEC_VERSION,
        "source_hashes": {name: digest(Path(__file__).parent / name) for name in MODULES},
        "codec_parameters": {"anchor_template": "Repeat this exact string as your answer: {value}",
                             "eval_values": "derive from input capsule once per case without answer labels",
                             "residual_position": "last prompt token", "cache_enabled": False},
    }
    write_json(out / "FREEZE.json", freeze)


def eval_cases(runtime, out, examfile):
    manifest = json.loads((out / "FREEZE.json").read_text(encoding="utf-8"))
    layer, alpha = (manifest["selected"][key] for key in ("layer", "alpha"))
    gatefile = out / "development/gate.safetensors"
    if (manifest["revision"] != REVISION or manifest["weight_sha256"] != WEIGHT_SHA
        or manifest["gate_sha256"] != digest(gatefile)
        or any(digest(Path(__file__).parent / key) != val for key, val in manifest["source_hashes"].items())):
        raise RuntimeError("ONI-01 freeze verification failed")
    gate = load_file(gatefile) | {"threshold": manifest["gate_threshold"]}
    cases = rows(examfile)
    if not cases or len({row["id"] for row in cases}) != len(cases):
        raise RuntimeError("An official case list with unique IDs is required")
    if any(set(row) != {"id", "world", "kind", "prompt"} for row in cases):
        raise RuntimeError("Official input must contain only the public case fields")
    directory = out / "official"
    directory.mkdir()
    trace = directory / "ONI01_ACTIVATION_TRACE"
    trace.mkdir()
    write_json(directory / "run_manifest.json", {"freeze_sha256": digest(out / "FREEZE.json"),
                                               "public_exam_sha256": digest(examfile),
                                               "cases": len(cases), "conditions": list(CONDITIONS)})
    written = 0
    with EventLog(directory, "HELDOUT") as log, (directory / "predictions.jsonl").open("x", encoding="utf-8") as predictions:
        for number, sample in enumerate(cases, 1):
            value = context_value(sample["prompt"])
            banks, anchors = activation_bank(runtime, [value] if value is not None else [], [layer])
            log.record("success", id=sample["id"], function_override="anchor_probe", anchor_provenance=anchors)
            baseline_injection = baseline_logits = None
            distributions = {}
            for condition in CONDITIONS:
                log.record("start", id=sample["id"], condition=condition, layer_index=layer)
                try:
                    row, injection, logits = run_one(runtime, sample, condition, layer, alpha, banks[layer], gate)
                    if condition == "BASE":
                        baseline_injection, baseline_logits = injection, logits
                    else:
                        compare(row, injection, logits, baseline_injection, baseline_logits)
                    row.pop("prompt")
                    distributions[condition] = logits.to(torch.bfloat16).contiguous()
                    if number <= 2 or condition == "FORCED" and number <= 6:
                        samples = injection.samples | {
                            f"downstream_{key}": tensor for key, tensor in injection.last_observed.items()
                            if int(key) in (layer + 1, len(runtime.blocks) - 1)}
                        save_file({key: ten.contiguous() for key, ten in samples.items()},
                                  trace / f"{sample['id']}_{condition}.safetensors")
                except (RuntimeError, ValueError, KeyError, TypeError, IndexError) as error:
                    row = {k: sample[k] for k in ("id", "world", "kind")}
                    row.update(condition=condition, generated_text="", error=f"{type(error).__name__}: {error}",
                               gate_value=None, triggered=False, delta_norm=None)
                    log.record("failed", **row)
                else:
                    log.record("success", **row)
                predictions.write(json.dumps(row, ensure_ascii=False) + "\n")
                predictions.flush()
                written += 1
            if distributions:
                # 首步 logits 供考官用私有目标 token 计算 log-prob，可在 CPU 端离线评分。
                save_file(distributions, directory / f"logits_{sample['id']}.safetensors")
            print(f"HELDOUT {number}/{len(cases)} responses={written}", flush=True)
    write_json(directory / "completion.json", {"responses": written,
                                               "predictions_sha256": digest(directory / "predictions.jsonl"),
                                               "weight_sha256_after": digest(WEIGHT)})
    if digest(WEIGHT) != WEIGHT_SHA:
        raise RuntimeError("Base model file changed during the ONI experiment")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("calibrate", "heldout"))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    args = parser.parse_args()
    runtime = Runtime()
    if args.stage == "calibrate":
        calibration(runtime, args.out, args.data)
    else:
        eval_cases(runtime, args.out, args.data)


if __name__ == "__main__":
    main()
