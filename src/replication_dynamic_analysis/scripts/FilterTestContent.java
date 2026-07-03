import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilterTestContent {
    private static final Set<String> LIFECYCLE_ANNOTATIONS = new HashSet<>();
    private static final Set<String> TEST_ANNOTATIONS = new HashSet<>();

    static {
        Collections.addAll(
            LIFECYCLE_ANNOTATIONS,
            "BeforeAll",
            "AfterAll",
            "BeforeEach",
            "AfterEach",
            "Before",
            "After",
            "BeforeClass",
            "AfterClass"
        );
        Collections.addAll(
            TEST_ANNOTATIONS,
            "Test",
            "ParameterizedTest",
            "RepeatedTest",
            "TestFactory",
            "TestTemplate"
        );
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: FilterTestContent <test_class> <test_method>");
            System.exit(1);
        }
        String targetClass = args[0];
        String targetMethod = args[1];

        StringBuilder sourceBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceBuilder.append(line).append("\n");
            }
        }
        String source = sourceBuilder.toString();
        if (source.isEmpty()) {
            System.out.print("");
            return;
        }

        ParserConfiguration config = new ParserConfiguration();
        JavaParser parser = new JavaParser(config);
        CompilationUnit cu = parser.parse(source).getResult().orElse(null);
        if (cu == null) {
            System.out.print(source);
            return;
        }

        String pkgName = cu.getPackageDeclaration()
            .map(pkg -> pkg.getNameAsString())
            .orElse("");

        List<Range> rangesToRedact = new ArrayList<>();
        List<Range> commentRangesToRedact = new ArrayList<>();
        Deque<String> classStack = new ArrayDeque<>();
        Range targetJavadocRange = null;

        Range[] targetJavadocRangeHolder = new Range[1];
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                classStack.push(n.getNameAsString());
                String fqcn = qualifiedName(pkgName, classStack);
                if (fqcn.equals(targetClass)) {
                    for (MethodDeclaration method : n.getMethods()) {
                        boolean isTarget = method.getNameAsString().equals(targetMethod);
                        boolean keep = isTarget || hasLifecycleAnnotation(method) || !isTestMethod(method);
                        if (isTarget) {
                            method.getJavadocComment()
                                .flatMap(comment -> comment.getRange())
                                .ifPresent(range -> targetJavadocRangeHolder[0] = range);
                        }
                        if (!keep && method.getRange().isPresent()) {
                            rangesToRedact.add(method.getRange().get());
                        }
                    }
                }
                super.visit(n, arg);
                classStack.pop();
            }
        }, null);

        targetJavadocRange = targetJavadocRangeHolder[0];
        for (var comment : cu.getAllContainedComments()) {
            if (comment.getRange().isEmpty()) {
                continue;
            }
            Range range = comment.getRange().get();
            if (targetJavadocRange != null && range.equals(targetJavadocRange)) {
                continue;
            }
            boolean insideRedactedMethod = false;
            for (Range methodRange : rangesToRedact) {
                if (isRangeWithin(range, methodRange)) {
                    insideRedactedMethod = true;
                    break;
                }
            }
            if (insideRedactedMethod) {
                continue;
            }
            commentRangesToRedact.add(range);
        }

        List<Integer> lineOffsets = computeLineOffsets(source);
        List<int[]> spans = new ArrayList<>();
        for (Range range : rangesToRedact) {
            int start = toIndex(lineOffsets, range.begin.line, range.begin.column);
            int end = toIndex(lineOffsets, range.end.line, range.end.column) + 1;
            spans.add(new int[]{start, end});
        }
        for (Range range : commentRangesToRedact) {
            int start = toIndex(lineOffsets, range.begin.line, range.begin.column);
            int end = toIndex(lineOffsets, range.end.line, range.end.column) + 1;
            spans.add(new int[]{start, end});
        }
        spans.sort((a, b) -> Integer.compare(b[0], a[0]));

        StringBuilder out = new StringBuilder(source);
        for (int[] span : spans) {
            int start = span[0];
            int end = span[1];
            if (start < 0 || end > out.length() || start >= end) {
                continue;
            }
            out.replace(start, end, "");
        }

        System.out.print(collapseBlankLines(out.toString()));
    }

    private static boolean hasLifecycleAnnotation(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                name = name.substring(lastDot + 1);
            }
            if (LIFECYCLE_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTestMethod(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                name = name.substring(lastDot + 1);
            }
            if (TEST_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static String qualifiedName(String pkgName, Deque<String> classStack) {
        List<String> parts = new ArrayList<>(classStack);
        Collections.reverse(parts);
        String joined = String.join(".", parts);
        if (pkgName == null || pkgName.isEmpty()) {
            return joined;
        }
        return pkgName + "." + joined;
    }

    private static List<Integer> computeLineOffsets(String source) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets;
    }

    private static int toIndex(List<Integer> lineOffsets, int line, int column) {
        int lineIndex = Math.max(0, line - 1);
        if (lineIndex >= lineOffsets.size()) {
            return -1;
        }
        return lineOffsets.get(lineIndex) + Math.max(0, column - 1);
    }

    private static boolean isRangeWithin(Range inner, Range outer) {
        return comparePosition(inner.begin.line, inner.begin.column, outer.begin.line, outer.begin.column) >= 0
            && comparePosition(inner.end.line, inner.end.column, outer.end.line, outer.end.column) <= 0;
    }

    private static int comparePosition(int lineA, int columnA, int lineB, int columnB) {
        if (lineA != lineB) {
            return Integer.compare(lineA, lineB);
        }
        return Integer.compare(columnA, columnB);
    }

    private static String collapseBlankLines(String source) {
        String normalized = source.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean previousBlank = false;
        for (String line : lines) {
            boolean isBlank = line.trim().isEmpty();
            if (isBlank && previousBlank) {
                continue;
            }
            out.append(line).append("\n");
            previousBlank = isBlank;
        }
        return out.toString();
    }
}
