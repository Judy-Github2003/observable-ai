#!/usr/bin/env python3
"""Read-only structural audit of E2.3 historical-query replay. No oracle replay."""
from __future__ import annotations
import argparse,collections,hashlib,json,pathlib,sys
ARMS={'STATIC','FULL_REFIT','KEEP_IF_AGREE','LOCAL_FIRST'}
def fail(condition,msg):
 if not condition: raise AssertionError(msg)
def rows(p):
 fail(p.exists(),f'missing {p}')
 return [json.loads(x) for x in p.read_text(encoding='utf-8-sig').splitlines() if x.strip()]
def audit(root,plan):
 plans=json.loads(plan.read_text(encoding='utf-8'))['tasks']
 token_specs={x['taskToken']:x for x in plans}
 summaries=[]
 for path in sorted(root.glob('STUDENT-*.jsonl')):
  events=rows(path); token=path.stem.removeprefix('STUDENT-')
  fail(token in token_specs,f'unrecognized task {token}')
  stages=collections.defaultdict(list)
  seen=collections.defaultdict(list)
  for event in events:
   arm=event.get('arm')
   fail(arm in ARMS,f'unknown arm {arm}')
   phase=event['phase']
   if phase=='QUERY_DECISION_FROZEN':
    fail(not stages[arm] or stages[arm][-1]=='UPDATE_FROZEN',f'overlapping decisions {arm}')
   if phase=='LABEL_REVEALED':
    fail(stages[arm] and stages[arm][-1]=='QUERY_DECISION_FROZEN',f'reveal before freeze {arm}')
    seen[arm].append(event['queryId'])
   if phase=='UPDATE_FROZEN':fail(stages[arm] and stages[arm][-1]=='LABEL_REVEALED',f'update without label {arm}')
   stages[arm].append(phase)
  frozen={e['arm']:e for e in events if e['phase']=='FINAL_STUDENT_FROZEN'}
  fail(set(frozen)==ARMS,f'missing or repeated final arm in {token}')
  for a in ARMS:
   fail(frozen[a]['queryIds']==seen[a],f'query ID mismatch {token} {a}')
   if a=='STATIC':fail(not seen[a],f'STATIC queried {token}')
   else:fail(seen[a]==token_specs[token]['queryIds'],f'frozen replay mismatch {token} {a}')
  fail(frozen['FULL_REFIT']['finalHash']==token_specs[token]['expectedE22FullRefitHash'],f'historical baseline drift {token}')
  summaries.append({'task':token,'queries':len(seen['FULL_REFIT']),
   'fullHash':frozen['FULL_REFIT']['finalHash']})
 fail(set(x['task'] for x in summaries)==set(token_specs), 'missing replay task')
 graded=rows(root/'EXAMINER-AFTER-ALL-FREEZE.jsonl')
 for token in token_specs:
  group=[g for g in graded if g['task']==token]
  fail(len(group)==4 and {g['arm'] for g in group}==ARMS,f'grading mismatch {token}')
  for g in group:
   fail(g['heldoutCorrect'] is None or g['challengeStatus']=='PASS_ON_CHALLENGE','graded heldout without pass')
 print('E23_REPLAY_STRUCTURAL_AUDIT_PASS')
 print(json.dumps({'tasks':len(summaries),'arms':len(graded),'replayedQueriesPerPolicy':sum(x['queries'] for x in summaries),'summaries':summaries},ensure_ascii=False,indent=2))
def main():
 p=argparse.ArgumentParser();p.add_argument('output_dir',type=pathlib.Path);p.add_argument('--plan',type=pathlib.Path,default=pathlib.Path('config/e23-frozen-replay-plan.json'))
 a=p.parse_args();audit(a.output_dir,a.plan)
if __name__=='__main__':
 try: main()
 except (AssertionError,ValueError,KeyError,TypeError) as e:print('E23_REPLAY_STRUCTURAL_AUDIT_FAIL:',e,file=sys.stderr);raise SystemExit(1)
