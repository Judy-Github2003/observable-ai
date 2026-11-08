#!/usr/bin/env python3
"""E23-R engineering contracts, NOT scientific evidence or Gradle integration."""
from __future__ import annotations
import importlib.util, json, hashlib, tempfile, unittest
from pathlib import Path

PACK=Path(__file__).resolve().parents[3]

def mod(name,rel):
    spec=importlib.util.spec_from_file_location(name, PACK/rel)
    m=importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(m)
    return m

rebuild=mod('rebuild_e23r','tools/e23r/rebuild_frozen_plan.py')
regression=mod('isolated_e23r','tools/e222/isolated_full_regression.py')
classify=mod('classify_e23r','tools/e23r/classify_regression.py')
apply=mod('apply_e23r','tools/e23r/apply_recovery.py')

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()

    def populate(self):
        plan=json.loads((PACK/'config/e23-frozen-replay-plan.json').read_text())
        for row in plan['tasks']:
            p=self.root/rebuild.REL_SOURCE/f'STUDENT-EVENTS-{row["taskToken"]}.jsonl'
            p.parent.mkdir(parents=True,exist_ok=True)
            lines=[{'phase':'QUERY_LABEL_REVEALED','arm':'E22_CONJECTURE','queryId':q}
                   for q in row['queryIds']]
            lines.append({'phase':'FINAL_STUDENT_FROZEN','arm':'E22_CONJECTURE',
                          'task':row['taskToken'],'queryIds':row['queryIds'],
                          'finalProgramHash':row['expectedE22FullRefitHash']})
            p.write_text('\n'.join(json.dumps(x) for x in lines)+'\n')
        return plan

    def test_token_is_sha_of_world_seed(self):
        rows=json.loads((PACK/'config/e23-frozen-replay-plan.json').read_text())['tasks']
        self.assertEqual(len(rows),8)
        self.assertEqual(sum(len(r['queryIds']) for r in rows),11)
        self.assertTrue(all(rebuild.token(r['world'],r['seed'])==r['taskToken'] for r in rows))
        self.assertEqual(rows[0]['taskToken'],'6894c295a0615971')
        self.assertEqual(rows[2]['taskToken'],'e0734e453d890dbd')
        self.assertEqual(rows[4]['taskToken'],'4254cfe428af73ca')

    def test_derivation_matches_synthetic_transcripts(self):
        old=self.populate()
        res=rebuild.reconstruct(self.root)
        for a,b in zip(old['tasks'],res['tasks']):
            self.assertEqual(a['queryIds'],b['queryIds'])
            self.assertEqual(a['expectedE22FullRefitHash'],b['expectedE22FullRefitHash'])
        self.assertEqual(res['replayQueryCount'],11)

    def test_freeze_vs_revealed_query_mismatch_fails_closed(self):
        self.populate();row=json.loads((PACK/'config/e23-frozen-replay-plan.json').read_text())['tasks'][0]
        p=self.root/rebuild.REL_SOURCE/f'STUDENT-EVENTS-{row["taskToken"]}.jsonl'
        p.write_text(json.dumps({'phase':'FINAL_STUDENT_FROZEN','arm':'E22_CONJECTURE',
            'task':row['taskToken'],'queryIds':['q-5'],
            'finalProgramHash':row['expectedE22FullRefitHash']})+'\n')
        with self.assertRaisesRegex(ValueError,'QUERY transcript'):
            rebuild.reconstruct(self.root)

    def test_sandbox_cleanup_only_targeted_json(self):
        d=self.root/regression.E1_ONESHOT_DIR;d.mkdir(parents=True)
        (d/'FROZEN-abc.json').write_text('candidate')
        (d/'PREREGISTRATION.json').write_text('important')
        (d/'RESULT.md').write_text('archived report')
        (d/'E1-RUN-STARTED.txt').write_text('marker')
        m=self.root/'experiments/representation-evolution-e3/reports/mrx-01-freeze.json'
        m.parent.mkdir(parents=True);m.write_text('original')
        removed=regression.prepare(self.root)
        self.assertTrue(any('FROZEN-abc.json' in p for p in removed))
        self.assertFalse((d/'FROZEN-abc.json').exists())
        self.assertTrue((d/'PREREGISTRATION.json').exists())
        self.assertTrue((d/'RESULT.md').exists())
        self.assertTrue(m.exists())

    def test_classifier_never_promotes_archival_mismatch_to_full_pass(self):
        d=self.root/'junit';d.mkdir()
        (d/'TEST-a.xml').write_text('''<testsuite name="logic.e3.pack02.MRXFormalTest" tests="2" failures="1">
          <testcase name="check"><failure message="MRXSpace.kt expected:&lt;abc&gt; but was:&lt;def&gt;"/></testcase>
          <testcase name="ok"/></testsuite>''')
        outcome=classify.classify(d)
        self.assertEqual(outcome['total'],2)
        self.assertEqual(outcome['archivalSourceMismatch'],1)
        self.assertEqual(outcome['fullUnfilteredGradleRegression'],'FAIL')

    def test_installer_rejects_unrecognized_source(self):
        self.populate()
        (self.root/'build.gradle.kts').write_text('plugins {}')
        for rel in apply.ORIGINAL:
            p=self.root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('unexpected')
        with self.assertRaisesRegex(ValueError,'Packaged plan disagrees'):
            apply.recover(PACK,self.root,False)

    def test_manifest_is_read_only_repair_no_truth_labels(self):
        plan=json.loads((PACK/'config/e23-frozen-replay-plan.json').read_text())
        self.assertEqual(plan['independentClosedBook'],'NOT_RUN')
        self.assertEqual(plan['formalRuns'],0)
        for row in plan['tasks']:
            self.assertNotIn('label',json.dumps(row).lower())
            self.assertEqual(len(row['sourceEventSha256']),64)

if __name__=='__main__':unittest.main(verbosity=2)
