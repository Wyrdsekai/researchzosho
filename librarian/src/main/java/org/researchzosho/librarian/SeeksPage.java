package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.n;
import static org.researchzosho.librarian.Pages.t;

/**
 * The Seeks page ({@code /seeks}): the same question researched several times apart, each seek on its own copy of the shelves, listed by
 * group with each seek's run and report; a button merges the seeks that landed into one report, another drops a group. The chat and the
 * command line had both; the pages had neither until 2026-11-07.
 */
final class SeeksPage {
    private SeeksPage() { }
    static final String PATH = "/seeks";
    private static final ObjectMapper M = new ObjectMapper();

    static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); return a; }

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer = Pages.mayWrite(store, patron);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p class=\"k\">").append(t("A seek is one question researched several times apart, each run on its own copy of the shelves, so that what they agree on can be told from what one of them found alone. Send the same question twice or more with a seek name, from the chat or the command line ({0}); once two have landed, merge them into one report: agreed claims are filed, contradictions go to the open questions as disputed, what only one seek found is listed.", "<code>researchzosho research ask \"…\" --seek &lt;group&gt;</code>")).append("</p>\n");
        JsonNode r = new LibraryProtocol(store).seeks(args(patron).put("op", "list"));
        if (r.path("groups").isEmpty()) { b.append("<p>").append(t("No seeks yet.")).append("</p>"); return b.toString(); }
        for (JsonNode g : r.path("groups")) {
            String group = g.path("group").asText();
            int landed = 0; for (JsonNode s : g.path("seeks")) if (!s.path("investigation").asText("").isEmpty()) landed++;
            b.append("<div class=\"card\"><h3>").append(esc(group)).append("</h3><p>").append(esc(g.path("question").asText(""))).append("</p>");
            b.append("<table><tr><th>").append(t("run")).append("</th><th>").append(t("model")).append("</th><th>").append(t("sources")).append("</th><th>").append(t("state")).append("</th><th>").append(t("report")).append("</th></tr>\n");
            for (JsonNode s : g.path("seeks")) {
                String inv = s.path("investigation").asText("");
                b.append("<tr><td><a href=\"/jobs/").append(enc(s.path("job_id").asText())).append("\">").append(esc(s.path("job_id").asText())).append("</a></td><td>").append(esc(s.path("model").asText(""))).append("</td><td>").append(esc(s.path("sources").asText(""))).append("</td><td>").append(esc(t(s.path("state").asText("")))).append("</td><td>")
                 .append(inv.isEmpty() ? "<i>" + t("not yet") + "</i>" : "<a href=\"/entry/" + enc(inv) + "\">" + esc(inv) + "</a>").append("</td></tr>\n");
            }
            b.append("</table>");
            JsonNode merged = g.path("merged");
            if (merged.isObject() && !merged.path("investigation").asText("").isEmpty()) b.append("<p>").append(t("Merged into {0}.", "<a href=\"/entry/" + enc(merged.path("investigation").asText()) + "\">" + esc(merged.path("investigation").asText()) + "</a>")).append("</p>");
            else if (merged.isObject() && !merged.path("job_id").asText("").isEmpty()) b.append("<p>").append(t("The merge is running as {0}.", "<a href=\"/jobs/" + enc(merged.path("job_id").asText()) + "\">" + esc(merged.path("job_id").asText()) + "</a>")).append("</p>");
            if (writer) {
                b.append("<form method=\"post\" action=\"/seeks\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"merge\"><input type=\"hidden\" name=\"group\" value=\"").append(esc(group)).append("\"><button class=\"btn\"").append(landed < 2 ? " disabled title=\"" + esc(t("A merge needs at least two seeks that landed.")) + "\"" : "").append(">").append(t("Merge the seeks that landed")).append("</button></form> ")
                 .append("<form method=\"post\" action=\"/seeks\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"drop\"><input type=\"hidden\" name=\"group\" value=\"").append(esc(group)).append("\"><button class=\"quiet\">").append(t("Drop the group")).append("</button></form>")
                 .append(" <span class=\"k\">").append(n(landed, "{0} seek landed", "{0} seeks landed")).append("</span>");
            }
            b.append("</div>\n");
        }
        return b.toString();
    }

    /** A form of the page: merge or drop a group. The note shown back. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", ""), group = form.getOrDefault("group", "").strip();
        try {
            ObjectNode a = args(patron).put("group", group);
            switch (op) {
                case "merge" -> { ObjectNode r = new LibraryProtocol(store).seeks(a.put("op", "merge")); return t("The merge of {0} runs as {1}; the merged report lands on the group.", group, r.path("job_id").asText()); }
                case "drop" -> { new LibraryProtocol(store).seeks(a.put("op", "drop")); return t("The seeks of {0} are dropped; a merged report stays.", group); }
                default -> { return t("That is not something the Seeks page does."); }
            }
        } catch (ProtocolError e) { return e.getMessage(); }
    }
}
