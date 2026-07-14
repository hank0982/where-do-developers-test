#!/usr/bin/env python3
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DBS_DIR = ROOT / "dbs"


def discover_model_outputs(repo: str) -> dict[str, list[Path]]:
    base = DBS_DIR / f"batch_input_{repo}"
    if not base.exists():
        raise FileNotFoundError(f"Missing batch input directory: {base}")

    found: dict[str, list[Path]] = {"gemini": [], "deepseek": [], "qwen": []}
    for path in sorted(base.glob("*/output.compressed.jsonl")):
        parent = path.parent.name.lower()
        if "gemini" in parent:
            found["gemini"].append(path)
        elif "deepseek" in parent:
            found["deepseek"].append(path)
        elif "qwen" in parent:
            found["qwen"].append(path)

    return found


def parse_output_overrides(values: list[str]) -> dict[tuple[str, str], Path]:
    overrides: dict[tuple[str, str], Path] = {}
    for value in values:
        try:
            repo, model, raw_path = value.split(":", 2)
        except ValueError as exc:
            raise RuntimeError(
                f"Invalid --output value {value!r}; expected repo:model:path"
            ) from exc
        if model not in {"gemini", "deepseek", "qwen"}:
            raise RuntimeError(f"Invalid model in --output {value!r}: {model}")
        path = Path(raw_path).expanduser().resolve()
        if not path.exists():
            raise RuntimeError(f"Override path does not exist: {path}")
        overrides[(repo, model)] = path
    return overrides


def resolve_model_outputs(
    repo: str,
    overrides: dict[tuple[str, str], Path],
) -> dict[str, Path]:
    discovered = discover_model_outputs(repo)
    resolved: dict[str, Path] = {}
    for model in ("gemini", "deepseek", "qwen"):
        override = overrides.get((repo, model))
        if override is not None:
            resolved[model] = override
            continue
        paths = discovered[model]
        if not paths:
            continue
        if len(paths) > 1:
            raise RuntimeError(
                f"Repo {repo} has multiple {model} outputs; specify manually with "
                f"--output {repo}:{model}:<path>. Candidates: "
                + ", ".join(str(p) for p in paths)
            )
        resolved[model] = paths[0]
    return resolved


def load_test_methods(path: Path) -> dict[int, set[int]]:
    out: dict[int, set[int]] = {}
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            row = json.loads(raw)
            test_id = int(row["test_id"])
            methods: set[int] = set()
            for item in row.get("focal_methods") or []:
                method_id = item.get("method_id")
                if method_id is not None:
                    methods.add(int(method_id))
            out[test_id] = methods
    return out


def per_test_jaccard(
    left: dict[int, set[int]],
    right: dict[int, set[int]],
) -> tuple[float, int]:
    scores: list[float] = []
    for test_id in sorted(set(left) | set(right)):
        lset = left.get(test_id, set())
        rset = right.get(test_id, set())
        union = lset | rset
        if not union:
            continue
        scores.append(len(lset & rset) / len(union))
    if not scores:
        return 1.0, 0
    return sum(scores) / len(scores), len(scores)


