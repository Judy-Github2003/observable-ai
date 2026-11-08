#!/usr/bin/env python3
"""Compile and run a public Kotlin smoke. Does NOT exercise sealed exams."""
from pathlib import Path
import subprocess, tempfile

root=Path(__file__).resolve().parents[2]
src=root/'src/main/kotlin/logic/e3/gnjv/GNJV01Kernel.kt'
smoke=root/'tools/gnjv/GNJV01Smoke.kt'
with tempfile.TemporaryDirectory(prefix='gnjv01-smoke-') as d:
    jar=str(Path(d)/'smoke.jar')
    subprocess.run(['kotlinc',str(src),str(smoke),'-include-runtime','-d',jar],check=True)
    out=subprocess.check_output(['java','-jar',jar],text=True)
    assert 'GNJV01_PUBLIC_SMOKE_PASS' in out
    print(out.strip())
