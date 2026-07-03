package local.jacoco.pertest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Tracks level-0 function calls - direct calls from test code to application code.
 * 
 * This is a self-contained tracker that works with the agent's test method instrumentation.
 * No external coordination needed - the agent instruments test methods to call startTest/endTest.
 */
public class Level0CallTracker {
    
    // Thread-local to track per-test calls
    private static final ThreadLocal<Set<String>> currentTestCalls =
            ThreadLocal.withInitial(() -> ConcurrentHashMap.newKeySet());
    private static final ThreadLocal<Set<String>> currentTestCallsWithIds =
            ThreadLocal.withInitial(() -> ConcurrentHashMap.newKeySet());
    private static final ThreadLocal<Deque<CallFrame>> currentCallStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> callSequence = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<File> depthTempFile = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<PrintWriter> depthWriter = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<long[]> depthCallCount =
            ThreadLocal.withInitial(() -> new long[] {0L});
    
    private static final ThreadLocal<String> currentTestName = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<String> currentTestClassName = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<String> currentTestMethodName = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<File> currentOutputDir = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<String> expectedDisplayNameHash = ThreadLocal.withInitial(() -> null); // Keeps JUnit display-name hash from the listeners in sync
    private static final ThreadLocal<String> currentTestFileName = ThreadLocal.withInitial(() -> null);
    private static final ConcurrentHashMap<String, String> TEST_FILE_CACHE = new ConcurrentHashMap<>();
    private static final List<Path> SOURCE_ROOTS = new ArrayList<>();
    
    // Track call depth: 0 = in test method, 1+ = in app code called from app code
    private static final ThreadLocal<Integer> callDepth = ThreadLocal.withInitial(() -> 0);

    // Global output directory (set by system property)
    private static volatile File globalOutputDir = null;
    
    // Debug flag - only print messages if enabled
    private static final boolean DEBUG = Boolean.getBoolean("level0.debug");
    private static final boolean ONLY_TEST_FILENAME = Boolean.getBoolean("level0.only_test_filename");
    private static final String METHOD_INDEX_PROPERTY = "level0.method.map";
    private static final ConcurrentHashMap<String, Integer> METHOD_ID_LOOKUP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> METHOD_ID_TO_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<MethodSignature>> METHOD_SIGNATURES_BY_CLASS_METHOD =
            new ConcurrentHashMap<>();
    private static final Set<String> MISSING_METHOD_SIGNATURES = ConcurrentHashMap.newKeySet();
    private static volatile boolean METHOD_INDEX_READY = false;
    private static final Object METHOD_INDEX_LOCK = new Object();
    
