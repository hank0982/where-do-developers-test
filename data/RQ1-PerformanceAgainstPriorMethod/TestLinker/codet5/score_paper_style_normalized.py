#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Callable


def strip_generics(value: str) -> str:
    out: list[str] = []
    depth = 0
    for char in value:
        if char == "<":
            depth += 1
            continue
        if char == ">":
            depth = max(0, depth - 1)
            continue
        if depth == 0:
            out.append(char)
    return "".join(out)


def normalize_type(raw: str) -> str:
    value = strip_generics(raw.strip().replace("...", "[]"))
    value = re.sub(r"^\?\s*(extends|super)\s+", "", value)
    value = value.strip()
    if value == "?":
        value = "Object"

    suffix = ""
    while value.endswith("[]"):
        suffix += "[]"
        value = value[:-2].strip()

    if "." in value:
        value = value.split(".")[-1]

    return f"{value}{suffix}"


def normalize_signature(raw: str) -> str:
    value = raw.strip()
    if "(" not in value or not value.endswith(")"):
        return value

    head, params = value.split("(", 1)
    params = params[:-1]
    params_no_generics = strip_generics(params)
    parts = []
    if params_no_generics.strip():
        parts = [normalize_type(part) for part in params_no_generics.split(",") if part.strip()]
    return f"{head.strip()}({', '.join(parts)})"


def load_predictions(prediction_dir: Path) -> dict[str, dict[str, list[str]]]:
    predictions: dict[str, dict[str, list[str]]] = {}
    for path in sorted(prediction_dir.glob("*_detail.json")):
        project = path.name.replace("_detail.json", "")
        rows: dict[str, list[str]] = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            rows[str(row["id"])] = row.get("recom_signatures", []) or []
        predictions[project] = rows
    return predictions


def gold_original(data: dict[str, object]) -> list[str]:
    return list(data.get("label", []) or [])


def gold_suggested(data: dict[str, object]) -> list[str]:
    suggestions = data.get("label_suggestions", []) or []
    if not suggestions:
        return list(data.get("label", []) or [])
    labels: list[str] = []
    for item in suggestions:
        if item.get("effective_exclude"):
            continue
        value = item.get("suggested_label") or item.get("old_label")
        if value and value not in labels:
            labels.append(value)
    return labels


def gold_db_human(data: dict[str, object]) -> list[str]:
    return list(data.get("current_db_human_annotation", []) or [])


def score(
    dataset_root: Path,
    predictions: dict[str, dict[str, list[str]]],
    gold_loader: Callable[[dict[str, object]], list[str]],
) -> dict[str, float | int]:
    tp = 0
    pred_total = 0
    gold_total = 0
    case_count = 0

    for project_dir in sorted(path for path in dataset_root.iterdir() if path.is_dir() and not path.name.startswith("_")):
        project = project_dir.name
        project_predictions = predictions.get(project, {})
        for json_path in sorted(project_dir.glob("*.json")):
            data = json.loads(json_path.read_text(encoding="utf-8"))
            case_count += 1
            gold = [normalize_signature(sig) for sig in gold_loader(data)]
            pred = [normalize_signature(sig) for sig in project_predictions.get(str(data["id"]), [])]
            gold_total += len(gold)
            pred_total += len(pred)
            gold_set = set(gold)
            tp += sum(1 for sig in pred if sig in gold_set)

    precision = tp / pred_total if pred_total else 0.0
    recall = tp / gold_total if gold_total else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "case_count": case_count,
        "tp": tp,
        "pred_total": pred_total,
        "gold_total": gold_total,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Rescore paper-style old-tech predictions after canonicalizing signatures.")
    parser.add_argument("--dataset-root", required=True)
    parser.add_argument("--prediction-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    dataset_root = Path(args.dataset_root).resolve()
    prediction_dir = Path(args.prediction_dir).resolve()
    output = Path(args.output).resolve()

    predictions = load_predictions(prediction_dir)
    results = {
        "dataset_root": str(dataset_root),
        "prediction_dir": str(prediction_dir),
        "normalization": {
            "owner_and_method": "unchanged",
            "parameter_types": "strip package qualification, strip generics, keep array suffixes",
        },
        "scores": {
            "original": score(dataset_root, predictions, gold_original),
            "suggested": score(dataset_root, predictions, gold_suggested),
            "db_human": score(dataset_root, predictions, gold_db_human),
        },
    }
    output.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
