#!/usr/bin/env python3
"""Verify source hashes and the sealed ZIP against Phase-A FREEZE.json; no re-signing."""
import argparse, hashlib, json
from pathlib import Path

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--repo',required=True)
    ap.add_argument('--examiner-zip',required=True)
    args=ap.parse_args()
    repo=Path(args.repo)
    m=json.loads((repo/'docs/research/gnjv-01/FREEZE.json').read_text(encoding='utf-8'))
    examiner_actual=hashlib.sha256(Path(args.examiner_zip).read_bytes()).hexdigest()
    assert m['examinerZipSha256']==examiner_actual, f'Examiner ZIP changed: {examiner_actual}'
    for rel,expected in m['sources'].items():
        actual=hashlib.sha256((repo/rel).read_bytes()).hexdigest()
        assert actual==expected, f'Student source changed after freeze: {rel}'
    print(f'GNJV01_FREEZE_VERIFIED files={len(m["sources"])} examinerSha256={examiner_actual}')

if __name__=='__main__': main()
