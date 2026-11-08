#!/usr/bin/env python3
"""Read-only inventory of three archival formal source hash mismatches.

This does NOT re-sign old freezes, skip failing tests, run a formal examination,
or turn the current-source full Gradle regression green. Optional --git-history
finds whether older source bytes with matching SHA can be reconstructed.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

MANIFESTS = (
    'experiments/representation-evolution-e3/reports/pack02r-c8-03-freeze.json',
    'experiments/representation-evolution-e3/reports/mrx-01-freeze.json',
    'experiments/representation-evolution-e3/reports/mrx-01r-freeze.json',
)

def digest(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()

def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], capture_output=True, check=False)

def locate_version(repo: Path, path: str, expected: str, limit: int) -> dict:
    commits = git(repo, 'log', '--all', '--format=%H', '--', path)
    if commits.returncode != 0:
        return {'status': 'GIT_HISTORY_UNAVAILABLE'}
    revs = commits.stdout.decode('ascii', 'replace').splitlines()
    for rev in revs[:limit]:
        blob = git(repo, 'show', f'{rev}:{path}')
        if blob.returncode == 0 and digest(blob.stdout) == expected:
            return {'status': 'FOUND_HISTORICAL_BLOB', 'commit': rev}
    return {'status': 'NOT_FOUND_IN_SCANNED_HISTORY', 'commitsScanned': min(len(revs), limit),
            'totalPathCommits': len(revs)}

def audit(repo: Path, history=False, limit=100) -> dict:
    checks = []
    for file in MANIFESTS:
        manifest_path = repo / file
        if not manifest_path.is_file():
            raise FileNotFoundError(file)
        data = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
        sources = []
        if 'sources' in data:
            for label, record in data['sources'].items():
                sources.append((label, record['path'], record['sha256']))
        else:
            sources.append(('student', data['student_source'], data['student_sha256']))
            sources.append(('exam', data['exam_source'], data['exam_sha256']))
        for label, path, expected in sources:
            file_path = repo / Path(path.replace('\\', '/'))
            actual = digest(file_path.read_bytes()) if file_path.is_file() else None
            record = {
                'manifest': file, 'source': label, 'path': path,
                'expectedSha256': expected, 'currentSha256': actual,
                'status': 'MATCH' if actual == expected else 'ARCHIVAL_SOURCE_MISMATCH',
            }
            if history and actual != expected:
                record['gitHistorySearch'] = locate_version(repo, Path(path.replace('\\', '/')).as_posix(), expected, limit)
            checks.append(record)
    mismatches = sum(row['status'] != 'MATCH' for row in checks)
    return {'status': 'ARCHIVE_AND_HEAD_COMPARED_NOT_RESIGNED',
            'historicalManifestCount': len(MANIFESTS),
            'checkedSources': len(checks), 'mismatchedSources': mismatches,
            'currentSourceUnfilteredTestExpectedToFailIfMismatch': mismatches > 0,
            'checks': checks}

def main(argv=None):
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo',type=Path,default=Path('.'))
    ap.add_argument('--git-history',action='store_true')
    ap.add_argument('--max-commits',type=int,default=100)
    ap.add_argument('--output',type=Path)
    args=ap.parse_args(argv)
    if args.max_commits < 1:
        ap.error('--max-commits must be positive')
    result = audit(args.repo.resolve(), args.git_history, args.max_commits)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding='utf-8')
    print(rendered, end='')
    # Status mismatch is diagnostic, not a tool execution error; never print PASS.
    return 0
if __name__ == '__main__':
    try: raise SystemExit(main())
    except (OSError,ValueError,KeyError) as exc:
        print('HISTORICAL_FREEZE_AUDIT_ERROR:', exc, file=sys.stderr)
        raise SystemExit(2)
