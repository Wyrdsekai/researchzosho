package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.researchzosho.drive.aws.Converse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server's own signal that the model declined is read, not thrown away: finish_reason content_filter, a message's refusal
 * field, and Bedrock's guardrail and content filter. The client raises {@link Declined} with the model's name and its words, once:
 * nothing is sent again.
 */
class DriveDeclineTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String DECLINE = "I am not able to help with that request.";

    /** A server that answers every chat completion with {@code body}, and counts the requests. */
    static HttpServer server(String body, AtomicInteger calls) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            calls.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    static ArrayNode task() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "Summarise the placeholder topic. Answer with JSON only."); return m; }

    static String completion(String finishReason, String content, String refusal) throws Exception {
        ObjectNode r = M.createObjectNode();
        ObjectNode c = r.putArray("choices").addObject();
        c.put("index", 0);
        ObjectNode msg = c.putObject("message"); msg.put("role", "assistant");
        if (content == null) msg.putNull("content"); else msg.put("content", content);
        if (refusal == null) msg.putNull("refusal"); else msg.put("refusal", refusal);
        c.put("finish_reason", finishReason);
        r.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 3);
        return M.writeValueAsString(r);
    }

    @Test
    void aFilteredReplyIsADeclineAndIsNotSentAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = server(completion("content_filter", "", null), calls);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model");
            Declined d = assertThrows(Declined.class, () -> c.chat(task(), null, 64, "auto"));
            assertEquals(Declined.How.FILTERED, d.how());
            assertEquals("placeholder-model", d.model());
            assertEquals(1, calls.get(), "one request: a decline is never retried");
            assertTrue(d.statement("to research this question").startsWith("The model this library uses (placeholder-model) declined to research this question. ResearchZosho did not try to get around it."), d.statement("to research this question"));
            assertNotNull(DriveClient.lastUsage(), "what the declined call cost is still counted");
        } finally { s.stop(0); }
    }

    @Test
    void aRefusalFieldIsADeclineWithTheModelsWords() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = server(completion("stop", null, DECLINE), calls);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model");
            Declined d = assertThrows(Declined.class, () -> c.chat(task(), null, 64, "auto"));
            assertEquals(Declined.How.REFUSAL, d.how());
            assertEquals(DECLINE, d.said());
            assertTrue(d.statement().contains("What the model said: \"" + DECLINE + "\""), d.statement());
        } finally { s.stop(0); }
    }

    @Test
    void classifyRaisesTheDeclineInsteadOfAnsweringWithNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer filtered = server(completion("content_filter", "", null), calls);
        HttpServer refused = server(completion("stop", null, DECLINE), calls);
        try {
            DriveClient a = new DriveClient("http://127.0.0.1:" + filtered.getAddress().getPort(), "m");
            assertEquals(Declined.How.FILTERED, assertThrows(Declined.class, () -> a.classify(task(), 64)).how(), "a filtered reply is a decline, not an empty answer");
            DriveClient b = new DriveClient("http://127.0.0.1:" + refused.getAddress().getPort(), "m");
            Declined d = assertThrows(Declined.class, () -> b.classify(task(), 64));
            assertEquals(DECLINE, d.said());
            assertEquals(2, calls.get(), "each asked once");
        } finally { filtered.stop(0); refused.stop(0); }
    }

    @Test
    void anOrdinaryReplyIsNotADecline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = server(completion("stop", "{\"topic\": \"placeholder\"}", null), calls);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            assertEquals("{\"topic\": \"placeholder\"}", c.classify(task(), 64));
            assertEquals("{\"topic\": \"placeholder\"}", c.chat(task(), null, 64, "auto").path("content").asText());
        } finally { s.stop(0); }
    }

    @Test
    void bedrocksGuardrailAndContentFilterAreDeclines() throws Exception {
        ObjectNode guarded = Converse.response(M.readTree("{\"output\":{\"message\":{\"content\":[{\"text\":\"Sorry, the model cannot answer this question.\"}]}},\"stopReason\":\"guardrail_intervened\",\"usage\":{\"inputTokens\":5,\"outputTokens\":0}}"), "m");
        assertEquals("content_filter", guarded.path("choices").get(0).path("finish_reason").asText(), "the chat-completions shape keeps its word for it");
        assertEquals(Declined.How.GUARDRAIL, DriveClient.declineSignal(guarded), "the guardrail's own text is ordinary text: the stop reason says what it is");
        ObjectNode filtered = Converse.response(M.readTree("{\"output\":{\"message\":{\"content\":[]}},\"stopReason\":\"content_filtered\",\"usage\":{\"inputTokens\":5,\"outputTokens\":0}}"), "m");
        assertEquals(Declined.How.FILTERED, DriveClient.declineSignal(filtered));
        ObjectNode ended = Converse.response(M.readTree("{\"output\":{\"message\":{\"content\":[{\"text\":\"done\"}]}},\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":5,\"outputTokens\":1}}"), "m");
        assertNull(DriveClient.declineSignal(ended));
    }

    @Test
    void aRequestTheServicesContentFilterStoppedIsADeclineNotAnOutage() throws Exception {
        // M10: Azure OpenAI answers a prompt its filter stops with HTTP 400 and error.code content_filter
        String azure = "{\"error\":{\"message\":\"The response was filtered due to the prompt triggering the content management policy.\",\"type\":null,\"param\":\"prompt\",\"code\":\"content_filter\",\"status\":400,"
                + "\"innererror\":{\"code\":\"ResponsibleAIPolicyViolation\",\"content_filter_result\":{\"hate\":{\"filtered\":true,\"severity\":\"medium\"}}}}}";
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            calls.incrementAndGet();
            byte[] b = azure.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(400, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "placeholder-model");
            Declined d = assertThrows(Declined.class, () -> c.chat(task(), null, 64, "auto"));
            assertEquals("placeholder-model", d.model());
            assertTrue(d.statement().contains("stopped the request with its content filter before the model answered"), d.statement());
            assertThrows(Declined.class, () -> c.classify(task(), 64), "classify says the decline instead of an empty answer");
            assertEquals(2, calls.get(), "each asked once: no retry");
        } finally { s.stop(0); }
    }

    @Test
    void theJudgesQuestionLeavesTheTurnsUsageAsTheCallerReadsIt() throws Exception {
        // L1: the one-word judge is a request of its own; its usage took the place of the turn's, so traces and the ledger counted the
        // judge's few tokens and the runner calibrated its token scale from them
        AtomicInteger calls = new AtomicInteger();
        String turn = completion("stop", "I am not able to help with that request.", null).replace("\"prompt_tokens\":12", "\"prompt_tokens\":900").replace("\"completion_tokens\":3", "\"completion_tokens\":50");
        HttpServer work = server(turn, calls), judging = server(completion("stop", "yes", null).replace("\"prompt_tokens\":12", "\"prompt_tokens\":7").replace("\"completion_tokens\":3", "\"completion_tokens\":1"), calls);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + work.getAddress().getPort(), "placeholder-model");
            DriveClient j = new DriveClient("http://127.0.0.1:" + judging.getAddress().getPort(), "placeholder-model");
            String reply = c.classify(task(), 64);
            assertArrayEquals(new long[]{900, 50}, DriveClient.lastUsage());
            DeclineJudge.Verdict v = new DeclineJudge(null, m -> j.classify(m, 4)).read("name what two areas of a library share", reply);
            assertTrue(v.declined(), "the one word was asked and said yes");
            assertArrayEquals(new long[]{900, 50}, DriveClient.lastUsage(), "the turn's usage, not the judge's");
        } finally { work.stop(0); judging.stop(0); }
    }
}
