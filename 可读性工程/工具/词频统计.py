#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""词频统计 —— 用真实 Kotlin 源码决定「先翻译哪些词」。

用法：python words.py <源码目录>
"""
import collections
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HARD = """as break class continue do else false for fun if in interface is null
object package return super this throw true try typealias val var when while""".split()
SOFT = """by catch constructor context delegate dynamic field file finally get
import init param property receiver set setparam value where""".split()
MODIFIER = """abstract actual annotation companion const crossinline data enum expect
external final infix inline inner internal lateinit noinline open operator out
override private protected public reified sealed suspend tailrec vararg""".split()

ALL = HARD + SOFT + MODIFIER

# 批次一已经译掉的词；其余为英文
BATCH1 = {"fun", "val", "var", "if", "else", "when", "for", "while", "in",
          "return", "true", "false", "null"}

WORD_RE = {k: re.compile(r"\b" + re.escape(k) + r"\b") for k in ALL}


def strip_noise(src):
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\])*'", "''", src)
    src = re.sub(r"/\*(?:.|\n)*?\*/", " ", src)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def main(root):
    counts = collections.Counter()
    files = 0
    lines = 0
    lines_with_kw = 0
    lines_with_batch1 = 0

    for dirpath, _, names in os.walk(root):
        for name in names:
            if not name.endswith(".kt"):
                continue
            files += 1
            with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as f:
                text = strip_noise(f.read())
            for line in text.splitlines():
                if not line.strip():
                    continue
                lines += 1
                hit = False
                hit_b1 = False
                for kw, rx in WORD_RE.items():
                    c = len(rx.findall(line))
                    if c:
                        counts[kw] += c
                        hit = True
                        if kw in BATCH1:
                            hit_b1 = True
                lines_with_kw += hit
                lines_with_batch1 += hit_b1

    total = sum(counts.values())
    print(f"文件 {files} 个，非空行 {lines} 行，含关键字的行 {lines_with_kw} 行")
    print(f"关键字出现 {total} 次")
    print(f"批次一 13 个词覆盖：{sum(v for k, v in counts.items() if k in BATCH1)} 次"
          f"（{sum(v for k, v in counts.items() if k in BATCH1) / total:.1%}）")
    print(f"含批次一词的行：{lines_with_batch1} 行（{lines_with_batch1 / lines:.1%}）")
    print()
    print("| 排名 | 关键字 | 次数 | 占比 | 累计覆盖 | 批次一 |")
    print("|---|---|---|---|---|---|")
    cum = 0
    for i, (kw, c) in enumerate(counts.most_common(30), 1):
        cum += c
        mark = "✅" if kw in BATCH1 else ""
        print(f"| {i} | `{kw}` | {c} | {c / total:.1%} | {cum / total:.1%} | {mark} |")


if __name__ == "__main__":
    main(sys.argv[1])
