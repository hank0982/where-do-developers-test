#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PYTHON_BIN="${PYTHON_BIN:-python3}"
TOP_K="${TOP_K:-1}"
NO_CUDA="${NO_CUDA:-1}"

PRETRAINED_MODEL_DIR="${PRETRAINED_MODEL_DIR:-${SCRIPT_DIR}/models/codet5-base}"
CHECKPOINT_DIR="${CHECKPOINT_DIR:-${SCRIPT_DIR}/checkpoints/checkpoint-best-acc_and_f1}"
PROJECTS_DIR="${PROJECTS_DIR:-${SCRIPT_DIR}/../../human-label/updated_500/CurrentDBHumanAnnotated_json}"
OUTPUT_DIR="${OUTPUT_DIR:-${SCRIPT_DIR}/output}"

if [[ ! -f "${PRETRAINED_MODEL_DIR}/pytorch_model.bin" ]]; then
    echo "Missing pretrained model at ${PRETRAINED_MODEL_DIR}/pytorch_model.bin" >&2
    echo "See ${SCRIPT_DIR}/models/codet5-base/README.md for the original model card." >&2
    exit 1
fi

if [[ ! -f "${CHECKPOINT_DIR}/pytorch_model.bin" ]]; then
    echo "Missing fine-tuned checkpoint at ${CHECKPOINT_DIR}/pytorch_model.bin" >&2
    echo "Place the TestLinker fine-tuned checkpoint in ${CHECKPOINT_DIR}." >&2
    exit 1
fi

if [[ ! -d "${PROJECTS_DIR}" ]]; then
    echo "Missing dataset directory: ${PROJECTS_DIR}" >&2
    exit 1
fi

mkdir -p "${OUTPUT_DIR}"

if [[ "${NO_CUDA}" != "0" ]]; then
    export NO_CUDA=1
else
    export NO_CUDA=0
fi

"${SCRIPT_DIR}/codet5/annotate.sh" \
    "${PRETRAINED_MODEL_DIR}" \
    "${CHECKPOINT_DIR}" \
    "${PROJECTS_DIR}" \
    "${OUTPUT_DIR}"
