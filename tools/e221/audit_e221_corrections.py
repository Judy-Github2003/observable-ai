#!/usr/bin/env python3
"""E2.2.1 forensic recomputation from immutable E2.2 JSONL.

Not a truth-oracle replay. No student program, predictions, or original evidence
is changed. Initial-program counterexamples are examiner-side post-freeze labels;
current-program counterexamples are query-time revelations. NEVER conflate.
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path

ARMS = ("STATIC", "RANDOM_FIXED_4", "E2_LEGACY", "E21_STRICT", "E22_CONJECTURE")

def check(test, message):
    if not test: raise AssertionError(message)

def load(path: Path):
    check(path.is_file(), f"missing {path}")
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]

def analyse(root: Path):
    exam = load(root / "EXAMINER-OPPORTUNITY-AFTER-FREEZE.jsonl")
    scores = load(root / "EXAMINER-SCORES-AFTER-FREEZE.jsonl")
    files = sorted(root.glob("STUDENT-EVENTS-*.jsonl"))
    check(len(files) == 8 and len(exam) == 8 and len(scores) == 40,
          "expected original 8 tasks, 8 examiner opportunities, 40 scores; no partial pass")
    opportunities = {x['task']: x for x in exam}
    check(len(opportunities) == 8, "duplicated examiner task")
    all_scores = defaultdict(dict)
    for score in scores:
        task, arm = score['task'], score['arm']
        check(task in opportunities and arm in ARMS, "unrecognized score task or arm")
        check(arm not in all_scores[task], "duplicate score")
        all_scores[task][arm] = score
    check(all(set(x) == set(ARMS) for x in all_scores.values()), "incomplete grade arms")
    rows=[]
    selected_initial=defaultdict(list)
    selected_current=defaultdict(list)
    change_counts=defaultdict(int)
    confirm_changes=defaultdict(int)
    matrix_counts=defaultdict(int)
    total_reveals=defaultdict(int)
    seen_tokens=set()
    for f in files:
        events=load(f)
        final={e['arm']: e for e in events if e['phase']=="FINAL_STUDENT_FROZEN"}
        check(set(final)==set(ARMS),f"invalid final arms in {f.name}")
        token=next(iter(final.values()))['task']
        check(token not in seen_tokens and token in opportunities, "missing or repeated task token")
        seen_tokens.add(token)
        initial=set(opportunities[token]['initialCounterexampleIds'])
        check(len(initial)==opportunities[token]['initialCounterexampleCount'], "bad initial opportunity")
        initial_hash={e['initialProgramHash'] for e in final.values()}
        check(len(initial_hash)==1,"arms don't share initial proposal")
        matrix=defaultdict(dict)
        reveals=defaultdict(dict)
        for e in events:
            phase=e['phase']
            if phase=='FULL_MATRIX_FROZEN':
                p=e['payload']; arm=p['arm']; step=p['step']
                check((arm in ARMS and arm!='STATIC' and step not in matrix[arm]), 'duplicate matrix')
                check(len(p['allQueryRows'])==12, 'expected 12 QUERY rows')
                check({r['id'] for r in p['allQueryRows']}=={f'q-{i}' for i in range(12)}, 'bad row ids')
                matrix[arm][step]=p
                matrix_counts[arm]+=1
            elif phase=='QUERY_LABEL_REVEALED':
                arm=e['arm'];step=e['step']
                check(step not in reveals[arm], 'duplicate reveal')
                check(e['oldPrediction'] is not None, 'Unknown prediction cannot be counted as correct/incorrect')
                check(e['counterexampleOfCurrent'] == (e['oldPrediction'] != e['label']),
                      'current counterexample flag contradicts revealed label')
                reveals[arm][step]=e
                total_reveals[arm]+=1
        for arm in ARMS:
            qs=final[arm]['queryIds']
            check(len(qs)==len(reveals[arm]) and set(qs)=={x['queryId'] for x in reveals[arm].values()}, 'queries mismatch')
            check(final[arm]['publicLabels']==32+len(qs), 'public evidence count mismatch')
            check(len(matrix[arm])>=len(reveals[arm]), 'missing query matrix')
            sorted_steps=sorted(reveals[arm]);check(sorted_steps==list(range(len(qs))), 'nonconsecutive reveals')
            for step in sorted_steps:
                v=reveals[arm][step];p=matrix[arm][step]
                check(p['selectedQuery']==v['queryId'], 'unmatched decision and reveal')
                row=next(r for r in p['allQueryRows'] if r['id']==v['queryId'])
                check(row['currentPrediction']==v['oldPrediction'], 'matrix/reveal current prediction mismatch')
                if v['queryId'] in initial: selected_initial[arm].append((token,v['queryId']))
                if v['counterexampleOfCurrent']:selected_current[arm].append((token,v['queryId']))
                next_hash = matrix[arm].get(step+1,{}).get('programHash',final[arm]['finalProgramHash'])
                before_hash=p['programHash']
                if next_hash != before_hash:
                    change_counts[arm]+=1
                    if not v['counterexampleOfCurrent']:confirm_changes[arm]+=1
            rows.append({
                'task': token, 'arm': arm, 'queryIds': qs,
                'initialCounterexampleIds': sorted(initial),
                'initialCounterexampleHits': [qid for qid in qs if qid in initial],
                'currentCounterexampleHits': [reveals[arm][i]['queryId'] for i in sorted_steps
                                               if reveals[arm][i]['counterexampleOfCurrent']],
                'programChanges': sum(matrix[arm].get(i+1,{}).get('programHash',final[arm]['finalProgramHash'])
                                      != matrix[arm][i]['programHash'] for i in sorted_steps),
            })
    check(seen_tokens==set(opportunities), 'task mismatch between events and examiner')
    summary={}
    for arm in ARMS:
        gs=[all_scores[token][arm] for token in sorted(all_scores)]
        passed=[g for g in gs if g['challengeStatus']=='PASS_ON_CHALLENGE']
        check(all(g['heldoutCorrect'] is None for g in gs if g not in passed), 'failed challenge has heldout')
        check(all(g['heldoutCorrect'] is not None for g in passed), 'passed challenge missing heldout')
        summary[arm]={
            'challengePassedTasks':len(passed),'totalTasks':len(gs),
            'heldoutCorrectAmongPass':sum(g['heldoutCorrect'] for g in passed),
            'heldoutTotalAmongPass':sum(g['heldoutTotal'] for g in passed),
            'queriesRevealed':total_reveals[arm],
            'initialCounterexampleHits':len(selected_initial[arm]),
            'currentCounterexampleHits':len(selected_current[arm]),
            'programChangesAfterReveal':change_counts[arm],
            'programChangesAfterConfirmingEvidence':confirm_changes[arm],
            'fullMatrixSnapshots':matrix_counts[arm]
        }
    shared_pass = [token for token in all_scores if
        all(all_scores[token][arm]['challengeStatus']=='PASS_ON_CHALLENGE' for arm in ('STATIC','E22_CONJECTURE'))]
    common={arm: {'correct':sum(all_scores[t][arm]['heldoutCorrect'] for t in shared_pass),
                  'total':sum(all_scores[t][arm]['heldoutTotal'] for t in shared_pass)} for arm in ('STATIC','E22_CONJECTURE')}
    result={'disclaimer':'Historical E2.2 record reaggregation only; NOT a fresh experiment or independent truth replay.',
            'tasks':len(seen_tokens), 'totalInitialCounterexamples':sum(o['initialCounterexampleCount'] for o in exam),
            'arms':summary,
            'initialHitsByArm':{arm:[{'task':t,'queryId':q} for t,q in selected_initial[arm]] for arm in ARMS},
            'currentHitsByArm':{arm:[{'task':t,'queryId':q} for t,q in selected_current[arm]] for arm in ARMS},
            'pairedCommonPassedTasks':len(shared_pass),'pairedHeldout':common,
            'taskArms':rows}
    check(result['totalInitialCounterexamples']==5,'expected five original opportunities')
    check(summary['E22_CONJECTURE']['heldoutTotalAmongPass']==105 and summary['STATIC']['heldoutTotalAmongPass']==126,
          'recomputed denom unexpectedly changed')
    return result

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('results_dir',type=Path)
    ap.add_argument('--output',type=Path)
    args=ap.parse_args()
    result=analyse(args.results_dir)
    data=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(data,encoding='utf-8')
    print('E221 HISTORICAL CORRECTION AUDIT PASS (not independent replay)')
    for a,v in result['arms'].items():
        print(f'{a}: query={v["queriesRevealed"]}, initialHits={v["initialCounterexampleHits"]}, currentHits={v["currentCounterexampleHits"]}, pass={v["challengePassedTasks"]}/8, HELDOUT={v["heldoutCorrectAmongPass"]}/{v["heldoutTotalAmongPass"]}, confirmRebuilds={v["programChangesAfterConfirmingEvidence"]}')
    print('E22 initial:',result['initialHitsByArm']['E22_CONJECTURE'])
    print('E22 current:',result['currentHitsByArm']['E22_CONJECTURE'])
    return 0

if __name__=='__main__':
    try:raise SystemExit(main())
    except (AssertionError,ValueError,KeyError,TypeError) as e:
        print('E221 AUDIT FAIL:',e)
        raise SystemExit(1)
