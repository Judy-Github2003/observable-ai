#!/usr/bin/env python3
"""Opt-in unfiltered Gradle test in a detached Git worktree; never touch archival evidence.

The sandbox is a separate checkout of committed HEAD. Historical *generated*
research output directories can be cleared ONLY INSIDE THAT CHECKOUT so one-shot
integration tests can rerun. This is not a proof of no external side-effects.
"""
from __future__ import annotations
import argparse,datetime as dt,hashlib,json,os,platform,re,shutil,subprocess,sys,tempfile,time,xml.etree.ElementTree as ET
from pathlib import Path
from frozen_hash_preflight import audit_freezes

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
# E23-R repair: E1 emits many one-shot FROZEN-*.json files via CREATE_NEW.
# Delete ONLY these exact-name generated files INSIDE the detached worktree.
# Never broadly delete *.json: preregistrations, source manifests and reports
# can be JSON and may be needed by historical audit tests.
E1_ONESHOT_DIR='docs/research/sd03-e1-development-2026-11-07'

def is_sandbox_generated(relative: str, name: str, suffix: str) -> bool:
    if suffix in GENERATED_SUFFIX or name in GENERATED_NAMES:
        return True
    return (relative.startswith(E1_ONESHOT_DIR + '/') and
            name.startswith('FROZEN-') and name.endswith('.json'))

def git(repo,*args,check=True):
 return subprocess.run(['git','-C',str(repo),*args],check=check,capture_output=True,text=True)

def git_worktree_checkout_lf(repo, *args, check=True):
 # Scoped to the new checkout process; do not change the host/global Git config.
 return subprocess.run(['git','-c','core.autocrlf=false','-c','core.eol=lf',
                        '-C',str(repo),*args],
                       check=check,capture_output=True,text=True)

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
   if f.is_file() and is_sandbox_generated(
       f.relative_to(sandbox).as_posix(), f.name, f.suffix):
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


# E2.3R mixed-EOL archival recovery: the 2026-11-06 MRXR historical freeze
# recorded CRLF bytes for exactly two files, while the Git blobs are LF.
# This allowlist is not a general-purpose EOL conversion policy.
ARCHIVAL_CRLF_ALLOWLIST = frozenset({
    'src/main/kotlin/logic/e3/pack02/MRXRSpace.kt',
    'src/main/kotlin/logic/e3/pack02/MRXRStudent.kt',
})
MRXR_FREEZE_MANIFEST = 'experiments/representation-evolution-e3/reports/mrx-01r-freeze.json'


