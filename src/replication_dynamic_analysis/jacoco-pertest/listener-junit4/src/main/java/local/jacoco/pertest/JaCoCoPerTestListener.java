package local.jacoco.pertest;

import org.jacoco.agent.rt.IAgent;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;

/**
 * JUnit 4 RunListener that captures per-test coverage data using JaCoCo.
 * 
 * This listener hooks into the test lifecycle to dump coverage data after each test method.
 * The coverage data is saved to individual .exec files, one per test.
 * 
 * Usage:
 * 1. Ensure JaCoCo agent is attached to the JVM (via maven-surefire-plugin)
 * 2. Set system property: jacoco.pertest.output=/path/to/output/dir
 * 3. Configure surefire plugin to use this listener with the 'listener' property
 */
public class JaCoCoPerTestListener extends RunListener {

    private static final String[] EXCLUDED_CLASS_PREFIXES = {
        "net/bytebuddy/",
        "local/jacoco/pertest/JaCoCoPerTestListener",
        "local/jacoco/pertest/Level0CallTracker",
        "local/jacoco/level0/Level0ByteBuddyAgent$1"
    };

    private static final String OUTPUT_DIR_PROPERTY = "jacoco.pertest.output";
    private static final String JACOCO_AGENT_RT_CLASS = "org.jacoco.agent.rt.RT";
    private static final String JACOCO_DESTFILE_PROPERTY = "jacoco.destFile";
    private static final String PARAM_MAPPING_FILE = "test-parameter-mapping.csv";

    private File outputDir;
    private File jacocoDestFile;
    private IAgent jacocoAgent;
    private File paramMappingFile;
    private Description currentTest;
    private boolean initialized = false;

    public JaCoCoPerTestListener() {
        // Delay initialization until first use
    }

