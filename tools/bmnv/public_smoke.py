#!/usr/bin/env python3
"""Run 11 public Kotlin contract checks without Gradle or official exam. Requires kotlinc+java."""
import argparse, shutil, subprocess, tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',default='.');a=p.parse_args()
root=Path(a.root).resolve(); k=shutil.which('kotlinc'); j=shutil.which('java')
if not k or not j:raise SystemExit('Need kotlinc+java. Alternatively wire the public check into Gradle quickTest.')
with tempfile.TemporaryDirectory(prefix='bmnv_smoke_') as tmp:
    jar=Path(tmp)/'public.jar'
    subprocess.run([k,str(root/'src/main/kotlin/logic/e3/bmnv/BMNV01Kernel.kt'),
      str(root/'src/test/kotlin/logic/e3/bmnv/BMNV01PublicContract.kt'),'-include-runtime','-d',str(jar)],check=True)
    subprocess.run([j,'-cp',str(jar),'logic.e3.bmnv.BMNV01PublicContract'],check=True)
