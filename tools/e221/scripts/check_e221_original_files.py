#!/usr/bin/env python3
"""Optional raw-byte evidence identity check against originally delivered E2.2 ZIP.

May report differences on Git checkouts with altered line-endings; never normalize
or rewrite automatically. Rely on git diff for worktree-change audit in addition.
"""
from __future__ import annotations
import argparse, hashlib, json
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('repository_root',type=Path)
    p.add_argument('original_manifest',type=Path)
    a=p.parse_args()
    entries=json.loads(a.original_manifest.read_text(encoding='utf8'))['files']
    fails=[]
    for rel,digest in entries.items():
        f=a.repository_root/rel
        if not f.is_file():fails.append(f'MISSING {rel}')
        elif hashlib.sha256(f.read_bytes()).hexdigest()!=digest:fails.append(f'DIFFERENT RAW BYTES {rel} (check Git line-ending conversion)')
    if fails:
        print('\n'.join(fails));print('FAIL: do not silently normalize or overwrite')
        return 1
    print(f'E221 RAW E22 JSONL IDENTITY PASS: {len(entries)} files')
    return 0

if __name__=='__main__':raise SystemExit(main())
