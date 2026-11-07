#!/usr/bin/env python3
"""Guarded, source-level patch against the real MRX2BSpace.kt.
Use --check to preview, --apply to write; refuses changed/unrecognized code.
Runs from the repository root. Uses stdlib only. No target truth/hidden labels.
"""
from __future__ import annotations
import argparse
import difflib
from pathlib import Path

TARGET=Path('src/main/kotlin/logic/e3/pack02/MRX2BSpace.kt')


def replace_once(text: str, old: str, new: str, name: str) -> str:
    n=text.count(old)
    if n!=1:
        raise RuntimeError(f'{name}: expected 1 exact anchor, got {n}; STOP and review upstream source')
    return text.replace(old,new,1)


def patch(src: str) -> str:
    if 'MRX2BR31Schedule' in src:
        raise RuntimeError('R3.1 appears already applied; refuse double patch')
    src=replace_once(src,
        '    val pairGains = ArrayList<MRX2BPairGain>()\n',
        '    val pairGains = ArrayList<MRX2BPairGain>()\n    val r31 = MRX2BR31Counters()\n',
        'add public family-level instrumentation')
    src=replace_once(src,
        '    private val consistentLimit: Int = 2_000,\n) {',
        '    private val consistentLimit: Int = 2_000,\n'
        '    private val r31Schedule: MRX2BR31Schedule = MRX2BR31Schedule.LEGACY,\n'
        '    private val r31Quotas: MRX2BR31Quotas = MRX2BR31Quotas(),\n'
        '    private val r31IfLiteralLimit: Int = 12,\n) {',
        'add optional experiment parameters (legacy defaults)')
    src=replace_once(src,
        '    private val labels = training.map { it.label }\n',
        '    private val labels = training.map { it.label }\n'
        '    private var r31InterpreterCalls: Long = 0\n',
        'instrument actual interpreter evaluation')
    src=replace_once(src,
        '        val started = System.nanoTime()\n        val trace = MRX2BTrace()\n',
        '        if (r31Schedule == MRX2BR31Schedule.QUOTA_IF_FIRST) {\n'
        '            require(r31IfLiteralLimit in 1..(atomLimit * 2)) { "invalid R3.1 If literal limit" }\n'
        '            require(r31Quotas.total == maxCandidates) { "R3.1 family quotas must sum to maxCandidates" }\n'
        '        }\n'
        '        r31InterpreterCalls = 0\n'
        '        val started = System.nanoTime()\n        val trace = MRX2BTrace()\n',
        'check budget contract and reset call counter')
    start=src.find('        val candidates = ArrayList<StudyCandidate>()\n')
    end=src.find('        val consistent = ArrayList<StudyCandidate>()\n',start)
    if start<0 or end<0:
        raise RuntimeError('MRX2BSpace generation block not found')
    old=src[start:end]
    MUST_HAVE=[
        'fun admit(body: Expr)', 'if (candidates.size >= maxCandidates) return',
        'if (!seen.add(canonical)) return', 'terms.forEach { admit(it) }',
        'val simple = literals.take(12)', 'for (c in simple) for (a in simple) for (b in simple) admit(Expr.If(c, a, b))',
    ]
    for anchor in MUST_HAVE:
        if anchor not in old: raise RuntimeError(f'Unrecognized original generation block: {anchor}')
    new='''        val candidates = ArrayList<StudyCandidate>()
        val seen = HashSet<String>()
        val r31 = trace.r31

        /** Instrument all enumerated attempts, not only accepted candidates.
         * LEGACY keeps previous admission order and outcomes unchanged.
         */
        fun admit(body: Expr, family: MRX2BR31Family) {
            val metrics = r31.forFamily(family)
            metrics.attempted++
            if (candidates.size >= maxCandidates) {
                metrics.globalCapRejected++
                return
            }
            if (r31Schedule == MRX2BR31Schedule.QUOTA_IF_FIRST &&
                metrics.admitted >= r31Quotas.limit(family)) {
                metrics.familyQuotaRejected++
                return
            }
            if (countNodes(body) > 40) { metrics.nodeRejected++; return }
            val canonical = try { CanonicalJson.expr(body) }
                catch (_: Exception) { metrics.canonicalRejected++; return }
            if (!seen.add(canonical)) { metrics.duplicateRejected++; return }
            val definition = CapabilityDefinition("__mrx2b", 1, inputTypes, CapType.Bool3, body)
            if (!checker.check(definition).ok) { metrics.typeRejected++; return }
            val candidate = StudyCandidate(
                body, canonical, countNodes(body), exprDepth(body), collectDependencies(body),
                estimatedSteps(body), countNodes(body) - collectDependencies(body).map { it.id }.distinct().size,
            )
            candidates += candidate
            metrics.admitted++
            trace.record(MRX2BStage.GENERATED, candidate)
        }

        // The same ranked atoms, literals, two-way AND terms and OR templates are
        // used in both arms. Only enumeration order and bounded If visibility differ.
        if (r31Schedule == MRX2BR31Schedule.LEGACY) {
            terms.forEach { admit(it, if (it is Expr.And) MRX2BR31Family.AND else MRX2BR31Family.LITERAL) }
            for (i in terms.indices) for (j in i until terms.size)
                admit(Expr.Or(terms[i], terms[j]), MRX2BR31Family.OR)
            // Preserve original exact order and first-12 visibility.
            val simple = literals.take(12)
            for (c in simple) for (a in simple) for (b in simple)
                admit(Expr.If(c, a, b), MRX2BR31Family.IF)
        } else {
            terms.forEach { admit(it, if (it is Expr.And) MRX2BR31Family.AND else MRX2BR31Family.LITERAL) }
            val simple = literals.take(r31IfLiteralLimit)
            // Condition is innermost: rotate it over the entire visible literal
            // space before exhausting the reserved If quota.
            for (a in simple) for (b in simple) for (c in simple)
                admit(Expr.If(c, a, b), MRX2BR31Family.IF)
            for (i in terms.indices) for (j in i until terms.size)
                admit(Expr.Or(terms[i], terms[j]), MRX2BR31Family.OR)
        }

'''
    src=src[:start]+new+src[end:]
    src=replace_once(src,
        '        return MRX2BSpaceResult(\n            strategy.wire, candidates.size, consistent.size, retained.size, selected, trace,',
        '        trace.r31.interpreterCalls = r31InterpreterCalls\n'
        '        return MRX2BSpaceResult(\n            strategy.wire, candidates.size, consistent.size, retained.size, selected, trace,',
        'record actual search interpreter calls')
    src=replace_once(src,
        '    private fun evaluate(body: Expr, values: Map<String, String>): Boolean? {\n        val definition =',
        '    private fun evaluate(body: Expr, values: Map<String, String>): Boolean? {\n'
        '        r31InterpreterCalls++\n        val definition =',
        'count atom and candidate executions')
    return src


def main():
    ap=argparse.ArgumentParser()
    group=ap.add_mutually_exclusive_group(required=True)
    group.add_argument('--check',action='store_true')
    group.add_argument('--apply',action='store_true')
    args=ap.parse_args()
    if not TARGET.is_file():
        raise SystemExit(f'Expected to run at repository root, cannot find {TARGET}')
    original=TARGET.read_text(encoding='utf-8-sig')
    updated=patch(original)
    diff=''.join(difflib.unified_diff(
        original.splitlines(True),updated.splitlines(True),
        fromfile='a/'+str(TARGET),tofile='b/'+str(TARGET)))
    if args.check:
        print(diff)
        print('CHECK_OK: patch anchors verified; no files changed')
    else:
        TARGET.write_text(updated,encoding='utf-8')
        print('APPLIED:',TARGET,'; validate with gradlew quickTest and git diff')

if __name__=='__main__':main()
