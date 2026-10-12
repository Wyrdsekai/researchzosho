package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Shelf;
import org.researchzosho.tools.Podcasts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The second kind, podcasts (0.6.0): an episode's DNA from the transcript the library holds — the one the show published or the one the
 * library transcribed at night — through the same three layers and the same window pipeline as a book. The record from the capture
 * (the show, the episode, its date, the audio's address, the length from the last time stamp); the measured layer counted from the
 * spoken words, with the words a minute; the labelled layer by the settled questions, the tone words for the talk. The show is the
 * maker here: "more of the same show" is the old mechanism and goes to the side list, as an author does. This is what proves the frame
 * is not about books; the podcast's own question wording and the audio features the design names come in 0.6.x (docs/REC_DNA.md).
 */
public final class PodcastDna {
    private PodcastDna() { }

    /** A transcript the library keeps, with what its capture says. */
    public record Saved(Path raw, String locator, String title, String published, String body) {
        /** The show, before the dash the capture put between it and the episode. */
        public String show() { int i = title.indexOf(" — "); return i < 0 ? "" : title.substring(0, i).strip(); }
        public String episode() { int i = title.indexOf(" — "); return i < 0 ? title : title.substring(i + 3).strip(); }
    }

    static final Pattern STAMP = Pattern.compile("^\\[(?:(\\d+):)?(\\d{1,2}):(\\d{2})\\] ?", Pattern.MULTILINE);

    /** Every transcript the library holds, newest capture first. */
    public static List<Saved> saved(LibraryStore store) {
        List<Saved> out = new ArrayList<>();
        if (store == null || !Files.isDirectory(store.rawDir())) return out;
        try (Stream<Path> s = Files.list(store.rawDir())) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".md")).sorted(Comparator.reverseOrder()).toList()) {
                boolean podcast;
                try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) { podcast = lines.limit(8).anyMatch(l -> l.equals("fetched_by: " + Podcasts.BY)); } catch (UncheckedIOException e) { continue; }
                if (!podcast) continue;
                String[] r = RawCapture.read(p);
                out.add(new Saved(p, r[0], r[1], RawCapture.published(p), r[2]));
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The spoken words without the time stamps. */
    public static String spoken(String body) { return STAMP.matcher(body == null ? "" : body).replaceAll(""); }

    /** The episode's length from the last time stamp, in seconds; 0 when the transcript carries none. */
    public static int seconds(String body) {
        int last = 0; Matcher m = STAMP.matcher(body == null ? "" : body);
        while (m.find()) last = Math.max(last, (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 3600) + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)));
        return last;
    }

    /** The record layer from the capture: the show as the maker, the episode as the title. */
    public static ObjectNode record(Saved s) {
        ObjectNode r = Dna.M.createObjectNode();
        r.put("title", s.episode()); r.put("authors", s.show()); r.put("show", s.show()); r.put("year", s.published().length() >= 4 ? s.published().substring(0, 4) : "");
        r.put("date", s.published()); r.put("audio", s.locator()); r.put("seconds", seconds(s.body())); r.putArray("tags");
        r.putObject("sources").put("library-transcript", Instant.now().toString());
        return r;
    }

    public static ObjectNode doc(Saved s) throws IOException { return Dna.read(Dna.PODCAST, Dna.key(s.raw())); }

    /** The document with its measured layer current, made now. */
    public static ObjectNode measure(Saved s) throws IOException {
        String key = Dna.key(s.raw());
        ObjectNode d = Dna.read(Dna.PODCAST, key);
        if (d == null) d = Dna.fresh(Dna.PODCAST, key, record(s));
        if (Dna.measuredCurrent(d)) return d;
        String text = spoken(s.body());
        ObjectNode m = d.putObject("measured");
        m.put("version", Measured.VERSION); m.put("at", Instant.now().toString());
        ObjectNode f = Measured.features(List.of(text));
        int secs = seconds(s.body());
        if (secs > 0 && f.path("words").asInt() > 0) f.put("words_per_minute", Py.round(f.path("words").asInt() / (secs / 60.0), 1));
        m.set("features", f);
        d.put("file", s.raw().toString());
        Dna.write(d);
        return d;
    }

    /** The document with both layers current, the labelled one made now by the model; null when the transcript is too short. */
    public static ObjectNode label(Saved s, Labels.Model model, Consumer<String> progress) throws Exception {
        ObjectNode d = measure(s);
        if (Dna.labelledCurrent(d)) return d;
        long t0 = System.currentTimeMillis();
        ObjectNode l = Labels.label(List.of(spoken(s.body())), model, progress);
        if (l == null) { d.putObject("labelled").put("note", "too little text to label"); Dna.write(d); return null; }
        l.put("at", Instant.now().toString()); l.put("seconds", Py.round((System.currentTimeMillis() - t0) / 1000.0, 1));
        if (model instanceof ShelfDna.Named n) l.put("model", n.name());
        d.set("labelled", l);
        Dna.write(d);
        return d;
    }

    /** The night's step for podcasts: the transcripts not yet read, newest first, within the budget. A sentence. */
    public static String nightly(LibraryStore store, long budgetMillis, Labels.Model model, BooleanSupplier stop) throws IOException {
        List<Saved> all = saved(store);
        if (all.isEmpty()) return "no podcast transcript is held yet";
        if (budgetMillis <= 0) return "turned off (" + ShelfDna.MINUTES_KEY + " is 0)";
        long until = System.currentTimeMillis() + budgetMillis;
        int labelled = 0, measured = 0, failed = 0, skipped = 0;
        for (Saved s : all) {
            if (System.currentTimeMillis() > until || stop.getAsBoolean()) break;
            try {
                ObjectNode d = doc(s);
                if (Dna.labelledCurrent(d) || (d != null && d.path("labelled").has("note"))) continue;
                if (model == null) { if (!Dna.measuredCurrent(d)) { measure(s); measured++; } continue; }
                if (label(s, model, null) == null) skipped++; else labelled++;
            } catch (Exception e) { failed++; }
        }
        int[] c = Dna.counts(Dna.PODCAST);
        return String.format(Locale.ROOT, "%d of the %d transcripts held have their DNA counted and %d are labelled; tonight %d labelled and %d counted%s%s%s", c[0], all.size(), c[1], labelled, measured,
                model == null ? " (no model answered, so none was labelled)" : "", skipped > 0 ? ", " + skipped + " too short to label" : "", failed > 0 ? ", " + failed + " could not be read" : "");
    }

    /** What "like <episode>" comes to: the episode found by words of its title among the labelled, then the list; or the sentence that says why not. */
    public record Found(ObjectNode x, LikeX.Result result, String message) { public boolean ok() { return result != null; } }

    public static Found like(String words, int limit) {
        List<ObjectNode> pool = Dna.labelled(Dna.PODCAST);
        if (pool.isEmpty()) return new Found(null, null, "No podcast episode is labelled yet; the night housekeeping reads the transcripts the library holds (researchzosho podcast dna reads now).");
        String want = Shelf.norm(words);
        if (want.isEmpty()) return new Found(null, null, "Say which episode, by words of its title.");
        ObjectNode x = null;
        for (ObjectNode d : pool) {
            String hay = Shelf.norm(d.path("record").path("show").asText("") + " " + d.path("record").path("title").asText(""));
            boolean all = true; for (String w : want.split(" ")) if (!hay.contains(w)) { all = false; break; }
            if (all) { x = d; break; }
        }
        if (x == null) return new Found(null, null, "No labelled episode carries the words \"" + words + "\" in its show or title.");
        return new Found(x, LikeX.like(x, pool, Prefs.load(Dna.PODCAST), limit), "");
    }

    /** The list as the person reads it: episodes, the same show set aside. */
    public static String text(LikeX.Result r) { return LikeX.text(r, "episodes", "the transcripts the library holds", "the same show's other episodes set aside (more of the same show is the old way of finding more)"); }
}
