package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.tools.Shops;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The person's own shelf as a recommender reads it (0.6.0): the Calibre library named by the {@code calibre} setting, read-only, with
 * every book's rating, tags, authors and year; the podcasts followed and the searches watched ({@link Serials}); what the ledger says was
 * bought ({@link Owned}); the holdings lists. Three questions: the profile (what the person rates highly, as a subject mix, authors,
 * periods and sample titles — the model writes the prose, this gives the counts), whether a title is already here, and what was liked on
 * a subject. Ratings outrank possession: a shelf of seventy thousand books says little, the three thousand the person rated say what
 * they like. The design and its evidence are in docs/REC_BOOKS.md.
 */
public final class Shelf {
    private Shelf() { }

    static final ObjectMapper M = new ObjectMapper();

    /**
     * One book as Calibre holds it; {@code rating} is Calibre's 0–10 (two per star), 0 when unrated. {@code chosen}: added on a day with
     * fewer than {@link #BULK} additions — the person's own pick, where a day of fifty or more is an import that came with the files and
     * says nothing about taste. {@code ratingGiven}: rated on a day with fewer than {@link #BULK} ratings — a star the person gave, where a
     * day of fifty or more came in with the files (a shelf of 74,707 held 3,462 stars, 3,410 of them in four such days, none given by the
     * person).
     */
    public record Book(long id, String title, String authors, String year, List<String> tags, int rating, String series, String isbn, String lang, String added, boolean chosen, boolean ratingGiven) {
        public double stars() { return rating / 2.0; }
        /** What the person went for: a book they added themselves and did not rate low, or one they rated well themselves. */
        public boolean liked() { return (chosen && !(ratingGiven && rating <= 4)) || (ratingGiven && rating >= 8); }
        /** A book the person rated low themselves. */
        public boolean disliked() { return ratingGiven && rating <= 4; }
    }

    /** Additions or ratings on one day from this many up are an import, not a choice. */
    public static final int BULK = 50;

    record Loaded(Path db, long mtime, List<Book> books, Map<String, List<Book>> byTitle, Map<String, List<Book>> byShortTitle, Map<String, Book> byIsbn) { }

    private static volatile Loaded cache;
    /** A test's own database. */
    static volatile Path override;

    /** The Calibre database the setting names (a folder means its metadata.db), else the default library in the home folder, else null. */
    public static Path path() {
        String v = Config.get(Settings.CALIBRE);
        Path p;
        if (override != null) p = override;
        else if (v == null || v.isBlank()) p = Path.of(System.getProperty("user.home"), "Calibre Library", "metadata.db");
        else { String s = v.strip(); if (s.startsWith("~")) s = System.getProperty("user.home") + s.substring(1); p = Path.of(s); }
        if (Files.isDirectory(p)) p = p.resolve("metadata.db");
        return Files.isRegularFile(p) ? p : null;
    }

    /** Every book, read once per change of the database file. Empty when no library is set. */
    public static List<Book> books() throws IOException {
        Path db = path();
        if (db == null) { cache = null; return List.of(); }   // a library unset or gone: the old shelf is forgotten, not answered from
        long mtime;
        try { mtime = Files.getLastModifiedTime(db).toMillis(); } catch (IOException e) { mtime = 0; }
        Loaded c = cache;
        if (c != null && c.db().equals(db) && c.mtime() == mtime) return c.books();
        synchronized (Shelf.class) {
            c = cache;
            if (c != null && c.db().equals(db) && c.mtime() == mtime) return c.books();
            List<Book> books;
            try { books = read(db); } catch (Exception e) { throw new IOException("the Calibre library could not be read: " + e.getMessage()); }
            Map<String, List<Book>> byTitle = new HashMap<>(), byShort = new HashMap<>(); Map<String, Book> byIsbn = new HashMap<>();
            for (Book b : books) {
                String n = norm(b.title()); if (!n.isEmpty()) byTitle.computeIfAbsent(n, k -> new ArrayList<>()).add(b);
                String s = shortTitle(b.title()); if (!s.isEmpty() && !s.equals(n)) byShort.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
                if (!b.isbn().isEmpty()) byIsbn.put(b.isbn().replaceAll("[^0-9Xx]", ""), b);
            }
            cache = new Loaded(db, mtime, books, byTitle, byShort, byIsbn);
            return books;
        }
    }

