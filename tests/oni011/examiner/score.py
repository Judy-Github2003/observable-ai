# Codex 独立考官，2026-11-18。冻结后仅在全部预测提交后揭盲，保留原始失败和配对比较。
"""Score the commit-bound post-freeze experiment. No student imports; standard library only."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import random
import re
from statistics import mean, median
import tempfile

KINDS = ("HELDOUT", "NEGATIVE", "AMBIGUOUS", "GENERAL")
CORE = ("BASE","SHAM","LEGACY_ANCHOR","VALUE_NO_AUX","STRUCT_FORCED","STRUCT_ZERO","STRUCT_RESTORE","STRUCT_WRONG")
GATES = ("STRUCT_GATED","STRUCT_GATE_ZERO","STRUCT_GATE_RESTORE")
EXTRA = ("DROP_VALUE","DROP_SOURCE","DROP_DEPTH","DROP_PATH","DROP_STATUS","STRUCTURE_FIRST","COUNTERFACTUAL_PATH")


def audit(pairs):
    """Test the whole matched triplet; only a parsed case with an open gate should apply a delta."""
    errors = []
    counts = {}
    for on, zero, restore, mode in [("STRUCT_FORCED","STRUCT_ZERO","STRUCT_RESTORE","forced"),
                                    ("STRUCT_GATED","STRUCT_GATE_ZERO","STRUCT_GATE_RESTORE","activation")]:
        active = 0
        for qid, conditions in pairs.items():
            base = conditions["BASE"]
            a,b,c = [conditions[x] for x in (on,zero,restore)]
            keys = ("model_sha256","revision","freeze_sha256","prompt_hash","capsule_hash","codec_hash","layer_index",
                    "alpha","injection_site","injection_locus","gate_mode","effective_gate","rng_seed","budget","use_cache")
            for key in keys:
                if a.get(key) is None or not (a[key]==b.get(key)==c.get(key)):
                    errors.append([qid,on,"triplet: "+key])
            should_apply = bool(a.get("effective_gate")) and a.get("capsule") is not None
            active += int(should_apply)
            if a.get("gate_mode")!=mode or (mode=="forced" and a.get("effective_gate")!=1):
                errors.append([qid,on,"gate mode"])
            for row, expected in ((a, should_apply), (b, False), (c, should_apply)):
                steps = row.get("steps",[])
                if row.get("applied_delta")!=expected or not steps or not all(s.get("next_consumed") and
                        s.get("finite") and (s.get("delta_norm",0)>0)==expected for s in steps):
                    errors.append([qid,row["condition"],"delta/consumption"])
            for left,right in ((a,c),(base,b)):
                for key in ("prompt_hash","first_step_logits_hash","output","generated_ids"):
                    if left.get(key) is None or left[key]!=right.get(key):
                        errors.append([qid,on,"match: "+key])
        counts[on] = {"triplets":len(pairs),"active_on":active}
    return {"counts":counts,"errors":errors,"pass":not errors}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def load_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]


def write(path, obj):
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def norm(s):
    # Exact full string, case-folded, outer whitespace and one optional punctuation character removed.
    return re.sub(r"[.!?。！？]$", "", s.strip().casefold()).rstrip()


def wilson(k, n):
    if not n:
        return None
    z = 1.959963984540054
    p = k / n
    scale = 1 + z*z/n
    half = z * math.sqrt(p*(1-p)/n + z*z/(4*n*n))
    return [max(0.,(p+z*z/(2*n)-half)/scale),min(1.,(p+z*z/(2*n)+half)/scale)]


def paired(a, b):
    # a→b, per-case paired correctness. Bootstrap samples case IDs with replacement.
    assert len(a) == len(b) and all(x["id"]==y["id"] for x,y in zip(a,b))
    counts = Counter((x["correct"],y["correct"]) for x,y in zip(a,b))
    diff = [int(y["correct"])-int(x["correct"]) for x,y in zip(a,b)]
    n = len(diff)
    if not n:
        return {"n":0}
    if len(set(diff)) == 1:
        interval = [diff[0],diff[0]]
    else:
        rng = random.Random(841277)
        bs = sorted(sum(rng.choices(diff,k=n))/n for _ in range(4000))
        interval = [bs[99],bs[3899]]
    return {"n":n, "both_correct":counts[True,True], "beneficial":counts[False,True],
            "harmful":counts[True,False], "both_miss":counts[False,False], "delta_accuracy":mean(diff),
            "paired_bootstrap95":interval, "raw_output_changed":sum(x["output"]!=y["output"] for x,y in zip(a,b)),
            "beneficial_ids":[x["id"] for x,y in zip(a,b) if not x["correct"] and y["correct"]],
            "harmful_ids":[x["id"] for x,y in zip(a,b) if x["correct"] and not y["correct"]]}


def short(rows):
    n = len(rows)
    correct = sum(row["correct"] for row in rows)
    result = {"n":n, "correct":correct, "accuracy":correct/n if n else None, "wilson95":wilson(correct,n)}
    for key in ("log_likelihood","mean_nll","gate_score","delta_norm","elapsed_ms","peak_bytes","generation_ms",
                "explicit_rule_ms","input_ms","codec_ms","aux_forward_ms","scoring_ms"):
        xs = [r[key] for r in rows if isinstance(r.get(key),(int,float)) and math.isfinite(r[key])]
        if xs:
            xs.sort()
            result[key] = {"count":len(xs),"mean":mean(xs),"p50":median(xs),"p95":xs[math.ceil(len(xs)*.95)-1],
                           "min":xs[0],"max":xs[-1]}
    return result


def gate_stats(rows):
    count = Counter()
    for r in rows:
        value = r.get("effective_gate")
        if value is None:
            count["missing"] += 1
            continue
        used, applicable = float(value)>0, r["kind"] == "HELDOUT"
        count[("tp" if applicable else "fp") if used else ("fn" if applicable else "tn")] += 1
    def rate(a,b):
        return count[a]/(count[a]+count[b]) if count[a]+count[b] else None
    return {"tp":count["tp"],"fp":count["fp"],"tn":count["tn"],"fn":count["fn"],"missing":count["missing"],
            "precision":rate("tp","fp"),"recall":rate("tp","fn"),"fpr":rate("fp","tn")}


def result_key(row):
    return row.get("question_id",row.get("id")),row["condition"]


def matrix(rows, keys, desc):
    keyed = {result_key(row):row for row in rows}
    if len(keyed)!=len(rows) or set(keyed)!=keys:
        raise ValueError(f"Incomplete/duplicate or unexpected {desc} matrix. Expected {len(keys)}, got {len(rows)}.")
    return keyed


def score(args):
    if args.out.exists():
        raise FileExistsError(args.out)
    receipt = json.loads((args.exam/"commitment.json").read_bytes())
    pub = args.exam/"public/questions.jsonl"
    if sha(Path(__file__).read_bytes()) != receipt["scripts"]["score.py"] or sha(pub.read_bytes()) != receipt["public_sha256"]:
        raise ValueError("Scorer or public test changed after commitment")
    if sha(args.predictions.read_bytes()) != args.predictions_sha:
        raise ValueError("Predictions differ from independently provided completion checksum")
    questions = load_rows(pub)
    arms = (*CORE,*GATES,"PLAIN_BASE")
    sub = []
    seen = Counter()
    for q in questions:
        if q["kind"]=="HELDOUT" and seen[q["world"]]<4:
            sub.append(q["id"])
            seen[q["world"]]+=1
    keys = {(q["id"],c) for q in questions for c in arms} | {(qid,c) for qid in sub for c in EXTRA}
    pred = matrix(load_rows(args.predictions),keys,"predictions")
    ll = matrix(load_rows(args.ll),keys,"LL") if args.ll else {}

    # Open private material only after confirming the complete prediction matrix and its fixed hash.
    truth_path = args.exam/"private/truth.json"
    truth_data = truth_path.read_bytes()
    truth = json.loads(truth_data)
    if sha(truth_data)!=receipt["private_sha256"] or sha(truth["seed"].encode())!=receipt["seed_sha256"]:
        raise ValueError("Commitment integrity error")
    oracle = {x["id"]:x for x in truth["items"]}
    if set(oracle)!={q["id"] for q in questions} or len(oracle)!=56:
        raise ValueError("Public/private key mismatch")
    rows = []
    for q in questions:
        t = oracle[q["id"]]
        if t["world"] != q["world"] or t["kind"] != q["kind"]:
            raise ValueError("Kind/world mismatch")
        for c in (*arms,*(EXTRA if q["id"] in sub else ())):
            k = (q["id"],c)
            r = pred[k]
            output = r.get("output",r.get("generated_text"))
            if not isinstance(output,str) and not r.get("error"):
                raise ValueError(f"Missing raw output {k}")
            output = output or ""
            correct = not bool(r.get("error")) and norm(output)==norm(t["expected"])
            result = {**t,"condition":c,"output":output,"correct":correct,"error":r.get("error"),
                      "first_step_logits_hash":r.get("first_step_logits_hash"), "effective_gate":r.get("effective_gate"),
                      "applied_delta":r.get("applied_delta"),"delta_norm":r.get("applied_delta_norm",r.get("delta_norm")),
                      "gate_score":r.get("gate_score"),"elapsed_ms":r.get("request_ms"),
                      "peak_bytes":r.get("gpu_peak_bytes")}
            result.update({name:r.get(name) for name in ("generation_ms","explicit_rule_ms","input_ms","codec_ms","aux_forward_ms")})
            if k in ll:
                rec = ll[k]
                if rec.get("error"):
                    result["ll_error"] = rec["error"]
                else:
                    ids, logps = rec["target_token_ids"],rec["token_logprobs"]
                    total = float(rec["log_likelihood"])
                    if not ids or len(ids)!=len(logps) or not all(math.isfinite(x) and x<=1.e-5 for x in logps):
                        raise ValueError(f"Invalid full-answer likelihood tokens {k}")
                    if not math.isclose(sum(logps),total,rel_tol=1.e-5,abs_tol=1.e-5):
                        raise ValueError(f"Inconsistent summed LL {k}")
                    if not math.isclose(-total/len(ids),rec["mean_nll"],rel_tol=1.e-5,abs_tol=1.e-5):
                        raise ValueError(f"Inconsistent length-normalized NLL {k}")
                    result.update(token_count=len(ids),log_likelihood=total,mean_nll=rec["mean_nll"],scoring_ms=rec.get("scoring_ms"))
            rows.append(result)

    def select(c, kind=None, world=None, multi=False):
        return [r for r in rows if r["condition"]==c and (kind is None or kind==r["kind"])
                and (world is None or world==r["world"]) and (not multi or r.get("token_count",0)>1)]
    worlds = sorted({q["world"] for q in questions})
    by_kind = {k:{c:short(select(c,k)) for c in arms} for k in KINDS}
    by_world = {w:{c:short(select(c,"HELDOUT",w)) for c in arms} for w in worlds}
    comparisons = {}
    for a,b in [("BASE",c) for c in arms if c!="BASE"] + [("SHAM","STRUCT_FORCED"),("STRUCT_WRONG","STRUCT_FORCED")]:
        comparisons[f"{a}_to_{b}"] = {k:paired(select(a,k),select(b,k)) for k in KINDS}
    bf = comparisons["BASE_to_STRUCT_FORCED"]["HELDOUT"]
    sf = comparisons["SHAM_to_STRUCT_FORCED"]["HELDOUT"]
    per_world = {w:paired(select("BASE","HELDOUT",w),select("STRUCT_FORCED","HELDOUT",w)) for w in worlds}
    primary = bf["beneficial"]-bf["harmful"] >= 4 and sf["beneficial"]-sf["harmful"]>=4
    primary = primary and bf["beneficial"]>=5 and bf["harmful"]<=1 and all(x["beneficial"]>=1 for x in per_world.values())
    gate = gate_stats(select("STRUCT_GATED")) if "STRUCT_GATED" in arms else None
    exact = {}
    for a,b in [("BASE","STRUCT_ZERO"),("STRUCT_FORCED","STRUCT_RESTORE"),
                ("BASE","STRUCT_GATE_ZERO"),("STRUCT_GATED","STRUCT_GATE_RESTORE")]:
        if a in arms and b in arms:
            pairs = list(zip(select(a),select(b)))
            exact[f"{a}_vs_{b}"] = {"n":len(pairs),"identical_output":sum(x["output"]==y["output"] for x,y in pairs),
                "identical_logits_hash":sum(bool(x["first_step_logits_hash"]) and x["first_step_logits_hash"]==y["first_step_logits_hash"] for x,y in pairs)}
    summary = {"at_utc":datetime.now(timezone.utc).isoformat(),"prediction_sha256":args.predictions_sha,
               "loglikelihood_sha256":sha(args.ll.read_bytes()) if args.ll else None,
               "normalizer":"strip whitespace, casefold, strip one trailing .!?。！？, rstrip, whole string equality",
               "records":len(rows),"failures":sum(not r["correct"] for r in rows),"errors":sum(bool(r["error"]) for r in rows),
               "by_kind":by_kind,"heldout_by_world":by_world,"paired":comparisons,"forced_vs_base_by_world":per_world,
               "matched_exact_summaries":exact,"gate_metrics":gate,"p1_prespecified":bool(primary),
               "metadata_and_step_audit":audit({q["id"]:{c:pred[q["id"],c] for c in arms} for q in questions}),
               "ablation_subset":{"ids":sub,"by_condition":{c:short([r for r in select(c) if r["id"] in sub])
                        for c in ("BASE","STRUCT_FORCED",*EXTRA)},
                    "forced_to_condition":{c:paired([r for r in select("STRUCT_FORCED") if r["id"] in sub],select(c)) for c in EXTRA}},
               "p3_prespecified":(gate["missing"]==0 and gate["recall"] is not None and gate["fpr"] is not None and
                                  gate["recall"]>=.75 and gate["fpr"]<=.1) if gate else None,
               "multi_token_heldout":{c:short(select(c,"HELDOUT",multi=True)) for c in arms} if ll else None,
               "ll_errors":sum(bool(r.get("ll_error")) for r in rows),
               "ci":"Wilson 95% per accuracy; paired case bootstrap percentile 95%, fixed seed, 4000 resamples."}
    args.out.parent.mkdir(exist_ok=True,parents=True)
    with tempfile.TemporaryDirectory(dir=args.out.parent) as td:
        temp = Path(td)/"review"
        temp.mkdir()
        write(temp/"score.json",summary)
        for name,subset in (("scored_rows.jsonl",rows),("failures.jsonl",[r for r in rows if not r["correct"]])):
            with (temp/name).open("w",encoding="utf-8",newline="\n") as f:
                for row in subset:
                    f.write(json.dumps(row,ensure_ascii=False,allow_nan=False)+"\n")
        temp.rename(args.out)
    print(json.dumps({"predictions":len(rows),"p1":primary,"gate":gate,"heldout_correct":
                      {c:by_kind["HELDOUT"][c]["correct"] for c in arms},"failures":summary["failures"]}))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--exam",type=Path,required=True)
    p.add_argument("--predictions",type=Path,required=True)
    p.add_argument("--predictions-sha",required=True)
    p.add_argument("--ll",type=Path)
    p.add_argument("--out",type=Path,required=True)
    score(p.parse_args())
