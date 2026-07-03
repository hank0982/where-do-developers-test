#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_NAME="${1:-commons-exec}"
REPO_DIR="${SCRIPT_DIR}/java_repos/${REPO_NAME}"
OUTPUT_DIR="${REPO_DIR}/target/jacoco-pertest"

"${SCRIPT_DIR}/check_env.sh"

if [[ ! -d "${REPO_DIR}" ]]; then
  echo "Repo not found: ${REPO_DIR}" >&2
  exit 1
fi

echo "[smoke] running jacoco-pertest on ${REPO_NAME}"
"${SCRIPT_DIR}/jacoco-pertest/jacoco-pertest-wrapper-v2.sh" "${REPO_DIR}" "test" --skip-xml

if [[ ! -d "${OUTPUT_DIR}" ]]; then
  echo "[fail] expected output directory not found: ${OUTPUT_DIR}" >&2
  exit 1
fi

exec_count=$(find "${OUTPUT_DIR}" -name "*.exec" | wc -l)
level0_count=$(find "${OUTPUT_DIR}" -name "*.level0.methodid.csv" | wc -l)
depth_count=$(find "${OUTPUT_DIR}" -name "*.level0.depth.csv" | wc -l)

echo "[smoke] exec files: ${exec_count}"
echo "[smoke] level0 files: ${level0_count}"
echo "[smoke] depth files: ${depth_count}"

if [[ "${exec_count}" -eq 0 ]]; then
  echo "[fail] no per-test .exec files were produced" >&2
  exit 1
fi

if [[ "${level0_count}" -eq 0 ]]; then
  echo "[fail] no level-0 method-id files were produced" >&2
  exit 1
fi

if [[ "${depth_count}" -eq 0 ]]; then
  echo "[fail] no depth call files were produced" >&2
  exit 1
fi

echo "[ok] smoke test completed"
