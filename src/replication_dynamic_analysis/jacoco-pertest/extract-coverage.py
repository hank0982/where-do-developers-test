#!/usr/bin/env python3
"""
Extract coverage metrics from JaCoCo XML report.
Usage: extract-coverage.py <xml-file>
Outputs: covered missed (space-separated)
"""

import sys
import xml.etree.ElementTree as ET

def extract_coverage(xml_file):
    """Extract instruction coverage from JaCoCo XML report."""
    try:
        tree = ET.parse(xml_file)
        root = tree.getroot()
        
        # Find the report-level counter (the last one in the document)
        # JaCoCo XML structure:
        # <report>
        #   <package>
        #     <class>
        #       <counter type="INSTRUCTION" missed="X" covered="Y"/>
        #       ...
        #     </class>
        #     <counter type="INSTRUCTION" missed="X" covered="Y"/> <!-- package summary -->
        #   </package>
        #   <counter type="INSTRUCTION" missed="X" covered="Y"/> <!-- report summary -->
        # </report>
        
        # Get all INSTRUCTION counters at the report level (direct children of <report>)
        counters = root.findall("./counter[@type='INSTRUCTION']")
        
        if counters:
            # Use the report-level counter (should be the only one at this level)
            counter = counters[0]
            covered = counter.get('covered', '0')
            missed = counter.get('missed', '0')
            print(f"{covered} {missed}")
            return 0
        else:
            # If no report-level counter, something is wrong
            print("0 0", file=sys.stderr)
            return 1
            
    except Exception as e:
        print(f"Error parsing {xml_file}: {e}", file=sys.stderr)
        print("0 0")
        return 1

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: extract-coverage.py <xml-file>", file=sys.stderr)
        sys.exit(1)
    
    sys.exit(extract_coverage(sys.argv[1]))
