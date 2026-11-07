#!/usr/bin/env python3
"""Register SD-01 structure discovery after the research-owner default authorization."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "SD-01-STRUCTURE-DISCOVERY-2026-11-07"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit(f"{TASK} already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "SD-01 structure discovery development: generic typed candidate composition, "
                "TRAIN/QUERY/CHALLENGE validation, isolated structure record, transfer/ablation checks, "
                "honest SD0/SD1 evidence. No 160 formal exam, no full regression, no historical changes."
            ),
            "approval_reference": "Research-owner default authorization for subsequent tasks, 2026-11-07.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/SD01*.kt",
                "src/test/kotlin/logic/e3/pack02/SD01*.kt",
                "docs/research/sd-01-2026-11-07/",
                "SD-01-STRUCTURE-DISCOVERY-2026-11-07-REPORT.md",
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
            "SD-01 structure discovery authorized", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


def load_repairs():
    import server
    return server.load_repairs()


if __name__ == "__main__":
    main()
