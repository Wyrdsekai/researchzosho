package org.researchzosho.librarian;

import org.researchzosho.tools.DocText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A person's own documents as a corpus: a folder of PDFs, office files, EPUBs, markdown and text,
 * shelved as raw captures under a named COLLECTION so a question can be put to them first — or to
 * them only. Every file is a {@code file://} capture with the personal tier's trust, its collection in
 * the frontmatter and in the index, so the shelf search can be scoped to it. Registered folders are
 * rescanned by the nightly crews; a file already captured with the same text is skipped.
 *
 * <p>Two ways to shelve: KEEP copies the extracted text into raw/ (the file itself is never copied);
 * LINK keeps only a pointer, the file's hash and size, and reads the text from the file whenever
 * something needs it — for a drive of documents larger than the library's disk, or material that must
 * not be duplicated. {@link #survey} counts a folder first so the person can choose with the numbers.
 */
public final class Corpus {

    static final java.util.Set<String> EXTENSIONS = java.util.Set.of("pdf", "docx", "pptx", "odt", "epub", "md", "txt", "html", "htm", "rst", "tex");

    /** {@code changed}: linked files whose bytes differ from the last scan (re-indexed; the claims resting on them are marked for review). */
    public record Outcome(int seen, int added, int unchanged, int skipped, int changed, List<String> problems) {
        public Outcome(int seen, int added, int unchanged, int skipped, List<String> problems) { this(seen, added, unchanged, skipped, 0, problems); }
        public String line() {
            return added + " added, " + unchanged + " unchanged" + (changed > 0 ? ", " + changed + " changed" : "") + ", " + skipped + " skipped of " + seen + " document(s)";
        }
    }

    /** The dry pass over a folder: what is there and what shelving it would take, before choosing keep or link. */
    public record Survey(int files, long bytes, long textBytes, Map<String, Integer> byType, long freeBytes, int unreadable) {
        /** One sentence with the numbers a person needs to choose. */
        public String line() {
            StringBuilder sb = new StringBuilder(files + " document(s), " + human(bytes));
            if (!byType.isEmpty()) {
                sb.append(" (");
                boolean first = true;
                for (Map.Entry<String, Integer> e : byType.entrySet()) { if (!first) sb.append(", "); first = false; sb.append(e.getValue()).append(' ').append(e.getKey()); }
                sb.append(')');
            }
            sb.append("; keeping the text would take about ").append(human(textBytes)).append(", the library's disk has ").append(human(freeBytes)).append(" free");
            if (unreadable > 0) sb.append("; ").append(unreadable).append(" of the sampled files gave no text");
            return sb.toString();
        }
    }

    static String human(long b) {
        if (b < 1_000) return b + " B";
        if (b < 1_000_000) return String.format(Locale.ROOT, "%.0f KB", b / 1e3);
        if (b < 1_000_000_000) return String.format(Locale.ROOT, "%.1f MB", b / 1e6);
        return String.format(Locale.ROOT, "%.1f GB", b / 1e9);
    }

    private Corpus() { }

    /** The documents under a folder, sorted, by extension. */
    static List<Path> documents(Path dir, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException(dir + " is not a directory");
        try (Stream<Path> s = recursive ? Files.walk(dir) : Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> EXTENSIONS.contains(ext(p))).sorted().toList();
        }
    }

    /** Files of sampled per type to measure how much text a document of that kind yields. */
    static final int SURVEY_SAMPLE = 6;

    /**
     * Count a folder without shelving anything: files by type, their size, the text they would leave on
     * the library's disk if kept (measured on a few files of each type, a rule of thumb for the rest), and
     * the free space on that disk.
     */
    public static Survey survey(LibraryStore store, Path dir, boolean recursive) throws IOException {
        List<Path> files = documents(dir, recursive);
        Map<String, Integer> byType = new java.util.TreeMap<>();
        Map<String, long[]> sampled = new java.util.HashMap<>();   // ext → [bytes, text bytes, files sampled]
        long bytes = 0;
        int unreadable = 0;
        for (Path f : files) {
            String e = ext(f);
            byType.merge(e, 1, Integer::sum);
            long size = Files.size(f);
            bytes += size;
            long[] sm = sampled.computeIfAbsent(e, k -> new long[3]);
            if (sm[2] < SURVEY_SAMPLE && size <= 60_000_000) {
                try {
                    DocText.Doc doc = DocText.convert(Files.readAllBytes(f), f.getFileName().toString());
                    long text = Math.min(doc.text().getBytes(StandardCharsets.UTF_8).length, 400_000);
                    if (doc.text().isBlank()) unreadable++;
                    sm[0] += size; sm[1] += text; sm[2]++;
                } catch (Exception ex) { unreadable++; }
            }
        }
        long textBytes = 0;
        for (Path f : files) {
            String e = ext(f);
            long[] sm = sampled.get(e);
            double ratio = sm != null && sm[0] > 0 ? (double) sm[1] / sm[0] : ruleOfThumb(e);
            textBytes += Math.min((long) (Files.size(f) * ratio), 400_000);
        }
        long free = 0;
        try { free = Files.getFileStore(Files.exists(store.rawDir()) ? store.rawDir() : store.root()).getUsableSpace(); } catch (Exception ignored) { }
        return new Survey(files.size(), bytes, textBytes, byType, free, unreadable);
    }

    /** Text per byte of file when nothing of that type could be sampled. */
    static double ruleOfThumb(String ext) {
        return switch (ext) {
            case "pdf" -> 0.03;
            case "docx", "pptx", "odt", "epub" -> 0.10;
            default -> 1.0;
        };
    }

    /** Shelve every document under {@code dir} into {@code collection}, keeping the text. Idempotent. */
    public static Outcome addFolder(LibraryStore store, Path dir, String collection, boolean recursive) throws IOException {
        return addFolder(store, dir, collection, recursive, false);
    }

    /** Shelve every document under {@code dir}; {@code link} reads the files in place instead of keeping their text. Idempotent. */
    public static Outcome addFolder(LibraryStore store, Path dir, String collection, boolean recursive, boolean link) throws IOException {
        List<String> problems = new ArrayList<>();
        int seen = 0, added = 0, unchanged = 0, skipped = 0, changed = 0;
        List<Path> files = documents(dir, recursive);
        for (Path f : files) {
            seen++;
            try {
                if (Files.size(f) > 60_000_000) { skipped++; problems.add(f.getFileName() + ": larger than 60 MB, skipped"); continue; }
                String locator = "file://" + f.toAbsolutePath().normalize();
                Path have = RawCapture.find(store, locator);
                if (link) {
                    // a linked file: the hash says whether anything changed, no extraction needed
                    if (have != null && RawCapture.linkedFile(have) != null) {
                        if (RawCapture.linkedHash(have).equals(RawCapture.fileHash(f))) {
                            unchanged++;
                            // indexed while its drive was away: the index holds the title only; read it now that it is back
                            if (!new LibrarianIndex(store).hasChunks(have.getFileName().toString())) {
                                String[] r = RawCapture.read(have);
                                if (!RawCapture.isUnreachable(r[2])) new LibrarianIndex(store).upsertRaw(have.getFileName().toString(), r[1], r[0], r[2], collection);
                            }
                            continue;
                        }
                        changed++;
                    }
                }
                byte[] bytes = Files.readAllBytes(f);
                DocText.Doc doc = DocText.convert(bytes, f.getFileName().toString());
                if (doc.text().isBlank() || doc.kind().startsWith("pdf-unreadable")) { skipped++; problems.add(f.getFileName() + ": no text (" + doc.kind() + ")"); continue; }
                String title = doc.title().isBlank() ? f.getFileName().toString().replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ') : doc.title();
                Path p;
                if (link) {
                    boolean wasChanged = have != null && RawCapture.linkedFile(have) != null;
                    p = RawCapture.link(store, f, doc.text(), title, "corpus:" + collection, collection);
                    if (p != null && wasChanged) { markChanged(store, locator, RawCapture.linkedHash(p)); continue; }
                } else {
                    // a linked capture is not "unchanged" for keep: the person now wants the text on the shelves
                    if (have != null && RawCapture.linkedFile(have) == null && RawCapture.read(have)[2].strip().equals(doc.text().strip())) { unchanged++; continue; }
                    p = RawCapture.capture(store, locator, doc.text(), title, "corpus:" + collection, collection);
                }
                if (p == null) { skipped++; problems.add(f.getFileName() + ": not captured"); continue; }
                added++;
            } catch (Exception e) {
                skipped++; problems.add(f.getFileName() + ": " + e.getMessage());
            }
        }
        store.circulate("corpus", collection + ": " + added + " added, " + unchanged + " unchanged, " + changed + " changed, " + skipped + " skipped of " + seen + " from " + dir + (link ? " (linked)" : ""));
        return new Outcome(seen, added, unchanged, skipped, changed, problems);
    }

    /**
     * A linked file's bytes changed since the claims resting on it were reviewed: every finding that
     * cites it gets the new hash as its source edition, which mechanically stales its approval (the
     * review hash no longer matches), so the librarian reads it again before it stands.
     */
    static int markChanged(LibraryStore store, String locator, String hash) {
        int n = 0;
        for (Finding f : store.scanFindings().findings()) {
            boolean cites = false;
            List<Finding.Source> sources = new ArrayList<>();
            for (Finding.Source s : f.sources()) {
                if (s.locator().equals(locator) && !s.edition().equals("sha256:" + hash)) { cites = true; sources.add(new Finding.Source(s.locator(), "sha256:" + hash, s.whyItMatters())); }
                else sources.add(s);
            }
            if (!cites) continue;
            try {
                Finding g = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                        f.volatility(), f.reviewBy(), sources, f.supersedes(), f.review(), f.body(), f.triple(), f.notes());
                store.write(g);
                new LibrarianIndex(store).upsert(g);
                n++;
            } catch (Exception ignored) { }
        }
        if (n > 0) store.circulate("corpus", locator + " changed on disk; " + n + " finding(s) resting on it are marked for review");
        return n;
    }

    /** One file, kept or linked, into a collection ("" for none). Returns the raw/ path, or null when it has no text. */
    public static Path addFile(LibraryStore store, Path file, String collection, boolean link) throws IOException {
        Path f = file.toAbsolutePath().normalize();
        DocText.Doc doc = DocText.convert(Files.readAllBytes(f), f.getFileName().toString());
        if (doc.text().isBlank()) return null;
        String title = doc.title().isBlank() ? f.getFileName().toString().replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ') : doc.title();
        String by = collection == null || collection.isBlank() ? "researchzosho-add" : "corpus:" + collection;
        return link ? RawCapture.link(store, f, doc.text(), title, by, collection)
                : RawCapture.capture(store, "file://" + f, doc.text(), title, by, collection);
    }

    /** A page or document at a URL, fetched and kept (a URL cannot be linked: the web rots). Returns [raw path, title, kind]. */
    public static Object[] addUrl(LibraryStore store, String url, String collection) throws Exception {
        org.researchzosho.tools.Fetch.Result resp = org.researchzosho.tools.Fetch.get(url, java.time.Duration.ofSeconds(60));
        if (resp.status() >= 400) throw new IOException("HTTP " + resp.status() + " for " + url);
        DocText.Doc doc = DocText.convert(resp.body(), url);
        if (doc.text().isBlank()) throw new IOException("no text could be extracted from " + url + " (" + doc.kind() + ")");
        String title = doc.title();
        try {
            Citations.Meta meta = Citations.resolve(store, resp.url(), Citations.LIVE);
            if (meta != null && !meta.title().isEmpty()) title = meta.title();
        } catch (Exception ignored) { }
        String published = "";
        try { published = org.researchzosho.tools.WebFetchTool.publishedDate(new String(resp.body(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        String by = collection == null || collection.isBlank() ? "researchzosho-add" : "corpus:" + collection;
        Path p = RawCapture.capture(store, resp.url(), doc.text(), title, by, collection, published);
        return new Object[]{p, title, doc.kind()};
    }

    static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    /** A collection name from a folder name: lowercase, hyphens. */
    public static String nameFor(Path dir) {
        String n = dir.toAbsolutePath().normalize().getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        return n.isEmpty() ? "corpus" : n;
    }

    // ---- registered folders, rescanned nightly ----

    static Path registry(LibraryStore store) { return store.root().resolve("catalog").resolve("collections.md"); }

    static final String LINKED_MARK = " (linked)";

    public static Map<String, Path> registered(LibraryStore store) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Path f = registry(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String[] p = line.substring(2).split(" — ", 2);
            if (p.length == 2) out.put(p[0].strip(), Path.of(unmarked(p[1])));
        }
        return out;
    }

    /** The registered folders that are read in place rather than kept. */
    public static java.util.Set<String> linked(LibraryStore store) throws IOException {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        Path f = registry(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ") || !line.strip().endsWith(LINKED_MARK.strip())) continue;
            String[] p = line.substring(2).split(" — ", 2);
            if (p.length == 2) out.add(p[0].strip());
        }
        return out;
    }

    private static String unmarked(String folder) {
        String s = folder.strip();
        return s.endsWith(LINKED_MARK.strip()) ? s.substring(0, s.length() - LINKED_MARK.strip().length()).strip() : s;
    }

    public static void register(LibraryStore store, String name, Path dir) throws IOException { register(store, name, dir, false); }

    public static void register(LibraryStore store, String name, Path dir, boolean link) throws IOException {
        Map<String, Path> all = registered(store);
        java.util.Set<String> linked = linked(store);
        all.put(name, dir.toString().startsWith(URLS) ? dir : dir.toAbsolutePath().normalize());
        if (link) linked.add(name); else linked.remove(name);
        Files.createDirectories(registry(store).getParent());
        StringBuilder sb = new StringBuilder("# Collections — the person's own corpora, rescanned by the crews\n\nOne per line: `- <name> — <folder>`; ` (linked)` after the folder means its files are read in place, not kept.\n\n");
        for (Map.Entry<String, Path> e : all.entrySet()) sb.append("- ").append(e.getKey()).append(" — ").append(e.getValue()).append(linked.contains(e.getKey()) ? LINKED_MARK : "").append('\n');
        Files.writeString(registry(store), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static final String URLS = "urls:";

    /** Register a shelved list of urls (a bookmarks file, a reading list) so the housekeeping re-reads its pages. */
    public static void registerUrls(LibraryStore store, String name, String rawListFile) throws IOException {
        register(store, name, Path.of(URLS + rawListFile), false);
    }

    /** Re-read every url in a shelved list; a page whose text changed gets a new dated capture. */
    static String reread(LibraryStore store, String name, String rawListFile) {
        int same = 0, changed = 0, failed = 0, n = 0;
        try {
            Path list = LibraryStore.under(store.rawDir(), rawListFile);
            if (list == null || !Files.exists(list)) return name + ": the list " + rawListFile + " is gone";
            String body = RawCapture.read(list)[2];
            java.util.regex.Matcher m = Drafts.URL.matcher(body);
            java.util.Set<String> urls = new java.util.LinkedHashSet<>();
            while (m.find()) urls.add(m.group());
            for (String u : urls) {
                if (++n > Bookmarks.MAX) break;
                Path before = RawCapture.find(store, u);
                String was = before == null ? null : RawCapture.read(before)[2];
                try {
                    var resp = org.researchzosho.tools.Fetch.get(u, java.time.Duration.ofSeconds(60));
                    if (resp.status() >= 400) { failed++; continue; }
                    DocText.Doc doc = DocText.convert(resp.body(), u);
                    if (doc.text().isBlank() || org.researchzosho.tools.Fetch.wall(doc.title(), doc.text()) != null) { failed++; continue; }
                    if (was != null && was.strip().equals(doc.text().strip())) { same++; continue; }
                    Path p = RawCapture.capture(store, resp.url(), doc.text(), doc.title(), "researchzosho-" + name, name);
                    if (p != null && !p.equals(before)) { changed++; store.circulate("reread", name + ": " + u + " changed → " + p.getFileName()); } else same++;
                } catch (Exception e) { failed++; }
            }
        } catch (Exception e) { return name + ": " + e.getMessage(); }
        return name + ": " + n + " url(s) re-read, " + changed + " changed, " + same + " unchanged, " + failed + " not readable";
    }

    /** Rescan every registered folder and re-read every registered url list; the crews' step. */
    public static String rescan(LibraryStore store) throws IOException {
        Map<String, Path> all = registered(store);
        if (all.isEmpty()) return "no collections registered";
        java.util.Set<String> linked = linked(store);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> e : all.entrySet()) {
            if (e.getValue().toString().startsWith(URLS)) { sb.append(reread(store, e.getKey(), e.getValue().toString().substring(URLS.length()))).append("; "); continue; }
            if (!Files.isDirectory(e.getValue())) { sb.append(e.getKey()).append(": folder ").append(linked.contains(e.getKey()) ? "not reachable now" : "missing").append("; "); continue; }
            Outcome o = addFolder(store, e.getValue(), e.getKey(), true, linked.contains(e.getKey()));
            sb.append(e.getKey()).append(": ").append(o.added()).append(" added, ").append(o.unchanged()).append(" unchanged").append(o.changed() > 0 ? ", " + o.changed() + " changed" : "").append("; ");
        }
        return sb.toString().strip();
    }
}
