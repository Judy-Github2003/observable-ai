#!/usr/bin/env python3
"""Small local unit test for PACK-01 console gate (no server needed)."""
import copy
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import console_cli  # noqa: E402
import server  # noqa: E402


class ConsoleGateTest(unittest.TestCase):
    def setUp(self):
        state, _, _ = server.load_state()
        self.state = copy.deepcopy(state)

    def test_pack_whitelist_allows_pack_task(self):
        ok, reason = console_cli.gate(self.state, "D2", "PACK-01")
        self.assertTrue(ok, reason)

    def test_pack_whitelist_rejects_outside_task(self):
        ok, reason = console_cli.gate(self.state, "A1", "PACK-01")
        self.assertFalse(ok)
        self.assertIn("not in active pack", reason)

    def test_non_pack_task_needs_matching_authorization(self):
        self.state.pop("active_pack", None)
        self.state["authorized_action"] = "D2"
        task = server.find_task(self.state, "D2")
        task["authorized_action"] = "D2"
        task["status"] = "READY"
        ok, reason = console_cli.gate(self.state, "D2", "D2")
        self.assertTrue(ok, reason)

        other = server.find_task(self.state, "RES-E01")
        other["status"] = "READY"
        other["authorized_action"] = "none"
        ok, reason = console_cli.gate(self.state, "RES-E01", "D2")
        self.assertFalse(ok)

    def test_finish_rejects_review_or_unauthorized(self):
        task = server.find_task(self.state, "D2")
        task["status"] = "REVIEW"
        ok, reason = console_cli.gate(self.state, "D2", "PACK-01", for_finish=True)
        self.assertFalse(ok)

        task["status"] = "PATCH_ONCE"
        self.state["authorized_action"] = "none"
        ok, reason = console_cli.gate(self.state, "D2", "PACK-01", for_finish=True)
        self.assertFalse(ok)


if __name__ == "__main__":
    unittest.main()
