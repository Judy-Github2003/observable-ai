#!/usr/bin/env python3
"""Run one model arm (A zero-history or B raw-retrieval) against public questions.

This runner never reads sealed answers. It writes raw model output, parsed
answers and prompt hashes for audit. Scoring is done separately.
"""
import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path

from prompts import parse_answer, render_messages


def post_chat(base_url, model, messages, num_predict, think, timeout=240):
    payload = {
        "model": model,
        "stream": False,
        "messages": messages,
        "options": {"temperature": 0, "num_ctx": 4096, "num_predict": num_predict},
    }
    if not think:
        # MiniCPM5 has a long hidden thinking channel; for a bounded A/B pilot we
        # request the direct answer so a small num_predict is not consumed by it.
        payload["think"] = False
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/chat",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.time()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body, round((time.time() - started) * 1000)


def read_jsonl(path):
    rows = []
    with open(path, encoding="utf-8-sig") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def write_jsonl(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--study", required=True)
    parser.add_argument("--questions", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--arm", choices=["A", "B"], required=True)
    parser.add_argument("--model", default="openbmb/minicpm5-2b:latest")
    parser.add_argument("--base-url", default="http://127.0.0.1:11434")
    parser.add_argument("--num-predict", type=int, default=512)
    parser.add_argument("--think", action="store_true")
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    studies = {row["world_id"]: row for row in read_jsonl(args.study)}
    questions = read_jsonl(args.questions)
    if args.limit:
        questions = questions[: args.limit]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    predictions = []
    traces = []
    prompts = []
    failures = 0
    for index, query in enumerate(questions, 1):
        study = studies[query["world_id"]]
        messages = render_messages(study, query, args.arm)
        prompt_text = "\n".join(message["content"] for message in messages)
        prompt_sha = hashlib.sha256(prompt_text.encode("utf-8")).hexdigest()
        started = time.time()
        try:
            body, latency_ms = post_chat(args.base_url, args.model, messages, args.num_predict, args.think)
            message = body.get("message") or {}
            content = message.get("content") or ""
            thinking = message.get("thinking") or ""
            parsed = parse_answer(study["domain"], content)
            error = None
            if parsed is None:
                failures += 1
                error = "EMPTY_CONTENT" if not content.strip() else "PARSE_FAILED"
        except Exception as exc:  # network/runtime error; keep the record
            body = {}
            content = ""
            thinking = ""
            parsed = None
            latency_ms = round((time.time() - started) * 1000)
            failures += 1
            error = "MODEL_ERROR: " + str(exc)

        predictions.append({
            "query_id": query["query_id"],
            "answer": parsed,
            "arm": args.arm,
            "world_id": query["world_id"],
            "domain": query["domain"],
        })
        prompts.append({
            "query_id": query["query_id"],
            "prompt_sha256": prompt_sha,
            "messages": messages,
        })
        traces.append({
            "query_id": query["query_id"],
            "world_id": query["world_id"],
            "domain": query["domain"],
            "arm": args.arm,
            "model": args.model,
            "prompt_sha256": prompt_sha,
            "response_content": content,
            "thinking_chars": len(thinking),
            "parsed_answer": parsed,
            "error": error,
            "latency_ms": latency_ms,
            "prompt_eval_count": body.get("prompt_eval_count"),
            "eval_count": body.get("eval_count"),
            "done_reason": body.get("done_reason"),
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        })
        print(f"[{args.arm}] {index}/{len(questions)} {query['query_id']} -> {parsed!r} ({latency_ms} ms)")

    write_jsonl(out / "predictions.jsonl", predictions)
    write_jsonl(out / "traces.jsonl", traces)
    write_jsonl(out / "prompts.jsonl", prompts)
    digest = None
    try:
        with urllib.request.urlopen(args.base_url.rstrip("/") + "/api/tags", timeout=30) as response:
            tags = json.loads(response.read().decode("utf-8"))
        row = next((m for m in tags.get("models", []) if m.get("name") == args.model), None)
        digest = row.get("digest") if row else None
    except Exception:
        pass
    summary = {
        "arm": args.arm,
        "model": args.model,
        "model_digest": digest,
        "num_questions": len(questions),
        "parse_failures": failures,
        "num_predict": args.num_predict,
        "think": bool(args.think),
        "temperature": 0,
        "num_ctx": 4096,
        "prompt_hashes_sha256": hashlib.sha256(
            "".join(sorted(p["prompt_sha256"] for p in prompts)).encode()
        ).hexdigest(),
    }
    (out / "run.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
