#!/usr/bin/env python3
"""Archive PACK-01 and authorize the PACK-02 whitelist."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

PACK02 = {
    "id": "PACK-02",
    "title": "知识组合、多步演绎与跨任务复用",
    "status": "IN_PROGRESS",
    "tasks": ["C4", "RES-H01", "C8"],
    "authorized_by": "research_owner",
    "started_at": "2026-11-05",
    "authorized_action": "PACK-02",
    "boundary": "只允许 C4/RES-H01/C8 通过执行门禁；不自动启动 PACK-03",
}


def main():
    with server.WRITE_LOCK:
        state, _, _ = server.load_state()
        old = state.get("active_pack")
        if old:
            state.setdefault("archived_packs", []).append({
                "id": old.get("id"),
                "status": old.get("status"),
                "tasks": old.get("tasks", []),
                "patch_used": old.get("patch_used"),
                "exception": old.get("exception"),
                "archived_at": "2026-11-05",
                "legacy_issues": ["attribution text may predate acceptance", "document SHA in old report is historical"],
            })
        state["active_pack"] = PACK02
        state["authorized_action"] = "PACK-02"
        state["current_task_id"] = "C4"
        for tid in PACK02["tasks"]:
            task = server.find_task(state, tid)
            if not task:
                raise SystemExit("missing task " + tid)
            before = json.loads(json.dumps(task))
            task["status"] = "IN_PROGRESS"
            task["authorized_action"] = "PACK-02"
            task["pack_id"] = "PACK-02"
            task["patch_used"] = 0
            task["dependencies"] = list(dict.fromkeys(task.get("dependencies", []) + ["PACK-02: B0 Composer/Filter/Interpreter and active capability registry"]))
            task.setdefault("decision_history", []).append({
                "at": "2026-11-05",
                "decision": "READY -> IN_PROGRESS under PACK-02 authorization",
                "actor": "research_owner",
            })
            server.append_event("status_changed", "research_owner", f"{tid}: -> IN_PROGRESS (PACK-02)", before, task)
        server.append_event("authorization", "research_owner", "PACK-02 authorized: C4/RES-H01/C8", None, PACK02)
        server.save_state(state)
        print("PACK-02 authorized:", PACK02["tasks"])


if __name__ == "__main__":
    main()
