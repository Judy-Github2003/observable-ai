#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ktzh 0.1 —— 中文 Kotlin 方言转译器

设计要点：
1. 关键字与全角标点只在「代码态」生效，字符串、字符、注释内部一律原样保留。
2. 全角标点用显式白名单，不用 NFKC（NFKC 会把 ①→1、㎏→kg、ﬁ→fi，范围不可控）。
3. 输出与源文件逐字符建立偏移映射，编译器报错可回映射到 .ktzh 原始行列。
4. 保留字出现在标识符位置时主动报错，而不是产出一份 `val val` 再让编译器去骂人。
"""
import argparse
import bisect
import json
import os
import re
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

# ---------------------------------------------------------------- 词表

# 批次一：只收「前缀位 / 中缀位」的词，不需要句尾标记词
KEYWORDS = {
    "函数": "fun",
    "变量": "var",
    "值": "val",
    "遍历": "for",
    "只要": "while",
    "当": "when",
    "如果": "if",
    "否则": "else",
    "返回": "return",
    "在": "in",
    "真": "true",
    "假": "false",
    "空": "null",
}

# 全角 / 中文标点 → ASCII。仅在代码态生效。
PUNCT = {
    "（": "(", "）": ")",
    "，": ",", "、": ",",
    "：": ":",
    "；": ";", "。": ";",
    "？": "?",
    "！": "!",
    "＝": "=", "＋": "+", "－": "-", "＊": "*", "／": "/", "％": "%",
    "＜": "<", "＞": ">", "＆": "&", "｜": "|", "＠": "@", "＃": "#",
    "～": "~", "　": " ",
}

# 出现在这些词后面 = 标识符位置，此处不得出现保留字
DECL_PREFIX = {"val", "var", "fun", "class", "object", "interface", "package", "import", "typealias"}
# 出现在这些字符后面 = 成员访问位置，同样不得出现保留字
MEMBER_ACCESS = {".", "@"}

_KEYWORDS_BY_LEN = sorted(KEYWORDS.items(), key=lambda kv: -len(kv[0]))


class TranspileError(Exception):
    def __init__(self, message, line, col):
        super().__init__(message)
        self.message = message
        self.line = line
        self.col = col


def _line_starts(text):
    starts = [0]
    for i, ch in enumerate(text):
        if ch == "\n":
            starts.append(i + 1)
    return starts


def _pos_of(offset, starts):
    line = bisect.bisect_right(starts, offset) - 1
    return line + 1, offset - starts[line] + 1


def transpile(source):
    """返回 (输出文本, 输出偏移->源偏移映射表)"""
    out = []
    out_src = []
    mode = ["code"]          # 状态栈：code / string / raw / char / line_comment / block
    block_depth = 0
    template_depth = 0
    i = 0
    n = len(source)
    last_word = ""           # 最近一个输出出来的「词」，用于识别标识符位置
    last_char = ""

    def emit(text, src_off):
        for ch in text:
            out.append(ch)
            out_src.append(src_off)

    def fail(msg, off):
        line, col = _pos_of(off, _line_starts(source))
        raise TranspileError(msg, line, col)

    while i < n:
        ch = source[i]
        top = mode[-1]

        if top == "line_comment":
            emit(ch, i)
            if ch == "\n":
                mode.pop()
            i += 1
            continue

        if top == "block":
            if source.startswith("/*", i):
                block_depth += 1
                emit("/*", i)
                i += 2
                continue
            if source.startswith("*/", i):
                block_depth -= 1
                emit("*/", i)
                i += 2
                if block_depth == 0:
                    mode.pop()
                continue
            emit(ch, i)
            i += 1
            continue

        if top == "string":
            if ch == "\\" and i + 1 < n:
                emit(source[i:i + 2], i)
                i += 2
                continue
            if ch == '"':
                emit(ch, i)
                mode.pop()
                i += 1
                continue
            if source.startswith("${", i):
                emit("${", i)
                mode.append("code")
                template_depth = 1
                i += 2
                continue
            emit(ch, i)
            i += 1
            continue

        if top == "raw":
            if source.startswith('"""', i):
                emit('"""', i)
                mode.pop()
                i += 3
                continue
            if source.startswith("${", i):
                emit("${", i)
                mode.append("code")
                template_depth = 1
                i += 2
                continue
            emit(ch, i)
            i += 1
            continue

        if top == "char":
            if ch == "\\" and i + 1 < n:
                emit(source[i:i + 2], i)
                i += 2
                continue
            emit(ch, i)
            if ch == "'":
                mode.pop()
            i += 1
            continue

        # ---------------- code 态 ----------------
        if source.startswith("//", i):
            emit("//", i)
            mode.append("line_comment")
            i += 2
            continue
        if source.startswith("/*", i):
            emit("/*", i)
            mode.append("block")
            block_depth = 1
            i += 2
            continue
        if source.startswith('"""', i):
            emit('"""', i)
            mode.append("raw")
            i += 3
            continue
        if ch == '"':
            emit(ch, i)
            mode.append("string")
            i += 1
            continue
        if ch == "'":
            emit(ch, i)
            mode.append("char")
            i += 1
            continue

        # 字符串模板 ${...} 的花括号配平
        if template_depth:
            if ch == "{":
                template_depth += 1
            elif ch == "}":
                template_depth -= 1
                if template_depth == 0:
                    emit(ch, i)
                    mode.pop()
                    i += 1
                    continue

        # 中文关键字（最长优先）
        matched = None
        for word, kw in _KEYWORDS_BY_LEN:
            if source.startswith(word, i):
                matched = (word, kw)
                break
        if matched:
            word, kw = matched
            if last_word in DECL_PREFIX or last_char in MEMBER_ACCESS:
                fail(f"『{word}』在批次一里是保留字，不能用作标识符；请换个名字（例如 结果、当前值）。", i)
            emit(kw, i)
            last_word = kw
            last_char = kw[-1]
            i += len(word)
            continue

        # 全角标点
        if ch in PUNCT:
            emit(PUNCT[ch], i)
            last_char = PUNCT[ch]
            i += 1
            continue

        emit(ch, i)
        if ch.isalnum() or ch == "_" or ord(ch) > 0x2E80:
            last_word += ch
        elif ch not in " \t\r\n":
            last_word = ""
        if ch not in " \t\r\n":
            last_char = ch
        i += 1

    return "".join(out), out_src


