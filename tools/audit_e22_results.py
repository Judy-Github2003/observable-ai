#!/usr/bin/env python3
"""Validate E2.2 JSONL forensic structure, not ground-truth correctness.

Do not treat this as an independent oracle replay, cryptographic timestamping,
research success, or proof that the student could not access unrecorded sources.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import pathlib
import sys
from collections import defaultdict

ARMS = {"STATIC", "RANDOM_FIXED_4", "E2_LEGACY", "E21_STRICT", "E22_CONJECTURE"}


def require(ok: bool, explanation: str) -> None:
    if not ok:
        raise AssertionError(explanation)


def read_jsonl(p: pathlib.Path):
    require(p.exists(), f"Missing {p}")
    lines = p.read_text(encoding="utf-8").splitlines()
    require(lines, f"Empty {p}")
    for n, line in enumerate(lines, 1):
        try:
            yield line, json.loads(line)
        except ValueError as exc:
            raise AssertionError(f"Invalid JSONL {p}:{n}: {exc}") from exc


def audit_task(path: pathlib.Path) -> dict:
    ev = list(read_jsonl(path))
    expected = set()
    final = {}
    matrices = 0
    reveals = 0
    steps = defaultdict(int)
    used = defaultdict(set)
    pending = {}
    for index, (raw, e) in enumerate(ev):
        phase = e.get("phase")
        arm = e.get("arm")
        if phase == "FULL_MATRIX_FROZEN":
            require(',"payload":' in raw and raw.endswith("}"), f"Missing exact raw payload {index}")
            serialized = raw.split(',"payload":', 1)[1][:-1]
            require(hashlib.sha256(serialized.encode("utf-8")).hexdigest() == e["sha256"],
                    f"Raw matrix SHA mismatch at {index}")
            p = e["payload"]
            arm = p["arm"]
            require(arm in ARMS and arm != "STATIC", f"Invalid matrix arm {arm}")
            require((arm, p["step"]) not in expected, f"Duplicate snapshot {arm}/{p['step']}")
            expected.add((arm, p["step"]))
            require(p["step"] == steps[arm], f"Nonsequential steps {arm}: {p['step']}")
            steps[arm] += 1
            rows = p["allQueryRows"]
            require(len(rows) == 12, f"Need all 12 QUERY rows, got {len(rows)}")
            require(set(r["id"] for r in rows) == {f"q-{i}" for i in range(12)},
                    f"Incomplete/duplicate row IDs for {arm}")
            selected = p["selectedQuery"]
            chosen = [r["id"] for r in rows if r["selected"]]
            require(chosen == ([selected] if selected else []),
                    f"Selected row does not match selectedQuery for {arm}")
            for row in rows:
                votes = row["votes"]
                require(row["true"] == sum(v["prediction"] is True for v in votes), "true votes mismatch")
                require(row["false"] == sum(v["prediction"] is False for v in votes), "false votes mismatch")
                require(row["unknown"] == sum(v["prediction"] is None for v in votes), "unknown votes mismatch")
                require(row["score"] == min(row["true"], row["false"]), "score mismatch")
                if row["id"] in used[arm]:
                    require(row["reason"] == "ALREADY_QUERIED", "Known query not marked ALREADY_QUERIED")
            for model in p["models"]:
                canonical = model["programCanonical"]
                require(hashlib.sha256(canonical.encode("utf-8")).hexdigest() == model["programHash"],
                        f"Model canonical hash mismatch: {arm} {model['id']}")
                if model["origin"] == "LEAF_COUNTERFACTUAL":
                    require(model["publicCertified"] is True, "Uncertified E22 hypothesis in vote list")
                    require(model["supportingPublicIds"] and model["oppositeUnlabelledQueryIds"],
                            "E22 conjecture missing TRAIN support / unlabeled contrast")
            pending[arm] = (p["step"], e["sha256"], selected, "need_decision")
            matrices += 1
        elif phase == "QUERY_DECISION_FROZEN":
            require(arm in pending, f"Decision lacks preceding full matrix: {arm}")
            step, digest, chosen, state = pending[arm]
            require(state == "need_decision", "Decision repeated or out of order")
            require(e["step"] == step and e["matrixSha256"] == digest and e["queryId"] == chosen,
                    f"Decision not bound to full matrix at {arm} step {step}")
            pending[arm] = (step, digest, chosen, "need_reveal" if chosen else "abstain")
        elif phase == "QUERY_LABEL_REVEALED":
            require(arm in pending, f"Reveal without decision {arm}")
            step, digest, chosen, state = pending[arm]
            require(state == "need_reveal", f"Reveal without requested query {arm}")
            require(e["queryId"] == chosen and e["step"] == step and e["matrixSha256"] == digest,
                    "Reveal not bound to prior frozen matrix")
            require(chosen not in used[arm], "Repeated QUERY id in same arm")
            used[arm].add(chosen)
            pending[arm] = (step, digest, chosen, "revealed")
            reveals += 1
        elif phase == "FINAL_STUDENT_FROZEN":
            require(arm in ARMS and arm not in final, f"Unexpected/duplicate final {arm}")
            if arm != "STATIC" and arm in pending:
                require(pending[arm][3] in {"revealed", "abstain"}, f"Arm ended with unfinished request {arm}")
            require(set(e["queryIds"]) == used[arm], f"Final query IDs mismatch {arm}")
            require(e["publicLabels"] == 32 + len(used[arm]), f"Public labels count mismatch {arm}")
            final[arm] = e
    require(set(final) == ARMS, f"Expected five arms; got {set(final)}")
    require(len({x["task"] for x in final.values()}) == 1, "Task token mismatch among final arms")
    require(len(used["RANDOM_FIXED_4"]) == 4, "Random control must use fixed 4 queries")
    for arm in ARMS:
        require(len(used[arm]) <= 4, "Oracle budget exceeded")
    return {"task": next(iter(final.values()))["task"], "matrix_snapshots": matrices,
            "reveals": reveals, "query_counts": {a: len(used[a]) for a in sorted(ARMS)}}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("results_dir", type=pathlib.Path)
    ap.add_argument("--allow-partial", action="store_true", help="diagnose incomplete runs; does not PASS them")
    ns = ap.parse_args(argv)
    d = ns.results_dir
    files = sorted(d.glob("STUDENT-EVENTS-*.jsonl"))
    require(files, "No student timeline JSONL present")
    tasks = [audit_task(f) for f in files]
    examiner = [r for _,r in read_jsonl(d / "EXAMINER-OPPORTUNITY-AFTER-FREEZE.jsonl")]
    grades = [r for _,r in read_jsonl(d / "EXAMINER-SCORES-AFTER-FREEZE.jsonl")]
    tokens = {t["task"] for t in tasks}
    require(len(tokens) == len(tasks), "Duplicate task tokens")
    require({e["task"] for e in examiner} == tokens, "Examiner opportunity token mismatch")
    require(len(examiner) == len(tasks), "Examiner opportunity row count mismatch")
    require(len(grades) == len(tasks)*5, "Five grade rows per task required")
    grouped = defaultdict(list)
    for g in grades: grouped[g["task"]].append(g)
    for token in tokens:
        require({g["arm"] for g in grouped[token]} == ARMS, "Missing grade arm")
        for g in grouped[token]:
            if g["challengeStatus"] != "PASS_ON_CHALLENGE":
                require(g["heldoutCorrect"] is None, "Heldout must be null when Challenge failed")
            else:
                require(0 <= g["heldoutCorrect"] <= g["heldoutTotal"], "Invalid heldout")
    for e in examiner:
        require(e["poolSize"] == 12 and e["initialCounterexampleCount"] == len(e["initialCounterexampleIds"]),
                "Opportunity mismatch")
        require(len(e["arms"]) == 5, "Missing opportunity arm")
        require({a["arm"] for a in e["arms"]} == ARMS, "Opportunity arm mismatch")
        for a in e["arms"]:
            require(0 <= a["initialCounterexampleHits"] <= min(a["queries"],e["initialCounterexampleCount"]),
                    "Invalid hit count")
    print("E22 RAW STRUCTURAL AUDIT PASS")
    print(json.dumps({"task_seeds":len(tasks),"student_final_freezes":len(tasks)*5,
                      "total_matrix_snapshots":sum(t["matrix_snapshots"] for t in tasks),
                      "revealed_query_labels":sum(t["reveals"] for t in tasks),
                      "examiner_opportunity_rows":len(examiner),"examiner_grade_rows":len(grades),
                      "by_task":tasks},ensure_ascii=False,indent=2))
    if ns.allow_partial:
        print("Note: --allow-partial does not certify incomplete runs; above PASS requires complete records.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, ValueError) as exc:
        print("E22 RAW STRUCTURAL AUDIT FAIL:", exc, file=sys.stderr)
        raise SystemExit(1)
