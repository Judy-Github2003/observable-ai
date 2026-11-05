#!/usr/bin/env python3
"""Record the PACK-01 authorization in the authoritative task state."""
import json
from pathlib import Path

p = Path(__file__).resolve().parents[2] / "research-console/tasks.json"
d = json.loads(p.read_text(encoding="utf-8"))
pack = {
    "id": "PACK-01",
    "title": "多猜想、主动实验与反例修正",
    "status": "IN_PROGRESS",
    "tasks": ["D2", "RES-N02", "A-R1"],
    "authorized_by": "research_owner",
    "started_at": "2026-11-05",
    "authorized_action": "PACK-01",
    "boundary": "只允许包内三项任务通过执行门禁；不自动启动其他任务",
}
d["active_pack"] = pack
d["authorized_action"] = "PACK-01"
d["current_task_id"] = "D2"
for t in d["tasks"]:
    if t["id"] in pack["tasks"]:
        t["status"] = "IN_PROGRESS"
        t["authorized_action"] = "PACK-01"
        t["pack_id"] = "PACK-01"
        t["known_implementation"] = ["src/main/kotlin/logic/e3/pack01/Pack01Session.kt"]
        t.setdefault("decision_history", []).append({
            "at": "2026-11-05",
            "decision": "authorized in PACK-01; independent task status retained",
            "actor": "research_owner",
        })
        t.setdefault("dependencies", []).append("existing B0 Composer/Filter/Interpreter; A1 query order")
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("PACK-01 recorded")