def restore_mixed_eol(sandbox: Path, before: dict) -> tuple[dict, list[dict]]:
    """Fail closed; restore only two archived CRLF byte representations.

    A mismatch is eligible only if a CRLF-only transformation of the actual
    LF Git checkout exactly matches the *original* manifest SHA-256. We never
    adjust a manifest or alter a matching source. Caller must abort on error.
    """
    if before.get('sourceCount') != 11 or before.get('manifestCount') != 3:
        raise ValueError('FROZEN_MANIFEST_STRUCTURE_INVALID')
    bad = [x for x in before['sources'] if not x['matched']]
    if not bad:
        if before['status'] != 'PASS':
            raise ValueError('FROZEN_SOURCE_PREFLIGHT_FAILED_WITHOUT_MISMATCHES')
        return before, []
    if before.get('status') != 'FAIL':
        raise ValueError('FROZEN_SOURCE_AUDIT_INCONSISTENT')
    for row in bad:
        if (row['path'] not in ARCHIVAL_CRLF_ALLOWLIST or
                row['manifest'] != MRXR_FREEZE_MANIFEST):
            raise ValueError('UNAUTHORIZED_FROZEN_SOURCE_MISMATCH: ' + row['path'])
    if len(bad) > 2:
        raise ValueError('UNEXPECTED_FROZEN_SOURCE_MISMATCH_COUNT')

    # Validate ALL candidates BEFORE modifying either file in the sandbox.
    prepared: list[tuple[Path, bytes, dict, str, str]] = []
    for row in bad:
        rel = row['path']
        target = (sandbox / rel).resolve()
        try:
            target.relative_to(sandbox.resolve())
        except ValueError as exc:
            raise ValueError('ARCHIVE_SOURCE_OUTSIDE_WORKTREE') from exc
        raw = target.read_bytes()
        initial_sha = hashlib.sha256(raw).hexdigest()
        if initial_sha != row['actualSha256']:
            raise ValueError('SOURCE_CHANGED_DURING_PREFLIGHT: ' + rel)
        if b'\r' in raw or b'\n' not in raw:
            raise ValueError('NOT_LF_ONLY_SOURCE: ' + rel)
        crlf = raw.replace(b'\n', b'\r\n')
        new_sha = hashlib.sha256(crlf).hexdigest()
        if new_sha != row['expectedSha256']:
            raise ValueError('CRLF_NOT_EQUAL_TO_FROZEN_BYTES: ' + rel)
        prepared.append((target, crlf, row, initial_sha, new_sha))

    changes = []
    for target, crlf, row, initial_sha, new_sha in prepared:
        # Isolated worktree ONLY. The user's original repository is untouched.
        target.write_bytes(crlf)
        changes.append({'path': row['path'], 'fromSha256': initial_sha,
                        'toSha256': new_sha, 'method': 'LF_TO_CRLF_IN_SANDBOX_ONLY'})

    after = audit_freezes(sandbox)
    if after['status'] != 'PASS' or after['matchedSources'] != 11:
        raise ValueError('POST_RESTORATION_FREEZE_AUDIT_FAILED')
    if set(x['path'] for x in changes) - ARCHIVAL_CRLF_ALLOWLIST:
        raise ValueError('CHANGED_PATH_NOT_ALLOWLISTED')
    return after, changes

def main():
 ap=argparse.ArgumentParser(description=__doc__)
 ap.add_argument('--repo',type=Path,default=Path('.'))
 action=ap.add_mutually_exclusive_group()
 action.add_argument('--run-full',action='store_true',help='Explicit authorization to run unfiltered test')
 action.add_argument('--freeze-only',action='store_true',help='Make a LF checkout and check 3 manifests/11 source hashes, no Gradle')
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
   'freezeOnly':ns.freeze_only,'checkoutCoreAutocrlf':'false','checkoutCoreEol':'lf',
   'platform':platform.platform(),'cpuCount':os.cpu_count(),
   'warning':'Git worktree isolates relative writes, not absolute paths or network side effects.'}
 if not (ns.run_full or ns.freeze_only):
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
  cp=git_worktree_checkout_lf(repo,'worktree','add','--detach',str(sandbox),head,check=False)
  if cp.returncode!=0: raise RuntimeError('git worktree add failed: '+cp.stderr[-2000:])
  added=True
  freeze_before=audit_freezes(sandbox)
  report['freezeSourcePreflightBeforeRestoration']=freeze_before
  try:
   freeze, restorable = restore_mixed_eol(sandbox, freeze_before)
   report['restoredArchivalCRLF']=restorable
  except (ValueError, OSError, KeyError) as exc:
   freeze=freeze_before
   report['restoredArchivalCRLF']=[]
   report['restorationError']=str(exc)
  report['freezeSourcePreflight']=freeze
  if freeze['status'] != 'PASS' or report.get('restorationError'):
   report.update({'status':'FROZEN_SOURCE_MISMATCH','exitCode':None,
                  'sandbox':str(sandbox),'gradleStarted':False})
  elif ns.freeze_only:
   report.update({'status':'FROZEN_PREFLIGHT_PASS','exitCode':0,
                  'sandbox':str(sandbox),'gradleStarted':False})
  else:
   report['gradleStarted']=True
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
   if status=='PASS' and (counts['failures'] or counts['errors']): status='JUNIT_FAILURES'
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
 return 0 if report.get('status') in {'PASS','FROZEN_PREFLIGHT_PASS'} and report.get('historicalTrackedUnchanged') else 1
if __name__=='__main__':raise SystemExit(main())
