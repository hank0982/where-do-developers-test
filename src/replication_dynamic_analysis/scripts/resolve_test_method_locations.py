#!/usr/bin/env python3
import argparse
import json
import sqlite3
import subprocess
from pathlib import Path


def ensure_columns(conn: sqlite3.Connection) -> None:
    columns = {row[1] for row in conn.execute("PRAGMA table_info(testcase)")}
    if "test_method_filename" not in columns:
        conn.execute("ALTER TABLE testcase ADD COLUMN test_method_filename TEXT")
    if "line_number" not in columns:
        conn.execute("ALTER TABLE testcase ADD COLUMN line_number INTEGER")
    conn.commit()


def compile_helper(helper_java: Path, class_output: Path, classpath: str) -> None:
    class_file = class_output / "FindTestMethod.class"
    if class_file.exists() and class_file.stat().st_mtime >= helper_java.stat().st_mtime:
        return
    class_output.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["javac", "-cp", classpath, "-d", str(class_output), str(helper_java)],
        check=True,
    )


def run_helper(classpath: str, file_path: str, test_name: str) -> dict:
    try:
        proc = subprocess.run(
            ["java", "-cp", classpath, "FindTestMethod", "--file", file_path, "--test-name", test_name],
            text=True,
            capture_output=True,
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        err = (exc.stderr or "").strip()
        if err:
            err = err.splitlines()[0]
        print(
            f"[warn] FindTestMethod failed for {test_name} ({file_path}): {err or 'exit status 1'}"
        )
        return {}
    raw = proc.stdout.strip()
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def candidate_java_files(repo_root: Path, test_filename: str, test_name: str) -> list[Path]:
    candidates: list[Path] = []
    raw_path = Path(test_filename)
    if raw_path.is_absolute():
        candidates.append(raw_path)
    else:
        candidates.append(repo_root / raw_path)

    def add_class_style_path(class_like_name: str) -> None:
        outer = class_like_name[:-5] if class_like_name.endswith(".java") else class_like_name
        outer = outer.split("$", 1)[0]
        rel = Path(*outer.split(".")).with_suffix(".java")
        candidates.append(repo_root / "src/test/java" / rel)
        candidates.append(repo_root / "src/main/java" / rel)
        candidates.append(repo_root / rel)

    filename_text = test_filename.strip()
    if filename_text.endswith(".java") and "/" not in filename_text and "\\" not in filename_text:
        add_class_style_path(filename_text)

    if test_name:
        base_test_name = test_name.rsplit("_", 1)[0]
        if "_" in base_test_name:
            class_like_name = base_test_name.rsplit("_", 1)[0] + ".java"
            add_class_style_path(class_like_name)

    unique: list[Path] = []
    seen: set[Path] = set()
    for path in candidates:
        resolved = path.resolve(strict=False)
        if resolved in seen:
            continue
        seen.add(resolved)
        unique.append(path)
    return unique


def resolve_test_source_file(repo_root: Path, test_filename: str, test_name: str) -> Path | None:
    for candidate in candidate_java_files(repo_root, test_filename, test_name):
        if candidate.exists():
            return candidate
    return None


def iter_dbs(paths: list[Path]) -> list[Path]:
    dbs: list[Path] = []
    for path in paths:
        if path.is_dir():
            dbs.extend(sorted(path.glob("*.db")))
        elif path.is_file() and path.suffix == ".db":
            dbs.append(path)
    return dbs


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Resolve test method source file/line and store in testcase table."
    )
    parser.add_argument(
        "db_paths",
        nargs="+",
        help="SQLite DB paths or a directory containing *.db files.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Recompute even if test_method_filename already set.",
    )
    parser.add_argument(
        "--repos-root",
        default="java_repos",
        help="Root directory containing repo checkouts (used for relative test_filename).",
    )
    args = parser.parse_args()

    root_dir = Path(__file__).resolve().parent
    helper_java = root_dir / "FindTestMethod.java"
    class_output = root_dir / "bin"
    jar_path = root_dir.parent / "java_test_analyzer" / "target" / "java-test-analyzer-1.0.0.jar"
    classpath = f"{class_output}:{jar_path}"
    compile_helper(helper_java, class_output, classpath)

    db_paths = iter_dbs([Path(p) for p in args.db_paths])
    if not db_paths:
        raise FileNotFoundError("No .db files found.")

    bundle_root = Path(__file__).resolve().parent.parent
    repos_root_arg = Path(args.repos_root)
    repos_root = repos_root_arg if repos_root_arg.is_absolute() else bundle_root / repos_root_arg

    for db_path in db_paths:
        repo_root = repos_root / db_path.stem
        conn = sqlite3.connect(db_path)
        try:
            ensure_columns(conn)
            if args.force:
                rows = conn.execute(
                    "SELECT test_id, test_name, test_filename FROM testcase WHERE test_filename IS NOT NULL"
                ).fetchall()
            else:
                rows = conn.execute(
                    """
                    SELECT test_id, test_name, test_filename
                    FROM testcase
                    WHERE test_filename IS NOT NULL
                      AND (test_method_filename IS NULL OR line_number IS NULL)
                    """
                ).fetchall()
            updated = 0
            for test_id, test_name, test_filename in rows:
                if not test_filename:
                    continue
                file_path = resolve_test_source_file(repo_root, test_filename, test_name)
                if file_path is None or not file_path.exists():
                    continue
                result = run_helper(classpath, str(file_path), test_name)
                if not result:
                    continue
                print(
                    f"[resolved] test_id={test_id} "
                    f"test_name={test_name} "
                    f"file={result.get('file')} "
                    f"line={result.get('line')}"
                )
                conn.execute(
                    """
                    UPDATE testcase
                    SET test_method_filename = ?, line_number = ?
                    WHERE test_id = ?
                    """,
                    (result.get("file"), result.get("line"), test_id),
                )
                updated += 1
            conn.commit()
        finally:
            conn.close()
        print(f"Updated {updated} tests in {db_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
