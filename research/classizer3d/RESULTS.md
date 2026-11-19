# Classizer3D v0 — verification snapshot (2026-11-19)

## Scope

This is an untrained feasibility prototype with a hand-designed typed IR, a deliberately limited `X想吃Y` parser, open-vocabulary five-view embeddings, and a top-k routed forward-only 3D directed acyclic computation graph. No trained language understanding, retrieval learning, speedup, or improved task accuracy is claimed.

## Local checks

Environment: Python 3.13, PyTorch 2.10.0+cpu, CPU only.

Run:

```bash
python -m unittest discover -s research/classizer3d/tests -v
python -m research.classizer3d.neural
```

Result: 12/12 test cases passed. Test coverage includes strict field checking, inheritance, object references, JSON round trip, fallback text preservation, 3D forward constraint, sparse top-k edge selection, multiview field separation, novel schemas without parameter changes, gradient flow, and changed-leaf propagation.

## Interpretation

Passing tests establishes only that this specific code path behaves as specified on small examples. The 3D coordinates are user-supplied scheduling values, not learned 3D attention positions. A held-out, matched-compute quality comparison is not available. No benchmarks in this repository have established that this model outperforms standard Transformer architectures.
