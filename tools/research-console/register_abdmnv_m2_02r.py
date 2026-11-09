#!/usr/bin/env python3
"""Register ABDMNV-01 M2-02r (schema/query-isolation/budget/evidence) as a repair grant.

授权来源：研究负责人 2026-11-09 的 M2-02r 指令（无单独 ZIP，指令文本即授权），
依据上一轮交付 `ABDMNV01-M2-02-Fix-2026-11-09.zip`
（sha256 8ae94ea4904b93408ac72b7c8cea9b5f8e329adace36c96af19dc58e1c4afc74）。
范围：独立 FiniteDomainSchema、查询池与封存测试隔离、真实预算计费、查询证据入账；
复用 M1/M2，不做新沙箱/隐藏考官/全量回归。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "ABDMNV-01-M2-02R-2026-11-09"
BASIS_SHA = "8ae94ea4904b93408ac72b7c8cea9b5f8e329adace36c96af19dc58e1c4afc74"
PARENTS = ["A-R1", "B2", "B7", "RES-M01", "RES-M05", "RES-N01", "RES-N02", "RES-V03"]


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
                "ABDMNV-01 M2-02r：独立声明的 FiniteDomainSchema（不从训练推断）、"
                "查询池与封存测试组合严格隔离、候选/探针/查询/评分全量计费、查询证据入账（PUBLIC_QUERY 非签名）；"
                "定向测试 + 448×2 实验。不动 M1/历史冻结；不做隐藏考官/六臂/全量回归。"
            ),
            "approval_reference": (
                "Research-owner M2-02r instruction 2026-11-09, basis "
                f"ABDMNV01-M2-02-Fix-2026-11-09.zip (sha256 {BASIS_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/abdmnv-01/",
                "build/abdmnv-m2-02r-*",
                "tools/research-console/register_abdmnv_m2_02r.py",
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
            "ABDMNV-01 M2-02r schema/query/budget/evidence round authorized",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
