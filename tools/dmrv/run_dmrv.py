#!/usr/bin/env python3
"""Run DMRV-01 one-shot official or smoke development using available kotlinc and Java.
Does not modify historical B0/MRX/SD-03 tests. Does not hide examiner from developer.
"""
import argparse,hashlib,json,os,secrets,shutil,subprocess,sys,datetime
from pathlib import Path
SOURCES=['src/main/kotlin/logic/e3/dmrv/DMRV01Kernel.kt',
         'src/test/kotlin/logic/e3/dmrv/DMRV01PublicContract.kt',
         'src/test/kotlin/logic/e3/dmrv/DMRV01Examiner.kt']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--repo',default='.')
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--official',action='store_true');mode.add_argument('--development',action='store_true')
    parser.add_argument('--output',required=True)
    a=parser.parse_args();root=Path(a.repo).resolve();out=Path(a.output).resolve()
    if out.exists():raise SystemExit('Output folder must not exist; never overwrite previous results')
    for f in SOURCES:
        if not (root/f).is_file():raise SystemExit('Missing source '+f)
    freeze=root/'docs/research/dmrv-01/FREEZE.json'
    if not freeze.exists():raise SystemExit('Run freeze_dmrv.py first')
    metadata=json.loads(freeze.read_text('utf-8'))
    if metadata.get('schema')!='DMRV01-FREEZE-v1':raise SystemExit('Invalid freeze manifest')
    for f,expected in metadata['sources'].items():
        if sha(root/f)!=expected:raise SystemExit('Freeze source hash mismatch: '+f)
    java=shutil.which('java');kotlinc=shutil.which('kotlinc')
    if not java or not kotlinc:raise SystemExit('Missing java or kotlinc in PATH. Do not bypass freeze: use equivalent Kotlin compiler with documented script adjustment.')
    build=root/'build/dmrv-01-runner'
    build.mkdir(parents=True,exist_ok=True)
    jar=build/'dmrv01-examiner.jar'
    cp=subprocess.run([kotlinc,str(root/SOURCES[0]),str(root/SOURCES[2]),'-include-runtime','-d',str(jar)],
                      text=True,capture_output=True,timeout=180)
    if cp.returncode:raise SystemExit('Kotlin compile failed:\n'+cp.stdout+'\n'+cp.stderr)
    out.parent.mkdir(parents=True,exist_ok=True)
    if a.official:
        guard=root/'build/dmrv-01-official-one-shot-intent.json'
        intent={'mode':'official','atUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'output':str(out),'freezeSha256':sha(freeze),'jarSha256':sha(jar)}
        try:
            with guard.open('x',encoding='utf-8') as g:json.dump(intent,g,indent=2)
        except FileExistsError:raise SystemExit('Official one-shot attempt already registered; stop and report, no rerun')
    mode='official' if a.official else 'development'
    salt=secrets.randbits(62)
    command=[java,'-cp',str(jar),'logic.e3.dmrv.DMRV01Examiner',str(out),str(salt),mode]
    try: run=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,timeout=180)
    except subprocess.TimeoutExpired:raise SystemExit('Examiner timed out. Do not rerun official without researcher authorization.')
    if not out.exists():raise SystemExit('Examiner failed before output creation. Exit='+str(run.returncode)+'\n'+run.stderr)
    (out/'RUN-STDOUT.log').write_text(run.stdout,'utf-8')
    (out/'RUN-STDERR.log').write_text(run.stderr,'utf-8')
    (out/'FREEZE-COPY.json').write_bytes(freeze.read_bytes())
    (out/'RUN.json').write_text(json.dumps({'mode':mode,'exitCode':run.returncode,'salt':salt,
        'freezeSha256':sha(freeze),'examinerJarSha256':sha(jar),
        'studentSha256':sha(out/'STUDENTS-FROZEN.jsonl') if (out/'STUDENTS-FROZEN.jsonl').exists() else None,
        'sourceSha256After':{f:sha(root/f) for f in SOURCES},
        'utcFinished':datetime.datetime.now(datetime.timezone.utc).isoformat()},ensure_ascii=False,indent=2)+'\n','utf-8')
    if run.returncode:raise SystemExit('Examiner nonzero exit '+str(run.returncode)+'; do not rerun official')
    for f,d in metadata['sources'].items():
        if sha(root/f)!=d:raise SystemExit('Source changed during run: '+f)
    print(run.stdout.strip());print('DMRV01_RUN_FINISHED',out)
if __name__=='__main__':main()
