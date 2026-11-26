package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A quick check's answer cut short is no answer, and the model's thinking is never its answer: neither is read as the model declining.
 * Found on 2026-11-24 with a model that thinks whatever it is told (Inkling 2 Mini on llama.cpp): the cite-check's 60 tokens went to its
 * thinking, the answer stopped at {"verdict":"cannot-, the word "cannot" was taken for a refusal, the report got a Declined section and
 * the check of every later sentence stopped. The responses below are that record's shapes.
 */
class CutAnswerTest {
    static final ObjectMapper M = new ObjectMapper();
    /** DriveClient.THINKING_ROOM: the allowance a quick check is asked again with. */
    static final int ROOM = 2048;
    @TempDir Path tmp;

    @AfterEach void back() { Config.provide(Map.of()); }

    /** The record's response to the 60-token cite-check (seq 1163 of the research look): the thinking took the room, the answer was cut. */
    static String cutAfterThinking() {
        return "{\"choices\":[{\"finish_reason\":\"length\",\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"verdict\\\":\\\"cannot-\","
                + "\"reasoning_content\":\"The user asks whether the source supports the sentence. The sentence is just a link to ASPCA cats plant list. The source excerpt is homepage, doesn't contain cats plant list. So cannot-tell. Need JSON only.\"}}],"
                + "\"usage\":{\"completion_tokens\":60,\"prompt_tokens\":1265}}";
    }
    static String said(String content) { return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":" + q(content) + "}}],\"usage\":{\"completion_tokens\":9,\"prompt_tokens\":900}}"; }
    static String thoughtOnly(String thinking) { return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":" + q(thinking) + "}}],\"usage\":{\"completion_tokens\":2048,\"prompt_tokens\":900}}"; }
    static String q(String s) { try { return M.writeValueAsString(s); } catch (Exception e) { throw new RuntimeException(e); } }

    /** A stand-in model server: each chat request is answered by {@code answer}, /props by {@code props} (null: no such page). */
    static HttpServer serve(List<JsonNode> requests, Function<JsonNode, String> answer, String props, AtomicInteger propsAsked) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            byte[] out; int code;
            if ("POST".equals(x.getRequestMethod()) && x.getRequestURI().getPath().endsWith("/chat/completions")) {
                JsonNode req = M.readTree(x.getRequestBody().readAllBytes()); requests.add(req);
                out = answer.apply(req).getBytes(StandardCharsets.UTF_8); code = 200;
            } else if (x.getRequestURI().getPath().equals("/props") && props != null) {
                if (propsAsked != null) propsAsked.incrementAndGet();
                out = props.getBytes(StandardCharsets.UTF_8); code = 200;
            } else { x.getRequestBody().readAllBytes(); out = "{}".getBytes(StandardCharsets.UTF_8); code = 404; }
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(code, out.length);
            try (var os = x.getResponseBody()) { os.write(out); }
        });
        s.start();
        return s;
    }
    static String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    static ArrayNode verdictAsk() {
        ArrayNode m = M.createArrayNode();
        m.addObject().put("role", "user").put("content", "A report sentence cites the SOURCE below. Does the source support the sentence as written?\n\nSENTENCE:\nthe sentence\n\nSOURCE (excerpt):\nthe page\n\n"
                + "Answer with JSON only: {\"verdict\": \"supported|unsupported|cannot-tell\"}.");
        return m;
    }

    /** The judge of a watched seat that says yes to anything it is asked, counting the questions. */
    static DeclineJudge yesSayer(AtomicInteger asked) { return new DeclineJudge(null, m -> { asked.incrementAndGet(); return "yes"; }); }

    @Test
    void aVerdictCutOffAfterTheThinkingIsAskedAgainWithRoom() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> r.path("max_tokens").asInt() < ROOM ? cutAfterThinking() : said("{\"verdict\":\"cannot-tell\"}"), null, null);
        try {
            String got = new DriveClient(url(s), "m").classify(verdictAsk(), 60);
            assertEquals("{\"verdict\":\"cannot-tell\"}", got, "the whole verdict, asked again with room");
            assertEquals(2, requests.size());
            assertEquals(60, requests.get(0).path("max_tokens").asInt());
            assertEquals(ROOM, requests.get(1).path("max_tokens").asInt());
            assertFalse(DriveClient.lastClassifyCut());
        } finally { s.stop(0); }
    }

    @Test
    void aCutVerdictIsNotADeclineAndTheJudgeIsNotAsked() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        // cut every time, even with room: what is left is an answer not finished
        HttpServer s = serve(requests, r -> cutAfterThinking(), null, null);
        AtomicInteger asked = new AtomicInteger();
        try (var step = Declines.step("say whether a source supports a sentence of a report, as JSON")) {
            Researcher.Drive watched = Declines.watch(Researcher.drive(url(s), "m"), "judge", "m", yesSayer(asked), null);
            String got = assertDoesNotThrow(() -> watched.classify(verdictAsk(), 60));
            assertEquals("{\"verdict\":\"cannot-", got);
            assertTrue(DriveClient.lastClassifyCut());
            assertEquals(0, asked.get(), "a cut answer is never put to the decline judge");
        } finally { s.stop(0); }
    }

    @Test
    void theThinkingIsNeverReadAsADecline() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> thoughtOnly("I cannot tell from this excerpt whether the page lists the plant; the sentence is only a link."), null, null);
        AtomicInteger asked = new AtomicInteger();
        try (var step = Declines.step("say whether a source supports a sentence of a report, as JSON")) {
            Researcher.Drive watched = Declines.watch(Researcher.drive(url(s), "m"), "judge", "m", yesSayer(asked), null);
            String got = assertDoesNotThrow(() -> watched.classify(verdictAsk(), 60));
            assertTrue(got.startsWith("I cannot tell"), "the thinking is still handed back to a caller that reads a verdict wherever it is");
            assertTrue(DriveClient.lastClassifyThinking());
            assertEquals(0, asked.get(), "thinking is no answer, so no decline");
            assertEquals("cannot-tell", CiteCheck.verdictInProse(got), "and the check reads no verdict from it either way");
        } finally { s.stop(0); }
    }

    /** The control: a model whose ANSWER declines still declines. */
    @Test
    void aDeclineInTheAnswerIsStillADecline() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> said("I'm sorry, I can't help check this source."), null, null);
        AtomicInteger asked = new AtomicInteger();
        try (var step = Declines.step("say whether a source supports a sentence of a report, as JSON")) {
            Researcher.Drive watched = Declines.watch(Researcher.drive(url(s), "m"), "judge", "m", yesSayer(asked), null);
            Declined d = assertThrows(Declined.class, () -> watched.classify(verdictAsk(), 60));
            assertEquals("I'm sorry, I can't help check this source.", d.said());
            assertEquals(1, asked.get());
        } finally { s.stop(0); }
    }

    /** The damage the record shows: the cite-check stopped at the cut verdict, and every later sentence went unchecked. */
    @Test
    void theCiteCheckGoesOnPastAVerdictCutShort() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String a = "https://plants.example/list", b = "https://plants.example/fern";
        RawCapture.capture(store, a, "Plants non-toxic to cats: spider plant, Boston fern, African violet. " + "Menu and links. ".repeat(30), "Plant list", "test", "");
        RawCapture.capture(store, b, "The Boston fern is listed as non-toxic to cats and dogs by the poison control centre. " + "Menu and links. ".repeat(30), "Boston fern", "test", "");
        List<CiteCheck.Ref> refs = List.of(new CiteCheck.Ref(1, a, "", "Plant list"), new CiteCheck.Ref(2, b, "", "Boston fern"));
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> {
            String prompt = r.path("messages").path(0).path("content").asText();
            if (prompt.contains("Boston fern is listed")) return said("{\"verdict\":\"supported\"}");
            return r.path("max_tokens").asInt() < ROOM ? cutAfterThinking() : said("{\"verdict\":\"cannot-tell\"}");
        }, null, null);
        AtomicInteger asked = new AtomicInteger();
        try {
            Researcher.Drive judge = Declines.watch(Researcher.drive(url(s), "m"), "judge", "m", yesSayer(asked), null);
            String text = "Cats can live with several common houseplants without harm, though owners should still check each one [1].\n"
                    + "The fern most often sold for hanging baskets is regarded as safe for cats by the poison control service [2].";
            CiteCheck.Outcome cc = CiteCheck.run(store, text, refs, judge, new Researcher.Budget(20));
            assertNull(cc.declined(), "nothing was declined: " + cc.problems());
            assertEquals(2, cc.checked(), "both sentences were checked: " + cc.problems());
            assertEquals(1, cc.supported());
            assertEquals(0, asked.get(), "no decline question was spent");
        } finally { s.stop(0); }
    }

    /** The one-word judges read the model's word, never its thinking: a thought that begins "Yes" is no yes. */
    @Test
    void theOneWordJudgesDoNotReadTheThinkingAsTheWord() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> thoughtOnly("Yes, the reply says it can't read the report, but the report does not exist, so"), null, null);
        try {
            DriveClient c = new DriveClient(url(s), "m");
            DeclineJudge.Verdict v = new DeclineJudge(null, m -> c.classify(m, 4)).read("answer a person's message", "I can't find I-0003 on the shelves.");
            assertFalse(v.declined(), "a thought is not a verdict");
            assertFalse(v.sure());
            ContentJudge.Reading r = new ContentJudge(null, m -> c.classify(m, 4)).ask("a page about kilns", "Is this page sexually explicit?");
            assertFalse(r.judged(), "the page check got no answer, and says so: " + r.why());
            assertTrue(r.why().contains("thought before answering"), r.why());
            Judge plain = new Judge("m", body -> { throw new IllegalStateException("HTTP 400: grammar not supported"); }, null, (m, began) -> c.classify(m, 4, began));
            Judge.Answer a = plain.noul("the sky", "Is the sky green?");
            assertFalse(a.ran(), "the plain way read no answer from the thinking");
        } finally { s.stop(0); }
    }

    // ---- the way to switch the thinking off, learnt from the server ----

    /** llama.cpp's /props for a template that reads reasoning_effort and not enable_thinking, as b12679 gave it for Inkling 2 Mini. */
    static final String INKLING_PROPS = "{\"default_generation_settings\":{\"n_ctx\":32768},\"chat_template_caps\":{\"supports_reasoning_effort\":true,"
            + "\"reasoning_effort_levels\":[\"none\",\"minimal\",\"low\",\"medium\",\"high\",\"max\"],\"reasoning_effort_rejected\":[]}}";

    /** A model that thinks unless its template is told "none": then it answers in the small budget. */
    static String thinksUnlessNone(JsonNode r) {
        boolean none = "none".equals(r.path("chat_template_kwargs").path("reasoning_effort").asText(""));
        if (none || r.path("max_tokens").asInt() >= ROOM) return said("no");
        return thoughtOnly("The user asks");
    }

    @Test
    void aDriveSeenThinkingOnAQuickCheckAsksItsTemplateForTheLowestLevelItLists() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, CutAnswerTest::thinksUnlessNone, INKLING_PROPS, null);
        try {
            DriveClient c = new DriveClient(url(s), "m");
            ArrayNode ask = M.createArrayNode(); ask.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no.");
            assertEquals("no", c.classify(ask.deepCopy(), 4));
            assertEquals("low", requests.get(0).path("chat_template_kwargs").path("reasoning_effort").asText(), "the first ask as every quick check asks");
            assertEquals("none", requests.get(1).path("chat_template_kwargs").path("reasoning_effort").asText(), "the ask with room carries the template's lowest level");
            assertFalse(requests.get(1).has("reasoning_effort"), "never the request's own field: llama.cpp from 2026-08-14 to 11-21 erased the template's level for \"none\"");
            assertTrue(requests.get(1).path("chat_template_kwargs").path("enable_thinking").isBoolean());
            assertEquals("no", c.classify(ask.deepCopy(), 4));
            assertEquals(3, requests.size(), "from then on one ask: the template is told none and the model answers in the small budget");
            assertEquals("none", requests.get(2).path("chat_template_kwargs").path("reasoning_effort").asText());
            assertEquals(4, requests.get(2).path("max_tokens").asInt());
            // the typed judge's questions are quick checks too
            ObjectNode typed = M.createObjectNode(); typed.set("messages", ask.deepCopy()); typed.put("max_tokens", 1);
            DriveClient.decisionSettings(typed);
            c.postChat(typed);
            assertEquals("none", requests.get(3).path("chat_template_kwargs").path("reasoning_effort").asText());
        } finally { s.stop(0); }
    }

    @Test
    void aModelThatDoesNotThinkIsAskedAsBefore() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> said("no"), INKLING_PROPS, null);
        try {
            DriveClient c = new DriveClient(url(s), "m");
            ArrayNode ask = M.createArrayNode(); ask.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no.");
            c.classify(ask.deepCopy(), 4); c.classify(ask.deepCopy(), 4);
            assertEquals(2, requests.size());
            for (JsonNode r : requests) assertEquals("low", r.path("chat_template_kwargs").path("reasoning_effort").asText(), "a model that answers without thinking (the 27B's records) is asked as before");
        } finally { s.stop(0); }
    }

    @Test
    void theLevelIsOneTheTemplateTakesAndTheDrivesOwnSettingStands() throws Exception {
        String props = "{\"chat_template_caps\":{\"supports_reasoning_effort\":true,\"reasoning_effort_levels\":[\"minimal\",\"low\",\"high\"],\"reasoning_effort_rejected\":[\"minimal\"]}}";
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = serve(requests, r -> r.path("max_tokens").asInt() >= ROOM ? said("no") : thoughtOnly("The user asks"), props, null);
        try {
            DriveClient c = new DriveClient(url(s), "m");
            ArrayNode ask = M.createArrayNode(); ask.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no.");
            c.classify(ask.deepCopy(), 4);
            assertEquals("low", requests.get(1).path("chat_template_kwargs").path("reasoning_effort").asText(), "the lowest level the template does not raise on");
            Config.provide(Map.of("RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS", "{\"reasoning_effort\":\"high\"}"));
            c.classify(ask.deepCopy(), 4);
            assertEquals("high", requests.get(2).path("chat_template_kwargs").path("reasoning_effort").asText(), "a level the person set for the drive stands");
        } finally { s.stop(0); Config.provide(Map.of()); }
        // a server that lists no levels: the template is asked as before
        List<JsonNode> again = Collections.synchronizedList(new ArrayList<>());
        HttpServer t = serve(again, r -> r.path("max_tokens").asInt() >= ROOM ? said("no") : thoughtOnly("The user asks"), "{\"chat_template_caps\":{\"reasoning_effort_levels\":[]}}", null);
        try {
            DriveClient c = new DriveClient(url(t), "m");
            ArrayNode ask = M.createArrayNode(); ask.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no.");
            c.classify(ask.deepCopy(), 4); c.classify(ask.deepCopy(), 4);
            for (JsonNode r : again) assertEquals("low", r.path("chat_template_kwargs").path("reasoning_effort").asText());
        } finally { t.stop(0); }
    }
}
