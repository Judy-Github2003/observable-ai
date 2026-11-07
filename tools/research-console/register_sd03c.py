#!/usr/bin/env python3
"""Register SD-03 continuation after the research-owner default authorization."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "SD-03-CONTINUATION-2026-11-07"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit(f"{TASK} already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "SD-03 continuation: multi-source student pool, forensic AST mismatch classification, "
                "honest G0-G4 gates. No SD-04, no full regression, no 160 formal exam, no historical changes."
            ),
            "approval_reference": "Research-owner default authorization for subsequent tasks, 2026-11-07.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/SD03*.kt",
                "src/test/kotlin/logic/e3/pack02/SD03*.kt",
                "docs/research/sd03-continuation-2026-11-07/",
                "SD-03-CONTINUATION-2026-11-07-REPORT.md",
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
            "SD-03 continuation authorized", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
