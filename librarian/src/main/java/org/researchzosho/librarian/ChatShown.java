package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library's own pictures under a chat reply (0.8.0): when a reply names pictures the library keeps ({@code /images/<file>}) or a
 * picture collection ({@code /images?collection=J-…}), and on the line that says a collection is done, the library puts under it the
 * gallery and the pictures themselves. It is kept on the reply's record beside its words ({@code shown}), never in them, so no text a
 * model writes can make it; the Chat page shows it as small pictures, each a link to the whole picture, and the gallery as a link; the
 * chat in a terminal prints it as full addresses, the gallery's and each picture's with its page. On 2026-11-19 a reply listed five kept
 * pictures as {@code /images/4a20….jpg} in code spans, and the person could neither click them nor see them.
 */
final class ChatShown {
    private ChatShown() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The kept pictures and collections a reply names by their addresses. */
    static final Pattern OWN_PICTURE = Pattern.compile("/images/([0-9a-f]{16}\\.(?:jpg|jpeg|png|gif|webp|bmp|tif|tiff|avif))"),
            OWN_COLLECTION = Pattern.compile("/images(?:\\?collection=|/collections/)(J-[0-9]{1,8})");
    /** A collection named by its job's id in the words ("the pictures of collection J-0001"): the library's own identifier, looked up. */
    static final Pattern JOB_ID = Pattern.compile("\\bJ-[0-9]{4,8}\\b");
    /** The most pictures shown under one reply, a collection's default number; the gallery holds the rest. */
    static final int MOST = 20;

    /** What to show under these words: the collections and kept pictures they name; null when they name none the library has. */
    static ObjectNode of(LibraryStore store, String words) {
        if (store == null || words == null) return null;
        Set<String> files = new LinkedHashSet<>(), jobs = new LinkedHashSet<>();
        Matcher m = OWN_PICTURE.matcher(words); while (m.find()) files.add(m.group(1));
        Matcher c = OWN_COLLECTION.matcher(words); while (c.find()) jobs.add(c.group(1));
        Matcher id = JOB_ID.matcher(words); while (id.find()) jobs.add(id.group());   // only a picture collection's has a manifest or a collection job below
        ObjectNode o = M.createObjectNode();
        ArrayNode galleries = o.putArray("galleries"), pictures = o.putArray("pictures");
        for (String job : jobs) {
            ObjectNode man = Collection.manifest(store, job);
            if (man != null) { collection(man, job, galleries, pictures); continue; }
            ObjectNode j = null;   // filed, not started yet: its gallery fills as it runs
            try { j = new Jobs(store, x -> { throw new IllegalStateException("read only"); }).get(job); } catch (IOException | RuntimeException ignored) { }
            if (j != null && Collection.JOB_KIND.equals(j.path("kind").asText())) galleries.addObject().put("job", job).put("path", Collection.gallery(job)).put("kept", 0).put("running", true);
        }
        for (String f : files) {
            if (pictures.size() >= MOST) break;
            Images.Shelved e = Images.entryFor(store, f);
            if (e == null) continue;
            pictures.addObject().put("title", e.title() == null ? "" : e.title()).put("thumb", e.url()).put("full", e.url()).put("page", e.page() == null ? "" : e.page());
        }
        return galleries.isEmpty() && pictures.isEmpty() ? null : o;
    }

    /** What to show under the line that says a collection is done: its gallery, and the first of its pictures; null without its manifest. */
    static ObjectNode collection(ObjectNode man, String job) {
        if (man == null) return null;
        ObjectNode o = M.createObjectNode();
        collection(man, job, o.putArray("galleries"), o.putArray("pictures"));
        return o;
    }

    private static void collection(ObjectNode man, String job, ArrayNode galleries, ArrayNode pictures) {
        galleries.addObject().put("job", job).put("path", Collection.gallery(job)).put("kept", man.path("passed").size()).put("running", "running".equals(man.path("state").asText("")));
        for (JsonNode p : man.path("passed")) {
            if (pictures.size() >= MOST) break;
            if (p.path("thumb").asText("").isBlank()) continue;
            pictures.addObject().put("title", p.path("title").asText("")).put("thumb", "/images/collections/" + job + "/" + p.path("thumb").asText()).put("full", p.path("image").asText("")).put("page", p.path("page").asText(""));
        }
    }

    /** The gallery's line: while the collection runs its pictures come in as they are found; after, how many it kept. */
    static String galleryLine(JsonNode g, Chrome c) {
        return g.path("running").asBoolean(false) ? c.t("The pictures of {0}, on the Pictures page as they are found", g.path("job").asText(""))
                : c.t("The pictures of {0}, {1} kept, on the Pictures page", g.path("job").asText(""), g.path("kept").asInt(0));
    }

    /**
     * The library's own words with the addresses of its galleries in them as links: only for the library's lines (the line that says a
     * collection is filed or done), whose addresses the library wrote; {@code html} is those words already made into the page's HTML.
     */
    static String linked(String html, JsonNode shown) {
        if (shown == null || html == null) return html;
        String out = html;
        for (JsonNode g : shown.path("galleries")) {
            String path = g.path("path").asText("");
            if (!path.startsWith("/images?collection=")) continue;
            String e = Pages.esc(path);
            out = out.replace(e, "<a href=\"" + e + "\">" + e + "</a>");
        }
        return out;
    }

