#!/usr/bin/env python3
"""GNJV01 Phase-A student source freeze. NO examiner code or task answers needed.
 Run AFTER source/test integration and before revealing Phase-B ZIP.
 Does not execute any scientific exam or modify historical freeze files.
"""
import argparse, hashlib, json, subprocess
from datetime import datetime, timezone
from pathlib import Path

SOURCE = 'src/main/kotlin/logic/e3/gnjv/GNJV01Kernel.kt'
PUBLIC_TEST = 'src/test/kotlin/logic/e3/gnjv/GNJV01PublicContractTest.kt'

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--repo',required=True)
    ap.add_argument('--examiner-sha256',required=True)
    ap.add_argument('--out',default='docs/research/gnjv-01/FREEZE.json')
    args=ap.parse_args()
    repo=Path(args.repo).resolve()
    assert len(args.examiner_sha256)==64 and all(x in '0123456789abcdef' for x in args.examiner_sha256.lower())
    sources={}
    for relative in [SOURCE,PUBLIC_TEST]:
        file=repo/relative
        assert file.exists(),f'Missing frozen input: {file}'
        sources[relative]=hashlib.sha256(file.read_bytes()).hexdigest()
    def git(*argv):
        return subprocess.check_output(['git','-C',str(repo),*argv],text=True).strip()
    head=git('rev-parse','HEAD')
    status=git('status','--porcelain')
    out=repo/args.out
    out.parent.mkdir(parents=True,exist_ok=True)
    assert not out.exists(),f'Freeze exists, cannot overwrite: {out}'
    manifest={
        'protocol':'GNJV01-PHASE-A-v1',
        'researchPackage':'GNJV-01',
        'createdUtc':datetime.now(timezone.utc).isoformat(),
        'gitHeadBeforeFreezeCommit':head,
        'workingTreeStatusBeforeFreeze':status.splitlines(),
        'examinerZipSha256':args.examiner_sha256.lower(),
        'sources':sources,
        'independentClosedBook':'NOT_RUN',
        'historicalFullRegression':'NOT_CLAIMED',
        'scientificExam':'NOT_RUN',
    }
    out.write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(f'GNJV01_FREEZE_CREATED path={out} gitHead={head} studentSha256={sources[SOURCE]}')
    print('Commit FREEZE.json and both source files; hand back freeze COMMIT before Phase B reveal.')

if __name__=='__main__': main()
