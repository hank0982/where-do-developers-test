#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[missing] command not found: $cmd" >&2
    return 1
  fi
  echo "[ok] command found: $cmd"
}

require_file() {
  local path="$1"
  if [[ ! -e "$path" ]]; then
    echo "[missing] file not found: $path" >&2
    return 1
  fi
  echo "[ok] file present: $path"
}

require_cmd bash
require_cmd python3
require_cmd java
require_cmd javac
require_cmd mvn

require_file "${SCRIPT_DIR}/jacoco-pertest/jacoco-pertest-wrapper-v2.sh"
require_file "${SCRIPT_DIR}/scripts/rebuild_repo_db.sh"
require_file "${SCRIPT_DIR}/scripts/load_repo_with_level0.py"
require_file "${SCRIPT_DIR}/scripts/load_test_filenames.py"
require_file "${SCRIPT_DIR}/scripts/resolve_test_method_locations.py"
require_file "${SCRIPT_DIR}/scripts/extract_llm_samples.py"
require_file "${SCRIPT_DIR}/scripts/filter_random_llm_sample.py"
require_file "${SCRIPT_DIR}/scripts/batch_prepare_llm_requests.py"
require_file "${SCRIPT_DIR}/scripts/FindTestMethod.java"
require_file "${SCRIPT_DIR}/scripts/FilterTestContent.java"
require_file "${SCRIPT_DIR}/java_test_analyzer/pom.xml"
require_file "${SCRIPT_DIR}/java_test_analyzer/target/java-test-analyzer-1.0.0.jar"
require_file "${SCRIPT_DIR}/dbs/analysis/create_dynamic_feature_summary.py"

echo "[ok] environment check completed"
