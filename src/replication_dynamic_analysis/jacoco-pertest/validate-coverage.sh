#!/bin/bash

# Validation Script: Compare per-test coverage with individual test runs
# Usage: ./validate-coverage.sh <results-directory> <num-tests>

set -e

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

if [ "$#" -lt 1 ]; then
    echo "Usage:"
    echo "  $0 <results-directory> [num-tests]"
    echo "  $0 <results-directory> --test <fully.qualified.TestClass> <methodName>"
    echo ""
    echo "Examples:"
    echo "  $0 /path/to/jacoco-pertest-results 5"
    echo "  $0 /path/to/jacoco-pertest-results --test io.example.MyTest testMethod"
    echo ""
    exit 1
fi

RESULTS_DIR="$(cd "$1" && pwd)"
MODE="random"
NUM_TESTS=5
TEST_CLASS=""
TEST_METHOD=""

if [ "$#" -ge 2 ]; then
    if [ "$2" = "--test" ]; then
        MODE="single"
        TEST_CLASS="$3"
        TEST_METHOD="$4"
        if [ -z "$TEST_CLASS" ] || [ -z "$TEST_METHOD" ]; then
            echo -e "${RED}Error: --test requires <class> <method>${NC}"
            exit 1
        fi
    else
        NUM_TESTS="$2"
    fi
fi

if [ ! -d "$RESULTS_DIR/exec" ]; then
    echo -e "${RED}Error: $RESULTS_DIR/exec not found${NC}"
    exit 1
fi

echo -e "${GREEN}=========================================="
echo "Validation: Comparing Per-Test Coverage"
echo -e "==========================================${NC}"
echo "Results directory: $RESULTS_DIR"
echo "Number of tests to validate: $NUM_TESTS"
echo ""

# Find the project directory (parent of jacoco-pertest-results)
PROJECT_DIR="$(dirname "$RESULTS_DIR")"
echo "Project directory: $PROJECT_DIR"

# Determine level-0 target package once from project groupId
PROJECT_GROUP_ID=$(mvn -q -o -DforceStdout help:evaluate -Dexpression=project.groupId -f "$PROJECT_DIR/pom.xml" 2>/dev/null | grep -v '\[' | tail -n 1)
if [ -z "$PROJECT_GROUP_ID" ]; then
    PROJECT_GROUP_ID=$(grep -m1 '<groupId>' "$PROJECT_DIR/pom.xml" 2>/dev/null | sed 's/.*<groupId>\(.*\)<\/groupId>.*/\1/')
fi
LEVEL0_TARGET_PACKAGE=""
if [ -n "$PROJECT_GROUP_ID" ]; then
    LEVEL0_TARGET_PACKAGE=$(echo "$PROJECT_GROUP_ID" | tr '.' '/')
fi
if [ -n "$LEVEL0_TARGET_PACKAGE" ]; then
    echo "Level0 target package: $LEVEL0_TARGET_PACKAGE"
else
    echo "Level0 target package not detected; Level-0 agent will be disabled for validation runs."
fi

# Setup
VALIDATION_DIR="$RESULTS_DIR/validation"
mkdir -p "$VALIDATION_DIR"

JACOCO_VERSION="0.8.12"
JACOCO_CLI="$HOME/.m2/repository/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar"

if [ ! -f "$JACOCO_CLI" ]; then
    echo "Downloading JaCoCo CLI..."
    mvn -q dependency:get -Dartifact=org.jacoco:org.jacoco.cli:${JACOCO_VERSION}:jar:nodeps
fi

# Create a temp file with exec->module mapping
VALIDATION_MAP=$(mktemp)
echo "Building exec->module mapping..."

