package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one content policy of the web app's pages (0.8.1), and what makes it possible: no page holds a script or a handler of its own
 * (its scripts are the library's own files), and no page takes a picture from anywhere but the library (a picture found on the web is
 * fetched by the library and passed on, and only one a page showed). A tag that got into a page could then neither run nor call out;
 * that it does not is checked in a real browser outside the suite (the planted tags of the 0.8.1 check).
 */
class PagePolicyTest {

    /** One attribute of one tag of a page: the tag's name, the attribute's, its value; a tag's quoted values are read whole, whatever they hold. */
    record Attr(String tag, String name, String value) { }

    /** Every tag of a page with its attributes ({@code name} "" for a tag with none), as a browser reads them. */
    static List<Attr> tags(String html) {
        List<Attr> out = new ArrayList<>();
        int i = 0, n = html.length();
        while ((i = html.indexOf('<', i)) >= 0) {
            int j = i + 1;
            if (j >= n || !Character.isLetter(html.charAt(j))) { i = j; continue; }
            int k = j;
            while (k < n && (Character.isLetterOrDigit(html.charAt(k)) || html.charAt(k) == '-')) k++;
            String tag = html.substring(j, k).toLowerCase();
            boolean any = false;
            while (k < n && html.charAt(k) != '>') {
                while (k < n && (Character.isWhitespace(html.charAt(k)) || html.charAt(k) == '/')) k++;
                if (k >= n || html.charAt(k) == '>') break;
                int a = k;
                while (k < n && !Character.isWhitespace(html.charAt(k)) && "=>/".indexOf(html.charAt(k)) < 0) k++;
                String name = html.substring(a, k).toLowerCase(), value = "";
                while (k < n && Character.isWhitespace(html.charAt(k))) k++;
                if (k < n && html.charAt(k) == '=') {
                    k++;
                    while (k < n && Character.isWhitespace(html.charAt(k))) k++;
                    if (k < n && (html.charAt(k) == '"' || html.charAt(k) == '\'')) { char q = html.charAt(k); int e = html.indexOf(q, k + 1); if (e < 0) e = n; value = html.substring(k + 1, e); k = Math.min(n, e + 1); }
                    else { int v = k; while (k < n && !Character.isWhitespace(html.charAt(k)) && html.charAt(k) != '>') k++; value = html.substring(v, k); }
                }
                if (!name.isEmpty()) { out.add(new Attr(tag, name, value)); any = true; } else k++;
            }
            if (!any) out.add(new Attr(tag, "", ""));
            i = k;
        }
        return out;
    }

