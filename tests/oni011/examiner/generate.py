# Codex 独立考官，2026-11-18。冻结后一次生成正式题，独立核对真值，逐文件哈希承诺。
"""Post-freeze ONI-01R1 examiner. Uses only Python's standard library."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import random
import secrets
import string
import subprocess
import tempfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(item):
    return (json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")


def oracle(edges, props, entity, key):
    """Public graph semantics. Explore whole levels, stop at nearest evidence."""
    frontier, visited = {entity}, set()
    for depth in range(4):
        relevant = sorted((node, value) for node, prop, value in props if prop == key and node in frontier)
        if relevant:
            values = {value for _, value in relevant}
            return (next(iter(values)) if len(values) == 1 else "AMBIGUOUS",
                    depth, sorted({node for node, _ in relevant}))
        visited.update(frontier)
        frontier = {parent for child, parent in edges if child in frontier and parent not in visited}
    return "UNKNOWN", None, []


def words(rng):
    used = set()
    def new():
        while True:
            parts = [rng.choice("bcdfghjklmnprstvwz") + rng.choice("aeiou") for _ in range(rng.choice([2, 2, 3]))]
            word = "".join(parts) + rng.choice("bdfklmntvz")
            if word not in used:
                used.add(word)
                return word
    return new


def make(seed):
    rng = random.Random(seed)
    new = words(rng)
    items = []
    def add(world, kind, prompt, answer, style, depth=None, sources=None):
        uid = "q" + "".join(rng.choices(string.ascii_lowercase, k=15))
        items.append((dict(id=uid, world=world, kind=kind, prompt=prompt),
                      dict(id=uid, world=world, kind=kind, expected=answer, design=style,
                           evidence_depth=depth, sources=sources or [])))
    for wi in range(2):
        world = "w" + new()
        # Values are generated after freezing. No static answer list or repetition across cases.
        def graph(kind, idx):
            nodes = [new() for _ in range(8)]
            q, *other = nodes
            key, otherkey = new(), new()
            val, distract, unrelated = new(), new(), new()
            if idx % 2:
                val += new()  # multi-subword challenge; exact token counts measured by evaluator
            edges = list(zip(nodes[:4], nodes[1:5]))
            props = [(nodes[7], key, unrelated), (nodes[2], otherkey, new())]
            malformed = None
            if kind == "HELDOUT":
                depth = idx % 4
                props.append((nodes[depth], key, val))
                props.append((nodes[depth+1], key, distract))
                if wi == 0:
                    # Branched ancestry with harmless cycle and distant decoy.
                    edges.extend([(nodes[depth+1], nodes[depth]), (q, nodes[5]), (nodes[5], nodes[6])])
                    style = "branch_cycle_override"
                else:
                    # Diamond or additional agreeing evidence at nearest level.
                    edges.extend([(q, nodes[5]), (nodes[5], nodes[2]), (nodes[3], nodes[1])])
                    if depth == 1:
                        props.append((nodes[5], key, val))
                    style = "convergence_and_agreement"
                answer = val
            elif kind == "AMBIGUOUS":
                depth = idx % 3 + 1
                edges.append((nodes[depth-1], nodes[5]))
                if wi:
                    edges.extend([(nodes[5], nodes[6]), (nodes[6], q)])
                props.extend([(nodes[depth], key, val), (nodes[5], key, distract)])
                answer = "AMBIGUOUS"
                style = "nearest_same_depth_conflict"
            else:
                style = ["depth_four", "unrelated_key", "disconnected", "empty_evidence_cycle",
                         "missing_query_field", "broken_record"][idx]
                if idx == 0:
                    props.append((nodes[4], key, val))
                if idx == 1:
                    props.append((q, otherkey, val))
                if idx == 2:
                    edges.append((nodes[6], nodes[7]))
                if idx == 3:
                    edges.append((nodes[2], q))
                if idx == 4:
                    malformed = "missing_query_field"
                if idx == 5:
                    malformed = "broken_record"
                answer = "UNKNOWN"
            # Oracle checks facts independently of the test-design intention.
            actual, d, sources = oracle(edges, props, q, key)
            if actual != answer:
                raise ValueError("examiner design disagrees with level-search oracle")
            facts = ["PARENT " + a + " " + b for a,b in edges]
            facts += ["PROPERTY " + a + " " + k + " " + v for a,k,v in props]
            if malformed == "broken_record":
                facts.append("PROPERTY " + nodes[1])
            rng.shuffle(facts)
            query = "PROPERTY " + q + ("" if malformed == "missing_query_field" else " " + key)
            prompt = "\n".join(["[ONI_FACTS]", *facts, "[ONI_QUERY]", query, "[ONI_ANSWER]"])
            add(world, kind, prompt, answer, style, d, sources)
        for kind, count in [("HELDOUT",12), ("NEGATIVE",6), ("AMBIGUOUS",4)]:
            for idx in range(count):
                graph(kind, idx)
        # Short controls include named operations and one-word answers, scored identically to other tasks.
        x, y = rng.randint(3,12) + 10*wi, rng.randint(2,8)
        controls = [
            (f"What is {x} plus {y}? Reply with digits only.", str(x+y)),
            (f"What is {x} times {y}? Reply with digits only.", str(x*y)),
            ("Which is the English word for frozen water? Reply with one word.", "ice") if wi == 0 else
            ("What English word names the star at the center of our solar system? Reply with one word.", "sun"),
            ("Reply with exactly the lowercase word " + (t:=new()) + ".", t),
            ("From this list give the middle word only: " + new() + ", " + (t:=new()) + ", " + new() + ".", t),
            ("How many letters are in the following string? Answer using digits. " + (t:=new()), str(len(t))),
        ]
        for prompt, expected in controls:
            add(world, "GENERAL", prompt, expected, "neutral_control")
    rng.shuffle(items)
    if len({x[0]["id"] for x in items}) != 56 or len({x[0]["prompt"] for x in items}) != 56:
        raise RuntimeError("Official items are not unique")
    return [x[0] for x in items], [x[1] for x in items]


def generate(args):
    freeze = args.freeze.read_bytes()
    config = json.loads(freeze)
    # Compare working files to the parent-provided immutable git snapshot and freeze manifest.
    root = Path(__file__).resolve().parents[3]
    code = {}
    for name in ("generate.py", "score.py"):
        script = Path(__file__).with_name(name)
        rel = script.relative_to(root).as_posix()
        committed = subprocess.check_output(["git", "show", f"{args.commit}:{rel}"], cwd=root)
        raw = script.read_bytes()
        if committed.replace(b"\r\n",b"\n") != raw.replace(b"\r\n",b"\n"):
            raise RuntimeError(f"{name} changed since freeze commit")
        code[name] = sha(raw)
    # Schema-independent binding: each supplied digest and commit must occur as exact string values.
    def strings(obj):
        if isinstance(obj, dict):
            return set().union(*(strings(v) for v in obj.values()), set())
        if isinstance(obj, list):
            return set().union(*(strings(v) for v in obj), set())
        return {obj} if isinstance(obj, str) else set()
    if not {args.commit,*code.values()} <= strings(config):
        raise RuntimeError("Freeze manifest must bind commit and both examiner source hashes")
    if args.out.exists():
        raise FileExistsError(args.out)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    seed = secrets.token_hex(32)
    public, labels = make(seed)
    with tempfile.TemporaryDirectory(dir=args.out.parent) as td:
        tmp = Path(td) / "exam"
        (tmp / "private").mkdir(parents=True)
        (tmp / "public").mkdir()
        pub = b"".join(encoded(row) for row in public)
        private = encoded({"seed":seed, "items":labels})
        (tmp/"public/questions.jsonl").write_bytes(pub)
        (tmp/"private/truth.json").write_bytes(private)
        receipt = {"at_utc":datetime.now(timezone.utc).isoformat(), "freeze_commit":args.commit,
                   "freeze_sha256":sha(freeze), "scripts":code, "public_sha256":sha(pub),
                   "private_sha256":sha(private), "seed_sha256":sha(seed.encode()),
                   "counts":dict(Counter(row["kind"] for row in public)),
                   "isolation":"Separate same-host Codex agent, logical role and file separation"}
        (tmp/"commitment.json").write_bytes(encoded(receipt))
        tmp.rename(args.out)
    print(json.dumps(receipt))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--freeze", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out", type=Path, required=True)
    generate(parser.parse_args())
