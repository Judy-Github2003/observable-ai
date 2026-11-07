#!/usr/bin/env python3
"""Strict, deterministic, no-dependency patcher for current MRX2BSpace.kt.

Only patches the R3.1 QUOTA branch. Retains the LEGACY branch untouched and
R31_LEXICOGRAPHIC as the default. Does not run any experiment or access truth.
"""
import argparse
from pathlib import Path
import difflib
import sys

TARGET = Path('src/main/kotlin/logic/e3/pack02/MRX2BSpace.kt')
OLD_CTOR = '''    private val r31IfLiteralLimit: Int = 12,
) {'''
NEW_CTOR = '''    private val r31IfLiteralLimit: Int = 12,
    private val r32IfPolicy: MRX2BR32IfPolicy = MRX2BR32IfPolicy.R31_LEXICOGRAPHIC,
) {'''
OLD_BODY = '''            val simple = literals.take(r31IfLiteralLimit)
            // Condition is innermost: rotate it over the entire visible literal
            // space before exhausting the reserved If quota.
            for (a in simple) for (b in simple) for (c in simple)
                admit(Expr.If(c, a, b), MRX2BR31Family.IF)
            for (i in terms.indices) for (j in i until terms.size)
                admit(Expr.Or(terms[i], terms[j]), MRX2BR31Family.OR)'''
NEW_BODY = '''            val simple = literals.take(r31IfLiteralLimit)
            when (r32IfPolicy) {
                MRX2BR32IfPolicy.R31_LEXICOGRAPHIC, MRX2BR32IfPolicy.ROTATING_DIRECT -> {
                    for (t in MRX2BR32Enumeration.direct(simple.size, r32IfPolicy)) {
                        admit(Expr.If(simple[t.condition], simple[t.whenTrue], simple[t.whenFalse]),
                            MRX2BR31Family.IF)
                    }
                }
                MRX2BR32IfPolicy.ROTATING_WITH_NESTED -> {
                    // Same 5,720 IF-family admission ceiling, replacing its second
                    // half with a restricted double-nested grammar. This is a
                    // budget-allocation intervention, NOT a pure grammar-only test.
                    val half = r31Quotas.ifTerms / 2
                    for (t in MRX2BR32Enumeration.direct(simple.size,
                        MRX2BR32IfPolicy.ROTATING_DIRECT).take(half)) {
                        admit(Expr.If(simple[t.condition], simple[t.whenTrue], simple[t.whenFalse]),
                            MRX2BR31Family.IF)
                    }
                    for (t in MRX2BR32Enumeration.nested(simple.size)) {
                        val nested = Expr.If(
                            simple[t.outerCondition],
                            Expr.If(simple[t.trueCondition],
                                simple[t.trueWhenTrue], simple[t.trueWhenFalse]),
                            Expr.If(simple[t.falseCondition],
                                simple[t.falseWhenTrue], simple[t.falseWhenFalse]),
                        )
                        admit(nested, MRX2BR31Family.IF)
                    }
                }
            }
            for (i in terms.indices) for (j in i until terms.size)
                admit(Expr.Or(terms[i], terms[j]), MRX2BR31Family.OR)'''

def transform(s):
    if 'r32IfPolicy: MRX2BR32IfPolicy' in s:
        raise RuntimeError('ALREADY_APPLIED: refuse duplicate patch')
    for old, name in [(OLD_CTOR, 'constructor'), (OLD_BODY, 'quota enumeration')]:
        if s.count(old) != 1:
            raise RuntimeError(f'ANCHOR_MISMATCH_{name}: expected exactly one, found {s.count(old)}')
    return s.replace(OLD_CTOR, NEW_CTOR).replace(OLD_BODY, NEW_BODY)

def main():
    ap = argparse.ArgumentParser()
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--diff', action='store_true')
    a=ap.parse_args()
    if not TARGET.is_file():
        raise SystemExit('MISSING_TARGET: run from repository root')
    raw=TARGET.read_bytes()
    had_bom=raw.startswith(b'\xef\xbb\xbf')
    had_crlf=b'\r\n' in raw
    old=raw.decode('utf-8-sig').replace('\r\n','\n')
    try: new=transform(old)
    except RuntimeError as e: raise SystemExit(str(e))
    if had_crlf: new=new.replace('\n','\r\n')
    if a.diff:
        print(''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),
            fromfile='a/'+str(TARGET),tofile='b/'+str(TARGET))))
    if a.apply:
        TARGET.write_bytes((b'\xef\xbb\xbf' if had_bom else b'') + new.encode('utf-8'))
        print('APPLIED',TARGET)
    else:
        print('PATCHABLE',TARGET,file=sys.stderr)

if __name__=='__main__':main()
