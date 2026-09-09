package org.researchzosho.librarian;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;

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
            if (Files.exists(p) && !looksBinary(read(p)[2])) return p;
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
        String locator = "", title = "";
        for (String line : head.split("\n")) {
            if (line.startsWith("url: ")) locator = line.substring(5).strip();
            else if (line.startsWith("title: ")) title = line.substring(7).strip();
        }
        return new String[]{locator, title, s.substring(end + 5)};
    }
}
