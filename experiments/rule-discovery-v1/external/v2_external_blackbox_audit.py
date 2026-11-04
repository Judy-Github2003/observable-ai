#!/usr/bin/env python3
# Independent black-box V2 audit harness.
# Does not import student's Kotlin implementation or DeepSeek's test generator.
# Run locally from an existing checkout after `./gradlew test installDist`.
import argparse
import hashlib
import itertools
import json
import random
import subprocess
import tempfile
from collections import Counter
from pathlib import Path


def all_assignments(schema):
    fields = list(schema)
    for vals in itertools.product(*(schema[f] for f in fields)):
        yield dict(zip(fields, vals))


def candidates(schema):
    fields = list(schema)
    out = []
    for a in fields:
        for va in schema[a]:
            out.append(((a, va),))
    for a, b in itertools.combinations(fields, 2):
        for va, vb in itertools.product(schema[a], schema[b]):
            out.append(((a, va), (b, vb)))
    return out


def eval_rule(rule, features):
    return all(features.get(k) == v for k, v in rule)


def rule_signatures(schema, observations):
    """Independent oracle: remaining semantics, using complete assignments."""
    domain = list(all_assignments(schema))
    signatures = set()
    for rule in candidates(schema):
        if all(eval_rule(rule, feats) == label for feats, label in observations):
            signatures.add(tuple(eval_rule(rule, x) for x in domain))
    return signatures


def new_world(rng, number, kind, fraction):
    names = ["光谱", "形态", "纹理", "环境", "材质", "状态", "信号", "标记"]
    fields = rng.sample(names, rng.choice([2, 3]))
    schema = {f: [f"{f}_{rng.randrange(100, 999)}_{j}" for j in range(rng.choice([2, 3]))]
              for f in fields}
    domain = list(all_assignments(schema))
    selected = rng.sample(fields, 2)
    c1, c2 = ((f, rng.choice(schema[f])) for f in selected)
    if kind == "in":
        target = tuple([c1] if rng.randrange(2) else [c1, c2])
        def truth(f): return eval_rule(target, f)
    elif kind == "out":
        target = (c1, c2)
        def truth(f): return eval_rule((c1,), f) or eval_rule((c2,), f)
    else:
        raise ValueError(kind)

    rng.shuffle(domain)
    pos = next(f for f in domain if truth(f))
    neg = next(f for f in domain if not truth(f))
    rest = [f for f in domain if f is not pos and f is not neg]
    pool_n = len(rest) if fraction == 1 else round(len(rest) * fraction)
    rng.shuffle(rest)
    pool = rest[:pool_n]

    # IDs intentionally unrelated to features and labels.
    sid = hashlib.sha256(f"audit-{number}-{rng.getrandbits(64)}".encode()).hexdigest()[:14]
    def oid(i): return f"holdout-{sid}-{i:03d}"
    known = [
        {"id": oid(0), "features": pos, "label": True, "evidence_id": f"init-{sid}-p"},
        {"id": oid(1), "features": neg, "label": False, "evidence_id": f"init-{sid}-n"},
    ]
    pool_objs = [{"id": oid(i + 2), "features": row} for i, row in enumerate(pool)]
    pool_map = {o["id"]: o["features"] for o in pool_objs}
    return {
        "id": f"world-{sid}", "kind": kind, "fraction": fraction, "schema": schema,
        "target": target, "domain": list(all_assignments(schema)),
        "known": known, "pool": pool_objs, "pool_map": pool_map, "truth": truth
    }


class Student:
    def __init__(self, repo, state_dir):
        lib = repo / "build/install/logic-learner/lib"
        if not lib.is_dir():
            raise RuntimeError(f"Missing {lib}: run ./gradlew test installDist first")
        self.proc = subprocess.Popen(
            ["java", "-cp", str(lib / "*"), "logic.discovery.DiscoveryCliKt",
             "-", str(state_dir)],
            cwd=repo, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1
        )
        self.index = 0

    def send(self, world, op, args=None):
        if self.proc.poll() is not None:
            raise RuntimeError("Kotlin student exited unexpectedly")
        self.index += 1
        payload = {
            "task_id": f"external-{self.index}", "world_id": world,
            "op": op, "args": args or {}
        }
        self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError("Kotlin student returned no JSON response")
        ans = json.loads(line)
        assert ans.get("task_id") == payload["task_id"], ("task_id mismatch", ans)
        assert ans.get("world_id") == world, ("world_id mismatch", ans)
        assert ans.get("op") == op, ("op mismatch", ans)
        assert ans.get("status") in ("ok", "unknown", "conflict", "error"), ans
        assert ans.get("trace_id"), ("missing trace_id", ans)
        return ans

    def close(self):
        if self.proc.poll() is None:
            self.proc.stdin.close()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        err = self.proc.stderr.read() if self.proc.stderr else ""
        return self.proc.returncode, err[-2000:]


