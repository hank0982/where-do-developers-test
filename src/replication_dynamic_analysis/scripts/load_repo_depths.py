#!/usr/bin/env python3
"""
Load a repository's CK method metadata and level0 depth CSVs into SQLite.

Steps:
1. Import scripts/methods-csv/<repo>/method.csv into method_signatures (overwriting prior rows).
2. Record unique test names in testcase and stream *.level0.depth.csv files into calls with repo_name tagging.
3. Skip redundant class/method/descriptor values when a numeric method_id is available (> 0).
"""

import argparse
import csv
import sqlite3
import sys
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

DEPTH_SUFFIXES = (".level0.depth.csv",)

METHOD_COLUMNS = [
    "method_id",
    "repo_name",
    "class_name",
    "method_name",
    "method_signature",
    "raw_method",
    "source_file",
    "is_constructor",
    "line",
    "cbo",
    "cbo_modified",
    "fanin",
    "fanout",
    "wmc",
    "rfc",
    "loc",
    "returns_qty",
    "variables_qty",
    "parameters_qty",
    "methods_invoked_qty",
    "methods_invoked_local_qty",
    "methods_invoked_indirect_local_qty",
    "loop_qty",
    "comparisons_qty",
    "try_catch_qty",
    "parenthesized_exps_qty",
    "string_literals_qty",
    "numbers_qty",
    "assignments_qty",
    "math_operations_qty",
    "max_nested_blocks_qty",
    "anonymous_classes_qty",
    "inner_classes_qty",
    "lambdas_qty",
    "unique_words_qty",
    "modifiers",
    "is_public",
    "is_protected",
    "is_private",
    "is_package",
    "log_statements_qty",
    "has_javadoc",
]

METHOD_TABLE_SCHEMA = f"""
CREATE TABLE IF NOT EXISTS method_signatures (
    method_id INTEGER PRIMARY KEY,
    repo_name TEXT NOT NULL,
    class_name TEXT NOT NULL,
    method_name TEXT NOT NULL,
    method_signature TEXT NOT NULL,
    raw_method TEXT NOT NULL,
    source_file TEXT,
    source_code TEXT,
    is_constructor INTEGER NOT NULL,
    line INTEGER,
    cbo REAL,
    cbo_modified REAL,
    fanin REAL,
    fanout REAL,
    wmc REAL,
    rfc REAL,
    loc REAL,
    returns_qty REAL,
    variables_qty REAL,
    parameters_qty REAL,
    methods_invoked_qty REAL,
    methods_invoked_local_qty REAL,
    methods_invoked_indirect_local_qty REAL,
    loop_qty REAL,
    comparisons_qty REAL,
    try_catch_qty REAL,
    parenthesized_exps_qty REAL,
    string_literals_qty REAL,
    numbers_qty REAL,
    assignments_qty REAL,
    math_operations_qty REAL,
    max_nested_blocks_qty REAL,
    anonymous_classes_qty REAL,
    inner_classes_qty REAL,
    lambdas_qty REAL,
    unique_words_qty REAL,
    modifiers TEXT,
    is_public INTEGER,
    is_protected INTEGER,
    is_private INTEGER,
    is_package INTEGER,
    log_statements_qty REAL,
    has_javadoc INTEGER
)
"""

