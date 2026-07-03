#!/bin/bash

# Universal JaCoCo Per-Test Coverage Wrapper (Version 2)
# Simplified, single-file runner for collecting per-test JaCoCo results
# Usage: ./jacoco-pertest-wrapper.sh <project-path> [maven-command] [--skip-xml] [--skip-level0]

set -e -o pipefail

# Basic coloring for readability
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
METHOD_INDEX_PATH=""

print_usage() {
    cat <<EOF
Usage: $0 <path-to-maven-project> [maven-command] [--skip-xml] [--skip-level0]

Examples:
  $0 ../java_repos/jsoup test
  $0 ../java_repos/commons-io clean test
  $0 ../java_repos/assertj-core test --skip-xml    # Skip XML generation
  $0 ../java_repos/assertj-core test --skip-level0 # Skip Level 0 instrumentation
EOF
}

info() { printf "%b\n" "${GREEN}$*${NC}"; }
warn() { printf "%b\n" "${YELLOW}$*${NC}"; }
fail() { printf "%b\n" "${RED}$*${NC}"; exit 1; }

require_project_dir() {
    [ "$#" -ge 1 ] || { print_usage; exit 1; }
    PROJECT_DIR="$(cd "$1" && pwd)"
    POM_FILE="$PROJECT_DIR/pom.xml"
    [ -f "$POM_FILE" ] || fail "Error: No pom.xml found in $PROJECT_DIR"
    shift
    set -- "$@"
}

parse_flags() {
    SKIP_XML_GENERATION=false
    SKIP_LEVEL0=false
    ARGS=()

    for arg in "$@"; do
        case "$arg" in
            --skip-xml) SKIP_XML_GENERATION=true ;;
            --skip-level0|--no-level0) SKIP_LEVEL0=true ;;
            *) ARGS+=("$arg") ;;
        esac
    done

    set -- "${ARGS[@]}"
    MAVEN_CMD="${*:-test}"
}

append_flag_if_missing() {
    local marker="$1"
    local addition="$2"
    [[ "$MAVEN_CMD" =~ "$marker" ]] || MAVEN_CMD+=" $addition"
}

ensure_argline_property() {
    [[ "$MAVEN_CMD" =~ "-DargLine" ]] && return

    mapfile -t ARG_LINE_INFO < <(python3 - "$POM_FILE" <<'PY'
import sys
import xml.etree.ElementTree as ET

pom_file = sys.argv[1]
ET.register_namespace('', 'http://maven.apache.org/POM/4.0.0')
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}

def has_argline_property(root):
    props = root.find('m:properties', ns)
    if props is None:
        return False
    node = props.find('m:argLine', ns)
    return node is not None and (node.text or '').strip()

def has_argline_config(root):
    for node in root.findall('.//m:argLine', ns):
        if (node.text or '').strip():
            return True
    return False

try:
    tree = ET.parse(pom_file)
    root = tree.getroot()
    print("true" if has_argline_property(root) else "false")
    print("true" if has_argline_config(root) else "false")
except Exception:
    print("false")
    print("false")
PY
)

    HAS_ARG_LINE_PROP="${ARG_LINE_INFO[0]}"
    HAS_ARG_LINE_CONFIG="${ARG_LINE_INFO[1]}"

    if [[ "$HAS_ARG_LINE_PROP" != "true" && "$HAS_ARG_LINE_CONFIG" != "true" ]]; then
        MAVEN_CMD+=" -DargLine="
    fi
}

