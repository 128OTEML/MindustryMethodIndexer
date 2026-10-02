package indexer;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import indexer.model.FileIndex;
import indexer.model.InnerInfo;
import indexer.model.MemberInfo;
import indexer.model.ParamInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
public class JavaAnalyzer {

    private static boolean EXTRACT_CALLS = true;

    private static final Map<String, String> CALL_CACHE = new ConcurrentHashMap<>();

    public static void setExtractCalls(boolean b) {
        EXTRACT_CALLS = b;
    }

    public static boolean isExtractCalls() {
        return EXTRACT_CALLS;
    }

    public static void clearCache() {
        CALL_CACHE.clear();
    }

    public static int getCacheSize() {
        return CALL_CACHE.size();
    }

    public static void configureTypeSolver(Path mindustry, Path arc) throws IOException {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());

        addModuleSource(solver, mindustry, "core");
        addModuleSource(solver, mindustry, "desktop");
        addModuleSource(solver, mindustry, "server");
        addModuleSource(solver, mindustry, "tools");
        addModuleSource(solver, mindustry, "annotations");
        addRawSource(solver, mindustry.resolve(
                "core/build/generated/source/kapt/main"));

        addModuleSource(solver, arc, "arc-core");
        addModuleSource(solver, arc, "extensions/freetype");
        addModuleSource(solver, arc, "extensions/filedialogs");
        addModuleSource(solver, arc, "extensions/discord");
        addModuleSource(solver, arc, "extensions/arcnet");
        addModuleSource(solver, arc, "extensions/packer");
        addModuleSource(solver, arc, "extensions/flabel");
        addModuleSource(solver, arc, "extensions/g3d");
        addModuleSource(solver, arc, "extensions/fx");
        addModuleSource(solver, arc, "extensions/tiled");
        addModuleSource(solver, arc, "extensions/profiling");
        addModuleSource(solver, arc, "backends/backend-sdl");
        addModuleSource(solver, arc, "backends/backend-sdl3");
        addModuleSource(solver, arc, "backends/backend-headless");

        addJars(solver, mindustry.resolve("core/build/exported-deps"));
        addJars(solver, mindustry.resolve("desktop/build/exported-deps"));
        addJars(solver, mindustry.resolve("server/build/exported-deps"));
        addJars(solver, mindustry.resolve("tools/build/exported-deps"));
        addJars(solver, mindustry.resolve("annotations/build/exported-deps"));

        addJars(solver, arc.resolve("arc-core/build/exported-deps"));
        addJarsRecursive(solver, arc.resolve("extensions"));
        addJarsRecursive(solver, arc.resolve("backends"));

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        StaticJavaParser.getConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    public static Path findSourceRoot(Path moduleDir) {
        if (!Files.exists(moduleDir)) return null;

        Path standard = moduleDir.resolve("src/main/java");
        if (Files.exists(standard) && hasJavaFiles(standard)) {
            return standard;
        }

        Path simple = moduleDir.resolve("src");
        if (Files.exists(simple) && hasJavaFiles(simple)) {
            return simple;
        }

        return null;
    }

