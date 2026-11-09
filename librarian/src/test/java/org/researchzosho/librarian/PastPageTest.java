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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Past (0.7.0): every report as a row with one line of state, newest first; chips by kind and state; a search of the titles. */
class PastPageTest {

    static final ObjectMapper M = new ObjectMapper();

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everyReportIsARowWithItsLineOfState(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        long now = System.currentTimeMillis();
        Finding f1 = new Finding("F-0001-saros", "The saros is 223 synodic months", List.of("eclipses"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/saros", "", "the catalogue")), List.of(), null, "The saros is 223 synodic months.");
        Finding f2 = new Finding("F-0002-exeligmos", "The exeligmos is three saros cycles", List.of("eclipses"), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/exeligmos", "", "the article")), List.of(), null, "Three saros cycles.");
        store.write(f1); store.write(f2);
        store.write(new Investigation("I-0001-why-eclipses-repeat", "Why do eclipses repeat?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of("F-0001-saros", "F-0002-exeligmos"), List.of("What is the exeligmos used for?"), "## Answer\n\nEclipses repeat because of the saros [F-0001-saros].\n"));
        store.write(new Investigation("I-0002-older", "An older report", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 5 * 86_400_000L).toString(), List.of(), List.of(), "## Answer\n\nNothing much.\n"));
        // the run that filed the first report, with its ledger row; a run that was declined; one still going
        Jobs jobs = new Jobs(store, j -> "");
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        ObjectNode done = M.createObjectNode(); done.put("job_id", "J-0007"); done.put("kind", "research"); done.put("state", "done"); done.put("patron", "person");
        done.put("queued_at", Instant.ofEpochMilli(now - 4_000_000).toString()); done.put("started_at", Instant.ofEpochMilli(now - 3_900_000).toString()); done.put("ended_at", Instant.ofEpochMilli(now - 3_600_000).toString());
        done.putObject("args").put("question", "Why do eclipses repeat?"); done.put("result", "investigation I-0001-why-eclipses-repeat"); done.put("investigation", "I-0001-why-eclipses-repeat");
        Files.writeString(month.resolve("J-0007.json"), M.writeValueAsString(done));
        ObjectNode declined = M.createObjectNode(); declined.put("job_id", "J-0008"); declined.put("kind", "research"); declined.put("state", "done"); declined.put("patron", "person");
        declined.put("queued_at", Instant.ofEpochMilli(now - 2_000_000).toString()); declined.put("started_at", Instant.ofEpochMilli(now - 1_900_000).toString()); declined.put("ended_at", Instant.ofEpochMilli(now - 1_800_000).toString());
        declined.putObject("args").put("question", "How much does my neighbour earn?"); declined.putObject("declined").put("run", true).put("statement", "The question asks for someone's money and names no public role.");
        Files.writeString(month.resolve("J-0008.json"), M.writeValueAsString(declined));
        ObjectNode row = M.createObjectNode(); row.put("job_id", "J-0007"); row.put("cite_checked", 33); row.put("cite_supported", 31); row.put("cite_unsupported", 2);
        Files.createDirectories(RunLedger.file(store).getParent()); Files.writeString(RunLedger.file(store), row + "\n");
        String going = jobs.submit("research", "person", M.createObjectNode().put("question", "Who funds instrument makers in Greater Boston?").put("title", "Funding of instrument makers"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String h = get(c, base + "/past").body();
            assertTrue(h.contains("<h2 id=\"reports\">Reports</h2>") && h.contains("<form class=\"ask\" method=\"get\" action=\"/past\">"), h);
            assertTrue(h.contains("<a class=\"chip on\" href=\"/past\"><b>all</b> 4</a>") && h.contains("href=\"/past?kind=research%20report\"><b>research report</b> 4</a>"), "chips by kind: " + h);
            assertTrue(h.contains("<b>done</b> 2</a>") && h.contains("<b>declined</b> 1</a>") && h.contains("<b>being made</b> 1</a>"), "chips by state: " + h);
            String filed = "<div class=\"prow done\"><span class=\"kind\">research report</span><span><span class=\"t\"><a href=\"/entry/I-0001-why-eclipses-repeat?beside=1\">Why do eclipses repeat?</a></span><span class=\"state done\">Done " + Pages.dayTime(Instant.ofEpochMilli(now - 3_600_000).toString());   // an hour ago: yesterday, in the first hour of a day
            assertTrue(h.contains(filed), "a filed report's row: " + h);
            assertTrue(h.contains("Report filed; 31 of 33 cited sentences checked against their sources, 2 marked; 2 claims, 1 open question. Since then: 1 claim waits for your review.</span>"), "its line of state, from the ledger and the inbox: " + h);
            assertTrue(h.contains("<span class=\"state done\">Done ") && h.contains(">An older report</a>") && h.contains("Report filed; 0 claims, 0 open questions.</span>"), "a report without a ledger row says what it has: " + h);
            assertTrue(h.contains("<div class=\"prow declined\">") && h.contains("Declined by the model: The question asks for someone's money and names no public role. Nothing filed. <a href=\"/research?q=How%20much%20does%20my%20neighbour%20earn%3F\">Ask again</a>."), "a declined run is a row with its why and a way to ask again: " + h);
            assertTrue(h.contains("<div class=\"prow being-made\">") && h.contains("href=\"/jobs/" + going + "\">Funding of instrument makers</a>") && h.contains("Waiting to start; the runs ahead of it go first."), "a run going is a report being made: " + h);
            assertTrue(h.indexOf("prow being-made") < h.indexOf("prow declined") && h.indexOf("prow declined") < h.indexOf("I-0001-why-eclipses-repeat") && h.indexOf("I-0001-why-eclipses-repeat") < h.indexOf("An older report"), "newest first");
            String only = get(c, base + "/past?state=declined").body();
            assertTrue(only.contains("prow declined") && !only.contains("prow done") && only.contains("<a class=\"chip on\" href=\"/past?state=declined\">"), "a chip narrows the rows: " + only);
            String words = get(c, base + "/past?q=older").body();
            assertTrue(words.contains("An older report") && !words.contains("Why do eclipses repeat?</a>"), "the box searches the titles: " + words);
            assertTrue(get(c, base + "/past?q=nothing-like-this").body().contains("No report matches."));
        } finally { d.stop(); }
    }

    @Test
    void aMomentReadsAsAPersonSaysIt() {
        assertEquals("", Pages.dayTime(""));
        assertTrue(Pages.dayTime(Instant.now().toString()).startsWith("today, "), Pages.dayTime(Instant.now().toString()));
        String noonYesterday = LocalDate.now().minusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toString();
        assertEquals("yesterday, 12:00", Pages.dayTime(noonYesterday), "the day before is said as that, at any hour the test runs");
        assertEquals("14 Oct 2025, 05:08", Pages.dayTime("2025-10-14T05:08:00"));
        assertEquals("14 Oct 2025", Pages.dayTime("2025-10-14"));
    }
}
