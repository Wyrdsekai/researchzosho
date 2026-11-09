package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The frame every page sits in (0.7.0): the rail at the top with the page it is on marked, Keep for the keeper, every page behind one
 * word, the look switch, the brand's palette light by default and dark by the computer's setting or the switch, and no panel beside
 * the page: the chat is a page of its own.
 */
class PageFrameTest {

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyPageSitsInTheFrame(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            for (String path : new String[]{"/", "/search", "/inbox", "/owned", "/settings", "/keep", "/jobs", "/updates", "/past"}) {
                HttpResponse<String> r = get(c, base + path);
                assertEquals(200, r.statusCode(), path + ": " + r.body());
                String h = r.body();
                for (String g : new String[]{"<header class=\"top\">", "<nav class=\"verbs\">", "href=\"/past\"", "href=\"/updates\"", "<a class=\"keep\" href=\"/keep\"", "<details class=\"contents\"><summary>all pages</summary>",
                        "<b>Library</b>", "<b>Work</b>", "<b>Yours</b>", "href=\"/questions\" title=\"Open questions\"", "<span class=\"ico\">", "class=\"look-switch\"", "data-look=\"system\"", "data-look=\"light\"", "data-look=\"dark\""})
                    assertTrue(h.contains(g), path + " lacks " + g + ": " + h);
                for (String gone : new String[]{"id=\"lib-chat\"", "id=\"site-nav\"", "id=\"nav-toggle\"", "id=\"theme-toggle\"", "/chat/panel"}) assertFalse(h.contains(gone), path + " still carries " + gone);
                // the palette: light unless the computer or the switch says dark, every colour a token, the old six names kept for the pages that style their own
                assertTrue(h.indexOf("localStorage.getItem('rz.theme')") < h.indexOf("<style>"), "the look the browser remembered is set before the stylesheet, so the page never flashes the other one");
                assertTrue(h.contains(":root{color-scheme:light;--paper:#faf3ea;") && h.contains("@media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;--paper:#16121c;") && h.contains(":root[data-theme=dark]{color-scheme:dark;--paper:#16121c;")
                        && h.contains("--bg:var(--paper);--card:var(--paper-2);--line:var(--rule);--k:var(--ink-2)"), path);
                assertTrue(h.contains("<body>") || h.contains("<body class=\"fill\">"), "a page is as long as its content, the chat fills the window: " + path);
            }
            // the rail marks the page it is on: the home and the chat are Chat, the inbox is Updates, the keeper's pages are Keep
            assertTrue(get(c, base + "/").body().contains("<a href=\"/\" aria-current=\"page\">Chat</a>"), "the home is the chat");
            String inbox = get(c, base + "/inbox").body();
            assertTrue(inbox.contains("<a href=\"/updates\" aria-current=\"page\">Updates</a>") && !inbox.contains("href=\"/\" aria-current"), "the inbox is the updates' review page: " + inbox);
            String settings = get(c, base + "/settings").body();   // Settings stands beside Keep in the top menu and is the one marked on its own page (2026-11-07)
            assertTrue(settings.contains("<a class=\"keep\" href=\"/keep\">Keep</a><a class=\"keep\" href=\"/settings\" aria-current=\"page\">Settings</a>"), settings);
            assertTrue(get(c, base + "/sources").body().contains("<a class=\"keep\" href=\"/keep\" aria-current=\"page\">Keep</a><a class=\"keep\" href=\"/settings\">Settings</a>"));
            String keep = get(c, base + "/keep").body();
            assertTrue(keep.contains("<h1>Keep</h1>") && keep.contains("href=\"/settings\"") && keep.contains("href=\"/sources\""), keep);
            HttpResponse<String> home = get(c, base + "/");
            String csp = home.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(csp.contains("script-src 'unsafe-inline'") && csp.contains("connect-src 'self'") && !csp.contains("http"), "the look switch's script is inline and nothing comes from elsewhere: " + csp);
            assertEquals("no-store", home.headers().firstValue("Cache-Control").orElse(""), "a page is never cached by the browser");
            HttpResponse<String> stale = c.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Cookie", Pages.COOKIE + "=no-such-token").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stale.statusCode(), "a token the library no longer knows: forgotten, and the page is served as to any visitor: " + stale.body());
            assertTrue(stale.headers().allValues("Set-Cookie").stream().anyMatch(v -> v.startsWith(Pages.COOKIE + "=;") && v.contains("Max-Age=0")), "and the cookie is cleared: " + stale.headers().map());
            Patrons.set(store, "did:key:zGone", "a former reader", Patrons.Level.deny);
            String gone = Patrons.issueToken(store, "did:key:zGone");
            HttpResponse<String> denied = c.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Cookie", Pages.COOKIE + "=" + gone).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.headers().allValues("Set-Cookie").stream().anyMatch(v -> v.startsWith(Pages.COOKIE + "=;") && v.contains("Max-Age=0")), "a sign-in that is denied now is cleared by the refusal itself: " + denied.headers().map());
            assertTrue(denied.body().contains("that sign-in has been cleared"), denied.body());
            // a reader sees no Keep: the keeper's things are the keeper's
            Patrons.set(store, "did:key:zReader", "a reader", Patrons.Level.read);
            HttpResponse<String> reader = c.send(HttpRequest.newBuilder(URI.create(base + "/search")).header("Cookie", Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zReader")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reader.statusCode(), reader.body());
            assertFalse(reader.body().contains("href=\"/keep\""), "a reader's frame has no Keep: " + reader.body());
            // the chat is a page of its own that fills the window and scrolls its turns in their own box
            HttpResponse<String> chat = get(c, base + "/chat");
            assertEquals(200, chat.statusCode(), chat.body());
            assertTrue(chat.body().contains("<body class=\"fill\">"), "the Chat page fills the window: " + chat.body());
            assertTrue(!chat.body().contains("class=\"chat\"") || chat.body().contains("id=\"chat-scroll\""), "the conversation scrolls in its own box, the box to say the next thing stays put: " + chat.body());
            if (chat.body().contains("id=\"chat-scroll\"")) assertTrue(chat.body().contains("window.addEventListener('load',end)"), "the box opens at the latest reply once the whole page is laid out: " + chat.body());
            assertEquals(404, get(c, base + "/chat/panel").statusCode(), "the panel beside every page is gone");
        } finally { d.stop(); }
    }
}
