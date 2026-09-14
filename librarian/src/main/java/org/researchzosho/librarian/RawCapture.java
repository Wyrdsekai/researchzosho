package org.researchzosho.librarian;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private static final int MAX_TEXT = 400_000;

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
            Files.writeString(p, "---\nurl: " + locator + "\ntitle: " + t + "\nfetched_at: "
                    + Instant.now() + "\nfetched_by: " + by + (collection == null || collection.isBlank() ? "" : "\ncollection: " + collection) + (published == null || published.isBlank() ? "" : "\npublished: " + published) + "\n---\n" + body, StandardCharsets.UTF_8);
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
    public static Path link(LibraryStore store, Path file, String text, String title, String by, String collection) {
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
            Files.writeString(p, "---\nurl: " + locator + "\ntitle: " + t + "\nfetched_at: " + Instant.now() + "\nfetched_by: " + by
                    + (collection == null || collection.isBlank() ? "" : "\ncollection: " + collection)
                    + "\nlinked: " + abs + "\nsha256: " + sha + "\nbytes: " + bytes + "\n---\n", StandardCharsets.UTF_8);
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
    public static String fileHash(Path file) throws java.io.IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String headField(Path raw, String key) {
        try (var lines = Files.lines(raw, StandardCharsets.UTF_8)) {
            return lines.limit(12).filter(l -> l.startsWith(key)).map(l -> l.substring(key.length()).strip()).findFirst().orElse("");
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
    public static boolean looksBinary(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.startsWith("%PDF") || head.startsWith("PK")) return true;
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
    public static Path find(LibraryStore store, String locator) throws java.io.IOException {
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
                } catch (java.io.UncheckedIOException ignored) { }
                if (url.equals(loc) || url.equals(loc + "/") || (url + "/").equals(loc)
                        || (loc.startsWith("http") && org.researchzosho.tools.Fetch.canonical(url).equals(org.researchzosho.tools.Fetch.canonical(loc)))) best = p;   // sorted by name = by date; last wins
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
    public static String[] read(Path raw) throws java.io.IOException {
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
        catch (java.io.IOException e) { return unreachable(file, Files.exists(file) ? e.getMessage() : "the path is missing"); }
        synchronized (LINKED) {
            String[] hit = LINKED.get(key);
            if (hit != null && hit[0].equals(Long.toString(mtime))) return hit[1];
        }
        String text;
        try {
            var doc = org.researchzosho.tools.DocText.convert(Files.readAllBytes(file), file.getFileName().toString());
            text = doc.text().isBlank() ? unreachable(file, "no text could be read (" + doc.kind() + ")")
                    : doc.text().length() > MAX_TEXT ? doc.text().substring(0, MAX_TEXT) + "\n\n[truncated at read]" : doc.text();
        } catch (Exception e) {
            return unreachable(file, e.getMessage());
        }
        synchronized (LINKED) { LINKED.put(key, new String[]{Long.toString(mtime), text}); }
        return text;
    }
}
