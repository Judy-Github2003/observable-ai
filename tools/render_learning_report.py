#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 教材、考试与报告组B：DeepSeek Harness，2026-11-04。
# 修改原因：研究者要求离线自包含、可筛选、可展开真实执行链的HTML人读报告；本文件也可独立重渲染并合并Codex抽查。
# 安全边界：所有动态内容经textContent/转义；不依赖CDN、网络或file:// fetch；教材字符串不能注入脚本。
"""离线的可审计学习报告渲染器。

  python tools/render_learning_report.py --run <输出目录> [--spotcheck <codex.json>]

报告只展示实际学生输出；未执行的项显示未运行/未合并，不补造通过。
"""

from __future__ import annotations

import argparse
import html
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _anchor_snippet(snapshot: Path, rule: Dict[str, Any]) -> Dict[str, Any]:
    """在源码快照中定位 source_anchor；找不到就明确报未定位，不伪造行号。"""
    source_file = str(rule.get("source_file") or "").replace("\\", "/").lstrip("/")
    anchor = str(rule.get("source_anchor") or ("RULE:" + str(rule.get("rule_id") or "")))
    candidates: List[Path] = []
    if source_file:
        candidates.append(snapshot / "source" / source_file)
        candidates.append(snapshot / "source" / Path(source_file).name)
    if not candidates or not any(p.exists() for p in candidates):
        found = list((snapshot / "source").rglob("*.kt")) if (snapshot / "source").exists() else []
        candidates = found
    for path in candidates:
        if not path.exists() or not path.is_file():
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except Exception:
            continue
        index = -1
        if anchor:
            for i, line in enumerate(lines):
                if anchor in line:
                    index = i
                    break
        if index < 0 and rule.get("rule_id"):
            for i, line in enumerate(lines):
                if str(rule["rule_id"]) in line:
                    index = i
                    break
        if index >= 0:
            start = max(0, index - 8)
            end = min(len(lines), index + 9)
            numbered = "\n".join(f"{i + 1:5d} | {lines[i]}" for i in range(start, end))
            return {
                "found": True, "path": str(path), "line": index + 1,
                "anchor": anchor, "snippet": numbered, "start": start + 1, "end": end,
            }
    return {"found": False, "path": source_file, "anchor": anchor, "line": None, "snippet": "", "start": None, "end": None}


def enrich_report(data: Dict[str, Any], output: Path) -> Dict[str, Any]:
    manifest = data.get("manifest") or {}
    root = Path(manifest.get("project_root") or data.get("run", {}).get("project_root") or ".")
    snapshot = output / "snapshot"
    catalog = (data.get("rules") or {}).get("rules") or []
    by_id: Dict[str, Dict[str, Any]] = {}
    for rule in catalog:
        rid = rule.get("rule_id")
        if rid:
            rule["anchor"] = _anchor_snippet(snapshot, rule)
            by_id[str(rid)] = rule
    # 事件里出现但目录没登记的规则，明确标“目录未登记”，不能当成已验证。
    observed = set()
    for bucket in ("training", "exam"):
        for case in (data.get(bucket) or {}).get("cases", []):
            for rid in case.get("rule_ids") or []:
                observed.add(str(rid))
    for rid in sorted(observed):
        if rid not in by_id:
            by_id[rid] = {
                "rule_id": rid, "version": None, "title": "目录未登记", "introduced_lesson": None,
                "acquired_at": None, "reason": None, "author": None, "acquisition_method": None,
                "source_file": None, "function": None, "source_anchor": None,
                "anchor": {"found": False, "path": None, "anchor": None, "line": None,
                           "snippet": "", "start": None, "end": None},
                "unregistered": True,
            }
    data.setdefault("rules", {})["by_id"] = by_id
    data["rules"]["observed_rule_ids"] = sorted(observed)
    for bucket in ("training", "exam"):
        for case in (data.get(bucket) or {}).get("cases", []):
            case["rule_details"] = [by_id[rid] for rid in case.get("rule_ids", []) if rid in by_id]
    for lid, lesson in (data.get("per_lesson") or {}).items():
        rule_ids = list(lesson.get("rules_executed") or [])
        lesson["rules_catalog_refs"] = [by_id[rid] for rid in rule_ids if rid in by_id]
        for rule in catalog:
            if str(rule.get("introduced_lesson") or "") == str(lid) and rule.get("rule_id") not in rule_ids:
                lesson["rules_catalog_refs"].append(rule)
    return data


def _json_for_script(data: Any) -> str:
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return (text.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))


