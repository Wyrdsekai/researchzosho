package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.t;

/** The Sources page and the chat's {@code /sources}: what the library mines, shown and changed by the person (docs/DESIGN_SOURCES.md). */
public final class SourcesPage {
    private SourcesPage() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The page's body: every source by field, with what it yielded and for whom; add, take off, bring back for a writer. */
    public static String body(LibraryStore store, boolean writer, Map<String, String> q) throws IOException {
        List<Sources.Source> all = Sources.all(store);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(Pages.esc(note)).append("</p>\n");
        b.append("<p>").append(t("The places the library mines: registers, directories, boards, list pages, feeds, and the searches and podcasts it runs again on their own. A search keeps the ones it reads, with what each yielded and for whom; one that yields nothing new for a while goes dry and is read rarely; one you take off is never read again, however often a search finds it, and a saved search taken off is parked until you bring it back. Add your own by address, with a word on what it is for, or words to search again.")).append("</p>\n");
        if (writer) {
            b.append("<form method=\"post\" action=\"/sources\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"add\"><label>").append(t("Address or search words")).append(" <input name=\"address\" size=\"50\"></label> ")
                    .append("<label>").append(t("For")).append(" <input name=\"for\" size=\"28\" placeholder=\"").append(Pages.esc(t("nurses in Lyon; podcasts on wine"))).append("\"></label> <label>").append(t("Field")).append(" <input name=\"field\" size=\"10\" placeholder=\"career\"></label> <button>").append(t("Add")).append("</button></form>\n");
        }
        if (all.isEmpty()) { b.append("<p>").append(t("No sources of the library's own yet.")).append("</p>"); return b.toString(); }
        b.append("<table><tr><th>").append(t("id")).append("</th><th>").append(t("source")).append("</th><th>").append(t("kind")).append("</th><th>").append(t("yields")).append("</th><th>").append(t("for")).append("</th><th>").append(t("found by")).append("</th><th>").append(t("last yield")).append("</th><th>").append(t("in all")).append("</th><th>").append(t("state")).append("</th><th></th></tr>\n");
        for (Sources.Source s : all) {
            b.append("<tr><td>").append(Pages.esc(s.id())).append("</td><td>").append(s.name().isEmpty() ? "" : "<b>" + Pages.esc(s.name()) + "</b><br>").append(s.address().matches("(?i)https?://.*") ? "<a href=\"" + Pages.esc(s.address()) + "\" rel=\"noopener\">" + Pages.esc(s.address()) + "</a>" : Pages.esc(s.address()))
                    .append(s.note().isEmpty() ? "" : "<br><small>" + Pages.esc(s.note()) + "</small>").append("</td><td>").append(Pages.esc(s.kind())).append("</td><td>").append(Pages.esc(s.yields()))
                    .append("</td><td>").append(Pages.esc(String.join(", ", Sources.nonEmpty(s.field(), s.subject(), s.place(), s.forWhom())))).append("</td><td>").append(Pages.esc(s.foundBy()))
                    .append("</td><td>").append(s.lastYield().isEmpty() ? "<i>" + t("never") + "</i>" : Pages.esc(Chrome.now().day(s.lastYield())) + " (" + s.yieldLast() + ")").append("</td><td>").append(s.yieldTotal()).append("</td><td>").append(Pages.esc(t(s.state()))).append(s.survived() > 0 || s.fell() > 0 ? "<br><small>" + t("{0} survived, {1} fell", s.survived(), s.fell()) + "</small>" : "").append("</td><td>");
            if (writer) b.append("<form method=\"post\" action=\"/sources\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(s.off() ? "restore" : "remove").append("\"><input type=\"hidden\" name=\"id\" value=\"").append(Pages.esc(s.id()))
                    .append("\"><button class=\"lnk\">").append(s.off() ? t("bring back") : s.shelf() ? t("park") : t("take off")).append("</button></form>");
            b.append("</td></tr>\n");
        }
        b.append("</table>\n<p class=\"k\">").append(t("The same from a terminal: {0}; and in the chat, {1}.", "<code>researchzosho sources</code>, <code>sources add &lt;address&gt; --for &lt;words&gt;</code>, <code>sources remove &lt;id&gt;</code>", "<code>/sources</code>")).append("</p>\n");
        return b.append(rules(store)).toString();
    }

