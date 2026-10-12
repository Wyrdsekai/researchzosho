package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The chat as a panel beside every page (0.6.0): mounted everywhere but the Chat page, filled from /chat/panel, a turn answered in place. */
class ChatPanelTest {

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyPageCarriesThePanelAndTheChatPageDoesNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            for (String path : new String[]{"/", "/search", "/inbox", "/owned", "/settings"}) {
                HttpResponse<String> r = get(c, base + path);
                assertEquals(200, r.statusCode(), path + ": " + r.body());
                assertTrue(r.body().contains("id=\"lib-chat\"") && r.body().contains("/chat/panel"), path + " carries the panel");
            }
            HttpResponse<String> home = get(c, base + "/");
            for (String g : new String[]{"<b>Library</b>", "<b>Work</b>", "<b>Yours</b>", "id=\"nav-toggle\"", "href=\"/questions\" title=\"Open questions\"", "<span class=\"ico\">", "id=\"lib-chat-resize\"", "id=\"theme-toggle\"", ":root[data-theme=light]{", "localStorage.getItem('rz.theme')"}) assertTrue(home.body().contains(g), "the menu is grouped, has its button, its symbols and the light-or-dark switch, dark by default, and the chat panel has its drag edge: " + g);
            String csp = home.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(csp.contains("script-src") && csp.contains("connect-src 'self'"), "the panel's script and its fetches are allowed by the page's policy: " + csp);
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
            HttpResponse<String> chat = get(c, base + "/chat");
            assertEquals(200, chat.statusCode(), chat.body());
            assertFalse(chat.body().contains("id=\"lib-chat\""), "the Chat page is the whole conversation, not a panel beside itself");
            assertTrue(!chat.body().contains("class=\"chat\"") || chat.body().contains("id=\"chat-scroll\""), "the conversation scrolls in its own box, the box to say the next thing stays put: " + chat.body());
            // the page fits the window (the box of turns takes what is left), so the latest reply and the box to say the next thing are in view without scrolling the page (2026-10-11)
            assertTrue(chat.body().contains("<body class=\"fill\">"), "the Chat page fills the window: " + chat.body());
            assertTrue(home.body().contains("<body>"), "every other page is as long as its content");
            if (chat.body().contains("id=\"chat-scroll\"")) assertTrue(chat.body().contains("window.addEventListener('load',end)"), "the box opens at the latest reply once the whole page is laid out, the footer included: " + chat.body());
            assertTrue(Pages.CHAT_PANEL_JS.contains("querySelector('.panel-scroll')") && !Pages.CHAT_PANEL_JS.contains("body.scrollTop"), "the panel scrolls its own box of turns to the latest one, not the panel around it: " + Pages.CHAT_PANEL_JS);
            HttpResponse<String> panel = get(c, base + "/chat/panel");
            assertEquals(200, panel.statusCode(), panel.body());
            assertTrue(panel.body().contains("class=\"panel-say\"") || panel.body().contains("Sign in"), "the panel's content is the box to say the next thing, or the sign-in note: " + panel.body());
            if (panel.body().contains("class=\"panel-say\"")) {
                String session = panel.body().replaceAll("(?s).*name=\"session\" value=\"([^\"]+)\".*", "$1");
                HttpResponse<String> turn = c.send(HttpRequest.newBuilder(URI.create(base + "/chat")).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("panel=1&session=" + session + "&say=hello")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, turn.statusCode(), "a turn the panel sent is answered in place, never with a redirect: " + turn.body());
                assertTrue(turn.body().contains("class=\"panel-say\""), "and the panel's content comes back, whatever the model did: " + turn.body());
            }
        } finally { d.stop(); }
    }
}
