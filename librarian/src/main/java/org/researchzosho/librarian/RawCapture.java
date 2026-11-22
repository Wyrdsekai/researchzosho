package org.researchzosho.librarian;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.NoSuchAlgorithmException;
import org.researchzosho.tools.DocText;
import org.researchzosho.tools.Fetch;
/**
 * The raw tier — the web rots, and a finding whose source is a dead link is unverifiable, so
 * every successfully fetched page is preserved in full at CAPTURE time with its provenance
 * stamped in the same act (the FAIR/ELN rule: provenance is captured, never reconstructed).
 * Field consensus: PaperQA2's paper dir, the LSR corpora, LLM-Wiki's raw/ — everyone keeps the
 * documents.
 *
 * <p>Raw is IMMUTABLE: the first capture of a URL wins; a re-fetch of the same URL is a no-op
 * (freshness is the serials crew's job, and a refresh will be a NEW dated capture, not an
 * overwrite). Filenames are {@code <date>-<urlhash>.md} — content-addressed enough to dedupe,
 * dated enough for a human browsing the shelf.
 *
 * <p>Best-effort by contract: capture must never break the fetch it observes, and it does
 * nothing at all unless the person has a library ({@code librarian init} — the consent rule).
 */
public final class RawCapture {

    /** Bytes of readable text worth keeping per page — beyond this it's a dump, not a source. */
    /** The most of a document's text a capture keeps; the page check reads the same ({@code PageCheck}). */
    public static final int MAX_TEXT = 400_000;