def build(src_path, out_path):
    with open(src_path, encoding="utf-8") as f:
        source = f.read()
    text, out_src = transpile(source)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    with open(out_path + ".map.json", "w", encoding="utf-8") as f:
        json.dump({"source": os.path.abspath(src_path), "map": out_src}, f)
    return text, out_src


def remap(out_path, line, col):
    with open(out_path + ".map.json", encoding="utf-8") as f:
        data = json.load(f)
    text = open(out_path, encoding="utf-8").read()
    out_starts = _line_starts(text)
    out_src = data["map"]
    out_off = out_starts[line - 1] + (col - 1)
    out_off = min(out_off, len(out_src) - 1)
    src_off = out_src[out_off]
    with open(data["source"], encoding="utf-8") as f:
        src_starts = _line_starts(f.read())
    return _pos_of(src_off, src_starts)


ERROR_RE = re.compile(r"^(.*?):(\d+):(\d+): (error|warning): (.*)$")


def check(src_path, kotlinc, java_home):
    out_dir = os.path.join(tempfile.gettempdir(), "ktzh-build")
    out_path = os.path.join(out_dir, os.path.basename(src_path).replace(".ktzh", ".kt"))
    build(src_path, out_path)
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
    proc = subprocess.run(
        [kotlinc, out_path, "-d", os.path.join(out_dir, "out")],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env,
    )
    raw = (proc.stdout or "") + (proc.stderr or "")
    remapped = []
    for raw_line in raw.splitlines():
        m = ERROR_RE.match(raw_line)
        if m:
            _, ln, cl, kind, msg = m.groups()
            sl, sc = remap(out_path, int(ln), int(cl))
            remapped.append(f"{src_path}:{sl}:{sc}: {kind}: {msg}")
        elif raw_line.strip():
            remapped.append("    " + raw_line)
    print("\n".join(remapped) if remapped else "编译通过，无输出。")
    return proc.returncode


def main():
    ap = argparse.ArgumentParser(prog="ktzh", description="中文 Kotlin 方言转译器")
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="转译 .ktzh 为 .kt")
    b.add_argument("source")
    b.add_argument("-o", "--output", required=True)

    c = sub.add_parser("check", help="转译并用 kotlinc 编译，报错回映射到源文件")
    c.add_argument("source")
    c.add_argument("--kotlinc", required=True)
    c.add_argument("--java-home", default=None)

    args = ap.parse_args()
    if args.cmd == "build":
        text, _ = build(args.source, args.output)
        print(f"已生成 {args.output}（{len(text)} 字符）")
        return 0
    return check(args.source, args.kotlinc, args.java_home)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except TranspileError as e:
        print(f"{e.line}:{e.col}: 转译错误: {e.message}", file=sys.stderr)
        sys.exit(2)