HTML_SHELL = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>学前24课训练与第一轮教师考试审计报告</title>
<style>
:root{--bg:#f4f6f8;--panel:#fff;--ink:#1f2933;--muted:#52606d;--line:#d9e2ec;--blue:#2563eb;--green:#15803d;--red:#b91c1c;--amber:#b45309;--purple:#6d28d9}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.55 system-ui,"Segoe UI","Microsoft YaHei",sans-serif}
header{background:#0f172a;color:#f8fafc;padding:18px 24px}
header h1{margin:0 0 6px;font-size:21px}
header .meta{color:#cbd5e1;font-size:12px;word-break:break-all}
main{max-width:1500px;margin:0 auto;padding:18px 20px 60px}
section{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px 18px;margin:14px 0}
h2{margin:0 0 12px;font-size:18px}
h3{margin:14px 0 8px;font-size:15px}
a{color:var(--blue)}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}
.card{border:1px solid var(--line);border-radius:8px;padding:10px 12px;background:#fbfdff}
.card .label{color:var(--muted);font-size:12px}.card .value{font-size:22px;font-weight:700;margin-top:2px}.card .sub{color:var(--muted);font-size:12px}
.badge{display:inline-block;padding:1px 7px;border-radius:999px;font-size:12px;font-weight:600;border:1px solid transparent;white-space:nowrap}
.pass{background:#dcfce7;color:#166534;border-color:#86efac}.fail{background:#fee2e2;color:#991b1b;border-color:#fca5a5}
.warn{background:#fef3c7;color:#92400e;border-color:#fcd34d}.info{background:#e0e7ff;color:#3730a3;border-color:#a5b4fc}
.muted{color:var(--muted)}.small{font-size:12px}.mono{font-family:ui-monospace,Consolas,"Courier New",monospace}
table{border-collapse:collapse;width:100%;font-size:13px}th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
th{background:#f8fafc;color:var(--muted);font-weight:600}
.controls{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 12px}
.controls input,.controls select{padding:7px 9px;border:1px solid #bcccdc;border-radius:7px;background:#fff;min-width:130px}
.controls input[type=search]{min-width:320px}
button{padding:7px 10px;border:1px solid #bcccdc;background:#fff;border-radius:7px;cursor:pointer}
button:hover{background:#f1f5f9}
.case{border:1px solid var(--line);border-radius:8px;margin:8px 0;background:#fff}
.case>summary{cursor:pointer;padding:8px 10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.case .body{padding:4px 12px 12px;border-top:1px solid var(--line)}
pre{background:#0f172a;color:#e2e8f0;padding:9px 11px;border-radius:7px;overflow:auto;max-height:430px;font:12px/1.45 ui-monospace,Consolas,monospace;white-space:pre-wrap;word-break:break-word}
.chain{max-height:430px;overflow:auto;border:1px solid var(--line);border-radius:7px}
.chain table{font-size:12px}.chain th{position:sticky;top:0}
.rule-card{border:1px solid var(--line);border-radius:8px;padding:10px 12px;margin:8px 0}
.kv{display:grid;grid-template-columns:170px 1fr;gap:3px 10px;font-size:13px}.kv div:nth-child(odd){color:var(--muted)}
ul{margin:6px 0 6px 20px;padding:0}li{margin:3px 0}
.lesson{border:1px solid var(--line);border-radius:8px;padding:10px 12px;margin:8px 0}
.lesson .head{display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap;align-items:center}
.grid2{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:900px){.grid2{grid-template-columns:1fr}}
.notice{border-left:4px solid var(--amber);background:#fffbeb;padding:8px 12px;border-radius:4px;margin:8px 0}
code{background:#eef2f6;padding:1px 4px;border-radius:4px}
</style>
</head>
<body>
<header>
  <h1>学前24课：训练、第一轮教师考试与全链路审计报告</h1>
  <div class="meta" id="runline"></div>
</header>
<main>
  <section id="scope-section"><h2>范围、来源与限制</h2><div id="scope"></div></section>
  <section><h2>总览</h2><div id="summary" class="cards"></div><div id="threshold-note" class="notice"></div></section>
  <section><h2>按课成绩与目标覆盖</h2><div class="controls"><select id="lesson-jump"></select><button id="show-all-btn">显示全部课程</button></div><div id="lessons"></div></section>
  <section><h2>失败定位（基于实际执行链）</h2><div id="issues"></div></section>
  <section><h2>逐题记录</h2>
    <div class="controls">
      <input type="search" id="q" placeholder="搜索 task_id / 题目 / 期望 / 实际 / 规则 / 原因">
      <select id="f-lesson"></select><select id="f-type"></select><select id="f-result"></select>
      <select id="f-source"></select><select id="f-rule"></select>
      <button id="reset-btn">重置</button>
    </div>
    <div id="case-count" class="small muted"></div><div id="cases"></div>
  </section>
  <section><h2>规则目录、引入课程与代码锚点</h2><div id="rules-note" class="notice"></div><div id="rules"></div></section>
  <section><h2>Codex 独立抽查（与教师考试分开）</h2><div id="spotcheck"></div></section>
  <section><h2>Manifest、快照与输入哈希</h2><div id="manifest"></div></section>
</main>
<script id="report-data" type="application/json">__DATA__</script>
<script>
(function(){
'use strict';
var DATA = JSON.parse(document.getElementById('report-data').textContent);
var SOURCE_LABEL = {teacher_exam:'教师第一轮考试', training:'训练材料', codex_spotcheck:'Codex独立抽查'};
var state = {q:'', lesson:'all', type:'all', result:'all', source:'teacher_exam', rule:'all'};
var allCases = [];
var limit = 250;

function mk(tag, cls, text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined&&text!==null)e.textContent=String(text);return e;}
function add(parent, tag, cls, text){var e=mk(tag,cls,text);parent.appendChild(e);return e;}
function clear(node){while(node.firstChild)node.removeChild(node.firstChild);}
function badge(text, cls){return mk('span','badge '+(cls||'info'),text);}
function text(v){if(v===null||v===undefined)return '—';if(typeof v==='string')return v;return JSON.stringify(v,null,2);}
function pre(parent, title, value){if(title){add(parent,'h3',null,title);}var p=add(parent,'pre');p.textContent=text(value);return p;}
function kv(parent, rows){var g=add(parent,'div','kv');rows.forEach(function(r){add(g,'div',null,r[0]);add(g,'div',null,r[1]);});return g;}
function sourceLabel(s){return SOURCE_LABEL[s]||s||'未标来源';}
function resultBadge(c){
  if(c.pass===true)return badge('通过','pass');
  if(c.pass===false)return badge('未通过','fail');
  return badge('无法判定','warn');
}
function sourceBadge(s){var cls=s==='teacher_exam'?'info':(s==='codex_spotcheck'?'warn':'muted');return badge(sourceLabel(s),cls);}

function renderRunline(){
  var r=DATA.run||{}, m=DATA.manifest||{}, node=document.getElementById('runline');clear(node);
  add(node,'div',null,'run_id: '+(r.run_id||'—')+' ｜ seed: '+(r.seed!==undefined?r.seed:'—')+' ｜ 生成: '+(DATA.generated_at||'—')+' ｜ DSH会话: '+(r.session_id||'—')+' ('+(r.session_source||'—')+')');
  add(node,'div',null,'输出目录: '+(r.output_dir||'—')+' ｜ 报告JSON: report.json ｜ manifest: '+(m.run_id||'—')+' ｜ 源文件变化: '+((m.source_changed_during_run&&m.source_changed_during_run.changed_count)||0)+' 项');
}

function renderScope(){
  var scope=DATA.scope||{}, node=document.getElementById('scope');clear(node);
  add(node,'p',null,'本报告是DSH第一轮教师考试（teacher_exam）的可审计记录，不是Codex独立闭卷，也不是正式学前毕业。所有课程都是受控输入/符号认知；形状、颜色、情绪等标签查询不等于真实看图、听说或情绪体验。');
  var ul=add(node,'ul');
  (scope.perception_limitations||[]).forEach(function(x){add(ul,'li',null,x);});
  add(ul,'li',null,'新操作由教师编程引入，标teacher_programmed；复用课只引用已学规则，不虚构新增规则。');
  var ce=scope.comprehensive_exams||{};
  add(node,'div','notice','综合卷状态：'+(ce.status||'not_generated')+'。'+(ce.planned||'两套120项综合卷未生成；单课40题不能当毕业卷。'));
  add(node,'p','small muted','JSON下载：');var dl=add(node,'a',null,'report.json');dl.href='report.json';dl.download='report.json';add(node,'span','small muted','（同目录离线打开；不使用fetch/CDN）。');
}

function card(parent,label,value,sub){var d=add(parent,'div','card');add(d,'div','label',label);add(d,'div','value',value);if(sub)add(d,'div','sub',sub);}
function renderSummary(){
  var s=DATA.summary||{}, node=document.getElementById('summary');clear(node);
  card(node,'24课考试题量',s.exam_total||0,'teacher_exam；每课40题（16/12/8/4）');
  card(node,'考试通过',(s.exam_passed||0)+' / '+(s.exam_total||0),'机械判分，来自学生实际输出');
  card(node,'达到首轮阈值',s.lessons_meeting_first_round_threshold||0,'阈值：≥36/40，迁移≥10/12，未知与状态全对');
  card(node,'训练题量',s.training_total||0,'训练先于考试，成绩单列');
  card(node,'外部教师调用',s.teacher_invocation_count||0,'必须为0才算独立运行');
  card(node,'模型调用',s.model_call_count||0,'必须为0');
  card(node,'阻断课程',(s.blocking_lessons||[]).length,'未知当已知、冲突未报、状态/来源问题');
  card(node,'Codex抽查',((DATA.spotcheck&&DATA.spotcheck.status==='merged')?((DATA.spotcheck.summary&&DATA.spotcheck.summary.total)||0):'未合并'),'与教师考试分开统计');
  document.getElementById('threshold-note').textContent='“达到首轮阈值”只表示本课40题达到本轮门槛，不等于正式毕业，也不替代Codex独立抽查。未达标课程保留原attempt和失败证据。';
}

function lessonTitle(lid){var ls=(DATA.curriculum&&DATA.curriculum.lessons)||[];for(var i=0;i<ls.length;i++){if(ls[i].lesson_id===lid)return ls[i].title;}return '';}
function renderLessons(){
  var node=document.getElementById('lessons');clear(node);
  var per=DATA.per_lesson||{};
  Object.keys(per).forEach(function(lid){
    var L=per[lid], e=L.exam||{};
    var d=add(node,'div','lesson'); d.setAttribute('data-lesson',lid);
    var head=add(d,'div','head');
    var left=add(head,'div');
    add(left,'strong',null,lid+' '+L.title+' ');
    add(left,'span','muted small','（'+(L.domain||'')+'）');
    var right=add(head,'div');
    right.appendChild(resultBadge({pass:e.first_round_threshold_met}));
    right.appendChild(document.createTextNode(' 总分 '+(e.passed||0)+'/'+(e.total||0)));
    add(d,'div','small',L.goal||'');
    var info=add(d,'div','small muted');
    info.textContent='目标操作：'+(L.ops_target||[]).join('、')+' ｜ 实际执行：'+(L.ops_executed||[]).join('、');
    var types=e.by_type||{}; var tline=[]; ['basic','transfer','unknown','state'].forEach(function(k){var x=types[k]||{};tline.push(k+' '+(x.passed||0)+'/'+(x.total||0));});
    add(d,'div','small',tline.join(' ｜ '));
    var ul=add(d,'ul');
    add(ul,'li',null,'已支持/边界：'+(L.supported||[]).join('；')+'。未支持：'+(L.unsupported||[]).join('；')+'。');
    add(ul,'li',null,'感知边界：'+(L.perception_boundary||'—'));
    add(ul,'li',null,'规则说明：'+(L.rules_note||'—'));
    add(ul,'li',null,'实际执行规则：'+(((L.rules_executed||[]).join('、'))||'事件未记录rule_id（不能把规则目录当作已执行）'));
    if((e.blocking||[]).length){add(ul,'li',null,'阻断项：'+e.blocking.join('；'));}
    if(L.first_failure){var f=L.first_failure;var loc=f.location||{};add(ul,'li',null,'最早失败：'+f.task_id+' / '+f.op+' / '+f.reason+'；疑似层：'+(loc.layer||'—')+'；节点：'+(loc.earliest_node||'—'));}
    var btn=add(d,'button',null,'只看本课题目');
    btn.addEventListener('click',function(){state.lesson=lid;state.source='all';document.getElementById('f-lesson').value=lid;document.getElementById('f-source').value='all';limit=250;renderCases();document.getElementById('cases').scrollIntoView({behavior:'smooth'});});
  });
}

function renderIssues(){
  var node=document.getElementById('issues');clear(node);
  var issues=DATA.issues||[];
  if(!issues.length){add(node,'p',null,'没有记录到失败项；这不表示能力普遍成立，只表示本attempt未发现失败。');return;}
  add(node,'p','small muted','共 '+issues.length+' 条；层级来自实际链的最早异常节点，属于疑似定位，不声称根因已确定。');
  var table=add(node,'table');var thead=add(table,'thead');var tr=add(thead,'tr');['课','任务','层','最早节点','类型/操作','说明','确定性'].forEach(function(h){add(tr,'th',null,h);});
  var tb=add(table,'tbody');
  issues.slice(0,300).forEach(function(it){var row=add(tb,'tr');add(row,'td',null,it.lesson_id||'全局');add(row,'td','mono',it.task_id||'—');add(row,'td',null,it.layer||'—');add(row,'td','small',it.earliest_node||'—');add(row,'td','small',(it.type||'')+' / '+(it.op||''));add(row,'td','small',it.description||'—');add(row,'td','small',it.certainty||'—');});
  if(issues.length>300)add(node,'p','small muted','仅显示前300条；完整列表见 report.json 的 issues。');
}

function buildFilters(){
  var lessons=DATA.curriculum.lessons||[];
  var ls=document.getElementById('f-lesson');add(ls,'option',null,'全部课程').value='all';
  lessons.forEach(function(l){var o=add(ls,'option',null,l.lesson_id+' '+l.title);o.value=l.lesson_id;});
  var jump=document.getElementById('lesson-jump');add(jump,'option',null,'跳到课程…').value='';
  lessons.forEach(function(l){var o=add(jump,'option',null,l.lesson_id+' '+l.title);o.value=l.lesson_id;});
  jump.addEventListener('change',function(){if(jump.value){document.querySelector('[data-lesson="'+jump.value+'"]').scrollIntoView({behavior:'smooth'});}});
  document.getElementById('show-all-btn').addEventListener('click',function(){document.getElementById('lessons').scrollIntoView({behavior:'smooth'});});
  var ft=document.getElementById('f-type');add(ft,'option',null,'全部题型').value='all';['basic','transfer','unknown','state','prep','spotcheck'].forEach(function(t){var o=add(ft,'option',null,t);o.value=t;});
  var fr=document.getElementById('f-result');add(fr,'option',null,'全部结果').value='all';add(fr,'option',null,'通过').value='pass';add(fr,'option',null,'未通过').value='fail';add(fr,'option',null,'无法判定').value='unknown';
  var fs=document.getElementById('f-source');add(fs,'option',null,'全部来源').value='all';add(fs,'option',null,'教师第一轮考试').value='teacher_exam';add(fs,'option',null,'训练材料').value='training';add(fs,'option',null,'Codex独立抽查').value='codex_spotcheck';
  var frule=document.getElementById('f-rule');add(frule,'option',null,'全部规则').value='all';
  var by=DATA.rules.by_id||{};Object.keys(by).sort().forEach(function(rid){var o=add(frule,'option',null,rid);o.value=rid;});
  ls.addEventListener('change',function(){state.lesson=ls.value;limit=250;renderCases();});
  ft.addEventListener('change',function(){state.type=ft.value;limit=250;renderCases();});
  fr.addEventListener('change',function(){state.result=fr.value;limit=250;renderCases();});
  fs.addEventListener('change',function(){state.source=fs.value;limit=250;renderCases();});
  frule.addEventListener('change',function(){state.rule=frule.value;limit=250;renderCases();});
  document.getElementById('q').addEventListener('input',function(ev){state.q=ev.target.value;limit=250;renderCases();});
  document.getElementById('reset-btn').addEventListener('click',function(){state={q:'',lesson:'all',type:'all',result:'all',source:'teacher_exam',rule:'all'};document.getElementById('q').value='';ls.value='all';ft.value='all';fr.value='all';fs.value='teacher_exam';frule.value='all';limit=250;renderCases();});
  ls.value=state.lesson;ft.value=state.type;fr.value=state.result;fs.value=state.source;frule.value=state.rule;
}

function matchCase(c){
  if(state.lesson!=='all'&&c.lesson_id!==state.lesson)return false;
  if(state.type!=='all'&&c.type!==state.type)return false;
  if(state.source!=='all'&&c.source!==state.source)return false;
  if(state.result==='pass'&&c.pass!==true)return false;
  if(state.result==='fail'&&c.pass!==false)return false;
  if(state.result==='unknown'&&typeof c.pass==='boolean')return false;
  if(state.rule!=='all'&&(c.rule_ids||[]).indexOf(state.rule)<0)return false;
  var q=state.q.trim().toLowerCase();
  if(!q)return true;
  var hay=[c.task_id,c.lesson_id,c.op,c.skill,c.reason,JSON.stringify(c.request||{}),JSON.stringify(c.expected||{}),JSON.stringify(c.actual||{}),(c.rule_ids||[]).join(' ')].join(' ').toLowerCase();
  return hay.indexOf(q)>=0;
}

function chainTable(parent,chain){
  if(!chain||!chain.length){add(parent,'p','small muted','该任务没有可对应的执行链事件（可能是准备请求、学生未输出，或事件缺少task_id/run_id）。');return;}
  var wrap=add(parent,'div','chain');var t=add(wrap,'table');var th=add(t,'thead');var tr=add(th,'tr');['#','时间','事件','函数','阶段','规则','事实','说明'].forEach(function(h){add(tr,'th',null,h);});
  var tb=add(t,'tbody');
  chain.forEach(function(e){var row=add(tb,'tr');if(e._failed){row.style.background='#fff1f2';}add(row,'td',null,e.step_seq!==undefined?e.step_seq:'—');add(row,'td','small',e.timestamp||'—');add(row,'td','small',e.event_type||'—');add(row,'td','small',e.function||'—');add(row,'td','small',e.step_phase||e.status||'—');add(row,'td','small',e.rule_id||'—');add(row,'td','small',(e.fact_ids||[]).join(',')||'—');add(row,'td','small',e.description||'—');});
  add(parent,'p','small muted','事实依据只显示事件中记录的fact_ids；没有就是没有，不从答案反推。');
}

function ruleDetails(parent,c){
  var list=c.rule_details||[];
  if(!list.length){add(parent,'p','small muted','该题事件没有rule_id；不能把规则目录清单当作本题已执行规则。');return;}
  list.forEach(function(r){
    var d=add(parent,'details','rule-card');var s=add(d,'summary');s.appendChild(badge(r.rule_id||'未登记',r.unregistered?'warn':'info'));s.appendChild(document.createTextNode(' '+(r.title||'')+' ｜ 引入课程 '+(r.introduced_lesson||'—')+' ｜ 版本 '+(r.version||'—')));
    var body=add(d,'div');kv(body,[['首次课程',r.introduced_lesson||'—'],['引入时间',r.acquired_at||'—'],['原因',r.reason||'—'],['作者',r.author||'—'],['获得方式',r.acquisition_method||'—'],['函数',r.function||'—'],['源文件',r.source_file||'—'],['锚点',r.source_anchor||'—']]);
    if(r.anchor&&r.anchor.found){add(body,'p','small muted','快照 '+(r.anchor.path||'')+' 第 '+(r.anchor.line||'')+' 行');var p=add(body,'pre');p.textContent=r.anchor.snippet;}
    else{add(body,'p','small warn','未在源码快照中定位到锚点；不能声称代码位置已核验。');}
  });
}

function caseCard(c){
  var d=add(document.createDocumentFragment(),'details','case');
  var s=add(d,'summary');s.appendChild(resultBadge(c));s.appendChild(sourceBadge(c.source));s.appendChild(badge(c.type||'—','muted'));
  s.appendChild(document.createTextNode(' '+c.task_id+' ｜ '+c.lesson_id+' '+lessonTitle(c.lesson_id)+' ｜ '+c.op+' ｜ '+(c.skill||'')));
  var body=add(d,'div','body');
  if(c.location&&c.location.layer){var loc=add(body,'div','notice');loc.textContent='失败定位：疑似层 '+(c.location.layer||'—')+'；最早节点 '+(c.location.earliest_node||'—')+'；'+(c.location.certainty||'');}
  add(body,'p','small muted','说明：'+((c.note||'')||'—')+'；判分原因：'+(c.reason||'—'));
  pre(body,'学生请求（仅request，无expected）',c.request);
  pre(body,'期望（只供判分器/报告）',{status:c.expected_status,result:c.expected});
  pre(body,'学生实际输出',c.actual);
  if(c.run_id||c.trace_ids){kv(body,[['run_id',c.run_id||'—'],['trace_ids',(c.trace_ids||[]).join(', ')||'—'],['教师/模型调用',String(c.teacher_invocation_count||0)+' / '+String(c.model_call_count||0)]]);}
  add(body,'h3',null,'实际执行链');
  chainTable(body,c.chain||[]);
  add(body,'h3',null,'事实依据');
  add(body,'p','small',(c.facts||[]).join('、')||'事件未记录fact_ids；不补造证据。');
  add(body,'h3',null,'规则引入课程、时间、原因与代码锚点');
  ruleDetails(body,c);
  return d;
}

function renderCases(){
  var node=document.getElementById('cases');clear(node);limit=Math.max(limit,250);
  var filtered=allCases.filter(matchCase);
  var count=document.getElementById('case-count');
  count.textContent='匹配 '+filtered.length+' 题；当前显示 '+Math.min(filtered.length,limit)+' 题。展开每题可看请求、期望、实际、执行链、事实和规则锚点。';
  var frag=document.createDocumentFragment();filtered.slice(0,limit).forEach(function(c){frag.appendChild(caseCard(c));});node.appendChild(frag);
  if(filtered.length>limit){var b=add(node,'button',null,'显示更多（剩余 '+(filtered.length-limit)+' 题）');b.addEventListener('click',function(){limit+=250;renderCases();});}
  if(!filtered.length)add(node,'p',null,'没有匹配的题目。');
}

function anchorBlock(parent,r){
  if(r.anchor&&r.anchor.found){add(parent,'p','small muted','快照 '+(r.anchor.path||'')+' 第 '+(r.anchor.line||'')+' 行');var p=add(parent,'pre');p.textContent=r.anchor.snippet;}
  else add(parent,'p','small warn','未定位到源码锚点；不能声称代码位置已核验。');
}
function renderRules(){
  var node=document.getElementById('rules');clear(node);var rules=DATA.rules||{};var note=document.getElementById('rules-note');
  if(!rules.present){note.textContent='rules.json 在运行时缺失或未登记：规则目录未核验，报告不把规则清单当成已执行规则。';}
  else{var c=(rules.rules||[]).length;note.textContent='规则目录共 '+c+' 条，catalog_version='+(rules.catalog_version||'—')+'。清单只说明可用规则，不等于每题都执行；每题以事件中的rule_id为准。';}
  var counts={};(DATA.exam.cases||[]).forEach(function(c){(c.rule_ids||[]).forEach(function(rid){counts[rid]=(counts[rid]||0)+1;});});
  (rules.rules||[]).forEach(function(r){
    var d=add(node,'div','rule-card');var h=add(d,'h3');h.appendChild(badge(r.rule_id||'—','info'));h.appendChild(document.createTextNode(' '+(r.title||'')));
    kv(d,[['版本',r.version||'—'],['首次课程',r.introduced_lesson||'—'],['引入时间',r.acquired_at||'—'],['原因',r.reason||'—'],['作者',r.author||'—'],['获得方式',r.acquisition_method||'—'],['源文件',r.source_file||'—'],['函数',r.function||'—'],['锚点',r.source_anchor||'—'],['考试中实际出现题数',String(counts[r.rule_id]||0)]]);
    anchorBlock(d,r);
  });
  if(rules.observed_rule_ids&&rules.observed_rule_ids.length){add(node,'p','small muted','事件中出现但目录未登记的规则：'+rules.observed_rule_ids.join('、'));}
}

function renderSpotcheck(){
  var node=document.getElementById('spotcheck');clear(node);var sp=DATA.spotcheck||{};
  if(!sp||sp.status==='not_merged'){add(node,'p',null,'Codex独立抽查尚未合并。可用 tools/render_learning_report.py --run <目录> --spotcheck <json> 重渲染。');return;}
  if(sp.status==='missing'||sp.status==='parse_error'){add(node,'div','notice',sp.warning||'抽查文件不可用');return;}
  var s=sp.summary||{};add(node,'p',null,'抽查来源：'+(sp.source_file||'—')+'；共 '+(s.total||0)+' 项，通过 '+(s.passed||0)+'，未通过 '+(s.failed||0)+'，无法判定 '+(s.unknown||0)+'。抽查不并入教师考试分数。');
  if(sp.warning)add(node,'div','notice',sp.warning);
  var table=add(node,'table');var th=add(table,'thead');var tr=add(th,'tr');['任务','课','类型','状态','结果','说明'].forEach(function(h){add(tr,'th',null,h);});var tb=add(table,'tbody');
  (sp.cases||[]).forEach(function(c){var row=add(tb,'tr');add(row,'td','mono',c.task_id||c.case_id||'—');add(row,'td',null,c.lesson_id||'—');add(row,'td',null,c.type||'—');add(row,'td',null,c.status||'—');var td=add(row,'td');td.appendChild(resultBadge({pass:c.pass}));add(row,'td','small',c.reason||'—');});
}

function renderManifest(){
  var node=document.getElementById('manifest');clear(node);var m=DATA.manifest||{};
  add(node,'p','small muted','manifest只记录可见文件哈希、实际jar、课程/规则/材料哈希、时间与会话来源；源文件运行中变化会列在下面。');
  kv(node,[['run_id',m.run_id||'—'],['开始/结束',(m.started_at||'—')+' / '+(m.finished_at||'—')],['DSH会话',(m.dsh_session_id||'—')+' ('+(m.session_source||'—')+')'],['源文件树哈希',m.source_tree_sha256||'—'],['输入树哈希',m.inputs_tree_sha256||'—'],['课程材料哈希',(m.curriculum&&m.curriculum.materials_tree_sha256)||'—'],['规则目录哈希',(m.rules&&m.rules.sha256)||'—'],['规则数量',String((m.rules&&m.rules.rule_count)||0)],['实际jar哈希',(m.artifact&&m.artifact.jar_sha256)||'—'],['jar快照',(m.artifact&&m.artifact.jar_snapshot)||'—'],['产物新鲜度',(m.artifact_freshness&&m.artifact_freshness.state)||'—']]);
  var changed=m.source_changed_during_run||{};add(node,'div',changed.changed_count? 'notice':'small','运行中源文件变化：'+(changed.changed_count||0)+' 项'+(changed.changed_count?('：'+(changed.changed||[]).join(', ')):'')+'；jar运行中变化：'+(changed.artifact_changed_during_run?'是':'否'));
  pre(node,'Manifest 摘要',{rules:m.rules,artifact:m.artifact,artifact_freshness:m.artifact_freshness,source_changed_during_run:m.source_changed_during_run,unverified:m.unverified});
  var files=m.source_files||{};var keys=Object.keys(files);if(keys.length){add(node,'h3',null,'源文件哈希（'+keys.length+'项）');var table=add(node,'table');var th=add(table,'thead');var tr=add(th,'tr');['文件','sha256','快照'].forEach(function(h){add(tr,'th',null,h);});var tb=add(table,'tbody');keys.sort().forEach(function(k){var row=add(tb,'tr');add(row,'td','small mono',k);add(row,'td','small mono',files[k].sha256||'—');add(row,'td','small mono',files[k].snapshot||'—');});}
  var inputs=m.inputs||{};var ikeys=Object.keys(inputs);if(ikeys.length){add(node,'h3',null,'输入文件哈希（'+ikeys.length+'项）');add(node,'p','small muted','请求、答案、材料均在此；answers只在private/下，不发给学生。');var t2=add(node,'table');var th2=add(t2,'thead');var tr2=add(th2,'tr');['输入','sha256'].forEach(function(h){add(tr2,'th',null,h);});var tb2=add(t2,'tbody');ikeys.sort().forEach(function(k){var row=add(tb2,'tr');add(row,'td','small mono',k);add(row,'td','small mono',inputs[k]);});}
  var warnings=DATA.warnings||[];if(warnings.length){add(node,'h3',null,'生成/运行警告');var ul=add(node,'ul');warnings.slice(0,80).forEach(function(w){add(ul,'li','small',(w.lesson_id||'全局')+' '+(w.phase||'')+'：'+(w.message||''));});}
}

function initCases(){
  allCases=[];
  (DATA.exam&&DATA.exam.cases||[]).forEach(function(c){allCases.push(c);});
  (DATA.training&&DATA.training.cases||[]).forEach(function(c){allCases.push(c);});
  ((DATA.spotcheck&&DATA.spotcheck.cases)||[]).forEach(function(c){allCases.push(c);});
}
function render(){renderRunline();renderScope();renderSummary();renderLessons();renderIssues();buildFilters();initCases();renderCases();renderRules();renderSpotcheck();renderManifest();}
render();
})();
</script>
</body>
</html>
"""


def render_html(data: Dict[str, Any]) -> str:
    payload = _json_for_script(data)
    return HTML_SHELL.replace("__DATA__", payload)


def build_report(output: Path, data: Dict[str, Any]) -> Dict[str, str]:
    output = output.resolve()
    data = enrich_report(data, output)
    report_json = output / "report.json"
    write_json(report_json, data)
    # 重新写回报告数据，便于以后只重渲染HTML或追加抽查。
    write_json(output / "report_data.json", data)
    report_html = output / "report.html"
    report_html.write_text(render_html(data), encoding="utf-8")
    return {"html": str(report_html), "json": str(report_json)}


def merge_spotcheck(data: Dict[str, Any], extra: Dict[str, Any]) -> Dict[str, Any]:
    if extra.get("status") != "merged":
        # 缺失/解析失败的文件不能被包装成“已合并0项”，否则会掩盖抽查未完成。
        data["spotcheck"] = extra
        return data
    current = data.get("spotcheck") or {}
    merged = list(current.get("cases") or []) if current.get("status") == "merged" else []
    seen = set()
    for case in merged:
        seen.add((case.get("case_id"), str(case.get("raw"))[:120]))
    for case in extra.get("cases") or []:
        key = (case.get("case_id"), str(case.get("raw"))[:120])
        if key not in seen:
            seen.add(key)
            merged.append(case)
    known = [c for c in merged if isinstance(c.get("pass"), bool)]
    data["spotcheck"] = {
        "status": "merged",
        "source_file": extra.get("source_file") or current.get("source_file"),
        "cases": merged,
        "summary": {"total": len(merged), "passed": sum(1 for c in known if c["pass"]),
                    "failed": sum(1 for c in known if not c["pass"]),
                    "unknown": len(merged) - len(known)},
        "warning": None,
    }
    return data


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="独立重渲染学前24课审计报告，可合并Codex抽查")
    parser.add_argument("--run", required=True, help="curriculum_batch.py 生成的输出目录")
    parser.add_argument("--spotcheck", action="append", default=[], help="可重复：Codex抽查JSON")
    args = parser.parse_args(argv)
    output = Path(args.run).resolve()
    data_path = output / "report_data.json"
    if data_path.exists():
        data = read_json(data_path)
    elif (output / "report.json").exists():
        data = read_json(output / "report.json")
    else:
        print(f"错误：{output} 缺少 report_data.json/report.json", file=sys.stderr)
        return 2
    for spot_path in args.spotcheck:
        path = Path(spot_path).resolve()
        extra = load_spotcheck_file(path)
        data = merge_spotcheck(data, extra)
        print(f"已合并抽查：{path}（{len(extra.get('cases') or [])}项）")
    result = build_report(output, data)
    print(json.dumps({"html": result["html"], "json": result["json"]}, ensure_ascii=False))
    return 0


def load_spotcheck_file(path: Path) -> Dict[str, Any]:
    # 与 curriculum_batch.load_spotcheck 同构的轻量版本，避免独立render时反向依赖主脚本。
    if not path.exists():
        return {"status": "missing", "source_file": str(path), "cases": [],
                "summary": {"total": 0, "passed": 0, "failed": 0, "unknown": 0},
                "warning": "抽查文件不存在"}
    try:
        raw = read_json(path)
    except Exception as exc:
        return {"status": "parse_error", "source_file": str(path), "cases": [],
                "summary": {"total": 0, "passed": 0, "failed": 0, "unknown": 0},
                "warning": f"抽查文件无法解析：{exc}"}
    entries: List[Any] = raw if isinstance(raw, list) else []
    if isinstance(raw, dict):
        for key in ("cases", "results", "spotchecks", "items"):
            if isinstance(raw.get(key), list):
                entries = raw[key]
                break
        else:
            entries = [raw]
    cases = []
    for i, item in enumerate(entries):
        if not isinstance(item, dict):
            continue
        passed = item.get("pass") if isinstance(item.get("pass"), bool) else None
        if passed is None and isinstance(item.get("passed"), bool):
            passed = item["passed"]
        status = item.get("status") if isinstance(item.get("status"), str) else None
        if passed is None and status is not None:
            low = status.lower()
            passed = True if low in ("ok", "pass", "passed", "success") else (False if low in ("fail", "failed", "error") else None)
        cases.append({
            "case_id": item.get("case_id") or f"spotcheck-{i + 1:03d}",
            "task_id": item.get("task_id"), "lesson_id": item.get("lesson_id"),
            "type": item.get("type", "spotcheck"), "op": item.get("op"),
            "source": "codex_spotcheck", "expected": item.get("expected"),
            "actual": item.get("actual"), "status": status, "pass": passed,
            "reason": item.get("reason") or item.get("note"), "scored": False, "raw": item,
        })
    known = [c for c in cases if isinstance(c.get("pass"), bool)]
    return {"status": "merged", "source_file": str(path), "cases": cases,
            "summary": {"total": len(cases), "passed": sum(1 for c in known if c["pass"]),
                        "failed": sum(1 for c in known if not c["pass"]),
                        "unknown": len(cases) - len(known)}, "warning": None}


if __name__ == "__main__":
    sys.exit(main())
