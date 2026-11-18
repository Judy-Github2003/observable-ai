# Observable AI

[中文](README.md) · **English**

When a small language model answers a question it previously missed, can we trace that change to a particular rule, piece of evidence, and computation? Observable AI studies that question through code and controlled experiments.

The project began with explicit rule learning in semantics, logic, code behavior, and mathematics. Its current **Observable Neural Injection (ONI)** experiments bring these rules into MiniCPM's Transformer forward pass. The base weights stay frozen while we measure how evidence affects the model's own generated answer.

## Follow one rule

Suppose a sparrow is a bird, and a bird is an animal. The facts give birds the color blue and animals the color brown. To find the sparrow's color, the inheritance rule follows at most three parent edges and stops at the nearest level with evidence. It returns blue and records its source (bird), its depth (one), and the path (sparrow → bird).

In ONI-01.1 the model reads a record with blank slots. Inside a selected Transformer layer, the code encodes the evidence source, depth, path, and value into the hidden states at those slots. The remaining layers process them, and the model generates its answer token by token. Turning the intervention on, zeroing its delta, and restoring it on the same question tests which changes disappear and return.

## Experiments

| Research question | Observation |
|---|---|
| Can the explicit rules execute across four domains? | On these bounded tasks, selecting the frozen rule directly solved all 32 reuse and 16 boundary cases (48/48). When MiniCPM decided whether to call a rule, it answered 9/32 reuse cases correctly. Eight had a corresponding capability call. |
| Did ONI-01 intervene in the real model? | Forced injection changed the activation received by the next layer on all 12 applicable held-out cases. Both the baseline and the forced condition answered 2/12 correctly. The automatic gate fired on 0/12. |
| Can ONI-01.1 use structured evidence? | Forced injection and its matched baseline both scored 2/24 on applicable hidden questions across two new worlds. Zero and restore controls reproduced their paired outputs on all 56 questions. The gate detected all 24 applicable cases and also opened on 21/32 controls. |

[ONI-01 experiment report](docs/research/oni-01/ONI01_EXPERIMENT_REPORT.md) · [ONI-01.1 experiment report](docs/research/oni-01r1/ONI011_EXPERIMENT_REPORT.md)

## Explore the repository

| Entry point | What you'll find |
|---|---|
| [Kotlin learning core](src/main/kotlin/) | Executable rules, representations of facts, and curriculum experiments |
| [ONI-01 prototype](src/oni01/) | The inheritance rule, captured activations, and injection inside a model layer |
| [ONI-01.1 implementation](src/oni011/) | Structured encodings and paired experiments |
| [Historical experiment index](README.history.md) | Earlier courses, evaluations, commands, and contributor records, in Chinese |

## Run locally

From the repository root, use the Python standard library to test the ONI-01 inheritance rule. These tests exercise nearest evidence, conflicts, the three-edge limit, and cycles.

```powershell
$env:PYTHONPATH = "src"
python -m unittest discover -s tests/oni01 -p test_capsule.py -v
```

The Kotlin learning core requires JDK 21. Build the local executable with the bundled Gradle Wrapper. The first build downloads the dependencies.

```powershell
./gradlew.bat --no-daemon installDist
```

[Engineering test guide](docs/engineering/TESTING.md) · [MIT license](LICENSE)