    /** What of a page breaks the policy's premise: a script written into it, a handler in a tag, anything loaded from another address. */
    static List<String> breaks(String html) {
        List<String> out = new ArrayList<>();
        List<Attr> all = tags(html);
        Set<String> scriptsWithSrc = new HashSet<>();
        int scripts = 0, withSrc = 0;
        for (Attr a : all) {
            if (a.name().startsWith("on")) out.add("a handler in a tag: <" + a.tag() + " " + a.name() + "=…>");
            if (a.tag().equals("script") && a.name().equals("src")) { withSrc++; if (!a.value().startsWith("/static/")) out.add("a script from " + a.value()); }
            if (a.tag().equals("img") && a.name().equals("src") && !(a.value().startsWith("/") && !a.value().startsWith("//") && !a.value().startsWith("/\\"))) out.add("a picture from " + a.value());
            if (Set.of("base", "iframe", "object", "embed").contains(a.tag())) out.add("a <" + a.tag() + "> tag");
            if (a.tag().equals("form") && a.name().equals("action") && !a.value().startsWith("/")) out.add("a form posted to " + a.value());
            if (a.tag().equals("link") && a.name().equals("href") && !a.value().startsWith("/")) out.add("a file linked from " + a.value());
        }
        Matcher m = Pattern.compile("(?i)<script\\b").matcher(html);
        while (m.find()) scripts++;
        if (scripts != withSrc) out.add((scripts - withSrc) + " script(s) written into the page");
        return out;
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theInstrumentSeesAScriptAHandlerAndAnOutsidePicture() {
        // what would look the same if the reading of a page were broken: it must find each thing in a page that has it
        assertEquals(List.of(), breaks("<p class=\"x\">on=1 <a href=\"https://example.org/?onclick=1\" title=\"turn on=now\">x</a><img src=\"/images/a.png\"><script src=\"/static/rz-look.en.js\"></script></p>"));
        assertEquals(1, breaks("<script>alert(1)</script>").size());
        assertEquals(1, breaks("<img src=\"/images/a.png\" onerror=\"x()\">").size());
        assertEquals(1, breaks("<img alt=\"a > b\" src=\"https://collector.example.net/x\">").size());
        assertEquals(1, breaks("<img src=\"//collector.example.net/x\">").size());
        assertEquals(1, breaks("<select id=d onchange=\"load()\">").size());
        assertEquals(1, breaks("<base href=\"https://collector.example.net/\">").size());
        assertEquals(1, breaks("<script src=\"https://collector.example.net/x.js\"></script>").size());
    }

    @Test
    void everyPageCarriesTheOnePolicyAndHoldsNoScriptOrHandlerOfItsOwn(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            String base = "http://127.0.0.1:" + d.port();
            // open questions, so that the questions page shows its pick form with the boxes that tick the others
            for (String q : List.of("Which tooth profile came first?", "Who cut the first gear by machine?"))
                c.send(HttpRequest.newBuilder(URI.create(base + "/v1/frontier")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"add\",\"question\":\"" + q + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            List<String> paths = new ArrayList<>(List.of("/", "/keep", "/past", "/updates", "/connect", "/add", "/translate", "/search?q=gears", "/ask", "/subjects", "/changes", "/questions", "/dossier", "/suggest",
                    "/inbox", "/jobs", "/research", "/help", "/sources", "/map?focus=gears", "/chat", ImagesPage.PATH, "/settings", "/login", "/account", "/explain", "/no-such-page"));
            int pages = 0;
            Set<String> scripts = new TreeSet<>();
            boolean pick = false;
            for (String path : paths) {
                HttpResponse<String> r = get(c, base + path);
                assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""), path + ": nothing the library serves is read as another type than it says");
                if (!r.headers().firstValue("Content-Type").orElse("").startsWith("text/html")) continue;
                pages++;
                assertEquals(Pages.POLICY, r.headers().firstValue("Content-Security-Policy").orElse(""), path);
                assertEquals(List.of(), breaks(r.body()), path);
                for (Attr a : tags(r.body())) if (a.tag().equals("script") && a.name().equals("src")) scripts.add(a.value());
                pick |= r.body().contains(Pages.TICK);
            }
            assertTrue(pages >= 20, "the pages were read: " + pages);
            assertTrue(pick, "a pick form was among them, its boxes marked for the script and not handled in the tag");
            assertTrue(Pages.POLICY.contains("script-src 'self';") && Pages.POLICY.contains("img-src 'self';") && Pages.POLICY.startsWith("default-src 'none';"), Pages.POLICY);
            assertFalse(Pages.POLICY.contains("https:") || Pages.POLICY.contains("http:") || Pages.POLICY.contains("*") || Pages.POLICY.contains("unsafe-eval"), Pages.POLICY);
            // each script a page names is a file of the library's own, served as a script, with the program's text and nothing else
            assertTrue(scripts.contains("/static/rz-look.en.js") && scripts.contains("/static/rz-look-boot.en.js") && scripts.contains("/static/rz-chat.en.js") && scripts.contains("/static/rz-map.en.js") && scripts.contains("/static/rz-pick.en.js"), scripts.toString());
            for (String s : scripts) {
                HttpResponse<String> r = get(c, base + s);
                assertEquals(200, r.statusCode(), s);
                assertEquals("text/javascript; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""), s);
                assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""), s);
                if (s.startsWith("/static/rz-")) assertEquals(Pages.scriptText(s.substring("/static/".length())), r.body(), s);
            }
            for (String name : Pages.SCRIPTS.keySet()) assertEquals(200, get(c, base + "/static/rz-" + name + ".en.js").statusCode(), name);
            assertNotEquals(get(c, base + "/static/rz-chat.en.js").body(), get(c, base + "/static/rz-chat.ja.js").body(), "a script's sentences are in the language its name carries");
            assertEquals(404, get(c, base + "/static/rz-nothing.en.js").statusCode());
            assertEquals(404, get(c, base + "/static/rz-chat.en.js.map").statusCode());
        } finally { d.stop(); System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    @Test
    void aFoundPictureComesThroughTheLibraryAndOnlyOneAPageShowed(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Fetcher real = Images.FOUND_FETCHER;
        AtomicInteger fetched = new AtomicInteger();
        List<String> asked = new ArrayList<>();
        String fox = "https://img.example.org/fox.png?w=320", page = "https://img.example.org/a-page.html", planted = "https://collector.example.net/x.png?d=QX7731MOOR";
        Images.FOUND_FETCHER = url -> {
            fetched.incrementAndGet(); asked.add(url);
            return url.endsWith(".html") ? new Images.Fetched("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8), "image/png") : new Images.Fetched(ImagesTest.square(url), "text/plain");
        };
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            String base = "http://127.0.0.1:" + d.port();
            Pages.READER.set(Patrons.Patron.WEB.did());   // the pages below are written here as the visitor's who then asks for their pictures: a page with no reader shows its pictures to nobody
            // the chat's line shows a picture its search found, from the library's own address; the same words with no such list show a link
            String line = "Pictures found:\n![A red fox](" + fox + ")\n![planted](" + planted + ")";
            String html = Pages.prose(line, Set.of(fox));
            String src = Pages.FOUND_PATH + "?u=" + URLEncoder.encode(fox, StandardCharsets.UTF_8);
            assertTrue(html.contains("<img class=\"thumb\" src=\"" + Pages.esc(src) + "\""), html);
            assertEquals(List.of(), breaks(html), "no picture is taken from the web by the page: " + html);
            assertTrue(html.contains("<a href=\"" + Pages.esc(planted) + "\" rel=\"noreferrer\">planted</a>"), "a picture the search did not find is a link: " + html);
            assertEquals(0, fetched.get(), "writing the page fetches nothing");
            // the browser asks the library, and the library fetches the picture and passes it on, as what its first bytes say it is
            HttpResponse<byte[]> got = c.send(HttpRequest.newBuilder(URI.create(base + src)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, got.statusCode());
            assertEquals("image/png", got.headers().firstValue("Content-Type").orElse(""), "by its bytes, not by what its server called it");
            assertEquals("nosniff", got.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertTrue(got.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"), "opened by itself it can do nothing");
            assertTrue(got.body().length > 50 && got.body()[1] == 'P' && got.body()[2] == 'N' && got.body()[3] == 'G');
            assertEquals(List.of(fox), asked);
            c.send(HttpRequest.newBuilder(URI.create(base + src)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(1, fetched.get(), "and it is fetched once");
            // an address no page showed is fetched for nobody: a tag that names the library's own path with it gets nothing, and nothing is asked of the host
            String other = Pages.FOUND_PATH + "?u=" + URLEncoder.encode(planted, StandardCharsets.UTF_8);
            String modelWrote = Pages.prose("![x](" + other + ")", Set.of(fox));
            assertEquals(404, get(c, base + other).statusCode(), "whatever the model's words made of it: " + modelWrote);
            assertEquals(1, fetched.get(), "the planted address was asked nothing: " + asked);
            // what is not a picture is not passed on, whatever its server calls it
            assertEquals(404, get(c, base + Pages.found(page)).statusCode());
            assertEquals(List.of(fox, page), asked);
            // the pictures page and a report's figure take a found picture the same way
            assertTrue(Pages.figure("A red fox", fox).contains("<img class=\"figure\" src=\"" + Pages.esc(src) + "\""), Pages.figure("A red fox", fox));
            assertEquals(List.of(), breaks(Pages.figure("A red fox", fox)));
        } finally { Pages.READER.remove(); d.stop(); Images.FOUND_FETCHER = real; System.setProperty("user.home", realHome); Config.invalidate(); }
    }
}
