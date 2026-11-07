#!/usr/bin/env python3
"""Register the 2026-11-07 MRX open exploration task after explicit research-owner authorization."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-NEXT-2026-11-07"


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
                "MRX next open exploration 2026-11-07: student-visible evidence, competing failure "
                "hypotheses with provenance, and evidence-driven next action in real MRX2Student runs; "
                "W4 and independent XOR development cases; STATIC/RULE/EVIDENCE comparison. "
                "No 160-run formal exam; no old frozen JSON or Research Console score changes."
            ),
            "approval_reference": (
                "Research-owner message 2026-11-07: '授权，特殊研究不用受限', in response to "
                "MRX-NEXT-2026-11-07 Gate-0 authorization request."
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/MRXNext*.kt",
                "src/main/kotlin/logic/e3/pack02/MRX2Student.kt",
                "src/test/kotlin/logic/e3/pack02/MRXNext*.kt",
                "docs/research/mrx-next-2026-11-07/",
                "experiments/representation-evolution-e3/reports/mrx-next-*.json",
                "experiments/representation-evolution-e3/reports/mrx-next-*.jsonl",
                "RESEARCH-QUESTION.md",
                "COMPARISON.md",
                "RESULT.md",
                "TRACE-*.jsonl",
                "MRX-NEXT-2026-11-07-REPORT.md",
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
            "MRX next open exploration authorized; evidence/hypothesis provenance and real E2E", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
