package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.Judge;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model that sends no signal declines in its own words. The watched seat asks the judge about suspect replies only (prose where a tool
 * was required, the text of done or write_section, a classify reply with no JSON where JSON was asked for, a prose answer that is the
 * work), records a decline only when the judge is sure, and notes an unsure reply in the run's trace. The replies here are neutral
 * placeholders: the words of a decline, never the content of one.
 */
class DeclinesTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String DECLINE = "I am not able to help with that request.";
    @TempDir Path tmp;

    /** A server's answer to the typed judge: yes with probability {@code pYes}. */
    static JsonNode judged(double pYes) {
        ObjectNode r = M.createObjectNode();
        ObjectNode c = r.putArray("choices").addObject();
        String top = pYes >= 0.5 ? "yes" : "no";
        c.putObject("message").put("role", "assistant").put("content", top);
        var tops = c.putObject("logprobs").putArray("content").addObject().put("token", top).putArray("top_logprobs");
        tops.addObject().put("token", "yes").put("logprob", Math.log(Math.max(1e-9, pYes)));
        tops.addObject().put("token", "no").put("logprob", Math.log(Math.max(1e-9, 1 - pYes)));
        return r;
    }

    /** A typed judge that answers yes with {@code pYes}, counting its calls. */
    static DeclineJudge judge(double pYes, AtomicInteger asked) {
        return new DeclineJudge(new Judge("m", body -> { asked.incrementAndGet(); return judged(pYes); }), null);
    }

    /** A drive that answers every chat with {@code reply} and every classify with {@code words}. */
    static Researcher.Drive drive(ObjectNode reply, String words) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return reply.deepCopy(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return words; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    static ObjectNode prose(String text) { ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", text); return m; }

    static ObjectNode call(String name, ObjectNode args) {
        ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.putNull("content");
        ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "c1"); c.put("type", "function");
        c.putObject("function").put("name", name).put("arguments", args.toString());
        return m;
    }

    static ArrayNode task(String text) { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "system").put("content", "You are a researcher."); m.addObject().put("role", "user").put("content", text); return m; }

    static ArrayNode tools(String... names) {
        ArrayNode a = M.createArrayNode();
        for (String n : names) a.addObject().put("type", "function").putObject("function").put("name", n);
        return a;
    }

    @Test
    void proseWhereAToolWasRequiredIsReadAndASureDeclineIsRaised() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(DECLINE), ""), "workers", "placeholder-model", judge(0.95, asked), null);
        Declined x = assertThrows(Declined.class, () -> d.chat(task("Research the placeholder topic."), tools("web_search", "done"), 256, "required"));
        assertEquals(Declined.How.WORDS, x.how());
        assertEquals(DECLINE, x.said());
        assertEquals("workers", x.seat());
        assertEquals("placeholder-model", x.model());
        assertEquals(1, asked.get());
    }

    @Test
    void theTextOfDoneAndOfWriteSectionIsRead() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive done = Declines.watch(drive(call("done", M.createObjectNode().put("summary", DECLINE)), ""), "workers", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> done.chat(task("Research the placeholder topic."), tools("note", "done"), 256, "required"));
        Researcher.Drive section = Declines.watch(drive(call("write_section", M.createObjectNode().put("heading", "Answer").put("text", DECLINE)), ""), "workers", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> section.chat(task("Write the report."), tools("write_section", "done"), 256, "required"));
        assertEquals(2, asked.get());
    }

    @Test
    void aClassifyReplyWithNoJsonWhereJsonWasAskedIsRead() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(""), DECLINE), "judge", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> d.classify(task("Decompose the placeholder topic. Answer with a JSON array of strings and nothing else."), 400));
        Researcher.Drive json = Declines.watch(drive(prose(""), "[\"a\", \"b\"]"), "judge", "m", judge(0.95, asked), null);
        assertEquals("[\"a\", \"b\"]", json.classify(task("Answer with a JSON array of strings."), 400));
        Researcher.Drive words = Declines.watch(drive(prose(""), "A short answer in words."), "judge", "m", judge(0.95, asked), null);
        assertEquals("A short answer in words.", words.classify(task("Answer in a sentence."), 400), "prose asked for is not a suspect reply");
        assertEquals(1, asked.get(), "only the reply with no JSON where JSON was asked for was read");
    }

    @Test
    void aProseAnswerThatIsTheWorkIsRead() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(""), DECLINE), "judge", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> d.prose(task("Answer the ask from the evidence."), 2500));
        assertEquals(1, asked.get());
    }

    @Test
    void ordinaryRepliesAreNeverRead() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive search = Declines.watch(drive(call("web_search", M.createObjectNode().put("query", "placeholder")), ""), "workers", "m", judge(0.99, asked), null);
        assertEquals("web_search", search.chat(task("Research the placeholder topic."), tools("web_search", "done"), 256, "required").path("tool_calls").get(0).path("function").path("name").asText());
        Researcher.Drive chat = Declines.watch(drive(prose("The library holds two claims on the placeholder topic."), ""), "chat", "m", judge(0.99, asked), null);
        assertEquals("The library holds two claims on the placeholder topic.", chat.chat(task("hello"), tools("library_search"), 256, "auto").path("content").asText(),
                "prose is the ordinary reply when no tool is required, and read only when it sounds like a decline");
        assertEquals(0, asked.get());
    }

    @Test
    void anUnsureReplyIsNotADeclineAndIsNotedInTheTrace() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        RunTrace trace = RunTrace.open(store, "J-0001");
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(DECLINE), ""), "workers", "m", judge(0.6, asked), trace);
        ObjectNode reply = d.chat(task("Research the placeholder topic."), tools("web_search", "done"), 256, "required");
        assertEquals(DECLINE, reply.path("content").asText(), "not sure: the reply goes on as it was");
        String lines = Files.readString(RunTrace.fileFor(store, "J-0001"), StandardCharsets.UTF_8);
        assertTrue(lines.contains("\"capture\":\"decline_unsure\""), lines);
        assertTrue(lines.contains("\"seat\":\"workers\""), lines);
    }

    @Test
    void whereTheTypedJudgeCannotRunOneWordIsAskedOfTheSameModel() {
        // at a step whose decline ends nothing: where one would end a run, one word is only noted (see aStepWhoseDeclineEndsWork…)
        AtomicInteger words = new AtomicInteger();
        try (var step = Declines.step("name what two areas of a library share")) { oneWordAtAnOrdinaryStep(words); }
    }

    private void oneWordAtAnOrdinaryStep(AtomicInteger words) {
        // Bedrock: no typed judge at all
        DeclineJudge bedrock = new DeclineJudge(null, m -> { words.incrementAndGet(); return "Yes."; });
        Researcher.Drive d = Declines.watch(drive(prose(DECLINE), ""), "workers", "m", bedrock, null);
        assertThrows(Declined.class, () -> d.chat(task("Research the placeholder topic."), tools("done"), 256, "required"));
        // a server that refuses the grammar: the typed judge leaves no probabilities, and the one word decides
        DeclineJudge refused = new DeclineJudge(new Judge("m", body -> { throw new IllegalStateException("HTTP 400: grammar is not supported"); }), m -> { words.incrementAndGet(); return "no"; });
        Researcher.Drive e = Declines.watch(drive(prose("Still looking."), ""), "workers", "m", refused, null);
        assertEquals("Still looking.", e.chat(task("Research the placeholder topic."), tools("done"), 256, "required").path("content").asText());
        DeclineJudge rambling = new DeclineJudge(null, m -> { words.incrementAndGet(); return "Perhaps, it depends."; });
        Researcher.Drive f = Declines.watch(drive(prose(DECLINE), ""), "workers", "m", rambling, null);
        assertEquals(DECLINE, f.chat(task("Research the placeholder topic."), tools("done"), 256, "required").path("content").asText(), "only a plain yes counts");
        assertEquals(3, words.get());
    }

    @Test
    void aServersDeclineIsPassedOnWithTheSeatNamed() {
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive filtered = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new Declined("m", "", Declined.How.FILTERED); }
            @Override public String classify(ArrayNode messages, int maxTokens) { throw new Declined("m", "", Declined.How.FILTERED); }
            @Override public int contextWindow() { return 16384; }
        };
        Researcher.Drive d = Declines.watch(filtered, "judge", "m", judge(0.95, asked), null);
        assertEquals("judge", assertThrows(Declined.class, () -> d.chat(task("x"), null, 64, "auto")).seat());
        assertEquals("judge", assertThrows(Declined.class, () -> d.classify(task("x"), 64)).seat());
        assertEquals(0, asked.get(), "the server said so: nothing to judge");
    }

    @Test
    void theWatchedSeatAsksItsOwnModelUnderAGrammar() throws Exception {
        // the seat as it is built for a run: one server answers the work with a decline in words, and the typed judge's request with yes
        AtomicInteger judgeRequests = new AtomicInteger(), work = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            JsonNode out;
            if (body.has("grammar")) { judgeRequests.incrementAndGet(); out = judged(0.97); }
            else { work.incrementAndGet(); ObjectNode r = M.createObjectNode(); r.putArray("choices").addObject().put("finish_reason", "stop").set("message", prose(DECLINE)); out = r; }
            byte[] b = M.writeValueAsBytes(out);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            Researcher.Drive d = Researcher.watched("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model", "workers", null);
            Declined x = assertThrows(Declined.class, () -> d.chat(task("Research the placeholder topic."), tools("web_search", "done"), 256, "required"));
            assertEquals("placeholder-model", x.model());
            assertEquals(1, work.get(), "the work was asked once and not again");
            assertEquals(1, judgeRequests.get());
        } finally { s.stop(0); }
    }

    /** A typed judge that says yes when what it reads holds {@code trigger}, no otherwise, keeping every state it was shown. */
    static DeclineJudge fooledBy(String trigger, List<String> shown) {
        return new DeclineJudge(new Judge("m", body -> {
            String state = body.path("messages").path(0).path("content").asText();
            shown.add(state);
            return judged(state.contains(trigger) ? 0.97 : 0.02);
        }), null);
    }

    @Test
    void theJudgeReadsTheStepNotTheTranscriptTheStepWorkedOn() {
        // H1: the claim extractor's instruction is in the system message and its first user message is the transcript. A transcript that
        // quotes a refusal, read as "the task", made the extractor's correct NONE look like the model declining.
        String quoted = "I'm sorry, but I can't help with that request.";
        List<String> shown = new ArrayList<>();
        Researcher.Drive seat = Declines.watch(drive(prose(""), "NONE"), "judge", "placeholder-model", fooledBy(quoted, shown), null);
        List<String> claims = Conversations.modelExtractor(seat).apply("Person: can you look this up?\nAssistant: " + quoted);
        assertEquals(List.of(), claims, "NONE is no claim, and no decline");
        for (String state : shown) {
            assertTrue(state.contains("THE STEP THE MODEL WAS ASKED TO DO:\nlist the checkable factual claims in a text"), state);
            assertFalse(state.contains(quoted), "the data the step worked on is not shown as the step: " + state);
        }
    }

    @Test
    void aWorkersSubQuestionIsTheStepEvenBehindALongAsk() {
        // H1: a worker's first message is the whole ask, then its sub-question; a cut at 1500 characters dropped the sub-question
        List<String> shown = new CopyOnWriteArrayList<>();
        ResearcherTest.ScriptedDrive inner = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                if (!names(tools).contains("write_section") && assistantTurns(history) == 0) { histories.add(history.deepCopy()); return DeclinesTest.prose("Thinking about where to look first."); }
                return super.chat(history, tools, maxTokens, toolChoice);
            }
        };
        inner.criticWantsMore = false;
        Researcher.Drive watched = Declines.watch(inner, "workers", "placeholder-model", fooledBy("never in the state", shown), null);
        String ask = "Placeholder background. ".repeat(90) + "How were the placeholder gears cut?";
        new Researcher(watched, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask(ask, "broad", 60, List.of("who cut the placeholder gears?")), "");
        assertTrue(shown.stream().anyMatch(st -> st.contains("research this sub-question") && st.contains("who cut the placeholder gears?")), "the judge read the worker's sub-question: " + shown);
        assertTrue(shown.stream().filter(st -> st.contains("research this sub-question")).noneMatch(st -> st.contains("Placeholder background.")), "the ask in front of it is not the worker's step");
    }

    /** A typed judge that answers yes with {@code pYes}, keeping every request it was sent. */
    static DeclineJudge judgeKeeping(double pYes, List<String> sent) {
        return new DeclineJudge(new Judge("m", body -> { sent.add(body.path("messages").path(0).path("content").asText()); return judged(pYes); }), null);
    }

    @Test
    void theWritersCaveatsAreNeverAskedAbout() {
        // H2: the writer's done is by instruction its CAVEATS, "I could not establish …": never read for a decline, on any turn
        List<String> sent = new ArrayList<>();
        ObjectNode caveats = call("done", M.createObjectNode().put("summary", "CAVEATS: I could not establish the exact year; the two sources disagree, and I cannot tell from these sources which is right."));
        Researcher.Drive writer = Declines.watch(drive(caveats, ""), "workers", "m", judgeKeeping(0.99, sent), null);
        assertDoesNotThrow(() -> writer.chat(task("Write the report."), tools("write_section", "done"), 256, "required"));
        try (var step = Declines.writeUp("write the sections of a research report from the research notes, answering: the placeholder question")) {
            assertDoesNotThrow(() -> writer.chat(task("Write the report."), tools("done"), 256, "required"), "the deadline turn offers done alone, and it is still the caveats");
        }
        assertEquals(List.of(), sent);
    }

    @Test
    void textThatIsRecognisablyTheWorkIsNeverAskedAbout() {
        // H2: a written section with content, or a section about refusals, is the work; it cites its sources
        List<String> sent = new ArrayList<>();
        for (String text : List.of("The sources could not settle the date, and two pages refused access to automated readers [3].",
                "The court refused the appeal in 2019, and the company declined to comment on the lawsuit [2][5].",
                "Studies find that language models refuse between 2% and 8% of benign requests (https://example.org/over-refusal).")) {
            Researcher.Drive d = Declines.watch(drive(call("write_section", M.createObjectNode().put("heading", "Limitations").put("text", text)), ""), "workers", "m", judgeKeeping(0.99, sent), null);
            try (var step = Declines.writeUp("write the sections of a research report from the research notes, answering: the placeholder question")) {
                assertDoesNotThrow(() -> d.chat(task("Write the report."), tools("write_section", "done"), 256, "required"), text);
            }
        }
        // a worker's done after it noted facts is its account of work done
        ArrayNode noted = task("Research the placeholder topic.");
        noted.add(call("note", M.createObjectNode().put("claim", "c").put("source", "s")));
        Researcher.Drive worker = Declines.watch(drive(call("done", M.createObjectNode().put("summary", "I was unable to find a death record; nothing else could be established.")), ""), "workers", "m", judgeKeeping(0.99, sent), null);
        assertDoesNotThrow(() -> worker.chat(noted, tools("note", "done"), 256, "required"));
        assertEquals(List.of(), sent);
    }

    @Test
    void aStepWhoseDeclineEndsWorkNeedsTheTypedJudgesHighBar() {
        // H2: "could not establish", "cannot tell from these sources", "the page refused automated readers" at a worker's step, with a judge
        // leaning yes but short of 0.8: not a decline, noted as unsure; the judge reads the step beside the reply
        String step = "research this sub-question with the tools, note what the sources show, and finish with a summary: when did the placeholder family emigrate?";
        for (String reply : List.of("I cannot tell from these sources whether he emigrated in 1905 or 1906; the manifest is illegible.",
                "I could not establish the year: the archive's page refused automated readers, so no record could be read.")) {
            List<String> sent = new ArrayList<>();
            Researcher.Drive d = Declines.watch(drive(call("done", M.createObjectNode().put("summary", reply)), ""), "workers", "m", judgeKeeping(0.7, sent), null);
            try (var s = Declines.step(step, true)) {
                assertDoesNotThrow(() -> d.chat(task("Research."), tools("web_search", "done"), 256, "required"), reply);
            }
            assertEquals(1, sent.size());
            assertTrue(sent.get(0).contains("THE STEP THE MODEL WAS ASKED TO DO:\n" + step), sent.get(0));
            assertTrue(sent.get(0).contains("QUESTION: Is this reply the model declining to do the task it was given?"), sent.get(0));
        }
        // the same leaning yes at a step whose decline ends nothing is a decline, as before
        Researcher.Drive critic = Declines.watch(drive(prose(""), DECLINE), "judge", "m", judgeKeeping(0.7, new ArrayList<>()), null);
        try (var s = Declines.step("check whether the research so far covers the question, and name the gaps, as JSON: the placeholder question")) {
            assertThrows(Declined.class, () -> critic.classify(task("Answer with JSON only."), 400));
        }
        // the bar for a decline that ends work is a typed p(yes) of 0.8: the weakest real decline measured on the 27B scored 0.90, the
        // highest ordinary reply 0.40
        Researcher.Drive atTheBar = Declines.watch(drive(prose("I will not research this topic."), ""), "workers", "m", judgeKeeping(0.85, new ArrayList<>()), null);
        try (var s = Declines.step(step, true)) {
            assertThrows(Declined.class, () -> atTheBar.chat(task("Research."), tools("web_search", "done"), 256, "required"));
        }
        // a plain decline the typed judge is sure of still ends the work
        Researcher.Drive worker = Declines.watch(drive(prose("I will not research this topic."), ""), "workers", "m", judgeKeeping(0.97, new ArrayList<>()), null);
        try (var s = Declines.step(step, true)) {
            assertThrows(Declined.class, () -> worker.chat(task("Research."), tools("web_search", "done"), 256, "required"));
        }
        // one word cannot end work: noted, not a decline; at an ordinary step a plain yes counts
        Researcher.Drive oneWord = Declines.watch(drive(prose("I will not research this topic."), ""), "workers", "m", new DeclineJudge(null, m -> "yes"), null);
        try (var s = Declines.step(step, true)) {
            assertDoesNotThrow(() -> oneWord.chat(task("Research."), tools("web_search", "done"), 256, "required"));
        }
        try (var s = Declines.step("name what two areas of a library share")) {
            assertThrows(Declined.class, () -> oneWord.chat(task("Research."), tools("web_search", "done"), 256, "required"));
        }
    }

    @Test
    void theChatsOwnProseIsReadWhenItSoundsLikeADecline() {
        // M9: a local model that declines in the chat was shown in its own words, with no statement; its prose is read now, but only when
        // it carries decline wording, at the chat's ordinary bar
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive chat = Declines.watch(drive(prose("I'm sorry, but I can't help with that."), ""), "chat", "placeholder-model", judge(0.95, asked), null);
        try (var step = Declines.step("answer a person's message in a conversation about their research library, with the library's tools: a placeholder question")) {
            Declined d = assertThrows(Declined.class, () -> chat.chat(task("a placeholder question"), tools("library_search"), 256, "auto"));
            assertEquals("chat", d.seat());
        }
        assertEquals(1, asked.get());
    }

    @Test
    void theJudgeIsAskedOnlyAboutWordsThatSoundLikeADecline() {
        // M9, L2: a prose answer, a picture's writing, an article: ordinary work is not sent to the judge at all
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(""), "The placeholder gears were cut by hand, and the workshop stood by the harbour."), "judge", "m", judge(0.99, asked), null);
        assertEquals("The placeholder gears were cut by hand, and the workshop stood by the harbour.", d.prose(task("Answer the ask from the evidence."), 2500));
        judge(0.99, asked).raise("m", "transcribe the writing in a picture", "Tom Hale, born 1911 in Leeds; a farmer.");
        assertEquals(0, asked.get(), "no question was spent on ordinary work");
        judge(0.02, asked).raise("m", "transcribe the writing in a picture", "Tom Hale, born 1911 in Leeds; a farmer. I am sorry for your loss, dear Mary.");
        assertEquals(1, asked.get(), "decline wording lets the judge be asked, and the judge decides");
        // the measured declines all carry the default wording, in every language they were written in; the default list is behind a general rule
        for (String decline : List.of("I'm sorry, but I can't help with that.", "I can't assist with that request.", "I'm not able to provide that information.",
                "I will not research this topic because it involves dangerous activities.", "As an AI, I must decline to write this section.",
                "申し訳ありませんが、そのご依頼にはお応えできません。", "This request goes against my guidelines, so I won't complete it.", "I must refuse to summarise this page.",
                "I won't be able to help with finding someone's home address.", "I cannot fulfil this request.", "My apologies, but I'm not comfortable producing that content.",
                "I'm unable to help with this.", "Je suis désolé, mais je ne peux pas vous aider avec cette demande."))
            assertTrue(DeclineJudge.soundsLikeDecline(decline), decline);
        assertFalse(DeclineJudge.soundsLikeDecline("NONE"));
        assertFalse(DeclineJudge.soundsLikeDecline("資料によると、1911年にリーズに住んでいた。"));
    }

    @Test
    void aDeclineInBracketsIsNotJson() {
        // L5: a bracket made a decline look like the JSON that was asked for, and it went unread
        AtomicInteger asked = new AtomicInteger();
        Researcher.Drive d = Declines.watch(drive(prose(""), "[Note: I cannot help with this request.]"), "judge", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> d.classify(task("Decompose the placeholder topic. Answer with a JSON array of strings and nothing else."), 400));
        Researcher.Drive e = Declines.watch(drive(prose(""), "Sorry, I can't help with {that}."), "judge", "m", judge(0.95, asked), null);
        assertThrows(Declined.class, () -> e.classify(task("Answer with JSON only: {\"verdict\": \"…\"}"), 60));
        assertEquals(2, asked.get());
        assertTrue(Declines.hasJson("Here it is:\n```json\n[\"a\", \"b\"]\n```"));
        assertTrue(Declines.hasJson("{\"verdict\": \"supported\"} (see [1])"));
        assertFalse(Declines.hasJson("I can't help with [that] request."));
    }

    @Test
    void theSuspectRulesReadTheReplyAsTheLoopWouldRunIt() {
        assertNull(Declines.suspect(prose("thinking out loud"), tools("done"), "auto"));
        assertEquals("thinking out loud", Declines.suspect(prose("thinking out loud"), tools("done"), "required"));
        assertNull(Declines.suspect(call("note", M.createObjectNode().put("claim", "c").put("source", "s")), tools("note"), "required"));
        assertTrue(Declines.asksForJson(task("Answer with JSON only: {\"sufficient\": true}")));
        assertFalse(Declines.asksForJson(task("Answer in words.")));
        assertTrue(Declines.hasJson("sure: {\"a\": 1}"));
        assertFalse(Declines.hasJson(DECLINE));
    }
}
