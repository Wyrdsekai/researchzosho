package org.researchzosho.librarian;

import java.util.List;

/** The Help page, {@code /help}: the library's live state, a box to look something up in its own documentation, and the documents themselves. */
public final class HelpPage {
    private HelpPage() { }

    public static String body(LibraryStore store, String question, String doc) {
        StringBuilder b = new StringBuilder();
        if (doc != null && !doc.isBlank()) {
            String md = Help.doc(doc);
            if (md.isEmpty()) return "<p>There is no document called " + Pages.esc(doc) + ". <a href=\"/help\">Back to Help</a></p>";
            return b.append("<p><a href=\"/help\">Back to Help</a></p><div class=\"prose\">").append(Pages.md(md)).append("</div>").toString();
        }
        b.append("<form method=\"get\" action=\"/help\" class=\"inline\"><input name=\"q\" value=\"").append(Pages.esc(question == null ? "" : question))
                .append("\" placeholder=\"what you want to do, or what is not working\" size=\"60\" aria-label=\"look something up\"> <button>Look it up</button></form>");
        String text = Help.lookup(store, question);
        b.append("<div class=\"prose\">").append(Pages.md(text)).append("</div>");
        List<String> docs = Help.docs();
        if (!docs.isEmpty()) {
            b.append("<h2>The documentation</h2><ul>");
            for (String d : docs) b.append("<li><a href=\"/help?doc=").append(Pages.esc(d)).append("\">").append(Pages.esc(d.replaceFirst("\\.md$", "").replace('_', ' '))).append("</a></li>");
            b.append("</ul>");
        }
        return b.toString();
    }
}
