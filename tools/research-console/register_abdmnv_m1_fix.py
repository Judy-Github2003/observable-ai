#!/usr/bin/env python3
"""Register ABDMNV-01 M1 fix round as a repair grant.

授权来源：研究负责人 2026-11-09《M1 修复》指令与其交付的
`ABDMNV01-M1-Combined-Audit-Fix-Package-2026-11-09.zip`
（sha256 d3e350656462205614398e6ab934d5dc26445965a39f78503af79b03d5e3e70c）。

范围：只修 M1 聚合包的 8 项缺陷（哈希绑定、依赖 pinned hash、环/缺失依赖、world 隔离、
端到端链路、学生固定 id、黄金样例自包含、source_kind 策略）。不重建系统、不做新考官、
不跑全量回归。修复代码在独立工作目录产出并以 ZIP 交付，仓库只登记授权与结果说明。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "ABDMNV-01-M1-FIX-2026-11-09"
PACKAGE_SHA = "d3e350656462205614398e6ab934d5dc26445965a39f78503af79b03d5e3e70c"
PARENTS = ["A-R1", "B2", "B7", "B8", "RES-M01", "RES-M05", "RES-M06", "RES-V01", "RES-V02", "RES-V03"]


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
                "ABDMNV-01 M1 修复轮：修复聚合包的版本哈希绑定、依赖 pinned hash、循环/缺失依赖、"
                "证据 world 隔离、学生固定 ID、黄金样例自包含，并把学生/账本/版本/槽位/评分接成端到端公开链路。"
                "只跑定向测试；不重建系统、不做隐藏考官、不跑历史全量回归；不改 B0 与历史冻结产物。"
            ),
            "approval_reference": (
                "Research-owner M1 fix instruction 2026-11-09 with audit package "
                f"(sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/abdmnv-01/",
                "build/abdmnv-fix-*",
                "tools/research-console/register_abdmnv_m1_fix.py",
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
            "ABDMNV-01 M1 fix round authorized (audit defects A-H)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
