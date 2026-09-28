package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The genealogy module's boundary, read from the source. The module is the set of classes declared in
 * {@code src/test/resources/genealogy-module.txt}; every other main class is the core. The core may reach the module only through the
 * {@link Profile} hooks: it names none of the module's classes, imports none, loads none by name, and writes none of genealogy's own
 * words (its writers' names, the word genealogy, the slugs of its relations) as a string.
 *
 * <p>What is still left over from before the module was drawn is listed in {@code genealogy-boundary-baseline.txt}. The file is written by
 * the test ({@code ./gradlew :librarian:test --tests '*GenealogyBoundaryTest' -Dboundary.write=true}), never by hand, and only shrinks: a
 * reference that is not in it fails, and so does a line of it that no longer matches the source. When it is empty, the core is compiled
 * without the module to prove it.
 *
 * <p>What a source scan cannot see: genealogy writing into files the core reads too (nodes.md, merges.tsv, predicates.md, the frontier,
 * the search log). {@code GenealogyIsolationDifferentialTest} covers those, from what the library shows. A green boundary test says
 * nothing about the data.
 */
class GenealogyBoundaryTest {

    static Path src() { return Files.exists(Path.of("src")) ? Path.of("src") : Path.of("librarian/src"); }
    static Path mainJava() { return src().resolve("main").resolve("java"); }
    static Path declaredFile() { return src().resolve("test").resolve("resources").resolve("genealogy-module.txt"); }
    static Path baselineFile() { return src().resolve("test").resolve("resources").resolve("genealogy-boundary-baseline.txt"); }

    /** The module's files (paths under src/main/java, with .java) and their simple class names. */
    record Module(Set<String> files, Set<String> names) { }

    static Module declared() throws IOException {
        Set<String> files = new LinkedHashSet<>(), names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(declaredFile(), StandardCharsets.UTF_8)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            files.add(l + ".java");
            names.add(l.substring(l.lastIndexOf('/') + 1));
        }
        return new Module(files, names);
    }

    static List<String> mainFiles() throws IOException {
        Path root = mainJava();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(p -> root.relativize(p).toString().replace(File.separatorChar, '/')).sorted().toList();
        }
    }

    @Test
    void everyGenealogyClassIsInTheDeclaredModule() throws Exception {
        Module m = declared();
        for (String f : m.files()) assertTrue(Files.exists(mainJava().resolve(f)), "genealogy-module.txt names a class that does not exist: " + f);
        List<String> missing = new ArrayList<>();
        for (String f : mainFiles()) {
            String name = f.substring(f.lastIndexOf('/') + 1).replace(".java", "");
            boolean family = name.startsWith("Family") || name.startsWith("Gedcom");
            String text = Files.readString(mainJava().resolve(f), StandardCharsets.UTF_8);
            boolean genealogyProfile = text.contains("implements Profile") && text.contains("return \"genealogy\";");
            if ((family || genealogyProfile) && !m.files().contains(f)) missing.add(f);
        }
        assertTrue(missing.isEmpty(), "these classes are genealogy's and are not listed in " + declaredFile() + ": " + missing);
    }

    /** A source file read as code: comments gone, string literals emptied and kept aside with their line. */
    record Lexed(String code, List<String[]> literals) { }

    static Lexed lex(String s) {
        StringBuilder code = new StringBuilder(s.length());
        List<String[]> lits = new ArrayList<>();
        int i = 0, line = 1, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') { line++; code.append(c); i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') { while (i < n && s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) { if (s.charAt(i) == '\n') { line++; code.append('\n'); } i++; }
                i += 2; continue;
            }
            if (c == '"' && s.startsWith("\"\"\"", i)) {
                int start = line; StringBuilder lit = new StringBuilder(); i += 3;
                while (i < n && !s.startsWith("\"\"\"", i)) { char d = s.charAt(i); if (d == '\\' && i + 1 < n) { lit.append(d).append(s.charAt(i + 1)); i += 2; continue; } if (d == '\n') { line++; code.append('\n'); } lit.append(d); i++; }
                i += 3; code.append("\"\""); lits.add(new String[]{String.valueOf(start), lit.toString()}); continue;
            }
            if (c == '"') {
                StringBuilder lit = new StringBuilder(); i++;
                while (i < n && s.charAt(i) != '"' && s.charAt(i) != '\n') { char d = s.charAt(i); if (d == '\\' && i + 1 < n) { lit.append(unescape(s.charAt(i + 1))); i += 2; continue; } lit.append(d); i++; }
                i++; code.append("\"\""); lits.add(new String[]{String.valueOf(line), lit.toString()}); continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != '\'' && s.charAt(j) != '\n') { if (s.charAt(j) == '\\') j++; j++; }
                code.append("' '"); i = Math.min(n, j + 1); continue;
            }
            code.append(c); i++;
        }
        return new Lexed(code.toString(), lits);
    }

    private static char unescape(char c) { return switch (c) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; default -> c; }; }

    /** Genealogy's own words, which the core may not write as a string: its writers, its relations' slugs. */
    static Set<String> ownLiterals() {
        Set<String> out = new LinkedHashSet<>(new GenealogyProfile().ownWriters());
        for (Vocabulary.Term t : new GenealogyProfile().predicates()) out.add(t.slug());
        return out;
    }

    /**
     * Every reference of the core into the module, one line each: the core file, the kind of reference, what it names, and how many
     * times. The lines are sorted, so the baseline is stable under edits that move code around.
     */
    static List<String> references() throws IOException {
        Module m = declared();
        Set<String> literals = ownLiterals();
        Map<String, Integer> counts = new TreeMap<>();
        for (String f : mainFiles()) {
            if (m.files().contains(f)) continue;
            Lexed lx = lex(Files.readString(mainJava().resolve(f), StandardCharsets.UTF_8));
            for (String name : m.names()) {
                Matcher x = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])").matcher(lx.code());
                while (x.find()) counts.merge(f + "\tclass\t" + name, 1, Integer::sum);
            }
            for (String[] lit : lx.literals()) {
                String v = lit[1];
                if (literals.contains(v)) counts.merge(f + "\tword\t" + v, 1, Integer::sum);
                if (v.toLowerCase(Locale.ROOT).contains("genealog")) counts.merge(f + "\tgenealogy\t" + (v.length() > 60 ? v.substring(0, 60) + "…" : v).replace('\t', ' ').replace('\n', ' '), 1, Integer::sum);
                for (String name : m.names()) if (v.endsWith("." + name) || v.equals(name)) counts.merge(f + "\tby-name\t" + v, 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>();
        for (var e : counts.entrySet()) out.add(e.getKey() + "\t" + e.getValue());
        return out;
    }

    static List<String> baseline() throws IOException {
        if (!Files.exists(baselineFile())) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(baselineFile(), StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) out.add(l);
        return out;
    }

    @Test
    void theCoreReachesTheModuleOnlyThroughTheProfileHooks() throws Exception {
        List<String> now = references();
        if (Boolean.getBoolean("boundary.write")) {
            List<String> file = new ArrayList<>(List.of("# Written by GenealogyBoundaryTest with -Dboundary.write=true. Never edit by hand: this list only shrinks.",
                    "# core file\tkind\twhat it names\thow many times"));
            file.addAll(now);
            Files.write(baselineFile(), file, StandardCharsets.UTF_8);
            return;
        }
        List<String> base = baseline();
        List<String> added = new ArrayList<>(now); added.removeAll(base);
        List<String> gone = new ArrayList<>(base); gone.removeAll(now);
        StringBuilder why = new StringBuilder();
        if (!added.isEmpty()) why.append("The core reaches into the genealogy module in ways the baseline does not list. Use a Profile hook instead:\n  ").append(String.join("\n  ", added)).append('\n');
        if (!gone.isEmpty()) why.append("These baseline lines no longer match the source. If the reference went, write the baseline again with -Dboundary.write=true so it shrinks:\n  ").append(String.join("\n  ", gone)).append('\n');
        assertTrue(added.isEmpty() && gone.isEmpty(), why.toString());
    }

    @Test
    void theCoreCompilesWithoutTheModule() throws Exception {
        List<String> base = baseline();
        assumeTrue(base.isEmpty(), "the core still reaches the module in " + base.size() + " way(s) the baseline lists; the compile check runs when the list is empty");
        Module m = declared();
        List<Path> core = new ArrayList<>();
        for (String f : mainFiles()) if (!m.files().contains(f)) core.add(mainJava().resolve(f));
        // the classpath the tests run with, without this project's own compiled classes: otherwise the module resolves from them and the check proves nothing
        List<String> cp = new ArrayList<>();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) {
            String u = e.replace(File.separatorChar, '/');
            if (u.contains("/build/classes/java/") || u.contains("/build/resources/")) continue;
            cp.add(e);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "the tests run on a JDK");
        Path out = Files.createTempDirectory("core-alone");
        StringWriter errors = new StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(core);
            boolean ok = javac.getTask(errors, fm, null, List.of("--release", "21", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-d", out.toString(), "-cp", String.join(File.pathSeparator, cp)), null, units).call();
            assertTrue(ok, "the core does not compile without the genealogy module. Reach it through a Profile hook instead:\n" + errors);
        }
    }
}