    private synchronized void initializeJaCoCoAgent() {
        if (initialized) return;
        initialized = true;

        String outputPath = System.getProperty(OUTPUT_DIR_PROPERTY);
        if (outputPath == null) {
            outputPath = System.getProperty("project.build.directory", "target") + "/jacoco-pertest";
            System.out.println("JaCoCo per-test: No output directory specified, using default: " + outputPath);
        }

        outputDir = new File(outputPath, "exec");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        paramMappingFile = new File(outputPath, PARAM_MAPPING_FILE);
        if (!paramMappingFile.exists()) {
            try {
                paramMappingFile.getParentFile().mkdirs();
                try (PrintWriter writer = new PrintWriter(new FileWriter(paramMappingFile))) {
                    writer.println("TestName,Hash,FullDisplayName,Parameters");
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not create parameter mapping file: " + e.getMessage());
            }
        }

        String destFilePath = System.getProperty(JACOCO_DESTFILE_PROPERTY);
        if (destFilePath != null) {
            jacocoDestFile = new File(destFilePath);
            System.out.println("JaCoCo destfile: " + jacocoDestFile.getAbsolutePath());
        }

        try {
            Class<?> rtClass = Class.forName(JACOCO_AGENT_RT_CLASS);
            Method getAgentMethod = rtClass.getMethod("getAgent");
            Object agent = getAgentMethod.invoke(null);
            if (agent instanceof IAgent) {
                jacocoAgent = (IAgent) agent;
            } else {
                System.err.println("WARNING: Retrieved JaCoCo agent does not implement IAgent; per-test coverage disabled.");
                jacocoAgent = null;
                return;
            }
            System.out.println("JaCoCo per-test JUnit4 listener initialized successfully");
            System.out.println("Coverage output directory: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("WARNING: Failed to initialize JaCoCo per-test listener: " + e.getMessage());
            System.err.println("Per-test coverage will not be collected. Ensure JaCoCo agent is attached.");
            jacocoAgent = null;
        }
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        initializeJaCoCoAgent();
        // Reset coverage at the very beginning
        if (jacocoAgent != null) {
            try {
                jacocoAgent.reset();
                System.out.println("JaCoCo per-test: Initial reset performed");
            } catch (Exception e) {
                System.err.println("Error performing initial JaCoCo reset: " + e.getMessage());
            }
        }
    }

    @Override
    public void testStarted(Description description) throws Exception {
        initializeJaCoCoAgent();
        currentTest = description;
        // Optionally, set display name hash for Level0CallTracker
        String displayName = description.getDisplayName();
        try {
            Class<?> trackerClass = Class.forName("local.jacoco.pertest.Level0CallTracker");
            java.lang.reflect.Method setHashMethod = trackerClass.getMethod("setDisplayNameHash", String.class);
            setHashMethod.invoke(null, displayName);
        } catch (Exception e) {
            // Level0CallTracker might not be available
        }
    }

    @Override
    public void testFinished(Description description) throws Exception {
        if (jacocoAgent == null || currentTest == null) {
            return; // JaCoCo not available
        }

        try {
            // Get test information
            String testClass = description.getClassName();
            String methodName = description.getMethodName();
            String displayName = description.getDisplayName();

            // Build test name (robust logic for parameterized/custom tests)
            String testName;
            boolean isParameterized = false;
            if (methodName != null && displayName != null) {
                // JUnit 4 parameterized: displayName like "methodName[0]" or "methodName[paramValue]"
                if (!displayName.equals(methodName + "(" + testClass + ")") &&
                    (displayName.contains("[") || !displayName.equals(methodName))) {
                    isParameterized = true;
                }
            }

            if (isParameterized) {
                // Parameterized display names are not stable, hash them so the coverage file name is deterministic.
                int fullHash = displayName.hashCode();
                String paramHash = String.format("%08x", fullHash);
                testName = testClass + "_" + methodName + "_" + paramHash;
                saveMappingToCSV(testClass + "." + methodName, paramHash, displayName, displayName);
            } else {
                testName = testClass + "_" + methodName;
            }

            testName = testName.replaceAll("[^a-zA-Z0-9._\\[\\]-]", "_");

            // Dump coverage data directly (prefer getExecutionData if available)
            byte[] coverageData = null;
            try {
                coverageData = jacocoAgent.getExecutionData(false);
            } catch (Exception e) {
                System.err.println("JaCoCo per-test: Failed to fetch execution data via agent API: " + e.getMessage());
                coverageData = null;
            }

            if (coverageData != null && coverageData.length > 0) {
                coverageData = trimCoverageData(coverageData);
            }

            if (coverageData != null && coverageData.length > 0) {
                File execFile = new File(outputDir, testName + ".exec");
                try (FileOutputStream fos = new FileOutputStream(execFile)) {
                    fos.write(coverageData);
                }
                System.out.println("JaCoCo per-test: Coverage saved for " + testName +
                                 " (" + coverageData.length + " bytes)");
            } else if (jacocoDestFile != null && jacocoDestFile.exists()) {
                try {
                    byte[] destBytes = Files.readAllBytes(jacocoDestFile.toPath());
                    destBytes = trimCoverageData(destBytes);
                    if (destBytes != null && destBytes.length > 0) {
                        File execFile = new File(outputDir, testName + ".exec");
                        try (FileOutputStream fos = new FileOutputStream(execFile)) {
                            fos.write(destBytes);
                        }
                        System.out.println("JaCoCo per-test: Coverage saved for " + testName +
                                         " (" + destBytes.length + " bytes)");
                    }
                } catch (IOException ioe) {
                    System.err.println("JaCoCo per-test: Failed to copy destfile for " + testName + ": " + ioe.getMessage());
                }
            } else {
                System.err.println("JaCoCo per-test: No coverage data for " + testName);
            }

            // Reset after dumping
            if (jacocoAgent != null) {
                jacocoAgent.reset();
            }

        } catch (Exception e) {
            System.err.println("Error capturing per-test coverage: " + e.getMessage());
            e.printStackTrace();
        } finally {
            currentTest = null;
        }
    }

    /**
     * Save the parameter mapping to CSV file for reference
     */
    private synchronized void saveMappingToCSV(String testName, String hash, String fullDisplayName, String params) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(paramMappingFile, true))) {
            String escapedDisplayName = escapeCsv(fullDisplayName);
            String escapedParams = escapeCsv(params);
            writer.println(String.format("%s,%s,%s,%s", testName, hash, escapedDisplayName, escapedParams));
        } catch (IOException e) {
            System.err.println("Warning: Could not write to parameter mapping file: " + e.getMessage());
        }
    }

    /**
     * Escape CSV special characters
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Remove classes without any hits to keep per-test exec files compact.
     */
    private byte[] trimCoverageData(byte[] coverageData) {
        if (coverageData == null || coverageData.length == 0) {
            return coverageData;
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(coverageData);
             ByteArrayOutputStream out = new ByteArrayOutputStream(coverageData.length)) {

            ExecutionDataStore execStore = new ExecutionDataStore();
            SessionInfoStore sessionStore = new SessionInfoStore();
            ExecutionDataReader reader = new ExecutionDataReader(in);
            reader.setExecutionDataVisitor(execStore);
            reader.setSessionInfoVisitor(sessionStore);
            while (reader.read()) {
                // consume all records
            }

            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            sessionStore.accept(writer);
            for (ExecutionData data : execStore.getContents()) {
                if (data.hasHits() && !isExcluded(data.getName())) {
                    writer.visitClassExecution(data);
                }
            }
            writer.flush();
            return out.toByteArray();
        } catch (IOException e) {
            System.err.println("Warning: Failed to trim coverage data: " + e.getMessage());
            return coverageData;
        }
    }

    private boolean isExcluded(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : EXCLUDED_CLASS_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
