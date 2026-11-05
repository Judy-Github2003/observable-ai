#!/usr/bin/env python3
"""One-time activation of the already-registered PACK-02R-AUTH-01 grant.

The research-owner message of 2026-11-06 is the approval basis. This script
only transitions PENDING_APPROVAL -> ACTIVE for that exact repair id; it is
not a generic repair-authorization CLI.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "PACK-02R-AUTH-01"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        record = next((r for r in repairs.get("repairs", []) if r.get("repair_id") == TASK), None)
        if record is None:
            raise SystemExit("AUTH repair record missing")
        if record.get("authorization_status") == "CONSUMED":
            raise SystemExit("AUTH already consumed")
        if record.get("authorization_status") == "ACTIVE":
            print("AUTH already ACTIVE")
            return
        if record.get("authorization_status") != "PENDING_APPROVAL":
            raise SystemExit("AUTH not pending")
        if record.get("content_hash") != server.repair_digest(record):
            raise SystemExit("AUTH grant hash mismatch; refusing activation")
        before = json.loads(json.dumps(record))
        record["authorization_status"] = "ACTIVE"
        record["activated_at"] = datetime.now(timezone.utc).isoformat()
        record["activation_reference"] = (
            "Research-owner authorization, 2026-11-06: activate PACK-02R-AUTH-01; "
            "scope remains trusted evidence authentication only."
        )
        event = server.append_event(
            "repair_activated", "research_owner",
            "PACK-02R-AUTH-01 activated from PENDING_APPROVAL; AUTH scope only", before, record,
        )
        record.setdefault("audit_event_ids", []).append(event)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