    /** On the Chat page: the gallery as a link (unless the words above already link it), and the pictures small, each a link to the whole picture, its page under it. */
    static String html(JsonNode shown, boolean galleryInWords) {
        if (shown == null || !shown.isObject()) return "";
        StringBuilder b = new StringBuilder("<div class=\"shown\">");
        if (!galleryInWords) for (JsonNode g : shown.path("galleries")) {
            String path = g.path("path").asText("");
            if (!path.startsWith("/images?collection=")) continue;
            b.append("<p class=\"gallery\"><a href=\"").append(Pages.esc(path)).append("\">").append(Pages.esc(galleryLine(g, Chrome.now()))).append("</a></p>");
        }
        boolean any = false;
        for (JsonNode p : shown.path("pictures")) {
            String thumb = p.path("thumb").asText(""), full = p.path("full").asText(""), page = p.path("page").asText(""), title = p.path("title").asText("");
            if (!thumb.startsWith("/images/")) continue;   // the library's own copy only: the page's rule for pictures lets nothing else in from here
            if (!any) { b.append("<div class=\"tiles\">"); any = true; }
            String open = full.startsWith("https://") || full.startsWith("http://") || full.startsWith("/images/") ? full : thumb;
            b.append("<figure class=\"tile\"><a href=\"").append(Pages.esc(open)).append("\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"thumb\" src=\"").append(Pages.esc(thumb))
             .append("\" alt=\"").append(Pages.esc(title)).append("\" title=\"").append(Pages.esc(title)).append("\" loading=\"lazy\"></a>");
            if (page.startsWith("https://") || page.startsWith("http://")) b.append("<figcaption><a href=\"").append(Pages.esc(page)).append("\" target=\"_blank\" rel=\"noopener noreferrer\">").append(Pages.esc(Pages.t("its page"))).append("</a></figcaption>");
            b.append("</figure>");
        }
        if (any) b.append("</div>");
        return b.append("</div>").toString();
    }

    /** The library's own addresses in some words as a terminal opens them: full, and out of the code marks a model put around them. */
    static final Pattern LIBRARY_ADDRESS = Pattern.compile("`?(?<![\\w/.:])(/images(?:\\?collection=J-[0-9]{1,8}|/collections/J-[0-9]{1,8}/[\\w.-]+|/[0-9a-f]{16}\\.[a-z]{3,4}))`?");

    static String forTerminal(String words, String base) {
        if (words == null || words.indexOf("/images") < 0) return words == null ? "" : words;
        String at = base == null ? "" : base.replaceAll("/+$", "");
        Matcher m = LIBRARY_ADDRESS.matcher(words);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(at + m.group(1)));
        m.appendTail(b);
        return b.toString();
    }

    /** In a terminal: the gallery's full address (unless the words above give it), and each picture's whole-picture address and its page, a line each. */
    static String text(JsonNode shown, String base, Chrome c, boolean galleryInWords) {
        if (shown == null || !shown.isObject()) return "";
        String at = base == null ? "" : base.replaceAll("/+$", "");
        StringBuilder b = new StringBuilder();
        if (!galleryInWords) for (JsonNode g : shown.path("galleries")) b.append(galleryLine(g, c)).append(": ").append(at).append(g.path("path").asText("")).append('\n');
        int n = 0;
        for (JsonNode p : shown.path("pictures")) {
            String full = p.path("full").asText(""), page = p.path("page").asText("");
            if (full.startsWith("/")) full = at + full;
            if (full.isBlank()) continue;
            String title = p.path("title").asText("").strip();
            if (title.isEmpty()) b.append("  ").append(++n).append(". ").append(c.t("picture: {0}", full)).append('\n');   // no title: the address once
            else b.append("  ").append(++n).append(". ").append(Images.cut(title, 90)).append('\n').append("     ").append(c.t("picture: {0}", full)).append('\n');
            if (!page.isBlank()) b.append("     ").append(c.t("its page: {0}", page)).append('\n');
        }
        return b.toString().stripTrailing();
    }

    /** Whether some words name every gallery of what is shown, so the line under them need not. */
    static boolean galleryIn(String words, JsonNode shown) {
        if (shown == null || words == null || shown.path("galleries").isEmpty()) return false;
        for (JsonNode g : shown.path("galleries")) if (!words.contains(g.path("path").asText("\u0000"))) return false;
        return true;
    }

    /** A reply as the terminal prints it: its words with the library's addresses full, then what the library shows under it. */
    static String terminal(String words, JsonNode shown, String base, Chrome c) {
        String said = forTerminal(words, base);
        String under = text(shown, base, c, galleryIn(words, shown));
        return under.isEmpty() ? said : said + "\n\n" + under;
    }

    // ---- where the library's pages are, for the addresses a terminal prints ----

    /** The file the service writes its address to when it starts. */
    static Path addressFile(LibraryStore store) { return store.root().resolve("catalog").resolve("pages-address"); }

    /** Written by the service as it starts: the address its pages are served at, the loopback's for a server that listens on every address. */
    static void recordAddress(LibraryStore store, String url) {
        if (store == null || url == null || url.isBlank()) return;
        String u = url.replace("://0.0.0.0:", "://127.0.0.1:").replace("://[::]:", "://127.0.0.1:").replace("://0:0:0:0:0:0:0:0:", "://127.0.0.1:");
        try {
            Path f = addressFile(store), tmp = f.resolveSibling(".pages-address.part");
            Files.createDirectories(f.getParent());
            Files.writeString(tmp, u, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }

    /** The address of the library's pages: where the service last said it serves them, else this computer's default. */
    static String address(LibraryStore store) {
        try { Path f = store == null ? null : addressFile(store); if (f != null && Files.isRegularFile(f)) { String a = Files.readString(f, StandardCharsets.UTF_8).strip(); if (a.matches("https?://[^\\s]+")) return a.replaceAll("/+$", ""); } }
        catch (IOException ignored) { }
        return "http://127.0.0.1:" + LibrarianDaemon.DEFAULT_PORT;
    }
}
