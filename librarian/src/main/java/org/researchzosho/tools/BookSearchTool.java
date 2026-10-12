package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Shelf;
import org.researchzosho.librarian.ShelfAuthors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code book_search}: the real books a run may recommend or write about — from the catalogues, by words, by subject heading, by author,
 * by Japanese 件名 or NDC class, and a work's own record with its description and subjects. Keyless. A book that is not in a catalogue
 * result of this run is not a book the run may name: that is the rule that keeps invented titles out.
 */
public final class BookSearchTool implements Tool {

    static final int MOST = 30, MOST_CALLS = 40;
    private final AtomicInteger calls = new AtomicInteger();
    private ContentPolicy policy;

    /** The run's policy, on which what the catalogues returned is recorded, for the title check of the write-up. */
    public BookSearchTool policy(ContentPolicy p) { this.policy = p; return this; }

    /** A book returned: its normalised title, its title before the subtitle, and its record key, so that the write-up's title check can find it. */
    static void returned(ContentPolicy p, String title, String url) { returned(p, title, url, List.of(), ""); }
    static void returned(ContentPolicy p, String title, String url, List<String> authors) { returned(p, title, url, authors, ""); }

    /**
     * With the authors and the year: a "title|surname" key for each author, so that a row's author is checked with its title (a bare title
     * matches too much), and a "title@year" key, so that a row's year is checked against the record's.
     */
    static void returned(ContentPolicy p, String title, String url, List<String> authors, String year) {
        if (p == null) return;
        String key = url == null ? "" : url.replaceFirst("^https?://[^/]+", "");
        p.returned(Shelf.norm(title), Shelf.shortTitle(title), key.isEmpty() ? null : key);
        for (String a : authors) { String sn = surname(a); if (!sn.isEmpty()) p.returned(Shelf.norm(title) + "|" + sn, Shelf.shortTitle(title) + "|" + sn); }
        if (year != null && year.matches("\\d{4}")) p.returned(Shelf.norm(title) + "@" + year, Shelf.shortTitle(title) + "@" + year);
    }

    /** The last word of a name, normalised: "Ursula K. Le Guin" → "guin", "林 ケストレル" → "ケストレル". */
    public static String surname(String name) {
        String n = Shelf.norm(name); if (n.isEmpty()) return "";
        String[] w = n.split(" "); return w[w.length - 1];
    }

    @Override public String name() { return "book_search"; }

    @Override public String description() {
        return "Books, from the catalogues: only a book this tool returned in this run may be recommended or described, cited by its record link. kind=search: "
                + "books by words (Open Library; NDL Search for Japanese words), each with authors, year, subjects, editions, ratings when the catalogue has them, "
                + "ISBNs and the record link. kind=subject: books under a subject heading (Open Library's headings, e.g. \"Information retrieval\"; NDL's 件名 for "
                + "Japanese), where the long tail lives — a book with one edition stands beside one with fifty. kind=author: an author's books. kind=ndc: Japanese "
                + "books under an NDC class prefix (007, 913.6…). kind=work: one work's record by its Open Library key (its description, every subject, places "
                + "and times), or by ISBN for a Japanese book (openBD's 内容紹介 and 目次 when the publisher gave them). kind=shelf: for \"what should I read "
                + "next\" — the works of the authors the person rates well that the shelf lacks (the lever that reaches the catalogue's tail), then candidates "
                + "under the subjects they rated highly for breadth; query narrows both to its words; what they own is left out and each book's fit is counted; "
                + "the first call to make for such an ask. Every list marks a book already on the person's shelf. Subject headings are plain words as Open Library spells them (\"Space "
                + "opera\", \"Military science fiction\", \"Moths\"). language narrows (eng, jpn); sort=new or sort=rating orders Open Library results. "
                + "Ratings and edition counts are shown as cues, never ranked on.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "search | subject | author | ndc | work | shelf");
        for (String k : List.of("search", "subject", "author", "ndc", "work", "shelf")) kind.withArray("enum").add(k);
        props.putObject("query").put("type", "string").put("description", "the words (search), the subject heading (subject), the author's name (author), the NDC prefix (ndc), the work key or ISBN (work), a subject to narrow to (shelf; none for all the liked subjects)");
        props.putObject("language").put("type", "string").put("description", "ISO 639-2: eng, jpn, fra… (search, subject, author)");
        props.putObject("sort").put("type", "string").put("description", "Open Library only: new | rating | editions; relevance when left out");
        props.putObject("limit").put("type", "integer").put("description", "how many rows, up to " + MOST + " (default 15)");
        p.putArray("required").add("kind");
        return p;
    }

