#!/usr/bin/env python3
"""One-time creation/verification of BMNV-01 student+examiner freeze manifest.
Usage: python tools/freeze_bmnv.py --root . [--verify]
"""
import argparse, hashlib, json, subprocess
from datetime import datetime, timezone
from pathlib import Path

STUDENT = [
    'src/main/kotlin/logic/e3/bmnv/BMNV01Kernel.kt',
    'src/test/kotlin/logic/e3/bmnv/BMNV01PublicContract.kt',
]
EXAMINER_CANDIDATES = [
    'src/test/kotlin/logic/e3/bmnv/BMNV01Examiner.kt',
    'examiner/BMNV01Examiner.kt',
]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def manifest(root):
    examiner = next((x for x in EXAMINER_CANDIDATES if (root / x).is_file()), None)
    if examiner is None:
        raise SystemExit('ERROR: examiner source not found')
    paths = STUDENT + [examiner]
    for rel in paths:
        if not (root / rel).is_file():
            raise SystemExit(f'ERROR: missing file {rel}')
    return {x: sha(root / x) for x in paths}

def git_head(root):
    try:
        p = subprocess.run(['git','rev-parse','HEAD'],cwd=root,capture_output=True,text=True,check=True)
        return p.stdout.strip()
    except (OSError,subprocess.CalledProcessError):
        return 'not-a-git-checkout'

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',default='.');p.add_argument('--verify',action='store_true')
    opts=p.parse_args();root=Path(opts.root).resolve()
    output=root/'docs/research/bmnv-01/FREEZE.json'
    expected=manifest(root)
    if opts.verify:
        if not output.is_file():raise SystemExit('FREEZE MISSING; do not run examiner')
        saved=json.loads(output.read_text(encoding='utf-8'))
        if saved.get('sources') != expected:raise SystemExit('SOURCE OR EXAMINER HASH MISMATCH')
        print('BMNV01_FREEZE_VERIFIED',len(expected),'files',saved.get('gitHeadAtFreeze'))
        return
    if output.exists():raise SystemExit('FREEZE ALREADY EXISTS: cannot overwrite or re-sign')
    output.parent.mkdir(parents=True,exist_ok=True)
    record={
        'protocol':'BMNV01-SINGLE-PACK-v1',
        'createdUtc':datetime.now(timezone.utc).isoformat(),
        'gitHeadAtFreeze':git_head(root),
        'sources':expected,
        'independentClosedBook':'NOT_CLAIMED',
        'exam':'NOT_RUN',
        'note':'Examiner code is visible from single package; no independent blind examination.'
    }
    output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('BMNV01_FROZEN',output)

if __name__=='__main__':main()
