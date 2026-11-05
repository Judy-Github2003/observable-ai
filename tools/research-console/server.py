#!/usr/bin/env python3
"""Observable AI Research Console: local, auditable planning state server.

Data source of truth: research-console/tasks.json (+ directions/ideas/events).
Only binds 127.0.0.1. Writes require a local token and a role header/field.
"""
from __future__ import annotations

import argparse
import difflib
import html
import json
import os
import re
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "research-console"
HISTORY = DATA / "history"
TOKEN = os.environ.get("RESEARCH_CONSOLE_TOKEN", "")
WRITE_LOCK = threading.RLock()

TASK_FIELDS = [
    "id", "aliases", "title", "direction", "research_question", "hypothesis", "goal",
    "status", "priority", "dependencies", "readiness_conditions", "known_implementation",
    "proposed_approaches", "minimal_experiment", "success_criteria", "failure_conditions",
    "attribution", "known_limitations", "patch_used", "commit_sha", "evidence_links",
    "decision_history", "next_possible_actions", "authorized_action",
]
EDITABLE_FIELDS = {
    "title", "research_question", "hypothesis", "goal", "dependencies",
    "readiness_conditions", "known_implementation", "proposed_approaches",
    "minimal_experiment", "success_criteria", "failure_conditions",
    "known_limitations", "evidence_links", "next_possible_actions",
}
STATUSES = {"PROPOSED", "EXPLORATORY", "READY", "IN_PROGRESS", "REVIEW", "PATCH_ONCE",
            "ACCEPTED", "ACCEPTED_LIMITED", "PAUSED"}
