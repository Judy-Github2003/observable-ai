#!/usr/bin/env python3
"""Read-only risk detector before any unfiltered Gradle regression.

It detects known one-shot test output collisions and scans for additional guards.
Not a guarantee that tests are free of other irreversible side effects.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re

KNOWN = [
    ('SD03E1DevelopmentIntegrationTest', 'docs/research/sd03-e1-development-2026-11-07/E1-RUN-STARTED.txt'),
    ('SD03E2DevelopmentIntegrationTest', 'docs/research/sd03-e2-development-2026-11-07/E2-RUN-STARTED.txt'),
    ('SD03E21DevelopmentIntegrationTest', 'docs/research/sd03-e21-development-2026-11-07/E21-RUN-STARTED.txt'),
    ('SD03E22DevelopmentIntegrationTest', 'docs/research/sd03-e22-development-2026-11-07/E22-RUN-STARTED.txt'),
    ('SD03R32IntegrationTest', 'docs/research/sd03-r32-development-2026-11-07/FROZEN-BEFORE-GRADING.jsonl'),
]

def inspect(repo:Path):
    issues=[]
    for classname,relpath in KNOWN:
        src=list((repo/'src/test').rglob(classname+'.kt'))
        if src and (repo/relpath).exists():
            issues.append({'kind':'KNOWN_COLLISION','source':str(src[0].relative_to(repo)),'existing':relpath})
    for test in (repo/'src/test').rglob('*.kt'):
        data=test.read_text(encoding='utf-8-sig',errors='replace')
        if re.search(r'require\s*\(\s*!\s*Files\.exists\(',data) or re.search(r'CREATE_NEW',data):
            issues.append({'kind':'ONE_SHOT_GUARD_REVIEW','source':str(test.relative_to(repo)),'existing':None})
    return issues

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('repo',type=Path)
    args=ap.parse_args()
    repo=args.repo.resolve()
    if not (repo/'build.gradle.kts').exists():
        print('PREFLIGHT ERROR: expected build.gradle.kts')
        return 2
    issues=inspect(repo)
    for v in issues: print(v['kind'],v['source'],v['existing'] or '')
    if issues:
        print('PREFLIGHT BLOCKED: unfiltered test may collide with historical evidence. Do not erase, overwrite, or move committed research outputs. Isolate research test outputs before running.')
        return 3
    print('PREFLIGHT NO KNOWN BLOCKERS; NOT A GUARANTEE OF SIDE-EFFECT SAFETY')
    return 0

if __name__=='__main__':raise SystemExit(main())
