package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decision asked of a model that thinks by default, on a server that takes none of the thinking-off fields: the small budget goes to
 * the hidden reasoning and the visible answer is empty. Measured 2026-10-17 on Ollama's OpenAI-style endpoint (gpt-oss, gemma4, qwen3):
 * every page check came back empty and the research stopped after three pages. The decision is asked once more with room to think,
 * and the answer is read after the thinking; an Ollama server is told, in the one word it takes, not to think at all.
 */
class DriveThinkingAnswerTest {

    static final ObjectMapper M = new ObjectMapper();

    static ArrayNode ask() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "QUESTION: is the sky green? Answer with one word: yes or no."); return m; }

    /** A server whose model thinks: with a small budget the content is empty and the thinking is cut; with room it answers "no". */
    static HttpServer thinking(List<JsonNode> requests, boolean ollama) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            JsonNode req = M.readTree(x.getRequestBody().readAllBytes()); requests.add(req);
            boolean room = req.path("max_tokens").asInt(0) >= 256 || "none".equals(req.path("reasoning_effort").asText(""));
            String body = room
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"no\",\"reasoning\":\"Okay, the sky is blue.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":12}}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"Okay,\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        if (ollama) s.createContext("/api/show", x -> {
            x.getRequestBody().readAllBytes();
            byte[] b = "{\"model_info\":{\"qwen3.context_length\":40960}}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    @Test
    void aModelThatThinksThroughTheSmallBudgetIsAskedAgainWithRoomAndItsAnswerIsRead() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = thinking(requests, false);
        try {
            String said = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").classify(ask(), 4);
            assertEquals("no", said, "the answer after the thinking, not the thinking");
            assertEquals(2, requests.size(), "the small ask, then one with room");
            assertEquals(4, requests.get(0).path("max_tokens").asInt());
            assertEquals(DriveClient.THINKING_ROOM, requests.get(1).path("max_tokens").asInt());
            assertFalse(requests.get(0).has("reasoning_effort"), "a server that is not Ollama is not sent Ollama's word");
            assertEquals("", DriveClient.lastClassifyProblem(), "nothing went wrong: the model answered");
        } finally { s.stop(0); }
    }

    /** The server's kind is learnt after the first empty answer, never by probing a server that may not answer: from then on one ask. */
    @Test
    void anOllamaServerIsToldNotToThinkInTheWordItTakes() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        HttpServer s = thinking(requests, true);
        try {
            DriveClient d = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            assertEquals("no", d.classify(ask(), 4));
            assertEquals(2, requests.size(), "the small ask, then the one with room, which carries Ollama's word");
            assertFalse(requests.get(0).has("reasoning_effort"), "not yet known to be Ollama");
            assertEquals("none", requests.get(1).path("reasoning_effort").asText());
            assertEquals(DriveClient.THINKING_ROOM, requests.get(1).path("max_tokens").asInt());
            assertEquals("no", d.classify(ask(), 4));
            assertEquals(3, requests.size(), "known to be Ollama: told not to think, the model answers within the small budget, one ask");
            assertEquals("none", requests.get(2).path("reasoning_effort").asText());
            assertEquals(4, requests.get(2).path("max_tokens").asInt());
        } finally { s.stop(0); }
    }

    /** A second empty answer is reported as before: the model thought and said nothing, and the caller treats it as unanswered. */
    @Test
    void aModelThatSaysNothingEvenWithRoomIsReportedAsSuch() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", x -> {
            x.getRequestBody().readAllBytes();
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"Hmm\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (var os = x.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            String said = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").classify(ask(), 4);
            assertEquals("Hmm", said, "the thinking is handed back, as before, for a caller that can read it");
            assertEquals("the model thought before answering and gave no answer", DriveClient.lastClassifyProblem());
        } finally { s.stop(0); }
    }
}
