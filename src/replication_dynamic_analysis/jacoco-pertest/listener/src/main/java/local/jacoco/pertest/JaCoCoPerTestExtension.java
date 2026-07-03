package local.jacoco.pertest;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * JUnit 5 Extension that captures per-test coverage data using JaCoCo.
 * 
 * This extension hooks into the test lifecycle to dump coverage data after each test method.
 * The coverage data is saved to individual .exec files, one per test.
 * 
 * Usage:
 * 1. Ensure JaCoCo agent is attached to the JVM (via maven-surefire-plugin)
 * 2. Set system property: jacoco.pertest.output=/path/to/output/dir
 * 3. This extension will automatically be detected if junit.jupiter.extensions.autodetection.enabled=true
 * 
 * Or explicitly register it:
 * @ExtendWith(JaCoCoPerTestExtension.class)
 */
public class JaCoCoPerTestExtension implements BeforeEachCallback, AfterEachCallback {

    private static final String OUTPUT_DIR_PROPERTY = "jacoco.pertest.output";
    private static final String JACOCO_AGENT_RT_CLASS = "org.jacoco.agent.rt.RT";
    private static final String JACOCO_DESTFILE_PROPERTY = "jacoco.destFile";
    private static final String PARAM_MAPPING_FILE = "test-parameter-mapping.csv";
    
    private File outputDir;
    private File jacocoDestFile;
    private Object jacocoAgent;
    private Method getAgentMethod;
    private Method dumpMethod;
    private Method resetMethod;
    private File paramMappingFile;

    public JaCoCoPerTestExtension() {
        initializeJaCoCoAgent();
    }

    /**
     * Initialize connection to JaCoCo agent via reflection
     */
    private void initializeJaCoCoAgent() {
        String outputPath = System.getProperty(OUTPUT_DIR_PROPERTY);
        if (outputPath == null) {
            outputPath = System.getProperty("project.build.directory", "target") + "/jacoco-pertest";
            // System.out.println("JaCoCo per-test: No output directory specified, using default: " + outputPath);
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
            getAgentMethod = rtClass.getMethod("getAgent");
            jacocoAgent = getAgentMethod.invoke(null);
            
            // Get methods from agent
            dumpMethod = jacocoAgent.getClass().getMethod("dump", boolean.class);
            resetMethod = jacocoAgent.getClass().getMethod("reset");
            
            // System.out.println("JaCoCo per-test listener initialized successfully");
            // System.out.println("Coverage output directory: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("WARNING: Failed to initialize JaCoCo per-test listener: " + e.getMessage());
            System.err.println("Per-test coverage will not be collected. Ensure JaCoCo agent is attached.");
            jacocoAgent = null;
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        // Reset coverage data before each test
        if (jacocoAgent != null && resetMethod != null) {
            try {
                resetMethod.invoke(jacocoAgent);
            } catch (Exception e) {
                System.err.println("Error resetting JaCoCo data: " + e.getMessage());
            }
        }
        
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (jacocoAgent == null) {
            return; // JaCoCo not available
        }

        try {
            // Get test information
            String testClass = context.getTestClass()
                    .map(Class::getName)
                    .orElse("UnknownClass");
            
            // Get the method name
            String methodName = context.getTestMethod()
                    .map(Method::getName)
                    .orElse("unknownMethod");
            
            // Get display name which includes parameter information for parameterized tests
            String displayName = context.getDisplayName();
            
            // Build test name
            String testName;
            
            if (displayName.endsWith("()")) {
                // Regular (non-parameterized) test
                testName = testClass + "_" + methodName;
            } else {
                // Parameterized test - use hash of display name for uniqueness
                // (display names often contain generated arguments that would make the file name invalid)
                int fullHash = displayName.hashCode();
                String paramHash = String.format("%08x", fullHash);
                
                testName = testClass + "_" + methodName + "_" + paramHash;
                
                // Save mapping for reference
                saveMappingToCSV(testClass + "." + methodName, paramHash, displayName, displayName);
            }
            
            // Sanitize filename - allow alphanumeric, dots, dashes, underscores, and brackets
            testName = testName.replaceAll("[^a-zA-Z0-9._\\[\\]-]", "_");
            
            // Force the agent to write data to the destfile
            // dump(false) forces a write without resetting
            // The dump() call is synchronous and ensures data is flushed
            dumpMethod.invoke(jacocoAgent, false);
            
            // Give JaCoCo a moment to ensure the file is fully written to disk
            // This is a safety measure for file system flush
            Thread.sleep(50);
            
            // Now copy the destfile to our per-test location
            if (jacocoDestFile != null && jacocoDestFile.exists()) {
                File execFile = new File(outputDir, testName + ".exec");
                // This copy operation is synchronous and blocking
                java.nio.file.Files.copy(
                    jacocoDestFile.toPath(), 
                    execFile.toPath(), 
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                long size = execFile.length();
                // System.out.println("JaCoCo per-test: Coverage saved for " + testName + 
                //                  " (" + size + " bytes)");
            } else {
                System.out.println("JaCoCo per-test: Destfile not available, trying dump method");
                // Fallback to dump() method
                byte[] coverageData = (byte[]) dumpMethod.invoke(jacocoAgent, false);
                if (coverageData != null && coverageData.length > 0) {
                    File execFile = new File(outputDir, testName + ".exec");
                    try (FileOutputStream fos = new FileOutputStream(execFile)) {
                        fos.write(coverageData);
                    }
                    // System.out.println("JaCoCo per-test: Coverage saved for " + testName + 
                    //                  " (" + coverageData.length + " bytes)");
                }
            }
                        
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
            // Escape CSV special characters
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
        // If contains comma, quote, or newline, wrap in quotes and escape quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
