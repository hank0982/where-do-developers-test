package com.example.testanalyzer;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class Main {

    public static void main(String[] args) throws IOException {
        StaticJavaParser.getConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        Args parsed = Args.parse(args);
        if (parsed == null) {
            System.exit(1);
        }

        List<TestMethod> testMethods = new ArrayList<>();
        Files.walkFileTree(parsed.repoRoot(), new JavaSourceVisitor(parsed.repoRoot(), testMethods));
        writeCsv(parsed.outputCsv(), testMethods);
        System.out.println("[OK] Wrote " + parsed.outputCsv().toAbsolutePath());
    }

    private static void writeCsv(Path output, List<TestMethod> rows) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("Class,Method,File,AbsoluteFile,Line");
            writer.newLine();
            for (TestMethod row : rows) {
                writer.write(String.join(",",
                        csvEscape(row.className()),
                        csvEscape(row.methodName()),
                        csvEscape(row.sourceFile()),
                        csvEscape(row.absoluteFile()),
                        Integer.toString(row.line())));
                writer.newLine();
            }
        }
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static final class TestMethod {
        private final String className;
        private final String methodName;
        private final String sourceFile;
        private final String absoluteFile;
        private final int line;

        private TestMethod(String className, String methodName, String sourceFile, String absoluteFile, int line) {
            this.className = className;
            this.methodName = methodName;
            this.sourceFile = sourceFile;
            this.absoluteFile = absoluteFile;
            this.line = line;
        }

        private String className() {
            return className;
        }

        private String methodName() {
            return methodName;
        }

        private String sourceFile() {
            return sourceFile;
        }

        private String absoluteFile() {
            return absoluteFile;
        }

        private int line() {
            return line;
        }
    }

    private static final class JavaSourceVisitor implements FileVisitor<Path> {
        private final Path repoRoot;
        private final List<TestMethod> testMethods;

        private JavaSourceVisitor(Path repoRoot, List<TestMethod> testMethods) {
            this.repoRoot = repoRoot;
            this.testMethods = testMethods;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (isIgnoredDirectory(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!file.toString().endsWith(".java")) {
                return FileVisitResult.CONTINUE;
            }
            parseJavaFile(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            System.err.println("[WARN] Failed to read " + file + ": " + exc.getMessage());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                System.err.println("[WARN] Failed to walk " + dir + ": " + exc.getMessage());
            }
            return FileVisitResult.CONTINUE;
        }

        private void parseJavaFile(Path file) {
            try {
                CompilationUnit unit = StaticJavaParser.parse(file);
                String packageName = unit.getPackageDeclaration()
                        .map(pkg -> pkg.getName().asString())
                        .orElse("");
                Context context = new Context(packageName, file, repoRoot, testMethods);
                unit.accept(new TestMethodVisitor(), context);
            } catch (ParseProblemException e) {
                System.err.println("[WARN] Parse error in " + file + ": " + e.getMessage());
            } catch (IOException e) {
                System.err.println("[WARN] Read error in " + file + ": " + e.getMessage());
            }
        }

        private boolean isIgnoredDirectory(String name) {
            return name.equals(".git")
                    || name.equals("target")
                    || name.equals("out")
                    || name.equals("build")
                    || name.equals(".idea")
                    || name.equals(".gradle")
                    || name.equals(".mvn")
                    || name.equals("node_modules");
        }
    }

    private static final class Context {
        private final String packageName;
        private final Path file;
        private final Path repoRoot;
        private final List<TestMethod> testMethods;
        private final Deque<String> nesting;

        private Context(String packageName, Path file, Path repoRoot, List<TestMethod> testMethods) {
            this.packageName = packageName;
            this.file = file;
            this.repoRoot = repoRoot;
            this.testMethods = testMethods;
            this.nesting = new ArrayDeque<>();
        }
    }

    private static final class TestMethodVisitor extends VoidVisitorAdapter<Context> {
        @Override
        public void visit(ClassOrInterfaceDeclaration decl, Context context) {
            context.nesting.push(decl.getNameAsString());
            addMethods(decl.getMethods(), context);
            super.visit(decl, context);
            context.nesting.pop();
        }

        @Override
        public void visit(EnumDeclaration decl, Context context) {
            context.nesting.push(decl.getNameAsString());
            addMethods(decl.getMethods(), context);
            super.visit(decl, context);
            context.nesting.pop();
        }

        @Override
        public void visit(RecordDeclaration decl, Context context) {
            context.nesting.push(decl.getNameAsString());
            addMethods(decl.getMethods(), context);
            super.visit(decl, context);
            context.nesting.pop();
        }

        private void addMethods(List<MethodDeclaration> methods, Context context) {
            for (MethodDeclaration method : methods) {
                if (!hasTestAnnotation(method)) {
                    continue;
                }
                String className = buildClassName(context.packageName, context.nesting);
                int line = method.getBegin().map(pos -> pos.line).orElse(-1);
                String relPath = context.repoRoot.relativize(context.file.toAbsolutePath()).toString();
                String absPath = context.file.toAbsolutePath().normalize().toString();
                context.testMethods.add(new TestMethod(className, method.getNameAsString(), relPath, absPath, line));
            }
        }

        private String buildClassName(String packageName, Deque<String> nesting) {
            List<String> parts = new ArrayList<>(nesting);
            StringBuilder builder = new StringBuilder();
            if (!packageName.isEmpty()) {
                builder.append(packageName).append('.');
            }
            for (int i = parts.size() - 1; i >= 0; i--) {
                builder.append(parts.get(i));
                if (i != 0) {
                    builder.append('.');
                }
            }
            return builder.toString();
        }

        private boolean hasTestAnnotation(MethodDeclaration method) {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                if (isTestAnnotation(annotation)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isTestAnnotation(AnnotationExpr annotation) {
            String name = annotation.getNameAsString();
            if (name.equals("Test")) {
                return true;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            return normalized.endsWith(".test");
        }
    }

    private static final class Args {
        private final Path repoRoot;
        private final Path outputCsv;

        private Args(Path repoRoot, Path outputCsv) {
            this.repoRoot = repoRoot;
            this.outputCsv = outputCsv;
        }

        private Path repoRoot() {
            return repoRoot;
        }

        private Path outputCsv() {
            return outputCsv;
        }

        private static Args parse(String[] args) {
            if (args.length != 2) {
                printUsage();
                return null;
            }
            Path repoRoot = Paths.get(args[0]).toAbsolutePath().normalize();
            Path outputCsv = Paths.get(args[1]).toAbsolutePath().normalize();
            if (!Files.isDirectory(repoRoot)) {
                System.err.println("Repo root is not a directory: " + repoRoot);
                return null;
            }
            return new Args(repoRoot, outputCsv);
        }

        private static void printUsage() {
            System.err.println("Usage: java -jar java-test-analyzer.jar <repoRoot> <outputCsv>");
        }
    }
}
