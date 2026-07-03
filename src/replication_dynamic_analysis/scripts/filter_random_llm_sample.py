#!/usr/bin/env python3
import json
import subprocess
import sys
from pathlib import Path


def compile_helper(helper_java: Path, class_output: Path, classpath: str) -> None:
    class_file = class_output / "FilterTestContent.class"
    if class_file.exists() and class_file.stat().st_mtime >= helper_java.stat().st_mtime:
        return
    class_output.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "javac",
            "-cp",
            classpath,
            "-d",
            str(class_output),
            str(helper_java),
        ],
        check=True,
    )


def run_filter(classpath: str, test_class: str, test_method: str, file_content: str) -> str:
    proc = subprocess.run(
        ["java", "-cp", classpath, "FilterTestContent", test_class, test_method],
        input=file_content,
        text=True,
        capture_output=True,
        check=True,
    )
    return proc.stdout


def main() -> int:
    bundle_root = Path(__file__).resolve().parent.parent
    input_path = Path(sys.argv[1]) if len(sys.argv) > 1 else (
        bundle_root / "dbs" / "random-llm-sample.jsonl"
    )
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else (
        bundle_root / "dbs" / "random-llm-sample.filtered.jsonl"
    )

    if not input_path.exists():
        print(f"Input not found: {input_path}")
        return 1

    scripts_dir = Path(__file__).resolve().parent
    helper_java = scripts_dir / "FilterTestContent.java"
    class_output = scripts_dir / "bin"
    jar_path = scripts_dir.parent / "java_test_analyzer" / "target" / "java-test-analyzer-1.0.0.jar"
    classpath = f"{class_output}:{jar_path}"

    compile_helper(helper_java, class_output, classpath)

    with input_path.open("r", encoding="utf-8") as handle, output_path.open(
        "w", encoding="utf-8"
    ) as out:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            filtered = run_filter(
                classpath,
                record["test_class"],
                record["test_method"],
                record["file_content"],
            )
            record["file_content"] = filtered
            out.write(json.dumps(record) + "\n")

    print(f"Wrote filtered output to {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
