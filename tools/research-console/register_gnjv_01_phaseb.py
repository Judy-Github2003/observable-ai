#!/usr/bin/env python3
"""Register GNJV-01 Phase B (sealed examiner run, once) as a repair grant.

授权来源：研究负责人 2026-11-08《GNJV-01 Phase-B 正式执行指令》及其交付的
`Observable-AI-GNJV-01-PHASE-B-SEALED-EXAMINER-2026-11-08.zip`
（sha256 aa6838728aac4c3c4c3cfd35170b6961ce6b4809abc0bd69f13f39789761c57c）。

与 Phase-A 不同，本记录**不**挂到 PACK-02：GNJV-01 是独立的 G+N+J+V 研究线。
Phase-A 记录中的 PACK-02 归属是已知登记错误，按负责人要求只在报告中注明，
并以追加式更正事件处理，不改写历史授权记录。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "GNJV-01-PHASE-B-SEALED-EXAM-2026-11-08"
EXAMINER_SHA = "aa6838728aac4c3c4c3cfd35170b6961ce6b4809abc0bd69f13f39789761c57c"


def main():
    with server.WRITE_LOCK:
        _, _, _ = server.load_state()
        repairs = server.load_repairs()
        if any(r.get("repair_id") == TASK for r in repairs.get("repairs", [])):
            raise SystemExit("already registered")
        record = {
            "repair_id": TASK,
            "parent_pack_id": "",
            "parent_task_ids": [],
            "approved_scope": (
                "GNJV-01 Phase B（独立 G+N+J+V 研究线，不隶属 PACK-02）："
                "集成封存考官源码，独立 Kotlin 编译入口执行一次 12 任务 × 4 臂正式考试，"
                "冻结 48 条学生输出后再评分，运行 audit_gnjv.py，保存原始证据与结果包并提交。"
                "不含历史全量回归、不含 SD-04/GNJV-02、不修改冻结学生源码或 FREEZE.json。"
            ),
            "approval_reference": (
                "Research-owner Phase-B execution instruction 2026-11-08 with sealed examiner ZIP "
                f"sha256 {EXAMINER_SHA}"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/test/kotlin/logic/e3/gnjv/GNJV01SealedExaminer.kt",
                "tools/gnjv/audit_gnjv.py",
                "tools/gnjv/verify_freeze.py",
                "docs/research/gnjv-01/",
                "build/gnjv01-phaseb-*",
                "tools/research-console/register_gnjv_01_phaseb.py",
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
            "GNJV-01 Phase B authorized (sealed examiner ZIP; independent research line)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK, "status": "ACTIVE"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
