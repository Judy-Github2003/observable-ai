#!/usr/bin/env python3
"""V2 规则自主发现：公开开发演示 + 多世界统计。

作者：DeepSeek Harness（本会话），2026-11-04。
原因：用独立 Python oracle（不调用学生 Kotlin 判分函数）交互式驱动 CLI，验证归纳、拒答、冲突与迁移。
限制：这是公开开发测试，不是独立闭卷；隐藏世界与最终裁判应交给外部审查者。
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import random
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELDS = {
    "颜色": ["红色", "蓝色", "绿色", "黄色"],
    "形状": ["圆形", "方形", "三角形"],
    "大小": ["大", "小"],
}


def assignments(schema: dict[str, list[str]]) -> list[dict[str, str]]:
    keys = list(schema)
    return [dict(zip(keys, values)) for values in itertools.product(*(schema[k] for k in keys))]


def evaluate(rule: dict, features: dict[str, str]):
    """独立三值求值：返回 True / False / None(unknown)。"""
    unknown = False
    for clause in rule["clauses"]:
        value = features.get(clause["field"])
        if value is None:
            unknown = True
        elif value != clause["eq"]:
            return False
    return None if unknown else True


def equivalent(left: dict, right: dict, schema: dict[str, list[str]]) -> bool:
    return all(evaluate(left, item) == evaluate(right, item) for item in assignments(schema))


class Session:
    """一个 JVM，stdin/stdout 逐行交互；状态由 CLI 自己持久化。"""

    def __init__(self, workdir: Path):
        workdir.mkdir(parents=True, exist_ok=True)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java.exe") if os.environ.get("JAVA_HOME") else "java"
        classpath = str(ROOT / "build" / "install" / "logic-learner" / "lib" / "*")
        self.process = subprocess.Popen(
            [java, "-cp", classpath, "logic.discovery.DiscoveryCliKt", "-", str(workdir / "state")],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", bufsize=1,
        )

    def call(self, payload: dict) -> dict:
        assert self.process.stdin and self.process.stdout
        self.process.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            error = self.process.stderr.read()[-500:] if self.process.stderr else ""
            raise RuntimeError(f"CLI 无响应：{error}")
        return json.loads(line)

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        self.process.wait(timeout=30)


def make_world(rng: random.Random, index: int) -> dict:
    fields = rng.sample(list(FIELDS), rng.choice([2, 3]))
    schema = {field: rng.sample(FIELDS[field], min(len(FIELDS[field]), rng.choice([2, 3]))) for field in fields}
    rule_fields = rng.sample(fields, rng.choice([1, 2]))
    rule = {"op": "and", "clauses": [{"field": field, "eq": rng.choice(schema[field])} for field in rule_fields]}
    domain = assignments(schema)
    positives = [item for item in domain if evaluate(rule, item) is True]
    negatives = [item for item in domain if evaluate(rule, item) is False]
    if not positives or not negatives:
        return {}
    known = [positives[0], negatives[0]]
    rng.shuffle(known)
    rest = [item for item in domain if item not in known]
    return {"index": index, "schema": schema, "rule": rule, "known": known, "pool": rest, "domain": domain}


def drive(world: dict, workdir: Path, rng: random.Random) -> dict:
    schema, hidden = world["schema"], world["rule"]
    known_examples = [
        {"id": obj_id, "features": item, "label": evaluate(hidden, item) is True, "evidence_id": f"e-{obj_id}"}
        for obj_id, item in world["named_known"]
    ]
    pool = dict(world["named_pool"])
    query_pool = [{"id": obj_id, "features": item} for obj_id, item in pool.items()]
    session = Session(workdir)
    trace = []
    try:
        started = session.call({
            "task_id": "start", "world_id": world["world_id"], "op": "discovery.start",
            "args": {"feature_schema": schema, "known_examples": known_examples, "query_pool": query_pool},
        })
        trace.append({"op": "start", "surviving": started.get("result", {}).get("surviving_count")})
        queries = 0
        final_reason = None
        while queries < len(pool):
            query = session.call({"task_id": "q", "world_id": world["world_id"], "op": "discovery.next_query", "args": {}})
            if query.get("status") != "ok":
                final_reason = query.get("reason")
                break
            object_id = query["result"]["object_id"]
            label = evaluate(hidden, pool[object_id]) is True
            observed = session.call({
                "task_id": "o", "world_id": world["world_id"], "op": "discovery.observe",
                "args": {"object_id": object_id, "label": label, "evidence_id": f"e-{object_id}"},
            })
            queries += 1
            trace.append({
                "op": "observe", "object_id": object_id, "label": label,
                "candidate_count": observed.get("result", {}).get("candidate_count"),
                "eliminated": len(observed.get("result", {}).get("eliminated", [])),
            })
            if observed.get("status") != "ok":
                final_reason = observed.get("reason")
                break

        finalize = session.call({"task_id": "f", "world_id": world["world_id"], "op": "discovery.finalize", "args": {}})
        promoted = finalize.get("result", {}).get("rule") if finalize.get("status") == "ok" else None
        exact = bool(promoted) and equivalent(promoted, hidden, schema)

        predicted = {"true": 0, "false": 0, "unknown": 0}
        correct = 0
        for _, item in pool.items():
            response = session.call({"task_id": "p", "world_id": world["world_id"], "op": "discovery.predict",
                                     "args": {"features": item}})
            label = response.get("result", {}).get("label", "unknown")
            predicted[label] = predicted.get(label, 0) + 1
            truth = evaluate(hidden, item)
            if (label == "true" and truth is True) or (label == "false" and truth is False):
                correct += 1
    finally:
        session.close()

    return {
        "world": world["world_id"], "schema": schema, "hidden_rule": hidden,
        "promoted": bool(promoted), "exact_equivalence": exact, "promoted_rule": promoted,
        "queries": queries, "finalize_status": finalize.get("status"),
        "finalize_reason": finalize.get("reason") or final_reason,
        "predictions": predicted, "predict_correct": correct, "predict_total": len(pool),
        "pool_negatives": sum(1 for item in pool.values() if evaluate(hidden, item) is False),
        "trace": trace,
    }


def fixed_cases(workdir: Path) -> dict:
    schema = {"颜色": ["红色", "蓝色"], "形状": ["圆形", "方形"]}
    examples = [
        {"id": "A", "features": {"颜色": "红色", "形状": "圆形"}, "label": True, "evidence_id": "e-A"},
        {"id": "B", "features": {"颜色": "蓝色", "形状": "方形"}, "label": False, "evidence_id": "e-B"},
    ]
    pool = [
        {"id": "C", "features": {"颜色": "红色", "形状": "方形"}},
        {"id": "D", "features": {"颜色": "蓝色", "形状": "圆形"}},
    ]
    session = Session(workdir)
    try:
        out = []
        out.append(session.call({"task_id": "e2s", "world_id": "e2", "op": "discovery.start",
                                 "args": {"feature_schema": schema, "known_examples": examples, "query_pool": []}}))
        out.append(session.call({"task_id": "e2f", "world_id": "e2", "op": "discovery.finalize", "args": {}}))
        out.append(session.call({"task_id": "e3s", "world_id": "e3", "op": "discovery.start",
                                 "args": {"feature_schema": schema, "known_examples": [examples[0]], "query_pool": [pool[1]]}}))
        out.append(session.call({"task_id": "e3o", "world_id": "e3", "op": "discovery.observe",
                                 "args": {"object_id": "A", "label": False, "evidence_id": "e-A2"}}))
        out.append(session.call({"task_id": "e3f", "world_id": "e3", "op": "discovery.finalize", "args": {}}))
    finally:
        session.close()
    return {
        "E2": {"finalize_status": out[1]["status"], "reason": out[1]["reason"]},
        "E3": {"observe_status": out[3]["status"], "finalize_status": out[4]["status"], "reason": out[4]["reason"]},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worlds", type=int, default=30)
    parser.add_argument("--seed", type=int, default=20261104)
    parser.add_argument("--out", type=Path, default=ROOT / "experiments/rule-discovery-v1/reports/public-30.json")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    results, skipped = [], 0
    with tempfile.TemporaryDirectory(prefix="rule-discovery-") as temp:
        temp_root = Path(temp)
        for index in range(args.worlds):
            world = make_world(rng, index)
            if not world:
                skipped += 1
                continue
            world["world_id"] = f"world-{index:03d}-{rng.randrange(16**6):06x}"
            world["named_known"] = [(f"k{index}_{i}", item) for i, item in enumerate(world["known"])]
            world["named_pool"] = [(f"q{index}_{i}", item) for i, item in enumerate(world["pool"])]
            results.append(drive(world, temp_root / world["world_id"], rng))
        fixed = fixed_cases(temp_root / "fixed")

    promoted = sum(1 for item in results if item["promoted"])
    exact = sum(1 for item in results if item["exact_equivalence"])
    correct = sum(item["predict_correct"] for item in results)
    total = sum(item["predict_total"] for item in results)
    queries = [item["queries"] for item in results]
    baseline_correct = sum(item["pool_negatives"] for item in results)
    summary = {
        "seed": args.seed,
        "worlds_attempted": len(results) + skipped,
        "worlds_run": len(results),
        "worlds_skipped": skipped,
        "promoted": promoted,
        "exact_rule_equivalence": exact,
        "promotion_rate": round(promoted / len(results), 4) if results else 0,
        "exact_rate": round(exact / len(results), 4) if results else 0,
        "predict_correct": correct,
        "predict_total": total,
        "predict_accuracy": round(correct / total, 4) if total else 0,
        "avg_queries": round(sum(queries) / len(queries), 2) if queries else 0,
        "max_queries": max(queries) if queries else 0,
        # 只记忆 ID 的基线：对未见对象只能答 unknown，因此在新对象上正确率为 0。
        "id_memory_baseline_accuracy": 0.0,
        # 多数类基线：对未见对象一律答 false；用来显示“只靠准确率”会被高负例比例抬高。
        "majority_false_baseline_accuracy": round(baseline_correct / total, 4) if total else 0,
        "teacher_direct_upper_bound": 1.0,
        "fixed_cases": fixed,
        "scenarios": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({k: v for k, v in summary.items() if k != "scenarios"}, ensure_ascii=False, indent=2))
    print("\nE1 trace (first world):")
    if results:
        for step in results[0]["trace"]:
            print(" ", json.dumps(step, ensure_ascii=False))
    return 0 if results and exact == len(results) and correct == total else 1


if __name__ == "__main__":
    sys.exit(main())
