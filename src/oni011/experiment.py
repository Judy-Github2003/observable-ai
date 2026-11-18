# Codex 2026-11-18. Frozen greedy runs and separate examiner-only likelihood replay.
import argparse
from contextlib import contextmanager
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import statistics
import time

import torch
from safetensors.torch import load_file, save_file

from oni01.injection import Injector as LegacyInjector, activation_bank, fit_gate
from oni01.capsule import parse_prompt, solve
from oni01.runtime import Runtime, digest, write_json
from .codec import StructuredCodec, memory_suffix
from .injector import StructuralInjector
from .data import development, validation

BUILD = Path("build/oni-01r1")
DOC = Path("docs/research/oni-01r1")
CORE = ["BASE","SHAM","LEGACY_ANCHOR","VALUE_NO_AUX","STRUCT_FORCED","STRUCT_ZERO","STRUCT_RESTORE","STRUCT_WRONG"]
GATES = ["STRUCT_GATED","STRUCT_GATE_ZERO","STRUCT_GATE_RESTORE"]
DROPS = ["DROP_" + n for n in ("VALUE","SOURCE","DEPTH","PATH","STATUS")]
EXTRA = DROPS + ["STRUCTURE_FIRST","COUNTERFACTUAL_PATH"]
BUDGET = 32
DONOR = development()[0]["prompt"]
SECOND_DONOR = development()[1]["prompt"]


def tick():
    torch.cuda.synchronize()
    return time.perf_counter()


def now():
    return datetime.now(timezone.utc).isoformat()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def lines(path):
    return [json.loads(s) for s in Path(path).read_text(encoding="utf-8").splitlines() if s]


def put(fp, row):
    fp.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
    fp.flush()


def fingerprint(tensor):
    return hashlib.sha256(tensor.contiguous().view(torch.uint8).cpu().numpy().tobytes()).hexdigest()


def text_sha(s):
    return hashlib.sha256(s.encode()).hexdigest()


def normalize(s):
    return s.strip().casefold().rstrip(".!?。！？").strip()


