package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server that refuses the typed question's form gets the question the plain way, in the same time, and is not sent the typed form again
 * (round G, the final live check on 0fbf7188: llama.cpp with gpt-oss-20b answered the typed question, max_tokens 1 with a grammar and
 * token probabilities, with HTTP 500 "The model produced output that does not match the expected peg-native format" in 372 of 372 calls;
 * the judge never answered, and every one of them was sent again).
 */
class JudgePlainWayTest {
    static final ObjectMapper M = new ObjectMapper();
    HttpServer server;
    /** Each ask as the server saw it: "typed" for one with a grammar, else its max_tokens. */
    final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach void clean() { Judge.forgetGrammar(); }
    @AfterEach void stop() { if (server != null) server.stop(0); Judge.forgetGrammar(); }

    record Reply(int status, String body, long waitMs) { }

    String serve(Function<JsonNode, Reply> answer) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", x -> {
            if (!x.getRequestURI().getPath().endsWith("/chat/completions")) { x.sendResponseHeaders(404, -1); x.close(); return; }
            JsonNode body = M.readTree(x.getRequestBody().readAllBytes());
            asked.add(body.has("grammar") ? "typed" : String.valueOf(body.path("max_tokens").asInt()));
            Reply r = answer.apply(body);
            try { Thread.sleep(r.waitMs()); } catch (InterruptedException e) { return; }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            try { x.getResponseHeaders().add("Content-Type", "application/json"); x.sendResponseHeaders(r.status(), b.length); x.getResponseBody().write(b); } catch (Exception gone) { /* the caller left */ }
            x.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static final String PEG = "{\"error\":{\"code\":500,\"message\":\"The model produced output that does not match the expected peg-native format\",\"type\":\"server_error\"}}";
    static String thought() { return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"We\"}}]}"; }
    static String said(String word) { return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"" + word + "\",\"reasoning_content\":\"No instructions.\"}}]}"; }

    /** gpt-oss-20b on llama.cpp as the probe met it: the typed form refused with a 500, four tokens thought away, an answer with room. */
    static Reply gptOss(JsonNode body, String word) {
        if (body.has("grammar")) return new Reply(500, PEG, 0);
        return body.path("max_tokens").asInt() < DriveClient.THINKING_ROOM ? new Reply(200, thought(), 0) : new Reply(200, said(word), 0);
    }

    @Test
    void aServerThatRefusesTheTypedFormIsAskedThePlainWayAndRemembered() throws Exception {
        String url = serve(b -> gptOss(b, "no"));
        DriveClient d = new DriveClient(url, "gpt-oss-20b").decisionTimeout(Duration.ofSeconds(20));
        Judge judge = new Judge(d);
        Judge.Answer a = judge.noul("THE PAGE: a ferry timetable.", "Does this page contain sexual content involving a child?");
        assertTrue(a.ran(), "the judge answers: " + Judge.lastProblem());
        assertFalse(a.yes());
        assertEquals(0.0, a.p("yes"));
        assertEquals(List.of("typed", "4", String.valueOf(DriveClient.THINKING_ROOM)), asked, "the typed form once, then the plain way with room");
        assertFalse(Judge.grammarWorks(d.decisionKey()), "the server and model are remembered");
        // the next question goes the plain way at once: the typed form is not sent again
        asked.clear();
        Judge.Answer b = new Judge(d).noul("THE PAGE: a ferry timetable.", "Does this page give step-by-step instructions for making a weapon?");
        assertTrue(b.ran() && !b.yes());
        assertEquals(List.of("4", String.valueOf(DriveClient.THINKING_ROOM)), asked, "no typed form: " + asked);
        // a choice among options is read by its letter
        stop(); asked.clear();
        String url2 = serve(body -> body.has("grammar") ? new Reply(500, PEG, 0) : new Reply(200, said("B."), 0));
        Judge.Answer c = new Judge(new DriveClient(url2, "gpt-oss-20b")).choice("", "Which?", List.of("now", "next", "hold"));
        assertTrue(c.ran());
        assertEquals("next", c.choice());
        // the content judge reads it, and asks nothing a second time
        stop(); asked.clear();
        String url3 = serve(b3 -> gptOss(b3, "yes"));
        DriveClient d3 = new DriveClient(url3, "gpt-oss-20b-3").decisionTimeout(Duration.ofSeconds(20));
        ContentJudge.Reading r = new ContentJudge(new Judge(d3), m -> d3.classify(m, 4)).ask("THE PAGE: …", "Does this page contain sexual content involving a child?");
        assertEquals(ContentJudge.Verdict.YES, r.verdict());
        assertEquals(List.of("typed", "4", String.valueOf(DriveClient.THINKING_ROOM)), asked);
    }

