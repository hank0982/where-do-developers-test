#!/usr/bin/env python3
"""
Convenience wrapper that runs load_repo_depths.py followed by load_level0.py.

Usage example:
    ./scripts/load_repo_with_level0.py commons-collections \
        --depth-paths java_repos/commons-collections/target/jacoco-pertest \

The script ensures both loaders write to the same SQLite database, so any
method metadata inserted by load_repo_depths is immediately available to the
level-zero aggregation.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List

import load_level0
import load_repo_depths


def build_depths_command(args) -> List[str]:
    cmd = [args.repo_name]
    if args.method_csv:
        cmd += ["--method-csv", str(args.method_csv)]
    cmd += ["--depth-paths", *[str(p) for p in args.depth_paths]]
    if args.sqlite_path:
        cmd += ["--sqlite-path", str(args.sqlite_path)]
    if args.truncate_methods:
        cmd.append("--truncate-methods")
    return cmd


def build_level0_command(args) -> List[str]:
    cmd = [args.repo_name]
    if args.sqlite_path:
        cmd += ["--sqlite-path", str(args.sqlite_path)]
    cmd += [str(p) for p in args.level0_paths]
    return cmd


def run_step(module, argv: List[str]) -> None:
    if not hasattr(module, "main"):
        raise AttributeError(f"{module.__name__} does not expose a main() function")
    name = getattr(module, "__file__", module.__name__)
    print(f"[INFO] Running {name}: {' '.join([name, *argv])}", flush=True)
    previous = sys.argv
    sys.argv = [name, *argv]
    try:
        module.main()
    finally:
        sys.argv = previous


def collect_files(paths: List[Path], pattern: str) -> List[Path]:
    files: List[Path] = []
    for path in paths:
        if path.is_dir():
            files.extend(sorted(path.glob(pattern)))
        elif path.match(pattern):
            files.append(path)
    return files


def delete_file(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        return


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run load_repo_depths.py and load_level0.py sequentially."
    )
    parser.add_argument("repo_name", help="Repository name (e.g., commons-collections).")
    parser.add_argument(
        "--depth-paths",
        nargs="+",
        type=Path,
        required=True,
        help="One or more *.level0.depth.csv files or directories.",
    )
    parser.add_argument(
        "--level0-paths",
        nargs="+",
        type=Path,
        help="One or more *.level0.methodid.csv files or directories (defaults to --depth-paths).",
    )
    parser.add_argument(
        "--method-csv",
        type=Path,
        help="Optional path to scripts/methods-csv/<repo>/method.csv override.",
    )
    parser.add_argument(
        "--sqlite-path",
        type=Path,
        help="SQLite database path (defaults to ./dbs/<repo>.db in each script).",
    )
    parser.add_argument(
        "--truncate-methods",
        action="store_true",
        help="Pass through to load_repo_depths.py to delete existing method rows.",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    if not args.level0_paths:
        args.level0_paths = args.depth_paths
    depth_files = collect_files(args.depth_paths, "*.level0.depth.csv")
    level0_files = collect_files(args.level0_paths, "*.level0.methodid.csv")
    for depth_file in depth_files:
        args.depth_paths = [depth_file]
        run_step(load_repo_depths, build_depths_command(args))
        delete_file(depth_file)
    for level0_file in level0_files:
        args.level0_paths = [level0_file]
        run_step(load_level0, build_level0_command(args))
        delete_file(level0_file)


if __name__ == "__main__":
    main()
