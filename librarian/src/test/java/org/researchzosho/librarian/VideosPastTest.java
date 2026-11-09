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

/** Past's row for the videos the library has read (0.7.0), and the Video page's list of the videos reports, as the books and podcasts pages have. */
class VideosPastTest {

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void theVideosReadAreARowAndTheVideoPageListsTheVideosReports(@TempDir Path tmp) throws Exception {
        LibraryStore store = VideoRecallTest.storeWithTwoVideos(tmp);
        long now = System.currentTimeMillis();
        store.write(new Investigation("I-0001-which-channel-teaches-welding", "Which channel teaches welding?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\nOne channel.\n"));
        PastFieldsTest.done(new Jobs(store, j -> ""), "J-0001", "youtube", "Which channel teaches welding?", "I-0001-which-channel-teaches-welding", now);
        List<VideoRecall.Held> held = VideoRecall.held(store);
        assertEquals(2, held.size());
        String line = VideoRecallPage.heldLine(held);
        assertTrue(line.startsWith("2 videos read, 2 with what was said, 0 with the words on screen, 0 with what is seen; last read "), line);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String past = get(c, base + "/past").body();
            assertTrue(past.contains("<span class=\"kind\">videos</span><span><span class=\"t\"><a href=\"/video#held\">Videos read</a></span><span class=\"state done\">2 videos read, 2 with what was said"), "the videos read are a row of the videos' kind: " + past);
            assertTrue(past.contains("<span class=\"kind\">videos</span><span><span class=\"t\"><a href=\"/entry/I-0001-which-channel-teaches-welding?beside=1\">"), "a youtube report is a videos row: " + past);
            String video = get(c, base + "/video").body();
            assertTrue(video.contains("<h2 id=\"held\">The videos the library has read</h2>") && video.contains("2 videos read, 2 with what was said") && video.contains("<h2>Videos reports</h2>") && video.contains("I-0001-which-channel-teaches-welding"), video);
            assertTrue(get(c, base + "/").body().contains("href=\"/video\" title=\"Video\""), "the page is under all pages");
        } finally { d.stop(); }
    }
}
