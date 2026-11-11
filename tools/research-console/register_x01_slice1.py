#!/usr/bin/env python3
"""Register X01-SLICE1-2026-11-11 as a repair grant.

授权来源：研究负责人 2026-11-11（DeepSeek 会话中批准"X01-SLICE1 全量"）。
输入：`Observable-AI-X01-SLICE1-Start-2026-11-11.zip`
（sha256 77ffc4ed083a3238994e64a24d07f614ecf428365bb8b50aa0d30f4fb902df2d），
该包自述不是授权，仅作任务书与参考实现。

范围：vendor 13 份 M1 参考源（原样只读副本，路径 `src/main/kotlin/observable/ai/abdmnv/m1/`）；
新增 `logic/x01` 桥接（Term→B0 Expr 受限编译，不支持即拒绝）；新增契约测试与真实 B0 SQLite
临时库 smoke（propose → verify → activate → executeActive → revoke 后拒绝）。

边界：不改 `logic/capability/*` 与 M1 冻结正文；不写生产数据库；不跑历史全量回归；
不启动 X01 八臂/独立闭卷；失败保留原貌，不修改旧成绩。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "X01-SLICE1-2026-11-11"
PKG_SHA = "77ffc4ed083a3238994e64a24d07f614ecf428365bb8b50aa0d30f4fb902df2d"
SUPP_SHA = "c1c86176b5547392b5c534a729c5f850478ffc2ef8c5399831ecd7fbd08602f3"
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
                "X01-SLICE1：vendor 13 份 M1 参考源（原样只读副本）；新增 logic/x01 桥接"
                "（Category/Bool/单参数，Term->B0 Expr 受限编译，不支持即拒绝）；"
                "契约测试 + 真实 B0 临时 SQLite smoke（propose→verify→activate→executeActive→revoke 拒绝）；"
                "不改 B0/M1 冻结源，不写生产库，不跑全量回归，不启动八臂/独立闭卷。"
            ),
            "approval_reference": (
                "Research-owner instruction 2026-11-11 (DeepSeek session: approved X01-SLICE1 full scope), "
                f"slice-1 package sha256 {PKG_SHA}, supplement package basis {SUPP_SHA}"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "src/main/kotlin/logic/x01/",
                "src/main/kotlin/observable/ai/abdmnv/m1/",
                "src/test/kotlin/logic/x01/",
                "docs/research/x01/",
                "build/x01-slice1-*",
                "tools/research-console/register_x01_slice1.py",
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
            "X01 slice-1 (M1->B0 bridge + real SQLite smoke) authorized",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
