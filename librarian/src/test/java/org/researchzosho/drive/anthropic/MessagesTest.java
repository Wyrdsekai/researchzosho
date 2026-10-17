package org.researchzosho.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The translation to and from Anthropic's Messages API: the cache breakpoints, no forced tool use, no sampling fields, the usage with the cache's share. */
class MessagesTest {

    static final ObjectMapper M = new ObjectMapper();

    static ObjectNode chatBody() {
        ObjectNode b = M.createObjectNode();
        b.put("model", "x"); b.put("max_tokens", 500); b.put("temperature", 0.7); b.put("top_p", 0.9); b.put("tool_choice", "required");
        ArrayNode msgs = b.putArray("messages");
        msgs.addObject().put("role", "system").put("content", "You are the librarian.");
        msgs.addObject().put("role", "developer").put("content", "Cite everything.");
        msgs.addObject().put("role", "user").put("content", "Who wrote it?");
        ObjectNode a = msgs.addObject(); a.put("role", "assistant"); a.put("content", "Let me look.");
        ObjectNode call = a.putArray("tool_calls").addObject(); call.put("id", "c1"); call.put("type", "function"); call.putObject("function").put("name", "search").put("arguments", "{\"q\":\"author\"}");
        msgs.addObject().put("role", "tool").put("tool_call_id", "c1").put("content", "a result");
        msgs.addObject().put("role", "user").put("content", "and then?");
        msgs.addObject().put("role", "user").put("content", "go on");
        ArrayNode tools = b.putArray("tools");
        for (String n : new String[]{"search", "done"}) { ObjectNode t = tools.addObject(); t.put("type", "function"); ObjectNode f = t.putObject("function"); f.put("name", n); f.put("description", n + " it"); f.putObject("parameters").put("type", "object"); }
        b.putArray("stop").add("END").add(" ");
        return b;
    }

    @Test
    void aRequestHasTheBreakpointsAutoToolChoiceAndNoSamplingFields() {
        ObjectNode r = Messages.request(chatBody(), "claude-sonnet-5-5", "medium");
        assertEquals("claude-sonnet-5-5", r.path("model").asText()); assertEquals(500, r.path("max_tokens").asInt());
        assertFalse(r.has("temperature")); assertFalse(r.has("top_p"));
        assertEquals("You are the librarian.\n\nCite everything.", r.path("system").get(0).path("text").asText());
        assertEquals("ephemeral", r.path("system").get(0).path("cache_control").path("type").asText(), "the system text is a cached prefix");
        assertEquals(2, r.path("tools").size());
        assertFalse(r.path("tools").get(0).has("cache_control")); assertEquals("ephemeral", r.path("tools").get(1).path("cache_control").path("type").asText(), "the tool list is cached at its last tool");
        assertEquals("object", r.path("tools").get(0).path("input_schema").path("type").asText());
        assertEquals("auto", r.path("tool_choice").path("type").asText(), "required is never sent: the current models refuse it");
        assertEquals("ephemeral", r.path("cache_control").path("type").asText(), "the conversation is cached at its end");
        JsonNode msgs = r.path("messages");
        assertEquals(3, msgs.size(), "the two sides take turns, so three user messages in a row are one: " + msgs);
        assertEquals("user", msgs.get(0).path("role").asText()); assertEquals("Who wrote it?", msgs.get(0).path("content").get(0).path("text").asText());
        assertEquals("assistant", msgs.get(1).path("role").asText());
        assertEquals("tool_use", msgs.get(1).path("content").get(1).path("type").asText()); assertEquals("author", msgs.get(1).path("content").get(1).path("input").path("q").asText());
        assertEquals("tool_result", msgs.get(2).path("content").get(0).path("type").asText()); assertEquals("c1", msgs.get(2).path("content").get(0).path("tool_use_id").asText());
        assertEquals("and then?", msgs.get(2).path("content").get(1).path("text").asText(), "the result and the next question are one user turn");
        assertEquals("go on", msgs.get(2).path("content").get(2).path("text").asText());
        assertEquals(1, r.path("stop_sequences").size()); assertEquals("END", r.path("stop_sequences").get(0).asText());
        assertEquals("medium", r.path("output_config").path("effort").asText());
        assertFalse(Messages.request(chatBody(), "m", "").has("output_config"));
        ObjectNode none = chatBody(); none.put("tool_choice", "none");
        assertEquals("none", Messages.request(none, "m", null).path("tool_choice").path("type").asText());
        // a decision asked for without thinking: little thinking, and room for the answer under it
        ObjectNode quick = chatBody(); quick.put("max_tokens", 12); quick.putObject("chat_template_kwargs").put("enable_thinking", false);
        ObjectNode q = Messages.request(quick, "m", null);
        assertEquals("low", q.path("output_config").path("effort").asText()); assertEquals(1024, q.path("max_tokens").asInt()); assertFalse(q.has("chat_template_kwargs"));
        assertEquals("high", Messages.request(quick, "m", "high").path("output_config").path("effort").asText(), "a set effort wins");
        assertTrue(q.path("system").get(0).path("text").asText().endsWith("No explanation before or after it."), "a quick ask is answered in the form asked: Claude otherwise explains in prose where the harness reads JSON (2026-10-15): " + q.path("system"));
        assertTrue(q.path("system").get(0).path("text").asText().startsWith("You are the librarian."), "after the system text, not instead of it");
        assertFalse(r.path("system").get(0).path("text").asText().contains("No explanation"), "an ordinary call is not told how to answer");
    }

    @Test
    void aReplyBecomesAChatCompletionWithItsToolCallsStopReasonAndCacheShare() throws Exception {
        JsonNode reply = M.readTree("{\"id\":\"msg_1\",\"model\":\"claude-sonnet-5-5\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"},{\"type\":\"text\",\"text\":\"Looking.\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"search\",\"input\":{\"q\":\"author\"}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":120,\"output_tokens\":30,\"cache_creation_input_tokens\":1000,\"cache_read_input_tokens\":9000}}");
        ObjectNode c = Messages.response(reply, "claude-sonnet-5-5");
        JsonNode msg = c.path("choices").get(0).path("message");
        assertEquals("Looking.", msg.path("content").asText());
        assertEquals("search", msg.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("{\"q\":\"author\"}", msg.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("tu_1", msg.path("tool_calls").get(0).path("id").asText());
        assertEquals("tool_calls", c.path("choices").get(0).path("finish_reason").asText());
        assertEquals(10120, c.path("usage").path("prompt_tokens").asLong(), "the prompt tokens count the cache's share too");
        assertEquals(9000, c.path("usage").path("cache_read_input_tokens").asLong()); assertEquals(30, c.path("usage").path("completion_tokens").asLong());
        JsonNode stopped = M.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"Done.\"}],\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":5,\"output_tokens\":5}}");
        assertEquals("length", Messages.response(stopped, "m").path("choices").get(0).path("finish_reason").asText());
        JsonNode refused = M.readTree("{\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"not this\"},\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}");
        ObjectNode r = Messages.response(refused, "m");
        assertEquals("refusal", r.path("choices").get(0).path("finish_reason").asText());
        assertEquals("not this", r.path("choices").get(0).path("message").path("refusal").asText(), "the decline travels as the chat format's refusal, which the drive client reads");
    }
}