    private static boolean hasJavaFiles(Path dir) {
        try (Stream<Path> s = Files.walk(dir, 10)) {
            return s.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void addModuleSource(CombinedTypeSolver solver, Path root, String modulePath) {
        Path moduleDir = root.resolve(modulePath);
        Path srcRoot = findSourceRoot(moduleDir);
        if (srcRoot != null) {
            solver.add(new JavaParserTypeSolver(srcRoot.toFile()));
            System.out.println("  + source: " + srcRoot);
        }
    }

    private static void addRawSource(CombinedTypeSolver solver, Path srcRoot) {
        if (srcRoot == null || !Files.exists(srcRoot)) {
            System.out.println("  - gen source missing: " + srcRoot);
            return;
        }
        try (Stream<Path> s = Files.walk(srcRoot, 10)) {
            if (s.anyMatch(p -> p.toString().endsWith(".java"))) {
                solver.add(new JavaParserTypeSolver(srcRoot.toFile()));
                System.out.println("  + gen source: " + srcRoot);
            } else {
                System.out.println("  - gen source has no .java: " + srcRoot);
            }
        } catch (IOException e) {
            System.err.println("  ! cannot check " + srcRoot + ": " + e.getMessage());
        }
    }

    private static void addJars(CombinedTypeSolver solver, Path dir) {
        if (!Files.exists(dir)) return;
        int added = 0;
        try (Stream<Path> jars = Files.list(dir)) {
            List<Path> list = jars.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar")
                        && !n.endsWith("-sources.jar")
                        && !n.endsWith("-javadoc.jar");
            }).collect(Collectors.toList());

            for (Path p : list) {
                try {
                    solver.add(new JarTypeSolver(p.toFile()));
                    added++;
                } catch (Exception e) {
                    System.err.println("  ! skip jar: " + p.getFileName() + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            System.err.println("  ! cannot list " + dir + ": " + e.getMessage());
        }
        if (added > 0) {
            System.out.println("  + jars: " + added + " from " + dir.getFileName());
        }
    }

    private static void addJarsRecursive(CombinedTypeSolver solver, Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root, 3)) {
            s.filter(p -> p.getFileName().toString().equals("exported-deps"))
                    .filter(Files::isDirectory)
                    .forEach(p -> addJars(solver, p));
        } catch (IOException e) {
            System.err.println("  ! cannot walk " + root + ": " + e.getMessage());
        }
    }

    public FileIndex analyze(Path file) {
        FileIndex index = new FileIndex();
        index.file = file.getFileName().toString();

        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findFirst(ClassOrInterfaceDeclaration.class,
                            c -> c.getParentNode().map(p -> p instanceof CompilationUnit).orElse(false))
                    .ifPresent(type -> {
                        index.type = buildTypeString(type);
                        index.typeDesc = extractComment(type);
                    });

            cu.findAll(FieldDeclaration.class).forEach(f -> {
                if (isDirectMemberOfTopLevel(f)) {
                    index.fields.add(buildField(f));
                }
            });

            cu.findAll(ConstructorDeclaration.class).forEach(c -> {
                if (isDirectMemberOfTopLevel(c)) {
                    index.constructors.add(buildConstructor(c));
                }
            });

            cu.findAll(MethodDeclaration.class).forEach(m -> {
                if (isDirectMemberOfTopLevel(m)) {
                    index.methods.add(buildMethod(m));
                }
            });

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
                if (isNestedType(type)) {
                    index.inner.add(buildInner(type));
                }
            });

