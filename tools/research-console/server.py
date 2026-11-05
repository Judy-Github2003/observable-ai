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
    return {"head": run("git", "rev-parse", "HEAD"), "status": run("git", "status", "--short", "--untracked-files=no")}


def workstate_check(state):
    path = ROOT / "experiments/representation-evolution-e3/WORKSTATE.md"
    if not path.exists():
        return {"ok": False, "reason": "WORKSTATE.md missing"}
    text = path.read_text(encoding="utf-8", errors="replace")
    checks = {
        "code_commit": state.get("last_code_commit", ""),
        "test_result": state.get("last_test_result", ""),
    }
    problems = []
    if checks["code_commit"] and checks["code_commit"] not in text:
        problems.append("last_code_commit not present in WORKSTATE")
    if checks["test_result"] and checks["test_result"] not in text:
        problems.append("last_test_result not present in WORKSTATE")
    current = find_task(state, state.get("current_task_id", ""))
    if current and current.get("title") not in text:
        problems.append("current task title not present in WORKSTATE")
    auth_match = re.search(r"^authorization:\s*(\S+)", text, re.MULTILINE)
    if not auth_match or auth_match.group(1) != str(state.get("authorized_action", "none")):
        problems.append("authorization line does not match tasks.json")
    pack = state.get("active_pack")
    if pack:
        pack_match = re.search(r"^## 研究包 " + re.escape(str(pack.get("id"))) + r" · (\S+)", text, re.MULTILINE)
        if not pack_match or pack_match.group(1) != str(pack.get("status")):
            problems.append("pack status not present or mismatched in WORKSTATE")
        for tid in pack.get("tasks", []):
            task = find_task(state, tid)
            if not task:
                problems.append("pack task missing: " + tid)
                continue
            task_match = re.search(r"^- " + re.escape(tid) + r" · .* · (\S+) · patch ", text, re.MULTILINE)
            if not task_match or task_match.group(1) != str(task.get("status")):
                problems.append("task status mismatch in WORKSTATE: " + tid)
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


STATUS_ZH = {
    "PROPOSED": "提议", "EXPLORATORY": "探索中", "READY": "可启动",
    "IN_PROGRESS": "进行中", "REVIEW": "待审查", "PATCH_ONCE": "补丁阶段",
    "ACCEPTED": "已验收", "ACCEPTED_LIMITED": "受限验收", "PAUSED": "已暂停",
}
PRIORITY_ZH = {"high": "高", "medium": "中", "low": "低"}
FIELD_ZH = {
    "research_question": "研究问题", "hypothesis": "待验证假设", "goal": "目标能力",
    "status": "状态", "priority": "优先级", "dependencies": "依赖条件",
    "readiness_conditions": "启动条件", "known_implementation": "已有代码/接口",
    "proposed_approaches": "可能的技术路线", "minimal_experiment": "最小实验",
    "success_criteria": "成功标准", "failure_conditions": "失败条件",
    "attribution": "来源归因", "known_limitations": "已知限制",
    "patch_used": "补丁额度", "commit_sha": "代码提交", "evidence_links": "证据链接",
    "decision_history": "决策历史", "next_possible_actions": "下一步候选",
    "authorized_action": "当前授权动作", "direction": "研究方向",
    "title": "标题",
}
EVENT_ZH = {"idea_proposed": "新想法", "task_edited": "规划修改", "status_changed": "状态变化",
            "authorization": "授权", "implementation_finished": "实施完成"}


def zh(value, mapping):
    return mapping.get(str(value), str(value))


def zh_test_result(value):
    text = str(value or "")
    text = text.replace(" passed / ", "/").replace(" total, 0 failed", " 通过，0 失败")
    text = text.replace(" total, ", " 总数，").replace(" failed", " 失败")
    return text


def event_note(note):
    text = str(note or "")
    if text.startswith("edited "):
        return text[len("edited "):]
    if text.startswith("status ->"):
        return text[len("status ->"):]
    return text


def badge(status: str) -> str:
    cls = re.sub(r"[^A-Za-z0-9_-]", "", status or "UNKNOWN")
    return f'<span class="badge {cls}" title="{esc(status)}">{esc(zh(status, STATUS_ZH))}</span>'