    static final String QUERY = """
            SELECT b.id, b.title, b.pubdate, b.timestamp,
              (SELECT group_concat(a.name, ' & ') FROM books_authors_link l JOIN authors a ON a.id = l.author WHERE l.book = b.id) AS authors,
              (SELECT group_concat(t.name, '|') FROM books_tags_link l JOIN tags t ON t.id = l.tag WHERE l.book = b.id) AS tags,
              COALESCE((SELECT r.rating FROM books_ratings_link l JOIN ratings r ON r.id = l.rating WHERE l.book = b.id), 0) AS rating,
              (SELECT s.name FROM books_series_link l JOIN series s ON s.id = l.series WHERE l.book = b.id) AS series,
              COALESCE((SELECT i.val FROM identifiers i WHERE i.book = b.id AND i.type = 'isbn'), b.isbn, '') AS isbn,
              (SELECT lg.lang_code FROM books_languages_link l JOIN languages lg ON lg.id = l.lang_code WHERE l.book = b.id ORDER BY l.item_order LIMIT 1) AS lang
            FROM books b ORDER BY b.id""";

    static List<Book> read(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        List<Object[]> rows = new ArrayList<>();
        String url = "jdbc:sqlite:" + Calibre.sqliteUri(db.toAbsolutePath().toString()) + "?immutable=1&mode=ro";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
            while (rs.next()) {
                String tagStr = Calibre.nz(rs.getString("tags"));
                List<String> tags = new ArrayList<>();
                if (!tagStr.isEmpty()) for (String t : tagStr.split("\\|")) { String x = t.strip(); if (!x.isEmpty() && !tags.contains(x)) tags.add(x); }
                String added = Calibre.nz(rs.getString("timestamp")); if (added.length() > 10) added = added.substring(0, 10);
                rows.add(new Object[]{rs.getLong("id"), Calibre.nz(rs.getString("title")), Calibre.nz(rs.getString("authors")), Calibre.year(Calibre.nz(rs.getString("pubdate"))), tags,
                        rs.getInt("rating"), Calibre.nz(rs.getString("series")), Calibre.nz(rs.getString("isbn")), Calibre.nz(rs.getString("lang")), added});
            }
        }
        // provenance: how many books, and how many ratings, each day brought
        Map<String, Integer> addedOn = new HashMap<>(), ratedOn = new HashMap<>();
        for (Object[] r : rows) { addedOn.merge((String) r[9], 1, Integer::sum); if ((int) r[5] > 0) ratedOn.merge((String) r[9], 1, Integer::sum); }
        List<Book> out = new ArrayList<>();
        for (Object[] r : rows) {
            String day = (String) r[9];
            boolean chosen = addedOn.getOrDefault(day, 0) < BULK, given = (int) r[5] > 0 && ratedOn.getOrDefault(day, 0) < BULK;
            @SuppressWarnings("unchecked") List<String> tags = (List<String>) r[4];
            out.add(new Book((long) r[0], (String) r[1], (String) r[2], (String) r[3], tags, (int) r[5], (String) r[6], (String) r[7], (String) r[8], day, chosen, given));
        }
        return out;
    }

    // ---- the profile ----

    /** The tags that say nothing about taste. */
    static final List<String> EMPTY_TAGS = List.of("general", "etc.", "etc", "fiction", "nonfiction", "non-fiction", "literature", "unknown", "ebook", "ebooks", "calibre", "kindle", "epub", "misc", "miscellaneous");

    /** What the shelf, the follows and the ledger say, as counts; the model writes the prose. */
    public static ObjectNode profile(LibraryStore store) throws IOException {
        ObjectNode r = M.createObjectNode();
        List<Book> all = books();
        r.put("calibre", path() != null);
        r.put("books", all.size());
        List<Book> chosen = all.stream().filter(Book::chosen).toList();
        List<Book> given = all.stream().filter(Book::ratingGiven).toList();
        int imported = (int) all.stream().filter(b -> b.rating() > 0 && !b.ratingGiven()).count();
        List<Book> liked = all.stream().filter(Book::liked).toList();
        List<Book> disliked = all.stream().filter(Book::disliked).toList();
        r.put("chosen", chosen.size()); r.put("bulk", all.size() - chosen.size());
        r.put("ratings_given", given.size()); r.put("ratings_imported", imported);
        r.put("rated", given.size()); r.put("liked", liked.size()); r.put("disliked", disliked.size());
        ObjectNode stars = r.putObject("stars");
        Map<String, Integer> sc = new TreeMap<>(Comparator.reverseOrder());
        for (Book b : given) sc.merge(String.format(Locale.ROOT, "%.1f", Math.floor(b.stars() * 2) / 2), 1, Integer::sum);
        sc.forEach(stars::put);
        // the subject mix: among the liked books, against the whole shelf — the tilt says what is liked more than it is owned
        Map<String, Integer> likedTags = tagCounts(liked), shelfTags = tagCounts(all);
        ArrayNode subjects = r.putArray("subjects_liked");
        likedTags.entrySet().stream().sorted(BY_COUNT).limit(30).forEach(e -> {
            ObjectNode o = subjects.addObject(); o.put("tag", e.getKey()); o.put("liked", e.getValue()); o.put("share", pct(e.getValue(), liked.size()));
            int onShelf = shelfTags.getOrDefault(e.getKey(), 0); o.put("on_shelf", onShelf);
            if (onShelf > 0 && !all.isEmpty() && !liked.isEmpty()) o.put("tilt", String.format(Locale.ROOT, "%.1f", (e.getValue() / (double) liked.size()) / (onShelf / (double) all.size())));
        });
        ArrayNode shelfSubjects = r.putArray("subjects_shelf");
        shelfTags.entrySet().stream().sorted(BY_COUNT).limit(15).forEach(e -> { ObjectNode o = shelfSubjects.addObject(); o.put("tag", e.getKey()); o.put("books", e.getValue()); o.put("share", pct(e.getValue(), all.size())); });
        // authors the person keeps rating highly
        Map<String, int[]> authors = new LinkedHashMap<>();
        for (Book b : liked) for (String a : b.authors().split(" & ")) { String n = a.strip(); if (n.isEmpty() || n.equalsIgnoreCase("unknown") || n.equalsIgnoreCase("anonymous")) continue; int[] v = authors.computeIfAbsent(n, k -> new int[2]); v[0]++; v[1] += b.rating(); }
        ArrayNode au = r.putArray("authors_liked");
        authors.entrySet().stream().filter(e -> e.getValue()[0] >= 2).sorted((a, b) -> b.getValue()[0] != a.getValue()[0] ? b.getValue()[0] - a.getValue()[0] : b.getValue()[1] != a.getValue()[1] ? b.getValue()[1] - a.getValue()[1] : a.getKey().compareTo(b.getKey())).limit(25)
                .forEach(e -> { ObjectNode o = au.addObject(); o.put("author", e.getKey()); o.put("liked", e.getValue()[0]); o.put("stars", stars(e.getValue()[1] / 2.0 / e.getValue()[0])); });
        Map<String, Integer> decades = new TreeMap<>();
        for (Book b : liked) if (b.year().matches("\\d{4}") && Integer.parseInt(b.year()) > 1000) decades.merge(b.year().substring(0, 3) + "0s", 1, Integer::sum);
        ObjectNode dec = r.putObject("decades_liked"); decades.forEach(dec::put);
        Map<String, Integer> langs = new TreeMap<>();
        for (Book b : all) if (!b.lang().isEmpty()) langs.merge(b.lang(), 1, Integer::sum);
        ObjectNode lg = r.putObject("languages"); langs.entrySet().stream().sorted(BY_COUNT).limit(6).forEach(e -> lg.put(e.getKey(), e.getValue()));
        Map<String, Integer> series = new LinkedHashMap<>();
        for (Book b : liked) if (!b.series().isEmpty()) series.merge(b.series(), 1, Integer::sum);
        ArrayNode se = r.putArray("series_liked"); series.entrySet().stream().sorted(BY_COUNT).limit(10).forEach(e -> se.add(e.getKey() + " (" + e.getValue() + ")"));
        // samples spread across the shelf's history, not the last batch added: every k-th book in the order they were added
        sample(r.putArray("loved"), spread(given.stream().filter(b -> b.rating() >= 9).sorted(Comparator.comparing(Book::added)).toList(), 15));
        sample(r.putArray("liked_sample"), spread(given.stream().filter(b -> b.rating() == 8).sorted(Comparator.comparing(Book::added)).toList(), 15));
        sample(r.putArray("chosen_sample"), spread(chosen.stream().sorted(Comparator.comparing(Book::added)).toList(), 20));
        sample(r.putArray("disliked_sample"), spread(disliked.stream().sorted(Comparator.comparing(Book::added)).toList(), 8));
        sample(r.putArray("added_lately"), all.stream().sorted(Comparator.comparing(Book::added).reversed()).limit(10).toList());
        // what else the library knows the person follows, owns and holds
        ArrayNode pods = r.putArray("podcasts_followed"), watched = r.putArray("searches_watched");
        for (Serials.Shelf s : Serials.shelves(store)) { if (s.podcast()) pods.add(s.slug() + " — " + s.feedUrl()); else if (watched.size() < 20) watched.add(s.slug() + ": " + s.query()); }   // a feed address, so that podcast_search kind=similar can start from it
        ObjectNode st = Owned.status(store);
        ObjectNode owned = r.putObject("owned");
        owned.put("lines", st.path("lines").asInt()); owned.put("purchases", st.path("purchases").asInt());
        ObjectNode shops = owned.putObject("shops"); int n = 0; var it = st.path("shops").fields(); while (it.hasNext() && n++ < 8) { var e = it.next(); shops.put(e.getKey(), e.getValue().asInt()); }
        ArrayNode recent = owned.putArray("recent");
        for (Owned.Line l : Owned.find(store, "", "", "", 10)) if (!l.title().isEmpty()) recent.add(l.date() + " " + l.title() + (l.shop().isEmpty() ? "" : " (" + l.shop() + ")"));
        r.put("holdings", Holdings.size(store));
        return r;
    }

    /** Most first; equal counts in name order, so the profile reads the same twice. */
    static final Comparator<Map.Entry<String, Integer>> BY_COUNT = (a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue();

    /** 4.5 as "4.5", 4.0 as "4". */
    public static String stars(double v) { return String.format(Locale.ROOT, "%.1f", v).replace(".0", ""); }

    static Map<String, Integer> tagCounts(List<Book> books) {
        Map<String, Integer> m = new HashMap<>();
        for (Book b : books) for (String t : b.tags()) { String k = t.strip(); if (EMPTY_TAGS.contains(k.toLowerCase(Locale.ROOT))) continue; m.merge(k, 1, Integer::sum); }
        return m;
    }

    static void sample(ArrayNode arr, List<Book> books) { for (Book b : books) arr.add(line(b)); }

    /** Up to {@code n} of the list, evenly spaced, newest last. */
    static List<Book> spread(List<Book> all, int n) {
        if (all.size() <= n) return all;
        List<Book> out = new ArrayList<>();
        double step = all.size() / (double) n;
        for (int i = 0; i < n; i++) out.add(all.get((int) Math.min(all.size() - 1, Math.floor(i * step + step / 2))));
        return out;
    }

    /** One book on one line: title — authors (year), N★, tags. */
    public static String line(Book b) {
        StringBuilder s = new StringBuilder(Shops.clean(b.title()));
        if (!b.authors().isEmpty()) s.append(" — ").append(Shops.clean(b.authors()));
        if (!b.year().isEmpty()) s.append(" (").append(b.year()).append(')');
        if (b.ratingGiven()) s.append(", ").append(stars(b.stars())).append('★');
        if (!b.series().isEmpty()) s.append(", ").append(Shops.clean(b.series()));
        if (!b.tags().isEmpty()) s.append(", ").append(Shops.clean(String.join(", ", b.tags().subList(0, Math.min(5, b.tags().size())))));
        return s.toString();
    }

    static String pct(int part, int whole) { return whole == 0 ? "0%" : Math.round(100.0 * part / whole) + "%"; }

    // ---- have ----

    /** Where a title already is: the shelf, the ledger, the holdings lists. */
    public record Have(String where, String title, String detail) {
        public ObjectNode json() { ObjectNode o = M.createObjectNode(); o.put("where", where); o.put("title", title); o.put("detail", detail); return o; }
    }

    /** The shelf's books with this title (or ISBN); with an author, those by that author when any, else all of them — a wrong author does not hide a book. */
    public static List<Book> shelfMatches(String title, String author) throws IOException {
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) return List.of();
        books();
        Loaded c = cache;
        if (c == null) return List.of();
        String digits = t.replaceAll("[^0-9Xx]", "");
        List<Book> found = new ArrayList<>();
        if (digits.length() >= 10 && digits.length() == t.replaceAll("[\\s-]", "").length() && c.byIsbn().containsKey(digits)) found.add(c.byIsbn().get(digits));
        String n = norm(t), s = shortTitle(t);
        if (found.isEmpty()) found.addAll(c.byTitle().getOrDefault(n, List.of()));
        if (found.isEmpty() && !s.isEmpty()) { found.addAll(c.byTitle().getOrDefault(s, List.of())); found.addAll(c.byShortTitle().getOrDefault(s, List.of())); }
        if (found.isEmpty() && !n.equals(s)) found.addAll(c.byShortTitle().getOrDefault(n, List.of()));
        String a = author == null ? "" : norm(author);
        if (a.isEmpty() || found.isEmpty()) return found;
        List<Book> same = found.stream().filter(b -> sameAuthor(a, b.authors())).toList();
        return same.isEmpty() ? found : same;
    }

    /** The shelf's book with this title, by this author when the shelf has one; null when the shelf has none or is not set. */
    public static Book onShelf(String title, String author) {
        try { List<Book> m = shelfMatches(title, author); return m.isEmpty() ? null : m.get(0); } catch (IOException e) { return null; }
    }

    /** Whether the person has this title already, by its title (and author when given) or by ISBN. */
    public static List<Have> have(LibraryStore store, String title, String author) throws IOException {
        List<Have> out = new ArrayList<>();
        String t = title == null ? "" : title.strip();
        if (t.isEmpty()) return out;
        String a = author == null ? "" : norm(author);
        for (Book b : shelfMatches(t, author)) {
            String note = !a.isEmpty() && !sameAuthor(a, b.authors()) ? " — by " + Shops.clean(b.authors()) + ", not " + Shops.clean(author.strip()) : "";
            out.add(new Have("shelf", b.title(), line(b) + (b.ratingGiven() ? " — rated" : b.chosen() ? " — on the shelf, added by the person" : " — on the shelf, from a bulk import") + note));
            if (out.size() >= 5) break;
        }
        for (Owned.Line l : Owned.alreadyOwned(store, t)) { out.add(new Have("ledger", l.title(), "bought " + l.date() + (l.shop().isEmpty() ? "" : " at " + l.shop()))); if (out.size() >= 8) break; }
        try {
            for (Holdings.Match m : Holdings.find(store, t, 5, null)) {
                if (!a.isEmpty() && !sameAuthor(a, norm(m.item() + " " + m.note()).replace(" ", " & "))) continue;   // the author narrows a list entry too
                out.add(new Have("holdings", m.item(), "on the list " + m.list() + (m.note().isEmpty() ? "" : ": " + m.note())));
                if (out.size() >= 10) break;
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The subjects among the books the person went for (chosen, or rated well themselves), most first, as the shelf spells them, with how many carry each. */
    public static List<Map.Entry<String, Integer>> likedSubjects(int n) throws IOException {
        List<Book> liked = books().stream().filter(Book::liked).toList();
        return tagCounts(liked).entrySet().stream().sorted(BY_COUNT).limit(n).toList();
    }

    /** The authors with two or more books the person went for, normalised. */
    public static Set<String> likedAuthors() throws IOException {
        Map<String, Integer> m = new HashMap<>();
        for (Book b : books()) if (b.liked()) for (String a : b.authors().split(" & ")) { String x = norm(a); if (!x.isEmpty()) m.merge(x, 1, Integer::sum); }
        Set<String> out = new HashSet<>();
        m.forEach((k, v) -> { if (v >= 2) out.add(k); });
        return out;
    }

    static boolean sameAuthor(String wantNorm, String authors) {
        String[] want = wantNorm.split(" ");
        String last = want[want.length - 1];
        if (last.length() < 2) return true;
        for (String a : authors.split(" & ")) { String n = norm(a); if (n.equals(wantNorm) || (" " + n + " ").contains(" " + last + " ")) return true; }
        return false;
    }

    // ---- liked ----

    /**
     * The books the person went for whose title, authors, series or tags carry every word — with {@code stars} above zero, only those they
     * rated at least that themselves; best first (a given rating, then a chosen book), then newest added.
     */
    public static List<Book> liked(String words, double stars, int limit) throws IOException {
        List<String> want = new ArrayList<>();
        for (String w : norm(words == null ? "" : words).split(" ")) if (!w.isEmpty()) want.add(w);
        int min = (int) Math.round(stars * 2);
        List<Book> out = new ArrayList<>();
        for (Book b : books()) {
            if (min > 0 ? !(b.ratingGiven() && b.rating() >= min) : !b.liked()) continue;
            if (!want.isEmpty()) { String hay = " " + norm(b.title() + " " + b.authors() + " " + b.series() + " " + String.join(" ", b.tags())) + " "; boolean all = true; for (String w : want) if (!hay.contains(" " + w + " ") && !hay.contains(w)) { all = false; break; } if (!all) continue; }
            out.add(b);
        }
        out.sort(Comparator.comparingInt((Book b) -> b.ratingGiven() ? b.rating() : 0).reversed().thenComparing(Comparator.comparing(Book::added).reversed()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // ---- words ----

    /** Lower case, no accents, no punctuation, one space, no leading article. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        n = n.replace('’', '\'').replaceAll("['‘’]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        n = n.replaceFirst("^(the|a|an) ", "");
        return n;
    }

    /** The title before its subtitle (": …", " — …", " - …"), normalised. */
    public static String shortTitle(String s) {
        if (s == null) return "";
        String t = s.split("\\s+[:—–]\\s+|:\\s+|\\s+-\\s+|\\s*\\(")[0];
        return norm(t);
    }
}
