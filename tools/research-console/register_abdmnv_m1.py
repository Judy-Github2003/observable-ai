#!/usr/bin/env python3
"""Register ABDMNV-01 M1 (vertical slice) as a repair grant.

授权来源：研究负责人 2026-11-09 的直接开工指令与其交付的
`ABDMNV-01-M1-DeepSeek-Direct-Start-2026-11-09.zip`
（sha256 693ba834de35090fce254583f14a52170c7246208d8c43f56b39a4ede63b7864）。

M1 是 ABDMNV-01 的第一个增量切片；不挂 PACK-02，不复用旧授权。
本脚本只登记授权，不改历史任务状态，不写 ACCEPTED。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "ABDMNV-01-M1-2026-11-09"
PACKAGE_SHA = "693ba834de35090fce254583f14a52170c7246208d8c43f56b39a4ede63b7864"
PARENTS = [
    "A-R1", "B2", "B7", "B8",
    "D1", "D2", "D3", "D4", "D5",
    "RES-M01", "RES-M02", "RES-M03", "RES-M05", "RES-M06",
    "RES-N01", "RES-N02", "RES-N03", "RES-N04",
    "RES-V01", "RES-V02", "RES-V03", "RES-V04", "RES-V05", "RES-V06",
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
                "ABDMNV-01 M1 增量垂直切片（A+B+D+M+N+V 的 M1 基础层）："
                "概念 AST 与规范序列化、四值求值（Bool3+ERROR）、类型检查、关系成对测量接口、"
                "槽位提交与 R1–R6 评分、基础证据/版本字段与 T1–T5 公开 harness。"
                "只做定向测试，不跑历史全量回归；不开发完整学习器/隐藏考官/六臂正式实验；"
                "不改 B0 与任何历史冻结产物。"
            ),
            "approval_reference": (
                "Research-owner direct start instruction 2026-11-09 with "
                f"ABDMNV-01-M1-DeepSeek-Direct-Start ZIP (sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/e3/abdmnv/",
                "src/test/kotlin/logic/e3/abdmnv/",
                "tools/abdmnv/",
                "docs/research/abdmnv-01/",
                "build/abdmnv-*",
                "tools/research-console/register_abdmnv_m1.py",
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
            "ABDMNV-01 M1 vertical slice authorized (incremental, reuse-first)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
