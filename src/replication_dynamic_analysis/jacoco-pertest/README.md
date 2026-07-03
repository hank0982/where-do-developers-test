# JaCoCo Per-Test Coverage Solution

This folder contains utilities to enable per-test coverage collection using JaCoCo for any Maven-based Java project.

## Overview

This solution provides:
- A custom JUnit listener that captures coverage data for each individual test
- Maven configuration that can be easily applied to any project
- Scripts to automate the setup process
- Output in XML format for each test's coverage

## How It Works

1. **JaCoCo Java Agent**: Attaches to the JVM during test execution
2. **Custom Test Listener**: Hooks into JUnit test lifecycle to dump coverage after each test
3. **Per-Test Output**: Each test gets its own `.exec` file which is converted to XML

## Quick Start

### Option 1: Using the Maven Plugin (Recommended)

Add the following to your project's `pom.xml`:

```xml
<properties>
    <jacoco.version>0.8.12</jacoco.version>
    <pertest.output.dir>${project.build.directory}/jacoco-pertest</pertest.output.dir>
</properties>

<dependencies>
    <!-- Add the per-test listener as a test dependency -->
    <dependency>
        <groupId>local.jacoco</groupId>
        <artifactId>jacoco-pertest-listener</artifactId>
        <version>1.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>${jacoco.version}</version>
            <executions>
                <execution>
                    <id>prepare-agent</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                    <configuration>
                        <destFile>${project.build.directory}/jacoco-pertest/jacoco.exec</destFile>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <properties>
                    <configurationParameters>
                        junit.jupiter.extensions.autodetection.enabled=true
                    </configurationParameters>
                </properties>
                <systemPropertyVariables>
                    <jacoco.pertest.output>${pertest.output.dir}</jacoco.pertest.output>
                    <jacoco.destfile>${project.build.directory}/jacoco-pertest/jacoco.exec</jacoco.destfile>
                </systemPropertyVariables>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Option 2: Using the Automation Script

Run the setup script to automatically configure any Maven project:

```bash
./setup-pertest-coverage.sh /path/to/your/java/project
```

Then run tests:

```bash
cd /path/to/your/java/project
mvn clean test
```

## Output Structure

After running tests, you'll find:

```
target/
  jacoco-pertest/
    exec/
      com.example.TestClass_testMethod1.exec
      com.example.TestClass_testMethod2.exec
      ...
    xml/
      com.example.TestClass_testMethod1.xml
      com.example.TestClass_testMethod2.xml
      ...
```

Each XML file contains the coverage information for that specific test.

## Files in This Directory

- `listener/` - JUnit 5 test listener for per-test coverage
- `pom-template.xml` - Template Maven configuration snippet
- `setup-pertest-coverage.sh` - Script to setup projects automatically
- `apply-to-all-repos.sh` - Batch script for multiple repositories
- `README.md` - This file

## Requirements

- Java 8 or higher
- Maven 3.6+
- JUnit 5 (Jupiter) for projects using the listener
- JaCoCo 0.8.7 or higher

## Advanced Usage

### Custom Output Directory

Set the output directory via system property:

```bash
mvn test -Djacoco.pertest.output=/custom/path
```

### Filtering Classes

Add exclusions to the JaCoCo configuration:

```xml
<configuration>
    <excludes>
        <exclude>**/generated/**</exclude>
        <exclude>**/test/**</exclude>
    </excludes>
</configuration>
```

## Troubleshooting

### Tests not generating coverage

1. Ensure JaCoCo agent is attached (look for agent path in Maven output)
2. Check that the listener is in the test classpath
3. Verify system properties are set correctly

### Missing XML files

The XML files are generated in a post-processing step. Run:

```bash
./generate-xml-reports.sh /path/to/project
```

## License

This solution is provided as-is for research purposes.
