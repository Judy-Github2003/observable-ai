#!/usr/bin/env python3
"""One-shot, targeted E2.3-V examiner execution. Never invokes full Gradle test."""
import argparse, datetime, hashlib, json, os, pathlib, re, subprocess, sys, time, zipfile
TEST='src/test/kotlin/logic/e3/pack02/SD03E23VFreshExaminerTest.kt'
FREEZE='docs/research/sd03-e23v-2026-11-08/FREEZE.json'

def sha(b):return hashlib.sha256(b).hexdigest()
def verify(repo,archive):
    freeze=json.loads((repo/FREEZE).read_text('utf-8'))
    assert freeze['schema']=='E23V-FREEZE-v1'
    assert freeze['examinerArchiveSha256']==sha(archive.read_bytes()),'Examiner archive changed after preregistration'
    for path,expected in freeze['sourceSha256'].items():
        assert sha((repo/path).read_bytes())==expected,f'learner source drift: {path}'
    with zipfile.ZipFile(archive) as z:
        assert TEST in z.namelist(),'examiner Kotlin file missing'
        contents=z.read(TEST)
    if (repo/TEST).exists():
        assert (repo/TEST).read_bytes()==contents, 'examiner source already exists but differs'
    return contents

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo',type=pathlib.Path,default=pathlib.Path('.'))
    ap.add_argument('--examiner-archive',type=pathlib.Path,required=True)
    ap.add_argument('--execute',action='store_true',help='one authorized grading run')
    ap.add_argument('--timeout',type=int,default=300)
    ns=ap.parse_args()
    repo=ns.repo.resolve();archive=ns.examiner_archive.resolve()
    contents=verify(repo,archive)
    print('E23V_PREFLIGHT_PASS frozen learner byte hashes; examiner archive matches')
    if not ns.execute:return
    assert 60<=ns.timeout<=900,'bounded targeted execution only'
    marker=repo/'docs/research/sd03-e23v-2026-11-08/E23V-RUN-STARTED.json'
    marker.parent.mkdir(parents=True,exist_ok=True)
    fd=os.open(marker,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o644)
    with os.fdopen(fd,'w',encoding='utf-8') as f:
        json.dump({'status':'STARTED','examinerArchiveSha256':sha(archive.read_bytes()),
                   'freezeSha256':sha((repo/FREEZE).read_bytes()),
                   'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()},f,indent=2)
    target=repo/TEST;target.parent.mkdir(parents=True,exist_ok=True)
    if not target.exists():target.write_bytes(contents)
    assert target.read_bytes()==contents
    wrapper=repo/('gradlew.bat' if os.name=='nt' else 'gradlew')
    assert wrapper.is_file()
    command=[str(wrapper),'--offline','--no-daemon','--console=plain','quickTest',
             '-PquickTests=logic.e3.pack02.SD03E23VFreshExaminerTest']
    logs=repo/'build/e23v-run-logs';logs.mkdir(parents=True,exist_ok=True)
    log=logs/'first-run.log'
    started=time.monotonic();status='ERROR';code=None
    print('E23V_EXECUTE_TARGETED',command,flush=True)
    try:
        with log.open('x',encoding='utf-8',errors='replace') as f:
            r=subprocess.run(command,cwd=repo,stdout=f,stderr=subprocess.STDOUT,timeout=ns.timeout)
        code=r.returncode;status='PASS' if code==0 else 'FAIL'
    except subprocess.TimeoutExpired:status='TIMEOUT'
    finally:
        seconds=round(time.monotonic()-started,3)
        raw=log.read_text('utf-8',errors='replace') if log.exists() else ''
        match=re.findall(r'E23V_OUTPUT_DIR=(.+)',raw)
        outdir=match[-1].strip() if match else None
        data={'status':status,'exitCode':code,'elapsedSeconds':seconds,'outputDirectory':outdir,
              'studentCoreFrozen':True,'fullRegression':'NOT_RUN',
              'JUnit':str(repo/'build/test-results/quickTest'),
              'log':str(log),'warning':'Do not rerun or tune learner after seeing examiner results'}
        (logs/'first-run.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print('E23V_RESULT',json.dumps(data,ensure_ascii=False),flush=True)
    if status!='PASS':sys.exit(2)
    if not outdir:sys.exit('MISSING_E23V_OUTPUT_DIR; do not claim research result')
    from audit_e23v import audit
    result=audit(outdir)
    (logs/'audit-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('E23V_RAW_AUDIT_PASS',flush=True)
if __name__=='__main__':main()
