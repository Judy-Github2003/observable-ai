#!/usr/bin/env python3
"""Verify signed-by-checksum file inventory after extracting BMNV-01 ZIP."""
from pathlib import Path
import hashlib, sys
root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
lines=(root/'SHA256SUMS.txt').read_text(encoding='utf-8').splitlines()
for line in lines:
    wanted,rel=line.split('  ',1)
    p=root/rel
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=wanted:
        raise SystemExit(f'BMNV01_PACKAGE_INTEGRITY_FAIL: {rel}')
print('BMNV01_PACKAGE_INTEGRITY_PASS',len(lines),'files')
