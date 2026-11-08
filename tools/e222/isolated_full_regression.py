#!/usr/bin/env python3
"""Opt-in unfiltered Gradle test in a detached Git worktree; never touch archival evidence.

The sandbox is a separate checkout of committed HEAD. Historical *generated*
research output directories can be cleared ONLY INSIDE THAT CHECKOUT so one-shot
integration tests can rerun. This is not a proof of no external side-effects.
"""
from __future__ import annotations
import argparse,datetime as dt,hashlib,json,os,platform,re,shutil,subprocess,sys,tempfile,time,xml.etree.ElementTree as ET
from pathlib import Path

RESEARCH_OUTPUTS=(
 'docs/research/sd03-r31-development-2026-11-07',
 'docs/research/sd03-r32-development-2026-11-07',
 'docs/research/sd03-e1-development-2026-11-07',
 'docs/research/sd03-e2-development-2026-11-07',
 'docs/research/sd03-e21-development-2026-11-07',
 'docs/research/sd03-e22-development-2026-11-07',
)
# Some earlier developer tests append rather than CREATE_NEW. In sandbox these
# paths are redirected to fresh scratch outputs by clearing only generated
# evidence. Never delete README, preregistration, or other explanatory .md.
GENERATED_SUFFIX={'.jsonl','.log','.xml','.csv'}
GENERATED_NAMES={'E1-RUN-STARTED.txt','E2-RUN-STARTED.txt','E21-RUN-STARTED.txt',
 'E22-RUN-STARTED.txt','FROZEN-BEFORE-GRADING.jsonl','EXAMINER-AFTER-FREEZE.jsonl'}

def git(repo,*args,check=True):
 return subprocess.run(['git','-C',str(repo),*args],check=check,capture_output=True,text=True)

def tracked_sha(repo):
 paths=git(repo,'ls-files','--','docs/research','experiments').stdout.splitlines()
 h=hashlib.sha256()
 for name in paths:
  p=repo/name
  if p.is_file():
   h.update(name.encode('utf-8'));h.update(b'\0');h.update(hashlib.sha256(p.read_bytes()).digest())
 return h.hexdigest(),len(paths)

def prepare(sandbox):
 removed=[]
 for relative in RESEARCH_OUTPUTS:
  d=sandbox/relative
  if not d.is_dir(): continue
  for f in d.rglob('*'):
   if f.is_file() and (f.suffix in GENERATED_SUFFIX or f.name in GENERATED_NAMES):
    removed.append(str(f.relative_to(sandbox)))
    f.unlink()
 return removed

def scan_external_paths(repo):
 # Heuristic warning only; users should confirm no write to fixed external paths.
 issues=[]
 for p in (repo/'src/test').rglob('*.kt'):
  s=p.read_text(encoding='utf-8-sig',errors='replace')
  for needle in ('System.getProperty("user.home")','System.getenv("HOME")',
                 'System.getenv("USERPROFILE")','Path.of("C:\\\\','Paths.get("C:\\\\'):
   if needle in s: issues.append({'file':str(p.relative_to(repo)),'marker':needle})
 return issues

def test_counts(sandbox):
 suites=list((sandbox/'build/test-results/test').rglob('TEST-*.xml'))
 total={'files':len(suites),'tests':0,'failures':0,'errors':0,'skipped':0}
 for f in suites:
  try:
   doc=ET.parse(f).getroot()
   for key in ('tests','failures','errors','skipped'): total[key]+=int(doc.attrib.get(key,0))
  except (ET.ParseError,OSError,ValueError): continue
 return total

