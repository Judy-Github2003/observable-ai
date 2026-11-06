#!/usr/bin/env python3
"""One-time registration of MRX-02 (dynamic experiment decision study)."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-02"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("MRX-02 already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "MRX-02: evidence semantics and real cost accounting; dynamic re-planning after each experiment; "
                "paired feedback tests; candidate coverage/pruning attribution (G0-G4); FIXED/STATIC/DYNAMIC/ORACLE "
                "comparison if gates pass. Historical MRX-01/MRX-01R/C8-03 results preserved; no PACK-03."
            ),
            "approval_reference": "Research-owner message MRX-02, 2026-11-06.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/MRX2*.kt",
                "src/test/kotlin/logic/e3/pack02/MRX2*.kt",
                "experiments/representation-evolution-e3/reports/mrx-02-*.json",
                "MRX-02-REPORT.md",
            ],
            "authorization_status": "ACTIVE",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "consumed_at": None,
            "audit_event_ids": [],
            "content_hash": "",
        }
        record["content_hash"] = server.repair_digest(record)
        event = server.append_event(
            "repair_authorized", "research_owner",
            "MRX-02 authorized; dynamic experiment decision and cost accounting", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
