#!/usr/bin/env python3
"""
Ingest *.level0.methodid.csv files into SQLite tables.

For each test-level file we:
  1. Record the test name in the testcase table (test_id auto-increment).
  2. Count how many times each method_id appears.
  3. Store the aggregate counts in level_zero (test_id, method_id, num_of_invoked).
"""

import argparse
import sqlite3
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

LEVEL0_SUFFIX = ".level0.methodid.csv"

TESTCASE_SCHEMA = """
CREATE TABLE IF NOT EXISTS testcase (
    test_id INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name TEXT UNIQUE NOT NULL
)
"""

LEVEL_ZERO_SCHEMA = """
CREATE TABLE IF NOT EXISTS level_zero (
    test_id INTEGER NOT NULL,
    method_id INTEGER NOT NULL,
    num_of_invoked INTEGER NOT NULL,
    PRIMARY KEY (test_id, method_id)
)
"""


def collect_level0_files(paths: Iterable[Path]) -> List[Path]:
    files: List[Path] = []
    for entry in paths:
        if entry.is_dir():
            files.extend(sorted(entry.rglob(f"*{LEVEL0_SUFFIX}")))
        elif entry.is_file() and entry.name.endswith(LEVEL0_SUFFIX):
            files.append(entry)
        else:
            raise FileNotFoundError(f"Level0 path not found or invalid: {entry}")
    if not files:
        raise FileNotFoundError(f"No *{LEVEL0_SUFFIX} files found.")
    return files


def parse_level0_line(line: str) -> Optional[int]:
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    parts = line.split(",", 1)
    if not parts:
        return None
    try:
        return int(parts[0])
    except ValueError:
        return None


def extract_test_name(path: Path) -> str:
    name = path.name
    suffix = LEVEL0_SUFFIX
    if name.endswith(suffix):
        return name[: -len(suffix)]
    return path.stem


def get_or_create_test_id(conn: sqlite3.Connection, test_name: str) -> int:
    cur = conn.cursor()
    cur.execute("SELECT test_id FROM testcase WHERE test_name = ?", (test_name,))
    row = cur.fetchone()
    if row:
        return row[0]
    cur.execute("INSERT INTO testcase(test_name) VALUES (?)", (test_name,))
    conn.commit()
    return cur.lastrowid


def main():
    parser = argparse.ArgumentParser(
        description="Load *.level0.methodid.csv files into SQLite summary tables."
    )
    parser.add_argument("repo_name", help="Repository name associated with the method signatures.")
    parser.add_argument(
        "paths",
        nargs="+",
        type=Path,
        help="One or more *.level0.methodid.csv files or directories containing them.",
    )
    parser.add_argument(
        "--sqlite-path",
        type=Path,
        help="SQLite database path (defaults to ./dbs/<repo>.db).",
    )
    args = parser.parse_args()

    repo = args.repo_name
    level0_files = collect_level0_files(args.paths)
    project_root = Path(__file__).resolve().parent.parent
    db_dir = project_root / "dbs"
    db_dir.mkdir(parents=True, exist_ok=True)
    sqlite_path = args.sqlite_path or (db_dir / f"{repo}.db")

    conn = sqlite3.connect(sqlite_path)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=OFF;")
    conn.execute(TESTCASE_SCHEMA)
    conn.execute(LEVEL_ZERO_SCHEMA)

    insert_sql = """
        INSERT INTO level_zero(test_id, method_id, num_of_invoked)
        VALUES (?, ?, ?)
        ON CONFLICT(test_id, method_id) DO UPDATE SET num_of_invoked = excluded.num_of_invoked
    """
    cur = conn.cursor()

    for level0_file in level0_files:
        test_name = extract_test_name(level0_file)
        test_id = get_or_create_test_id(conn, test_name)
        counts: Dict[int, int] = defaultdict(int)
        with level0_file.open("r", encoding="utf-8") as handle:
            for line in handle:
                method_id = parse_level0_line(line)
                if method_id is None:
                    continue
                if method_id < 0:
                    continue
                counts[method_id] += 1
        if counts:
            cur.executemany(
                insert_sql,
                [(test_id, method_id, count) for method_id, count in counts.items()],
            )
            conn.commit()
        level0_file.unlink(missing_ok=True)
        print(f"[INFO] Processed {level0_file}", flush=True)

    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    conn.close()
    print(f"[INFO] Loaded {len(level0_files)} level0 files into {sqlite_path}")


if __name__ == "__main__":
    main()