class Trial:
    def __init__(self, runtime, config, gate=None):
        self.rt = runtime
        self.cfg = config
        self.codec = StructuredCodec(runtime, site=config.get("site","memory"))
        self.gate = gate
        self.baseline = {}

    @contextmanager
    def attach(self, raw, rendered, offsets, arm, trace, gate_decision):
        layer = self.cfg["layer"]
        alpha = self.cfg["alpha"]
        if arm == "PLAIN_BASE":
            yield None, 0.0
            return
        if arm == "LEGACY_ANCHOR":
            # Only this isolated historical comparator may use a value-bearing auxiliary forward.
            context = parse_prompt(raw)
            cap = solve(context) if context else None
            value = (cap.value or cap.status) if cap else None
            start = tick()
            banks, _ = activation_bank(self.rt, [value] if value else [], [27])
            cost = (tick()-start)*1000
            with LegacyInjector(self.rt,raw,rendered,offsets,banks[27],27,0.35,None,"FORCED") as hook:
                yield hook,cost
            return
        drop = arm[5:].lower() if arm in DROPS else None
        condition = "STRUCT_FORCED" if drop else arm
        with StructuralInjector(self.rt, raw, rendered, offsets, condition=condition, layer=layer,
                                alpha=alpha, codec=self.codec, gate=self.gate, trace=trace, drop=drop,
                                wrong_prompt=DONOR if " amber" not in raw else SECOND_DONOR,
                                frozen_gate=gate_decision) as hook:
            yield hook,0.

    @torch.inference_mode()
    def predict(self, case, arm, trace_dir=None, fixed_gate=None):
        raw = case["prompt"]
        torch.cuda.reset_peak_memory_stats()
        start = tick()
        rendered, encoded, offsets = self.rt.encode(raw if arm == "PLAIN_BASE" else raw + memory_suffix())
        encoded_at = tick()
        with self.attach(raw,rendered,offsets,arm,trace_dir is not None,fixed_gate) as (hook,aux):
            gen_at = tick()
            generated = self.rt.model.generate(**encoded, max_new_tokens=BUDGET, use_cache=False, do_sample=False,
                                               return_dict_in_generate=True, output_logits=True)
            end_gen = tick()
        ids = generated.sequences[0,encoded.input_ids.shape[1]:].tolist()
        first = generated.logits[0][0].detach()
        txt = self.rt.tokenizer.decode(ids,skip_special_tokens=True)
        if arm=="PLAIN_BASE":
            row=dict(effective_gate=0,gate_mode="disabled",applied_delta=False,applied_delta_norm=0.0,codec_mode="none")
            tensors,feature={},None
        elif arm=="LEGACY_ANCHOR":
            row = {k:v for k,v in hook.first.items()}
            row.update(gate_mode="forced",effective_gate=1,applied_delta=bool(row["delta_norm"]),codec_mode=arm,
                       codec_hash="oni01-historical",injection_locus="prompt_last_token",layer_index=27,alpha=0.35)
            tensors, feature = hook.samples,None
        else:
            row, tensors, feature = hook.row(),hook.tensors,hook.feature
        uid=case["id"]
        if arm=="BASE":
            self.baseline = {uid:hook.observed.copy()}
        if hook is not None and hasattr(hook,"observed") and uid in self.baseline:
            for i,vector in hook.observed.items():
                reference=self.baseline[uid][i]
                row["downstream"][i]["delta_from_base"]=float((vector-reference).norm())
                row["downstream"][i]["cosine_to_base"]=float(torch.nn.functional.cosine_similarity(vector,reference,dim=0))
        if trace_dir is not None and tensors:
            trace_dir.mkdir(parents=True,exist_ok=True)
            save_file({k:t.cpu().contiguous().clone() for k,t in tensors.items()},trace_dir/f"{uid}_{arm}.safetensors")
        row.update(question_id=uid,condition=arm,output=txt,generated_ids=ids,first_step_logits_hash=fingerprint(first),
                   first_argmax=int(first.argmax()),first_token_matches_argmax=bool(ids and ids[0]==int(first.argmax())),
                   prompt_hash=text_sha(rendered),raw_hash=text_sha(raw),input_bytes=len(raw.encode()),budget=BUDGET,
                   use_cache=False,rng_seed=17001,deterministic_algorithms=torch.are_deterministic_algorithms_enabled(),
                   input_ms=(encoded_at-start)*1000,aux_forward_ms=aux,generation_ms=(end_gen-gen_at)*1000,
                   gpu_peak_bytes=torch.cuda.max_memory_allocated(),request_ms=(tick()-start)*1000)
        row.setdefault("injection_locus",row.get("token_locus"))
        row.setdefault("applied_delta",bool(row.get("applied_delta_norm",0)))
        return row,feature

    @torch.inference_mode()
    def likelihood(self,case,target,arm,fixed_gate):
        start=tick()
        raw=case["prompt"]
        rendered, encoded, offsets=self.rt.encode(raw if arm == "PLAIN_BASE" else raw+memory_suffix())
        prefix=encoded.input_ids[0].tolist()
        combined=self.rt.tokenizer.encode(rendered+target,add_special_tokens=False)
        if combined[:len(prefix)] != prefix or len(combined)==len(prefix):
            raise ValueError("Tokenizer boundary changed for examiner suffix: "+case["id"])
        ids=combined[len(prefix):]
        # Single causal pass at fixed prefix intervention sites. Only the evaluator supplies teacher tokens.
        full=torch.tensor([combined[:-1]],device=encoded.input_ids.device)
        with self.attach(raw,rendered,offsets,arm,False,fixed_gate) as (_hook,_aux):
            logits=self.rt.model(input_ids=full,attention_mask=torch.ones_like(full),use_cache=False,
                                 logits_to_keep=len(ids)).logits[0].float()
        lp=logits.log_softmax(-1)[torch.arange(len(ids),device=logits.device),ids].tolist()
        return dict(question_id=case["id"],condition=arm,target_token_ids=ids,token_logprobs=lp,
                    log_likelihood=sum(lp),mean_nll=-statistics.mean(lp),scoring_ms=(tick()-start)*1000)


def develop(rt):
    out=BUILD/"development"
    out.mkdir(parents=True,exist_ok=True)
    dev,val=development(),validation()
    grid=[(0,0.65),(0,1.0),(2,0.65),(2,1.0),(8,0.65),(8,1.0)]
    summaries=[]
    with (out/"trials.jsonl").open("x",encoding="utf-8") as log:
        for layer,alpha in grid:
            cfg=dict(layer=layer,alpha=alpha,site="memory")
            trial=Trial(rt,cfg)
            correct=0
            for case in dev:
                row,_=trial.predict(case,"STRUCT_FORCED")
                row.update(expected=case["expected"],correct=normalize(row["output"])==normalize(case["expected"]))
                put(log, row);correct+=row["correct"]
            summaries.append((correct,layer,alpha))
            print("DEV",layer,alpha,correct,len(dev),flush=True)
    n,layer,alpha=max(summaries,key=lambda v:(v[0],-v[2],-v[1]))
    cfg=dict(layer=layer,alpha=alpha,site="memory",budget=BUDGET)
    trial=Trial(rt,cfg)
    features,positive=[],[]
    with (out/"validation.jsonl").open("x",encoding="utf-8") as log:
        # Validate forced transmission first. Gate calibration occurs after these paired runs.
        for case in val:
            for arm in ["BASE","SHAM","VALUE_NO_AUX","STRUCT_FORCED"]:
                row,_=trial.predict(case,arm)
                put(log,row|dict(expected=case["expected"],positive=case["positive"],correct=normalize(row["output"])==normalize(case["expected"])))
    for case in dev:
        _,feature=trial.predict(case,"BASE")
        features.append(feature.cpu().float().flatten());positive.append(case["positive"])
    gate,scores=fit_gate(features,positive)
    save_file({"direction":gate["direction"].cpu().contiguous()},out/"gate.safetensors")
    cfg["gate_threshold"]=gate["threshold"]
    trial.gate=gate
    with (out/"gate_validation.jsonl").open("x",encoding="utf-8") as log:
        for case in val:
            row,_=trial.predict(case,"STRUCT_GATED")
            put(log,row|dict(positive=case["positive"]))
    write_json(out/"config.json",cfg)
    write_json(out/"grid.json",dict(grid=summaries,selected=cfg,dev_n=len(dev),val_n=len(val),
                                   gate_dev=[dict(id=c["id"],positive=c["positive"],score=s) for c,s in zip(dev,scores)]))


