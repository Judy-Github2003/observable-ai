#!/usr/bin/env python3
"""Independent structural audit for DMRV-01, no student Kotlin evaluator used.
This does not prove truly independent closed-book or one physical execution.
"""
import argparse,hashlib,json,math,collections,sys
from pathlib import Path
ACT=['BASE_ONLY','ADD_BINARY','ADD_PARITY3','ADD_MUX']
ARMS=['META_CONTEXT','GLOBAL_FEEDBACK','STATIC_BINARY','RANDOM','SHUFFLED_CONTEXT','COLD_START']
def sha(b):return hashlib.sha256(b).hexdigest()
def rows(p):
    return [json.loads(s) for s in p.read_text('utf-8').splitlines() if s.strip()]
def ev(expr,x):
    ty,*p=expr.split(':')
    bit=lambda i:(x>>int(i))&1==1
    if ty=='L':return bit(p[0]) ^ (p[1]=='1')
    if ty=='B':
        a,b=bit(p[0]),bit(p[1]);op=p[2]
        return {'AND':a and b,'OR':a or b,'XOR':a!=b,'XNOR':a==b,'NAND':not(a and b),'NOR':not(a or b)}[op]
    if ty=='P':return (sum(bit(i) for i in p[:3])%2==1)^(p[3]=='1')
    if ty=='M':return bit(p[1]) if bit(p[0]) else bit(p[2])
    raise ValueError('unrecognized expression '+expr)
def candidates(action):
    base=[f'L:{i}:{neg}' for i in range(4) for neg in range(2)]
    if action=='BASE_ONLY':return base
    extra=[]
    if action=='ADD_BINARY':
        extra=[f'B:{i}:{j}:{op}' for i in range(4) for j in range(i+1,4) for op in ['AND','OR','XOR','XNOR','NAND','NOR']]
    if action=='ADD_PARITY3':
        extra=[f'P:{i}:{j}:{k}:{neg}' for i in range(4) for j in range(i+1,4) for k in range(j+1,4) for neg in range(2)]
    if action=='ADD_MUX':
        extra=[f'M:{c}:{t}:{f}' for c in range(4) for t in range(4) for f in range(4) if len({c,t,f})==3]
    return base+extra
def ctx(train):
    errs=[sum(ev(e,x['x'])!=x['y'] for x in train) for e in candidates('BASE_ONLY')]
    pos=sum(x['y'] for x in train);n=len(train);by={r['x']:r['y'] for r in train}
    edges=sum(by[x]!=by[x^(1<<k)] for x in by for k in range(4) if x^(1<<k) in by and x^(1<<k)>x)
    fit='FIT' if min(errs)==0 else 'NEAR' if min(errs)==1 else 'GAP'
    rate='LOW' if pos*3<n else 'HIGH' if pos*3>2*n else 'MID'
    edge='CHANGING' if edges>=3 else 'CALM'
    return f'{fit}/{rate}/{edge}'
def chosen_expr(train,action):
    options=candidates(action)
    consistent=[e for e in options if all(ev(e,r['x'])==r['y'] for r in train)]
    signatures={tuple(ev(e,x) for x in range(16)) for e in consistent}
    if len(signatures)!=1:return None,len(options),len(consistent),len(signatures)
    depth=lambda e: {'L':1,'B':2,'P':3,'M':3}[e[0]]
    return min(consistent,key=lambda e:(depth(e),e)),len(options),len(consistent),len(signatures)
def policy(acts,context,global_only=False):
    gs=collections.defaultdict(list);cs=collections.defaultdict(list)
    for x in acts:
        gs[x['action']].append(x['reward']);cs[x['context'],x['action']].append(x['reward'])
    scores={}
    for act in ACT:
        g=sum(gs[act])/len(gs[act]) if gs[act] else 0.0
        values=cs[context,act]
        scores[act]=g if global_only else (sum(values)+4*g)/(len(values)+4)
    return sorted(ACT,key=lambda a:(-scores[a],len(candidates(a)),a))[0]