def per_test_set_agreement_stats(
    left: dict[int, set[int]],
    right: dict[int, set[int]],
) -> tuple[float, float, int]:
    exact_matches = 0
    one_method_diff = 0
    counted = 0
    for test_id in sorted(set(left) | set(right)):
        lset = left.get(test_id, set())
        rset = right.get(test_id, set())
        if not (lset or rset):
            continue
        counted += 1
        sym_diff = len(lset ^ rset)
        if sym_diff == 0:
            exact_matches += 1
        elif sym_diff == 1:
            one_method_diff += 1
    if not counted:
        return 1.0, 0.0, 0
    return exact_matches / counted, one_method_diff / counted, counted


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Compute per-test Jaccard similarity between Gemini, DeepSeek, and Qwen "
            "using focal-method sets from output.compressed.jsonl."
        )
    )
    parser.add_argument(
        "--repos",
        nargs="+",
        required=True,
        help="Repo names, e.g. commons-lang gson",
    )
    parser.add_argument(
        "--output",
        action="append",
        default=[],
        help="Manual output override in repo:model:path form. Repeatable.",
    )
    args = parser.parse_args()

    overrides = parse_output_overrides(args.output)
    repo_data: dict[str, dict[str, dict[int, set[int]]]] = {}
    for repo in args.repos:
        outputs = resolve_model_outputs(repo, overrides)
        missing = [m for m in ("gemini", "deepseek", "qwen") if m not in outputs]
        if missing:
            raise RuntimeError(f"Repo {repo} is missing model outputs: {', '.join(missing)}")
        repo_data[repo] = {model: load_test_methods(path) for model, path in outputs.items()}

    pair_scores: dict[tuple[str, str], tuple[float, float, float, int]] = {}
    print("Per-repo pairwise per-test Jaccard")
    for repo in args.repos:
        print(f"\n== {repo} ==")
        loaded = repo_data[repo]
        repo_pair_scores: dict[tuple[str, str], float] = {}
        for left_model, right_model in itertools.combinations(("gemini", "deepseek", "qwen"), 2):
            score, count = per_test_jaccard(loaded[left_model], loaded[right_model])
            exact_ratio, one_diff_ratio, _ = per_test_set_agreement_stats(
                loaded[left_model],
                loaded[right_model],
            )
            exact_or_one_ratio = exact_ratio + one_diff_ratio
            repo_pair_scores[(left_model, right_model)] = score
            print(
                f"{left_model} vs {right_model}: "
                f"jaccard={score:.6f}, "
                f"exact={exact_ratio:.6f}, "
                f"one_method_diff={one_diff_ratio:.6f}, "
                f"exact_or_one_diff={exact_or_one_ratio:.6f} "
                f"(over {count} tests)"
            )
        print(
            "repo average across pairs: "
            f"{sum(repo_pair_scores.values()) / len(repo_pair_scores):.6f} "
            f"(over {len(repo_pair_scores)} pairs)"
        )

    print("\nCombined pairwise per-test Jaccard")
    for left_model, right_model in itertools.combinations(("gemini", "deepseek", "qwen"), 2):
        all_left: dict[tuple[str, int], set[int]] = {}
        all_right: dict[tuple[str, int], set[int]] = {}
        for repo in args.repos:
            for test_id, methods in repo_data[repo][left_model].items():
                all_left[(repo, test_id)] = methods
            for test_id, methods in repo_data[repo][right_model].items():
                all_right[(repo, test_id)] = methods
        score, count = per_test_jaccard(all_left, all_right)
        exact_ratio, one_diff_ratio, _ = per_test_set_agreement_stats(all_left, all_right)
        exact_or_one_ratio = exact_ratio + one_diff_ratio
        pair_scores[(left_model, right_model)] = (
            score,
            exact_ratio,
            one_diff_ratio,
            exact_or_one_ratio,
            count,
        )
        print(
            f"{left_model} vs {right_model}: "
            f"jaccard={score:.6f}, "
            f"exact={exact_ratio:.6f}, "
            f"one_method_diff={one_diff_ratio:.6f}, "
            f"exact_or_one_diff={exact_or_one_ratio:.6f} "
            f"(over {count} tests)"
        )

    consensus = {
        "gemini": {
            "jaccard": (pair_scores[("gemini", "deepseek")][0] + pair_scores[("gemini", "qwen")][0]) / 2,
            "exact": (pair_scores[("gemini", "deepseek")][1] + pair_scores[("gemini", "qwen")][1]) / 2,
            "one_method_diff": (pair_scores[("gemini", "deepseek")][2] + pair_scores[("gemini", "qwen")][2]) / 2,
            "exact_or_one_diff": (pair_scores[("gemini", "deepseek")][3] + pair_scores[("gemini", "qwen")][3]) / 2,
        },
        "deepseek": {
            "jaccard": (pair_scores[("gemini", "deepseek")][0] + pair_scores[("deepseek", "qwen")][0]) / 2,
            "exact": (pair_scores[("gemini", "deepseek")][1] + pair_scores[("deepseek", "qwen")][1]) / 2,
            "one_method_diff": (pair_scores[("gemini", "deepseek")][2] + pair_scores[("deepseek", "qwen")][2]) / 2,
            "exact_or_one_diff": (pair_scores[("gemini", "deepseek")][3] + pair_scores[("deepseek", "qwen")][3]) / 2,
        },
        "qwen": {
            "jaccard": (pair_scores[("gemini", "qwen")][0] + pair_scores[("deepseek", "qwen")][0]) / 2,
            "exact": (pair_scores[("gemini", "qwen")][1] + pair_scores[("deepseek", "qwen")][1]) / 2,
            "one_method_diff": (pair_scores[("gemini", "qwen")][2] + pair_scores[("deepseek", "qwen")][2]) / 2,
            "exact_or_one_diff": (pair_scores[("gemini", "qwen")][3] + pair_scores[("deepseek", "qwen")][3]) / 2,
        },
    }
    print("\nCombined consensus score by model")
    for model in ("gemini", "deepseek", "qwen"):
        print(
            f"{model}: "
            f"jaccard={consensus[model]['jaccard']:.6f}, "
            f"exact={consensus[model]['exact']:.6f}, "
            f"one_method_diff={consensus[model]['one_method_diff']:.6f}, "
            f"exact_or_one_diff={consensus[model]['exact_or_one_diff']:.6f} "
            f"(over 2 pairs)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