def single_world(student, world, budget):
    wid = world["id"]
    ans = student.send(wid, "discovery.start", {
        "feature_schema": world["schema"], "known_examples": world["known"],
        "query_pool": world["pool"]
    })
    if ans["status"] != "ok":
        return {"kind": world["kind"], "fraction": world["fraction"],
                "start_status": ans["status"], "error": ans.get("reason")}

    observations = [(o["features"], o["label"]) for o in world["known"]]
    seen = set()
    traces = []
    for i in range(budget):
        next_r = student.send(wid, "discovery.next_query")
        if next_r["status"] != "ok":
            break
        oid = next_r.get("result", {}).get("object_id")
        if oid not in world["pool_map"] or oid in seen:
            raise AssertionError(f"invalid or duplicate query: {oid}")
        seen.add(oid)
        feat = world["pool_map"][oid]
        label = world["truth"](feat)
        # Nothing containing the hidden oracle or target rule is sent to student.
        observed = student.send(wid, "discovery.observe", {
            "object_id": oid, "label": label, "evidence_id": f"oracle-{wid}-{i}"
        })
        traces.append({"chosen": oid, "observe_status": observed["status"]})
        if observed["status"] != "ok":
            break
        observations.append((feat, label))
    fin = student.send(wid, "discovery.finalize")
    promoted = fin["status"] == "ok" and fin.get("result", {}).get("status") == "promoted"
    signatures = rule_signatures(world["schema"], observations)
    identifiable = len(signatures) == 1 and any(y for _, y in observations) and any(not y for _, y in observations)

    correct, wrong, unknown = 0, 0, 0
    if promoted:
        for feat in world["domain"]:
            actual = student.send(wid, "discovery.predict", {"features": feat})
            val = actual.get("result", {}).get("label")
            if actual["status"] == "unknown" or val == "unknown":
                unknown += 1
            elif actual["status"] == "ok" and val in ("true", "false"):
                if (val == "true") == world["truth"](feat):
                    correct += 1
                else:
                    wrong += 1
            else:
                raise AssertionError(f"invalid prediction response: {actual}")

    return {
        "world_id": wid, "kind": world["kind"], "fraction": world["fraction"],
        "domain_size": len(world["domain"]), "queries": len(seen),
        "candidate_classes": len(signatures), "oracle_identifiable": identifiable,
        "promoted": promoted, "final_status": fin["status"],
        "final_reason": fin.get("reason"), "correct": correct, "wrong": wrong,
        "unknown": unknown, "wrong_promotion": promoted and (wrong > 0 or unknown > 0),
        "missed_promotion": world["kind"] == "in" and not promoted and identifiable,
        "observed_count": len(observations),
        "trace": traces
    }


def audit_protocol(student, rng):
    w = new_world(rng, 99999, "in", 1.0)
    wid = w["id"]
    r1 = student.send(wid, "discovery.start", {
        "feature_schema": w["schema"], "known_examples": w["known"], "query_pool": w["pool"]})
    # Probe a nonselected object without ever calling next_query.
    unauthorized = student.send(wid, "discovery.observe", {
        "object_id": w["pool"][0]["id"], "label": w["truth"](w["pool"][0]["features"]),
        "evidence_id": "audit-nonselected"
    })
    # Probe whether start silently rewrites previously saved state under same world_id.
    before = student.send(wid, "discovery.status")
    student.send(wid, "discovery.start", {
        "feature_schema": w["schema"], "known_examples": w["known"], "query_pool": w["pool"]})
    after = student.send(wid, "discovery.status")
    return {
        "start_status": r1["status"],
        "unselected_observe_status": unauthorized["status"],
        "unselected_observe_reason": unauthorized.get("reason"),
        "before_reset_evidence": before.get("result", {}).get("evidence_count"),
        "after_reset_evidence": after.get("result", {}).get("evidence_count"),
        "history_reset": (after.get("result", {}).get("evidence_count", -1)
                          < before.get("result", {}).get("evidence_count", -1))
    }


def main():
    parser = argparse.ArgumentParser(description="Independent V2 black-box audit")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--seed", type=int, default=874267)
    parser.add_argument("--worlds-per-cell", type=int, default=6)
    parser.add_argument("--budget", type=int, default=14)
    parser.add_argument("--output", type=Path, default=Path("v2-external-audit.json"))
    args = parser.parse_args()
    repo = args.repo.resolve()
    rng = random.Random(args.seed)

    rev = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True)
    commit = rev.stdout.strip() if rev.returncode == 0 else "unknown"

    with tempfile.TemporaryDirectory(prefix="v2-audit-") as temp_dir:
        student = Student(repo, Path(temp_dir))
        results = []
        for kind in ("in", "out"):
            for fraction in (1.0, 0.6, 0.3):
                for _ in range(args.worlds_per_cell):
                    w = new_world(rng, len(results), kind, fraction)
                    results.append(single_world(student, w, args.budget))
        protocol = audit_protocol(student, rng)
        exit_code, stderr_tail = student.close()

    by_cell = []
    for kind in ("in", "out"):
        for frac in (1.0, 0.6, 0.3):
            rows = [r for r in results if r.get("kind") == kind and r.get("fraction") == frac]
            by_cell.append({
                "kind": kind, "query_pool_fraction": frac, "worlds": len(rows),
                "promoted": sum(bool(r.get("promoted")) for r in rows),
                "wrong_promotion": sum(bool(r.get("wrong_promotion")) for r in rows),
                "missed_promotion": sum(bool(r.get("missed_promotion")) for r in rows),
                "correct_predictions": sum(r.get("correct", 0) for r in rows),
                "wrong_predictions": sum(r.get("wrong", 0) for r in rows),
                "unknown_predictions": sum(r.get("unknown", 0) for r in rows),
                "total_queries": sum(r.get("queries", 0) for r in rows),
            })
    report = {
        "audit_version": "external-blackbox-v1",
        "independent_oracle": True,
        "test_source": "external script, not repository generator",
        "repo_commit": commit, "seed": args.seed, "budget": args.budget,
        "protocol": protocol, "by_cell": by_cell,
        "student_exit_code": exit_code, "stderr_tail": stderr_tail,
        "worlds": results
    }
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"repo_commit": commit, "by_cell": by_cell, "protocol": protocol,
                      "output": str(args.output)}, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