def snap_policy(p,context,global_only=False):
    # p is the parsed POLICY-*.txt aggregate counter snapshot.
    globals_,locals_=p
    scores={}
    for action in ACT:
        gn,gs=globals_.get(action,(0,0.0));global_avg=gs/gn if gn else 0.0
        cn,cs=locals_.get((context,action),(0,0.0))
        scores[action]=global_avg if global_only else (cs+4*global_avg)/(cn+4)
    return sorted(ACT,key=lambda a:(-scores[a],len(candidates(a)),a))[0]
def parse_snapshot(path):
    g={};c={}
    lines=path.read_text('utf-8').splitlines()
    assert lines[0].startswith('DMRV01|')
    for line in lines[1:]:
        v=line.split('|')
        if v[0]=='G':assert len(v)==4;g[v[1]]=(int(v[2]),float(v[3]))
        elif v[0]=='C':assert len(v)==5;c[(v[1],v[2])]=(int(v[3]),float(v[4]))
        else:raise ValueError(line)
    return g,c

def main(directory):
    src=directory/'STUDENTS-FROZEN.jsonl'
    digest=sha(src.read_bytes())
    assert (directory/'STUDENTS-FROZEN.sha256').read_text('utf-8').split()[0]==digest,'student freeze SHA'
    pol=directory/'POLICY-FROZEN.txt';assert (directory/'POLICY-FROZEN.sha256').read_text('utf-8').split()[0]==sha(pol.read_bytes()),'policy hash'
    meta=rows(directory/'META-TRAIN-FEEDBACK.jsonl')
    p=parse_snapshot(pol);shuffled=parse_snapshot(directory/'POLICY-SHUFFLED-FROZEN.txt')
    assert len(meta)%4==0
    # Exact aggregate reward construction verifies the saved policy was actually learned from meta-training feedback.
    aggG=collections.defaultdict(lambda:[0,0.0]);aggC=collections.defaultdict(lambda:[0,0.0]);metaIds=collections.defaultdict(set)
    for r in meta:
        assert r['action'] in ACT
        assert math.isclose(r['reward'],r['correct']/r['total']-.08*r['candidateEvaluations']/44,abs_tol=1e-10)
        metaIds[r['taskId']].add(r['action'])
        aggG[r['action']][0]+=1;aggG[r['action']][1]+=r['reward']
        aggC[(r['context'],r['action'])][0]+=1;aggC[(r['context'],r['action'])][1]+=r['reward']
    assert all(len(v)==4 for v in metaIds.values())
    for action in ACT:
        n,s=p[0][action];assert n==aggG[action][0] and math.isclose(s,aggG[action][1],abs_tol=1e-8)
    for key,(n,s) in p[1].items():assert n==aggC[key][0] and math.isclose(s,aggC[key][1],abs_tol=1e-8)
    assert set(p[1])==set(aggC)
    # Shuffled-control must use identical action-specific total feedback; only contexts can change.
    assert shuffled[0]==p[0], 'Shuffled policy changed global feedback distribution'
    for action in ACT:
        sn=sum(n for (context,a),(n,_) in shuffled[1].items() if a==action)
        ss=sum(val for (context,a),(_,val) in shuffled[1].items() if a==action)
        assert sn==p[0][action][0] and math.isclose(ss,p[0][action][1],abs_tol=1e-8), 'Shuffled policy feedback mismatch'
    students=rows(src);grades=rows(directory/'EXAMINER-AFTER-FREEZE.jsonl')
    assert len(students)==len(grades)
    assert len(students)%len(ARMS)==0
    nTasks=len(students)//len(ARMS)
    assert nTasks in (64,128)
    assert len(meta)==(64 if nTasks==64 else 192)*4
    assert not set(metaIds).intersection({s['taskId'] for s in students})
    gradeIndex={(g['taskId'],g['arm']):g for g in grades}; assert len(gradeIndex)==len(grades)
    byArm=collections.defaultdict(lambda:dict(correct=0,covered=0,errors=0,candidates=0,reward=0.0,all_correct=0))
    matched=collections.defaultdict(dict)
    byFamily=collections.defaultdict(lambda:collections.defaultdict(lambda:dict(correct=0,covered=0,all_correct=0)))
    for s in students:
        ident=(s['taskId'],s['arm']);assert ident in gradeIndex
        grade=gradeIndex[ident]; assert s['arm'] in ARMS and s['action'] in ACT
        assert len(s['predictions'])==8 and len(grade['heldoutInputs'])==8 and len(grade['truths'])==8
        assert len(s['train'])==8
        assert {r['x'] for r in s['train']}.isdisjoint(grade['heldoutInputs'])
        assert {r['x'] for r in s['train']}.union(grade['heldoutInputs'])==set(range(16))
        assert all(ev(grade['groundTruthExpr'],r['x'])==r['y'] for r in s['train'])
        assert [ev(grade['groundTruthExpr'],x) for x in grade['heldoutInputs']]==grade['truths']
        assert grade['hiddenFamily']=={'L':'UNARY','B':'BINARY','P':'PARITY3','M':'MUX'}[grade['groundTruthExpr'][0]]
        assert s['context']==ctx(s['train'])
        expectedExpr,count,nFit,nClass=chosen_expr(s['train'],s['action'])
        assert s['expr']==expectedExpr,(s['taskId'],s['arm'],'expression-selection',s['expr'],expectedExpr)
        assert (s['candidateEvaluations'],s['consistentCandidates'],s['behaviorClasses'])==(count,nFit,nClass)
        predicted=[None if s['expr'] is None else ev(s['expr'],x) for x in grade['heldoutInputs']]
        assert predicted==s['predictions'],(ident,'prediction mismatch')
        correct=sum(a is not None and a==b for a,b in zip(predicted,grade['truths']))
        covered=sum(x is not None for x in predicted)
        assert (grade['correct'],grade['covered'],grade['total'],grade['candidateEvaluations'])==(correct,covered,8,count)
        rew=correct/8-.08*count/44
        assert math.isclose(rew,grade['reward'],abs_tol=1e-10)
        expectedAction={'META_CONTEXT':snap_policy(p,s['context']),
                        'GLOBAL_FEEDBACK':snap_policy(p,s['context'],True),
                        'SHUFFLED_CONTEXT':snap_policy(shuffled,s['context']),
                        'STATIC_BINARY':'ADD_BINARY',
                        'COLD_START':'BASE_ONLY',
                        'RANDOM':ACT[int(s['taskId'][:8],16)%4]}[s['arm']]
        assert s['action']==expectedAction,(ident,'policy action mismatch',expectedAction)
        a=byArm[s['arm']];a['correct']+=correct;a['covered']+=covered;a['errors']+=covered-correct;a['candidates']+=count;a['reward']+=rew
        a['all_correct']+=int(correct==8 and covered==8)
        matched[s['taskId']][s['arm']]=correct==8 and covered==8
        fam=byFamily[grade['hiddenFamily']][s['arm']]
        fam['correct']+=correct;fam['covered']+=covered;fam['all_correct']+=int(correct==8 and covered==8)
    assert all(len(t)==6 for t in matched.values())
    assert len(byFamily)==4 and all(sum(v['all_correct']<=32 for v in arms.values())==6 for arms in byFamily.values())
    comparisons={}
    for arm in ARMS[1:]:
        wins=sum(x['META_CONTEXT'] and not x[arm] for x in matched.values())
        losses=sum(x[arm] and not x['META_CONTEXT'] for x in matched.values())
        comparisons[arm]=dict(wins=wins,losses=losses,ties=nTasks-wins-losses)
    output={'status':'DMRV01_AUDIT_PASS_STRUCTURAL', 'examinerVisibility':'VISIBLE', 'metaTrainTasks':len(metaIds),
            'testTasks':nTasks,'studentRows':len(students),'gradeRows':len(grades),
            'studentSha256':digest,'byArm':byArm,'byFamily':byFamily,'pairedVsMeta':comparisons,
            'independentClosedBook':'NOT_RUN','strategyImprovement':'CHECK_PAIRED_METRICS_NOT_AUTOMATIC'}
    (directory/'AUDIT.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(output,ensure_ascii=False,indent=2))

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('output_directory');args=a.parse_args();main(Path(args.output_directory))