    @Test
    void theTypedQuestionAndThePlainOneShareOneLimit() throws Exception {
        // the typed form refused after 1.2 s; the plain way's four tokens thought away, and its ask with room would take 2 s more
        String url = serve(b -> b.has("grammar") ? new Reply(500, PEG, 1_200) : b.path("max_tokens").asInt() < DriveClient.THINKING_ROOM ? new Reply(200, thought(), 0) : new Reply(200, said("no"), 2_000));
        DriveClient d = new DriveClient(url, "gpt-oss-20b-limit").decisionTimeout(Duration.ofMillis(2_500));
        long t0 = System.currentTimeMillis();
        Judge.Answer a = new Judge(d).noul("THE PAGE: …", "Is it about ferries?");
        long took = System.currentTimeMillis() - t0;
        assertFalse(a.ran(), "no answer in the one limit");
        assertNotNull(a.unanswered(), "unanswered, never a no");
        assertTrue(took < 3_400, "one limit for both: " + took + " ms");
    }

    @Test
    void aServerThatKeepsToTheGrammarIsStillReadFromItsProbabilitiesAndABusyOneIsNotRemembered() throws Exception {
        // the typed form answered: probabilities read, nothing asked the plain way
        String url = serve(b -> new Reply(200, "{\"choices\":[{\"message\":{\"content\":\"no\"},\"logprobs\":{\"content\":[{\"token\":\"no\",\"logprob\":-0.105,"
                + "\"top_logprobs\":[{\"token\":\"no\",\"logprob\":-0.105},{\"token\":\"yes\",\"logprob\":-2.303}]}]}}]}", 0));
        DriveClient d = new DriveClient(url, "qwen");
        Judge.Answer a = new Judge(d).noul("x", "Is it?");
        assertTrue(a.ran() && !a.yes() && a.p("yes") > 0.05 && a.p("yes") < 0.2, "the model's own probability: " + a.probabilities());
        assertEquals(List.of("typed"), asked);
        assertFalse(Judge.askedPlainly());
        // a server error that says nothing of the form (out of memory, busy): unanswered as before, not remembered, not asked the plain way
        stop(); asked.clear();
        String url2 = serve(b -> new Reply(500, "{\"error\":{\"code\":500,\"message\":\"out of memory\"}}", 0));
        DriveClient busy = new DriveClient(url2, "qwen-busy");
        Judge.Answer b = new Judge(busy).noul("x", "Is it?");
        assertFalse(b.ran());
        assertNotNull(b.unanswered());
        assertEquals(List.of("typed"), asked);
        assertTrue(Judge.grammarWorks(busy.decisionKey()), "a passing failure is not remembered");
    }

    @Test
    void aPlainYesNeverEndsWorkWhereTheDeclineJudgeWantsTheModelsOwnProbability() throws Exception {
        String url = serve(b -> gptOss(b, "yes"));
        DriveClient d = new DriveClient(url, "gpt-oss-20b-decline").decisionTimeout(Duration.ofSeconds(20));
        DeclineJudge dj = new DeclineJudge(new Judge(d), m -> d.classify(m, 4));
        DeclineJudge.Verdict ends = dj.read("list the claims in a text", "I cannot help with that.", true);
        assertFalse(ends.declined(), "a one-word yes is only noted where a decline would end the run");
        assertEquals(List.of("typed", "4", String.valueOf(DriveClient.THINKING_ROOM)), asked, "asked once the plain way, not again: " + asked);
        assertFalse(dj.sameAsk("Who wrote it?", "Who is its author?"), "the same-question check stays the typed judge's only");
    }
}
