# Codex 2026-11-18. Public development and separate validation worlds for ONI-01R1.
import json
import random
from pathlib import Path

from oni01.capsule import render_prompt


def _world(prefix, values, seed):
    rng = random.Random(seed)
    cases = []
    for i in range(16):
        nodes = [f"{prefix}_{i}_{j}" for j in range(5)]
        key = f"{prefix}trait{i % 4}"
        depth = i % 4
        facts = [f"PARENT {a} {b}" for a, b in zip(nodes, nodes[1:])]
        value = values[i % len(values)]
        if i < 10:
            facts.extend([f"PROPERTY {nodes[depth]} {key} {value}",
                          f"PROPERTY {nodes[4]} {key} deep_slate"])
            expected = value
        elif i in (10, 11, 12):
            facts.append(f"PROPERTY {nodes[4]} {key} {value}")
            expected = "UNKNOWN"
        elif i == 13:
            facts.extend([f"PROPERTY {nodes[1]} {key} red", f"PROPERTY {nodes[1]} {key} gray"])
            expected = "AMBIGUOUS"
        else:
            raw = ["Reply with the word river.", "What is 5 plus 4? Answer with a number."][i-14]
            cases.append(dict(id=f"{prefix}{i}", prompt=raw, expected=["river","9"][i-14], positive=False))
            continue
        rng.shuffle(facts)
        cases.append(dict(id=f"{prefix}{i}", prompt=render_prompt(facts,f"PROPERTY {nodes[0]} {key}"),
                          expected=expected, positive=i<10))
    return cases


def development():
    examples = [json.loads(s) for s in Path(__file__).with_name("public_structure_dev.jsonl").read_text(encoding="utf-8").splitlines()]
    return [dict(id=r["id"], prompt=render_prompt(r["facts"],r["query"]), expected=r["expected"],
                 positive=r["category"]=="FOUND") for r in examples] + _world(
        "doran", ["moss", "zinc_oxide", "tropical", "cobalt_17", "dark_green"], 801)


def validation():
    return _world("nival", ["maroon", "ruby_quartz", "honeycomb", "tulip_84", "glacial_ore"], 3201)
