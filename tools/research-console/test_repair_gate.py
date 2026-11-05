#!/usr/bin/env python3
"""Independent tests for the PACK-02R special-repair gate.

They build their own state and audit directory; they never consume the real
GATE/AUTH records.
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import console_cli  # noqa: E402
import server  # noqa: E402


def state():
    return {
        "authorized_action": "none",
        "active_pack": {"id": "PACK-02", "status": "PAUSED", "tasks": ["C4", "RES-H01", "C8"]},
        "tasks": [
            {"id": "C4", "status": "PAUSED", "authorized_action": "none", "patch_used": 1},
            {"id": "RES-H01", "status": "REVIEW", "authorized_action": "none", "patch_used": 1},
            {"id": "C8", "status": "PAUSED", "authorized_action": "none", "patch_used": 1},
        ],
    }


def record(task="PACK-02R-GATE-01", status="ACTIVE"):
    rec = {
        "repair_id": task,
        "parent_pack_id": "PACK-02",
        "parent_task_ids": ["C4", "C8", "RES-H01"],
        "approved_scope": "bounded console gate test",
        "approval_reference": "research-owner test approval",
        "authorized_by": "research_owner",
        "allowed_task_id": task,
        "allowed_files_or_modules": ["tools/research-console/console_cli.py"],
        "authorization_status": status,
        "created_at": "2026-11-06T00:00:00+00:00",
        "consumed_at": None,
        "audit_event_ids": ["ev-test"],
        "content_hash": "",
    }
    rec["content_hash"] = server.repair_digest(rec)
    return rec


class RepairGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_history = server.HISTORY
        server.HISTORY = Path(self.tmp.name) / "history"
        server.HISTORY.mkdir(parents=True)
        (server.HISTORY / "ev-test.json").write_text('{"kind":"repair_authorized"}', encoding="utf-8")

    def tearDown(self):
        server.HISTORY = self.old_history
        self.tmp.cleanup()

    def gate(self, rec, task="PACK-02R-GATE-01"):
        repairs = {"repairs": [rec]}
        return server.repair_gate(state(), repairs, task)

    def test_active_repair_passes_only_its_allowed_task(self):
        self.assertTrue(self.gate(record())[0])
        ok, _ = self.gate(record(), "PACK-02R-AUTH-01")
        self.assertFalse(ok)
        ok, _ = self.gate(record(), "ACTIVE")
        self.assertFalse(ok)

    def test_active_auth_repair_is_registrable(self):
        auth = record(task="PACK-02R-AUTH-01")
        self.assertTrue(self.gate(auth, "PACK-02R-AUTH-01")[0])
        self.assertFalse(self.gate(record(task="PACK-02R-AUTH-01"), "ACTIVE")[0])

    def test_pending_unknown_and_hash_mismatch_refused(self):
        self.assertFalse(self.gate(record(status="PENDING_APPROVAL"))[0])
        bad = record()
        bad["approved_scope"] = "changed after approval"
        self.assertFalse(self.gate(bad)[0])
        bad = record()
        bad["allowed_task_id"] = "ACTIVE"
        self.assertFalse(self.gate(bad, "ACTIVE")[0])

    def test_missing_audit_event_refused(self):
        rec = record()
        rec["audit_event_ids"] = ["ev-missing"]
        self.assertFalse(self.gate(rec)[0])
        rec["audit_event_ids"] = []
        self.assertFalse(self.gate(rec)[0])

    def test_normal_pack_tasks_still_refused(self):
        s = state()
        for tid in ("C4", "RES-H01", "C8"):
            ok, reason = console_cli.gate(s, tid, "PACK-02")
            self.assertFalse(ok, tid)
            self.assertFalse(ok)

    def test_repair_is_not_global_authorization(self):
        s = state()
        s["authorized_action"] = "PACK-02R-GATE-01"
        ok, _ = console_cli.gate(s, "C4", "PACK-02R-GATE-01")
        self.assertFalse(ok)

    def test_consume_once(self):
        rec = record()
        repairs = {"repairs": [rec]}
        saved = {}

        def fake_save(data):
            saved.update(json.loads(json.dumps(data)))

        with mock.patch.object(server, "load_state", return_value=(state(), {}, {})), \
             mock.patch.object(server, "load_repairs", return_value=repairs), \
             mock.patch.object(server, "save_repairs", side_effect=fake_save), \
             mock.patch.object(server, "append_event", return_value="ev-consumed"):
            event = server.consume_repair("PACK-02R-GATE-01", "abc", "tests ok", "report.md")
        self.assertEqual("ev-consumed", event)
        self.assertEqual("CONSUMED", saved["repairs"][0]["authorization_status"])
        self.assertEqual("ev-consumed", saved["repairs"][0]["audit_event_ids"][-1])
        self.assertFalse(server.repair_gate(state(), saved, "PACK-02R-GATE-01")[0])


if __name__ == "__main__":
    unittest.main()
