package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The Submit page ({@code /submit}): a claim of the person's own, one to three sentences, with the sources that back it, filed as a draft
 * that waits in the inbox like any other. The protocol and the chat could file one (library_submit); the pages could not until 2026-11-07.
 */
final class SubmitPage {
    private SubmitPage() { }
    static final String PATH = "/submit";
    private static final ObjectMapper M = new ObjectMapper();

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p class=\"k\">").append(t("A claim of your own, with the sources that back it: it is filed as a draft and waits in the inbox for a decision, as a research run's claims do. A claim without a source is refused. At the terminal: {0}.", "<code>researchzosho submit \"…\" --source &lt;url&gt;</code>")).append("</p>\n");
        if (!Pages.mayWrite(store, patron)) return b.append("<p>").append(t("Filing a claim is for those who may write to this library.")).append("</p>").toString();
        b.append("<form method=\"post\" action=\"/submit\" class=\"stack\"><label>").append(t("The claim, one to three complete sentences")).append("<br><textarea name=\"claim\" rows=\"3\" required>").append(esc(q.getOrDefault("claim", ""))).append("</textarea></label>")
         .append("<label>").append(t("Its sources, one address a line")).append("<br><textarea name=\"sources\" rows=\"3\" required placeholder=\"https://…\"></textarea></label>")
         .append("<label>").append(t("Why the sources back it")).append(" <small>(").append(t("optional")).append(")</small><br><input name=\"why\" size=\"60\"></label>")
         .append("<button class=\"btn primary\">").append(t("File it as a draft")).append("</button></form>\n");
        return b.toString();
    }

    /** The form posted: the claim filed; the note shown back. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron));
        a.put("claim", form.getOrDefault("claim", "").strip());
        String why = form.getOrDefault("why", "").strip();
        ArrayNode sources = a.putArray("sources");
        for (String line : form.getOrDefault("sources", "").split("\\r?\\n")) { String s = line.strip(); if (s.isEmpty()) continue; if (why.isEmpty()) sources.add(s); else sources.addObject().put("locator", s).put("why", why); }
        try {
            ObjectNode r = new LibraryProtocol(store).submit(a);
            return t("Filed as a draft: {0}. It waits in the inbox for a decision.", r.path("id").asText());
        } catch (ProtocolError e) { return e.getMessage(); }
    }
}
