#!/usr/bin/env python3
"""One-time registration of the PACK-02R repair authorizations.

GATE-01 is authorized by the research-owner message of 2026-11-06.
AUTH-01 is registered as PENDING_APPROVAL: it is not executable until the
research owner performs the separate final authorization action.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = {"repairs": []}
        gate = {
            "repair_id": "PACK-02R-GATE-01",
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": "Research Console special-repair authorization mechanism only; no E3 cognitive code; no ACTIVE/EVAL.",
            "approval_reference": "Research-owner message PACK-02R-GATE-01, 2026-11-06: authorize a bounded Console management extension.",
            "authorized_by": "research_owner",
            "allowed_task_id": "PACK-02R-GATE-01",
            "allowed_files_or_modules": [
                "tools/research-console/console_cli.py",
                "tools/research-console/server.py",
                "tools/research-console/test_repair_gate.py",
                "research-console/repairs.json",
            ],
            "authorization_status": "ACTIVE",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "consumed_at": None,
            "audit_event_ids": [],
            "content_hash": "",
        }
        gate["content_hash"] = server.repair_digest(gate)
        event = server.append_event("repair_authorized", "research_owner",
                                    "PACK-02R-GATE-01 management authorization", None, gate)
        gate["audit_event_ids"] = [event]
        repairs["repairs"].append(gate)

        auth = {
            "repair_id": "PACK-02R-AUTH-01",
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": "Trusted evidence authentication chain only: ExecutionFactAdapter, Fact/ProofChecker, H01 fact migration.",
            "approval_reference": "PACK-02R-AUTH-01 is conditionally approved; final execution authorization must be recorded separately.",
            "authorized_by": "research_owner",
            "allowed_task_id": "PACK-02R-AUTH-01",
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/ExecutionFactAdapter.kt",
                "src/main/kotlin/logic/e3/pack02/Deduction.kt",
                "src/test/kotlin/logic/e3/pack02",
            ],
            "authorization_status": "PENDING_APPROVAL",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "consumed_at": None,
            "audit_event_ids": [],
            "content_hash": "",
        }
        auth["content_hash"] = server.repair_digest(auth)
        event = server.append_event("repair_registered", "execution",
                                    "PACK-02R-AUTH-01 registered; awaiting final research-owner action", None, auth)
        auth["audit_event_ids"] = [event]
        repairs["repairs"].append(auth)
        server.save_repairs(repairs)
        print(json.dumps({"gate_event": gate["audit_event_ids"][0], "auth_event": auth["audit_event_ids"][0]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
