#!/usr/bin/env python3
"""Collect OA-NEXT-01 FL-PILOT-01 score files into auditable CSV/JSONL.

Reads only score outputs (which came from the trusted scorer) and accepted-state
JSONL. It never reads sealed answers itself.
"""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

DOMAINS = ["semantics", "logic", "mathematics", "code_behavior"]


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def append_rows(rows, split, seed, score, arm):
    per_world = score.get("per_world", [])
    per_domain = score.get("per_domain", {})
    for row in per_world:
        total = row["total"]
        rows.append({
            "split": split, "seed": seed, "domain": row["domain"], "world_id": row["world_id"],
            "arm": arm, "correct": row["correct"], "total": total, "missing": row["missing"],
            "accuracy": round(row["correct"] / total, 6) if total else "",
        })
    for domain in DOMAINS:
        if domain not in per_domain:
            continue
        value = per_domain[domain]
        total = value["total"]
        rows.append({
            "split": split, "seed": seed, "domain": domain, "world_id": "ALL",
            "arm": arm, "correct": value["correct"], "total": total, "missing": value["missing"],
            "accuracy": round(value["correct"] / total, 6) if total else "",
        })
    total = sum(value["total"] for value in per_domain.values())
    correct = sum(value["correct"] for value in per_domain.values())
    missing = sum(value["missing"] for value in per_domain.values())
    rows.append({
        "split": split, "seed": seed, "domain": "ALL", "world_id": "ALL",
        "arm": arm, "correct": correct, "total": total, "missing": missing,
        "accuracy": round(correct / total, 6) if total else "",
    })


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dev", required=True)
    parser.add_argument("--heldout", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    dev = Path(args.dev)
    held = Path(args.heldout)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)

    rows = []
    for split, root, seed in (("dev", dev, 24117), ("heldout", held, 99173)):
        for arm in ("A", "B", "C"):
            if arm == "C":
                score_path = root / "kotlin-c" / "score.json"
            else:
                score_path = root / "runs" / f"{arm}-{'dev' if split == 'dev' else 'heldout'}" / "score.json"
            append_rows(rows, split, seed, load_json(score_path), arm)

    csv_path = out / "FOUNDATION_RESULTS.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["split", "seed", "domain", "world_id", "arm", "correct", "total", "missing", "accuracy"])
        writer.writeheader()
        writer.writerows(rows)

    # Accepted learning state from both splits, tagged with split.
    state_lines = []
    for split, root in (("dev", dev), ("heldout", held)):
        path = root / "kotlin-c" / "accepted_state.jsonl"
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            row["split"] = split
            state_lines.append(row)
    (out / "LEARNING_STATE.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in state_lines),
        encoding="utf-8",
    )

    # Sanitized model traces (public prompts and responses only).
    for split, root in (("dev", dev), ("heldout", held)):
        combined = []
        for arm in ("A", "B"):
            trace = root / "runs" / f"{arm}-{'dev' if split == 'dev' else 'heldout'}" / "traces.jsonl"
            for line in trace.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    combined.append(json.loads(line))
        (out / f"MODEL_TRACES_{split}.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in combined),
            encoding="utf-8",
        )

    manifest = {
        "schema": "oa-next01-foundation-results-v1",
        "generated_by": "experiments/foundation-pilot/collect_results.py",
        "dev_seed": 24117,
        "heldout_seed": 99173,
        "worlds_per_domain": 2,
        "domains": DOMAINS,
        "arms": {"A": "ZERO_HISTORY", "B": "RAW_RETRIEVAL", "C": "OBSERVABLE_LEARNING"},
        "model": "openbmb/minicpm5-2b:latest",
        "model_digest": "71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7",
        "inputs": {
            "dev_manifest": sha256(dev / "manifest.json"),
            "heldout_manifest": sha256(held / "manifest.json"),
            "kotlin_predictions_dev": sha256(dev / "kotlin-c" / "predictions.jsonl"),
            "kotlin_predictions_heldout": sha256(held / "kotlin-c" / "predictions.jsonl"),
        },
        "note": "Heldout is DEVELOPER_HELDOUT (developer-chosen seed), not independent closed-book. C is symbolic inversion of a narrow generator family.",
    }
    (out / "RESULTS_MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {csv_path} rows={len(rows)} state={len(state_lines)}")


if __name__ == "__main__":
    main()
