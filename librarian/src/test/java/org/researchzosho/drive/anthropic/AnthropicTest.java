package org.researchzosho.drive.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The client's own behaviour: the headers, the waits the limits ask for, a spend cap that is not waited for, the models and their windows. */
class AnthropicTest {

    static final ObjectMapper M = new ObjectMapper();
    record Sent(String method, URI uri, Map<String, String> headers, String body) { }

    /** A transport that answers from a script and keeps what was sent. */
    static final class Script implements Anthropic.Transport {
        final List<Anthropic.Answer> answers = new ArrayList<>(); final List<Sent> sent = new ArrayList<>();
        Script then(int status, String body, String... headers) { Map<String, String> h = new java.util.HashMap<>(); for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]); answers.add(new Anthropic.Answer(status, body, h)); return this; }
        @Override public Anthropic.Answer send(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {
            sent.add(new Sent(method, uri, headers, body));
            if (answers.isEmpty()) throw new IllegalStateException("no answer scripted for " + uri);
            return answers.remove(0);
        }
    }

    static ObjectNode ask() { ObjectNode b = M.createObjectNode(); b.put("max_tokens", 50); b.putArray("messages").addObject().put("role", "user").put("content", "hi"); return b; }
    static final String OK = "{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";

    @Test
    void theHeadersTheAddressAndTheReply() throws Exception {
        Script t = new Script().then(200, OK);
        List<Long> slept = new ArrayList<>();
        Anthropic a = new Anthropic("https://api.anthropic.com/v1/", "sk-test", t, slept::add);
        ObjectNode r = a.chat("claude-sonnet-5-5", ask(), Duration.ofSeconds(5));
        assertEquals("hello", r.path("choices").get(0).path("message").path("content").asText());
        Sent s = t.sent.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", s.uri().toString(), "the /v1 typed on the end is not doubled");
        assertEquals("sk-test", s.headers().get("x-api-key")); assertEquals(Anthropic.VERSION, s.headers().get("anthropic-version")); assertEquals("application/json", s.headers().get("content-type"));
        assertEquals("claude-sonnet-5-5", M.readTree(s.body()).path("model").asText());
        assertTrue(slept.isEmpty());
        assertTrue(Anthropic.is("https://api.anthropic.com") && Anthropic.is("anthropic") && Anthropic.is("https://api.anthropic.com/v1/") && !Anthropic.is("https://openrouter.ai/api/v1") && !Anthropic.is("http://localhost:8080"));
        assertEquals("https://api.anthropic.com", Anthropic.base("anthropic"));
        Anthropic noKey = new Anthropic("", "", t, slept::add);
        assertTrue(assertThrows(Anthropic.Refused.class, () -> noKey.chat("m", ask(), Duration.ofSeconds(5))).getMessage().contains("needs a key"));
    }

    @Test
    void aRateLimitIsWaitedOutAsTheHeaderSaysAndASpendCapIsNot() {
        String limited = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"too many tokens\"}}";
        Script t = new Script().then(429, limited, "retry-after", "7").then(429, limited).then(200, OK);
        List<Long> slept = new ArrayList<>();
        Anthropic a = new Anthropic(null, "k", t, slept::add);
        assertEquals("hello", a.chat("m", ask(), Duration.ofSeconds(5)).path("choices").get(0).path("message").path("content").asText());
        assertEquals(List.of(7_000L, 10_000L), slept, "the header's seconds, then a growing wait when it names none");
        assertEquals(3, t.sent.size());
        String cap = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"You have reached your API usage limits\",\"details\":{\"error_code\":\"enforced_spend_limit_reached\"}}}";
        Script c = new Script().then(429, cap);
        Anthropic capped = new Anthropic(null, "k", c, slept::add);
        Anthropic.Refused r = assertThrows(Anthropic.Refused.class, () -> capped.chat("m", ask(), Duration.ofSeconds(5)));
        assertTrue(r.getMessage().contains("spend cap"), r.getMessage());
        assertEquals(1, c.sent.size(), "a spend cap is not waited for");
    }

    @Test
    void anOverloadIsTriedAgainWithAGrowingWaitAndARefusalIsSaid() {
        String busy = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        Script t = new Script().then(529, busy).then(503, busy).then(200, OK);
        List<Long> slept = new ArrayList<>();
        Anthropic a = new Anthropic(null, "k", t, slept::add);
        a.chat("m", ask(), Duration.ofSeconds(5));
        assertEquals(List.of(2_000L, 4_000L), slept);
        Script bad = new Script().then(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"tool_choice: type \\\"any\\\" is not supported\"}}");
        Anthropic.Refused r = assertThrows(Anthropic.Refused.class, () -> new Anthropic(null, "k", bad, slept::add).chat("m", ask(), Duration.ofSeconds(5)));
        assertEquals(400, r.status); assertTrue(r.getMessage().contains("not supported"), r.getMessage());
        assertEquals(1, bad.sent.size(), "a refusal of the request's shape is not retried");
    }

    @Test
    void whenTheMinutesTokensAreNearlySpentTheNextCallWaitsForTheReset() {
        String reset = Instant.now().plusSeconds(20).toString();
        Script t = new Script().then(200, OK, "anthropic-ratelimit-input-tokens-remaining", "3000", "anthropic-ratelimit-input-tokens-reset", reset).then(200, OK, "anthropic-ratelimit-input-tokens-remaining", "900000", "anthropic-ratelimit-input-tokens-reset", reset).then(200, OK);
        List<Long> slept = new ArrayList<>();
        Anthropic a = new Anthropic(null, "k", t, slept::add);
        a.chat("m", ask(), Duration.ofSeconds(5));
        assertTrue(slept.isEmpty());
        a.chat("m", ask(), Duration.ofSeconds(5));
        assertEquals(1, slept.size()); assertTrue(slept.get(0) > 10_000 && slept.get(0) <= 20_000, "waited for the reset: " + slept);
        a.chat("m", ask(), Duration.ofSeconds(5));
        assertEquals(1, slept.size(), "plenty left: no wait");
    }

    @Test
    void theOutputAskedForIsCappedAtWhatTheModelCanWrite() throws Exception {
        // the library asks for half a million tokens of output (a share of a million-token window); the model writes 128,000 at most
        Script t = new Script().then(200, "{\"id\":\"claude-sonnet-5-5-test\",\"max_input_tokens\":1000000,\"max_tokens\":96000}").then(200, OK);
        Anthropic a = new Anthropic(null, "k", t, ms -> { });
        assertEquals(1_000_000, a.contextWindow("claude-sonnet-5-5-test"), "the window asked once, which brings the output cap too");
        ObjectNode big = ask(); big.put("max_tokens", 500000);
        a.chat("claude-sonnet-5-5-test", big, Duration.ofSeconds(5));
        assertEquals(96000, M.readTree(t.sent.get(1).body()).path("max_tokens").asInt(), "capped at the Models API's figure");
        // a model whose window was never asked: the usual cap first; the refusal names the real one, which is learnt and the call made again
        String refused = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: 128000 > 64000, which is the maximum allowed number of output tokens for claude-x\"}}";
        Script u = new Script().then(400, refused).then(200, OK);
        Anthropic b = new Anthropic(null, "k", u, ms -> { });
        b.chat("claude-x", big, Duration.ofSeconds(5));
        assertEquals(128000, M.readTree(u.sent.get(0).body()).path("max_tokens").asInt(), "the usual cap first");
        assertEquals(64000, M.readTree(u.sent.get(1).body()).path("max_tokens").asInt(), "then the cap the refusal named");
        assertEquals(64000, b.maxOutput("claude-x"), "and it is kept");
    }

    @Test
    void theModelsAndTheirWindows() {
        String list = "{\"data\":[{\"id\":\"claude-sonnet-5-5\",\"display_name\":\"Claude Sonnet 5.5\",\"max_input_tokens\":1000000,\"max_tokens\":128000},{\"id\":\"claude-haiku-5-5\",\"display_name\":\"Claude Haiku 5.5\"}],\"has_more\":false}";
        Script t = new Script().then(200, list).then(200, "{\"id\":\"claude-opus-5-5\",\"max_input_tokens\":1000000}").then(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        Anthropic a = new Anthropic(null, "k", t, ms -> { });
        List<Anthropic.Model> models = a.models();
        assertEquals(2, models.size()); assertEquals("claude-sonnet-5-5", models.get(0).id()); assertEquals(1_000_000, models.get(0).contextWindow());
        assertEquals("GET", t.sent.get(0).method()); assertTrue(t.sent.get(0).uri().toString().endsWith("/v1/models?limit=100"));
        assertEquals(1_000_000, a.contextWindow("claude-opus-5-5"));
        assertTrue(t.sent.get(1).uri().toString().endsWith("/v1/models/claude-opus-5-5"));
        assertEquals(1_000_000, a.contextWindow("claude-opus-5-5"), "asked once"); assertEquals(2, t.sent.size());
        assertFalse(a.reachable(), "a 401 is not reachable");
    }
}
