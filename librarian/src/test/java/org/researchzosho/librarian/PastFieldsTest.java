package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Past's rows for the books and podcasts fields (0.7.0): their reports by their field, the shelf and the shows followed as reports of their shape, each with a page. */
class PastFieldsTest {

    static final ObjectMapper M = new ObjectMapper();

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static void done(Jobs jobs, String id, String field, String question, String inv, long now) throws Exception {
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        ObjectNode j = M.createObjectNode(); j.put("job_id", id); j.put("kind", "research"); j.put("state", "done"); j.put("patron", "person");
        j.put("queued_at", Instant.ofEpochMilli(now - 4_000_000).toString()); j.put("started_at", Instant.ofEpochMilli(now - 3_900_000).toString()); j.put("ended_at", Instant.ofEpochMilli(now - 3_600_000).toString());
        j.putObject("args").put("question", question).put("field", field); j.put("result", "investigation " + inv); j.put("investigation", inv);
        Files.writeString(month.resolve(id + ".json"), M.writeValueAsString(j));
    }

    @Test
    void theFieldsReportsAndTheirKeptWorkAreRows(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        long now = System.currentTimeMillis();
        store.write(new Investigation("I-0001-which-book-on-eclipses", "Which book on eclipses for a beginner?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\nA table of three.\n"));
        store.write(new Investigation("I-0002-what-the-host-said", "What did the host say about the saros?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_500_000).toString(), List.of(), List.of(), "## Answer\n\nAt 12:40 of the episode.\n"));
        store.write(new Investigation("I-0003-plain", "Why do eclipses repeat?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_400_000).toString(), List.of(), List.of(), "## Answer\n\nThe saros.\n"));
        Jobs jobs = new Jobs(store, j -> "");
        done(jobs, "J-0001", "books", "Which book on eclipses for a beginner?", "I-0001-which-book-on-eclipses", now);
        done(jobs, "J-0002", "podcasts", "What did the host say about the saros?", "I-0002-what-the-host-said", now);
        done(jobs, "J-0003", "", "Why do eclipses repeat?", "I-0003-plain", now);
        Serials.add(store, "the-eclipse-hour", Serials.PODCAST + "https://feeds.example/eclipse-hour.xml", 7);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String past = get(c, base + "/past").body();
            assertTrue(past.contains("<div class=\"prow done\"><span class=\"kind\">books</span><span><span class=\"t\"><a href=\"/entry/I-0001-which-book-on-eclipses?beside=1\">"), "a books report is a row of its field's kind: " + past);
            assertTrue(past.contains("<span class=\"kind\">podcasts</span><span><span class=\"t\"><a href=\"/entry/I-0002-what-the-host-said?beside=1\">"), past);
            assertTrue(past.contains("<span class=\"kind\">research report</span><span><span class=\"t\"><a href=\"/entry/I-0003-plain?beside=1\">"), "a report of no field stays a research report: " + past);
            assertTrue(past.contains("<span class=\"kind\">podcasts</span><span><span class=\"t\"><a href=\"/podcasts\">Podcasts followed</a></span><span class=\"state done\">1 show followed: the-eclipse-hour; not checked yet.</span>"), "the shows followed are a row with their line: " + past);
            assertTrue(past.contains("href=\"/past?kind=books\"><b>books</b> 1</a>") && past.contains("href=\"/past?kind=podcasts\"><b>podcasts</b> 2</a>"), "chips by the fields: " + past);
            assertFalse(past.contains("Your shelf"), "no Calibre library: no shelf row");
            String books = get(c, base + "/books").body();
            assertTrue(books.contains("<h1>Books</h1>") && books.contains("No Calibre library is set") && books.contains("<h2>Books reports</h2>") && books.contains("I-0001-which-book-on-eclipses") && !books.contains("I-0002-what-the-host-said"), books);
            String pods = get(c, base + "/podcasts").body();
            assertTrue(pods.contains("<h1>Podcasts</h1>") && pods.contains("<td data-l=\"show\">the-eclipse-hour</td>") && pods.contains("feeds.example") && pods.contains("<h2>Podcasts reports</h2>") && pods.contains("I-0002-what-the-host-said"), pods);
            String home = get(c, base + "/").body();
            assertTrue(home.contains("href=\"/books\" title=\"Books\"") && home.contains("href=\"/podcasts\" title=\"Podcasts\""), "both pages are under all pages: " + home);
        } finally { d.stop(); }
    }
}