    @Override public String execute(JsonNode args) throws Exception {
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: book_search has been called " + MOST_CALLS + " times in this run, which is its limit. Work with what was found.";
        String kind = args.path("kind").asText("search").toLowerCase(Locale.ROOT), query = args.path("query").asText("").strip();
        String language = args.path("language").asText("").strip().toLowerCase(Locale.ROOT), sort = args.path("sort").asText("").strip().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(15)));
        if (query.isEmpty() && !kind.equals("shelf")) return "ERROR: query is needed.";
        boolean ja = language.equals("jpn") || language.equals("ja") || (language.isEmpty() && Books.japanese(query));
        try {
            return switch (kind) {
                case "search" -> ja ? japanese(query, null, null, limit, "words \"" + query + "\"") : list(Books.openLibrary(query, null, null, lang(language), limit, olSort(sort)), "Open Library, words \"" + query + "\"");
                case "subject" -> ja ? japanese(null, query, null, limit, "件名 \"" + query + "\"") : list(Books.openLibrary(null, query, null, lang(language), limit, olSort(sort)), "Open Library, subject \"" + query + "\"");
                case "author" -> ja ? japanese(null, null, query, limit, "author \"" + query + "\"") : list(Books.openLibrary(null, null, query, lang(language), limit, olSort(sort)), "Open Library, author \"" + query + "\"");
                case "ndc" -> list(Books.ndl(null, null, query, null, limit), "NDL Search, NDC " + query);
                case "work" -> work(query);
                case "shelf" -> shelf(query, lang(language), limit);
                default -> "ERROR: kind is search, subject, author, ndc, work or shelf.";
            };
        } catch (Exception e) { return "ERROR: the catalogue did not answer (" + e.getMessage() + "). Try again later or with other words."; }
    }

    /** Japanese books: CiNii Books (件名, author, words, with how many libraries hold each) when the appid is set, and NDL Search beside it. */
    String japanese(String words, String subject, String author, int limit, String what) {
        StringBuilder b = new StringBuilder();
        if (Books.ciniiOn()) {
            try { b.append(list(Books.cinii(words, subject, author, "", limit), "CiNii Books, " + what)); }
            catch (Exception e) { b.append("CiNii Books did not answer (").append(e.getMessage()).append(").\n"); }
        } else b.append("CiNii Books is not set up (the setting cinii_appid; NII's developer registration gives one, free): 件名 and author search across the Japanese university libraries would be asked too.\n");
        try { b.append(list(Books.ndl(null, subject, null, words != null ? words : author, limit), "NDL Search, " + what)); }
        catch (Exception e) { b.append("NDL Search did not answer (").append(e.getMessage()).append(").\n"); }
        return b.toString();
    }

    static String lang(String l) { return switch (l) { case "en" -> "eng"; case "ja" -> "jpn"; case "fr" -> "fre"; case "de" -> "ger"; case "es" -> "spa"; case "zh" -> "chi"; default -> l; }; }
    static String olSort(String s) { return switch (s) { case "new" -> "new"; case "rating" -> "rating"; case "editions" -> "editions"; default -> ""; }; }

    String list(List<Books.Book> books, String where) {
        if (books.isEmpty()) return "Nothing in " + where + ". Try other words, a subject heading, or the author.";
        StringBuilder b = new StringBuilder(books.size() + " book(s) in " + where + "; only these may be recommended from this call, each cited by its record link:\n");
        for (Books.Book x : books) {
            returned(policy, x.title(), x.url(), x.authors(), x.year());
            b.append("- ").append(Shops.clean(x.title())).append(x.authors().isEmpty() ? "" : " — " + Shops.clean(String.join(", ", x.authors()))).append(x.year().isEmpty() ? "" : " (" + x.year() + ")");
            if (!x.publisher().isEmpty()) b.append("; ").append(Shops.clean(x.publisher()));
            if (!x.subjects().isEmpty()) b.append("; subjects: ").append(Shops.clean(String.join(", ", x.subjects().subList(0, Math.min(8, x.subjects().size())))));
            if (x.editions() > 0) b.append("; ").append(x.editions()).append(" edition(s)");
            if (x.ratings() > 0) b.append("; ").append(x.ratings()).append(" ratings").append(x.ratingAverage().isEmpty() ? "" : " averaging " + x.ratingAverage()).append(" (a cue, not a rank)");
            if (!x.isbns().isEmpty()) b.append("; ISBN ").append(String.join(" ", x.isbns()));
            if (!x.note().isEmpty()) b.append("; ").append(x.note());
            b.append(" — record: ").append(x.url());
            Shelf.Book have = Shelf.onShelf(x.title(), x.authors().isEmpty() ? "" : x.authors().get(0));
            if (have != null) b.append(" — ON THE PERSON'S SHELF").append(have.rating() > 0 ? " (rated " + Shelf.stars(have.stars()) + "★)" : " (unrated)");
            b.append('\n');
        }
        return b.toString();
    }

    /**
     * Candidates under the person's liked subjects: the catalogue asked for each (and once more for the newest), what the shelf already holds
     * left out, each book's fit counted — a machine-made pool for the writer to choose from, so that breadth does not depend on the model
     * remembering to search.
     */
    String shelf(String query, String language, int limit) throws Exception {
        if (Shelf.path() == null) return "No Calibre library is set (the setting calibre names its folder or metadata.db), so there is no shelf to start from. Ask by subject (kind=subject) instead.";
        List<Map.Entry<String, Integer>> liked = Shelf.likedSubjects(40);
        List<ShelfAuthors.Liked> authors = ShelfAuthors.liked();
        if (liked.isEmpty() && authors.isEmpty()) return "The shelf has no rated books, so there is no author or subject to start from. Rate a few books in Calibre, or ask by subject (kind=subject).";
        String q = Shelf.norm(query);
        List<String> words = new ArrayList<>(); for (String w : q.split(" ")) if (w.length() > 1) words.add(w);
        Set<String> likedNorm = new HashSet<>(); for (var e : liked) likedNorm.add(Shelf.norm(e.getKey()));
        StringBuilder b = new StringBuilder();
        // 1. the authors the person rates well: their works the shelf lacks, narrowed by the words — the lever that reaches the tail
        record Row(ShelfAuthors.Liked author, Books.Book book, int fit, List<String> matched) { }
        List<Row> rows = new ArrayList<>(); List<String> notes = new ArrayList<>();
        int fetched = 0, pending = 0, onShelf = 0, covered = 0;
        for (ShelfAuthors.Liked a : authors) {
            List<Books.Book> pool = ShelfAuthors.cached(a.norm());
            if (pool == null) {
                if (fetched >= 6) { pending++; continue; }
                try { pool = ShelfAuthors.fetch(a.name(), a.norm()); fetched++; } catch (Exception e) { notes.add(a.name() + ": the catalogue did not answer"); continue; }
            }
            covered++;
            for (Books.Book x : pool) {
                if (Shelf.onShelf(x.title(), a.name()) != null) { onShelf++; continue; }
                if (!words.isEmpty()) { String hay = " " + Shelf.norm(x.title() + " " + String.join(" ", x.subjects())) + " "; boolean all = true; for (String w : words) if (!hay.contains(" " + w + " ")) { all = false; break; } if (!all) continue; }
                List<String> matched = matched(x, likedNorm);
                rows.add(new Row(a, x, matched.size(), matched));
            }
        }
        rows.sort((p, r) -> p.author().liked() != r.author().liked() ? r.author().liked() - p.author().liked() : p.author().stars() != r.author().stars() ? Double.compare(r.author().stars(), p.author().stars()) : r.fit() != p.fit() ? r.fit() - p.fit() : r.book().year().compareTo(p.book().year()));
        int shown = 0; Map<String, Integer> perAuthor = new LinkedHashMap<>(); String lastAuthor = null;
        StringBuilder byAuthor = new StringBuilder();
        for (Row r : rows) {
            if (shown >= limit) break;
            int n = perAuthor.getOrDefault(r.author().norm(), 0);
            if (n >= 4) continue;   // four an author, so one prolific author does not fill the list
            perAuthor.put(r.author().norm(), n + 1); shown++;
            if (!r.author().norm().equals(lastAuthor)) { byAuthor.append(Shops.clean(r.author().name())).append(" (").append(r.author().liked()).append(" chosen").append(r.author().stars() > 0 ? ", rated " + Shelf.stars(r.author().stars()) + "★" : "").append("):\n"); lastAuthor = r.author().norm(); }
            Books.Book x = r.book();
            returned(policy, x.title(), x.url(), List.of(r.author().name()), x.year());
            byAuthor.append("- ").append(Shops.clean(x.title())).append(x.year().isEmpty() ? "" : " (" + x.year() + ")");
            if (!x.subjects().isEmpty()) byAuthor.append("; subjects: ").append(Shops.clean(String.join(", ", x.subjects().subList(0, Math.min(5, x.subjects().size())))));
            if (x.editions() > 0) byAuthor.append("; ").append(x.editions()).append(" edition(s)");
            if (r.fit() > 0) byAuthor.append("; fits ").append(r.fit()).append(": ").append(Shops.clean(String.join(", ", r.matched())));
            byAuthor.append(" — record: ").append(x.url()).append('\n');
        }
        b.append("By the authors the person rates well — ").append(shown).append(" candidate(s) from ").append(covered).append(" author(s) on file")
         .append(pending > 0 ? ", " + pending + " more to be read by the night housekeeping" : "").append(onShelf > 0 ? "; " + onShelf + " already on the shelf left out" : "")
         .append(words.isEmpty() ? "" : "; narrowed to \"" + Shops.clean(query.strip()) + "\"").append(". Only these may be recommended from this call, each cited by its record link; four an author at most.\n");
        if (shown == 0) b.append("(none: the authors' other works are on the shelf already, or none carries the words)\n"); else b.append(byAuthor);
        // 2. the liked subjects, for breadth: these reach the head of the catalogue, not the tail
        List<String> subjects = new ArrayList<>();
        if (!q.isEmpty()) { subjects.add(query.strip()); for (var e : liked) { String t = Shelf.norm(e.getKey()); if (!t.equals(q) && (t.contains(q) || q.contains(t)) && subjects.size() < 4) subjects.add(e.getKey()); } }
        else for (var e : liked) { if (subjects.size() >= 6) break; subjects.add(e.getKey()); }
        Map<String, Books.Book> pool = new LinkedHashMap<>(); int onShelf2 = 0;
        for (int i = 0; i < subjects.size(); i++) {
            for (String sort : i < 2 ? List.of("", "new") : List.of("")) {
                List<Books.Book> got;
                try { got = Books.openLibrary(null, subjects.get(i), null, language, sort.isEmpty() ? 40 : 20, sort); } catch (Exception e) { notes.add("\"" + subjects.get(i) + "\": the catalogue did not answer (" + e.getMessage() + ")"); continue; }
                for (Books.Book x : got) {
                    if (pool.containsKey(x.url())) continue;
                    if (Shelf.onShelf(x.title(), x.authors().isEmpty() ? "" : x.authors().get(0)) != null) { onShelf2++; pool.put(x.url(), null); continue; }
                    pool.put(x.url(), x);
                }
            }
        }
        record Fit(Books.Book book, int fit, List<String> matched) { }
        List<Fit> fits = new ArrayList<>();
        for (Books.Book x : pool.values()) { if (x == null) continue; List<String> m = matched(x, likedNorm); fits.add(new Fit(x, m.size(), m)); }
        fits.sort((x, y) -> y.fit() != x.fit() ? y.fit() - x.fit() : y.book().year().compareTo(x.book().year()));
        int breadth = Math.max(5, limit / 3), n = 0;
        b.append("Under the person's liked subjects (").append(Shops.clean(String.join("; ", subjects))).append("), for breadth — these are the catalogue's best-known, not its tail")
         .append(onShelf2 > 0 ? "; " + onShelf2 + " already on the shelf left out" : "").append("; fit = how many of the book's subjects are among the person's liked ones:\n");
        if (fits.isEmpty()) b.append("(nothing beyond what the shelf holds)\n");
        for (Fit f : fits) {
            if (n++ >= breadth) break;
            Books.Book x = f.book();
            returned(policy, x.title(), x.url(), x.authors(), x.year());
            b.append("- ").append(Shops.clean(x.title())).append(x.authors().isEmpty() ? "" : " — " + Shops.clean(String.join(", ", x.authors()))).append(x.year().isEmpty() ? "" : " (" + x.year() + ")");
            if (!x.subjects().isEmpty()) b.append("; subjects: ").append(Shops.clean(String.join(", ", x.subjects().subList(0, Math.min(5, x.subjects().size())))));
            if (x.editions() > 0) b.append("; ").append(x.editions()).append(" edition(s)");
            b.append("; fit ").append(f.fit()).append(f.matched().isEmpty() ? "" : ": " + Shops.clean(String.join(", ", f.matched()))).append(" — record: ").append(x.url()).append('\n');
        }
        if (!notes.isEmpty()) b.append("Note: ").append(String.join("; ", notes)).append(".\n");
        return b.toString();
    }

    /**
     * The liked subjects a book's subjects match: a subject part equal to a liked subject, or holding it as a whole phrase. Never the other
     * way round — a part "Fiction" had matched every liked tag with the word in it, fifteen at a time — and a liked subject of one word
     * does not count.
     */
    static List<String> matched(Books.Book b, Set<String> likedNorm) {
        List<String> matched = new ArrayList<>();
        for (String s : b.subjects()) for (String part : s.split(",")) {
            String p = Shelf.norm(part); if (p.isEmpty()) continue;
            for (String t : likedNorm) if (t.contains(" ") && (p.equals(t) || (" " + p + " ").contains(" " + t + " ")) && !matched.contains(t)) matched.add(t);
        }
        return matched;
    }

    /** The liked subjects the ask names, as the shelf spells them — the words a pool for this ask is narrowed to; "" when it names none. */
    public static String askedSubjects(String question) throws IOException {
        String q = " " + Shelf.norm(question) + " ";
        List<String> hits = new ArrayList<>();
        for (var e : Shelf.likedSubjects(80)) { String t = Shelf.norm(e.getKey()); if (t.contains(" ") && q.contains(" " + t + " ") && !hits.contains(t)) hits.add(t); }
        if (hits.isEmpty()) for (var e : Shelf.likedSubjects(80)) { String t = Shelf.norm(e.getKey()); if (!t.contains(" ") && t.length() >= 5 && q.contains(" " + t + " ") && !hits.contains(t)) hits.add(t); }
        return String.join(" ", hits.subList(0, Math.min(3, hits.size())));
    }

    String work(String query) throws Exception {
        String q = query.strip();
        Books.Work w;
        if (q.replaceAll("[^0-9Xx]", "").length() >= 10 && !q.toUpperCase(Locale.ROOT).contains("OL")) { w = Books.openBd(q); if (w == null) return "openBD has no record for ISBN " + q + "."; }
        else w = Books.work(q);
        returned(policy, w.title(), w.url());
        StringBuilder b = new StringBuilder("Record: " + Shops.clean(w.title()) + (w.firstPublished().isEmpty() ? "" : " (first published " + w.firstPublished() + ")") + " — " + w.url() + "\n");
        if (!w.description().isEmpty()) b.append("Description: ").append(Shops.clean(w.description())).append('\n');
        if (!w.subjects().isEmpty()) b.append("Subjects: ").append(Shops.clean(String.join("; ", w.subjects()))).append('\n');
        if (!w.places().isEmpty()) b.append("Places: ").append(Shops.clean(String.join(", ", w.places()))).append('\n');
        if (!w.times().isEmpty()) b.append("Times: ").append(Shops.clean(String.join(", ", w.times()))).append('\n');
        return b.toString();
    }
}
