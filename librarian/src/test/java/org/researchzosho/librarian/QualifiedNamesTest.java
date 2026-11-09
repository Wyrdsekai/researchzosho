package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No fully-qualified class names in the Java code, main or test: a class is imported and written by its simple name. the keeper's rule across
 * all his projects. Read from the source: import and package lines, comments, strings and text blocks are not code and are not read. A name
 * stays written out only where its simple name is taken in that file: a type the file declares, another import of that simple name, a class
 * of the file's own package, a class of java.lang, a class of a package the file imports whole, or another class of that simple name the
 * file also writes out.
 */
class QualifiedNamesTest {

    static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.$])((?:java|javax|jdk|sun|org|com|software)(?:\\.[a-z_][a-z0-9_]*)+)\\.([A-Z][A-Za-z0-9_]*)");
    static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;");
    static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    static final Pattern DECLARED = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");

    static Path src() { return Files.exists(Path.of("src")) ? Path.of("src") : Path.of("librarian/src"); }

    static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String part : List.of("main", "test")) {
            Path root = src().resolve(part).resolve("java");
            try (Stream<Path> s = Files.walk(root)) { s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add); }
        }
        return out;
    }

    /** The code of a source file: comments, strings, text blocks and character literals blanked to spaces, the line breaks kept. */
    static String codeOnly(String src) {
        StringBuilder b = new StringBuilder(src);
        int i = 0, n = src.length();
        while (i < n) {
            int end;
            if (src.startsWith("//", i)) { end = src.indexOf('\n', i); if (end < 0) end = n; }
            else if (src.startsWith("/*", i)) { end = src.indexOf("*/", i + 2); end = end < 0 ? n : end + 2; }
            else if (src.startsWith("\"\"\"", i)) { end = src.indexOf("\"\"\"", i + 3); end = end < 0 ? n : end + 3; }
            else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                char q = src.charAt(i);
                end = i + 1;
                while (end < n && src.charAt(end) != q && src.charAt(end) != '\n') end += src.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(n, end + 1);
            } else { i++; continue; }
            for (int k = i; k < end; k++) if (b.charAt(k) != '\n') b.setCharAt(k, ' ');
            i = end;
        }
        return b.toString();
    }

    static boolean exists(String className) {
        try { Class.forName(className, false, QualifiedNamesTest.class.getClassLoader()); return true; } catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    /** Every name written out in the code where its simple name was free, as file:line: name. */
    static List<String> writtenOut(List<Path> files) throws IOException {
        Map<String, Set<String>> byPackage = new HashMap<>();   // the classes of each package of the module, by simple name
        Map<Path, String> code = new HashMap<>(), packages = new HashMap<>();
        for (Path f : files) {
            String c = codeOnly(Files.readString(f, StandardCharsets.UTF_8));
            Matcher pm = PACKAGE.matcher(c);
            String pkg = pm.find() ? pm.group(1) : "";
            code.put(f, c); packages.put(f, pkg);
            byPackage.computeIfAbsent(pkg, k -> new HashSet<>()).add(f.getFileName().toString().replace(".java", ""));
        }
        List<String> found = new ArrayList<>();
        for (Path f : files) {
            String c = code.get(f), pkg = packages.get(f);
            Map<String, String> imported = new HashMap<>();
            List<String> whole = new ArrayList<>();
            Matcher im = IMPORT.matcher(c);
            while (im.find()) {
                if (im.group(1) != null) continue;
                if (im.group(3) != null) whole.add(im.group(2));
                else imported.put(im.group(2).substring(im.group(2).lastIndexOf('.') + 1), im.group(2));
            }
            Set<String> declared = new HashSet<>();
            Matcher dm = DECLARED.matcher(c);
            while (dm.find()) declared.add(dm.group(1));
            String[] lines = c.split("\n", -1);
            Map<String, Set<String>> named = new HashMap<>();   // the simple names written out in this file, with every package each is written with
            for (String line : lines) {
                if (line.strip().startsWith("import ") || line.strip().startsWith("package ")) continue;
                Matcher m = QUALIFIED.matcher(line);
                while (m.find()) named.computeIfAbsent(m.group(2), k -> new HashSet<>()).add(m.group(1));
            }
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].strip().startsWith("import ") || lines[i].strip().startsWith("package ")) continue;
                Matcher m = QUALIFIED.matcher(lines[i]);
                while (m.find()) {
                    String from = m.group(1), simple = m.group(2), full = from + "." + simple;
                    boolean taken = declared.contains(simple)
                            || (imported.containsKey(simple) && !imported.get(simple).equals(full))
                            || (!from.equals(pkg) && byPackage.getOrDefault(pkg, Set.of()).contains(simple))
                            || (!from.equals("java.lang") && exists("java.lang." + simple))
                            || whole.stream().anyMatch(w -> !w.equals(from) && (byPackage.getOrDefault(w, Set.of()).contains(simple) || exists(w + "." + simple)))
                            || named.get(simple).size() > 1;
                    if (!taken) found.add((f.startsWith(src()) ? src().relativize(f) : f) + ":" + (i + 1) + ": " + full);
                }
            }
        }
        return found;
    }

    @Test
    void noClassIsWrittenOutWhereAnImportWouldDo() throws IOException {
        List<Path> files = javaFiles();
        assertTrue(files.size() > 300, "the scan reads the module's sources: " + files.size() + " files under " + src().toAbsolutePath());
        List<String> found = writtenOut(files);
        assertTrue(found.isEmpty(), found.size() + " class name(s) written out in full: import each and write its simple name (the keeper's rule across his projects; a clash with a"
                + " name the file already uses is the only exception)\n" + String.join("\n", found));
    }

    @Test
    void theScanSeesWhatItShouldAndOnlyThat(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("Sample.java");
        Files.writeString(f, String.join("\n",
                "package org.example.sample;",
                "import java.util.List;",
                "import java.util.regex.Pattern;",
                "/** {@link java.util.Map} in a comment is no code. */",
                "class Sample {",
                "    java.util.Set<String> a = new java.util.HashSet<>();   // two written out",
                "    String s = \"java.util.Deque in a string\";",
                "    String t = \"\"\"",
                "        java.util.Queue in a text block",
                "        \"\"\";",
                "    char q = '\"';",
                "    java.awt.List widgets;   // List is imported: a clash, kept",
                "    record Pattern(int n) { }",
                "    static final java.util.regex.Pattern P = java.util.regex.Pattern.compile(\"x\");   // the record takes the simple name: kept",
                "    java.lang.String u;   // java.lang written out where nothing takes the name",
                "}"), StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        for (String line : writtenOut(List.of(f))) found.add(line.substring(line.indexOf(": ") + 2));
        assertEquals(List.of("java.util.Set", "java.util.HashSet", "java.lang.String"), found);
    }
}
