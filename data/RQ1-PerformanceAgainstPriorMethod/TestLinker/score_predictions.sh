#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PYTHON_BIN="${PYTHON_BIN:-python3}"
DATASET_ROOT="${DATASET_ROOT:-${SCRIPT_DIR}/../../human-label/updated_500/CurrentDBHumanAnnotated_json}"
PREDICTION_DIR="${PREDICTION_DIR:-${SCRIPT_DIR}/output/prediction}"
OUTPUT_PATH="${OUTPUT_PATH:-${SCRIPT_DIR}/output/score_current_db_human.json}"

mkdir -p "$(dirname "${OUTPUT_PATH}")"

"${PYTHON_BIN}" "${SCRIPT_DIR}/codet5/score_paper_style_normalized.py" \
    --dataset-root "${DATASET_ROOT}" \
    --prediction-dir "${PREDICTION_DIR}" \
    --output "${OUTPUT_PATH}"
