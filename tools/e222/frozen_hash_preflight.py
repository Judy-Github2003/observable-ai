#!/usr/bin/env python3
"""Read-only byte-level validation of historic source manifests inside a checkout.

Unlike the historical Kotlin tests, this runs BEFORE Gradle. No hashes are
resigned, no line endings normalized, and no source or manifest is changed.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

REPORTS = 'experiments/representation-evolution-e3/reports'
MANIFESTS = (
    'pack02r-c8-03-freeze.json',
    'mrx-01-freeze.json',
    'mrx-01r-freeze.json',
)


def _inside(root: Path, path_text: str) -> Path:
    path = Path(path_text)
    if path.is_absolute():
        raise ValueError(f'Absolute freeze source is not allowed: {path_text}')
    resolved = (root / path).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError(f'Freeze source escapes sandbox: {path_text}') from exc
    return resolved


def audit_freezes(root: Path) -> dict:
    root = root.resolve()
    records = []
    errors = []
    for manifest_name in MANIFESTS:
        relative = f'{REPORTS}/{manifest_name}'
        try:
            manifest = json.loads(_inside(root, relative).read_text(encoding='utf-8-sig'))
            if manifest_name == 'pack02r-c8-03-freeze.json':
                sources = [
                    ('student', manifest['student_source'], manifest['student_sha256']),
                    ('exam', manifest['exam_source'], manifest['exam_sha256']),
                ]
            else:
                sources = [(name, source['path'], source['sha256'])
                           for name, source in sorted(manifest['sources'].items())]
            if not sources:
                raise ValueError(f'No source entries in {manifest_name}')
            for name, path_text, expected in sources:
                source_path = _inside(root, path_text)
                raw = source_path.read_bytes()
                actual = hashlib.sha256(raw).hexdigest()
                ok = actual == expected
                item = {
                    'manifest': relative, 'sourceKey': name, 'path': path_text,
                    'expectedSha256': expected, 'actualSha256': actual,
                    'matched': ok,
                    'rawCRLFCount': raw.count(b'\r\n'),
                    'rawLFCount': raw.count(b'\n'),
                }
                records.append(item)
                if not ok:
                    errors.append(f'{manifest_name}:{name}: {path_text}: byte hash mismatch')
        except (OSError, ValueError, KeyError, TypeError, UnicodeError) as exc:
            errors.append(f'{manifest_name}: {type(exc).__name__}: {exc}')
    # These three frozen manifests contain exactly 2 + 4 + 5 source records.
    # A smaller count is an incomplete preflight, never an implicit success.
    if len(records) != 11:
        errors.append(f'Expected 11 historic source entries, found {len(records)}')
    if len({r['path'] for r in records}) != len(records):
        errors.append('Duplicate historical source path across manifests')
    return {
        'status': 'PASS' if not errors else 'FAIL',
        'manifestCount': len(MANIFESTS),
        'sourceCount': len(records),
        'matchedSources': sum(bool(x['matched']) for x in records),
        'errors': errors,
        'sources': records,
        'disclaimer': 'Only byte-level historical-source check; not a research replay or an independent truth audit',
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--out', type=Path, default=None)
    ns = parser.parse_args(argv)
    report = audit_freezes(ns.repo)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if ns.out:
        ns.out.parent.mkdir(parents=True, exist_ok=True)
        ns.out.write_text(rendered, encoding='utf-8')
    print(rendered)
    return 0 if report['status'] == 'PASS' else 3


if __name__ == '__main__':
    raise SystemExit(main())