    /** Linked captures: documents read where they are, never copied. The capture holds the head only;
     *  {@link #read} extracts the text from the file on demand and keeps the last few in memory. */
    private static final int LINKED_CACHE = Integer.getInteger("researchzosho.linked.cache", 64);
    private static final Map<String, String[]> LINKED = new LinkedHashMap<>(LINKED_CACHE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> e) { return size() > LINKED_CACHE; }
    };

    private RawCapture() { }

    /** Capture one fetched/added document. Returns the raw/ path, or null (absent library, dupe, error). */
    public static Path capture(String locator, String text, String title) {
        return capture(locator, text, title, "researchzosho-web-fetch");
    }

    public static Path capture(String locator, String text, String title, String by) {
        if (!Acquisitions.libraryExists()) return null;
        return capture(LibraryStore.open(), locator, text, title, by, "");
    }

    /** The collection a capture belongs to ("" when none). */
    public static String collectionOf(Path raw) {
        try (var lines = Files.lines(raw, StandardCharsets.UTF_8)) {
            return lines.limit(8).filter(l -> l.startsWith("collection: ")).map(l -> l.substring(12).strip()).findFirst().orElse("");
        } catch (Exception e) { return ""; }
    }

    /** Capture into an explicit store, optionally under a collection (the corpus and the person's supplied documents). */
    public static Path capture(LibraryStore store, String locator, String text, String title, String by, String collection) {
        return capture(store, locator, text, title, by, collection, "");
    }

    /** With the page's own publication date (YYYY-MM-DD, or "" when the page does not say): kept in the capture's head. */
    public static Path capture(LibraryStore store, String locator, String text, String title, String by, String collection, String published) {
        return capture(store, locator, text, title, by, collection, published, Map.of());
    }

    /** The same, with more lines for the head ({@code more}: name → value; a translation's {@code translation_of} and {@code language}). */
    public static Path capture(LibraryStore store, String locator, String text, String title, String by, String collection, String published, Map<String, String> more) {
        try {
            if (locator == null || text == null || text.isBlank()) return null;
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(locator.getBytes(StandardCharsets.UTF_8)), 0, 6);
            Path p = store.rawDir().resolve(LocalDate.now() + "-" + hash + ".md");
            // immutable tier: same locator captured today already = done. (A different day writes
            // a new dated file — a feature: the serials crew diffs captures, not memories.)
            // EXCEPT when what is there is garbage: immutability protects sources, not the bytes
            // of a PDF mis-captured as text. Caught 2026-09-01 — `librarian add` reported a clean
            // conversion while the shelf kept 611KB of %PDF binary from an earlier fetch.
            // and a LINKED capture gives way: "keep it after all" writes the text over the pointer.
            if (Files.exists(p) && linkedFile(p) == null && !looksBinary(read(p)[2])) return p;
            String body = text.length() > MAX_TEXT
                    ? text.substring(0, MAX_TEXT) + "\n\n[truncated at capture]" : text;
            String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
            Files.createDirectories(p.getParent());
            // an older capture of the same locator that is unreadable binary gives way to this readable one: a database or a
            // PDF mis-saved as text by an earlier version would otherwise stay what the library holds for that file (2026-09-17)
            if (!looksBinary(body)) {
                try (var older = Files.list(p.getParent())) {
                    for (Path o : older.filter(x -> x.getFileName().toString().endsWith("-" + hash + ".md") && !x.equals(p)).toList()) {
                        try { if (linkedFile(o) == null && looksBinary(read(o)[2])) Files.delete(o); } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) { }
            }
            StringBuilder extra = new StringBuilder();
            for (var e : more.entrySet()) if (e.getKey() != null && e.getValue() != null && e.getKey().matches("[a-z_]+") && !e.getValue().isBlank()) extra.append('\n').append(e.getKey()).append(": ").append(e.getValue().replaceAll("[\\r\\n]+", " ").strip());
            // the head is read line by line, and its first line says where the copy came from: nothing in a locator, a writer's name or a
            // collection's starts a line of its own
            Files.writeString(p, "---\nurl: " + oneLine(locator) + "\ntitle: " + t + "\nfetched_at: "
                    + Instant.now() + "\nfetched_by: " + oneLine(by) + (collection == null || collection.isBlank() ? "" : "\ncollection: " + oneLine(collection)) + (published == null || published.isBlank() ? "" : "\npublished: " + oneLine(published)) + extra + "\n---\n" + body, StandardCharsets.UTF_8);
            // raw joins the catalog: "do we hold anything on X?" must see the documents, not
            // just the claims extracted from them.
            try {
                new LibrarianIndex(store).upsertRaw(p.getFileName().toString(), t, locator, body, collection == null ? "" : collection);
            } catch (Exception ignored) {
                // the index is rebuildable; the capture is what must not be lost
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read, do not copy: a capture whose body stays in the file it points at. The head records the path,
     * the file's hash and size so a rescan can tell a changed file from an untouched one; the text is
     * extracted once here for the index and then again whenever something reads the capture. Returns
     * the raw/ path, or null on error.
     */
    public static Path link(LibraryStore store, Path file, String text, String title, String by, String collection) { return link(store, file, text, title, by, collection, Map.of()); }

    /** The same, with more lines for the head ({@link #eyeHead}: a picture or a scan, whose text the model that sees gives each time it is read). */
    public static Path link(LibraryStore store, Path file, String text, String title, String by, String collection, Map<String, String> more) {
        try {
            Path abs = file.toAbsolutePath().normalize();
            String locator = "file://" + abs;
            if (text == null || text.isBlank()) return null;
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(locator.getBytes(StandardCharsets.UTF_8)), 0, 6);
            Path p = store.rawDir().resolve(LocalDate.now() + "-" + hash + ".md");
            String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
            String sha = fileHash(abs);
            long bytes = Files.size(abs);
            Files.createDirectories(p.getParent());
            StringBuilder extra = new StringBuilder();
            for (var e : more.entrySet()) if (e.getKey() != null && e.getValue() != null && e.getKey().matches("[a-z_]+") && !e.getValue().isBlank()) extra.append('\n').append(e.getKey()).append(": ").append(oneLine(e.getValue()));
            Files.writeString(p, "---\nurl: " + oneLine(locator) + "\ntitle: " + t + "\nfetched_at: " + Instant.now() + "\nfetched_by: " + oneLine(by)
                    + (collection == null || collection.isBlank() ? "" : "\ncollection: " + oneLine(collection))
                    + "\nlinked: " + abs + "\nsha256: " + sha + "\nbytes: " + bytes + extra + "\n---\n", StandardCharsets.UTF_8);
            synchronized (LINKED) { LINKED.remove(abs.toString()); }
            try {
                new LibrarianIndex(store).upsertRaw(p.getFileName().toString(), t, locator,
                        text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text, collection == null ? "" : collection);
            } catch (Exception ignored) { }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** The file a linked capture reads, or null for an ordinary capture. */
    public static Path linkedFile(Path raw) {
        String v = headField(raw, "linked: ");
        return v.isEmpty() ? null : Path.of(v);
    }

    /** The recorded hash of a linked capture's file ("" for an ordinary capture). */
    public static String linkedHash(Path raw) { return headField(raw, "sha256: "); }

    /** SHA-256 of a file's bytes, hex. */
    public static String fileHash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** One line of a capture's head by its name ({@code image}, {@code page}, {@code verdict}…), or "" when the head has none. */
    public static String field(Path raw, String name) { return headField(raw, name + ": "); }

    private static String headField(Path raw, String key) {
        // the head, up to its closing ---: a kept picture's head runs past twelve lines (kind, image, page, source, found, size, hash, verdict)
        try (var lines = Files.lines(raw, StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            for (int n = 0; it.hasNext() && n < 64; n++) {
                String l = it.next();
                if (n > 0 && l.equals("---")) break;
                if (l.startsWith(key)) return l.substring(key.length()).strip();
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    /** True for the text a reader gets when a linked file could not be read. */
    public static boolean isUnreachable(String body) { return body != null && body.startsWith("[not reachable now: "); }

    /** What a reader sees when a linked file cannot be read right now: the path, so the person knows what to mount. */
    public static String unreachable(Path file, String why) {
        return "[not reachable now: " + file + " cannot be read" + (why == null || why.isBlank() ? "" : " (" + why + ")") + "; mount the drive or put the file back, and the library reads it again]";
    }

    /** A capture that is bytes, not text: a PDF/zip header, or a body dense with control and
     *  replacement characters. Mechanical, so the immutability exception cannot be argued with. */
    /** Delete the saved copies of a locator that are unreadable binary. Returns how many went. A file read in again through another door (a database read as a list) must not leave its old unreadable copy for a run to find. */
    public static int dropUnreadable(LibraryStore store, String locator) {
        int gone = 0;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(locator.getBytes(StandardCharsets.UTF_8)), 0, 6);
            if (!Files.isDirectory(store.rawDir())) return 0;
            try (var files = Files.list(store.rawDir())) {
                for (Path o : files.filter(x -> x.getFileName().toString().endsWith("-" + hash + ".md")).toList()) {
                    try { if (linkedFile(o) == null && looksBinary(read(o)[2])) { Files.delete(o); gone++; } } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return gone;
    }

    public static boolean looksBinary(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.startsWith("%PDF") || head.startsWith("PK") || head.startsWith("SQLite format 3")) return true;
        int n = Math.min(head.length(), 4000);
        if (n == 0) return false;
        int bad = 0;
        for (int i = 0; i < n; i++) {
            char c = head.charAt(i);
            if (c == '�' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) bad++;
        }
        return bad * 100 / n > 5;
    }

    /** The captured document for a locator: raw/<file>, a bare file name, or the URL it was fetched from (newest capture wins). */
    /** The web addresses a library holds saved copies of, by the form addresses are compared in, each as its copy records it; read once, then only the new copies. */
    private static final class Held { final Map<String, String> byKey = new ConcurrentHashMap<>(); final Set<String> read = new HashSet<>(); }
    private static final Map<Path, Held> HELD = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, Held> e) { return size() > 64; }
    });

    /**
     * Every web address this library holds a saved copy of ({@link GivenAddresses#key} → the address as the copy's first line records it).
     * A copy's first line is written by {@link #capture} from the address the library itself opened (a fetch, the person's own add); a
     * line a title or a later head line could imitate is never read, only the first. What a model wrote into a report or a note is in no
     * copy's first line, so this is how an address the library recorded is told from one a model wrote down.
     */
    static Map<String, String> locators(LibraryStore store) {
        Path dir = store.rawDir().toAbsolutePath().normalize();
        Held h = HELD.computeIfAbsent(dir, d -> new Held());
        synchronized (h) {
            if (Files.isDirectory(dir)) try (var s = Files.list(dir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".md")).toList()) {
                    if (!h.read.add(p.getFileName().toString())) continue;
                    String url = recordedAddress(p);
                    if (!url.matches("(?i)^https?://\\S+")) continue;
                    String k = GivenAddresses.key(url);
                    if (k != null) h.byKey.putIfAbsent(k, url);
                }
            } catch (IOException | UncheckedIOException e) { /* what was read so far stands */ }
        }
        return h.byKey;
    }

    /** The address a saved copy records as its own: its head's first line, and no other. "" when the file does not begin as a copy does. */
    private static String recordedAddress(Path p) {
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String first = r.readLine(), second = r.readLine();
            return "---".equals(first) && second != null && second.startsWith("url: ") ? second.substring(5).strip() : "";
        } catch (IOException | UncheckedIOException e) { return ""; }
    }

    /** A value of the head on one line: a line's end in it would start a head line of its own. */
    private static String oneLine(String s) { return s == null ? "" : s.replaceAll("[\\r\\n\\u0085\\u2028\\u2029]+", " "); }

    /**
     * The head line of a saved copy whose text, or part of it, is the transcription of the model that sees: a picture's writing
     * ({@code ImageText}), the pages of a PDF that are pictures of text ({@code PdfReading}), a comic's pages. The file may be the
     * person's own or a page from outside; the words are a model's.
     */
    public static final String BY_EYE = "read_by_eye";

    /** The head line for a document as its reader gave it ({@link #BY_EYE}), or none when every word of it was in the file. */
    public static Map<String, String> eyeHead(DocText.Doc doc) {
        if (doc == null) return Map.of();
        if ("image".equals(doc.kind())) return Map.of(BY_EYE, "the picture");
        if (doc.reading() != null && !doc.reading().byEye().isEmpty()) return Map.of(BY_EYE, "pages " + PdfReading.pages(doc.reading().byEye()));
        return Map.of();
    }

    /** The endings of a picture's file name: a copy saved from one before 0.8.1 has no {@link #BY_EYE} line, and its text is the seeing model's all the same. */
    private static final Pattern PICTURE = Pattern.compile("(?i)\\.(?:jpe?g|png|gif|bmp|tiff?|webp)(?:[?#].*)?$");

    /**
     * Whether a saved copy's text is what came from outside or from the person, word for word: a page the library fetched, a file or a
     * page the person added or supplied, a file of a folder they shelved. Such text gives the addresses written in it when it is read
     * back, as it did when it was first read. A copy the library wrote from a model's words does not: a translation, a list of candidates
     * with their verdicts, what a model saw in a video; and a picture or a scanned page of the person's own, whose text in the library is
     * what the model that sees read off it (0.8.1: a seeing model that wrote an address holding a scan's words into its transcription
     * had the address opened when the scan was read back).
     */
    static boolean verbatim(Path raw) {
        String by = "", url = "";
        boolean eye = false;
        try (BufferedReader r = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
            if (!"---".equals(r.readLine())) return false;
            String l;
            for (int n = 0; n < 64 && (l = r.readLine()) != null && !l.equals("---"); n++) {
                if (n == 0 && l.startsWith("url: ")) url = l.substring(5).strip();
                else if (l.startsWith("fetched_by: ") && by.isEmpty()) by = l.substring(12).strip();
                else if (l.startsWith(BY_EYE + ": ") || l.startsWith("comic: ") || (l.startsWith("pdf_reading: ") && l.contains("read by eye by "))) eye = true;
            }
        } catch (IOException | UncheckedIOException e) { return false; }
        if (eye || PICTURE.matcher(url).find()) return false;
        return by.equals("researchzosho-web-fetch") || by.equals("researchzosho-add") || by.equals("person-supplied") || by.startsWith("corpus:");
    }

    public static Path find(LibraryStore store, String locator) throws IOException {
        if (locator == null || locator.isBlank()) return null;
        String loc = locator.strip();
        if (loc.startsWith("raw/")) loc = loc.substring(4);
        Path direct = LibraryStore.under(store.rawDir(), loc);   // a file NAME only; a URL locator goes to the scan below
        if (direct != null && Files.isRegularFile(direct)) return direct;
        if (!Files.isDirectory(store.rawDir())) return null;
        Path best = null;
        try (var s = Files.list(store.rawDir())) {
            for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().toList()) {
                String url = "";
                try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
                    url = lines.limit(8).filter(l -> l.startsWith("url: ")).map(l -> l.substring(5).strip()).findFirst().orElse("");
                } catch (UncheckedIOException ignored) { }
                if (url.equals(loc) || url.equals(loc + "/") || (url + "/").equals(loc) || (Hosted.any() && Hosted.shown(url).equals(loc))   // in a hosted library a file is named as its people are shown it, without the machine's path
                        || (loc.startsWith("http") && Fetch.canonical(url).equals(Fetch.canonical(loc)))) best = p;   // sorted by name = by date; last wins
            }
        }
        return best;
    }

    /** The page's own publication date from a capture's head (YYYY-MM-DD), or "". */
    public static String published(Path raw) {
        try (var lines = Files.lines(raw, StandardCharsets.UTF_8)) {
            return lines.limit(10).filter(l -> l.startsWith("published: ")).map(l -> l.substring(11).strip()).findFirst().orElse("");
        } catch (Exception e) { return ""; }
    }

    /** Parsed head of a raw file: [locator, title, body]. */
    public static String[] read(Path raw) throws IOException {
        String s = Files.readString(raw, StandardCharsets.UTF_8);
        int end = s.indexOf("\n---\n", 4);
        if (!s.startsWith("---\n") || end < 0) return new String[]{"", "", s};
        String head = s.substring(4, end);
        String locator = "", title = "", linked = "";
        for (String line : head.split("\n")) {
            if (line.startsWith("url: ")) locator = line.substring(5).strip();
            else if (line.startsWith("title: ")) title = line.substring(7).strip();
            else if (line.startsWith("linked: ")) linked = line.substring(8).strip();
        }
        if (!linked.isEmpty()) return new String[]{locator, title, linkedText(Path.of(linked))};
        return new String[]{locator, title, s.substring(end + 5)};
    }

    /** The text of a linked file, extracted on demand; cached by path and modification time. */
    static String linkedText(Path file) {
        String key = file.toString();
        long mtime;
        try { mtime = Files.getLastModifiedTime(file).toMillis(); }
        catch (IOException e) { return unreachable(file, Files.exists(file) ? e.getMessage() : "the path is missing"); }
        synchronized (LINKED) {
            String[] hit = LINKED.get(key);
            if (hit != null && hit[0].equals(Long.toString(mtime))) return hit[1];
        }
        String text;
        try {
            var doc = DocText.convert(Files.readAllBytes(file), file.getFileName().toString());
            text = doc.text().isBlank() ? unreachable(file, "no text could be read (" + doc.kind() + ")")
                    : doc.text().length() > MAX_TEXT ? doc.text().substring(0, MAX_TEXT) + "\n\n[truncated at read]" : doc.text();
        } catch (Exception e) {
            return unreachable(file, e.getMessage());
        }
        synchronized (LINKED) { LINKED.put(key, new String[]{Long.toString(mtime), text}); }
        return text;
    }
}
