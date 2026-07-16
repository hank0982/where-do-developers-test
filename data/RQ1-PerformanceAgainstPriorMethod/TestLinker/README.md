# TestLinker Prior-Method Reproduction

This directory contains the scripts needed to rerun the prior `TestLinker` / `old_tech` inference on the updated 500 human-reviewed dataset.

## Included files

- `run_inference.sh`: main reviewer entrypoint for rerunning inference.
- `score_predictions.sh`: recomputes precision, recall, and F1 from the generated prediction files.
- `codet5/`: copied inference code used by the prior-method pipeline.
- `models/codet5-base/README.md`: the original `CodeT5` model card from the source artifact.

## Dataset path

The default dataset path is relative to this directory:

```text
../../human-label/updated_500/CurrentDBHumanAnnotated_json
```

This resolves to the updated 500 human-review package in the reviewer repository.

## Required model files

The runnable scripts use these relative default locations:

```text
./models/codet5-base/
./checkpoints/checkpoint-best-acc_and_f1/
```

Required files:

- `models/codet5-base/pytorch_model.bin`
- `models/codet5-base/config.json`
- `models/codet5-base/merges.txt`
- `models/codet5-base/vocab.json`
- `models/codet5-base/tokenizer_config.json`
- `models/codet5-base/special_tokens_map.json`
- `models/codet5-base/added_tokens.json`
- `checkpoints/checkpoint-best-acc_and_f1/pytorch_model.bin`

The pretrained `CodeT5` model directory is about 852 MB and the fine-tuned checkpoint directory is about 851 MB, so they are not duplicated here by default.

## Python environment

The scripts were run locally with:

- Python `3.10.14`
- `torch==2.1.1`
- `transformers==4.30.0`
- `tensorboardX==2.6.5`
- `jsonlines==4.0.0`
- `tqdm==4.68.3`
- `numpy==1.26.4`
- `pandas==2.3.3`
- `scikit-learn==1.7.0`

You can install the Python dependencies with:

```bash
python3 -m venv .venv-old-tech
source .venv-old-tech/bin/activate
pip install -r requirements.txt
```

## Run inference

By default, inference runs on CPU to match the original rerun setup.

```bash
./run_inference.sh
```

Optional overrides:

```bash
NO_CUDA=0 ./run_inference.sh
TOP_K=1 ./run_inference.sh
PRETRAINED_MODEL_DIR=./models/codet5-base \
CHECKPOINT_DIR=./checkpoints/checkpoint-best-acc_and_f1 \
PROJECTS_DIR=../../human-label/updated_500/CurrentDBHumanAnnotated_json \
OUTPUT_DIR=./output \
./run_inference.sh
```

The prediction files are written to:

```text
./output/prediction/
```

The run log is written to:

```text
./output/annotate.log
```

## Score the generated predictions

```bash
./score_predictions.sh
```

This writes:

```text
./output/score_current_db_human.json
```

The scoring script reports three label views:

- `original`: the original `label` field in each JSON file.
- `suggested`: the effective labels after applying `label_suggestions`.
- `db_human`: the `current_db_human_annotation` field.

For the updated 500 annotation package, the paper-facing updated label view is the `db_human` score.
