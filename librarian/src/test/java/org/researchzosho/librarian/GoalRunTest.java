package org.researchzosho.librarian;

import org.researchzosho.librarian.profiles.YoutubeProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A goal named by the asker travels with the run, and every worker of the run gets the goal's rules, not only a pursued sub-question. */
class GoalRunTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String VIDEO = "Find the sewing video I watched a while ago: a canvas tote bag, nobody speaks, the fabric sizes shown on screen at the start.";

    @Test
    void aGoalNamedInTheProtocolIsKeptOnTheJob(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode ask = M.createObjectNode().put("question", VIDEO).put("goal", "choose");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "mcp");
        ObjectNode filed = p.research(ask);
        String id = filed.path("job_id").asText();
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        assertEquals("choose", jobs.get(id).path("args").path("goal").asText(), "the job carries the goal: " + jobs.get(id));
        assertEquals(List.of("choose"), LibraryProtocol.goalsOf(jobs.get(id).path("args")));
        ObjectNode bad = ask.deepCopy(); bad.put("goal", "fortune-telling");
        assertThrows(ProtocolError.class, () -> p.research(bad), "a goal the library does not have is refused");
    }

    @Test
    void theNamedGoalComesFirstAmongTheRunsGoals() {
        Researcher.Ask ask = new Researcher.Ask(VIDEO, "broad", 40, List.of()).withGoals(List.of("Choose"));
        assertEquals(List.of("choose"), ask.goals(), "a goal's name is kept as the registry spells it");
        List<Goal> goals = Fields.goalsForRun(null, ask);
        assertEquals("choose", goals.get(0).name(), goals.toString());
        assertEquals(List.of("choose"), ask.withFields(List.of("youtube")).goals(), "a copy of the ask keeps its goals");
    }

    @Test
    void everyWorkerOfARunWithAGoalGetsTheGoalsRules() {
        RunBrainTest.Drive drive = new RunBrainTest.Drive();
        List<String> logged = new CopyOnWriteArrayList<>();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), logged::add, 1, null).run(new Researcher.Ask(VIDEO, "depth", 40, List.of()).withGoals(List.of("choose")), "");
        Goal choose = Goals.named("choose");
        boolean told = false;
        for (ArrayNode hist : drive.histories) {
            String sys = hist.get(0).path("content").asText();
            if (sys.contains("THIS RUN SERVES THE GOAL choose: " + choose.words()) && sys.contains(choose.decisionRules())) told = true;
        }
        assertTrue(told, "a worker of the run was told the goal and its rules");
        // a run with no goal of its own tells its workers none
        RunBrainTest.Drive plain = new RunBrainTest.Drive();
        new Researcher(plain, plain, new ResearcherTest.FakeTools(), logged::add, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 40, List.of()), "");
        List<String> systems = new ArrayList<>(); for (ArrayNode hist : plain.histories) systems.add(hist.get(0).path("content").asText());
        assertTrue(systems.stream().noneMatch(s -> s.contains("THIS RUN SERVES THE GOAL")), "an ordinary run carries no goal block");
    }

    @Test
    void theLastStepOfAChatTurnIsToldNoMoreLookUpsCanRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode search = M.createObjectNode(); search.put("tool", "search"); search.set("args", M.readTree("{\"where\":\"shelves\",\"query\":\"tote bag\"}"));
        List<List<String>> toolsSeen = new ArrayList<>(); List<ArrayNode> messagesSeen = new ArrayList<>();
        Librarian lib = new Librarian(store, ChatBrainTest.scripted(List.of(search), toolsSeen, messagesSeen), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("find every tote bag video on my shelves");
        assertFalse(reply.isBlank(), "the turn ends in words");
        ArrayNode lastAsk = messagesSeen.get(messagesSeen.size() - 1);
        assertEquals(Librarian.LAST_STEP, lastAsk.get(lastAsk.size() - 1).path("content").asText(), "the last step is told that no look-up can run");
        assertTrue(toolsSeen.get(toolsSeen.size() - 1).isEmpty(), "and is offered none");
        for (int i = 0; i < messagesSeen.size() - 1; i++) {
            ArrayNode m = messagesSeen.get(i);
            assertNotEquals(Librarian.LAST_STEP, m.get(m.size() - 1).path("content").asText(), "only the last step is told so");
        }
    }

    /** The plan of a run asked to find one remembered video: told the goal's rules, and the video field's rule for one thing judged whole. */
    static class PlanSeen extends RunBrainTest.Drive {
        final List<String> plans = new CopyOnWriteArrayList<>();
        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.contains("Decompose")) plans.add(prompt);
            return super.classify(messages, maxTokens);
        }
    }

    @Test
    void thePlanOfARunToFindOneVideoIsToldToKeepTheVideoWhole() {
        PlanSeen drive = new PlanSeen();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask(VIDEO, "depth", 40, List.of()).withGoals(List.of("choose")), "");
        assertFalse(drive.plans.isEmpty(), "the run planned");
        String plan = drive.plans.get(0);
        assertTrue(plan.contains("THIS RUN SERVES THE GOAL choose") && plan.contains("begins with candidates") && plan.contains("never a search"), "the planner is told the goal and that the candidates come from candidates, never a search: " + plan);
        String rules = Profiles.named("youtube").planRules();
        assertTrue(rules.startsWith("When the person is looking for ONE video they remember") && rules.contains("the exception to splitting by facet")
                && rules.contains("Otherwise this is a question about channels"), "the video field's plan rule puts the one-video case first: " + rules);
    }

    @Test void aRememberedVideosCandidatesComeFromTheCandidatesInstrumentNeverASearchAndTheLooksGoDownThePool() {
        String rule = YoutubeProfile.FIND_ONE;
        assertTrue(rule.contains("The candidates come from candidates, never from a search") && rule.contains("only the thing, two to four words, in each language"), rule);
        assertTrue(rule.contains("{\"things\": [\"apron\", \"エプロン\", \"앞치마\"], \"remembered\": [\"nobody speaks\", \"a pocket on the front\"]}"), "a complete literal call for a small model to copy: " + rule);
        assertTrue(rule.contains("the remembered features (the material, a colour, that nobody speaks, a pocket, how it closes) go in remembered as the person said them and are never typed into things"), rule);
        assertTrue(rule.contains("the pages where other people describe a video in the words remembered"), rule);
        assertTrue(rule.indexOf("candidates") < rule.indexOf("Then sift") && rule.indexOf("Then sift") < rule.indexOf("given to one look together") && rule.contains("then the next five, down the survivors while the budget lasts"), "candidates first, then the sift, then the looks down the survivors: " + rule);
        assertFalse(rule.contains("video_details"), "no reading of descriptions stands between the pool and the look");
        String choose = Goals.named("choose").decisionRules();
        assertTrue(choose.contains("begins with candidates") && choose.contains("never a search") && choose.contains("Then sift") && choose.contains("then the next five, down the survivors"), choose);
    }

    /** The tools a worker of the run was offered: the first list with the worker's note on it (the planner's and the writer's lists have none). */
    static List<String> worker(RunBrainTest.Drive drive) {
        for (List<String> o : drive.offered) if (o.contains("note")) return o;
        throw new AssertionError("no worker was offered a table: " + drive.offered);
    }

    @Test void aRunChoosingOrRememberingAVideoHasNoRankedSearchOnItsTable() {
        RunBrainTest.Drive drive = new RunBrainTest.Drive();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask(VIDEO, "depth", 40, List.of()).withGoals(List.of("choose")), "");
        List<String> offered = worker(drive);
        assertFalse(offered.contains("web_search"), "the ranked search is off the table: " + offered);
        assertTrue(offered.containsAll(List.of("candidates", "look", "open", "web_fetch", "note", "done")), "what enumerates, looks and opens stays: " + offered);
        // the same goal on a question about channels keeps the searches, and a plain run keeps them
        RunBrainTest.Drive channels = new RunBrainTest.Drive();
        new Researcher(channels, channels, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("Which YouTube channels teach iaido in a way a beginner can follow, and which still post?", "depth", 40, List.of()).withGoals(List.of("choose")), "");
        assertTrue(worker(channels).contains("web_search"), worker(channels).toString());
        RunBrainTest.Drive plain = new RunBrainTest.Drive();
        new Researcher(plain, plain, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 40, List.of()), "");
        assertTrue(worker(plain).contains("web_search"), worker(plain).toString());
        assertTrue(YoutubeProfile.findOne("Find a specific YouTube sewing tutorial: a canvas tote bag, no talking, the dimensions on screen at the start") && YoutubeProfile.findOne("見た動画をもう一度見たい：トートバッグの作り方") && !YoutubeProfile.findOne("which channels make videos about iaido") && !YoutubeProfile.findOne("a podcast about the Tokyo subway"));
        assertTrue(YoutubeProfile.FIND_ONE.contains("A run of this kind has no ranked search on its table: candidates is the search, and the sift is the funnel."));
    }

    @Test void theWriterReadsTheShapeOfTheReportTheRunsGoalAsksFor() {
        RunBrainTest.Drive drive = new RunBrainTest.Drive();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, null)
                .run(new Researcher.Ask("Find the sewing video I saw: nobody speaks in it and the sizes come up at the start.", "depth", 80, List.of()).withGoals(List.of("choose")), "");
        String writer = drive.histories.stream().map(h -> h.get(0).path("content").asText()).filter(t -> t.contains("Then call done.")).findFirst().orElse("");
        assertTrue(writer.contains("THE REPORT SERVES THE GOAL choose: The candidates that fit"), "the goal's shape reaches the writer: " + writer.length());
        assertTrue(writer.contains("says it matches every one only when every one was found"), "and the answer is bound by the checks");
        assertTrue(writer.contains("When more than one candidate has every remembered thing found, the answer names each of them as matching"), "and a tie is said as a tie");
        RunBrainTest.Drive plain = new RunBrainTest.Drive();
        new Researcher(plain, plain, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertTrue(plain.histories.stream().noneMatch(h -> h.get(0).path("content").asText().contains("THE REPORT SERVES THE GOAL")), "the generic goal adds no shape");
    }
}

