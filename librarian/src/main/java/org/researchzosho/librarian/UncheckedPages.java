package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;

/**
 * The pages a person gave (an address to add, a reading list, bookmarks, a url given to absorb, a list or a draft) that were saved
 * before a model could check them. The address is the person's own act, so a page no model could check is saved all the same and
 * marked {@link PageCheck#NOT_CHECKED_YET}; the always-dropped question ({@link PageCheck.Category#CHILD}) is asked of its saved text
 * at the next housekeeping, or at the next command that has a model. A page it then finds is removed from the shelves and the index,
 * and the person is told: in the command that removed it, in {@code researchzosho status}, and on the Inbox page.
 *
 * <p>{@code catalog/unchecked-pages.tsv}: the address, the saved file ("" when the address was read into something else, found then
 * by the address in the saved files' heads), and when. {@code catalog/removed-pages.tsv}: the address, the date, and what it was
 * removed as. Nothing of a removed page's content is kept.
 */
public final class UncheckedPages {

    private UncheckedPages() { }

    /** A line with no saved file younger than this may belong to a save still under way, so it is not dropped yet. */
    static final long SETTLE_MS = 10 * 60_000L;
    /** How many waiting pages one check takes at most, where it runs inside another command: a few, so that the command stays quick. */
    public static final int PER_COMMAND = 5;

    /** One page waiting for its check: its address, the saved file ("" when not known), and when it was saved. */
    public record Pending(String url, String raw, Instant at) { }

