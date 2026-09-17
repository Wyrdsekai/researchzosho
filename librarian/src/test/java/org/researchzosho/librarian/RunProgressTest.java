package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/** A run's progress in plain words, the chat that follows the runs it starts, and the strip on the web chat. */
class RunProgressTest {

    static final ObjectMapper M = new ObjectMapper();

    static ObjectNode job(String state, String phase, int done, int total, int round, long startedAgoSec, long deadlineInSec) {
        long now = 1_800_000_000_000L;
        ObjectNode j = M.createObjectNode().put("job_id", "J-0012").put("state", state).put("question", "How were the gears cut?");
        if (startedAgoSec >= 0) j.put("started_at", Instant.ofEpochMilli(now - startedAgoSec * 1000).toString());
        ObjectNode p = j.putObject("progress"); p.put("phase", phase).put("workers_done", done).put("workers_total", total).put("round", round).put("rounds", 2);
        if (deadlineInSec != 0) p.put("deadline_at", Instant.ofEpochMilli(now + deadlineInSec * 1000).toString());
        return j;
    }
    static final long NOW = 1_800_000_000_000L;

    @Test
    void theStageIsInPlainWordsAndThePercentIsTheLargerOfTheStageAndTheClock() {
        // reading, 3 of 8 parts, 5 minutes into a 25-minute limit: the stage says 24, the clock says 19; the stage wins
        RunProgress.View v = RunProgress.of(job("running", "workers", 3, 8, 1, 300, 1200), NOW);
        assertEquals("Reading, 3 of 8 parts done", v.stage());
        assertEquals(24, v.percent()); assertEquals(300, v.elapsedSeconds());
        assertEquals("J-0012 · Reading, 3 of 8 parts done · 5 min elapsed · about 24% done", RunProgress.line(v));
        // the same stage 20 minutes into the 25: the clock says 76 and cannot overstate, so it wins
        assertEquals(76, RunProgress.of(job("running", "workers", 3, 8, 1, 1200, 300), NOW).percent());
        // no time limit and no history: the stage alone
        assertEquals(24, RunProgress.of(job("running", "workers", 3, 8, 1, 4000, 0), NOW).percent());
        // no time limit, but runs here usually take 27 minutes: 12 minutes into the reading, with no part done yet, reads about 40, not 5
        assertEquals(5, RunProgress.of(job("running", "workers", 0, 8, 1, 720, 0), NOW).percent());
        assertEquals(40, RunProgress.of(job("running", "workers", 0, 8, 1, 720, 0), NOW, 1620).percent());
        assertEquals(90, RunProgress.of(job("running", "workers", 0, 8, 1, 5000, 0), NOW, 1620).percent(), "a run that goes long holds at 90 until its stage moves on");
        assertEquals(76, RunProgress.of(job("running", "workers", 3, 8, 1, 1200, 300), NOW, 99999).percent(), "a time limit outranks the history");
        // the stages in order never go backwards, and a second pass starts where the first ended
        int last = 0;
        for (ObjectNode j : List.of(job("running", "planning", 0, 0, 1, 10, 0), job("running", "workers", 0, 8, 1, 10, 0), job("running", "workers", 8, 8, 1, 10, 0), job("running", "critic", 8, 8, 1, 10, 0),
                job("running", "workers", 0, 4, 2, 10, 0), job("running", "workers", 4, 4, 2, 10, 0), job("running", "critic", 4, 4, 2, 10, 0), job("running", "synthesis", 4, 4, 2, 10, 0),
                job("running", "cite-check", 4, 4, 2, 10, 0), job("running", "filing", 4, 4, 2, 10, 0))) {
            int pc = RunProgress.of(j, NOW).percent();
            assertTrue(pc >= last, j.path("progress") + " gave " + pc + " after " + last); last = pc;
        }
        assertTrue(last < 100, "never 100 while it runs");
        assertEquals(96, RunProgress.of(job("running", "filing", 4, 4, 2, 9000, -60), NOW).percent(), "past its limit and still saving: the clock stops at 95, the stage says 96, never 100");
        assertEquals("Writing the report", RunProgress.of(job("running", "synthesis", 8, 8, 1, 10, 0), NOW).stage());
        assertEquals("Checking citations", RunProgress.of(job("running", "cite-check", 8, 8, 1, 10, 0), NOW).stage());
        assertEquals("Reading, 1 of 4 parts done (second pass)", RunProgress.of(job("running", "workers", 1, 4, 2, 10, 0), NOW).stage());
        // queued, done, failed
        RunProgress.View q = RunProgress.of(job("queued", "", 0, 0, 0, -1, 0), NOW);
        assertEquals(0, q.percent()); assertEquals("J-0012 · Waiting to start", RunProgress.line(q)); assertTrue(q.active());
        ObjectNode done = job("done", "filing", 8, 8, 1, 1500, 0); done.put("ended_at", Instant.ofEpochMilli(NOW - 60_000).toString()); done.put("investigation", "I-0009-gears");
        RunProgress.View d = RunProgress.of(done, NOW);
        assertEquals(100, d.percent()); assertFalse(d.active()); assertEquals("I-0009-gears", d.report());
        assertEquals("J-0012 · Done after 24 min", RunProgress.line(d));
        assertEquals("40 sec", RunProgress.elapsed(40)); assertEquals("12 min", RunProgress.elapsed(725)); assertEquals("1 hour 4 min", RunProgress.elapsed(3850)); assertEquals("2 hours 0 min", RunProgress.elapsed(7200));
    }

