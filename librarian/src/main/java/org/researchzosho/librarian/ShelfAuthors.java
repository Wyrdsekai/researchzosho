package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.tools.Books;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogues of the authors the person rates well, kept on this machine (0.6.0). Measured on the operator's shelf: a held-out fifth of
 * the liked books was covered 31 % by pools of the other four fifths' liked authors (29–38 % of the tail strata), against 6–7 % by subject
 * pools of any kind (0–4 % of the tail); 44 % of the liked books are by an author already rated well. Coverage grows with the number of
 * authors (12: 8 %, 100: 21 %, all 265: 31 %), too many to ask the catalogue for inside one call, so each author's works are fetched once
 * and kept for two weeks, the night housekeeping reading the missing and stale ones within a time budget. {@code book_search kind=shelf}
 * reads from here first.
 */
public final class ShelfAuthors {
    private ShelfAuthors() { }

    static final ObjectMapper M = new ObjectMapper();
    /** How long a pool is good for. */
    public static final Duration FRESH = Duration.ofDays(14);
    /** An author with two or more books the person went for: as the shelf writes the name, normalised, how many, and the mean of the stars the person gave them (0 when none). */
    public record Liked(String name, String norm, int liked, double stars) { }

    /** A test's own folder. */
    static volatile Path override;
    static Path dir() { return override != null ? override : Config.home().resolve("shelf").resolve("authors"); }
    static Path file(String norm) { return dir().resolve(norm.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "") + ".json"); }

    /** The authors rated well, most liked first, then by stars, then by name. */
    public static List<Liked> liked() throws IOException {
        Map<String, Object[]> m = new HashMap<>();
        for (Shelf.Book b : Shelf.books()) {
            if (!b.liked()) continue;
            for (String a : b.authors().split(" & ")) {
                String name = a.strip(), norm = Shelf.norm(name);
                if (norm.isEmpty() || norm.equals("unknown") || norm.equals("anonymous") || norm.equals("various")) continue;
                Object[] v = m.computeIfAbsent(norm, k -> new Object[]{name, 0, 0, 0});
                v[1] = (int) v[1] + 1;
                if (b.ratingGiven()) { v[2] = (int) v[2] + b.rating(); v[3] = (int) v[3] + 1; }
            }
        }
        List<Liked> out = new ArrayList<>();
        m.forEach((norm, v) -> { if ((int) v[1] >= 2) out.add(new Liked((String) v[0], norm, (int) v[1], (int) v[3] == 0 ? 0 : (int) v[2] / 2.0 / (int) v[3])); });
        out.sort(Comparator.comparingInt(Liked::liked).reversed().thenComparing(Comparator.comparingDouble(Liked::stars).reversed()).thenComparing(Liked::name));
        return out;
    }

    /** The author's works as kept, or null when none is kept or it is stale. */
    public static List<Books.Book> cached(String norm) {
        Path f = file(norm);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode d = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            if (System.currentTimeMillis() - d.path("fetchedAt").asLong(0) > FRESH.toMillis()) return null;
            List<Books.Book> out = new ArrayList<>();
            for (JsonNode w : d.path("works")) out.add(M.treeToValue(w, Books.Book.class));
            return out;
        } catch (Exception e) { return null; }
    }

    /** The author's works from the catalogue, kept for next time; up to a hundred, every language. */
    public static List<Books.Book> fetch(String name, String norm) throws IOException {
        List<Books.Book> works = Books.openLibrary(null, null, name, "", 100, "");
        Files.createDirectories(dir());
        ObjectNode d = M.createObjectNode(); d.put("author", name); d.put("fetchedAt", System.currentTimeMillis());
        ArrayNode arr = d.putArray("works"); for (Books.Book b : works) arr.add(M.valueToTree(b));
        Files.writeString(file(norm), M.writerWithDefaultPrettyPrinter().writeValueAsString(d), StandardCharsets.UTF_8);
        return works;
    }

    /** How many liked authors have a kept pool, and how many do not. */
    public static int[] onFile(List<Liked> authors) {
        int have = 0; for (Liked a : authors) if (cached(a.norm()) != null) have++;
        return new int[]{have, authors.size() - have};
    }

    /** The night housekeeping: the missing and stale pools, most liked authors first, within the time given. A sentence for the log. */
    public static String nightly(long budgetMillis) throws IOException {
        if (Shelf.path() == null) return "no Calibre library is set";
        List<Liked> authors = liked();
        long until = System.currentTimeMillis() + budgetMillis;
        int read = 0, failed = 0, left = 0;
        for (Liked a : authors) {
            if (cached(a.norm()) != null) continue;
            if (System.currentTimeMillis() > until) { left++; continue; }
            try { fetch(a.name(), a.norm()); read++; } catch (Exception e) { failed++; }
            try { Thread.sleep(400); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        int[] now = onFile(authors);
        return String.format(Locale.ROOT, "%d author(s) the person went for; %d read tonight, %d on file, %d to go%s", authors.size(), read, now[0], now[1], failed == 0 ? "" : " (" + failed + " did not answer)");
    }
}
