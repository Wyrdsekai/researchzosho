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

/** A report's page (0.7.0): one line of state at its head, two marks for a reader, how it was made behind a fold, and the report beside the chat when opened from Past. */
class ReportPageTest {

    static final ObjectMapper M = new ObjectMapper();

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aReportOpensWithItsStateItsMarksAndHowItWasMade(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        long now = System.currentTimeMillis();
        store.write(new Finding("F-0001-saros", "The saros is 223 synodic months", List.of("eclipses"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/saros", "", "the catalogue")), List.of(), null, "The saros is 223 synodic months."));
        store.write(new Finding("F-0002-exeligmos", "The exeligmos is three saros cycles", List.of("eclipses"), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/exeligmos", "", "the article")), List.of(), null, "Three saros cycles."));
        store.write(new Investigation("I-0001-why-eclipses-repeat", "Why do eclipses repeat?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of("F-0001-saros", "F-0002-exeligmos"), List.of("What is the exeligmos used for?"), "## Answer\n\nEclipses repeat because of the saros.\n"));
        store.write(new Investigation("I-0002-acme", "Corporate history of Acme Corp.: a full dossier.", Finding.State.accepted, "patron:test", "2026-10-24T10:00:00Z", List.of(), List.of(), "## Question\n\nAcme Corp.\n\n## Answer\n\nFounded in 1999.\n"));
        Jobs jobs = new Jobs(store, j -> "");
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        ObjectNode done = M.createObjectNode(); done.put("job_id", "J-0007"); done.put("kind", "research"); done.put("state", "done"); done.put("patron", "person"); done.put("drive", "http://homebox:8212");
        done.put("queued_at", Instant.ofEpochMilli(now - 4_000_000).toString()); done.put("started_at", Instant.ofEpochMilli(now - 3_900_000).toString()); done.put("ended_at", Instant.ofEpochMilli(now - 3_600_000).toString());
        done.putObject("args").put("question", "Why do eclipses repeat?").put("model_entry", "gemma-3-27b"); done.put("result", "investigation I-0001-why-eclipses-repeat\n\nturns 13 · 3 sub-question(s) · 1 round(s)"); done.put("investigation", "I-0001-why-eclipses-repeat");
        Files.writeString(month.resolve("J-0007.json"), M.writeValueAsString(done));
        ObjectNode row = M.createObjectNode(); row.put("job_id", "J-0007"); row.put("cite_checked", 33); row.put("cite_supported", 31); row.put("cite_unsupported", 2);
        Files.createDirectories(RunLedger.file(store).getParent()); Files.writeString(RunLedger.file(store), row + "\n");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String h = get(c, base + "/entry/I-0001-why-eclipses-repeat").body();
            // the day in words is the page's own for that moment: the report is an hour old, which is yesterday when the test runs in the first hour of a day
            assertTrue(h.contains("<h1>Why do eclipses repeat?</h1><p class=\"state done\">Done " + Pages.dayTime(Instant.ofEpochMilli(now - 3_600_000).toString()) + ". "), "the line of state at the head: " + h);
            assertTrue(h.contains("Report filed; 31 of 33 cited sentences checked against their sources, 2 marked; 2 claims, 1 open question. Since then: 1 claim waits for your review.</p>"), h);
            assertTrue(h.contains("<p class=\"legend\"><span class=\"est\">●</span> established: accepted · <span class=\"est no\">○</span> not yet"), "the legend of the two marks: " + h);
            assertTrue(h.contains("<li><a class=\"est\" href=\"/entry/F-0001-saros\" title=\"established: accepted\">●</a> <a href=\"/entry/F-0001-saros\">F-0001-saros</a> The saros is 223 synodic months</li>"), "an accepted claim's mark and title: " + h);
            assertTrue(h.contains("<li><a class=\"est no\" href=\"/entry/F-0002-exeligmos\" title=\"not yet: draft\">○</a> <a href=\"/entry/F-0002-exeligmos\">F-0002-exeligmos</a> The exeligmos is three saros cycles</li>"), "a draft's: " + h);
            int fold = h.indexOf("<details class=\"sec made\"><summary><h2>How this was made</h2><span class=\"gist\">5 min, 31 of 33 cites held</span></summary>");
            assertTrue(fold > 0, "how it was made, behind a fold with its gist: " + h);
            String made = h.substring(fold);
            for (String g : new String[]{"<dt>Asked by</dt><dd>you, ", "<dt>Model</dt><dd>gemma-3-27b on http://homebox:8212</dd>", "<dt>Time</dt><dd>5 min, started ", "<dt>The run</dt><dd>turns 13 · 3 sub-question(s) · 1 round(s) · <a href=\"/jobs/J-0007\">the run&#39;s record</a></dd>".replace("&#39;", "'"),
                    "<dt>Cite-check</dt><dd>31 of 33 cited sentences supported by their sources, 2 marked</dd>", "<dt>Record</dt><dd><p class=\"k\">investigation · <span class=\"badge accepted\">accepted</span>"})
                assertTrue(made.contains(g), "the fold lacks " + g + ": " + made);
            assertTrue(h.indexOf("investigation · <span class=\"badge accepted\">") > fold, "the record's line is inside the fold, not at the head");
            // a report without a run's record says what it has and who filed it
            String bare = get(c, base + "/entry/I-0002-acme").body();
            assertTrue(bare.contains("<p class=\"state done\">Done 24 Oct, ") && bare.contains("Report filed; 0 claims, 0 open questions.</p>") && bare.contains("<dt>The run</dt><dd>No record of the run that filed it; the report was filed by patron:test.</dd>"), bare);
            // opened from Past, the report stands beside the chat: the conversation with its strip under the composer, the reading with a way to close it
            String side = get(c, base + "/entry/I-0001-why-eclipses-repeat?beside=1").body();
            for (String g : new String[]{"<body class=\"fill\">", "<main class=\"split\">", "<div class=\"cols\"><section class=\"conv\">", "id=\"chat-say\"", "id=\"runs-strip\" class=\"strip below\"", "<section class=\"reading\"><a class=\"back small\" href=\"/past\">← Past</a>",
                    "<p class=\"kicker\">Report · opened from Past · <a href=\"/entry/I-0001-why-eclipses-repeat\">its own page</a></p><a class=\"close\" href=\"/past\" title=\"Close\">×</a>", "<h1>Why do eclipses repeat?</h1><p class=\"state done\">", "<a href=\"/past\" aria-current=\"page\">Past</a>"})
                assertTrue(side.contains(g), "beside the chat, the page lacks " + g + ": " + side);
            assertEquals(1, side.split("<h1>", -1).length - 1, "one heading, the reading's own");
            assertFalse(side.contains("id=\"runs-strip\" class=\"strip side\""), "the strip is under the composer, not beside: three columns would be too many");
            // the dossier: its state line, and beside the chat from Past
            String doss = get(c, base + "/dossier?subject=Acme%20Corp").body();
            assertTrue(doss.contains("<p class=\"state done\">Last run 24 Oct. 0 claims from 1 report.</p>"), doss);
            String dside = get(c, base + "/dossier?subject=Acme%20Corp&beside=1").body();
            assertTrue(dside.contains("<main class=\"split\">") && dside.contains("<p class=\"kicker\">Dossier · organization · opened from Past · <a href=\"/dossier?subject=Acme%20Corp\">its own page</a></p>") && dside.contains("<h1>Acme Corp</h1><p class=\"state done\">Last run 24 Oct."), dside);
            String past = get(c, base + "/past").body();
            assertTrue(past.contains("href=\"/entry/I-0001-why-eclipses-repeat?beside=1\"") && past.contains("href=\"/dossier?subject=Acme%20Corp&amp;beside=1\""), "Past's rows open beside the chat: " + past);
        } finally { d.stop(); }
    }
}