# Build the mapping (avoid subshell by using process substitution)
count=0
while IFS= read -r exec_path; do
    # exec_path is like: /path/to/project/jacoco-pertest-results/exec/TestName.exec
    # We need to get back to the project directory
    # From exec file -> exec dir -> results dir -> project dir
    exec_dir=$(dirname "$exec_path")
    results_dir=$(dirname "$exec_dir")
    module_dir=$(dirname "$results_dir")
    exec_name=$(basename "$exec_path")
    echo "${exec_name}|${module_dir}" >> "$VALIDATION_MAP"
    count=$((count + 1))
done < <(find "$RESULTS_DIR/exec/" -name "*.exec" 2>/dev/null)

echo "Found $count exec files"
echo ""

# Select test exec files
mapfile -t SELECTED_TESTS < <(
    if [ "$MODE" = "single" ]; then
        base_pattern="${TEST_CLASS}_${TEST_METHOD}"
        ls "$RESULTS_DIR/exec/" 2>/dev/null | grep "^${base_pattern}" || true
    else
        ls "$RESULTS_DIR/exec/" 2>/dev/null | shuf | head -n "$NUM_TESTS"
    fi
)

if [ "${#SELECTED_TESTS[@]}" -eq 0 ]; then
    if [ "$MODE" = "single" ]; then
        echo -e "${RED}Error: No exec files found for ${TEST_CLASS}#${TEST_METHOD}${NC}"
    else
        echo -e "${RED}Error: No exec files found${NC}"
    fi
    exit 1
fi

