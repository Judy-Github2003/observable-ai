<!-- Documentation update by ChatGPT, 2026-11-21: describe Classizer and the R03 development snapshot, distinguish published evidence from pending materials, and preserve historical entry points. -->
# Observable AI

[中文](README.md) · **English**

**Research into observable learning systems, with inspectable evidence for knowledge, computation, and capability updates.**

When a system learns to solve a task it previously failed, can we identify the knowledge, rules, and computation responsible—and correct mistakes without losing existing capabilities?

Observable AI investigates this question through experiments in semantics, logic, mathematics, and code, with a growing scope that includes Linux operations, general knowledge, and history. The project combines explicit knowledge and rules, MiniCPM as a neural executor, and a native model using Unicode and Class representations. Learning knowledge, learning executable methods, and improving learning strategies are separate research goals evaluated through controlled experiments.

> **Research status · 2026-11-21**  
> This repository contains the Kotlin learning core, ONI model-injection experiments, and a runnable Classizer3D prototype. The latest ARLS R03 development materials include an approximately **987M-parameter** model and training-metering prototype. The complete pilot bundle has not yet been published here and is described separately below. R03 has **0 optimizer updates on the full-size model**; it is not a pretrained general-purpose model release or a demonstrated capability improvement over baselines.

## Research questions

| Question | Approach | What needs to be measured |
|---|---|---|
| How does knowledge affect an answer? | Explicit rules, provenance, Class representations, and a neural bridge | Whether replacing, removing, or restoring evidence changes the final answer as expected |
| How does the system acquire new knowledge? | Parameter training, structured registration, external lookup, and controlled comparisons | New learning, retention, the scope of changes, and update cost |
| Can harder tasks use more computation? | A shared computation core, adaptive depth, and a monotone 3D budget | Actual computation, latency, and stopping errors at comparable accuracy |
| Can errors become easier to diagnose? | Records of inputs, candidates, routing, execution, and versions, paired with interventions | Diagnostic and repair effectiveness relative to controls, including effects on other tasks |

“Observable” refers to inspectable provenance, explicit state, execution records, and intervention results. Logs, gradients, or changed activations alone do not establish conceptual understanding or a causal explanation.

## Class definitions and contextual uses

Classizer explores structured representations containing lexical forms, sense facets, properties, relations, and provenance. A **Class definition** can retain several possible meanings. A **Class use** binds a particular mention to its instance, scope, candidate facets, active facets, and supporting evidence.

For the Chinese word “苹果” (apple / Apple), the intended behavior is:

| Input | Class definition | Interpretation of this use |
|---|---|---|
| 我想买一个苹果。 — Purchase request without prior device context | Retain fruit and electronics-related candidate facets | Keep the use unresolved when evidence is insufficient |
| 我的手机坏了，我想买一个苹果。 — Broken-phone context before the purchase request | Keep the same definition | The phone context supports the electronics-related facet |
| A passage separately discusses eating an apple and buying an Apple phone. | Mentions may share a definition | Bind each mention independently; one use must not overwrite another |

A phone Class may also have a conditional relation to Apple, such as a candidate-brand association. That does not imply that every phone is an Apple product. Unknown, unresolved, coactivated, and conflicting states need distinct representations.

These are design and evaluation contracts. Existing structures and small examples express parts of them; autonomous span discovery, instance binding, and contextual disambiguation in open text still require training and evaluation.

## Implementations and public evidence

| Stage | Available work | Evidence and scope |
|---|---|---|
| Kotlin learning core | Explicit facts, executable rules, capability registration, curricula, and provenance | [Historical index](README.history.md), [source](src/main/kotlin/); results apply to their bounded tasks |
| Observable Neural Injection (ONI) | Structured evidence injected into a frozen MiniCPM Transformer, with zero-and-restore controls | [ONI-01](docs/research/oni-01/ONI01_EXPERIMENT_REPORT.md), [ONI-01.1](docs/research/oni-01r1/ONI011_EXPERIMENT_REPORT.md); some intervention paths verified, without an accuracy gain on the corresponding tasks |
| Classizer3D v0 | Typed objects, five-view byte-based compositional representations, and sparse graph computation in a monotone 3D order | [Implementation](research/classizer3d/README.md), [verification record](research/classizer3d/RESULTS.md); 12/12 CPU tests reported, with caller-supplied positions and topology |
| ARLS R03 | A Unicode/Class model, shared-core and budget-control research paths, training and checkpoint metering | Development snapshot below; the complete materials are not published in this repository, so the current checkout cannot reproduce every R03 record |

### Historical ONI baselines

In ONI-01, forced injection changed the activation received by the next layer on all 12 applicable held-out cases, but both the intervention and baseline answered **2/12** correctly. The automatic gate fired on **0/12**. In ONI-01.1, forced injection and its matched baseline both scored **2/24** on applicable hidden questions across two new worlds. Zero-and-restore controls reproduced their paired outputs on all **56** questions. The gate detected 24 applicable cases and also opened on 21 of 32 controls.

These records distinguish evidence entering the computation, correct gating, and improved final answers. The linked reports document the experimental conditions.

## R03 development snapshot

The following figures come from the maintainer-provided `ARLS_R03_PILOT.zip`, codec comparison, and mechanism notes, reviewed through source inspection and limited independent checks. **The complete materials have not yet been published here. This section reports development progress; the runnable public entry points are listed below.**

