# Codex 2026-11-18. Describe fixed-threshold behavior separately from actual hidden-state interventions.
import argparse
import json
from pathlib import Path
from statistics import median


def read_rows(path):
    return [json.loads(s) for s in path.read_text(encoding="utf-8").splitlines() if s.strip()]


def describe(rows, threshold):
    pos = [r for r in rows if r["positive"]]
    neg = [r for r in rows if not r["positive"]]
    def cell(rows):
        scores = [r["score"] for r in rows]
        return f"{min(scores):.5f} / {median(scores):.5f} / {max(scores):.5f}" if scores else "—"
    tp = sum(r["score"]>=threshold for r in pos)
    fp = sum(r["score"]>=threshold for r in neg)
    return [str(len(rows)),cell(pos),cell(neg),str(tp),str(fp),str(len(neg)-fp),str(len(pos)-tp)]


def main(args):
    dev = json.loads((args.development/"grid.json").read_bytes())
    threshold = dev["selected"]["gate_threshold"]
    val = [dict(score=r["gate_score"],positive=r["positive"]) for r in read_rows(args.development/"gate_validation.jsonl")]
    pred = [r for r in read_rows(args.predictions) if r["condition"]=="STRUCT_GATED"]
    summary = json.loads((args.scores/"score.json").read_bytes())
    def observations(items):
        return [dict(score=r["gate_score"],positive=r["kind"]=="HELDOUT") for r in items]
    split = [("DEV",dev["gate_dev"]),("VALIDATION",val)]
    split.extend((w,observations([r for r in pred if r["world"]==w])) for w in sorted({r["world"] for r in pred}))
    split.append(("HIDDEN",observations(pred)))
    gate = summary["gate_metrics"]
    def rate(num):
        return f"{num:.1%}" if num is not None else "无阳性预测，未定义"
    lines = ["# ONI-01R1 门控分布与实际干预","",
             f"固定阈值为 `{threshold:.9f}`。开发集拟合查询位置的归一化激活方向，以召回率减误触率选择阈值。平分时优先较低误触率。激活低于阈值表示拒绝干预。输入语法解析在门控之外，解析失败时即使打开门控也没有可应用的胶囊。",
             "", "| 数据 | 总数 | 阳性分数 min / median / max | 阴性分数 min / median / max | TP | FP | TN | FN |",
             "|---|---:|---|---|---:|---:|---:|---:|"]
    for title, rows in split:
        lines.append("| "+" | ".join([title,*describe(rows,threshold)])+" |")
    lines += ["",f"隐藏集 precision {rate(gate['precision'])}，recall {rate(gate['recall'])}，FPR {rate(gate['fpr'])}。预注册 P3 要求 recall ≥75% 且 FPR ≤10%，本次{'达到' if summary['p3_prespecified'] else '未达到'}。",
              "", "| 隐藏题型 | 样本数 | 打开门控 | 实际非零干预 |", "|---|---:|---:|---:|"]
    for kind in ("HELDOUT","NEGATIVE","AMBIGUOUS","GENERAL"):
        selected = [r for r in pred if r["kind"]==kind]
        lines.append(f"| {kind} | {len(selected)} | {sum(bool(r['effective_gate']) for r in selected)} | {sum(bool(r['applied_delta']) for r in selected)} |")
    lines += ["","两个世界使用同一组冻结参数。门控只区分适用题和其他题型，答对与否另由完整答案精确匹配计量。",
              "","数据来自结果包的 development/grid.json、development/gate_validation.jsonl、ONI011_PREDICTIONS.jsonl 与 score.json。"]
    args.out.write_text("\n".join(lines)+"\n",encoding="utf-8")


if __name__ == "__main__":
    p=argparse.ArgumentParser()
    for name in ("development","predictions","scores","out"):
        p.add_argument("--"+name,type=Path,required=True)
    main(p.parse_args())
