package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        if (!q.getOrDefault("collection", "").isBlank()) return gallery(store, patron, q.get("collection").strip(), q.getOrDefault("note", ""));
        StringBuilder b = new StringBuilder(STYLE);
        b.append("<p class=\"lede\">").append(t("The pictures the library keeps: found on the web and judged by the model that sees, or read out of the documents you added. A research run cites a kept picture as it cites a page.")).append("</p>");
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        String words = q.getOrDefault("q", "").strip();
        b.append("<form method=\"get\" action=\"").append(PATH).append("\" class=\"inline\"><label>").append(t("Find pictures for")).append(" <input name=\"q\" size=\"36\" value=\"").append(esc(words)).append("\" placeholder=\"").append(esc(t("a person, a place, an event, a thing"))).append("\"></label> <button class=\"btn\">").append(t("Find")).append("</button> <span class=\"k\">")
         .append(t("the shelves first, then Brave with your key and the library's own SearXNG")).append("</span></form>");
        boolean writer = Pages.mayWrite(store, patron);
        List<ObjectNode> collections = Collection.all(store);
        if (!collections.isEmpty()) {   // the collections, newest first: each a gallery of its own
            b.append("<h2>").append(t("Collections")).append("</h2><ul class=\"plain\">");
            for (ObjectNode m : collections) {
                String job = m.path("job").asText(""), state = m.path("state").asText("");
                b.append("<li><a href=\"").append(esc(Collection.gallery(job))).append("\">").append(esc(m.path("form").path("words").asText(job))).append("</a> <span class=\"k\">")
                 .append(esc(t("{0} of {1} kept", m.path("passed").size(), m.path("form").path("count").asInt(CollectionForm.DEFAULT_COUNT)))).append(" · ")
                 .append(esc(state.equals("running") ? t("running") : state.equals("stopped") ? t("stopped") : t("done"))).append(" · ").append(esc(job)).append("</span></li>");
            }
            b.append("</ul>");
        }
        if (!words.isEmpty()) {
            Images.Result r = Images.find(store, words, 24);
            b.append("<h2>").append(esc(t("Found for \"{0}\"", words))).append("</h2><p class=\"state").append(r.found().isEmpty() ? "" : " done").append("\">").append(esc(r.said(words))).append("</p>");
            if (!r.found().isEmpty()) {
                b.append("<div class=\"pics\">");
                for (Images.Found f : r.found()) {
                    String src = f.thumbnail() != null && !f.thumbnail().isBlank() ? f.thumbnail() : f.image();
                    b.append("<figure><img class=\"thumb\" ").append(Pages.SHOWN).append(" src=\"").append(esc(src)).append("\" alt=\"").append(esc(f.title())).append("\" loading=\"lazy\" referrerpolicy=\"no-referrer\"><figcaption><b>").append(esc(f.title().isBlank() ? t("A picture") : f.title())).append("</b><br>");
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

    /**
     * One collection as a gallery (0.8.0): what was asked, in plain words; how far it has come or why it ended; each kept picture's small
     * copy with its measured size and format, its page, its record (author, date, licence) and why it passed; then what was not counted —
     * the copies beside the picture each copies, and the pictures with no record when the person wanted none made by AI — the reasons
     * counted, and the searches the model chose, each with what it kept.
     */
    static String gallery(LibraryStore store, Patrons.Patron patron, String job, String note) throws IOException {
        ObjectNode m = Collection.manifest(store, job);
        StringBuilder b = new StringBuilder(STYLE);
        if (m == null) {
            ObjectNode j = null;
            try { j = new Jobs(store, x -> { throw new IllegalStateException("read only"); }).get(job); } catch (IOException | RuntimeException ignored) { }
            if (j != null && Collection.JOB_KIND.equals(j.path("kind").asText())) return b.append("<p class=\"state\">").append(esc(t("The collection {0} has not started yet; this page shows its pictures as they are found.", job))).append("</p>").toString();
            return b.append("<p>").append(esc(t("No collection is filed as {0}.", job))).append("</p>").toString();
        }
        String base = "/images/collections/" + Pages.enc(job) + "/";
        CollectionForm form = CollectionForm.fromArgs(m.path("form"));
        b.append("<p class=\"lede\">").append(esc(capital(form.said(Chrome.now())))).append(".</p>");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        if (m.path("dropped").size() > 0) {   // the model's readings of the request that its words do not say: left out of the form
            List<String> d = new ArrayList<>(); for (JsonNode x : m.path("dropped")) d.add(x.asText());
            b.append("<p class=\"k\">").append(esc(t("Left out of the form, because your words do not say it: {0}.", String.join("; ", d)))).append("</p>");
        }
        // the name the pictures were looked for by: a picture whose page, address and caption do not name it was held to a closer look
        if (!m.path("name_used").asText("").isBlank()) b.append("<p class=\"k\">").append(esc(t("Looked for by the name {0}: a picture whose page, address or caption does not name it was held to a closer look.", "“" + m.path("name_used").asText() + "”"))).append("</p>");
        boolean running = m.path("state").asText("").equals("running");
        String phase = "";
        if (running) {   // the manifest says running: so does the job, unless the service ended it (stopped three times in a row, failed) before the manifest could say so
            ObjectNode j = null;
            try { j = new Jobs(store, x -> { throw new IllegalStateException("read only"); }).get(job); } catch (IOException | RuntimeException ignored) { }
            String st = j == null ? "" : j.path("state").asText("");
            if (j != null) phase = j.path("progress").path("phase").asText("");
            if (j == null || !(st.equals("running") || st.equals("queued") || st.equals(Jobs.OFFERED))) { running = false; m = m.deepCopy(); m.put("stopped", "ended"); }
        }
        if (running) {
            b.append("<p class=\"state\">").append(esc(t("Collecting: {0} of {1} kept so far.", m.path("passed").size(), form.target()))).append(phase.isEmpty() ? "" : " <span class=\"k\">" + esc(phase) + "</span>")
             .append(" ").append(esc(t("Reload the page to see the pictures kept since."))).append("</p>");
        } else b.append("<p class=\"state done\">").append(esc(Collection.told(m, Chrome.now()))).append("</p>");
        String differ = form.differBy();
        b.append("<div class=\"pics\">");
        for (JsonNode p : m.path("passed")) {
            String title = p.path("title").asText("").isBlank() ? host(p) : p.path("title").asText();
            b.append("<figure><a href=\"").append(esc(p.path("image").asText())).append("\" rel=\"noreferrer\"><img class=\"thumb\" src=\"").append(esc(base + p.path("thumb").asText())).append("\" alt=\"").append(esc(title)).append("\" loading=\"lazy\"></a><figcaption><b>")
             .append(p.path("n").asInt()).append(". ").append(esc(title)).append("</b><br>").append(esc(p.path("width").asInt() + "×" + p.path("height").asInt())).append(" · ").append(esc(p.path("format").asText("").toUpperCase(Locale.ROOT)));
            if (!p.path("page").asText("").isBlank()) b.append(" · <a href=\"").append(esc(p.path("page").asText())).append("\" rel=\"noreferrer\">").append(esc(host(p))).append("</a>");
            b.append(" · <a href=\"").append(esc(p.path("image").asText())).append("\" rel=\"noreferrer\">").append(esc(t("the full picture"))).append("</a>");
            List<String> rec = new ArrayList<>();
            for (String k : List.of("author", "date", "licence")) if (!p.path(k).asText("").isBlank()) rec.add(p.path(k).asText());
            if (!rec.isEmpty()) b.append("<br>").append(esc(String.join(" · ", rec)));
            if (!p.path("provenance").asText("").isBlank()) b.append("<br>").append(esc(t("record: {0}", p.path("provenance").asText())));
            if (!p.path("declared_ai").asText("").isBlank()) b.append("<br>").append(esc(p.path("declared_ai").asText()));
            if (!differ.isEmpty() && !p.path("value").asText("").isBlank()) b.append("<br>").append(esc(CollectionForm.thing(differ) + ": " + p.path("value").asText()));
            b.append("<br>").append(esc(t("why it passed: {0}", p.path("why").asText(""))));
            if (!p.path("entry").asText("").isBlank()) b.append("<br><a href=\"/entry/").append(Pages.enc(p.path("entry").asText())).append("\">").append(esc(t("kept in the library"))).append("</a>");
            b.append("</figcaption></figure>");
        }
        b.append("</div>");
        boolean hosted = Hosted.on(store), kept = false;
        for (JsonNode p : m.path("passed")) if (!p.path("entry").asText("").isBlank()) kept = true;
        if (!running && !m.path("passed").isEmpty() && Pages.mayWrite(store, patron)) {
            if (hosted) b.append("<p class=\"k\">").append(esc(t(Collection.REFUSED_FULL))).append("</p>");
            else if (!kept) b.append("<form method=\"post\" action=\"").append(PATH).append("\"><input type=\"hidden\" name=\"op\" value=\"keep-full\"><input type=\"hidden\" name=\"collection\" value=\"").append(esc(job)).append("\"><button class=\"btn\">")
                    .append(esc(t("Keep these pictures at full size in the library"))).append("</button> <span class=\"k\">").append(esc(t("each file is fetched again and kept on the shelves with its page and why it passed"))).append("</span></form>");
        }
        // what was not counted, so the instrument can be checked by eye
        if (m.path("copies").size() > 0 || m.path("unverified").size() > 0) {
            b.append("<h2>").append(t("Not counted")).append("</h2><div class=\"pics\">");
            for (JsonNode c : m.path("copies")) {
                b.append("<figure><a href=\"").append(esc(c.path("image").asText())).append("\" rel=\"noreferrer\"><img class=\"thumb\" src=\"").append(esc(base + c.path("thumb").asText())).append("\" alt=\"\" loading=\"lazy\"></a><figcaption>")
                 .append(esc(t("a copy of picture {0}: {1}", c.path("of").asInt(), c.path("how").asText("")))).append("<br>").append(esc(c.path("width").asInt() + "×" + c.path("height").asInt()));
                if (!c.path("page").asText("").isBlank()) b.append(" · <a href=\"").append(esc(c.path("page").asText())).append("\" rel=\"noreferrer\">").append(esc(host(c))).append("</a>");
                b.append("</figcaption></figure>");
            }
            for (JsonNode u : m.path("unverified")) {
                b.append("<figure><a href=\"").append(esc(u.path("image").asText())).append("\" rel=\"noreferrer\"><img class=\"thumb\" src=\"").append(esc(base + u.path("thumb").asText())).append("\" alt=\"\" loading=\"lazy\"></a><figcaption>")
                 .append(esc(t("not counted: no record of who made it or when"))).append("<br>").append(esc(u.path("width").asInt() + "×" + u.path("height").asInt()));
                if (!u.path("page").asText("").isBlank()) b.append(" · <a href=\"").append(esc(u.path("page").asText())).append("\" rel=\"noreferrer\">").append(esc(host(u))).append("</a>");
                b.append("</figcaption></figure>");
            }
            b.append("</div>");
        }
        JsonNode searches = m.path("searches");
        if (searches.size() > 0) {
            b.append("<details><summary>").append(esc(t("The searches the model chose ({0})", searches.size()))).append("</summary><ol>");
            for (JsonNode x : searches) b.append("<li>").append(esc(x.path("where").asText() + " \"" + x.path("query").asText() + "\"" + (x.path("page").asInt(1) > 1 ? " · " + t("page {0}", x.path("page").asInt()) : "") + " · "
                    + t("{0} found, {1} kept", x.path("found").asInt(), x.path("kept").asInt()) + (x.path("failed").asText("").isBlank() ? "" : " · " + x.path("failed").asText()))).append("</li>");
            b.append("</ol></details>");
        }
        return b.toString();
    }

    static String capital(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String host(JsonNode p) { return new Images.Found(p.path("image").asText(""), p.path("page").asText(""), "", "", 0, 0, "").host(); }

    /** POST op=keep: a picture from the results onto the shelves, by a keeper or a writer; op=keep-full: a collection's pictures kept at full size, as a job. The note for the page. */
    static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) {
        if (!Pages.mayWrite(store, patron)) return t("Only the person who keeps this library and those who may write to it put pictures on the shelves.");
        if ("keep-full".equals(form.getOrDefault("op", ""))) {
            if (Hosted.on(store)) return t(Collection.REFUSED_FULL);
            try { return t("Filed as {0}: the pictures are fetched again and kept at full size; this page links each to its entry when it is done.", Collection.fileKeep(store, form.getOrDefault("collection", ""), patron.did())); }
            catch (IOException | IllegalArgumentException e) { return t("Not kept: {0}", e.getMessage()); }
        }
        if (!"keep".equals(form.getOrDefault("op", ""))) return "";
        Images.Found f = new Images.Found(form.getOrDefault("image", ""), form.getOrDefault("page", ""), form.getOrDefault("title", ""), form.getOrDefault("source", ""), 0, 0, "");
        try { Images.Kept k = Images.keep(store, f, "", ""); return t("Kept \"{0}\" as {1}, shown at {2}.", f.title().isBlank() ? f.image() : f.title(), k.id(), k.url()); }
        catch (IOException e) { return t("Not kept: {0}", e.getMessage()); }
    }
}
