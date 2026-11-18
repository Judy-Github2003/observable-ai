# Codex, 2026-11-18: isolate and audit paired interventions in the real decoder.
from contextlib import ExitStack
from dataclasses import asdict
import hashlib
import json
import time

import torch
from torch.nn.functional import normalize
from oni01.capsule import Capsule, parse_prompt, solve
from oni01.injection import token_position
from oni01.runtime import hidden


def fingerprint(value):
    data = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(data.encode()).hexdigest()


class StructuralInjector:
    def __init__(self, runtime, raw, rendered, offsets, *, condition, layer, alpha, codec,
                 gate=None, wrong_prompt=None, trace=False, drop=None, frozen_gate=None):
        self.runtime, self.condition, self.layer, self.alpha = runtime, condition, layer, alpha
        self.codec, self.gate, self.trace, self.drop = codec, gate, trace, drop
        self.frozen_gate = frozen_gate
        self.context, self.wrong_context = parse_prompt(raw), parse_prompt(wrong_prompt or "")
        self.positions = codec.positions(rendered, offsets)
        # Query span localization is textual even for malformed or general inputs.
        marker = "[ONI_QUERY]\n"
        start = raw.find(marker)
        left = start + len(marker) if start >= 0 else max(0, len(raw) - 12)
        end = raw.find("\n", left)
        right = end if end > left else len(raw)
        base = rendered.index(raw)
        self.query_position = token_position(offsets, (base + left, base + right))
        self.stack, self.tensors, self.steps, self.downstream = ExitStack(), {}, [], {}
        self.feature = self.score = self.effective_gate = self.capsule = None
        self.info, self.codes, self.alt_codes = {}, None, None
        self.compute_ms = self.codec_ms = 0.
        self.mode = ("activation" if condition in {"STRUCT_GATED", "STRUCT_GATE_RESTORE", "STRUCT_GATE_ZERO"}
                     else "zero" if condition == "BASE" else "forced")

    def __enter__(self):
        try:
            block = self.runtime.blocks[self.layer]
            self.stack.callback(block.register_forward_hook(self.apply).remove)
            nxt = self.runtime.blocks[self.layer + 1]
            self.stack.callback(nxt.register_forward_pre_hook(self.verify, with_kwargs=True).remove)
            for index in sorted({self.layer+1, len(self.runtime.blocks)-1}):
                def observe(_, args, output, i=index):
                    if len(self.steps) == 1:
                        vector = hidden(output)[0,-1].detach().float().cpu()
                        self.downstream[str(i)] = dict(norm=float(vector.norm()), rms=float(vector.square().mean().sqrt()))
                        self.observed[str(i)] = vector
                        if self.trace:
                            self.tensors[f"downstream_{i}"] = vector
                self.stack.callback(self.runtime.blocks[index].register_forward_hook(observe).remove)
            self.observed = {}
            return self
        except BaseException:
            self.stack.close()
            raise

    def __exit__(self, *exc):
        self.stack.close()

    def prepare(self, state):
        self.feature = state[0,self.query_position].detach().float().cpu()
        if self.gate is not None:
            self.score = float(normalize(self.feature, dim=0) @ self.gate["direction"].detach().float().cpu())
        if self.mode == "activation":
            if self.score is None:
                raise ValueError("Activation triplet requires calibrated activation gate")
            self.effective_gate = int(self.score >= self.gate["threshold"] if self.frozen_gate is None else self.frozen_gate)
        else:
            self.effective_gate = int(self.mode == "forced")
        if self.context is None or self.condition == "BASE":
            return
        start = time.perf_counter()
        # Explicit rule runs inside the decoder hook. Only public facts enter this function.
        if self.condition=="STRUCTURE_FIRST":
            own=Capsule("UNKNOWN",self.context.query_entity,self.context.property_key,self.context.input_hash,
                        evidence_status="UNSOLVED_FRONTIER")
        else:
            own=solve(self.context)
        self.capsule = own
        if self.condition == "STRUCT_WRONG":
            if self.wrong_context is None:
                raise ValueError("WRONG requires an independent parseable public graph")
            self.capsule = solve(self.wrong_context)
        self.compute_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        self.codes, self.info = self.codec.encode(own, self.condition, self.drop, self.context)
        if self.condition == "STRUCT_WRONG":
            self.alt_codes, self.info = self.codec.encode(self.capsule, self.condition, self.drop, self.wrong_context)
        torch.cuda.synchronize() if state.is_cuda else None
        self.codec_ms = (time.perf_counter() - start) * 1000

    def apply(self, _, args, output):
        state = hidden(output)
        if self.effective_gate is None:
            self.prepare(state)
        old = state[0,self.positions].detach().float()
        new = old
        if self.codes is not None and self.effective_gate and self.condition not in {"STRUCT_ZERO","STRUCT_GATE_ZERO"}:
            delta = self.codec.delta(old,self.codes) * self.alpha
            if self.condition == "SHAM":
                gen = torch.Generator(device="cpu").manual_seed(1701)
                random = torch.randn(old.shape,generator=gen).to(old.device)
                delta = normalize(random,dim=-1) * delta.norm(dim=-1,keepdim=True)
            elif self.condition == "STRUCT_WRONG":
                alt = self.codec.delta(old,self.alt_codes) * self.alpha
                if alt.norm() > 0:
                    delta = alt * (delta.norm() / alt.norm())
                else:
                    delta = alt
            modified = state.clone()
            modified[0,self.positions] = (old+delta).to(state.dtype)
            new = modified[0,self.positions].float()
        else:
            modified = state
        self.expected_next = new.detach()
        diff = new-old
        step = len(self.steps)
        self.steps.append(dict(step_index=step,sequence_tokens=state.shape[1],delta_norm=float(diff.norm()),
                               finite=bool(torch.isfinite(new).all()), before_norm=float(old.norm()),
                               after_norm=float(new.norm()), next_consumed=None))
        if self.trace and step < 2:
            self.tensors[f"step{step}_before"] = old.cpu().to(state.dtype)
            self.tensors[f"step{step}_after"] = new.cpu().to(state.dtype)
        return (modified,*output[1:]) if isinstance(output,tuple) else modified

    def verify(self, _, args, kwargs):
        x = kwargs.get("hidden_states")
        if x is None:
            x=args[0]
        seen=x[0,self.positions].detach()
        self.steps[-1]["next_consumed"] = torch.equal(seen.float(),self.expected_next)
        if self.trace and len(self.steps)<=2:
            self.tensors[f"step{len(self.steps)-1}_consumed"] = seen.cpu()

    def row(self):
        cap=asdict(self.capsule) if self.capsule is not None else None
        applied=self.steps[0]["delta_norm"] if self.steps else 0
        return dict(gate_mode=self.mode,effective_gate=self.effective_gate,gate_score=self.score,
                    capsule=cap,capsule_hash=fingerprint(cap),codec_mode=self.codec.name(self.condition),
                    codec_hash=self.codec.identity(self.condition), alpha=self.alpha,layer_index=self.layer,
                    token_locus=self.positions,injection_locus=self.positions,injection_site=f"{self.runtime.block_path}.{self.layer}",
                    applied_delta_norm=applied,applied_delta=applied>0, next_consumed=all(s["next_consumed"] for s in self.steps),
                    steps=self.steps,downstream=self.downstream,explicit_rule_ms=self.compute_ms,codec_ms=self.codec_ms,**self.info)
