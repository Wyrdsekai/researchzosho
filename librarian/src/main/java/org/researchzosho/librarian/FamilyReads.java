package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the family reader has read, in {@code family/read-files.tsv}: every source, one line each, with what it was and when. A file of
 * a folder read as a whole, a file, a picture, a tree file or a list of web addresses read on its own, a web page, and a Geni profile
 * with the --steps it was read with. A page read from a list names the list, and a Geni profile reached from another names the first
 * one. A reset keeps the list as it was in its copy ({@code family/backup-…/read-files.tsv}), so the lists of every copy and the one now
 * together say which folder was read last, which command reads a source again, and where the facts about a name came from.
 *
 * <p>A line is {@code <key> TAB <when> TAB <how> TAB <path or address>}. The key is a file's SHA-256, so a folder read skips a file it
 * has read while the file has not changed, or "-" for an address. {@code how} is the kind, then what the read was given, as
 * {@code ;name=value}. The lines older versions wrote are {@code <sha> TAB <date> TAB <path>}, and only a folder read wrote them: they
 * are read as files of a folder. The path or address is always the last column, so an older version still reads every line.
 *
 * <p>An address is compared as one address however it is written ({@link #addressKey}): with its %-escapes decoded, so a Japanese Geni
 * address written {@code %E9%81%A0…} and written in characters is one address, without {@code www.}, a trailing slash or a fragment,
 * and a Geni profile by its number alone.
 */
public final class FamilyReads {

    private FamilyReads() { }

    /** The kinds of source, and a folder, which is read as a whole. */
    public static final String FILE = "file", PICTURE = "picture", TREE = "gedcom", LIST = "list", PAGE = "page", GENI = "geni", FOLDER = "folder", OTHER = "other";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * One source read. {@code folder}: the folder it was read with, "" when it was read by itself. {@code list}: the list of addresses a
     * page or a Geni profile was read from. {@code from}: the Geni profile a relative's profile was reached from. {@code steps}: how many
     * Geni profiles the read took. {@code by} and {@code families}: what --by and --family said. {@code old}: a line an older version wrote.
     */
    public record Row(String key, String when, String kind, String folder, String list, String from, int steps, String by, String families, String where, boolean old) {
        /** A file of a folder the library read as a whole: a new read of that folder reads it again. Older versions wrote only such lines. */
        public boolean ofFolder() { return old || !folder.isEmpty(); }

        String line() {
            StringBuilder how = new StringBuilder(kind);
            if (!folder.isEmpty()) how.append(";folder=").append(enc(folder));
            if (!list.isEmpty()) how.append(";list=").append(enc(list));
            if (!from.isEmpty()) how.append(";from=").append(enc(from));
            if (steps > 1) how.append(";steps=").append(steps);
            if (!by.isEmpty()) how.append(";by=").append(enc(by));
            if (!families.isEmpty()) how.append(";family=").append(enc(families));
            return key + "\t" + when + "\t" + how + "\t" + where.replaceAll("[\\t\\r\\n]", " ");
        }
    }

    private static String enc(String v) { return v.replace("%", "%25").replace(";", "%3B").replace("=", "%3D").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D"); }

    private static String dec(String v) { return v.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%3D", "=").replace("%3B", ";").replace("%25", "%"); }

    private static String nz(String s) { return s == null ? "" : s.strip(); }

    static Row parse(String line) {
        String[] c = line.split("\t", -1);
        if (c.length < 2) return null;
        if (c.length <= 3) return new Row(c[0], c.length == 3 ? c[1] : "", FILE, "", "", "", 0, "", "", c[c.length - 1], true);
        String[] how = c[2].split(";");
        String folder = "", list = "", from = "", by = "", families = "";
        int steps = 0;
        for (int i = 1; i < how.length; i++) {
            int eq = how[i].indexOf('=');
            if (eq < 0) continue;
            String k = how[i].substring(0, eq), v = dec(how[i].substring(eq + 1));
            switch (k) {
                case "folder" -> folder = v;
                case "list" -> list = v;
                case "from" -> from = v;
                case "by" -> by = v;
                case "family" -> families = v;
                case "steps" -> { try { steps = Integer.parseInt(v); } catch (NumberFormatException e) { steps = 1; } }
                default -> { }
            }
        }
        return new Row(c[0], c[1], how[0].isBlank() ? FILE : how[0], folder, list, from, steps, by, families, c[c.length - 1], false);
    }

    public static Path ledger(LibraryStore store) { return store.root().resolve("family").resolve("read-files.tsv"); }

    public static List<Row> rows(LibraryStore store) throws IOException { return rows(ledger(store)); }

    public static List<Row> rows(Path file) throws IOException {
        List<Row> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) return out;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) { if (l.isBlank()) continue; Row r = parse(l); if (r != null) out.add(r); }
        return out;
    }

    private static synchronized void add(LibraryStore store, Row row) throws IOException {
        Path f = ledger(store);
        Files.createDirectories(f.getParent());
        Files.writeString(f, row.line() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Writes down that a file was read: by its content, so that a folder read skips it while it has not changed. {@code folder}: "" for a file read by itself. */
    public static void readFile(LibraryStore store, Path file, String kind, String folder, String by, String families) throws IOException {
        add(store, new Row(FamilyFolder.sha(file), LocalDateTime.now().format(WHEN), kind, nz(folder), "", "", 0, nz(by), nz(families), file.toAbsolutePath().normalize().toString(), false));
    }

    /** Writes down that an address was read: a web page, or a Geni profile with the --steps it was read with. */
    public static void readAddress(LibraryStore store, String address, String kind, String list, String from, int steps, String by) throws IOException {
        add(store, new Row("-", LocalDateTime.now().format(WHEN), kind, "", nz(list), nz(from), steps, nz(by), "", nz(address), false));
    }

    // ── the copies resets kept ─────────────────────────────────────────────────────────────────────────────────────────

    private static final Pattern BACKUP = Pattern.compile("backup-(\\d{8}-\\d{6})(?:-(\\d+))?");

    /** Every copy a reset kept ({@code family/backup-<stamp>}), the newest first. Two resets in one second keep {@code -2}, {@code -3}. */
    public static List<Path> backups(LibraryStore store) throws IOException {
        Path dir = store.root().resolve("family");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isDirectory(p) && BACKUP.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> stamp(p)).thenComparingInt(p -> number(p)).reversed()).toList();
        }
    }

    private static String stamp(Path backup) { Matcher m = BACKUP.matcher(backup.getFileName().toString()); return m.matches() ? m.group(1) : ""; }

    private static int number(Path backup) { Matcher m = BACKUP.matcher(backup.getFileName().toString()); return m.matches() && m.group(2) != null ? Integer.parseInt(m.group(2)) : 1; }

    /** The day the reset that kept this copy was made, as 2026-09-24. */
    public static String day(Path backup) {
        try { return LocalDateTime.parse(stamp(backup), DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")).toLocalDate().toString(); }
        catch (DateTimeParseException e) { return stamp(backup); }
    }

    /** Every line of the list of reads in every copy a reset kept, the oldest copy first, and the list as it is now last. */
    public static List<Row> everyRow(LibraryStore store) throws IOException {
        List<Row> out = new ArrayList<>();
        List<Path> copies = new ArrayList<>(backups(store));
        Collections.reverse(copies);
        for (Path b : copies) out.addAll(rows(b.resolve("read-files.tsv")));
        out.addAll(rows(store));
        return out;
    }

    /** The facts a reset's copy holds. */
    static List<Finding> copies(Path backup) throws IOException {
        List<Finding> out = new ArrayList<>();
        Path dir = backup.resolve("findings");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".md")).sorted().toList()) {
                try { out.add(Finding.parse(Files.readString(p, StandardCharsets.UTF_8))); } catch (RuntimeException unreadable) { }
            }
        }
        return out;
    }

    // ── one address however it is written ──────────────────────────────────────────────────────────────────────────────

    /** A path or an address as a person reads it: its %-escapes decoded, a file without "file://" in front. A stray % stays as it is. */
    public static String decoded(String locator) {
        String s = locator == null ? "" : locator.strip();
        if (s.contains("%")) {
            try { s = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); } catch (IllegalArgumentException stray) { }
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        return s.startsWith("file:") ? filePath(s) : s;
    }

    /** A file: address as a path: "file:///home/me/x" is /home/me/x, and "file:///C:/Users/me/x" is C:/Users/me/x, with its drive letter first as Windows writes it. */
    static String filePath(String locator) {
        String p = locator.replaceFirst("^file:/*", "/");
        return p.matches("^/[A-Za-z]:[\\\\/].*") ? p.substring(1) : p;
    }

    private static final Pattern URL = Pattern.compile("(?i)^(https?)://([^/?#]+)([^?#]*)(\\?[^#]*)?(?:#.*)?$");

    /**
     * A path or an address as it is compared: decoded ({@link #decoded}); a Geni profile by its number, whatever name the address carries;
     * a web address without http or https, {@code www.}, the usual port, a trailing slash or a fragment; small letters throughout.
     */
    public static String addressKey(String where) {
        String d = decoded(where);
        String guid = GeniFamily.guidOf(d);
        if (guid != null) return "geni:" + guid;
        Matcher m = URL.matcher(d);
        if (m.matches()) {
            String host = m.group(2).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceFirst(":(80|443)$", "");
            String path = m.group(3).replaceAll("/+$", "");
            String query = m.group(4) == null || m.group(4).equals("?") ? "" : m.group(4);
            return (host + path + query).toLowerCase(Locale.ROOT);
        }
        return FamilyReset.key(d);
    }

    static boolean isFile(String where) { return where.startsWith("/") || where.matches("^[A-Za-z]:[\\\\/].*"); }

    private static String parent(String path) { int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return i > 0 ? path.substring(0, i) : path; }

    static String name(String path) { int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return i >= 0 ? path.substring(i + 1) : path; }

    private static String host(String url) {
        Matcher m = URL.matcher(url == null ? "" : url.strip());
        return m.matches() ? m.group(2).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") : "";
    }

    static boolean geniAddress(String where) { return GeniFamily.guidOf(where) != null || host(where).endsWith("geni.com"); }

    // ── folders ────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The folders the library read as a whole, shortest first: the ones the lines name, and for the lines older versions wrote, the folder each file is in. */
    public static List<String> folders(Collection<Row> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (Row r : rows) if (!r.folder().isEmpty()) out.add(r.folder());
        for (Row r : rows) if (r.old() && isFile(r.where())) out.add(parent(r.where()));
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparingInt(String::length));
        return sorted;
    }

    /** The folders to name to a person: the ones no other one is inside. */
    public static List<String> topFolders(Collection<Row> rows) {
        List<String> out = new ArrayList<>();
        for (String f : folders(rows)) if (out.stream().noneMatch(o -> folderKey(f).startsWith(folderKey(o) + "/"))) out.add(f);
        return out;
    }

    static boolean sameFolder(String a, String b) { return folderKey(a).equals(folderKey(b)); }

    /**
     * A folder as it is compared: as {@link #decoded} writes a path, in composed characters, and as {@link FamilyReset#key}, without a slash at
     * the end. A folder copied from a Mac can have its name in decomposed characters (デ as テ and its mark), and is the same folder.
     */
    private static String folderKey(String path) { return FamilyReset.key(decoded(path)).replaceAll("/+$", ""); }

    /** The folder read as a whole that a file is in, three folders down at most, as a folder read goes; null when it is in none. */
    public static String folderOf(String path, List<String> folders) {
        String d = decoded(path);
        if (!isFile(d)) return null;
        String k = FamilyReset.key(d);
        for (String f : folders) {
            String fk = folderKey(f);
            if (k.startsWith(fk + "/") && k.substring(fk.length() + 1).split("/").length <= 3) return f;
        }
        return null;
    }

    /**
     * The files a new read of the folders brings back, each by its address ({@link #addressKey}): a file that every line of the lists of
     * reads for it says was read with a folder read as a whole, that is still where it was, and that a folder read reads (its kind, three
     * folders down at most). What came from a file read on its own, even one inside the folder, from a file that is no longer there, or
     * from a kind of file a folder read leaves out, no read of the folder brings back.
     */
    public static Set<String> broughtBackByFolder(Collection<Row> rows) {
        List<String> folders = folders(rows);
        Map<String, List<Row>> byFile = new LinkedHashMap<>();
        for (Row r : rows) if (isFile(decoded(r.where()))) byFile.computeIfAbsent(addressKey(r.where()), k -> new ArrayList<>()).add(r);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<Row>> e : byFile.entrySet()) {
            if (!e.getValue().stream().allMatch(Row::ofFolder)) continue;
            Row last = e.getValue().get(e.getValue().size() - 1);
            String folder = !last.folder().isEmpty() ? last.folder() : folderOf(last.where(), folders);
            if (folder == null || folderOf(last.where(), List.of(folder)) == null) continue;
            try {
                Path file = Path.of(last.where());
                if (Files.isRegularFile(file) && FamilyFolder.reads(file)) out.add(e.getKey());
            } catch (RuntimeException unreadable) { }
        }
        return out;
    }

    /** Whether this path is a file that no longer exists: a file read, and moved or deleted since. */
    static boolean goneFile(String where) {
        String d = decoded(where);
        if (!isFile(d)) return false;
        try { return !Files.exists(Path.of(d)); } catch (RuntimeException unreadable) { return false; }
    }

    /**
     * The folder the library read last as a whole, from the list of reads now and in every copy a reset kept: the folder of the line read
     * latest, and among lines of one time the one written last. Null when the library has read no folder.
     */
    public static String lastFolder(LibraryStore store) throws IOException {
        List<Row> all = everyRow(store), older = all.stream().filter(Row::old).toList();
        List<String> folders = folders(all);
        String best = null, bestWhen = null;
        boolean bestOld = false;
        for (Row r : all) {
            if (!r.ofFolder()) continue;
            String f = !r.folder().isEmpty() ? r.folder() : folderOf(r.where(), folders);
            if (f == null) continue;
            if (bestWhen == null || r.when().compareTo(bestWhen) >= 0) { best = f; bestWhen = r.when(); bestOld = r.folder().isEmpty(); }
        }
        if (best == null || !bestOld) return best;
        // a line an older version wrote names the folder its file is in: the folder that read was given is the outermost of those lines' folders that holds it
        for (String top : topFolders(older)) if (sameFolder(best, top) || folderOf(best + "/x", List.of(top)) != null) return top;
        return best;
    }

    // ── which read brings a source back ────────────────────────────────────────────────────────────────────────────────

    /**
     * A read that brings back what came from a source: a folder, which is read as a whole, or one file, list of addresses, tree file,
     * picture, web page or Geni profile, with what it was read with. {@code steps}: the --steps the command gives, the largest any read of
     * it had; 0 or 1 gives none.
     */
    public record Reread(String kind, String where, int steps, String by, String families) {
        public boolean geni() { return kind.equals(GENI); }
        public boolean folder() { return kind.equals(FOLDER); }

        /** The command that reads it again, as a person types it. */
        public String command() {
            if (kind.equals(TREE)) return "researchzosho genealogy import \"" + where + "\"";
            return "researchzosho genealogy read \"" + where + "\"" + (steps > 1 ? " --steps " + steps : "") + (by.isBlank() ? "" : " --by \"" + by + "\"")
                    + (families.isBlank() ? "" : " --family " + families);
        }

        /** What that command does, in a sentence. */
        public String says() {
            String geniSteps = steps > 1 ? " Each Geni profile goes on through the relatives' families, " + steps + " profiles in all, as the first read did, and Geni allows one profile every ten seconds." : "";
            return switch (kind) {
                case FOLDER -> "reads every file in this folder that the library has not read yet, your own notes first, and the pages of any list of web addresses in it." + geniSteps;
                case GENI -> steps > 1 ? "reads this Geni profile again and goes on through the relatives' families, " + steps + " profiles in all, as the first read did. Geni allows one profile every ten seconds."
                        : "reads this Geni profile again: the person, their dates and places, their partners, parents and children.";
                case PAGE -> "reads this web page again.";
                case LIST -> "reads this list of web addresses again, and every page and Geni profile in it." + geniSteps;
                case TREE -> "reads this family-tree file (GEDCOM) again.";
                case PICTURE -> "reads the writing in this picture again. A transcript of it that you accepted is read as it stands.";
                case OTHER -> "reads this address again.";
                default -> "reads this file again.";
            };
        }
    }

    /** Every read of each source by its address ({@link #addressKey}), from the lists of reads now and in the copies resets kept. */
    public static final class Index {
        private final Map<String, List<Row>> byKey = new LinkedHashMap<>();
        private final List<Row> rows;
        final List<String> folders;
        /** The files a new read of the folders brings back ({@link #broughtBackByFolder}). */
        final Set<String> back;

        public Index(Collection<Row> rows) {
            this.rows = new ArrayList<>(rows);
            for (Row r : rows) byKey.computeIfAbsent(addressKey(r.where()), k -> new ArrayList<>()).add(r);
            folders = folders(rows);
            back = broughtBackByFolder(rows);
        }

        public static Index of(LibraryStore store) throws IOException { return new Index(everyRow(store)); }

        /** The read of a source that brings it back most simply: as a file of a folder, then read by itself, then from a Geni profile, then from a list; the latest of those. */
        Row row(String key) {
            List<Row> all = byKey.get(key);
            if (all == null) return null;
            Row best = null;
            int bestRank = 9;
            for (Row r : all) {
                int rank = r.ofFolder() ? 0 : r.list().isEmpty() && r.from().isEmpty() ? 1 : !r.from().isEmpty() ? 2 : 3;
                if (rank <= bestRank) { best = r; bestRank = rank; }
            }
            return best;
        }

        /** The largest --steps a read of this Geni profile had, 1 when none said. The same profile read twice keeps the larger. */
        int steps(String key) {
            int most = 1;
            for (Row r : byKey.getOrDefault(key, List.of())) most = Math.max(most, r.steps());
            return most;
        }

        /** The largest --steps of the Geni profiles read from one list of addresses; 0 when it has none. */
        int listSteps(String listKey) {
            int most = 0;
            for (Row r : rows) if (r.kind().equals(GENI) && !r.list().isEmpty() && addressKey(r.list()).equals(listKey)) most = Math.max(most, Math.max(1, r.steps()));
            return most;
        }

        /** The largest --steps of the Geni profiles the lists of links in a folder named; 0 when none. */
        public int folderSteps(String folder) { return folderRead(folder).steps(); }

        /** A new read of a folder, with the largest --steps of the Geni profiles its lists of links named. */
        public Reread folderRead(String folder) {
            int most = 0;
            for (Row r : rows) {
                if (!r.kind().equals(GENI) || r.list().isEmpty()) continue;
                Row list = row(addressKey(r.list()));
                if (list != null && list.ofFolder() && sameFolder(folder, folderOfRow(list))) most = Math.max(most, Math.max(1, r.steps()));
            }
            return new Reread(FOLDER, folder, most, "", "");
        }

        private String folderOfRow(Row r) {
            if (!r.folder().isEmpty()) return r.folder();
            String in = folderOf(r.where(), folders);
            return in == null ? parent(r.where()) : in;
        }

        /**
         * The read that brings back what came from one source. A file of a folder comes back with a new read of the folder; a page or a
         * Geni profile read from a list comes back with the list, a Geni profile reached from another with the first profile, each with the
         * largest --steps it was read with. A source the lists do not have, read before the library wrote such reads down, is read by
         * itself: a file inside a folder the library read comes back with that folder.
         */
        public Reread readOf(String locator) {
            String where = decoded(locator);
            Row row = row(addressKey(where));
            for (int hop = 0; row != null && hop < 8 && !row.ofFolder(); hop++) {
                if (!row.from().isEmpty()) {
                    Row up = row(addressKey(row.from()));
                    if (up == null) return new Reread(GENI, row.from(), steps(addressKey(row.from())), "", "");
                    row = up;
                    continue;
                }
                if (!row.list().isEmpty()) {
                    Row list = row(addressKey(row.list()));
                    if (list != null && list.ofFolder()) return folderRead(folderOfRow(list));
                    return new Reread(LIST, list == null ? row.list() : list.where(), listSteps(addressKey(row.list())), list == null ? "" : list.by(), "");
                }
                break;
            }
            if (row != null) {
                if (row.ofFolder()) return folderRead(folderOfRow(row));
                if (row.kind().equals(GENI)) return new Reread(GENI, row.where(), steps(addressKey(row.where())), "", "");
                if (row.kind().equals(LIST)) return new Reread(LIST, row.where(), listSteps(addressKey(row.where())), row.by(), "");
                return new Reread(row.kind(), row.where(), 0, row.by(), row.families());
            }
            if (isFile(where)) {
                String in = folderOf(where, folders);
                if (in != null) return folderRead(in);
                String ext = Corpus.ext(Path.of(name(where)));
                return new Reread(ext.equals("ged") ? TREE : Corpus.PICTURES.contains(ext) ? PICTURE : FILE, where, 0, "", "");
            }
            if (GeniFamily.guidOf(where) != null) return new Reread(GENI, where, 1, "", "");
            if (where.matches("(?i)https?://.+")) return new Reread(PAGE, where, 0, "", "");
            return new Reread(OTHER, where, 0, "", "");
        }
    }

    // ── facts from outside the folder ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The facts a reset takes or keeps that rest on sources a new read of the folders does not bring back: Geni profiles, web pages, files
     * elsewhere, files of the folder read on their own, and files that are no longer there. {@code sources}: each such fact → those
     * sources, as paths and addresses. {@code reads}: each read that brings some back → the facts; a file that is no longer there has
     * none. {@code only}: the facts with no source a new read of the folder brings back. {@code folders}: the folders read as a whole.
     */
    public record Outside(Map<String, List<String>> sources, Map<Reread, Set<String>> reads, Set<String> only, List<String> folders) {
        public boolean isEmpty() { return sources.isEmpty(); }

        /** The reads that bring them back: Geni first, as its sign-in comes first. */
        public List<Reread> commands() {
            List<Reread> out = new ArrayList<>();
            for (Reread r : reads.keySet()) if (r.geni()) out.add(r);
            for (Reread r : reads.keySet()) if (!r.geni()) out.add(r);
            return out;
        }
    }

    /**
     * Sorts the sources of these facts that {@code counts} (the family's own material, as a reset reads it) into the folders and the rest.
     * Only the rest is kept in the answer.
     */
    public static Outside outside(Collection<Finding> facts, Predicate<Finding.Source> counts, Index idx) {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        Map<Reread, Set<String>> reads = new LinkedHashMap<>();
        Set<String> only = new LinkedHashSet<>();
        for (Finding f : facts) {
            boolean inFolder = false, out = false;
            for (Finding.Source s : f.sources()) {
                if (!counts.test(s)) continue;
                String where = decoded(s.locator());
                if (idx.back.contains(addressKey(where))) { inFolder = true; continue; }
                out = true;
                sources.computeIfAbsent(f.id(), k -> new ArrayList<>()).add(where);
                // a file that is no longer there: no command reads it again
                if (goneFile(where)) continue;
                Reread r = idx.readOf(s.locator());
                reads.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(f.id());
            }
            if (out && !inFolder) only.add(f.id());
        }
        return new Outside(sources, reads, only, idx.folders);
    }

    /** The same for facts given by their ids, as the library holds them now; what was told is not the family's reading and does not count. */
    public static Outside outside(LibraryStore store, Collection<String> ids, Index idx) throws IOException {
        List<Finding> facts = new ArrayList<>();
        for (String id : ids) { Finding f = store.finding(id); if (f != null) facts.add(f); }
        return outside(facts, s -> FamilyReset.takenBack(s, "", Set.of()), idx);
    }

    /** The folders, as a person names them (the outermost), that these facts were read from; in the order the facts name them. */
    public static List<String> foldersOf(LibraryStore store, Collection<String> ids, List<String> folders) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            Finding f = store.finding(id);
            if (f == null) continue;
            for (Finding.Source s : f.sources()) {
                String in = folderOf(s.locator(), folders);
                if (in == null) continue;
                String top = in;
                for (String o : folders) if (sameFolder(o, in) || folderOf(in + "/x", List.of(o)) != null) { top = o; break; }
                out.add(top);
            }
        }
        return new ArrayList<>(out);
    }

    private static String facts(int n) { return n == 1 ? "1 fact" : n + " facts"; }

    static String listed(List<String> names) {
        List<String> shown = names.stream().limit(5).toList();
        if (names.size() > 5) return String.join(", ", shown) + " and " + (names.size() - 5) + " more";
        return shown.size() == 1 ? shown.get(0) : String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
    }

    /** Where a source is, for the lines of a reset: a file elsewhere, a file of the folder read on its own, a file no longer there, Geni, a web site. */
    private static String group(String src, List<String> folders) {
        if (isFile(src)) return folderOf(src, folders) == null ? "file" : goneFile(src) ? "gone" : "alone";
        return geniAddress(src) ? "geni" : src.matches("(?i)https?://.+") ? "site\t" + host(src) : "other";
    }

    /** Where these facts came from: one line for Geni, one for each web site, one for the files outside the folder, one for any other address. */
    public static List<String> breakdown(Outside o, Collection<String> ids) {
        Map<String, Set<String>> factsIn = new LinkedHashMap<>(), sourcesIn = new LinkedHashMap<>();
        for (String id : ids) for (String src : o.sources().getOrDefault(id, List.of())) {
            String group = group(src, o.folders());
            factsIn.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(id);
            sourcesIn.computeIfAbsent(group, k -> new LinkedHashSet<>()).add(group.equals("geni") ? addressKey(src) : src);
        }
        List<String> out = new ArrayList<>();
        if (factsIn.containsKey("geni")) { int p = sourcesIn.get("geni").size(); out.add(facts(factsIn.get("geni").size()) + " from " + (p == 1 ? "1 Geni profile." : p + " Geni profiles.")); }
        for (String g : factsIn.keySet()) if (g.startsWith("site\t")) {
            int p = sourcesIn.get(g).size();
            out.add(facts(factsIn.get(g).size()) + " from " + (p == 1 ? "1 web page" : p + " web pages") + " on " + g.substring(5) + ".");
        }
        if (factsIn.containsKey("file")) {
            List<String> names = sourcesIn.get("file").stream().map(FamilyReads::name).distinct().toList();
            out.add(facts(factsIn.get("file").size()) + " from " + (names.size() == 1 ? "a file" : names.size() + " files") + " outside the folder: " + listed(names) + ".");
        }
        if (factsIn.containsKey("alone")) {
            List<String> names = sourcesIn.get("alone").stream().map(FamilyReads::name).distinct().toList();
            out.add(facts(factsIn.get("alone").size()) + " from " + (names.size() == 1 ? "a file" : names.size() + " files") + " in the folder that you read on " + (names.size() == 1 ? "its own" : "their own") + ": " + listed(names) + ".");
        }
        if (factsIn.containsKey("gone")) {
            List<String> names = sourcesIn.get("gone").stream().map(FamilyReads::name).distinct().toList();
            out.add(facts(factsIn.get("gone").size()) + " from " + (names.size() == 1 ? "a file that is" : names.size() + " files that are") + " no longer in the folder: " + listed(names) + ".");
        }
        if (factsIn.containsKey("other")) out.add(facts(factsIn.get("other").size()) + " from other addresses: " + listed(new ArrayList<>(sourcesIn.get("other"))) + ".");
        return out;
    }

    /** The kinds of place these facts came from, in a few words, in the order of {@link #breakdown}: "Geni and web pages". */
    public static String kinds(Outside o, Collection<String> ids) {
        Set<String> groups = new LinkedHashSet<>();
        for (String id : ids) for (String src : o.sources().getOrDefault(id, List.of())) groups.add(group(src, o.folders()).replaceFirst("\t.*", ""));
        List<String> out = new ArrayList<>();
        if (groups.contains("geni")) out.add("Geni");
        if (groups.contains("site")) out.add("web pages");
        if (groups.contains("file")) out.add("files outside the folder");
        if (groups.contains("alone")) out.add("files in the folder that you read on their own");
        if (groups.contains("gone")) out.add("files that are no longer in the folder");
        if (groups.contains("other")) out.add("other addresses");
        return out.isEmpty() ? "other places" : and(out);
    }

    /** The commands that read these again, each with what it does: the Geni sign-in first when a Geni profile is among them. */
    public static String commands(List<Reread> reads, String indent) {
        StringBuilder b = new StringBuilder();
        if (reads.stream().anyMatch(Reread::geni) || reads.stream().anyMatch(r -> r.steps() > 0))
            b.append(indent).append("researchzosho records login geni\n").append(indent).append("    signs you in to Geni. Geni shows nothing without a sign-in, and the sign-in lasts a day. Give this command first.\n");
        int shown = 0;
        for (Reread r : reads) {
            if (shown++ == 20) { b.append(indent).append("… and ").append(reads.size() - 20).append(" more. To see the facts from one source, give researchzosho genealogy source followed by its file name or address.\n"); break; }
            b.append(indent).append(r.command()).append('\n').append(indent).append("    ").append(r.says()).append('\n');
        }
        return b.toString();
    }

    /** "your folder /home/me/family", "your folders /a and /b", or "" when the library has read no folder as a whole. */
    public static String folderSaid(Collection<Row> rows) {
        List<String> top = topFolders(rows);
        if (top.isEmpty()) return "";
        return (top.size() == 1 ? "your folder " : "your folders ") + and(top);
    }

    /**
     * What a reset of everything says before it asks: how many of the facts it takes out came from outside the folder, where they came
     * from, and the commands that read those sources again. A page or a Geni profile that a list of links in the folder names comes back
     * with the folder, and is said so. "" when every source it takes out is a file of a folder.
     */
    public static String notBroughtBack(Outside o, Collection<Row> rows) {
        if (o.isEmpty()) return "";
        String folder = folderSaid(rows);
        // the facts whose every read is a read of a folder: the pages and Geni profiles of a list of links in it
        Set<String> byFolder = new LinkedHashSet<>(), notBack = new LinkedHashSet<>(), gone = new LinkedHashSet<>();
        for (String id : o.only()) {
            List<Reread> of = o.reads().entrySet().stream().filter(e -> e.getValue().contains(id)).map(Map.Entry::getKey).toList();
            if (!of.isEmpty() && of.stream().allMatch(Reread::folder)) byFolder.add(id); else notBack.add(id);
            if (of.isEmpty()) gone.add(id);
        }
        StringBuilder b = new StringBuilder("\n");
        if (!byFolder.isEmpty()) {
            b.append("From the lists of web addresses in ").append(folder.isEmpty() ? "your folder" : folder).append(":\n");
            for (String line : breakdown(o, byFolder)) b.append("  ").append(line).append('\n');
            b.append("  Reading the folder again reads those lists again, and with them these pages and Geni profiles.")
             .append(notBack.isEmpty() ? " To read them again after the reset, give these commands:\n\n" : "\n");
        }
        if (!notBack.isEmpty()) {
            int n = notBack.size();
            String them = n == 1 ? "it" : "them";
            boolean inside = notBack.stream().flatMap(id -> o.sources().getOrDefault(id, List.of()).stream()).anyMatch(src -> isFile(src) && folderOf(src, o.folders()) != null);
            b.append(folder.isEmpty() ? "From other places than a folder:\n" : inside ? "What reading " + folder + " again does not read:\n" : "From outside " + folder + ":\n");
            List<String> lines = breakdown(o, notBack);
            if (lines.size() == 1) b.append("  ").append(lines.get(0)).append('\n');
            else {
                b.append("  ").append(n).append(" of these facts came from ").append(kinds(o, notBack)).append(":\n");
                for (String line : lines) b.append("    ").append(line).append('\n');
            }
            if (!gone.isEmpty()) b.append("  ").append(gone.size() == 1 ? (n == 1 ? "It came from a file" : "1 of them came from a file") + " that is no longer there, so no command reads it again. The copy the reset saves keeps it.\n"
                    : gone.size() + " of them came from files that are no longer there, so no command reads them again. The copy the reset saves keeps them.\n");
            int rest = n - gone.size();
            if (rest > 0) b.append("  ").append(folder.isEmpty() ? "" : "Reading the folder again will not bring " + them + " back. ")
                    .append("To get ").append(gone.isEmpty() ? them : rest == 1 ? "the other one" : "the others").append(" back after the reset, give these commands. Each one reads one source again:\n\n");
            else if (!o.commands().isEmpty()) b.append("\n");
        } else if (byFolder.isEmpty()) {
            int n = o.sources().size();
            b.append("Also from other places:\n");
            b.append("  Every fact taken out is also in ").append(folder.isEmpty() ? "a folder the library read" : folder).append(", so reading it again brings the facts back.\n");
            b.append("  But ").append(n).append(" of them also came").append(" from other places, and reading the folder will not add those back as sources:\n");
            for (String line : breakdown(o, o.sources().keySet())) b.append("    ").append(line).append('\n');
            b.append("  To add them back after the reset, give these commands. Each one reads one source again:\n\n");
        }
        b.append(commands(o.commands(), "    "));
        return b.toString();
    }

    // ── a name the library no longer has ────────────────────────────────────────────────────────────────────────────────

    /** Two ways of writing one name: the same apart from spaces between Chinese characters, accents, capitals, the old form of a character, or the order of the words. */
    static boolean sameName(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String x = FamilyNames.written(a), y = FamilyNames.written(b);
        if (!x.isEmpty() && x.equals(y)) return true;
        if (FamilyQuestions.plain(KanjiForms.modern(a)).equals(FamilyQuestions.plain(KanjiForms.modern(b)))) return true;
        Set<String> ka = new LinkedHashSet<>(FamilyNames.keys(a));
        ka.retainAll(FamilyNames.keys(b));
        return !ka.isEmpty();
    }

    /** Each person's other names, as a copy of the list of names holds them, by the name. */
    private static Map<String, List<String>> otherNames(Path nodes) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(nodes)) return out;
            for (Vocabulary.Term t : Vocabulary.read(nodes).terms().values()) {
                String label = t.description().contains(":") ? t.description().substring(t.description().indexOf(':') + 1).strip() : t.slug();
                if (!t.also().isEmpty()) out.put(Vocabulary.norm(label), t.also());
            }
        } catch (IOException | RuntimeException unreadable) { }
        return out;
    }

    /** The names a fact is about: its subject, and its object when the relation ties two people. */
    private static List<String> sides(Finding f) {
        List<String> out = new ArrayList<>();
        if (f.triple() == null) return out;
        out.add(f.triple().subject());
        if (FamilyAccount.personToPerson(f.triple().predicate()) || FamilyAccount.associate(f.triple().predicate())) out.add(f.triple().object());
        out.removeIf(s -> s == null || s.isBlank() || FamilyQuestions.placeholder(s));
        return out;
    }

    /** Whether a name claim gives the person this other name: its object, "name: 遠藤健二", is the name typed. */
    private static boolean namesIt(Finding f, String typed) {
        return f.triple() != null && f.triple().predicate().equals(FamilyNameHistory.PREDICATE) && sameName(typed, FamilyNameHistory.bare(f.triple().object()));
    }

    /**
     * The name among these facts that the typed name is: a person a fact is about, one of their other names in the copy of the list of
     * names, or a name a name claim gives them. Null when none.
     */
    private static String label(List<Finding> facts, String typed, Map<String, List<String>> also) {
        for (Finding f : facts) for (String side : sides(f)) {
            if (sameName(typed, side) || also.getOrDefault(Vocabulary.norm(side), List.of()).stream().anyMatch(a -> sameName(typed, a))) return side;
        }
        for (Finding f : facts) if (namesIt(f, typed) && !FamilyQuestions.placeholder(f.triple().subject())) return f.triple().subject();
        return null;
    }

    /** Where one read's facts came from, for a person: "the Geni profile https://…", "the file notes.txt in your folder /home/me/family". */
    static String cameFrom(Reread r, Collection<String> items) {
        List<String> names = items.stream().map(FamilyReads::name).distinct().toList();
        return switch (r.kind()) {
            case GENI -> "the Geni profile " + r.where() + (r.steps() > 1 ? " and the relatives' profiles read from it" : "");
            case PAGE -> "the web page " + r.where();
            case LIST -> "the web pages in your list of addresses " + r.where();
            case FOLDER -> names.isEmpty() ? "your folder " + r.where() : (names.size() == 1 ? "the file " : "the files ") + listed(names) + " in your folder " + r.where();
            case TREE -> "the family-tree file " + r.where();
            case PICTURE -> "the picture " + r.where();
            case OTHER -> "the address " + r.where();
            default -> "the file " + r.where();
        };
    }

    /** "a", "a and b", "a, b and c". */
    public static String and(List<String> parts) {
        return parts.size() == 1 ? parts.get(0) : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** Whether the list of reads now has a line for this file: a folder read skips it while it has not changed. */
    private static boolean readNow(List<Row> now, String where) {
        String k = addressKey(where);
        for (Row r : now) if (addressKey(r.where()).equals(k)) return true;
        return false;
    }

    /**
     * The commands that bring back what came from these reads, each in a sentence with what it does: the Geni sign-in first. A folder whose
     * files were all read again since is read once more for those files alone.
     */
    private static String bringBack(Map<Reread, Set<String>> items, List<Row> now) {
        List<String> said = new ArrayList<>();
        List<Reread> reads = new ArrayList<>();
        for (Reread r : items.keySet()) if (r.geni()) reads.add(r);
        for (Reread r : items.keySet()) if (!r.geni()) reads.add(r);
        if (reads.stream().anyMatch(r -> r.geni() || r.steps() > 0)) said.add("researchzosho records login geni, which signs you in to Geni for a day");
        for (Reread r : reads) {
            if (r.folder()) {
                List<String> again = items.get(r).stream().filter(i -> readNow(now, i)).toList();
                if (again.size() < items.get(r).size()) { said.add(r.command() + ", which reads the files in that folder that the library has not read since the reset"); continue; }
                for (String i : again) said.add(r.command() + " --again --only \"" + name(i) + "\", which reads " + name(i) + " in that folder once more");
                continue;
            }
            said.add(r.command() + ", which " + r.says().replaceFirst("\\. .*$", "").replaceFirst("\\.$", ""));
        }
        return String.join(", then ", said);
    }

    /**
     * What the library can say of a name that is nobody in the family now, from every copy a reset kept (the newest first, and the older
     * ones too) and from the library as it is: where the facts about that name came from, and the commands that bring them back. A name
     * that was another name of somebody who is in the library now is said to be that. "" when neither the copies nor the library hold it.
     */
    public static String gone(LibraryStore store, String typed) throws IOException {
        if (typed == null || typed.isBlank()) return "";
        List<Row> now = rows(store);
        Index idx = Index.of(store);
        List<String> said = new ArrayList<>();
        // the library as it is: facts about the name that it leaves out of the family because you retired them
        List<Finding> current = store.scanFindings().findings();
        List<Finding> retired = current.stream().filter(f -> f.state() == Finding.State.retired).toList();
        String setAside = label(retired, typed, Map.of());
        if (setAside != null) {
            List<Finding> about = retired.stream().filter(f -> sides(f).stream().anyMatch(s -> Vocabulary.norm(s).equals(Vocabulary.norm(setAside)))).toList();
            Map<Reread, Set<String>> items = itemsOf(about, idx);
            List<String> codes = about.stream().map(f -> f.id().replaceFirst("^(F-\\d+).*", "$1")).distinct().toList();
            boolean one = about.size() == 1;
            said.add("You retired " + (one ? "the 1 fact" : "the " + about.size() + " facts") + " your library has about " + setAside + " (" + listed(codes) + "), so the library leaves " + setAside + " out of your family."
                    + (items.isEmpty() ? "" : " " + (one ? "It came" : "They came") + " from " + and(items.entrySet().stream().map(e -> cameFrom(e.getKey(), e.getValue())).toList()) + ".")
                    + " Reading that source again will not bring back a fact you retired. " + (one ? "If it is right after all, give researchzosho accept " + codes.get(0) + "."
                            : "If one of them is right after all, give researchzosho accept with that fact's code: " + and(about.stream().limit(12).map(f -> "researchzosho accept " + FamilyMentions.code(f.id()) + " for \"" + f.title().strip().replaceFirst("[.。]$", "") + "\"").distinct().toList()) + ".")
                    + " The library then uses it again.");
        }
        // every copy a reset kept, the newest first: the facts about the name in each copy that holds it, less those the library holds now
        Set<String> held = new LinkedHashSet<>();
        for (Finding f : current) if (f.triple() != null) held.add(factKey(f));
        String name = null;
        List<String> days = new ArrayList<>();
        List<Finding> about = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String otherName = null;
        for (Path backup : backups(store)) {
            List<Finding> copy = copies(backup);
            Map<String, List<String>> also = otherNames(backup.resolve("nodes.md"));
            String here = name != null ? name : label(copy, typed, also);
            if (here == null) continue;
            final String person = here;
            List<Finding> of = copy.stream().filter(f -> !held.contains(factKey(f)) && sides(f).stream().anyMatch(s -> Vocabulary.norm(s).equals(Vocabulary.norm(person)))).toList();
            if (of.isEmpty()) continue;
            if (name == null) {
                name = person;
                // the typed name is another name of this person: the claims that gave it say where the name came from
                if (!sameName(typed, person)) otherName = typed;
            }
            if (!days.contains(day(backup))) days.add(day(backup));
            for (Finding f : of) if (seen.add(f.id() + "\t" + f.sources().stream().map(Finding.Source::locator).toList())) about.add(f);
        }
        if (name != null) {
            FamilyQuestions.Found there = FamilyQuestions.find(store, name);
            String when = days.size() == 1 ? "the reset on " + days.get(0) : "the resets on " + and(days.reversed());
            if (otherName != null && there.found()) {
                // somebody who is in the library now, whose other name the reset took
                final String typedName = otherName;
                List<Finding> naming = about.stream().filter(f -> namesIt(f, typedName)).toList();
                Map<Reread, Set<String>> items = itemsOf(naming.isEmpty() ? about : naming, idx);
                said.add("\"" + typed + "\" was another name of " + there.person() + " until " + when + ". " + there.person() + " is still in your library, but without that name."
                        + (items.isEmpty() ? "" : " The name came from " + and(items.entrySet().stream().map(e -> cameFrom(e.getKey(), e.getValue())).toList()) + ". To get it back, give " + bringBack(items, now) + "."));
            } else if (!there.found()) {
                Map<Reread, Set<String>> items = itemsOf(about, idx);
                List<String> moved = movedFiles(about);
                boolean readSince = !items.isEmpty() && items.values().stream().flatMap(Set::stream).allMatch(i -> isFile(i) && readNow(now, i));
                List<String> came = new ArrayList<>(items.entrySet().stream().map(e -> cameFrom(e.getKey(), e.getValue())).toList());
                for (String m : moved) came.add("the file " + m + ", which is no longer there");
                said.add(name + " was in your library until " + when + "." + (came.isEmpty() ? "" : " The facts about " + name + " came from " + and(came) + ".")
                        + (items.isEmpty() ? "" : readSince ? " " + readAgainSince(store, name, items) : " To get them back, give " + bringBack(items, now) + ".")
                        + (moved.isEmpty() ? "" : " To get back what " + (moved.size() == 1 ? "that file says" : "those files say") + ", give researchzosho genealogy read followed by the place where "
                                + (moved.size() == 1 ? "the file is" : "each file is") + " now, in quotation marks."));
            }
        }
        return String.join(" ", said);
    }

    /**
     * For a name whose files were all read again since the reset: that reading them once more brings nothing back, what the files now
     * write for the name ("森田健二's parent (written only as Endo)") and who that is in the library, and the command that lists every fact a
     * file gives now.
     */
    private static String readAgainSince(LibraryStore store, String name, Map<Reread, Set<String>> items) throws IOException {
        Set<String> files = new LinkedHashSet<>();
        for (Set<String> i : items.values()) files.addAll(i);
        Set<String> keys = new LinkedHashSet<>();
        for (String f : files) keys.add(addressKey(f));
        List<String> names = files.stream().map(FamilyReads::name).distinct().toList();
        boolean one = names.size() == 1;
        StringBuilder b = new StringBuilder(one ? "That file was" : "Those files were").append(" read again after the reset, so reading ").append(one ? "it" : "them").append(" once more brings nothing back.");
        // the words about the name as the files write them now: a person written only by that family name, and whom the library has for them
        Graph g = FamilyPeople.view(store);
        Set<String> now = new LinkedHashSet<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.triple() == null || f.sources().stream().noneMatch(src -> keys.contains(addressKey(src.locator())))) continue;
            for (boolean subject : new boolean[]{true, false}) {
                String side = subject ? f.triple().subject() : f.triple().object();
                Matcher m = FamilyMentions.MENTION.matcher(side == null ? "" : side.strip());
                if (!m.matches() || !sameName(m.group(3), name)) continue;
                Graph.Node n = g.node(g.nodeOf(f, subject));
                String who = n == null ? side : n.label();
                now.add("\"" + side.strip() + "\"" + (Vocabulary.norm(who).equals(Vocabulary.norm(side)) ? "" : ", who is " + who + " in your library"));
            }
        }
        if (!now.isEmpty()) b.append(" ").append(one ? "It now writes " : "They now write ").append(name).append(" as ").append(and(new ArrayList<>(now))).append(".");
        b.append(" To see every fact ").append(one ? "that file gives" : "one of them gives").append(" now, give researchzosho genealogy source \"").append(names.get(0)).append("\".");
        return b.toString();
    }

    /** One fact however many claims give it: its subject, relation and object as the names are compared. */
    private static String factKey(Finding f) { return Vocabulary.norm(f.triple().subject()) + "\t" + f.triple().predicate() + "\t" + Vocabulary.norm(f.triple().object()); }

    /** The reads these facts' sources came from, each with the files or pages of it the facts rest on. What was told is left out: it is still in the library. */
    private static Map<Reread, Set<String>> itemsOf(Collection<Finding> facts, Index idx) {
        Map<Reread, Set<String>> out = new LinkedHashMap<>();
        for (Finding f : facts) for (Finding.Source s : f.sources()) {
            // a file that is no longer there: no read of it or of its folder brings it back ({@link #movedFiles})
            if (!FamilyReset.takenBack(s, "", Set.of()) || goneFile(s.locator())) continue;
            out.computeIfAbsent(idx.readOf(s.locator()), k -> new LinkedHashSet<>()).add(decoded(s.locator()));
        }
        return out;
    }

    /** The files these facts were read from that are no longer where they were read, by their whole paths. */
    private static List<String> movedFiles(Collection<Finding> facts) {
        Set<String> out = new LinkedHashSet<>();
        for (Finding f : facts) for (Finding.Source s : f.sources()) if (FamilyReset.takenBack(s, "", Set.of()) && goneFile(s.locator())) out.add(decoded(s.locator()));
        return new ArrayList<>(out);
    }
}
