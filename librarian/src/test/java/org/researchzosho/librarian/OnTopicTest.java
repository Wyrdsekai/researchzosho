package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.Tool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The defenses against a report on another subject than its question, on the case of 2026-11-05: the workers researched the ask, the
 * first reply of the write-up was a fetch on an unrelated subject, seven sections followed on it, the analysis and the checks carried
 * on, the report was filed as accepted and eight claims on the wrong subject were filed under the question. Scripted drives, fake
 * pages, no model and no network: the write-up is read against the question on the judge's seat before anything is built on it; a
 * write-up that does not answer is set aside and the report is the workers' findings; the report then waits for the keeper; the
 * trace names the stage of every call and keeps the reasoning of each stage's first; the pages a write-up fetches count as read, up to
 * an allowance; and a report's state follows its checks.
 */
class OnTopicTest {

    static final ObjectMapper J = new ObjectMapper();

    static final String ASK = "As of November 2026, who is building a layer that sits between one person's several AI agents and routes asks and handoffs between those agents through the person?";
    static final List<String> SUBS = List.of("Which open-source projects route messages between one person's coding agents?", "Which products ask the person's approval before an agent's ask carries their authority?");
    static final String MAIL = "https://example.org/agent-mail", CONTAGION = "https://encyclopedia.example/Emotional_contagion";
    static final String ON_ANSWER = "One project builds such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + "). "
            + "No project was found that asks the person's approval before an ask carries their authority.";
    static final String OFF_ANSWER = "Emotional contagion is the tendency to catch the emotions of other people. Hatfield's account rests on mimicry and afferent feedback (" + CONTAGION + "). "
            + "It differs from empathy, which keeps self and other apart.";
    static final String OFF_REASONING = "The mechanism of emotional contagion is the centre of this. Gallese on embodied simulation and intentional attunement should be checked first.";
    static final String NO = "{\"verdict\": \"no\", \"why\": \"The answer is about how emotions spread between people; the question asks who builds a layer between one person's AI agents.\"}";
    static final String YES = "{\"verdict\": \"yes\", \"why\": \"The answer names a project that routes messages between a person's agents, which is what the question asks.\"}";
    static final String PARTLY = "{\"verdict\": \"partly\", \"why\": \"The answer names a routing project and says nothing of the shared record the question asks about.\"}";

    /** Fake pages: a search that lists the one page on the ask, and a fetch that answers as the real one does, with its source line, and keeps the page in the library. */
    static final class Pages implements Researcher.Tools {
        final LibraryStore store;
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Map<String, String> text = new ConcurrentHashMap<>(Map.of(
                MAIL, "mcp_agent_mail routes messages between coding agents through a shared mailbox. It keeps a record of what each agent said and decided.",
                CONTAGION, "Emotional contagion is the tendency to catch the emotions of other people. Hatfield's account rests on mimicry and afferent feedback."));
        Pages(LibraryStore store) { this.store = store; }
        @Override public List<Tool> web(String focus) {
            Tool search = ResearcherTest.tool("web_search", "search", "query", a -> "1. Agent mail — " + MAIL);
            Tool fetch = ResearcherTest.tool("web_fetch", "fetch", "url", a -> {
                String url = a.path("url").asText();
                fetched.add(url);
                String page = text.getOrDefault(url, "A page at " + url + " about the routing of messages between agents.");
                RawCapture.capture(store, url, page, "A page", "test", "");
                return "source: " + url + " — A page — fetched 2026-11-05T00:41:00Z\n" + page;
            });
            return List.of(search, fetch);
        }
        @Override public BooleanSupplier exhausted() { return () -> false; }
    }

    static ObjectNode call(String name, ObjectNode args) {
        ObjectNode m = J.createObjectNode(); m.put("role", "assistant"); m.putNull("content");
        ObjectNode c = m.putArray("tool_calls").addObject();
        c.put("id", "c" + System.nanoTime()); c.put("type", "function");
        c.putObject("function").put("name", name).put("arguments", args.toString());
        return m;
    }

    static ObjectNode section(String heading, String text) { return call("write_section", J.createObjectNode().put("heading", heading).put("text", text)); }

    static int turnsSoFar(ArrayNode history) { int n = 0; for (JsonNode m : history) if ("assistant".equals(m.path("role").asText())) n++; return n; }

    /**
     * The model of the case. A worker searches, reads the page on the ask, notes it and closes. A write-up is on the ask or off it, by
     * its number in {@link #writeUps}: off, its first reply is a fetch on emotional contagion after reasoning about it, then sections on
     * it; on, it answers from the notes. The judge answers the topic check by what the answer it is shown says.
     */
    static class CaseDrive implements Researcher.Drive {
        List<String> writeUps = List.of("off");
        Function<String, String> topic = prompt -> prompt.contains("contagion") ? NO : YES;
        Function<Integer, ObjectNode> onTopicWriteUp = turn -> switch (turn) {
            case 1 -> section("Answer", ON_ANSWER);
            case 2 -> section("Sources", MAIL);
            default -> call("done", J.createObjectNode().put("summary", "one project only"));
        };
        final AtomicInteger started = new AtomicInteger();
        final List<String> firstRequests = new CopyOnWriteArrayList<>(), topicPrompts = new CopyOnWriteArrayList<>(), analysisRequests = new CopyOnWriteArrayList<>(), toolAnswers = new CopyOnWriteArrayList<>();

        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> names = new ArrayList<>(); for (JsonNode t : tools) names.add(t.path("function").path("name").asText());
            if (names.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed on the deadline"));
            int turn = turnsSoFar(history) + 1;
            String system = history.get(0).path("content").asText();
            if (system.contains("writing the analysis layer")) {
                analysisRequests.add(history.toString());
                return turn == 1 ? section("Judgments", "One project routes messages between agents [1].") : call("done", J.createObjectNode().put("summary", ""));
            }
            if (names.contains("revise")) return call("revise", J.createObjectNode().put("n", 0).put("revised", ""));   // no revision is offered
            if (names.contains("write_section")) {
                if (turn == 1) { started.incrementAndGet(); firstRequests.add(history.toString()); }
                for (JsonNode m : history) if ("tool".equals(m.path("role").asText())) toolAnswers.add(m.path("content").asText());
                boolean off = turn == 1 ? writeUps.get(Math.min(started.get(), writeUps.size()) - 1).equals("off") : history.toString().contains("encyclopedia.example");
                if (!off) return onTopicWriteUp.apply(turn);
                return switch (turn) {
                    case 1 -> { ObjectNode m = call("web_fetch", J.createObjectNode().put("url", CONTAGION).put("find", "Gallese mirror neuron intentional attunement embodied simulation")); m.put("reasoning_content", OFF_REASONING); yield m; }
                    case 2 -> section("Answer", OFF_ANSWER);
                    case 3 -> section("The mechanism: three competing accounts", "Hatfield's primitive contagion, Gallese's embodied simulation and common coding compete (" + CONTAGION + ").");
                    case 4 -> section("Sources", CONTAGION);
                    default -> call("done", J.createObjectNode().put("summary", "the accounts are contested"));
                };
            }
            return switch (turn) {
                case 1 -> call("web_search", J.createObjectNode().put("query", "layer between a person's AI agents"));
                case 2 -> call("web_fetch", J.createObjectNode().put("url", MAIL));
                case 3 -> call("note", J.createObjectNode().put("claim", "mcp_agent_mail routes messages between coding agents through a shared mailbox").put("source", MAIL).put("quote", "routes messages between coding agents through a shared mailbox"));
                default -> call("done", J.createObjectNode().put("summary", "One project, mcp_agent_mail, routes messages between coding agents through a shared mailbox. Nothing found asks the person's approval first."));
            };
        }

        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("whether the report's answer is about what was asked")) { topicPrompts.add(prompt); return topic.apply(prompt); }
            if (prompt.contains("Decompose")) return "[\"" + SUBS.get(0) + "\", \"" + SUBS.get(1) + "\"]";
            if (prompt.contains("reviewing research COVERAGE")) return "{\"sufficient\": true}";
            if (prompt.contains("Split this research request")) return "{\"task\": \"the question\", \"format\": \"none\"}";
            if (prompt.contains("Does the source support the sentence")) return "{\"verdict\": \"supported\"}";
            return "";
        }

        @Override public int contextWindow() { return 32_000; }
    }

    /** The review's model: the claims it reads out of a report, and what it says of the report's answer against its question (null: this seat has no such check). */
    static final class Reviewer implements LibrarianReview.Judge {
        String claims = "[]";
        TopicCheck.Verdict topic = null;
        final List<String> read = new ArrayList<>(), compared = new ArrayList<>();
        @Override public String extract(String body) { read.add(body); return claims; }
        @Override public String compare(String candidate, String neighbors) { return "{\"verdict\": \"independent\"}"; }
        @Override public TopicCheck.Verdict onTopic(String question, List<String> subQuestions, String answer) { compared.add(question + "\n" + subQuestions + "\n" + answer); return topic; }
    }

    static final String WORKERS_CLAIM = "{\"title\": \"mcp_agent_mail routes messages between coding agents\", \"claim\": \"mcp_agent_mail routes messages between coding agents through a shared mailbox.\", "
            + "\"claim_type\": \"extraction\", \"confidence\": \"medium\", \"volatility\": \"slow\", \"sources\": [\"" + MAIL + "\"]}";
    static final String CONTAGION_CLAIM = "{\"title\": \"Emotional contagion rests on mimicry\", \"claim\": \"Hatfield's account of emotional contagion rests on mimicry and afferent feedback.\", "
            + "\"claim_type\": \"extraction\", \"confidence\": \"medium\", \"volatility\": \"slow\", \"sources\": [\"" + CONTAGION + "\"]}";

    static LibraryStore library(Path dir) throws Exception {
        LibraryStore store = new LibraryStore(dir); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** One filed run, as the service runs it: both seats seen through the job's trace. */
    record Run(LibraryStore store, CaseDrive drive, Pages pages, Researcher.Filed filed, List<String> log, List<JsonNode> trace) {
        Investigation report() throws Exception { return store.investigation(filed.investigationId()); }
        List<JsonNode> calls() { return trace.stream().filter(l -> l.path("capture").asText().equals("llm_call")).toList(); }
        Set<String> seats() { Set<String> s = new LinkedHashSet<>(); for (JsonNode l : calls()) s.add(l.path("seat").asText()); return s; }
    }

    static Run run(Path dir, CaseDrive drive, Researcher.Ask ask) throws Exception {
        LibraryStore store = library(dir);
        Pages pages = new Pages(store);
        List<String> log = new CopyOnWriteArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-0157");
        Researcher researcher = new Researcher(trace.wrap(drive, "workers"), trace.wrap(drive, "judge"), pages, log::add, 2, store);
        researcher.trace(trace);
        Researcher.Filed filed = Researcher.file(store, researcher, ask, "patron:did:key:test", "J-0157", "asked");
        List<JsonNode> lines = new ArrayList<>();
        for (String l : Files.readAllLines(RunTrace.fileFor(store, "J-0157"), StandardCharsets.UTF_8)) lines.add(J.readTree(l));
        return new Run(store, drive, pages, filed, log, lines);
    }

    static Researcher.Ask ask() { return new Researcher.Ask(ASK, "broad", 0, SUBS, "web", List.of(), 0); }

    // ---- the case ----

    @Test
    void aWriteUpOnAnotherSubjectIsSetAsideAndTheReportIsTheWorkersFindings(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("off", "off");   // the run has room, so the report is written once more; the second drifts too
        Run run = run(tmp, drive, ask().withFields(List.of("organization")));   // a field whose reports carry the analysis layer
        assertTrue(run.filed().admitted(), run.filed().reason());
        Researcher.Result r = run.filed().result();

        // the judge was asked with the question, its sub-questions and the first section of the answer, and nothing of the sources
        assertEquals(2, drive.topicPrompts.size(), "one check for each write-up");
        for (String prompt : drive.topicPrompts) {
            assertTrue(prompt.contains(ASK) && prompt.contains("- " + SUBS.get(0)) && prompt.contains("- " + SUBS.get(1)), prompt);
            assertTrue(prompt.contains("## Answer\n\n" + OFF_ANSWER), prompt);
            assertFalse(prompt.contains("EVIDENCE") || prompt.contains("SOURCES (cite by number") || prompt.contains("routes messages between coding agents") || prompt.contains("three competing accounts"), "only the question and the answer's first section: " + prompt);
        }
        // the second write-up was shown nothing of the first
        assertEquals(2, drive.started.get());
        assertFalse(drive.firstRequests.get(1).contains("contagion") || drive.firstRequests.get(1).contains("encyclopedia.example"), drive.firstRequests.get(1));
        assertTrue(run.log().stream().anyMatch(l -> l.contains("the run's time has room for one more") && l.contains("nothing of the first write-up is shown to the second")), run.log().toString());

        // the report is the workers' findings under the plain heading and sentence; nothing of the set-aside text is in it
        Investigation report = run.report();
        String body = report.body();
        assertTrue(body.contains("## " + Researcher.SET_ASIDE_HEADING + "\n\nThe write-up did not answer the question, so this report is the workers' findings as they stand."), body);
        assertTrue(body.contains("The report was written a second time, and that one did not answer the question either."), body);
        assertTrue(body.contains("### " + SUBS.get(0) + "\n\nOne project, mcp_agent_mail, routes messages between coding agents through a shared mailbox."), "each worker's own summary under its sub-question: " + body);
        for (String gone : List.of("Hatfield", "mimicry", "three competing accounts", "encyclopedia.example", "the accounts are contested"))
            assertFalse(body.contains(gone), "nothing of the write-up on the other subject: " + gone + "\n" + body);
        // the sources the set-aside text cited are not the report's sources, though the write-up fetched the page
        assertTrue(run.pages().fetched.contains(CONTAGION), "the write-up did fetch it");
        assertTrue(body.contains("## References") && body.contains("[1] ") && body.contains(MAIL) && body.contains("## Evidence"), body);
        assertTrue(body.contains("## Sources cited\n\n- " + MAIL + "\n"), body);
        // the set-aside write-ups are kept apart, whole, for the keeper to read and to judge the check by; nothing on the shelves holds them
        String kept = Files.readString(ReportStanding.setAsideFile(run.store(), report.id()));
        assertTrue(kept.contains("Write-up 1 of 2. The check said: no. The answer is about how emotions spread between people") && kept.contains("Write-up 2 of 2.") && kept.contains(OFF_ANSWER) && kept.contains("three competing accounts") && kept.contains("The question: " + ASK), kept);
        assertTrue(body.contains("It is kept apart for you to read, in the library's folder catalog/set-aside, under this report's id."), body);
        // (the page the write-up fetched stays a captured page, as every page the library fetched does; no report and no claim holds its subject)
        assertTrue(new LibrarianIndex(run.store(), Embeddings.none()).searchIn("Hatfield mimicry contagion", 8, null, null).stream().allMatch(h -> h.kind().equals("raw") || h.kind().equals("chunk")), "no report and no claim on the shelves holds the set-aside text");
        // no analysis layer was written on the set-aside text, and no check or revision ran on the library's own section
        assertTrue(drive.analysisRequests.isEmpty(), "no analysis pass");
        assertFalse(body.contains("## Judgments") || body.contains("## Revision after the checks") || body.contains("Found and not in the answer above"), body);
        assertTrue(body.contains("cite-check: not run, since the write-up was set aside"), body);
        // the Checks section says why the state is what it is, in a sentence, with the judge's line
        assertTrue(body.contains("## Checks\n\n- state: not accepted, it waits for your eye. The write-up did not answer the question, so it was set aside and this report is the workers' findings as they stand. "
                + "The check said: \"The answer is about how emotions spread between people; the question asks who builds a layer between one person's AI agents.\""), body);

        // the run says it: not an error, not an accepted report
        assertEquals("no", r.topic());
        assertTrue(r.setAside() && r.done());
        assertTrue(r.summary().contains("the write-up did not answer the question and was set aside"), r.summary());
        assertTrue(r.heldStatement().startsWith("This report is not accepted. It waits for you to look at it. The write-up did not answer the question"), r.heldStatement());
        ObjectNode checks = r.checksView();
        assertEquals("no", checks.path("topic").asText()); assertTrue(checks.path("set_aside").asBoolean() && checks.path("held").asBoolean()); assertEquals(1, checks.path("reasons").size());
        assertEquals(Finding.State.draft, report.state());
        ReportStanding.Row hold = ReportStanding.held(run.store(), report);
        assertNotNull(hold, "the report waits for the keeper");
        assertEquals("run", hold.by()); assertEquals("no", hold.topic());

        // the review reads the claims out of the workers' notes only, and the report stays a draft
        Reviewer reviewer = new Reviewer();
        reviewer.claims = "[" + WORKERS_CLAIM + ", " + CONTAGION_CLAIM + "]";   // a reviewer that still came up with the other subject cites a source the report does not hold
        LibrarianReview review = new LibrarianReview(run.store(), new LibrarianIndex(run.store(), Embeddings.none()), reviewer, "librarian:test");
        LibrarianReview.Outcome out = review.review(report);
        assertTrue(reviewer.compared.isEmpty(), "the run's own verdict stands: the review does not ask again");
        assertEquals(1, reviewer.read.size());
        assertFalse(reviewer.read.get(0).contains("Hatfield") || reviewer.read.get(0).contains("encyclopedia.example"), "the reviewer is never shown the set-aside text");
        Investigation reviewed = run.report();
        assertEquals(Finding.State.draft, reviewed.state(), "not accepted: " + out.problems());
        assertEquals(1, reviewed.findings().size(), "the workers' claim is filed, the other subject's is not: " + out.problems());
        assertTrue(run.store().finding(reviewed.findings().get(0)).title().contains("mcp_agent_mail"));
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("cites no source present in the investigation")), out.problems().toString());
        assertTrue(out.problems().stream().anyMatch(p -> p.contains("the report is not accepted and waits for your eye") && p.contains("researchzosho accept " + report.id())), out.problems().toString());
        // the nightly review leaves it: its claims are read out, and it has not changed
        assertTrue(Crews.reviewDrafts(run.store(), review).contains("1 left as a draft that waits for the keeper's eye"));
        assertEquals(1, reviewer.read.size(), "not read a second time");

        // the keeper sees it waiting, with why, and accepts it as it stands
        List<ReportStanding.Waiting> waiting = ReportStanding.waiting(run.store());
        assertEquals(1, waiting.size());
        assertTrue(LibrarianCli.waitingReports(waiting).contains("1 research report is waiting for you to look at it.") && LibrarianCli.waitingReports(waiting).contains("Why it waits: The write-up did not answer the question"), LibrarianCli.waitingReports(waiting));
        // a program that reads the report is told too: a draft of low confidence, with who held it and why
        ObjectNode entry = new LibraryProtocol(run.store()).entry(report.id(), "investigation", true);
        assertEquals("draft", entry.path("state").asText()); assertEquals("low", entry.path("confidence").asText());
        assertEquals("run", entry.path("waits").path("held_by").asText()); assertEquals("no", entry.path("waits").path("topic").asText());
        assertTrue(entry.path("waits").path("reasons").get(0).asText().startsWith("The write-up did not answer the question"), entry.path("waits").toString());
        JsonNode inbox = new LibraryProtocol(run.store()).inbox(J.createObjectNode().put("op", "list"));
        assertEquals(report.id(), inbox.path("reports").get(0).path("id").asText());
        assertEquals("no", inbox.path("reports").get(0).path("topic").asText());
        JsonNode accepted = new LibraryProtocol(run.store()).inbox(J.createObjectNode().put("op", "accept").put("id", report.id()));
        assertEquals("accepted", accepted.path("decided").get(0).path("state").asText());
        assertEquals(Finding.State.accepted, run.report().state());
        assertTrue(ReportStanding.waiting(run.store()).isEmpty());
        ObjectNode after = new LibraryProtocol(run.store()).entry(report.id(), "investigation", true);
        assertFalse(after.has("waits"), after.toString()); assertEquals("medium", after.path("confidence").asText());

        // the trace: every call under its stage's seat, the drive beside it, and the reasoning of the first call of each stage
        assertTrue(run.seats().containsAll(List.of(RunTrace.WORKERS, RunTrace.CRITIC, RunTrace.WRITE_UP, RunTrace.TOPIC_CHECK)), run.seats().toString());
        List<JsonNode> writeUpCalls = run.calls().stream().filter(l -> l.path("seat").asText().equals(RunTrace.WRITE_UP)).toList();
        JsonNode first = writeUpCalls.get(0);
        assertEquals("workers", first.path("drive").asText());
        assertTrue(first.path("first_of_stage").asBoolean());
        assertEquals(OFF_REASONING, first.path("reply").path("reasoning").asText(), "what the model thought before its first act: " + first.path("reply"));
        assertEquals("web_fetch", first.path("reply").path("tool_calls").get(0).path("name").asText());
        assertTrue(first.path("reply").path("tool_calls").get(0).path("arguments").asText().contains("Emotional_contagion"));
        assertEquals(2, writeUpCalls.stream().filter(l -> l.path("first_of_stage").asBoolean()).count(), "the second write-up has a first call of its own");
        assertEquals(2, run.calls().stream().filter(l -> l.path("seat").asText().equals(RunTrace.WORKERS) && l.path("first_of_stage").asBoolean()).count(), "each worker's first call is one");
        JsonNode topicCall = run.calls().stream().filter(l -> l.path("seat").asText().equals(RunTrace.TOPIC_CHECK)).findFirst().orElseThrow();
        assertEquals("judge", topicCall.path("drive").asText()); assertEquals("classify", topicCall.path("kind").asText());
        assertTrue(topicCall.path("reply").path("head").asText().contains("\"verdict\": \"no\""));
        // the set-aside write-up is kept on the trace, whole, for whoever traces the case
        List<JsonNode> aside = run.trace().stream().filter(l -> l.path("capture").asText().equals("writeup_set_aside")).toList();
        assertEquals(2, aside.size());
        assertTrue(aside.get(0).path("text").asText().contains(OFF_ANSWER) && aside.get(0).path("fetched").get(0).asText().equals(CONTAGION), aside.get(0).toString());
        assertEquals(2, run.trace().stream().filter(l -> l.path("capture").asText().equals("topic_check") && l.path("verdict").asText().equals("no")).count());
    }

    @Test
    void aSecondWriteUpThatAnswersTheQuestionIsFiledAsUsual(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("off", "on");
        Run run = run(tmp, drive, ask());
        String body = run.report().body();
        assertTrue(body.contains("## Answer\n\nOne project builds such a layer."), body);
        assertFalse(body.contains("contagion") || body.contains("encyclopedia.example") || body.contains(Researcher.SET_ASIDE_HEADING), "the first write-up left nothing, its fetched page included: " + body);
        assertEquals("yes", run.filed().result().topic());
        assertTrue(run.filed().result().heldFor().isEmpty() && !run.filed().result().setAside());
        assertTrue(run.filed().result().stats().path("writeup_retried").asBoolean());
        assertFalse(body.contains("state: not accepted"), body);
        assertNull(ReportStanding.held(run.store(), run.report()));
        Reviewer reviewer = new Reviewer(); reviewer.claims = "[" + WORKERS_CLAIM + "]";
        new LibrarianReview(run.store(), new LibrarianIndex(run.store(), Embeddings.none()), reviewer, "librarian:test").review(run.report());
        assertEquals(Finding.State.accepted, run.report().state());
        assertTrue(reviewer.compared.isEmpty(), "the run checked it: the review does not ask again");
    }

    @Test
    void withNoRoomInTheAsksCeilingThereIsNoSecondWriteUp(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("off", "on");   // a second write-up would have answered; the ceiling leaves no room for one
        // 17 turns, one sub-question: 4 for the worker, 1 for the critic, 5 for the write-up, 1 for the topic check; 6 are left, 3 of them the
        // cite-check's, and a second write-up is started with four or more
        Run run = run(tmp, drive, new Researcher.Ask(ASK, "broad", 17, List.of(SUBS.get(0)), "web", List.of(), 0));
        assertEquals(1, drive.started.get(), "one write-up: " + run.log());
        assertTrue(run.log().stream().anyMatch(l -> l.contains("the run's time has no room for a second write-up")), run.log().toString());
        assertTrue(run.report().body().contains(Researcher.SET_ASIDE_SENTENCE) && !run.report().body().contains("written a second time"), run.report().body());
        assertEquals(Finding.State.draft, run.report().state());
        // a report that is removed takes its set-aside write-up with it
        Path kept = ReportStanding.setAsideFile(run.store(), run.report().id());
        assertTrue(Files.readString(kept).contains("Write-up 1. The check said: no."), "one write-up, kept apart");
        Removal.apply(run.store(), Removal.plan(run.store(), run.filed().investigationId(), Removal.What.all), "person");
        assertFalse(Files.exists(kept), "removed with its report");
        assertTrue(ReportStanding.waiting(run.store()).isEmpty(), "and a report that is gone no longer waits");
        // the same rule by the clock: a write-up that took 40 seconds leaves no room in a minute, one that took ten does
        assertFalse(new Researcher.Budget(0, 1).roomForAnotherWriteUp(40_000));
        assertTrue(new Researcher.Budget(0, 1).roomForAnotherWriteUp(10_000));
        assertTrue(new Researcher.Budget(0).roomForAnotherWriteUp(3_600_000), "no ceiling: room");
    }

    @Test
    void aWriteUpThatAnswersPartOfTheQuestionIsFiledAsUsualAndWaitsForTheKeeper(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        drive.topic = prompt -> PARTLY;
        Run run = run(tmp, drive, ask());
        String body = run.report().body();
        assertEquals(1, drive.started.get(), "partly is not written again");
        assertTrue(body.contains("## Answer\n\nOne project builds such a layer."), "filed as usual: " + body);
        assertTrue(body.contains("- state: not accepted, it waits for your eye. The write-up starts on the question and turns to another subject. The check said: \"The answer names a routing project and says nothing of the shared record the question asks about.\""), body);
        assertEquals("partly", run.filed().result().topic());
        assertFalse(run.filed().result().setAside());
        assertEquals(Finding.State.draft, run.report().state());
        Reviewer reviewer = new Reviewer(); reviewer.claims = "[" + WORKERS_CLAIM + "]";
        var out = new LibrarianReview(run.store(), new LibrarianIndex(run.store(), Embeddings.none()), reviewer, "librarian:test").review(run.report());
        assertEquals(1, run.report().findings().size(), "its claims are read out: " + out.problems());
        assertEquals(Finding.State.draft, run.report().state(), "and it is not accepted");
        assertEquals("partly", ReportStanding.waiting(run.store()).get(0).hold().topic());
    }

    @Test
    void aWriteUpThatAnswersTheQuestionIsFiledAndAcceptedAsBefore(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        // no sub-questions given, so the plan is a call of its own; the organization field, so the analysis layer is one too
        Run run = run(tmp, drive, new Researcher.Ask(ASK, "broad", 0, List.of(), "web", List.of(), 0).withFields(List.of("organization")));
        String body = run.report().body();
        assertEquals(1, drive.topicPrompts.size());
        assertTrue(drive.topicPrompts.get(0).contains("## Answer\n\n" + ON_ANSWER));
        assertTrue(body.contains("## Answer\n\nOne project builds such a layer.") && body.contains("## Judgments\n\nOne project routes messages between agents [1]."), "the analysis layer follows a write-up that answers: " + body);
        assertFalse(body.contains("state: not accepted") || body.contains("topic:"), "a report that passed says nothing more than it did: " + body);
        assertEquals("yes", run.filed().result().topic());
        assertTrue(run.filed().result().heldStatement().isEmpty());
        assertFalse(run.filed().result().checksView().path("held").asBoolean());
        Reviewer reviewer = new Reviewer(); reviewer.claims = "[" + WORKERS_CLAIM + "]";
        new LibrarianReview(run.store(), new LibrarianIndex(run.store(), Embeddings.none()), reviewer, "librarian:test").review(run.report());
        assertEquals(Finding.State.accepted, run.report().state());
        assertTrue(ReportStanding.waiting(run.store()).isEmpty());
        // every stage of the run has its own seat on the trace
        assertTrue(run.seats().containsAll(List.of(RunTrace.PLAN, RunTrace.WORKERS, RunTrace.CRITIC, RunTrace.WRITE_UP, RunTrace.TOPIC_CHECK, RunTrace.ANALYSIS)), run.seats().toString());
        assertFalse(run.seats().contains("judge"), "no call of a run is left unnamed: " + run.seats());
        for (JsonNode l : run.calls()) assertTrue(Set.of("workers", "judge").contains(l.path("drive").asText()), l.toString());
    }

    @Test
    void aCheckThatGotNoAnswerIsSaidAsThatAndTheReviewAsksBeforeItReadsAnyClaim(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        drive.topic = prompt -> "";   // the judge gave no answer
        Run run = run(tmp, drive, ask());
        String body = run.report().body();
        assertEquals("unchecked", run.filed().result().topic());
        assertTrue(body.contains("- topic: the write-up was not checked against the question (the judge gave no answer). The review checks it before it reads any claim out of this report."), body);
        assertTrue(run.filed().result().heldFor().isEmpty(), "no answer is not a no: the run does not hold the report back on it");
        assertNull(ReportStanding.held(run.store(), run.report()));
        LibrarianIndex index = new LibrarianIndex(run.store(), Embeddings.none());
        // the review's own check gets no answer either: nothing is read out, the report stays a draft, and the next review asks again
        Reviewer silent = new Reviewer(); silent.claims = "[" + WORKERS_CLAIM + "]"; silent.topic = TopicCheck.parse("");
        var left = new LibrarianReview(run.store(), index, silent, "librarian:test").review(run.report());
        assertTrue(silent.read.isEmpty() && run.report().findings().isEmpty(), "no claim without a verdict");
        assertEquals(Finding.State.draft, run.report().state());
        assertTrue(left.problems().get(0).contains("could not be checked against its question (the judge gave no answer)") && left.problems().get(0).contains("the next review asks again"), left.problems().toString());
        assertFalse(ReportStanding.reviewedWhileHeld(run.store(), run.report()));
        // asked again, the judge answers: the question, the workers' sub-questions and the answer's first section are what it is shown
        Reviewer answers = new Reviewer(); answers.claims = "[" + WORKERS_CLAIM + "]"; answers.topic = TopicCheck.parse(YES);
        new LibrarianReview(run.store(), index, answers, "librarian:test").review(run.report());
        assertEquals(1, answers.compared.size());
        assertTrue(answers.compared.get(0).startsWith(ASK + "\n[" + SUBS.get(0) + ", " + SUBS.get(1) + "]\n## Answer\n\nOne project builds such a layer."), answers.compared.get(0));
        assertFalse(answers.compared.get(0).contains("## Sources") || answers.compared.get(0).contains("## References"), "the first section only: " + answers.compared.get(0));
        assertEquals(Finding.State.accepted, run.report().state());
    }

    @Test
    void theJobAndTheUpdatesPageSayThatTheReportWaitsAndTheKeeperAcceptsItThere(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        Patrons.setDefault(store, Patrons.Level.write);
        // a model server that answers the service's look for a model; the run itself is scripted, and the review after it gets no JSON from this one
        HttpServer probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext("/", ex -> {
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        probe.start();
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + probe.getAddress().getPort(), "scripted", -1);
        daemon.researcherFactory = d -> { CaseDrive drive = new CaseDrive(); drive.writeUps = List.of("off", "off"); return new Researcher(drive, drive, new Pages(store), s -> { }, 2, store); };
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + daemon.port();
            ObjectNode ask = J.createObjectNode().put("question", ASK).put("sources", "web");
            ask.putArray("sub_questions").add(SUBS.get(0)).add(SUBS.get(1));
            HttpResponse<String> filed = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/research")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ask.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, filed.statusCode(), filed.body());
            String jobId = J.readTree(filed.body()).get("job_id").asText();
            JsonNode job = null;
            for (long until = System.currentTimeMillis() + 60_000; System.currentTimeMillis() < until; Thread.sleep(100)) {
                job = J.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/v1/jobs/" + jobId)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
                if ("done".equals(job.path("state").asText()) || "failed".equals(job.path("state").asText())) break;
            }
            // the job: done and no error, and its result and its checks say what happened in words
            assertEquals("done", job.path("state").asText(), job.toString());
            assertFalse(job.path("is_error").asBoolean(), "a report that waits is not a failed run");
            String id = job.path("investigation").asText();
            assertTrue(id.startsWith("I-"), job.toString());
            String result = job.path("result").asText();
            assertTrue(result.startsWith("investigation " + id + "\n\n") && result.contains("the write-up did not answer the question and was set aside"), result);
            assertTrue(result.contains("This report is not accepted. It waits for you to look at it. The write-up did not answer the question, so it was set aside and this report is the workers' findings as they stand.")
                    && result.contains("To accept it as it stands: researchzosho accept " + id), result);
            assertEquals("no", job.path("checks").path("topic").asText(), job.toString());
            assertTrue(job.path("checks").path("held").asBoolean() && job.path("checks").path("set_aside").asBoolean());
            assertEquals(Finding.State.draft, store.investigation(id).state(), "the review that follows the run leaves it a draft");
            assertTrue(Files.readString(store.root().resolve("catalog").resolve("crews.log")).contains("a draft that waits for the keeper"));
            // Updates: the run's row says the report waits and why, with the button that accepts it
            String updates = http.send(HttpRequest.newBuilder(URI.create(base + "/updates")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(updates.contains("<span class=\"kind\"> · a run landed; its report waits for you</span>"), updates);
            assertTrue(updates.contains("Report filed. It is not accepted and waits for you to look at it: The write-up did not answer the question, so it was set aside"), updates);
            assertTrue(updates.contains("<input type=\"hidden\" name=\"do\" value=\"accept-report\"><input type=\"hidden\" name=\"report\" value=\"" + id + "\">") && updates.contains("<button class=\"btn\">Accept the report</button>"), updates);
            assertTrue(http.send(HttpRequest.newBuilder(URI.create(base + "/past")).build(), HttpResponse.BodyHandlers.ofString()).body().contains("It is not accepted and waits for you to look at it."), "the report's line on Past says it too");
            String once = updates.replaceAll("(?s).*name=\"once\" value=\"([0-9a-f]+)\".*", "$1");
            HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(URI.create(base + "/inbox")).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("do=accept-report&report=" + id + "&once=" + once)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, accepted.statusCode(), accepted.body());
            assertEquals(Finding.State.accepted, store.investigation(id).state());
            String after = http.send(HttpRequest.newBuilder(URI.create(base + "/updates")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(after.contains("<span class=\"kind\"> · a run landed</span>") && !after.contains("Accept the report"), after);
        } finally {
            daemon.stop();
            probe.stop(0);
        }
    }

    // ---- the review of a report nobody checked ----

    static final String OLD_BODY_OFF = "## Answer\n\n" + OFF_ANSWER + "\n\n## Sources\n\n" + CONTAGION;

    @Test
    void theReviewReadsNoClaimOutOfAReportWhoseAnswerIsOnAnotherSubject(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        LibrarianIndex index = new LibrarianIndex(store, Embeddings.none());
        // a report from before the check existed: its answer is about another subject, and its run recorded nothing
        Investigation old = Acquisitions.admit(store, index, ASK, OLD_BODY_OFF, "patron:did:key:test", "SUB-QUESTION: " + SUBS.get(0) + "\nSUMMARY: one project\nEVIDENCE:\n- a note — source: " + MAIL);
        Reviewer reviewer = new Reviewer(); reviewer.claims = "[" + CONTAGION_CLAIM + "]"; reviewer.topic = TopicCheck.parse(NO);
        LibrarianReview review = new LibrarianReview(store, index, reviewer, "librarian:test");
        var out = review.review(old);
        assertEquals(1, reviewer.compared.size());
        assertTrue(reviewer.compared.get(0).startsWith(ASK + "\n[" + SUBS.get(0) + "]\n## Answer\n\n" + OFF_ANSWER), reviewer.compared.get(0));
        assertTrue(reviewer.read.isEmpty(), "the claims are never asked for");
        assertTrue(store.scanFindings().findings().isEmpty(), "and none is filed");
        Investigation after = store.investigation(old.id());
        assertEquals(Finding.State.draft, after.state());
        assertTrue(out.problems().get(0).contains("the report is not accepted and waits for your eye: The report's answer is not about its question, so no claim was read out of it."), out.problems().toString());
        // marked for the keeper: in the list of reports that wait, by the review, with the judge's line
        ReportStanding.Waiting waits = ReportStanding.waiting(store).get(0);
        assertEquals(old.id(), waits.report().id()); assertEquals("review", waits.hold().by()); assertEquals("no", waits.hold().topic());
        assertTrue(waits.hold().why().contains("The check said: \"The answer is about how emotions spread between people"), waits.hold().why());
        // neither the nightly review nor a second call reads it again
        assertTrue(Crews.reviewDrafts(store, review).startsWith("0 draft investigation(s) reviewed"));
        review.review(after);
        assertEquals(1, reviewer.compared.size(), "asked once");
        assertTrue(reviewer.read.isEmpty());
        // the keeper's word stands: accepted as it stands, and a later review does not hold it back again
        ReportStanding.accept(store, old.id(), ReportStanding.PERSON);
        assertEquals(Finding.State.accepted, store.investigation(old.id()).state());
        review.review(store.investigation(old.id()));
        assertEquals(1, reviewer.compared.size(), "the keeper accepted it: the check is not made again");
        assertEquals(Finding.State.accepted, store.investigation(old.id()).state());
    }

    @Test
    void theReviewOfAnUncheckedReportFollowsTheJudge_partlyWaits_yesIsAccepted_aSeatWithNoCheckReadsAsBefore(@TempDir Path tmp) throws Exception {
        String onBody = "## Answer\n\n" + ON_ANSWER + "\n\n## Sources\n\n" + MAIL;
        // partly: the claims are read out, the report waits
        LibraryStore a = library(tmp.resolve("a"));
        LibrarianIndex ia = new LibrarianIndex(a, Embeddings.none());
        Investigation partly = Acquisitions.admit(a, ia, ASK, onBody, "patron:did:key:test");
        Reviewer r1 = new Reviewer(); r1.claims = "[" + WORKERS_CLAIM + "]"; r1.topic = TopicCheck.parse(PARTLY);
        new LibrarianReview(a, ia, r1, "librarian:test").review(partly);
        assertEquals(1, a.investigation(partly.id()).findings().size());
        assertEquals(Finding.State.draft, a.investigation(partly.id()).state());
        assertTrue(ReportStanding.waiting(a).get(0).hold().why().startsWith("The report's answer starts on its question and turns to another subject."));
        // yes: accepted, and the verdict is on record, so it is not asked for again
        LibraryStore b = library(tmp.resolve("b"));
        LibrarianIndex ib = new LibrarianIndex(b, Embeddings.none());
        Investigation yes = Acquisitions.admit(b, ib, ASK, onBody, "patron:did:key:test");
        Reviewer r2 = new Reviewer(); r2.claims = "[" + WORKERS_CLAIM + "]"; r2.topic = TopicCheck.parse(YES);
        new LibrarianReview(b, ib, r2, "librarian:test").review(yes);
        assertEquals(Finding.State.accepted, b.investigation(yes.id()).state());
        assertEquals("yes", ReportStanding.topic(b, b.investigation(yes.id())));
        // a seat with no topic check (a stand-in that only extracts and compares): the review reads the report as it always did
        LibraryStore c = library(tmp.resolve("c"));
        LibrarianIndex ic = new LibrarianIndex(c, Embeddings.none());
        Investigation plain = Acquisitions.admit(c, ic, ASK, onBody, "patron:did:key:test");
        LibrarianReview.Judge twoCalls = new LibrarianReview.Judge() {
            @Override public String extract(String body) { return "[" + WORKERS_CLAIM + "]"; }
            @Override public String compare(String candidate, String neighbors) { return "{\"verdict\": \"independent\"}"; }
        };
        new LibrarianReview(c, ic, twoCalls, "librarian:test").review(plain);
        assertEquals(Finding.State.accepted, c.investigation(plain.id()).state());
        assertFalse(Files.exists(ReportStanding.file(c)), "nothing was checked, so nothing is on record");
        // a report with no answer of its own (the synthesis produced none) has nothing to compare, and is read as before
        LibraryStore d = library(tmp.resolve("d"));
        LibrarianIndex id = new LibrarianIndex(d, Embeddings.none());
        Investigation thin = Acquisitions.admit(d, id, ASK, "", "patron:did:key:test", "SUB-QUESTION: " + SUBS.get(0) + "\nSUMMARY: one project builds it, as its page says.\nEVIDENCE:\n- mcp_agent_mail routes messages — source: " + MAIL);
        Reviewer r4 = new Reviewer(); r4.claims = "[" + WORKERS_CLAIM + "]"; r4.topic = TopicCheck.parse(NO);
        new LibrarianReview(d, id, r4, "librarian:test").review(thin);
        assertTrue(r4.compared.isEmpty());
        assertEquals(Finding.State.accepted, d.investigation(thin.id()).state());
    }

    // ---- the pages a write-up fetches ----

    @Test
    void aPageTheWriteUpFetchesCountsAsReadThisRun(@TempDir Path tmp) throws Exception {
        String extra = "https://example.org/agent-bus", never = "https://example.org/never-opened";
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        drive.onTopicWriteUp = turn -> switch (turn) {
            case 1 -> call("web_fetch", J.createObjectNode().put("url", extra));
            case 2 -> section("Answer", "Two projects build such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + "). "
                    + "A second project carries handoffs between agents over a bus that the person reads first (" + extra + "). A third is said to exist and was not opened (" + never + ").");
            case 3 -> section("Sources", MAIL + "\n" + extra);
            default -> call("done", J.createObjectNode().put("summary", "two projects"));
        };
        LibraryStore store = library(tmp);
        Pages pages = new Pages(store);
        pages.text.put(extra, "The agent bus carries handoffs between agents over a bus that the person reads first, before any agent acts on them.");
        Researcher researcher = new Researcher(drive, drive, pages, s -> { }, 2, store);
        Researcher.Result r = researcher.run(ask(), "");
        assertTrue(pages.fetched.contains(extra));
        assertTrue(drive.toolAnswers.stream().anyMatch(t -> t.startsWith("source: " + extra) && t.contains("This page now counts as read in this run. It is not on the SOURCES list")), "the writer is told how to cite it: " + drive.toolAnswers);
        // the sentence that rests on the fetched page is read against it and stands unmarked; the page is a reference of the report
        assertTrue(r.answer().contains("over a bus that the person reads first (" + extra + "). A third"), r.answer());
        assertFalse(r.answer().contains("reads first (" + extra + ") [cites a source that was not read this run]") || r.answer().contains("reads first (" + extra + "). [cites"), r.answer());
        assertTrue(r.answer().contains("## References") && r.answer().matches("(?s).*\\[\\d\\] A page — " + extra.replace(".", "\\.") + ".*"), r.answer());
        assertEquals(1, r.stats().path("writer_fetches").asInt());
        // the instrument can tell: a page nobody opened is still marked as not read
        assertTrue(r.answer().contains("(" + never + "). [cites a source that was not read this run]") || r.answer().contains("(" + never + ") [cites a source that was not read this run]"), r.answer());
        assertEquals(1, r.stats().path("cite_unretrieved").asInt(), "one citation of an unread page, the one nobody opened");
        assertTrue(r.heldFor().isEmpty(), "one unread source of three does not hold the report: " + r.heldFor());
    }

    @Test
    void theWriteUpFetchesWithinAnAllowanceAndAPageAReaderReadIsNotCounted(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        // the writer asks for the page a reader read, then for eight pages nobody read, then writes
        drive.onTopicWriteUp = turn -> turn == 1 ? call("web_fetch", J.createObjectNode().put("url", MAIL))
                : turn <= 9 ? call("web_fetch", J.createObjectNode().put("url", "https://example.org/more-" + (turn - 1)))
                : turn == 10 ? section("Answer", ON_ANSWER) : call("done", J.createObjectNode().put("summary", "one project only"));
        LibraryStore store = library(tmp.resolve("six"));
        Pages pages = new Pages(store);
        List<String> log = new CopyOnWriteArrayList<>();
        Researcher.Result r = new Researcher(drive, drive, pages, log::add, 2, store).run(ask(), "");
        List<String> more = pages.fetched.stream().filter(u -> u.contains("/more-")).toList();
        assertEquals(6, more.size(), "the allowance is six pages: " + pages.fetched);
        assertEquals(6, r.stats().path("writer_fetches").asInt());
        assertEquals(2, r.stats().path("writer_fetches_refused").asInt());
        assertTrue(drive.toolAnswers.stream().anyMatch(t -> t.startsWith("The write-up's allowance of 6 page fetches is used. The evidence in hand is what the report is written from")), drive.toolAnswers.toString());
        assertTrue(drive.toolAnswers.stream().anyMatch(t -> t.startsWith("source: " + MAIL) && t.contains("read earlier this run by another reader")), "the reader's page came from the run's own copy, outside the allowance: " + drive.toolAnswers);
        assertTrue(log.stream().anyMatch(l -> l.contains("write-up: 6 page(s) fetched to verify, of an allowance of 6; 2 more asked for after it was used, and not fetched")), log.toString());
        assertTrue(r.answer().contains("## Answer\n\nOne project builds such a layer."), "the report is written from the evidence in hand: " + r.answer());
        // the allowance is a setting, read when the write-up starts; set in a home of this test's own
        String realHome = System.getProperty("user.home");
        try {
            Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
            Config.set("RESEARCHZOSHO_WRITEUP_FETCHES", "2");
            assertEquals(2, Researcher.writerFetches());
            CaseDrive again = new CaseDrive(); again.writeUps = List.of("on"); again.onTopicWriteUp = drive.onTopicWriteUp;
            LibraryStore two = library(tmp.resolve("two"));
            Pages pages2 = new Pages(two);
            Researcher.Result r2 = new Researcher(again, again, pages2, s -> { }, 2, two).run(ask(), "");
            assertEquals(2, pages2.fetched.stream().filter(u -> u.contains("/more-")).count());
            assertEquals(6, r2.stats().path("writer_fetches_refused").asInt());
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
        assertEquals(6, Researcher.writerFetches(), "six unless set");
        assertNotNull(Settings.named("writeup_fetches"), "the settings list knows it");
    }

    // ---- the state follows the checks ----

    @Test
    void aReportWhoseChecksFlaggedMoreThanTheyCouldSendForRevisionWaitsForTheKeeper(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        // fourteen sentences, each with a size no note or page states: the checks flag them all, and a revision turn takes twelve
        StringBuilder sizes = new StringBuilder("One project builds such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + ").");
        for (int i = 21; i <= 34; i++) sizes.append("\nIts store number ").append(i - 20).append(" holds ").append(i).append(" GB of messages.");
        sizes.append("\nThe project passes notes among programming assistants by way of one common inbox (" + MAIL + ").");   // a cited sentence in other words than the page's: the judge reads it
        drive.onTopicWriteUp = turn -> turn == 1 ? section("Answer", sizes.toString()) : call("done", J.createObjectNode().put("summary", "sizes unverified"));
        Run run = run(tmp, drive, ask());
        String body = run.report().body();
        assertTrue(body.contains("The checks flagged 14 sentence(s)") && body.contains("kept as marked (more than 12 were flagged; not sent for revision)"), body);
        assertTrue(body.contains("- state: not accepted, it waits for your eye. The checks flagged 14 sentence(s), and 2 of them were never sent for revision and stand as marked."), body);
        assertTrue(body.indexOf("- state: not accepted") < body.indexOf("- number "), "the state leads the Checks section: " + body);
        assertEquals("yes", run.filed().result().topic());
        assertEquals(List.of("The checks flagged 14 sentence(s), and 2 of them were never sent for revision and stand as marked."), run.filed().result().heldFor());
        assertEquals(2, run.filed().result().stats().path("revision_unsent").asInt());
        Reviewer reviewer = new Reviewer(); reviewer.claims = "[" + WORKERS_CLAIM + "]";
        new LibrarianReview(run.store(), new LibrarianIndex(run.store(), Embeddings.none()), reviewer, "librarian:test").review(run.report());
        assertEquals(Finding.State.draft, run.report().state(), "the front matter does not say accepted over a report its checks could not finish");
        assertTrue(run.report().format().contains("\nstate: draft\n"));
        assertTrue(run.seats().containsAll(List.of(RunTrace.CITE_CHECK, RunTrace.REVISE)), run.seats().toString());
        // twelve flagged sentences all go to the writer: nothing is unsent, and nothing holds the report on that count
        CaseDrive twelve = new CaseDrive(); twelve.writeUps = List.of("on");
        StringBuilder fewer = new StringBuilder("One project builds such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + ").");
        for (int i = 21; i <= 32; i++) fewer.append("\nIts store number ").append(i - 20).append(" holds ").append(i).append(" GB of messages.");
        twelve.onTopicWriteUp = turn -> turn == 1 ? section("Answer", fewer.toString()) : call("done", J.createObjectNode().put("summary", "sizes unverified"));
        Run within = run(tmp.resolve("twelve"), twelve, ask());
        assertTrue(within.report().body().contains("The checks flagged 12 sentence(s)") && within.filed().result().heldFor().isEmpty(), within.report().body());
    }

    @Test
    void pagesTheWriteUpNamesButNobodyReadAreListedApartAndDoNotHoldTheReport(@TempDir Path tmp) throws Exception {
        CaseDrive drive = new CaseDrive();
        drive.writeUps = List.of("on");
        drive.onTopicWriteUp = turn -> turn == 1 ? section("Answer", "Several projects build such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + "). "
                + "A relay for agents is described on its own site (https://example.org/unread-one). A broker for handoffs has a page as well (https://example.org/unread-two). "
                + "A ledger of what agents promised is kept by a third (https://example.org/unread-three).")
                : call("done", J.createObjectNode().put("summary", "three of the four were not opened"));
        Run run = run(tmp, drive, ask());
        String body = run.report().body();
        // the three pages the writer named by itself: numbered, so the sentences that cite them are marked, but apart from the references
        assertTrue(body.contains(Researcher.NAMED_NOT_READ + "\n\n" + Researcher.NAMED_NOT_READ_SENTENCE + "\n\n[2] https://example.org/unread-one\n[3] https://example.org/unread-two\n[4] https://example.org/unread-three\n"), body);
        assertTrue(body.contains("\n1 source(s), "), "the references count the sources that were read: " + body);
        assertTrue(body.contains("(https://example.org/unread-one). [cites a source that was not read this run]"), body);
        assertTrue(body.contains("- sources: 3 pages the write-up cites were read by no worker this run, and the library holds no copy; they are listed apart from the references and are not a source of this report."), body);
        assertFalse(body.contains("- state: not accepted"), "a page the writer named is not a reason to wait: " + body);
        assertTrue(run.filed().result().heldFor().isEmpty(), run.filed().result().heldFor().toString());
        assertNull(ReportStanding.held(run.store(), run.report()));
        // a page the library holds from an earlier run is a source: numbered among the references, read by the check from the library's copy, unmarked
        RawCapture.capture(run.store(), "https://example.org/held-before", "A broker for handoffs keeps a ledger of what each agent promised, and the person approves each handoff.", "A broker", "test", "");
        CaseDrive again = new CaseDrive(); again.writeUps = List.of("on");
        again.onTopicWriteUp = turn -> turn == 1 ? section("Answer", "One project builds such a layer. mcp_agent_mail routes messages between coding agents through a shared mailbox (" + MAIL + "). "
                + "A broker for handoffs keeps a ledger of what each agent promised (https://example.org/held-before).")
                : call("done", J.createObjectNode().put("summary", "one from the shelves"));
        Run second = run(tmp, again, ask());   // the same library: it holds the copy
        String held = second.report().body();
        assertTrue(held.contains("[2] A broker — https://example.org/held-before  (the library's copy, read in an earlier run)"), held);
        assertFalse(held.contains(Researcher.NAMED_NOT_READ), held);
        assertFalse(held.contains("[cites a source that was not read this run"), held);
        assertTrue(second.filed().result().heldFor().isEmpty());
    }

    // ---- the pieces ----

    @Test
    void theVerdictIsReadFromTheJudgesReplyAndNoVerdictIsNotAnAnswer() {
        assertTrue(TopicCheck.parse(YES).yes() && TopicCheck.parse(NO).no() && TopicCheck.parse(PARTLY).partly());
        assertEquals("The answer names a routing project and says nothing of the shared record the question asks about.", TopicCheck.parse(PARTLY).line());
        assertTrue(TopicCheck.parse("Here it is: {\"verdict\": \"No\", \"why\": \"another subject\"} — done").no(), "a verdict inside words, in any case");
        assertTrue(TopicCheck.parse("no").no() && TopicCheck.parse("Yes.").yes(), "one word");
        for (String none : List.of("", "   ", "{\"verdict\": \"supported\"}", "{\"sufficient\": true}", "I would say it is not about the question, no doubt.", "{\"verdict\": "))
            assertFalse(TopicCheck.parse(none).checked(), "no verdict in: " + none);
        assertEquals("the judge gave no answer", TopicCheck.parse("").line());
        assertTrue(TopicCheck.parse("{\"sufficient\": true}").line().startsWith("the judge's reply held no verdict; it began: {\"sufficient\""), "a reply that belongs to another call is not a verdict");
        // a judge that cannot be asked is unchecked with the reason, never a yes
        TopicCheck.Verdict failed = TopicCheck.ask((m, n) -> { throw new IOException("connection refused"); }, ASK, SUBS, ON_ANSWER);
        assertFalse(failed.checked());
        assertTrue(failed.line().contains("connection refused"), failed.line());
        assertFalse(TopicCheck.ask((m, n) -> YES, ASK, SUBS, "  ").checked(), "nothing to compare");
        // the prompt: the question, the sub-questions, the answer inside a fence, the three words defined, and a long answer cut
        String prompt = TopicCheck.prompt(ASK, SUBS, "## Answer\n\n" + "x".repeat(5_000));
        assertTrue(prompt.contains("THE QUESTION:\n" + ASK) && prompt.contains("ITS SUB-QUESTIONS:\n- " + SUBS.get(0)) && prompt.contains(Fence.open("ANSWER")) && prompt.contains(Fence.close("ANSWER")), prompt);
        assertTrue(prompt.contains("{\"verdict\": \"yes|partly|no\"") && prompt.contains("no: the opening is about another subject than the question's."), prompt);
        // the judge reads the opening alone and is told so: asked whether the report covers everything, it held back good reports whose lists sit in later sections
        assertTrue(prompt.contains("You are shown the opening section only.") && prompt.contains("Judge one thing: what the opening is about."), prompt);
        assertFalse(prompt.contains("unaddressed"), "completeness is not the opening's to answer: " + prompt);
        assertTrue(prompt.length() < 5_000 + ASK.length(), "the answer is cut to what the judge needs");
    }

    @Test
    void theAnswersFirstSectionIsFoundInAReportAsItStandsOnTheShelves() {
        String filed = "## Question\n\n" + ASK + "\n\nResearched by a model.\n\n## Answer (as submitted by the run — draft until reviewed)\n\n## Answer\n\n" + ON_ANSWER + "\n\n## Sources\n\n" + MAIL
                + "\n\n## Worker findings (fan sub-investigations, verbatim)\n\nSUB-QUESTION: [goal:law] " + SUBS.get(0) + "\nSUMMARY: s\nEVIDENCE:\n- n — source: " + MAIL + "\n\nSUB-QUESTION: " + SUBS.get(1) + "\nSUMMARY: t\n";
        assertEquals(ASK, TopicCheck.questionIn(filed), "the question, without the lines the library adds under it");
        assertEquals("## Answer\n\n" + ON_ANSWER, TopicCheck.answerIn(filed));
        assertEquals(SUBS, TopicCheck.subQuestionsIn(filed), "the workers' sub-questions, without a goal's tag");
        // a synthesis cut off at its deadline: the library's own line in brackets is passed over
        assertEquals("## Answer\n\n" + ON_ANSWER, TopicCheck.answerIn("## Question\n\nq\n\n## Answer (as submitted by the run — draft until reviewed)\n\n(the synthesis was cut off at its deadline — the sections it wrote, then the evidence)\n\n## Answer\n\n" + ON_ANSWER + "\n\n## Sources\n\nx\n"));
        // prose with no heading of its own, and a body with no frame
        assertEquals("The gears were cut by hand.", TopicCheck.answerIn("## Question\n\nq\n\n## Answer (as submitted by the run — draft until reviewed)\n\nThe gears were cut by hand.\n\n## Sources cited\n\n- x\n"));
        assertEquals("By meantone, in the 1700s.", TopicCheck.answerIn("## Question\n\nHow?\n\n## Answer\n\nBy meantone, in the 1700s.\n\n## Worker findings (fan sub-investigations, verbatim)\n\nnotes\n"));
        // nothing to compare: no answer at all, or the sentence that says the synthesis produced none
        assertEquals("", TopicCheck.answerIn("## Question\n\nq\n"));
        assertEquals("", TopicCheck.answerIn("## Question\n\nq\n\n## Answer (as submitted by the run — draft until reviewed)\n\n(the synthesis step did not produce an answer — the worker findings below are the record; a later run or the person can synthesize them)\n\n## Worker findings (fan sub-investigations, verbatim)\n\nnotes\n"));
        assertEquals("", TopicCheck.questionIn("## Answer\n\nx\n"));
        assertEquals("## Answer\n\nfirst", TopicCheck.firstSection(List.of("## Background\n\nb", "## Answer\n\nfirst")), "the section headed Answer, wherever it stands");
        assertEquals("prose only", TopicCheck.firstSection(List.of("prose only", "## Sources\n\nx")));
    }

    @Test
    void theTraceNamesEachStageAndKeepsTheReasoningOfItsFirstCall(@TempDir Path tmp) throws Exception {
        LibraryStore store = library(tmp);
        RunTrace trace = RunTrace.open(store, "J-0001");
        String long9k = "r".repeat(9_000);
        Researcher.Drive model = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode m = call("web_fetch", J.createObjectNode().put("url", "https://example.org/a"));
                m.put("content", "I will fetch it."); m.put("reasoning_content", long9k);
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "yes"; }
            @Override public int contextWindow() { return 16_384; }
        };
        Researcher.Drive workers = trace.wrap(model, "workers"), judge = trace.wrap(model, "judge");
        ArrayNode msgs = J.createArrayNode(); msgs.addObject().put("role", "user").put("content", "find the gears");
        ArrayNode tools = J.createArrayNode();
        workers.chat(msgs, tools, 100, "required");                                   // outside any stage: the drive's own name, no reasoning kept
        try (var s = RunTrace.stage(RunTrace.WRITE_UP)) {
            workers.chat(msgs, tools, 100, "required");                               // the stage's first
            workers.chat(msgs, tools, 100, "required");                               // its second
            try (var inner = RunTrace.stage(RunTrace.CITE_CHECK)) { judge.classify(msgs, 20); }   // a stage inside a stage
            judge.classify(msgs, 20);                                                 // back in the write-up, on the judge's drive
        }
        try (var s = RunTrace.stage(RunTrace.WRITE_UP)) { workers.chat(msgs, tools, 100, "required"); }   // the stage opened again: a first call of its own
        judge.classify(msgs, 20);
        List<JsonNode> lines = new ArrayList<>();
        for (String l : Files.readAllLines(RunTrace.fileFor(store, "J-0001"), StandardCharsets.UTF_8)) lines.add(J.readTree(l));
        assertEquals(List.of("workers", "write-up", "write-up", "cite-check", "write-up", "write-up", "judge"), lines.stream().map(l -> l.path("seat").asText()).toList());
        assertEquals(List.of("workers", "workers", "workers", "judge", "judge", "workers", "judge"), lines.stream().map(l -> l.path("drive").asText()).toList());
        assertFalse(lines.get(0).has("first_of_stage") || lines.get(0).path("reply").has("reasoning"), "no stage, no reasoning kept: " + lines.get(0).path("reply"));
        assertEquals(9_000, lines.get(0).path("reply").path("reasoning_chars").asInt(), "how much was thought is always said");
        JsonNode first = lines.get(1);
        assertTrue(first.path("first_of_stage").asBoolean());
        assertEquals(RunTrace.REASONING_CHARS, first.path("reply").path("reasoning").asText().length(), "bounded");
        assertEquals("I will fetch it.", first.path("reply").path("content").asText(), "and what it said in words");
        assertEquals(9_000, first.path("reply").path("reasoning_chars").asInt());
        assertFalse(lines.get(2).has("first_of_stage") || lines.get(2).path("reply").has("reasoning") || lines.get(2).path("reply").has("content"), "the second call keeps the counts only");
        assertTrue(lines.get(5).path("first_of_stage").asBoolean() && lines.get(5).path("reply").has("reasoning"), "a stage opened again starts again");
        // what read the trace before reads it still: the request, the reply's tool calls, the counters, the fetches
        assertEquals("find the gears", first.path("messages").get(0).path("content").asText());
        assertEquals("web_fetch", first.path("reply").path("tool_calls").get(0).path("name").asText());
        assertEquals(16, first.path("reply").path("content_chars").asInt());
        assertEquals("yes", lines.get(3).path("reply").path("head").asText());
        assertEquals(7, trace.totals().path("calls").asInt());
        assertEquals(4, RunLedger.fetchCalls(store, "J-0001"));
        assertEquals("", RunTrace.stageNow(), "the stage ends with its scope");
    }
}
