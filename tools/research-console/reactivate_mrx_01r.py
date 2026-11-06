#!/usr/bin/env python3
"""Reactivate MRX-01R after mistaken revocation by another session.

The research owner confirmed the directive belongs to this session. Keep the
previous registration/revocation events as audit history; append a reactivation
event instead of deleting records.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-01R"


def main():
    with server.WRITE_LOCK:
        repairs = server.load_repairs()
        record = next((r for r in repairs["repairs"] if r.get("repair_id") == TASK), None)
        if record is None:
            raise SystemExit("MRX-01R not registered")
        before = json.loads(json.dumps(record))
        record["parent_task_ids"] = ["C4", "C8", "RES-H01"]
        record["approval_reference"] = (
            "Research-owner message MRX-01R, 2026-11-06; owner confirmed the directive "
            "belongs to this session after a mistaken cross-session send."
        )
        record["authorization_status"] = "ACTIVE"
        record["consumed_at"] = None
        record["content_hash"] = server.repair_digest(record)
        event = server.append_event(
            "repair_reactivated", "research_owner",
            "MRX-01R reactivated: directive belongs to this session; prior revocation kept as audit history",
            before, record,
        )
        record["audit_event_ids"] = list(dict.fromkeys(record.get("audit_event_ids", []) + [event]))
        record["content_hash"] = server.repair_digest(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