def official(rt,args):
    freeze=read(DOC/"ONI011_FREEZE.json")
    for name,expected in freeze["sha256"].items():
        if digest(Path(name))!=expected: raise RuntimeError("Freeze mismatch: "+name)
    receipt=read(args.public.parent.parent/"commitment.json")
    if digest(args.public)!=receipt["public_sha256"] or receipt["freeze_sha256"]!=digest(DOC/"ONI011_FREEZE.json"):
        raise RuntimeError("Exam commitment mismatch")
    public=lines(args.public)
    if len(public)!=56 or any(set(c)!={"id","prompt","kind","world"} for c in public):
        raise ValueError("Expected only the 56 committed public cases")
    cfg=read(BUILD/"development/config.json")
    gate=load_file(BUILD/"development/gate.safetensors")|{"threshold":cfg["gate_threshold"]}
    trial=Trial(rt,cfg,gate)
    out=BUILD/"official"
    out.mkdir(exist_ok=True)
    latent=args.stage=="ll"
    truth={c["id"]:c["expected"] for c in read(args.truth)["items"]} if latent else {}
    target=out/("likelihoods.jsonl" if latent else "ONI011_PREDICTIONS.jsonl")
    gate_cache={}
    if latent:
        if digest(out/"ONI011_PREDICTIONS.jsonl")!=read(out/"completion.json")["sha256"]:raise RuntimeError("Official inference incomplete")
        gate_cache={r["question_id"]:r["effective_gate"] for r in lines(out/"ONI011_PREDICTIONS.jsonl") if r["condition"]=="STRUCT_GATED"}
    events=out/("LL_EVENTS.jsonl" if latent else "ONI011_EVENTS.jsonl")
    manifest=read(DOC/"ONI011_EXPERIMENT_PROTOCOL.json")
    with target.open("x",encoding="utf-8") as log,events.open("x",encoding="utf-8") as ev, events.with_suffix(".steps.log").open("x",encoding="utf-8") as steps:
        counts={}
        for i,case in enumerate(public):
            uid=case["id"]
            world=case["world"]
            extra=case["kind"]=="HELDOUT" and counts.get(world,0)<4
            if extra: counts[world]=counts.get(world,0)+1
            arms=CORE+GATES+["PLAIN_BASE"]+(EXTRA if extra else [])
            for arm in arms:
                ctx=dict(question_id=uid,condition=arm,run_id="ONI-01R1/"+args.stage,version=freeze["git_commit"])
                def event(phase,**fields):
                    stamp=now();put(ev,ctx|dict(timestamp=stamp,phase=phase,event_type="step",function=args.stage,**fields))
                    steps.write(f"{stamp} {ctx['run_id']} {ctx['version']} {uid} {arm} {phase}\n");steps.flush()
                event("start")
                try:
                    decision=gate_cache.get(uid) if arm in GATES[1:] or latent and arm in GATES else None
                    if latent:
                        row=trial.likelihood(case,truth[uid],arm,decision)
                    else:
                        row,_=trial.predict(case,arm,out/"ONI011_TRACE" if extra or i<4 else None,decision)
                        if arm=="STRUCT_GATED":gate_cache[uid]=row["effective_gate"]
                    put(log,row|dict(world=case["world"],kind=case["kind"],freeze_sha256=digest(DOC/"ONI011_FREEZE.json"),
                                      model_sha256=freeze["weight_sha256"],revision=freeze["model_revision"]))
                except Exception as exc:
                    event("failed",error=type(exc).__name__);raise
                event("success")
            print(args.stage,i+1,"/56",flush=True)
    write_json(out/("likelihood_completion.json" if latent else "completion.json"),
               dict(sha256=digest(target),rows=len(lines(target)),public_sha256=digest(args.public)))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("stage",choices=["develop","official","ll"])
    parser.add_argument("--public",type=Path)
    parser.add_argument("--truth",type=Path)
    args=parser.parse_args()
    if args.stage!="ll" and args.truth:raise ValueError("Labels are exclusive to the post-inference evaluator")
    rt=Runtime(inspect_file=args.stage=="develop")
    if args.stage=="develop":
        write_json(BUILD/"model.json",rt.describe());develop(rt)
    else:official(rt,args)


if __name__=="__main__":
    main()
