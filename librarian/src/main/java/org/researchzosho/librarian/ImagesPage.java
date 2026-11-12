package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.researchzosho.librarian.Pages.enc;
import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The pictures page (0.7.0): the pictures on the shelves as small tiles, newest first, each with its page, its verdict and where it
 * came from (the search engine, or the document it was read out of), and a box that finds pictures for words through {@link
 * Images#find}, the shelves first; a keeper or a writer keeps one from the results.
 */
final class ImagesPage {
    static final String PATH = "/images";

    private ImagesPage() { }

    /** The tiles' own style, on this page alone. */
    static final String STYLE = "<style>.pics{display:grid;grid-template-columns:repeat(auto-fill,minmax(15em,1fr));gap:1em;margin:1em 0}.pics figure{margin:0;background:var(--card);border:1px solid var(--line);border-radius:.5em;padding:.7em}"
            + ".pics img.thumb{max-width:100%;max-height:13em;width:auto;height:auto;display:block;margin:0 auto .5em}.pics figcaption{font-size:.86em;color:var(--k);line-height:1.4}.pics figcaption b{color:var(--ink);font-weight:500}.pics form{margin-top:.4em}</style>";

    static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        StringBuilder b = new StringBuilder(STYLE);
        b.append("<p class=\"lede\">").append(t("The pictures the library keeps: found on the web and judged by the model that sees, or read out of the documents you added. A research run cites a kept picture as it cites a page.")).append("</p>");
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        String words = q.getOrDefault("q", "").strip();
        b.append("<form method=\"get\" action=\"").append(PATH).append("\" class=\"inline\"><label>").append(t("Find pictures for")).append(" <input name=\"q\" size=\"36\" value=\"").append(esc(words)).append("\" placeholder=\"").append(esc(t("a person, a place, an event, a thing"))).append("\"></label> <button class=\"btn\">").append(t("Find")).append("</button> <span class=\"k\">")
         .append(t("the shelves first, then Brave with your key and the library's own SearXNG")).append("</span></form>");
        boolean writer = Pages.mayWrite(store, patron);
        if (!words.isEmpty()) {
            Images.Result r = Images.find(store, words, 24);
            b.append("<h2>").append(esc(t("Found for \"{0}\"", words))).append("</h2><p class=\"state").append(r.found().isEmpty() ? "" : " done").append("\">").append(esc(r.said(words))).append("</p>");
            if (!r.found().isEmpty()) {
                b.append("<div class=\"pics\">");
                for (Images.Found f : r.found()) {
                    String src = f.thumbnail() != null && !f.thumbnail().isBlank() ? f.thumbnail() : f.image();
                    b.append("<figure><img class=\"thumb\" src=\"").append(esc(src)).append("\" alt=\"").append(esc(f.title())).append("\" loading=\"lazy\" referrerpolicy=\"no-referrer\"><figcaption><b>").append(esc(f.title().isBlank() ? t("A picture") : f.title())).append("</b><br>");
                    if (f.page() != null && !f.page().isBlank()) b.append(f.page().startsWith("/") || f.page().startsWith("file:") ? esc(f.page()) : "<a href=\"" + esc(f.page()) + "\" rel=\"noreferrer\">" + esc(f.host()) + "</a>").append(" · ");
                    if (!f.size().isEmpty()) b.append(esc(f.size())).append(" · ");
                    b.append(esc(Images.SHELVES.equals(f.source()) ? t("on the shelves") : t("found through {0}", f.source())));
                    if (writer && !Images.SHELVES.equals(f.source())) b.append("<form method=\"post\" action=\"").append(PATH).append("\"><input type=\"hidden\" name=\"op\" value=\"keep\"><input type=\"hidden\" name=\"q\" value=\"").append(esc(words)).append("\"><input type=\"hidden\" name=\"image\" value=\"").append(esc(f.image())).append("\"><input type=\"hidden\" name=\"page\" value=\"").append(esc(f.page())).append("\"><input type=\"hidden\" name=\"title\" value=\"").append(esc(f.title())).append("\"><input type=\"hidden\" name=\"source\" value=\"").append(esc(f.source())).append("\"><button class=\"quiet\">").append(Chrome.now().tc("Keep", "a button that saves the thing beside it")).append("</button></form>");
                    b.append("</figcaption></figure>");
                }
                b.append("</div>");
            }
        }
        List<Images.Shelved> all = Images.onShelves(store);
        b.append("<h2>").append(t("On the shelves")).append("</h2>");
        if (all.isEmpty()) { b.append("<p class=\"k\">").append(t("No picture is kept yet. Find some above, say \"find me some pictures of …\" in the chat, or add a document that carries pictures.")).append("</p>"); return b.toString(); }
        b.append("<p class=\"state done\">").append(esc(Pages.n(all.size(), "{0} picture", "{0} pictures"))).append("</p><div class=\"pics\">");
        for (Images.Shelved s : all) {
            b.append("<figure><a href=\"/entry/").append(enc(s.id())).append("\"><img class=\"thumb\" src=\"").append(esc(s.url())).append("\" alt=\"").append(esc(s.title())).append("\" loading=\"lazy\"></a><figcaption><b><a href=\"/entry/").append(enc(s.id())).append("\">").append(esc(s.title())).append("</a></b><br>");
            if (s.verdict() != Images.Verdict.UNJUDGED) b.append(esc(t("judged \"{0}\": {1}", s.criteria(), t(s.verdict().word())))).append(s.why().isBlank() ? "" : " — " + esc(s.why())).append("<br>");
            if (Images.DOCUMENT.equals(s.source())) {
                String docTitle = "", docId = "";
                try { Path d = RawCapture.find(store, s.page()); if (d != null) { docId = d.getFileName().toString(); docTitle = RawCapture.read(d)[1]; } } catch (IOException ignored) { }
                b.append(docId.isEmpty() ? esc(t("read out of {0}", s.page())) : t("read out of {0}", "<a href=\"/entry/" + enc(docId) + "\">" + esc(docTitle.isBlank() ? docId : docTitle) + "</a>"));
            } else if (Images.GIVEN.equals(s.source())) {
                b.append(esc(t("given by you")));
            } else {
                if (!s.page().isBlank()) b.append(t("from {0}", "<a href=\"" + esc(s.page()) + "\" rel=\"noreferrer\">" + esc(s.asFound().host().isEmpty() ? s.page() : s.asFound().host()) + "</a>")).append(s.source().isBlank() ? "" : ", ");
                if (!s.source().isBlank()) b.append(esc(t("found through {0}", s.source())));
            }
            if (!s.found().isBlank()) b.append(" · ").append(esc(Pages.dayTime(s.found())));
            b.append("</figcaption></figure>");
        }
        return b.append("</div>").toString();
    }

    /** POST op=keep: a picture from the results onto the shelves, by a keeper or a writer. The note for the page. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) {
        if (!Pages.mayWrite(store, patron)) return t("Only the person who keeps this library and those who may write to it put pictures on the shelves.");
        if (!"keep".equals(form.getOrDefault("op", ""))) return "";
        Images.Found f = new Images.Found(form.getOrDefault("image", ""), form.getOrDefault("page", ""), form.getOrDefault("title", ""), form.getOrDefault("source", ""), 0, 0, "");
        try { Images.Kept k = Images.keep(store, f, "", ""); return t("Kept \"{0}\" as {1}, shown at {2}.", f.title().isBlank() ? f.image() : f.title(), k.id(), k.url()); }
        catch (IOException e) { return t("Not kept: {0}", e.getMessage()); }
    }
}
