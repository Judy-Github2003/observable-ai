#!/usr/bin/env python3
"""Register GNJV-01 Phase A (integration + public contract + one-time freeze).

授权来源是研究负责人 2026-11-08 交付的 ZIP
`Observable-AI-GNJV-01-PHASE-A-STUDENT-FREEZE-2026-11-08.zip`
（sha256 ec366e4d4947996fe6f510dead5d4e6d09f1b45cc911335d165b2be88cee5025）。
本脚本只登记授权记录，不写 ACCEPTED；验收裁定仍属于研究负责人。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "GNJV-01-PHASE-A-FREEZE-2026-11-08"
PACKAGE_SHA = "ec366e4d4947996fe6f510dead5d4e6d09f1b45cc911335d165b2be88cee5025"


def main():
    with server.WRITE_LOCK:
        _, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "PACK-02",
            "parent_task_ids": ["C4", "C8", "RES-H01"],
            "approved_scope": (
                "GNJV-01 Phase A: 整合 ChatGPT 参考学习器与公开合同测试，定向 quickTest，"
                "一次性 freeze_learner.py 写入 docs/research/gnjv-01/FREEZE.json 并提交。"
                "不含 Phase B、不含考官源码、不含历史全量回归、不含 SD-04。"
            ),
            "approval_reference": (
                "Research-owner delivery of Observable-AI-GNJV-01-PHASE-A-STUDENT-FREEZE-2026-11-08.zip "
                f"on 2026-11-08 (sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/gnjv/GNJV01Kernel.kt",
                "src/test/kotlin/logic/e3/gnjv/GNJV01PublicContractTest.kt",
                "tools/gnjv/",
                "docs/research/gnjv-01/",
                "tools/research-console/register_gnjv_01.py",
            ],
            "authorization_status": "ACTIVE",
            "created_at": datetime.now(timezone.utc).isoformat(),
            "consumed_at": None,
            "audit_event_ids": [],
            "content_hash": "",
        }
        record["content_hash"] = server.repair_digest(record)
        event_id = server.append_event(
            "repair_authorized",
            "research_owner",
            "GNJV-01 Phase A authorized (ZIP delivered by research owner)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
