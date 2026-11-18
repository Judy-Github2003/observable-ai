# Codex, 2026-11-18. Assemble the ONI-01R1 deliverable after unblinding and console review.
"""Run from the repository root with the local Python 3.11+ standard library."""
import argparse
import hashlib
import json
from pathlib import Path
import os
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "build/oni-01r1"
DOC = ROOT / "docs/research/oni-01r1"
SAFE_EXTENSIONS = {".py", ".md", ".txt", ".log", ".json", ".jsonl", ".safetensors"}
REPORTS = ["AUTHORIZATION_REPORT.md", "BASELINE_AND_REGRESSION.md", "CODEC_SPEC.md",
           "GATE_SHIFT_REPORT.md", "EXPERIMENT_PROTOCOL.json", "FREEZE.json",
           "EXPERIMENT_REPORT.md", "HANDOFF.md"]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def json_at(path):
    return json.loads(path.read_bytes())


def package(exam, scores, finish):
    files = {}

    def add(path, name):
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Expected a regular evidence file: {path}")
        if not name.isascii() or name in files or path.suffix not in SAFE_EXTENSIONS:
            raise ValueError(f"Unexpected archive name: {name}")
        files[name] = path

    def tree(path, name):
        if not path.is_dir():
            raise ValueError(f"Missing directory: {path}")
        for child in sorted(path.rglob("*")):
            if child.is_file() and "__pycache__" not in child.parts and child.suffix != ".pyc":
                add(child, name + "/" + child.relative_to(path).as_posix())

    # Only include task-specific files. The run directory also holds a separate Git worktree.
    for name in REPORTS:
        if not (DOC / ("ONI011_" + name)).is_file():
            raise FileNotFoundError(name)
    for name in ["src/oni01", "src/oni011", "tests/oni011", "docs/research/oni-01r1"]:
        tree(ROOT / name, name)
    for name in ["README.md", "README.en.md"]:
        add(ROOT / name, name)
    for name in ["model.json", "authorization_preflight.txt"]:
        add(RUN / name, "build/oni-01r1/" + name)
    add(finish, "build/oni-01r1/authorization_finish.json")
    completed = json_at(finish)
    if completed.get("repair_task") != "ONI-01R1" or completed.get("status") != "CONSUMED":
        raise ValueError("Console receipt does not close this research task")
    for name in ["t1", "development"]:
        tree(RUN / name, "build/oni-01r1/" + name)
    # Include previous public-development attempts, any additional diagnostics and the
    # exact model-integrity receipts. Never traverse the repository, model or runtime cache.
    for pattern in ("development-*", "development_*", "diagnostics*", "field_probes", "probe*", "pilot*"):
        for path in sorted(RUN.glob(pattern)):
            if path.is_dir():
                tree(path, "build/oni-01r1/" + path.name)
            elif path.suffix in SAFE_EXTENSIONS:
                add(path, "build/oni-01r1/" + path.name)
    for path in sorted(RUN.glob("*.json")):
        if path not in files.values() and not path.name.endswith("validation.json"):
            add(path, "build/oni-01r1/" + path.name)
    for name in ["requirements-freeze.txt", "bf16-runtime.json"]:
        path = ROOT / "build/oni-01/runtime" / name
        if path.is_file():
            add(path, "environment/" + name)

    # The frozen code and calibration hashes allow a check independent of the local git history.
    freeze = json_at(DOC / "ONI011_FREEZE.json")
    for name, expected in freeze["sha256"].items():
        if digest(ROOT / name) != expected:
            raise ValueError("Frozen artifact changed: " + name)
    off = RUN / "official"
    for receipt, raw in [("completion.json", "ONI011_PREDICTIONS.jsonl"),
                         ("likelihood_completion.json", "likelihoods.jsonl")]:
        if json_at(off / receipt)["sha256"] != digest(off / raw):
            raise ValueError("Inference receipt mismatch: " + raw)
    score = json_at(scores / "score.json")
    if score["prediction_sha256"] != digest(off / "ONI011_PREDICTIONS.jsonl"):
        raise ValueError("Score was produced from different predictions")
    if score["loglikelihood_sha256"] != digest(off / "likelihoods.jsonl"):
        raise ValueError("Score was produced from different likelihoods")
    for path in sorted(off.iterdir()):
        if path.name == "ONI011_TRACE":
            tree(path, path.name)
        elif path.is_file():
            add(path, path.name)
    if not list((off / "ONI011_TRACE").glob("*.safetensors")):
        raise ValueError("Missing raw activation tensors")
    tree(exam, "ONI011_EXAMINER/data")
    if not scores.is_relative_to(exam):
        tree(scores, "ONI011_EXAMINER/scoring")
    for name in ["score.py", "generate.py"]:
        add(ROOT / "tests/oni011/examiner" / name, "ONI011_EXAMINER/" + name)
    manifest = {"task": "ONI-01R1", "freeze_commit": freeze["git_commit"],
                "weight_sha256": freeze["weight_sha256"], "excluded": ["base model", "virtual environment"],
                "files": [{"path": n, "source": p.relative_to(ROOT).as_posix(),
                           "size_bytes": p.stat().st_size, "sha256": digest(p)}
                          for n,p in sorted(files.items())]}
    target = RUN / "ONI_01R1_RESULTS.zip"
    # A temporary archive keeps even a failed integrity pass away from the final filename.
    with tempfile.TemporaryDirectory(dir=RUN) as temp:
        staged = Path(temp) / target.name
        with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED, compresslevel=4) as archive:
            for name,path in sorted(files.items()):
                archive.write(path, name)
            archive.writestr("PACKAGE_MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
        with zipfile.ZipFile(staged) as archive:
            if archive.testzip() or len(archive.namelist()) != len(files)+1:
                raise ValueError("ZIP CRC or file count mismatch")
            for entry in manifest["files"]:
                with archive.open(entry["path"]) as stream:
                    if hashlib.file_digest(stream, "sha256").hexdigest() != entry["sha256"]:
                        raise ValueError("ZIP content mismatch: " + entry["path"])
        if target.exists():
            raise FileExistsError(target)
        os.rename(staged, target)
    checksum = digest(target)
    target.with_suffix(".zip.sha256").write_text(f"{checksum}  {target.name}\n", encoding="ascii")
    print(json.dumps({"name": target.name, "size_bytes": target.stat().st_size,
                      "files": len(files)+1, "sha256": checksum}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exam", type=Path, required=True)
    parser.add_argument("--scores", type=Path, required=True)
    parser.add_argument("--finish", type=Path, default=RUN / "authorization_finish.json")
    args = parser.parse_args()
    package(args.exam.resolve(), args.scores.resolve(), args.finish.resolve())
