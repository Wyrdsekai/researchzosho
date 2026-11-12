package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The Forecasts page ({@code /forecasts}): the dated forecasts the reports made, each with its likelihood and outcome, for every subject or
 * one; a form scores one once its date has passed, happened, did not or void, and the Brier score over the scored ones follows. The
 * command line had both; the dossier page listed them and could not score them until 2026-11-07.
 */
final class ForecastsPage {
    private ForecastsPage() { }
    static final String PATH = "/forecasts";
    private static final ObjectMapper M = new ObjectMapper();

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer = Pages.mayWrite(store, patron);
        String subject = q.getOrDefault("subject", "").strip();
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p class=\"k\">").append(t("The dated forecasts the reports made, each with its likelihood, and what became of it. Score one once its date has passed; the Brier score over the scored ones says how well the forecasts did (0 is perfect, 0.25 is coin-tossing). At the terminal: {0}.", "<code>researchzosho forecasts</code>, <code>forecast &lt;P-…&gt; happened|did-not|void</code>")).append("</p>\n");
        b.append("<form method=\"get\" action=\"/forecasts\" class=\"inline\"><label>").append(t("Subject")).append(" <input name=\"subject\" size=\"30\" value=\"").append(esc(subject)).append("\"></label> <button class=\"btn\">").append(t("Show")).append("</button>").append(subject.isEmpty() ? "" : " <a href=\"/forecasts\">" + t("all subjects") + "</a>").append("</form>\n");
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); a.put("op", "forecasts"); if (!subject.isEmpty()) a.put("subject", subject);
        JsonNode r = new LibraryProtocol(store).dossier(a);
        if (r.path("forecasts").isEmpty()) return b.append("<p>").append(t("No forecasts yet. A dossier's run writes them when the evidence allows one.")).append("</p>").toString();
        b.append("<table><tr><th>").append(t("id")).append("</th><th>").append(t("subject")).append("</th><th>").append(t("forecast")).append("</th><th>").append(t("likelihood")).append("</th><th>").append(t("outcome")).append("</th>").append(writer ? "<th></th>" : "").append("</tr>\n");
        for (JsonNode f : r.path("forecasts")) {
            String id = f.path("id").asText(), outcome = f.path("outcome").asText("");
            b.append("<tr><td>").append(esc(id)).append("</td><td><a href=\"/dossier?subject=").append(enc(f.path("subject").asText())).append("\">").append(esc(f.path("subject").asText())).append("</a></td><td>").append(esc(f.path("text").asText())).append(f.path("report").asText("").isEmpty() ? "" : " <a class=\"small\" href=\"/entry/" + enc(f.path("report").asText()) + "\">" + esc(f.path("report").asText()) + "</a>").append("</td><td>")
             .append(esc(f.path("likelihood").asText(""))).append(f.has("percent") ? " (" + f.path("percent").asInt() + "%)" : "").append("</td><td>").append(outcome.isEmpty() ? "<i>" + t("open") + "</i>" : esc(t(outcome)) + (f.path("note").asText("").isEmpty() ? "" : " <small>" + esc(f.path("note").asText()) + "</small>")).append("</td>");
            if (writer) {
                b.append("<td>");
                if (outcome.isEmpty()) {
                    b.append("<form method=\"post\" action=\"/forecasts\" class=\"inline\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(id)).append("\"><input type=\"hidden\" name=\"subject\" value=\"").append(esc(subject)).append("\">")
                     .append("<select name=\"outcome\"><option value=\"happened\">").append(t("happened")).append("</option><option value=\"did-not\">").append(t("did not")).append("</option><option value=\"void\">").append(t("void")).append("</option></select> <input name=\"note\" size=\"18\" placeholder=\"").append(esc(t("what settled it"))).append("\"> <button class=\"btn\">").append(t("Score")).append("</button></form>");
                }
                b.append("</td>");
            }
            b.append("</tr>\n");
        }
        b.append("</table>\n");
        if (r.has("brier")) b.append("<p class=\"k\">").append(t("Brier score over the scored forecasts: {0}.", Chrome.now().decimal(r.path("brier").asDouble(), 3))).append("</p>");
        return b.toString();
    }

    /** The form posted: a forecast scored; the note shown back. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron));
        a.put("op", "score").put("id", form.getOrDefault("id", "").strip()).put("outcome", form.getOrDefault("outcome", "").strip()).put("note", form.getOrDefault("note", "").strip());
        try {
            ObjectNode r = new LibraryProtocol(store).dossier(a);
            return r.path("summary").asText() + (r.has("brier") ? " " + t("Brier score over the scored forecasts on {0}: {1}.", r.path("subject").asText(), Chrome.now().decimal(r.path("brier").asDouble(), 3)) : "");
        } catch (ProtocolError e) { return e.getMessage(); }
    }
}
