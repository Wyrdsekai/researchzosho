package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The Bridges page ({@code /bridges}): discovery by combination, two areas of the library no source read together, joined by terms both
 * use. The open proposals with their evidence, each with a button that files the research run testing it or drops it; and a form that
 * looks for new pairs from an area, shown first and filed on a second click. The command line and the chat had it; the pages did not
 * until 2026-11-07. Design: docs/DESIGN_BRIDGES.md.
 */
final class BridgesPage {
    private BridgesPage() { }
    static final String PATH = "/bridges";
    private static final ObjectMapper M = new ObjectMapper();

    static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); return a; }

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q, String found) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer = Pages.mayWrite(store, patron);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p class=\"k\">").append(t("A bridge is a question that connects two subjects no source read together, joined by terms both use. It is a question the shelves have not answered, not a finding: accepting one files the research run that tests it. At the terminal: {0}.", "<code>researchzosho bridges [&lt;area&gt;] [--dry]</code>, <code>bridges accept|dismiss &lt;question&gt;</code>")).append("</p>\n");
        if (writer) {
            b.append("<form method=\"post\" action=\"/bridges\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"run\"><label>").append(t("From an area")).append(" <input name=\"area\" size=\"28\" value=\"").append(esc(q.getOrDefault("area", ""))).append("\" placeholder=\"").append(esc(t("blank: the hottest areas"))).append("\"></label> ")
             .append("<label><input type=\"checkbox\" name=\"dry\" value=\"1\" checked> ").append(t("only show the pairs, file nothing")).append("</label> <button class=\"btn primary\">").append(t("Look for pairs")).append("</button></form>\n");
        }
        if (found != null) b.append(found);
        JsonNode r = new LibraryProtocol(store).bridges(args(patron).put("op", "list"));
        b.append("<h2>").append(t("Open proposals")).append("</h2>");
        if (r.path("proposals").isEmpty()) return b.append("<p>").append(t("None open. Look for pairs above, or the housekeeping proposes a few each night where an area is set to.")).append("</p>").toString();
        for (JsonNode p : r.path("proposals")) {
            String question = p.path("question").asText();
            b.append("<div class=\"card\"><p>").append(esc(question)).append("</p><p class=\"k\">").append(esc(p.path("date").asText(""))).append(p.path("found_by").asText("").isEmpty() ? "" : " · " + esc(p.path("found_by").asText())).append("</p>");
            if (!p.path("evidence").asText("").isBlank()) b.append("<p class=\"small\">").append(esc(p.path("evidence").asText())).append("</p>");
            if (writer) b.append("<form method=\"post\" action=\"/bridges\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"accept\"><input type=\"hidden\" name=\"question\" value=\"").append(esc(question)).append("\"><button class=\"btn\">").append(t("Test it with a run")).append("</button></form> ")
                          .append("<form method=\"post\" action=\"/bridges\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"dismiss\"><input type=\"hidden\" name=\"question\" value=\"").append(esc(question)).append("\"><button class=\"quiet\">").append(t("Drop it")).append("</button></form>");
            b.append("</div>\n");
        }
        return b.toString();
    }

    /** What a run of the pair-finder found, as a section of the page: each pair with its shared terms and its question; a dry run's pairs are not filed. */
    static String found(JsonNode r, boolean dry) {
        StringBuilder b = new StringBuilder("<h2>").append(dry ? t("Pairs found, not filed") : t("Pairs found and filed as open questions")).append("</h2>");
        if (!r.path("summary").asText("").isEmpty()) b.append("<p class=\"k\">").append(esc(r.path("summary").asText())).append("</p>");
        if (r.path("proposals").isEmpty()) b.append("<p>").append(t("No pair came up from there.")).append("</p>");
        for (JsonNode p : r.path("proposals")) {
            List<String> via = new ArrayList<>(); for (JsonNode v : p.path("via")) via.add(v.asText());
            b.append("<div class=\"card\"><p><b>").append(esc(p.path("a_label").asText(p.path("a").asText()))).append("</b> × <b>").append(esc(p.path("c_label").asText(p.path("c").asText()))).append("</b>").append(via.isEmpty() ? "" : " <span class=\"k\">" + t("shared: {0}", esc(String.join(", ", via))) + "</span>").append("</p><p>").append(esc(p.path("question").asText())).append("</p></div>");
        }
        if (!r.path("declined").isEmpty()) b.append("<p class=\"k\">").append(t("{0} pairs were declined by the model.", r.path("declined").size())).append("</p>");
        return b.toString();
    }

    /** A form posted: a run of the pair-finder (its pairs come back for the page), or a proposal accepted or dropped (a note comes back). */
    record Done(String note, String section) { }

    static Done post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", "");
        LibraryProtocol p = new LibraryProtocol(store);
        try {
            switch (op) {
                case "run" -> {
                    boolean dry = "1".equals(form.get("dry"));
                    ObjectNode a = args(patron).put("op", "run").put("dry", dry);
                    String area = form.getOrDefault("area", "").strip(); if (!area.isEmpty()) a.put("area", area);
                    return new Done(null, found(p.bridges(a), dry));
                }
                case "accept" -> { ObjectNode r = p.bridges(args(patron).put("op", "accept").put("question", form.getOrDefault("question", ""))); return new Done(r.path("job_id").asText("").isEmpty() ? r.path("next").asText(t("Nothing was started.")) : t("Accepted: research run {0} tests it. The Runs page follows it.", r.path("job_id").asText()), null); }
                case "dismiss" -> { p.bridges(args(patron).put("op", "dismiss").put("question", form.getOrDefault("question", ""))); return new Done(t("Dropped."), null); }
                default -> { return new Done(t("That is not something the Bridges page does."), null); }
            }
        } catch (ProtocolError e) { return new Done(e.getMessage(), null); }
    }
}
