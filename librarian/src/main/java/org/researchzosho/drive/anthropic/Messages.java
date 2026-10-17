package org.researchzosho.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Between the chat format the rest of the program speaks (OpenAI's chat completions) and Anthropic's Messages API, the native way to
 * Claude. A request is turned into a Messages request with the cache breakpoints that make a long research run cheap (the tool list,
 * the system text, and the conversation so far are each a prefix Claude keeps for five minutes and reads back at a twentieth of the
 * price); the answer is turned back into a chat completion, so nothing above the model client knows which of the two it talked to.
 * Forced tool use is never asked for (the current models refuse it): {@code required} becomes {@code auto}, and the system text says
 * what the tools are for. Sampling fields are left out: the current models refuse any temperature or top_p but their own.
 */
public final class Messages {

    private Messages() { }

    private static final ObjectMapper J = new ObjectMapper();

    /** {@code body}: a chat-completions request (messages, tools, tool_choice, max_tokens, stop). {@code effort}: low/medium/high/xhigh/max, or blank for the model's own. */
    public static ObjectNode request(JsonNode body, String model, String effort) {
        ObjectNode out = J.createObjectNode();
        out.put("model", model);
        out.put("max_tokens", body.path("max_tokens").isNumber() ? body.path("max_tokens").asInt() : body.path("max_completion_tokens").asInt(4096));
        // the system text: every system or developer message, in order, as one cached block
        StringBuilder system = new StringBuilder();
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText("user");
            if (role.equals("system") || role.equals("developer")) { String t = text(m.path("content")); if (!t.isBlank()) { if (system.length() > 0) system.append("\n\n"); system.append(t); } }
        }
        // the tools, the first prefix: cached at the last one
        ArrayNode tools = J.createArrayNode();
        for (JsonNode t : body.path("tools")) {
            JsonNode f = t.has("function") ? t.path("function") : t;
            ObjectNode tool = tools.addObject();
            tool.put("name", f.path("name").asText(""));
            if (!f.path("description").asText("").isBlank()) tool.put("description", f.path("description").asText());
            JsonNode params = f.path("parameters");
            if (params.isObject()) tool.set("input_schema", params.deepCopy()); else { ObjectNode s = tool.putObject("input_schema"); s.put("type", "object"); s.putObject("properties"); }
        }
        if (!tools.isEmpty()) {
            ((ObjectNode) tools.get(tools.size() - 1)).set("cache_control", ephemeral());
            out.set("tools", tools);
            String choice = body.path("tool_choice").isTextual() ? body.path("tool_choice").asText() : body.path("tool_choice").path("type").asText("auto");
            ObjectNode tc = out.putObject("tool_choice");
            tc.put("type", choice.equals("none") ? "none" : "auto");   // required and a named tool: refused by the current models; auto with the system text
        }
        // a decision the harness asked for without thinking is asked for in the form it names: Claude otherwise explains in prose where a local
        // model answers {"verdict": …}, and the harness then reads nothing (every Anthropic cite-check of 2026-10-15 read "cannot-tell")
        boolean quickAsk = body.path("chat_template_kwargs").path("enable_thinking").isBoolean() && !body.path("chat_template_kwargs").path("enable_thinking").asBoolean();
        if (quickAsk) { if (system.length() > 0) system.append("\n\n"); system.append("Answer in exactly the form the message asks for: the JSON object alone when it asks for JSON, the one word alone when it asks for one word. No explanation before or after it."); }
        if (system.length() > 0) {
            ObjectNode s = out.putArray("system").addObject();
            s.put("type", "text"); s.put("text", system.toString()); s.set("cache_control", ephemeral());
        }
        // the conversation: user text and pictures, the assistant's text and tool calls, each tool's result as something the user side says
        ArrayNode messages = J.createArrayNode();
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText("user");
            if (role.equals("system") || role.equals("developer")) continue;
            ArrayNode blocks = J.createArrayNode();
            if (role.equals("tool")) {
                ObjectNode r = blocks.addObject();
                r.put("type", "tool_result");
                r.put("tool_use_id", m.path("tool_call_id").asText(""));
                String t = text(m.path("content"));
                r.put("content", t.isBlank() ? "(nothing)" : t);
                role = "user";
            } else {
                content(m.path("content"), blocks);
                for (JsonNode call : m.path("tool_calls")) {
                    ObjectNode use = blocks.addObject();
                    use.put("type", "tool_use");
                    use.put("id", call.path("id").asText(""));
                    use.put("name", call.path("function").path("name").asText(""));
                    use.set("input", object(call.path("function").path("arguments")));
                }
                if (!role.equals("assistant")) role = "user";
            }
            if (blocks.isEmpty()) { ObjectNode t = blocks.addObject(); t.put("type", "text"); t.put("text", "(nothing)"); }
            JsonNode last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && last.path("role").asText().equals(role)) ((ArrayNode) last.path("content")).addAll(blocks);   // the two sides take turns
            else { ObjectNode msg = messages.addObject(); msg.put("role", role); msg.set("content", blocks); }
        }
        if (messages.isEmpty() || messages.get(0).path("role").asText().equals("assistant")) {
            ObjectNode first = J.createObjectNode(); first.put("role", "user");
            ObjectNode t = first.putArray("content").addObject(); t.put("type", "text"); t.put("text", "(continue)");
            messages.insert(0, first);
        }
        out.set("messages", messages);
        // the conversation so far, cached at its end: the next turn reads all of it back (the system checks twenty blocks back for an earlier entry)
        out.set("cache_control", ephemeral());
        ArrayNode stops = J.createArrayNode();
        for (JsonNode s : body.path("stop").isArray() ? body.path("stop") : J.createArrayNode().add(body.path("stop"))) if (s.isTextual() && !s.asText().isBlank()) stops.add(s.asText());
        if (!stops.isEmpty()) out.set("stop_sequences", stops);
        // a decision the harness asked for without thinking (llama.cpp's enable_thinking=false): the current models always think, so little of it
        boolean quick = body.path("chat_template_kwargs").path("enable_thinking").isBoolean() && !body.path("chat_template_kwargs").path("enable_thinking").asBoolean();
        if (effort != null && !effort.isBlank()) out.putObject("output_config").put("effort", effort.strip().toLowerCase());
        else if (quick) out.putObject("output_config").put("effort", "low");
        if (quick && out.path("max_tokens").asInt() < 1024) out.put("max_tokens", 1024);   // the thinking counts against it; a one-word answer must still fit
        return out;
    }

    private static ObjectNode ephemeral() { ObjectNode c = J.createObjectNode(); c.put("type", "ephemeral"); return c; }

    /** A Messages reply as a chat completion: the text, the tool calls, how it stopped, and what it cost with the cache's share. */
    public static ObjectNode response(JsonNode reply, String model) {
        ObjectNode out = J.createObjectNode();
        out.put("id", reply.path("id").asText(""));
        out.put("object", "chat.completion");
        out.put("model", reply.path("model").asText(model));
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode calls = J.createArrayNode();
        for (JsonNode block : reply.path("content")) {
            String type = block.path("type").asText("");
            if (type.equals("text")) text.append(block.path("text").asText(""));
            else if (type.equals("tool_use")) {
                ObjectNode call = calls.addObject();
                call.put("id", block.path("id").asText(""));
                call.put("type", "function");
                ObjectNode f = call.putObject("function");
                f.put("name", block.path("name").asText(""));
                f.put("arguments", block.path("input").isMissingNode() ? "{}" : block.path("input").toString());
            }
        }
        if (text.length() > 0 || calls.isEmpty()) message.put("content", text.toString()); else message.putNull("content");
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        String stop = reply.path("stop_reason").asText("end_turn");
        String finish = switch (stop) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "refusal" -> "refusal";
            default -> "stop";
        };
        choice.put("finish_reason", finish);
        if (finish.equals("refusal")) {
            String why = reply.path("stop_details").path("explanation").asText("");
            message.put("refusal", why.isBlank() ? "The model declined this request." : why);
        }
        JsonNode u = reply.path("usage");
        ObjectNode usage = out.putObject("usage");
        long in = u.path("input_tokens").asLong(0), made = u.path("cache_creation_input_tokens").asLong(0), read = u.path("cache_read_input_tokens").asLong(0), outTokens = u.path("output_tokens").asLong(0);
        usage.put("prompt_tokens", in + made + read);
        usage.put("completion_tokens", outTokens);
        usage.put("total_tokens", in + made + read + outTokens);
        usage.put("cache_read_input_tokens", read);
        usage.put("cache_creation_input_tokens", made);
        return out;
    }

    private static void content(JsonNode content, ArrayNode blocks) {
        if (content.isTextual()) { if (!content.asText().isBlank()) { ObjectNode t = blocks.addObject(); t.put("type", "text"); t.put("text", content.asText()); } return; }
        for (JsonNode part : content) {
            String type = part.path("type").asText("text");
            if (type.equals("text")) { if (!part.path("text").asText("").isBlank()) { ObjectNode t = blocks.addObject(); t.put("type", "text"); t.put("text", part.path("text").asText()); } }
            else if (type.equals("image_url")) {
                String url = part.path("image_url").path("url").asText("");
                ObjectNode img = blocks.addObject(); img.put("type", "image");
                ObjectNode src = img.putObject("source");
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(','); int semi = url.indexOf(';');
                    src.put("type", "base64"); src.put("media_type", semi > 5 ? url.substring(5, semi) : "image/png"); src.put("data", comma > 0 ? url.substring(comma + 1) : "");
                } else { src.put("type", "url"); src.put("url", url); }
            }
        }
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        List<String> parts = new ArrayList<>();
        for (JsonNode p : content) if (p.path("type").asText("text").equals("text")) parts.add(p.path("text").asText(""));
        return String.join("\n", parts);
    }

    private static JsonNode object(JsonNode arguments) {
        if (arguments.isObject()) return arguments.deepCopy();
        try { JsonNode n = J.readTree(arguments.asText("{}")); return n.isObject() ? n : J.createObjectNode(); } catch (Exception e) { return J.createObjectNode(); }
    }
}
