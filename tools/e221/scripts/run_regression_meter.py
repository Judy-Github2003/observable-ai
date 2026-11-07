#!/usr/bin/env python3
"""Opt-in Gradle regression timer; never touches historical E2.2 evidence.

Run only after source/test-output preflight. A subprocess timer, not an automation.
Artifacts go under ignored build/e221-regression-logs/.
"""
from __future__ import annotations
import argparse
import datetime as dt
import json
from pathlib import Path
import platform
import subprocess
import sys
import time

from regression_preflight import inspect


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,default=Path('.'))
    parser.add_argument('--mode',choices=('compile','targeted','full'),required=True)
    parser.add_argument('--timeout-seconds',type=int,default=1800)
    args=parser.parse_args()
    repo=args.repo.resolve()
    wrapper=repo/('gradlew.bat' if platform.system()=='Windows' else 'gradlew')
    if not wrapper.exists() or not (repo/'build.gradle.kts').exists():
        print('ERROR: Gradle repository root not found',file=sys.stderr);return 2
    if args.mode=='full':
        issues=inspect(repo)
        if issues:
            for x in issues:print('BLOCK',x['kind'],x['source'],x['existing'])
            print('REFUSED: one-shot research fixtures could collide; make tests write only to isolated temporary output first.',file=sys.stderr)
            return 3
    if args.timeout_seconds<30:
        print('ERROR: timeout too small',file=sys.stderr);return 2
    cmd=[str(wrapper),'--offline','--console=plain','--no-daemon']
    if args.mode=='compile':cmd+=['testClasses']
    if args.mode=='targeted':cmd+=['quickTest','-PquickTests=logic.e3.pack02.SD03E22ContractTest,logic.e3.pack02.SD03E21ContractTest,logic.e3.pack02.SD03E2ContractTest,logic.e3.pack02.SD03EvidenceCompositionE1ContractTest']
    if args.mode=='full':cmd+=['test']
    logdir=repo/'build/e221-regression-logs';logdir.mkdir(parents=True,exist_ok=True)
    timestamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    logfile=logdir/f'{timestamp}-{args.mode}.log'
    before=time.monotonic()
    outcome={'mode':args.mode,'command':cmd,'startedUtc':timestamp,
             'timeoutSeconds':args.timeout_seconds,'platform':platform.platform(),
             'stdoutLog':str(logfile.relative_to(repo))}
    print('RUN',cmd,'\nLOG',logfile,flush=True)
    try:
        with open(logfile,'w',encoding='utf-8',errors='replace') as output:
            done=subprocess.run(cmd,cwd=repo,stdout=output,stderr=subprocess.STDOUT,
                                timeout=args.timeout_seconds,check=False)
        outcome['exitCode']=done.returncode
        outcome['status']='PASS' if done.returncode==0 else 'FAIL'
    except subprocess.TimeoutExpired:
        outcome['exitCode']=None;outcome['status']='TIMEOUT'
    finally:
        outcome['elapsedSeconds']=round(time.monotonic()-before,3)
        resultfile=logdir/f'{timestamp}-{args.mode}.json'
        resultfile.write_text(json.dumps(outcome,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('RESULT',json.dumps(outcome,ensure_ascii=False),flush=True)
    return 0 if outcome['status']=='PASS' else 1

if __name__=='__main__':raise SystemExit(main())
