package local.jacoco.level0;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * Alternative Level-0 agent implementation that relies on Byte Buddy for
 * instrumentation. This variant is intended to cooperate with other agents
 * (e.g. Mockito's Byte Buddy based mock maker) by leveraging Byte Buddy's
 * decorator support and by restricting transformations to the configured
 * target package.
 *
 * Usage:
 *   -javaagent:level0-agent.jar=bytebuddy -Dlevel0.target.package=org/assertj/
 *
 * The main {@link Level0Agent} (ASM based) remains available; this agent can
 * be selected by specifying the "bytebuddy" argument or by making this class
 * the entrypoint in a dedicated JAR.
 */
public final class Level0ByteBuddyAgent {

    private static final boolean DEBUG = Boolean.getBoolean("level0.debug");
    private Level0ByteBuddyAgent() {
        // utility
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        if (DEBUG) {
            System.out.println("[Level0ByteBuddyAgent] premain invoked (args=" + agentArgs + ")");
        }
        String targetPackage = System.getProperty("level0.target.package");
        if (targetPackage == null || targetPackage.isEmpty()) {
            System.err.println("[Level0ByteBuddyAgent] ERROR: level0.target.package system property not set!");
            System.err.println("[Level0ByteBuddyAgent] Usage: -Dlevel0.target.package=org/assertj/");
            return;
        }

        // Accept either dot or slash notation and normalise to dotted prefix
        targetPackage = targetPackage.replace('/', '.');
        if (!targetPackage.endsWith(".")) {
            targetPackage = targetPackage + ".";
        }

        if (DEBUG) {
            System.out.println("[Level0ByteBuddyAgent] Initialising for package prefix: " + targetPackage);
        }

        ElementMatcher.Junction<MethodDescription> baseMethodMatcher =
                not(isTypeInitializer()
                        .or(isAbstract())
                        .or(isNative())
                        .or(isSynthetic()));

        ElementMatcher.Junction<MethodDescription> constructorMatcher =
                baseMethodMatcher.and(isConstructor());

        ElementMatcher.Junction<MethodDescription> nonConstructorMatcher =
                baseMethodMatcher.and(not(isConstructor()));

        ElementMatcher.Junction<MethodDescription> testMethodMatcher =
                nonConstructorMatcher.and(net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith(nameContains("Test")));

        ElementMatcher.Junction<MethodDescription> appMethodMatcher =
                nonConstructorMatcher.and(not(testMethodMatcher));

        AgentBuilder.Transformer transformer = new AgentBuilder.Transformer() {
            @Override
            public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                    net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
                    net.bytebuddy.description.type.TypeDescription typeDescription,
                    ClassLoader classLoader,
                    JavaModule module,
                    java.security.ProtectionDomain protectionDomain) {
                return builder
                        .visit(Advice.to(TestMethodAdvice.class).on(testMethodMatcher))
                        .visit(Advice.to(ApplicationMethodAdvice.class).on(appMethodMatcher))
                        .visit(Advice.to(ConstructorAdvice.class).on(constructorMatcher));
            }
        };

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .ignore(nameStartsWith("java.")
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("org.mockito."))
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("local.jacoco.")))
                .type(nameStartsWith(targetPackage))
                .transform(transformer);

        if (DEBUG) {
            agentBuilder = agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut());
        }

        agentBuilder.installOn(instrumentation);
    }

    /**
     * Advice applied to test methods (any method annotated with an annotation whose
     * name contains {@code Test}).
     */
    public static class TestMethodAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Origin("#t") String className,
                                   @Advice.Origin("#m") String methodName) {
            local.jacoco.pertest.Level0CallTracker.startTest(className, methodName);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            local.jacoco.pertest.Level0CallTracker.endTest();
        }
    }

    /**
     * Advice applied to application methods (non-test methods). Records method entry
     * and exit so Level0CallTracker can maintain the call stack.
     */
    public static class ApplicationMethodAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Origin("#t") String className,
                                   @Advice.Origin("#m") String methodName,
                                   @Advice.Origin("#d") String descriptor) {
            local.jacoco.pertest.Level0CallTracker.recordMethodEntry(className, methodName, descriptor);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit() {
            local.jacoco.pertest.Level0CallTracker.recordMethodExit();
        }
    }

    /**
     * Separate advice for constructors: Byte Buddy cannot wrap constructor bodies with exception
     * handlers (required for {@link Advice.OnMethodExit} with {@code onThrowable}) so we keep a
     * dedicated transformer that emits minimal entry/exit hooks.
     */
    public static class ConstructorAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Origin("#t") String className,
                                   @Advice.Origin("#m") String methodName,
                                   @Advice.Origin("#d") String descriptor) {
            local.jacoco.pertest.Level0CallTracker.recordMethodEntry(className, methodName, descriptor);
        }

        @Advice.OnMethodExit
        public static void onExit() {
            local.jacoco.pertest.Level0CallTracker.recordMethodExit();
        }
    }
}
