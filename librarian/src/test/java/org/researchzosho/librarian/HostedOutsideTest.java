package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Podcasts;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a hosted library sends out and what it shows. It asks no address the fetcher would refuse, by the roads that do not go through
 * the fetcher either: a podcast's feed (followed, or saved as a source), and the files a home search's research names. And it shows its
 * people no path of the service's machine: a file a browser posted is shown by its name, a file inside the library by its place in it,
 * in a page, a JSON answer and an error alike, and a file is found again by the name it was shown under.
 */
class HostedOutsideTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SERVICE = "http://accounts.test";
    static final String FEED = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>A show on this machine's own network</title><item><title>One</title><enclosure url=\"http://127.0.0.1:1/a.mp3\" type=\"audio/mpeg\"/></item></channel></rss>";

    static HttpResponse<String> post(HttpClient c, String base, String statement, String route, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(base + route)).header(Statement.HEADER, statement).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aHostedLibraryAsksNoAddressTheFetcherWouldRefuse(@TempDir Path tmp) throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer inside = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // something on the service's own machine
        inside.createContext("/", x -> { asked.incrementAndGet(); byte[] b = FEED.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/rss+xml"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        inside.start();
        String at = "http://127.0.0.1:" + inside.getAddress().getPort();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        try {
            // a library on its own asks it, as it always did: the address answers and these roads lead to it
            assertNotNull(Podcasts.feed(at + "/feed", 5), "the feed is read");
            assertNotNull(HomeRecordsResearch.probe(at + "/data.csv"));
            assertTrue(HomeFiles.download(at + "/data.csv", tmp.resolve("got.csv")));
            int before = asked.get();
            assertTrue(before >= 3, "the stand-in was asked " + before + " times");
            // a hosted library does not
            KeyPair service = Statement.newKey();
            LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching(SERVICE, kid -> service.getPublic()));
            try {
                assertNull(Podcasts.feed(at + "/feed", 5));
                assertNull(HomeRecordsResearch.probe(at + "/data.csv"));
                assertFalse(HomeFiles.download(at + "/data.csv", tmp.resolve("not-got.csv")));
                HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                String base = "http://127.0.0.1:" + d.port(), writer = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, store.identity().id(), "a1", "Ann", Statement.WRITE, Statement.SESSION));
                HttpResponse<String> followed = post(c, base, writer, "/v1/follow", "{\"op\":\"follow\",\"feed\":\"" + at + "/feed\"}");
                assertFalse(followed.body().contains("A show on this machine's own network"), "the feed's title did not come back: " + followed.body());
                post(c, base, writer, "/v1/sources", "{\"op\":\"add\",\"address\":\"podcast " + at + "/feed\"}");
                post(c, base, writer, "/v1/serials", "{\"op\":\"add\",\"name\":\"inside\",\"query\":\"podcast " + at + "/feed\"}");
                assertEquals(before, asked.get(), "nothing on the service's own machine was asked");
            } finally { d.stop(); }
            assertNotNull(Podcasts.feed(at + "/feed", 5), "and the library stopped, this process asks it again");
        } finally { inside.stop(0); }
    }

    @Test
    void aHostedLibraryShowsNoPathOfTheServicesMachine(@TempDir Path tmp) throws Exception {
        Path folder = tmp.resolve("accounts").resolve("libraries").resolve("ann-example");   // as the service keeps a library: <folder>/library
        LibraryStore store = new LibraryStore(folder.resolve("library")); store.init();
        Path posted = Uploads.newFolder().resolve("harbour-notes.txt");   // a file as a browser's upload is written
        Files.writeString(posted, "Harbour notes.\n\nThe first reader asked for a map of the harbour as it was in 1870, and the desk had none.\n");
        String upload = posted.getParent().toString(), temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() + "/" + Uploads.DIR;
        KeyPair service = Statement.newKey();
        assertEquals("a path " + folder + "/library/exports/x.pdf", Hosted.shown("a path " + folder + "/library/exports/x.pdf"), "outside a hosted library a text is as it is");
        // a stand-in for the service's answer to a use (0.8.0, stage 3): a document sent is asked for, so its keeper's storage is checked
        HttpServer uses = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        uses.createContext("/", x -> { x.getRequestBody().readAllBytes(); byte[] b = "{\"use\":\"use_t\",\"state\":\"open\",\"said\":\"\",\"ceiling_dollars\":-1,\"ceiling_minutes\":-1}".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        uses.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, new Vouching(SERVICE, "http://127.0.0.1:" + uses.getAddress().getPort(), kid -> service.getPublic()));
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = "http://127.0.0.1:" + d.port(), writer = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, store.identity().id(), "a1", "Ann", Statement.WRITE, Statement.SESSION));
        try {
            // the rule itself
            assertEquals("file://harbour-notes.txt", Hosted.shown("file://" + posted));
            assertEquals("exports/report.pdf", Hosted.shown(folder + "/library/exports/report.pdf"));
            assertEquals("its log is serve.log", Hosted.shown("its log is " + folder + "/serve.log"));
            assertEquals("q=" + "file%3A%2F%2Fharbour-notes.txt", Hosted.shown("q=" + URLEncoder.encode("file://" + posted, StandardCharsets.UTF_8)), "as an address carries it, too");
            // a file a browser posted, added: every answer about it, without the machine's path
            HttpResponse<String> added = post(c, base, writer, "/v1/add", "{\"path\":\"" + posted + "\"}");
            assertEquals(200, added.statusCode(), added.body());
            HttpResponse<String> found = post(c, base, writer, "/v1/search", "{\"query\":\"harbour 1870\"}");
            assertTrue(found.body().contains("file://harbour-notes.txt"), found.body());
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(URI.create(base + "/search?q=harbour")).header(Statement.HEADER, writer).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(page.body().contains("harbour-notes"), "the page lists it");
            HttpResponse<String> missing = post(c, base, writer, "/v1/read", "{\"locator\":\"file://" + folder + "/library/no-such-file.txt\"}");
            HttpResponse<String> status = post(c, base, writer, "/v1/status", "{}");
            String raw = M.readTree(added.body()).path("raw").asText();
            HttpResponse<String> entry = post(c, base, writer, "/v1/get", "{\"id\":\"" + raw + "\"}");
            HttpResponse<String> exported = post(c, base, writer, "/v1/export", "{\"id\":\"" + raw + "\",\"format\":\"md\"}");
            for (HttpResponse<String> r : new HttpResponse[]{added, found, page, missing, status, entry, exported}) {
                for (String path : new String[]{folder.toString(), tmp.toString(), upload, temporary, URLEncoder.encode(folder.toString(), StandardCharsets.UTF_8), URLEncoder.encode(upload, StandardCharsets.UTF_8)})
                    assertFalse(r.body().contains(path), r.uri() + " shows " + path + ": " + r.body().substring(0, Math.min(400, r.body().length())));
            }
            // and the file is found again by the name it was shown under
            HttpResponse<String> read = post(c, base, writer, "/v1/read", "{\"locator\":\"file://harbour-notes.txt\"}");
            assertEquals(200, read.statusCode(), read.body());
            JsonNode text = M.readTree(read.body());
            assertTrue(text.path("text").asText().contains("a map of the harbour as it was in 1870"), read.body());
            assertEquals("file://harbour-notes.txt", text.path("locator").asText());
        } finally { d.stop(); uses.stop(0); }
    }
}
