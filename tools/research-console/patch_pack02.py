#!/usr/bin/env python3
"""Record PACK-02's ordinary patch usage and sync limitations/evidence."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

LIMITS = {
    "C4": ["复合 R 在受限假设空间内辨识；未声明穷尽", "未完成已激活知识版本替换闭环", "independent_closed_book=not_run"],
    "RES-H01": ["最小受限前向演绎；无通用量词/定理证明", "推理规则来源为开发者实验规则", "independent_closed_book=not_run"],
    "C8": ["全知识组仍 AMBIGUOUS；未形成唯一复合规则", "无 R 组未产生可评估预测，不记作 0%", "independent_closed_book=not_run"],
}


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        pack = state.get("active_pack") or {}
        for tid in ("C4", "RES-H01", "C8"):
            task = server.find_task(state, tid)
            if not task:
                raise SystemExit("missing " + tid)
            before = json.loads(json.dumps(task))
            task["status"] = "REVIEW"
            task["authorized_action"] = "none"
            task["patch_used"] = 1
            task["known_implementation"] = list(dict.fromkeys(task.get("known_implementation", []) + [
                "src/main/kotlin/logic/e3/pack02/Pack02Knowledge.kt",
                "src/main/kotlin/logic/e3/pack02/Deduction.kt",
                "src/main/kotlin/logic/e3/pack02/ExecutionFactAdapter.kt",
            ]))
            task["known_limitations"] = LIMITS[tid]
            task["evidence_links"] = list(dict.fromkeys(task.get("evidence_links", []) + [
                "experiments/representation-evolution-e3/reports/E3-PACK02-REPORT.md",
                "experiments/representation-evolution-e3/reports/pack02-run.json",
            ]))
            task.setdefault("decision_history", []).append({
                "at": "2026-11-05",
                "decision": "PACK-02 ordinary patch 1/1 used; status remains REVIEW",
                "actor": "research_owner",
            })
            server.append_event("patch_used", "execution", f"{tid}: ordinary patch 1/1", before, task)
        pack["status"] = "REVIEW"
        pack["patch_used"] = "1/1"
        state["active_pack"] = pack
        state["authorized_action"] = "none"
        state["current_task_id"] = "C4"
        server.save_state(state)
        print("PACK-02 patch recorded 1/1")


if __name__ == "__main__":
    main()
