package local.jacoco.pertest;

import org.jacoco.agent.rt.IAgent;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * JUnit Platform TestExecutionListener that captures per-test coverage for ALL test types.
 * 
 * This listener works with:
 * - JUnit Jupiter (JUnit 5) tests
 * - JUnit Vintage (JUnit 4 tests running on JUnit Platform)
 * - Any other test engine on the JUnit Platform
 * 
 * Usage:
 * Register in META-INF/services/org.junit.platform.launcher.TestExecutionListener
 */
public class JaCoCoPerTestPlatformListener implements TestExecutionListener {

    private static final String OUTPUT_DIR_PROPERTY = "jacoco.pertest.output";
    private static final String JACOCO_AGENT_RT_CLASS = "org.jacoco.agent.rt.RT";
    private static final String JACOCO_DESTFILE_PROPERTY = "jacoco.destFile";
    private static final String PARAM_MAPPING_FILE = "test-parameter-mapping.csv";
    
    private static final String[] EXCLUDED_CLASS_PREFIXES = {
        "net/bytebuddy/",
        "local/jacoco/pertest/JaCoCoPerTestListener",
        "local/jacoco/pertest/Level0CallTracker",
        "local/jacoco/level0/Level0ByteBuddyAgent$1"
    };

    private File outputDir;
    private File jacocoDestFile;
    private IAgent jacocoAgent;
    private File paramMappingFile;
    private boolean initialized = false;

    public JaCoCoPerTestPlatformListener() {
        // Constructor is called early, but we delay initialization until first use
    }

    /**
     * Initialize connection to JaCoCo agent via reflection
     */
    private synchronized void initializeJaCoCoAgent() {
        if (initialized) {
            return;
        }
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
        
        // Initialize parameter mapping CSV file
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

        // Get the JaCoCo destfile location
        String destFilePath = System.getProperty(JACOCO_DESTFILE_PROPERTY);
        if (destFilePath != null) {
            jacocoDestFile = new File(destFilePath);
            System.out.println("JaCoCo destfile: " + jacocoDestFile.getAbsolutePath());
        }

        try {
            // Access JaCoCo agent via reflection
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
            
            System.out.println("JaCoCo per-test Platform listener initialized successfully");
            System.out.println("Coverage output directory: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("WARNING: Failed to initialize JaCoCo per-test listener: " + e.getMessage());
            System.err.println("Per-test coverage will not be collected. Ensure JaCoCo agent is attached.");
            jacocoAgent = null;
        }
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        initializeJaCoCoAgent();
        
        // Reset coverage at the very beginning to ensure clean state
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
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!testIdentifier.isTest()) {
            return; // Only process actual tests, not containers
        }

        // Set the display name hash for Level0CallTracker to use
        // This ensures level-0 files have the same naming as JaCoCo exec files
        String displayName = testIdentifier.getDisplayName();
        try {
            Class<?> trackerClass = Class.forName("local.jacoco.pertest.Level0CallTracker");
            java.lang.reflect.Method setHashMethod = trackerClass.getMethod("setDisplayNameHash", String.class);
            setHashMethod.invoke(null, displayName);
        } catch (Exception e) {
            // Level0CallTracker might not be available, that's okay
            if (Boolean.getBoolean("level0.debug")) {
                System.out.println("[JaCoCo Listener] Could not set display name hash: " + e.getMessage());
            }
        }
        
        // Note: We do NOT reset here. We reset AFTER dumping in executionFinished.
        // This ensures each test starts with a clean slate after the previous test's
        // data has been captured.
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, org.junit.platform.engine.TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) {
            return; // Only process actual tests, not containers
        }

        if (jacocoAgent == null) {
            return; // JaCoCo not available
        }

