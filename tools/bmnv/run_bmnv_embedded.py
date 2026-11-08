#!/usr/bin/env python3
"""BMNV-01 one-shot runner for hosts without `kotlinc` on PATH.

Semantics are identical to tools/bmnv/run_bmnv.py; only the compiler invocation is
replaced by the Kotlin embeddable compiler (same compiler version as the project's
Kotlin Gradle plugin), because this host has no `kotlinc` and no `java` on PATH.

Everything else is preserved:
  * frozen source hashes are re-checked against docs/research/bmnv-01/FREEZE.json
  * a fresh output directory is required
  * a one-time random 63-bit salt is generated
  * `<out>.intent.json` is written BEFORE the examiner is started
  * RUN.json is written only after the examiner exits successfully

Usage:
  python tools/bmnv/run_bmnv_embedded.py --root . --out build/bmnv-01-official-once
"""
import argparse
import hashlib
import json
import secrets
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from freeze_bmnv import manifest  # noqa: E402

KOTLIN_VERSION = "2.2.21"


def find_java() -> str:
    for candidate in (
        Path.home() / ".jdks/jbr-21.0.11/bin/java.exe",
        Path.home() / ".jdks/jbr-21.0.11/bin/java",
    ):
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if not found:
        raise SystemExit("ABORT: no java executable found")
    return found


def find_gradle_jar(pattern: str, prefer_largest: bool = False) -> str:
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    matches = sorted(cache.rglob(pattern), key=lambda p: (p.stat().st_size if prefer_largest else 0), reverse=prefer_largest)
    if not matches:
        raise SystemExit(f"ABORT: cannot locate {pattern} in the Gradle cache")
    return str(matches[0])


def compiler_classpath() -> str:
    return ";".join([
        find_gradle_jar(f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar"),
        find_gradle_jar(f"kotlin-stdlib-{KOTLIN_VERSION}.jar", prefer_largest=True),
        find_gradle_jar("kotlin-reflect-1.6.10.jar"),
        find_gradle_jar("kotlinx-coroutines-core-jvm-1.8.0.jar"),
        find_gradle_jar("annotations-13.0.jar"),
    ])


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", default=".")
    p.add_argument("--out", required=True)
    a = p.parse_args()
    root = Path(a.root).resolve()
    out = Path(a.out).resolve()
    freeze = root / "docs/research/bmnv-01/FREEZE.json"
    if not freeze.is_file():
        raise SystemExit("ABORT: no student freeze manifest")
    record = json.loads(freeze.read_text(encoding="utf-8"))
    if record["sources"] != manifest(root):
        raise SystemExit("ABORT: frozen source hash differs")
    if out.exists():
        raise SystemExit("ABORT: output directory already exists")
    intent = Path(str(out) + ".intent.json")
    if intent.exists():
        raise SystemExit("ABORT: existing intent; official attempt has already begun")

    java = find_java()
    stdlib = find_gradle_jar(f"kotlin-stdlib-{KOTLIN_VERSION}.jar", prefer_largest=True)
    exam_rel = next(p for p in record["sources"] if p.endswith("BMNV01Examiner.kt"))
    exam = root / exam_rel
    learner = root / "src/main/kotlin/logic/e3/bmnv/BMNV01Kernel.kt"

    with tempfile.TemporaryDirectory(prefix="bmnv_compile_") as tmp:
        jar = Path(tmp) / "BMNV01Exam.jar"
        compile_cmd = [
            java, "-cp", compiler_classpath(),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            str(learner), str(exam),
            "-no-stdlib", "-classpath", stdlib,
            "-d", str(jar),
        ]
        subprocess.run(compile_cmd, check=True)
        # If compilation fails, no attempt was started and no seed was consumed.
        out.parent.mkdir(parents=True, exist_ok=True)
        salt = secrets.randbits(63)
        precommit = {
            "protocol": "BMNV01-SINGLE-PACK-v1",
            "createdUtc": datetime.now(timezone.utc).isoformat(),
            "sourceHashes": record["sources"],
            "freezeSha256": hashlib.sha256(freeze.read_bytes()).hexdigest(),
            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "salt": salt,
            "out": str(out),
            "status": "STARTED_BEFORE_EXAM",
            "compiler": f"kotlin-compiler-embeddable {KOTLIN_VERSION} (host has no kotlinc)",
            "java": java,
        }
        with intent.open("x", encoding="utf-8") as f:
            json.dump(precommit, f, indent=2)
        subprocess.run([java, "-cp", f"{jar};{stdlib}", "logic.e3.bmnv.BMNV01Examiner", str(out), str(salt)], check=True)
        done = {
            **precommit,
            "status": "FINISHED",
            "studentSha256": hashlib.sha256((out / "STUDENTS-FROZEN.jsonl").read_bytes()).hexdigest(),
        }
        (out / "RUN.json").write_text(json.dumps(done, indent=2) + "\n", encoding="utf-8")
    print("BMNV01_EXAM_COMPLETE", out)
    print("Next: python tools/bmnv/audit_bmnv.py --result", out)


if __name__ == "__main__":
    main()
