package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.AtomicWrite;
import org.researchzosho.librarian.Calibre;
import org.researchzosho.librarian.Crews;
import org.researchzosho.librarian.Shelf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The person's own shelf and the DNA store (0.6.0): the EPUB behind each Calibre book, the record layer from what the shelf says, the
 * measured layer counted on sight, the labelled layer when the model answers. Labelling takes the model about a minute a book, so the
 * night housekeeping does it within a budget — the books the person went for first, then the rest — and the first "like X" on a book
 * not yet read labels it then. The person's reactions and stars never enter a document: they are the person's, kept with the preferences.
 */
public final class ShelfDna {
    private ShelfDna() { }

    /** The night's minutes, and the setting that changes them (0 turns the step off). */
    public static final String MINUTES_KEY = "RESEARCHZOSHO_DNA_MINUTES";
    public static final int MINUTES = 20;

    record Paths(Path db, long mtime, Map<Long, Path> epubs) { }
    private static volatile Paths paths;

    /** The EPUB file of every book on the shelf that has one, by Calibre id. */
    public static Map<Long, Path> epubs() throws IOException {
        Path db = Shelf.path();
        if (db == null) return Map.of();
        long mtime; try { mtime = Files.getLastModifiedTime(db).toMillis(); } catch (IOException e) { mtime = 0; }
        Paths p = paths;
        if (p != null && p.db().equals(db) && p.mtime() == mtime) return p.epubs();
        Map<Long, Path> out = new HashMap<>();
        Path root = db.toAbsolutePath().getParent();
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + Calibre.sqliteUri(db.toAbsolutePath().toString()) + "?immutable=1&mode=ro";
            try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT b.id, b.path, d.name FROM books b JOIN data d ON d.book = b.id AND d.format = 'EPUB'")) {
                while (rs.next()) { String bp = rs.getString(2), name = rs.getString(3); if (bp == null || name == null) continue; out.put(rs.getLong(1), root.resolve(bp).resolve(name + ".epub")); }
            }
        } catch (Exception e) { throw new IOException("the Calibre library could not be read for its files: " + e.getMessage()); }
        paths = new Paths(db, mtime, out);
        return out;
    }

    /** The EPUB behind a shelf book, or null when it has none or the file is gone. */
    public static Path epub(Shelf.Book b) throws IOException {
        Path p = epubs().get(b.id());
        return p != null && Files.isRegularFile(p) ? p : null;
    }

    // ---- the keys: a shelf book's document, without hashing sixty gigabytes twice ----

    record Known(String key, long size, long mtime) { }
    private static volatile Map<Long, Known> known;

    /** Forgets the files and keys remembered, so another shelf or store reads afresh. */
    public static synchronized void forget() { known = null; paths = null; }
    static Path keysFile() { return Dna.dir().resolve("shelf-keys.json"); }   // beside the kinds, not among the documents

    static synchronized Map<Long, Known> known() {
        if (known != null) return known;
        Map<Long, Known> m = new HashMap<>();
        Path f = keysFile();
        if (Files.isRegularFile(f)) {
            try {
                var n = Dna.M.readTree(Files.readString(f, StandardCharsets.UTF_8));
                n.fields().forEachRemaining(e -> m.put(Long.parseLong(e.getKey()), new Known(e.getValue().path("key").asText(), e.getValue().path("size").asLong(), e.getValue().path("mtime").asLong())));
            } catch (Exception ignored) { }
        }
        known = m;
        return m;
    }

    static synchronized void remember(long id, Known k) throws IOException {
        Map<Long, Known> m = known(); m.put(id, k);
        ObjectNode o = Dna.M.createObjectNode();
        for (Map.Entry<Long, Known> e : m.entrySet()) { ObjectNode v = o.putObject(Long.toString(e.getKey())); v.put("key", e.getValue().key()); v.put("size", e.getValue().size()); v.put("mtime", e.getValue().mtime()); }
        Files.createDirectories(keysFile().getParent());
        AtomicWrite.text(keysFile(), o.toString());
    }

    /** The DNA key of a shelf book: the hash of its EPUB, remembered against the file's size and date. Null without a file. */
    public static String key(Shelf.Book b) throws IOException {
        Path f = epub(b);
        if (f == null) return null;
        long size = Files.size(f), mtime = Files.getLastModifiedTime(f).toMillis();
        Known k = known().get(b.id());
        if (k != null && k.size() == size && k.mtime() == mtime) return k.key();
        String key = Dna.key(f);
        remember(b.id(), new Known(key, size, mtime));
        return key;
    }

    /** The record layer from the shelf: what Calibre holds, said to come from it. The person's own stars stay out: they are preference, not the book. */
    public static ObjectNode record(Shelf.Book b) {
        ObjectNode r = Dna.M.createObjectNode();
        r.put("title", b.title()); r.put("authors", b.authors()); r.put("year", b.year());
        ArrayNode tags = r.putArray("tags"); for (String t : b.tags()) tags.add(t);
        r.put("series", b.series()); r.put("isbn", b.isbn()); r.put("language", b.lang());
        r.put("calibre_id", b.id()); r.put("added", b.added());
        ObjectNode sources = r.putObject("sources"); sources.put("calibre", Instant.now().toString());
        return r;
    }

    /** The book's document as the store holds it, or null. */
    public static ObjectNode doc(Shelf.Book b) throws IOException {
        String key = key(b);
        return key == null ? null : Dna.read(Dna.BOOK, key);
    }

    /** The document with its measured layer current, made or completed now; null when the book has no EPUB. */
    public static ObjectNode measure(Shelf.Book b) throws IOException {
        String key = key(b);
        if (key == null) return null;
        ObjectNode d = Dna.read(Dna.BOOK, key);
        if (d == null) d = Dna.fresh(Dna.BOOK, key, record(b));
        if (Dna.measuredCurrent(d)) return d;
        Path f = epub(b);
        List<String> chapters = EpubText.chapters(f);
        long t0 = System.currentTimeMillis();
        ObjectNode m = d.putObject("measured");
        m.put("version", Measured.VERSION); m.put("at", Instant.now().toString());
        m.set("features", Measured.features(chapters));
        m.put("seconds", Py.round((System.currentTimeMillis() - t0) / 1000.0, 1));
        d.put("file", f.toString());
        Dna.write(d);
        return d;
    }

    /** The document with both layers current, the labelled one made now by the model; null when the book has no EPUB or too little text. */
    public static ObjectNode label(Shelf.Book b, Labels.Model model, Consumer<String> progress) throws Exception {
        ObjectNode d = measure(b);
        if (d == null) return null;
        if (Dna.labelledCurrent(d)) return d;
        List<String> chapters = EpubText.chapters(epub(b));
        long t0 = System.currentTimeMillis();
        ObjectNode l = Labels.label(chapters, model, progress);
        if (l == null) { d.putObject("labelled").put("note", "too little text to label"); Dna.write(d); return null; }
        l.put("at", Instant.now().toString()); l.put("seconds", Py.round((System.currentTimeMillis() - t0) / 1000.0, 1));
        if (model instanceof Named n) l.put("model", n.name());
        d.set("labelled", l);
        Dna.write(d);
        return d;
    }

    /** A model that says its name, so the document can carry it. */
    public interface Named { String name(); }

    /** The drive as the labeller, named, or null when no drive answers. */
    public static Labels.Model driveModel() {
        String driveUrl = Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), model = Config.get("RESEARCHZOSHO_MODEL", "local-model");
        if (!Crews.driveAnswers(driveUrl)) return null;
        return named(Labels.drive(new DriveClient(driveUrl, model)), model);
    }

    public static Labels.Model named(Labels.Model m, String name) {
        final class Both implements Labels.Model, Named {
            @Override public String complete(String system, String user, int maxTokens) throws Exception { return m.complete(system, user, maxTokens); }
            @Override public String name() { return name; }
        }
        return new Both();
    }

    /** The order the shelf is read in: what the person went for and rated first, then their other picks, then the imports. */
    public static List<Shelf.Book> order() throws IOException {
        List<Shelf.Book> books = new ArrayList<>(Shelf.books());
        books.sort(Comparator.comparingInt((Shelf.Book b) -> b.ratingGiven() && b.rating() >= 8 ? 0 : b.liked() ? 1 : b.chosen() ? 2 : 3).thenComparing(Comparator.comparing(Shelf.Book::added).reversed()).thenComparingLong(Shelf.Book::id));
        return books;
    }

    /**
     * The night's step: three quarters of the time labelling books the store has not read (the model), a quarter measuring books it has
     * not counted (no model), in the shelf's order; stops at the budget or when asked. A sentence for the log.
     */
    public static String nightly(long budgetMillis, Labels.Model model, BooleanSupplier stop) throws IOException {
        if (Shelf.path() == null) return "no Calibre library is set";
        if (budgetMillis <= 0) return "turned off (" + MINUTES_KEY + " is 0)";
        List<Shelf.Book> order = order();
        Map<Long, Path> files = epubs();
        long start = System.currentTimeMillis(), labelUntil = start + (model == null ? 0 : budgetMillis * 3 / 4), until = start + budgetMillis;
        int labelled = 0, measured = 0, failed = 0, skipped = 0;
        double labelSeconds = 0;
        if (model != null) {
            for (Shelf.Book b : order) {
                if (System.currentTimeMillis() > labelUntil || stop.getAsBoolean()) break;
                if (!files.containsKey(b.id())) continue;
                try {
                    ObjectNode d = doc(b);
                    if (Dna.labelledCurrent(d) || (d != null && d.path("labelled").has("note"))) continue;
                    long t0 = System.currentTimeMillis();
                    if (label(b, model, null) == null) skipped++; else { labelled++; labelSeconds += (System.currentTimeMillis() - t0) / 1000.0; }
                } catch (Exception e) { failed++; }
            }
        }
        for (Shelf.Book b : order) {
            if (System.currentTimeMillis() > until || stop.getAsBoolean()) break;
            if (!files.containsKey(b.id())) continue;
            try { ObjectNode d = doc(b); if (Dna.measuredCurrent(d)) continue; if (measure(b) != null) measured++; } catch (Exception e) { failed++; }
        }
        int[] counts = Dna.counts(Dna.BOOK);
        int withFile = files.size();
        return String.format(Locale.ROOT, "%d of the %d books with a file have their DNA counted and %d are labelled; tonight %d labelled%s and %d counted%s%s%s",
                counts[0], withFile, counts[1], labelled, labelled > 0 ? String.format(Locale.ROOT, " (%.0f seconds a book)", labelSeconds / labelled) : "", measured,
                model == null ? " (no model answered, so none was labelled)" : "", skipped > 0 ? ", " + skipped + " too short to label" : "", failed > 0 ? ", " + failed + " could not be read" : "");
    }

    /** The night's step as the crews call it: the setting's minutes, the drive as the model. */
    public static String nightly(String driveUrl, String model, BooleanSupplier stop) throws IOException {
        int minutes = Config.getInt(MINUTES_KEY, MINUTES);
        Labels.Model m = driveUrl == null ? null : named(Labels.drive(new DriveClient(driveUrl, model)), model);
        return nightly(minutes * 60_000L, m, stop);
    }
}
