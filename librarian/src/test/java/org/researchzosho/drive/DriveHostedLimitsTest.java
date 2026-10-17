package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hosted OpenAI-style API's limits on the plain path (OpenRouter, OpenAI, Gemini): a 429 is waited out for as long as retry-after says
 * and the call goes on; an error OpenRouter puts inside a 200 (a provider failing after the request was taken) is said as an error, never
 * read as an empty reply; OpenRouter is known by its host.
 */
class DriveHostedLimitsTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void aRateLimitIsWaitedOutAndTheCallGoesOn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(); byte[] out; int code = 200;
            if (path.equals("/v1/chat/completions")) {
                ex.getRequestBody().readAllBytes();
                if (calls.incrementAndGet() == 1) { code = 429; ex.getResponseHeaders().add("Retry-After", "1"); out = "{\"error\":{\"code\":429,\"message\":\"Rate limited\",\"metadata\":{\"error_type\":\"rate_limit_exceeded\"}}}".getBytes(StandardCharsets.UTF_8); }
                else out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"after the wait\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/models")) out = "{\"data\":[{\"id\":\"m\",\"context_length\":32768}]}".getBytes(StandardCharsets.UTF_8);
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            long t0 = System.nanoTime();
            JsonNode reply = c.chat(msgs, null, 32, "none");
            assertEquals("after the wait", reply.path("content").asText());
            assertEquals(2, calls.get());
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 900, "waited the second retry-after named");
        } finally { s.stop(0); }
    }

    @Test
    void anErrorInsideA200IsSaidAsAnError() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(); byte[] out; int code = 200;
            if (path.equals("/v1/chat/completions")) { ex.getRequestBody().readAllBytes(); out = "{\"error\":{\"code\":502,\"message\":\"Provider returned error\",\"metadata\":{\"error_type\":\"provider_unavailable\",\"provider_name\":\"X\"}},\"user_id\":\"u\"}".getBytes(StandardCharsets.UTF_8); }
            else if (path.equals("/v1/models")) out = "{\"data\":[{\"id\":\"m\",\"context_length\":32768}]}".getBytes(StandardCharsets.UTF_8);
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.chat(msgs, null, 32, "none"));
            assertTrue(e.getMessage().contains("Provider returned error"), e.getMessage());
        } finally { s.stop(0); }
    }

    @Test
    void aHostedApiGetsNoTemplateFieldAndADecisionAsksForLittleReasoning() {
        ObjectNode body = M.createObjectNode(); body.put("model", "m"); body.put("max_tokens", 12);
        body.putObject("chat_template_kwargs").put("enable_thinking", false);
        body.put("temperature", 0.7); body.put("top_p", 0.9);
        DriveClient.OpenRouterModels.OVERRIDE = java.util.Map.of("m", new DriveClient.OpenRouterModels.Entry(false, java.util.Set.of("tools", "tool_choice", "reasoning", "max_tokens")), "must", new DriveClient.OpenRouterModels.Entry(true, java.util.Set.of("tools", "temperature", "max_tokens")));
        try {
            ObjectNode or = DriveClient.hostedShape(body.deepCopy(), "https://openrouter.ai/api/v1");
            assertFalse(or.has("chat_template_kwargs")); assertFalse(or.path("reasoning").path("enabled").asBoolean(true), "off where the model allows it"); assertEquals(DriveClient.QUICK_FLOOR, or.path("max_tokens").asInt(), "room for the answer under the thinking");
            assertFalse(or.has("temperature") || or.has("top_p"), "a field the model's endpoints do not take is not sent (GPT-6 Luna: no endpoint found, 2026-10-15): " + or);
            ObjectNode must = body.deepCopy(); must.put("model", "must");
            ObjectNode shaped = DriveClient.hostedShape(must, "https://openrouter.ai/api/v1");
            assertEquals("low", shaped.path("reasoning").path("effort").asText(), "little where it cannot be turned off");
            assertTrue(shaped.has("temperature") && !shaped.has("top_p"), "only the fields the model takes: " + shaped);
            ObjectNode unknown = body.deepCopy(); unknown.put("model", "nobody/knows");
            assertEquals("low", DriveClient.hostedShape(unknown, "https://openrouter.ai/api/v1").path("reasoning").path("effort").asText(), "little when the list does not say");
        } finally { DriveClient.OpenRouterModels.OVERRIDE = null; }
        // a host that runs vLLM gets the template field back, with DeepSeek's own word beside the usual one; a working turn sends none
        ObjectNode neb = DriveClient.hostedShape(body.deepCopy(), "https://api.tokenfactory.nebius.com/v1");
        assertFalse(neb.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)); assertFalse(neb.path("chat_template_kwargs").path("thinking").asBoolean(true), neb.toString());
        assertEquals(DriveClient.QUICK_FLOOR, neb.path("max_tokens").asInt()); assertFalse(neb.has("reasoning") || neb.has("reasoning_effort"));
        ObjectNode nebWork = M.createObjectNode(); nebWork.put("model", "m"); nebWork.putObject("chat_template_kwargs").put("enable_thinking", true);
        assertFalse(DriveClient.hostedShape(nebWork, "https://api.tokenfactory.nebius.com/v1").has("chat_template_kwargs"), "a working turn keeps the model's own thinking, and the field goes home");
        assertEquals("disabled", DriveClient.hostedShape(body.deepCopy(), "https://api.deepseek.com/v1").path("thinking").path("type").asText(), "DeepSeek's own API has its own field");
        assertTrue(DriveClient.templateHost("router.huggingface.co") && !DriveClient.templateHost("openrouter.ai"));
        ObjectNode gem = DriveClient.hostedShape(body.deepCopy(), "https://generativelanguage.googleapis.com/v1beta/openai");
        assertFalse(gem.has("chat_template_kwargs")); assertEquals("low", gem.path("reasoning_effort").asText()); assertFalse(gem.has("reasoning"));
        assertFalse(DriveClient.hostedApi("https://api.openai.com/v1"), "OpenAI's own API is not on the plain path: it goes through the Responses client");
        assertFalse(DriveClient.hostedShape(body.deepCopy(), "https://openrouter.ai/api/v1").has("reasoning_effort"), "OpenRouter's field is reasoning, not reasoning_effort");
        ObjectNode thinking = M.createObjectNode(); thinking.putObject("chat_template_kwargs").put("enable_thinking", true);
        assertFalse(DriveClient.hostedShape(thinking, "https://openrouter.ai/api/v1").has("reasoning"), "a research turn keeps the model's own reasoning");
        assertTrue(DriveClient.hostedApi("https://openrouter.ai/api/v1") && DriveClient.hostedApi("https://generativelanguage.googleapis.com/v1beta/openai") && !DriveClient.hostedApi("http://localhost:8080") && !DriveClient.hostedApi("http://homebox:8212"));
    }

    @Test
    void openRouterIsKnownByItsHost() {
        assertTrue(new DriveClient("https://openrouter.ai/api/v1", "deepseek/deepseek-v4.1-flash").openRouter());
        assertFalse(new DriveClient("https://api.openai.com/v1", "m").openRouter());
        assertFalse(new DriveClient("http://localhost:8080", "m").openRouter());
    }
}
