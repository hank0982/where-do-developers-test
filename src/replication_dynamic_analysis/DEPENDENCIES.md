# Dependencies

This bundle is intended to run on a Unix-like environment and has been prepared to minimize machine-specific assumptions.

## Required tools

- `bash`
- `python3`
- `java`
- `javac`
- `mvn`

## Python dependencies

The repaired `rebuild_repo_db.sh` path only uses Python standard-library modules:

- `argparse`
- `json`
- `pathlib`
- `re`
- `sqlite3`
- `subprocess`
- `sys`
- `typing`

No third-party Python packages are required for the supported pipeline in this bundle.

## Java and Maven dependencies

- The dynamic-analysis pipeline requires a JDK because it compiles helper Java files and runs Maven-based subject projects.
- The bundle uses a bundle-local Maven repository by default:
  - `.m2/repository` under `src/replication_dynamic_analysis`
- On first run, Maven may download dependencies such as:
  - the CK metrics plugin
  - JaCoCo CLI
  - any unresolved dependencies required by the subject projects

## Bundled helper components

The following artifacts are included because `rebuild_repo_db.sh` depends on them:

- `scripts/FindTestMethod.java`
- `scripts/FilterTestContent.java`
- `java_test_analyzer/`
- `dbs/analysis/create_dynamic_feature_summary.py`

## Supported entrypoints

- `jacoco-pertest/jacoco-pertest-wrapper-v2.sh`
- `scripts/rebuild_repo_db.sh`
- `scripts/process_all_java_repos.sh`

## Notes

- The full `rebuild_repo_db.sh` pipeline writes generated outputs under:
  - `dbs/`
  - `dbs/batch_input_<repo>/`
  - `<repo>/target/jacoco-pertest/`
  - `<repo>/jacoco-pertest-results/`
- Running the full pipeline for every bundled repository is time-consuming and disk-intensive.