INSERT_COLUMNS = ",\n    ".join(METHOD_COLUMNS)
INSERT_PLACEHOLDERS = ", ".join("?" for _ in METHOD_COLUMNS)
METHOD_INSERT_SQL = f"""
INSERT INTO method_signatures (
    {INSERT_COLUMNS}
) VALUES ({INSERT_PLACEHOLDERS})
ON CONFLICT(method_id)
DO UPDATE SET
    repo_name=excluded.repo_name,
    raw_method=excluded.raw_method,
    source_file=excluded.source_file,
    is_constructor=excluded.is_constructor,
    line=excluded.line,
    cbo=excluded.cbo,
    cbo_modified=excluded.cbo_modified,
    fanin=excluded.fanin,
    fanout=excluded.fanout,
    wmc=excluded.wmc,
    rfc=excluded.rfc,
    loc=excluded.loc,
    returns_qty=excluded.returns_qty,
    variables_qty=excluded.variables_qty,
    parameters_qty=excluded.parameters_qty,
    methods_invoked_qty=excluded.methods_invoked_qty,
    methods_invoked_local_qty=excluded.methods_invoked_local_qty,
    methods_invoked_indirect_local_qty=excluded.methods_invoked_indirect_local_qty,
    loop_qty=excluded.loop_qty,
    comparisons_qty=excluded.comparisons_qty,
    try_catch_qty=excluded.try_catch_qty,
    parenthesized_exps_qty=excluded.parenthesized_exps_qty,
    string_literals_qty=excluded.string_literals_qty,
    numbers_qty=excluded.numbers_qty,
    assignments_qty=excluded.assignments_qty,
    math_operations_qty=excluded.math_operations_qty,
    max_nested_blocks_qty=excluded.max_nested_blocks_qty,
    anonymous_classes_qty=excluded.anonymous_classes_qty,
    inner_classes_qty=excluded.inner_classes_qty,
    lambdas_qty=excluded.lambdas_qty,
    unique_words_qty=excluded.unique_words_qty,
    modifiers=excluded.modifiers,
    is_public=excluded.is_public,
    is_protected=excluded.is_protected,
    is_private=excluded.is_private,
    is_package=excluded.is_package,
    log_statements_qty=excluded.log_statements_qty,
    has_javadoc=excluded.has_javadoc
"""

CALLS_SCHEMA = """
CREATE TABLE IF NOT EXISTS calls (
    repo_name TEXT NOT NULL,
    test_id INTEGER NOT NULL,
    call_id TEXT NOT NULL,
    parent_call_id TEXT,
    parent_method_id TEXT,
    depth INTEGER NOT NULL,
    method_id INTEGER,
    class_name TEXT,
    method_name TEXT,
    descriptor TEXT,
    descendant_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (repo_name, test_id, call_id)
)
"""