    static {
        // Initialize output directory from system property
        String outputPath = System.getProperty("jacoco.pertest.output");
        if (outputPath != null) {
            globalOutputDir = new File(outputPath);
            if (!globalOutputDir.exists()) {
                globalOutputDir.mkdirs();
            }
            if (DEBUG) {
                System.out.println("[Level0] Output directory: " + globalOutputDir.getAbsolutePath());
            }
        } else {
            System.err.println("[Level0] WARNING: jacoco.pertest.output not set!");
        }
        ensureMethodIndexLoaded();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                flushPendingDepthTempFiles();
            } catch (Exception e) {
                if (DEBUG) {
                    System.out.println("[Level0] Shutdown flush failed: " + e.getMessage());
                }
            }
        }, "level0-shutdown-flush"));
    }
    
    /**
     * Set the display name hash for the current test (called by listener)
     * This helps coordinate naming between JaCoCo exec files and level-0 files
     */
    public static void setDisplayNameHash(String displayName) {
        if (displayName == null) {
            expectedDisplayNameHash.set(null);
            return;
        }
        int fullHash = displayName.hashCode();
        String paramHash = String.format("%08x", fullHash);
        expectedDisplayNameHash.set(paramHash);
        if (DEBUG) {
            System.out.println("[Level0] Display name hash set: " + paramHash + " for: " + displayName);
        }
    }
    
    /**
     * Called by instrumented test methods at the start
     */
    public static void startTest(String testClassName, String testMethodName) {
        if (ONLY_TEST_FILENAME) {
            String baseTestName = testClassName + "_" + testMethodName;
            String hash = expectedDisplayNameHash.get();
            String testName = hash != null ? baseTestName + "_" + hash : baseTestName;
            currentTestName.set(testName);
            currentTestClassName.set(testClassName);
            currentTestMethodName.set(testMethodName);
            currentTestFileName.set(resolveTestFileNameFromClass(testClassName));
            currentOutputDir.set(globalOutputDir);
            return;
        }
        // Use dots for package separator to match JaCoCo exec file naming
        String baseTestName = testClassName + "_" + testMethodName;
        
        // Check if we have a display name hash set by the listener (for parameterized tests)
        String hash = expectedDisplayNameHash.get();
        String testName;
        if (hash != null) {
            testName = baseTestName + "_" + hash;
        } else {
            // For non-parameterized tests or when listener hasn't set the hash yet
            testName = baseTestName;
        }
        
        currentTestName.set(testName);
        currentTestClassName.set(testClassName); // Store the actual test class name
        currentTestMethodName.set(testMethodName); // Store the actual test method name
        currentTestFileName.set(resolveTestFileNameFromClass(testClassName));
        currentOutputDir.set(globalOutputDir);
        currentTestCalls.get().clear();
        currentTestCallsWithIds.get().clear();
        currentCallStack.get().clear();
        callSequence.set(0);
        callDepth.set(0); // Reset depth to 0 when test starts
        initializeDepthWriter(testName, globalOutputDir);
        if (DEBUG) {
            System.out.println("[Level0] START TEST: " + testName);
            System.out.println("[Level0]   Base: " + baseTestName);
            System.out.println("[Level0]   Hash: " + (hash != null ? hash : "none"));
            System.out.println("[Level0]   Instrumented method: " + testClassName + "." + testMethodName);
        }
    }
    
    /**
     * Called to record potential level-0 calls
     * This is called by instrumented application code at method entry
     */
    public static void recordMethodEntry(String className, String methodName, String signature) {
        if (ONLY_TEST_FILENAME) {
            return;
        }
        // Instrumented code runs in every method that the agent touches.
        // We rely on the thread-local depth counter to ensure only the very first
        // frame entered from the test method is emitted as a "level-0" call.
        String testName = currentTestName.get();
        if (testName == null) {
            return; // Not in a test
        }
        
        // Check current call depth
        int depth = callDepth.get();

        // Track parent/child relationships for every application call
        Deque<CallFrame> stack = currentCallStack.get();
        CallFrame parent = stack.peek();
        String callId = nextCallId();
        String methodId = resolveMethodId(className, methodName, signature);
        CallFrame frame = new CallFrame(callId, className, methodName, signature, methodId);
        stack.push(frame);

        recordDepthCall(
                callId,
                parent != null ? parent.callId : null,
                parent != null ? parent.methodId : null,
                methodId,
                className,
                methodName,
                signature,
                depth);
        
        // Level-0 = depth 0 (direct call from test method)
        if (depth == 0) {
            String callSignature = className + "." + methodName + signature;
            currentTestCalls.get().add(callSignature);
            String callWithId = methodId + "," + className + "," + methodName + "," + signature;
            currentTestCallsWithIds.get().add(callWithId);
        }
        
        // Increment depth for nested calls
        callDepth.set(depth + 1);
    }


    private static boolean isTestHelperClass(String className) {
        if (className == null) {
            return false;
        }
        String normalized = className.replace('\\', '/').replace('.', '/');
        if (normalized.toLowerCase().contains("/test/")) {
            return true;
        }
        int slash = normalized.lastIndexOf('/');
        String simple = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return simple.startsWith("Test")
                || simple.endsWith("Test")
                || simple.endsWith("Tests")
                || simple.endsWith("TestCase");
    }
    
    /**
     * Called by instrumented application code at method exit
     */
    public static void recordMethodExit() {
        if (ONLY_TEST_FILENAME) {
            return;
        }
        if (currentTestName.get() == null) {
            return; // Not in a test
        }
        Deque<CallFrame> stack = currentCallStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        int depth = callDepth.get();
        if (depth > 0) {
            callDepth.set(depth - 1);
        }
    }

    /**
     * Called when execution enters a helper method that belongs to the test class itself.
     * Helper frames should increase the depth so that application calls originating from them
     * are not treated as direct level-0 interactions.
     */
    public static void enterTestHelper() {
        if (currentTestName.get() == null) {
            return;
        }
    }

    /**
     * Counterpart to {@link #enterTestHelper()} that pops the helper frame once it returns.
     */
    public static void exitTestHelper() {
        if (currentTestName.get() == null) {
            return;
        }
    }
    
    /**
     * Called by instrumented test methods at the end
     */
    public static void endTest() {
        String testName = currentTestName.get();
        String testClassName = currentTestClassName.get();
        String testFileName = currentTestFileName.get();
        File outputDir = currentOutputDir.get();
        
        if (ONLY_TEST_FILENAME) {
            writeTestFilename(testName, testClassName, testFileName, outputDir);
            currentTestName.remove();
            currentTestClassName.remove();
            currentTestMethodName.remove();
            currentTestFileName.remove();
            currentOutputDir.remove();
            currentTestCalls.remove();
            currentTestCallsWithIds.remove();
            currentCallStack.remove();
            callSequence.remove();
            callDepth.remove();
            expectedDisplayNameHash.remove();
            depthTempFile.remove();
            depthWriter.remove();
            depthCallCount.remove();
            return;
        }

        // Persist the captured call signatures right away so the wrapper can
        // post-process the level-0 files even if the JVM crashes later.
        if (DEBUG) {
            System.out.println("[Level0] END TEST: " + testName + ", calls=" + 
                              (currentTestCalls.get() != null ? currentTestCalls.get().size() : 0));
        }
        
        if (testName == null || outputDir == null) {
            if (DEBUG) {
                System.out.println("[Level0] Skipping endTest - testName or outputDir is null");
            }
            currentTestName.remove();
            currentTestClassName.remove();
            currentTestMethodName.remove();
            currentTestFileName.remove();
            currentOutputDir.remove();
            currentTestCalls.remove();
            callDepth.remove();
            return;
        }
        
        Set<String> callsWithIds = currentTestCallsWithIds.get();
        if (callsWithIds.isEmpty()) {
            if (DEBUG) {
                System.out.println("[Level0] No level-0 calls recorded for " + testName);
            }
        }
        if (!callsWithIds.isEmpty()) {
            try {
                File outputFile = new File(outputDir, testName + ".level0.methodid.csv");
                if (DEBUG) {
                    System.out.println("[Level0] Writing " + callsWithIds.size()
                            + " level-0 calls with method ids to: " + outputFile.getAbsolutePath());
                }
                try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                    writer.println("# Level-0 calls for test: " + testName);
                    writer.println("# Total calls: " + callsWithIds.size());
                    writer.println("# Columns: method_id,class_name,method_name,descriptor");
                    for (String call : callsWithIds) {
                        writer.println(call);
                    }
                }
            } catch (IOException e) {
                System.err.println("[Level0] Error saving level-0 calls with method ids: " + e.getMessage());
            }
        }

        writeDepthCalls(testName, outputDir);
        writeTestFilename(testName, testClassName, testFileName, outputDir);
        
        // Clean up thread locals
        currentTestName.remove();
        currentTestClassName.remove();
        currentTestMethodName.remove();
        currentTestFileName.remove();
        currentOutputDir.remove();
        currentTestCalls.remove();
        currentTestCallsWithIds.remove();
        currentCallStack.remove();
        callSequence.remove();
        callDepth.remove();
        expectedDisplayNameHash.remove();
        depthTempFile.remove();
        depthWriter.remove();
        depthCallCount.remove();
    }

    private static void writeDepthCalls(String testName, File outputDir) {
        PrintWriter writer = depthWriter.get();
        if (writer != null) {
            writer.flush();
            writer.close();
        }
        long count = depthCallCount.get()[0];
        if (outputDir == null || testName == null) {
            return;
        }
        File tempFile = depthTempFile.get();
        try {
            File depthFile = new File(outputDir, testName + ".level0.depth.csv");
            if (DEBUG) {
                System.out.println("[Level0] Writing " + count +
                        " depth>0 calls to: " + depthFile.getAbsolutePath());
            }
            try (PrintWriter outputWriter = new PrintWriter(new FileWriter(depthFile))) {
                if (tempFile != null && tempFile.exists()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            outputWriter.println(line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Level0] Error saving depth calls: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete() && DEBUG) {
                System.out.println("[Level0] Unable to delete temp depth file: " + tempFile.getAbsolutePath());
            }
        }
    }

    private static void writeTestFilename(
            String testName, String testClassName, String testFileName, File outputDir) {
        if (outputDir == null || testName == null || testClassName == null) {
            return;
        }
        File outputFile = new File(outputDir, testName + ".test_filename.txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            String resolved = testFileName;
            if (resolved == null || resolved.trim().isEmpty()) {
                resolved = resolveTestFilePath(testClassName);
            }
            if (resolved == null) {
                resolved = testClassName + ".java";
            }
            writer.println(resolved);
        } catch (IOException e) {
            System.err.println("[Level0] Error saving test filename: " + e.getMessage());
        }
    }

    private static String resolveTestFileNameFromClass(String testClassName) {
        if (testClassName == null || testClassName.trim().isEmpty()) {
            return null;
        }
        try {
            Class<?> testClass = Class.forName(testClassName, false,
                    Thread.currentThread().getContextClassLoader());
            try {
                java.lang.reflect.Field field = testClass.getDeclaredField("MY_TEST_FILE_NAME");
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    return null;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String) {
                    return (String) value;
                }
            } catch (NoSuchFieldException ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static String resolveTestFilePath(String testClassName) {
        String cached = TEST_FILE_CACHE.get(testClassName);
        if (cached != null) {
            return cached;
        }
        String relPath = testClassName.replace('.', '/') + ".java";
        List<Path> roots = getSourceRoots();
        for (Path root : roots) {
            Path candidate = root.resolve("src/test/java").resolve(relPath);
            if (Files.exists(candidate)) {
                String abs = candidate.toAbsolutePath().toString();
                TEST_FILE_CACHE.put(testClassName, abs);
                return abs;
            }
            candidate = root.resolve("src/main/java").resolve(relPath);
            if (Files.exists(candidate)) {
                String abs = candidate.toAbsolutePath().toString();
                TEST_FILE_CACHE.put(testClassName, abs);
                return abs;
            }
            candidate = root.resolve(relPath);
            if (Files.exists(candidate)) {
                String abs = candidate.toAbsolutePath().toString();
                TEST_FILE_CACHE.put(testClassName, abs);
                return abs;
            }
        }
        Path relName = Paths.get(relPath).getFileName();
        for (Path root : roots) {
            try {
                Path found = Files.walk(root, 10)
                        .filter(p -> p.getFileName().equals(relName))
                        .filter(p -> p.toString().replace('\\', '/').endsWith(relPath))
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    String abs = found.toAbsolutePath().toString();
                    TEST_FILE_CACHE.put(testClassName, abs);
                    return abs;
                }
            } catch (IOException e) {
                continue;
            }
        }
        return null;
    }

    private static List<Path> getSourceRoots() {
        if (!SOURCE_ROOTS.isEmpty()) {
            return SOURCE_ROOTS;
        }
        String prop = System.getProperty("level0.source.roots");
        if (prop != null && !prop.trim().isEmpty()) {
            String[] parts = prop.split(Pattern.quote(File.pathSeparator));
            for (String part : parts) {
                if (!part.trim().isEmpty()) {
                    SOURCE_ROOTS.add(Paths.get(part.trim()));
                }
            }
        }
        if (SOURCE_ROOTS.isEmpty()) {
            SOURCE_ROOTS.add(Paths.get(System.getProperty("user.dir")));
        }
        return SOURCE_ROOTS;
    }

    private static void flushPendingDepthTempFiles() {
        File outputDir = globalOutputDir;
        if (outputDir == null || !outputDir.isDirectory()) {
            return;
        }
        File[] tempFiles = outputDir.listFiles(
                (dir, name) -> name != null && name.endsWith(".level0.depth.tmp"));
        if (tempFiles == null || tempFiles.length == 0) {
            return;
        }
        for (File tempFile : tempFiles) {
            String name = tempFile.getName();
            String base = name.substring(0, name.length() - ".level0.depth.tmp".length());
            File depthFile = new File(outputDir, base + ".level0.depth.csv");
            try (PrintWriter outputWriter = new PrintWriter(new FileWriter(depthFile));
                 BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputWriter.println(line);
                }
            } catch (IOException e) {
                if (DEBUG) {
                    System.out.println("[Level0] Error flushing temp depth file: " +
                            tempFile.getAbsolutePath() + " -> " + e.getMessage());
                }
                continue;
            }
            if (!tempFile.delete() && DEBUG) {
                System.out.println("[Level0] Unable to delete temp depth file: " +
                        tempFile.getAbsolutePath());
            }
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String nextCallId() {
        int next = callSequence.get() + 1;
        callSequence.set(next);
        return String.format("%08x", next);
    }

    private static void initializeDepthWriter(String testName, File outputDir) {
        depthCallCount.get()[0] = 0;
        if (testName == null || outputDir == null) {
            depthTempFile.set(null);
            depthWriter.set(null);
            return;
        }
        File tempFile = new File(outputDir, testName + ".level0.depth.tmp");
        try {
            if (tempFile.exists() && !tempFile.delete() && DEBUG) {
                System.out.println("[Level0] Could not delete existing temp depth file: " + tempFile.getAbsolutePath());
            }
            PrintWriter writer = new PrintWriter(new FileWriter(tempFile));
            depthTempFile.set(tempFile);
            depthWriter.set(writer);
        } catch (IOException e) {
            depthTempFile.set(null);
            depthWriter.set(null);
            System.err.println("[Level0] Error initializing depth writer: " + e.getMessage());
        }
    }

    private static void recordDepthCall(
            String callId,
            String parentCallId,
            String parentMethodId,
            String methodId,
            String className,
            String methodName,
            String descriptor,
            int depth) {
        PrintWriter writer = depthWriter.get();
        if (writer == null) {
            return;
        }
        writer.println(
                safe(callId) + "," +
                safe(parentCallId) + "," +
                safe(parentMethodId) + "," +
                depth + "," +
                safe(methodId) + "," +
                safe(className) + "," +
                safe(methodName) + "," +
                safe(descriptor) + "," +
                safe(currentTestName.get()));
        depthCallCount.get()[0]++;
    }

    private static String resolveMethodId(String className, String methodName, String descriptor) {
        ensureMethodIndexLoaded();
        String normalizedClassName = normalizeClassNameForLookup(className);
        String params = parameterDescriptor(descriptor);
        String key = buildSignatureKey(normalizedClassName, methodName, params);
        Integer id = METHOD_ID_LOOKUP.get(key);
        if (id != null) {
            return Integer.toString(id);
        }
        Integer resolved = resolveMethodIdFromHierarchy(className, normalizedClassName, methodName, params);
        if (resolved != null) {
            METHOD_ID_LOOKUP.putIfAbsent(key, resolved);
            return Integer.toString(resolved);
        }

        String generalized = generalizeReferenceDescriptor(params);
        if (!generalized.equals(params)) {
            String generalizedKey = buildSignatureKey(normalizedClassName, methodName, generalized);
            Integer generalizedId = METHOD_ID_LOOKUP.get(generalizedKey);
            if (generalizedId != null) {
                METHOD_ID_LOOKUP.putIfAbsent(key, generalizedId);
                return Integer.toString(generalizedId);
            }
            generalizedId = resolveMethodIdFromHierarchy(className, normalizedClassName, methodName, generalized);
            if (generalizedId != null) {
                METHOD_ID_LOOKUP.putIfAbsent(key, generalizedId);
                return Integer.toString(generalizedId);
            }
        }
        Integer fallbackId = resolveMethodIdByName(normalizedClassName, methodName, params);
        if (fallbackId != null) {
            METHOD_ID_LOOKUP.putIfAbsent(key, fallbackId);
            return Integer.toString(fallbackId);
        }
        if (MISSING_METHOD_SIGNATURES.add(key) && DEBUG) {
            System.out.println("[Level0] Missing method index entry for: " + key);
        }
        return "-1";
    }

    private static String generalizeReferenceDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
            return descriptor != null ? descriptor : "";
        }
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        result.append('(');
        int i = 1;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int start = i;
            char ch = descriptor.charAt(i);
            if (ch == '[') {
                int arrayDepth = 0;
                while (i < descriptor.length() && descriptor.charAt(i) == '[') {
                    arrayDepth++;
                    i++;
                }
                if (i >= descriptor.length()) {
                    return descriptor;
                }
                char component = descriptor.charAt(i);
                if (component == 'L') {
                    changed = true;
                    while (i < descriptor.length() && descriptor.charAt(i) != ';') {
                        i++;
                    }
                    if (i < descriptor.length()) {
                        i++; // skip ';'
                    }
                    for (int depth = 0; depth < arrayDepth; depth++) {
                        result.append('[');
                    }
                    result.append("Ljava/lang/Object;");
                } else {
                    // primitive array; leave as-is
                    int end = i + 1;
                    result.append(descriptor, start, end);
                    i = end;
                }
            } else if (ch == 'L') {
                changed = true;
                while (i < descriptor.length() && descriptor.charAt(i) != ';') {
                    i++;
                }
                if (i < descriptor.length()) {
                    i++;
                }
                result.append("Ljava/lang/Object;");
            } else {
                result.append(ch);
                i++;
            }
        }
        if (i >= descriptor.length()) {
            return descriptor;
        }
        result.append(')');
        return changed ? result.toString() : descriptor;
    }

    private static String parameterDescriptor(String descriptor) {
        if (descriptor == null) {
            return "";
        }
        int end = descriptor.indexOf(')');
        if (end < 0) {
            return descriptor;
        }
        return descriptor.substring(0, end + 1);
    }

    private static String buildSignatureKey(String className, String methodName, String paramDescriptor) {
        return safe(className) + "#" + safe(methodName) + safe(paramDescriptor);
    }

    private static Integer resolveMethodIdByName(String className, String methodName, String paramDescriptor) {
        String lookupKey = safe(className) + "#" + safe(methodName);
        List<MethodSignature> signatures = METHOD_SIGNATURES_BY_CLASS_METHOD.get(lookupKey);
        if (signatures == null || signatures.isEmpty()) {
            return null;
        }
        if (signatures.size() == 1) {
            return signatures.get(0).methodId;
        }
        int targetArity = countParamsFromDescriptor(paramDescriptor);
        MethodSignature match = null;
        for (MethodSignature signature : signatures) {
            if (signature.arity == targetArity) {
                if (match != null) {
                    return null;
                }
                match = signature;
            }
        }
        return match != null ? match.methodId : null;
    }

    private static int countParamsFromDescriptor(String descriptor) {
        if (descriptor == null) {
            return 0;
        }
        int start = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (start < 0 || end < 0 || end <= start + 1) {
            return 0;
        }
        int count = 0;
        int i = start + 1;
        while (i < end) {
            char ch = descriptor.charAt(i);
            if (ch == '[') {
                while (i < end && descriptor.charAt(i) == '[') {
                    i++;
                }
                if (i >= end) {
                    break;
                }
                ch = descriptor.charAt(i);
            }
            if (ch == 'L') {
                int semi = descriptor.indexOf(';', i);
                if (semi == -1 || semi > end) {
                    break;
                }
                i = semi + 1;
                count++;
            } else {
                i++;
                count++;
            }
        }
        return count;
    }

    private static void ensureMethodIndexLoaded() {
        if (METHOD_INDEX_READY) {
            return;
        }
        synchronized (METHOD_INDEX_LOCK) {
            if (METHOD_INDEX_READY) {
                return;
            }
            String configuredPath = System.getProperty(METHOD_INDEX_PROPERTY);
            if (configuredPath == null || configuredPath.isEmpty()) {
                if (DEBUG) {
                    System.out.println("[Level0] No method index path configured; will emit method id -1.");
                }
                METHOD_INDEX_READY = true;
                return;
            }
            Path csvPath = Paths.get(configuredPath);
            if (!Files.exists(csvPath)) {
                if (DEBUG) {
                    System.out.println("[Level0] Method index file not found at " + csvPath);
                }
                METHOD_INDEX_READY = true;
                return;
            }
            loadMethodIndex(csvPath);
            METHOD_INDEX_READY = true;
        }
    }

    private static void loadMethodIndex(Path csvPath) {
        if (DEBUG) {
            System.out.println("[Level0] Loading method index from " + csvPath.toAbsolutePath());
        }
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            List<String> headers = parseCsvLine(headerLine);
            Map<String, Integer> columnIndex = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                columnIndex.put(headers.get(i), i);
            }
            int classIdx = columnIndex.getOrDefault("class", -1);
            int methodIdx = columnIndex.getOrDefault("method", -1);
            int constructorIdx = columnIndex.getOrDefault("constructor", -1);
            if (classIdx < 0 || methodIdx < 0 || constructorIdx < 0) {
                if (DEBUG) {
                    System.out.println("[Level0] Method index missing expected columns");
                }
                return;
            }
            long rowNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                List<String> values = parseCsvLine(line);
                if (values.isEmpty()) {
                    continue;
                }
                String className = getColumnValue(values, classIdx);
                String methodValue = getColumnValue(values, methodIdx);
                String constructorValue = getColumnValue(values, constructorIdx);
                if (className.isEmpty() || methodValue.isEmpty()) {
                    continue;
                }
                boolean isConstructor = Boolean.parseBoolean(constructorValue);
                String key = createSignatureKeyFromCsv(className, methodValue, isConstructor);
                if (key == null || key.isEmpty()) {
                    continue;
                }
                METHOD_ID_LOOKUP.putIfAbsent(key, (int) rowNumber);
                METHOD_ID_TO_CLASS.putIfAbsent(
                        String.valueOf(rowNumber),
                        normalizeClassNameForLookup(className));
                registerSignatureForFallback(key, (int) rowNumber);
            }
        } catch (IOException e) {
            System.err.println("[Level0] Unable to load method index: " + e.getMessage());
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null) {
            return values;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    current.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String getColumnValue(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index).trim();
    }

    private static String createSignatureKeyFromCsv(String className, String rawMethod, boolean isConstructor) {
        if (rawMethod == null || className == null) {
            return null;
        }
        String trimmed = rawMethod.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int bracketStart = trimmed.indexOf('[');
        String base = bracketStart >= 0 ? trimmed.substring(0, bracketStart) : trimmed;
        int slash = base.indexOf('/');
        String methodName = slash >= 0 ? base.substring(0, slash) : base;
        if (isConstructor) {
            methodName = "<init>";
        }
        String paramsSection = "";
        if (bracketStart >= 0 && trimmed.endsWith("]")) {
            paramsSection = trimmed.substring(bracketStart + 1, trimmed.length() - 1);
        }
        String paramDescriptor = buildParameterDescriptor(paramsSection);
        return buildSignatureKey(normalizeClassNameForLookup(className), methodName, paramDescriptor);
    }

    private static String buildParameterDescriptor(String paramsSection) {
        StringBuilder descriptor = new StringBuilder("(");
        if (paramsSection != null && !paramsSection.isEmpty()) {
            for (String type : splitParameterTypes(paramsSection)) {
                String desc = typeToDescriptor(type);
                descriptor.append(desc);
            }
        }
        descriptor.append(")");
        return descriptor.toString();
    }

    private static List<String> splitParameterTypes(String paramsSection) {
        List<String> results = new ArrayList<>();
        if (paramsSection == null || paramsSection.isEmpty()) {
            return results;
        }
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < paramsSection.length(); i++) {
            char ch = paramsSection.charAt(i);
            if (ch == '<') {
                genericDepth++;
            } else if (ch == '>') {
                if (genericDepth > 0) genericDepth--;
            }
            if (ch == ',' && genericDepth == 0) {
                results.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        String finalValue = current.toString().trim();
        if (!finalValue.isEmpty()) {
            results.add(finalValue);
        }
        return results;
    }

    private static String typeToDescriptor(String rawType) {
        String type = sanitizeTypeName(rawType);
        if (type.isEmpty()) {
            return "";
        }
        int arrayDepth = 0;
        while (type.endsWith("[]")) {
            arrayDepth++;
            type = type.substring(0, type.length() - 2);
        }
        if (type.endsWith("...")) {
            arrayDepth++;
            type = type.substring(0, type.length() - 3);
        }
        type = normalizeInnerClassNotation(type);
        String desc;
        switch (type) {
            case "byte":
                desc = "B";
                break;
            case "char":
                desc = "C";
                break;
            case "double":
                desc = "D";
                break;
            case "float":
                desc = "F";
                break;
            case "int":
                desc = "I";
                break;
            case "long":
                desc = "J";
                break;
            case "short":
                desc = "S";
                break;
            case "boolean":
                desc = "Z";
                break;
            case "void":
                desc = "V";
                break;
            default:
                if (isTypeVariable(type)) {
                    type = "java.lang.Object";
                }
                String normalized = type.replace('.', '/');
                desc = "L" + normalized + ";";
                break;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < arrayDepth; i++) {
            builder.append('[');
        }
        builder.append(desc);
        return builder.toString();
    }

    private static String normalizeInnerClassNotation(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean seenClassName = false;
        for (int i = 0; i < type.length(); i++) {
            char ch = type.charAt(i);
            if (ch == '.' && seenClassName && i + 1 < type.length() && Character.isUpperCase(type.charAt(i + 1))) {
                result.append('$');
                continue;
            }
            if (!seenClassName && Character.isUpperCase(ch)) {
                seenClassName = true;
            }
            result.append(ch);
        }
        return result.toString();
    }

    private static String sanitizeTypeName(String rawType) {
        if (rawType == null) {
            return "";
        }
        String type = rawType.trim();
        if (type.isEmpty()) {
            return type;
        }
        StringBuilder sb = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < type.length(); i++) {
            char ch = type.charAt(i);
            if (ch == '<') {
                genericDepth++;
                continue;
            }
            if (ch == '>') {
                if (genericDepth > 0) genericDepth--;
                continue;
            }
            if (genericDepth == 0) {
                sb.append(ch);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.startsWith("? extends ")) {
            cleaned = cleaned.substring("? extends ".length()).trim();
        } else if (cleaned.startsWith("? super ")) {
            cleaned = cleaned.substring("? super ".length()).trim();
        } else if (cleaned.startsWith("?")) {
            cleaned = "java.lang.Object";
        }
        if (cleaned.startsWith("extends ")) {
            cleaned = cleaned.substring("extends ".length()).trim();
        } else if (cleaned.startsWith("super ")) {
            cleaned = cleaned.substring("super ".length()).trim();
        }
        return cleaned;
    }

    private static boolean isTypeVariable(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        if (type.length() == 1 && Character.isUpperCase(type.charAt(0))) {
            return true;
        }
        // Generic parameter names often start with T, E, etc. and may have suffixes like "E1".
        for (int i = 0; i < type.length(); i++) {
            char ch = type.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        // Heuristic: uppercase first letter and no dot means it's a type variable.
        return Character.isUpperCase(type.charAt(0)) && type.indexOf('.') < 0;
    }

    private static String normalizeClassNameForLookup(String className) {
        if (className == null || className.isEmpty()) {
            return "";
        }
        int dollar = className.lastIndexOf('$');
        if (dollar >= 0 && dollar + 1 < className.length()) {
            String suffix = className.substring(dollar + 1);
            if (isAllDigits(suffix)) {
                String prefix = className.substring(0, dollar + 1);
                return prefix + "Anonymous" + suffix;
            }
        }
        return className;
    }

    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void registerSignatureForFallback(String key, int methodId) {
        if (key == null || key.isEmpty()) {
            return;
        }
        int hashIdx = key.indexOf('#');
        if (hashIdx <= 0) {
            return;
        }
        int parenIdx = key.indexOf('(', hashIdx + 1);
        if (parenIdx <= hashIdx) {
            return;
        }
        String className = key.substring(0, hashIdx);
        String methodName = key.substring(hashIdx + 1, parenIdx);
        String paramDescriptor = key.substring(parenIdx);
        String lookupKey = className + "#" + methodName;
        List<MethodSignature> signatures = METHOD_SIGNATURES_BY_CLASS_METHOD.computeIfAbsent(
                lookupKey, k -> new ArrayList<>());
        signatures.add(new MethodSignature(methodId, paramDescriptor, countParamsFromDescriptor(paramDescriptor)));
    }

    private static Integer resolveMethodIdFromHierarchy(
            String originalClassName,
            String normalizedClassName,
            String methodName,
            String paramDescriptor) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = Level0CallTracker.class.getClassLoader();
        }
        try {
            Class<?> current = Class.forName(originalClassName, false, loader);
            Set<String> visited = new HashSet<>();
            ArrayDeque<Class<?>> queue = new ArrayDeque<>();
            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }
            for (Class<?> iface : current.getInterfaces()) {
                queue.add(iface);
            }
            while (!queue.isEmpty()) {
                Class<?> candidate = queue.poll();
                if (candidate == null) {
                    continue;
                }
                String candidateName = candidate.getName();
                if (!visited.add(candidateName)) {
                    continue;
                }
                String candidateKey = buildSignatureKey(
                        normalizeClassNameForLookup(candidateName),
                        methodName,
                        paramDescriptor);
                Integer id = METHOD_ID_LOOKUP.get(candidateKey);
                if (id != null) {
                    METHOD_ID_LOOKUP.putIfAbsent(
                            buildSignatureKey(normalizedClassName, methodName, paramDescriptor),
                            id);
                    return id;
                }
                Class<?> superClass = candidate.getSuperclass();
                if (superClass != null) {
                    queue.add(superClass);
                }
                for (Class<?> iface : candidate.getInterfaces()) {
                    queue.add(iface);
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            if (DEBUG) {
                System.out.println("[Level0] Unable to inspect hierarchy for " + originalClassName + ": " + e);
            }
        }
        return null;
    }

    private static final class CallFrame {
        final String callId;
        final String className;
        final String methodName;
        final String descriptor;
        final String methodId;

        CallFrame(String callId, String className, String methodName, String descriptor, String methodId) {
            this.callId = callId;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.methodId = methodId;
        }
    }

    private static final class MethodSignature {
        final int methodId;
        final String paramDescriptor;
        final int arity;

        MethodSignature(int methodId, String paramDescriptor, int arity) {
            this.methodId = methodId;
            this.paramDescriptor = paramDescriptor;
            this.arity = arity;
        }
    }
}