def main():
 ap=argparse.ArgumentParser(description=__doc__)
 ap.add_argument('--repo',type=Path,default=Path('.'))
 ap.add_argument('--run-full',action='store_true',help='Explicit authorization to run unfiltered test')
 ap.add_argument('--timeout',type=int,default=1800)
 ap.add_argument('--cleanup-on-success',action='store_true',help='Delete sandbox after output has been manually archived; off by default')
 ns=ap.parse_args()
 repo=ns.repo.resolve()
 if not (repo/'build.gradle.kts').exists() or not (repo/'gradlew').exists() and not (repo/'gradlew.bat').exists():
  raise SystemExit('NOT_A_GRADLE_REPOSITORY')
 if ns.timeout<60: raise SystemExit('timeout must be >= 60 seconds')
 head=git(repo,'rev-parse','HEAD').stdout.strip()
 committed=git(repo,'status','--porcelain','--untracked-files=no').stdout.strip()
 external=scan_external_paths(repo)
 report={'head':head,'sourceHasUncommittedChanges':bool(committed),
   'suspiciousExternalPathIndicators':external,'unfilteredCommand':'gradlew --offline --console=plain --no-daemon test',
   'timeoutSeconds':ns.timeout,'runAuthorized':ns.run_full,
   'platform':platform.platform(),'cpuCount':os.cpu_count(),
   'warning':'Git worktree isolates relative writes, not absolute paths or network side effects.'}
 if not ns.run_full:
  print(json.dumps({**report,'status':'DRY_RUN_NO_EXECUTION'},ensure_ascii=False,indent=2));return 0
 if committed:
  print('REFUSED: commit changes first; detached worktree would not contain current source edits',file=sys.stderr);return 3
 if external:
  print('REFUSED: inspect suspected external-path side effects before running',file=sys.stderr)
  print(json.dumps(report,ensure_ascii=False,indent=2));return 3
 out=repo/'build/e222-isolated-regression';out.mkdir(parents=True,exist_ok=True)
 stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
 log=out/(stamp+'-full.log'); meta=out/(stamp+'-full.json')
 sandbox=Path(tempfile.mkdtemp(prefix='observable-e222-',dir=None))
 before,count=tracked_sha(repo)
 started=time.monotonic();added=False
 try:
  cp=git(repo,'worktree','add','--detach',str(sandbox),head,check=False)
  if cp.returncode!=0: raise RuntimeError('git worktree add failed: '+cp.stderr[-2000:])
  added=True
  removed=prepare(sandbox)
  wrapper=sandbox/('gradlew.bat' if platform.system()=='Windows' else 'gradlew')
  cmd=[str(wrapper),'--offline','--console=plain','--no-daemon','test']
  with log.open('w',encoding='utf-8',errors='replace') as fh:
   try:
    done=subprocess.run(cmd,cwd=sandbox,stdout=fh,stderr=subprocess.STDOUT,timeout=ns.timeout,check=False)
    status='PASS' if done.returncode==0 else 'FAIL'
    code=done.returncode
   except subprocess.TimeoutExpired:
    status='TIMEOUT';code=None
  # Copy raw JUnit evidence OUT of the disposable sandbox before any cleanup.
  src_xml=sandbox/'build/test-results/test'
  stored_xml=out/(stamp+'-junit')
  if src_xml.is_dir(): shutil.copytree(src_xml,stored_xml,dirs_exist_ok=True)
  counts=test_counts(sandbox)
  if status=='PASS' and counts['tests']==0: status='NO_TESTS_REPORTED'
  report.update({'status':status,'exitCode':code,'sanitizedSandboxFiles':removed,
    'testResultCounts':counts,'sandbox':str(sandbox),'log':str(log),
    'junitSnapshot':str(stored_xml) if src_xml.is_dir() else None,
    'elapsedSeconds':round(time.monotonic()-started,3)})
 except Exception as exc:
  report.update({'status':'INFRA_ERROR','error':str(exc),'sandbox':str(sandbox)})
 finally:
  after,countAfter=tracked_sha(repo)
  report['historicalTrackedDigestBefore']=before
  report['historicalTrackedDigestAfter']=after
  report['historicalTrackedFileCount']=count
  report['historicalTrackedUnchanged']=before==after
  meta.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
  print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
  if added and report.get('status')=='PASS' and ns.cleanup_on_success:
   # Sandbox has its own generated evidence; original history remains unchanged.
   clean=git(repo,'worktree','remove','--force',str(sandbox),check=False)
   if clean.returncode==0: shutil.rmtree(sandbox,ignore_errors=True)
 return 0 if report.get('status')=='PASS' and report.get('historicalTrackedUnchanged') else 1
if __name__=='__main__':raise SystemExit(main())
