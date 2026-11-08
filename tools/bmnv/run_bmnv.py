#!/usr/bin/env python3
"""One-shot post-freeze runner. Run only after integration, public checks and Git freeze commit.
Usage: python tools/run_bmnv.py --root . --out ./build/bmnv-01-FIRST-OFFICIAL
Required: java and kotlinc (or manually use Gradle/embedded Kotlin compiler with identical sources).
"""
import argparse, hashlib, json, secrets, shutil, subprocess, sys, tempfile
from datetime import datetime,timezone
from pathlib import Path

from freeze_bmnv import manifest

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',default='.');p.add_argument('--out',required=True)
    a=p.parse_args();root=Path(a.root).resolve();out=Path(a.out).resolve()
    freeze=root/'docs/research/bmnv-01/FREEZE.json'
    if not freeze.is_file():raise SystemExit('ABORT: no student freeze manifest')
    record=json.loads(freeze.read_text(encoding='utf-8'))
    if record['sources']!=manifest(root):raise SystemExit('ABORT: frozen source hash differs')
    if out.exists():raise SystemExit('ABORT: output directory already exists')
    intent=Path(str(out)+'.intent.json')
    if intent.exists():raise SystemExit('ABORT: existing intent; official attempt has already begun')
    compiler=shutil.which('kotlinc');java=shutil.which('java')
    if not compiler or not java:
        raise SystemExit('Compiler unavailable: use project Kotlin Gradle task; DO NOT change frozen sources')
    exam=next(root / p for p in record['sources'] if p.endswith('BMNV01Examiner.kt'))
    learner=root/'src/main/kotlin/logic/e3/bmnv/BMNV01Kernel.kt'
    with tempfile.TemporaryDirectory(prefix='bmnv_compile_') as tmp:
        jar=Path(tmp)/'BMNV01Exam.jar'
        subprocess.run([compiler,str(learner),str(exam),'-include-runtime','-d',str(jar)],check=True)
        # If compilation fails, no attempt was started and no seed was consumed.
        out.parent.mkdir(parents=True,exist_ok=True)
        salt=secrets.randbits(63)
        precommit={
            'protocol':'BMNV01-SINGLE-PACK-v1',
            'createdUtc':datetime.now(timezone.utc).isoformat(),
            'sourceHashes':record['sources'],
            'freezeSha256':hashlib.sha256(freeze.read_bytes()).hexdigest(),
            'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
            'salt':salt,
            'out':str(out),
            'status':'STARTED_BEFORE_EXAM'
        }
        with intent.open('x',encoding='utf-8') as f:json.dump(precommit,f,indent=2)
        subprocess.run([java,'-cp',str(jar),'logic.e3.bmnv.BMNV01Examiner',str(out),str(salt)],check=True)
        done={**precommit,'status':'FINISHED','studentSha256':hashlib.sha256((out/'STUDENTS-FROZEN.jsonl').read_bytes()).hexdigest()}
        (out/'RUN.json').write_text(json.dumps(done,indent=2)+'\n',encoding='utf-8')
    print('BMNV01_EXAM_COMPLETE',out)
    print('Next: python tools/audit_bmnv.py --result',out)

if __name__=='__main__':main()
