package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Shelf;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.LikeX;
import org.researchzosho.librarian.dna.Prefs;

import java.util.List;
import java.util.Locale;

/**
 * {@code my_shelf}: the person's own shelf, follows and purchases for a run that recommends — the profile as counts (the model writes the
 * prose), whether a title is already here, and what was liked on a subject. A reference, never a requirement: with no Calibre library set
 * it says so and the run goes on with what the person asked for.
 */
public final class MyShelfTool implements Tool {

    private final LibraryStore store;

    public MyShelfTool(LibraryStore store) { this.store = store; }

    @Override public String name() { return "my_shelf"; }

    @Override public String description() {
        return "The person's own shelf, from their Calibre library, the podcasts they follow and what they bought. kind=profile: what they went for — the books "
                + "they added one by one (a day of fifty or more additions is an import that came with the files: holdings, not taste) and any they rated themselves; "
                + "the subject mix among those against the whole shelf (tilt > 1 means chosen more than merely owned), the authors they went for more than once, the "
                + "decades, languages and series, sample titles they loved, liked and disliked, what was added lately, the podcasts followed with their feed addresses (for podcast_search kind=similar), the shops bought "
                + "from. A rating counts only when the person gave it themselves; imported stars are set aside. kind=have with title (and author): whether a book is already "
                + "on the shelf, in the purchase ledger or on a holdings list — call it for every candidate before recommending it. kind=liked with words: "
                + "the books the person went for on a subject or by an author, best first (stars narrows to what they rated themselves), to say \"more like these\". kind=like with title: the books on the shelf like that one by their "
                + "DNA — subject first, then kind, audience, demand, mood and pace, the author's own works set aside, under the person's preferences file — each row with the reason per dimension; the title must be a book on the shelf.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "profile | have | liked");
        for (String k : List.of("profile", "have", "liked", "like")) kind.withArray("enum").add(k);
        props.putObject("title").put("type", "string").put("description", "the book's title, or an ISBN (have); the book on the shelf to be like (like)");
        props.putObject("author").put("type", "string").put("description", "the author, to tell books of one title apart (have)");
        props.putObject("words").put("type", "string").put("description", "a subject, a tag, an author or title words (liked); none for the best-rated of all");
        props.putObject("stars").put("type", "number").put("description", "liked: only the books the person rated at least this themselves; left out, every book they went for");
        props.putObject("limit").put("type", "integer").put("description", "how many rows (liked; default 20, up to 60)");
        p.putArray("required").add("kind");
        return p;
    }

