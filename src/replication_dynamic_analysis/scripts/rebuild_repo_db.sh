#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DB_DIR="${PROJECT_ROOT}/dbs"
ANALYSIS_DIR="${DB_DIR}/analysis"
WRAPPER_SCRIPT="${PROJECT_ROOT}/jacoco-pertest/jacoco-pertest-wrapper-v2.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") <repo_name> [--from-step <1-9>] [--use-raw-jsonl]

Options:
  --from-step <1-9>  Start execution from this step number (default: 1)
  --use-raw-jsonl    Use batch-input.jsonl (raw) for step 9 instead of batch-input.filtered.jsonl
  -h, --help         Show this help
EOF
}

REPO_NAME=""
START_STEP=1
USE_RAW_JSONL="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from-step)
      if [[ $# -lt 2 ]] || [[ -z "${2:-}" ]]; then
        echo "Missing value for --from-step" >&2
        usage
        exit 1
      fi
      START_STEP="${2:-}"
      shift 2
      ;;
    --use-raw-jsonl)
      USE_RAW_JSONL="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
    *)
      if [[ -z "${REPO_NAME}" ]]; then
        REPO_NAME="$1"
        shift
      else
        echo "Unexpected argument: $1" >&2
        usage
        exit 1
      fi
      ;;
  esac
done

if [[ -z "${REPO_NAME}" ]]; then
  usage
  exit 1
fi

if [[ ! "${START_STEP}" =~ ^[0-9]+$ ]] || (( START_STEP < 1 || START_STEP > 9 )); then
  echo "--from-step must be an integer between 1 and 9 (got: ${START_STEP})" >&2
  exit 1
fi

REPO_ROOT="${PROJECT_ROOT}/java_repos/${REPO_NAME}"
JACOCO_ROOT="${REPO_ROOT}/target/jacoco-pertest"
BATCH_INPUT_DIR="${DB_DIR}/batch_input_${REPO_NAME}"
RAW_BATCH_INPUT="${BATCH_INPUT_DIR}/batch-input.jsonl"
FILTERED_BATCH_INPUT="${BATCH_INPUT_DIR}/batch-input.filtered.jsonl"
DB_PATH="${DB_DIR}/${REPO_NAME}.db"

if [[ "${USE_RAW_JSONL}" == "true" ]]; then
  STEP9_INPUT="${RAW_BATCH_INPUT}"
else
  STEP9_INPUT="${FILTERED_BATCH_INPUT}"
fi

if [[ ! -d "${REPO_ROOT}" ]]; then
  echo "Repo not found: ${REPO_ROOT}"
  exit 1
fi

mkdir -p "${DB_DIR}" "${BATCH_INPUT_DIR}" "${ANALYSIS_DIR}"

run_step() {
  local step="$1"
  (( step >= START_STEP ))
}

if run_step 1; then
  echo "[1/9] Running jacoco-pertest for ${REPO_NAME}"
  "${WRAPPER_SCRIPT}" "${REPO_ROOT}/" "-fae -e clean compile test"
else
  echo "[1/9] Skipping jacoco-pertest (--from-step ${START_STEP})"
fi

if run_step 2; then
  echo "[2/9] Loading repo + level0 into DB"
  python3 "${SCRIPT_DIR}/load_repo_with_level0.py" "${REPO_NAME}" \
    --depth-paths "${JACOCO_ROOT}/" \
    --level0-paths "${JACOCO_ROOT}/" \
    --sqlite-path "${DB_PATH}"
else
  echo "[2/9] Skipping load repo + level0 (--from-step ${START_STEP})"
fi

if run_step 3; then
  echo "[3/9] Loading test filenames"
  python3 "${SCRIPT_DIR}/load_test_filenames.py" "${DB_PATH}" "${JACOCO_ROOT}/"
else
  echo "[3/9] Skipping load test filenames (--from-step ${START_STEP})"
fi

if run_step 4; then
  echo "[4/9] Recomputing descendants from calls"
  python3 "${SCRIPT_DIR}/recompute_descendants_from_calls.py" "${REPO_NAME}" \
    --sqlite-path "${DB_PATH}"
else
  echo "[4/9] Skipping recompute descendants (--from-step ${START_STEP})"
fi

if run_step 5; then
  echo "[5/9] Computing dynamic feature summary"
  python3 "${ANALYSIS_DIR}/create_dynamic_feature_summary.py" \
    --db-dir "${DB_DIR}" \
    --db "${REPO_NAME}"
else
  echo "[5/9] Skipping dynamic feature summary (--from-step ${START_STEP})"
fi

if run_step 6; then
  echo "[6/9] Resolving test method locations"
  python3 "${SCRIPT_DIR}/resolve_test_method_locations.py" "${DB_PATH}" \
    --repos-root "${PROJECT_ROOT}/java_repos"
else
  echo "[6/9] Skipping resolve test method locations (--from-step ${START_STEP})"
fi

if run_step 7; then
  echo "[7/9] Extracting LLM samples"
  python3 "${SCRIPT_DIR}/extract_llm_samples.py" "${DB_PATH}"
else
  echo "[7/9] Skipping extract LLM samples (--from-step ${START_STEP})"
fi

if run_step 8; then
  echo "[8/9] Filtering LLM sample"
  python3 "${SCRIPT_DIR}/filter_random_llm_sample.py" \
    "${RAW_BATCH_INPUT}" \
    "${FILTERED_BATCH_INPUT}"
else
  echo "[8/9] Skipping filter LLM sample (--from-step ${START_STEP})"
fi

if run_step 9; then
  echo "[9/9] Preparing LLM batch requests"
  echo "Using input: ${STEP9_INPUT}"
  python3 "${SCRIPT_DIR}/batch_prepare_llm_requests.py" \
    --input "${STEP9_INPUT}" \
    --out "${BATCH_INPUT_DIR}/"
else
  echo "[9/9] Skipping prepare LLM batch requests (--from-step ${START_STEP})"
fi

echo "Done: ${DB_PATH}"
