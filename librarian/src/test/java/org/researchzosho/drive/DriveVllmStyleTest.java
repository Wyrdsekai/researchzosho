package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A vLLM-style server (no /props, a context length in /v1/models) is asked for tool calls with tool_choice=auto where the caller said
 * "required": vLLM's "required" is guided JSON decoding, and a model whose template speaks XML tool calls answered it with a wrong, empty
 * call (2026-10-13: the writer's done() came back as a 755-token note() with no report; at "auto" the same model wrote the whole report).
 * A llama.cpp server (/props) keeps "required".
 */
class DriveVllmStyleTest {

    static final ObjectMapper M = new ObjectMapper();

    /** A stub model server: llama.cpp when it serves /props, vLLM-style when /v1/models carries max_model_len; the request bodies are kept. */
    static HttpServer server(boolean props, List<JsonNode> bodies) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(); byte[] out; int code = 200;
            if (path.equals("/props") && props) out = "{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":4}".getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/v1/models")) out = (props ? "{\"data\":[{\"id\":\"m\"}]}" : "{\"data\":[{\"id\":\"m\",\"max_model_len\":32768}]}").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/v1/chat/completions")) {
                bodies.add(M.readTree(ex.getRequestBody().readAllBytes()));
                out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"done\",\"arguments\":\"{\\\"report\\\":\\\"all of it\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}".getBytes(StandardCharsets.UTF_8);
            } else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        return s;
    }

    static ArrayNode tools() {
        ArrayNode t = M.createArrayNode();
        var f = t.addObject(); f.put("type", "function"); var fn = f.putObject("function"); fn.put("name", "done"); fn.putObject("parameters").put("type", "object");
        return t;
    }

    @Test
    void aVllmStyleServerIsAskedWithAutoWhereRequiredWasWanted() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        HttpServer s = server(false, bodies);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "write the report");
            JsonNode reply = c.chat(msgs, tools(), 64, "required");
            assertEquals("done", reply.path("tool_calls").path(0).path("function").path("name").asText());
            assertEquals(1, bodies.size());
            assertEquals("auto", bodies.get(0).path("tool_choice").asText(), "vLLM style: auto, never required: " + bodies.get(0));
            assertTrue(c.vllmStyle());
        } finally { s.stop(0); }
    }

    @Test
    void aLlamaCppServerKeepsRequired() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        HttpServer s = server(true, bodies);
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "write the report");
            c.chat(msgs, tools(), 64, "required");
            assertEquals("required", bodies.get(0).path("tool_choice").asText(), bodies.get(0).toString());
            assertFalse(c.vllmStyle());
        } finally { s.stop(0); }
    }
}
