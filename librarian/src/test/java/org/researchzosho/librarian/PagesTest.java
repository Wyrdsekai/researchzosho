package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The library in a browser: the pages render from a real store, and writing needs the cookie. */
class PagesTest {

    private static Finding f(String id, String title, String body, String locator) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "the museum's own page")), List.of(), null, body);
    }

    static HttpResponse<String> get(HttpClient c, String url, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient c, String url, String form, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theLibraryInABrowser(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum states the **gears** were cut with files.\n\n- a dividing plate\n- hand files\n\nSee [[F-0002-teeth]].\n", "https://museum.example/gears"));
        store.write(f("F-0002-teeth", "Tooth profiles measured by CT", "CT scans show triangular teeth. https://journal.example/paper\n", "https://journal.example/paper"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        java.nio.file.Files.writeString(store.subjectsFile(), "# Subjects\n\n- gears--cutting — how the gears were made\n");
        store.frontier("gap person", "Who measured the tooth profiles first, and with what instrument?");
        Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
        String token = Patrons.issueToken(store, "did:key:me");
        Explain.DRIVES = () -> null;   // no model in this test: a reading that is not cached says so (the configured drive is real on the dev box)
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            var home = get(c, base + "/", null);
            assertEquals(200, home.statusCode());
            assertTrue(home.headers().firstValue("content-type").orElse("").startsWith("text/html"));
            assertTrue(home.body().contains("2 claims") && home.body().contains("Open questions") && home.body().contains("sign in"), home.body());

            var search = get(c, base + "/search?q=gears", null);
            assertTrue(search.body().contains("/entry/F-0001-gears") && search.body().contains("The gears were cut by hand"), search.body());

            var entry = get(c, base + "/entry/F-0001-gears", null);
            assertTrue(entry.body().contains("<b>gears</b>"), "markdown bold renders");
            assertTrue(entry.body().contains("<li>a dividing plate</li>"), "markdown lists render");
            assertTrue(entry.body().contains("href=\"/entry/F-0002-teeth\""), "[[wikilinks]] become shelf links");
            assertTrue(entry.body().contains("href=\"https://museum.example/gears\"") && entry.body().contains("the museum&#39;s own page".replace("&#39;", "'")), "sources are listed with their why");
            assertTrue(entry.body().contains("/search?subject=gears--cutting"), "subjects link to a scoped search");

            var ask = get(c, base + "/ask?q=how+were+the+gears+cut", null);
            assertTrue(ask.body().contains("class=\"card\"") && ask.body().contains("F-0001-gears"), ask.body());
            var nothing = get(c, base + "/ask?q=what+colour+is+the+moon+of+jupiter", null);
            assertTrue(nothing.body().contains("has nothing on this") && nothing.body().contains("/research?q="), nothing.body());

            assertTrue(entry.body().contains("/explain?id=F-0001-gears&rung=beginner"), "the rung ladder is on the entry");
            assertTrue(entry.body().contains("/download?id=F-0001-gears&as=pdf"), "download links are on the entry");
            var dl = get(c, base + "/download?id=F-0001-gears&as=md", null);
            assertEquals(200, dl.statusCode());
            assertTrue(dl.headers().firstValue("content-disposition").orElse("").contains("F-0001-gears.md"));
            assertTrue(dl.body().startsWith("# The gears were cut by hand"));
            var pdf = c.send(HttpRequest.newBuilder(URI.create(base + "/download?id=F-0001-gears&as=pdf")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, pdf.statusCode());
            assertTrue(pdf.headers().firstValue("content-type").orElse("").startsWith("application/pdf") && pdf.body().length > 1000);
            var written = get(c, base + "/explain?id=F-0001-gears&rung=written", null);
            assertEquals(200, written.statusCode());
            assertTrue(written.body().contains("<b>as written</b>") && written.body().contains("a dividing plate"), written.body());
            var needsDrive = get(c, base + "/explain?id=F-0001-gears&rung=beginner", null);
            assertEquals(503, needsDrive.statusCode());
            assertTrue(needsDrive.body().contains("No model is answering"), needsDrive.body());
            // with a (slow) model: the page comes back at once saying what is happening, refreshes, and becomes the reading
            Explain.DRIVES = () -> new Researcher.Drive() {
                @Override public com.fasterxml.jackson.databind.node.ObjectNode chat(com.fasterxml.jackson.databind.node.ArrayNode m, com.fasterxml.jackson.databind.node.ArrayNode tl, int x, String y) { throw new UnsupportedOperationException(); }
                @Override public int contextWindow() { return 32_000; }
                @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode m, int max) {
                    try { Thread.sleep(400); } catch (InterruptedException e) { }
                    String p = m.get(m.size() - 1).path("content").asText();
                    return p.startsWith("A report sentence cites") ? "{\"verdict\":\"supported\"}" : "Someone cut each tooth with a file [F-0001-gears].\n\n## Terms\n- file — a hand tool\n";
                }
            };
            var working = get(c, base + "/explain?id=F-0001-gears&rung=beginner", null);
            assertEquals(200, working.statusCode());
            assertTrue(working.body().contains("http-equiv=\"refresh\"") && working.body().contains("The library is"), working.body());
            String readingBody = null;
            for (int i = 0; i < 50 && readingBody == null; i++) {
                Thread.sleep(200);
                var again = get(c, base + "/explain?id=F-0001-gears&rung=beginner", null);
                if (again.body().contains("Someone cut each tooth")) readingBody = again.body();
            }
            assertNotNull(readingBody, "the working page turns into the reading");
            assertFalse(readingBody.contains("http-equiv=\"refresh\""), "a finished reading does not refresh");
            assertTrue(readingBody.contains("/explain?term=file&in=F-0001-gears&rung=beginner"), "terms link onward");
            Explain.DRIVES = () -> null;
            assertTrue(get(c, base + "/subjects", null).body().contains("how the gears were made"));
            var qp = get(c, base + "/questions", null);
            assertTrue(qp.body().contains("tooth profiles first"));
            assertTrue(qp.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'unsafe-inline'"), "a pick form's \"all\" box needs its inline handler allowed");
            assertFalse(get(c, base + "/changes", null).headers().firstValue("Content-Security-Policy").orElse("").contains("script-src"), "other pages stay script-free");
            assertTrue(get(c, base + "/changes", null).body().contains("F-0002-teeth"));
            assertTrue(get(c, base + "/jobs", null).body().contains("Nothing is running"));
            var map = get(c, base + "/map?focus=gears", null);
            assertEquals(200, map.statusCode());
            assertTrue(map.body().contains("<nav>") && map.body().contains("id=\"c\"></canvas>") && map.body().contains("main class=\"wide\""), "the map sits in the site frame with the nav");
            assertTrue(map.headers().firstValue("content-security-policy").orElse("").contains("script-src 'unsafe-inline'"));
            assertEquals(404, get(c, base + "/entry/F-9999-nope", null).statusCode());
            assertEquals(404, get(c, base + "/nothing-here", null).statusCode(), "a stray path is still the API's 404, not a page");

            var researchForm = get(c, base + "/research", null);
            assertTrue(researchForm.body().contains("Sharpen it first"), "the research page offers to sharpen the question first");
            var noDrive = post(c, base + "/research", "question=" + Pages.enc("how were the gears cut") + "&sharpen=1", null);
            assertEquals(503, noDrive.statusCode());
            assertTrue(noDrive.body().contains("No model drive answers"), noDrive.body());
            // with a slow model: the sharpen post comes back at once as a working page, which turns into the sharpened form
            Explain.DRIVES = () -> new Researcher.Drive() {
                @Override public com.fasterxml.jackson.databind.node.ObjectNode chat(com.fasterxml.jackson.databind.node.ArrayNode m, com.fasterxml.jackson.databind.node.ArrayNode tl, int x, String y) { throw new UnsupportedOperationException(); }
                @Override public int contextWindow() { return 32_000; }
                @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode m, int max) {
                    try { Thread.sleep(300); } catch (InterruptedException e) { }
                    String p = m.get(m.size() - 1).path("content").asText();
                    if (p.startsWith("A person typed a research question")) return "{\"question\":\"How were the Antikythera gears cut?\",\"assumptions\":[\"the Antikythera mechanism\"],\"sub_questions\":[\"What tools survive?\"],\"depth\":\"depth\",\"size\":\"quick\"}";
                    return "[]";
                }
            };
            var sharpening = post(c, base + "/research", "question=" + Pages.enc("how were the gears cut") + "&sharpen=1", null);
            assertEquals(200, sharpening.statusCode());
            assertTrue(sharpening.body().contains("Sharpening the question") && sharpening.body().contains("http-equiv=\"refresh\""), sharpening.body());
            String sharpenedBody = null;
            for (int i = 0; i < 60 && sharpenedBody == null; i++) {
                Thread.sleep(200);
                String key = Pages.sharpenKey("how were the gears cut");
                var again = get(c, base + "/research?sharpen=" + key, null);
                if (again.body().contains("How were the Antikythera gears cut?")) sharpenedBody = again.body();
            }
            assertNotNull(sharpenedBody, "the working page turns into the sharpened form");
            assertTrue(sharpenedBody.contains("name=\"once\"") && sharpenedBody.contains("the Antikythera mechanism"), sharpenedBody);
            Explain.DRIVES = () -> null;
            // a send carries a one-time token: the same token again does not file a second job
            String once = "abc123";
            var first = post(c, base + "/research", "question=" + Pages.enc("Who cut the gears of the Antikythera mechanism, and how?") + "&mode=broad&sources=shelves&once=" + once, null);
            assertEquals(200, first.statusCode());   // no drive in the test: it reports that, before any job is filed
            Pages.SENT.put(once, "J-0042");         // as a filed send would have
            var second = post(c, base + "/research", "question=" + Pages.enc("Who cut the gears of the Antikythera mechanism, and how?") + "&mode=broad&sources=shelves&once=" + once, null);
            assertTrue(second.body().contains("already sent") && second.body().contains("J-0042"), second.body());
            // as shipped the pages are open: a browser may send a question with no sign-in, and the home page says so
            assertTrue(home.body().contains("open to everyone") && home.body().contains("web signin on"), home.body());
            var open = post(c, base + "/research", "question=" + Pages.enc("Who cut the gears of the Antikythera mechanism, and how?") + "&mode=broad&sources=shelves", null);
            assertEquals(200, open.statusCode());
            assertTrue(open.body().contains("No model drive answers"), "it got as far as the drive check, so it was allowed: " + open.body());
            // with the sign-in on: refused without a token, and the page says how to sign in
            WebAccess.OVERRIDE = Boolean.TRUE;
            var refused = post(c, base + "/research", "question=" + Pages.enc("Who cut the gears of the Antikythera mechanism, and how?") + "&mode=broad&sources=shelves", null);
            assertEquals(403, refused.statusCode());
            assertTrue(refused.body().contains("/login"), refused.body());
            assertFalse(get(c, base + "/", null).body().contains("open to everyone"));

            // a bad token is turned away; a good one sets the cookie and sends you home
            assertEquals(403, post(c, base + "/login", "token=nope", null).statusCode());
            var login = post(c, base + "/login", "token=" + token, null);
            assertEquals(303, login.statusCode());
            String cookie = login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            assertTrue(cookie.startsWith(Pages.COOKIE + "="));
            assertTrue(get(c, base + "/", cookie).body().contains("sign out"));
            // signed in, the research form posts; no drive answers in the test, so the page reports that rather than filing
            var filed = post(c, base + "/research", "question=" + Pages.enc("Who cut the gears of the Antikythera mechanism, and how?") + "&mode=broad&sources=shelves", cookie);
            assertEquals(200, filed.statusCode());
            assertTrue(filed.body().contains("No model drive answers"), filed.body());
        } finally { d.stop(); Explain.DRIVES = Explain::configuredDrive; WebAccess.OVERRIDE = null; }
    }

    @Test
    void theLittleMarkdownRenderer() {
        assertEquals("<h2>Title</h2><p>One <b>two</b> <code>three</code>.</p><ul><li>a</li><li>b</li></ul><p><a href=\"https://x.org/p\" rel=\"noreferrer\">https://x.org/p</a> and <a href=\"https://y.org\" rel=\"noreferrer\">y</a></p>",
                Pages.md("# Title\n\nOne **two** `three`.\n\n- a\n- b\n\nhttps://x.org/p and [y](https://y.org)\n"));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", Pages.md("<script>alert(1)</script>"), "page text is escaped");
        assertEquals("<p>How ‘Tokyo Vice’ Secured Japan's Capital &amp; more</p>", Pages.md("How &#8216;Tokyo Vice&#8217; Secured Japan&#039;s Capital &amp; more"), "entities captured as text become characters, then are escaped once");
        assertEquals("<p>&lt;b&gt;x&lt;/b&gt;</p>", Pages.md("&lt;b&gt;x&lt;/b&gt;"), "a decoded tag is still text, not markup");
        assertEquals("<p class=\"ref\" id=\"ref-1\"><b>[1]</b> A title — <a href=\"https://a.example/x\" rel=\"noreferrer\">https://a.example/x</a></p><p class=\"ref\" id=\"ref-2\"><b>[2]</b> <a href=\"https://b.example/y\" rel=\"noreferrer\">https://b.example/y</a><br>also <a href=\"https://b.example/z\" rel=\"noreferrer\">https://b.example/z</a></p>",
                Pages.md("[1] A title — https://a.example/x\n[2] https://b.example/y  also https://b.example/z\n"), "reference lines are one row each");
        assertEquals("<p class=\"src\"><a href=\"https://a.example/x\" rel=\"noreferrer\">https://a.example/x</a></p><p class=\"src\"><a href=\"https://b.example/y\" rel=\"noreferrer\">https://b.example/y</a> (accessed via a repost)</p>",
                Pages.md("https://a.example/x\nhttps://b.example/y (accessed via a repost)\n"), "source lines never run together");
        String linked = Pages.withRefs(Pages.md("| claim | source | quote |\n|---|---|---|\n| Twenty staff. | [1] | as many as 20 |\n\n[1] Hollywood Reporter piece — https://www.hollywoodreporter.com/tv/x\n"),
                "| claim | source | quote |\n|---|---|---|\n| Twenty staff. | [1] | as many as 20 |\n\n[1] Hollywood Reporter piece — https://www.hollywoodreporter.com/tv/x\n");
        assertTrue(linked.contains("<td><a href=\"#ref-1\">[1]</a><br><span class=\"k\">Hollywood Reporter piece</span></td>"), linked);
        assertEquals("<h3>Evidence</h3><div class=\"tablewrap\"><table><tr><th>claim</th><th>source</th><th>quote</th></tr><tr><td>Twenty staff for one night.</td><td>[1]</td><td>could count as many as 20</td></tr></table></div><p>After.</p>",
                Pages.md("## Evidence\n\n| claim | source | quote |\n|---|---|---|\n| Twenty staff for one night. | [1] | could count as many as 20 |\n\nAfter.\n"), "a markdown table renders as a table");
    }
}
