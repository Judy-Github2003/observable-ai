#!/usr/bin/env python3
"""Register FMPS021-AUDIT-SUPPLEMENT-2026-11-11 as a repair grant.

授权来源：研究负责人 2026-11-11 的明确指示（在 DeepSeek Harness 会话中选择"批准 A+B+C+D 全量"）。
输入依据：外部补充包 `Observable-AI-FMPS021-FairFix-X01-Prep-2026-11-11.zip`
（sha256 c1c86176b5547392b5c534a729c5f850478ffc2ef8c5399831ecd7fbd08602f3），该包本身声明不是授权。

范围：A 复核补充包与本地交付（46 文件、旧测试保真、历史快照）；B 无提示盲基线 16 seeds×2 modes×3
算法复跑并与 oracle-hint 旧表分列；C 文档勘误（新增文件，不静默改旧报告）；D X-01 只读 adapter
审计（真实 B0 签名映射、冻结边界、下一轮授权建议）。

边界：不改 B0/M1 冻结源，不把 adapter 写进生产路径，不跑历史全量回归，不启动 X-01 八臂实验，
不改写 11-10 旧成绩。仓库只写 `docs/research/fmps-021/` 与登记脚本。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "FMPS021-AUDIT-SUPPLEMENT-2026-11-11"
PKG_SHA = "c1c86176b5547392b5c534a729c5f850478ffc2ef8c5399831ecd7fbd08602f3"
PREV_ZIP_SHA = "3cf1eac2769c0d623e76796205a7c980989bc0fba87f3d33e0ce323fb0229770"
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
                "FMPS-02.1 补充审计（A-D）：A 复核外部补充包与本地交付（41 旧文件逐字节对照、旧测试保真、"
                "历史快照摘要）；B 无提示盲基线 16 seeds×2 modes×3 算法复跑，与 oracle-hint 旧表分列，"
                "确定性复现；C 文档勘误（新增文件，不静默覆盖旧报告）；D X-01 只读 adapter 审计"
                "（真实 B0 签名映射、冻结边界、NEXT-AUTHORIZATION）。不改 B0/M1，不入生产路径，"
                "不跑历史全量回归，不启动 X-01 八臂实验，不改写旧成绩。"
            ),
            "approval_reference": (
                "Research-owner instruction 2026-11-11 (DeepSeek session: approved A+B+C+D), "
                f"supplement package sha256 {PKG_SHA}, previous closure zip sha256 {PREV_ZIP_SHA}"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/fmps-021/",
                "build/fmps021-supplement-*",
                "tools/research-console/register_fmps021_supplement.py",
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
            "FMPS-02.1 supplement (audit + blind baselines + errata + X-01 read-only prep) authorized",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
