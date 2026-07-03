#!/usr/bin/env python3
import json
import sqlite3
import sys
from pathlib import Path


def split_test_name(test_name: str) -> tuple[str, str]:
    if "_" not in test_name:
        return test_name, ""
    base = test_name
    parts = base.rsplit("_", 1)
    if len(parts) == 2 and len(parts[1]) == 8 and all(c in "0123456789abcdef" for c in parts[1]):
        base = parts[0]
    class_part, method_part = base.rsplit("_", 1)
    return class_part, method_part


def iter_candidates(db_path: Path) -> list[dict[str, str]]:
    db_uri = f"file:{db_path}?mode=ro"
    conn = sqlite3.connect(db_uri, uri=True)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        """
        select distinct t.test_id,
               t.test_name,
               t.test_method_filename,
               t.line_number
          from testcase t
          join level_zero lz
            on lz.test_id = t.test_id
         where t.test_method_filename is not null
           and t.line_number is not null
        """
    ).fetchall()
    conn.close()

    candidates: list[dict[str, str]] = []
    for row in rows:
        test_id = row["test_id"]
        test_name = row["test_name"]
        test_class, test_method = split_test_name(test_name)
        abs_path = Path(row["test_method_filename"]).resolve()
        if not abs_path.exists() or not test_method:
            continue
        candidates.append(
            {
                "test_id": str(test_id),
                "test_name": test_name,
                "test_class": test_class,
                "test_method": test_method,
                "test_file": str(abs_path),
                "test_absolute_file": str(abs_path),
                "test_line": str(row["line_number"]),
            }
        )
    return candidates


def iter_dbs(paths: list[Path]) -> list[Path]:
    dbs: list[Path] = []
    for path in paths:
        if path.is_dir():
            dbs.extend(sorted(path.glob("*.db")))
        elif path.is_file() and path.suffix == ".db":
            dbs.append(path)
    return dbs


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: extract_llm_samples.py <db path|dir>")
        return 1

    root_dir = Path(__file__).resolve().parent.parent
    dbs_dir = root_dir / "dbs"
    db_paths = iter_dbs([Path(arg) for arg in sys.argv[1:2]])
    if not db_paths:
        print("No .db files found.")
        return 1

    total_all = 0
    for db_path in db_paths:
        output_dir = dbs_dir / f"batch_input_{db_path.stem}"
        output_dir.mkdir(parents=True, exist_ok=True)
        output_path = output_dir / "batch-input.jsonl"
        total = 0
        candidates = iter_candidates(db_path)
        with output_path.open("w", encoding="utf-8") as handle:
            for item in candidates:
                db_uri = f"file:{db_path}?mode=ro"
                conn = sqlite3.connect(db_uri, uri=True)
                conn.row_factory = sqlite3.Row
                invoked = conn.execute(
                    """
                    select lz.method_id,
                           lz.num_of_invoked,
                           ms.class_name,
                           ms.method_name,
                           ms.raw_method
                      from level_zero lz
                      join method_signatures ms
                        on ms.method_id = lz.method_id
                     where lz.test_id = ?
                     order by lz.num_of_invoked desc
                    """,
                    (int(item["test_id"]),),
                ).fetchall()
                conn.close()

                invoked_methods = [
                    {
                        "method_id": int(row["method_id"]),
                        "num_of_invoked": int(row["num_of_invoked"]),
                        "class_name": row["class_name"],
                        "method_name": row["method_name"],
                        "raw_method": row["raw_method"],
                    }
                    for row in invoked
                ]

                file_content = Path(item["test_absolute_file"]).read_text(
                    encoding="utf-8", errors="replace"
                )

                record = {
                    "repo_name": db_path.stem,
                    "db_path": str(db_path),
                    "test_id": int(item["test_id"]),
                    "test_name": item["test_name"],
                    "test_class": item["test_class"],
                    "test_method": item["test_method"],
                    "test_file": item["test_file"],
                    "test_absolute_file": item["test_absolute_file"],
                    "test_line": item["test_line"],
                    "invoked_methods": invoked_methods,
                    "file_content": file_content,
                }
                handle.write(json.dumps(record) + "\n")
                total += 1
        total_all += total
        print(f"Wrote {total} rows to {output_path}")
    print(f"Wrote {total_all} total rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
