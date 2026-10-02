package indexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import indexer.model.FileIndex;
import indexer.model.InnerInfo;
import indexer.model.MemberInfo;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
public class Enhancer {
    private final Path outRoot;
    private final ObjectMapper jsonMapper;
    public Enhancer(Path outRoot) {
        this.outRoot = outRoot;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }
    public void run() throws IOException {
        Path enhanced = outRoot.resolve("_enhanced");
        Files.createDirectories(enhanced);

        System.out.println("[enhance] loading module json...");
        List<ModuleData> modules = loadAllModules();
        if (modules.isEmpty()) {
            System.out.println("[enhance] no module json found, skip.");
            return;
        }
        long t0 = System.currentTimeMillis();

        TypeGraph graph = buildTypeGraph(modules);
        writeHierarchy(enhanced, graph);
        writeInterfaceImpl(enhanced, graph);
        System.out.printf("[enhance] hierarchy + iface done (%d types)%n", graph.byType().size());

        Hotspots hotspots = computeHotspots(modules);
        writeHotspots(enhanced, hotspots);
        System.out.printf("[enhance] hotspots done (%d entries)%n", hotspots.all.size());

        DepGraph deps = computeDeps(modules);
        writeDeps(enhanced, deps);
        System.out.printf("[enhance] deps done (%d packages)%n", deps.byPkg.size());

        FieldUsage fieldUsage = computeFieldUsage(modules);
        writeFields(enhanced, fieldUsage);
        System.out.printf("[enhance] field usage done (%d targets)%n", fieldUsage.byTarget.size());

        ChainIndex chains = computeChains(modules, hotspots);
        writeChains(enhanced, chains);
        System.out.printf("[enhance] chains done (%d targets)%n", chains.byTarget.size());

        InlineIndex inlined = computeInline(modules);
        writeInline(enhanced, inlined);
        System.out.printf("[enhance] inline done (%d packages)%n", inlined.byPkg.size());

        writeSummary(enhanced, modules, graph, hotspots, deps, fieldUsage, chains, inlined);

        System.out.printf("[enhance] all done (%.1fs)%n",
                (System.currentTimeMillis() - t0) / 1000.0);
    }
    public static class ModuleData {
        public String name;
        public List<FileIndex> files = new ArrayList<>();
    }
    private List<ModuleData> loadAllModules() throws IOException {
        if (!Files.exists(outRoot)) return List.of();

        List<ModuleData> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(outRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                String name = dir.getFileName().toString();
                if (name.startsWith("_")) continue;
                Path jsonDir = dir.resolve("json");
                if (!Files.isDirectory(jsonDir)) continue;

                ModuleData md = new ModuleData();
                md.name = name;

                try (Stream<Path> jsons = Files.list(jsonDir)) {
                    for (Path j : jsons.filter(p -> p.toString().endsWith(".json"))
                            .collect(Collectors.toList())) {
                        try {
                            List<FileIndex> files = jsonMapper.readValue(
                                    j.toFile(),
                                    jsonMapper.getTypeFactory()
                                            .constructCollectionType(List.class, FileIndex.class));
                            if (files != null) md.files.addAll(files);
                        } catch (IOException e) {
                            System.err.println("[enhance] ! read " + j + ": " + e.getMessage());
                        }
                    }
                }

                if (!md.files.isEmpty()) {
                    result.add(md);
                    System.out.printf("[enhance]   %-30s %d files%n", name, md.files.size());
                }
            }
        }
        return result;
    }
    private static void forEachMember(FileIndex fi, MemberVisitor v) {
        String owner = classNameOf(fi.file);
        for (MemberInfo m : fi.fields)       v.visit(owner, null, m, "field");
        for (MemberInfo m : fi.constructors) v.visit(owner, null, m, "ctor");
        for (MemberInfo m : fi.methods)      v.visit(owner, null, m, "method");
        for (InnerInfo inner : fi.inner) {
            forEachMemberInner(inner, owner, null, v);
        }
    }

    private static void forEachMemberInner(InnerInfo inner, String owner,
                                           String prefix, MemberVisitor v) {
        String simple = inner.simpleName != null ? inner.simpleName : inner.signature;
        String scope = (prefix == null ? "" : prefix + ".") + simple;
        for (MemberInfo m : inner.fields)       v.visit(owner, scope, m, "field");
        for (MemberInfo m : inner.constructors) v.visit(owner, scope, m, "ctor");
        for (MemberInfo m : inner.methods)      v.visit(owner, scope, m, "method");
        for (InnerInfo nested : inner.inner) {
            forEachMemberInner(nested, owner, scope, v);
        }
    }

    private interface MemberVisitor {
        void visit(String owner, String scope, MemberInfo m, String kind);
    }

    private static String classNameOf(String file) {
        if (file == null) return "?";
        return file.endsWith(".java")
                ? file.substring(0, file.length() - 5)
                : file;
    }
    public static class TypeGraph {
        public Map<String, List<String>> children = new TreeMap<>();
        public Map<String, String> kind = new TreeMap<>();
        public Map<String, List<String>> parents = new TreeMap<>();
        public Set<String> byType() { return parents.keySet(); }
        public Map<String, List<String>> byType;
    }

    private TypeGraph buildTypeGraph(List<ModuleData> modules) {
        TypeGraph g = new TypeGraph();
        g.byType = g.parents;

        for (ModuleData md : modules) {
            for (FileIndex fi : md.files) {
                String name = fqn(fi);
                g.kind.putIfAbsent(name, detectKind(fi.type));

                List<String> parents = extractParents(fi.type);
                g.parents.put(name, parents);
                for (String p : parents) {
                    g.children.computeIfAbsent(p, k -> new ArrayList<>()).add(name);
                }

                for (InnerInfo inner : fi.inner) {
                    collectInnerGraph(inner, name, g);
                }
            }
        }
        for (List<String> v : g.children.values()) Collections.sort(v);

        mergeShortNames(g);

        return g;
    }
    private static void mergeShortNames(TypeGraph g) {
        Map<String, List<String>> shortToFqn = new HashMap<>();
        for (String key : g.children.keySet()) {
            int dot = key.lastIndexOf('.');
            if (dot < 0) continue;
            String shortName = key.substring(dot + 1);
            shortToFqn.computeIfAbsent(shortName, k -> new ArrayList<>()).add(key);
        }

        List<String> toRemove = new ArrayList<>();

        for (var e : g.children.entrySet()) {
            String key = e.getKey();
            if (key.indexOf('.') >= 0) continue;
            List<String> candidates = shortToFqn.get(key);
            if (candidates == null || candidates.size() != 1) continue;

            String fqn = candidates.get(0);
            List<String> merged = g.children.computeIfAbsent(fqn, k -> new ArrayList<>());
            merged.addAll(e.getValue());
            Collections.sort(merged);
            toRemove.add(key);
        }

        for (String key : new ArrayList<>(g.children.keySet())) {
            if (key.indexOf('.') < 0) {
                toRemove.add(key);
            }
        }

        for (String k : toRemove) {
            g.children.remove(k);
            g.kind.remove(k);
            g.parents.remove(k);
        }
    }

    private void collectInnerGraph(InnerInfo inner, String outer, TypeGraph g) {
        String simple = inner.simpleName != null ? inner.simpleName : inner.signature;
        String name = outer + "." + simple;
        g.kind.putIfAbsent(name, inner.kind);
        List<String> parents = extractInnerParents(inner.signature);
        g.parents.put(name, parents);
        for (String p : parents) {
            g.children.computeIfAbsent(p, k -> new ArrayList<>()).add(name);
        }
        for (InnerInfo nested : inner.inner) {
            collectInnerGraph(nested, name, g);
        }
    }

    private static String fqn(FileIndex fi) {
        String cls = stripFile(fi.file);
        return (fi.pkg == null || fi.pkg.isEmpty()) ? cls : fi.pkg + "." + cls;
    }

    private static String stripFile(String file) {
        if (file == null) return "?";
        return file.endsWith(".java")
                ? file.substring(0, file.length() - 5)
                : file;
    }

    private static String detectKind(String typeStr) {
        if (typeStr == null) return "unknown";
        if (typeStr.startsWith("Java interface")) return "interface";
        if (typeStr.startsWith("Java class")) return "class";
        return "unknown";
    }

    private static List<String> extractParents(String typeStr) {
        List<String> parents = new ArrayList<>();
        if (typeStr == null) return parents;

        int ext = typeStr.indexOf(" extends ");
        int impl = typeStr.indexOf(" implements ");

        if (ext >= 0) {
            int end = impl >= 0 ? impl : typeStr.length();
            String s = typeStr.substring(ext + 9, end).trim();
            for (String p : s.split(",")) {
                p = p.trim();
                if (!p.isEmpty()) parents.add(p);
            }
        }
        if (impl >= 0) {
            String s = typeStr.substring(impl + 12).trim();
            for (String p : s.split(",")) {
                p = p.trim();
                if (!p.isEmpty()) parents.add(p);
            }
        }
        return parents;
    }

    private static List<String> extractInnerParents(String sig) {
        if (sig == null) return List.of();
        int space = sig.indexOf(' ');
        if (space < 0) return List.of();
        return extractParents("X" + sig.substring(space));
    }

    private void writeHierarchy(Path enhanced, TypeGraph g) throws IOException {
        Path dir = enhanced.resolve("hierarchy");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        for (var e : g.children.entrySet()) {
            String parent = e.getKey();
            List<String> kids = e.getValue();
            if (kids.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append(parent).append('\n');
            sb.append("  kind: ").append(g.kind.getOrDefault(parent, "unknown")).append('\n');
            sb.append("  direct-children: ").append(kids.size()).append('\n');
            for (String k : kids) {
                sb.append("  <- ").append(k).append('\n');
            }
            Files.writeString(dir.resolve(safeFileName(parent) + ".txt"), sb.toString());
        }

        jsonMapper.writeValue(enhanced.resolve("hierarchy.json").toFile(), g.children);
    }

    private void writeInterfaceImpl(Path enhanced, TypeGraph g) throws IOException {
        Path dir = enhanced.resolve("iface");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        for (var e : g.children.entrySet()) {
            String name = e.getKey();
            if (!"interface".equals(g.kind.get(name))) continue;
            List<String> impls = e.getValue();
            if (impls.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append(name).append("  (interface)\n");
            sb.append("  implementors: ").append(impls.size()).append('\n');
            for (String i : impls) {
                sb.append("  * ").append(i).append('\n');
            }
            Files.writeString(dir.resolve(safeFileName(name) + ".txt"), sb.toString());
        }
    }

    public static class Hotspots {
        public List<HotEntry> all = new ArrayList<>();
    }

    public static class HotEntry {
        public String target;
        public int inDegree;
        public List<String> callers = new ArrayList<>();
    }

    private Hotspots computeHotspots(List<ModuleData> modules) {
        Map<String, Set<String>> inbound = new TreeMap<>();

        for (ModuleData md : modules) {
            for (FileIndex fi : md.files) {
                forEachMember(fi, (owner, scope, m, kind) -> {
                    if (m.calls == null || m.calls.length == 0) return;
                    String caller = (scope == null ? owner : owner + "::" + scope)
                            + "::" + m.name;
                    for (String target : m.calls) {
                        if (!looksLikeMethodSig(target)) continue;
                        inbound.computeIfAbsent(target, k -> new TreeSet<>()).add(caller);
                    }
                });
            }
        }

        Hotspots h = new Hotspots();
        for (var e : inbound.entrySet()) {
            HotEntry he = new HotEntry();
            he.target = e.getKey();
            he.callers.addAll(e.getValue());
            he.inDegree = he.callers.size();
            h.all.add(he);
        }
        h.all.sort((a, b) -> Integer.compare(b.inDegree, a.inDegree));
        return h;
    }

    private void writeHotspots(Path enhanced, Hotspots h) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Top methods by in-degree (number of distinct callers)\n");
        sb.append("# format: in-degree  target\n");
        int limit = Math.min(200, h.all.size());
        for (int i = 0; i < limit; i++) {
            HotEntry e = h.all.get(i);
            sb.append(String.format("%6d  %s%n", e.inDegree, e.target));
        }
        Files.writeString(enhanced.resolve("hotspots.txt"), sb.toString());

        int jsonLimit = Math.min(500, h.all.size());
        List<Map<String, Object>> jsonList = new ArrayList<>();
        for (int i = 0; i < jsonLimit; i++) {
            HotEntry e = h.all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", e.target);
            m.put("inDegree", e.inDegree);
            jsonList.add(m);
        }
        jsonMapper.writeValue(enhanced.resolve("hotspots.json").toFile(), jsonList);
    }

    public static class DepGraph {
        public Map<String, Map<String, Integer>> byPkg = new TreeMap<>();
    }

    private DepGraph computeDeps(List<ModuleData> modules) {
        DepGraph g = new DepGraph();

        Set<String> knownPackages = new HashSet<>();
        for (ModuleData md : modules) {
            for (FileIndex fi : md.files) {
                if (fi.pkg != null && !fi.pkg.isEmpty()) {
                    knownPackages.add(fi.pkg);
                }
            }
        }

        for (ModuleData md : modules) {
            for (FileIndex fi : md.files) {
                final String fromPkg = (fi.pkg == null || fi.pkg.isEmpty()) ? md.name : fi.pkg;
                forEachMember(fi, (owner, scope, m, kind) -> {
                    if (m.calls == null) return;
                    for (String target : m.calls) {
                        if (!looksLikeMethodSig(target)) continue;
                        String toPkg = guessPackage(target);
                        if (toPkg == null) return;
                        if (toPkg.equals(fromPkg)) return;

                        boolean isKnown = knownPackages.contains(toPkg);
                        boolean isJdk = toPkg.startsWith("java.")
                                || toPkg.startsWith("javax.")
                                || toPkg.startsWith("sun.")
                                || toPkg.startsWith("jdk.")
                                || toPkg.equals("java");
                        if (!isKnown && !isJdk) return;

                        g.byPkg.computeIfAbsent(fromPkg, k -> new TreeMap<>())
                                .merge(toPkg, 1, Integer::sum);
                    }
                });
            }
        }
        return g;
    }

    private static String guessPackage(String sig) {
        int paren = sig.indexOf('(');
        String head = paren >= 0 ? sig.substring(0, paren) : sig;
        int lastDot = head.lastIndexOf('.');
        if (lastDot < 0) return null;
        String cls = head.substring(0, lastDot);
        int prev = cls.lastIndexOf('.');
        return prev < 0 ? cls : cls.substring(0, prev);
    }

    private void writeDeps(Path enhanced, DepGraph g) throws IOException {
        Path dir = enhanced.resolve("deps");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        for (var e : g.byPkg.entrySet()) {
            String pkg = e.getKey();
            Map<String, Integer> deps = e.getValue();

            StringBuilder sb = new StringBuilder();
            sb.append("# package: ").append(pkg).append('\n');
            sb.append("# out-edges (this package calls into):\n");
            deps.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .forEach(en -> sb.append(String.format("  -> %-50s %,d%n",
                            en.getKey(), en.getValue())));

            sb.append("# in-edges (who calls this package):\n");
            for (var other : g.byPkg.entrySet()) {
                Integer n = other.getValue().get(pkg);
                if (n != null) {
                    sb.append(String.format("  <- %-50s %,d%n", other.getKey(), n));
                }
            }

            Files.writeString(dir.resolve(safeFileName(pkg) + ".txt"), sb.toString());
        }

        jsonMapper.writeValue(enhanced.resolve("deps.json").toFile(), g.byPkg);
    }

    public static class FieldUsage {
        public Map<String, List<String>> byTarget = new TreeMap<>();
    }

    private FieldUsage computeFieldUsage(List<ModuleData> modules) {
        FieldUsage fu = new FieldUsage();

        for (ModuleData md : modules) {
            for (FileIndex fi : md.files) {
                forEachMember(fi, (owner, scope, m, kind) -> {
                    if (m.calls == null) return;
                    String caller = (scope == null ? owner : owner + "::" + scope)
                            + "::" + m.name;
                    for (String c : m.calls) {
                        if (looksLikeMethodSig(c)) continue;
                        if (!looksLikeFieldRef(c)) continue;
                        fu.byTarget.computeIfAbsent(c, k -> new ArrayList<>())
                                .add(caller + "  (" + fi.file + ")");
                    }
                });
            }
        }
        for (List<String> v : fu.byTarget.values()) Collections.sort(v);
        return fu;
    }

    private static boolean looksLikeFieldRef(String s) {
        if (s.indexOf('(') >= 0) return false;
        if (s.indexOf(' ') >= 0) return false;
        int dot = s.lastIndexOf('.');
        return dot >= 0;
    }

    private void writeFields(Path enhanced, FieldUsage fu) throws IOException {
        Path dir = enhanced.resolve("fields");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        Map<String, StringBuilder> byPkg = new TreeMap<>();
        for (var e : fu.byTarget.entrySet()) {
            String target = e.getKey();
            String pkg = guessPackage(target + "()");
            if (pkg == null) pkg = "_misc";

            StringBuilder sb = byPkg.computeIfAbsent(pkg, k -> new StringBuilder());
            sb.append(target).append('\n');
            for (String u : e.getValue()) {
                sb.append("  <- ").append(u).append('\n');
            }
        }

        for (var e : byPkg.entrySet()) {
            Files.writeString(dir.resolve(safeFileName(e.getKey()) + ".txt"),
                    e.getValue().toString());
        }

        jsonMapper.writeValue(enhanced.resolve("fields.json").toFile(), fu.byTarget);
    }

    public static class ChainIndex {
        public Map<String, List<String>> byTarget = new TreeMap<>();
    }

    private ChainIndex computeChains(List<ModuleData> modules, Hotspots hotspots) {
        ChainIndex ci = new ChainIndex();

        Map<String, Set<String>> inbound = new HashMap<>();
        for (var e : hotspots.all) {
            inbound.put(e.target, new TreeSet<>(e.callers));
        }

        int limit = Math.min(100, hotspots.all.size());
        for (int i = 0; i < limit; i++) {
            String target = hotspots.all.get(i).target;
            List<String> lines = new ArrayList<>();
            lines.add("# target: " + target);

            Set<String> d1 = inbound.getOrDefault(target, Set.of());
            lines.add("depth 1 (" + d1.size() + "):");
            for (String c : d1) lines.add("  <- " + c);

            Set<String> d2 = new TreeSet<>();
            for (String c : d1) {
                Set<String> up = inbound.getOrDefault(c, Set.of());
                d2.addAll(up);
            }
            d2.removeAll(d1);
            d2.remove(target);
            lines.add("depth 2 (" + d2.size() + "):");
            int shown = 0;
            for (String c : d2) {
                if (shown++ >= 50) { lines.add("  ... (" + (d2.size() - 50) + " more)"); break; }
                lines.add("  <- " + c);
            }

            ci.byTarget.put(target, lines);
        }
        return ci;
    }

    private void writeChains(Path enhanced, ChainIndex ci) throws IOException {
        Path dir = enhanced.resolve("chains");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        for (var e : ci.byTarget.entrySet()) {
            Files.writeString(dir.resolve(safeFileName(e.getKey()) + ".txt"),
                    String.join("\n", e.getValue()) + "\n");
        }
    }

    public static class InlineIndex {
        public Map<String, StringBuilder> byPkg = new TreeMap<>();
    }

    private InlineIndex computeInline(List<ModuleData> modules) throws IOException {
        InlineIndex ii = new InlineIndex();

        for (ModuleData md : modules) {
            Path srcRoot = findModuleSrcRoot(md.name);
            if (srcRoot == null) continue;

            for (FileIndex fi : md.files) {
                Path srcFile = locateSource(srcRoot, fi.file);
                if (srcFile == null || !Files.exists(srcFile)) continue;

                String content;
                try {
                    content = Files.readString(srcFile);
                } catch (IOException e) {
                    continue;
                }
                String[] lines = content.split("\n", -1);

                boolean isInterface = fi.type != null && fi.type.startsWith("Java interface");

                for (MemberInfo m : fi.methods) {
                    if (m.line < 0) continue;

                    if (hasModifier(m, "abstract") || hasModifier(m, "native")) continue;

                    if (isInterface
                            && !hasModifier(m, "default")
                            && !hasModifier(m, "static")) {
                        continue;
                    }

                    int start = m.line - 1;
                    if (start >= lines.length) continue;
                    int end = findMethodEnd(lines, start);
                    if (end < 0) continue;
                    int bodyLines = end - start;
                    if (bodyLines > 10) continue;

                    StringBuilder body = new StringBuilder();
                    for (int i = start; i <= end; i++) {
                        body.append(lines[i]).append('\n');
                    }

                    String pkg = (fi.pkg == null || fi.pkg.isEmpty()) ? "_misc" : fi.pkg;
                    StringBuilder sb = ii.byPkg.computeIfAbsent(pkg, k -> new StringBuilder());
                    sb.append("// ").append(fi.file).append(" : ").append(m.signature).append('\n');
                    sb.append(body).append('\n');
                }
            }
        }
        return ii;
    }

    private static boolean hasModifier(MemberInfo m, String mod) {
        if (m.modifiers == null) return false;
        for (String s : m.modifiers) {
            if (mod.equals(s)) return true;
        }
        return false;
    }

    private Path findModuleSrcRoot(String moduleName) {
        Path f = outRoot.resolve(moduleName).resolve("src-root.txt");
        if (!Files.exists(f)) return null;
        try {
            return Paths.get(Files.readString(f).trim());
        } catch (IOException e) {
            return null;
        }
    }

    private Path locateSource(Path srcRoot, String fileName) throws IOException {
        try (Stream<Path> s = Files.walk(srcRoot, 20)) {
            return s.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst().orElse(null);
        }
    }

    private static int findMethodEnd(String[] lines, int start) {
        int depth = 0;
        boolean started = false;
        for (int i = start; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { depth++; started = true; }
                else if (c == '}') {
                    depth--;
                    if (started && depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private void writeInline(Path enhanced, InlineIndex ii) throws IOException {
        Path dir = enhanced.resolve("inline");
        Files.createDirectories(dir);
        cleanTxtDir(dir);

        for (var e : ii.byPkg.entrySet()) {
            Files.writeString(dir.resolve(safeFileName(e.getKey()) + ".txt"),
                    e.getValue().toString());
        }
    }

    private void writeSummary(Path enhanced,
                              List<ModuleData> modules,
                              TypeGraph graph,
                              Hotspots hotspots,
                              DepGraph deps,
                              FieldUsage fieldUsage,
                              ChainIndex chains,
                              InlineIndex inlined) throws IOException {

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("modules", modules.size());
        summary.put("totalFiles", modules.stream().mapToInt(m -> m.files.size()).sum());
        summary.put("totalTypes", graph.byType().size());
        summary.put("typesWithChildren", graph.children.size());
        summary.put("hotspotsCount", hotspots.all.size());
        summary.put("packagesWithDeps", deps.byPkg.size());
        summary.put("fieldUsageTargets", fieldUsage.byTarget.size());
        summary.put("chainTargets", chains.byTarget.size());
        summary.put("inlinePackages", inlined.byPkg.size());
        jsonMapper.writeValue(enhanced.resolve("summary.json").toFile(), summary);
    }

    private static boolean looksLikeMethodSig(String sig) {
        if (sig == null) return false;
        if (sig.indexOf('(') >= 0) return true;
        return sig.endsWith(".<init>");
    }

    private static void cleanTxtDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".txt"))
                    .collect(Collectors.toList())) {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {}
            }
        }
    }

    private static String safeFileName(String s) {
        if (s == null || s.isEmpty()) return "_";

        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '/'
                    || c == '\\' || c == '|' || c == '?' || c == '*'
                    || c == '.' || c == '(' || c == ')' || c == ','
                    || c == '[' || c == ']' || c == '&' || c == ' '
                    || c < 0x20) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }

        String out = sb.toString();
        if (out.length() > 180) {
            String hash = Integer.toHexString(out.hashCode());
            out = out.substring(0, 170) + "_" + hash;
        }
        return out;
    }
}