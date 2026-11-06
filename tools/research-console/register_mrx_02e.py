#!/usr/bin/env python3
"""One-time registration of MRX-02E (真实双失败后的第三步重规划)."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-02E"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("MRX-02E already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "MRX-02E: real feedback snapshots and third-step dynamic re-planning after two failures; "
                "FIXED/STATIC/DYNAMIC planner contract and real MRX2Student execution where feasible. "
                "No 160-run formal exam; historical MRX results preserved."
            ),
            "approval_reference": "Research-owner MRX-02E pack, 2026-11-07.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/MRX2E*.kt",
                "src/main/kotlin/logic/e3/pack02/MRX2Student.kt",
                "src/test/kotlin/logic/e3/pack02/MRX2E*.kt",
                "experiments/representation-evolution-e3/reports/mrx-02e-*.json",
                "MRX-02E-REPORT.md",
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
            "MRX-02E authorized; dynamic third-step re-planning", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
