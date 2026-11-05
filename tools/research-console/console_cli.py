#!/usr/bin/env python3
"""Controlled Research Console entry points.

Usage:
  python console_cli.py preflight --task C1
  python console_cli.py check-workstate
  python console_cli.py sync-workstate --write
  python console_cli.py finish --task C1 --commit <sha> --test "297/297" --report <path>
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import server  # noqa: E402


def fail(message: str, code: int = 2):
    print("REFUSED: " + message)
    raise SystemExit(code)


def gate(state, task_id: str | None, action: str | None = None, for_finish: bool = False):
    """Pure authorization gate used by preflight and finish."""
    task = server.find_task(state, task_id or state.get("current_task_id", ""))
    if not task:
        return False, "unknown task"
    authorized = state.get("authorized_action", "none")
    pack = state.get("active_pack")
    if pack:
        if task["id"] not in pack.get("tasks", []):
            return False, "task is not in active pack " + str(pack.get("id"))
        if task.get("authorized_action") != pack.get("id"):
            return False, "task has no pack authorization"
        if authorized != pack.get("id"):
            return False, "global authorization is not active pack " + str(pack.get("id"))
        if pack.get("status") not in {"IN_PROGRESS", "PATCH_ONCE"}:
            return False, "pack status " + str(pack.get("status")) + " cannot start/finish"
    else:
        if authorized == "none":
            return False, "no authorized_action in tasks.json"
        if task.get("authorized_action") != authorized:
            return False, "task authorization does not match global authorization"
    if action and authorized != action:
        return False, "authorized_action is " + authorized + ", not " + action
    if for_finish:
        if task.get("status") not in {"IN_PROGRESS", "PATCH_ONCE"}:
            return False, "task status " + str(task.get("status")) + " cannot finish"
    elif task.get("status") not in {"READY", "IN_PROGRESS", "PATCH_ONCE"}:
        return False, "task status " + str(task.get("status")) + " cannot start"
    if task.get("patch_used", 0) >= 1 and task.get("status") not in {"ACCEPTED", "ACCEPTED_LIMITED", "PAUSED"}:
        return False, "patch quota used"
    return True, None


def preflight(task_id: str | None = None, action: str | None = None):
    state, directions, _ = server.load_state()
    errors = server.validate_state(state, directions)
    if errors:
        fail("data invalid: " + "; ".join(errors))
    print(json.dumps({
        "git_head": server.git_info()["head"],
        "current_task_id": state.get("current_task_id"),
        "authorized_action": state.get("authorized_action"),
        "active_pack": state.get("active_pack"),
        "patch_used": None,
        "workstate": server.workstate_check(state),
    }, ensure_ascii=False, indent=2))
    ws = server.workstate_check(state)
    if not ws["ok"]:
        fail("WORKSTATE out of sync: " + "; ".join(ws.get("problems", [])))
    ok, reason = gate(state, task_id, action, for_finish=False)
    if not ok:
        fail(reason)
    print("AUTHORIZED: " + str(task_id or state.get("current_task_id")))


def render_workstate(state) -> str:
    tasks = {t["id"]: t for t in state["tasks"]}
    current = tasks.get(state.get("current_task_id", ""))
    lines = [
        "# E3 工作状态（Research Console 生成视图）",
        "",
        "```text",
        "E3 stage: E3-A",
        f"current_task: {current['title'] if current else 'none'}",
        f"research_direction: {current['direction'] if current else 'none'}",
        f"authorization: {state.get('authorized_action', 'none')}",
        f"last_verified_code_commit: {state.get('last_code_commit', 'TBD')}",
        f"base_b0_sha: {next((t.get('commit_sha') for t in state['tasks'] if t['id'] == 'B0'), '0ca4561309aeb914df6e3f2c3877a50b080bf731')}",
        "working_tree: see Research Console health",
        f"last_test_result: {state.get('last_test_result', 'TBD')}",
        "verification_source: implementer_run",
        f"independent_closed_book: {state.get('independent_closed_book', 'not_run')}",
    ]
    patched = [t for t in state["tasks"] if t.get("patch_used", 0) > 0]
    if patched:
        lines.append("patch_count: " + "; ".join(f"{t['id']} {t.get('patch_used')}/1" for t in patched))
    else:
        lines.append("patch_count: none")
    lines.append("test_task_status:")
    status_ids = ["A1", "C1"]
    pack = state.get("active_pack")
    if pack:
        status_ids += [tid for tid in pack.get("tasks", []) if tid not in status_ids]
    for tid in status_ids:
        if tid in tasks:
            lines.append(f"  {tid}: {tasks[tid]['status']} (patch {tasks[tid].get('patch_used', 0)}/1)")
    following = state.get("authorized_action", "none")
    lines += [
        "paused_reason: none",
        f"next_authorized_action: {following}" if following != "none" else "next_authorized_action: none; wait for research-owner authorization",
        "unauthorized: " + str(sum(1 for t in state["tasks"] if t.get("status") in {"PROPOSED", "EXPLORATORY", "READY"} and t.get("authorized_action", "none") == "none")) + " tasks without authorization",
        "```",
    ]
    if pack:
        lines += ["", f"## 研究包 {pack.get('id')} · {pack.get('status', 'TBD')}", ""]
        for tid in pack.get("tasks", []):
            task = tasks.get(tid)
            if task:
                lines.append(f"- {tid} · {task['title']} · {task['status']} · patch {task.get('patch_used', 0)}/1")
        use = pack.get("patch_used", "0/1")
        lines += ["", f"- 普通补丁: {use}"]
        exception = pack.get("exception")
        if exception:
            lines.append(f"- 例外: {exception.get('exception_id')} · used {exception.get('exception_used')}/{exception.get('exception_limit')}")
            lines.append(f"- 例外范围: {exception.get('scope')} · {exception.get('authorized_by')}")
        lines += ["", "> 包内任务独立状态；包整体 ACCEPTED_LIMITED 不代表独立闭卷通过。"]
    lines += [
        "> 本文件是 Research Console 状态的交接视图；权威状态为 `research-console/tasks.json`。",
        "",
        f"Generated: {datetime.now(timezone.utc).isoformat()}",
    ]
    return "\n".join(lines) + "\n"


def sync_workstate(write: bool):
    state, directions, _ = server.load_state()
    canonical = render_workstate(state)
    target = server.DATA / "generated" / "WORKSTATE.md"
    server.atomic_write(target, canonical)
    if write:
        actual = server.ROOT / "experiments/representation-evolution-e3/WORKSTATE.md"
        if actual.exists():
            backup = server.DATA / "history" / f"WORKSTATE-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.md"
            server.atomic_write(backup, actual.read_text(encoding="utf-8"))
        server.atomic_write(actual, canonical)
        print(f"wrote {actual}")
    else:
        print(f"wrote {target} (use --write to update human WORKSTATE)")


def finish(task_id: str, commit: str, test: str, report: str):
    with server.WRITE_LOCK:
        state, directions, _ = server.load_state()
        task = server.find_task(state, task_id)
        if not task:
            fail("unknown task")
        ok, reason = gate(state, task_id, for_finish=True)
        if not ok:
            fail(reason)
        before = json.loads(json.dumps(task))
        task["status"] = "REVIEW"
        task["commit_sha"] = commit
        task["evidence_links"] = list(dict.fromkeys(task.get("evidence_links", []) + [report]))
        task.setdefault("decision_history", []).append({
            "at": datetime.now(timezone.utc).isoformat(),
            "decision": "implementation finished; REVIEW; independent acceptance not implied",
            "actor": "execution",
        })
        state["last_code_commit"] = commit
        state["last_test_result"] = test
        pack = state.get("active_pack")
        if pack:
            pack["status"] = "REVIEW" if all(
                (server.find_task(state, tid) or {}).get("status") == "REVIEW" for tid in pack.get("tasks", [])
            ) else "IN_PROGRESS"
            if pack["status"] == "REVIEW":
                state["authorized_action"] = "none"
        event_id = server.append_event("implementation_finished", "execution", f"{task_id} -> REVIEW", before, task)
        server.save_state(state)
        sync_workstate(write=True)
        print(json.dumps({"event_id": event_id, "task": task["id"], "status": task["status"]}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("preflight")
    p.add_argument("--task")
    p.add_argument("--action")
    sub.add_parser("check-workstate")
    s = sub.add_parser("sync-workstate")
    s.add_argument("--write", action="store_true")
    f = sub.add_parser("finish")
    f.add_argument("--task", required=True)
    f.add_argument("--commit", required=True)
    f.add_argument("--test", required=True)
    f.add_argument("--report", required=True)
    args = parser.parse_args()
    if args.command == "preflight":
        preflight(args.task, args.action)
    elif args.command == "check-workstate":
        state, directions, _ = server.load_state()
        print(json.dumps(server.workstate_check(state), ensure_ascii=False, indent=2))
        raise SystemExit(0 if server.workstate_check(state)["ok"] else 1)
    elif args.command == "sync-workstate":
        sync_workstate(args.write)
    elif args.command == "finish":
        finish(args.task, args.commit, args.test, args.report)


if __name__ == "__main__":
    main()