def page(title: str, body: str, state=None) -> bytes:
    current = state.get("current_task_id", "none") if state else "none"
    nav = """<nav class="tabs">
      <a href="/">首页</a>
      <a href="/map">研究地图</a>
      <a href="/ideas">想法收件箱</a>
      <a href="/activity">活动记录</a>
      <a href="/api/health">健康检查</a>
    </nav>"""
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} · Observable AI 研究控制台</title>
<style>
:root{{--bg:#f4f6fa;--panel:#ffffff;--line:#e2e8f0;--text:#1e293b;--muted:#64748b;--accent:#2563eb;--accent-soft:#eff6ff;--ok:#047857;--warn:#b45309;--bad:#b91c1c}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--bg);color:var(--text);font:15px/1.65 "Segoe UI","Microsoft YaHei",system-ui,sans-serif}}
header{{background:rgba(255,255,255,.94);border-bottom:1px solid var(--line);padding:16px 28px;display:flex;justify-content:space-between;gap:20px;align-items:center;flex-wrap:wrap;position:sticky;top:0;z-index:5;backdrop-filter:blur(8px)}}
.brand strong{{display:block;font-size:19px;letter-spacing:.2px}} .brand span{{color:var(--muted);font-size:12px}}
nav.tabs{{display:flex;gap:4px;flex-wrap:wrap}} nav.tabs a{{color:var(--muted);padding:7px 11px;border-radius:8px;text-decoration:none;font-size:14px}}
nav.tabs a:hover{{background:var(--accent-soft);color:var(--accent)}}
main{{max-width:1180px;margin:0 auto;padding:28px 22px 50px}} h1{{font-size:28px;letter-spacing:-.3px;margin:0 0 6px}} h2{{font-size:19px;margin:32px 0 12px;padding-top:8px}}
h3{{font-size:16px}} .muted{{color:var(--muted)}} .small{{font-size:12px}} .mono{{font-family:ui-monospace,Consolas,monospace}}
.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:14px}} .card{{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:17px;box-shadow:0 1px 2px rgba(15,23,42,.04)}}
.card h3{{margin:0 0 7px}} .hero{{background:linear-gradient(135deg,#ffffff,#eef4ff);border:1px solid #dbe6f7}} .hero h3{{font-size:21px}}
.badge{{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:2px 9px;font-size:12px;white-space:nowrap;background:#f8fafc}}
.ACCEPTED,.ACCEPTED_LIMITED{{border-color:#a7f3d0;background:#ecfdf5;color:#047857}} .PROPOSED,.EXPLORATORY{{border-color:#dbe3ed;background:#f8fafc;color:#475569}} .READY{{border-color:#fde68a;background:#fffbeb;color:#92400e}}
.IN_PROGRESS,.PATCH_ONCE,.REVIEW{{border-color:#bfdbfe;background:#eff6ff;color:#1d4ed8}} .PAUSED{{border-color:#fecaca;background:#fef2f2;color:#b91c1c}}
table{{width:100%;border-collapse:collapse}} th,td{{border-bottom:1px solid var(--line);padding:10px 8px;text-align:left;vertical-align:top}} th{{color:var(--muted);font-weight:600;font-size:13px}} tr:last-child td{{border-bottom:0}}
a{{color:var(--accent);text-decoration:none}} a:hover{{text-decoration:underline}} input,textarea,select,button{{background:#fff;color:var(--text);border:1px solid #cbd5e1;border-radius:8px;padding:9px 10px;font:inherit}} input:focus,textarea:focus,select:focus{{outline:2px solid #bfdbfe;border-color:var(--accent)}} button{{cursor:pointer;background:var(--accent);color:#fff;border-color:var(--accent);font-weight:600}} button:hover{{background:#1d4ed8}}
textarea{{width:100%;min-height:86px}} label{{display:block;color:var(--muted);font-size:13px;margin-top:10px}} pre{{background:#0f172a;color:#e2e8f0;border:1px solid #1e293b;padding:14px;overflow:auto;border-radius:9px;white-space:pre-wrap;font-size:13px}}
.error{{border-color:#fecaca;background:#fef2f2;color:#991b1b}} .ok{{color:var(--ok)}} .warn{{color:var(--warn)}} ul{{margin:6px 0 0 20px;padding:0}} code{{background:#f1f5f9;padding:1px 5px;border-radius:4px;font-size:13px}} details{{margin:10px 0}} summary{{cursor:pointer;padding:8px 0}} .kicker{{text-transform:uppercase;letter-spacing:.08em;font-size:11px;color:var(--muted);font-weight:700}}
footer{{max-width:1180px;margin:0 auto;padding:0 22px 28px;color:var(--muted);font-size:12px}}
</style></head><body>
<header><div class="brand"><strong>Observable AI</strong><span>研究控制台 · 当前：{esc(current)}</span></div>{nav}</header>
<main>{body}</main>
<footer>公开只读视图。写入需要本地 token 与角色。独立闭卷未完成，仍为 not_run；实施者测试不等于独立验收。</footer>
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
        recent.append(f'<li><a href="/activity/{esc(event["event_id"])}">{esc(event["event_id"])}</a> · {esc(zh(event["kind"], EVENT_ZH))} · {esc(event_note(event.get("note", "")))}</li>')
    pack = state.get("active_pack")
    cards = [
        ("当前任务", esc(current["title"]) if current else "无", f'<a href="/task/{esc(current["id"])}">{esc(current["id"])}</a>' if current else "无"),
        ("当前授权动作", esc("无" if state.get("authorized_action", "none") == "none" else state.get("authorized_action")), "没有明确授权时，任何任务都不得启动。"),
        ("独立闭卷", "未运行" if state.get("independent_closed_book", "not_run") == "not_run" else esc(state.get("independent_closed_book")), "实施者测试不等于独立验收。"),
        ("任务", f'{len(tasks)} 个 · {len(directions.get("directions", []))} 个方向', "任务状态以 tasks.json 为唯一权威。"),
        ("测试记录", esc(zh_test_result(state.get("last_test_result", "待定"))), "实施者运行，不是独立闭卷。"),
        ("Git 工作区", esc("干净" if not git_info()["status"] else "有未提交修改")[:500], f'HEAD {esc(git_info()["head"][:12])}'),
    ]
    if pack:
        cards.insert(1, ("研究包", esc(pack.get("id", "")) + " · " + esc(pack.get("status", "")), " · ".join(esc(x) for x in pack.get("tasks", []))))
    body = "<h1>研究总览</h1><p class='muted'>这不是完成率仪表盘；状态、证据、授权和未知分开显示。</p><div class='grid'>"
    for title, value, note in cards:
        body += f"<div class='card'><div class='muted small'>{title}</div><h3>{value}</h3><div class='muted small'>{note}</div></div>"
    body += "</div><h2>当前与最近</h2><div class='grid'>"
    body += f"<div class='card'><h3>{esc(current['title']) if current else '无'}</h3><p>{esc(current['research_question']) if current else ''}</p><p>{badge(current['status']) if current else ''} 补丁 {esc(current['patch_used']) if current else ''}/1</p><div class='small'>{render_value(current.get('known_limitations')) if current else ''}</div></div>"
    body += "<div class='card'><h3>最近活动</h3><ul>" + ("".join(recent) or "<li>暂无结构化事件；初始导入不冒充历史事件。</li>") + "</ul></div>"
    body += "</div><h2>值得关注的任务</h2><div class='grid'>"
    for task in [t for t in tasks if t["status"] in ("READY", "EXPLORATORY") and t["priority"] == "high"][:6]:
        body += f"<div class='card'><a href='/task/{esc(task['id'])}'><strong>{esc(task['id'])} · {esc(task['title'])}</strong></a><p class='small'>{esc(task['research_question'])}</p><div>{badge(task['status'])}</div></div>"
    body += "</div><h2>状态分布</h2><div class='card'><table><tr><th>状态</th><th>数量</th></tr>"
    for status in sorted(counts):
        body += f"<tr><td>{badge(status)}</td><td>{counts[status]}</td></tr>"
    body += "</table><p class='small muted'>没有“研究完成百分比”。“受限验收”不等于独立闭卷。</p></div>"
    return page("研究总览", body, state)


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
        row = f"<details open><summary><strong>{esc(d['code'])} · {esc(d['name'])}</strong> <span class='muted small'>{len(tasks)} 个任务</span></summary><p class='muted'>{esc(d['question'])}</p><table><tr><th>编号</th><th>任务</th><th>状态</th><th>依赖</th></tr>"
        for t in sorted(tasks, key=lambda x: x["id"]):
            row += f"<tr><td><a href='/task/{esc(t['id'])}'>{esc(t['id'])}</a></td><td>{esc(t['title'])}</td><td>{badge(t['status'])}</td><td class='small'>{esc(', '.join(t['dependencies'])) or '—'}</td></tr>"
        row += "</table></details>"
        rows.append(row)
    form = """<form method="get" action="/map" class="card"><label>搜索 <input name="q" value="%s" placeholder="任务 ID、标题或研究问题"></label><label>研究方向 <select name="direction"><option value="">全部</option>%s</select></label><label>状态 <select name="status"><option value="">全部</option>%s</select></label><button>应用筛选</button></form>""" % (
        esc(q), "".join(f"<option value='{esc(d['code'])}' {'selected' if direction == d['code'] else ''}>{esc(d['code'])} {esc(d['name'])}</option>" for d in directions["directions"]),
        "".join(f"<option value='{esc(s)}' {'selected' if status == s else ''}>{esc(zh(s, STATUS_ZH))}</option>" for s in sorted(STATUSES)))
    return page("研究地图", "<h1>研究地图</h1><p class='muted'>方向不是串行阶段；筛选只改变视图，不代表完成顺序。</p>" + form + "".join(rows), state)


def render_value(value):
    if isinstance(value, list):
        return "<ul>" + "".join(f"<li>{esc(v)}</li>" for v in value) + "</ul>" if value else "<span class='muted'>—</span>"
    if isinstance(value, dict):
        return f"<pre>{esc(json.dumps(value, ensure_ascii=False, indent=2))}</pre>"
    return esc(value) if value not in (None, "") else "<span class='muted'>待定</span>"


def task_page(state, task, events):
    fields = ["research_question", "hypothesis", "goal", "status", "priority", "dependencies",
              "readiness_conditions", "known_implementation", "proposed_approaches",
              "minimal_experiment", "success_criteria", "failure_conditions", "attribution",
              "known_limitations", "patch_used", "commit_sha", "evidence_links",
              "decision_history", "next_possible_actions", "authorized_action"]
    body = f"<p><a href='/map'>← 返回研究地图</a></p><h1>{esc(task['id'])} · {esc(task['title'])}</h1><p>{badge(task['status'])} <span class='muted'>方向 {esc(task['direction'])} · 优先级 {esc(zh(task['priority'], PRIORITY_ZH))} · 补丁 {esc(task['patch_used'])}/1</span></p>"
    body += "<div class='grid'>"
    for field in fields:
        body += f"<div class='card'><div class='kicker'>{esc(zh(field, FIELD_ZH))}</div>{render_value(task.get(field))}</div>"
    body += "</div>"
    if task.get("evidence_links"):
        body += "<h2>证据链接</h2><div class='card'><ul>" + "".join(f"<li><code>{esc(x)}</code></li>" for x in task["evidence_links"]) + "</ul></div>"
    related = [e for e in events if (e.get("after") or {}).get("id") == task["id"] or (e.get("before") or {}).get("id") == task["id"]]
    body += "<h2>编辑规划（需要本地 token + 规划角色）</h2><form method='post' action='/task/" + esc(task["id"]) + "/edit' class='card'>"
    for field in ["title", "research_question", "hypothesis", "goal", "minimal_experiment", "known_limitations"]:
        val = task.get(field, "")
        if isinstance(val, list):
            val = "\n".join(val)
        body += f"<label>{esc(zh(field, FIELD_ZH))}<textarea name='{esc(field)}'>{esc(val)}</textarea></label>"
    body += "<label>本地 token <input type='password' name='token'></label><label>角色 <input name='role' value='planning'></label><button>保存并记录历史</button></form>"
    body += "<h2>状态 / 授权（不同角色）</h2><div class='grid'><form method='post' action='/task/" + esc(task["id"]) + "/status' class='card'><label>新状态 <select name='status'>" + "".join(f"<option value='{esc(s)}'>{esc(zh(s, STATUS_ZH))}</option>" for s in sorted(STATUSES)) + "</select></label><label>本地 token <input type='password' name='token'></label><label>角色 <input name='role' value='planning'></label><button>提交状态变化</button></form>"
    body += "<form method='post' action='/api/authorization' class='card'><label>授权动作 <input name='action' value='' placeholder='例如 C1-followup'></label><label>本地 token <input type='password' name='token'></label><label>角色 <input name='role' value='authorization'></label><button>记录授权</button></form></div>"
    if related:
        body += "<h2>本地历史</h2><div class='card'><ul>" + "".join(f"<li><a href='/activity/{esc(e['event_id'])}'>{esc(e['event_id'])}</a> · {esc(zh(e.get('kind',''), EVENT_ZH))} · {esc(e.get('note',''))}</li>" for e in related[-20:]) + "</ul></div>"
    return page(task["id"], body, state)


def ideas_page(state, ideas):
    body = "<h1>想法收件箱</h1><p class='muted'>新想法先保存为“提议”；批准前不创建正式任务，也不启动研究。</p>"
    body += "<form method='post' action='/ideas' class='card'><label>标题 <input name='title' required></label><label>原始想法 <textarea name='body' required></textarea></label><label>提出者 <input name='author' value='research_owner'></label><label>本地 token <input type='password' name='token'></label><label>角色 <input name='role' value='planning'></label><button>加入想法收件箱</button></form>"
    body += "<h2>全部想法</h2>"
    for idea in reversed(ideas.get("ideas", [])):
        body += f"<div class='card'><strong>{esc(idea['title'])}</strong> {badge(idea.get('status','PROPOSED'))}<p>{esc(idea.get('body',''))}</p><p class='small muted'>{esc(idea.get('author',''))} · {esc(idea.get('created_at',''))} · 编号 {esc(idea['id'])}</p></div>"
    return page("想法收件箱", body, state)


def activity_page(state, events):
    body = "<h1>活动记录</h1><p class='muted'>追加式事件；每个事件保存修改前后的版本。初始导入不伪造历史事件。</p><table><tr><th>时间</th><th>事件</th><th>类型</th><th>说明</th></tr>"
    for e in events:
        body += f"<tr><td class='small'>{esc(e.get('at',''))}</td><td><a href='/activity/{esc(e['event_id'])}'>{esc(e['event_id'])}</a></td><td>{esc(zh(e.get('kind',''), EVENT_ZH))}</td><td>{esc(e.get('note',''))}</td></tr>"
    body += "</table>"
    return page("活动记录", body, state)


def history_page(event_id):
    path = HISTORY / f"{event_id}.json"
    if not path.exists():
        return None
    event = json.loads(path.read_text(encoding="utf-8"))
    before = json.dumps(event.get("before"), ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    after = json.dumps(event.get("after"), ensure_ascii=False, indent=2, sort_keys=True).splitlines()
    diff = "\n".join(difflib.unified_diff(before, after, fromfile="before", tofile="after", lineterm=""))
    body = f"<p><a href='/activity'>← 返回活动记录</a></p><h1>修改历史 · {esc(event_id)}</h1><div class='card'><div class='muted'>{esc(event.get('at',''))} · {esc(zh(event.get('kind',''), EVENT_ZH))} · {esc(event.get('actor',''))}</div><p>{esc(event.get('note',''))}</p><pre>{esc(diff or '没有字段差异')}</pre></div>"
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
        self.send_html(page("出错了", f"<h1>出错了</h1><div class='card error'>{esc(message)}</div><p><a href='/'>返回首页</a></p>", state), status)

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
                                "active_pack": state.get("active_pack"),
                                "writes_enabled": bool(TOKEN), "task_count": len(state["tasks"]),
                                "direction_count": len(directions["directions"])})
                return
            if path == "/api/state":
                self.send_json({"current_task_id": state.get("current_task_id"), "authorized_action": state.get("authorized_action"), "active_pack": state.get("active_pack"),
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
                    event_id = append_event("task_edited", role, f"规划修改：{task_id}", before, task)
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
