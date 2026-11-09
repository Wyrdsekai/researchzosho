package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A kept picture is served at /images/<file> as a picture, to a reader of the library, and nothing outside catalog/images/ is. */
class ImagesRouteTest {

    String home; Images.Fetcher fetcher;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); fetcher = Images.FETCHER; Images.FETCHER = url -> new Images.Fetched(Vision.square(0, 0, 255), "image/png"); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.FETCHER = fetcher; }

    @Test void theKeptPictureIsServedAtItsAddress(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Kept k = Images.keep(store, new Images.Found("https://a.example/blue.png", "https://a.example/", "A blue square", "Brave", 0, 0, ""), "", "");
        assertEquals("/images/" + Images.sha256(Vision.square(0, 0, 255)).substring(0, 16) + ".png", k.url());
        ApiKeys.Minted key = ApiKeys.mint(store, Accounts.OWNER, "the test", Patrons.Level.read);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(base + k.url())).header("Authorization", "Bearer " + key.secret()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode(), new String(r.body()));
            assertEquals("image/png", r.headers().firstValue("Content-Type").orElse(""));
            assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertArrayEquals(Vision.square(0, 0, 255), r.body(), "the bytes as kept");
            assertTrue(r.headers().firstValue("Content-Disposition").isEmpty(), "shown in the page, not downloaded");
            HttpResponse<String> gone = c.send(HttpRequest.newBuilder(URI.create(base + "/images/nothing.png")).header("Authorization", "Bearer " + key.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, gone.statusCode()); assertTrue(gone.body().contains("No picture is kept under the name nothing.png"), gone.body());
            HttpResponse<String> outside = c.send(HttpRequest.newBuilder(URI.create(base + "/images/..%2Flibrary.md")).header("Authorization", "Bearer " + key.secret()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, outside.statusCode(), "nothing outside catalog/images/ is served");
        } finally { d.stop(); }
    }
}
