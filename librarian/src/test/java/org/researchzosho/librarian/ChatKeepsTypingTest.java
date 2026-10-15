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
 * What is typed in the chat's box is never lost (2026-10-12). The Chat page sends a turn from its own script and puts the reply in place
 * of the conversation, never of the box; the panel keeps its box through a reply and through the refresh it does while a run is going;
 * both keep the draft in the browser for the tab. Before this a reply came as a whole new page, and the panel redrew its box every half
 * minute while a run was going, so anything typed while the Librarian worked was gone.
 */
class ChatKeepsTypingTest {

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient c, String url, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theChatPageSendsATurnFromItsScriptAndGetsBackTheConversationNotTheBox(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            HttpResponse<String> page = get(c, base + "/chat?session=" + s.id);
            assertEquals(200, page.statusCode(), page.body());
            String html = page.body();
            for (String g : new String[]{"id=\"chat-view\"", "id=\"chat-say\"", "id=\"chat-scroll\"", "autocomplete=\"off\"", "fd.append('page','1')", "'rz.chat.draft'", "window.addEventListener('load',end)"})
                assertTrue(html.contains(g), "the page has its changing part, its box, and the script that keeps the box: " + g);
            assertTrue(html.indexOf("id=\"chat-say\"") > html.indexOf("id=\"chat-view\""), "the box comes after the changing part, outside it");
            assertFalse(html.contains("setInterval"), "the strip of runs is refreshed by the page's one script, not by a script of its own");
            // a turn sent the way the script sends it: what comes back is the changing part alone, never the page or its box
            HttpResponse<String> turn = post(c, base + "/chat", "page=1&session=" + s.id + "&say=hello+from+the+page");
            assertEquals(200, turn.statusCode(), "answered in place, never with a redirect: " + turn.body());
            String frag = turn.body();
            assertTrue(frag.contains("id=\"chat-scroll\"") && frag.contains("class=\"chat\""), frag);
            assertFalse(frag.contains("<html") || frag.contains("id=\"chat-say\"") || frag.contains("name=\"say\" autofocus"), "the box is never sent back: " + frag);
            // the same with no conversation named: the latest one answers
            HttpResponse<String> latest = post(c, base + "/chat", "page=1&say=again");
            assertEquals(200, latest.statusCode(), latest.body());
            assertTrue(latest.body().contains("id=\"chat-scroll\""), latest.body());
        } finally { d.stop(); }
    }

    @Test
    void thePanelKeepsItsBoxAndBothScriptsKeepTheDraft(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session.open(store);
            HttpResponse<String> panel = get(c, base + "/chat/panel");
            assertEquals(200, panel.statusCode(), panel.body());
            assertTrue(panel.body().contains("<form id=\"panel-say\" class=\"panel-say\""), "the box the script keeps is named: " + panel.body());
            assertEquals(1, panel.body().split("id=\"panel-say\"", -1).length - 1, "one box; the Yes and No forms are not it");
        } finally { d.stop(); }
        String js = Pages.CHAT_PANEL_JS;
        assertTrue(js.contains("getElementById('panel-say')") && js.contains("body.insertBefore(") && js.contains("osid.value=sid.value"), "the panel's content is rebuilt around the box already there: " + js);
        assertTrue(js.contains("querySelectorAll('button')") && !js.contains("'button,input'"), "while the model works the buttons are held, the box is not: " + js);
        assertFalse(js.contains("then(function(h){body.innerHTML=h"), "a reply or a refresh never redraws the box: " + js);
        for (String s : new String[]{Pages.CHAT_PANEL_JS, Pages.CHAT_PAGE_JS}) {
            assertTrue(s.contains("sessionStorage.getItem(dkey)") && s.contains("sessionStorage.setItem(dkey,") && s.contains("sessionStorage.removeItem(dkey)"), "the draft is kept by the browser for the tab, and cleared once sent: " + s);
            assertTrue(s.contains("dkey='rz.chat.draft'") && s.contains("if(busy)return"), "one key for both, and a second turn waits for the first: " + s);
        }
    }
}
