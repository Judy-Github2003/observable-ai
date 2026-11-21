#!/usr/bin/env python3
"""Freeze the OA-NEXT-01 FL-PILOT-01 protocol before the heldout run.

The freeze records code/data/prompt/model hashes and the heldout seed. It does
not read sealed answers and does not run any student. After this file is
written, code, prompts and scoring must not change for the heldout run.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sha_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kit-tools", required=True)
    parser.add_argument("--dev-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--code-commit", required=True)
    parser.add_argument("--holdout-seed", type=int, required=True)
    parser.add_argument("--model", default="openbmb/minicpm5-2b:latest")
    args = parser.parse_args()

    kit = Path(args.kit_tools)
    dev = Path(args.dev_dir)
    repo = Path(__file__).resolve().parents[2]
    files = {
        "generator": kit / "foundation_microbench.py",
        "scorer": kit / "score_microbench.py",
        "examiner_selftest": kit / "selftest.py",
        "prompts": repo / "experiments/foundation-pilot/prompts.py",
        "model_runner": repo / "experiments/foundation-pilot/run_model_arm.py",
        "kotlin_learner": repo / "src/main/kotlin/observable/ai/foundation/FoundationPilot.kt",
        "kotlin_gradle": repo / "build.gradle.kts",
    }
    hashes = {name: sha256(path) for name, path in files.items()}
    dev_manifest = json.loads((dev / "manifest.json").read_text(encoding="utf-8"))
    arm_prompts = {}
    for arm in ("A", "B"):
        run_file = dev / "runs" / f"{arm}-dev" / "run.json"
        if run_file.exists():
            run = json.loads(run_file.read_text(encoding="utf-8"))
            arm_prompts[arm] = {
                "num_questions": run.get("num_questions"),
                "prompt_hashes_sha256": run.get("prompt_hashes_sha256"),
            }
    c_prompts = {}
    try:
        c_run = json.loads((dev / "kotlin-c" / "manifest.json").read_text(encoding="utf-8"))
        c_prompts = {
            "question_file_sha256": c_run.get("question_file_sha256"),
            "accepted": c_run.get("accepted"),
            "rejected": c_run.get("rejected"),
        }
    except FileNotFoundError:
        pass
    freeze = {
        "schema": "oa-next01-foundation-pilot-freeze-v1",
        "frozen_at_local": __import__("datetime").datetime.now().astimezone().isoformat(),
        "code_commit": args.code_commit,
        "model": args.model,
        "model_digest": "71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7",
        "dev_seed": dev_manifest.get("seed"),
        "holdout_seed": args.holdout_seed,
        "worlds_per_domain": dev_manifest.get("worlds_per_domain"),
        "dev_manifest_sha256": sha_text((dev / "manifest.json").read_text(encoding="utf-8")),
        "file_hashes": hashes,
        "dev_arm_prompts": arm_prompts,
        "dev_c_state": c_prompts,
        "freeze_note": "Protocol frozen before the heldout seed is generated/run. Heldout is DEVELOPER_HELDOUT, not independent closed-book.",
    }
    Path(args.out).write_text(json.dumps(freeze, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(freeze, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
