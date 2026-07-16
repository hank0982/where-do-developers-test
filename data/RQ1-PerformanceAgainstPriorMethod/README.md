# RQ1 Performance Against Prior Method

This directory contains the reviewer artifact for the RQ1 comparison between `CallWalker` and the prior method `TestLinker`.

## Files

- `score_comparison_table.py`
  Prints the exact terminal table reported in the paper for:
  - the 146-case suggested-label comparison across `commons-lang`, `commons-io`, `jfreechart`, and `gson`
  - the overall updated-500 comparison
  It reads ground truth from `../human-label/`.

- `callwalker_machine_annotations_146.json`
  Reviewer-readable export of the `machine_annotated` DB predictions for the 146-case comparison set.
  Each case includes the project, test id, DB test name, and the reconstructed method signatures used as `CallWalker` predictions.
  The 146-case ground truth labels are stored separately in `../human-label/suggested_label_146.json`.

- `callwalker_machine_annotations_500.json`
  Reviewer-readable export of the `machine_annotated` DB predictions for the updated 500-case comparison set.
  The 500-case ground truth labels are stored separately in `../human-label/updated_500/current_db_human_annotation_500.json`.

- `output_146/`
  Copied `TestLinker` output used for the 146-case paper comparison.

- `output_146/prediction/`
  `TestLinker` prediction files for the 146-case comparison, one `*_detail.json` file per project.

- `output_146/annotate.log`
  Original inference log from the `TestLinker` run for the 146-case set.

- `output_146/summary.log`
  Original summary log from the `TestLinker` run for the 146-case set.

- `output_146/cache/`
  Runtime cache directory produced by the original `TestLinker` run.
  It is not required for score recomputation, but is retained as part of the original run artifact.

- `output_500/`
  Copied `TestLinker` output used for the updated 500-case paper comparison.

- `output_500/prediction/`
  `TestLinker` prediction files for the updated 500-case comparison, one `*_detail.json` file per project.

- `output_500/annotate.log`
  Original inference log from the `TestLinker` run for the updated 500-case set.

- `output_500/summary.log`
  Original summary log from the `TestLinker` run for the updated 500-case set.

- `output_500/cache/`
  Runtime cache directory produced by the original `TestLinker` run.
  It is not required for score recomputation, but is retained as part of the original run artifact.

- `TestLinker/`
  Minimal runnable package for rerunning the prior-method inference.

## `TestLinker/` contents

- `TestLinker/run_inference.sh`
  Wrapper to rerun `TestLinker` inference on the reviewer dataset.

- `TestLinker/score_predictions.sh`
  Helper to score generated `TestLinker` predictions against the dataset labels.

- `TestLinker/codet5/`
  Copied source files needed for the `TestLinker` inference pipeline.

- `TestLinker/models/codet5-base/README.md`
  Original `CodeT5` model card. The large model weights are not duplicated here.

- `TestLinker/checkpoints/checkpoint-best-acc_and_f1/README.md`
  Notes where the fine-tuned checkpoint file belongs if the reviewer wants to rerun inference.

- `TestLinker/requirements.txt`
  Python dependency list used for the copied `TestLinker` runner.

- `TestLinker/README.md`
  Setup and rerun instructions for the `TestLinker` package itself.

## Print the paper table

Run from this directory:

```bash
python3 score_comparison_table.py
```

Or from anywhere:

```bash
python3 /path/to/RQ1-PerformanceAgainstPriorMethod/score_comparison_table.py \
  --root /path/to/RQ1-PerformanceAgainstPriorMethod
```

The script prints the exact terminal table used for the paper comparison:

- per-project 146-case rows for `lang`, `io`, `jfreechart`, and `gson`
- overall 146-case row
- overall updated-500 row