    @Test
    void theChatFollowsTheRunsItStartsAndSaysOnceWhenOneIsDone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Librarian.Session s = Librarian.Session.open(store);
        // a drive that starts a run on the first turn, then speaks
        Researcher.Drive drive = new Researcher.Drive() {
            int n = 0;
            @Override public int contextWindow() { return 32_000; }
            @Override public String classify(ArrayNode m, int max) { return ""; }
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode a = M.createObjectNode().put("role", "assistant");
                if (n++ == 0) { a.put("content", ""); ObjectNode c = a.putArray("tool_calls").addObject(); c.put("id", "c1"); c.putObject("function").put("name", "library_research").put("arguments", "{\"question\":\"How were the gears of the Antikythera mechanism cut?\"}"); }
                else a.put("content", "The run has started. It usually takes twenty to forty minutes.");
                return a;
            }
        };
        String reply = new Librarian(store, drive, Librarian.person(), s).say("find out how the gears were cut");
        assertTrue(reply.startsWith("The run has started"), reply);
        assertEquals(List.of("J-0001"), s.watched(), "the run the chat started is followed");
        s.watch("J-0001"); assertEquals(1, s.watched().size(), "once");
        // the prompt tells the model not to make the person ask
        assertTrue(new Librarian(store, drive, Librarian.person(), s).systemPrompt().contains("never tell them to ask how it is going"));
        // done: one notice, as the Librarian, naming the report; the run is no longer followed; a resumed session knows
        ObjectNode done = job("done", "filing", 8, 8, 1, 1500, 0); done.put("job_id", "J-0001").put("ended_at", Instant.ofEpochMilli(NOW - 60_000).toString()).put("investigation", "I-0001-gears");
        String notice = s.told("J-0001", RunProgress.of(done, NOW));
        assertEquals("Research run J-0001 is done after 24 min. Its report is [I-0001-gears]. Say \"show it\" to read the answer.", notice);
        assertTrue(s.watched().isEmpty());
        Librarian.Session again = Librarian.Session.resume(store, s.id);
        assertTrue(again.watched().isEmpty());
        assertEquals(notice, again.messages().get(again.messages().size() - 1).path("content").asText());
        ObjectNode failed = job("failed", "workers", 1, 8, 1, 90, 0); failed.put("ended_at", Instant.ofEpochMilli(NOW).toString());
        s.watch("J-0002");
        assertTrue(s.told("J-0002", RunProgress.of(failed, NOW)).startsWith("Research run J-0002 ended as failed after 1 min."));
    }

    @Test
    void theWebChatShowsTheRunsItFollowsAndRefreshesThemInPlace(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        var drives = Explain.DRIVES; Explain.DRIVES = () -> null;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // a run filed for this conversation: it waits (no model in this test), and the chat follows it
            ObjectNode ask = M.createObjectNode().put("question", "How were the gears of the Antikythera mechanism cut?");
            ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
            String jobId = new LibraryProtocol(store).research(ask).path("job_id").asText();
            Librarian.Session s = Librarian.Session.open(store);
            s.watch(jobId);
            var page = c.send(HttpRequest.newBuilder(URI.create(base + "/chat?session=" + s.id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("id=\"runs-strip\"") && page.body().contains(jobId), "the strip names the run");
            assertTrue(page.headers().firstValue("content-security-policy").orElse("").contains("connect-src 'self'"), "the strip may refresh itself from this server");
            var frag = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/runs?session=" + s.id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, frag.statusCode());
            assertTrue(frag.body().startsWith("<p class=\"k run\">") && frag.body().contains(jobId) && !frag.body().contains("<html"), "the strip alone: " + frag.body());
            // a conversation that follows nothing has no strip and no script
            Librarian.Session quiet = Librarian.Session.open(store);
            quiet.append(M.createObjectNode().put("role", "user").put("content", "hello"));   // a conversation exists once something is said in it
            var plain = c.send(HttpRequest.newBuilder(URI.create(base + "/chat?session=" + quiet.id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(plain.body().contains("runs-strip"));
            assertFalse(plain.headers().firstValue("content-security-policy").orElse("").contains("script-src"));
        } finally { d.stop(); Explain.DRIVES = drives; }
    }
}
