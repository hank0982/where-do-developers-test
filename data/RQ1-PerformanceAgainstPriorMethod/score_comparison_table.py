#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from collections import OrderedDict
from pathlib import Path


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
    parts: list[str] = []
    if params_no_generics.strip():
        parts = [normalize_type(part) for part in params_no_generics.split(",") if part.strip()]
    return f"{head.strip()}({', '.join(parts)})"


def load_testlinker_predictions(prediction_dir: Path) -> dict[tuple[str, str], list[str]]:
    predictions: dict[tuple[str, str], list[str]] = {}
    for path in sorted(prediction_dir.glob("*_detail.json")):
        project = path.name.replace("_detail.json", "")
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            predictions[(project, str(row["id"]))] = [
                normalize_signature(sig) for sig in (row.get("recom_signatures") or [])
            ]
    return predictions


def load_cases(path: Path) -> OrderedDict[tuple[str, str], dict[str, object]]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    result: OrderedDict[tuple[str, str], dict[str, object]] = OrderedDict()
    for row in rows["cases"]:
        result[(str(row["project"]), str(row["id"]))] = row
    return result


def score_subset(
    gold_cases: OrderedDict[tuple[str, str], dict[str, object]],
    prediction_lookup: dict[tuple[str, str], list[str]],
    gold_key: str,
    project: str | None = None,
) -> dict[str, float | int]:
    tp = 0
    pred_total = 0
    gold_total = 0
    case_count = 0

    for (case_project, case_id), row in gold_cases.items():
        if project is not None and case_project != project:
            continue
        gold = [normalize_signature(sig) for sig in (row.get(gold_key) or [])]
        pred = prediction_lookup.get((case_project, case_id), [])
        case_count += 1
        pred_total += len(pred)
        gold_total += len(gold)
        gold_set = set(gold)
        tp += sum(1 for sig in pred if sig in gold_set)

    precision = tp / pred_total if pred_total else 0.0
    recall = tp / gold_total if gold_total else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "cases": case_count,
        "tp": tp,
        "pred_total": pred_total,
        "gold_total": gold_total,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def load_callwalker_predictions(
    cases: OrderedDict[tuple[str, str], dict[str, object]],
) -> dict[tuple[str, str], list[str]]:
    result: dict[tuple[str, str], list[str]] = {}
    for key, row in cases.items():
        result[key] = [normalize_signature(sig) for sig in (row.get("machine_annotation_signatures") or [])]
    return result


def fmt(value: float) -> str:
    return f"{value:.2f}"


def build_rows(root: Path, human_label_root: Path) -> list[tuple[str, str, str, str, str]]:
    output_146 = root / "output_146" / "prediction"
    output_500 = root / "output_500" / "prediction"
    callwalker_146_json = root / "callwalker_machine_annotations_146.json"
    callwalker_500_json = root / "callwalker_machine_annotations_500.json"
    gold_146_json = human_label_root / "suggested_label_146.json"
    gold_500_json = human_label_root / "updated_500" / "current_db_human_annotation_500.json"

    gold_146_cases = load_cases(gold_146_json)
    gold_500_cases = load_cases(gold_500_json)
    callwalker_146_cases = load_cases(callwalker_146_json)
    callwalker_500_cases = load_cases(callwalker_500_json)

    tl_146 = load_testlinker_predictions(output_146)
    tl_500 = load_testlinker_predictions(output_500)
    cw_146 = load_callwalker_predictions(callwalker_146_cases)
    cw_500 = load_callwalker_predictions(callwalker_500_cases)

    repo_order = [
        ("commons-lang", "lang"),
        ("commons-io", "io"),
        ("jfreechart", "jfreechart"),
        ("gson", "gson"),
    ]

    rows: list[tuple[str, str, str, str, str]] = []
    for repo_key, repo_label in repo_order:
        tl = score_subset(gold_146_cases, tl_146, "gold_labels", project=repo_key)
        cw = score_subset(gold_146_cases, cw_146, "gold_labels", project=repo_key)
        rows.append((repo_label, "TestLinker", fmt(tl["precision"]), fmt(tl["recall"]), fmt(tl["f1"])))
        rows.append(("", "CallWalker", fmt(cw["precision"]), fmt(cw["recall"]), fmt(cw["f1"])))

    tl146_overall = score_subset(gold_146_cases, tl_146, "gold_labels")
    cw146_overall = score_subset(gold_146_cases, cw_146, "gold_labels")
    tl500_overall = score_subset(gold_500_cases, tl_500, "current_db_human_annotation")
    cw500_overall = score_subset(gold_500_cases, cw_500, "current_db_human_annotation")

    rows.append(("Overall", "TestLinker", fmt(tl146_overall["precision"]), fmt(tl146_overall["recall"]), fmt(tl146_overall["f1"])))
    rows.append(("(Original 146)", "CallWalker", fmt(cw146_overall["precision"]), fmt(cw146_overall["recall"]), fmt(cw146_overall["f1"])))
    rows.append(("Overall", "TestLinker", fmt(tl500_overall["precision"]), fmt(tl500_overall["recall"]), fmt(tl500_overall["f1"])))
    rows.append(("(Updated 500)", "CallWalker", fmt(cw500_overall["precision"]), fmt(cw500_overall["recall"]), fmt(cw500_overall["f1"])))

    return rows


def print_table(rows: list[tuple[str, str, str, str, str]]) -> None:
    headers = ("Project", "Approach", "Precision", "Recall", "F1")
    widths = [len(header) for header in headers]
    for row in rows:
        for idx, value in enumerate(row):
            widths[idx] = max(widths[idx], len(value))

    def sep() -> str:
        return "+-" + "-+-".join("-" * width for width in widths) + "-+"

    def render(row: tuple[str, str, str, str, str]) -> str:
        return "| " + " | ".join(value.ljust(widths[idx]) for idx, value in enumerate(row)) + " |"

    print(sep())
    print(render(headers))
    print(sep())
    for idx, row in enumerate(rows):
        print(render(row))
        if idx in {1, 3, 5, 7, 9}:
            print(sep())


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Print the exact score-comparison table in the terminal from reviewer artifact files."
    )
    parser.add_argument(
        "--root",
        default=str(Path(__file__).resolve().parent),
        help="Root directory containing output_146, output_500, and callwalker_machine_annotations_*.json",
    )
    parser.add_argument(
        "--human-label-root",
        default=str(Path(__file__).resolve().parent.parent / "human-label"),
        help="Directory containing suggested_label_146.json and updated_500/current_db_human_annotation_500.json",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    human_label_root = Path(args.human_label_root).resolve()
    rows = build_rows(root, human_label_root)
    print_table(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
