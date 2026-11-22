package org.researchzosho.librarian;

import java.util.List;

import static org.researchzosho.librarian.Pages.t;

/** The Help page, {@code /help}: the library's live state, a box to look something up in its own documentation, and the documents themselves. */
public final class HelpPage {
    private HelpPage() { }

    public static String body(LibraryStore store, String question, String doc) {
        StringBuilder b = new StringBuilder();
        if (doc != null && !doc.isBlank()) {
            String md = Help.doc(doc);
            if (md.isEmpty()) return "<p>" + t("There is no document called {0}.", Pages.esc(doc)) + " <a href=\"/help\">" + t("Back to Help") + "</a></p>";
            return b.append("<p><a href=\"/help\">").append(t("Back to Help")).append("</a></p><div class=\"prose\">").append(Pages.mdOfTheProgram(md)).append("</div>").toString();
        }
        b.append("<form method=\"get\" action=\"/help\" class=\"inline\"><input name=\"q\" value=\"").append(Pages.esc(question == null ? "" : question))
                .append("\" placeholder=\"").append(Pages.esc(t("what you want to do, or what is not working"))).append("\" size=\"60\" aria-label=\"").append(Pages.esc(t("look something up"))).append("\"> <button>").append(t("Look it up")).append("</button></form>");
        // the state and the framing in the person's language; the documents as they are written. What the page was asked is not the
        // program's own words (it is whatever stood in the page's address): where the answer says it back, it is put in afterwards as
        // plain words, so that nothing in it is read as a link or a picture
        String asked = "RZASKED" + Long.toHexString(System.nanoTime()) + "X";
        String text = Help.lookup(store, question, Chrome.now(), asked);
        b.append("<div class=\"prose\">").append(Pages.mdOfTheProgram(text).replace(asked, Pages.esc(question == null ? "" : question.strip()))).append("</div>");   // the program's own documents and state: their links stand as written
        List<String> docs = Help.docs();
        if (!docs.isEmpty()) {
            b.append("<h2>").append(t("The documentation")).append("</h2><ul>");
            for (String d : docs) b.append("<li><a href=\"/help?doc=").append(Pages.esc(d)).append("\">").append(Pages.esc(d.replaceFirst("\\.md$", "").replace('_', ' '))).append("</a></li>");
            b.append("</ul>");
        }
        return b.toString();
    }
}