        try {
            // Get test information from TestIdentifier
            String uniqueId = testIdentifier.getUniqueId().toString();
            String displayName = testIdentifier.getDisplayName();

            // Debug output
            boolean debug = Boolean.getBoolean("jacoco.pertest.debug");
            if (debug) {
                System.out.println("[JaCoCo Listener] uniqueId: " + uniqueId);
                System.out.println("[JaCoCo Listener] displayName: " + displayName);
            }

            String testClass = "UnknownClass";
            String methodName = "unknownMethod";

            // The JUnit Platform encodes the test engine, class and method inside the unique id.
            // Parse it carefully so parameterized and template invocations end up with stable names.
            // Extract class name
            int classStart = uniqueId.indexOf("[class:") + 7;
            if (classStart > 6) {
                int classEnd = uniqueId.indexOf("]", classStart);
                if (classEnd > classStart) {
                    testClass = uniqueId.substring(classStart, classEnd);
                }
            } else {
                // Try Vintage format
                classStart = uniqueId.indexOf("[runner:") + 8;
                if (classStart > 7) {
                    int classEnd = uniqueId.indexOf("]", classStart);
                    if (classEnd > classStart) {
                        testClass = uniqueId.substring(classStart, classEnd);
                    }
                }
            }

            // Extract method name (improved for parameterized)
            int methodStart = uniqueId.indexOf("[method:") + 8;
            if (methodStart > 7) {
                int methodEnd = uniqueId.indexOf("]", methodStart);
                if (methodEnd > methodStart) {
                    String methodPart = uniqueId.substring(methodStart, methodEnd);
                    if (methodPart.contains("(")) {
                        methodName = methodPart.substring(0, methodPart.indexOf("("));
                    } else {
                        methodName = methodPart;
                    }
                }
            } else {
                // Try test-template format (parameterized tests)
                methodStart = uniqueId.indexOf("[test-template:") + 15;
                if (methodStart > 14) {
                    int methodEnd = uniqueId.indexOf("]", methodStart);
                    if (methodEnd > methodStart) {
                        String methodPart = uniqueId.substring(methodStart, methodEnd);
                        if (methodPart.contains("(")) {
                            methodName = methodPart.substring(0, methodPart.indexOf("("));
                        } else {
                            methodName = methodPart;
                        }
                    }
                } else {
                    // Try invocation format (for test-template-invocation)
                    methodStart = uniqueId.indexOf("[test:") + 6;
                    if (methodStart > 5) {
                        int methodEnd = uniqueId.indexOf("]", methodStart);
                        if (methodEnd > methodStart) {
                            String methodPart = uniqueId.substring(methodStart, methodEnd);
                            if (methodPart.contains("(")) {
                                methodName = methodPart.substring(0, methodPart.indexOf("("));
                            } else {
                                methodName = methodPart;
                            }
                        }
                    }
                }
            }

            // Build test name
            String testName;

            // Check if this is a parameterized test (display name differs from method name)
            if (displayName.equals(methodName + "()") || displayName.equals(methodName)) {
                testName = testClass + "_" + methodName;
            } else {
                // Parameterized / templated tests encode arguments in the display name.
                // Hash the user-facing text so the resulting filename stays short and filesystem-safe.
                int fullHash = displayName.hashCode();
                String paramHash = String.format("%08x", fullHash);
                testName = testClass + "_" + methodName + "_" + paramHash;
                saveMappingToCSV(testClass + "." + methodName, paramHash, displayName, displayName);
            }

            // Sanitize filename
            testName = testName.replaceAll("[^a-zA-Z0-9._\\[\\]-]", "_");

            byte[] coverageData = jacocoAgent.getExecutionData(false);
            coverageData = trimCoverageData(coverageData);

            if (coverageData != null && coverageData.length > 0) {
                File execFile = new File(outputDir, testName + ".exec");
                try (FileOutputStream fos = new FileOutputStream(execFile)) {
                    fos.write(coverageData);
                }
                System.out.println("JaCoCo per-test: Coverage saved for " + testName +
                                 " (" + coverageData.length + " bytes)");
            }

            jacocoAgent.reset();

        } catch (Exception e) {
            System.err.println("Error capturing per-test coverage: " + e.getMessage());
            e.printStackTrace();
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
     * Remove classes without hits to keep per-test exec files compact.
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