if [ "$MODE" = "single" ]; then
    NUM_TESTS=${#SELECTED_TESTS[@]}
    echo "Validating specific test(s) for ${TEST_CLASS}#${TEST_METHOD}"
else
    echo "Randomly selected $NUM_TESTS tests for validation:"
fi
validation_count=0
validation_passed=0
validation_failed=0

# Temporarily disable exit on error
set +e

for exec_file in "${SELECTED_TESTS[@]}"; do
    validation_count=$((validation_count + 1))
    test_name="${exec_file%.exec}"
    echo ""
    echo -e "${YELLOW}[$validation_count/$NUM_TESTS]${NC} Validating: $test_name"
    
    # Parse test class and method from filename
    # Format: org.example.TestClass_testMethod or org.example.TestClass_testMethod_hash
    if [[ $test_name =~ ^(.+)_([^_]+)(_.+)?$ ]]; then
        test_class="${BASH_REMATCH[1]}"
        test_method="${BASH_REMATCH[2]}"
        
        # Find the module that contains this test
        module_dir=$(awk -F'|' -v name="$exec_file" '$1==name {print $2; exit}' "$VALIDATION_MAP")
        
        if [ -z "$module_dir" ] || [ ! -d "$module_dir" ]; then
            echo -e "  ${RED}✗ FAILED${NC}: Could not find module directory"
            validation_failed=$((validation_failed + 1))
            continue
        fi
        
        echo "  Module: $(basename $module_dir)"
        echo "  Test: ${test_class}#${test_method}"
        
        # For multi-module projects, find the actual submodule containing the test
        # Look for the test class file
        test_class_file=$(echo "$test_class" | tr '.' '/').java
        actual_module=$(find "$module_dir" -path "*/src/test/java/$test_class_file" -type f 2>/dev/null | head -1)
        
        if [ -n "$actual_module" ]; then
            # Get the module directory from the test file path
            # Extract everything before "/src/test/java/"
            module_dir=$(echo "$actual_module" | sed 's|/src/test/java/.*||')
            echo "  Actual module: $(basename $module_dir)"
        fi
        
        # Run the individual test with JaCoCo 
        individual_exec="$VALIDATION_DIR/${test_name}_individual.exec"
        
        # Temporarily restore original POM if it was modified
        NEED_RESTORE=0
        if [ -f "$module_dir/pom.xml.jacoco-backup" ]; then
            echo "  Temporarily restoring original POM..."
            cp "$module_dir/pom.xml" "$module_dir/pom.xml.jacoco-modified"
            cp "$module_dir/pom.xml.jacoco-backup" "$module_dir/pom.xml"
            NEED_RESTORE=1
        fi
        
        cd "$module_dir"
        # Clean any previous jacoco exec files
        rm -f "$module_dir/target/jacoco.exec" 2>/dev/null
        rm -f "$module_dir/target/jacoco-it.exec" 2>/dev/null
        
        # Run test with basic JaCoCo agent (no listener, direct file output)
        mvn -q test \
            -Dtest="${test_class}#${test_method}" \
            -Drat.skip=true \
            -Djacoco.skip=false \
            ${LEVEL0_TARGET_PACKAGE:+-Dlevel0.target.package=$LEVEL0_TARGET_PACKAGE} \
            -Dpertest.argLine= \
            -Dmaven.test.failure.ignore=true \
            org.jacoco:jacoco-maven-plugin:${JACOCO_VERSION}:prepare-agent \
            -Djacoco.destFile="$individual_exec" \
            > "$VALIDATION_DIR/${test_name}_run.log" 2>&1
        
        # Restore modified POM if needed
        if [ $NEED_RESTORE -eq 1 ]; then
            cp "$module_dir/pom.xml.jacoco-modified" "$module_dir/pom.xml"
        fi
        
        # Check multiple possible locations for the exec file
        if [ ! -f "$individual_exec" ]; then
            # Check standard Maven locations
            if [ -f "$module_dir/target/jacoco.exec" ]; then
                cp "$module_dir/target/jacoco.exec" "$individual_exec"
            elif [ -f "$module_dir/target/jacoco-pertest/jacoco.exec" ]; then
                cp "$module_dir/target/jacoco-pertest/jacoco.exec" "$individual_exec"
            elif [ -f "$module_dir/target/jacoco-it.exec" ]; then
                cp "$module_dir/target/jacoco-it.exec" "$individual_exec"
            else
                echo -e "  ${RED}✗ FAILED${NC}: Individual test did not generate coverage"
                echo "    Check log: $VALIDATION_DIR/${test_name}_run.log"
                validation_failed=$((validation_failed + 1))
                cd "$PROJECT_DIR"
                continue
            fi
        fi
        
        # Generate coverage reports for comparison
        pertest_report="$VALIDATION_DIR/${test_name}_pertest.xml"
        individual_report="$VALIDATION_DIR/${test_name}_individual.xml"
        
        CLASSES_DIR="$module_dir/target/classes"
        
        if [ ! -d "$CLASSES_DIR" ]; then
            echo -e "  ${RED}✗ FAILED${NC}: Classes directory not found"
            validation_failed=$((validation_failed + 1))
            cd "$PROJECT_DIR"
            continue
        fi
        
        # Generate reports
        java -jar "$JACOCO_CLI" report "$RESULTS_DIR/exec/$exec_file" \
            --classfiles "$CLASSES_DIR" \
            --xml "$pertest_report" \
            --name "pertest" 2>/dev/null
        
        java -jar "$JACOCO_CLI" report "$individual_exec" \
            --classfiles "$CLASSES_DIR" \
            --xml "$individual_report" \
            --name "individual" 2>/dev/null
        
        # Extract and compare coverage metrics
        if [ -f "$pertest_report" ] && [ -f "$individual_report" ]; then
            # Use Python script to reliably extract coverage from XML
            EXTRACT_SCRIPT="$SCRIPT_DIR/extract-coverage.py"
            
            pertest_coverage=$("$EXTRACT_SCRIPT" "$pertest_report")
            individual_coverage=$("$EXTRACT_SCRIPT" "$individual_report")
            
            pertest_covered=$(echo "$pertest_coverage" | cut -d' ' -f1)
            pertest_missed=$(echo "$pertest_coverage" | cut -d' ' -f2)
            individual_covered=$(echo "$individual_coverage" | cut -d' ' -f1)
            individual_missed=$(echo "$individual_coverage" | cut -d' ' -f2)
            
            if [ -n "$pertest_covered" ] && [ -n "$individual_covered" ] && [ "$pertest_covered" != "0" ] || [ "$individual_covered" != "0" ]; then
                pertest_total=$((pertest_covered + pertest_missed))
                individual_total=$((individual_covered + individual_missed))
                
                # Produce detailed diff file
                diff_file="$VALIDATION_DIR/${test_name}_diff.txt"
                python3 - "$pertest_report" "$individual_report" "$diff_file" <<'PY'
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

pertest, individual, output = sys.argv[1:]

def collect(path):
    root = ET.parse(path).getroot()
    stats = defaultdict(lambda: [0, 0])
    for pkg in root.findall('package'):
        pname = pkg.get('name')
        for cls in pkg.findall('class'):
            cname = f"{pname}.{cls.get('name').replace('/', '.')}"
            for counter in cls.findall('counter'):
                if counter.get('type') == 'INSTRUCTION':
                    stats[cname][0] += int(counter.get('covered'))
                    stats[cname][1] += int(counter.get('missed'))
    return stats

pt = collect(pertest)
ind = collect(individual)

rows = []
for key in set(pt) | set(ind):
    pc, pm = pt[key]
    ic, im = ind[key]
    if (pc, pm) != (ic, im):
        rows.append((abs(ic - pc), key, pc, pm, ic, im))

rows.sort(reverse=True)

with open(output, 'w') as f:
    f.write("Class Coverage Differences (per-test vs individual)\n")
    f.write("====================================================\n\n")
    if not rows:
        f.write("No differences detected.\n")
    else:
        for _, key, pc, pm, ic, im in rows:
            f.write(f"{key}\n")
            f.write(f"  per-test   : {pc}/{pc+pm} instructions\n")
            f.write(f"  individual : {ic}/{ic+im} instructions\n")
            f.write(f"  delta      : {ic - pc:+d}\n\n")
PY
                echo "  Diff report: $diff_file"

                # Calculate percentage difference
                if [ "$pertest_covered" -eq "$individual_covered" ]; then
                    echo -e "  ${GREEN}✓ MATCH${NC}: Both captured $pertest_covered/$pertest_total instructions"
                    validation_passed=$((validation_passed + 1))
                else
                    diff=$((pertest_covered - individual_covered))
                    if [ "$diff" -lt 0 ]; then diff=$((-diff)); fi
                    pct_diff=0
                    if [ "$individual_covered" -gt 0 ]; then
                        pct_diff=$((diff * 100 / individual_covered))
                    fi
                    
                    if [ "$pct_diff" -le 5 ]; then
                        echo -e "  ${GREEN}✓ CLOSE${NC}: Per-test: $pertest_covered/$pertest_total, Individual: $individual_covered/$individual_total (${pct_diff}% diff)"
                        validation_passed=$((validation_passed + 1))
                    else
                        echo -e "  ${YELLOW}⚠ DIFF${NC}: Per-test: $pertest_covered/$pertest_total, Individual: $individual_covered/$individual_total (${pct_diff}% diff)"
                        validation_failed=$((validation_failed + 1))
                    fi
                fi
            else
                echo -e "  ${RED}✗ FAILED${NC}: Could not extract coverage metrics"
                validation_failed=$((validation_failed + 1))
            fi
        else
            echo -e "  ${RED}✗ FAILED${NC}: Could not generate comparison reports"
            validation_failed=$((validation_failed + 1))
        fi
        
        cd "$PROJECT_DIR"
    else
        echo -e "  ${YELLOW}⚠ SKIPPED${NC}: Could not parse test name format"
        validation_failed=$((validation_failed + 1))
    fi
done

rm -f "$VALIDATION_MAP"

echo ""
echo -e "${GREEN}=========================================="
echo "Validation Summary"
echo -e "==========================================${NC}"
echo "  Passed: $validation_passed/$NUM_TESTS"
echo "  Failed: $validation_failed/$NUM_TESTS"
echo "  Results saved to: $VALIDATION_DIR"
echo ""
