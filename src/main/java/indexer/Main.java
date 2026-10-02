package indexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import indexer.model.FileIndex;
import indexer.model.InnerInfo;
import indexer.model.MemberInfo;
import indexer.TxtRenderer.CallerEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    private record Module(String repo, String path, String outputName, boolean withCalls) {}

    private static final List<Module> MODULES = List.of(
            new Module("mindustry", "core",           "mindustry-core",        true),
            new Module("mindustry", "desktop",        "mindustry-desktop",     true),
            new Module("mindustry", "server",         "mindustry-server",      false),
            new Module("mindustry", "tools",          "mindustry-tools",       false),
            new Module("mindustry", "annotations",    "mindustry-annotations", false),

            new Module("arc", "arc-core",                    "arc-core",                    true),
            new Module("arc", "extensions/freetype",         "arc-extensions-freetype",     false),
            new Module("arc", "extensions/filedialogs",      "arc-extensions-filedialogs",  false),
            new Module("arc", "extensions/discord",          "arc-extensions-discord",      false),
            new Module("arc", "extensions/arcnet",           "arc-extensions-arcnet",       false),
            new Module("arc", "extensions/packer",           "arc-extensions-packer",       false),
            new Module("arc", "extensions/flabel",           "arc-extensions-flabel",       false),
            new Module("arc", "extensions/g3d",              "arc-extensions-g3d",          false),
            new Module("arc", "extensions/fx",               "arc-extensions-fx",           false),
            new Module("arc", "extensions/tiled",            "arc-extensions-tiled",        false),
            new Module("arc", "extensions/profiling",        "arc-extensions-profiling",    false),
            new Module("arc", "backends/backend-sdl",        "arc-backends-sdl",            false),
            new Module("arc", "backends/backend-sdl3",       "arc-backends-sdl3",           false),
            new Module("arc", "backends/backend-headless",   "arc-backends-headless",       false)
    );

    private static final int PROGRESS_INTERVAL = 10;

    public static void main(String[] args) throws Exception {
        boolean globalWithCalls = false;
        boolean onlyArcCore = false;
        String mindustryArg = null;
        String arcArg = null;
        String outputArg = null;

        for (String arg : args) {
            if (arg.equals("--with-calls")) {
                globalWithCalls = true;
            } else if (arg.equals("--arc-core-only")) {
                onlyArcCore = true;
            } else if (arg.startsWith("--mindustry=")) {
                mindustryArg = arg.substring("--mindustry=".length());
            } else if (arg.startsWith("--arc=")) {
                arcArg = arg.substring("--arc=".length());
            } else if (arg.startsWith("--output=")) {
                outputArg = arg.substring("--output=".length());
            }
        }

        // ── Resolve paths: CLI arg > env var > default relative path ──
        Path mindustry = Paths.get(
                mindustryArg != null ? mindustryArg
                        : System.getenv().getOrDefault("MINDUSTRY_DIR", "Mindustry"));
        Path arc = Paths.get(
                arcArg != null ? arcArg
                        : System.getenv().getOrDefault("ARC_DIR", "Arc"));
        Path output = Paths.get(
                outputArg != null ? outputArg
                        : System.getenv().getOrDefault("OUTPUT_DIR", "out"));

        System.out.println("+----------------------+");
        System.out.println("|   Method Indexer v9  |");
        System.out.println("+----------------------+");
        System.out.println("  Mindustry: " + mindustry.toAbsolutePath()
                + "  (exists=" + Files.exists(mindustry) + ")");
        System.out.println("  Arc:       " + arc.toAbsolutePath()
                + "  (exists=" + Files.exists(arc) + ")");
        System.out.println("  Output:    " + output.toAbsolutePath());
        if (onlyArcCore) System.out.println("  Mode:      arc-core only");
        System.out.println();

        if (!Files.exists(mindustry) || !Files.exists(arc)) {
            System.err.println("ERROR: Mindustry or Arc directory not found. Check paths.");
            System.err.println("       Set MINDUSTRY_DIR / ARC_DIR env vars, or pass --mindustry= and --arc=");
            return;
        }

        Files.createDirectories(output);

        long t0 = System.currentTimeMillis();
        System.out.println("[1/4] Configuring TypeSolver...");
        JavaAnalyzer.configureTypeSolver(mindustry, arc);
        System.out.printf("      done (%.1fs)%n%n", (System.currentTimeMillis() - t0) / 1000.0);

        JavaAnalyzer analyzer = new JavaAnalyzer();
        TxtRenderer renderer  = new TxtRenderer();
        ObjectMapper jsonMapper = new ObjectMapper();
        jsonMapper.enable(SerializationFeature.INDENT_OUTPUT);

        System.out.println("[2/4] Analyzing modules...");
        System.out.println();

        List<Module> modules = onlyArcCore
                ? List.of(new Module("arc", "arc-core", "arc-core", true))
                : MODULES;

        List<ModuleResult> results = new ArrayList<>();
        int totalFiles = 0;
        int totalFailed = 0;
        long analyzeStart = System.currentTimeMillis();

        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            boolean withCalls = globalWithCalls || module.withCalls;

            System.out.printf("+- [%d/%d] %s%s%n",
                    i + 1, modules.size(), module.outputName,
                    withCalls ? "  (with calls)" : "");

            ModuleResult result = analyzeModule(
                    module, mindustry, arc, analyzer, renderer, jsonMapper, output, withCalls
            );

            if (result != null) {
                results.add(result);
                totalFiles += result.fileCount;
                totalFailed += result.failedCount;

                System.out.printf("+- done: %d files, %d packages, %.1fs%s%n%n",
                        result.fileCount,
                        result.packages.size(),
                        result.elapsedMs / 1000.0,
                        result.failedCount > 0 ? ", " + result.failedCount + " failed" : "");
            } else {
                System.out.printf("+- skipped (no source dir)%n%n");
            }

            JavaAnalyzer.clearCache();
        }

        System.out.println("[3/4] Writing master index...");
        writeMasterIndex(results, output, jsonMapper);

        System.out.println("[4/4] Enhancing...");
        try {
            new Enhancer(output).run();
        } catch (IOException e) {
            System.err.println("  ! enhancer failed: " + e.getMessage());
            e.printStackTrace();
        }

        long totalElapsed = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("+--------------------------------------------------------------+");
        System.out.println("|                          DONE                                |");
        System.out.println("+--------------------------------------------------------------+");
        System.out.printf("  Modules:     %d / %d%n", results.size(), modules.size());
        System.out.printf("  Files:       %d%n", totalFiles);
        System.out.printf("  Failed:      %d%n", totalFailed);
        System.out.printf("  Elapsed:     %.1fs%n", totalElapsed / 1000.0);
        System.out.printf("  Output dir:  %s%n", output.toAbsolutePath());
    }

    private static ModuleResult analyzeModule(
            Module module,
            Path mindustry,
            Path arc,
            JavaAnalyzer analyzer,
            TxtRenderer renderer,
            ObjectMapper jsonMapper,
            Path output,
            boolean withCalls) {

        long moduleStart = System.currentTimeMillis();

        Path repoRoot = module.repo.equals("mindustry") ? mindustry : arc;
        Path moduleDir = repoRoot.resolve(module.path);
        Path srcDir = JavaAnalyzer.findSourceRoot(moduleDir);

        if (srcDir == null) return null;
        System.out.println("|  src: " + srcDir);

        List<Path> javaFiles;
        try (var stream = Files.walk(srcDir)) {
            javaFiles = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("|  ! cannot walk " + srcDir + ": " + e.getMessage());
            return null;
        }

        if (javaFiles.isEmpty()) return null;
        System.out.println("|  files: " + javaFiles.size());

        JavaAnalyzer.setExtractCalls(withCalls);

        Map<String, List<FileIndex>> byPackage = new TreeMap<>();

        int failed = 0;
        int processed = 0;

        for (Path f : javaFiles) {
            long fileStart = System.currentTimeMillis();
            try {
                FileIndex idx = analyzer.analyze(f);
                if (idx != null && idx.file != null) {
                    String pkg = packageOf(srcDir, f);
                    idx.pkg = pkg;
                    byPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(idx);
                }
            } catch (Throwable t) {
                failed++;
                System.err.printf("|      ! parse failed: %s (%s)%n", f.getFileName(), t.getMessage());
            }

            processed++;
            if (processed % PROGRESS_INTERVAL == 0 || processed == javaFiles.size()) {
                long elapsed = System.currentTimeMillis() - moduleStart;
                long fileElapsed = System.currentTimeMillis() - fileStart;
                double avg = (double) elapsed / processed;
                long eta = (long) (avg * (javaFiles.size() - processed));
                int barWidth = 20;
                int filled = (int) ((double) processed / javaFiles.size() * barWidth);
                String bar = "#".repeat(filled) + "-".repeat(barWidth - filled);
                System.out.printf("|  [%s] %5d/%-5d  %5.1fs  ETA %5.1fs  last %.0fms%n",
                        bar, processed, javaFiles.size(),
                        elapsed / 1000.0, eta / 1000.0, (double) fileElapsed);
            }
        }

        long moduleElapsed = System.currentTimeMillis() - moduleStart;

        Path modOut = output.resolve(module.outputName);
        Path sigDir = modOut.resolve("sig");
        Path callsDir = modOut.resolve("calls");
        Path callersDir = modOut.resolve("callers");
        Path jsonDir = modOut.resolve("json");

        try {
            Files.createDirectories(sigDir);
            Files.createDirectories(callsDir);
            Files.createDirectories(callersDir);
            Files.createDirectories(jsonDir);
        } catch (IOException e) {
            System.err.println("|  ! cannot create dirs: " + e.getMessage());
            return null;
        }

        // Record source root so Enhancer can locate source files for inlining.
        writeString(modOut.resolve("src-root.txt"), srcDir.toAbsolutePath().toString());

        ModuleResult r = new ModuleResult();
        r.name = module.outputName;
        r.repo = module.repo;
        r.path = module.path;
        r.fileCount = 0;
        r.failedCount = failed;
        r.elapsedMs = moduleElapsed;

        Map<String, List<CallerEntry>> callersByCallerPkg = new TreeMap<>();

        if (withCalls) {
            for (var entry : byPackage.entrySet()) {
                String callerPkg = entry.getKey();
                List<CallerEntry> list = new ArrayList<>();

                for (FileIndex fi : entry.getValue()) {
                    collectCallers(fi, fi.file, list);
                }

                if (!list.isEmpty()) {
                    list.sort(Comparator
                            .comparing((CallerEntry e) -> e.target)
                            .thenComparing(e -> e.caller));
                    callersByCallerPkg.put(callerPkg, list);
                }
            }
        }

        for (var entry : byPackage.entrySet()) {
            String pkg = entry.getKey();
            List<FileIndex> files = entry.getValue();
            String flat = pkg.isEmpty() ? "_root" : pkg;

            StringBuilder sigTxt = new StringBuilder();
            for (FileIndex fi : files) {
                sigTxt.append(renderer.renderCompact(fi));
            }
            writeString(sigDir.resolve(flat + ".txt"), sigTxt.toString());

            if (withCalls) {
                StringBuilder callsTxt = new StringBuilder();
                for (FileIndex fi : files) {
                    callsTxt.append(renderer.renderCalls(fi));
                }
                if (callsTxt.length() > 0) {
                    writeString(callsDir.resolve(flat + ".txt"), callsTxt.toString());
                }
            }

            Path jsonFile = jsonDir.resolve(flat + ".json");
            try {
                jsonMapper.writeValue(jsonFile.toFile(), files);
            } catch (IOException e) {
                System.err.println("|  ! write json failed " + jsonFile + ": " + e.getMessage());
            }

            PackageEntry pe = new PackageEntry();
            pe.pkg = pkg;
            pe.files = files.size();
            pe.methods = files.stream().mapToInt(fi -> fi.methods.size()).sum();
            pe.constructors = files.stream().mapToInt(fi -> fi.constructors.size()).sum();
            pe.fields = files.stream().mapToInt(fi -> fi.fields.size()).sum();
            pe.innerTypes = files.stream().mapToInt(fi -> fi.inner.size()).sum();
            pe.sigPath = "sig/" + flat + ".txt";
            pe.callsPath = withCalls ? "calls/" + flat + ".txt" : null;
            pe.callersPath = (withCalls && callersByCallerPkg.containsKey(pkg))
                    ? "callers/" + flat + ".txt" : null;
            pe.jsonPath = "json/" + flat + ".json";
            r.packages.add(pe);
            r.fileCount += files.size();
        }

        if (withCalls) {
            for (var entry : callersByCallerPkg.entrySet()) {
                String pkg = entry.getKey();
                String flat = pkg.isEmpty() ? "_root" : pkg;
                String txt = renderer.renderCallers(pkg, entry.getValue());
                if (!txt.isEmpty()) {
                    writeString(callersDir.resolve(flat + ".txt"), txt);
                }
            }
        }

        ModuleIndex mi = new ModuleIndex();
        mi.name = r.name;
        mi.repo = r.repo;
        mi.path = r.path;
        mi.fileCount = r.fileCount;
        mi.failedCount = r.failedCount;
        mi.withCalls = withCalls;
        mi.packages = r.packages;
        Path modIndex = modOut.resolve("index.json");
        try {
            jsonMapper.writeValue(modIndex.toFile(), mi);
        } catch (IOException e) {
            System.err.println("|  ! write module index failed: " + e.getMessage());
        }

        System.out.printf("|  packages: %d, methods: %d%n",
                r.packages.size(),
                r.packages.stream().mapToInt(p -> p.methods).sum());

        return r;
    }

    private static void collectCallers(FileIndex fi, String file, List<CallerEntry> out) {
        String className = classNameOf(file);

        for (MemberInfo m : fi.constructors) {
            addCallers(m, file, className, null, out);
        }
        for (MemberInfo m : fi.methods) {
            addCallers(m, file, className, null, out);
        }
        for (InnerInfo inner : fi.inner) {
            collectCallersInner(inner, file, className, null, out);
        }
    }

    private static void collectCallersInner(InnerInfo inner, String file, String className,
                                            String prefix, List<CallerEntry> out) {
        String simple = inner.simpleName != null ? inner.simpleName : inner.signature;
        String scope = (prefix == null ? "" : prefix + ".") + simple;

        for (MemberInfo m : inner.constructors) {
            addCallers(m, file, className, scope, out);
        }
        for (MemberInfo m : inner.methods) {
            addCallers(m, file, className, scope, out);
        }
        for (InnerInfo nested : inner.inner) {
            collectCallersInner(nested, file, className, scope, out);
        }
    }

    private static void addCallers(MemberInfo m, String file, String className, String scope,
                                   List<CallerEntry> out) {
        if (m.calls == null || m.calls.length == 0) return;

        StringBuilder caller = new StringBuilder(className);
        if (scope != null && !scope.isEmpty()) {
            caller.append("::").append(scope);
        }
        caller.append("::").append(m.name);

        String callerName = caller.toString();

        for (String target : m.calls) {
            if (target == null || target.isEmpty()) continue;
            if (!looksLikeMethodSig(target)) continue;
            out.add(new CallerEntry(target, callerName, file));
        }
    }

    private static boolean looksLikeMethodSig(String sig) {
        if (sig.indexOf('(') >= 0) return true;
        return sig.endsWith(".<init>");
    }

    private static String classNameOf(String file) {
        if (file == null) return "?";
        return file.endsWith(".java")
                ? file.substring(0, file.length() - 5)
                : file;
    }

    private static String packageOf(Path srcDir, Path file) {
        Path rel = srcDir.relativize(file);
        Path parent = rel.getParent();
        if (parent == null) return "";
        return parent.toString().replace('\\', '.').replace('/', '.');
    }

    private static void writeString(Path p, String content) {
        try {
            Files.writeString(p, content);
        } catch (IOException e) {
            System.err.println("  ! write failed " + p + ": " + e.getMessage());
        }
    }

    private static void writeMasterIndex(
            List<ModuleResult> results,
            Path output,
            ObjectMapper jsonMapper) {

        MasterIndex master = new MasterIndex();
        for (ModuleResult r : results) {
            ModuleIndex mi = new ModuleIndex();
            mi.name = r.name;
            mi.repo = r.repo;
            mi.path = r.path;
            mi.fileCount = r.fileCount;
            mi.failedCount = r.failedCount;
            mi.packages = r.packages;
            mi.withCalls = r.packages.stream().anyMatch(p -> p.callsPath != null);
            master.modules.add(mi);
            master.totalFiles += r.fileCount;
            master.totalPackages += r.packages.size();
            master.totalMethods += r.packages.stream().mapToInt(p -> p.methods).sum();
        }

        Path masterFile = output.resolve("index.json");
        try {
            jsonMapper.writeValue(masterFile.toFile(), master);
            long size = Files.size(masterFile);
            System.out.printf("  OK %s (%.1f KB, %d modules, %d packages)%n",
                    masterFile.getFileName(),
                    size / 1024.0,
                    master.modules.size(),
                    master.totalPackages);
        } catch (IOException e) {
            System.err.println("  ! write master index failed: " + e.getMessage());
        }
    }

    private static class ModuleResult {
        String name;
        String repo;
        String path;
        int fileCount;
        int failedCount;
        long elapsedMs;
        List<PackageEntry> packages = new ArrayList<>();
    }

    public static class PackageEntry {
        public String pkg;
        public int files;
        public int methods;
        public int constructors;
        public int fields;
        public int innerTypes;
        public String sigPath;
        public String callsPath;
        public String callersPath;
        public String jsonPath;
    }

    public static class ModuleIndex {
        public String name;
        public String repo;
        public String path;
        public int fileCount;
        public int failedCount;
        public boolean withCalls;
        public List<PackageEntry> packages = new ArrayList<>();
    }

    public static class MasterIndex {
        public int totalFiles;
        public int totalPackages;
        public int totalMethods;
        public List<ModuleIndex> modules = new ArrayList<>();
    }
}