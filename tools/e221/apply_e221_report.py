#!/usr/bin/env python3
"""Guarded E2.2 report errata. Raw JSONL and Kotlin learner never modified."""
from __future__ import annotations
import argparse
import difflib
import hashlib
from pathlib import Path

# Guarded literal replacements: all exact original anchors must occur once.
FIXES = [
    ("| Arm | Challenge pass | HELDOUT 正确/147 | 备注 |", "| Arm | Challenge pass | HELDOUT 正确/实际评分数 | 备注 |"),
    ("| STATIC | 6 | 119 | 基线 |", "| STATIC | 6 | 119/126 | 基线 |"),
    ("| RANDOM_FIXED_4 | 6 | 119 | 与 STATIC 相同 |", "| RANDOM_FIXED_4 | 6 | 119/126 | 与 STATIC 相同 |"),
    ("| E2_LEGACY | 5 | 103 | 比 STATIC 差 |", "| E2_LEGACY | 5 | 103/105 | 比 STATIC 差 |"),
    ("| E21_STRICT | 6 | 119 | 0 查询，等同 STATIC |", "| E21_STRICT | 6 | 119/126 | 0 查询，等同 STATIC |"),
    ("| E22_CONJECTURE | **5** | **103** | 比 STATIC 差 |", "| E22_CONJECTURE | **5** | **103/105** | 比 STATIC 差 |"),
    ("| E22_CONJECTURE | 11 | **2**（q-2、q-6） |", "| E22_CONJECTURE | 11 | **2**（q-2、q-11） |"),
    ("- `if-nested-3/20261124` 命中 q-6，但漏掉 q-10/q-11；", "- `if-nested-3/20261124` 命中初始反例 q-11，但漏掉初始反例 q-6/q-10；"),
    ("- 命中 q-6 没有转化为整体成功，反而破坏了原程序。", "- 命中初始反例 q-11（当次并非当前程序反例），随后在 q-1 命中当前程序反例；整体学习仍失败，且最终程序比初始程序退化。"),
    ("下一问题：为什么 q-6 命中后反而使 4b1f 从 PASS 变 FAIL？需要审计 E22 在每次 reveal 后的 refit 是否丢失了原程序的有效结构，而不是继续增加猜想数量。", "下一问题：为何 q-11（初始反例）和 q-1（当前反例）的查询链未能阻止 4b1f 从 PASS 变 FAIL？需要审计 E22 在每次 reveal 后的 refit 是否丢失了原程序的有效结构，而不是继续增加猜想数量。"),
]
EXTRA = """
### E2.2.1 事后勘误：反例口径与有评分分母（不改变原始实验）

- **初始程序反例**：考官在全部学生程序冻结后，用每个 arm 的初始程序审计 QUERY 池。E22 的 2 次命中是 `if-nested-3/20261109:q-2` 和 `if-nested-3/20261124:q-11`；不能写成 `q-6`。
- **查询前当前程序反例**：用当次 `QUERY_LABEL_REVEALED.oldPrediction` 与揭示标签逐条比较。E22 的 2 次命中是 `if-nested-3/20261109:q-2` 和 `if-nested-3/20261124:q-1`；`q-11` 揭示时当前程序预测正确。
- **确认性证据触发结构变化**：E22 共有 5 次（E2_LEGACY 3 次）；属于待检验的更新策略现象，不能直接认定每次改变都是 Bug。
- **HELDOUT 统计分母**：只汇总 `PASS_ON_CHALLENGE` 的评分任务；STATIC、RANDOM、STRICT 为 119/126，E2、E22 为 103/105。未过 Challenge 的 HELDOUT 是 `null`，不是 0。
- **共同通过任务配对**：STATIC 与 E22 共同通过的 5 个 task-seeds，各自都是 103/105；E22 另外失去一个初始能通过的任务。
- **科研结论不变**：一致猜想与反例命中机制有限成立；总体学习收益 `NO_BENEFIT` 且有退化。`formal_runs=0`、`independent_closed_book=NOT_RUN`、`fullRegression=NOT_RUN`。
- 本节为 E2.2.1 来源明确的**事后勘误**，不是重新实验或追加闭卷证据。统计来源：`tools/audit_e221_corrections.py` 输出及 E2.2 原始 JSONL。原始 JSONL、冻结程序、代码和学生标签保持不变。
"""

def corrected(original: str)->str:
    result=original
    for old,new in FIXES:
        if result.count(old)!=1:raise AssertionError(f"Guarded anchor count {result.count(old)} for {old!r}; stale or already patched")
        result=result.replace(old,new)
    anchor='## 5. Gate'
    if result.count(anchor)!=1:raise AssertionError('Missing Gate anchor')
    result=result.replace(anchor,EXTRA+'\n'+anchor)
    return result

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('result_md',type=Path)
    ap.add_argument('--apply',action='store_true',help='write only RESULT.md; no evidence files')
    ap.add_argument('--diff-output',type=Path)
    args=ap.parse_args()
    source=args.result_md.read_text(encoding='utf-8-sig')
    try:
        target=corrected(source)
    except AssertionError as e:
        print('E221 ANCHOR GUARD FAIL:',e)
        return 2
    diff=''.join(difflib.unified_diff(source.splitlines(keepends=True),target.splitlines(keepends=True),
                                     fromfile='a/RESULT.md',tofile='b/RESULT.md'))
    if args.diff_output:
        args.diff_output.parent.mkdir(parents=True,exist_ok=True)
        args.diff_output.write_text(diff,encoding='utf-8')
    if args.apply:
        args.result_md.write_text(target,encoding='utf-8')
        print('E221 REPORT PATCH APPLIED (RESULT.md only; JSONL untouched)')
    else:print('E221 REPORT PATCH CHECK PASS (dry-run, no files changed)')
    print('oldSha256:',hashlib.sha256(source.encode()).hexdigest())
    print('newSha256:',hashlib.sha256(target.encode()).hexdigest())
    return 0

if __name__=='__main__':raise SystemExit(main())
