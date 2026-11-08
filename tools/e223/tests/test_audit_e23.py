"""Synthetic structural tests only: no historical examiner labels."""
import importlib.util,json,tempfile,unittest
from pathlib import Path
SCRIPT=Path(__file__).resolve().parents[1]/'audit_e23_results.py'
spec=importlib.util.spec_from_file_location('e23_audit',SCRIPT)
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class AuditTests(unittest.TestCase):
 def build(self,root,wrong=False):
  root=Path(root); plan={'tasks':[{'taskToken':'abcd','queryIds':['q-1'],'expectedE22FullRefitHash':'sha-test'}]}
  pp=root/'plan.json';pp.write_text(json.dumps(plan))
  ev=[]
  for arm in sorted(audit.ARMS):
   if arm!='STATIC':
    ev.extend([{'phase':'QUERY_DECISION_FROZEN','arm':arm,'queryId':'q-1'},
     {'phase':'LABEL_REVEALED','arm':arm,'queryId':'q-1'},
     {'phase':'UPDATE_FROZEN','arm':arm,'status':'synthetic'}])
   ev.append({'phase':'FINAL_STUDENT_FROZEN','arm':arm,'queryIds':[] if arm=='STATIC' else ['q-1'],
              'finalHash':'sha-test' if arm=='FULL_REFIT' else 'other'})
  if wrong:ev[0],ev[1]=ev[1],ev[0]
  (root/'STUDENT-abcd.jsonl').write_text('\n'.join(json.dumps(x) for x in ev)+'\n')
  grades=[{'task':'abcd','arm':a,'challengeStatus':'COUNTEREXAMPLE','heldoutCorrect':None} for a in sorted(audit.ARMS)]
  (root/'EXAMINER-AFTER-ALL-FREEZE.jsonl').write_text('\n'.join(json.dumps(x) for x in grades)+'\n')
  return pp
 def test_well_formed(self):
  with tempfile.TemporaryDirectory() as td:audit.audit(Path(td),self.build(td))
 def test_reveal_without_freeze_fails(self):
  with tempfile.TemporaryDirectory() as td:
   with self.assertRaises(AssertionError):audit.audit(Path(td),self.build(td,True))
if __name__=='__main__':unittest.main()
