#!/usr/bin/env python3
"""One-time registration of PACK-02R-EVAL-02."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "PACK-02R-EVAL-02"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("EVAL-02 already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": "EVAL-02: explain eval-101; fix examiner-side C8 data/dependency design; supplementary frozen-student exams. No student algorithm changes.",
            "approval_reference": "Research-owner message PACK-02R-EVAL-02, 2026-11-06.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/EvalExam.kt",
                "src/main/kotlin/logic/e3/pack02/EvalC8.kt",
                "src/test/kotlin/logic/e3/pack02/EvalExamTest.kt",
                "src/test/kotlin/logic/e3/pack02/EvalC8Test.kt",
                "experiments/representation-evolution-e3/reports/pack02r-eval-*.json",
            ],
            "authorization_status": "ACTIVE",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "consumed_at": None,
            "audit_event_ids": [],
            "content_hash": "",
        }
        record["content_hash"] = server.repair_digest(record)
        event = server.append_event("repair_authorized", "research_owner",
                                    "PACK-02R-EVAL-02 authorized; examiner-side only", None, record)
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
