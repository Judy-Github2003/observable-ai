#!/usr/bin/env python3
"""Rebuild E23 public-development replay manifest ONLY from immutable E22 events.

Never reads EXAMINER labels/scores or learning targets. This script is a forensic
repair of an accidentally mis-associated set of token/query/hash triples, not
an invitation to alter E22 data or E23 seed selection after seeing new scores.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import sys

REL_SOURCE = Path('docs/research/sd03-e22-development-2026-11-07')
REL_OUTPUT = Path('config/e23-frozen-replay-plan.json')
SOURCE_COMMIT = 'b250bc9e55362b9b966bc0b27f01cf190e3ece41'
# Pre-existing 8 public development task/seed pairs (order frozen E2.2).
TASKS = [
    ('mrx-base-or-r-d1', 20261111),
    ('mrx-depth-w4', 20261110),
    ('mrx-depth-r-xor-b2', 20261112),
    ('mrx-if-nested-3', 20261109),
    ('mrx-base-or-r-d1', 20261121),
    ('mrx-depth-w4', 20261122),
    ('mrx-depth-r-xor-b2', 20261123),
    ('mrx-if-nested-3', 20261124),
]

def sha(buf: bytes) -> str:
    return hashlib.sha256(buf).hexdigest()

def token(world: str, seed: int) -> str:
    return sha(f'{world}|{seed}'.encode('utf-8'))[:16]

def read_events(path: Path):
    raw = path.read_bytes()
    events = [json.loads(s) for s in raw.decode('utf-8-sig').splitlines() if s.strip()]
    if not events:
        raise ValueError(f'Empty historical E22 event file: {path}')
    return events, sha(raw)

def reconstruct(repo: Path) -> dict:
    root = repo / REL_SOURCE
    if not root.is_dir():
        raise FileNotFoundError(f'Historical E22 evidence unavailable: {root}')
    rows = []
    for world, seed in TASKS:
        name = token(world, seed)
        path = root / f'STUDENT-EVENTS-{name}.jsonl'
        events, source_digest = read_events(path)
        finals = [x for x in events if x.get('phase') == 'FINAL_STUDENT_FROZEN'
                  and x.get('arm') == 'E22_CONJECTURE']
        if len(finals) != 1:
            raise ValueError(f'Expected exactly one E22_CONJECTURE FINAL in {path}, got {len(finals)}')
        e = finals[0]
        if e.get('task') != name:
            raise ValueError(f'Token mismatch in frozen result {path}')
        ids = e.get('queryIds')
        prog_hash = e.get('finalProgramHash')
        if not isinstance(ids, list) or len(ids) > 4 or len(set(ids)) != len(ids):
            raise ValueError(f'Invalid query count or duplicate IDs {path}')
        if any(not isinstance(s, str) or s not in {f'q-{i}' for i in range(12)} for s in ids):
            raise ValueError(f'Invalid public QUERY ID {path}')
        if not isinstance(prog_hash, str) or len(prog_hash) != 64 or any(c not in '0123456789abcdef' for c in prog_hash):
            raise ValueError(f'Invalid final canonical program hash {path}')
        # A freeze may be trusted only if query IDs match actually revealed events.
        revealed = [x['queryId'] for x in events if x.get('phase') == 'QUERY_LABEL_REVEALED'
                    and x.get('arm') == 'E22_CONJECTURE']
        if ids != revealed:
            raise ValueError(f'QUERY transcript and final freeze differ in {path}: {ids} vs {revealed}')
        rows.append({
            'world': world, 'seed': seed, 'taskToken': name,
            'queryIds': ids,
            'expectedE22FullRefitHash': prog_hash,
            'sourceEventSha256': source_digest,
        })
    if sum(len(r['queryIds']) for r in rows) != 11:
        raise ValueError('Historical E22 replay must contain exactly 11 QUERY labels')
    return {
        'schemaVersion': 2,
        'purpose': 'E23-R: restore accidental token/query/hash association drift; not a new experiment',
        'source': str(REL_SOURCE).replace('\\', '/'),
        'sourceCommit': SOURCE_COMMIT,
        'formalRuns': 0,
        'independentClosedBook': 'NOT_RUN',
        'replayQueryCount': 11,
        'tasks': rows,
    }

def rendered(plan: dict) -> bytes:
    return (json.dumps(plan, indent=2, ensure_ascii=False) + '\n').encode('utf-8')

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo', type=Path, default=Path('.'))
    ap.add_argument('--output', type=Path, default=REL_OUTPUT,
                    help='Output path relative to repo; defaults to config/e23-frozen-replay-plan.json')
    ap.add_argument('--write', action='store_true', help='Explicitly write regenerated config only')
    ap.add_argument('--check', action='store_true', help='Check existing config is byte-for-byte correct')
    args = ap.parse_args(argv)
    if args.write and args.check:
        ap.error('--write and --check are mutually exclusive')
    root = args.repo.resolve()
    plan = reconstruct(root)
    result = rendered(plan)
    target = args.output if args.output.is_absolute() else root / args.output
    if args.check:
        if not target.is_file() or target.read_bytes() != result:
            print('E23_REPLAY_PLAN_DRIFT: regenerated plan differs from current file', file=sys.stderr)
            return 3
        print('E23_REPLAY_PLAN_VERIFIED', sha(result), len(plan['tasks']), plan['replayQueryCount'])
        return 0
    if args.write:
        target.parent.mkdir(parents=True, exist_ok=True)
        temp = target.with_suffix(target.suffix + '.e23r.tmp')
        temp.write_bytes(result)
        temp.replace(target)
        print('E23_REPLAY_PLAN_WRITTEN', target, sha(result))
        return 0
    print(result.decode('utf-8'), end='')
    print('DRY_RUN_NOT_WRITTEN', file=sys.stderr)
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print('E23_REPLAY_RECOVERY_ERROR:', exc, file=sys.stderr)
        raise SystemExit(2)
