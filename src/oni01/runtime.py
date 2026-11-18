# Codex，2026-11-17：锁定 BF16 工件、测绘真实模块及采集原始前向。
from __future__ import annotations

import hashlib
import inspect
import json
import time
from collections import Counter
from pathlib import Path

import torch
import transformers
from safetensors import safe_open
from transformers import AutoModelForCausalLM, AutoTokenizer

REVISION = "42433e90dad95123ab87729fd7011e7110629556"
WEIGHT_SHA = "14fb8e7f0a18d53d1f239773758bf581cee7e456a4523a54622c3a245b64402c"
MODEL = Path("C:/models/MiniCPM5-2B-BF16") / REVISION
WEIGHT = MODEL / "model-00000-of-00001.safetensors"
ANSWER_SYSTEM = (
    "Answer using only the input. For a property query, reply with the single property value; "
    "if absent use UNKNOWN, and for conflicting evidence use AMBIGUOUS. "
    "For other requests reply briefly. No reasoning or explanation."
)
MAX_NEW = 8


def digest(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def write_json(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_rows(path: Path, rows) -> None:
    with path.open("x", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
            file.flush()


def rows(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def hidden(value):
    # Llama styles may return a tensor or a tuple with auxiliary results.
    return value[0] if isinstance(value, tuple) else value


def stats(vector: torch.Tensor) -> dict:
    data = vector.detach().float()
    return {"norm": float(data.norm()), "rms": float(data.square().mean().sqrt())}


def diff(a: torch.Tensor, b: torch.Tensor) -> dict:
    aa, bb = a.float().flatten(), b.float().flatten()
    cosine = torch.nn.functional.cosine_similarity(aa, bb, dim=0)
    return {"delta_norm": float((aa - bb).norm()), "cosine_distance": max(0.0, float(1 - cosine))}


class Runtime:
    def __init__(self, inspect_file=False):
        self.started = time.perf_counter()
        torch.manual_seed(17001)
        if not torch.cuda.is_available() or not torch.cuda.is_bf16_supported(including_emulation=False):
            raise RuntimeError("Native CUDA BF16 is unavailable")
        model_config = json.loads((MODEL / "config.json").read_text(encoding="utf-8"))
        if model_config.get("auto_map") or model_config.get("architectures") != ["LlamaForCausalLM"]:
            raise RuntimeError("Unexpected model architecture or custom repository code required")
        self.metadata = {}
        if inspect_file:
            if WEIGHT.stat().st_size != 5_033_557_096 or digest(WEIGHT) != WEIGHT_SHA:
                raise RuntimeError("Pinned weight integrity check failed")
            with safe_open(WEIGHT, framework="pt", device="cpu") as file:
                names = list(file.keys())
                dtypes = Counter(file.get_slice(name).get_dtype() for name in names)
                first = file.get_slice(names[0])
                self.metadata = {"tensor_count": len(names), "dtypes": dict(dtypes),
                                 "sample_tensor": names[0], "sample_shape": list(first.get_shape())}
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL, local_files_only=True, trust_remote_code=False)
        self.model = AutoModelForCausalLM.from_pretrained(
            MODEL, local_files_only=True, trust_remote_code=False, dtype=torch.bfloat16,
            device_map={"": "cuda:0"}, attn_implementation="sdpa",
        ).eval().requires_grad_(False)
        self.modules = dict(self.model.named_modules())
        # 按 ModuleList 的运行时结构发现 decoder，而不是按配置中的层数套路径。
        candidates = [(name, module) for name, module in self.modules.items()
                      if isinstance(module, torch.nn.ModuleList) and len(module) > 1
                      and all(hasattr(block, "self_attn") and hasattr(block, "mlp") for block in module)]
        if len(candidates) != 1:
            raise RuntimeError("Could not uniquely identify decoder blocks at runtime")
        self.block_path, self.blocks = candidates[0]
        if any(param.dtype != torch.bfloat16 or param.device.type != "cuda" or param.requires_grad
               for param in self.model.parameters()):
            raise RuntimeError("Base parameters must all be frozen BF16 on CUDA")
        self.depths = sorted({round((len(self.blocks) - 1) * fraction) for fraction in (1/3, 1/2, 2/3)})
        self.load_ms = (time.perf_counter() - self.started) * 1000

    def module_name(self, reference):
        return next(name for name, part in self.modules.items() if part is reference)

    def describe(self):
        first = self.blocks[0]
        return {
            "model_class": type(self.model).__name__, "blocks": len(self.blocks), "block_path": self.block_path,
            "hidden_size": self.model.config.hidden_size,
            "heads": self.model.config.num_attention_heads, "kv_heads": self.model.config.num_key_value_heads,
            "mlp_size": self.model.config.intermediate_size,
            "embedding": self.module_name(self.model.get_input_embeddings()),
            "lm_head": self.module_name(self.model.get_output_embeddings()),
            "attention": type(first.self_attn).__name__, "mlp": type(first.mlp).__name__,
            "input_norm": type(first.input_layernorm).__name__, "post_attention_norm": type(first.post_attention_layernorm).__name__,
            "forward": str(inspect.signature(self.model.forward)),
            "block_forward": str(inspect.signature(first.forward)),
            "dtype": "bfloat16", "device": str(next(self.model.parameters()).device),
            "num_parameters": sum(p.numel() for p in self.model.parameters()),
            "torch": torch.__version__, "transformers": transformers.__version__,
            "cuda": torch.version.cuda, "gpu": torch.cuda.get_device_name(),
            "load_ms": round(self.load_ms, 2), "candidate_layers": self.depths,
            "safetensors": self.metadata, "model_commit": REVISION, "model_weight_sha256": WEIGHT_SHA,
            "tokenizer_sha256": digest(MODEL / "tokenizer.json"),
            "cache_enabled": False,
        }

    def encode(self, raw):
        messages = [{"role": "system", "content": ANSWER_SYSTEM}, {"role": "user", "content": raw}]
        prompt = self.tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True,
                                                    enable_thinking=False, thinking=False)
        if prompt.count(raw) != 1:
            raise RuntimeError("The rendered prompt must contain one unchanged user message")
        encoded = self.tokenizer(prompt, add_special_tokens=False, return_tensors="pt",
                                 return_offsets_mapping=True)
        offsets = encoded.pop("offset_mapping")[0].tolist()
        return prompt, encoded.to("cuda"), offsets

    @torch.inference_mode()
    def generate(self, encoded, *, max_new=MAX_NEW):
        started = time.perf_counter()
        torch.cuda.reset_peak_memory_stats()
        output = self.model.generate(
            **encoded, use_cache=False, do_sample=False, max_new_tokens=max_new,
            return_dict_in_generate=True, output_scores=False,
        )
        torch.cuda.synchronize()
        generated = output.sequences[0, encoded.input_ids.shape[1]:]
        return {
            "generated_text": self.tokenizer.decode(generated, skip_special_tokens=True),
            "generated_ids": generated.tolist(),
            "latency_ms": (time.perf_counter()-started)*1000,
            "peak_gpu_allocated_bytes": torch.cuda.max_memory_allocated(),
        }

    @torch.inference_mode()
    def original(self, raw, layers, max_new=MAX_NEW):
        prompt, encoded, _ = self.encode(raw)
        captured = {}
        handles = []
        for index in layers:
            def record(_, _args, value, i=index):
                if i not in captured:
                    captured[i] = hidden(value)[0, -1].detach().clone()
            handles.append(self.blocks[index].register_forward_hook(record))
        try:
            output = self.model(**encoded, use_cache=False)
        finally:
            for hook in handles:
                hook.remove()
        logits = output.logits[0, -1].float()
        metadata = {
            "prompt_hash": hashlib.sha256(prompt.encode()).hexdigest(),
            "input_tokens": encoded.input_ids.shape[1], "cache_enabled": False,
            "activations": {str(index): stats(tensor) for index, tensor in captured.items()},
            "logits_shape": list(output.logits.shape),
        }
        del output
        return metadata | self.generate(encoded, max_new=max_new), captured, logits

    def first_token(self, value):
        return self.tokenizer.encode(value, add_special_tokens=False)[0]
