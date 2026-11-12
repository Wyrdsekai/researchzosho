package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.Pool;
import org.researchzosho.librarian.dna.Prefs;

import java.io.IOException;
import java.util.Map;

/** The books page (0.7.0): the person's shelf as the library reads it, the books they went for, and the books reports, as a report of its own shape. */
public final class BooksPage {
    public static final String PATH = "/books";

    private BooksPage() { }

    /** One line of state for the shelf, in English. */
    static String stateLine(ObjectNode p) { return stateLine(p, Chrome.ENGLISH); }

    /** One line of state for the shelf, on Past's row and at the page's head, in {@code c}'s language: each count after its noun, right for every number. */
    static String stateLine(ObjectNode p, Chrome c) {
        if (!p.path("calibre").asBoolean()) return c.t("No Calibre library is set, so there is no shelf to read; the setting calibre names its folder or its metadata.db.");
        return c.t("Books on the shelf: {0}. Chosen one by one: {1}. You went for: {2}. Ratings given by you: {3}.", c.number(p.path("books").asInt()), c.number(p.path("chosen").asInt()), c.number(p.path("liked").asInt()), c.number(p.path("ratings_given").asInt()));
    }

    public static String body(LibraryStore store, Patrons.Patron patron) throws IOException { return body(store, patron, Map.of()); }

