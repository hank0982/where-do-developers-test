# Human Label Data

This directory contains reviewer-facing human-label artifacts used by the paper.

## Files

- `README.md`
  This description file.

- `label_difference_review_report.pdf`
  PDF report that summarizes the reviewed label differences between the previous human annotation and the updated annotation.

- `suggested_label_146.json`
  Lean ground-truth file for the 146-case RQ1 comparison set.
  Each case stores the suggested-label gold annotations used for the paper table across `commons-lang`, `commons-io`, `jfreechart`, and `gson`. These labeled were directly import from previous paper (fixed only when the method is not executed) All the differences are discussed in `label_difference_review_report.pdf`.

- `updated_500/CurrentDBHumanAnnotated_json/`
  The updated 500-case human-labeled dataset package.

- `updated_500/current_db_human_annotation_500.json`
  Lean ground-truth file for the updated 500-case RQ1 comparison set.
  Each case stores the `current_db_human_annotation` labels used for the paper table.

## Relation to RQ1 scoring

The scorer in `../RQ1-PerformanceAgainstPriorMethod/score_comparison_table.py` reads:

- `suggested_label_146.json` for the 146-case ground truth
- `updated_500/current_db_human_annotation_500.json` for the updated-500 ground truth

The `RQ1-PerformanceAgainstPriorMethod` directory stores the `TestLinker` outputs and the `CallWalker` machine-annotation predictions. This `human-label` directory stores the corresponding human-labeled gold files.
