package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The Databases page ({@code /db}): the owner's databases the library may read, each with its kind and address; a database's schema, tables,
 * columns and sample rows; and one SELECT run against it, its rows shown and saved as a locator a claim can cite. Read-only, the owner's
 * alone, as the command line and the chat have it; the pages lacked it until 2026-11-07. Design: docs/DESIGN_DATABASE_ACCESS.md.
 */
final class DbPage {
    private DbPage() { }
    static final String PATH = "/db";
    private static final ObjectMapper M = new ObjectMapper();

    static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); return a; }

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q, String result) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p class=\"k\">").append(t("Your databases, read-only: what each holds, and one SELECT at a time, its rows saved under a locator a claim can cite. A database is added at the terminal: {0}.", "<code>researchzosho db add &lt;name&gt; &lt;address&gt;</code>")).append("</p>\n");
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode list;
        try { list = p.db(args(patron).put("op", "list")); } catch (ProtocolError e) { return b.append("<p>").append(esc(e.getMessage())).append("</p>").toString(); }
        if (!list.path("warning").asText("").isEmpty()) b.append("<p class=\"note\">").append(esc(list.path("warning").asText())).append("</p>");
        if (list.path("databases").isEmpty()) return b.append("<p>").append(t("No database has been added yet.")).append("</p>").toString();
        String chosen = q.getOrDefault("database", "").strip();
        b.append("<table><tr><th>").append(t("name")).append("</th><th>").append(t("kind")).append("</th><th>").append(t("address")).append("</th><th></th></tr>\n");
        for (JsonNode d : list.path("databases")) b.append("<tr><td>").append(esc(d.path("name").asText())).append("</td><td>").append(esc(d.path("kind").asText())).append("</td><td><code>").append(esc(d.path("address").asText(""))).append("</code></td><td><a href=\"/db?database=").append(enc(d.path("name").asText())).append("\">").append(t("schema")).append("</a></td></tr>\n");
        b.append("</table>\n");
        if (!chosen.isEmpty()) {
            try {
                JsonNode s = p.db(args(patron).put("op", "schema").put("database", chosen));
                b.append("<h2>").append(t("The schema of {0}", esc(chosen))).append("</h2><pre>").append(esc(s.path("schema").asText(""))).append("</pre>");
            } catch (ProtocolError e) { b.append("<p class=\"err\">").append(esc(e.getMessage())).append("</p>"); }
        }
        if (result != null) b.append(result);
        b.append("<h2>").append(t("A query")).append("</h2><form method=\"post\" action=\"/db\" class=\"stack\"><label>").append(t("Database")).append(" <select name=\"database\">");
        for (JsonNode d : list.path("databases")) b.append("<option value=\"").append(esc(d.path("name").asText())).append("\"").append(d.path("name").asText().equals(chosen) ? " selected" : "").append(">").append(esc(d.path("name").asText())).append("</option>");
        b.append("</select></label><label>").append(t("One SELECT (or a MongoDB filter or pipeline)")).append("<br><textarea name=\"sql\" rows=\"3\" required>").append(esc(q.getOrDefault("sql", ""))).append("</textarea></label>")
         .append("<label>").append(t("Rows at most")).append(" <input name=\"limit\" size=\"5\" value=\"50\"></label><button class=\"btn primary\">").append(t("Run it")).append("</button></form>\n");
        return b.toString();
    }

    /** The rows a query returned, as a section of the page. */
    static String rows(JsonNode r) {
        StringBuilder b = new StringBuilder("<h2>").append(t("Rows")).append("</h2><p class=\"k\">").append(esc(r.path("summary").asText(""))).append("</p>");
        if (r.path("columns").size() > 0) {
            b.append("<div class=\"tablewrap\"><table><tr>");
            for (JsonNode c : r.path("columns")) b.append("<th>").append(esc(c.asText())).append("</th>");
            b.append("</tr>\n");
            for (JsonNode row : r.path("rows")) { b.append("<tr>"); for (JsonNode v : row) b.append("<td>").append(esc(v.asText(""))).append("</td>"); b.append("</tr>\n"); }
            b.append("</table></div>");
        } else if (!r.path("text").asText("").isEmpty()) b.append("<pre>").append(esc(r.path("text").asText())).append("</pre>");
        if (!r.path("saved").asText("").isEmpty()) b.append("<p class=\"k\">").append(t("Saved as {0}, which a claim can cite.", "<code>" + esc(r.path("saved").asText()) + "</code>")).append("</p>");
        return b.toString();
    }

    /** The query form posted: the rows, or the refusal. */
    record Done(String note, String section) { }

    static Done post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String database = form.getOrDefault("database", "").strip(), sql = form.getOrDefault("sql", "").strip();
        int limit = form.getOrDefault("limit", "").matches("\\d+") ? Integer.parseInt(form.get("limit")) : 0;
        ObjectNode a = args(patron).put("op", "query").put("database", database).put("limit", limit);
        if (sql.startsWith("{") || sql.startsWith("[")) { if (sql.startsWith("[")) a.put("pipeline", sql); else a.put("filter", sql); } else a.put("sql", sql);
        try { return new Done(null, rows(new LibraryProtocol(store).db(a))); }
        catch (ProtocolError e) { return new Done(e.getMessage(), null); }
    }
}