    /** The hosts the person trusts or refuses, which research runs honour: shown with the mined sources, since both are "sources" to the person. */
    static String rules(LibraryStore store) {
        SourceRules rules = SourceRules.load(store);
        StringBuilder b = new StringBuilder("<h2>").append(t("Hosts trusted or refused")).append("</h2>");
        if (rules.isEmpty()) return b.append("<p class=\"k\">").append(t("None yet.")).append(" ").append(t("A trusted host counts as a primary source; a refused one is dropped from searches, and a claim resting only on it is dropped at review.")).append(" <code>researchzosho sources trust &lt;host&gt; [why]</code>, <code>sources refuse &lt;host&gt; [why]</code>.</p>\n").toString();
        b.append("<table><tr><th>").append(t("host")).append("</th><th>").append(t("rule")).append("</th><th>").append(t("why")).append("</th></tr>\n");
        for (SourceRules.Rule r : rules.rules()) b.append("<tr><td>").append(Pages.esc(r.host())).append("</td><td>").append(Pages.esc(t(r.kind()))).append("</td><td>").append(Pages.esc(r.why())).append("</td></tr>\n");
        return b.append("</table>\n<p class=\"k\">").append(t("A trusted host counts as a primary source; a refused one is dropped from searches, and a claim resting only on it is dropped at review.")).append(" <code>researchzosho sources trust|refuse|forget &lt;host&gt;</code>.</p>\n").toString();
    }

    /** A form posted on the page: add, remove, restore. The note shown back. */
    public static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", patron.runtime());
        String op = form.getOrDefault("op", "");
        try {
            switch (op) {
                case "add" -> { a.put("op", "add").put("address", form.getOrDefault("address", "")).put("for", form.getOrDefault("for", "")).put("field", form.getOrDefault("field", "")); return lp.sources(a).path("note").asText(); }
                case "remove", "restore" -> { a.put("op", op).put("id", form.getOrDefault("id", "")); return lp.sources(a).path("note").asText(); }
                default -> { return t("That is not something the Sources page does."); }
            }
        } catch (ProtocolError e) { return e.getMessage(); }
    }

    /** The chat's /sources: list, add <address> [--for words], remove <id>, restore <id>, show <id>; in sentences. */
    public static String chat(LibraryStore store, Patrons.Patron patron, String line, String how) throws IOException {
        String rest = line.strip().replaceFirst("^/?sources\\s*", "").strip();
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", patron.did()).put("name", patron.name()).put("runtime", patron.runtime());
        try {
            if (rest.isEmpty() || rest.equals("list") || rest.startsWith("list ")) {
                a.put("op", "list").put("off", rest.contains("--off"));
                return lp.sources(a).path("text").asText();
            }
            String[] w = rest.split("\\s+", 2);
            String arg = w.length > 1 ? w[1].strip() : "";
            switch (w[0].toLowerCase()) {
                case "add" -> {
                    String address = arg, forWords = "", field = "";
                    int at = arg.indexOf("--for"); if (at >= 0) { address = arg.substring(0, at).strip(); forWords = arg.substring(at + 5).strip(); }
                    int fa = forWords.indexOf("--field"); if (fa >= 0) { field = forWords.substring(fa + 7).strip(); forWords = forWords.substring(0, fa).strip(); }
                    a.put("op", "add").put("address", address).put("for", forWords).put("field", field);
                    return lp.sources(a).path("note").asText();
                }
                case "remove", "restore", "show" -> { a.put("op", w[0].toLowerCase()).put("id", arg); return lp.sources(a).path(w[0].equalsIgnoreCase("show") ? "text" : "note").asText(); }
                default -> { return "Type " + how + " to see the sources; " + how + " add <address> --for <words>; " + how + " remove <id>; " + how + " restore <id>; " + how + " show <id>."; }
            }
        } catch (ProtocolError e) { return e.getMessage(); }
    }
}
