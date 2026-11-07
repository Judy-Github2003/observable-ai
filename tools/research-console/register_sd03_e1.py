#!/usr/bin/env python3
"""Register SD-03 E1 evidence composition after default authorization."""
import json, sys
from datetime import datetime, timezone
from pathlib import Path
HERE=Path(__file__).resolve().parent; sys.path.insert(0,str(HERE)); import server
TASK="SD-03-E1-EVIDENCE-COMPOSITION-2026-11-07"
def main():
 with server.WRITE_LOCK:
  state,_,_=server.load_state(); repairs=server.load_repairs()
  if any(r.get("repair_id")==TASK for r in repairs.get("repairs",[])): raise SystemExit("already registered")
  rec={"repair_id":TASK,"parent_pack_id":"PACK-02","parent_task_ids":["C4","C8","RES-H01"],
   "approved_scope":"SD-03 E1: evidence-driven conditional composition; LEGACY_10/R32_NESTED_12/EVIDENCE_DEPTH_1/EVIDENCE_DEPTH_4; real TRAIN/Challenge/HELDOUT. No SD-04, no full regression, no 160 formal exam.",
   "approval_reference":"Research-owner request 2026-11-07","authorized_by":"research_owner","allowed_task_id":TASK,
   "allowed_files_or_modules":["src/main/kotlin/logic/e3/pack02/SD03EvidenceCompositionE1.kt","src/test/kotlin/logic/e3/pack02/SD03E1*.kt","src/test/kotlin/logic/e3/pack02/SD03EvidenceCompositionE1ContractTest.kt","docs/research/sd03-e1-development-2026-11-07/"],
   "authorization_status":"ACTIVE","created_at":datetime.now(timezone.utc).isoformat(),"consumed_at":None,"audit_event_ids":[],"content_hash":""}
  rec["content_hash"]=server.repair_digest(rec)
  ev=server.append_event("repair_authorized","research_owner","SD-03 E1 authorized",None,rec)
  rec["audit_event_ids"]=[ev]; repairs["repairs"].append(rec); server.save_repairs(repairs)
  print(json.dumps({"event_id":ev,"repair":TASK,"status":"ACTIVE"},ensure_ascii=False))
if __name__=="__main__": main()