set_paths() {
    OUTPUT_DIR="$PROJECT_DIR/target/jacoco-pertest"
    EXEC_DIR="$OUTPUT_DIR/exec"
    FINAL_OUTPUT_DIR="$PROJECT_DIR/jacoco-pertest-results"
    JACOCO_VERSION="0.8.12"
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
    MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${REPO_ROOT}/.m2/repository}"
    LOCAL_AGENT_JAR="$SCRIPT_DIR/level0-agent/target/level0-tracking-agent-1.0.jar"
    LEVEL0_AGENT_REPO_JAR="$MAVEN_REPO_LOCAL/local/jacoco/level0-tracking-agent/1.0/level0-tracking-agent-1.0.jar"
    LEVEL0_AGENT_JAR="${LOCAL_AGENT_JAR}"
    if [ ! -f "$LEVEL0_AGENT_JAR" ]; then
        LEVEL0_AGENT_JAR="$LEVEL0_AGENT_REPO_JAR"
    fi
    LISTENER_TARGET_JAR="$SCRIPT_DIR/listener/target/jacoco-pertest-listener-1.0.jar"
    LISTENER_REPO_JAR="$MAVEN_REPO_LOCAL/local/jacoco/jacoco-pertest-listener/1.0/jacoco-pertest-listener-1.0.jar"
    LISTENER_JAR="$LISTENER_TARGET_JAR"
    if [ ! -f "$LISTENER_JAR" ]; then
        LISTENER_JAR="$LISTENER_REPO_JAR"
    fi
    LISTENER_JUNIT4_TARGET_JAR="$SCRIPT_DIR/listener-junit4/target/jacoco-pertest-listener-junit4-1.0.jar"
    LISTENER_JUNIT4_REPO_JAR="$MAVEN_REPO_LOCAL/local/jacoco/jacoco-pertest-listener-junit4/1.0/jacoco-pertest-listener-junit4-1.0.jar"
    LISTENER_JUNIT4_JAR="$LISTENER_JUNIT4_TARGET_JAR"
    if [ ! -f "$LISTENER_JUNIT4_JAR" ]; then
        LISTENER_JUNIT4_JAR="$LISTENER_JUNIT4_REPO_JAR"
    fi
    PROJECT_NAME="$(basename "$PROJECT_DIR")"
    local candidate_method_csv="$REPO_ROOT/scripts/methods-csv/$PROJECT_NAME/method.csv"
    if [ -f "$candidate_method_csv" ]; then
        METHOD_INDEX_PATH="$candidate_method_csv"
    fi
}

prepare_maven_cmd() {
    append_flag_if_missing "-Drat.skip" "-Drat.skip=true"
    append_flag_if_missing "-Dmaven.javadoc.skip" "-Dmaven.javadoc.skip=true"
    append_flag_if_missing "-Dcheckstyle.skip" "-Dcheckstyle.skip=true"
    append_flag_if_missing "-Djacoco.version" "-Djacoco.version=$JACOCO_VERSION"
    append_flag_if_missing "-Djacoco.skip" "-Djacoco.skip=false"
    append_flag_if_missing "-Dmaven.repo.local" "-Dmaven.repo.local=$MAVEN_REPO_LOCAL"
    append_flag_if_missing "-Dpertest.output.dir" "-Dpertest.output.dir=$OUTPUT_DIR"
    append_flag_if_missing "-Djacoco.pertest.output" "-Djacoco.pertest.output=$OUTPUT_DIR"
    append_flag_if_missing "-Djacoco.destFile" "-Djacoco.destFile=${OUTPUT_DIR}/jacoco.exec"
    ensure_argline_property
    if [ "$SKIP_LEVEL0" = "false" ] && [ -n "$METHOD_INDEX_PATH" ]; then
        append_flag_if_missing "-Dlevel0.method.map" "-Dlevel0.method.map=$METHOD_INDEX_PATH"
    fi
}

