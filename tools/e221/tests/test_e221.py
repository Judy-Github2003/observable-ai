"""Pure-Python correction contracts. No Gradle or oracle required."""
from __future__ import annotations
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
def readmod(filename):
    spec=importlib.util.spec_from_file_location(filename,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

report=readmod('apply_e221_report.py')
audit=readmod('audit_e221_corrections.py')
preflight=readmod('regression_preflight.py')

class E221Contracts(unittest.TestCase):
    def test_report_guards_missing_anchor(self):
        with self.assertRaises(AssertionError):report.corrected('# no old result')

    def test_report_guards_double_application(self):
        original=(ROOT/'verification'/'e22-original-result.md').read_text(encoding='utf-8')
        fixed=report.corrected(original)
        self.assertIn('119/126',fixed)
        self.assertIn('q-11',fixed)
        with self.assertRaises(AssertionError):report.corrected(fixed)

    def test_preflight_detects_one_shot_collision(self):
        with tempfile.TemporaryDirectory() as t:
            d=Path(t)
            (d/'build.gradle.kts').write_text('plugins {}',encoding='utf-8')
            source=d/'src/test/kotlin/SD03E22DevelopmentIntegrationTest.kt'
            source.parent.mkdir(parents=True,exist_ok=True)
            source.write_text('require(!Files.exists(marker))',encoding='utf-8')
            marker=d/'docs/research/sd03-e22-development-2026-11-07/E22-RUN-STARTED.txt'
            marker.parent.mkdir(parents=True,exist_ok=True)
            marker.write_text('old',encoding='utf-8')
            issues=preflight.inspect(d)
            self.assertTrue(any(x['kind']=='KNOWN_COLLISION' for x in issues))

    def test_recalculated_observed_summary(self):
        observed=json.loads((ROOT/'verification'/'e22-recomputed-summary.json').read_text())
        self.assertEqual((119,126),(observed['arms']['STATIC']['heldoutCorrectAmongPass'],
                                  observed['arms']['STATIC']['heldoutTotalAmongPass']))
        self.assertEqual((103,105),(observed['arms']['E22_CONJECTURE']['heldoutCorrectAmongPass'],
                                  observed['arms']['E22_CONJECTURE']['heldoutTotalAmongPass']))
        self.assertEqual('q-11',observed['initialHitsByArm']['E22_CONJECTURE'][1]['queryId'])
        self.assertEqual('q-1',observed['currentHitsByArm']['E22_CONJECTURE'][1]['queryId'])
        self.assertEqual(5,observed['arms']['E22_CONJECTURE']['programChangesAfterConfirmingEvidence'])

if __name__=='__main__':unittest.main(verbosity=2)
