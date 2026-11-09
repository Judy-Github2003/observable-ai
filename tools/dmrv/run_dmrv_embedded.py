#!/usr/bin/env python3
"""DMRV-01 one-shot runner for hosts without `kotlinc`/`java` on PATH.

Semantics are identical to tools/dmrv/run_dmrv.py; only the compiler invocation is
replaced by the Kotlin embeddable compiler (same 2.2.21 compiler the project uses).

Preserved behaviour:
  * output folder must not exist
  * all three frozen sources must exist and match docs/research/dmrv-01/FREEZE.json
  * official mode writes a one-shot intent guard BEFORE starting the examiner
  * a fresh 62-bit random salt is generated per attempt
  * stdout/stderr logs, FREEZE-COPY.json and RUN.json are written
  * frozen sources are re-hashed after the run
"""
import argparse
import hashlib
import json
import shutil
import secrets
import subprocess
import sys
import datetime
from pathlib import Path

SOURCES = [
    'src/main/kotlin/logic/e3/dmrv/DMRV01Kernel.kt',
    'src/test/kotlin/logic/e3/dmrv/DMRV01PublicContract.kt',
    'src/test/kotlin/logic/e3/dmrv/DMRV01Examiner.kt',
]
KOTLIN_VERSION = "2.2.21"


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def find_java() -> str:
    for candidate in (Path.home() / ".jdks/jbr-21.0.11/bin/java.exe", Path.home() / ".jdks/jbr-21.0.11/bin/java"):
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if not found:
        raise SystemExit("Missing java executable")
    return found


def find_jar(pattern: str, prefer_largest: bool = False) -> str:
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    matches = sorted(cache.rglob(pattern), key=lambda p: p.stat().st_size if prefer_largest else 0, reverse=prefer_largest)
    if not matches:
        raise SystemExit(f"Cannot locate {pattern} in Gradle cache")
    return str(matches[0])


def compiler_cp() -> str:
    return ";".join([
        find_jar(f"kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar"),
        find_jar(f"kotlin-stdlib-{KOTLIN_VERSION}.jar", True),
        find_jar("kotlin-reflect-1.6.10.jar"),
        find_jar("kotlinx-coroutines-core-jvm-1.8.0.jar"),
        find_jar("annotations-13.0.jar"),
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', default='.')
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--official', action='store_true')
    mode.add_argument('--development', action='store_true')
    parser.add_argument('--output', required=True)
    a = parser.parse_args()
    root = Path(a.repo).resolve()
    out = Path(a.output).resolve()
    if out.exists():
        raise SystemExit('Output folder must not exist; never overwrite previous results')
    for f in SOURCES:
        if not (root / f).is_file():
            raise SystemExit('Missing source ' + f)
    freeze = root / 'docs/research/dmrv-01/FREEZE.json'
    if not freeze.exists():
        raise SystemExit('Run freeze_dmrv.py first')
    metadata = json.loads(freeze.read_text('utf-8'))
    if metadata.get('schema') != 'DMRV01-FREEZE-v1':
        raise SystemExit('Invalid freeze manifest')
    for f, expected in metadata['sources'].items():
        if sha(root / f) != expected:
            raise SystemExit('Freeze source hash mismatch: ' + f)

    java = find_java()
    stdlib = find_jar(f"kotlin-stdlib-{KOTLIN_VERSION}.jar", True)
    build = root / 'build/dmrv-01-runner'
    build.mkdir(parents=True, exist_ok=True)
    jar = build / 'dmrv01-examiner.jar'
    cp = subprocess.run([
        java, '-cp', compiler_cp(), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        str(root / SOURCES[0]), str(root / SOURCES[2]),
        '-no-stdlib', '-classpath', stdlib, '-d', str(jar),
    ], text=True, capture_output=True, timeout=600)
    if cp.returncode:
        raise SystemExit('Kotlin compile failed:\n' + cp.stdout + '\n' + cp.stderr)
    out.parent.mkdir(parents=True, exist_ok=True)
    if a.official:
        guard = root / 'build/dmrv-01-official-one-shot-intent.json'
        intent = {'mode': 'official',
                  'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'output': str(out), 'freezeSha256': sha(freeze), 'jarSha256': sha(jar),
                  'compiler': f'kotlin-compiler-embeddable {KOTLIN_VERSION} (host has no kotlinc)'}
        try:
            with guard.open('x', encoding='utf-8') as g:
                json.dump(intent, g, indent=2)
        except FileExistsError:
            raise SystemExit('Official one-shot attempt already registered; stop and report, no rerun')
    mode_name = 'official' if a.official else 'development'
    salt = secrets.randbits(62)
    command = [java, '-cp', f'{jar};{stdlib}', 'logic.e3.dmrv.DMRV01Examiner', str(out), str(salt), mode_name]
    try:
        run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=900)
    except subprocess.TimeoutExpired:
        raise SystemExit('Examiner timed out. Do not rerun official without researcher authorization.')
    if not out.exists():
        raise SystemExit('Examiner failed before output creation. Exit=' + str(run.returncode) + '\n' + run.stderr)
    (out / 'RUN-STDOUT.log').write_text(run.stdout, 'utf-8')
    (out / 'RUN-STDERR.log').write_text(run.stderr, 'utf-8')
    (out / 'FREEZE-COPY.json').write_bytes(freeze.read_bytes())
    (out / 'RUN.json').write_text(json.dumps({
        'mode': mode_name, 'exitCode': run.returncode, 'salt': salt,
        'freezeSha256': sha(freeze), 'examinerJarSha256': sha(jar),
        'studentSha256': sha(out / 'STUDENTS-FROZEN.jsonl') if (out / 'STUDENTS-FROZEN.jsonl').exists() else None,
        'sourceSha256After': {f: sha(root / f) for f in SOURCES},
        'utcFinished': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'compiler': f'kotlin-compiler-embeddable {KOTLIN_VERSION} (host has no kotlinc)',
    }, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    if run.returncode:
        raise SystemExit('Examiner nonzero exit ' + str(run.returncode) + '; do not rerun official')
    for f, d in metadata['sources'].items():
        if sha(root / f) != d:
            raise SystemExit('Source changed during run: ' + f)
    print(run.stdout.strip())
    print('DMRV01_RUN_FINISHED', out)


if __name__ == '__main__':
    main()
