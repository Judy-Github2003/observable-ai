#!/usr/bin/env python3
"""Independent structural/statistical audit of an E2.3-V examiner output directory.
This checks log shape and arithmetic, not truth authenticity or true independence.
"""
import argparse, collections, json, pathlib, sys
ARMS=("STATIC","FULL_REFIT","KEEP_IF_AGREE","LOCAL_FIRST")

def load_jsonl(path):
    return [json.loads(line) for line in path.read_text('utf-8').splitlines() if line.strip()]

def audit(directory):
    directory=pathlib.Path(directory)
    student_files=sorted(directory.glob('STUDENT-*.jsonl'))
    assert len(student_files)==32, f'expected 32 tasks, got {len(student_files)}'
    assert (directory/'ALL_STUDENTS_FROZEN.txt').is_file(), 'all-frozen barrier missing'
    gradepath=directory/'EXAMINER-AFTER-ALL-FREEZE.jsonl'
    assert gradepath.is_file(), 'examiner file missing'
    grades=load_jsonl(gradepath)
    assert len(grades)==128, f'expected 128 grades, got {len(grades)}'
    lookup={(r['task'],r['arm']):r for r in grades}
    assert len(lookup)==128, 'duplicate arm grades'
    summary={a:{'tasks':0,'passed':0,'eligible':0,'heldoutCorrect':0,'heldoutTotalOnPass':0,'queries':0,
                 'fullRefitCalls':0,'localRepairs':0,'programChanges':0,'lostCorrectChallenge':0,
                 'gainedCorrectChallenge':0,'atomInterpreterCalls':0,'wallMillis':0} for a in ARMS}
    paired=[]
    for file in student_files:
        e=load_jsonl(file)
        assert e[0]['phase']=='TASK_FROZEN'
        token=e[0]['task']
        assert file.name==f'STUDENT-{token}.jsonl'
        freezes=[x for x in e if x['phase']=='FINAL_STUDENT_FROZEN']
        assert len(freezes)==4 and {x['arm'] for x in freezes}==set(ARMS)
        starts=[x for x in e if x['phase']=='ARM_PREPARED']
        assert len(starts)==4
        schedule=e[0]['plannedQueryIds']
        assert len(schedule)==4 and len(set(schedule))==4
        assert sorted(schedule)==sorted(starts[0]['plannedIds'])
        assert len({tuple(x['plannedIds']) for x in starts})==1
        key={}
        for arm in ARMS:
            final=next(x for x in freezes if x['arm']==arm)
            assert final['plannedIds']==schedule
            reveal=[x for x in e if x['phase']=='LABEL_REVEALED' and x['arm']==arm]
            frozen=[x for x in e if x['phase']=='QUERY_DECISION_FROZEN' and x['arm']==arm]
            updates=[x for x in e if x['phase']=='UPDATE_FROZEN' and x['arm']==arm]
            assert len(reveal)==len(frozen)==len(updates)==len(final['revealedIds'])
            assert [x['queryId'] for x in reveal]==final['revealedIds']
            if arm=='STATIC':assert not reveal and not updates
            elif final['startCertified']:
                assert final['revealedIds']==schedule, 'matched label schedule drift'
            else:
                assert not reveal, 'invalid initial program still revealed labels'
            g=lookup[(token,arm)]
            assert g['revealedIds']==final['revealedIds']
            assert g['plannedIds']==schedule
            assert g['queryCount']==len(reveal)
            assert g['heldoutTotal']==21
            if g['challengeStatus']=='PASS_ON_CHALLENGE':
                assert g['eligible'] and isinstance(g['heldoutCorrect'],int)
                assert 0<=g['heldoutCorrect']<=21
            else:
                assert g['heldoutCorrect'] is None
            v=summary[arm];v['tasks']+=1
            v['passed']+=(g['challengeStatus']=='PASS_ON_CHALLENGE')
            v['eligible']+=bool(g['eligible'])
            if g['heldoutCorrect'] is not None:
                v['heldoutCorrect']+=g['heldoutCorrect'];v['heldoutTotalOnPass']+=21
            for k,src in [('queries','queryCount'),('fullRefitCalls','fullRefitCalls'),
                          ('localRepairs','localRepairs'),('programChanges','programChanges'),
                          ('atomInterpreterCalls','atomInterpreterCalls'),('wallMillis','wallMillis')]:
                v[k]+=g[src]
            for keyname,src in (('lostCorrectChallenge','lostPreviouslyCorrectChallenge'),('gainedCorrectChallenge','gainedCorrectChallenge')):
                if g.get(src) is not None:v[keyname]+=g[src]
            key[arm]=g['challengeStatus']=='PASS_ON_CHALLENGE'
        paired.append({'task':token,'static':key['STATIC'],'full':key['FULL_REFIT'],
                       'keep':key['KEEP_IF_AGREE'],'local':key['LOCAL_FIRST']})
    assert all(x['tasks']==32 for x in summary.values())
    assert sum(x['queries'] for x in summary.values())<=384
    comparisons={}
    for arm,col in [('KEEP_IF_AGREE','keep'),('LOCAL_FIRST','local')]:
        comparisons[arm]={'winOverFull':sum(x[col] and not x['full'] for x in paired),
                          'loseToFull':sum(x['full'] and not x[col] for x in paired),
                          'samePass':sum(x['full'] and x[col] for x in paired),
                          'sameFail':sum(not x['full'] and not x[col] for x in paired)}
    return {'status':'E23V_RAW_AUDIT_PASS','tasks':32,'studentArms':128,'gradeRows':128,
            'queryBudgetPerEligibleTask':4,'independentClosedBook':'NOT_CLAIMED',
            'arms':summary,'pairedVsFullRefit':comparisons,
            'disclaimer':'Frozen code, new examiner families; no after-test tuning. Data consistency, not independent truth audit.'}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('output_dir');ap.add_argument('--save')
    ns=ap.parse_args()
    result=audit(ns.output_dir)
    s=json.dumps(result,ensure_ascii=False,indent=2)
    if ns.save:pathlib.Path(ns.save).write_text(s+'\n',encoding='utf-8')
    print(s)
if __name__=='__main__':
    try:main()
    except Exception as e:
        print('E23V_RAW_AUDIT_FAIL',type(e).__name__,str(e),file=sys.stderr);sys.exit(2)
