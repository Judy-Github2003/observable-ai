# Classizer + compositional embeddings + sparse 3D DAG (research prototype v0)

This directory is a **small runnable architectural prototype**, not a pretrained language model and **not evidence of higher intelligence, speed, efficiency, or accuracy**. It intentionally sits alongside existing ONI experiments without changing their paths or claims.

## What actually works

- A Python typed-object intermediate representation with schemas, field types, single/multiple inheritance (compatible field definitions only), object references, stable IDs, and JSON round-trip.
- An **intentionally narrow** rule-based parser for sentences matching `X想吃Y`, with lossless fallback to `RawText`. It represents the desire to eat; it does not incorrectly claim eating occurred.
- Open-vocabulary *byte-based* multi-view compositional embedding: type, lexical surface, properties, relations and inheritance are separate inspectable channels, projected and combined with trainable weights; schema names, field names, and literal values reuse the byte vocabulary; adding new Class definitions does **not** automatically add model parameters.
- A trainable PyTorch sparse message-passing operator whose nodes are embedded typed objects. Each directed reference edge must advance monotonically in an integer 3D scheduling coordinate. Each node selects at most `top_k` explicit predecessors with attention; only those predecessor values are mixed. The operator exposes candidate/selected edge traces and supports gradient backpropagation.
- Unit tests for type checks, inheritance, serialization, the refusal of backward edges, actual edge pruning, end-to-end gradients, open type vocabulary, and changed-input propagation.

## What is *not* demonstrated

- General natural language parsing, learned Class creation, formal semantic understanding, class-to-language generation, or autonomous self-improvement.
- Multi-head Transformer replacement, 3D dense/volumetric attention, dynamic topology learning, real hardware speedups, or better performance than a matched Transformer.
- Retrieval from SQL, sandboxed executable Python skills, teacher distillation, or large-model integration.
- Isolating *all* causal effects: gradients and activation changes do not establish that a learned policy uses a fact correctly.

The three coordinates here are **explicit scheduling coordinates**; their axes have no learned semantics yet. Edges form a monotone partial order so the computation is acyclic. Placement and routing topology are supplied by the caller, not learned. Top-k selection is discrete, with differentiable attention weights for the selected edges. This is a feasibility scaffold for future comparison, not a validated scientific architecture.

## Quick start

From repository root:

```bash
python -m unittest discover -s research/classizer3d/tests -v
python -m research.classizer3d.neural
```

Requires Python >=3.10 and `torch` for neural tests (CPU suffices); schema tests use only the standard library. `python -m pip install torch` if necessary.

## Scientific next steps (not yet performed)

Run matched-compute baselines: byte/subword Transformer with equivalent structure privilege, flat graph attention vs 3D DAG, hard-routed vs dense counterparts, same external knowledge/tool permissions. Evaluate unseen schemas, field combinations, aliasing, negation, ambiguity, adversarial parsing, genuine end-to-end language input, and wall-clock/FLOP tradeoffs. Report negative results.