#!/usr/bin/env python3
"""Register FMPS021-CLOSURE-2026-11-11 as a repair grant.

授权来源：研究负责人 2026-11-11 的明确指示（在 DeepSeek Harness 会话中选定"批准 B+C 全量"），
依据外部工作包 `Observable-AI-FMPS021-Closure-Kit-2026-11-11.zip`
（sha256 d94a5d28f639fa0a569b182fe63b188cca2e1dd43e6f8e00e80c5d5245400756，仅作任务书，
不构成授权）与官方参考包 `ObservableAI-FMPS-02.1-Fair-Baselines-Reference-2026-11-10.zip`
（sha256 392ba13b8024fb11229600fbc87c86fb2519d7cf9832b401d55be68795e7884c）。

范围（阶段 B+C）：官方 FMPS-02.1 源码最小修复（3 处语义修复，去掉 BOM/空行/绝对路径噪声）、
Kotlin 2.2.21 复编复跑、镜像与实跑差异定位、等预算 LOOKUP/TREE_DEPTH2/BERNOULLI_NB 对照
（分 object/combination 留出，记录 train/query/candidate/predict/total ops 与 wall_ms）、
只读集成端口设计（对照真实 CombinedVersionStore 签名）。

边界：不改 M1/B0 冻结源，不入主仓代码，不跑历史全量回归，不做隐藏考官/六臂/X-01，
不覆盖历史阴性结论（combination 0/22/10）。仓库只写 `docs/research/fmps-021/` 与登记脚本。
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

TASK = "FMPS021-CLOSURE-2026-11-11"
KIT_SHA = "d94a5d28f639fa0a569b182fe63b188cca2e1dd43e6f8e00e80c5d5245400756"
OFFICIAL_SHA = "392ba13b8024fb11229600fbc87c86fb2519d7cf9832b401d55be68795e7884c"
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
                "FMPS-02.1 收尾（阶段 B+C）：官方源码 3 处最小语义修复并复编复跑；镜像与实跑差异定位；"
                "等预算 LOOKUP/TREE_DEPTH2/BERNOULLI_NB 对照（object 与 combination 留出分列，"
                "记录 train/query/candidate/predict/total ops 与 wall_ms）；只读集成端口映射。"
                "不改 M1/B0，不入主仓代码，不跑历史全量回归，不做隐藏考官/六臂/X-01，不改写历史阴性结论。"
            ),
            "approval_reference": (
                "Research-owner instruction 2026-11-11 (DeepSeek session: approved B+C full scope), "
                f"kit sha256 {KIT_SHA}, official reference sha256 {OFFICIAL_SHA}"
            ),
            "authorized_by": "research_owner",
            "allowed_task_id": TASK,
            "allowed_files_or_modules": [
                "docs/research/fmps-021/",
                "build/fmps021-closure-*",
                "tools/research-console/register_fmps021_closure.py",
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
            "FMPS-02.1 closure round (fix + fair baselines + read-only ports) authorized",
            None,
            record,
        )
        record["audit_event_ids"] = [event_id]
        repairs["repairs"].append(record)
        server.save_repairs(repairs)
        print(json.dumps({"event_id": event_id, "repair": TASK}, ensure_ascii=False))


if __name__ == "__main__":
    main()
