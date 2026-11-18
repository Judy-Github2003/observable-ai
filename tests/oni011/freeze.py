# Codex 2026-11-18. Bind the reviewed commit, calibration files and actual model inputs before sampling examiner entropy.
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))
from oni01.runtime import ANSWER_SYSTEM, MODEL, REVISION, WEIGHT_SHA, digest
from oni011.codec import memory_suffix, VERSION
from oni011.data import development, validation


def freeze(args):
    root = ROOT
    commit = subprocess.check_output(["git","rev-parse","HEAD"],cwd=root,text=True).strip()
    if commit != args.commit:
        raise ValueError("Freeze commit differs from current HEAD")
    doc = Path("docs/research/oni-01r1")
    build = Path("build/oni-01r1")
    if (root / build / "exam").exists():
        raise ValueError("Official exam directory already exists")
    src = sorted({p.relative_to(root) for base in ["src/oni01","src/oni011","tests/oni011/examiner"]
                  for p in (root/base).rglob("*") if p.suffix in {".py",".jsonl"}})
    src += [doc/"ONI011_EXPERIMENT_PROTOCOL.json",Path("tests/oni011/freeze.py"),
            Path("tests/oni011/field_probe.py"), Path("tests/oni011/test_injector.py"), Path("tests/oni011/gate_report.py")]
    for p in src:
        # Git can normalize Windows CRLF; compare program content and also record hashes of exact execution bytes.
        blob = subprocess.check_output(["git","show",f"{commit}:{p.as_posix()}"],cwd=root)
        if blob.replace(b"\r\n",b"\n") != (root/p).read_bytes().replace(b"\r\n",b"\n"):
            raise ValueError(f"Uncommitted source or protocol: {p}")
    artifacts = [build/"development"/f for f in ("config.json","gate.safetensors","grid.json")]
    source_hashes = {p.as_posix():digest(root/p) for p in (*src,*artifacts)}
    model_files = ("tokenizer.json","tokenizer_config.json","special_tokens_map.json","chat_template.jinja",
                   "config.json","generation_config.json","model.safetensors.index.json")
    config = json.loads((root/artifacts[0]).read_bytes())
    model = json.loads((root/build/"model.json").read_bytes())
    if model["model_commit"]!=REVISION or model["model_weight_sha256"]!=WEIGHT_SHA:
        raise ValueError("Verified local model revision or weight digest mismatch")
    def hash_text(s):
        return hashlib.sha256(s.encode("utf-8")).hexdigest()
    def data_digest(rows):
        return hash_text(json.dumps(rows,ensure_ascii=False,sort_keys=True,separators=(",",":")))
    result = {"task_id":"ONI-01R1","at_utc":datetime.now(timezone.utc).isoformat(),
              "git_commit":commit,"sha256":source_hashes,"model_revision":REVISION,"weight_sha256":WEIGHT_SHA,
              "model_files_sha256":{f:digest(MODEL/f) for f in model_files},"config":config,
              "codec_version":VERSION,"system":ANSWER_SYSTEM,"system_sha256":hash_text(ANSWER_SYSTEM),
              "common_memory_suffix":memory_suffix(),"memory_suffix_sha256":hash_text(memory_suffix()),
              "development_data_sha256":data_digest(development()),"validation_data_sha256":data_digest(validation()),
              "data_hash_method":"SHA-256 of UTF-8 canonical JSON list, sorted keys, compact separators",
              "prompt":"Model-local chat template; add_generation_prompt=true, enable_thinking=false, thinking=false.",
              "codec_locus":"Ordered slots located by common prefix offsets, decoder block output.",
              "decode":{"seed":17001,"use_cache":False,"do_sample":False,"max_new_tokens":32}}
    out = root/doc/"ONI011_FREEZE.json"
    with out.open("x",encoding="utf-8",newline="\n") as f:
        f.write(json.dumps(result,indent=2,ensure_ascii=False)+"\n")
    print(json.dumps({"manifest":out.as_posix(),"sha256":digest(out),"files":len(source_hashes),"commit":commit}))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--commit",required=True)
    freeze(p.parse_args())
