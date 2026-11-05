#!/usr/bin/env python3
"""Record the truthful PACK-02 patch outcome: C4/C8 paused, H01 review."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        pack = state.get("active_pack") or {}
        for tid in ("C4", "C8"):
            task = server.find_task(state, tid)
            before = json.loads(json.dumps(task))
            task["status"] = "PAUSED"
            task["authorized_action"] = "none"
            task["patch_used"] = 1
            task["known_limitations"] = [
                "原训练/留出配置下 R 未能 IDENTIFIED；未强行激活",
                "扩展样本只作诊断，不作为正式结果",
                "independent_closed_book=not_run",
            ]
            task.setdefault("decision_history", []).append({
                "at": "2026-11-05", "decision": "PACK-02 ordinary patch 1/1; PAUSED due real ambiguity", "actor": "research_owner",
            })
            server.append_event("paused", "research_owner", f"{tid}: PAUSED after patch; honest failure", before, task)
        h = server.find_task(state, "RES-H01")
        if h:
            before = json.loads(json.dumps(h))
            h["status"] = "REVIEW"
            h["authorized_action"] = "none"
            h["patch_used"] = 1
            h.setdefault("decision_history", []).append({
                "at": "2026-11-05", "decision": "PACK-02 patch; H01 remains REVIEW", "actor": "research_owner",
            })
            server.append_event("status_changed", "research_owner", "RES-H01: REVIEW after patch", before, h)
        pack["status"] = "PAUSED"
        pack["patch_used"] = "1/1"
        state["active_pack"] = pack
        state["authorized_action"] = "none"
        state["current_task_id"] = "C4"
        server.save_state(state)
        print("PACK-02 truthful patch state recorded")


if __name__ == "__main__":
    main()
