#!/usr/bin/env python3
"""Register ABDMNV-01 M2-02 (identifiability + public query) as a repair grant.

授权来源：研究负责人 2026-11-09 交付的
`M2-01-Partial-Coverage-Review-and-Fix-2026-11-09.zip`
（sha256 301eb23a213275b6fe1c359e191f47de60d0cb4b3e39289a5eb4dd62d6fd4658）。
范围：只改 M2 学生（语义等价类分组 + 受控公开查询 + 不可辨识时弃答）与实验/测试，
不动 M1、不重建执行器、不做隐藏考官/全量回归。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "ABDMNV-01-M2-02-2026-11-09"
PACKAGE_SHA = "301eb23a213275b6fe1c359e191f47de60d0cb4b3e39289a5eb4dd62d6fd4658"
PARENTS = ["A-R1", "B2", "B7", "RES-M01", "RES-M05", "RES-N01", "RES-N02"]


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
                "ABDMNV-01 M2-02：候选可辨识性（按预注册探针的行为指纹分语义等价类）、"
                "受控公开查询（按候选分歧选实例）、不可辨识时 UNDETERMINED/ABSTAIN；"
                "新增 5-of-8 部分覆盖实验与超语法负世界。只跑定向测试；不动 M1 与历史冻结。"
            ),
            "approval_reference": (
                "Research-owner M2-02 instruction 2026-11-09 with partial-coverage audit "
                f"(sha256 {PACKAGE_SHA})"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/abdmnv-01/",
                "build/abdmnv-m2-*",
                "tools/research-console/register_abdmnv_m2_02.py",
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
            "ABDMNV-01 M2-02 identifiability and query round authorized",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
