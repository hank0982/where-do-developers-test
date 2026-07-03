import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FindTestMethod {
    private static final Pattern HASH_SUFFIX = Pattern.compile("^(.*)_([0-9a-f]{8})$");

    public static void main(String[] args) throws Exception {
        String filePath = null;
        String testName = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--file") && i + 1 < args.length) {
                filePath = args[++i];
            } else if (arg.equals("--test-name") && i + 1 < args.length) {
                testName = args[++i];
            }
        }
        if (filePath == null || testName == null) {
            System.err.println("Usage: FindTestMethod --file <path> --test-name <name>");
            System.exit(1);
        }

        Path startFile = Paths.get(filePath);
        if (!Files.exists(startFile)) {
            System.out.println("{}");
            return;
        }

        Result result = resolve(startFile, testName);
        if (result == null) {
            System.out.println("{}");
            return;
        }
        String json = "{"
                + "\"class_name\":\"" + escape(result.className) + "\","
                + "\"method_name\":\"" + escape(result.methodName) + "\","
                + "\"file\":\"" + escape(result.filePath) + "\","
                + "\"line\":" + result.line
                + "}";
        System.out.println(json);
    }

    private static Result resolve(Path startFile, String testName) throws IOException {
        String source = Files.readString(startFile, StandardCharsets.UTF_8);
        CompilationUnit cu = parse(source);
        if (cu == null) {
            return null;
        }

        ClassMatch match = matchClassAndMethod(cu, testName);
        if (match == null) {
            return null;
        }

        String currentClass = match.className;
        String methodName = match.methodName;
        Path currentFile = startFile;
        for (int depth = 0; depth < 8; depth++) {
            CompilationUnit currentCu = parse(Files.readString(currentFile, StandardCharsets.UTF_8));
            if (currentCu == null) {
                return null;
            }
            ClassOrInterfaceDeclaration cls = findClass(currentCu, currentClass);
            if (cls != null) {
                Optional<MethodDeclaration> method = cls.getMethodsByName(methodName).stream().findFirst();
                if (method.isPresent() && method.get().getBegin().isPresent()) {
                    return new Result(
                            currentClass,
                            methodName,
                            currentFile.toAbsolutePath().toString(),
                            method.get().getBegin().get().line
                    );
                }
            }
            String parent = findParentClass(currentCu, currentClass);
            if (parent == null) {
                return null;
            }
            Path parentFile = resolveClassFile(parent, currentFile);
            if (parentFile == null) {
                return null;
            }
            currentClass = parent;
            currentFile = parentFile;
        }
        return null;
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration());
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return null;
        }
        return parsed.getResult().get();
    }

    private static ClassMatch matchClassAndMethod(CompilationUnit cu, String testName) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> classNames = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String fqcn = getQualifiedClassName(cls, pkg);
            classNames.add(fqcn);
        }
        String best = null;
        for (String fqcn : classNames) {
            String prefix = fqcn + "_";
            if (testName.startsWith(prefix)) {
                if (best == null || fqcn.length() > best.length()) {
                    best = fqcn;
                }
            }
        }
        if (best == null) {
            return null;
        }
        String remainder = testName.substring((best + "_").length());
        Matcher matcher = HASH_SUFFIX.matcher(remainder);
        if (matcher.matches()) {
            remainder = matcher.group(1);
        }
        if (remainder.isEmpty()) {
            return null;
        }
        return new ClassMatch(best, remainder);
    }

    private static ClassOrInterfaceDeclaration findClass(CompilationUnit cu, String fqcn) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String full = getQualifiedClassName(cls, pkg);
            if (full.equals(fqcn)) {
                return cls;
            }
        }
        return null;
    }

    private static String findParentClass(CompilationUnit cu, String fqcn) {
        ClassOrInterfaceDeclaration cls = findClass(cu, fqcn);
        if (cls == null || cls.getExtendedTypes().isEmpty()) {
            return null;
        }
        ClassOrInterfaceType parentType = cls.getExtendedTypes().get(0);
        String parent = parentType.getNameAsString();
        if (parent.contains(".")) {
            return parent;
        }
        Map<String, String> imports = buildImportMap(cu);
        if (imports.containsKey(parent)) {
            return imports.get(parent);
        }
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (!pkg.isEmpty()) {
            return pkg + "." + parent;
        }
        return parent;
    }

    private static Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.getImports().forEach(imp -> {
            if (!imp.isAsterisk()) {
                String name = imp.getNameAsString();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    map.put(name.substring(dot + 1), name);
                }
            }
        });
        return map;
    }

    private static Path resolveClassFile(String fqcn, Path currentFile) throws IOException {
        String rel = fqcn.replace('.', '/') + ".java";
        Path root = guessRoot(currentFile);
        if (root == null) {
            return null;
        }
        Path candidate = root.resolve("src/test/java").resolve(rel);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = root.resolve("src/main/java").resolve(rel);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = root.resolve(rel);
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path sibling = resolveInSiblingModules(root, rel);
        if (sibling != null) {
            return sibling;
        }
        return null;
    }

    private static Path guessRoot(Path filePath) {
        String normalized = filePath.toAbsolutePath().toString().replace('\\', '/');
        int idx = normalized.indexOf("/src/test/java/");
        if (idx >= 0) {
            return Paths.get(normalized.substring(0, idx));
        }
        idx = normalized.indexOf("/src/main/java/");
        if (idx >= 0) {
            return Paths.get(normalized.substring(0, idx));
        }
        return filePath.getParent();
    }

    private static String getQualifiedClassName(ClassOrInterfaceDeclaration cls, String pkg) {
        Deque<String> segments = new ArrayDeque<>();
        segments.addFirst(cls.getNameAsString());
        Optional<com.github.javaparser.ast.Node> parent = cls.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?>) {
                segments.addFirst(((TypeDeclaration<?>) parent.get()).getNameAsString());
            }
            parent = parent.get().getParentNode();
        }
        String nestedPath = String.join("$", segments);
        return pkg.isEmpty() ? nestedPath : pkg + "." + nestedPath;
    }

    private static Path resolveInSiblingModules(Path moduleRoot, String relPath) throws IOException {
        Path parent = moduleRoot.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(parent)) {
            for (Path sibling : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(sibling) || sibling.equals(moduleRoot)) {
                    continue;
                }
                Path candidate = sibling.resolve("src/test/java").resolve(relPath);
                if (Files.exists(candidate)) {
                    return candidate;
                }
                candidate = sibling.resolve("src/main/java").resolve(relPath);
                if (Files.exists(candidate)) {
                    return candidate;
                }
                candidate = sibling.resolve(relPath);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class ClassMatch {
        final String className;
        final String methodName;

        ClassMatch(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    private static class Result {
        final String className;
        final String methodName;
        final String filePath;
        final int line;

        Result(String className, String methodName, String filePath, int line) {
            this.className = className;
            this.methodName = methodName;
            this.filePath = filePath;
            this.line = line;
        }
    }
}
