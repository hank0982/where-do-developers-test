#!/usr/bin/env bash
#
# Run CK metrics, aggregate CSV outputs, collect JaCoCo per-test data,
# and refresh SQLite descendants for every Maven project under java_repos/.
# The script only orchestrates the workflow; it does not modify java_repos/*
# outside of the generated artifacts produced by each command.
#
# Usage:
#   scripts/process_all_java_repos.sh
#     --repos-dir <path>   # optional: override java_repos directory
#     --mvn-cmd <command>  # optional: Maven binary to use (default: mvn)
#     --wrapper-flags "<flags>" # optional: extra flags for jacoco wrapper
#     --help               # print this message

set -euo pipefail

usage() {
  sed -n '1,20p' "$0"
  exit 0
}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( dirname "$SCRIPT_DIR" )"
REPOS_DIR="${PROJECT_ROOT}/java_repos"
METHODS_ROOT="${PROJECT_ROOT}/scripts/methods-csv"
DB_DIR="${PROJECT_ROOT}/dbs"
MVN_CMD="${MVN_CMD:-mvn}"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${PROJECT_ROOT}/.m2/repository}"
WRAPPER_SCRIPT="${PROJECT_ROOT}/jacoco-pertest/jacoco-pertest-wrapper-v2.sh"
LOAD_REPO_SCRIPT="${PROJECT_ROOT}/scripts/load_repo_with_level0.py"
RECOMPUTE_SCRIPT="${PROJECT_ROOT}/scripts/recompute_descendants_from_calls.py"
WRAPPER_FLAGS=""
SKIP_EXISTING_DB=false
declare -a EXCLUDE_REPOS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repos-dir)
      REPOS_DIR="$2"
      shift 2
      ;;
    --mvn-cmd)
      MVN_CMD="$2"
      shift 2
      ;;
    --wrapper-flags)
      WRAPPER_FLAGS="$2"
      shift 2
      ;;
    --exclude-repo)
      EXCLUDE_REPOS+=("$2")
      shift 2
      ;;
    --skip-existing-db)
      SKIP_EXISTING_DB=true
      shift
      ;;
    --help|-h)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      ;;
  esac
done

if [[ ! -d "$REPOS_DIR" ]]; then
  echo "ERROR: Repositories directory not found: $REPOS_DIR" >&2
  exit 1
fi

if ! command -v "$MVN_CMD" >/dev/null 2>&1; then
  echo "ERROR: Maven command not found: $MVN_CMD" >&2
  exit 1
fi

if [[ ! -x "$WRAPPER_SCRIPT" ]]; then
  echo "ERROR: JaCoCo wrapper not found or not executable: $WRAPPER_SCRIPT" >&2
  exit 1
fi

mkdir -p "$METHODS_ROOT" "$DB_DIR" "$MAVEN_REPO_LOCAL"

log() {
  local level="$1"
  local message="$2"
  printf '[%s] %s\n' "$level" "$message"
}

combine_ck_csvs() {
  local repo_name="$1"
  local repo_dir="$2"
  local dest_dir="${METHODS_ROOT}/${repo_name}"
  mkdir -p "$dest_dir"

  local csv_types=("class.csv" "method.csv" "field.csv" "variable.csv")
  for csv_name in "${csv_types[@]}"; do
    local tmp_file
    tmp_file="$(mktemp)"
    local found=0
    local is_first=1
    while IFS= read -r -d '' src_csv; do
      if [[ $is_first -eq 1 ]]; then
        cat "$src_csv" > "$tmp_file"
        is_first=0
      else
        tail -n +2 "$src_csv" >> "$tmp_file"
      fi
      ((found++))
    done < <(find "$repo_dir" -type f -name "$csv_name" -path "*/src/main/*" -print0 | sort -z)

    if [[ $found -eq 0 ]]; then
      rm -f "$tmp_file"
      continue
    fi

    mv "$tmp_file" "${dest_dir}/${csv_name}"
    log "ck-csv" "${repo_name}: wrote ${dest_dir}/${csv_name} from ${found} source file(s)"
  done
}

run_for_repo() {
  local repo_dir="$1"
  local repo_name="$2"
  local db_path="${DB_DIR}/${repo_name}.db"

  log "repo" "Processing ${repo_name}"

  if [[ ! -f "${repo_dir}/pom.xml" ]]; then
    log "skip" "${repo_name}: no root-level pom.xml"
    return 2
  fi

  if $SKIP_EXISTING_DB && [[ -f "$db_path" ]]; then
    log "skip" "${repo_name}: database already exists at ${db_path}"
    return 2
  fi

  if ! (cd "$repo_dir" && "$MVN_CMD" -Dmaven.repo.local="$MAVEN_REPO_LOCAL" com.github.jazzmuesli:ck-mvn-plugin:metrics); then
    log "fail" "${repo_name}: CK metrics failed"
    return 1
  fi

  combine_ck_csvs "$repo_name" "$repo_dir"

  local wrapper_cmd=("$WRAPPER_SCRIPT" "$repo_dir" "clean compile test")
  if [[ -n "$WRAPPER_FLAGS" ]]; then
    wrapper_cmd+=($WRAPPER_FLAGS)
  fi
  if ! "${wrapper_cmd[@]}"; then
    log "fail" "${repo_name}: jacoco wrapper failed"
    return 1
  fi

  local depth_dir="${repo_dir}/target/jacoco-pertest"
  if [[ ! -d "$depth_dir" ]]; then
    log "fail" "${repo_name}: expected depth dir not found at ${depth_dir}"
    return 1
  fi

  if ! python3 "$LOAD_REPO_SCRIPT" "$repo_name" --depth-paths "$depth_dir"; then
    log "fail" "${repo_name}: load_repo_with_level0.py failed"
    return 1
  fi

  if ! python3 "$RECOMPUTE_SCRIPT" "$repo_name"; then
    log "fail" "${repo_name}: recompute_descendants_from_calls.py failed"
    return 1
  fi

  log "done" "${repo_name}: completed all steps"
  return 0
}

declare -a success_repos=()
declare -a skipped_repos=()
declare -a failed_repos=()

for repo_dir in "$REPOS_DIR"/*; do
  [[ -d "$repo_dir" ]] || continue
  repo_name="$( basename "$repo_dir" )"
  if [[ " ${EXCLUDE_REPOS[*]} " == *" ${repo_name} "* ]]; then
    log "skip" "${repo_name}: explicitly excluded"
    skipped_repos+=("$repo_name")
    continue
  fi
  if run_for_repo "$repo_dir" "$repo_name"; then
    success_repos+=("$repo_name")
  else
    status=$?
    if [[ $status -eq 2 ]]; then
      skipped_repos+=("$repo_name")
    else
      failed_repos+=("$repo_name")
    fi
  fi
  echo
done

log "summary" "Completed processing ${#success_repos[@]} repositories."
if [[ ${#success_repos[@]} -gt 0 ]]; then
  log "summary" "Succeeded: ${success_repos[*]}"
fi
if [[ ${#skipped_repos[@]} -gt 0 ]]; then
  log "summary" "Skipped: ${skipped_repos[*]}"
fi
if [[ ${#failed_repos[@]} -gt 0 ]]; then
  log "summary" "Failed: ${failed_repos[*]}"
  exit 1
fi

exit 0
