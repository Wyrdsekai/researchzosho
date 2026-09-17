package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A code repository, read for {@link Surveys}: its README and docs, its build and dependency files, the
 * shape of its tree, the top of a sample of its source files. The description, the claim, the directions
 * and the runs are Surveys' work, the same for every kind of thing.
 *
 * <p>A path is read in place. A URL is cloned (shallow) into {@code raw/repos/} when git is installed;
 * when it is not, the call stops and says so, with the command to run by hand.
 */
public final class Repos {

    /** Source files sampled; characters of each file shown to the model. */
    static final int SAMPLE_FILES = 12;
    static final int SAMPLE_CHARS = 1500;
    static final int README_CHARS = 8000;
    static final int MANIFEST_CHARS = 2500;
    static final int CLONE_SECONDS = 300;

    static final Set<String> MANIFESTS = Set.of("package.json", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Cargo.toml", "go.mod",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml", "Gemfile", "composer.json", "CMakeLists.txt", "Makefile",
            "Package.swift", "pubspec.yaml", "mix.exs", "Dockerfile", "docker-compose.yml", "flake.nix");
    static final Set<String> SOURCE = Set.of("java", "kt", "py", "rs", "go", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "rb", "php", "swift",
            "scala", "clj", "ex", "exs", "hs", "ml", "lua", "sh", "sql", "r", "jl", "zig", "dart", "m", "mm");
    static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "build", "dist", "target", "out", ".gradle", ".idea", ".vscode", "vendor", "__pycache__",
            ".venv", "venv", ".tox", ".mypy_cache", "coverage", ".next", ".cache", "bin", "obj");

    /** Where the repo is, what it is called, and where it came from (a path, or the url it was cloned from). */
    public record Repo(Path dir, String name, String origin, boolean cloned) { }

    /** What the reading found: the pieces the model is shown, and the counts. */
    public record Survey(Repo repo, String readme, Map<String, String> manifests, List<String> docs, Map<String, Integer> byLanguage, List<String> topDirs,
                         Map<String, String> samples, int files) {
        public String languages() {
            StringBuilder sb = new StringBuilder();
            byLanguage.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> { if (sb.length() > 0) sb.append(", "); sb.append(e.getKey()).append(' ').append(e.getValue()); });
            return sb.toString();
        }
    }

    private Repos() { }

    // ---- obtaining ----

    public static boolean isUrl(String spec) { return spec.startsWith("http://") || spec.startsWith("https://") || spec.startsWith("git@") || spec.endsWith(".git"); }

    /** The repo's short name: the last path segment, without .git. */
    public static String nameOf(String spec) {
        String s = spec.strip().replaceAll("[/\\\\]+$", "");
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int i = Math.max(s.lastIndexOf('/'), Math.max(s.lastIndexOf('\\'), s.lastIndexOf(':')));
        String n = i < 0 ? s : s.substring(i + 1);
        n = n.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "repo" : n;
    }

    /** Whether git answers on this machine. */
    public static boolean gitInstalled() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    /** A path in place, or a url cloned under raw/repos/. Throws with a plain message when git is missing or the clone fails. */
    public static Repo obtain(LibraryStore store, String spec) throws IOException {
        String s = spec.strip();
        if (!isUrl(s)) {
            Path dir = Path.of(s).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) throw new IOException(dir + " is not a folder");
            return new Repo(dir, nameOf(dir.toString()), dir.toString(), false);
        }
        if (!gitInstalled()) throw new IOException("git is not installed on this machine, so the library cannot clone " + s + ". Install git, or clone it yourself (git clone " + s + ") and give the folder's path instead.");
        Path dir = store.rawDir().resolve("repos").resolve(nameOf(s));
        Files.createDirectories(dir.getParent());
        List<String> cmd = Files.isDirectory(dir.resolve(".git"))
                ? List.of("git", "-C", dir.toString(), "pull", "--ff-only", "--quiet")
                : List.of("git", "clone", "--depth", "1", "--quiet", s, dir.toString());
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(CLONE_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("git took longer than " + CLONE_SECONDS + " seconds on " + s); }
            if (p.exitValue() != 0 && !Files.isDirectory(dir.resolve(".git"))) throw new IOException("git could not clone " + s + ": " + Acquisitions.compress(out.strip(), 300));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted while cloning " + s); }
        return new Repo(dir, nameOf(s), s, true);
    }

    // ---- reading ----

    static String ext(Path p) { String n = p.getFileName().toString(); int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT); }

    static String head(Path p, int chars) {
        try {
            byte[] b = Files.readAllBytes(p);
            String s = new String(b, 0, Math.min(b.length, chars * 2), StandardCharsets.UTF_8);
            return s.length() > chars ? s.substring(0, chars) : s;
        } catch (IOException e) { return ""; }
    }

    /** Read the repo: README, docs, manifests, the tree's shape, and the top of a sample of source files. */
    public static Survey survey(Repo repo) throws IOException {
        Path root = repo.dir();
        String readme = "";
        Map<String, String> manifests = new LinkedHashMap<>();
        List<String> docs = new ArrayList<>();
        Map<String, Integer> byLanguage = new TreeMap<>();
        List<String> topDirs = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        int[] files = {0};
        try (var top = Files.list(root)) { top.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !SKIP_DIRS.contains(n) && !n.startsWith(".")).sorted().forEach(topDirs::add); }
        try (var walk = Files.walk(root, 12)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p)) continue;
                boolean skip = false;
                for (Path part : root.relativize(p)) if (SKIP_DIRS.contains(part.toString())) { skip = true; break; }
                if (skip) continue;
                files[0]++;
                String name = p.getFileName().toString(), e = ext(p), lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("readme") && readme.isEmpty() && p.getParent().equals(root)) readme = head(p, README_CHARS);
                else if ((e.equals("md") || e.equals("rst") || e.equals("txt")) && !lower.startsWith("readme") && docs.size() < 40) docs.add(root.relativize(p).toString());
                if (MANIFESTS.contains(name) && manifests.size() < 8) manifests.put(root.relativize(p).toString(), head(p, MANIFEST_CHARS));
                if (SOURCE.contains(e)) { byLanguage.merge(e, 1, Integer::sum); sources.add(p); }
            }
        }
        // a sample of the source: the largest files of the commonest languages, spread across the tree
        List<String> langs = byLanguage.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).map(Map.Entry::getKey).limit(3).toList();
        Map<String, String> samples = new LinkedHashMap<>();
        for (String lang : langs) {
            List<Path> of = new ArrayList<>(sources.stream().filter(p -> ext(p).equals(lang)).toList());
            of.sort((a, b) -> { try { return Long.compare(Files.size(b), Files.size(a)); } catch (IOException e) { return 0; } });
            Set<Path> dirs = new LinkedHashSet<>();
            for (Path p : of) {
                if (samples.size() >= SAMPLE_FILES) break;
                if (!dirs.add(p.getParent()) && dirs.size() < of.size()) continue;   // one per folder first
                samples.put(root.relativize(p).toString(), head(p, SAMPLE_CHARS));
            }
        }
        return new Survey(repo, readme, manifests, docs, byLanguage, topDirs, samples, files[0]);
    }

    /** The survey as the model sees it, and as it is shelved. */
    public static String render(Survey s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Repository: ").append(s.repo().name()).append("\n\n");
        sb.append("Origin: ").append(s.repo().origin()).append("\nFiles: ").append(s.files()).append("\nLanguages (files): ").append(s.languages().isEmpty() ? "none recognised" : s.languages()).append('\n');
        sb.append("Top-level folders: ").append(String.join(", ", s.topDirs())).append("\n\n");
        if (!s.readme().isEmpty()) sb.append("## README\n\n").append(s.readme().strip()).append("\n\n");
        for (var e : s.manifests().entrySet()) sb.append("## ").append(e.getKey()).append("\n\n```\n").append(e.getValue().strip()).append("\n```\n\n");
        if (!s.docs().isEmpty()) sb.append("## Documents in the tree\n\n").append(String.join("\n", s.docs().stream().map(d -> "- " + d).toList())).append("\n\n");
        for (var e : s.samples().entrySet()) sb.append("## ").append(e.getKey()).append(" (top)\n\n```\n").append(e.getValue().strip()).append("\n```\n\n");
        return sb.toString();
    }

    // ---- what the manifests name ----

    static final Pattern DEP = Pattern.compile("^\\s*(?:\"?([A-Za-z0-9@][A-Za-z0-9_./@-]{1,60})\"?\\s*[:=]\\s*\"?[~^><=]*\\d|implementation\\(?[\"']([^\"':]+)|([A-Za-z0-9_.-]{2,60})\\s*(?:>=|==|~=|<)\\s*\\d|(github\\.com/[A-Za-z0-9_./-]+)\\s+v\\d)", Pattern.MULTILINE);

    /** The dependencies the rendered survey's manifest blocks name, without the manifest's own keys. */
    static List<String> dependencies(String rendered) {
        List<String> deps = new ArrayList<>();
        Matcher blocks = Pattern.compile("```\\n(.*?)\\n```", Pattern.DOTALL).matcher(rendered);
        while (blocks.find()) {
            Matcher m = DEP.matcher(blocks.group(1));
            while (m.find() && deps.size() < 12) {
                String d = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
                if (d == null) continue;
                d = d.strip();
                if (d.length() < 2 || d.matches("(?i)version|name|main|scripts|dependencies|devdependencies|engines|private|license|description|python|node|java|module|go|edition|authors|readme|requires-python")) continue;
                if (!deps.contains(d)) deps.add(d);
            }
        }
        return deps;
    }
}
