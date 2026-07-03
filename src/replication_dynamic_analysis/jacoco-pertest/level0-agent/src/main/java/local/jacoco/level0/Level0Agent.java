package local.jacoco.level0;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Java Agent for tracking level-0 method calls.
 * 
 * This agent:
 * 1. Instruments test methods to call Level0CallTracker.startTest() / endTest()
 * 2. Instruments application code to call Level0CallTracker.recordMethodEntry()
 * 
 * Usage: java -javaagent:level0-agent.jar -Dlevel0.target.package=org/assertj/
 */
public class Level0Agent {

    private static String targetPackage = null;
    private static final boolean DEBUG = Boolean.getBoolean("level0.debug");
    
    // Track transformed classes to prevent duplicate transformation (important for multi-release JARs)
    private static final Set<String> transformedClasses = ConcurrentHashMap.newKeySet();

    public static void premain(String agentArgs, Instrumentation inst) {
        if (DEBUG) {
            System.out.println("[Level0Agent] premain invoked (args=" + agentArgs + ")");
        }
        // Allow the wrapper to opt into the Byte Buddy implementation by passing "=bytebuddy".
        // The ASM path below remains the default because it keeps the agent tiny and fast.
        if (agentArgs != null && agentArgs.contains("bytebuddy")) {
            Level0ByteBuddyAgent.premain(agentArgs, inst);
            return;
        }
        
        // Read target package from system property (required)
        targetPackage = System.getProperty("level0.target.package");
        if (targetPackage == null || targetPackage.isEmpty()) {
            System.err.println("[Level0Agent] ERROR: level0.target.package system property not set!");
            System.err.println("[Level0Agent] Usage: -Dlevel0.target.package=org/assertj/");
            return;
        }
        
        // Normalize: ensure ends with /
        if (!targetPackage.endsWith("/")) {
            targetPackage = targetPackage + "/";
        }
        
        if (DEBUG) {
            System.out.println("[Level0Agent] Initialized - target package: " + targetPackage);
        }
        
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                // Skip retransformation - only transform on initial load
                if (classBeingRedefined != null) {
                    return null;
                }
                if (loader == null) {
                    // Bootstrap class; skip it
                    return null;
                }
                if (shouldSkipClass(className)) {
                    return null;
                }
                if (DEBUG) {
                    System.out.println("[Level0Agent] Transforming " + className);
                }
                
                // Skip if already transformed (belt and suspenders approach)
                if (!transformedClasses.add(className)) {
                    if (DEBUG) {
                        System.out.println("[Level0Agent] Skipping already transformed class: " + className);
                    }
                    return null;
                }
                
                try {
                    ClassReader reader = new ClassReader(classfileBuffer);
                    
                    // Determine if this is a test class
                    boolean isTestClass = isTestClass(protectionDomain, loader, className);
                    
                    // Both test and application classes now inject exception handlers.
                    // Recompute frames to keep verifier stackmaps valid on newer JVMs.
                    int computeFlags = ClassWriter.COMPUTE_FRAMES;
                    ClassWriter writer = new ConservativeFrameClassWriter(reader, computeFlags, loader);
                    
                    if (isTestClass && DEBUG) {
                        System.out.println("[Level0Agent] Instrumenting TEST class: " + className);
                    }
                    
                    ClassVisitor visitor = new Level0ClassVisitor(writer, className, isTestClass);
                    reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                    return writer.toByteArray();
                } catch (Exception e) {
                    System.err.println("Error instrumenting " + className + ": " + e.getMessage());
                    return null;
                }
            }
        });
    }

    /**
     * Frame computation for test classes can trigger eager class loading via ASM's
     * default getCommonSuperClass() resolution. We resolve hierarchy info from
     * class bytes (plus a reflection fallback) to keep frame merges precise
     * without forcing eager class initialization.
     */
    static final class ConservativeFrameClassWriter extends ClassWriter {
        private final TypeHierarchyResolver hierarchyResolver;

        ConservativeFrameClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.hierarchyResolver = new TypeHierarchyResolver(loader);
        }

        @Override
        protected String getCommonSuperClass(final String type1, final String type2) {
            String common = hierarchyResolver.commonSuperClass(type1, type2);
            if (common == null) {
                return "java/lang/Object";
            }
            return common;
        }
    }

    static final class TypeHierarchyResolver {
        private static final String OBJECT = "java/lang/Object";
        private final ClassLoader loader;
        private final Map<String, ClassInfo> cache = new HashMap<>();

        TypeHierarchyResolver(ClassLoader loader) {
            this.loader = loader;
        }

        String commonSuperClass(String type1, String type2) {
            if (type1 == null || type2 == null) {
                return OBJECT;
            }
            if (type1.equals(type2)) {
                return type1;
            }
            if (isArray(type1) || isArray(type2)) {
                return OBJECT;
            }
            if (isAssignableFrom(type1, type2)) {
                return type1;
            }
            if (isAssignableFrom(type2, type1)) {
                return type2;
            }

            ClassInfo info1 = classInfo(type1);
            ClassInfo info2 = classInfo(type2);
            if (info1 == null || info2 == null) {
                return OBJECT;
            }
            if (info1.isInterface || info2.isInterface) {
                return OBJECT;
            }

            String current = info1.superName;
            while (current != null) {
                if (isAssignableFrom(current, type2)) {
                    return current;
                }
                ClassInfo currentInfo = classInfo(current);
                if (currentInfo == null) {
                    return OBJECT;
                }
                current = currentInfo.superName;
            }
            return OBJECT;
        }

        private boolean isArray(String internalName) {
            return internalName != null && internalName.startsWith("[");
        }

        private boolean isAssignableFrom(String expectedSuper, String candidateSub) {
            if (expectedSuper == null || candidateSub == null) {
                return false;
            }
            if (expectedSuper.equals(candidateSub) || OBJECT.equals(expectedSuper)) {
                return true;
            }
            return isSubtype(candidateSub, expectedSuper, new HashSet<>());
        }

        private boolean isSubtype(String candidateSub, String expectedSuper, Set<String> seen) {
            if (!seen.add(candidateSub)) {
                return false;
            }
            if (candidateSub.equals(expectedSuper)) {
                return true;
            }
            ClassInfo info = classInfo(candidateSub);
            if (info == null) {
                return false;
            }

            for (String itf : info.interfaces) {
                if (isSubtype(itf, expectedSuper, seen)) {
                    return true;
                }
            }
            if (info.superName != null) {
                return isSubtype(info.superName, expectedSuper, seen);
            }
            return false;
        }

        private ClassInfo classInfo(String internalName) {
            if (internalName == null) {
                return null;
            }
            ClassInfo cached = cache.get(internalName);
            if (cached != null) {
                return cached;
            }

            ClassInfo resolved = resolveFromResource(internalName);
            if (resolved == null) {
                resolved = resolveWithReflection(internalName);
            }
            if (resolved != null) {
                cache.put(internalName, resolved);
            }
            return resolved;
        }

        private ClassInfo resolveFromResource(String internalName) {
            String resourceName = internalName + ".class";
            try (InputStream in = openResource(resourceName)) {
                if (in == null) {
                    return null;
                }
                ClassReader reader = new ClassReader(in);
                return new ClassInfo(
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0,
                    reader.getSuperName(),
                    reader.getInterfaces()
                );
            } catch (IOException e) {
                return null;
            }
        }

        private InputStream openResource(String resourceName) throws IOException {
            if (loader != null) {
                InputStream in = loader.getResourceAsStream(resourceName);
                if (in != null) {
                    return in;
                }
            }
            return ClassLoader.getSystemResourceAsStream(resourceName);
        }

        private ClassInfo resolveWithReflection(String internalName) {
            try {
                ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
                Class<?> clazz = Class.forName(internalName.replace('/', '.'), false, effectiveLoader);
                Class<?> superclass = clazz.getSuperclass();
                Class<?>[] interfaces = clazz.getInterfaces();
                String[] interfaceNames = new String[interfaces.length];
                for (int i = 0; i < interfaces.length; i++) {
                    interfaceNames[i] = interfaces[i].getName().replace('.', '/');
                }
                return new ClassInfo(
                    clazz.isInterface(),
                    superclass == null ? null : superclass.getName().replace('.', '/'),
                    interfaceNames
                );
            } catch (LinkageError | ClassNotFoundException e) {
                return null;
            }
        }
    }

    static final class ClassInfo {
        final boolean isInterface;
        final String superName;
        final String[] interfaces;

        ClassInfo(boolean isInterface, String superName, String[] interfaces) {
            this.isInterface = isInterface;
            this.superName = superName;
            this.interfaces = interfaces != null ? interfaces : new String[0];
        }
    }
    
    private static boolean shouldSkipClass(String className) {
        if (className == null) return true;
        
        // Skip agent infrastructure itself (MUST be checked first to avoid infinite recursion)
        if (className.startsWith("local/jacoco/")) {
            return true;
        }
        // Skip well-known test/tooling frameworks we never want to weave
        if (className.startsWith("org/junit")) {
            return true;
        }
        if (className.startsWith("org/apache/maven")) {
            return true;
        }
        // Only instrument classes inside the configured target package
        if (targetPackage != null && !className.startsWith(targetPackage)) {
            return true;
        }
        
        
        return false;
    }
    
    /**
     * Check if this is a test class
     */
    private static boolean isTestClass(ProtectionDomain protectionDomain, ClassLoader loader, String className) {
        URL location = null;
        if (protectionDomain != null && protectionDomain.getCodeSource() != null) {
            location = protectionDomain.getCodeSource().getLocation();
        }
        if (DEBUG) {
            System.out.println("[Level0Agent] Checking if test class: " + className);
            System.out.println("[Level0Agent]   Location: " + location);
            System.out.println("[Level0Agent]   Loader: " + loader);
        }
        if (location != null) {
            if (isPathMarkedAsTest(location)) {
                return true;
            }
        } else if (loader != null && className != null) {
            String resourceName = className + ".class";
            URL resource = loader.getResource(resourceName);
            if (resource != null && isPathMarkedAsTest(resource)) {
                return true;
            }
        }
        if (className != null && looksLikeTestName(className)) {
            return true;
        }
        return false;
    }

    private static boolean isPathMarkedAsTest(URL url) {
        Path path = resolvePath(url);
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.getNameCount(); i++) {
            String segment = path.getName(i).toString().toLowerCase(Locale.ROOT);
            if (segment.equals("test") ||
                segment.equals("tests") ||
                segment.equals("test-classes") ||
                segment.endsWith("-test") ||
                segment.endsWith("-tests") ||
                segment.contains("test-classes")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolvePath(URL url) {
        if (url == null) {
            return null;
        }
        try {
            String protocol = url.getProtocol();
            if ("jar".equalsIgnoreCase(protocol)) {
                String spec = url.getFile();
                int bang = spec.indexOf('!');
                if (bang >= 0) {
                    spec = spec.substring(0, bang);
                }
                URL nested = new URL(spec);
                return Paths.get(nested.toURI()).toAbsolutePath().normalize();
            }
            if ("file".equalsIgnoreCase(protocol)) {
                return Paths.get(url.toURI()).toAbsolutePath().normalize();
            }
        } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            // Ignore and fall through to null
        }
        return null;
    }

    private static boolean looksLikeTestName(String className) {
        String normalized = className.replace('\\', '/');
        if (normalized.contains("/test/")) {
            return true;
        }
        int slash = normalized.lastIndexOf('/');
        String simple = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dollar = simple.indexOf('$');
        if (dollar >= 0) {
            simple = simple.substring(0, dollar);
        }
        return simple.startsWith("Test")
                || simple.endsWith("Test")
                || simple.endsWith("Tests")
                || simple.endsWith("TestCase");
    }
    
    /**
     * ClassVisitor that adds level-0 tracking to methods
     */
    static class Level0ClassVisitor extends ClassVisitor {
        private final String className;
        private final boolean isTestClass;
        
        public Level0ClassVisitor(ClassVisitor cv, String className, boolean isTestClass) {
            super(Opcodes.ASM9, cv);
            this.className = className.replace('/', '.');
            this.isTestClass = isTestClass;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            
            // Skip abstract methods, native methods, and synthetic methods
            if (mv == null || 
                (access & Opcodes.ACC_ABSTRACT) != 0 ||
                (access & Opcodes.ACC_NATIVE) != 0 ||
                (access & Opcodes.ACC_SYNTHETIC) != 0) {
                return mv;
            }
            
            // For test classes, wrap method visitor to detect @Test annotation
            if (isTestClass && !name.equals("<init>") && !name.equals("<clinit>")) {
                return new TestMethodDetector(mv, access, name, descriptor, className);
            } else if (!isTestClass) {
                // Instrument application code to track method entries
                // Note: All app methods are instrumented, but Level0CallTracker.recordMethodEntry()
                // will only record if the immediate caller is a test method (level-0 check)
                // This means doSomething() called from hi() will be instrumented but NOT recorded
                return new Level0MethodVisitor(mv, access, name, descriptor, className);
            }
            
            return mv;
        }
    }
    
    /**
     * MethodVisitor that detects @Test annotation and wraps with TestMethodVisitor if found
     */
    static class TestMethodDetector extends MethodVisitor {
        private final int access;
        private final String name;
        private final String descriptor;
        private final String className;
        private boolean hasTestAnnotation = false;
        private final MethodVisitor originalMv;
        private boolean wrapped = false;
        
        public TestMethodDetector(MethodVisitor mv, int access, String name, 
                                 String descriptor, String className) {
            super(Opcodes.ASM9, mv);
            this.originalMv = mv;
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.className = className;
        }
        
        @Override
        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
            // Check for any annotation containing "Test" in the descriptor
            // This catches JUnit 4 @Test, JUnit 5 @Test, @ParameterizedTest, @RepeatedTest, etc.
            // and other testing frameworks like TestNG, Spock, etc.
            if (annotationDescriptor.contains("Test")) {
                hasTestAnnotation = true;
            }
            return super.visitAnnotation(annotationDescriptor, visible);
        }
        
        @Override
        public void visitCode() {
            if (!wrapped) {
                MethodVisitor target;
                if (hasTestAnnotation) {
                    target = new TestMethodVisitor(originalMv, access, name, descriptor, className);
                    if (DEBUG) {
                        System.out.println("[Level0Agent] Instrumenting test method: " + className + "." + name);
                    }
                } else {
                    target = new TestHelperMethodVisitor(originalMv, access, name, descriptor, className);
                    if (DEBUG) {
                        System.out.println("[Level0Agent] Instrumenting test helper: " + className + "." + name);
                    }
                }
                this.mv = target;
                wrapped = true;
            }
            super.visitCode();
        }
    }
    
    /**
     * MethodVisitor that wraps test methods with startTest/endTest
     */
    static class TestMethodVisitor extends AdviceAdapter {
        private final boolean isStatic;
        private final String declaredClass;
        private final String methodName;
        private final Label startLabel = new Label();
        private final Label endLabel = new Label();
        private final Label handlerLabel = new Label();
        
        protected TestMethodVisitor(MethodVisitor mv, int access, String name,
                                String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.declaredClass = className;
            this.methodName = name;
        }
        
        @Override
        protected void onMethodEnter() {
            if (isStatic) {
                mv.visitLdcInsn(declaredClass);
            } else {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
            }
            mv.visitLdcInsn(methodName);
            mv.visitMethodInsn(INVOKESTATIC, "local/jacoco/pertest/Level0CallTracker",
                        "startTest", "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitLabel(startLabel);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) {
                return;
            }
            emitEndTest();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(endLabel);
            mv.visitTryCatchBlock(startLabel, endLabel, handlerLabel, null);
            mv.visitLabel(handlerLabel);
            int exceptionLocal = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionLocal);
            emitEndTest();
            mv.visitVarInsn(ALOAD, exceptionLocal);
            mv.visitInsn(ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitEndTest() {
            mv.visitMethodInsn(INVOKESTATIC,
                "local/jacoco/pertest/Level0CallTracker",
                "endTest",
                "()V",
                false);
        }
    }
    
    /**
     * MethodVisitor that injects tracking code at method entry for application code
     */
    static class Level0MethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String descriptor;
        private final String className;
        private final Label startLabel = new Label();
        private final Label endLabel = new Label();
        private final Label handlerLabel = new Label();
        
        protected Level0MethodVisitor(MethodVisitor mv, int access, String name,
                                    String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
            this.methodName = name;
            this.descriptor = descriptor;
            this.className = className;
        }
        
        @Override
        protected void onMethodEnter() {
            // Call Level0CallTracker.recordMethodEntry(className, methodName, descriptor)
            // The tracker will handle the stack analysis to determine if this is a level-0 call
            emitRecordMethodEntry();
            mv.visitLabel(startLabel);
        }
        
        @Override
        protected void onMethodExit(int opcode) {
            // Exceptional exits are handled by a synthetic try/finally in visitMaxs.
            if (opcode == ATHROW) {
                return;
            }
            emitRecordMethodExit();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(endLabel);
            mv.visitTryCatchBlock(startLabel, endLabel, handlerLabel, null);
            mv.visitLabel(handlerLabel);
            int exceptionLocal = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionLocal);
            emitRecordMethodExit();
            mv.visitVarInsn(ALOAD, exceptionLocal);
            mv.visitInsn(ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }

        private void emitRecordMethodEntry() {
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(methodName);
            mv.visitLdcInsn(descriptor);
            mv.visitMethodInsn(INVOKESTATIC,
                "local/jacoco/pertest/Level0CallTracker",
                "recordMethodEntry",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                false);
        }

        private void emitRecordMethodExit() {
            // Call Level0CallTracker.recordMethodExit() to decrement depth.
            mv.visitMethodInsn(INVOKESTATIC,
                "local/jacoco/pertest/Level0CallTracker",
                "recordMethodExit",
                "()V",
                false);
        }
    }

    /**
     * MethodVisitor that treats non-@Test methods inside test classes as helper frames.
     * Helpers should not show up in level-0 output, but they must still bump the depth so
     * indirect calls from helpers don't look like direct test-to-app interactions.
     */
    static class TestHelperMethodVisitor extends AdviceAdapter {
        protected TestHelperMethodVisitor(MethodVisitor mv, int access, String name,
                                          String descriptor, String className) {
            super(Opcodes.ASM9, mv, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            mv.visitMethodInsn(INVOKESTATIC,
                "local/jacoco/pertest/Level0CallTracker",
                "enterTestHelper",
                "()V",
                false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            mv.visitMethodInsn(INVOKESTATIC,
                "local/jacoco/pertest/Level0CallTracker",
                "exitTestHelper",
                "()V",
                false);
        }
    }
}
