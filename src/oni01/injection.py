# Codex，2026-11-17：在实际 decoder 边界注入证据原型，记录下一层消费与消融。
from __future__ import annotations

from dataclasses import asdict
import hashlib
import math

import torch
from torch.nn import functional as F

from .capsule import parse_prompt, solve
from .runtime import diff, hidden, stats

CODEC_VERSION = "minicpm-answer-anchor-residual-v1"
CONDITIONS = ("BASE", "SHAM", "FORCED", "GATED", "ABLATE", "WRONG", "RECOVERY")
STATUSES = ("UNKNOWN", "AMBIGUOUS")
WRONG_VALUES = ("marble", "silver", "tangerine")


def token_position(offsets, span):
    start, end = span
    matched = [i for i, (a, b) in enumerate(offsets) if a < end and b > start]
    if not matched:
        raise RuntimeError("No tokenizer offsets overlap the selected span")
    return matched[-1]


@torch.inference_mode()
def activation_bank(runtime, values, layers):
    """Build word prototypes in separate model forwards, without asking an oracle."""
    values = sorted(set(values) | set(STATUSES) | set(WRONG_VALUES))
    vectors = {}
    originals = []
    for name in values:
        raw = f"Repeat this exact string as your answer: {name}"
        prompt, inputs, _ = runtime.encode(raw)
        originals.append((name, prompt, inputs))
    max_length = max(item[2].input_ids.shape[1] for item in originals)
    # Left padding keeps every true readout token at the last sequence position.
    ids = torch.stack([F.pad(encoded.input_ids[0], (max_length - encoded.input_ids.shape[1], 0),
                             value=runtime.tokenizer.pad_token_id) for _, _, encoded in originals])
    mask = torch.stack([F.pad(encoded.attention_mask[0], (max_length - encoded.attention_mask.shape[1], 0))
                        for _, _, encoded in originals])
    positions = (mask.long().cumsum(-1) - 1).clamp_min(0)
    handles = [runtime.blocks[layer].register_forward_hook(
        lambda _, _args, output, index=layer: vectors.update({index: hidden(output)[:, -1].detach().float().cpu()})
    ) for layer in layers]
    try:
        runtime.model(input_ids=ids, attention_mask=mask, position_ids=positions, use_cache=False, logits_to_keep=1)
    finally:
        for handle in handles:
            handle.remove()
    bank = {layer: {value: vectors[layer][i] for i, value in enumerate(values)} for layer in layers}
    provenance = [
        {"value": value, "anchor": f"Repeat this exact string as your answer: {value}", "token": -1,
         "prompt_hash": hashlib.sha256(prompt.encode()).hexdigest(),
         "vector_sha256": {str(i): hashlib.sha256(bytes(bank[i][value].contiguous().numpy())).hexdigest() for i in layers}}
        for value, prompt, _ in originals
    ]
    return bank, provenance


