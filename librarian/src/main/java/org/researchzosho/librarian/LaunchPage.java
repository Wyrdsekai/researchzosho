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
 * The Read-in page ({@code /launch}): two of the library's starting points as forms, which the command line and the chat had and the pages
 * lacked until 2026-11-07. A browser's bookmarks, as an export or one address a line, onto the shelves as a collection, watched nightly
 * if asked; and a survey of a code repository, a paper, a website or an issue tracker by its address, which reads it, files one draft claim
 * on what it is and offers numbered research directions to run, or the person's own question about it.
 */
final class LaunchPage {
    private LaunchPage() { }
    static final String PATH = "/launch";
    private static final ObjectMapper M = new ObjectMapper();

    static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); return a; }

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q, String result) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        if (!Pages.mayWrite(store, patron)) return b.append("<p>").append(t("Reading material in is for those who may write to this library.")).append("</p>").toString();
        if (result != null) b.append(result);
        b.append("<h2>").append(t("Your bookmarks")).append("</h2><p class=\"k\">").append(t("A browser's exported bookmarks file, Chrome's Bookmarks file, or one address a line: every page goes onto the shelves as a collection. Watched, they are read again every night. At the terminal: {0}.", "<code>researchzosho bookmarks &lt;export|urls&gt; [--folder F] [--watch]</code>")).append("</p>");
        b.append("<form method=\"post\" action=\"/launch\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"bookmarks\">")
         .append("<label>").append(t("Addresses, one a line")).append("<br><textarea name=\"text\" rows=\"4\" placeholder=\"https://…\"></textarea></label>")
         .append("<label>").append(t("or the address of an exported bookmarks file")).append(" <input name=\"url\" size=\"50\"></label>")
         .append("<label>").append(t("One folder only")).append(" <small>(").append(t("optional")).append(")</small> <input name=\"folder\" size=\"24\"></label>")
         .append("<label><input type=\"checkbox\" name=\"watch\" value=\"1\"> ").append(t("read them again every night")).append("</label>")
         .append("<button class=\"btn primary\">").append(t("Put them on the shelves")).append("</button></form>\n");
        b.append("<h2>").append(t("A repository, a paper, a website or an issue tracker")).append("</h2><p class=\"k\">").append(t("Given its address, the library reads it, saves one draft claim on what it is, and lists numbered research directions; pick some to run, or ask your own question about it. Each run costs the model about half an hour. At the terminal: {0}.", "<code>researchzosho survey &lt;repo|paper|url|issues&gt;</code>")).append("</p>");
        b.append("<form method=\"post\" action=\"/launch\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"survey\">")
         .append("<label>").append(t("The address")).append(" <input name=\"url\" size=\"50\" required placeholder=\"https://github.com/…\"></label>")
         .append("<label>").append(t("What it is")).append(" <select name=\"kind\"><option value=\"\">").append(t("let the library tell")).append("</option><option value=\"repo\">").append(t("a code repository")).append("</option><option value=\"paper\">").append(t("a paper")).append("</option><option value=\"site\">").append(t("a website or a product page")).append("</option><option value=\"issues\">").append(t("an issue tracker")).append("</option></select></label>")
         .append("<button class=\"btn primary\">").append(t("Read it")).append("</button></form>\n");
        return b.toString();
    }

    /** What a survey found, as a section of the page: what it is, its claim, and the directions each with a tick box, sent as runs by one button; and the box for the person's own question. */
    static String surveyed(JsonNode r) {
        String name = r.path("name").asText("");
        StringBuilder b = new StringBuilder("<div class=\"card\"><h3>").append(esc(r.path("title").asText(name))).append("</h3><p>").append(esc(r.path("summary").asText(""))).append("</p>");
        if (!r.path("claim_id").asText("").isEmpty()) b.append("<p class=\"k\">").append(t("Its claim: {0}.", "<a href=\"/entry/" + enc(r.path("claim_id").asText()) + "\">" + esc(r.path("claim_id").asText()) + "</a>")).append("</p>");
        if (r.path("options").size() > 0) {
            b.append("<form method=\"post\" action=\"/launch\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"pick\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(name)).append("\"><p>").append(t("Directions to research; tick any number:")).append("</p>");
            for (JsonNode o : r.path("options")) b.append("<label><input type=\"checkbox\" name=\"pick").append(o.path("n").asInt()).append("\" value=\"").append(o.path("n").asInt()).append("\"> ").append(o.path("n").asInt()).append(". ").append(esc(o.path("question").asText())).append("</label><br>");
            b.append("<button class=\"btn\">").append(t("Run the ticked ones")).append("</button></form>");
        }
        b.append("<form method=\"post\" action=\"/launch\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"do\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(name)).append("\"><label>").append(t("Or your own question about it")).append("<br><input name=\"question\" size=\"60\"></label><button class=\"btn\">").append(t("Run it")).append("</button></form>");
        if (r.path("runs").size() > 0) { b.append("<p>").append(t("Runs started:")); for (JsonNode j : r.path("runs")) b.append(" <a href=\"/jobs/").append(enc(j.isTextual() ? j.asText() : j.path("job_id").asText())).append("\">").append(esc(j.isTextual() ? j.asText() : j.path("job_id").asText())).append("</a>"); b.append("</p>"); }
        return b.append("</div>\n").toString();
    }

    /** A form posted: bookmarks shelved (a note), a survey read (its section), directions or a question run (their section). */
    record Done(String note, String section) { }

    static Done post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", "");
        LibraryProtocol p = new LibraryProtocol(store);
        try {
            switch (op) {
                case "bookmarks" -> {
                    ObjectNode a = args(patron);
                    String text = form.getOrDefault("text", "").strip(), url = form.getOrDefault("url", "").strip(), folder = form.getOrDefault("folder", "").strip();
                    if (!text.isEmpty()) a.put("text", text); else if (!url.isEmpty()) a.put("url", url); else return new Done(t("Give the addresses, one a line, or the address of an exported bookmarks file."), null);
                    if (!folder.isEmpty()) a.put("folder", folder);
                    if ("1".equals(form.get("watch"))) a.put("watch", true);
                    return new Done(p.bookmarks(a).path("summary").asText(), null);
                }
                case "survey" -> {
                    ObjectNode a = args(patron).put("op", "survey").put("url", form.getOrDefault("url", "").strip());
                    String kind = form.getOrDefault("kind", "").strip(); if (!kind.isEmpty()) a.put("kind", kind);
                    return new Done(null, surveyed(p.survey(a)));
                }
                case "pick", "do" -> {
                    ObjectNode a = args(patron).put("op", op).put("name", form.getOrDefault("name", "").strip());
                    if (op.equals("pick")) { List<String> picks = new ArrayList<>(); for (var e : form.entrySet()) if (e.getKey().startsWith("pick") && e.getValue().matches("\\d+")) picks.add(e.getValue()); a.put("picks", String.join(",", picks)); }   // one box a direction: pick1, pick2…
                    else a.put("question", form.getOrDefault("question", "").strip());
                    return new Done(null, surveyed(p.survey(a)));
                }
                default -> { return new Done(t("That is not something this page does."), null); }
            }
        } catch (ProtocolError e) { return new Done(e.getMessage(), null); }
    }
}