    /** What a check of the waiting pages did: how many it checked, the addresses it removed, and how many still wait. */
    public record Outcome(int checked, List<String> removed, int waiting) {
        static final Outcome NONE = new Outcome(0, List.of(), 0);
        /** The sentence for the person, or "" when nothing was removed. */
        public String sentence() {
            if (removed.isEmpty()) return "";
            return "The library removed " + (removed.size() == 1 ? "a page you added" : removed.size() + " pages you added") + " after checking "
                    + (removed.size() == 1 ? "it" : "them") + ": " + (removed.size() == 1 ? "it had " : "they had ") + PageCheck.Category.CHILD.said()
                    + ". Nothing of " + (removed.size() == 1 ? "it" : "them") + " is kept: " + String.join(", ", removed) + ".";
        }
    }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("unchecked-pages.tsv"); }
    static Path removedFile(LibraryStore store) { return store.root().resolve("catalog").resolve("removed-pages.tsv"); }

    /** A page the person gave, saved before its check could run: written down, to be checked when a model answers. {@code raw} may be null. */
    public static void note(LibraryStore store, String url, Path raw) throws IOException {
        if (store == null || url == null || url.isBlank()) return;
        String rawName = raw == null ? "" : raw.getFileName().toString();
        store.locked("unchecked-pages", () -> {
            for (Pending p : read(store)) if (p.url().equals(url.strip()) && p.raw().equals(rawName)) return null;
            Files.createDirectories(file(store).getParent());
            if (!Files.exists(file(store))) Files.writeString(file(store), "# address\tsaved file\twhen — pages the person gave that were saved before a model could check them\n", StandardCharsets.UTF_8);
            Files.writeString(file(store), clean(url) + "\t" + clean(rawName) + "\t" + Instant.now() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** Every page waiting for its check, oldest first. */
    public static List<Pending> pending(LibraryStore store) throws IOException { return read(store); }

    private static List<Pending> read(LibraryStore store) throws IOException {
        List<Pending> out = new ArrayList<>();
        if (store == null || !Files.exists(file(store))) return out;
        for (String l : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            Instant at;
            try { at = Instant.parse(f.length > 2 ? f[2] : ""); } catch (Exception e) { at = Instant.EPOCH; }
            out.add(new Pending(f[0], f.length > 1 ? f[1] : "", at));
        }
        return out;
    }

    /**
     * After a fetch of the person's own address: a page that could not be checked is written down; a page that was checked means a
     * model answers now, so the pages still waiting are checked ({@link #PER_COMMAND} at most). {@code raw}: the saved file, or null.
     */
    public static Outcome after(LibraryStore store, PageCheck.Page page, String url, Path raw, ContentJudge judge) {
        if (store == null || page == null || !page.kept()) return Outcome.NONE;
        try {
            if (page.unchecked()) { note(store, url, raw); return Outcome.NONE; }
            return recheck(store, judge, PER_COMMAND);
        } catch (IOException e) {
            return Outcome.NONE;
        }
    }

    /** Check up to {@code max} waiting pages with {@code judge} (null: the library's configured model). */
    public static Outcome recheck(LibraryStore store, ContentJudge judge, int max) throws IOException { return recheck(store, judge, max, System.currentTimeMillis()); }

    static Outcome recheck(LibraryStore store, ContentJudge judge, int max, long now) throws IOException {
        if (store == null || !Files.exists(file(store))) return Outcome.NONE;
        List<Pending> all = read(store);
        if (all.isEmpty()) return Outcome.NONE;
        ContentJudge j = judge != null ? judge : ContentJudge.configured();
        List<Pending> done = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int checked = 0;
        for (Pending p : all) {
            if (checked >= max) break;
            List<Path> files = saved(store, p);
            if (files.isEmpty()) { if (now - p.at().toEpochMilli() > SETTLE_MS) done.add(p); continue; }   // nothing was saved of it: nothing to check
            boolean unanswered = false, found = false;
            for (Path f : files) {
                String[] r;
                try { r = RawCapture.read(f); } catch (IOException gone) { continue; }
                ContentJudge.Reading reading = PageCheck.childWhole(r[1], p.url(), r[2], j);
                if (!reading.judged()) { unanswered = true; break; }
                if (reading.leansYes()) { found = true; remove(store, f); }
            }
            if (unanswered) break;   // no model answers now: this page and the rest wait for the next time
            checked++;
            done.add(p);
            if (found) {
                removed.add(p.url());
                record(store, p.url(), PageCheck.Category.CHILD.said());
                store.circulate("removed", "a page the person added, after its check: " + PageCheck.Category.CHILD.said() + " — " + p.url());
            }
        }
        if (!done.isEmpty()) drop(store, done);
        return new Outcome(checked, removed, read(store).size());
    }

    /** The saved files of a waiting page: its own file when known, else every saved file whose head names its address. */
    static List<Path> saved(LibraryStore store, Pending p) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!p.raw().isBlank()) {
            Path f = LibraryStore.under(store.rawDir(), p.raw());
            if (f != null && Files.isRegularFile(f)) out.add(f);
            return out;
        }
        if (!Files.isDirectory(store.rawDir())) return out;
        String canonical = Fetch.canonical(p.url());
        String source = Acquisitions.compress(p.url(), 120);
        try (var s = Files.list(store.rawDir())) {
            for (Path f : s.filter(x -> x.toString().endsWith(".md")).sorted().toList()) {
                String url = "", by = "";
                try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
                    for (String l : lines.limit(10).toList()) {
                        if (l.startsWith("url: ")) url = l.substring(5).strip();
                        else if (l.startsWith("fetched_by: ")) by = l.substring(12).strip();
                    }
                } catch (Exception unreadable) { continue; }
                int colon = by.indexOf(':');
                boolean fromIt = colon > 0 && by.substring(colon + 1).equals(source);   // a list, a transcript or a draft read from that address
                if ((!url.isEmpty() && Fetch.canonical(url).equals(canonical)) || fromIt) out.add(f);
            }
        }
        return out;
    }

    /** A saved page out of the shelves and out of the index, the index's files included. */
    private static void remove(LibraryStore store, Path f) {
        try { new LibrarianIndex(store).purge(f.getFileName().toString()); } catch (Exception ignored) { }
        try { Files.deleteIfExists(f); } catch (IOException ignored) { }
    }

    private static void drop(LibraryStore store, List<Pending> done) throws IOException {
        Set<String> gone = new LinkedHashSet<>();
        for (Pending p : done) gone.add(p.url() + "\t" + p.raw());
        store.locked("unchecked-pages", () -> {
            List<String> keep = new ArrayList<>();
            for (String l : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", -1);
                if (!l.startsWith("#") && f.length > 1 && gone.contains(f[0] + "\t" + f[1])) continue;
                keep.add(l);
            }
            Files.writeString(file(store), String.join("\n", keep) + "\n", StandardCharsets.UTF_8);
            return null;
        });
    }

    private static void record(LibraryStore store, String url, String why) throws IOException {
        store.locked("removed-pages", () -> {
            Files.createDirectories(removedFile(store).getParent());
            if (!Files.exists(removedFile(store))) Files.writeString(removedFile(store), "# address\tdate\tremoved as — pages the person added that a later check removed; nothing of them is kept\n", StandardCharsets.UTF_8);
            Files.writeString(removedFile(store), clean(url) + "\t" + LocalDate.now() + "\t" + clean(why) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** The pages a later check removed in the last {@code days} days: [address, date, removed as]. */
    public static List<String[]> removed(LibraryStore store, int days) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (store == null || !Files.exists(removedFile(store))) return out;
        LocalDate since = LocalDate.now().minus(days, ChronoUnit.DAYS);
        for (String l : Files.readAllLines(removedFile(store), StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            try { if (f.length > 1 && LocalDate.parse(f[1]).isBefore(since)) continue; } catch (Exception ignored) { }
            out.add(f);
        }
        return out;
    }

    /**
     * What the person is told where they look at the library's state: the pages waiting for their check, and the pages a later check
     * removed in the last thirty days. Each a sentence; none when there is nothing to say.
     */
    public static List<String> tell(LibraryStore store) {
        List<String> out = new ArrayList<>();
        try {
            int waiting = read(store).size();
            if (waiting > 0) out.add((waiting == 1 ? "One page you added is" : waiting + " pages you added are") + " saved but " + PageCheck.NOT_CHECKED_YET
                    + ". The library checks " + (waiting == 1 ? "it" : "them") + " when a model answers, and removes what the check finds.");
            List<String[]> gone = removed(store, 30);
            if (!gone.isEmpty()) {
                List<String> urls = new ArrayList<>();
                for (String[] g : gone) urls.add(g[0]);
                out.add(new Outcome(0, urls, 0).sentence());
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }
}