class Injector:
    def __init__(self, runtime, raw, prompt, offsets, bank, layer, alpha, gate, condition):
        self.runtime = runtime
        self.raw = raw
        self.context = parse_prompt(raw)
        self.bank = bank
        self.layer = layer
        self.alpha = alpha
        self.gate = gate
        self.condition = condition
        self.handles = []
        self.last_observed = {}
        self.first = {}
        self.samples = {}
        self.hits = 0
        self.readout_position = len(offsets) - 1
        raw_start = prompt.index(raw)
        # 门控定位只读取文本锚点，不依赖解析通过、规则选择或证据胶囊。
        anchor = raw.find("[ONI_QUERY]\n")
        start = anchor + len("[ONI_QUERY]\n")
        end = raw.find("\n", start)
        span = (start, end if end >= 0 else len(raw)) if anchor >= 0 else (max(0, len(raw) - 12), len(raw))
        if span[0] >= span[1]:
            span = (anchor, anchor + len("[ONI_QUERY]"))
        self.query_position = token_position(offsets, (raw_start + span[0], raw_start + span[1]))

    def __enter__(self):
        self.handles.append(self.runtime.blocks[self.layer].register_forward_hook(self.apply))
        self.handles.append(self.runtime.blocks[self.layer + 1].register_forward_pre_hook(self.next_input, with_kwargs=True))
        for i in range(self.layer + 1, len(self.runtime.blocks)):
            self.handles.append(self.runtime.blocks[i].register_forward_hook(
                lambda _, _args, value, index=i: self.observe_downstream(index, value)))
        return self

    def __exit__(self, *err):
        for hook in self.handles:
            hook.remove()

    def observe_downstream(self, index, output):
        if str(index) not in self.last_observed:
            self.last_observed[str(index)] = hidden(output)[0, self.readout_position].detach().float().cpu()

    def next_input(self, _module, args, kwargs):
        if "next_consumed" in self.first:
            return
        value = kwargs.get("hidden_states")
        if value is None:
            value = args[0]
        current = value[0, self.readout_position].detach().cpu()
        self.first["next_consumed"] = torch.equal(current, self.samples["hidden_after"])
        self.samples["next_block_input"] = current

    def apply(self, _module, _args, output):
        self.hits += 1
        tensor = hidden(output)
        # 无 KV cache 时每步重算前缀，固定修改原提示的回答起始位置。
        old = tensor[0, self.readout_position]
        feature = tensor[0, self.query_position].detach().float().cpu()
        score = None
        if self.gate is not None:
            score = float(F.normalize(feature, dim=0) @ self.gate["direction"])
        g = 0.0 if self.condition == "BASE" else 1.0
        if self.condition in {"GATED", "ABLATE", "RECOVERY"}:
            g = float(score is not None and score >= self.gate["threshold"])
        capsule = solve(self.context) if self.context is not None and self.condition != "BASE" else None
        chosen = capsule.value if capsule is not None and capsule.status == "FOUND" else None
        if capsule is not None and capsule.status == "UNKNOWN":
            chosen = "UNKNOWN"
        if capsule is not None and capsule.status == "AMBIGUOUS":
            chosen = "AMBIGUOUS"
        effective = bool(g and chosen and self.condition != "ABLATE")
        wrong = None
        if effective:
            current = old.detach().float()
            proto = self.bank[chosen].to(current.device)
            # Use norms of the same-layer real activation to calibrate the residual.
            requested = self.alpha * (F.normalize(proto, dim=0) * current.norm() - current)
            if self.condition == "SHAM":
                generator = torch.Generator(device="cpu").manual_seed(1701)
                control = torch.randn(current.shape, generator=generator).to(current.device)
                requested = F.normalize(control, dim=0) * requested.norm()
            if self.condition == "WRONG":
                wrong = next(value for value in WRONG_VALUES if value != chosen)
                alt = self.bank[wrong].to(current.device)
                control = F.normalize(alt, dim=0) * current.norm() - current
                requested = F.normalize(control, dim=0) * requested.norm()
            modified = tensor.clone()
            modified[0, self.readout_position] = (current + g * requested).to(tensor.dtype)
            new = modified[0, self.readout_position]
        else:
            modified, new = tensor, old
        if not self.first:
            old_stats, new_stats = stats(old), stats(new)
            difference = diff(new, old)
            self.first = {
                "gate_value": g, "gate_score": score, "gate_threshold": None if self.gate is None else self.gate["threshold"],
                "triggered": bool(g) if self.condition in {"GATED", "ABLATE", "RECOVERY"} else effective,
                "applied": effective, "capsule": asdict(capsule) if capsule is not None else None,
                "incorrect_capsule_value": wrong, "hidden_norm_before": old_stats["norm"],
                "hidden_norm_after": new_stats["norm"], "hidden_rms_before": old_stats["rms"],
                "hidden_rms_after": new_stats["rms"], **difference,
                "delta_ratio": difference["delta_norm"] / max(old_stats["norm"], 1e-8),
                "query_token_index": self.query_position, "readout_token_index": self.readout_position,
                "delta_finite": bool(torch.isfinite(new).all()),
            }
            self.samples.update(hidden_before=old.detach().cpu().clone(), hidden_after=new.detach().cpu().clone(),
                                gate_input=feature,
                                delta=(new.float() - old.float()).detach().cpu())
        return (modified, *output[1:]) if isinstance(output, tuple) else modified


def fit_gate(features, positive):
    """A small closed-form prototype classifier, calibrated only on DEV activations."""
    normalized = torch.stack([F.normalize(v.float(), dim=0) for v in features])
    labels = torch.tensor(positive, dtype=torch.bool)
    if not labels.any() or labels.all():
        raise RuntimeError("Gate development samples need both classes")
    direction = F.normalize(normalized[labels].mean(0) - normalized[~labels].mean(0), dim=0)
    scores = (normalized @ direction).tolist()
    ordered = sorted(set(scores))
    thresholds = [math.nextafter(ordered[0], -math.inf),
                  *[(a + b)*0.5 for a, b in zip(ordered, ordered[1:])],
                  math.nextafter(ordered[-1], math.inf)]
    def rank(threshold):
        recall = sum(score >= threshold and goal for score, goal in zip(scores, positive)) / sum(positive)
        fpr = sum(score >= threshold and not goal for score, goal in zip(scores, positive)) / (len(positive)-sum(positive))
        return recall - fpr, -fpr
    threshold = max(thresholds, key=rank)
    return {"direction": direction, "threshold": threshold}, scores
