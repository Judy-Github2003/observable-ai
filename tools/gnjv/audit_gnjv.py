#!/usr/bin/env python3
"""GNJV-01 results audit: checks pairing, freeze hash, denominator and cost bounds.
Does not provide independent verification of Kotlin semantics or student isolation.
"""
from __future__ import annotations
import argparse, hashlib, json
from collections import defaultdict
from pathlib import Path

ARMS = {'OBS_ONLY', 'FIXED', 'RANDOM', 'GNJV_ACTIVE'}

def read_jsonl(path):
    return [json.loads(x) for x in path.read_text(encoding='utf-8').splitlines() if x.strip()]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('output_directory')
    args=p.parse_args()
    root=Path(args.output_directory)
    frozen=root/'STUDENTS-FROZEN.jsonl'
    grades=root/'EXAMINER-AFTER-FREEZE.jsonl'
    hashfile=root/'STUDENTS-FROZEN.sha256'
    assert all(p.exists() for p in [frozen,grades,hashfile]), 'incomplete sealed outputs'
    frozenbytes=frozen.read_bytes()
    expected=hashfile.read_text().split()[0]
    assert hashlib.sha256(frozenbytes).hexdigest()==expected, 'student freeze hash mismatch'
    st=read_jsonl(frozen); gr=read_jsonl(grades)
    assert len(st)==len(gr)==48,(len(st),len(gr))
    k=lambda x:(x['task'],x['arm'])
    assert len({k(x) for x in st})==48
    assert len({k(x) for x in gr})==48
    assert {k(x) for x in st}=={k(x) for x in gr}
    tasks={x['task'] for x in st}; assert len(tasks)==12,len(tasks)
    ss={k(x):x for x in st}
    for token in tasks:
        assert {x['arm'] for x in st if x['task']==token}==ARMS
        assert {x['arm'] for x in gr if x['task']==token}==ARMS
    report=defaultdict(lambda:{'tasks':0,'identified':0,'correct':0,'covered':0,'total':0,'errors':0,'queries':0,'falseCertainty':0})
    by_grade={k(x):x for x in gr}
    for x in gr:
        s=ss[k(x)]; a=x['arm']; n=x['queryCount']; d=report[a]
        assert n==s['queryCount']==len(s['actions']) and 0<=n<=2
        assert s['teacherCalls']==0 and len(s['heldoutPredictions'])==4
        assert len(s['hypotheses'])>=1
        assert 0<=x['correct']<=x['covered']<=x['total']==4
        assert x['errors']==x['covered']-x['correct']
        if a=='OBS_ONLY': assert n==0
        d['tasks']+=1;d['identified']+=int(x['identifiedCorrect']);d['correct']+=x['correct']
        d['covered']+=x['covered'];d['total']+=4;d['errors']+=x['errors'];d['queries']+=n
        d['falseCertainty']+=int(x['falseCertainty'])
    pair=defaultdict(lambda:dict(win=0,loss=0,tie=0))
    for t in tasks:
        active=by_grade[(t,'GNJV_ACTIVE')]
        for ctrl in ('OBS_ONLY','RANDOM','FIXED'):
            control=by_grade[(t,ctrl)]
            da=int(active['identifiedCorrect'])-int(control['identifiedCorrect'])
            if da>0:pair[ctrl]['win']+=1
            elif da<0:pair[ctrl]['loss']+=1
            else:pair[ctrl]['tie']+=1
    print(json.dumps({'status':'GNJV01_RAW_AUDIT_PASS','tasks':len(tasks),'rows':len(st),'frozenSha256':expected,
                      'arms':dict(report),'pairedIdentificationVsActive':dict(pair)},ensure_ascii=False,indent=2))

if __name__=='__main__': main()
