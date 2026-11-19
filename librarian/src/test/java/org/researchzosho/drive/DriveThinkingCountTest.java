package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a call wrote, as its server bills it, thinking among it, in the single-person library's own count (2026-11-18). Gemini through its
 * OpenAI door leaves the thinking out of completion_tokens and has it in total_tokens (849 of 2,203 on the check), so the count was a
 * fifth low. OpenAI counts it in completion_tokens already, and says it again in its details: counted once. Bedrock is left as it is.
 */
class DriveThinkingCountTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String USAGE = "{\"prompt_tokens\":1203,\"completion_tokens\":151,\"total_tokens\":2203}";

    /** A server like Gemini's OpenAI door: the answer whole, or streamed with the usage in its last event. */
    static HttpServer gemini() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            byte[] in = x.getRequestBody().readAllBytes();
            String out; String type = "application/json";
            if (path.endsWith("/models")) out = "{\"data\":[{\"id\":\"m\"}]}";
            else if (!path.endsWith("/chat/completions")) { x.sendResponseHeaders(404, -1); x.close(); return; }
            else if (M.readTree(in).path("stream").asBoolean(false)) {
                type = "text/event-stream";
                out = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"The ferry ran until 1968.\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":" + USAGE + "}\n\ndata: [DONE]\n\n";
            } else out = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"The ferry ran until 1968.\"},\"finish_reason\":\"stop\"}],\"usage\":" + USAGE + "}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        return s;
    }

    static ArrayNode ask() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "When did the ferry stop?"); return m; }

    @Test
    void theThinkingAServerBillsAsWrittenIsCountedOnceInTheLibrarysOwnCount() throws Exception {
        HttpServer s = gemini();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            long[] whole = new long[3];
            DriveClient.countInto(whole);
            try { c.chat(ask(), null, 64, "auto"); } finally { DriveClient.stopCounting(); }
            assertEquals(1203, whole[0]); assertEquals(1000, whole[2], "written: 151 said and 849 of thinking, from the total");
            long[] streamed = new long[3];
            DriveClient.countInto(streamed); DriveClient.streamHere(delta -> { });
            try { c.chat(ask(), null, 64, "auto"); } finally { DriveClient.stopCounting(); DriveClient.streamHere(null); }
            assertEquals(1000, streamed[2], "the same, streamed");
        } finally { s.stop(0); }
    }

    @Test
    void openAiCountsItOnceAndBedrockIsLeftAsItSays() throws Exception {
        JsonNode openai = M.readTree("{\"prompt_tokens\":100,\"completion_tokens\":500,\"total_tokens\":600,\"completion_tokens_details\":{\"reasoning_tokens\":400}}");
        assertEquals(500, DriveClient.written(openai, false), "the reasoning is in completion_tokens already");
        JsonNode bedrock = M.readTree("{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":900}");   // a total that counts the cache's tokens too
        assertEquals(50, DriveClient.written(bedrock, true));
        assertEquals(151, DriveClient.written(M.readTree("{\"prompt_tokens\":1203,\"completion_tokens\":151}"), false), "no total: as said");
    }
}
