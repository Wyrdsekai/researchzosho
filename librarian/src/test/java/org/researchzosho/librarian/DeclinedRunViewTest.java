package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.Declined;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run the model declined is said to be declined wherever a run is shown: the job view (MCP, HTTP, the chat's tools), the web page of
 * the run and its line in a list, the chat's strip and its notice, the terminal's jobs output, and the run ledger. Not as found nothing,
 * not as a failure of the machine.
 */
class DeclinedRunViewTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String DECLINE = "I am not able to help with that request.";
    @TempDir Path tmp;

    static Researcher.Result declinedResult() {
        Declined d = new Declined("placeholder-model", DECLINE, Declined.How.WORDS).seat("judge").at("to plan this research");
        return new Researcher.Result(false, "", "", 1, 0, 0, List.of(), List.of(), M.createObjectNode(), List.of(d), d);
    }

    /** A finished research job whose run the model declined, as the daemon leaves it on the ledger. */
    String declinedJob(LibraryStore store) throws Exception {
        Researcher.Result r = declinedResult();
        Jobs[] holder = new Jobs[1];
        Jobs jobs = new Jobs(store, (job, drive) -> {
            holder[0].declined(job.path("job_id").asText(), r.declinedView());
            return r.declinedStatement();
        }, List.of(""), 1);
        holder[0] = jobs;
        String id = jobs.submit("research", "", (ObjectNode) M.readTree("{\"question\":\"Placeholder question about the placeholder topic\"}"));
        jobs.start();
        try {
            long t0 = System.currentTimeMillis();
            while (!"done".equals(jobs.get(id).path("state").asText()) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(25);
        } finally { jobs.stop(); }
        return id;
    }

    @Test
    void theJobViewCarriesDeclined() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = declinedJob(store);
        JsonNode j = new LibraryProtocol(store).job((ObjectNode) M.readTree("{\"job_id\":\"" + id + "\"}")).path("job");
        assertEquals("done", j.path("state").asText(), "declined is not a failure of the machine");
        assertFalse(j.path("is_error").asBoolean());
        assertTrue(j.path("declined").path("run").asBoolean(), j.toString());
        assertEquals("placeholder-model", j.path("declined").path("model").asText());
        assertEquals("to plan this research", j.path("declined").path("parts").get(0).path("step").asText());
        assertEquals(DECLINE, j.path("declined").path("parts").get(0).path("said").asText());
        assertTrue(j.path("declined").path("statement").asText().startsWith("The model this library uses (placeholder-model) declined to research this question. ResearchZosho did not try to get around it."), j.toString());
        assertEquals(j.path("declined").path("statement").asText(), j.path("result").asText(), "the result is the statement");
        assertFalse(j.has("investigation"));
    }

    @Test
    void thePagesTheChatAndTheTerminalSayItWasDeclined() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String id = declinedJob(store);
        LibraryProtocol lp = new LibraryProtocol(store);
        JsonNode j = lp.job((ObjectNode) M.readTree("{\"job_id\":\"" + id + "\"}")).path("job");
        String page = Pages.job(store, lp, Librarian.person(), id, Map.of());
        assertTrue(page.contains("Declined by the model"), page);
        assertTrue(page.contains("ResearchZosho did not try to get around it."), page);
        assertTrue(Pages.jobLine(j).contains("declined by the model"), Pages.jobLine(j));
        RunProgress.View v = RunProgress.of(j);
        assertEquals("Declined by the model", v.stage());
        assertFalse(v.active());
        Librarian.Session s = Librarian.Session.open(store);
        s.watch(id);
        String told = s.told(id, v);
        assertTrue(told.contains("declined") && told.contains("ResearchZosho did not try to get around it."), told);
        assertFalse(told.contains("Say \"show it\""), "there is no answer to show: " + told);
        assertTrue(LibrarianCli.jobRow(j).contains("declined by the model"), LibrarianCli.jobRow(j));
    }

    @Test
    void theRunLedgerHasAColumnForIt() {
        Researcher.Result r = declinedResult();
        ObjectNode row = RunLedger.row("J-0001", new Researcher.Ask("Placeholder question", "broad", 0, List.of()), r, "declined by the model", 1000, "", "placeholder-model", null);
        assertEquals("run", row.path("declined").asText());
        assertEquals(1, row.path("declined_parts").asInt());
        ObjectNode ok = RunLedger.row("J-0002", new Researcher.Ask("Placeholder question", "broad", 0, List.of()),
                new Researcher.Result(true, "an answer", "", 3, 1, 1, List.of()), "filed I-1", 1000, "", "m", null);
        assertEquals("", ok.path("declined").asText());
        Map<String, String> sum = RunLedger.summary(List.of(row, ok));
        assertEquals("1 of 2", sum.get("declined by the model"), sum.toString());
    }

    @Test
    void aWritersOwnHeadingThatBeginsWithDeclinedIsPartOfTheAnswer() {
        // L3: the harness's "Declined" section is matched exactly; a writer's "Declined applications" is the answer's own
        String body = "## Answer\nThe placeholder bank changed its rules in 2019 [1].\n\n## Declined applications\nThe bank declined 40% of applications in 2020 [2].\n\n"
                + "## Declined\n\nWritten by the library, not by the model.\n- The model placeholder-model declined to research the sub-question \"who decided?\".";
        String answer = LibraryProtocol.sectionOf(body, "answer");
        assertTrue(answer.contains("## Declined applications") && answer.contains("declined 40% of applications"), answer);
        assertFalse(answer.contains("Written by the library"), "the library's own Declined section is not the answer: " + answer);
    }

    @Test
    void theReviewDoesNotReadTheDeclinedSectionAsClaims() {
        // L4: the Declined section was in the text the review extracts claims from, so a declined sub-question became a "not found" finding
        String body = "## Answer\nThe placeholder gears were cut by hand [1].\n\n## Declined applications\nThe bank declined 40% of applications [2].\n\n"
                + "## Declined\n\nWritten by the library, not by the model.\n- The model placeholder-model declined to research the sub-question \"who cut them?\".\n\n"
                + "## References\n[1] https://example.org/gears";
        String read = LibrarianReview.forExtraction(body, 32_000);
        assertFalse(read.contains("Written by the library") || read.contains("who cut them?"), read);
        assertTrue(read.contains("## Declined applications") && read.contains("## References"), read);
    }

    @Test
    void eachDeclinedPartNamesItsOwnModel() {
        // L6: the workers' seat and the judge seat can run different models; the job view named only the first
        Declined worker = new Declined("worker-model", DECLINE, Declined.How.WORDS).seat("workers").at("to research the sub-question \"who cut them?\"");
        Declined judge = new Declined("judge-model", "", Declined.How.FILTERED).seat("judge").at("to check whether the research covers the question");
        Researcher.Result r = new Researcher.Result(true, "an answer", "", 3, 2, 1, List.of(), List.of(), M.createObjectNode(), List.of(worker, judge), null);
        ObjectNode view = r.declinedView();
        assertEquals("worker-model", view.path("parts").get(0).path("model").asText(), view.toString());
        assertEquals("judge-model", view.path("parts").get(1).path("model").asText(), view.toString());
        assertTrue(view.path("statement").asText().startsWith("The models this library uses (worker-model, judge-model) declined 2 parts of this research."), view.toString());
    }
}