            index.methods = mergeOverloads(index.methods);
            index.constructors = mergeOverloads(index.constructors);

        } catch (Exception e) {
            System.err.println("Failed to parse " + file + ": " + e.getMessage());
        }

        return index;
    }

    private boolean isDirectMemberOfTopLevel(Node node) {
        Optional<Node> parent = node.getParentNode();
        if (parent.isEmpty()) return false;

        Node p = parent.get();
        if (!(p instanceof ClassOrInterfaceDeclaration)) return false;

        return p.getParentNode()
                .map(gp -> gp instanceof CompilationUnit)
                .orElse(false);
    }

    private boolean isNestedType(ClassOrInterfaceDeclaration type) {
        return type.getParentNode()
                .map(p -> p instanceof ClassOrInterfaceDeclaration || p instanceof EnumDeclaration)
                .orElse(false);
    }

    private String buildTypeString(ClassOrInterfaceDeclaration type) {
        StringBuilder sb = new StringBuilder();
        boolean isInterface = type.isInterface();
        sb.append(isInterface ? "Java interface" : "Java class");

        List<String> mods = new ArrayList<>();
        if (type.isAbstract()) mods.add("abstract");
        if (type.isFinal()) mods.add("final");
        if (type.isStatic()) mods.add("static");
        if (!mods.isEmpty()) {
            sb.append(" (").append(String.join(", ", mods)).append(")");
        }

        type.getExtendedTypes().forEach(t ->
                sb.append(" extends ").append(resolveTypeName(t)));

        if (!type.getImplementedTypes().isEmpty()) {
            sb.append(" implements ");
            sb.append(type.getImplementedTypes().stream()
                    .map(this::resolveTypeName)
                    .collect(Collectors.joining(", ")));
        }

        return sb.toString();
    }

    /** Try to resolve a type reference to its fully-qualified name; fall back to source text. */
    private String resolveTypeName(ClassOrInterfaceType t) {
        try {
            var resolved = t.resolve();
            if (resolved.isReferenceType()) {
                String qn = resolved.asReferenceType().getQualifiedName();
                if (qn != null && !qn.isEmpty()) return qn;
            }
        } catch (Throwable ignored) {
            // resolve failed (missing dep, unresolved generic, etc.)
        }
        return t.getNameAsString();
    }

    private String extractComment(Node node) {
        return node.getComment()
                .map(c -> {
                    String content = c.getContent();
                    if (c instanceof JavadocComment) {
                        content = content.replaceAll("(?m)^\\s*\\*\\s?", "").trim();
                        int at = content.indexOf("\n@");
                        if (at > 0) content = content.substring(0, at).trim();
                    }
                    content = content.trim();
                    int end = content.length();
                    int zh = content.indexOf("\u3002");
                    int en = content.indexOf(". ");
                    int nl = content.indexOf("\n");
                    if (zh >= 0) end = Math.min(end, zh + 1);
                    if (en >= 0) end = Math.min(end, en + 1);
                    if (nl >= 0) end = Math.min(end, nl);
                    return content.substring(0, end).trim();
                })
                .orElse(null);
    }

    private MemberInfo buildField(FieldDeclaration f) {
        MemberInfo info = new MemberInfo();
        info.kind = (f.isStatic() && f.isFinal()) ? "constant" : "field";

        info.modifiers = f.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .toArray(String[]::new);

        VariableDeclarator v = f.getVariable(0);
        StringBuilder sig = new StringBuilder();
        if (info.modifiers.length > 0) {
            sig.append(String.join(" ", info.modifiers)).append(" ");
        }
        sig.append(f.getElementType().asString())
                .append(" ")
                .append(v.getNameAsString());
        info.signature = sig.toString();

        info.name = v.getNameAsString();
        info.desc = extractComment(f);
        info.line = f.getBegin().map(p -> p.line).orElse(-1);
        return info;
    }

    private MemberInfo buildConstructor(ConstructorDeclaration c) {
        MemberInfo info = new MemberInfo();
        info.kind = "ctor";
        info.name = c.getNameAsString();

        info.modifiers = c.getModifiers().stream()
                .map(m -> m.getKeyword().asString())
                .toArray(String[]::new);

        info.parameters = c.getParameters().stream()
                .map(p -> {
                    ParamInfo pi = new ParamInfo();
                    pi.name = p.getNameAsString();
                    pi.type = p.getType().asString();
                    return pi;
                })
                .toArray(ParamInfo[]::new);

        StringBuilder sig = new StringBuilder();
        if (info.modifiers.length > 0) {
            sig.append(String.join(" ", info.modifiers)).append(" ");
        }
        sig.append(info.name).append("(");
        sig.append(Arrays.stream(info.parameters)
                .map(p -> p.type + " " + p.name)
                .collect(Collectors.joining(", ")));
        sig.append(")");
        info.signature = sig.toString();

        info.desc = extractComment(c);
        info.line = c.getBegin().map(p -> p.line).orElse(-1);

        info.calls = extractCalls(c);
        info.callCount = info.calls.length;

        return info;
    }

    private MemberInfo buildMethod(MethodDeclaration m) {
        MemberInfo info = new MemberInfo();
        info.kind = "method";
        info.name = m.getNameAsString();

        info.modifiers = m.getModifiers().stream()
                .map(mod -> mod.getKeyword().asString())
                .toArray(String[]::new);

        info.returnType = m.getType().asString();

        info.parameters = m.getParameters().stream()
                .map(p -> {
                    ParamInfo pi = new ParamInfo();
                    pi.name = p.getNameAsString();
                    pi.type = p.getType().asString();
                    return pi;
                })
                .toArray(ParamInfo[]::new);

        info.throwsList = m.getThrownExceptions().stream()
                .map(t -> t.asString())
                .toArray(String[]::new);

        info.annotations = m.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toArray(String[]::new);

        StringBuilder sig = new StringBuilder();
        if (info.modifiers.length > 0) {
            sig.append(String.join(" ", info.modifiers)).append(" ");
        }
        if (info.returnType != null && !info.returnType.isEmpty()) {
            sig.append(info.returnType).append(" ");
        }
        sig.append(info.name).append("(");
        sig.append(Arrays.stream(info.parameters)
                .map(p -> p.type + " " + p.name)
                .collect(Collectors.joining(", ")));
        sig.append(")");

        if (info.throwsList.length > 0) {
            sig.append(" throws ").append(String.join(", ", info.throwsList));
        }

        info.signature = sig.toString();
        info.desc = extractComment(m);
        info.line = m.getBegin().map(p -> p.line).orElse(-1);

        info.calls = extractCalls(m);
        info.callCount = info.calls.length;

        return info;
    }

    private String[] extractCalls(Node node) {
        if (!EXTRACT_CALLS) return new String[0];

        String fileKey = node.findCompilationUnit()
                .flatMap(cu -> cu.getStorage().map(s -> s.getPath().toString()))
                .orElse("?");

        Set<String> calls = new LinkedHashSet<>();

        // -- Method calls --
        node.findAll(MethodCallExpr.class).forEach(call -> {
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            String key = fileKey + ":" + line + ":" + call.getNameAsString();
            String sig = CALL_CACHE.get(key);

            if (sig == null) {
                try {
                    ResolvedMethodDeclaration resolved = call.resolve();
                    sig = resolved.getQualifiedSignature();
                } catch (Throwable ignored) {
                    try {
                        sig = call.getScope()
                                .map(s -> s.toString() + "." + call.getNameAsString())
                                .orElse(call.getNameAsString());
                    } catch (Throwable ignored2) {
                        sig = call.getNameAsString();
                    }
                }
                CALL_CACHE.put(key, sig);
            }

            if (sig != null && !sig.isEmpty()) {
                calls.add(sig);
            }
        });

        // -- Constructor calls --
        node.findAll(ObjectCreationExpr.class).forEach(expr -> {
            int line = expr.getBegin().map(p -> p.line).orElse(-1);
            String key = fileKey + ":" + line + ":new:" + expr.getType().asString();
            String sig = CALL_CACHE.get(key);

            if (sig == null) {
                try {
                    ResolvedConstructorDeclaration resolved = expr.resolve();
                    sig = resolved.getQualifiedSignature();
                } catch (Throwable ignored) {
                    sig = expr.getType().asString() + ".<init>";
                }
                CALL_CACHE.put(key, sig);
            }

            if (sig != null && !sig.isEmpty()) {
                calls.add(sig);
            }
        });

        return calls.toArray(new String[0]);
    }

    private InnerInfo buildInner(ClassOrInterfaceDeclaration type) {
        InnerInfo info = new InnerInfo();
        info.kind = type.isInterface() ? "inner-interface" : "inner-class";
        info.simpleName = type.getNameAsString();

        StringBuilder sig = new StringBuilder();
        sig.append(type.getNameAsString());

        type.getExtendedTypes().forEach(t ->
                sig.append(" extends ").append(resolveTypeName(t)));

        if (!type.getImplementedTypes().isEmpty()) {
            sig.append(" implements ");
            sig.append(type.getImplementedTypes().stream()
                    .map(this::resolveTypeName)
                    .collect(Collectors.joining(", ")));
        }

        info.signature = sig.toString();
        info.desc = extractComment(type);

        type.getFields().forEach(f -> info.fields.add(buildField(f)));
        type.getConstructors().forEach(c -> info.constructors.add(buildConstructor(c)));
        type.getMethods().forEach(m -> info.methods.add(buildMethod(m)));

        type.getMembers().stream()
                .filter(m -> m instanceof ClassOrInterfaceDeclaration)
                .map(m -> (ClassOrInterfaceDeclaration) m)
                .forEach(nested -> info.inner.add(buildInner(nested)));

        info.methods = mergeOverloads(info.methods);
        info.constructors = mergeOverloads(info.constructors);

        return info;
    }

    private List<MemberInfo> mergeOverloads(List<MemberInfo> methods) {
        Map<String, List<MemberInfo>> byName = new LinkedHashMap<>();
        for (MemberInfo m : methods) {
            byName.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m);
        }

        List<MemberInfo> result = new ArrayList<>();
        for (List<MemberInfo> group : byName.values()) {
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }

            MemberInfo merged = group.get(0);
            merged.overloads = group.stream()
                    .map(m -> m.signature)
                    .toArray(String[]::new);
            merged.signature = String.join(" / ", merged.overloads);

            merged.desc = group.stream()
                    .map(m -> m.desc)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            Set<String> mergedCalls = new LinkedHashSet<>();
            for (MemberInfo m : group) {
                if (m.calls != null) {
                    mergedCalls.addAll(Arrays.asList(m.calls));
                }
            }
            merged.calls = mergedCalls.toArray(new String[0]);
            merged.callCount = merged.calls.length;

            result.add(merged);
        }
        return result;
    }
}