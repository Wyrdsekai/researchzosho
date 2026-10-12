package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Shelf;
import org.researchzosho.tools.Books;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The record layer's catalogue fill (0.6.0): what Open Library says of a book on the shelf — its subject headings, its work key, how many
 * editions and ratings it has, its first year — joined to the book's document by ISBN first, then by title and author, each value said
 * to come from the catalogue. The shelf's own tags are coarse (shop codes, "General"); the catalogue's subjects are what "like X"
 * compares first and what the judge found alike, so the labelled books are filled first. A book the catalogue does not know is marked
 * so it is not asked for again for a month. The offline join over the monthly dumps stays a tool of the measurement box; this is the
 * path that ships: a few requests a second, within the night's budget, cached in the document.
 */
public final class RecordFill {
    private RecordFill() { }

    /** How long a miss stands before the catalogue is asked again. */
    static final long RETRY_MISS_MS = 30L * 86_400_000L;

    /** Whether the document's record has been filled from the catalogue (or the catalogue said it has nothing, lately). */
    public static boolean filled(ObjectNode doc) {
        ObjectNode r = (ObjectNode) doc.path("record");
        if (r.path("sources").has("openlibrary")) return true;
        String miss = r.path("sources").path("openlibrary_miss").asText("");
        if (miss.isEmpty()) return false;
        try { return Instant.parse(miss).toEpochMilli() > System.currentTimeMillis() - RETRY_MISS_MS; } catch (Exception e) { return false; }
    }

    /** The catalogue's record for the document's book, joined into it: true when the catalogue knew it. */
    public static boolean fill(ObjectNode doc) throws IOException {
        ObjectNode r = (ObjectNode) doc.path("record");
        String isbn = r.path("isbn").asText("").replaceAll("[^0-9Xx]", ""), title = r.path("title").asText(""), authors = r.path("authors").asText("");
        Books.Book hit = null;
        if (isbn.length() >= 10) hit = first(Books.openLibrary("isbn:" + isbn, null, null, "", 3, ""), title, "");
        if (hit == null && !title.isEmpty()) {
            String author = authors.split(" & ")[0].strip();
            hit = first(Books.openLibrary(beforeSubtitle(title), null, author.isEmpty() || author.equalsIgnoreCase("unknown") ? null : author, "", 5, ""), title, author);
        }
        ObjectNode sources = r.has("sources") && r.path("sources").isObject() ? (ObjectNode) r.path("sources") : r.putObject("sources");
        if (hit == null) { sources.put("openlibrary_miss", Instant.now().toString()); return false; }
        ArrayNode subjects = r.putArray("subjects"); for (String s : hit.subjects()) subjects.add(s);
        r.put("ol_key", hit.url().replaceFirst("^.*/works/", "/works/"));
        r.put("edition_count", hit.editions()); r.put("ratings_count", hit.ratings());
        if (!hit.ratingAverage().isEmpty()) r.put("ratings_average", hit.ratingAverage());
        if (!hit.year().isEmpty()) r.put("first_published", hit.year());
        if (r.path("isbn").asText("").isEmpty() && !hit.isbns().isEmpty()) r.put("isbn", hit.isbns().get(0));
        sources.remove("openlibrary_miss"); sources.put("openlibrary", Instant.now().toString());
        return true;
    }

    /** The title before its subtitle, as the shelf spells it (the catalogue is asked in the book's own words, not the normalised ones). */
    static String beforeSubtitle(String title) {
        String t = title.split("\\s+[:—–]\\s+|:\\s+|\\s+-\\s+|\\s*\\(")[0].strip();
        return t.isEmpty() ? title.strip() : t;
    }

    /** The first result whose title agrees with the book's (the whole title or the part before the subtitle), and whose author does when one is given. */
    static Books.Book first(List<Books.Book> hits, String title, String author) {
        String want = Shelf.norm(title), wantShort = Shelf.shortTitle(title);
        for (Books.Book b : hits) {
            String t = Shelf.norm(b.title()), ts = Shelf.shortTitle(b.title());
            boolean titleOk = want.isEmpty() || t.equals(want) || (!wantShort.isEmpty() && (ts.equals(wantShort) || t.equals(wantShort)));
            boolean authorOk = author == null || author.isEmpty() || b.authors().isEmpty() || b.authors().stream().anyMatch(a -> LikeX.sameAuthor(Shelf.norm(author), a));
            if (titleOk && authorOk) return b;
        }
        return null;
    }

    /** The night's step: the labelled documents first, then the rest of the shelf's, filled within the budget at a few requests a second. A sentence. */
    public static String nightly(long budgetMillis, BooleanSupplier stop) throws IOException {
        long until = System.currentTimeMillis() + budgetMillis;
        int found = 0, missed = 0, failed = 0, carry = 0;
        List<ObjectNode> labelled = Dna.labelled(Dna.BOOK);
        Set<String> done = new HashSet<>();
        for (List<ObjectNode> batch : List.of(labelled, Dna.all(Dna.BOOK))) {
            for (ObjectNode d : batch) {
                if (!done.add(d.path("key").asText())) continue;
                if (d.path("record").path("sources").has("openlibrary")) { carry++; continue; }
                if (filled(d)) continue;   // a miss the catalogue gave lately: not asked again this month
                if (System.currentTimeMillis() > until || stop.getAsBoolean()) break;
                try { if (fill(d)) { found++; carry++; } else missed++; Dna.write(d); } catch (IOException e) { failed++; if (failed >= 5) break; }
                try { Thread.sleep(350); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        int[] c = Dna.counts(Dna.BOOK);
        return String.format(Locale.ROOT, "%d of the %d documents carry the catalogue's record; tonight %d filled, %d not in the catalogue%s", carry, c[0], found, missed, failed > 0 ? ", " + failed + " requests failed" : "");
    }
}
