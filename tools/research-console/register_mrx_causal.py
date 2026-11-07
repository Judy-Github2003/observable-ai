#!/usr/bin/env python3
"""Register MRX-CAUSAL-ABLATION-2026-11-07 after the research-owner default authorization."""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "MRX-CAUSAL-ABLATION-2026-11-07"


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
                "MRX causal ablation: LIVE vs LIVE_FROZEN_BELIEF vs LIVE_NO_HYP decision sensitivity, "
                "scoped hypothesis semantics, real-prefix pair search where feasible, and honest L1/L2/L3 "
                "reporting. No 160-run formal exam, no full regression, no historical result changes."
            ),
            "approval_reference": (
                "Research-owner message 2026-11-07: '后续一律默认授权' and this package's execution request."
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/pack02/MRXLive*.kt",
                "src/main/kotlin/logic/e3/pack02/MRX2Student.kt",
                "src/test/kotlin/logic/e3/pack02/MRXLive*.kt",
                "docs/research/mrx-causal-ablation-2026-11-07/",
                "MRX-CAUSAL-ABLATION-2026-11-07-REPORT.md",
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
            "MRX causal ablation authorized", None, record,
        )
        record["audit_event_ids"] = [event]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
