#!/usr/bin/env python3
"""One-time registration of MRX-02B (candidate search audit + pairwise strategies)."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-02B"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("MRX-02B already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "MRX-02B: candidate lifecycle audit; A/B/C atom search strategies (baseline/diversity/pairwise); "
                "G0-G4 attribution after exam; multi-round dynamic feedback tests. Historical MRX-01/01R/02 and "
                "C8-03 results preserved; no PACK-03."
            ),
            "approval_reference": "Research-owner MRX-02B pack, 2026-11-06.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/MRX2B*.kt",
                "src/test/kotlin/logic/e3/pack02/MRX2B*.kt",
                "experiments/representation-evolution-e3/reports/mrx-02b-*.json",
                "MRX-02B-REPORT.md",
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
            "MRX-02B authorized; candidate search audit and pairwise strategies", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