build_artifacts() {
    if [ "$SKIP_LEVEL0" = "false" ]; then
        if [ ! -f "$LOCAL_AGENT_JAR" ] || [ ! -f "$LEVEL0_AGENT_REPO_JAR" ]; then
            warn "Building Level-0 tracking agent..."
            (cd "$SCRIPT_DIR/level0-agent" && mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" clean install)
            info "✓ Level-0 agent built"
        fi
        if [ ! -f "$LEVEL0_AGENT_JAR" ]; then
            fail "Level-0 agent JAR not found at $LEVEL0_AGENT_JAR"
        fi
    fi

    if [ ! -f "$LISTENER_TARGET_JAR" ] || [ ! -f "$LISTENER_REPO_JAR" ]; then
        warn "Building JUnit Platform listener..."
        (cd "$SCRIPT_DIR/listener" && mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" clean install)
        info "✓ Platform listener built"
    fi

    if [ ! -f "$LISTENER_JUNIT4_TARGET_JAR" ] || [ ! -f "$LISTENER_JUNIT4_REPO_JAR" ]; then
        warn "Building JUnit 4 listener..."
        (cd "$SCRIPT_DIR/listener-junit4" && mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" clean install)
        info "✓ JUnit 4 listener built"
    fi
}

extract_group_id() {
    if [[ "$PROJECT_NAME" == "gson" ]]; then
        TARGET_PACKAGE="com/google/gson"
        info "Target package for level-0 tracking: $TARGET_PACKAGE (hardcoded for gson)"
    else
        GROUP_ID=$(grep -m 1 '<groupId>' "$POM_FILE" | sed 's/.*<groupId>\(.*\)<\/groupId>.*/\1/')
        [ -n "$GROUP_ID" ] || fail "Error: Could not extract groupId from pom.xml"
        TARGET_PACKAGE=$(echo "$GROUP_ID" | tr '.' '/')
        info "Target package for level-0 tracking: $TARGET_PACKAGE (from groupId: $GROUP_ID)"
    fi

    if [ "$SKIP_LEVEL0" = "false" ]; then
        append_flag_if_missing "-Dlevel0.agent.path" "-Dlevel0.agent.path=$LEVEL0_AGENT_JAR"
        append_flag_if_missing "-Dlevel0.target.package" "-Dlevel0.target.package=$TARGET_PACKAGE"
    fi
}

run_maven() {
    echo ""
    info "=========================================="
    echo "JaCoCo Per-Test Coverage Runner"
    info "=========================================="
    echo "Project: $PROJECT_DIR"
    echo "Command: mvn $MAVEN_CMD"
    echo ""

    mkdir -p "$EXEC_DIR"
    (cd "$PROJECT_DIR" && mvn $MAVEN_CMD) || fail "Maven build failed"

    echo ""
    info "=========================================="
    echo "Coverage Collection Complete!"
    info "=========================================="
}

collect_execs() {
    exec_count=$(find "$PROJECT_DIR" -name "*.exec" -path "*/jacoco-pertest/exec/*" 2>/dev/null | wc -l)
    [ "$exec_count" -gt 0 ] || return

    JACOCO_CLI="$MAVEN_REPO_LOCAL/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar"
    if [ ! -f "$JACOCO_CLI" ]; then
        echo "Downloading JaCoCo CLI..."
        mvn -q -Dmaven.repo.local="$MAVEN_REPO_LOCAL" dependency:get -Dartifact=org.jacoco:org.jacoco.cli:${JACOCO_VERSION}:jar:nodeps
    fi

    echo ""
    info "Collecting results from all modules..."
    rm -rf "$FINAL_OUTPUT_DIR"
    mkdir -p "$FINAL_OUTPUT_DIR/exec" "$FINAL_OUTPUT_DIR/xml"

    EXEC_MODULE_MAP=$(mktemp)

    CLASS_DIRS=()
    while IFS= read -r dir; do CLASS_DIRS+=("$dir"); done < <(find "$PROJECT_DIR" -type d -path "*/target/classes" 2>/dev/null)
    SOURCE_DIRS=()
    while IFS= read -r dir; do SOURCE_DIRS+=("$dir"); done < <(find "$PROJECT_DIR" -type d -path "*/src/main/java" 2>/dev/null)

    CLASS_DIRS_FILE=$(mktemp)
    SOURCE_DIRS_FILE=$(mktemp)
    printf "%s\n" "${CLASS_DIRS[@]}" > "$CLASS_DIRS_FILE"
    printf "%s\n" "${SOURCE_DIRS[@]}" > "$SOURCE_DIRS_FILE"

    while IFS= read -r exec_file; do
        [ -f "$exec_file" ] || continue
        module_dir=$(dirname "$(dirname "$(dirname "$(dirname "$exec_file")")")")
        base_name=$(basename "$exec_file")
        cp "$exec_file" "$FINAL_OUTPUT_DIR/exec/"
        echo "${base_name}|${module_dir}" >> "$EXEC_MODULE_MAP"
    done < <(find "$PROJECT_DIR" -name "*.exec" -path "*/jacoco-pertest/exec/*" 2>/dev/null)

    find "$PROJECT_DIR" -name "test-parameter-mapping.csv" -path "*/jacoco-pertest/*" -exec cat {} >> "$FINAL_OUTPUT_DIR/test-parameter-mapping.csv" \; 2>/dev/null
    info "✓ Files collected to: $FINAL_OUTPUT_DIR"

    if [ "$SKIP_XML_GENERATION" = true ]; then
        warn "Skipping XML generation (--skip-xml flag)"
    else
        "$SCRIPT_DIR/generate-xml-reports-parallel.sh" \
            "$FINAL_OUTPUT_DIR/exec" \
            "$FINAL_OUTPUT_DIR/xml" \
            "$EXEC_MODULE_MAP" \
            "$CLASS_DIRS_FILE" \
            "$SOURCE_DIRS_FILE" \
            "$JACOCO_CLI"
    fi

    rm -f "$EXEC_MODULE_MAP" "$CLASS_DIRS_FILE" "$SOURCE_DIRS_FILE"
}

print_summary() {
    echo ""
    echo "Summary"
    info "=========================================="
    echo "Project: $(basename "$PROJECT_DIR")"
    echo "Per-test exec files: $exec_count"
    if [ "$exec_count" -gt 0 ]; then
        echo "Results location: $FINAL_OUTPUT_DIR"
        echo "  - Exec files: $FINAL_OUTPUT_DIR/exec/"
        echo "  - XML reports: $FINAL_OUTPUT_DIR/xml/"
        [ -f "$FINAL_OUTPUT_DIR/test-parameter-mapping.csv" ] && echo "  - Parameter mapping: $FINAL_OUTPUT_DIR/test-parameter-mapping.csv"
        [ -d "$FINAL_OUTPUT_DIR/validation" ] && echo "  - Validation: $FINAL_OUTPUT_DIR/validation/"
    fi
    echo ""

    if [ "$exec_count" -gt 5 ]; then
        warn "=========================================="
        echo "Optional: Validation"
        warn "=========================================="
        echo "To validate per-test coverage accuracy, run:"
        info "$SCRIPT_DIR/validate-coverage.sh $FINAL_OUTPUT_DIR 5"
        echo "This randomly selects 5 tests and compares per-test coverage with individual test runs."
    fi
}

main() {
    require_project_dir "$@"
    shift
    set_paths
    parse_flags "$@"
    prepare_maven_cmd
    extract_group_id
    build_artifacts
    run_maven

    exec_count=$(find "$PROJECT_DIR" -name "*.exec" -path "*/jacoco-pertest/exec/*" 2>/dev/null | wc -l)
    if [ "$exec_count" -eq 0 ]; then
        warn "Warning: No per-test .exec files were generated"
        echo "This could mean:"
        echo "  - No tests were executed"
        echo "  - Listener not properly triggered (check Maven output for initialization message)"
        echo "  - JaCoCo agent not attached"
        echo ""
        echo "Coverage data location: $OUTPUT_DIR"
    else
        info "✓ Generated $exec_count per-test coverage files"
        collect_execs
    fi

    print_summary
}

main "$@"