CALLS_INSERT_SQL = """
INSERT OR REPLACE INTO calls (
    repo_name,
    test_id,
    call_id,
    parent_call_id,
    parent_method_id,
    depth,
    method_id,
    class_name,
    method_name,
    descriptor
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

TESTCASE_SCHEMA = """
CREATE TABLE IF NOT EXISTS testcase (
    test_id INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name TEXT UNIQUE NOT NULL
)
"""


def ensure_method_table_schema(conn: sqlite3.Connection):
    cur = conn.cursor()
    cur.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='method_signatures'"
    )
    row = cur.fetchone()
    if row and row[0]:
        schema_sql = row[0].upper()
        if "UNIQUE" in schema_sql:
            cur.execute("DROP TABLE IF EXISTS method_signatures")
            conn.commit()
    cur.execute("PRAGMA table_info(method_signatures)")
    columns = {info[1] for info in cur.fetchall()}
    if columns and "source_code" not in columns:
        cur.execute("ALTER TABLE method_signatures ADD COLUMN source_code TEXT")
        conn.commit()


def ensure_calls_table_schema(conn: sqlite3.Connection):
    cur = conn.cursor()
    cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='calls'"
    )
    if cur.fetchone():
        cur.execute("PRAGMA table_info(calls)")
        columns = {row[1] for row in cur.fetchall()}
        if "test_id" not in columns:
            cur.execute("DROP TABLE IF EXISTS calls")
            conn.commit()


def float_or_none(value: Optional[str]) -> Optional[float]:
    if value is None:
        return None
    text = value.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def parse_method_field(raw: str) -> Tuple[str, str, str]:
    if raw is None:
        return "", "", ""
    text = raw.strip().strip('"')
    if not text:
        return "", "", ""
    name_part, _, rest = text.partition("/")
    method_name = name_part.strip()
    arity = ""
    params = ""
    if rest:
        if "[" in rest and rest.endswith("]"):
            arity_part, params_part = rest.split("[", 1)
            arity = arity_part.strip()
            params = params_part[:-1].strip()
        else:
            arity = rest.strip()
    return method_name, arity, params


def get_or_create_test_id(
    conn: sqlite3.Connection, test_name: str, cache: Dict[str, int]
) -> int:
    if test_name in cache:
        return cache[test_name]
    cur = conn.cursor()
    cur.execute("SELECT test_id FROM testcase WHERE test_name = ?", (test_name,))
    row = cur.fetchone()
    if row:
        cache[test_name] = row[0]
        return row[0]
    cur.execute("INSERT INTO testcase(test_name) VALUES (?)", (test_name,))
    test_id = cur.lastrowid
    cache[test_name] = test_id
    return test_id


def iter_method_rows(repo: str, method_csv: Path):
    with method_csv.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row_number, row in enumerate(reader, start=1):
            class_name = row.get("class", "").strip()
            raw_method = row.get("method", "").strip()
            method_name, method_signature, descriptor = parse_method_field(raw_method)
            constructor = 1 if row.get("constructor", "").strip().lower() == "true" else 0
            visibility = row.get("visibility", "").strip().lower()
            is_public = 1 if visibility == "public" else 0
            is_protected = 1 if visibility == "protected" else 0
            is_private = 1 if visibility == "private" else 0
            is_package = 1 if visibility == "package" else 0
            line_str = row.get("line", "").strip()
            try:
                line = int(line_str) if line_str else None
            except ValueError:
                line = None
            yield (
                row_number,
                repo,
                class_name,
                method_name,
                method_signature or descriptor or "",
                raw_method,
                row.get("file", "").strip(),
                constructor,
                line,
                float_or_none(row.get("cbo")),
                float_or_none(row.get("cboModified")),
                float_or_none(row.get("fanin")),
                float_or_none(row.get("fanout")),
                float_or_none(row.get("wmc")),
                float_or_none(row.get("rfc")),
                float_or_none(row.get("loc")),
                float_or_none(row.get("returnsQty")),
                float_or_none(row.get("variablesQty")),
                float_or_none(row.get("parametersQty")),
                float_or_none(row.get("methodsInvokedQty")),
                float_or_none(row.get("methodsInvokedLocalQty")),
                float_or_none(row.get("methodsInvokedIndirectLocalQty")),
                float_or_none(row.get("loopQty")),
                float_or_none(row.get("comparisonsQty")),
                float_or_none(row.get("tryCatchQty")),
                float_or_none(row.get("parenthesizedExpsQty")),
                float_or_none(row.get("stringLiteralsQty")),
                float_or_none(row.get("numbersQty")),
                float_or_none(row.get("assignmentsQty")),
                float_or_none(row.get("mathOperationsQty")),
                float_or_none(row.get("maxNestedBlocksQty")),
                float_or_none(row.get("anonymousClassesQty")),
                float_or_none(row.get("innerClassesQty")),
                float_or_none(row.get("lambdasQty")),
                float_or_none(row.get("uniqueWordsQty")),
                row.get("modifiers", "").strip(),
                is_public,
                is_protected,
                is_private,
                is_package,
                float_or_none(row.get("logStatementsQty")),
                1 if row.get("hasJavaDoc", "").strip().lower() == "true" else 0,
            )


def iter_depth_rows(repo: str, path: Path):
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        for row in reader:
            if not row:
                continue
            row += [""] * (9 - len(row))
            call_id = row[0].strip()
            parent_call_id = row[1].strip() or None
            parent_method_id = row[2].strip() or None
            depth = int(row[3]) if row[3] else 0
            method_id_raw = row[4].strip()
            class_name = row[5].strip()
            method_name = row[6].strip()
            descriptor = row[7].strip()
            test_name = row[8].strip()
            method_id: Optional[int] = None
            if method_id_raw:
                try:
                    method_id = int(method_id_raw)
                except ValueError:
                    method_id = None
            if method_id and method_id > 0:
                class_name = ""
                method_name = ""
                descriptor = ""
            yield (
                repo,
                test_name,
                call_id,
                parent_call_id,
                parent_method_id,
                depth,
                method_id,
                class_name,
                method_name,
                descriptor,
            )


def collect_depth_files(inputs: Iterable[Path]) -> List[Path]:
    files: List[Path] = []
    for entry in inputs:
        if entry.is_dir():
            for suffix in DEPTH_SUFFIXES:
                files.extend(sorted(entry.rglob(f"*{suffix}")))
        elif entry.is_file():
            if not any(entry.name.endswith(suffix) for suffix in DEPTH_SUFFIXES):
                continue
            files.append(entry)
        else:
            raise FileNotFoundError(f"Depth path not found: {entry}")
    if not files:
        raise FileNotFoundError("No *.level0.depth.csv files found.")
    return files


def main():
    parser = argparse.ArgumentParser(
        description="Load CK method metadata and depth CSVs into SQLite."
    )
    parser.add_argument("repo_name", help="Repository name (e.g. jsoup).")
    parser.add_argument(
        "--method-csv",
        type=Path,
        help="Path to method.csv (defaults to scripts/methods-csv/<repo>/method.csv).",
    )
    parser.add_argument(
        "--depth-paths",
        nargs="+",
        type=Path,
        required=True,
        help="One or more depth CSV files or directories containing them.",
    )
    parser.add_argument(
        "--sqlite-path",
        type=Path,
        help="SQLite DB path (defaults to ./dbs/<repo>.db).",
    )
    parser.add_argument(
        "--truncate-methods",
        action="store_true",
        help="Drop and recreate method_signatures before loading.",
    )
    args = parser.parse_args()

    repo = args.repo_name
    project_root = Path(__file__).resolve().parent.parent
    method_csv = args.method_csv or project_root / "scripts" / "methods-csv" / repo / "method.csv"
    if not method_csv.exists():
        raise FileNotFoundError(f"method.csv not found at {method_csv}")

    depth_files = collect_depth_files(args.depth_paths)
    db_dir = project_root / "dbs"
    db_dir.mkdir(parents=True, exist_ok=True)
    sqlite_path = args.sqlite_path or (db_dir / f"{repo}.db")

    conn = sqlite3.connect(sqlite_path)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA synchronous=OFF;")

    ensure_method_table_schema(conn)
    conn.execute(METHOD_TABLE_SCHEMA)
    if args.truncate_methods:
        conn.execute("DELETE FROM method_signatures WHERE repo_name = ?", (repo,))
    conn.execute(TESTCASE_SCHEMA)
    ensure_calls_table_schema(conn)
    conn.execute(CALLS_SCHEMA)

    cur = conn.cursor()
    batch: List[Tuple] = []
    for row in iter_method_rows(repo, method_csv):
        batch.append(row)
        if len(batch) >= 5000:
            cur.executemany(METHOD_INSERT_SQL, batch)
            conn.commit()
            batch.clear()
    if batch:
        cur.executemany(METHOD_INSERT_SQL, batch)
        conn.commit()
        batch.clear()

    inserted_calls = 0
    call_batch: List[Tuple] = []
    test_id_cache: Dict[str, int] = {}
    for depth_path in depth_files:
        print(f"[INFO] Loading {depth_path}", file=sys.stderr, flush=True)
        for row in iter_depth_rows(repo, depth_path):
            (
                repo_name,
                test_name,
                call_id,
                parent_call_id,
                parent_method_id,
                depth,
                method_id,
                class_name,
                method_name,
                descriptor,
            ) = row
            test_id = get_or_create_test_id(conn, test_name, test_id_cache)
            call_batch.append(
                (
                    repo_name,
                    test_id,
                    call_id,
                    parent_call_id,
                    parent_method_id,
                    depth,
                    method_id,
                    class_name,
                    method_name,
                    descriptor,
                )
            )
            if len(call_batch) >= 5000:
                cur.executemany(CALLS_INSERT_SQL, call_batch)
                conn.commit()
                inserted_calls += len(call_batch)
                call_batch.clear()
        if call_batch:
            cur.executemany(CALLS_INSERT_SQL, call_batch)
            conn.commit()
            inserted_calls += len(call_batch)
            call_batch.clear()
        depth_path.unlink(missing_ok=True)

    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    conn.close()
    print(f"[INFO] Loaded method metadata and {inserted_calls:,} call rows into {sqlite_path}")


if __name__ == "__main__":
    main()
