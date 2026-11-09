#!/usr/bin/env python3
"""Register ABDMNV-01 M1 gates-fix round as a repair grant.

授权来源：研究负责人 2026-11-09 交付的
`ABDMNV01-M1-Fix-Audit-Gates-2026-11-09.zip`
（sha256 61e078629a4799744269ccefa9f424e0917125abf8c281176472c1866635f682）。

范围：只修 GATE-01..05（依赖激活门禁、撤销起始 ref 校验、跨 world 依赖、槽位 kind/sort、
受信来源签名），并补一条公开 held-out 演示。定向测试；不做隐藏考官/六臂/全量回归。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "ABDMNV-01-M1-GATES-2026-11-09"
PACKAGE_SHA = "61e078629a4799744269ccefa9f424e0917125abf8c281176472c1866635f682"
PARENTS = ["A-R1", "B2", "B7", "RES-M01", "RES-M05", "RES-V01", "RES-V02"]


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
                "ABDMNV-01 M1 修复轮 2：GATE-01 依赖激活门禁、GATE-02 撤销起始 ref 校验、"
                "GATE-03 跨 world 依赖禁止、GATE-04 槽位 kind/sort 校验、GATE-05 受信来源签名；"
                "并补公开 held-out 演示。只跑定向测试；不改 B0 与历史冻结；不做隐藏考官/全量回归。"
            ),
            "approval_reference": (
                "Research-owner gates audit 2026-11-09 with "
                f"ABDMNV01-M1-Fix-Audit-Gates ZIP (sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/abdmnv-01/",
                "build/abdmnv-gates-*",
                "tools/research-console/register_abdmnv_m1_gates.py",
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
            "ABDMNV-01 M1 gates round authorized (GATE-01..05)",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
