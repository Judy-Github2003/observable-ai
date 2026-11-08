#!/usr/bin/env python3
"""Fail-closed E23-R patch installer for an OUTSIDE-the-repo staging package.

Checks exact normalized base source hashes, reconstructs E22 manifest from
archived evidence, and atomically replaces only two implementation/test files
and one manifest. Never touches historical evidence or formal freeze manifests.
"""
from __future__ import annotations
import argparse, hashlib, json, os
from pathlib import Path
import sys
import importlib.util
_spec=importlib.util.spec_from_file_location("rebuild_frozen_plan", Path(__file__).with_name("rebuild_frozen_plan.py"))
rf=importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(rf)

ORIGINAL = {
 'src/test/kotlin/logic/e3/pack02/SD03E23DevelopmentIntegrationTest.kt':
  '5c0f8074ab294987e8546b0aa8cc857ffd5517d3100d83e5483cfd7bd40cd89e',
 'tools/e222/isolated_full_regression.py':
  'c97eaad9b0f1f998f3da37a676e8fb583ba6d898dc0c7f4d26b9e8e3bd87e594',
 'config/e23-frozen-replay-plan.json':
  'f13ce76e32264e114da0f7f876bfb9e83bdabefdafd0452f5bef50124ae2ef5f',
}

def normalize(raw:bytes)->bytes:
    return raw.decode('utf-8-sig').replace('\r\n','\n').encode('utf-8')

def digest(raw:bytes)->str:
    return hashlib.sha256(raw).hexdigest()

def recover(package:Path,repo:Path,apply:bool):
    if not (repo/'build.gradle.kts').is_file():
        raise ValueError(f'Not the observable-ai repo root: {repo}')
    reconstructed=rf.rendered(rf.reconstruct(repo))
    manifest=package/'config/e23-frozen-replay-plan.json'
    if not manifest.is_file() or manifest.read_bytes()!=reconstructed:
        raise ValueError('Packaged plan disagrees with actual immutable E22 source; abort')
    changes=[]
    for rel, expected in ORIGINAL.items():
        src=package/rel;dst=repo/rel
        if not src.is_file() or not dst.is_file():
            raise ValueError(f'Missing source/destination: {rel}')
        proposed=reconstructed if rel.startswith('config/') else src.read_bytes()
        old=dst.read_bytes()
        if normalize(old)==normalize(proposed):
            changes.append((rel,'ALREADY_PATCHED',proposed,old))
            continue
        if digest(normalize(old))!=expected:
            raise ValueError(f'Unexpected repository changes to {rel}, refusing overwrite')
        changes.append((rel,'PATCH_NEEDED',proposed,old))
    print(json.dumps({'mode':'APPLY' if apply else 'CHECK',
       'files':[{'path':r,'status':s} for r,s,_,_ in changes],
       'replayTasks':8,'frozenQueryLabels':11,
       'originalE22EvidenceTouched':False},ensure_ascii=False,indent=2))
    if apply:
        # Archive source versions outside tracked docs. Never modify E22 evidence.
        backups=repo/'build/e23r-recovery-backups';backups.mkdir(parents=True,exist_ok=True)
        for rel,state,new,old in changes:
            if state=='PATCH_NEEDED':
                backup=backups/(Path(rel).name+'.'+digest(old)[:12]+'.bak')
                if not backup.exists():backup.write_bytes(old)
                path=repo/rel
                temp=path.with_name(path.name+'.e23r-tmp')
                temp.write_bytes(new)
                os.replace(temp,path)
        print('E23R_PATCH_APPLIED: commit the diff before isolated regression')

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo',type=Path,required=True)
    ap.add_argument('--apply',action='store_true')
    args=ap.parse_args()
    package=Path(__file__).resolve().parents[2]
    recover(package,args.repo.resolve(),args.apply)
if __name__=='__main__':
    try: main()
    except (OSError,ValueError,KeyError) as exc:
        print('E23R_PATCH_BLOCKED:',exc,file=sys.stderr)
        raise SystemExit(3)