    @Override public String execute(JsonNode args) throws Exception {
        String kind = args.path("kind").asText("profile").toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "profile" -> { String brief = Prefs.load(Dna.BOOK).brief(); yield profile(Shelf.profile(store)) + (brief.isEmpty() ? "" : "\nThe person's preferences file: " + brief + "\n"); }
            case "like" -> { if (args.path("title").asText("").isBlank()) yield "ERROR: title is needed: a book on the shelf.";
                LikeX.OnShelf o = LikeX.onShelf(args.path("title").asText(), args.path("author").asText(""), Math.max(1, Math.min(40, args.path("limit").asInt(10))), true);
                yield o.ok() ? Shops.clean(LikeX.text(o.result())) : Shops.clean(o.message()); }
            case "have" -> { List<Shelf.Have> h = Shelf.have(store, args.path("title").asText(""), args.path("author").asText(""));
                if (args.path("title").asText("").isBlank()) yield "ERROR: title is needed.";
                if (h.isEmpty()) yield "Not on the shelf, not in the purchase ledger, not on a holdings list: \"" + Shops.clean(args.path("title").asText()) + "\"" + (Shelf.path() == null ? " (no Calibre library is set; only the ledger and the lists were checked)" : "") + ".";
                StringBuilder b = new StringBuilder("Already here (" + h.size() + "):\n"); for (Shelf.Have x : h) b.append("- ").append(x.where()).append(": ").append(Shops.clean(x.detail())).append('\n'); yield b.toString(); }
            case "liked" -> { int limit = Math.max(1, Math.min(60, args.path("limit").asInt(20)));
                List<Shelf.Book> l = Shelf.liked(args.path("words").asText(""), args.path("stars").asDouble(0), limit);
                if (Shelf.path() == null) yield "No Calibre library is set (the setting calibre names its folder or metadata.db); the run goes on from what the person asked for.";
                if (l.isEmpty()) yield "No book the person went for carries those words. Try one word, a tag as the shelf spells it, or the author's surname.";
                StringBuilder b = new StringBuilder(l.size() + " book(s) the person went for, best first" + (args.path("words").asText("").isBlank() ? "" : " for \"" + Shops.clean(args.path("words").asText()) + "\"") + ":\n"); for (Shelf.Book x : l) b.append("- ").append(Shelf.line(x)).append('\n'); yield b.toString(); }
            default -> "ERROR: kind is profile, have or liked.";
        };
    }

    /** The profile as the model reads it. */
    public static String profile(ObjectNode p) {
        if (!p.path("calibre").asBoolean()) {
            StringBuilder b = new StringBuilder("No Calibre library is set (the setting calibre names its folder or metadata.db), so there is no shelf to read. ");
            b.append(follows(p)); return b.toString();
        }
        StringBuilder b = new StringBuilder();
        b.append("Shelf: ").append(p.path("books").asInt()).append(" books, ").append(p.path("chosen").asInt()).append(" added by the person one by one (their picks; the profile), ")
         .append(p.path("bulk").asInt()).append(" from bulk imports (holdings, not taste). Ratings: ").append(p.path("ratings_given").asInt()).append(" given by the person")
         .append(p.path("ratings_imported").asInt() > 0 ? ", " + p.path("ratings_imported").asInt() + " came in with the files and are not counted" : "").append(". ");
        var it = p.path("stars").fields(); boolean first = true; if (it.hasNext()) b.append("Stars given: "); while (it.hasNext()) { var e = it.next(); b.append(first ? "" : ", ").append(e.getKey().replace(".0", "")).append("★ ").append(e.getValue().asInt()); first = false; }
        b.append(p.path("stars").isEmpty() ? "" : ". ").append(p.path("liked").asInt()).append(" books count as what the person went for (chosen, or rated well by them)");
        b.append(".\nSubjects among the books the person went for (share; tilt against the whole shelf, >1 = chosen more than merely owned): ");
        first = true; for (JsonNode s : p.path("subjects_liked")) { b.append(first ? "" : "; ").append(Shops.clean(s.path("tag").asText())).append(' ').append(s.path("share").asText()).append(" (").append(s.path("liked").asInt()).append(s.has("tilt") ? ", tilt " + s.path("tilt").asText() : "").append(')'); first = false; }
        b.append(".\nThe whole shelf's subjects: "); first = true; for (JsonNode s : p.path("subjects_shelf")) { b.append(first ? "" : "; ").append(Shops.clean(s.path("tag").asText())).append(' ').append(s.path("share").asText()); first = false; }
        b.append(".\nAuthors the person went for more than once: "); first = true; for (JsonNode a : p.path("authors_liked")) { b.append(first ? "" : "; ").append(Shops.clean(a.path("author").asText())).append(" (").append(a.path("liked").asInt()).append(a.path("stars").asText().equals("0") ? "" : ", rated " + a.path("stars").asText() + "★").append(")"); first = false; }
        b.append(p.path("authors_liked").isEmpty() ? "none" : "").append(".\nDecades of the books the person went for: "); first = true; it = p.path("decades_liked").fields(); while (it.hasNext()) { var e = it.next(); b.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue().asInt()); first = false; }
        b.append(".\nLanguages: "); first = true; it = p.path("languages").fields(); while (it.hasNext()) { var e = it.next(); b.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue().asInt()); first = false; }
        if (!p.path("series_liked").isEmpty()) { b.append(".\nSeries liked: "); first = true; for (JsonNode s : p.path("series_liked")) { b.append(first ? "" : "; ").append(Shops.clean(s.asText())); first = false; } }
        b.append(".\n"); list(b, "Chosen by the person, a spread across the years they were added", p.path("chosen_sample")); list(b, "Rated 4.5★ and up by the person", p.path("loved")); list(b, "Rated 4★ by the person", p.path("liked_sample")); list(b, "Rated low by the person", p.path("disliked_sample")); list(b, "Added lately", p.path("added_lately"));
        b.append(follows(p));
        return b.toString();
    }

    static void list(StringBuilder b, String head, JsonNode arr) { if (arr.isEmpty()) return; b.append(head).append(":\n"); for (JsonNode x : arr) b.append("- ").append(Shops.clean(x.asText())).append('\n'); }

    static String follows(ObjectNode p) {
        StringBuilder b = new StringBuilder();
        if (!p.path("podcasts_followed").isEmpty()) { b.append("Podcasts followed (each with its feed address; podcast_search kind=similar with a feed finds shows like it): "); boolean f = true; for (JsonNode x : p.path("podcasts_followed")) { b.append(f ? "" : "; ").append(Shops.clean(x.asText())); f = false; } b.append(".\n"); }
        if (!p.path("searches_watched").isEmpty()) { b.append("Searches watched: "); boolean f = true; for (JsonNode x : p.path("searches_watched")) { b.append(f ? "" : "; ").append(Shops.clean(x.asText())); f = false; } b.append(".\n"); }
        JsonNode o = p.path("owned");
        if (o.path("lines").asInt() > 0) {
            b.append("Purchases on record: ").append(o.path("purchases").asInt()).append(" from "); boolean f = true; var it = o.path("shops").fields(); while (it.hasNext()) { var e = it.next(); b.append(f ? "" : ", ").append(Shops.clean(e.getKey())).append(" (").append(e.getValue().asInt()).append(')'); f = false; }
            b.append(". Bought lately:\n"); for (JsonNode x : o.path("recent")) b.append("- ").append(Shops.clean(x.asText())).append('\n');
        }
        if (p.path("holdings").asInt() > 0) b.append("Holdings lists: ").append(p.path("holdings").asInt()).append(" entries (the holdings tool reads them).\n");
        return b.toString();
    }
}
