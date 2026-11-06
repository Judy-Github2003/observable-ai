#!/usr/bin/env python3
"""One-time registration of PACK-02R-C8-03.

Scope is intentionally narrow: the student-side active-learning algorithm for the
four-attribute S/T study, its examiner harness, tests, and JSON evidence.  The
grant does not authorize production architecture, PACK-03, or changes to old
C8-02/EVAL records.
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "PACK-02R-C8-03"


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("C8-03 already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "C8-03: four-attribute active learning over a frozen 3-valued 81-input world; "
                "student-side candidate generation/scoring/querying, R->S->T chain, FULL/NO-R/BASE/NO-S "
                "and active/fixed/random comparisons, formal multi-world exam. No production platform."
            ),
            "approval_reference": "Research-owner message PACK-02R-C8-03, 2026-11-06.",
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/C8ActiveStudent.kt",
                "src/main/kotlin/logic/e3/pack02/C8Exam.kt",
                "src/test/kotlin/logic/e3/pack02/C8ActiveTest.kt",
                "src/test/kotlin/logic/e3/pack02/C8ExamTest.kt",
                "src/test/kotlin/logic/e3/pack02/C8FormalExamTest.kt",
                "experiments/representation-evolution-e3/reports/pack02r-c8-03-*.json",
                "PACK-02R-C8-03-REPORT.md",
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
            "PACK-02R-C8-03 authorized; four-attribute active S/T study", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
