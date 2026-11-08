#!/usr/bin/env python3
"""Register SD-03 E2.3V Phase A freeze after default authorization."""
import json, sys
from datetime import datetime, timezone
from pathlib import Path
HERE=Path(__file__).resolve().parent; sys.path.insert(0,str(HERE)); import server
TASK="SD-03-E23V-PHASE-A-FREEZE-2026-11-08"
def main():
 with server.WRITE_LOCK:
  _,_,_=server.load_state(); repairs=server.load_repairs()
  if any(r.get("repair_id")==TASK for r in repairs.get("repairs",[])): raise SystemExit("already registered")
  rec={"repair_id":TASK,"parent_pack_id":"PACK-02","parent_task_ids":["C4","C8","RES-H01"],
   "approved_scope":"SD-03 E2.3V Phase A: learner source freeze, three targeted quick tests, FREEZE.json commit. No Phase B, no full regression, no science scoring, no SD-04.",
   "approval_reference":"Research-owner request 2026-11-08","authorized_by":"research_owner","allowed_task_id":TASK,
   "allowed_files_or_modules":["docs/research/sd03-e23v-2026-11-08/FREEZE.json","tools/research-console/register_sd03_e23v.py"],
   "authorization_status":"ACTIVE","created_at":datetime.now(timezone.utc).isoformat(),"consumed_at":None,"audit_event_ids":[],"content_hash":""}
  rec["content_hash"]=server.repair_digest(rec)
  ev=server.append_event("repair_authorized","research_owner","SD-03 E2.3V Phase A authorized",None,rec)
  rec["audit_event_ids"]=[ev]; repairs["repairs"].append(rec); server.save_repairs(repairs)
  print(json.dumps({"event_id":ev,"repair":TASK,"status":"ACTIVE"},ensure_ascii=False))
if __name__=="__main__": main()
