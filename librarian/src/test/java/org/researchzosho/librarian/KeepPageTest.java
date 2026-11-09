package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Keep (0.7.0): the keeper's things behind one link, each behind a fold with its gist; a reader is told the keeper looks after them. */
class KeepPageTest {

    static HttpResponse<String> get(HttpClient c, String url, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theKeepersThingsBehindOneLink(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String h = get(c, base + "/keep", null).body();
            assertTrue(h.contains("<h1>Keep</h1>") && h.contains("<a class=\"keep\" href=\"/keep\" aria-current=\"page\">Keep</a>"), h);
            for (String id : new String[]{"connection", "sources", "people", "tally", "settings", "more"}) assertTrue(h.contains("<details class=\"sec\" id=\"" + id + "\"><summary><h2>"), "a fold for " + id + ": " + h);
            assertTrue(h.contains("<h2>Connection and models</h2><span class=\"gist\">1 model named · the drive at http://127.0.0.1:1</span>"), "the connection's gist: " + h);
            assertTrue(h.contains("<a class=\"btn\" href=\"/connect\">Connect another, or test one</a>") && h.contains("href=\"https://researchzosho.org/models/\""), h);
            assertTrue(h.contains("<h2>Sources and their yield</h2><span class=\"gist\">0 sources</span>"), h);
            assertTrue(h.contains("<h2>People and access</h2><span class=\"gist\">0 people listed; anyone else may ") && h.contains("name=\"op\" value=\"invite\""), "the readers' forms inside the fold: " + h);
            assertTrue(h.contains("<h2>The tally</h2><span class=\"gist\">nothing spent</span>"), h);
            assertTrue(h.contains("<h2>Settings</h2><span class=\"gist\">") && h.contains("<select name=\"name\">") && h.contains("<code>drive</code>"), "the settings form and table inside the fold: " + h);
            assertTrue(h.contains("<h2>Also</h2>") && h.contains("href=\"/jobs\">Runs</a>") && h.contains("researchzosho vault"), h);
            assertFalse(h.contains("<h2>Readers</h2>") || h.contains("<h2>Models</h2>"), "the folds carry the headings, the sections do not repeat them: " + h);
            // the Settings page still answers with the same sections, unfolded
            String s = get(c, base + "/settings", null).body();
            assertTrue(s.contains("<h2>Models</h2>") && s.contains("<h2>Readers</h2>") && s.contains("<code>drive</code>"), s);
            // a reader is told
            Patrons.set(store, "did:key:zReader", "a reader", Patrons.Level.read);
            String reader = get(c, base + "/keep", Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zReader")).body();
            assertTrue(reader.contains("You read this library; its keeper looks after those.") && !reader.contains("<details class=\"sec\" id=\"settings\">"), reader);
        } finally { d.stop(); }
    }
}
