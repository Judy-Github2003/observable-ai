#!/usr/bin/env python3
"""Create DMRV-01 manifest exactly once; does not run the official examination."""
import argparse,hashlib,json,subprocess,datetime
from pathlib import Path
SOURCES=['src/main/kotlin/logic/e3/dmrv/DMRV01Kernel.kt',
         'src/test/kotlin/logic/e3/dmrv/DMRV01PublicContract.kt',
         'src/test/kotlin/logic/e3/dmrv/DMRV01Examiner.kt']
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--repo',default='.')
    a=parser.parse_args();root=Path(a.repo).resolve()
    target=root/'docs/research/dmrv-01/FREEZE.json'
    if target.exists():raise SystemExit('Refusing to overwrite existing FREEZE.json')
    for f in SOURCES:
        if not (root/f).is_file():raise SystemExit(f'Missing {f}; install package into repository first')
    def git(*args):
        cp=subprocess.run(['git',*args],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        if cp.returncode:raise SystemExit('Git check failed: '+cp.stderr)
        return cp.stdout.strip()
    blob={'schema':'DMRV01-FREEZE-v1','researchPack':'DMRV-01','authoredAlgorithms':'ChatGPT reference; DeepSeek integration only',
          'createdUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'gitHeadBeforeFreeze':git('rev-parse','HEAD'),
          'statusBeforeFreeze':git('status','--porcelain=v1').splitlines(),
          'sources':{f:digest(root/f) for f in SOURCES},
          'officialRun':'NOT_RUN','independentClosedBook':'NOT_RUN','historicalRegression':'NOT_CLAIMED'}
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('x',encoding='utf-8') as fp:fp.write(json.dumps(blob,indent=2,ensure_ascii=False)+'\n')
    print('DMRV01_FREEZE_CREATED',target)
    for k,v in blob['sources'].items():print(k,v)
if __name__=='__main__':main()
