package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Connect (0.7.0): the first screen in the chat until a model has answered once, and the page of its own. */
class ConnectScreenTest {

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theFirstScreenUntilAModelAnswers(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            synchronized (Pages.DRIVE_UP) { Pages.DRIVE_UP[1] = 0; }
            String h = get(c, base + "/").body();
            assertTrue(h.contains("<div class=\"first\"><div class=\"connect\">") && h.contains("<h2 class=\"plain\">Connect a model</h2>") && h.contains("id=\"chat-say\""), "no model has answered: the chat home opens with Connect, the composer still there: " + h);
            for (String g : new String[]{"<h2>A model you own, on this machine</h2>", "name=\"op\" value=\"look\"", "<h2>One you rent</h2>", "<option value=\"anthropic\">Anthropic (Claude)</option>", "type=\"password\" name=\"key\"", "href=\"/add\">Add a file or a page</a>", "href=\"https://researchzosho.org/models/\"", "href=\"/keep\">Keep</a>"})
                assertTrue(h.contains(g), "Connect lacks " + g + ": " + h);
            assertFalse(h.contains("127.0.0.1:1 answered"), h);
            // the page of its own, and a test of an address that answers nothing
            String own = get(c, base + "/connect").body();
            assertTrue(own.contains("<h1>Connect a model</h1>") || own.contains("<h2 class=\"plain\">Connect a model</h2>"), own);
            HttpResponse<String> test = c.send(HttpRequest.newBuilder(URI.create(base + "/connect")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("op=test&address=http%3A%2F%2F127.0.0.1%3A1&model=")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, test.statusCode(), test.body());
            String after = get(c, base + test.headers().firstValue("Location").orElse("/connect")).body();
            assertTrue(after.contains("<p class=\"notice\">http://127.0.0.1:1 does not answer.</p>"), "the test says what it found: " + after);
            // once a model has answered, the chat is the home whatever the model does today
            Pages.modelAnswered(store);
            assertTrue(Files.exists(Pages.answeredMarker(store)));
            String later = get(c, base + "/").body();
            assertFalse(later.contains("<div class=\"first\">"), "Connect has left the home: " + later);
            assertTrue(get(c, base + "/connect").body().contains("Connect a model"), "and stays a page of its own, reached from Keep");
            assertTrue(get(c, base + "/keep").body().contains("href=\"/connect\""), "Keep leads to it");
        } finally { d.stop(); }
    }
}
