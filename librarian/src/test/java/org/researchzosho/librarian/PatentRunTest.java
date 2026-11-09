package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.records.GooglePatents;
import org.researchzosho.records.Ops;
import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.SearchTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run about prior art, start to end, with the office and the model stood in for: the worker calls the pool, the abstracts and the
 * claims in turn, each with the literal the one before it named; the claims it read are sources of the run; and the report carries the
 * library's own account, the office's count, the families enumerated, the abstracts and the claims read, each feature's closest claim
 * with its link, and what was not read, whatever the model wrote. And a collection that was asked and refused is said as refused.
 */
class PatentRunTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;
    Path realTally;
    OpsStandIn ops;
    GooglePatents.Reader realGoogle;
    double realGooglePace;

    @BeforeEach void standIns() throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        realTally = SearchTally.override; SearchTally.override = tmp.resolve("tally.json");
        ops = new OpsStandIn();
        Config.set(Ops.KEY_NAME, OpsStandIn.STAND_IN_KEY); Config.set(Ops.SECRET_NAME, OpsStandIn.STAND_IN_SECRET);
        realGoogle = GooglePatents.READER; realGooglePace = GooglePatents.pace;
        GooglePatents.READER = address -> new GooglePatents.Fetched(404, "");   // the stand-in keeps no page: nothing here asks the real site
        GooglePatents.pace = 0;
    }

    @AfterEach void back() {
        GooglePatents.READER = realGoogle; GooglePatents.pace = realGooglePace;
        ops.close();
        System.setProperty("user.home", realHome); Config.invalidate();
        SearchTally.override = realTally;
    }

    static final Pattern KEY = Pattern.compile("kept under the key (\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{16})");

    /** A worker that goes the goal's order, copying each step's literal from the answer before it; a writer of one section. */
    static class Chain extends RunBrainTest.Drive {
        final List<String> called = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = new ArrayList<>(); for (JsonNode t : tools) n.add(t.path("function").path("name").asText());
            offered.add(n); histories.add(history.deepCopy());
            if (n.equals(List.of("done"))) return RunBrainTest.call("done", M.createObjectNode().put("summary", "closed on the deadline"));
            int turn = 1; for (JsonNode m : history) if ("assistant".equals(m.path("role").asText())) turn++;
            if (n.contains("write_section")) return turn == 1 ? RunBrainTest.call("write_section", M.createObjectNode().put("heading", "Answer").put("text", "A kettle whose spout folds flat was claimed in 2008 (https://worldwide.espacenet.com/patent/search?q=pn%3DEP2100001A1)."))
                    : RunBrainTest.call("done", M.createObjectNode().put("summary", "the claims quoted"));
            String key = "";
            for (JsonNode m : history) { Matcher k = KEY.matcher(m.path("content").asText("")); if (k.find()) key = k.group(1); }
            ObjectNode call = switch (turn) {
                case 1 -> RunBrainTest.call("patents", (ObjectNode) read("{\"classes\": [\"A47J27/21\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": " + PatentsTest.FEATURES + "}"));
                case 2 -> RunBrainTest.call("patent_abstracts", M.createObjectNode().put("from", key));
                case 3 -> RunBrainTest.call("patent_claims", M.createObjectNode().put("from", key));
                case 4 -> RunBrainTest.call("note", M.createObjectNode().put("claim", "EP2100001A1 claims a kettle whose spout folds flat against the body").put("source", "https://worldwide.espacenet.com/patent/search?q=pn%3DEP2100001A1").put("quote", "the spout folds flat against the body"));
                default -> RunBrainTest.call("done", M.createObjectNode().put("summary", "the office counts 230 publications; 227 families enumerated; 227 abstracts and 3 families' claims read"));
            };
            called.add(call.path("tool_calls").get(0).path("function").path("name").asText());
            return call;
        }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) return "[\"Has anyone patented a kettle whose spout folds flat and that whistles: find the class, enumerate it, read the abstracts and the claims.\"]";
            if (prompt.contains("patent abstracts as the patent office holds them") || prompt.contains("the patent publication ")) return PatentsTest.byScript(prompt);
            return super.classify(messages, maxTokens);
        }
        static JsonNode read(String json) { try { return M.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); } }
    }

    @Test void aRunGoesThePoolTheAbstractsAndTheClaimsAndItsReportCarriesTheLibrarysOwnAccount() throws Exception {
        PatentsTest.kettles(ops);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Chain drive = new Chain();
        Researcher run = new Researcher(drive, drive, new PriorArtGoalTest.ToolsWithRecords(), s -> { }, 1, store);
        var r = run.run(new Researcher.Ask("Has anyone already patented a kettle whose spout folds flat and that whistles? Every application and grant from 2005 to 2015.", "depth", 60, List.of()), "");
        assertEquals(List.of("patents", "patent_abstracts", "patent_claims", "note", "done"), drive.called.subList(0, 5), "the worker's steps, in the goal's order");
        // each step answered the one before it: the key went on, the abstracts were read by the run's own model, the claims came from the office
        String worker = "";
        for (ArrayNode h : drive.histories) if (h.toString().contains("Claims read for the pool")) worker = h.toString();
        assertTrue(worker.contains("the office counts 230 publications; 230 were enumerated, in 227 families") && worker.contains("Read the abstracts of 227 of the 227 families") && worker.contains("the claims of 3 families were read from the office's own full text"), "the three answers reached the worker: " + worker.substring(0, Math.min(1500, worker.length())));
        assertTrue(worker.contains("Before your next step: note each feature's closest claim"), "and what it read is to be noted");
        assertEquals(3, ops.count("claims") - 1, "three families' claims were read from the office, and one number it does not hold was asked for once");
        // the report: the library's own account, whatever the model wrote
        String report = r.answer();
        assertTrue(report.contains("## Patent records enumerated and read\n\nWritten by the library from what the patent office returned and what was read, not by the model.\n\n- Classes and years: A47J27/21 (Water-boiling vessels, e.g. kettles), published 2005 to 2015, from the European Patent Office's Open Patent Services.\n- The office counts 230 publications; 230 were enumerated, in 227 families.\n- Abstracts read: 227 of 227 families.\n- Claims read: 3 families, from the office's own full text: [EP2100001A1](https://worldwide.espacenet.com/patent/search?q=pn%3DEP2100001A1)"), report);
        assertTrue(report.contains("- Closest for spout folds flat: EP2100001A1, claim 1 (stands on its own): \"the spout folds flat against the body\" https://worldwide.espacenet.com/patent/search?q=pn%3DEP2100001A1"), report);
        assertTrue(report.contains("- Closest for whistle: EP2100001A1, claim 2 (depends on another claim): \"a whistle mounted in the lid\""), report);
        assertTrue(report.contains("- Not read: the claims of 3 whose family has no claims text at the office, read by their abstracts instead (JP2012012345A, US2010000004A1, GB2400005A); the 5 publications in the subgroups under A47J27/21; the other offices' own databases, other classes and other years were not searched."), report);
        // the writer reads the library's own account, whose counts are later than any worker's note
        String writer = drive.histories.stream().map(h -> h.get(0).path("content").asText()).filter(t -> t.contains("Then call done.")).findFirst().orElse("");
        assertTrue(writer.contains("THE LIBRARY'S OWN ACCOUNT OF THE PATENT RECORDS OF THIS RUN") && writer.contains("Its counts are the final ones, later than any note") && writer.contains("- Claims read: 3 families, from the office's own full text"), writer.substring(Math.max(0, writer.length() - 2500)));
        // the report's own numbers are checked against the notes and the library's account alike: a count the account alone carries is backed
        assertTrue(run.backing("the notes").startsWith("the notes\n\n## Patent records enumerated and read") && run.backing("").contains("- The office counts 230 publications; 230 were enumerated, in 227 families."), run.backing(""));
        assertFalse(report.contains("230 publications — not in any note or source read this run") || report.contains("number 227 — not in any note"), report);
        // a worker of a later round is told to go on from the pool the run made, by its key, and not to enumerate again
        String key = ""; Matcher k = KEY.matcher(worker); if (k.find()) key = k.group(1);
        assertFalse(key.isEmpty());
        assertEquals("A pool of patents was already enumerated in this run and is kept under the key " + key + ": go on from it, and do not enumerate again. Call patent_abstracts {\"from\": \"" + key + "\", \"page\": 2}, which reads the abstracts not yet read, then patent_claims {\"from\": \"" + key + "\", \"page\": 2}, which reads the claims not yet read; then note each feature's closest claim with the link on its line.", Researcher.priorArtGoOn(key));
        // no patent search that ranks was on the table, and the pool was
        List<String> table = GoalRunTest.worker(drive);
        assertTrue(table.containsAll(List.of("patents", "patent_abstracts", "patent_claims")), table.toString());
    }

    @Test void aCollectionThatWasAskedAndDidNotAnswerIsSaidAsRefusedNeverAsNotSearched() throws Exception {
        RunBrainTest.Drive drive = new RunBrainTest.Drive();
        Researcher run = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, null);
        RecordSource google = RecordSources.named("google-patents"), papers = RecordSources.named("loc-newspapers");
        RecordSearchTool refused = new RecordSearchTool(List.of(google), url -> { throw new IllegalStateException("HTTP 503"); }).listen(run::recordSearched).refusals(run::recordRefused);
        for (String q : List.of("folding kettle", "collapsible kettle whistle")) assertTrue(refused.execute(M.readTree("{\"source\": \"google-patents\", \"query\": \"" + q + "\"}")).startsWith("ERROR: Google Patents did not answer (HTTP 503)."));
        // a collection that answered once and then refused: both are said
        AtomicInteger asked = new AtomicInteger();
        RecordSearchTool mixed = new RecordSearchTool(List.of(papers), url -> { if (asked.incrementAndGet() > 1) throw new IllegalStateException("HTTP 429: the source's limit on searches was reached; wait a minute"); return "{\"results\": []}"; }).listen(run::recordSearched).refusals(run::recordRefused);
        mixed.execute(M.readTree("{\"source\": \"loc-newspapers\", \"query\": \"Hale kettle works\"}"));
        mixed.execute(M.readTree("{\"source\": \"loc-newspapers\", \"query\": \"Hale kettle works Leeds\"}"));
        String section = run.searchedSection();
        assertTrue(section.contains("- Google Patents: asked 2 times and it did not answer (HTTP 503): refused, which is not a search that found nothing\n"), section);
        assertTrue(section.contains("- " + papers.name() + ": 1 search, 0 found records; asked 1 time more and it did not answer (HTTP 429: the source's limit on searches was reached; wait a minute)\n"), section);
        String never = section.contains("Not searched in this run: ") ? section.substring(section.indexOf("Not searched in this run: ")).split("\n")[0] : "";
        for (String name : never.replaceFirst("^Not searched in this run: ", "").replaceFirst("\\.$", "").split("; ")) assertFalse(name.equals("Google Patents") || name.equals(papers.name()), "a collection that was asked is not among those not searched: " + never);
        // in a run about prior art the collections taken off the table are said as that, never as not searched
        run.aboutPriorArt = true;
        String about = run.searchedSection();
        String off = about.contains("Not on this run's table") ? about.substring(about.indexOf("Not on this run's table")).split("\n")[0] : "";
        assertTrue(off.startsWith("Not on this run's table, since its patents were enumerated by class from the office's records and not searched for: ") && off.contains(RecordSources.named("epo-query").name()) == RecordSources.usable(RecordSources.named("epo-query")), about);
        String neverNow = about.contains("Not searched in this run: ") ? about.substring(about.indexOf("Not searched in this run: ")).split("\n")[0] : "";
        assertFalse(neverNow.contains(RecordSources.named("epo-query").name()), neverNow);
        run.aboutPriorArt = false;
        // the copies the run makes of the tool keep the listener: a refusal after the ranked collections are taken out is still heard
        RecordSearchTool without = new RecordSearchTool(List.of(google, papers), url -> { throw new IllegalStateException("HTTP 500"); }).refusals(run::recordRefused).without(List.of("google-patents"));
        assertTrue(without.execute(M.readTree("{\"source\": \"google-patents\", \"query\": \"kettle\"}")).startsWith("ERROR: no source \"google-patents\""));
        without.execute(M.readTree("{\"source\": \"loc-newspapers\", \"query\": \"kettle\"}"));
        assertTrue(run.searchedSection().contains("asked 2 times more and it did not answer (HTTP 500)"), run.searchedSection());
    }
}
