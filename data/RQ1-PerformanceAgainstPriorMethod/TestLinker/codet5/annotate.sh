#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PRETRAINED_MODEL_DIR="${1:?usage: annotate.sh <pretrained_model_dir> <checkpoint_dir> <projects_dir> <output_dir>}"
CHECKPOINT_DIR="${2:?usage: annotate.sh <pretrained_model_dir> <checkpoint_dir> <projects_dir> <output_dir>}"
PROJECTS_DIR="${3:?usage: annotate.sh <pretrained_model_dir> <checkpoint_dir> <projects_dir> <output_dir>}"
OUTPUT_DIR="${4:?usage: annotate.sh <pretrained_model_dir> <checkpoint_dir> <projects_dir> <output_dir>}"

TOP_K="${TOP_K:-1}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
CACHE_DIR="${OUTPUT_DIR}/cache"
SUMMARY_DIR="${OUTPUT_DIR}/summary"
RES_DIR="${OUTPUT_DIR}/prediction"
LOG="${OUTPUT_DIR}/annotate.log"
EXTRA_ARGS=()

if [[ "${NO_CUDA:-0}" != "0" ]]; then
    EXTRA_ARGS+=(--no_cuda)
fi

mkdir -p "${OUTPUT_DIR}" "${CACHE_DIR}" "${RES_DIR}"

"${PYTHON_BIN}" "${SCRIPT_DIR}/run_test.py" \
    --warmup_steps 1000 \
    --do_test \
    --eval_all_projects \
    --inference_only \
    --projects_dir "${PROJECTS_DIR}" \
    --checkpoints_dir "${CHECKPOINT_DIR}" \
    --top_k "${TOP_K}" \
    --model_type codet5 \
    --model_name_or_path "${PRETRAINED_MODEL_DIR}" \
    --cache_path "${CACHE_DIR}" \
    --summary_dir "${SUMMARY_DIR}" \
    --train_batch_size 8 \
    --eval_batch_size 16 \
    --max_source_length 512 \
    --max_target_length 3 \
    --patience 2 \
    --learning_rate 2e-5 \
    --output_dir "${OUTPUT_DIR}" \
    --summary_dir "${SUMMARY_DIR}" \
    --save_last_checkpoints \
    --always_save_model \
    --res_dir "${RES_DIR}" \
    "${EXTRA_ARGS[@]}" \
    2>&1 | tee "${LOG}"
