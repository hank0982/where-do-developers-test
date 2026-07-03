#!/bin/bash

# Generate XML reports from per-test .exec files (parallel, multi-module aware)
# This script is used by jacoco-pertest-wrapper-v2.sh to keep that wrapper lean.
# Usage:
#   ./generate-xml-reports-parallel.sh \
#       <exec-dir> <xml-dir> <exec-module-map> <class-dirs-file> <source-dirs-file> <jacoco-cli-jar>
#
# Args:
#   exec-dir          Directory containing .exec files (copied/aggregated)
#   xml-dir           Destination directory for XML reports
#   exec-module-map   File with lines: <exec_filename>|<module_dir>
#   class-dirs-file   File listing candidate class directories (one per line)
#   source-dirs-file  File listing candidate source directories (one per line)
#   jacoco-cli-jar    Path to org.jacoco.cli-*-nodeps.jar

set -e

if [ "$#" -ne 6 ]; then
    echo "Usage: $0 <exec-dir> <xml-dir> <exec-module-map> <class-dirs-file> <source-dirs-file> <jacoco-cli-jar>"
    exit 1
fi

EXEC_DIR="$1"
XML_DIR="$2"
EXEC_MODULE_MAP="$3"
CLASS_DIRS_FILE="$4"
SOURCE_DIRS_FILE="$5"
JACOCO_CLI="$6"

[ -d "$EXEC_DIR" ] || { echo "Error: exec dir not found: $EXEC_DIR"; exit 1; }
[ -f "$EXEC_MODULE_MAP" ] || { echo "Error: exec-module-map not found: $EXEC_MODULE_MAP"; exit 1; }
[ -f "$CLASS_DIRS_FILE" ] || { echo "Error: class-dirs-file not found: $CLASS_DIRS_FILE"; exit 1; }
[ -f "$SOURCE_DIRS_FILE" ] || { echo "Error: source-dirs-file not found: $SOURCE_DIRS_FILE"; exit 1; }
[ -f "$JACOCO_CLI" ] || { echo "Error: JaCoCo CLI not found: $JACOCO_CLI"; exit 1; }

mkdir -p "$XML_DIR"

exec_count=$(wc -l < "$EXEC_MODULE_MAP")
echo "Generating XML reports with 8 workers..."
echo "  Exec files : $EXEC_DIR"
echo "  XML output : $XML_DIR"
echo "  Exec count : $exec_count"

set +e
PARALLEL_SCRIPT=$(mktemp)
cat > "$PARALLEL_SCRIPT" << 'EOF'
#!/bin/bash
exec_name="$1"
module_dir="$2"
XML_DIR="$3"
JACOCO_CLI="$4"
CLASS_DIRS_FILE="$5"
SOURCE_DIRS_FILE="$6"
EXEC_DIR="$7"

base_name=$(basename "$exec_name" .exec)
xml_file="$XML_DIR/${base_name}.xml"

declare -a class_args=()
declare -a source_args=()

CLASSES_DIR="$module_dir/target/classes"
if [ -d "$CLASSES_DIR" ]; then
    class_args+=(--classfiles "$CLASSES_DIR")
else
    while IFS= read -r dir; do
        [ -d "$dir" ] && class_args+=(--classfiles "$dir")
    done < "$CLASS_DIRS_FILE"
fi

SOURCE_DIR="$module_dir/src/main/java"
if [ -d "$SOURCE_DIR" ]; then
    source_args+=(--sourcefiles "$SOURCE_DIR")
else
    while IFS= read -r dir; do
        [ -d "$dir" ] && source_args+=(--sourcefiles "$dir")
    done < "$SOURCE_DIRS_FILE"
fi

[ ${#class_args[@]} -eq 0 ] && exit 1

if java -jar "$JACOCO_CLI" report "$EXEC_DIR/$exec_name" \
    "${class_args[@]}" \
    "${source_args[@]}" \
    --xml "$xml_file" \
    --name "$base_name" 2>/dev/null; then
    echo "SUCCESS"
else
    exit 1
fi
EOF
chmod +x "$PARALLEL_SCRIPT"

processed=0
progress_prefix="$XML_DIR/.progress_"

while IFS='|' read -r exec_name module_dir; do
    [ -f "$EXEC_DIR/$exec_name" ] || continue
    while [ "$(jobs -r | wc -l)" -ge 8 ]; do sleep 0.1; done
    (
        if "$PARALLEL_SCRIPT" "$exec_name" "$module_dir" "$XML_DIR" "$JACOCO_CLI" "$CLASS_DIRS_FILE" "$SOURCE_DIRS_FILE" "$EXEC_DIR"; then
            echo "SUCCESS" > "${progress_prefix}$$_$RANDOM"
        else
            echo "FAILED" > "${progress_prefix}$$_$RANDOM"
        fi
    ) &
    ((processed++))
    if [ $((processed % 200)) -eq 0 ]; then
        current_success=$(find "$XML_DIR" -name ".progress_*" -exec cat {} \; 2>/dev/null | grep -c "SUCCESS" || echo 0)
        echo "  Progress: $processed/$exec_count processed, $current_success XML generated..."
    fi
done < "$EXEC_MODULE_MAP"

wait
xml_count=$(find "$XML_DIR" -name ".progress_*" -exec cat {} \; 2>/dev/null | grep -c "SUCCESS" || echo 0)
failed=$(find "$XML_DIR" -name ".progress_*" -exec cat {} \; 2>/dev/null | grep -c "FAILED" || echo 0)

rm -f "${progress_prefix}"*
rm -f "$PARALLEL_SCRIPT"
set -e

echo "✓ Generated $xml_count XML reports"
[ "$failed" -gt 0 ] 2>/dev/null && echo "  ($failed files skipped - no classes found or invalid)"
echo "Location: $XML_DIR"
