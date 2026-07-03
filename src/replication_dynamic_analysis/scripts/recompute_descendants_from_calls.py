#!/usr/bin/env python3
"""
Recompute descendant counts inside the calls table without reprocessing depth files.

For each test in calls (grouped by repo_name) the script rebuilds the call tree,
counts how many descendants hang off each call_id, and updates calls.descendant_count.
"""

from __future__ import annotations

import argparse
import sqlite3
from collections import defaultdict
from pathlib import Path
from typing import Dict, Iterable, Optional, Tuple


Row = Tuple[str, Optional[str], int]


def fetch_tests(
    conn: sqlite3.Connection,
    repo: str,
    start_test_id: Optional[int] = None,
) -> Iterable[int]:
    if start_test_id is None:
        query = "SELECT DISTINCT test_id FROM calls WHERE repo_name=? ORDER BY test_id"
        params = (repo,)
    else:
        query = (
            "SELECT DISTINCT test_id FROM calls "
            "WHERE repo_name=? AND test_id >= ? ORDER BY test_id"
        )
        params = (repo, start_test_id)
    cur = conn.execute(query, params)
    for row in cur:
        yield row[0]


def count_calls_for_test(conn: sqlite3.Connection, repo: str, test_id: int) -> int:
    cur = conn.execute(
        """
        SELECT COUNT(*) FROM calls
        WHERE repo_name = ? AND test_id = ?
        """,
        (repo, test_id),
    )
    result = cur.fetchone()
    return int(result[0]) if result else 0


def update_counts_streaming(
    conn: sqlite3.Connection,
    repo: str,
    test_id: int,
) -> int:
    """
    Update descendant counts for a single test while only keeping the active
    ancestor chain in memory. Rows are processed in reverse depth order so that
    each parent's descendants have already been computed by the time it is
    visited.
    """

    pending_totals: Dict[str, int] = defaultdict(int)
    updated = 0
    cur = conn.execute(
        """
        SELECT call_id, parent_call_id, depth
        FROM calls
        WHERE repo_name = ? AND test_id = ?
        ORDER BY depth DESC
        """,
        (repo, test_id),
    )
    update_sql = """
        UPDATE calls
        SET descendant_count = ?
        WHERE repo_name = ? AND test_id = ? AND call_id = ?
    """
    for call_id, parent_id, _depth in cur:
        total = pending_totals.pop(call_id, 0)
        conn.execute(update_sql, (total, repo, test_id, call_id))
        if parent_id is not None:
            pending_totals[parent_id] += total + 1
        updated += 1
    conn.commit()
    return updated


def recompute_for_repo(
    conn: sqlite3.Connection,
    repo: str,
    start_test_id: Optional[int] = None,
):
    tests = list(fetch_tests(conn, repo, start_test_id=start_test_id))
    if not tests:
        print(f"[WARN] No calls found for repo '{repo}'.")
        return
    print(
        f"[INFO] Recomputing descendants for {len(tests)} tests in repo '{repo}'",
        flush=True,
    )
    for idx, test_id in enumerate(tests, start=1):
        call_count = count_calls_for_test(conn, repo, test_id)
        if call_count == 0:
            continue
        if idx % 10 == 0 or idx == 1:
            print(
                f"[INFO] Processing test_id={test_id} "
                f"({idx}/{len(tests)}), {call_count} calls",
                flush=True,
            )
        update_counts_streaming(conn, repo, test_id)
        if idx % 50 == 0 or idx == len(tests):
            print(f"[INFO] Processed {idx}/{len(tests)} tests", flush=True)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Recompute calls.descendant_count directly from the calls table."
    )
    parser.add_argument("repo_name", help="Repository name stored in calls.repo_name.")
    parser.add_argument(
        "--sqlite-path",
        type=Path,
        help="SQLite database path (defaults to ./dbs/<repo>.db).",
    )
    parser.add_argument(
        "--start-test-id",
        type=int,
        help="Start from this test_id (inclusive) to skip earlier tests.",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    db_path = args.sqlite_path or Path("dbs") / f"{args.repo_name}.db"
    if not db_path.exists():
        raise FileNotFoundError(f"Database not found at {db_path}")
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=OFF;")
    try:
        recompute_for_repo(conn, args.repo_name, start_test_id=args.start_test_id)
    finally:
        conn.close()
    print(f"[INFO] Descendant counts updated in {db_path}")


if __name__ == "__main__":
    main()