    public static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode p = Shelf.profile(store);
        StringBuilder b = new StringBuilder("<p class=\"lede\">").append(Pages.t("What you liked, read against the long tail; the next one, with why. A books question in the chat is a conversation before it is a run.")).append("</p>");
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(Pages.esc(note)).append("</p>");
        b.append("<p class=\"state").append(p.path("calibre").asBoolean() ? " done" : "").append("\">").append(Pages.esc(stateLine(p, Chrome.now()))).append("</p>");
        if (p.path("calibre").asBoolean()) {
            if (!p.path("subjects_liked").isEmpty()) {
                b.append("<h2>").append(Pages.t("The subjects you go for")).append("</h2><p class=\"k\">").append(Pages.t("Among the books you chose or rated well; the tilt says how much more than merely owned.")).append("</p><ul class=\"tight\">");
                int n = 0; for (JsonNode s : p.path("subjects_liked")) { if (n++ >= 12) break; b.append("<li>").append(Pages.esc(s.path("tag").asText())).append(" <span class=\"k\">").append(Pages.esc(s.path("share").asText())).append(s.has("tilt") ? " · " + Pages.t("tilt {0}", Pages.esc(s.path("tilt").asText())) : "").append("</span></li>"); }
                b.append("</ul>");
            }
            list(b, Pages.t("Books you loved"), p.path("loved")); list(b, Pages.t("Books you liked"), p.path("liked_sample")); list(b, Pages.t("Books you chose one by one"), p.path("chosen_sample")); list(b, Pages.t("Added lately"), p.path("added_lately"));
        }
        if (owner(store, patron)) b.append(forms(store, patron));
        b.append(Pages.rowsSection(store, patron, "books", Pages.t("Books reports"), Pages.t("None yet. Ask in the chat which book on a subject, or what to read next: the report it files is the first row here.")));
        return b.toString();
    }

    /** Whether the reactions, the preferences and the DNA are this patron's to change: the person at the terminal, or the pages while they are open to everyone. */
    static boolean owner(LibraryStore store, Patrons.Patron patron) { return patron.person() || (patron.web() && !WebAccess.signInRequired()); }

    /** The page's forms (2026-11-07): a reaction to a book, the preferences file shown and changed, the DNA's state; each what the command line and the chat do. */
    static String forms(LibraryStore store, Patrons.Patron patron) throws IOException {
        StringBuilder b = new StringBuilder();
        LibraryProtocol lp = new LibraryProtocol(store);
        b.append("<h2>").append(Pages.t("Say what you thought of a book")).append("</h2><form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"react\"><select name=\"reaction\"><option value=\"like\">").append(Pages.t("liked")).append("</option><option value=\"dislike\">").append(Pages.t("disliked")).append("</option><option value=\"seen\">").append(Pages.t("seen")).append("</option><option value=\"own\">").append(Pages.t("own")).append("</option></select> ")
         .append("<input name=\"title\" size=\"30\" placeholder=\"").append(Pages.esc(Pages.t("the title"))).append("\" required> <input name=\"author\" size=\"18\" placeholder=\"").append(Pages.esc(Pages.t("the author, if it helps"))).append("\"> <button class=\"btn\">").append(Pages.t("Write it down")).append("</button></form>");
        b.append("<p class=\"k\">").append(Pages.t("A reaction teaches the preferences file; at the terminal: {0}.", "<code>researchzosho react like|dislike|seen|own &lt;title&gt;</code>")).append("</p>");
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); a.put("op", "show");
        ObjectNode prefs;
        try { prefs = lp.prefs(a); } catch (ProtocolError e) { prefs = null; }
        b.append("<h2>").append(Pages.t("Your preferences")).append("</h2>");
        if (prefs != null) { b.append("<p class=\"k\">").append(Pages.esc(prefsSaid(Prefs.load(Dna.BOOK)))).append("</p>"); if (!prefs.path("text").asText("").isBlank()) b.append("<pre>").append(Pages.esc(prefs.path("text").asText().strip())).append("</pre>"); }
        b.append("<form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"prefs-set\"><label>").append(Pages.t("Set")).append(" <input name=\"name\" size=\"16\" placeholder=\"weight.subject\" required></label> <label>").append(Pages.t("to")).append(" <input name=\"value\" size=\"24\" required></label> <button class=\"btn\">").append(Pages.t("Set")).append("</button></form> ")
         .append("<form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"prefs-unset\"><label>").append(Pages.t("Unset")).append(" <input name=\"name\" size=\"16\" required></label> <button class=\"quiet\">").append(Pages.t("Unset")).append("</button></form> ")
         .append("<form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"prefs-draft\"><button class=\"quiet\">").append(Pages.t("Draft it from my shelf")).append("</button></form> ")
         .append("<form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"prefs-learn\"><button class=\"quiet\">").append(Pages.t("Learn from my reactions")).append("</button></form>");
        b.append("<h2>").append(Pages.t("The DNA")).append("</h2>");
        b.append("<pre>").append(Pages.esc(Pool.status(Dna.BOOK, Chrome.now()) + "\n" + Pool.status(Dna.PODCAST, Chrome.now()))).append("</pre>");   // what library_dna's status says, in the person's language
        b.append("<form method=\"post\" action=\"/books\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"dna-records\"><button class=\"btn\">").append(Pages.t("Read the catalogue's records for my books now")).append("</button> <span class=\"k\">").append(Pages.t("a few a second, the labelled ones first; at the terminal: {0}", "<code>researchzosho dna records</code>")).append("</span></form>");
        return b.toString();
    }

    static final ObjectMapper M = new ObjectMapper();

    /** A form of the page: the note shown back. */
    public static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        if (!owner(store, patron)) return Pages.t("The reactions and the preferences are the owner's own.");
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron));
        String op = form.getOrDefault("op", "");
        try {
            switch (op) {
                case "react" -> { a.put("reaction", form.getOrDefault("reaction", "")).put("title", form.getOrDefault("title", "").strip()); String by = form.getOrDefault("author", "").strip(); if (!by.isEmpty()) a.put("author", by); return lp.react(a).path("summary").asText(); }
                case "prefs-set" -> { a.put("op", "set").put("name", form.getOrDefault("name", "").strip()).put("value", form.getOrDefault("value", "").strip()); return lp.prefs(a).path("summary").asText(); }
                case "prefs-unset" -> { a.put("op", "unset").put("name", form.getOrDefault("name", "").strip()); return lp.prefs(a).path("summary").asText(); }
                case "prefs-draft", "prefs-learn" -> { a.put("op", op.substring(6)); return lp.prefs(a).path("summary").asText(); }
                case "dna-records" -> { a.put("op", "records"); return lp.dna(a).path("summary").asText(Pages.t("The catalogue's records are being read.")); }
                default -> { return Pages.t("That is not something the Books page does."); }
            }
        } catch (ProtocolError e) { return e.getMessage(); }
    }

    /** What the protocol's show says of the preferences file, in the person's language: there is none yet, or how many lines it has. */
    static String prefsSaid(Prefs p) {
        if (!p.exists()) return Pages.t("No preferences file yet; the defaults apply.");
        return Pages.n(p.lines().size(), "The preferences file for books, {0} line.", "The preferences file for books, {0} lines.");
    }

    static void list(StringBuilder b, String head, JsonNode arr) {
        if (arr.isEmpty()) return;
        b.append("<h2>").append(head).append("</h2><ul class=\"tight\">");
        for (JsonNode x : arr) b.append("<li>").append(Pages.esc(x.asText())).append("</li>");
        b.append("</ul>");
    }
}