| Item | Current record | Interpretation |
|---|---|---|
| Model size | **987,252,481** parameters | Source structure and parameter arithmetic agree; 38 independent blocks, including 4 middle blocks that support repeated execution |
| Surface frontend | **13,929** symbol slots | Unicode scalar vocabulary, UTF-8 byte fallback, and structural control symbols; designed to preserve the original text |
| Hot Class registry | **65,536** Classes and **107,018** lexical forms | Candidate IDs enter the backbone through trainable 128-dimensional representations and a neural bridge; registry size is not a measure of internalized knowledge |
| Pilot material | **3,072** inputs in 8 shards | Stored reference-token counts sum to **492,368**; this is a recount of recorded fields, not evidence that a large pretraining corpus is ready |
| Review checks | **3,072/3,072** inputs round-tripped exactly; 82 manifest file hashes matched | Checks of encoding and delivery integrity, not language capability scores |
| Full-size model training | **0 optimizer updates** | Small-model and engineering checks do not substitute for training the full-size model |

The current TC (surface units + Class) backbone still predicts the next surface unit, with Class information entering through a neural bridge. Structured-output contracts and validators exist; reliable structured generation, direct Class prediction, and variable computation width still require training and evaluation. Whether token and Class mechanisms complement or replace one another requires separate evidence.

The shared core runs once by default and spherical budgeting is off by default. The default R03 training path does not yet train the routing and stopping controller. The monotone 3D spherical budget constrains progression and stopping in repeated computation; it is a different experiment from the caller-supplied scheduling coordinates in public Classizer3D v0. Logical stopping also does not imply proportional GPU savings.

### Encoding length: retaining unfavorable results

On 156 small development inputs with exact raw-text overlap against fitting material excluded, frozen codecs recorded:

| Representation | Total input units | Recorded exact round trips |
|---|---:|---:|
| Unicode surface frontend (TC/NC) | 44,020 | 156/156 |
| BPE frontend (BT/BN) | 19,388 | 156/156 |
| Current CL text-serialization control | 901,328 | 156/156 |

On this sample, the Unicode representation is approximately **2.27×** the BPE length. The current CL control writes a JSON frame with a Class reference for each character, then surface-encodes that text. Its approximately **20.48×** expansion over Unicode does not represent a compact direct-Class-ID encoding.

This check measures length and reversibility only. The development sample is small and nonrandom; vocabulary sizes and fitting material are not identical across codecs. It does not establish a general ranking of model capability, training efficiency, or frontends. Full comparison materials remain pending publication.

## Quick start

These commands use code already in this repository. Clone it and enter the project directory:

```bash
git clone https://github.com/Judy-Github2003/observable-ai.git
cd observable-ai
```

### 1. Checks without model weights

With Python 3.10 or later, run the Classizer3D type and serialization checks using only the standard library:

```bash
python -m unittest discover -s research/classizer3d/tests -p test_schema.py -v
```

The ONI-01 inheritance-rule checks also use only the Python standard library. On Linux / macOS:

```bash
PYTHONPATH=src python -m unittest discover -s tests/oni01 -p test_capsule.py -v
```

On Windows PowerShell:

```powershell
$env:PYTHONPATH = "src"
python -m unittest discover -s tests/oni01 -p test_capsule.py -v
```

### 2. Classizer3D neural prototype

Install PyTorch in a suitable Python environment. A CPU is sufficient for these small examples:

```bash
python -m pip install torch
python -m unittest discover -s research/classizer3d/tests -v
python -m research.classizer3d.neural
```

These are structure and gradient-path checks. They do not train the R03 model or require MiniCPM weights. See the [Classizer3D README](research/classizer3d/README.md) for the detailed scope.

### 3. Kotlin learning core

JDK 21 is required. The first build needs network access to download dependencies.

```bash
bash ./gradlew --no-daemon installDist
```

On Windows PowerShell:

```powershell
./gradlew.bat --no-daemon installDist
```

Use the targeted checks in the [engineering test guide](docs/engineering/TESTING.md) for routine work. An unfiltered `gradlew test` runs lengthy research regressions; read the guide first.

## Next evaluations

1. **Publish reproducible experiments.** Package R03 source, required fixtures, data provenance, verification records, and runnable entry points for independent review.
2. **Compare representations.** Use the same raw inputs and supervision for BPE, Unicode, Class, and hybrid paths; report parameters, actual computation, and final-answer quality.
3. **Measure learning and retention.** Evaluate new expressions, multiple instances, knowledge corrections, and revocations after training, then reassess existing capabilities.
4. **Test computation and diagnostic benefits.** Compare fixed depth, scalar budgets, and spherical budgets; use real errors to evaluate targeted interventions, repair scope, and cost.

MiniCPM remains a neural executor and a practical baseline. Both native-model development and integration with existing models will be guided by experimental results.

## Contributing and support

Contributions are welcome on reproducible examples, data provenance, instance binding, knowledge updates, computation metering, and controlled comparisons. Open an [issue](https://github.com/Judy-Github2003/observable-ai/issues) or contribute code. For a useful report, include the commit, environment, minimal input, actual output, and expected behavior, with credentials and private data removed.

The project seeks open-source maintenance and research support for code review, documentation, reproduction infrastructure, and controlled experiments. Any support must be used within the applicable program and data-license terms. Model capability and ecosystem usage are described through public evidence.

## License and history

Repository code is released under the [MIT license](LICENSE). Third-party models, dictionaries, and corpora retain their own licenses; the repository's code license does not establish their redistribution or training permissions. The pending R03 release needs to include its corresponding notices.

[Historical experiment index](README.history.md) · [ONI-01 report](docs/research/oni-01/ONI01_EXPERIMENT_REPORT.md) · [ONI-01.1 report](docs/research/oni-01r1/ONI011_EXPERIMENT_REPORT.md) · [Classizer3D verification record](research/classizer3d/RESULTS.md)
