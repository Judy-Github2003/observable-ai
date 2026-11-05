#!/usr/bin/env python3
"""Independent unit tests for the PACK-01 console authorization gate.

These tests build their own state.  They never read or write the real
research-console/tasks.json, so their result does not depend on whether the
real pack is currently authorized.
"""
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import console_cli  # noqa: E402
import server  # noqa: E402


def task(tid, status="IN_PROGRESS", auth="PACK-01", patch=0):
    return {
        "id": tid,
        "title": tid,
        "status": status,
        "authorized_action": auth,
        "patch_used": patch,
    }


class ConsoleGateTest(unittest.TestCase):
    def active_state(self):
        return {
            "authorized_action": "PACK-01",
            "active_pack": {
                "id": "PACK-01",
                "status": "IN_PROGRESS",
                "tasks": ["D2", "RES-N02", "A-R1"],
            },
            "tasks": [
                task("D2"),
                task("RES-N02"),
                task("A-R1"),
                task("A1", status="READY", auth="none", patch=1),
                task("RES-E01", status="READY", auth="none"),
            ],
        }

    def test_active_pack_allows_only_whitelist(self):
        state = self.active_state()
        for tid in ("D2", "RES-N02", "A-R1"):
            ok, reason = console_cli.gate(state, tid, "PACK-01")
            self.assertTrue(ok, f"{tid}: {reason}")
        ok, reason = console_cli.gate(state, "A1", "PACK-01")
        self.assertFalse(ok)
        self.assertIn("not in active pack", reason)

    def test_ended_pack_cannot_execute_or_finish(self):
        state = self.active_state()
        state["active_pack"]["status"] = "REVIEW"
        state["authorized_action"] = "none"
        for tid in ("D2", "RES-N02", "A-R1"):
            state_task = server.find_task(state, tid)
            state_task["status"] = "REVIEW"
            state_task["authorized_action"] = "none"
            ok, reason = console_cli.gate(state, tid, "PACK-01")
            self.assertFalse(ok)
            ok, reason = console_cli.gate(state, tid, "PACK-01", for_finish=True)
            self.assertFalse(ok)

    def test_non_pack_task_needs_matching_authorization(self):
        state = self.active_state()
        state.pop("active_pack")
        state["authorized_action"] = "D2"
        d2 = server.find_task(state, "D2")
        d2["authorized_action"] = "D2"
        d2["status"] = "READY"
        ok, reason = console_cli.gate(state, "D2", "D2")
        self.assertTrue(ok, reason)

        other = server.find_task(state, "RES-E01")
        ok, reason = console_cli.gate(state, "RES-E01", "D2")
        self.assertFalse(ok)
        self.assertIn("does not match", reason)

    def test_finish_requires_active_authorized_execution(self):
        state = self.active_state()
        ok, reason = console_cli.gate(state, "D2", "PACK-01", for_finish=True)
        self.assertTrue(ok, reason)

        state["authorized_action"] = "none"
        ok, reason = console_cli.gate(state, "D2", "PACK-01", for_finish=True)
        self.assertFalse(ok)

        state = self.active_state()
        server.find_task(state, "D2")["status"] = "REVIEW"
        ok, reason = console_cli.gate(state, "D2", "PACK-01", for_finish=True)
        self.assertFalse(ok)

    def test_same_rules_for_active_and_ended_scenarios(self):
        active = self.active_state()
        self.assertTrue(console_cli.gate(active, "D2", "PACK-01")[0])
        ended = self.active_state()
        ended["active_pack"]["status"] = "ACCEPTED_LIMITED"
        ended["authorized_action"] = "none"
        server.find_task(ended, "D2")["status"] = "ACCEPTED_LIMITED"
        server.find_task(ended, "D2")["authorized_action"] = "none"
        self.assertFalse(console_cli.gate(ended, "D2", "PACK-01")[0])


if __name__ == "__main__":
    unittest.main()
