package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.ContentPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a program gets when it files a research run (0.5.4): the answer at once, one bounded question to the model before it, the same run
 * back for the same ask, a run the service held for the person's yes, and what every reader of the library sees of the runs.
 */
class ProgramSubmitTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PROGRAM = "\"patron\":{\"did\":\"did:key:prog-1\",\"name\":\"a program\",\"runtime\":\"claude-code\"}";

    @AfterEach void restore() { ContentJudge.use(null); LibraryProtocol.SUBMIT_CHECK_MS = 45_000; }

    static LibraryStore shelf(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp); s.init(); return s; }
    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }
    /** A judge that answers every question with {@code word}, after {@code delayMs}; like the real one, it stops when the caller's time is up. */
    static ContentJudge judge(String word, long delayMs) {
        return new ContentJudge(null, m -> {
            for (long waited = 0; waited < delayMs; waited += 50) { Stopping.check(); try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            return word;
        });
    }

    @Test
    void everyReaderSeesEveryRun() throws Exception {
        ObjectNode byAnother = args("{\"job_id\":\"J-0001\",\"patron\":\"did:key:prog-2\",\"kind\":\"research\",\"state\":\"running\"}");
        assertTrue(Jobs.visibleTo(Patrons.Patron.from(args("{" + PROGRAM + "}")), byAnother), "a program sees another program's run");
        assertTrue(Jobs.visibleTo(Patrons.Patron.ANONYMOUS, byAnother));
    }

    @Test
    void aProgramsSubmitAsksTheModelOneQuestionAndReturns(@TempDir Path tmp) throws Exception {
        ContentJudge.use(judge("no", 0));
        LibraryProtocol p = new LibraryProtocol(shelf(tmp));
        long t0 = System.nanoTime();
        ObjectNode r = p.research(args("{\"question\":\"How are harpsichords tuned?\",\"title\":\"Harpsichord tuning\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        assertTrue(r.path("job_id").asText().startsWith("J-"), r.toString());
        assertEquals("queued", r.path("state").asText());
        assertFalse(r.has("content_check"), "the model answered in time: nothing is left for the service to read");
        assertEquals("Harpsichord tuning", r.path("title").asText());
        assertFalse(r.has("help"), "a question the judge read as harmless shows no help");
        Jobs jobs = new Jobs(p.store(), j -> "read only");
        ObjectNode job = jobs.get(r.path("job_id").asText());
        assertFalse(job.path("args").has("content_check"));
        assertEquals("Harpsichord tuning", job.path("args").path("title").asText());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "submit returned at once");
    }

    @Test
    void aModelTooBusyToAnswerInTimeLeavesTheWholeReadingToTheService(@TempDir Path tmp) throws Exception {
        ContentJudge.use(judge("no", 3_000));
        LibraryProtocol.SUBMIT_CHECK_MS = 300;
        LibraryProtocol p = new LibraryProtocol(shelf(tmp));
        long t0 = System.nanoTime();
        ObjectNode r = p.research(args("{\"question\":\"How are harpsichords tuned?\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        assertEquals("pending", r.path("content_check").asText(), r.toString());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_500, "submit did not wait for the slow model");
        assertTrue(r.path("job_id").asText().startsWith("J-"));
    }

    @Test
    void aQuestionThatReadsAsSelfHarmIsStillConfirmedAtSubmit(@TempDir Path tmp) throws Exception {
        ContentJudge.use(judge("yes", 0));
        LibraryProtocol p = new LibraryProtocol(shelf(tmp));
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(args("{\"question\":\"I want to end it all, how\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM));
        assertEquals(ProtocolError.CONFIRM, e.code);
    }

    @Test
    void theSameAskFiledAgainWhileItRunsIsThatRun(@TempDir Path tmp) throws Exception {
        ContentJudge.use(judge("no", 0));
        LibraryProtocol p = new LibraryProtocol(shelf(tmp));
        String q = "{\"question\":\"How are harpsichords tuned?\"," + PROGRAM + "}";
        ObjectNode first = p.research(args(q), LibraryProtocol.Way.PROGRAM);
        ObjectNode again = p.research(args(q), LibraryProtocol.Way.PROGRAM);
        assertEquals(first.path("job_id").asText(), again.path("job_id").asText(), "a retry gets the run that already waits");
        assertTrue(again.path("already_filed").asBoolean(false), again.toString());
        // the same key is the same ask whatever the words; another key, or another patron, is another run
        ObjectNode k1 = p.research(args("{\"question\":\"Tuning of harpsichords, early 18th century\",\"idempotency_key\":\"k-77\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        ObjectNode k2 = p.research(args("{\"question\":\"harpsichord tuning (retry)\",\"idempotency_key\":\"k-77\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        assertEquals(k1.path("job_id").asText(), k2.path("job_id").asText());
        assertTrue(k2.path("already_filed").asBoolean(false));
        ObjectNode other = p.research(args("{\"question\":\"How are harpsichords tuned?\",\"patron\":{\"did\":\"did:key:prog-2\",\"name\":\"b\",\"runtime\":\"x\"}}"), LibraryProtocol.Way.PROGRAM);
        assertNotEquals(first.path("job_id").asText(), other.path("job_id").asText(), "another program's same question is its own run");
        assertFalse(other.path("already_filed").asBoolean(false));
        // the same words sent again with allow, as the protocol tells a program to after it asked the person, is a new run
        ObjectNode letIn = p.research(args("{\"question\":\"How are harpsichords tuned?\",\"allow\":[\"explicit\"]," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        assertNotEquals(first.path("job_id").asText(), letIn.path("job_id").asText());
        assertFalse(letIn.path("already_filed").asBoolean(false));
        // a priority run goes ahead of the others waiting, with the full limits
        ObjectNode ahead = p.research(args("{\"question\":\"Which harpsichord makers worked in Antwerp?\",\"priority\":true," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        assertTrue(ahead.path("priority").asBoolean(false));
        assertFalse(ahead.has("quick"));
        Jobs jobs = new Jobs(p.store(), j -> "read only");
        jobs.pickUp();   // as the service's own line learns of filed runs
        assertEquals(ahead.path("job_id").asText(), jobs.nextInLine(), "first in line");
        assertTrue(Jobs.view(jobs.get(ahead.path("job_id").asText())).path("priority").asBoolean(false));
    }

    @Test
    void aRunAskedForWhatIsNewSinceAnEarlierReport(@TempDir Path tmp) throws Exception {
        ContentJudge.use(judge("no", 0));
        LibraryStore store = shelf(tmp);
        store.write(new Investigation("I-0001-harpsichord-tuning", "Harpsichord tuning", Finding.State.accepted, "patron:person", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nHow?\n\n## Answer\n\nBy meantone, in the 1700s.\n\n## Worker findings (fan sub-investigations, verbatim)\n\nnotes\n"));
        LibraryProtocol p = new LibraryProtocol(store);
        ProtocolError missing = assertThrows(ProtocolError.class, () -> p.research(args("{\"question\":\"anything new?\",\"delta_of\":\"I-0099-nothing\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM));
        assertEquals("not_found", missing.code);
        ObjectNode r = p.research(args("{\"question\":\"Anything new on harpsichord tuning?\",\"delta_of\":\"I-0001-harpsichord-tuning\"," + PROGRAM + "}"), LibraryProtocol.Way.PROGRAM);
        Jobs jobs = new Jobs(store, j -> "read only");
        ObjectNode job = jobs.get(r.path("job_id").asText());
        assertEquals("I-0001-harpsichord-tuning", job.path("args").path("delta_of").asText());
        assertTrue(job.path("args").path("title").asText().startsWith("What is new since I-0001-harpsichord-tuning"), job.toString());
        String q = LibrarianDaemon.withEarlierFor(store, "Anything new on harpsichord tuning?", "I-0001-harpsichord-tuning");
        assertTrue(q.contains("By meantone, in the 1700s.") && !q.contains("notes"), "the earlier answer, not its worker findings: " + q);
        assertTrue(q.contains("Report what is new"), q);
    }

    @Test
    void theServiceReadsTheQuestionBeforeTheRunAndHoldsOneThatReadsAsSelfHarm(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        Jobs jobs = new Jobs(store, j -> "read only");
        // the model gave no verdict at submit: the service reads the question before the run, and the job carries what it found
        ContentJudge.use(new ContentJudge(null, m -> m.get(0).path("content").asText().contains(ContentOffer.NEEDS_EXPLICIT) ? "yes" : "no"));
        String id = jobs.submit("research", "did:key:prog-1", args("{\"question\":\"q\",\"content_check\":\"pending\"}"));
        ObjectNode job = jobs.get(id);
        LibrarianDaemon.contentCheckBeforeRun(store, jobs, job, (ObjectNode) job.get("args"));
        ObjectNode after = jobs.get(id);
        assertFalse(after.path("args").has("content_check"), "read once");
        assertTrue(after.path("args").path("content_suggestion").isObject(), after.toString());
        assertTrue(Jobs.view(after).has("content_suggestion"), "a caller reads it on the job");
        // a submit that could not ask the model at all: the service asks, and holds a run that reads as a person asking about harming themselves
        ContentJudge.use(judge("yes", 0));
        String held = jobs.submit("research", "did:key:prog-1", args("{\"question\":\"q2\",\"content_check\":\"pending\"}"));
        ObjectNode heldJob = jobs.get(held);
        Jobs.Hold h = assertThrows(Jobs.Hold.class, () -> LibrarianDaemon.contentCheckBeforeRun(store, jobs, heldJob, (ObjectNode) heldJob.get("args")));
        assertTrue(h.waiting().contains("researchzosho jobs " + held + " --yes") && h.waiting().contains("op=allow"), h.waiting());
        // one already allowed is not held
        String allowed = jobs.submit("research", "did:key:prog-1", args("{\"question\":\"q3\",\"content_check\":\"pending\",\"allow\":[\"" + ContentPolicy.SELF_HARM + "\"]}"));
        ObjectNode allowedJob = jobs.get(allowed);
        assertDoesNotThrow(() -> LibrarianDaemon.contentCheckBeforeRun(store, jobs, allowedJob, (ObjectNode) allowedJob.get("args")));
    }

    @Test
    void aHeldRunWaitsForThePersonsYesAndStartsOnIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        Jobs jobs = new Jobs(store, (job, drive) -> {
            if (job.path("args").path("question").asText().equals("hold me")) throw new Jobs.Hold("waiting for a yes", System.currentTimeMillis() + 60_000);
            return "ran";
        }, List.of(""), 1);
        String id = jobs.submit("research", "did:key:prog-1", args("{\"question\":\"hold me\"}"));
        jobs.start();
        try {
            long t0 = System.currentTimeMillis();
            while (!Jobs.OFFERED.equals(jobs.get(id).path("state").asText()) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(50);
            ObjectNode j = jobs.get(id);
            assertEquals(Jobs.OFFERED, j.path("state").asText(), j.toString());
            assertTrue(j.path("offered_help").asBoolean(false));
            String waiting = Jobs.view(j).path("waiting").asText();
            assertTrue(waiting.startsWith("waiting for a yes") && waiting.contains("showed where to find help"), waiting);
            assertFalse(jobs.running().contains(id), "off the running set");
            // the person says yes through the protocol: the run is queued, and this runner runs it to the end
            LibraryProtocol p = new LibraryProtocol(store);
            ObjectNode r = p.job(args("{\"op\":\"allow\",\"job_id\":\"" + id + "\",\"patron\":{\"did\":\"person\",\"name\":\"me\",\"runtime\":\"cli\"}}"));
            assertEquals("queued", r.path("state").asText(), r.toString());
            ProtocolError again = assertThrows(ProtocolError.class, () -> p.job(args("{\"op\":\"allow\",\"job_id\":\"" + id + "\",\"patron\":{\"did\":\"person\",\"name\":\"me\",\"runtime\":\"cli\"}}")));
            assertEquals("invalid_args", again.code, "answered once");
        } finally { jobs.stop(); }
    }

    @Test
    void anAskCanBeCappedForASmallWindow(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 80; i++) big.append("Harpsichord strings are plucked by plectra on jacks; the tuning drifts with the room. ");
        store.write(new Finding("F-0001-harpsichord-tuning", "Harpsichord tuning drifts", List.of("music--harpsichord"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-10-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/h", "n/a", "s")), List.of(), null, big.toString()));
        new LibrarianIndex(store).rebuild();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode whole = p.ask(args("{\"question\":\"harpsichord tuning\"," + PROGRAM + "}"));
        assertTrue(whole.path("entries").size() > 0, whole.toString());
        int full = whole.path("entries").get(0).path("body").asText().length();
        ObjectNode capped = p.ask(args("{\"question\":\"harpsichord tuning\",\"max_chars\":600," + PROGRAM + "}"));
        String body = capped.path("entries").get(0).path("body").asText();
        assertTrue(body.length() < full && body.length() <= 620, body.length() + " of " + full);
        assertTrue(capped.path("entries").get(0).path("truncated").asBoolean(false));
        assertTrue(body.endsWith("…"));
    }

    @Test
    void aReportCanCarryTheTitleTheAskGave(@TempDir Path tmp) throws Exception {
        LibraryStore store = shelf(tmp);
        Investigation inv = Acquisitions.admit(store, new LibrarianIndex(store), "How are harpsichords tuned, and by whom, in which century?",
                "By meantone, in the 1700s, by the player. Source: https://example.org/h", "patron:prog", "", id -> { }, "", "Harpsichord tuning");
        assertEquals("Harpsichord tuning", inv.title());
        assertTrue(inv.id().contains("harpsichord-tuning"), inv.id());
    }
}
