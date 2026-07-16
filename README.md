# Anonymous Replication Package

This repository is an anonymous replication package prepared for peer review of an ICSE submission.
It intentionally omits author identities, affiliations, and project-specific attribution.

The current package primarily exposes the dynamic-analysis pipeline used to collect per-test coverage and level-0 method-call observations for a set of Java projects.

## Contents

The main material is located under [src/replication_dynamic_analysis](src/replication_dynamic_analysis/):

- [jacoco-pertest](src/replication_dynamic_analysis/jacoco-pertest/) contains the per-test JaCoCo wrapper, listeners, and tracking agent.
- [java_repos](src/replication_dynamic_analysis/java_repos/) contains the subject Java projects used by the study.
- [scripts](src/replication_dynamic_analysis/scripts/) contains the import and orchestration scripts for rebuilding the analysis outputs.
- [README.md](src/replication_dynamic_analysis/README.md) is the entrypoint for the self-contained dynamic-analysis bundle.
- [documentation/methods](documentation/methods/) contains the LLM-method documentation used to describe the annotation workflow, prompt inventory, and observed model configurations.

## Environment

The replication material assumes a Unix-like environment with:

- Java
- Maven
- Python 3

Additional Maven dependencies for the listeners and agents are built automatically when needed.

## Quick Start

From the repository root:

```bash
cd src/replication_dynamic_analysis
```

Run the per-test dynamic-analysis wrapper on one subject project:

```bash
./jacoco-pertest/jacoco-pertest-wrapper-v2.sh ./java_repos/commons-io test
```

This produces per-test artifacts inside the target repository, including:

- `target/jacoco-pertest/`
- `jacoco-pertest-results/`

To rebuild the broader per-repository analysis pipeline for one project:

```bash
./scripts/rebuild_repo_db.sh commons-io
```

To process multiple repositories:

```bash
./scripts/process_all_java_repos.sh
```

## Notes on Structure

The dynamic-analysis bundle is intentionally self-contained. In particular, `scripts/methods-csv/` is included so the level-0 tracker can resolve concrete method identifiers during execution.

The top-level `data/` and `documentation/` directories are retained for packaging convenience, but the runnable artifacts for the current submission snapshot are under `src/replication_dynamic_analysis/`.

## RQ1 Data

The `data/` directory now includes the reviewer-facing RQ1 artifacts for ground truth construction, internal-validity checks, model-agreement analysis, and comparison against the prior method.

### Human-Labeled Ground Truth

The [`data/human-label/`](data/human-label/) directory contains the human-labeled gold data used for RQ1:

- [`data/human-label/suggested_label_146.json`](data/human-label/suggested_label_146.json) stores the 146-case ground-truth file used for the prior comparison set.
- [`data/human-label/updated_500/current_db_human_annotation_500.json`](data/human-label/updated_500/current_db_human_annotation_500.json) stores the updated 500-case ground-truth file.
- [`data/human-label/updated_500/CurrentDBHumanAnnotated_json/`](data/human-label/updated_500/CurrentDBHumanAnnotated_json/) contains the project-level JSON package for the updated 500-case annotations.
- [`data/human-label/label_difference_review_report.pdf`](data/human-label/label_difference_review_report.pdf) summarizes the reviewed differences between our updated labels and the previous labels.

### Internal Validity

The [`data/Internal_validity/`](data/Internal_validity/) directory contains the two annotator files and their comparison outputs:

- [`data/Internal_validity/coder1.json`](data/Internal_validity/coder1.json)
- [`data/Internal_validity/coder2.json`](data/Internal_validity/coder2.json)
- [`data/Internal_validity/similarity_result.txt`](data/Internal_validity/similarity_result.txt)
- [`data/Internal_validity/calculate_score.py`](data/Internal_validity/calculate_score.py)

These files report the annotator differences used for the internal-validity discussion.

### Model Agreement

The [`data/RQ1-models-agreement/`](data/RQ1-models-agreement/) directory contains the scripts and copied model outputs used to measure agreement across the RQ1 annotation models. See [`data/RQ1-models-agreement/README.md`](data/RQ1-models-agreement/README.md) for reproduction details and expected agreement values.

### Performance Against the Prior Method

The [`data/RQ1-PerformanceAgainstPriorMethod/`](data/RQ1-PerformanceAgainstPriorMethod/) directory contains:

- the `CallWalker` machine-annotation exports for both the 146-case and updated-500 comparisons
- the copied `TestLinker` outputs used for the prior-method baseline
- the scoring script that reproduces the paper table

See [`data/RQ1-PerformanceAgainstPriorMethod/README.md`](data/RQ1-PerformanceAgainstPriorMethod/README.md) for the exact layout and rerun instructions.

### Repository Size Note

This repository does not include every runtime-generated artifact from the original experiments because the full database would exceed GitHub storage limits. The current package includes the outputs needed to inspect and reproduce the reported RQ1 comparisons, and the complete database will be released after paper acceptance.

## RQ2 and RQ3 Data

The `data/RQ2and3/` directory contains the combined method-level export used for RQ2 and RQ3:

- [`data/RQ2and3/method_rows_export.csv`](data/RQ2and3/method_rows_export.csv)

This CSV includes per-method structural metrics (`wmc`, `fanin`, `fanout`, parameter/return counts, visibility flags), repository and history fields (`repo`, commit counts, timestamps, age), and dynamic-analysis summary fields such as `level0_tests`, `annotated_tests`, `machine_annotated_tests`, `avg_depth`, descendant/child counts, call counts, and the corresponding variance columns.

## RQ4 Data

The `data/RQ4/` directory contains the RQ4 export derived from `Sheet4` of the qualitative coding workbook:

- [`data/RQ4/Qualitative Notes for The Most Tested Method.csv`](data/RQ4/Qualitative%20Notes%20for%20The%20Most%20Tested%20Method.csv)
- [`data/RQ4/RQ4_codebook.csv`](data/RQ4/RQ4_codebook.csv)

The main RQ4 CSV export is the consolidated RQ4 table, with focal-method identifiers, aggregate execution counts, tag counts, majority test-case type, and summarized oracle/test-type labels.

The codebook records the coding scheme used for the final four RQ4 dimensions: parameterized oracle type, parameterized test type, single-test oracle type, and single-test type. For each code, it provides the observed occurrence count together with its definition, inclusion criteria, exclusion criteria, and any additional notes.

## Documentation

The repository also includes method documentation for the LLM-assisted annotation pipeline under [`documentation/methods/`](documentation/methods/):

- [`documentation/methods/llm-annotation-workflow.md`](documentation/methods/llm-annotation-workflow.md) describes the multi-round focal-method annotation workflow, including request expansion, follow-up selection, and final compression.
- [`documentation/methods/llm-configurations.md`](documentation/methods/llm-configurations.md) records the observed model identifiers, sampling parameters, request formats, and historical configuration variants used in archived runs.
- [`documentation/methods/llm-prompts.md`](documentation/methods/llm-prompts.md) inventories the prompt builders and representative emitted system and user prompts used by the current annotation pipeline.

## Anonymity Notice

This package is distributed in anonymous form for peer review. Any identifying metadata should remain suppressed until the review process is complete.
