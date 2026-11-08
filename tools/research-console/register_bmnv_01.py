#!/usr/bin/env python3
"""Register BMNV-01 (single package, B+M+N+V) as a repair grant.

授权来源：研究负责人 2026-11-09 交付的
`Observable-AI-BMNV-01-SINGLE-2026-11-09.zip`
（sha256 d95152aa46cf3ab262c8a1b004e67b04bf15543d938575d0d7c16753326980a8）。

按研究协议要求：**不**挂 PACK-02（C4/C8/H01），只追加关联到 B2/B7/B8、
RES-M01/M05/M06、RES-N01/N02/N03、RES-V01/V02/V03/V05 这些既有研究条目。
本脚本只登记授权，不写 ACCEPTED，不改写任何历史任务状态。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "BMNV-01-SINGLE-2026-11-09"
PACKAGE_SHA = "d95152aa46cf3ab262c8a1b004e67b04bf15543d938575d0d7c16753326980a8"
PARENTS = [
    "B2", "B7", "B8",
    "RES-M01", "RES-M05", "RES-M06",
    "RES-N01", "RES-N02", "RES-N03",
    "RES-V01", "RES-V02", "RES-V03", "RES-V05",
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
                "BMNV-01（B+M+N+V 独立组合，不隶属 PACK-02）：整合 ChatGPT 参考学习器与可见考官，"
                "公开合同 11 项，学生+考官一次冻结，正式考试只运行一次（48 场景 × 4 臂 = 192 条），"
                "Python 审计与独立复核，结果包与提交。不含历史全量回归、不含 BMNV-02、"
                "不修改 GNJV-01/SD-03/PACK-02 历史冻结文件。"
            ),
            "approval_reference": (
                "Research-owner delivery of Observable-AI-BMNV-01-SINGLE-2026-11-09.zip "
                f"(sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/bmnv/",
                "src/test/kotlin/logic/e3/bmnv/",
                "tools/bmnv/",
                "docs/research/bmnv-01/",
                "build/bmnv-01-*",
                "tools/research-console/register_bmnv_01.py",
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
            "BMNV-01 single-package experiment authorized (B+M+N+V, independent of PACK-02)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK, "parents": PARENTS}, ensure_ascii=False))


if __name__ == "__main__":
    main()
