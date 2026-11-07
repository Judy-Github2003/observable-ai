#!/usr/bin/env python3
"""Register SD-03 E2.2.1 corrections/regression after default authorization."""
import json, sys
from datetime import datetime, timezone
from pathlib import Path
HERE=Path(__file__).resolve().parent; sys.path.insert(0,str(HERE)); import server
TASK="SD-03-E221-AUDIT-CORRECTIONS-REGRESSION-2026-11-07"
def main():
 with server.WRITE_LOCK:
  _,_,_=server.load_state(); repairs=server.load_repairs()
  if any(r.get("repair_id")==TASK for r in repairs.get("repairs",[])): raise SystemExit("already registered")
  rec={"repair_id":TASK,"parent_pack_id":"PACK-02","parent_task_ids":["C4","C8","RES-H01"],
   "approved_scope":"SD-03 E2.2.1: historical correction audit, report errata, regression preflight/timing. No E2.3, no strategy change, no formal 160-exam.",
   "approval_reference":"Research-owner request 2026-11-07","authorized_by":"research_owner","allowed_task_id":TASK,
   "allowed_files_or_modules":["tools/e221/","docs/research/sd03-e22-development-2026-11-07/E22.1-ERRATA.md","docs/research/sd03-e22-development-2026-11-07/RESULT.md","build/e221/","build/e221-regression-logs/"],
   "authorization_status":"ACTIVE","created_at":datetime.now(timezone.utc).isoformat(),"consumed_at":None,"audit_event_ids":[],"content_hash":""}
  rec["content_hash"]=server.repair_digest(rec)
  ev=server.append_event("repair_authorized","research_owner","SD-03 E2.2.1 authorized",None,rec)
  rec["audit_event_ids"]=[ev]; repairs["repairs"].append(rec); server.save_repairs(repairs)
  print(json.dumps({"event_id":ev,"repair":TASK,"status":"ACTIVE"},ensure_ascii=False))
if __name__=="__main__": main()
