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
 * The Perspectives page ({@code /perspectives}): who studies a question and what each would insist on asking, the sub-questions a research
 * run could take. One or two searches and one judge call; nothing is filed. A form the pages lacked until 2026-11-07; the command line
 * and the chat had it.
 */
final class PerspectivesPage {
    private PerspectivesPage() { }
    static final String PATH = "/perspectives";
    private static final ObjectMapper M = new ObjectMapper();

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String question = q.getOrDefault("q", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("Who studies a question, and what each would ask: the sub-questions a research run could take. The library searches once or twice and asks its model once; nothing is filed. At the terminal: {0}.", "<code>researchzosho perspectives &lt;question&gt;</code>")).append("</p>\n");
        b.append("<form method=\"get\" action=\"/perspectives\" class=\"stack\"><label>").append(t("The question")).append("<br><textarea name=\"q\" rows=\"3\" required>").append(esc(question)).append("</textarea></label><button class=\"btn primary\">").append(t("Who would ask what")).append("</button></form>\n");
        if (question.isEmpty()) return b.toString();
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); a.put("question", question);
        JsonNode r;
        try { r = new LibraryProtocol(store).perspectives(a); } catch (ProtocolError e) { return b.append("<p class=\"err\">").append(esc(e.getMessage())).append("</p>").toString(); }
        if (r.path("perspectives").isEmpty()) return b.append("<p>").append(t("No perspectives were found for that question.")).append("</p>").toString();
        for (JsonNode p : r.path("perspectives")) {
            b.append("<div class=\"card\"><h3>").append(esc(p.path("perspective").asText())).append("</h3><p class=\"k\">").append(esc(p.path("why").asText())).append("</p><ul class=\"tight\">");
            for (JsonNode s : p.path("questions")) b.append("<li>").append(esc(s.asText())).append(" <a class=\"small\" href=\"/research?q=").append(enc(s.asText())).append("\">").append(t("research it")).append("</a></li>");
            b.append("</ul></div>\n");
        }
        if (r.path("sub_questions").size() > 0) {
            b.append("<h2>").append(t("Sub-questions for a run")).append("</h2><ol>");
            for (JsonNode s : r.path("sub_questions")) b.append("<li>").append(esc(s.asText())).append("</li>");
            b.append("</ol>");
        }
        return b.toString();
    }
}
