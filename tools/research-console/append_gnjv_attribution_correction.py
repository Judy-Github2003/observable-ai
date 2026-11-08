#!/usr/bin/env python3
"""Append-only correction of the GNJV-01 Phase-A research attribution.

研究负责人 2026-11-08 指出：Phase-A 授权记录把 GNJV-01 登记为 PACK-02 的子任务
（parent_task_ids=[C4,C8,RES-H01]），但 GNJV-01 实际是独立的 G+N+J+V 研究线。
按指示：不改写历史授权记录，只追加一条更正事件；该事件不属于任何 repair grant。
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402

PHASE_A_RECORD = "GNJV-01-PHASE-A-FREEZE-2026-11-08"
PHASE_A_EVENT = "ev-1794151822937-1ef4d9"
PHASE_B_RECORD = "GNJV-01-PHASE-B-SEALED-EXAM-2026-11-08"


def main():
    with server.WRITE_LOCK:
        repairs = server.load_repairs()
        if any(
            e.get("kind") == "attribution_correction" and PHASE_A_EVENT in json.dumps(e, ensure_ascii=False)
            for e in [
                json.loads(p.read_text(encoding="utf-8"))
                for p in (server.HISTORY).glob("*.json")
            ]
        ):
            raise SystemExit("correction already recorded")
        event_id = server.append_event(
            "attribution_correction",
            "execution",
            "GNJV-01 is an independent G+N+J+V research line; PACK-02 parentage in the Phase-A grant was a registration error",
            None,
            {
                "corrects_repair_id": PHASE_A_RECORD,
                "corrects_event_id": PHASE_A_EVENT,
                "issue": "parent_pack_id=PACK-02 and parent_task_ids=[C4,C8,RES-H01] mis-attribute GNJV-01",
                "correction": "GNJV-01 belongs to no existing pack task; Phase-B grant records parent_pack_id='' and parent_task_ids=[]",
                "applies_to": [PHASE_A_RECORD, PHASE_B_RECORD],
                "historical_record_rewritten": False,
                "requested_by": "research_owner 2026-11-08 (Phase-B execution instruction)",
            },
        )
        print(json.dumps({"event_id": event_id, "kind": "attribution_correction"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
