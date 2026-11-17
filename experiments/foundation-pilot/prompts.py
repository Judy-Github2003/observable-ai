#!/usr/bin/env python3
"""Prompt rendering and answer parsing for OA-NEXT-01 FL-PILOT-01.

The tested model only sees public study/question content. Sealed answers are
never passed to this module; scoring is a separate step.
"""
import json
import re

SYSTEM_PROMPT = (
    "你是 Observable AI 四领域预实验中的受测推理器。"
    "只输出题目要求的最终答案，不要解释，不要输出推理过程，不要使用工具。"
)

DOMAIN_ANSWER_RULE = {
    "semantics": "只输出 TRUE、FALSE 或 UNKNOWN（大写英文），不要输出其他文字。",
    "logic": "只输出 TRUE、FALSE 或 UNKNOWN（大写英文），不要输出其他文字。",
    "mathematics": "只输出一个整数，不要输出单位、公式或解释。",
    "code_behavior": "只输出一个 JSON 整数数组，例如 [1,2,3]，不要输出解释。",
}


def question_text(query):
    domain = query["domain"]
    q = query["query"]
    if domain == "semantics":
        return f"实体 {q['entity']} 是否属于类别 {q['class']}？"
    if domain == "logic":
        return f"关系 R({q['left']}, {q['right']}) 是否成立？"
    if domain == "mathematics":
        return f"f({q['x']}) 的值是多少？"
    if domain == "code_behavior":
        return f"输入 {json.dumps(q['input'], ensure_ascii=False)} 的输出是什么？"
    raise ValueError(f"unknown domain: {domain}")


def render_messages(study, query, arm):
    if arm not in ("A", "B"):
        raise ValueError("model arms are A and B only")
    lines = [
        f"领域：{study['domain']}",
        f"任务说明：{study['instruction']}",
    ]
    if arm == "B":
        lines.append("训练观察（仅这些事实，不能假设其他事实）：")
        for observation in study["observations"]:
            lines.append(json.dumps(observation, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    lines.append("")
    lines.append(f"问题：{question_text(query)}")
    lines.append(DOMAIN_ANSWER_RULE[study["domain"]])
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "\n".join(lines)},
    ]


def parse_answer(domain, content):
    text = (content or "").strip()
    if not text:
        return None
    if domain in ("semantics", "logic"):
        match = re.search(r"\b(TRUE|FALSE|UNKNOWN)\b", text.upper())
        return match.group(1) if match else None
    if domain == "mathematics":
        match = re.search(r"-?\d+", text)
        return int(match.group(0)) if match else None
    if domain == "code_behavior":
        match = re.search(r"\[[^\]]*\]", text, re.S)
        if not match:
            return None
        try:
            value = json.loads(match.group(0))
        except ValueError:
            return None
        if isinstance(value, list) and all(isinstance(item, int) for item in value):
            return value
        return None
    raise ValueError(f"unknown domain: {domain}")
