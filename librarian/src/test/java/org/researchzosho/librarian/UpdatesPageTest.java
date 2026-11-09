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

/** Updates (0.7.0): what changed on the person's reports since they last looked, newest first, with the count in the rail and a way to mark it all seen. */
class UpdatesPageTest {

    static final ObjectMapper M = new ObjectMapper();

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void whatChangedOnTheReportsSinceTheLastLook(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        long now = System.currentTimeMillis();
        store.write(new Finding("F-0001-saros", "The saros is 223 synodic months", List.of("eclipses"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/saros", "", "the catalogue")), List.of(), null, "The saros is 223 synodic months."));
        store.write(new Finding("F-0002-exeligmos", "The exeligmos is three saros cycles", List.of("eclipses"), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), "2026-11-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.example/exeligmos", "", "the article")), List.of(), null, "Three saros cycles."));
        store.write(new Investigation("I-0001-why-eclipses-repeat", "Why do eclipses repeat?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of("F-0001-saros", "F-0002-exeligmos"), List.of("What is the exeligmos used for?"), "## Answer\n\nEclipses repeat because of the saros.\n"));
        Jobs jobs = new Jobs(store, j -> "");
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        ObjectNode done = M.createObjectNode(); done.put("job_id", "J-0007"); done.put("kind", "research"); done.put("state", "done"); done.put("patron", "person");
        done.put("queued_at", Instant.ofEpochMilli(now - 4_000_000).toString()); done.put("started_at", Instant.ofEpochMilli(now - 3_900_000).toString()); done.put("ended_at", Instant.ofEpochMilli(now - 3_600_000).toString());
        done.putObject("args").put("question", "Why do eclipses repeat?"); done.put("result", "investigation I-0001-why-eclipses-repeat"); done.put("investigation", "I-0001-why-eclipses-repeat");
        Files.writeString(month.resolve("J-0007.json"), M.writeValueAsString(done));
        Changes.append(store, "finding", "F-0001-saros", "state:draft→accepted", "by you");
        String going = jobs.submit("research", "person", M.createObjectNode().put("question", "Who funds instrument makers in Greater Boston?"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String h = get(c, base + "/updates").body();
            assertTrue(h.contains("<h1>Updates</h1>") && h.contains("<a href=\"/updates\" aria-current=\"page\">Updates <span class=\"n\">3</span></a>"), "the rail counts what is new: " + h);
            assertTrue(h.contains("<span class=\"rep\"><a href=\"/entry/I-0001-why-eclipses-repeat?beside=1\">Why do eclipses repeat?</a><span class=\"kind\"> · a run landed</span></span><span class=\"g\">Report filed; 2 claims, 1 open question; 1 waits for your review.</span></span><span class=\"acts\"><a class=\"btn\" href=\"/inbox?report=I-0001-why-eclipses-repeat\">Review the 1</a> <a class=\"btn quiet\" href=\"/entry/I-0001-why-eclipses-repeat?beside=1\">Open</a></span>"), "a run that landed, with its claims to review: " + h);
            assertTrue(h.contains("<a href=\"/jobs/" + going + "\">Who funds instrument makers in Greater Boston?</a><span class=\"kind\"> · a run started</span>") && h.contains("<a class=\"btn quiet\" href=\"/\">Watch it</a>"), "a run going: " + h);
            assertTrue(h.contains("<a href=\"/entry/F-0001-saros\">The saros is 223 synodic months</a><span class=\"kind\"> · a claim was accepted</span></span><span class=\"g\">by you</span>"), "a claim that changed: " + h);
            assertTrue(h.contains("<form class=\"inline\" method=\"post\" action=\"/updates\"><input type=\"hidden\" name=\"op\" value=\"seen\">") && !h.contains("urow seen"), "nothing seen yet: " + h);
            assertEquals(3, h.split("<div class=\"urow\">", -1).length - 1, h);
            // every page's rail carries the count; the Inbox is still where the claims are reviewed
            assertTrue(get(c, base + "/past").body().contains("<a href=\"/updates\">Updates <span class=\"n\">3</span></a>"), "the count on another page");
            assertTrue(get(c, base + "/inbox?report=I-0001-why-eclipses-repeat").body().contains("F-0002-exeligmos"), "the review of the report's claims");
            // mark all as seen: the rows stay, as seen; the count is gone
            String once = h.replaceAll("(?s).*name=\"once\" value=\"([0-9a-f]+)\".*", "$1");
            HttpResponse<String> seen = c.send(HttpRequest.newBuilder(URI.create(base + "/updates")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("op=seen&once=" + once)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, seen.statusCode(), seen.body());
            String after = get(c, base + "/updates").body();
            assertEquals(3, after.split("<div class=\"urow seen\">", -1).length - 1, after);
            assertTrue(after.contains("<a href=\"/updates\" aria-current=\"page\">Updates</a>") && after.contains("since you last looked, today, ") && after.contains("0 are new since your last look."), after);
        } finally { d.stop(); }
    }
}
