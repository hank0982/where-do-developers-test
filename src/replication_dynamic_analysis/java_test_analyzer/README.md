# Java Test Analyzer

Scans a Maven repository and lists every method annotated with `@Test` (JUnit 4/5 or any fully-qualified `*.Test`).

## Build

```bash
mvn -f java_test_analyzer/pom.xml package
```

This produces a fat JAR at `java_test_analyzer/target/java-test-analyzer-1.0.0.jar`.

## Run

```bash
java -jar java_test_analyzer/target/java-test-analyzer-1.0.0.jar /path/to/repo /path/to/output/tests.csv
```

The CSV columns are:

```
Class,Method,File,AbsoluteFile,Line
```

Notes:
- The scanner skips common build/cache directories (`target`, `build`, `.git`, etc.).
- File paths are relative to the repo root you pass in.
