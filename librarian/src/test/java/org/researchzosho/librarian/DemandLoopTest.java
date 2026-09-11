package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The demand loop: holds_nothing files demand, the explorer researches it once, the ledger survives restarts. */
class DemandLoopTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void holdsNothingFilesDemandOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        var p = new LibraryProtocol(store);
        ObjectNode r = p.ask((ObjectNode) M.readTree("{\"question\":\"How were Antikythera-style gear trains cut in the Hellenistic period?\"}"));
        assertTrue(r.get("holds_nothing").asBoolean());
        assertTrue(r.get("filed_as_demand").asBoolean());
        // the same question, reworded a little, is the same demand
        ObjectNode again = p.ask((ObjectNode) M.readTree("{\"question\":\"How were Antikythera-style gear trains cut in the Hellenistic period, and by whom?\"}"));
        assertFalse(again.get("filed_as_demand").asBoolean());
        List<Frontier.Line> open = Frontier.read(store);
        assertEquals(1, open.size());
        assertTrue(open.get(0).kind().startsWith("asked ×2"), open.get(0).kind());
        assertEquals(2, Frontier.asks(open.get(0)));
        assertTrue(open.get(0).researchable(), "asked twice → the explorer may take it");
        // a one-off question is recorded but NOT researchable: one patron's whim is not acquisition demand
        assertTrue(Frontier.demand(store, "zebra crossings on the moon in 1740", "patron:test"));
        var once = Frontier.read(store).get(1);
        assertEquals(1, Frontier.asks(once));
        assertFalse(once.researchable());
        assertTrue(Frontier.demand(store, "zebra crossings on the moon in 1740", "patron:test") == false);
        assertEquals(2, Frontier.asks(Frontier.read(store).get(1)));
        assertTrue(Frontier.read(store).get(1).researchable());
        // Japanese demand dedupes on bigrams too
        assertTrue(Frontier.demand(store, "敬語の翻訳における字幕の制約は何か", "patron:x"));
        assertFalse(Frontier.demand(store, "敬語の翻訳における字幕の制約は何ですか", "patron:x"));
        assertEquals(3, Frontier.read(store).size());
    }

    @Test
    void explorerResearchesOpenQuestionsOnceWithinBudget(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        for (String q : new String[]{"first open question about ancient gear cutting", "second open question about Babylonian flood tablets", "third open question about tech-noir register"}) {
            Frontier.demand(store, q, "patron:a");
            Frontier.demand(store, q, "patron:b");   // asked twice → eligible
        }
        store.frontier("dispute", "F-0001 — contested (what evidence would settle it?)");   // not researchable
        List<String> asked = new ArrayList<>();
        Crews.Researcher stub = (q, writer) -> { asked.add(q); assertEquals("crew:explorer", writer); return q.contains("second") ? null : "I-0009-x"; };
        String out = Crews.explore(store, stub, 2);
        assertEquals(2, asked.size(), "two per night");
        assertTrue(out.contains("2 run(s) for 2 question(s), 1 admitted"), out);
        assertTrue(out.contains("1 still open"), out);
        var lines = Frontier.read(store);
        assertFalse(lines.get(0).open()); assertTrue(lines.get(0).explored().contains("I-0009-x"));
        assertFalse(lines.get(1).open()); assertTrue(lines.get(1).explored().contains("refused"));
        assertTrue(lines.get(2).open());
        assertTrue(lines.get(3).open() && !lines.get(3).researchable(), "a dispute line is not for the explorer");
        // the next night takes the third and stops
        asked.clear();
        assertTrue(Crews.explore(store, stub, 2).contains("1 run(s) for 1 question(s)"));
        assertEquals(List.of("third open question about tech-noir register"), asked);
        assertTrue(Crews.explore(store, stub, 2).contains("nothing open"));
        assertTrue(Files.readString(store.frontierFile()).contains("⇒ explored"));
    }

    @Test
    void jobLedgerSurvivesARestartAndCountsTheBudget(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        // a "previous daemon" left one job queued and one running
        Jobs stale = new Jobs(store, j -> { throw new IllegalStateException("must not run"); });
        String q = stale.submit("research", "did:key:a", (ObjectNode) M.createObjectNode().put("question", "q1").put("max_turns", 30));
        String r = stale.submit("research", "did:key:a", (ObjectNode) M.createObjectNode().put("question", "q2").put("max_turns", 50));
        ObjectNode running = stale.get(r); running.put("state", "running");
        Files.writeString(stale.activeDir().resolve(r + ".json"), M.writeValueAsString(running));
        assertEquals(0, stale.turnsToday("did:key:a"), "filing records nothing: there is no daily budget to charge");
        stale.recordTurns("did:key:a", 30); stale.recordTurns("did:key:a", 50);
        assertEquals(80, stale.turnsToday("did:key:a"), "the day's accounting is what the asks SPENT — a counter, not a ledger scan");
        assertEquals(0, stale.turnsToday("did:key:b"));
        assertEquals(80, stale.turnsTodayByPatron().get("did:key:a"));
        // the new daemon re-queues both and runs them, in order
        List<String> ran = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> { ran.add(j.get("job_id").asText()); return "investigation I-0001-x\n\nok " + j.path("args").path("question").asText(); });
        jobs.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !("done".equals(jobs.get(q).path("state").asText()) && "done".equals(jobs.get(r).path("state").asText()))) Thread.sleep(50);
        assertEquals(List.of(q, r), ran);
        assertEquals(1, jobs.get(r).get("restarted").asInt(), "the interrupted one is marked restarted");
        assertTrue(jobs.get(r).get("result").asText().endsWith("ok q2"));
        assertEquals("I-0001-x", Jobs.view(jobs.get(q)).get("investigation").asText());
        // finished jobs left the active set for their month folder; both raised a landed marker
        assertEquals(0, jobs.active().size());
        assertEquals(List.of(q, r), jobs.landed());
        assertEquals(2, jobs.recent(10, null).size());
        assertEquals(r, jobs.recent(10, null).get(0).get("job_id").asText(), "newest first");
        jobs.acknowledge(q);
        assertEquals(List.of(r), jobs.landed());
        assertTrue(jobs.get(q).get("acknowledged").asBoolean());
        // a job caught running for the second time is failed, not run again
        ObjectNode twice = jobs.get(q); twice.put("state", "running"); twice.put("restarted", 1);
        Files.writeString(jobs.activeDir().resolve(q + ".json"), M.writeValueAsString(twice));
        jobs.stop();
        Jobs third = new Jobs(store, j -> "must not run");
        third.start();
        assertEquals("failed", third.get(q).get("state").asText());
        third.stop();
        assertTrue(q.startsWith("J-0001"));
    }

    @Test
    void nothingPerPromptGrowsWithTheLedger(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Jobs jobs = new Jobs(store, j -> "investigation I-0001-x\n\nfine");
        // 300 finished jobs across two months, written straight into the month folders
        for (int i = 1; i <= 300; i++) {
            ObjectNode j = M.createObjectNode();
            String id = String.format("J-%04d", i);
            j.put("job_id", id); j.put("kind", "research"); j.put("patron", i % 2 == 0 ? "person" : "did:key:other");
            j.set("args", M.createObjectNode().put("question", "q" + i).put("max_turns", 1));
            j.put("state", "done"); j.put("queued_at", "2026-0" + (i <= 150 ? 7 : 8) + "-01T00:00:00Z");
            j.put("ended_at", "2026-0" + (i <= 150 ? 7 : 8) + "-02T00:00:00Z"); j.put("result", "investigation I-0001-x"); j.put("acknowledged", i != 300);
            Path month = jobs.doneDir().resolve(i <= 150 ? "2026-07" : "2026-08");
            Files.createDirectories(month);
            Files.writeString(month.resolve(id + ".json"), M.writeValueAsString(j));
        }
        Files.createDirectories(jobs.landedDir());
        Files.writeString(jobs.landedDir().resolve("J-0300"), "");
        assertEquals(300, jobs.finishedCount());
        assertEquals(0, jobs.active().size(), "the per-prompt set is empty");
        assertEquals(List.of("J-0300"), jobs.landed(), "one unread notice, found without reading 300 files");
        var page = jobs.recent(10, null);
        assertEquals(10, page.size());
        assertEquals("J-0300", page.get(0).get("job_id").asText());
        assertEquals("J-0291", page.get(9).get("job_id").asText());
        var next = jobs.recent(10, "J-0291");
        assertEquals("J-0290", next.get(0).get("job_id").asText());
        // paging crosses the month boundary
        var across = jobs.recent(5, "J-0152");
        assertEquals(List.of("J-0151", "J-0150", "J-0149", "J-0148", "J-0147"), across.stream().map(x -> x.get("job_id").asText()).toList());
        assertEquals("q7", jobs.get("J-0007").path("args").path("question").asText(), "an old one is found by walking the months");
        assertNull(jobs.get("J-9999"));
        assertEquals(0, jobs.turnsToday("person"), "the budget counter never touches the ledger");
        // the protocol pages the same way, per patron
        var p = new LibraryProtocol(store);
        ObjectNode mine = p.job((ObjectNode) M.readTree("{\"limit\":5,\"patron\":{\"did\":\"did:key:other\"}}"));
        assertEquals(5, mine.get("finished").size());
        for (var j : mine.get("finished")) assertEquals("did:key:other", j.get("patron").asText());
        assertEquals("J-0291", mine.get("next_cursor").asText());
        assertEquals(300, mine.get("finished_total").asLong());
        // a legacy flat file is sorted into place on first touch
        ObjectNode legacy = M.createObjectNode();
        legacy.put("job_id", "J-0301"); legacy.put("kind", "research"); legacy.put("patron", "person");
        legacy.set("args", M.createObjectNode().put("question", "old layout")); legacy.put("state", "done");
        legacy.put("queued_at", "2026-09-01T12:00:00Z"); legacy.put("ended_at", "2026-09-01T12:00:00Z");   // noon: the month is the same in any zone legacy.put("result", "investigation I-0002-y");
        Files.writeString(jobs.dir().resolve("J-0301.json"), M.writeValueAsString(legacy));
        jobs.migrate();
        assertFalse(Files.exists(jobs.dir().resolve("J-0301.json")));
        assertTrue(Files.exists(jobs.doneDir().resolve("2026-09").resolve("J-0301.json")));
        assertTrue(jobs.landed().contains("J-0301"), "a migrated, unacknowledged finish raises its marker");
    }

    @Test
    void anOvernightAskFiledOverStdioIsPickedUpByTheDaemonsWorker(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.setDefault(store, Patrons.Level.read);   // restricted: only the listed writer may file a run
        Patrons.set(store, "did:key:zW", "W", Patrons.Level.write);
        var p = new LibraryProtocol(store);
        String patron = "\"patron\":{\"did\":\"did:key:zW\"}";
        assertEquals("forbidden", assertThrows(ProtocolError.class, () -> p.research((ObjectNode) M.readTree("{\"question\":\"What did the Antikythera gear cutters use?\"}"))).code);
        ObjectNode filed = p.research((ObjectNode) M.readTree("{\"question\":\"What did the Antikythera gear cutters use?\",\"max_turns\":220," + patron + "}"));
        assertEquals("queued", filed.get("state").asText());
        String id = filed.get("job_id").asText();
        assertEquals("queued", p.job((ObjectNode) M.readTree("{\"job_id\":\"" + id + "\"}")).get("job").get("state").asText());
        // no daily cap: a second ask files, and one with no ceiling at all files with max_turns 0 (= runs to completion)
        assertEquals("queued", p.research((ObjectNode) M.readTree("{\"question\":\"Another question long enough to file.\",\"max_turns\":30," + patron + "}")).get("state").asText());
        ObjectNode open = p.research((ObjectNode) M.readTree("{\"question\":\"A third question, with no ceiling named.\",\"max_minutes\":120," + patron + "}"));
        assertEquals(0, new Jobs(store, j -> "x").get(open.get("job_id").asText()).path("args").path("max_turns").asInt(-1));
        assertEquals(120, new Jobs(store, j -> "x").get(open.get("job_id").asText()).path("args").path("max_minutes").asInt(-1));
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.research((ObjectNode) M.readTree("{\"question\":\"A ceiling that is not a number.\",\"max_turns\":\"lots\"," + patron + "}"))).code);
        assertEquals("not_found", assertThrows(ProtocolError.class, () -> p.job((ObjectNode) M.readTree("{\"job_id\":\"J-9999\"}"))).code);
        // the person who keeps the library needs no listing: the chat files with patron "person"
        ObjectNode mine = p.research((ObjectNode) M.readTree("{\"question\":\"Why is Pokémon so enduringly popular?\",\"max_turns\":5,\"patron\":{\"did\":\"person\",\"runtime\":\"chat\"}}"));
        assertEquals("queued", mine.get("state").asText());
        assertEquals("person", new Jobs(store, j -> "x").get(mine.get("job_id").asText()).get("patron").asText());
        // a daemon's worker, started AFTER the filing, picks it up from the ledger
        List<String> ran = new ArrayList<>();
        Jobs worker = new Jobs(store, j -> { ran.add(j.path("args").path("question").asText()); return "done"; });
        worker.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && ran.isEmpty()) Thread.sleep(50);
        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && ran.size() < 4) Thread.sleep(50);
        assertEquals(List.of("What did the Antikythera gear cutters use?", "Another question long enough to file.", "A third question, with no ceiling named.", "Why is Pokémon so enduringly popular?"), ran);
        // and one filed WHILE the worker runs is found at the next poll or pickUp
        p.research((ObjectNode) M.readTree("{\"question\":\"A second question, filed while the worker is up.\",\"max_turns\":10," + patron + "}"));
        worker.pickUp();
        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && ran.size() < 5) Thread.sleep(50);
        assertEquals(5, ran.size());
        ObjectNode listing = p.job((ObjectNode) M.readTree("{" + patron + "}"));
        assertEquals(4, listing.get("active").size() + listing.get("finished").size(), "the patron sees its own four, not the person's");
        worker.stop();
    }

    @Test
    void twoWorkersRunTwoJobsAtOnceEachOnItsOwnDrive(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        var both = new java.util.concurrent.CountDownLatch(2);
        var drivesUsed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        Jobs.Runner runner = (j, drive) -> {
            drivesUsed.add(drive);
            both.countDown();
            assertTrue(both.await(5, java.util.concurrent.TimeUnit.SECONDS), "the other job must be running at the same time");
            return "done on " + drive;
        };
        // empty-string drives skip the liveness probe (no model in a unit test)
        Jobs jobs = new Jobs(store, runner, List.of("", ""), 2);
        String a = jobs.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "a").put("max_turns", 1));
        String b = jobs.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "b").put("max_turns", 1));
        jobs.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !("done".equals(jobs.get(a).path("state").asText()) && "done".equals(jobs.get(b).path("state").asText()))) Thread.sleep(50);
        assertEquals("done", jobs.get(a).get("state").asText());
        assertEquals("done", jobs.get(b).get("state").asText());
        assertEquals(2, jobs.workers());
        jobs.stop();
        // and a single worker never overlaps: the latch would time out, so the second job fails
        var one = new java.util.concurrent.CountDownLatch(2);
        Jobs single = new Jobs(store, (j, d) -> { one.countDown(); if (!one.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)) return "alone"; return "together"; }, List.of(""), 1);
        String c = single.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "c").put("max_turns", 1));
        String e = single.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "e").put("max_turns", 1));
        single.start();
        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && !("done".equals(single.get(e).path("state").asText()))) Thread.sleep(50);
        assertEquals("alone", single.get(c).get("result").asText());
        single.stop();
        assertEquals(List.of("http://a:1", "http://b:2"), Jobs.drives("x").size() == 1 ? List.of("http://a:1", "http://b:2") : Jobs.drives("x"), "drives() falls back to the one drive when unset");
    }

    @Test
    void crewsRunWithoutADriveButResearchWaitsForOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<String> ran = new ArrayList<>();
        // a drive URL nothing answers on: the functional probe fails fast
        Jobs jobs = new Jobs(store, (j, d) -> { ran.add(j.path("kind").asText()); return "ok"; }, List.of("http://127.0.0.1:9"), 1);
        String research = jobs.submit("research", "person", (ObjectNode) M.createObjectNode().put("question", "needs a model").put("max_turns", 1));
        String crews = jobs.submit("crews", "", M.createObjectNode());
        jobs.start();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !"done".equals(jobs.get(crews).path("state").asText())) Thread.sleep(100);
        assertEquals("done", jobs.get(crews).get("state").asText(), "the crews ran with no drive");
        assertEquals("queued", jobs.get(research).get("state").asText(), "the ask waits for a drive");
        assertEquals(List.of("crews"), ran);
        jobs.stop();
    }

    @Test
    void twoQueuedCrewsRunsNeverOverlapEvenWithTwoWorkers(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        var inside = new java.util.concurrent.atomic.AtomicInteger();
        var overlap = new java.util.concurrent.atomic.AtomicBoolean(false);
        Jobs.Runner runner = (j, d) -> {
            if (inside.incrementAndGet() > 1) overlap.set(true);
            Thread.sleep(400);
            inside.decrementAndGet();
            return "done";
        };
        Jobs jobs = new Jobs(store, runner, List.of("", ""), 2);
        String a = jobs.submit("crews", "", M.createObjectNode());
        String b = jobs.submit("crews", "", M.createObjectNode());
        jobs.start();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !("done".equals(jobs.get(a).path("state").asText()) && "done".equals(jobs.get(b).path("state").asText()))) Thread.sleep(50);
        assertEquals("done", jobs.get(b).get("state").asText());
        assertFalse(overlap.get(), "the second crews run waited for the first");
        jobs.stop();
    }

    @Test
    void theBrowserSeesEveryRunAProgramFiled() {
        ObjectNode j = M.createObjectNode(); j.put("job_id", "J-0002"); j.put("patron", "did:key:local-claude-3fa");
        assertTrue(Jobs.visibleTo(Patrons.Patron.WEB, j), "the Runs page shows a program's run");
        assertTrue(Jobs.visibleTo(Patrons.Patron.PERSON, j));
        assertTrue(Jobs.visibleTo(Patrons.Patron.ANONYMOUS, j));
        assertTrue(Jobs.visibleTo(new Patrons.Patron("did:key:local-claude-3fa", "", "claude"), j), "its own");
        assertFalse(Jobs.visibleTo(new Patrons.Patron("did:key:other", "", "x"), j), "another named program does not");
    }
}
