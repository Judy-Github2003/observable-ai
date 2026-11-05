#!/usr/bin/env python3
"""Write the research-owner's PACK-01 acceptance/exception decision into state.

This is a maintenance operation, not a new experiment.  It preserves the old
events and appends new status/exception events.
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

EXCEPTION = {
    "exception_id": "PACK-01-EXCEPTION-01",
    "exception_limit": 1,
    "exception_used": 1,
    "scope": "console_state_and_gate_tests_only",
    "authorized_by": "research_owner",
    "used_at": "2026-11-05",
}
LIMITS = {
    "D2": ["受控小世界实验；仍可能有冗余或等价候选", "未证明普遍的自主科学发现", "independent_closed_book=not_run"],
    "RES-N02": ["确定性最小查询策略；未证明全局最优或效率优势", "受控小世界实验", "independent_closed_book=not_run"],
    "A-R1": ["候选集合可根据反例持续更新", "未完成已激活 B0 知识版本的完整替换闭环", "independent_closed_book=not_run"],
}


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        pack = state.get("active_pack") or {}
        for tid in ("D2", "RES-N02", "A-R1"):
            task = server.find_task(state, tid)
            if not task:
                raise SystemExit("missing task " + tid)
            before = json.loads(json.dumps(task))
            task["status"] = "ACCEPTED_LIMITED"
            task["authorized_action"] = "none"
            task["patch_used"] = 1
            task["known_limitations"] = LIMITS[tid]
            task["evidence_links"] = list(dict.fromkeys(
                task.get("evidence_links", []) + [
                    "experiments/representation-evolution-e3/reports/E3-PACK01-REPORT.md",
                    "experiments/representation-evolution-e3/reports/pack01-run.json",
                ]
            ))
            task.setdefault("decision_history", []).append({
                "at": "2026-11-05",
                "decision": "ACCEPTED_LIMITED by research_owner; PACK-01-EXCEPTION-01 used for console state/gate tests",
                "actor": "research_owner",
            })
            server.append_event("acceptance", "research_owner", f"{tid}: REVIEW -> ACCEPTED_LIMITED", before, task)
        pack["status"] = "ACCEPTED_LIMITED"
        pack["patch_used"] = "1/1"
        pack["exception"] = EXCEPTION
        state["active_pack"] = pack
        state["authorized_action"] = "none"
        state["current_task_id"] = "D2"
        server.append_event("exception_used", "research_owner", "PACK-01-EXCEPTION-01: 1/1 used", None, EXCEPTION)
        server.save_state(state)
        print("PACK-01 accepted_limited; exception used 1/1")


if __name__ == "__main__":
    main()
