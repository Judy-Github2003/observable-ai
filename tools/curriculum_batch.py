#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 教材、考试与报告组B：DeepSeek Harness，2026-11-04。
# 修改原因：研究者授权批量完成学前24课训练、第一轮教师考试与全链路可审计HTML/JSON报告。
# 职责边界：本文件只生成受控世界、学生请求、机械判分和报告数据；不实现或修改Kotlin学生，
# 不写rules.json，不把expected/checker混进学生请求，不把标签查询说成真实感知或正式毕业。
"""学前24课批量训练、教师考试与报告入口。

用法：
  python tools/curriculum_batch.py --output <新目录> --seed <整数> [--build]
  python tools/render_learning_report.py --run <输出目录> [--spotcheck <json>]

关键约定：
- requests/*.jsonl 只有 task_id/lesson_id/world_id/op/args；答案只在 private/ 下供判分器读。
- 训练先执行，再用同一批 world 状态目录启动新进程做第一轮教师考试；
- exam 的 4 道 state 题在第二个学生进程中执行，用于检查重启/来源，不冒充毕业考；
- 所有通过/失败都来自实际学生输出；缺少输出时标 blocked，不补造 passed。
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import traceback
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]
CURRICULUM_PATH = ROOT / "experiments" / "curriculum" / "batch-v1" / "curriculum.json"
RULES_PATH = ROOT / "experiments" / "curriculum" / "batch-v1" / "rules.json"
DEFAULT_INSTALL = ROOT / "build" / "install" / "logic-learner"
EXAM_TYPES = {"basic": 16, "transfer": 12, "unknown": 8, "state": 4}
STATUSES = {"ok", "unknown", "conflict", "error"}


# ---------------------------------------------------------------------------
# 基础工具：哈希、JSON、随机数、时间。所有输出路径都限制在显式 --output 下。
# ---------------------------------------------------------------------------

def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def token_for(*parts: Any, length: int = 8) -> str:
    """稳定短标记：同一个 seed/课/阶段得到同一个世界ID，不依赖Python内置hash。"""
    raw = "|".join(str(p) for p in parts).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:length]


def rng_for(seed: int, lesson_id: str, phase: str) -> random.Random:
    digest = hashlib.sha256(f"{seed}|{lesson_id}|{phase}".encode("utf-8")).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def as_list(value: Any) -> List[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    if isinstance(value, tuple):
        return list(value)
    return [value]


def rel(path: Path, root: Path = ROOT) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except Exception:
        return path.as_posix()


def safe_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


# ---------------------------------------------------------------------------
# 世界构造辅助：教材只给显式对象、属性、关系、观察、故事、政策与轮次。
# 不按名称/颜色自动补答案；缺字段保持缺失，交给unknown。
# ---------------------------------------------------------------------------

def mk_obj(oid: str, name: str, attrs: Optional[Dict[str, Any]] = None,
           categories: Optional[List[str]] = None) -> Dict[str, Any]:
    d: Dict[str, Any] = {"id": oid, "name": name}
    if attrs:
        d["attributes"] = dict(attrs)
    if categories:
        d["categories"] = list(categories)
    return d


def mk_rel(rid: str, subject: str, predicate: str, obj: str) -> Dict[str, Any]:
    return {"id": rid, "subject": subject, "predicate": predicate, "object": obj}


def mk_claim(cid: str, subject: str, predicate: str, value: Any, truth: bool,
             depends_on: Optional[List[str]] = None) -> Dict[str, Any]:
    d: Dict[str, Any] = {
        "id": cid, "subject": subject, "predicate": predicate,
        "value": value, "truth": bool(truth), "active": True,
    }
    if depends_on:
        d["depends_on"] = list(depends_on)
    return d


def compare_values(left: Any, right: Any) -> str:
    if left < right:
        return "less"
    if left > right:
        return "greater"
    return "equal"


def expect_filter(objects: List[Dict[str, Any]], conditions: List[Dict[str, Any]],
                  ids: Optional[List[str]] = None) -> Tuple[List[str], List[str]]:
    """按契约计算filter.all的ids/unknown_ids。

    规则：候选对象不存在 -> unknown_ids；任一条件已知不满足 -> 排除；
    否则只要有一个必要条件缺失 -> unknown_ids；全部满足 -> ids。
    这里把“已知不满足”放在“缺失”之前，避免缺一个字段就掩盖了另一个明确反例。
    """
    by_id = {o.get("id"): o for o in objects}
    candidates = list(ids) if ids is not None else [o.get("id") for o in objects]
    matched: List[str] = []
    unknown_ids: List[str] = []
    for oid in candidates:
        o = by_id.get(oid)
        if o is None:
            unknown_ids.append(oid)
            continue
        attrs = o.get("attributes") or {}
        excluded = False
        missing = False
        for cond in conditions:
            key = cond.get("key")
            value = cond.get("value")
            if key not in attrs:
                missing = True
                continue
            if attrs.get(key) != value:
                excluded = True
                break
        if excluded:
            continue
        if missing:
            unknown_ids.append(oid)
        else:
            matched.append(oid)
    return sorted(matched), sorted(unknown_ids)


def unique_keep(items: Iterable[Any]) -> List[Any]:
    seen = set()
    out = []
    for item in items:
        key = json.dumps(item, ensure_ascii=False, sort_keys=True)
        if key not in seen:
            seen.add(key)
            out.append(item)
    return out


# ---------------------------------------------------------------------------
# 单个任务的上下文：每个任务只有request；expected只进判分器/报告。
# ---------------------------------------------------------------------------

class BuildCtx:
    def __init__(self, meta: Dict[str, Any], phase: str, seed: int):
        self.meta = meta
        self.lesson_id = meta["lesson_id"]
        self.order = int(meta["order"])
        self.phase = phase
        self.rng = rng_for(seed, self.lesson_id, phase)
        self.world_id = "w-" + self.lesson_id.replace("preschool-", "p") + "-" + phase + "-" + token_for(seed, self.lesson_id, phase, 6)
        self.source = ("teacher_exam" if phase == "exam" else "training_material") + ":" + self.lesson_id + ":" + phase
        self.world: Dict[str, Any] = {}
        self.cases: List[Dict[str, Any]] = []
        self.state_cases: List[Dict[str, Any]] = []
        self.seq = 0
        self.sseq = 0
        self.warnings: List[str] = []
        self.tracked_attrs: Dict[str, Dict[str, Any]] = {}
        self.tracked_rels: Dict[Tuple[str, str], str] = {}

    def set_world(self, world: Dict[str, Any]) -> None:
        self.world = world

    def add(self, qtype: str, op: str, args: Dict[str, Any], expected_status: str = "ok",
            expected_result: Optional[Dict[str, Any]] = None, validator: str = "subset",
            extra: Optional[Dict[str, Any]] = None, skill: str = "", note: str = "") -> Dict[str, Any]:
        self.seq += 1
        task_id = f"{self.lesson_id}-{self.phase}-{self.seq:03d}"
        case = {
            "case_id": task_id,
            "task_id": task_id,
            "lesson_id": self.lesson_id,
            "world_id": self.world_id,
            "type": qtype,
            "op": op,
            "args": args,
            "expected_status": expected_status,
            "expected_result": expected_result or {},
            "validator": validator,
            "extra": extra or {},
            "skill": skill or op,
            "note": note,
            "scored": True,
            "stage": "main",
        }
        self.cases.append(case)
        return case

    def add_state(self, qtype: str, op: str, args: Dict[str, Any], expected_status: str = "ok",
                  expected_result: Optional[Dict[str, Any]] = None, validator: str = "subset",
                  extra: Optional[Dict[str, Any]] = None, skill: str = "", note: str = "") -> Optional[Dict[str, Any]]:
        # 训练阶段不执行state题；考试阶段在第二个学生进程中执行，证明重启后仍可读。
        if self.phase != "exam":
            return None
        self.sseq += 1
        task_id = f"{self.lesson_id}-{self.phase}-s{self.sseq:02d}"
        case = {
            "case_id": task_id,
            "task_id": task_id,
            "lesson_id": self.lesson_id,
            "world_id": self.world_id,
            "type": "state",
            "op": op,
            "args": args,
            "expected_status": expected_status,
            "expected_result": expected_result or {},
            "validator": validator,
            "extra": extra or {},
            "skill": skill or op,
            "note": note,
            "scored": True,
            "stage": "state",
        }
        self.state_cases.append(case)
        return case

    def teach_track(self, oid: str, key: str, value: Any) -> None:
        self.tracked_attrs.setdefault(oid, {})[key] = value

    def rel_track(self, subject: str, predicate: str, obj: str) -> None:
        self.tracked_rels[(subject, predicate)] = obj


def request_of(case: Dict[str, Any]) -> Dict[str, Any]:
    # 学生只收到这5个字段；expected/validator/extra/note永不进入请求文件。
    return {
        "task_id": case["task_id"],
        "lesson_id": case["lesson_id"],
        "world_id": case["world_id"],
        "op": case["op"],
        "args": case["args"],
    }


def prep_case(ctx: BuildCtx) -> Dict[str, Any]:
    return {
        "case_id": f"{ctx.lesson_id}-{ctx.phase}-prep",
        "task_id": f"{ctx.lesson_id}-{ctx.phase}-prep",
        "lesson_id": ctx.lesson_id,
        "world_id": ctx.world_id,
        "type": "prep",
        "op": "world.load",
        "args": {"world": ctx.world, "source": ctx.source},
        "expected_status": "ok",
        "expected_result": {"object_count": len(ctx.world.get("objects") or [])},
        "validator": "subset",
        "extra": {},
        "skill": "world.load",
        "note": "准备请求：只导入受控世界，不计入40题成绩。",
        "scored": False,
        "stage": "prep",
    }


# ---------------------------------------------------------------------------
# 24课任务构造。每个函数生成同一课的考试/训练世界；随机值由seed决定。
# 复用课只调用已学op；新操作由教师编程引入并在rules.json/运行事件中归因。
# ---------------------------------------------------------------------------

def lesson_01(ctx: BuildCtx) -> None:
    """课1：对象ID、名称、同名不同对象；只做显式记录。"""
    names = ["苹果", "杯子", "球", "书", "小车", "积木"]
    ids = ["a1", "a2", "a3", "a4", "a5", "a6", "a7"]
    name_seq = [names[0], names[1], names[2], names[0], names[3], names[4], names[2]]
    objects = [mk_obj(ids[i], name_seq[i]) for i in range(len(ids))]
    ctx.set_world({"objects": objects})
    nm = {o["id"]: o["name"] for o in objects}
    distinct = unique_keep(name_seq)
    pairs = [(ids[i], ids[j]) for i in range(len(ids)) for j in range(i + 1, len(ids))]

    for oid in ids:
        ctx.add("basic", "object.name", {"id": oid}, expected_result={"id": oid, "name": nm[oid]})
    for name in distinct[:5]:
        ctx.add("basic", "object.findName", {"name": name},
                expected_result={"ids": sorted([oid for oid in ids if nm[oid] == name])}, validator="set")
    for left, right in pairs[:4]:
        ctx.add("basic", "object.same", {"left": left, "right": right},
                expected_result={"same_object": left == right, "same_name": nm[left] == nm[right]})

    for left, right in pairs[4:16]:
        ctx.add("transfer", "object.same", {"left": left, "right": right},
                expected_result={"same_object": left == right, "same_name": nm[left] == nm[right]})

    unknown_cases = [
        ("object.name", {"id": "a99"}),
        ("object.findName", {"name": "不存在名"}),
        ("object.same", {"left": "a99", "right": "a1"}),
        ("object.same", {"left": "a1", "right": "a98"}),
        ("object.same", {"left": "a97", "right": "a96"}),
        ("object.same", {"left": "a2", "right": "a95"}),
        ("object.findName", {"name": "苹果 "}),
        ("object.name", {"id": ""}),
    ]
    for op, args in unknown_cases[:-1]:
        ctx.add("unknown", op, args, expected_status="unknown")
    ctx.add("unknown", unknown_cases[-1][0], unknown_cases[-1][1], expected_status="reject", validator="reject")

    ctx.add_state("state", "object.name", {"id": "a1"}, expected_result={"id": "a1", "name": nm["a1"]})
    ctx.add_state("state", "object.findName", {"name": names[2]},
                  expected_result={"ids": sorted([oid for oid in ids if nm[oid] == names[2]])}, validator="set")
    ctx.add_state("state", "object.same", {"left": "a1", "right": "a2"},
                  expected_result={"same_object": False, "same_name": nm["a1"] == nm["a2"]})
    ctx.add_state("state", "object.name", {"id": "a5"}, expected_result={"id": "a5", "name": nm["a5"]})


def lesson_02(ctx: BuildCtx) -> None:
    """课2：颜色属性查询/分组/比较/补教；缺失属性保持unknown。"""
    ids = [f"b{i}" for i in range(1, 9)]
    colors = {ids[0]: "红色", ids[1]: "蓝色", ids[2]: "红色", ids[3]: "绿色", ids[4]: "蓝色", ids[5]: "黄色"}
    objects = []
    for i, oid in enumerate(ids):
        attrs = {"颜色": colors[oid]} if oid in colors else {}
        objects.append(mk_obj(oid, f"图卡{i + 1}", attrs))
    ctx.set_world({"objects": objects})
    get = lambda oid: colors.get(oid)

    for oid in ids[:5]:
        ctx.add("basic", "attr.get", {"id": oid, "key": "颜色"},
                expected_result={"id": oid, "key": "颜色", "value": get(oid)})
    for color in ["红色", "蓝色", "绿色"]:
        ctx.add("basic", "attr.find", {"key": "颜色", "value": color},
                expected_result={"ids": sorted([o for o in ids if get(o) == color])}, validator="set")
    for left, right in [(ids[0], ids[2]), (ids[0], ids[4]), (ids[1], ids[4]), (ids[3], ids[5])]:
        ctx.add("basic", "attr.same", {"left": left, "right": right, "key": "颜色"},
                expected_result={"same_value": get(left) == get(right)})
    teach = [(ids[6], "紫色"), (ids[7], "橙色"), (ids[3], "黑色"), (ids[5], "白色")]
    for oid, value in teach:
        ctx.add("basic", "attr.teach", {"id": oid, "key": "颜色", "value": value},
                expected_result={"id": oid, "key": "颜色", "value": value})
        colors[oid] = value
        ctx.teach_track(oid, "颜色", value)

    ctx.add("transfer", "attr.get", {"id": ids[5], "key": "颜色"},
            expected_result={"id": ids[5], "key": "颜色", "value": colors[ids[5]]})
    ctx.add("transfer", "attr.get", {"id": ids[3], "key": "颜色"},
            expected_result={"id": ids[3], "key": "颜色", "value": colors[ids[3]]})
    for color in ["紫色", "橙色", "黑色"]:
        ctx.add("transfer", "attr.find", {"key": "颜色", "value": color},
                expected_result={"ids": sorted([o for o in ids if get(o) == color])}, validator="set")
    for left, right in [(ids[1], ids[2]), (ids[0], ids[3]), (ids[1], ids[5]), (ids[2], ids[4]),
                        (ids[3], ids[6]), (ids[7], ids[0]), (ids[4], ids[7])]:
        ctx.add("transfer", "attr.same", {"left": left, "right": right, "key": "颜色"},
                expected_result={"same_value": get(left) == get(right)})

    unknown_cases = [
        ("attr.get", {"id": "b99", "key": "颜色"}),
        ("attr.get", {"id": ids[6], "key": "大小"}),
        ("attr.get", {"id": ids[7], "key": "形状"}),
        ("attr.find", {"key": "颜色", "value": "粉色"}),
        ("attr.same", {"left": ids[0], "right": ids[6], "key": "大小"}),
        ("attr.same", {"left": "b99", "right": ids[0], "key": "颜色"}),
        ("attr.get", {"id": "b98", "key": "颜色"}),
        ("attr.find", {"key": "颜色", "value": "红色 "}),
    ]
    for op, args in unknown_cases:
        ctx.add("unknown", op, args, expected_status="unknown")

    ctx.add_state("state", "attr.get", {"id": ids[6], "key": "颜色"},
                  expected_result={"id": ids[6], "key": "颜色", "value": "紫色"})
    ctx.add_state("state", "attr.find", {"key": "颜色", "value": "紫色"},
                  expected_result={"ids": sorted([o for o in ids if get(o) == "紫色"])}, validator="set")
    ctx.add_state("state", "attr.same", {"left": ids[6], "right": ids[7], "key": "颜色"},
                  expected_result={"same_value": False})
    ctx.add_state("state", "attr.get", {"id": ids[3], "key": "颜色"},
                  expected_result={"id": ids[3], "key": "颜色", "value": "黑色"})


def lesson_03(ctx: BuildCtx) -> None:
    """课3：形状标签查询、同形分组、比较；复用属性规则，不虚构视觉能力。"""
    ids = [f"c{i}" for i in range(1, 10)]
    shapes = {ids[0]: "圆形", ids[1]: "三角形", ids[2]: "正方形", ids[3]: "圆形",
              ids[4]: "长方形", ids[5]: "星形", ids[8]: "五边形"}
    objects = []
    for i, oid in enumerate(ids):
        attrs = {"形状": shapes[oid]} if oid in shapes else {"颜色": "红色"}
        objects.append(mk_obj(oid, f"图卡{i + 1}", attrs))
    ctx.set_world({"objects": objects})
    shape_of = lambda oid: shapes.get(oid)

    for oid in ids[:6]:
        ctx.add("basic", "attr.get", {"id": oid, "key": "形状"},
                expected_result={"id": oid, "key": "形状", "value": shape_of(oid)})
    for value in ["圆形", "三角形", "正方形", "长方形", "星形"]:
        ctx.add("basic", "attr.find", {"key": "形状", "value": value},
                expected_result={"ids": sorted([o for o in ids if shape_of(o) == value])}, validator="set")
    pairs = [(ids[i], ids[j]) for i in range(6) for j in range(i + 1, 6)]
    pairs += [(ids[i], ids[8]) for i in range(6)]
    for left, right in pairs[:5]:
        ctx.add("basic", "attr.same", {"left": left, "right": right, "key": "形状"},
                expected_result={"same_value": shape_of(left) == shape_of(right)})

    ctx.add("transfer", "attr.find", {"key": "形状", "value": "五边形"},
            expected_result={"ids": [ids[8]]}, validator="set")
    for left, right in pairs[5:16]:
        ctx.add("transfer", "attr.same", {"left": left, "right": right, "key": "形状"},
                expected_result={"same_value": shape_of(left) == shape_of(right)})

    unknown_cases = [
        ("attr.get", {"id": ids[6], "key": "形状"}),
        ("attr.get", {"id": ids[7], "key": "形状"}),
        ("attr.get", {"id": "c99", "key": "形状"}),
        ("attr.get", {"id": "", "key": "形状"}),
        ("attr.find", {"key": "形状", "value": "六边形"}),
        ("attr.find", {"key": "形状", "value": "圆形 "}),
        ("attr.same", {"left": ids[0], "right": ids[6], "key": "形状"}),
        ("attr.same", {"left": ids[7], "right": ids[1], "key": "形状"}),
    ]
    for op, args in [unknown_cases[0], unknown_cases[1], unknown_cases[2],
                     unknown_cases[4], unknown_cases[5], unknown_cases[6], unknown_cases[7]]:
        ctx.add("unknown", op, args, expected_status="unknown")
    # 空ID不是“未观察”，而是参数错误；单独用reject验收，避免把执行故障伪装成unknown。
    ctx.add("unknown", "attr.get", {"id": "", "key": "形状"}, expected_status="reject", validator="reject")

    ctx.add_state("state", "attr.get", {"id": ids[0], "key": "形状"},
                  expected_result={"id": ids[0], "key": "形状", "value": "圆形"})
    ctx.add_state("state", "attr.find", {"key": "形状", "value": "圆形"},
                  expected_result={"ids": sorted([o for o in ids if shape_of(o) == "圆形"])}, validator="set")
    ctx.add_state("state", "attr.same", {"left": ids[0], "right": ids[3], "key": "形状"},
                  expected_result={"same_value": True})
    ctx.add_state("state", "attr.get", {"id": ids[6], "key": "颜色"},
                  expected_result={"id": ids[6], "key": "颜色", "value": "红色"})


def lesson_04(ctx: BuildCtx) -> None:
    """课4：同单位数量比较；单位不同/缺失不猜换算。"""
    ctx.set_world({"objects": [mk_obj("q1", "量尺")]})
    units = ["格", "厘米", "千克"]
    for i in range(16):
        left = ctx.rng.randint(1, 10)
        right = ctx.rng.randint(1, 10)
        unit = units[i % len(units)]
        ctx.add("basic", "quantity.compare", {"left": {"value": left, "unit": unit}, "right": {"value": right, "unit": unit}},
                expected_result={"comparison": compare_values(left, right), "difference": left - right})
    for i in range(12):
        left = ctx.rng.randint(0, 12)
        right = ctx.rng.randint(0, 12)
        unit = units[(i + 1) % len(units)]
        ctx.add("transfer", "quantity.compare", {"left": {"value": left, "unit": unit}, "right": {"value": right, "unit": unit}},
                expected_result={"comparison": compare_values(left, right), "difference": left - right})
    unknown_cases = [
        {"left": {"value": 3, "unit": "格"}, "right": {"value": 3, "unit": "厘米"}},
        {"left": {"value": 5, "unit": "千克"}, "right": {"value": 5, "unit": "克"}},
        {"left": {"value": 2, "unit": "厘米"}, "right": {"value": 2, "unit": "米"}},
        {"left": {"value": 4, "unit": ""}, "right": {"value": 4, "unit": "格"}},
        {"left": {"value": 4, "unit": "格"}, "right": {"value": 4, "unit": ""}},
        {"left": {"value": 4}, "right": {"value": 4, "unit": "格"}},
        {"left": {"value": 4, "unit": "格"}, "right": {"unit": "格"}},
        {"left": {"unit": "格"}, "right": {"value": 1, "unit": "格"}},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "quantity.compare", args, expected_status="unknown")

    for i in range(4):
        left = ctx.rng.randint(1, 9)
        right = ctx.rng.randint(1, 9)
        ctx.add_state("state", "quantity.compare",
                      {"left": {"value": left, "unit": "格"}, "right": {"value": right, "unit": "格"}},
                      expected_result={"comparison": compare_values(left, right), "difference": left - right})


def lesson_05(ctx: BuildCtx) -> None:
    """课5：只按显式categories查成员；不以名称/颜色推类别。"""
    cats = [
        "水果类", "蔬菜类", "文具类", "玩具类", "餐具类", "家具类", "交通工具类", "动物类",
        "植物类", "衣物类", "电器类", "乐器类", "运动器材类", "厨房用品类", "清洁用品类", "图书类",
        "数字卡片类", "形状卡片类", "颜色卡片类", "建筑类", "自然物类", "食品类", "饮品类", "工具类",
        "装饰类", "容器类", "测量工具类", "天气符号类", "交通标志类", "身体部位类",
    ]
    ids = [f"d{i}" for i in range(1, 13)]
    buckets: Dict[str, List[str]] = {cat: [] for cat in cats}
    for idx, cat in enumerate(cats):
        for delta in (0, 3):
            oid = ids[(idx + delta) % len(ids)]
            if oid not in buckets[cat]:
                buckets[cat].append(oid)
    objects = [mk_obj(oid, f"卡片{i + 1}", categories=[c for c in cats if oid in buckets[c]]) for i, oid in enumerate(ids)]
    ctx.set_world({"objects": objects})

    for cat in cats[:16]:
        ctx.add("basic", "category.members", {"category": cat},
                expected_result={"ids": sorted(buckets[cat])}, validator="set")
    for cat in cats[16:28]:
        ctx.add("transfer", "category.members", {"category": cat},
                expected_result={"ids": sorted(buckets[cat])}, validator="set")
    for i, cat in enumerate(["未记录类别甲", "未记录类别乙", "未记录类别丙", "未记录类别丁",
                             "全部类别", "水果", "水果类 ", "category-x"]):
        ctx.add("unknown", "category.members", {"category": cat}, expected_status="unknown")
    for cat in cats[:4]:
        ctx.add_state("state", "category.members", {"category": cat},
                      expected_result={"ids": sorted(buckets[cat])}, validator="set")


def lesson_06(ctx: BuildCtx) -> None:
    """课6：两条件AND筛选；已知不符排除，缺必要条件进unknown_ids。"""
    specs = [
        ("e1", "红色", "圆形", "大"), ("e2", "蓝色", "三角形", "小"),
        ("e3", "红色", "正方形", "大"), ("e4", "绿色", "圆形", None),
        ("e5", "蓝色", "三角形", "大"), ("e6", "黄色", None, "小"),
        ("e7", "红色", "三角形", "小"), ("e8", None, "圆形", "大"),
        ("e9", "绿色", "正方形", "小"), ("e10", "红色", "圆形", "小"),
    ]
    objects = []
    for oid, color, shape, size in specs:
        attrs: Dict[str, Any] = {}
        if color:
            attrs["颜色"] = color
        if shape:
            attrs["形状"] = shape
        if size:
            attrs["大小"] = size
        objects.append(mk_obj(oid, f"积木{oid}", attrs))
    ctx.set_world({"objects": objects})
    ids = [o["id"] for o in objects]

    basic_conditions = [
        [{"key": "颜色", "value": "红色"}],
        [{"key": "颜色", "value": "蓝色"}],
        [{"key": "颜色", "value": "绿色"}],
        [{"key": "颜色", "value": "黄色"}],
        [{"key": "颜色", "value": "红色"}, {"key": "形状", "value": "圆形"}],
        [{"key": "颜色", "value": "蓝色"}, {"key": "形状", "value": "三角形"}],
        [{"key": "颜色", "value": "红色"}, {"key": "形状", "value": "正方形"}],
        [{"key": "颜色", "value": "绿色"}, {"key": "形状", "value": "圆形"}],
        [{"key": "颜色", "value": "红色"}, {"key": "大小", "value": "大"}],
        [{"key": "颜色", "value": "蓝色"}, {"key": "大小", "value": "小"}],
        [{"key": "颜色", "value": "绿色"}, {"key": "大小", "value": "小"}],
        [{"key": "颜色", "value": "黄色"}, {"key": "大小", "value": "小"}],
        [{"key": "形状", "value": "圆形"}, {"key": "大小", "value": "大"}],
        [{"key": "形状", "value": "三角形"}, {"key": "大小", "value": "小"}],
        [{"key": "形状", "value": "正方形"}, {"key": "大小", "value": "小"}],
        [{"key": "形状", "value": "圆形"}, {"key": "大小", "value": "小"}],
    ]
    for conds in basic_conditions:
        matched, unknown_ids = expect_filter(objects, conds)
        ctx.add("basic", "filter.all", {"conditions": conds},
                expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")
    transfer_conditions = [
        ([{"key": "颜色", "value": "红色"}, {"key": "大小", "value": "小"}], None),
        ([{"key": "形状", "value": "三角形"}], ["e1", "e2", "e3"]),
        ([{"key": "颜色", "value": "蓝色"}], ["e2", "e5", "e7"]),
        ([{"key": "大小", "value": "大"}], ["e4", "e8", "e10"]),
        ([{"key": "颜色", "value": "绿色"}, {"key": "形状", "value": "正方形"}], ["e9", "e2"]),
        ([{"key": "形状", "value": "圆形"}, {"key": "大小", "value": "大"}], ["e1", "e4", "e10"]),
        ([{"key": "颜色", "value": "黄色"}, {"key": "形状", "value": "圆形"}], ["e6", "e7"]),
        ([{"key": "形状", "value": "正方形"}, {"key": "大小", "value": "大"}], ["e3", "e4"]),
        ([{"key": "颜色", "value": "蓝色"}, {"key": "形状", "value": "三角形"}], ["e2", "e5", "e9"]),
        ([{"key": "颜色", "value": "红色"}], ["e1", "e3", "e7", "e10"]),
        ([{"key": "大小", "value": "小"}], ["e1", "e2", "e9"]),
        ([{"key": "颜色", "value": "绿色"}, {"key": "大小", "value": "大"}], ["e4", "e8"]),
    ]
    for conds, subset in transfer_conditions:
        matched, unknown_ids = expect_filter(objects, conds, subset)
        args = {"conditions": conds}
        if subset is not None:
            args["ids"] = subset
        ctx.add("transfer", "filter.all", args,
                expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")
    unknown_conditions = [
        [{"key": "重量", "value": "重"}],
        [{"key": "颜色", "value": "红色"}, {"key": "重量", "value": "轻"}],
        [{"key": "大小", "value": "大"}, {"key": "声音", "value": "响"}],
        [{"key": "形状", "value": "三角形"}, {"key": "材质", "value": "木"}],
        [{"key": "颜色", "value": "红色"}, {"key": "形状", "value": "圆形"}, {"key": "重量", "value": "重"}],
        [{"key": "大小", "value": "小"}, {"key": "温度", "value": "冷"}],
        [{"key": "颜色", "value": "蓝色"}],
        [{"key": "形状", "value": "正方形"}],
    ]
    for i, conds in enumerate(unknown_conditions):
        # filter.all 的特殊契约：缺条件不是整个请求 unknown，而是 ok 结果里的 unknown_ids。
        if i < 6:
            matched, unknown_ids = expect_filter(objects, conds)
            ctx.add("unknown", "filter.all", {"conditions": conds},
                    expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")
        elif i == 6:
            matched, unknown_ids = expect_filter(objects, conds, ["e99", "e1"])
            ctx.add("unknown", "filter.all", {"conditions": conds, "ids": ["e99", "e1"]},
                    expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")
        else:
            matched, unknown_ids = expect_filter(objects, conds, ["e99"])
            ctx.add("unknown", "filter.all", {"conditions": conds, "ids": ["e99"]},
                    expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")

    for conds in basic_conditions[:4]:
        matched, unknown_ids = expect_filter(objects, conds)
        ctx.add_state("state", "filter.all", {"conditions": conds},
                      expected_result={"ids": matched, "unknown_ids": unknown_ids}, validator="filter")


def lesson_07(ctx: BuildCtx) -> None:
    """课7：显式空间关系查询；左右等必须有明确参照数据。"""
    ids = [f"f{i}" for i in range(1, 12)]
    objects = [mk_obj(oid, f"位置卡{i + 1}") for i, oid in enumerate(ids)]
    predicates = ["inside", "above", "below", "next_to", "left_of", "right_of", "near"]
    combos: List[Tuple[str, str]] = []
    for oid in ids:
        for pred in predicates:
            if len(combos) < 28:
                combos.append((oid, pred))
    relations = []
    targets: Dict[Tuple[str, str], str] = {}
    for i, (subject, pred) in enumerate(combos):
        target = ids[(i * 3 + 2) % len(ids)]
        if target == subject:
            target = ids[(i + 1) % len(ids)]
        targets[(subject, pred)] = target
        relations.append(mk_rel(f"rel{i + 1}", subject, pred, target))
    ctx.set_world({"objects": objects, "relations": relations})

    for subject, pred in combos[:16]:
        ctx.add("basic", "relation.query", {"subject": subject, "predicate": pred},
                expected_result={"targets": [targets[(subject, pred)]]}, validator="set")
    for subject, pred in combos[16:28]:
        ctx.add("transfer", "relation.query", {"subject": subject, "predicate": pred},
                expected_result={"targets": [targets[(subject, pred)]]}, validator="set")
    unknown_cases = [
        {"subject": "f99", "predicate": "inside"},
        {"subject": "f98", "predicate": "above"},
        {"subject": ids[0], "predicate": "behind"},
        {"subject": ids[1], "predicate": "under"},
        {"subject": ids[3], "predicate": "far"},
        {"subject": ids[4], "predicate": "middle"},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "relation.query", args, expected_status="unknown")
    ctx.add("unknown", "relation.query", {"subject": "", "predicate": "inside"}, expected_status="reject", validator="reject")
    ctx.add("unknown", "relation.query", {"subject": ids[2], "predicate": ""}, expected_status="reject", validator="reject")
    for subject, pred in combos[:4]:
        ctx.add_state("state", "relation.query", {"subject": subject, "predicate": pred},
                      expected_result={"targets": [targets[(subject, pred)]]}, validator="set")


def lesson_08(ctx: BuildCtx) -> None:
    """课8：部件-整体与工具-用途的显式关系；不推广未给用途。"""
    parts = [f"p{i}" for i in range(1, 15)]
    wholes = [f"w{i}" for i in range(1, 8)]
    tools = [f"t{i}" for i in range(1, 15)]
    uses = [f"u{i}" for i in range(1, 15)]
    objects = []
    for p in parts:
        objects.append(mk_obj(p, f"部件{p}"))
    for w in wholes:
        objects.append(mk_obj(w, f"整体{w}"))
    for t in tools:
        objects.append(mk_obj(t, f"工具{t}"))
    for u in uses:
        objects.append(mk_obj(u, f"用途{u}"))
    relations = []
    targets: Dict[Tuple[str, str], str] = {}
    for i, part in enumerate(parts):
        whole = wholes[i % len(wholes)]
        relations.append(mk_rel(f"r-part-{i}", part, "part_of", whole))
        targets[(part, "part_of")] = whole
    for i, tool in enumerate(tools):
        use = uses[i % len(uses)]
        relations.append(mk_rel(f"r-use-{i}", tool, "used_for", use))
        targets[(tool, "used_for")] = use
    ctx.set_world({"objects": objects, "relations": relations})

    for i in range(8):
        part = parts[i]
        ctx.add("basic", "relation.query", {"subject": part, "predicate": "part_of"},
                expected_result={"targets": [targets[(part, "part_of")]]}, validator="set")
    for i in range(8):
        tool = tools[i]
        ctx.add("basic", "relation.query", {"subject": tool, "predicate": "used_for"},
                expected_result={"targets": [targets[(tool, "used_for")]]}, validator="set")
    for i in range(8, 14):
        part = parts[i]
        ctx.add("transfer", "relation.query", {"subject": part, "predicate": "part_of"},
                expected_result={"targets": [targets[(part, "part_of")]]}, validator="set")
    for i in range(8, 14):
        tool = tools[i]
        ctx.add("transfer", "relation.query", {"subject": tool, "predicate": "used_for"},
                expected_result={"targets": [targets[(tool, "used_for")]]}, validator="set")
    unknown_cases = [
        {"subject": parts[0], "predicate": "used_for"},
        {"subject": parts[1], "predicate": "used_for"},
        {"subject": tools[0], "predicate": "part_of"},
        {"subject": tools[1], "predicate": "part_of"},
        {"subject": "x99", "predicate": "part_of"},
        {"subject": "x98", "predicate": "used_for"},
        {"subject": tools[2], "predicate": "part_of_all"},
        {"subject": "", "predicate": "used_for"},
    ]
    for args in unknown_cases[:7]:
        ctx.add("unknown", "relation.query", args, expected_status="unknown")
    ctx.add("unknown", "relation.query", unknown_cases[7], expected_status="reject", validator="reject")
    for part in parts[:2]:
        ctx.add_state("state", "relation.query", {"subject": part, "predicate": "part_of"},
                      expected_result={"targets": [targets[(part, "part_of")]]}, validator="set")
    for tool in tools[:2]:
        ctx.add_state("state", "relation.query", {"subject": tool, "predicate": "used_for"},
                      expected_result={"targets": [targets[(tool, "used_for")]]}, validator="set")


def _action_result(step: Dict[str, Any]) -> Dict[str, Any]:
    op = step.get("op")
    args = step.get("args") or {}
    if op == "attr.teach":
        return {"id": args.get("id"), "key": args.get("key"), "value": args.get("value")}
    if op == "relation.set":
        return {"subject": args.get("subject"), "predicate": args.get("predicate"), "object": args.get("object")}
    return {}


def lesson_09(ctx: BuildCtx) -> None:
    """课9：受控动作/顺序；requires校验，失败不提交半套；状态跨进程可查。"""
    ids = [f"g{i}" for i in range(1, 9)]
    objects = []
    attrs = {
        ids[0]: {"颜色": "红色", "位置": "桌上"}, ids[1]: {"颜色": "蓝色", "位置": "架上"},
        ids[2]: {"颜色": "绿色", "位置": "盒内"}, ids[3]: {"颜色": "黄色", "位置": "桌上"},
        ids[4]: {"颜色": "红色", "位置": "架上"}, ids[5]: {"颜色": "蓝色", "位置": "盒内"},
        ids[6]: {"颜色": "绿色", "位置": "桌上"}, ids[7]: {"颜色": "黄色", "位置": "架上"},
    }
    for i, oid in enumerate(ids):
        objects.append(mk_obj(oid, f"物品{i + 1}", attrs[oid]))
    ctx.set_world({"objects": objects})
    for oid in ids:
        ctx.tracked_attrs[oid] = dict(attrs[oid])
    new_colors = ["橙色", "紫色", "白色", "黑色", "粉色", "青色", "棕色", "灰色"]

    def add_action(qtype: str, steps: List[Dict[str, Any]], skill: str, note: str = "") -> None:
        expected_results = [_action_result(s) for s in steps]
        ctx.add(qtype, "actions.run", {"steps": steps},
                expected_result={"results": expected_results}, validator="plan", skill=skill, note=note)
        for s in steps:
            if s["op"] == "attr.teach":
                a = s["args"]
                ctx.tracked_attrs.setdefault(a["id"], {})[a["key"]] = a["value"]
            elif s["op"] == "relation.set":
                a = s["args"]
                ctx.tracked_rels[(a["subject"], a["predicate"])] = a["object"]

    for i in range(4):
        oid = ids[i]
        steps = [{"op": "attr.teach", "args": {"id": oid, "key": "颜色", "value": new_colors[i]},
                  "requires": [{"id": oid, "key": "位置", "value": attrs[oid]["位置"]}]}]
        add_action("basic", steps, "单步补教")
    for i in range(4, 8):
        oid = ids[i]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "颜色", "value": new_colors[i]}},
            {"op": "attr.teach", "args": {"id": oid, "key": "标记", "value": f"已完成{i}"}},
        ]
        add_action("basic", steps, "两步补教")
    for i in range(4):
        subject = ids[i]
        target = ids[(i + 2) % len(ids)]
        steps = [{"op": "relation.set", "args": {"subject": subject, "predicate": "inside", "object": target}}]
        add_action("basic", steps, "关系设置")
    for i in range(4):
        oid = ids[i]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "准备"},
             "requires": [{"id": oid, "key": "颜色", "value": ctx.tracked_attrs[oid]["颜色"]}]},
            {"op": "relation.set", "args": {"subject": oid, "predicate": "next_to", "object": ids[(i + 3) % len(ids)]}},
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "完成"}},
        ]
        add_action("basic", steps, "三步顺序")

    for i in range(6):
        oid = ids[i]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "位置", "value": "盒子" + str(i)}},
            {"op": "attr.teach", "args": {"id": oid, "key": "颜色", "value": new_colors[(i + 2) % len(new_colors)]}},
            {"op": "relation.set", "args": {"subject": oid, "predicate": "above", "object": ids[(i + 1) % len(ids)]}},
        ]
        add_action("transfer", steps, "换场景三步")
    for i in range(6):
        oid = ids[(i + 2) % len(ids)]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "大小", "value": "大" if i % 2 == 0 else "小"}},
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "检查"},
             "requires": [{"id": oid, "key": "颜色", "value": ctx.tracked_attrs[oid]["颜色"]}]},
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "通过"}},
            {"op": "relation.set", "args": {"subject": oid, "predicate": "near", "object": ids[(i + 4) % len(ids)]}},
        ]
        add_action("transfer", steps, "带前置条件四步")

    reject_cases = [
        {"steps": [{"op": "attr.teach", "args": {"id": ids[0], "key": "颜色", "value": "灰色"},
                    "requires": [{"id": ids[0], "key": "大小", "value": "大"}]}]},
        {"steps": [{"op": "attr.teach", "args": {"id": ids[1], "key": "颜色", "value": "灰色"},
                    "requires": [{"id": ids[1], "key": "颜色", "value": "红色"}]}]},
        {"steps": [{"op": "attr.teach", "args": {"id": "g99", "key": "颜色", "value": "灰色"}}]},
        {"steps": [{"op": "unknown.op", "args": {"id": ids[2]}}]},
        {"steps": [{"op": "relation.set", "args": {"subject": "g99", "predicate": "inside", "object": ids[0]}}]},
        {"steps": [{"op": "attr.teach", "args": {"key": "颜色", "value": "灰色"}}]},
        {"steps": [{"op": "relation.set", "args": {"subject": ids[3], "predicate": "inside"}}]},
        {"steps": [{"op": "attr.teach", "args": {"id": ids[4], "key": "颜色", "value": "灰色"},
                    "requires": [{"id": "g98", "key": "颜色", "value": "蓝色"}]}]},
    ]
    for args in reject_cases:
        ctx.add("unknown", "actions.run", args, expected_status="reject", validator="reject")

    for oid in ids[:2]:
        key = "颜色"
        ctx.add_state("state", "attr.get", {"id": oid, "key": key},
                      expected_result={"id": oid, "key": key, "value": ctx.tracked_attrs[oid][key]})
    for oid in ids[:2]:
        ctx.add_state("state", "relation.query", {"subject": oid, "predicate": "next_to"},
                      expected_result={"targets": [ctx.tracked_rels[(oid, "next_to")]]}, validator="set")


def _pair_expected(left: List[str], right: List[str]) -> Dict[str, Any]:
    n = min(len(left), len(right))
    pairs = [[left[i], right[i]] for i in range(n)]
    return {
        "pairs": pairs,
        "unpaired_left": sorted(left[n:]),
        "unpaired_right": sorted(right[n:]),
    }


def lesson_10(ctx: BuildCtx) -> None:
    """课10：点数与一一对应；按ID去重，明确空列表为0，缺列表未知。"""
    ids = [f"h{i}" for i in range(1, 13)]
    objects = [mk_obj(oid, f"杯子{i + 1}") for i, oid in enumerate(ids)]
    ctx.set_world({"objects": objects})
    count_lists = [
        [ids[0], ids[1], ids[2]],
        [ids[0], ids[0], ids[1]],
        [ids[2], ids[1], ids[0]],
        [],
        [ids[3], ids[4], ids[5], ids[6]],
    ]
    for xs in count_lists:
        ctx.add("basic", "count", {"ids": xs},
                expected_result={"count": len(set(xs))})
    pair_cases = [
        ([ids[0], ids[1], ids[2]], [ids[3], ids[4], ids[5]]),
        ([ids[0], ids[1]], [ids[3], ids[4], ids[5]]),
        ([ids[0], ids[1], ids[2]], [ids[3], ids[4]]),
        ([ids[0], ids[1], ids[2], ids[3]], [ids[4], ids[5], ids[6], ids[7]]),
        ([ids[0], ids[1]], [ids[3], ids[4]]),
        ([ids[2], ids[3], ids[4]], [ids[5], ids[6], ids[7]]),
        ([ids[0], ids[1], ids[2], ids[3], ids[4]], [ids[5], ids[6], ids[7]]),
    ]
    for left, right in pair_cases:
        ctx.add("basic", "pair", {"left": left, "right": right},
                expected_result=_pair_expected(left, right), validator="pair")
    for xs in [[ids[7], ids[8], ids[9]], [ids[0], ids[1], ids[2], ids[3], ids[4]], [ids[10], ids[11]], [ids[5]]]:
        ctx.add("basic", "count", {"ids": xs}, expected_result={"count": len(set(xs))})

    transfer_pairs = [
        ([ids[0], ids[2], ids[4], ids[6]], [ids[1], ids[3], ids[5], ids[7]]),
        ([ids[0], ids[1], ids[2]], [ids[3]]),
        ([ids[0], ids[1]], [ids[3], ids[4], ids[5], ids[6]]),
        ([ids[6], ids[7], ids[8], ids[9], ids[10]], [ids[0], ids[1], ids[2]]),
        ([ids[0], ids[1], ids[2], ids[3], ids[4], ids[5]], [ids[6], ids[7], ids[8], ids[9], ids[10], ids[11]]),
        ([ids[8], ids[9]], [ids[10], ids[11]]),
    ]
    for left, right in transfer_pairs:
        ctx.add("transfer", "pair", {"left": left, "right": right},
                expected_result=_pair_expected(left, right), validator="pair")
    for xs in [[ids[0], ids[1], ids[2], ids[3]], [ids[9], ids[10], ids[11]], [],
               [ids[0], ids[1], ids[2], ids[3], ids[4], ids[5], ids[6], ids[7]],
               [ids[2], ids[2], ids[3], ids[4]], [ids[5], ids[6], ids[7], ids[8], ids[9]]]:
        ctx.add("transfer", "count", {"ids": xs}, expected_result={"count": len(set(xs))})

    unknown_cases = [
        ("count", {}),
        ("count", {"ids": ["missing-a"]}),
        ("count", {"ids": [ids[0], "missing-b"]}),
        ("pair", {"left": [ids[0]]}),
        ("pair", {"right": [ids[1]]}),
        ("pair", {}),
        ("pair", {"left": ["missing-c"], "right": [ids[2]]}),
        ("pair", {"left": [], "right": ["missing-d"]}),
    ]
    for op, args in unknown_cases:
        ctx.add("unknown", op, args, expected_status="unknown", validator="unknown_or_error")

    ctx.add_state("state", "count", {"ids": [ids[0], ids[1], ids[2]]}, expected_result={"count": 3})
    ctx.add_state("state", "count", {"ids": []}, expected_result={"count": 0})
    ctx.add_state("state", "pair", {"left": [ids[0], ids[1]], "right": [ids[2]]},
                  expected_result=_pair_expected([ids[0], ids[1]], [ids[2]]), validator="pair")
    ctx.add_state("state", "count", {"ids": [ids[3], ids[3], ids[4]]}, expected_result={"count": 2})


def lesson_11(ctx: BuildCtx) -> None:
    """课11：集合数量比较；空集合0，缺集合未知。"""
    ids = [f"i{i}" for i in range(1, 13)]
    objects = [mk_obj(oid, f"小球{i + 1}") for i, oid in enumerate(ids)]
    ctx.set_world({"objects": objects})
    compare_sets = [
        ([ids[0], ids[1], ids[2]], [ids[3], ids[4]]),
        ([ids[0], ids[1]], [ids[3], ids[4]]),
        ([ids[0]], [ids[3], ids[4], ids[5]]),
        ([], [ids[0]]),
        ([ids[0], ids[1], ids[2]], []),
        ([ids[5], ids[6], ids[7], ids[8]], [ids[0], ids[1], ids[2], ids[3]]),
        ([ids[0], ids[0], ids[1]], [ids[2]]),
        ([ids[0], ids[1], ids[2], ids[3]], [ids[4], ids[5]]),
        ([], []),
    ]
    for left, right in compare_sets:
        ctx.add("basic", "count.compare", {"left": left, "right": right},
                expected_result={"comparison": compare_values(len(set(left)), len(set(right))),
                                 "difference": len(set(left)) - len(set(right))})
    for xs in [[ids[0], ids[1], ids[2]], [], [ids[3], ids[3], ids[4]]]:
        ctx.add("basic", "count", {"ids": xs}, expected_result={"count": len(set(xs))})
    for xs in [[ids[5]], [ids[6], ids[7]]]:
        ctx.add("basic", "count", {"ids": xs}, expected_result={"count": len(set(xs))})
    for xs in [[ids[8], ids[9], ids[10]], [ids[11]]]:
        ctx.add("basic", "count", {"ids": xs}, expected_result={"count": len(set(xs))})

    transfer_compare = [
        ([ids[0], ids[1], ids[2], ids[3]], [ids[4], ids[5], ids[6]]),
        ([ids[0], ids[1]], [ids[2], ids[3], ids[4]]),
        ([ids[7], ids[8], ids[9], ids[10], ids[11]], [ids[0], ids[1]]),
        ([ids[0], ids[0], ids[0]], [ids[1], ids[2]]),
        ([], [ids[3], ids[4], ids[5]]),
        ([ids[6], ids[7]], []),
        ([ids[0], ids[1], ids[2]], [ids[3], ids[4], ids[5]]),
    ]
    for left, right in transfer_compare:
        ctx.add("transfer", "count.compare", {"left": left, "right": right},
                expected_result={"comparison": compare_values(len(set(left)), len(set(right))),
                                 "difference": len(set(left)) - len(set(right))})
    for xs in [[ids[0], ids[1], ids[2], ids[3], ids[4]], [ids[5], ids[5], ids[6], ids[6]],
               [], [ids[7], ids[8], ids[9], ids[10], ids[11], ids[0]],
               [ids[2], ids[3], ids[4], ids[5]]]:
        ctx.add("transfer", "count", {"ids": xs}, expected_result={"count": len(set(xs))})

    unknown_cases = [
        ("count.compare", {"left": [ids[0]]}),
        ("count.compare", {"right": [ids[0]]}),
        ("count.compare", {}),
        ("count.compare", {"left": ["missing-a"], "right": [ids[0]]}),
        ("count.compare", {"left": [ids[0]], "right": ["missing-b"]}),
        ("count", {}),
        ("count", {"ids": ["missing-c"]}),
        ("count", {"ids": [ids[0], "missing-d"]}),
    ]
    for op, args in unknown_cases:
        ctx.add("unknown", op, args, expected_status="unknown", validator="unknown_or_error")

    for left, right in [([ids[0], ids[1]], [ids[2]]), ([], []), ([ids[3]], [ids[4], ids[5]]), ([ids[6], ids[7]], [])]:
        ctx.add_state("state", "count.compare", {"left": left, "right": right},
                      expected_result={"comparison": compare_values(len(set(left)), len(set(right))),
                                       "difference": len(set(left)) - len(set(right))})


def lesson_12(ctx: BuildCtx) -> None:
    """课12：十以内加减；数值严格，缺参数/非数值拒绝。"""
    ctx.set_world({"objects": [mk_obj("j1", "计数卡")]})
    for i in range(16):
        a = ctx.rng.randint(0, 10)
        b = ctx.rng.randint(0, 10)
        op = "add" if i % 2 == 0 else "sub"
        if op == "sub" and b > a:
            a, b = b, a
        value = a + b if op == "add" else a - b
        ctx.add("basic", "arithmetic", {"operator": op, "a": a, "b": b},
                expected_result={"value": value})
    for i in range(12):
        a = ctx.rng.randint(0, 10)
        b = ctx.rng.randint(0, 10)
        op = "sub" if i % 3 == 0 else "add"
        if op == "sub" and b > a:
            a, b = b, a
        value = a + b if op == "add" else a - b
        ctx.add("transfer", "arithmetic", {"operator": op, "a": a, "b": b},
                expected_result={"value": value})
    unknown_cases = [
        {"a": 3, "b": 2},
        {"operator": "add", "b": 2},
        {"operator": "add", "a": 3},
        {"operator": "mul", "a": 3, "b": 2},
        {"operator": "add", "a": "三", "b": 2},
        {"operator": "sub", "a": 3, "b": "二"},
        {"operator": "", "a": 1, "b": 1},
        {"operator": "add", "a": None, "b": 1},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "arithmetic", args, expected_status="reject", validator="reject")
    for i in range(4):
        a = ctx.rng.randint(0, 10)
        b = ctx.rng.randint(0, 10)
        op = "add" if i % 2 == 0 else "sub"
        if op == "sub" and b > a:
            a, b = b, a
        ctx.add_state("state", "arithmetic", {"operator": op, "a": a, "b": b},
                      expected_result={"value": a + b if op == "add" else a - b})


def lesson_13(ctx: BuildCtx) -> None:
    """课13：显式重复单位续排；单元素/空模式不声称唯一规律。"""
    ctx.set_world({"objects": [mk_obj("k1", "节拍卡")]})
    units = [
        ["A", "B"], ["A", "A", "B"], ["红", "蓝"], ["圆形", "方形", "三角形"],
        ["A", "B", "C"], ["上", "下"], ["x", "y", "z"], ["红", "黄", "蓝", "绿"],
    ]
    for i in range(16):
        unit = units[i % len(units)]
        count = 1 + (i // len(units))
        prefix = unit * 2
        nxt = (unit * ((count + len(unit) - 1) // len(unit)))[:count]
        ctx.add("basic", "pattern.next", {"pattern": prefix, "count": count},
                expected_result={"values": nxt}, extra={"prefix": prefix}, validator="pattern")
    transfer_units = [
        ["圆形", "正方形"], ["红", "红", "蓝"], ["甲", "乙", "丙", "丁"],
        ["A", "B", "C", "D"], ["上", "中", "下"], ["一", "二", "三"],
    ]
    for i in range(12):
        unit = transfer_units[i % len(transfer_units)]
        count = 2 + (i // len(transfer_units))
        prefix = unit * 2
        nxt = (unit * ((count + len(unit) - 1) // len(unit)))[:count]
        ctx.add("transfer", "pattern.next", {"pattern": prefix, "count": count},
                expected_result={"values": nxt}, extra={"prefix": prefix}, validator="pattern")
    unknown_cases = [
        {"pattern": [], "count": 2},
        {"pattern": ["A"], "count": 3},
        {"pattern": ["圆形"], "count": 1},
        {"pattern": ["A", "B"], "count": -1},
        {"pattern": ["A", "B"], "count": "2"},
        {"pattern": "ABAB", "count": 2},
        {"pattern": ["A", "B"]},
        {"count": 2},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "pattern.next", args, expected_status="reject", validator="reject")
    for i in range(4):
        unit = units[i]
        count = 2
        ctx.add_state("state", "pattern.next", {"pattern": unit * 2, "count": count},
                      expected_result={"values": unit[:count] if count <= len(unit) else unit * 2}, validator="pattern")


def lesson_14(ctx: BuildCtx) -> None:
    """课14：给定ISO日期比较/偏移；不读取系统当前日期。"""
    ctx.set_world({"objects": [mk_obj("m1", "日历卡")]})
    base = date(2026, 1, 1)

    def add_compare(qtype: str, left: date, right: date) -> None:
        diff = (left - right).days
        ctx.add(qtype, "time.compare", {"left": left.isoformat(), "right": right.isoformat()},
                expected_result={"comparison": compare_values(left, right), "difference_days": diff},
                validator="time")

    for i in range(8):
        left = base + timedelta(days=ctx.rng.randint(0, 330))
        right = base + timedelta(days=ctx.rng.randint(0, 330))
        add_compare("basic", left, right)
    for i in range(8):
        ref = base + timedelta(days=ctx.rng.randint(0, 300))
        off = ctx.rng.randint(-20, 60)
        ctx.add("basic", "time.relative", {"reference_date": ref.isoformat(), "offset_days": off},
                expected_result={"date": (ref + timedelta(days=off)).isoformat()})
    for i in range(6):
        left = base + timedelta(days=ctx.rng.randint(0, 330))
        right = base + timedelta(days=ctx.rng.randint(0, 330))
        add_compare("transfer", left, right)
    for i in range(6):
        ref = base + timedelta(days=ctx.rng.randint(0, 300))
        off = ctx.rng.randint(-40, 90)
        ctx.add("transfer", "time.relative", {"reference_date": ref.isoformat(), "offset_days": off},
                expected_result={"date": (ref + timedelta(days=off)).isoformat()})
    unknown_cases = [
        ("time.compare", {"left": "2026-01-01"}),
        ("time.compare", {"right": "2026-01-01"}),
        ("time.compare", {"left": "2026-13-01", "right": "2026-01-01"}),
        ("time.compare", {"left": "昨天", "right": "2026-01-01"}),
        ("time.relative", {"reference_date": "2026-01-01"}),
        ("time.relative", {"reference_date": "2026-01-01", "offset_days": "两天"}),
        ("time.relative", {"reference_date": "not-a-date", "offset_days": 2}),
        ("time.compare", {"left": "", "right": "2026-01-01"}),
    ]
    for op, args in unknown_cases:
        ctx.add("unknown", op, args, expected_status="reject", validator="reject")
    for i in range(4):
        left = base + timedelta(days=ctx.rng.randint(0, 300))
        right = base + timedelta(days=ctx.rng.randint(0, 300))
        ctx.add_state("state", "time.compare", {"left": left.isoformat(), "right": right.isoformat()},
                      expected_result={"comparison": compare_values(left, right),
                                       "difference_days": (left - right).days}, validator="time")


def _obs_diff(left: Dict[str, Any], right: Dict[str, Any]) -> Dict[str, Any]:
    lc = left.get("conditions") or {}
    rc = right.get("conditions") or {}
    lo = left.get("outcome") or {}
    ro = right.get("outcome") or {}
    cc = {}
    for key in sorted(set(lc) | set(rc)):
        if lc.get(key) != rc.get(key):
            cc[key] = {"left": lc.get(key), "right": rc.get(key)}
    co = {}
    for key in sorted(set(lo) | set(ro)):
        if lo.get(key) != ro.get(key):
            co[key] = {"left": lo.get(key), "right": ro.get(key)}
    return {
        "changed_conditions": cc,
        "changed_outcomes": co,
        "controlled": len(cc) == 1,
        "causal_claim": False,
    }


def lesson_15(ctx: BuildCtx) -> None:
    """课15：比较两条给定观察；controlled只表示单条件变化，不宣称因果。"""
    base_cond = {"开关": "关", "水量": "中", "位置": "桌边", "盖子": "开"}
    base_out = {"灯": "灭", "声音": "无", "水位": "正常"}
    cond_keys = list(base_cond.keys())
    out_keys = list(base_out.keys())
    cond_values = {"开关": ["关", "开"], "水量": ["少", "中", "多"], "位置": ["桌边", "中央", "架上"], "盖子": ["开", "关"]}
    out_values = {"灯": ["灭", "亮"], "声音": ["无", "有"], "水位": ["正常", "升高"]}
    obs = []
    for i in range(14):
        cond = dict(base_cond)
        out = dict(base_out)
        for k in range(i % 3):
            key = cond_keys[(i + k) % len(cond_keys)]
            cond[key] = cond_values[key][(i + k) % len(cond_values[key])]
        for k in range((i // 2) % 3):
            key = out_keys[(i + k) % len(out_keys)]
            out[key] = out_values[key][(i + k) % len(out_values[key])]
        obs.append({"id": f"obs{i + 1}", "conditions": cond, "outcome": out})
    ctx.set_world({"objects": [mk_obj("n1", "观察记录")], "observations": obs})
    pairs = [(i, j) for i in range(len(obs)) for j in range(i + 1, len(obs))]
    for i, j in pairs[:16]:
        exp = _obs_diff(obs[i], obs[j])
        ctx.add("basic", "observation.compare", {"left_id": obs[i]["id"], "right_id": obs[j]["id"]},
                expected_result=exp, validator="observation",
                extra={"changed_condition_keys": sorted(exp["changed_conditions"].keys()),
                       "changed_outcome_keys": sorted(exp["changed_outcomes"].keys())})
    for i, j in pairs[16:28]:
        exp = _obs_diff(obs[i], obs[j])
        ctx.add("transfer", "observation.compare", {"left_id": obs[i]["id"], "right_id": obs[j]["id"]},
                expected_result=exp, validator="observation",
                extra={"changed_condition_keys": sorted(exp["changed_conditions"].keys()),
                       "changed_outcome_keys": sorted(exp["changed_outcomes"].keys())})
    unknown_cases = [
        {"left_id": "obs99", "right_id": "obs1"},
        {"left_id": "obs1", "right_id": "obs98"},
        {"left_id": "obs97", "right_id": "obs96"},
        {"left_id": "obs96", "right_id": "obs96"},
        {"left_id": "obs95", "right_id": "obs94"},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "observation.compare", args, expected_status="unknown")
    for args in [
        {"left_id": "obs3"},
        {"left_id": "obs4", "right_id": ""},
        {"left_id": "", "right_id": "obs5"},
    ]:
        ctx.add("unknown", "observation.compare", args, expected_status="reject", validator="reject")
    for i, j in pairs[28:32]:
        exp = _obs_diff(obs[i], obs[j])
        ctx.add_state("state", "observation.compare", {"left_id": obs[i]["id"], "right_id": obs[j]["id"]},
                      expected_result=exp, validator="observation",
                      extra={"changed_condition_keys": sorted(exp["changed_conditions"].keys()),
                             "changed_outcome_keys": sorted(exp["changed_outcomes"].keys())})


def _sentence(oid: str, key: str, value: Any) -> str:
    return f"{oid} 的 {key} 是 {value}"


def lesson_16(ctx: BuildCtx) -> None:
    """课16：受控中文句式解析/渲染；只支持公布句式，不支持自由语言。"""
    ids = [f"o{i}" for i in range(1, 9)]
    colors = ["红色", "蓝色", "绿色", "黄色", "紫色", "橙色", "白色", "黑色"]
    shapes = ["圆形", "三角形", "正方形", "长方形"]
    objects = []
    facts: List[Tuple[str, str, Any]] = []
    for i, oid in enumerate(ids):
        attrs = {"颜色": colors[i]}
        facts.append((oid, "颜色", colors[i]))
        if i < 4:
            attrs["形状"] = shapes[i]
            facts.append((oid, "形状", shapes[i]))
        if i < 2:
            attrs["大小"] = "大" if i == 0 else "小"
            facts.append((oid, "大小", attrs["大小"]))
        objects.append(mk_obj(oid, f"词卡{i + 1}", attrs))
    ctx.set_world({"objects": objects})

    for oid, key, value in facts[:8]:
        ctx.add("basic", "sentence.parse", {"text": _sentence(oid, key, value)},
                expected_result={"subject": oid, "key": key, "value": value})
    for oid, key, value in facts[:8]:
        ctx.add("basic", "sentence.render", {"id": oid, "key": key},
                expected_result={"text": _sentence(oid, key, value)})
    for oid, key, value in facts[8:14]:
        ctx.add("transfer", "sentence.parse", {"text": _sentence(oid, key, value)},
                expected_result={"subject": oid, "key": key, "value": value})
    for oid, key, value in facts[8:14]:
        ctx.add("transfer", "sentence.render", {"id": oid, "key": key},
                expected_result={"text": _sentence(oid, key, value)})
    unsupported = [
        "苹果是红色", ids[0] + "的颜色是红色", ids[1] + " 的颜色 红色",
        "把 " + ids[2] + " 涂成绿色",
    ]
    for text in unsupported:
        ctx.add("unknown", "sentence.parse", {"text": text}, expected_status="unknown")
    for oid in ["o99", "o98"]:
        ctx.add("unknown", "sentence.render", {"id": oid, "key": "颜色"}, expected_status="unknown")
    ctx.add("unknown", "sentence.render", {"id": ids[6], "key": "形状"}, expected_status="unknown")
    ctx.add("unknown", "sentence.render", {"id": ids[7], "key": "大小"}, expected_status="unknown")
    for oid, key, value in facts[:4]:
        ctx.add_state("state", "sentence.render", {"id": oid, "key": key},
                      expected_result={"text": _sentence(oid, key, value)})


def lesson_17(ctx: BuildCtx) -> None:
    """课17：结构化故事take/give/place重放；缺事件/动机保持未知。"""
    holder_ids = [f"sa{i}" for i in range(1, 15)]
    place_ids = [f"sb{i}" for i in range(1, 19)]
    extra_ids = [f"sc{i}" for i in range(1, 9)]
    objects = []
    story = []
    holder_of: Dict[str, str] = {}
    place_of: Dict[str, str] = {}
    for i, oid in enumerate(holder_ids):
        objects.append(mk_obj(oid, f"故事物品A{i + 1}"))
        actor = ["甲", "乙", "丙", "丁"][i % 4]
        to = ["乙", "丙", "丁", "甲"][i % 4]
        story.append({"id": f"st-a{i}-1", "actor": actor, "action": "take", "object": oid})
        story.append({"id": f"st-a{i}-2", "actor": actor, "action": "give", "object": oid, "to": to})
        holder_of[oid] = to
    for i, oid in enumerate(place_ids):
        objects.append(mk_obj(oid, f"故事物品B{i + 1}"))
        actor = ["甲", "乙", "丙", "丁"][i % 4]
        place = ["桌上", "架上", "盒内", "门边"][i % 4]
        story.append({"id": f"st-b{i}-1", "actor": actor, "action": "take", "object": oid})
        story.append({"id": f"st-b{i}-2", "actor": actor, "action": "place", "object": oid, "place": place})
        place_of[oid] = place
    for oid in extra_ids:
        objects.append(mk_obj(oid, f"故事物品C{oid}"))
    # 三个只有take、没有后续去向的物品，用于place未知。
    for i in range(3):
        oid = extra_ids[i]
        story.append({"id": f"st-c{i}", "actor": "甲", "action": "take", "object": oid})
    ctx.set_world({"objects": objects, "story": story})

    for i in range(8):
        oid = holder_ids[i]
        ctx.add("basic", "story.query", {"object": oid, "field": "holder"},
                expected_result={"value": holder_of[oid]})
    for i in range(8):
        oid = place_ids[i]
        ctx.add("basic", "story.query", {"object": oid, "field": "place"},
                expected_result={"value": place_of[oid]})
    for i in range(8, 12):
        oid = holder_ids[i]
        ctx.add("transfer", "story.query", {"object": oid, "field": "holder"},
                expected_result={"value": holder_of[oid]})
    for i in range(8, 16):
        oid = place_ids[i]
        ctx.add("transfer", "story.query", {"object": oid, "field": "place"},
                expected_result={"value": place_of[oid]})
    for i in range(3):
        ctx.add("unknown", "story.query", {"object": extra_ids[i], "field": "place"}, expected_status="unknown")
    for i in range(3, 6):
        ctx.add("unknown", "story.query", {"object": extra_ids[i], "field": "holder"}, expected_status="unknown")
    for oid in ["sx99", "sx98"]:
        ctx.add("unknown", "story.query", {"object": oid, "field": "holder"}, expected_status="unknown")
    for i in range(12, 14):
        oid = holder_ids[i]
        ctx.add_state("state", "story.query", {"object": oid, "field": "holder"},
                      expected_result={"value": holder_of[oid]})
    for i in range(16, 18):
        oid = place_ids[i]
        ctx.add_state("state", "story.query", {"object": oid, "field": "place"},
                      expected_result={"value": place_of[oid]})


def lesson_18(ctx: BuildCtx) -> None:
    """课18：唯一指代消解 + 两步模拟动作；多候选先澄清等待。"""
    ids = [f"r{i}" for i in range(1, 13)]
    objects = []
    attrs = {}
    for i, oid in enumerate(ids):
        attrs[oid] = {"颜色": ["红色", "蓝色", "绿色", "黄色"][i % 4], "位置": ["桌上", "架上"][i % 2]}
        objects.append(mk_obj(oid, f"指代卡{i + 1}", dict(attrs[oid])))
    ctx.set_world({"objects": objects})
    for oid in ids:
        ctx.tracked_attrs[oid] = dict(attrs[oid])

    def add_action(qtype: str, steps: List[Dict[str, Any]], skill: str) -> None:
        ctx.add(qtype, "actions.run", {"steps": steps},
                expected_result={"results": [_action_result(s) for s in steps]}, validator="plan", skill=skill)
        for s in steps:
            if s["op"] == "attr.teach":
                a = s["args"]
                ctx.tracked_attrs.setdefault(a["id"], {})[a["key"]] = a["value"]
            elif s["op"] == "relation.set":
                a = s["args"]
                ctx.tracked_rels[(a["subject"], a["predicate"])] = a["object"]

    for i in range(8):
        ctx.add("basic", "reference.resolve", {"candidates": [ids[i]]},
                expected_result={"id": ids[i]}, skill="唯一指代")
    for i in range(8):
        oid = ids[i]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "颜色", "value": "已处理" + str(i)}},
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "两步完成"}},
        ]
        add_action("basic", steps, "两步指令")
    for i in range(4):
        ctx.add("transfer", "reference.resolve", {"candidates": [ids[8 + i]]},
                expected_result={"id": ids[8 + i]}, skill="换候选唯一指代")
    for i in range(8):
        oid = ids[i]
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "位置", "value": "新位置" + str(i)}},
            {"op": "relation.set", "args": {"subject": oid, "predicate": "inside", "object": ids[(i + 1) % 8]}},
            {"op": "attr.teach", "args": {"id": oid, "key": "状态", "value": "三步完成"}},
        ]
        add_action("transfer", steps, "三步指令")
    ref_unknown = [
        ({"candidates": [ids[0], ids[1]]}, "unknown"),
        ({"candidates": [ids[2], ids[3]]}, "unknown"),
        ({"candidates": []}, "unknown"),
        ({"candidates": ["r99", "r98"]}, "unknown"),
        ({}, "unknown"),
        ({"candidates": "r1"}, "reject"),
    ]
    for args, expected in ref_unknown:
        ctx.add("unknown", "reference.resolve", args, expected_status=expected,
                validator="reject" if expected == "reject" else "unknown_or_error")
    ctx.add("unknown", "actions.run", {"steps": [{"op": "attr.teach", "args": {"id": ids[0], "key": "颜色", "value": "灰色"},
                                                 "requires": [{"id": ids[0], "key": "大小", "value": "大"}]}]},
            expected_status="reject", validator="reject")
    ctx.add("unknown", "actions.run", {"steps": [{"op": "relation.set", "args": {"subject": ids[1], "predicate": "inside"}}]},
            expected_status="reject", validator="reject")

    ctx.add_state("state", "attr.get", {"id": ids[0], "key": "颜色"},
                  expected_result={"id": ids[0], "key": "颜色", "value": ctx.tracked_attrs[ids[0]]["颜色"]})
    ctx.add_state("state", "attr.get", {"id": ids[1], "key": "状态"},
                  expected_result={"id": ids[1], "key": "状态", "value": ctx.tracked_attrs[ids[1]]["状态"]})
    ctx.add_state("state", "relation.query", {"subject": ids[0], "predicate": "inside"},
                  expected_result={"targets": [ctx.tracked_rels[(ids[0], "inside")]]}, validator="set")
    ctx.add_state("state", "reference.resolve", {"candidates": [ids[8]]},
                  expected_result={"id": ids[8]})


def lesson_19(ctx: BuildCtx) -> None:
    """课19：显式风险/物品/位置政策；缺条件未知，多匹配冲突，缺字段先问。"""
    policies = [
        {"id": "p-tang", "when": {"key": "风险", "value": "烫"}, "decision": "停下并求助"},
        {"id": "p-hua", "when": {"key": "风险", "value": "滑"}, "decision": "慢走并扶稳"},
        {"id": "p-safe", "when": {"key": "风险", "value": "安全"}, "decision": "继续"},
        {"id": "p-break", "when": {"key": "物品", "value": "易碎"}, "decision": "轻拿轻放"},
        {"id": "p-edge", "when": {"key": "位置", "value": "桌边"}, "decision": "移到中间"},
        {"id": "p-c1", "when": {"key": "标记", "value": "冲突"}, "decision": "选择甲"},
        {"id": "p-c2", "when": {"key": "标记", "value": "冲突"}, "decision": "选择乙"},
    ]
    objects = []
    risk_ids = []
    for i in range(10):
        oid = f"k{i + 1}"
        risk_ids.append(oid)
        objects.append(mk_obj(oid, f"生活卡{i + 1}", {"风险": ["烫", "滑", "安全"][i % 3]}))
    item_ids = []
    for i in range(4):
        oid = f"k{11 + i}"
        item_ids.append(oid)
        objects.append(mk_obj(oid, f"物品卡{i + 1}", {"物品": "易碎"}))
    pos_ids = []
    for i in range(4):
        oid = f"k{15 + i}"
        pos_ids.append(oid)
        objects.append(mk_obj(oid, f"位置卡{i + 1}", {"位置": "桌边"}))
    conflict_ids = ["k19", "k20"]
    for oid in conflict_ids:
        objects.append(mk_obj(oid, f"冲突卡{oid}", {"标记": "冲突"}))
    incomplete_ids = [f"k{21 + i}" for i in range(4)]
    for oid in incomplete_ids:
        objects.append(mk_obj(oid, f"信息不足卡{oid}", {"颜色": "红色"}))
    ctx.set_world({"objects": objects, "policies": policies})

    def policy_expected(oid: str, key: str, value: str, pid: str, decision: str) -> None:
        ctx.add("basic" if oid in risk_ids else "transfer", "policy.decide", {"id": oid},
                expected_result={"decision": decision, "policy_ids": [pid]}, validator="policy")

    for oid in risk_ids:
        risk = next(o["attributes"]["风险"] for o in objects if o["id"] == oid)
        if risk == "烫":
            policy_expected(oid, "风险", risk, "p-tang", "停下并求助")
        elif risk == "滑":
            policy_expected(oid, "风险", risk, "p-hua", "慢走并扶稳")
        else:
            policy_expected(oid, "风险", risk, "p-safe", "继续")
    missing_fields = [["颜色"], ["形状"], ["大小", "颜色"], ["位置"], ["情绪"], ["许可"]]
    for fields in missing_fields:
        ctx.add("basic", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})
    for oid in item_ids:
        ctx.add("transfer", "policy.decide", {"id": oid},
                expected_result={"decision": "轻拿轻放", "policy_ids": ["p-break"]}, validator="policy")
    for oid in pos_ids:
        ctx.add("transfer", "policy.decide", {"id": oid},
                expected_result={"decision": "移到中间", "policy_ids": ["p-edge"]}, validator="policy")
    for fields in [["物品"], ["形状", "大小"], ["标记"], ["风险"]]:
        ctx.add("transfer", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})
    for oid in incomplete_ids:
        ctx.add("unknown", "policy.decide", {"id": oid}, expected_status="unknown")
    for oid in conflict_ids:
        ctx.add("unknown", "policy.decide", {"id": oid}, expected_status="conflict", validator="conflict")
    for fields in [["容量"], ["温度", "颜色"]]:
        ctx.add("unknown", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})
    for oid in [risk_ids[0], risk_ids[1], item_ids[0], pos_ids[0]]:
        risk = None
        for o in objects:
            if o["id"] == oid:
                attrs = o["attributes"]
                if "风险" in attrs:
                    risk = attrs["风险"]
                elif "物品" in attrs:
                    risk = "item"
                elif "位置" in attrs:
                    risk = "pos"
        if risk == "烫":
            ctx.add_state("state", "policy.decide", {"id": oid},
                          expected_result={"decision": "停下并求助", "policy_ids": ["p-tang"]}, validator="policy")
        elif risk == "滑":
            ctx.add_state("state", "policy.decide", {"id": oid},
                          expected_result={"decision": "慢走并扶稳", "policy_ids": ["p-hua"]}, validator="policy")
        elif risk == "安全":
            ctx.add_state("state", "policy.decide", {"id": oid},
                          expected_result={"decision": "继续", "policy_ids": ["p-safe"]}, validator="policy")
        elif risk == "item":
            ctx.add_state("state", "policy.decide", {"id": oid},
                          expected_result={"decision": "轻拿轻放", "policy_ids": ["p-break"]}, validator="policy")
        else:
            ctx.add_state("state", "policy.decide", {"id": oid},
                          expected_result={"decision": "移到中间", "policy_ids": ["p-edge"]}, validator="policy")


def lesson_20(ctx: BuildCtx) -> None:
    """课20：显式情绪/许可政策与turns轮换；不猜隐藏情绪。"""
    turns = ["甲", "乙", "丙", "丁"]
    policies = [
        {"id": "p-sad", "when": {"key": "情绪", "value": "难过"}, "decision": "安慰并询问"},
        {"id": "p-angry", "when": {"key": "情绪", "value": "生气"}, "decision": "先冷静再表达"},
        {"id": "p-happy", "when": {"key": "情绪", "value": "开心"}, "decision": "一起分享"},
        {"id": "p-ask", "when": {"key": "许可", "value": "未允许"}, "decision": "先请求许可"},
        {"id": "p-share", "when": {"key": "许可", "value": "可以共享"}, "decision": "谢谢并轮流使用"},
        {"id": "p-nervous", "when": {"key": "情绪", "value": "紧张"}, "decision": "深呼吸再说明"},
    ]
    basic_specs = [
        ("t1", "情绪", "难过", "p-sad", "安慰并询问"),
        ("t2", "情绪", "生气", "p-angry", "先冷静再表达"),
        ("t3", "情绪", "开心", "p-happy", "一起分享"),
        ("t4", "许可", "未允许", "p-ask", "先请求许可"),
        ("t5", "许可", "可以共享", "p-share", "谢谢并轮流使用"),
        ("t6", "情绪", "紧张", "p-nervous", "深呼吸再说明"),
        ("t7", "情绪", "难过", "p-sad", "安慰并询问"),
        ("t8", "许可", "未允许", "p-ask", "先请求许可"),
    ]
    transfer_specs = [
        ("t9", "情绪", "紧张", "p-nervous", "深呼吸再说明"),
        ("t10", "情绪", "开心", "p-happy", "一起分享"),
        ("t11", "许可", "可以共享", "p-share", "谢谢并轮流使用"),
        ("t12", "情绪", "生气", "p-angry", "先冷静再表达"),
        ("t13", "许可", "未允许", "p-ask", "先请求许可"),
        ("t14", "情绪", "难过", "p-sad", "安慰并询问"),
    ]
    objects = []
    for oid, key, value, _, _ in basic_specs + transfer_specs:
        objects.append(mk_obj(oid, f"合作卡{oid}", {key: value}))
    unknown_specs = [("t15", {"情绪": "困惑"}), ("t16", {"许可": "未知"}), ("t17", {"颜色": "红色"}), ("t18", {})]
    for oid, attrs in unknown_specs:
        objects.append(mk_obj(oid, f"合作卡{oid}", attrs))
    ctx.set_world({"objects": objects, "policies": policies, "turns": turns})

    current = turns[0]

    def add_turn(qtype: str, count: int) -> None:
        nonlocal current
        for _ in range(count):
            idx = turns.index(current)
            nxt = turns[(idx + 1) % len(turns)]
            if qtype == "state":
                ctx.add_state("state", "turn.next", {"current": current}, expected_result={"next": nxt})
            else:
                ctx.add(qtype, "turn.next", {"current": current}, expected_result={"next": nxt})
            current = nxt

    add_turn("basic", 8)
    for oid, key, value, pid, decision in basic_specs:
        ctx.add("basic", "policy.decide", {"id": oid},
                expected_result={"decision": decision, "policy_ids": [pid]}, validator="policy")
    add_turn("transfer", 6)
    for oid, key, value, pid, decision in transfer_specs:
        ctx.add("transfer", "policy.decide", {"id": oid},
                expected_result={"decision": decision, "policy_ids": [pid]}, validator="policy")
    for value in ["戊", "己", "庚", ""]:
        ctx.add("unknown", "turn.next", {"current": value}, expected_status="reject", validator="reject")
    for oid, attrs in unknown_specs:
        ctx.add("unknown", "policy.decide", {"id": oid}, expected_status="unknown")
    add_turn("state", 2)
    for oid, key, value, pid, decision in [("t3", "情绪", "开心", "p-happy", "一起分享"),
                                           ("t4", "许可", "未允许", "p-ask", "先请求许可")]:
        ctx.add_state("state", "policy.decide", {"id": oid},
                      expected_result={"decision": decision, "policy_ids": [pid]}, validator="policy")


def _compose_expected(shapes: Dict[str, int], colors: List[str]) -> Dict[str, Any]:
    items = []
    ci = 0
    for shape, count in shapes.items():
        for _ in range(count):
            items.append({"shape": shape, "color": colors[ci % len(colors)]})
            ci += 1
    return {"items": items}


def lesson_21(ctx: BuildCtx) -> None:
    """课21：形状数量+颜色轮换的组合约束；多种排列合法，不评审美。"""
    ctx.set_world({"objects": [mk_obj("u1", "创作卡")]})
    shape_pool = ["圆形", "正方形", "三角形", "长方形", "星形"]
    color_pool = ["红色", "蓝色", "黄色", "绿色"]

    def add_compose(qtype: str, idx: int) -> None:
        shapes = {shape_pool[idx % len(shape_pool)]: 1 + (idx % 2),
                  shape_pool[(idx + 1) % len(shape_pool)]: 1}
        if idx % 3 == 0:
            shapes[shape_pool[(idx + 2) % len(shape_pool)]] = 2
        colors = color_pool[:2] if idx % 4 != 0 else color_pool[:3]
        if qtype == "state":
            ctx.add_state("state", "compose", {"shapes": shapes, "colors": colors},
                          expected_result=_compose_expected(shapes, colors), validator="compose")
        else:
            ctx.add(qtype, "compose", {"shapes": shapes, "colors": colors},
                    expected_result=_compose_expected(shapes, colors), validator="compose")

    for i in range(16):
        add_compose("basic", i)
    for i in range(16, 28):
        add_compose("transfer", i)
    unknown_cases = [
        {"colors": ["红色"]},
        {"shapes": {"圆形": 2}},
        {"shapes": {"圆形": 2}, "colors": []},
        {"shapes": {"": 2}, "colors": ["红色"]},
        {"shapes": {"圆形": 2}, "colors": ["红色", ""]},
        {"shapes": {"圆形": -1}, "colors": ["红色"]},
        {"shapes": {"圆形": "2"}, "colors": ["红色"]},
        {"shapes": ["圆形"], "colors": ["红色"]},
    ]
    for args in unknown_cases:
        ctx.add("unknown", "compose", args, expected_status="reject", validator="reject")
    for i in range(28, 32):
        add_compose("state", i)


def lesson_22(ctx: BuildCtx) -> None:
    """课22：证据支持/明确否定/缺证据/正反冲突；新增证据不覆盖相反证据。"""
    ids = [f"s{i}" for i in range(1, 7)]
    objects = [mk_obj(oid, f"证据卡{i + 1}") for i, oid in enumerate(ids)]
    claims = [
        mk_claim("c1", "s1", "颜色", "红色", True),
        mk_claim("c2", "s2", "颜色", "红色", False),
        mk_claim("c3", "s3", "颜色", "蓝色", True),
        mk_claim("c4", "s3", "颜色", "蓝色", False),
        mk_claim("c5", "s5", "颜色", "绿色", True),
        mk_claim("c6", "s5", "状态", "已确认", True, ["c5"]),
        mk_claim("c7", "s6", "形状", "圆形", True),
    ]
    ctx.set_world({"objects": objects, "claims": claims})

    def add_claim(case_id: str, subject: str, predicate: str, value: str, truth: bool,
                  qtype: str = "basic", depends_on: Optional[List[str]] = None) -> None:
        claim = {"id": case_id, "subject": subject, "predicate": predicate, "value": value, "truth": truth}
        if depends_on:
            claim["depends_on"] = depends_on
        ctx.add(qtype, "claim.add", {"claim": claim}, expected_result={"claim_id": case_id})

    def evaluate(qtype: str, subject: str, predicate: str, value: str, expected: Any,
                 status: str = "ok") -> None:
        payload = {"subject": subject, "predicate": predicate, "value": value}
        kwargs = dict(expected_status=status, expected_result={"truth": expected},
                      validator="conflict" if status == "conflict" else "subset")
        if qtype == "state":
            ctx.add_state("state", "claim.evaluate", payload, **kwargs)
        else:
            ctx.add(qtype, "claim.evaluate", payload, **kwargs)

    evaluate("basic", "s1", "颜色", "红色", True)
    evaluate("basic", "s2", "颜色", "红色", False)
    evaluate("basic", "s3", "颜色", "蓝色", "conflict", "conflict")
    evaluate("basic", "s5", "颜色", "绿色", True)
    evaluate("basic", "s6", "形状", "圆形", True)
    evaluate("basic", "s5", "状态", "已确认", True)
    add_claim("n1", "s1", "形状", "圆形", True)
    add_claim("n2", "s2", "形状", "方形", True)
    add_claim("n3", "s4", "颜色", "紫色", True)
    add_claim("n4", "s4", "颜色", "紫色", False)
    add_claim("n5", "s6", "颜色", "橙色", True)
    add_claim("n6", "s3", "颜色", "黄色", True)
    evaluate("basic", "s1", "形状", "圆形", True)
    evaluate("basic", "s2", "形状", "方形", True)
    evaluate("basic", "s4", "颜色", "紫色", "conflict", "conflict")
    evaluate("basic", "s6", "颜色", "橙色", True)

    add_claim("n10", "s1", "颜色", "红色", False, "transfer")
    evaluate("transfer", "s1", "颜色", "红色", "conflict", "conflict")
    add_claim("n7", "s2", "颜色", "蓝色", True, "transfer")
    evaluate("transfer", "s2", "颜色", "蓝色", True)
    add_claim("n8", "s3", "颜色", "黄色", True, "transfer")
    evaluate("transfer", "s3", "颜色", "黄色", True)
    ctx.add("transfer", "claim.retract", {"id": "c5"}, expected_result={"retracted_ids": ["c5", "c6"]},
            validator="retract")
    evaluate("transfer", "s5", "状态", "已确认", None, "unknown")
    evaluate("transfer", "s6", "形状", "圆形", True)
    evaluate("transfer", "s3", "颜色", "蓝色", "conflict", "conflict")
    add_claim("n9", "s4", "大小", "大", True, "transfer")
    evaluate("transfer", "s4", "大小", "大", True)

    ctx.add("unknown", "claim.evaluate", {"subject": "s6", "predicate": "颜色", "value": "红色"},
            expected_status="unknown")
    evaluate("unknown", "s4", "颜色", "紫色", "conflict", "conflict")
    ctx.add("unknown", "claim.retract", {"id": "c-missing"}, expected_status="unknown", validator="unknown_or_error")
    ctx.add("unknown", "claim.evaluate", {}, expected_status="unknown", validator="unknown_or_error")
    ctx.add("unknown", "claim.add", {"claim": {"id": "bad", "subject": "s1", "predicate": "颜色", "truth": True}},
            expected_status="reject", validator="reject")
    ctx.add("unknown", "claim.evaluate", {"subject": "s4", "predicate": "颜色", "value": "橙色"},
            expected_status="unknown")
    ctx.add("unknown", "claim.evaluate", {"subject": "s1", "predicate": "颜色", "value": "蓝色"},
            expected_status="unknown")
    ctx.add("unknown", "claim.retract", {"id": "c5"}, expected_status="ok",
            expected_result={"retracted_ids": []}, validator="retract")

    evaluate("state", "s5", "颜色", "绿色", None, "unknown")
    evaluate("state", "s5", "状态", "已确认", None, "unknown")
    evaluate("state", "s1", "颜色", "红色", "conflict", "conflict")
    evaluate("state", "s2", "颜色", "蓝色", True)


def lesson_23(ctx: BuildCtx) -> None:
    """课23：撤销证据并级联依赖；只问缺项并等待；补教后保存。"""
    ids = [f"v{i}" for i in range(1, 7)]
    objects = []
    attrs = {
        "v1": {"颜色": "红色"}, "v2": {"颜色": "蓝色"}, "v3": {"颜色": "绿色"},
        "v4": {"大小": "大"}, "v5": {"颜色": "黄色"}, "v6": {"形状": "圆形"},
    }
    for i, oid in enumerate(ids):
        objects.append(mk_obj(oid, f"纠错卡{i + 1}", dict(attrs[oid])))
    claims = [
        mk_claim("c1", "v1", "颜色", "红色", True),
        mk_claim("c2", "v2", "颜色", "蓝色", True),
        mk_claim("c3", "v3", "颜色", "绿色", True),
        mk_claim("c4", "v3", "颜色", "绿色", False),
        mk_claim("c5", "v4", "大小", "大", True),
        mk_claim("c6", "v4", "状态", "已确认", True, ["c5"]),
        mk_claim("c7", "v5", "颜色", "黄色", True),
        mk_claim("c8", "v6", "形状", "圆形", True),
    ]
    ctx.set_world({"objects": objects, "claims": claims})

    teach_cases = [
        ("v1", "颜色", "紫色"), ("v2", "颜色", "橙色"), ("v3", "大小", "中"),
        ("v5", "形状", "方形"), ("v6", "大小", "小"), ("v1", "状态", "新"),
    ]
    for oid, key, value in teach_cases:
        ctx.add("basic", "attr.teach", {"id": oid, "key": key, "value": value},
                expected_result={"id": oid, "key": key, "value": value})
    for cid in ["c5", "c7", "c8", "c1", "c2"]:
        ctx.add("basic", "claim.retract", {"id": cid}, expected_result={"retracted_ids": [cid]},
                validator="retract")
    for fields in [["颜色"], ["形状"], ["大小", "颜色"], ["情绪"], ["许可"]]:
        ctx.add("basic", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})

    transfer_teach = [
        ("v2", "状态", "复核"), ("v3", "颜色", "青色"), ("v4", "颜色", "粉色"),
        ("v5", "状态", "已整理"), ("v6", "颜色", "灰色"), ("v1", "大小", "大"),
    ]
    for oid, key, value in transfer_teach:
        ctx.add("transfer", "attr.teach", {"id": oid, "key": key, "value": value},
                expected_result={"id": oid, "key": key, "value": value})
    ctx.add("transfer", "claim.retract", {"id": "c3"}, expected_result={"retracted_ids": ["c3"]},
            validator="retract")
    for fields in [["颜色", "大小"], ["位置"], ["风险"], ["形状", "状态"], ["状态"]]:
        ctx.add("transfer", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})

    ctx.add("unknown", "claim.evaluate", {"subject": "v1", "predicate": "颜色", "value": "红色"},
            expected_status="unknown")
    ctx.add("unknown", "claim.evaluate", {"subject": "v4", "predicate": "状态", "value": "已确认"},
            expected_status="unknown")
    ctx.add("unknown", "claim.evaluate", {"subject": "v6", "predicate": "形状", "value": "圆形"},
            expected_status="unknown")
    ctx.add("unknown", "claim.retract", {"id": "c1"}, expected_status="ok",
            expected_result={"retracted_ids": []}, validator="retract")
    ctx.add("unknown", "claim.retract", {"id": "c99"}, expected_status="unknown", validator="unknown_or_error")
    for fields in [["数量"], ["大小", "位置"]]:
        ctx.add("unknown", "missing.ask", {"fields": fields}, expected_status="unknown", validator="missing",
                extra={"need_fields": fields})
    ctx.add("unknown", "claim.evaluate", {"subject": "v6", "predicate": "颜色", "value": "绿色"},
            expected_status="unknown")

    ctx.add_state("state", "attr.get", {"id": "v1", "key": "颜色"},
                  expected_result={"id": "v1", "key": "颜色", "value": "紫色"})
    ctx.add_state("state", "claim.evaluate", {"subject": "v1", "predicate": "颜色", "value": "红色"},
                  expected_status="unknown")
    ctx.add_state("state", "claim.evaluate", {"subject": "v4", "predicate": "状态", "value": "已确认"},
                  expected_status="unknown")
    ctx.add_state("state", "claim.evaluate", {"subject": "v3", "predicate": "颜色", "value": "绿色"},
                  expected_result={"truth": False})


def _plan_result(step: Dict[str, Any]) -> Dict[str, Any]:
    return _action_result(step)


def lesson_24(ctx: BuildCtx) -> None:
    """课24：换新世界用plan.run组合多步；不新增答案分支，每个子调用单独留链。"""
    ids = [f"z{i}" for i in range(1, 11)]
    objects = []
    attrs = {}
    colors = ["红色", "蓝色", "绿色", "黄色", "紫色"]
    shapes = ["圆形", "三角形", "正方形"]
    categories = ["水果类", "文具类", "玩具类"]
    for i, oid in enumerate(ids):
        attrs[oid] = {"颜色": colors[i % len(colors)], "形状": shapes[i % len(shapes)]}
        objects.append(mk_obj(oid, f"综合卡{i + 1}", dict(attrs[oid]), [categories[i % len(categories)]]))
    relations = [
        mk_rel("rel-1", ids[0], "inside", ids[1]),
        mk_rel("rel-2", ids[2], "next_to", ids[3]),
        mk_rel("rel-3", ids[4], "above", ids[5]),
        mk_rel("rel-4", ids[6], "near", ids[7]),
    ]
    claims = [mk_claim("cc1", ids[0], "颜色", colors[0], True)]
    ctx.set_world({"objects": objects, "relations": relations, "claims": claims})
    for oid in ids:
        ctx.tracked_attrs[oid] = dict(attrs[oid])
    ctx.tracked_rels[("rel-subject", "inside")] = ids[1]

    def add_plan(qtype: str, steps: List[Dict[str, Any]], expected_results: List[Dict[str, Any]],
                 skill: str, expected_status: str = "ok") -> None:
        validator = "plan" if expected_status == "ok" else "reject"
        ctx.add(qtype, "plan.run", {"steps": steps},
                expected_status=expected_status, expected_result={"results": expected_results} if expected_status == "ok" else {},
                validator=validator, skill=skill)

    filter_conditions = [{"key": "颜色", "value": "红色"}, {"key": "形状", "value": "圆形"}]
    matched, unknown_ids = expect_filter(objects, filter_conditions)
    for i in range(4):
        subset = ids[i * 2:(i * 2) + 4]
        m, u = expect_filter(objects, filter_conditions, subset)
        steps = [
            {"op": "filter.all", "args": {"conditions": filter_conditions, "ids": subset}},
            {"op": "count", "args": {"ids": m}},
        ]
        add_plan("basic", steps, [{"ids": m, "unknown_ids": u}, {"count": len(m)}], "筛选后点数")
    for i in range(4):
        oid = ids[i]
        steps = [
            {"op": "attr.get", "args": {"id": oid, "key": "颜色"}},
            {"op": "attr.same", "args": {"left": oid, "right": ids[i + 1], "key": "颜色"}},
            {"op": "sentence.render", "args": {"id": oid, "key": "颜色"}},
        ]
        add_plan("basic", steps, [
            {"id": oid, "key": "颜色", "value": attrs[oid]["颜色"]},
            {"same_value": attrs[oid]["颜色"] == attrs[ids[i + 1]]["颜色"]},
            {"text": _sentence(oid, "颜色", attrs[oid]["颜色"])},
        ], "读取与表达")
    for i in range(4):
        rel = relations[i % len(relations)]
        subject = rel["subject"]
        rel_target = rel["object"]
        steps = [
            {"op": "relation.query", "args": {"subject": subject, "predicate": rel["predicate"]}},
            {"op": "category.members", "args": {"category": categories[i % len(categories)]}},
        ]
        members = sorted([o["id"] for o in objects if categories[i % len(categories)] in (o.get("categories") or [])])
        add_plan("basic", steps, [{"targets": [rel_target]}, {"ids": members}], "关系与类别")
    for i in range(4):
        if i % 2 == 0:
            oid = ids[i // 2]
            key = "备注"
            value = f"计划{i}"
            steps = [
                {"op": "attr.teach", "args": {"id": oid, "key": key, "value": value}},
                {"op": "attr.get", "args": {"id": oid, "key": key}},
            ]
            add_plan("basic", steps, [{"id": oid, "key": key, "value": value},
                                      {"id": oid, "key": key, "value": value}], "先教后查")
            ctx.teach_track(oid, key, value)
        else:
            subject = ids[(i - 1) // 2]
            target = ids[((i - 1) // 2 + 2) % len(ids)]
            steps = [
                {"op": "relation.set", "args": {"subject": subject, "predicate": "inside", "object": target}},
                {"op": "relation.query", "args": {"subject": subject, "predicate": "inside"}},
            ]
            add_plan("basic", steps, [{"subject": subject, "predicate": "inside", "object": target},
                                      {"targets": [target]}], "先设关系后查")
            ctx.rel_track(subject, "inside", target)

    for i in range(6):
        oid = ids[i]
        steps = [
            {"op": "attr.get", "args": {"id": oid, "key": "颜色"}},
            {"op": "filter.all", "args": {"conditions": [{"key": "颜色", "value": colors[i % len(colors)]}]}},
            {"op": "count", "args": {"ids": [o["id"] for o in objects if o["attributes"].get("颜色") == colors[i % len(colors)]]}},
            {"op": "sentence.render", "args": {"id": oid, "key": "形状"}},
        ]
        m, u = expect_filter(objects, [{"key": "颜色", "value": colors[i % len(colors)]}])
        add_plan("transfer", steps, [
            {"id": oid, "key": "颜色", "value": attrs[oid]["颜色"]},
            {"ids": m, "unknown_ids": u},
            {"count": len(m)},
            {"text": _sentence(oid, "形状", attrs[oid]["形状"])},
        ], "四步综合")
    for i in range(6):
        oid = ids[(i + 4) % len(ids)]
        claim_id = f"cc-new-{i}"
        steps = [
            {"op": "attr.teach", "args": {"id": oid, "key": "标记", "value": f"新{i}"}},
            {"op": "claim.add", "args": {"claim": {"id": claim_id, "subject": oid, "predicate": "标记", "value": f"新{i}", "truth": True}}},
            {"op": "claim.evaluate", "args": {"subject": oid, "predicate": "标记", "value": f"新{i}"}},
        ]
        add_plan("transfer", steps, [
            {"id": oid, "key": "标记", "value": f"新{i}"},
            {"claim_id": claim_id},
            {"truth": True},
        ], "补教并核验证据")
        ctx.teach_track(oid, "标记", f"新{i}")

    bad_plans = [
        {},
        {"steps": "not-a-list"},
        {"steps": [{"op": "relation.set", "args": {"subject": ids[0]}}]},
        {"steps": [{"op": "unknown.op", "args": {}}]},
        {"steps": [{"op": "attr.get", "args": {}}]},
        {"steps": [{"op": "count"}]},
        {"steps": [{"op": "filter.all", "args": {}}]},
        {"steps": [{"op": "attr.teach", "args": {"key": "颜色", "value": "灰色"}}]},
    ]
    for args in bad_plans:
        add_plan("unknown", args, [], "非法计划", "reject")

    ctx.add_state("state", "attr.get", {"id": ids[0], "key": "备注"},
                  expected_result={"id": ids[0], "key": "备注", "value": "计划0"})
    ctx.add_state("state", "relation.query", {"subject": ids[0], "predicate": "inside"},
                  expected_result={"targets": [ctx.tracked_rels[(ids[0], "inside")]]}, validator="set")
    ctx.add_state("state", "count", {"ids": ids[:5]}, expected_result={"count": 5})
    m, u = expect_filter(objects, filter_conditions)
    ctx.add_state("state", "filter.all", {"conditions": filter_conditions},
                  expected_result={"ids": m, "unknown_ids": u}, validator="filter")


LESSON_BUILDERS = {
    1: lesson_01, 2: lesson_02, 3: lesson_03, 4: lesson_04, 5: lesson_05, 6: lesson_06,
    7: lesson_07, 8: lesson_08, 9: lesson_09, 10: lesson_10, 11: lesson_11, 12: lesson_12,
    13: lesson_13, 14: lesson_14, 15: lesson_15, 16: lesson_16, 17: lesson_17, 18: lesson_18,
    19: lesson_19, 20: lesson_20, 21: lesson_21, 22: lesson_22, 23: lesson_23, 24: lesson_24,
}


def load_curriculum(path: Path = CURRICULUM_PATH) -> Dict[str, Any]:
    data = read_json(path)
    lessons = data.get("lessons")
    if not isinstance(lessons, list) or len(lessons) != 24:
        raise RuntimeError("curriculum.json 必须包含24课")
    for meta in lessons:
        if int(meta.get("order", 0)) not in LESSON_BUILDERS:
            raise RuntimeError(f"课程 order 无生成器：{meta.get('order')}")
    return data


def build_lesson(meta: Dict[str, Any], phase: str, seed: int) -> BuildCtx:
    ctx = BuildCtx(meta, phase, seed)
    LESSON_BUILDERS[int(meta["order"])](ctx)
    if ctx.phase == "exam":
        counts = Counter(c["type"] for c in ctx.cases)
        expected = {k: v for k, v in EXAM_TYPES.items() if k != "state"}
        if any(counts.get(k, 0) != v for k, v in expected.items()) or len(ctx.state_cases) != EXAM_TYPES["state"]:
            raise RuntimeError(f"{ctx.lesson_id} 题型数量不符：{dict(counts)} state={len(ctx.state_cases)}")
    # 同课同阶段若出现完全相同的(op,args)，不是必然错误，但报告会标出，避免“重复凑题”。
    # turn.next 的current按轮次循环，重复是状态推进而非凑题；其余操作查重只作报告提示。
    keys = [json.dumps([c["op"], c["args"]], ensure_ascii=False, sort_keys=True)
            for c in ctx.cases if c["op"] != "turn.next"]
    dups = [k for k, n in Counter(keys).items() if n > 1]
    if dups:
        ctx.warnings.append(f"同课存在重复请求 {len(dups)} 组（仅提示，不自动改题）")
    return ctx


def generate_course(curriculum: Dict[str, Any], seed: int) -> Dict[str, Any]:
    training: List[Dict[str, Any]] = []
    exam_main: List[Dict[str, Any]] = []
    exam_state: List[Dict[str, Any]] = []
    materials: Dict[str, Any] = {}
    answers: Dict[str, Any] = {}
    warnings: List[Dict[str, Any]] = []
    for meta in curriculum["lessons"]:
        lid = meta["lesson_id"]
        tctx = build_lesson(meta, "training", seed)
        ectx = build_lesson(meta, "exam", seed)
        training.append(prep_case(tctx))
        training.extend(tctx.cases)
        exam_main.append(prep_case(ectx))
        exam_main.extend(ectx.cases)
        exam_state.extend(ectx.state_cases)
        materials[lid] = {
            "training": {
                "world_id": tctx.world_id, "source": tctx.source, "world": tctx.world,
                "tasks": [request_of(c) for c in tctx.cases],
            },
            "exam": {
                "world_id": ectx.world_id, "source": ectx.source, "world": ectx.world,
                "tasks": [request_of(c) for c in ectx.cases + ectx.state_cases],
                "answer_location": "private/exam_answers.json（不发给学生）",
            },
        }
        answers[lid] = {"training": tctx.cases, "exam_main": ectx.cases, "exam_state": ectx.state_cases}
        for ctx in (tctx, ectx):
            for warning in ctx.warnings:
                warnings.append({"lesson_id": lid, "phase": ctx.phase, "message": warning})
    return {
        "training": training, "exam_main": exam_main, "exam_state": exam_state,
        "materials": materials, "answers": answers, "warnings": warnings,
    }


# ---------------------------------------------------------------------------
# 机械判分：expected只在考试执行器内使用；支持契约中格式尚存歧义的少量字段。
# ---------------------------------------------------------------------------

def _result_of(actual: Any) -> Dict[str, Any]:
    if isinstance(actual, dict) and isinstance(actual.get("result"), dict):
        return actual["result"]
    return {}


def _match(expected: Any, actual: Any) -> bool:
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            return False
        for key, value in expected.items():
            if key not in actual or not _match(value, actual.get(key)):
                return False
        return True
    if isinstance(expected, list):
        if not isinstance(actual, list) or len(expected) != len(actual):
            return False
        return all(_match(e, a) for e, a in zip(expected, actual))
    if isinstance(expected, bool):
        return actual is expected
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        return isinstance(actual, (int, float)) and not isinstance(actual, bool) and abs(float(expected) - float(actual)) < 1e-9
    return expected == actual


def _match_set(expected: Dict[str, Any], actual: Dict[str, Any]) -> bool:
    for key, value in expected.items():
        if isinstance(value, list):
            got = actual.get(key)
            if not isinstance(got, list):
                return False
            if sorted(str(x) for x in got) != sorted(str(x) for x in value):
                return False
        elif not _match(value, actual.get(key)):
            return False
    return True


def _changed_keys(value: Any) -> set:
    keys: set = set()
    if isinstance(value, dict):
        keys.update(str(k) for k in value.keys())
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, str):
                keys.add(item)
            elif isinstance(item, dict):
                for key in ("key", "field", "name", "条件", "结果"):
                    if isinstance(item.get(key), str):
                        keys.add(item[key])
                        break
    return keys


def _normalize_pairs(pairs: Any) -> set:
    out = set()
    if isinstance(pairs, list):
        for item in pairs:
            if isinstance(item, (list, tuple)) and len(item) == 2:
                out.add((str(item[0]), str(item[1])))
            elif isinstance(item, dict):
                left = item.get("left") or item.get("subject")
                right = item.get("right") or item.get("object")
                if left is not None and right is not None:
                    out.add((str(left), str(right)))
    return out


def _grade_payload(case: Dict[str, Any], result: Dict[str, Any]) -> Tuple[bool, str]:
    validator = case.get("validator", "subset")
    expected = case.get("expected_result") or {}
    if validator == "set":
        return (_match_set(expected, result), "集合/列表值一致")
    if validator == "filter":
        ok = _match_set({"ids": expected.get("ids", []), "unknown_ids": expected.get("unknown_ids", [])}, result)
        return ok, "ids与unknown_ids分层一致"
    if validator == "pair":
        pairs_ok = _normalize_pairs(result.get("pairs")) == _normalize_pairs(expected.get("pairs"))
        left_ok = sorted(str(x) for x in as_list(result.get("unpaired_left"))) == sorted(str(x) for x in expected.get("unpaired_left", []))
        right_ok = sorted(str(x) for x in as_list(result.get("unpaired_right"))) == sorted(str(x) for x in expected.get("unpaired_right", []))
        return pairs_ok and left_ok and right_ok, "一对一配对与未配对集合一致"
    if validator == "pattern":
        values = result.get("values")
        if not isinstance(values, list):
            return False, "pattern.next 缺少values列表"
        expected_values = expected.get("values", [])
        prefix = (case.get("extra") or {}).get("prefix") or []
        accepted = [expected_values, prefix + expected_values]
        return any(values == item for item in accepted), "续排值一致（允许只返回续排或返回完整序列）"
    if validator == "compose":
        items = result.get("items")
        if not isinstance(items, list):
            return False, "compose缺少items"
        shapes = (case.get("args") or {}).get("shapes") or {}
        colors = (case.get("args") or {}).get("colors") or []
        if not colors:
            return False, "compose颜色轮换为空"
        total = sum(int(v) for v in shapes.values())
        if len(items) != total:
            return False, f"组合数量{len(items)}不等于约束总数{total}"
        got_counts = Counter(str(i.get("shape")) for i in items if isinstance(i, dict))
        if dict(got_counts) != {str(k): int(v) for k, v in shapes.items()}:
            return False, "形状数量不一致"
        for idx, item in enumerate(items):
            if not isinstance(item, dict) or item.get("color") != colors[idx % len(colors)]:
                return False, "颜色没有按给定轮换"
        return True, "数量与颜色轮换约束满足"
    if validator == "observation":
        if bool(result.get("controlled")) != bool(expected.get("controlled")):
            return False, "controlled判断不一致"
        if result.get("causal_claim") is not False:
            return False, "causal_claim必须为false"
        extra = case.get("extra") or {}
        if _changed_keys(result.get("changed_conditions")) != set(extra.get("changed_condition_keys", [])):
            return False, "changed_conditions键集合不一致"
        if _changed_keys(result.get("changed_outcomes")) != set(extra.get("changed_outcome_keys", [])):
            return False, "changed_outcomes键集合不一致"
        return True, "变化条件/结果与controlled一致"
    if validator == "missing":
        need = result.get("need")
        question = result.get("question")
        action = result.get("action")
        if not need or not isinstance(question, str) or not question.strip():
            return False, "missing.ask缺少need或question"
        if action != "wait":
            return False, "missing.ask应等待(action=wait)"
        text = safe_text(need)
        missing_fields = (case.get("extra") or {}).get("need_fields") or []
        if any(str(field) not in text for field in missing_fields):
            return False, "need没有覆盖所问缺项"
        return True, "只问缺项并等待"
    if validator == "policy":
        if not _match({"decision": expected.get("decision")}, result):
            return False, "政策决策不一致"
        got_ids = set(str(x) for x in as_list(result.get("policy_ids")))
        need_ids = set(str(x) for x in as_list(expected.get("policy_ids")))
        return need_ids.issubset(got_ids), "决策与政策来源一致"
    if validator == "retract":
        retracted = set(str(x) for x in as_list(result.get("retracted_ids")))
        need = set(str(x) for x in as_list(expected.get("retracted_ids")))
        return need.issubset(retracted), "撤销范围包含预期证据"
    if validator == "time":
        if result.get("comparison") != expected.get("comparison"):
            return False, "日期先后不一致"
        got = result.get("difference_days")
        if not isinstance(got, (int, float)):
            return False, "difference_days缺失"
        # 契约没有明示差值方向；按绝对值验收，报告仍展示期望的有符号值。
        return abs(float(got)) == abs(float(expected.get("difference_days", 0))), "先后一致；差值方向契约未明示，按绝对值验收"
    if validator == "plan":
        results = result.get("results")
        if not isinstance(results, list):
            return False, "缺少results列表"
        expected_list = expected.get("results") if isinstance(expected, dict) else expected
        if not isinstance(expected_list, list) or len(results) != len(expected_list):
            return False, "子结果数量不一致"
        for exp, got in zip(expected_list, results):
            if isinstance(got, dict) and isinstance(got.get("result"), dict):
                got = got["result"]
            if not _match(exp, got):
                return False, "子结果字段不一致"
        return True, "每个子调用的结果一致"
    return (_match(expected, result), "字段一致")


def grade_case(case: Dict[str, Any], actual: Optional[Dict[str, Any]]) -> Tuple[bool, str, str]:
    """返回(pass, reason, 失败归因提示)。actual只来自学生stdout。"""
    if not isinstance(actual, dict):
        return False, "未收到该任务的学生结果", "no_result"
    status = actual.get("status")
    if status not in STATUSES:
        return False, f"结果status非法：{status}", "bad_status"
    result = _result_of(actual)
    reason = actual.get("reason")
    expected_status = case.get("expected_status", "ok")
    validator = case.get("validator", "subset")
    if expected_status == "reject":
        if status in ("error", "unknown", "conflict"):
            return True, f"按预期拒绝（{status}）", ""
        return False, f"应拒绝却返回{status}", "should_reject"
    if expected_status == "unknown":
        allowed = ("unknown", "error") if validator == "unknown_or_error" else ("unknown",)
        if status not in allowed:
            return False, f"应为unknown却返回{status}", "unknown_as_known"
        if validator == "missing":
            ok, why = _grade_payload(case, result)
            return ok, why, "" if ok else "missing_shape"
        if not reason and not result.get("need"):
            return False, "unknown缺少reason/need来源", "unknown_without_reason"
        return True, "按预期保持unknown", ""
    if expected_status == "conflict":
        if status != "conflict":
            return False, f"应报告conflict却返回{status}", "conflict_not_reported"
        if not reason and not result.get("truth"):
            return False, "conflict缺少reason", "conflict_without_reason"
        return True, "按预期报告证据冲突", ""
    if status != "ok":
        return False, f"应为ok却返回{status}", "status_mismatch"
    ok, why = _grade_payload(case, result)
    return ok, why, "" if ok else "payload_mismatch"


def classify_failure(case: Dict[str, Any], actual: Optional[Dict[str, Any]],
                     prep_ok: bool, chain: List[Dict[str, Any]]) -> Tuple[str, str, str]:
    """基于实际链做最早节点定位，不凭空声称根因已确定。"""
    if not prep_ok:
        return "数据缺失", "该课world.load准备请求失败", "疑似层：世界未成功导入；未证明根因"
    if actual is None:
        return "执行", "学生没有输出该任务结果", "疑似层：进程/批次执行；未证明根因"
    status = actual.get("status") if isinstance(actual, dict) else None
    expected_status = case.get("expected_status", "ok")
    validator = case.get("validator", "subset")
    failure_events = [e for e in chain if e.get("_failed")]
    node = ""
    if failure_events:
        e = failure_events[0]
        node = f"{e.get('event_type')} / {e.get('function') or '-'} / {e.get('description') or ''}"
    else:
        rule_events = [e for e in chain if e.get("rule_id")]
        if rule_events:
            e = rule_events[-1]
            node = f"rule {e.get('rule_id')} / {e.get('function') or '-'}"
        elif chain:
            node = f"{chain[0].get('event_type')} / {chain[0].get('function') or '-'}"
        else:
            node = "没有可用的执行链事件"
    if status == "error":
        return "执行", node, "疑似层：执行报错；未证明根因"
    if expected_status in ("unknown", "conflict") and status == "ok":
        return "规则选择", node, "疑似层：缺证据时仍给出确定答案；未证明根因"
    if expected_status == "ok" and status == "unknown":
        return "规则选择", node, "疑似层：规则未从已给事实得出结论；未证明根因"
    if status == "ok":
        if validator in ("missing", "compose", "observation", "pair", "pattern"):
            return "执行", node, "疑似层：规则输出与契约形状/约束不一致；未证明根因"
        return "执行", node, "疑似层：规则输出值不一致；未证明根因"
    if status in ("unknown", "conflict"):
        return "数据缺失", node, "疑似层：输入/状态缺失导致无法判定；未证明根因"
    return "验收", node, "疑似层：结果形状不符合验收；未证明根因"


# ---------------------------------------------------------------------------
# 学生输出、执行事件与报告数据。
# ---------------------------------------------------------------------------

def parse_result_lines(text: str) -> Tuple[List[Dict[str, Any]], List[str]]:
    rows: List[Dict[str, Any]] = []
    bad: List[str] = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            value = json.loads(line)
        except Exception:
            bad.append(line[:500])
            continue
        if isinstance(value, dict):
            rows.append(value)
        else:
            bad.append(line[:500])
    return rows, bad


def trim_event(event: Dict[str, Any]) -> Dict[str, Any]:
    keys = [
        "event_id", "run_id", "task_id", "timestamp", "event_type", "actor", "status",
        "step_seq", "function", "description", "step_phase", "span_id", "parent_span_id",
        "rule_id", "rule_version", "rule_title", "introduced_lesson", "acquired_at",
        "rule_acquired_at", "rule_reason", "rule_author", "acquisition_method", "reason", "source_anchor",
        "fact_ids", "output", "result", "judgement", "trace_id", "trace_ids", "op", "args",
    ]
    out = {k: event.get(k) for k in keys if k in event}
    # 失败标记只用于内部定位，不写入公开报告字段。
    out["_failed"] = (event.get("status") == "failed" or event.get("step_phase") == "failure"
                      or "error" in str(event.get("event_type", "")).lower())
    return out


def load_events(student_dir: Path) -> Tuple[List[Dict[str, Any]], List[str], List[str]]:
    paths: List[Path] = []
    direct = student_dir / "events.jsonl"
    if direct.exists():
        paths.append(direct)
    else:
        paths.extend(sorted(student_dir.glob("**/events.jsonl")))
    events: List[Dict[str, Any]] = []
    bad_lines: List[str] = []
    used: List[str] = []
    for path in paths:
        used.append(str(path))
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except Exception:
                bad_lines.append(line[:300])
                continue
            if isinstance(value, dict):
                events.append(value)
    return events, bad_lines, used


def index_events(events: List[Dict[str, Any]]) -> Tuple[Dict[str, List[Dict[str, Any]]], Dict[str, List[Dict[str, Any]]]]:
    by_task: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    by_run: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for event in events:
        for key in ("task_id", "batch_task_id", "request_task_id", "case_id"):
            value = event.get(key)
            if isinstance(value, str) and value:
                by_task[value].append(event)
        run_id = event.get("run_id")
        if isinstance(run_id, str) and run_id:
            by_run[run_id].append(event)
    for bucket in (by_task, by_run):
        for key in bucket:
            bucket[key].sort(key=lambda e: (e.get("timestamp") or "", e.get("step_seq") or 0))
    return by_task, by_run


def event_rule_ids(event: Dict[str, Any]) -> List[str]:
    out = []
    for key in ("rule_id", "rule", "ruleId"):
        value = event.get(key)
        if isinstance(value, str) and value:
            out.append(value)
    return out


def collect_facts(chain: List[Dict[str, Any]]) -> List[str]:
    facts: List[str] = []
    for event in chain:
        for key in ("fact_ids", "facts", "evidence_ids"):
            for item in as_list(event.get(key)):
                if isinstance(item, str) and item not in facts:
                    facts.append(item)
    return facts


def build_case_report(case: Dict[str, Any], actual: Optional[Dict[str, Any]], prep_ok: bool,
                      by_task: Dict[str, List[Dict[str, Any]]], by_run: Dict[str, List[Dict[str, Any]]],
                      source: str) -> Dict[str, Any]:
    passed, reason, failure_kind = grade_case(case, actual)
    chain_raw: List[Dict[str, Any]] = []
    if isinstance(actual, dict):
        task_id = str(actual.get("task_id") or case["task_id"])
        chain_raw.extend(by_task.get(task_id, []))
        if not chain_raw:
            run_id = actual.get("run_id")
            if isinstance(run_id, str):
                chain_raw.extend(by_run.get(run_id, []))
    chain = [trim_event(e) for e in chain_raw[:60]]
    layer = ""
    earliest = ""
    certainty = ""
    if not passed:
        layer, earliest, certainty = classify_failure(case, actual, prep_ok, chain)
    rule_ids: List[str] = []
    for event in chain_raw:
        for rid in event_rule_ids(event):
            if rid not in rule_ids:
                rule_ids.append(rid)
    actual_status = actual.get("status") if isinstance(actual, dict) else None
    return {
        "case_id": case["case_id"],
        "task_id": case["task_id"],
        "lesson_id": case["lesson_id"],
        "world_id": case["world_id"],
        "type": case["type"],
        "op": case["op"],
        "skill": case.get("skill", case["op"]),
        "source": source,
        "scored": bool(case.get("scored", True)),
        "stage": case.get("stage", "main"),
        "request": request_of(case),
        "expected_status": case.get("expected_status"),
        "expected": case.get("expected_result"),
        "validator": case.get("validator"),
        "actual": actual,
        "actual_status": actual_status,
        "pass": passed,
        "reason": reason,
        "failure_kind": failure_kind,
        "run_id": actual.get("run_id") if isinstance(actual, dict) else None,
        "trace_ids": actual.get("trace_ids") if isinstance(actual, dict) else None,
        "teacher_invocation_count": actual.get("teacher_invocation_count") if isinstance(actual, dict) else None,
        "model_call_count": actual.get("model_call_count") if isinstance(actual, dict) else None,
        "rule_ids": rule_ids,
        "facts": collect_facts(chain_raw),
        "chain": chain,
        "location": {
            "layer": layer, "earliest_node": earliest, "certainty": certainty,
        } if not passed else None,
        "note": case.get("note", ""),
    }


def build_per_lesson(curriculum: Dict[str, Any], exam_cases: List[Dict[str, Any]],
                     training_cases: List[Dict[str, Any]], prep_status: Dict[str, bool]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for meta in curriculum["lessons"]:
        lid = meta["lesson_id"]
        cases = [c for c in exam_cases if c["lesson_id"] == lid]
        tcases = [c for c in training_cases if c["lesson_id"] == lid]
        by_type: Dict[str, Any] = {}
        for qtype in ("basic", "transfer", "unknown", "state"):
            subset = [c for c in cases if c["type"] == qtype]
            by_type[qtype] = {
                "total": len(subset),
                "passed": sum(1 for c in subset if c["pass"]),
                "required": EXAM_TYPES[qtype],
            }
        total = len(cases)
        passed = sum(1 for c in cases if c["pass"])
        teacher_calls = sum(int(c.get("teacher_invocation_count") or 0) for c in cases)
        model_calls = sum(int(c.get("model_call_count") or 0) for c in cases)
        blocking: List[str] = []
        if not prep_status.get(lid, False):
            blocking.append("world.load准备失败或缺少成功记录")
        if teacher_calls or model_calls:
            blocking.append(f"外部教师/模型调用非0：teacher={teacher_calls}, model={model_calls}")
        for c in cases:
            if c["expected_status"] == "unknown" and c.get("actual_status") == "ok":
                blocking.append(f"{c['task_id']} 把未知当已知")
            if c["expected_status"] == "conflict" and c.get("actual_status") != "conflict":
                blocking.append(f"{c['task_id']} 未报告证据冲突")
        threshold = (
            total == 40 and passed >= 36 and by_type["transfer"]["passed"] >= 10
            and by_type["unknown"]["passed"] == 8 and by_type["state"]["passed"] == 4
            and not blocking and not teacher_calls and not model_calls
        )
        first_failure = next((c for c in cases if not c["pass"]), None)
        executed_ops = sorted(set(c["op"] for c in cases))
        out[lid] = {
            "lesson_id": lid,
            "order": meta["order"],
            "title": meta["title"],
            "domain": meta["domain"],
            "goal": meta["goal"],
            "ops_target": meta["ops"],
            "ops_executed": executed_ops,
            "supported": meta["supported"],
            "unsupported": meta["unsupported"],
            "perception_boundary": meta["perception_boundary"],
            "rules_note": meta["rules_note"],
            "training": {
                "total": len(tcases), "passed": sum(1 for c in tcases if c["pass"]),
                "by_type": {k: {"total": sum(1 for c in tcases if c["type"] == k),
                                "passed": sum(1 for c in tcases if c["type"] == k and c["pass"])}
                            for k in ("basic", "transfer", "unknown")},
            },
            "exam": {
                "total": total, "passed": passed, "by_type": by_type,
                "teacher_invocation_count": teacher_calls, "model_call_count": model_calls,
                "prep_ok": bool(prep_status.get(lid, False)),
                "first_round_threshold_met": bool(threshold),
                "status": "not_run" if not cases else ("meets_first_round_threshold" if threshold else "below_threshold"),
                "blocking": blocking,
            },
            "first_failure": None if first_failure is None else {
                "task_id": first_failure["task_id"], "type": first_failure["type"],
                "op": first_failure["op"], "reason": first_failure["reason"],
                "location": first_failure.get("location"),
            },
            "rules_executed": sorted(set(r for c in cases for r in c.get("rule_ids", []))),
            "rules_catalog_refs": [],
        }
    return out


def build_issues(global_issues: List[Dict[str, Any]], exam_cases: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    issues: List[Dict[str, Any]] = []
    for item in global_issues:
        issues.append(item)
    for c in exam_cases:
        if c["pass"]:
            continue
        loc = c.get("location") or {}
        issues.append({
            "issue_id": f"iss-{len(issues) + 1:04d}",
            "lesson_id": c["lesson_id"],
            "task_id": c["task_id"],
            "type": c["type"],
            "op": c["op"],
            "layer": loc.get("layer", "验收"),
            "earliest_node": loc.get("earliest_node", ""),
            "certainty": loc.get("certainty", "基于执行链定位，未证明根因"),
            "description": c["reason"],
            "expected_status": c["expected_status"],
            "actual_status": c.get("actual_status"),
        })
    return issues


def read_rules_catalog(path: Path = RULES_PATH) -> Dict[str, Any]:
    if not path.exists():
        return {"present": False, "sha256": None, "catalog_version": None, "rules": [], "raw": None}
    raw = read_json(path)
    if isinstance(raw, list):
        rules = raw
        version = None
    elif isinstance(raw, dict):
        rules = raw.get("rules") or raw.get("catalog") or []
        version = raw.get("catalog_version") or raw.get("version")
    else:
        rules, version = [], None
    if not isinstance(rules, list):
        rules = []
    normalized = []
    for item in rules:
        if not isinstance(item, dict):
            continue
        normalized.append({
            "rule_id": item.get("rule_id") or item.get("id"),
            "version": item.get("version"),
            "title": item.get("title"),
            "introduced_lesson": item.get("introduced_lesson") or item.get("lesson_id"),
            "acquired_at": item.get("acquired_at"),
            "reason": item.get("reason"),
            "author": item.get("author"),
            "acquisition_method": item.get("acquisition_method"),
            "source_file": item.get("source_file"),
            "function": item.get("function"),
            "source_anchor": item.get("source_anchor"),
            "raw": item,
        })
    return {
        "present": True, "sha256": sha256_file(path), "catalog_version": version,
        "rules": normalized, "raw": raw,
    }


# ---------------------------------------------------------------------------
# Manifest、快照与构建/运行。运行前先冻结源文件哈希，运行后再核对是否变化。
# ---------------------------------------------------------------------------

def source_files(root: Path) -> List[Path]:
    patterns = [
        "src/main/kotlin/logic/*.kt",
        "build.gradle.kts", "settings.gradle.kts",
        "tools/*.py",
        "experiments/curriculum/batch-v1/curriculum.json",
        "experiments/curriculum/batch-v1/rules.json",
        "experiments/curriculum/batch-v1/IMPLEMENTATION.md",
        "experiments/curriculum/batch-v1/materials/**/*",
        "README.md",
    ]
    files: List[Path] = []
    for pattern in patterns:
        for path in sorted(root.glob(pattern)):
            if path.is_file() and path not in files:
                files.append(path)
    return files


def hash_files(paths: List[Path], root: Path) -> Dict[str, str]:
    return {rel(path, root): sha256_file(path) for path in paths if path.exists()}


def tree_hash(mapping: Dict[str, str]) -> str:
    text = "\n".join(f"{key}:{value}" for key, value in sorted(mapping.items()))
    return sha256_bytes(text.encode("utf-8"))


def copy_snapshot(files: List[Path], root: Path, output: Path) -> Dict[str, str]:
    snap = output / "snapshot"
    mapping: Dict[str, str] = {}
    for path in files:
        relpath = rel(path, root)
        dest = snap / "source" / relpath
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        mapping[relpath] = rel(dest, output)
    return mapping


def copy_artifact_snapshot(root: Path, output: Path) -> Dict[str, Any]:
    install = root / "build" / "install" / "logic-learner"
    jar = install / "lib" / "logic-learner.jar"
    snap = output / "snapshot" / "artifact"
    snap.mkdir(parents=True, exist_ok=True)
    libs: Dict[str, str] = {}
    if jar.exists():
        dest = snap / "logic-learner.jar"
        shutil.copy2(jar, dest)
        for lib in sorted((install / "lib").glob("*.jar")):
            destlib = snap / "lib" / lib.name
            destlib.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(lib, destlib)
            libs[lib.name] = sha256_file(lib)
    return {
        "install_dir": rel(install, root),
        "jar_path": rel(jar, root),
        "jar_sha256": sha256_file(jar) if jar.exists() else None,
        "jar_mtime_ns": jar.stat().st_mtime_ns if jar.exists() else None,
        "jar_snapshot": rel(snap / "logic-learner.jar", output) if jar.exists() else None,
        "lib_sha256": libs,
    }


def collect_input_hashes(output: Path) -> Dict[str, str]:
    mapping: Dict[str, str] = {}
    for folder in ("requests", "private", "materials"):
        base = output / folder
        if base.exists():
            for path in sorted(base.rglob("*")):
                if path.is_file():
                    mapping[rel(path, output)] = sha256_file(path)
    return mapping


def write_manifest(output: Path, data: Dict[str, Any]) -> Path:
    path = output / "manifest.json"
    write_json(path, data)
    return path


def find_java(root: Path) -> Optional[str]:
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    candidates.append(root / ".tools" / "jdk-21" / "bin" / ("java.exe" if os.name == "nt" else "java"))
    found = shutil.which("java")
    if found:
        candidates.append(Path(found))
    for item in candidates:
        if item.exists():
            return str(item)
    return None


def artifact_freshness(root: Path, artifact: Dict[str, Any]) -> Dict[str, Any]:
    jar = Path(artifact.get("jar_path") or "")
    if not jar.is_absolute():
        jar = root / jar
    sources = [p for p in source_files(root) if p.suffix == ".kt"]
    if not jar.exists():
        return {"state": "missing", "stale_sources": []}
    newest = max((p.stat().st_mtime_ns, rel(p, root)) for p in sources) if sources else (0, "")
    stale = [rel(p, root) for p in sources if p.stat().st_mtime_ns > jar.stat().st_mtime_ns + 1_000_000_000]
    state = "stale-suspected" if stale else "fresh"
    return {"state": state, "newest_source": newest[1], "stale_sources": stale}


def run_build(root: Path, output: Path, timeout: int) -> Dict[str, Any]:
    log_dir = output / "build"
    log_dir.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    jdk = root / ".tools" / "jdk-21"
    if jdk.exists():
        env["JAVA_HOME"] = str(jdk)
        env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    if os.name == "nt":
        cmd = ["cmd.exe", "/c", str(root / "gradlew.bat"), "--offline", "test", "installDist"]
    else:
        cmd = [str(root / "gradlew"), "--offline", "test", "installDist"]
    started = now_iso()
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
        code = proc.returncode
        stdout, stderr = proc.stdout, proc.stderr
    except Exception as exc:
        code = -1
        stdout, stderr = "", f"{type(exc).__name__}: {exc}"
    finished = now_iso()
    log = f"# command: {cmd}\n# started: {started}\n# finished: {finished}\n# exit: {code}\n\n## stdout\n{stdout}\n\n## stderr\n{stderr}\n"
    (log_dir / "build.log").write_text(log, encoding="utf-8")
    return {"requested": True, "command": cmd, "started_at": started, "finished_at": finished,
            "exit_code": code, "log": rel(log_dir / "build.log", output)}


def student_command(root: Path, requests: Path, batch_out: Path) -> Tuple[List[str], str]:
    java = find_java(root)
    install = root / "build" / "install" / "logic-learner"
    libs = sorted((install / "lib").glob("*.jar"))
    if java and libs:
        classpath = os.pathsep.join(str(p) for p in libs)
        return ([java, "-Dfile.encoding=UTF-8", "-cp", classpath, "logic.MainKt",
                 "--batch", str(requests), "--batch-out", str(batch_out)], "java-classpath")
    if os.name == "nt":
        bat = install / "bin" / "logic-learner.bat"
        return (["cmd.exe", "/c", str(bat), "--batch", str(requests), "--batch-out", str(batch_out)], "launcher-bat")
    return ([str(install / "bin" / "logic-learner"), "--batch", str(requests), "--batch-out", str(batch_out)], "launcher")


def run_student(root: Path, output: Path, name: str, requests: Path, batch_out: Path,
                manifest_path: Path, timeout: int) -> Dict[str, Any]:
    student_dir = output / "student"
    student_dir.mkdir(parents=True, exist_ok=True)
    batch_out.mkdir(parents=True, exist_ok=True)
    cmd, mode = student_command(root, requests, batch_out)
    env = os.environ.copy()
    env["LOGIC_AUDIT_MANIFEST"] = str(manifest_path)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    started = now_iso()
    try:
        proc = subprocess.run(cmd, cwd=str(root), env=env, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
        code = proc.returncode
        stdout, stderr = proc.stdout, proc.stderr
    except Exception as exc:
        code = -1
        stdout, stderr = "", f"{type(exc).__name__}: {exc}"
    finished = now_iso()
    raw_path = student_dir / f"{name}.results.jsonl"
    raw_path.write_text(stdout, encoding="utf-8")
    (student_dir / f"{name}.stderr.log").write_text(stderr, encoding="utf-8")
    rows, bad = parse_result_lines(stdout)
    return {
        "name": name, "command": cmd, "mode": mode, "started_at": started, "finished_at": finished,
        "exit_code": code, "stdout_path": rel(raw_path, output), "stderr_path": rel(student_dir / f"{name}.stderr.log", output),
        "rows": rows, "bad_lines": bad, "stderr_tail": stderr[-2000:],
    }


# ---------------------------------------------------------------------------
# 报告数据装配与主流程。
# ---------------------------------------------------------------------------

def build_report_data(run_info: Dict[str, Any], manifest: Dict[str, Any], curriculum: Dict[str, Any],
                      rules_catalog: Dict[str, Any], training_reports: List[Dict[str, Any]],
                      exam_reports: List[Dict[str, Any]], per_lesson: Dict[str, Any],
                      issues: List[Dict[str, Any]], warnings: List[Dict[str, Any]],
                      spotcheck: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    exam_scored = [c for c in exam_reports if c["scored"]]
    training_scored = [c for c in training_reports if c["scored"]]
    teacher_calls = sum(int(c.get("teacher_invocation_count") or 0) for c in exam_scored)
    model_calls = sum(int(c.get("model_call_count") or 0) for c in exam_scored)
    passed = sum(1 for c in exam_scored if c["pass"])
    total = len(exam_scored)
    # 规则目录里的引入课若晚于实际使用课，报告标出，不悄悄把时间差当成“一直都有”。
    warnings = list(warnings)
    rule_intro = {}
    for rule in (rules_catalog.get("rules") or []):
        try:
            rule_intro[str(rule.get("rule_id"))] = int(str(rule.get("introduced_lesson") or "").split("-")[-1])
        except Exception:
            continue
    for case in exam_scored:
        try:
            case_order = int(str(case.get("lesson_id") or "").split("-")[-1])
        except Exception:
            continue
        for rid in case.get("rule_ids") or []:
            intro = rule_intro.get(str(rid))
            if intro and intro > case_order:
                warnings.append({
                    "lesson_id": case["lesson_id"], "phase": "rules",
                    "message": f"使用规则 {rid}（规则目录登记引入课 {intro:03d}）早于本课 {case_order:03d}；按事件实际归因，不回溯改写规则时间。",
                })
                break
    warnings = unique_keep(warnings)
    return {
        "schema_version": "1.0",
        "generated_at": now_iso(),
        "run": run_info,
        "scope": {
            "course": "学前1—24课受控符号认知",
            "exam_source": "teacher_exam",
            "exam_total": total,
            "lesson_count": len(curriculum["lessons"]),
            "not_independent_closed_book": True,
            "not_formal_graduation": True,
            "comprehensive_exams": curriculum.get("assessment", {}).get("comprehensive_exams", {
                "status": "not_generated",
                "planned": "两套120项综合卷本轮未生成；不把单课40题当毕业卷。",
            }),
            "perception_limitations": [
                "形状/颜色/情绪等均为教材标签查询，不是真实看图、听说或情绪体验。",
                "新操作由教师编程引入，标teacher_programmed；复用课不虚构新规则。",
                "本轮是DSH第一轮教师考试，不是Codex独立抽查，也不是正式学前毕业。",
            ],
        },
        "summary": {
            "training_total": len(training_scored),
            "training_passed": sum(1 for c in training_scored if c["pass"]),
            "exam_total": total,
            "exam_passed": passed,
            "exam_failed": total - passed,
            "lessons_meeting_first_round_threshold": sum(1 for v in per_lesson.values() if v["exam"]["first_round_threshold_met"]),
            "teacher_invocation_count": teacher_calls,
            "model_call_count": model_calls,
            "blocking_lessons": sorted([lid for lid, v in per_lesson.items() if v["exam"]["blocking"]]),
        },
        "manifest": manifest,
        "curriculum": {
            "schema_version": curriculum.get("schema_version"),
            "course": curriculum.get("course"),
            "course_version": curriculum.get("course_version"),
            "assessment": curriculum.get("assessment"),
            "lessons": curriculum["lessons"],
        },
        "rules": rules_catalog,
        "training": {
            "summary": {
                "total": len(training_scored),
                "passed": sum(1 for c in training_scored if c["pass"]),
            },
            "cases": training_reports,
        },
        "exam": {
            "summary": {
                "total": total, "passed": passed, "failed": total - passed,
                "teacher_invocation_count": teacher_calls, "model_call_count": model_calls,
            },
            "cases": exam_reports,
        },
        "per_lesson": per_lesson,
        "issues": issues,
        "warnings": warnings,
        "spotcheck": spotcheck or {"status": "not_merged", "cases": [], "summary": {"total": 0, "passed": 0}},
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="学前24课批量训练、第一轮教师考试与报告")
    parser.add_argument("--output", required=True, help="新的运行目录；不得覆盖已有成绩")
    parser.add_argument("--seed", type=int, default=20261104)
    parser.add_argument("--build", action="store_true", help="离线执行一次 gradle test installDist")
    parser.add_argument("--project-root", default=str(ROOT))
    parser.add_argument("--session-id", default=None)
    parser.add_argument("--timeout", type=int, default=3600, help="每个学生进程的超时秒数")
    parser.add_argument("--spotcheck", default=None, help="可选：立即合并的Codex抽查JSON")
    args = parser.parse_args(argv)

    root = Path(args.project_root).resolve()
    output = Path(args.output).resolve()
    if output.exists() and any(output.iterdir()):
        print(f"错误：输出目录已存在且非空，不覆盖历史成绩：{output}", file=sys.stderr)
        return 2
    output.mkdir(parents=True, exist_ok=True)
    run_id = "preschool-batch-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{args.seed}"
    started = now_iso()
    build_info: Dict[str, Any] = {"requested": False}
    try:
        if args.build:
            print("[1/7] 离线构建 test installDist ...", flush=True)
            build_info = run_build(root, output, timeout=max(args.timeout, 1800))
            if build_info.get("exit_code") != 0:
                raise RuntimeError(f"Gradle构建失败，见 {output / 'build' / 'build.log'}")
        install_jar = root / "build" / "install" / "logic-learner" / "lib" / "logic-learner.jar"
        if not install_jar.exists():
            raise RuntimeError("缺少installDist产物；请带 --build 或先构建")
        artifact = copy_artifact_snapshot(root, output)
        freshness = artifact_freshness(root, artifact)
        if not args.build and freshness["state"] == "stale-suspected":
            print(f"警告：installDist可能早于源码，已在manifest标记：{freshness['stale_sources'][:5]}", flush=True)

        print("[2/7] 生成24课训练世界、考试世界与请求 ...", flush=True)
        curriculum = load_curriculum(root / "experiments" / "curriculum" / "batch-v1" / "curriculum.json")
        generated = generate_course(curriculum, args.seed)
        write_jsonl(output / "requests" / "training.jsonl", [request_of(c) for c in generated["training"]])
        write_jsonl(output / "requests" / "exam-main.jsonl", [request_of(c) for c in generated["exam_main"]])
        write_jsonl(output / "requests" / "exam-state.jsonl", [request_of(c) for c in generated["exam_state"]])
        write_json(output / "private" / "training_answers.json", generated["answers"])
        write_json(output / "private" / "exam_answers.json", {
            lid: {"exam_main": value["exam_main"], "exam_state": value["exam_state"]}
            for lid, value in generated["answers"].items()
        })
        for lid, material in generated["materials"].items():
            write_json(output / "materials" / lid / "training.json", material["training"])
            write_json(output / "materials" / lid / "exam.json", material["exam"])
        write_json(output / "materials" / "index.json", {
            "generated_at": now_iso(), "seed": args.seed,
            "note": "材料只含world与request；expected/checker在private/，不发给学生。",
            "lessons": {lid: {"training_world_id": m["training"]["world_id"],
                              "exam_world_id": m["exam"]["world_id"]}
                        for lid, m in generated["materials"].items()},
        })
        write_json(output / "generation-warnings.json", generated["warnings"])

        print("[3/7] 冻结源文件/规则/课程哈希并写manifest ...", flush=True)
        files = source_files(root)
        before_hashes = hash_files(files, root)
        source_snapshot = copy_snapshot(files, root, output)
        rules_catalog = read_rules_catalog(root / "experiments" / "curriculum" / "batch-v1" / "rules.json")
        input_hashes = collect_input_hashes(output)
        materials_hash = tree_hash({k: v for k, v in input_hashes.items() if k.startswith("materials/")})
        session_id = args.session_id
        session_source = "cli"
        if not session_id:
            for env_key in ("DSH_SESSION_ID", "DSH_SESSION", "CODEX_THREAD_ID", "DSH_JOB_ID"):
                if os.environ.get(env_key):
                    session_id = os.environ[env_key]
                    session_source = "env:" + env_key
                    break
        if not session_id:
            session_id, session_source = "unavailable", "unavailable"
        manifest: Dict[str, Any] = {
            "manifest_version": "1.0",
            "run_id": run_id,
            "started_at": started,
            "finished_at": None,
            "dsh_session_id": session_id,
            "session_source": session_source,
            "project_root": str(root),
            "output_dir": str(output),
            "seed": args.seed,
            "curriculum": {
                "path": rel(root / "experiments" / "curriculum" / "batch-v1" / "curriculum.json", root),
                "sha256": before_hashes.get("experiments/curriculum/batch-v1/curriculum.json"),
                "lesson_count": len(curriculum["lessons"]),
                "materials_tree_sha256": materials_hash,
            },
            "rules": {
                "path": rel(root / "experiments" / "curriculum" / "batch-v1" / "rules.json", root),
                "sha256": rules_catalog.get("sha256"),
                "present": rules_catalog.get("present"),
                "rule_count": len(rules_catalog.get("rules") or []),
            },
            "source_files": {
                key: {"sha256": value, "snapshot": source_snapshot.get(key)}
                for key, value in before_hashes.items()
            },
            "source_tree_sha256": tree_hash(before_hashes),
            "artifact": artifact,
            "artifact_freshness": freshness,
            "build": build_info,
            "inputs": input_hashes,
            "inputs_tree_sha256": tree_hash(input_hashes),
            "source_changed_during_run": None,
            "unverified": [] if rules_catalog.get("present") else ["rules.json在运行时缺失，规则目录未核验"],
        }
        manifest_path = write_manifest(output, manifest)

        print("[4/7] 训练（先）...", flush=True)
        batch_out = output / "student"
        training_run = run_student(root, output, "training", output / "requests" / "training.jsonl",
                                   batch_out, manifest_path, args.timeout)
        print(f"      training exit={training_run['exit_code']} rows={len(training_run['rows'])} bad={len(training_run['bad_lines'])}", flush=True)

        print("[5/7] 第一轮教师考试：主批 + 重启后的状态/来源批 ...", flush=True)
        exam_main_run = run_student(root, output, "exam-main", output / "requests" / "exam-main.jsonl",
                                    batch_out, manifest_path, args.timeout)
        exam_state_run = run_student(root, output, "exam-state", output / "requests" / "exam-state.jsonl",
                                     batch_out, manifest_path, args.timeout)
        print(f"      exam-main exit={exam_main_run['exit_code']} rows={len(exam_main_run['rows'])}", flush=True)
        print(f"      exam-state exit={exam_state_run['exit_code']} rows={len(exam_state_run['rows'])}", flush=True)

        print("[6/7] 机械判分与报告装配 ...", flush=True)
        events, bad_events, event_paths = load_events(batch_out)
        by_task, by_run = index_events(events)

        def result_map(run: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
            out: Dict[str, Dict[str, Any]] = {}
            for row in run["rows"]:
                tid = row.get("task_id")
                if isinstance(tid, str) and tid:
                    out[tid] = row
            return out

        training_map = result_map(training_run)
        exam_map = result_map(exam_main_run)
        exam_map.update(result_map(exam_state_run))

        training_cases = [c for c in generated["training"] if c["scored"]]
        exam_cases_raw = [c for c in generated["exam_main"] if c["scored"]] + generated["exam_state"]
        prep_status: Dict[str, bool] = {}
        for c in generated["exam_main"]:
            if c["type"] == "prep":
                actual = exam_map.get(c["task_id"])
                prep_status[c["lesson_id"]] = bool(actual and actual.get("status") == "ok" and grade_case(c, actual)[0])

        training_reports = [
            build_case_report(c, training_map.get(c["task_id"]), True, by_task, by_run, "training")
            for c in training_cases
        ]
        exam_reports = [
            build_case_report(c, exam_map.get(c["task_id"]), prep_status.get(c["lesson_id"], False),
                              by_task, by_run, "teacher_exam")
            for c in exam_cases_raw
        ]
        per_lesson = build_per_lesson(curriculum, exam_reports, training_reports, prep_status)
        global_issues: List[Dict[str, Any]] = []
        if not rules_catalog.get("present"):
            global_issues.append({
                "issue_id": "global-rules-missing", "lesson_id": None, "task_id": None,
                "layer": "数据缺失", "earliest_node": "rules.json缺失",
                "certainty": "已确认文件缺失；规则与事件一致性未核验",
                "description": "运行目录没有rules.json，报告不能声称规则目录已验证。",
            })
        if freshness.get("state") == "stale-suspected":
            global_issues.append({
                "issue_id": "global-artifact-stale", "lesson_id": None, "task_id": None,
                "layer": "验收", "earliest_node": "installDist jar早于源码",
                "certainty": "基于文件mtime的提示，未证明二进制内容",
                "description": f"未带--build且疑似旧产物：{freshness.get('stale_sources', [])[:5]}",
            })
        if bad_events:
            global_issues.append({
                "issue_id": "global-events-bad-lines", "lesson_id": None, "task_id": None,
                "layer": "验收", "earliest_node": "events.jsonl存在不可解析行",
                "certainty": "已确认行不可解析；影响范围待查",
                "description": f"{len(bad_events)}行事件无法解析",
            })
        if training_run["bad_lines"]:
            global_issues.append({
                "issue_id": "global-training-bad-stdout", "lesson_id": None, "task_id": None,
                "layer": "验收", "earliest_node": "训练stdout存在非JSON行",
                "certainty": "已确认存在非JSON行", "description": f"{len(training_run['bad_lines'])}行",
            })
        if not events:
            global_issues.append({
                "issue_id": "global-events-missing", "lesson_id": None, "task_id": None,
                "layer": "数据缺失", "earliest_node": "未找到student/events.jsonl",
                "certainty": "已确认事件文件缺失；执行链无法核对",
                "description": "批量学生没有留下可读取的events.jsonl；本次不声称规则执行链已核验。",
            })
        for run_name, run in (("training", training_run), ("exam-main", exam_main_run), ("exam-state", exam_state_run)):
            if run["exit_code"] != 0:
                global_issues.append({
                    "issue_id": f"global-{run_name}-exit",
                    "lesson_id": None, "task_id": None, "layer": "执行",
                    "earliest_node": f"{run_name}进程exit={run['exit_code']}",
                    "certainty": "已确认进程非0退出；未证明根因",
                    "description": (run.get("stderr_tail") or "")[-500:],
                })
        issues = build_issues(global_issues, exam_reports)

        # 运行后再次哈希：源文件在运行中变化必须标记，不能悄悄当作已核验版本。
        after_hashes = hash_files(source_files(root), root)
        changed = sorted(set(k for k in set(before_hashes) | set(after_hashes)
                             if before_hashes.get(k) != after_hashes.get(k)))
        artifact_after = sha256_file(install_jar)
        manifest["finished_at"] = now_iso()
        manifest["source_changed_during_run"] = {
            "changed": changed,
            "changed_count": len(changed),
            "artifact_changed_during_run": artifact_after != artifact.get("jar_sha256"),
        }
        if changed:
            manifest["unverified"].append("源文件在学生运行期间发生变化：" + ", ".join(changed[:10]))
        write_manifest(output, manifest)

        run_info = {
            "run_id": run_id, "started_at": started, "finished_at": now_iso(),
            "seed": args.seed, "output_dir": str(output), "session_id": session_id,
            "session_source": session_source, "command": " ".join(sys.argv),
            "runs": {
                "training": {k: training_run[k] for k in ("command", "mode", "started_at", "finished_at", "exit_code", "stdout_path", "stderr_path", "bad_lines")},
                "exam_main": {k: exam_main_run[k] for k in ("command", "mode", "started_at", "finished_at", "exit_code", "stdout_path", "stderr_path", "bad_lines")},
                "exam_state": {k: exam_state_run[k] for k in ("command", "mode", "started_at", "finished_at", "exit_code", "stdout_path", "stderr_path", "bad_lines")},
            },
            "events": {"count": len(events), "bad_lines": len(bad_events), "paths": event_paths},
        }
        warning_list = list(generated["warnings"])
        if changed:
            warning_list.append({"lesson_id": None, "phase": "run", "message": "源文件在运行中变化，见manifest"})
        spotcheck = None
        if args.spotcheck:
            spotcheck = load_spotcheck(Path(args.spotcheck))
        report_data = build_report_data(run_info, manifest, curriculum, rules_catalog, training_reports,
                                        exam_reports, per_lesson, issues, warning_list, spotcheck)
        write_json(output / "report_data.json", report_data)

        print("[7/7] 渲染离线HTML/JSON报告 ...", flush=True)
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import render_learning_report
        render_learning_report.build_report(output, report_data)
        summary = report_data["summary"]
        print(json.dumps({
            "output": str(output), "run_id": run_id, "seed": args.seed,
            "training": f"{summary['training_passed']}/{summary['training_total']}",
            "exam": f"{summary['exam_passed']}/{summary['exam_total']}",
            "lessons_meeting_first_round_threshold": summary["lessons_meeting_first_round_threshold"],
            "teacher_invocation_count": summary["teacher_invocation_count"],
            "model_call_count": summary["model_call_count"],
            "report": str(output / "report.html"), "json": str(output / "report.json"),
            "not_graduation": True,
        }, ensure_ascii=False), flush=True)
        return 0
    except Exception as exc:
        error = {
            "run_id": run_id, "started_at": started, "failed_at": now_iso(),
            "error_type": type(exc).__name__, "error": str(exc),
            "traceback": traceback.format_exc(),
            "note": "本文件只记录生成器/环境错误；没有从学生结果生成通过成绩。",
        }
        write_json(output / "run_error.json", error)
        print(f"运行失败：{type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
        traceback.print_exc()
        return 1


def load_spotcheck(path: Path) -> Dict[str, Any]:
    """读取Codex抽查JSON；容错但绝不把无法判定的项算成passed。"""
    if not path.exists():
        return {"status": "missing", "cases": [], "summary": {"total": 0, "passed": 0, "unknown": 0},
                "warning": f"抽查文件不存在：{path}"}
    try:
        raw = read_json(path)
    except Exception as exc:
        return {"status": "parse_error", "cases": [], "summary": {"total": 0, "passed": 0, "unknown": 0},
                "warning": f"抽查文件无法解析：{exc}"}
    entries: List[Any] = []
    if isinstance(raw, list):
        entries = raw
    elif isinstance(raw, dict):
        for key in ("cases", "results", "spotchecks", "items"):
            if isinstance(raw.get(key), list):
                entries = raw[key]
                break
        else:
            entries = [raw]
    normalized = []
    for i, item in enumerate(entries):
        if not isinstance(item, dict):
            continue
        status = item.get("status") if isinstance(item.get("status"), str) else None
        passed = item.get("pass")
        if not isinstance(passed, bool):
            passed = item.get("passed") if isinstance(item.get("passed"), bool) else None
        if passed is None and status is not None:
            low = status.lower()
            passed = True if low in ("ok", "pass", "passed", "success") else (False if low in ("fail", "failed", "error") else None)
        normalized.append({
            "case_id": item.get("case_id") or f"spotcheck-{i + 1:03d}",
            "task_id": item.get("task_id"),
            "lesson_id": item.get("lesson_id"),
            "type": item.get("type", "spotcheck"),
            "op": item.get("op"),
            "source": "codex_spotcheck",
            "expected": item.get("expected"),
            "actual": item.get("actual"),
            "status": status,
            "pass": passed,
            "reason": item.get("reason") or item.get("note"),
            "scored": False,
            "raw": item,
        })
    known = [x for x in normalized if isinstance(x.get("pass"), bool)]
    return {
        "status": "merged", "source_file": str(path), "cases": normalized,
        "summary": {"total": len(normalized), "passed": sum(1 for x in known if x["pass"]),
                    "failed": sum(1 for x in known if not x["pass"]),
                    "unknown": len(normalized) - len(known)},
        "warning": None if normalized else "抽查文件没有可识别案例",
    }


if __name__ == "__main__":
    sys.exit(main())