TRANSITIONS = {
    "PROPOSED": {"EXPLORATORY", "READY", "PAUSED"},
    "EXPLORATORY": {"READY", "PAUSED"},
    "READY": {"IN_PROGRESS", "PAUSED"},
    "IN_PROGRESS": {"REVIEW", "PAUSED"},
    "PATCH_ONCE": {"REVIEW", "PAUSED"},
    "REVIEW": {"ACCEPTED", "ACCEPTED_LIMITED", "PAUSED"},
}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_json(path: Path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def load_state():
    state = load_json(DATA / "tasks.json")
    directions = load_json(DATA / "directions.json", {"directions": []})
    ideas = load_json(DATA / "ideas.json", {"ideas": []})
    if not state or "tasks" not in state:
        raise RuntimeError("tasks.json missing or malformed")
    return state, directions, ideas


def save_state(state) -> None:
    state["updated_at"] = now()
    atomic_write(DATA / "tasks.json", json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def validate_state(state, directions) -> list[str]:
    errors = []
    tasks = state.get("tasks", [])
    ids = [t.get("id") for t in tasks]
    if len(ids) != len(set(ids)):
        errors.append("duplicate task ids")
    known = {d["code"] for d in directions.get("directions", [])}
    for task in tasks:
        for field in TASK_FIELDS:
            if field not in task:
                errors.append(f"{task.get('id')}: missing {field}")
        if task.get("direction") not in known:
            errors.append(f"{task.get('id')}: unknown direction")
        if task.get("status") not in STATUSES:
            errors.append(f"{task.get('id')}: invalid status")
    return errors


def git_info():
    def run(*args):
        try:
            result = subprocess.run(args, cwd=str(ROOT), capture_output=True, text=True, timeout=5)
            return result.stdout.strip()
        except Exception:
            return ""
    return {"head": run("git", "rev-parse", "HEAD"), "status": run("git", "status", "--short")}


def workstate_check(state):
    path = ROOT / "experiments/representation-evolution-e3/WORKSTATE.md"
    if not path.exists():
        return {"ok": False, "reason": "WORKSTATE.md missing"}
    text = path.read_text(encoding="utf-8", errors="replace")
    checks = {
        "code_commit": state.get("last_code_commit", ""),
        "test_result": state.get("last_test_result", ""),
        "current_task": state.get("current_task_id", ""),
    }
    problems = []
    if checks["code_commit"] and checks["code_commit"] not in text:
        problems.append("last_code_commit not present in WORKSTATE")
    if checks["test_result"] and checks["test_result"] not in text:
        problems.append("last_test_result not present in WORKSTATE")
    if checks["current_task"] and checks["current_task"] not in text:
        problems.append("current_task not present in WORKSTATE")
    return {"ok": not problems, "problems": problems, "path": str(path.relative_to(ROOT))}


def find_task(state, task_id):
    return next((t for t in state["tasks"] if t["id"] == task_id), None)


def append_event(kind, actor, note, before, after) -> str:
    HISTORY.mkdir(parents=True, exist_ok=True)
    event_id = f"ev-{int(time.time() * 1000)}-{secrets.token_hex(3)}"
    event = {
        "event_id": event_id,
        "at": now(),
        "kind": kind,
        "actor": actor,
        "note": note,
        "before": before,
        "after": after,
    }
    atomic_write(HISTORY / f"{event_id}.json", json.dumps(event, ensure_ascii=False, indent=2) + "\n")
    with (DATA / "events.jsonl").open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")
    return event_id


def event_list(limit=300):
    path = DATA / "events.jsonl"
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            rows.append(json.loads(line))
        except Exception:
            continue
    return list(reversed(rows[-limit:]))


def parse_form(body: bytes) -> dict:
    parsed = urllib.parse.parse_qs(body.decode("utf-8", errors="replace"), keep_blank_values=True)
    return {k: (v[0] if len(v) == 1 else v) for k, v in parsed.items()}


def esc(value) -> str:
    return html.escape(str(value), quote=True)


def badge(status: str) -> str:
    cls = re.sub(r"[^A-Za-z0-9_-]", "", status or "UNKNOWN")
    return f'<span class="badge {cls}">{esc(status)}</span>'


def page(title: str, body: str, state=None) -> bytes:
    current = state.get("current_task_id", "none") if state else "none"
    nav = """<nav>
      <a href="/">Dashboard</a>
      <a href="/map">Research Map</a>
      <a href="/ideas">Idea Inbox</a>
      <a href="/activity">Activity</a>
      <a href="/api/health">Health API</a>
    </nav>"""
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} · Observable AI Research Console</title>
<style>
:root{{--bg:#0f141b;--panel:#171f2a;--line:#2b3a4a;--text:#dce7f2;--muted:#8ea1b5;--accent:#68d391;--warn:#f6c177;--bad:#f28b82}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 ui-sans-serif,system-ui,Segoe UI,Microsoft YaHei,sans-serif}}
header{{background:#111923;border-bottom:1px solid var(--line);padding:14px 22px;display:flex;justify-content:space-between;gap:16px;align-items:center;flex-wrap:wrap}}
header strong{{font-size:18px}} nav a{{color:var(--muted);margin-right:14px;text-decoration:none}} nav a:hover{{color:var(--accent)}}
main{{max-width:1250px;margin:0 auto;padding:22px}} h1{{font-size:25px;margin:0 0 8px}} h2{{font-size:18px;margin-top:26px;border-top:1px solid var(--line);padding-top:16px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px}} .card{{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:15px}}
.card h3{{margin:0 0 8px;font-size:16px}} .muted{{color:var(--muted)}} .small{{font-size:12px}} .mono{{font-family:ui-monospace,Consolas,monospace}}
.badge{{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:1px 8px;font-size:12px;white-space:nowrap}}
.ACCEPTED,.ACCEPTED_LIMITED{{border-color:#3f8f68;color:#8ee6b0}} .PROPOSED,.EXPLORATORY{{border-color:#63758a;color:#b8c9da}} .READY{{border-color:#9a7b34;color:#f6c177}}
.IN_PROGRESS,.PATCH_ONCE,.REVIEW{{border-color:#4c78a8;color:#9fc7f0}} .PAUSED{{border-color:#a14b4b;color:#f2a7a7}}
table{{width:100%;border-collapse:collapse}} th,td{{border-bottom:1px solid var(--line);padding:8px;text-align:left;vertical-align:top}} th{{color:var(--muted);font-weight:500}}
a{{color:#9fc7f0}} input,textarea,select,button{{background:#0d141d;color:var(--text);border:1px solid var(--line);border-radius:6px;padding:8px;font:inherit}} button{{cursor:pointer;background:#1f3a30;border-color:#3f8f68}} button:hover{{background:#295044}}
textarea{{width:100%;min-height:80px}} label{{display:block;color:var(--muted);font-size:13px;margin-top:8px}} pre{{background:#0b1118;border:1px solid var(--line);padding:12px;overflow:auto;border-radius:8px;white-space:pre-wrap}}
.error{{border-color:#a14b4b;color:#f2a7a7}} .ok{{color:#8ee6b0}} .warn{{color:var(--warn)}} ul{{margin:6px 0 0 20px;padding:0}} code{{background:#0b1118;padding:1px 4px;border-radius:4px}}
</style></head><body>
<header><div><strong>Observable AI · Research Console</strong><div class="muted small">current task: {esc(current)} · data: Git-tracked JSON</div></div>{nav}</header>
<main>{body}</main>
<footer class="muted small" style="max-width:1250px;margin:20px auto;padding:0 22px">Read-only public view. Writes require local token + role; independent closed-book is not claimed.</footer>
</body></html>""".encode("utf-8")


def dashboard(state, directions, events):
    tasks = state["tasks"]
    counts = {}
    for task in tasks:
        counts[task["status"]] = counts.get(task["status"], 0) + 1
    current = find_task(state, state.get("current_task_id", ""))
    recent = []
    for event in events[:6]:
        after = event.get("after") or {}
        recent.append(f'<li><a href="/activity/{esc(event["event_id"])}">{esc(event["event_id"])}</a> · {esc(event["kind"])} · {esc(event.get("note", ""))}</li>')
    cards = [
        ("Current task", esc(current["title"]) if current else "none", f'<a href="/task/{esc(current["id"])}">{esc(current["id"])}</a>' if current else "no task"),
        ("Authorized action", esc(state.get("authorized_action", "none")), "No task may start without explicit authorization."),
        ("Independent closed book", esc(state.get("independent_closed_book", "not_run")), "Implementer test is not independent acceptance."),
        ("Tasks", f'{len(tasks)} across {len(directions.get("directions", []))} directions', "Status is authoritative in tasks.json."),
        ("Test record", esc(state.get("last_test_result", "TBD")), "implementer_run, not independent."),
        ("Working tree", esc((git_info()["status"] or "clean")[:500]), f'HEAD {esc(git_info()["head"][:12])}'),
    ]
    body = "<h1>Research Dashboard</h1><p class='muted'>这不是完成率仪表盘；状态、证据、授权和未知分开显示。</p><div class='grid'>"
    for title, value, note in cards:
        body += f"<div class='card'><div class='muted small'>{title}</div><h3>{value}</h3><div class='muted small'>{note}</div></div>"
    body += "</div><h2>Current / recent</h2><div class='grid'>"
    body += f"<div class='card'><h3>{esc(current['title']) if current else 'none'}</h3><p>{esc(current['research_question']) if current else ''}</p><p>{badge(current['status']) if current else ''} patch {esc(current['patch_used']) if current else ''}/1</p><p class='small'>{esc(current['known_limitations']) if current else ''}</p></div>"
    body += "<div class='card'><h3>最近事件</h3><ul>" + ("".join(recent) or "<li>暂无结构化事件；初始导入不冒充历史事件。</li>") + "</ul></div>"
    body += "</div><h2>可立即查看的候选</h2><div class='grid'>"
    for task in [t for t in tasks if t["status"] in ("READY", "EXPLORATORY") and t["priority"] == "high"][:6]:
        body += f"<div class='card'><a href='/task/{esc(task['id'])}'><strong>{esc(task['id'])} · {esc(task['title'])}</strong></a><p class='small'>{esc(task['research_question'])}</p><div>{badge(task['status'])}</div></div>"
    body += "</div><h2>状态摘要</h2><div class='card'><table><tr><th>Status</th><th>Count</th></tr>"
    for status in sorted(counts):
        body += f"<tr><td>{badge(status)}</td><td>{counts[status]}</td></tr>"
    body += "</table><p class='small muted'>没有“研究完成百分比”。Accepted 不等于 independent closed-book。</p></div>"
    return page("Dashboard", body, state)


def map_page(state, directions, query):
    status = query.get("status", [""])[0]
    direction = query.get("direction", [""])[0]
    q = query.get("q", [""])[0].lower()
    rows = []
    for d in directions["directions"]:
        tasks = [t for t in state["tasks"] if t["direction"] == d["code"]]
        if direction and d["code"] != direction:
            continue
        if status:
            tasks = [t for t in tasks if t["status"] == status]
        if q:
            tasks = [t for t in tasks if q in (t["id"] + " " + t["title"] + " " + t["research_question"]).lower()]
        if not tasks and (status or q):
            continue
        row = f"<details open><summary><strong>{esc(d['code'])} · {esc(d['name'])}</strong> <span class='muted small'>{len(tasks)} tasks</span></summary><p class='muted'>{esc(d['question'])}</p><table>"
        for t in sorted(tasks, key=lambda x: x["id"]):
            row += f"<tr><td><a href='/task/{esc(t['id'])}'>{esc(t['id'])}</a></td><td>{esc(t['title'])}</td><td>{badge(t['status'])}</td><td class='small'>{esc(', '.join(t['dependencies'])) or '—'}</td></tr>"
        row += "</table></details>"
        rows.append(row)
    form = """<form method="get" action="/map" class="card"><label>Search <input name="q" value="%s"></label><label>Direction <select name="direction"><option value="">all</option>%s</select></label><label>Status <select name="status"><option value="">all</option>%s</select></label><button>Filter</button></form>""" % (
        esc(q), "".join(f"<option value='{esc(d['code'])}' {'selected' if direction == d['code'] else ''}>{esc(d['code'])} {esc(d['name'])}</option>" for d in directions["directions"]),
        "".join(f"<option value='{esc(s)}' {'selected' if status == s else ''}>{esc(s)}</option>" for s in sorted(STATUSES)))
    return page("Research Map", "<h1>Research Map</h1><p class='muted'>方向不是串行阶段；筛选只改变视图。</p>" + form + "".join(rows), state)


def render_value(value):
    if isinstance(value, list):
        return "<ul>" + "".join(f"<li>{esc(v)}</li>" for v in value) + "</ul>" if value else "<span class='muted'>—</span>"
    if isinstance(value, dict):
        return f"<pre>{esc(json.dumps(value, ensure_ascii=False, indent=2))}</pre>"
    return esc(value) if value not in (None, "") else "<span class='muted'>TBD</span>"


def task_page(state, task, events):
    fields = ["research_question", "hypothesis", "goal", "status", "priority", "dependencies",
              "readiness_conditions", "known_implementation", "proposed_approaches",
              "minimal_experiment", "success_criteria", "failure_conditions", "attribution",
              "known_limitations", "patch_used", "commit_sha", "evidence_links",
              "decision_history", "next_possible_actions", "authorized_action"]
    body = f"<p><a href='/map'>← Research Map</a></p><h1>{esc(task['id'])} · {esc(task['title'])}</h1><p>{badge(task['status'])} <span class='muted'>direction {esc(task['direction'])} · priority {esc(task['priority'])} · patch {esc(task['patch_used'])}/1</span></p>"
    body += "<div class='grid'>"
    for field in fields:
        body += f"<div class='card'><div class='muted small'>{esc(field)}</div>{render_value(task.get(field))}</div>"
    body += "</div>"
    if task.get("evidence_links"):
        body += "<h2>证据链接</h2><div class='card'><ul>" + "".join(f"<li><code>{esc(x)}</code></li>" for x in task["evidence_links"]) + "</ul></div>"
    related = [e for e in events if (e.get("after") or {}).get("id") == task["id"] or (e.get("before") or {}).get("id") == task["id"]]
    body += "<h2>编辑规划（需要 token + planning 角色）</h2><form method='post' action='/task/" + esc(task["id"]) + "/edit' class='card'>"
    for field in ["title", "research_question", "hypothesis", "goal", "minimal_experiment", "known_limitations"]:
        val = task.get(field, "")
        if isinstance(val, list):
            val = "\n".join(val)
        body += f"<label>{esc(field)}<textarea name='{esc(field)}'>{esc(val)}</textarea></label>"
    body += "<label>token <input type='password' name='token'></label><label>role <input name='role' value='planning'></label><button>保存并记录历史</button></form>"
    body += "<h2>状态 / 授权（不同角色）</h2><div class='grid'><form method='post' action='/task/" + esc(task["id"]) + "/status' class='card'><label>新状态 <select name='status'>" + "".join(f"<option>{esc(s)}</option>" for s in sorted(STATUSES)) + "</select></label><label>token <input type='password' name='token'></label><label>role <input name='role' value='planning'></label><button>提交状态变化</button></form>"
    body += "<form method='post' action='/api/authorization' class='card'><label>授权 action <input name='action' value='' placeholder='例如 C1-followup'></label><label>token <input type='password' name='token'></label><label>role <input name='role' value='authorization'></label><button>记录授权</button></form></div>"
    if related:
        body += "<h2>本地历史</h2><div class='card'><ul>" + "".join(f"<li><a href='/activity/{esc(e['event_id'])}'>{esc(e['event_id'])}</a> {esc(e.get('kind',''))} {esc(e.get('note',''))}</li>" for e in related[-20:]) + "</ul></div>"
    return page(task["id"], body, state)


def ideas_page(state, ideas):
    body = "<h1>Idea Inbox</h1><p class='muted'>新想法先保存为 PROPOSED；批准前不创建正式任务，也不启动研究。</p>"
    body += "<form method='post' action='/ideas' class='card'><label>标题 <input name='title' required></label><label>原始想法 <textarea name='body' required></textarea></label><label>提出者 <input name='author' value='research_owner'></label><label>token <input type='password' name='token'></label><label>role <input name='role' value='planning'></label><button>加入 Idea Inbox</button></form>"
    body += "<h2>Ideas</h2>"
    for idea in reversed(ideas.get("ideas", [])):
        body += f"<div class='card'><strong>{esc(idea['title'])}</strong> {badge(idea.get('status','PROPOSED'))}<p>{esc(idea.get('body',''))}</p><p class='small muted'>{esc(idea.get('author',''))} · {esc(idea.get('created_at',''))} · id {esc(idea['id'])}</p></div>"
    return page("Idea Inbox", body, state)


def activity_page(state, events):
    body = "<h1>Activity Log</h1><p class='muted'>追加式事件；每个事件有 before/after 历史文件。初始导入不伪造历史事件。</p><table><tr><th>Time</th><th>Event</th><th>Kind</th><th>Note</th></tr>"
    for e in events:
        body += f"<tr><td class='small'>{esc(e.get('at',''))}</td><td><a href='/activity/{esc(e['event_id'])}'>{esc(e['event_id'])}</a></td><td>{esc(e.get('kind',''))}</td><td>{esc(e.get('note',''))}</td></tr>"
    body += "</table>"
    return page("Activity", body, state)


def history_page(event_id):
    path = HISTORY / f"{event_id}.json"
    if not path.exists():
        return None
    event = json.loads(path.read_text(encoding="utf-8"))
    before = json.dumps(event.get("before"), ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    after = json.dumps(event.get("after"), ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    diff = "\n".join(difflib.unified_diff(before, after, fromfile="before", tofile="after", lineterm=""))
    body = f"<p><a href='/activity'>← Activity</a></p><h1>{esc(event_id)}</h1><div class='card'><div class='muted'>{esc(event.get('at',''))} · {esc(event.get('kind',''))} · {esc(event.get('actor',''))}</div><p>{esc(event.get('note',''))}</p><pre>{esc(diff or 'no field diff')}</pre></div>"
    return page(event_id, body)


class Handler(BaseHTTPRequestHandler):
    server_version = "ResearchConsole/1.0"

    def log_message(self, fmt, *args):
        # Do not log request bodies or tokens.
        print("%s - %s" % (self.address_string(), fmt % args))

    def send_html(self, content: bytes, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error_page(self, message, status=400):
        state, _, _ = load_state()
        self.send_html(page("Error", f"<h1>Error</h1><div class='card error'>{esc(message)}</div><p><a href='/'>back</a></p>", state), status)

    def auth(self, payload):
        if not TOKEN:
            return None, "write disabled: RESEARCH_CONSOLE_TOKEN is not set"
        token = self.headers.get("X-Research-Token") or payload.get("token", "")
        if not secrets.compare_digest(str(token), TOKEN):
            return None, "unauthorized: bad or missing token"
        role = self.headers.get("X-Research-Role") or payload.get("role", "")
        if role not in {"planning", "execution", "authorization", "acceptance"}:
            return None, "forbidden: role required (planning/execution/authorization/acceptance)"
        return role, None

    def body_payload(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > 1_000_000:
            raise ValueError("body too large")
        raw = self.rfile.read(length) if length else b""
        ctype = self.headers.get("Content-Type", "")
        if "application/json" in ctype:
            return json.loads(raw.decode("utf-8") or "{}")
        return parse_form(raw)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = urllib.parse.parse_qs(parsed.query)
        try:
            state, directions, ideas = load_state()
            if path == "/api/health":
                git = git_info()
                errors = validate_state(state, directions)
                ws = workstate_check(state)
                self.send_json({"ok": not errors, "data_errors": errors, "workstate": ws,
                                "git": git, "state_git_head_at_import": state.get("git_head_at_import"),
                                "git_head_note": "import baseline; live HEAD may be newer after console/document commits",
                                "current_task_id": state.get("current_task_id"),
                                "authorized_action": state.get("authorized_action"),
                                "writes_enabled": bool(TOKEN), "task_count": len(state["tasks"]),
                                "direction_count": len(directions["directions"])})
                return
            if path == "/api/state":
                self.send_json({"current_task_id": state.get("current_task_id"), "authorized_action": state.get("authorized_action"),
                                "independent_closed_book": state.get("independent_closed_book"),
                                "directions": directions.get("directions", []), "tasks": state["tasks"]})
                return
            if path == "/api/tasks":
                rows = state["tasks"]
                for key in ("direction", "status"):
                    if query.get(key):
                        rows = [r for r in rows if r.get(key) == query[key][0]]
                if query.get("q"):
                    q = query["q"][0].lower()
                    rows = [r for r in rows if q in (r["id"] + " " + r["title"] + " " + r["research_question"]).lower()]
                self.send_json({"tasks": rows})
                return
            if path.startswith("/api/task/"):
                task = find_task(state, path.split("/")[-1])
                self.send_json({"task": task}) if task else self.send_json({"error": "not_found"}, 404)
                return
            if path == "/api/ideas":
                self.send_json(ideas)
                return
            if path == "/api/events":
                self.send_json({"events": event_list()})
                return
            if path == "/":
                self.send_html(dashboard(state, directions, event_list()))
                return
            if path == "/map":
                self.send_html(map_page(state, directions, query))
                return
            if path.startswith("/task/"):
                task = find_task(state, path.split("/")[-1])
                if not task:
                    self.error_page("task not found", 404)
                else:
                    self.send_html(task_page(state, task, event_list()))
                return
            if path == "/ideas":
                self.send_html(ideas_page(state, ideas))
                return
            if path == "/activity":
                self.send_html(activity_page(state, event_list()))
                return
            if path.startswith("/activity/"):
                event_id = path.split("/")[-1]
                if not re.fullmatch(r"ev-[A-Za-z0-9-]+", event_id):
                    self.error_page("invalid history event id", 400)
                    return
                content = history_page(event_id)
                if content is None:
                    self.error_page("history event not found", 404)
                else:
                    self.send_html(content)
                return
            self.error_page("not found", 404)
        except Exception as exc:
            self.error_page(f"server error: {exc}", 500)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        try:
            payload = self.body_payload()
            role, auth_error = self.auth(payload)
            if auth_error:
                self.send_json({"error": auth_error}, 401 if "unauthorized" in auth_error or "disabled" in auth_error else 403)
                return
            with WRITE_LOCK:
                state, directions, ideas = load_state()
                if path == "/ideas" or path == "/api/ideas":
                    if role not in {"planning"}:
                        self.send_json({"error": "planning role required"}, 403)
                        return
                    title = str(payload.get("title", "")).strip()
                    body = str(payload.get("body", "")).strip()
                    if not title or not body:
                        self.send_json({"error": "title and body required"}, 400)
                        return
                    idea = {"id": f"idea-{int(time.time())}-{secrets.token_hex(2)}", "title": title,
                            "body": body, "author": str(payload.get("author", "research_owner")),
                            "status": "PROPOSED", "created_at": now(), "history": []}
                    ideas.setdefault("ideas", []).append(idea)
                    atomic_write(DATA / "ideas.json", json.dumps(ideas, ensure_ascii=False, indent=2) + "\n")
                    event_id = append_event("idea_proposed", role, title, None, idea)
                    if path == "/api/ideas" or self.headers.get("Accept", "").startswith("application/json"):
                        self.send_json({"ok": True, "idea": idea, "event_id": event_id})
                    else:
                        self.send_response(303); self.send_header("Location", "/ideas"); self.end_headers()
                    return
                if path.startswith("/task/") and path.endswith("/edit"):
                    task_id = path.split("/")[-2]
                    task = find_task(state, task_id)
                    if not task:
                        self.send_json({"error": "task not found"}, 404); return
                    if role not in {"planning"}:
                        self.send_json({"error": "planning role required"}, 403); return
                    before = json.loads(json.dumps(task))
                    for field in EDITABLE_FIELDS:
                        if field not in payload:
                            continue
                        value = payload[field]
                        if isinstance(value, str) and field in {"dependencies", "readiness_conditions", "known_implementation",
                                                                "proposed_approaches", "success_criteria", "failure_conditions",
                                                                "known_limitations", "evidence_links", "next_possible_actions"}:
                            value = [line.strip() for line in value.splitlines() if line.strip()]
                        task[field] = value
                    task.setdefault("decision_history", []).append({"at": now(), "decision": "planning edited via Research Console", "actor": role})
                    event_id = append_event("task_edited", role, f"edited {task_id}", before, task)
                    save_state(state)
                    self.send_response(303); self.send_header("Location", f"/task/{task_id}"); self.end_headers()
                    return
                if path.startswith("/task/") and path.endswith("/status"):
                    task_id = path.split("/")[-2]
                    task = find_task(state, task_id)
                    if not task:
                        self.send_json({"error": "task not found"}, 404); return
                    new_status = str(payload.get("status", ""))
                    if new_status not in STATUSES:
                        self.send_json({"error": "invalid status"}, 400); return
                    if new_status not in TRANSITIONS.get(task["status"], set()):
                        self.send_json({"error": f"invalid transition {task['status']} -> {new_status}"}, 400); return
                    allowed_role = "acceptance" if new_status in {"ACCEPTED", "ACCEPTED_LIMITED"} or task["status"] == "REVIEW" else "planning"
                    if new_status == "IN_PROGRESS":
                        allowed_role = "authorization"
                        if task.get("authorized_action", "none") == "none":
                            self.send_json({"error": "task has no authorized_action"}, 403); return
                    if role != allowed_role:
                        self.send_json({"error": f"role {allowed_role} required"}, 403); return
                    before = json.loads(json.dumps(task))
                    task["status"] = new_status
                    task.setdefault("decision_history", []).append({"at": now(), "decision": f"status -> {new_status}", "actor": role})
                    event_id = append_event("status_changed", role, f"{task_id}: {before['status']} -> {new_status}", before, task)
                    save_state(state)
                    self.send_response(303); self.send_header("Location", f"/task/{task_id}"); self.end_headers()
                    return
                if path == "/api/authorization":
                    if role != "authorization":
                        self.send_json({"error": "authorization role required"}, 403); return
                    task_id = str(payload.get("task_id", ""))
                    action = str(payload.get("action", "")).strip()
                    task = find_task(state, task_id) if task_id else None
                    if task is None or not action:
                        self.send_json({"error": "task_id and action required"}, 400); return
                    before = json.loads(json.dumps(task))
                    task["authorized_action"] = action
                    state["authorized_action"] = action
                    event_id = append_event("authorization", role, f"{task_id}: {action}", before, task)
                    save_state(state)
                    self.send_json({"ok": True, "task": task, "event_id": event_id})
                    return
                self.send_json({"error": "not found"}, 404)
        except Exception as exc:
            self.send_json({"error": f"request failed: {exc}"}, 400)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    state, directions, _ = load_state()
    errors = validate_state(state, directions)
    if errors:
        raise SystemExit("invalid data: " + "; ".join(errors))
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Research Console listening on http://{args.host}:{args.port}")
    print("writes_enabled=" + str(bool(TOKEN)))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
