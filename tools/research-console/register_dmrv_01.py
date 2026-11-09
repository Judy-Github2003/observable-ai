#!/usr/bin/env python3
"""Register DMRV-01 (single package, D+M+R+V) as a repair grant.

授权来源：研究负责人 2026-11-09 交付的
`Observable-AI-DMRV-01-SINGLE-2026-11-09.zip`
（sha256 3ebc480206ff0228ec00c76e5f5306db7dfc1e4baf233b44ec407667e3cae5a9）。

按执行指令：登记独立 DMRV-01 研究包，关联 D1/D3/D4/D7、RES-M01/M05/M06、
RES-R01/R02/R05、RES-V01/V02/V03/V04/V05；**不挂 PACK-02**，不改历史任务状态。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "DMRV-01-SINGLE-2026-11-09"
PACKAGE_SHA = "3ebc480206ff0228ec00c76e5f5306db7dfc1e4baf233b44ec407667e3cae5a9"
PARENTS = [
    "D1", "D3", "D4", "D7",
    "RES-M01", "RES-M05", "RES-M06",
    "RES-R01", "RES-R02", "RES-R05",
    "RES-V01", "RES-V02", "RES-V03", "RES-V04", "RES-V05",
]


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        missing = [t for t in PARENTS if not server.find_task(state, t)]
        if missing:
            raise SystemExit("missing research tasks: " + ", ".join(missing))
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "",
            "parent_task_ids": PARENTS,
            "approved_scope": (
                "DMRV-01（D+M+R+V 独立组合，不隶属 PACK-02）：整合 ChatGPT 参考学习器与可见考官，"
                "公开契约 14 项，学生+考官+契约一次冻结，正式考试只运行一次"
                "（192 元训练任务 / 128 元测试任务 / 6 臂 / 768 条学生记录），"
                "Python 独立审计与结果包提交。不含历史全量回归、不含 DMRV-02、"
                "不修改 GNJV/BMNV/SD-03/PACK 历史冻结文件。"
            ),
            "approval_reference": (
                "Research-owner delivery of Observable-AI-DMRV-01-SINGLE-2026-11-09.zip "
                f"(sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/dmrv/",
                "src/test/kotlin/logic/e3/dmrv/",
                "tools/dmrv/",
                "docs/research/dmrv-01/",
                "build/dmrv-01-*",
                "tools/research-console/register_dmrv_01.py",
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
            "DMRV-01 single-package experiment authorized (D+M+R+V, independent of PACK-02)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK, "parents": PARENTS}, ensure_ascii=False))


if __name__ == "__main__":
    main()
