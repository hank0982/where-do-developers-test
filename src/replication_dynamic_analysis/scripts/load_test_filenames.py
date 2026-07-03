#!/usr/bin/env python3
import argparse
import sqlite3
from pathlib import Path


def ensure_column(conn: sqlite3.Connection) -> None:
    columns = {row[1] for row in conn.execute("PRAGMA table_info(testcase)")}
    if "test_filename" not in columns:
        conn.execute("ALTER TABLE testcase ADD COLUMN test_filename TEXT")
        conn.commit()


def extract_test_name(path: Path) -> str:
    name = path.name
    suffix = ".test_filename.txt"
    if name.endswith(suffix):
        return name[: -len(suffix)]
    return path.stem


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Load *.test_filename.txt into testcase.test_filename."
    )
    parser.add_argument("db_path", help="SQLite DB path (e.g., dbs/commons-cli.db).")
    parser.add_argument(
        "input_paths",
        nargs="+",
        help="Directories or files containing *.test_filename.txt.",
    )
    args = parser.parse_args()

    db_path = Path(args.db_path)
    if not db_path.exists():
        raise FileNotFoundError(f"DB not found: {db_path}")

    files: list[Path] = []
    for raw in args.input_paths:
        path = Path(raw)
        if path.is_dir():
            files.extend(sorted(path.rglob("*.test_filename.txt")))
        elif path.is_file() and path.name.endswith(".test_filename.txt"):
            files.append(path)
        else:
            raise FileNotFoundError(f"Input not found or invalid: {path}")
    if not files:
        raise FileNotFoundError("No *.test_filename.txt files found.")

    conn = sqlite3.connect(db_path)
    try:
        ensure_column(conn)
        updated = 0
        for file_path in files:
            test_name = extract_test_name(file_path)
            content = file_path.read_text(encoding="utf-8", errors="replace").strip()
            if not content:
                continue
            cur = conn.execute(
                "UPDATE testcase SET test_filename = ? WHERE test_name = ?",
                (content, test_name),
            )
            updated += cur.rowcount
        conn.commit()
    finally:
        conn.close()

    print(f"Updated {updated} rows in {db_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
