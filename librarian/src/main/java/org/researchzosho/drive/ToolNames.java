package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool names as a hosted API takes them. OpenAI, Anthropic and Bedrock take a tool's name only as letters, digits, underscores and
 * dashes, 64 at most, and refuse the whole request otherwise; the library names a tool of another MCP server {@code server.tool}. For a
 * hosted drive the request carries each such name in a form they take ({@code server__tool}, shortened with a hash when long), in the
 * tools, in the calls of the conversation so far and in a named tool choice; the model's calls come back under the library's names. A
 * local server takes the names as they are and is sent them so.
 */
final class ToolNames {
    private ToolNames() { }

    static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** The names of one request: the library's to the wire's, and back. */
    record Wire(Map<String, String> out, Map<String, String> back) {
        boolean none() { return out.isEmpty(); }

        /** The model's answer with its calls under the library's names. */
        ObjectNode restore(ObjectNode msg) {
            if (msg == null || back.isEmpty()) return msg;
            for (JsonNode call : msg.path("tool_calls")) {
                if (!(call.path("function") instanceof ObjectNode f)) continue;
                String n = f.path("name").asText("");
                if (back.containsKey(n)) f.put("name", back.get(n));
            }
            return msg;
        }
    }

    /** The names a request's tools carry that a hosted API would refuse, each with a form it takes. */
    static Wire of(ArrayNode tools) {
        Map<String, String> out = new LinkedHashMap<>(), back = new LinkedHashMap<>();
        if (tools == null) return new Wire(out, back);
        for (JsonNode t : tools) {
            String n = t.path("function").path("name").asText("");
            if (n.isEmpty() || SAFE.matcher(n).matches() || out.containsKey(n)) continue;
            String w = n.replace(".", "__").replaceAll("[^A-Za-z0-9_-]", "_");
            if (w.length() > 64) w = w.substring(0, 52) + "_" + hash(n);
            while (back.containsKey(w) || taken(tools, w)) w = w.substring(0, Math.min(w.length(), 60)) + "_" + back.size();
            out.put(n, w); back.put(w, n);
        }
        return new Wire(out, back);
    }

    private static boolean taken(ArrayNode tools, String w) { for (JsonNode t : tools) if (w.equals(t.path("function").path("name").asText(""))) return true; return false; }

    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 5); }
        catch (NoSuchAlgorithmException e) { return Integer.toHexString(s.hashCode()); }
    }

    /**
     * The request's body with the wire's names: the tools and the messages copied first, so the loop's own tools and history keep the
     * library's names.
     */
    static void apply(ObjectNode body, Wire w) {
        if (w.none()) return;
        if (body.path("tools") instanceof ArrayNode tools) {
            ArrayNode copy = tools.deepCopy();
            for (JsonNode t : copy) if (t.path("function") instanceof ObjectNode f && w.out().containsKey(f.path("name").asText(""))) f.put("name", w.out().get(f.path("name").asText()));
            body.set("tools", copy);
        }
        if (body.path("messages") instanceof ArrayNode messages) {
            ArrayNode copy = messages.deepCopy();
            for (JsonNode m : copy) {
                for (JsonNode call : m.path("tool_calls")) if (call.path("function") instanceof ObjectNode f && w.out().containsKey(f.path("name").asText(""))) f.put("name", w.out().get(f.path("name").asText()));
                if (m instanceof ObjectNode o && "tool".equals(o.path("role").asText("")) && w.out().containsKey(o.path("name").asText(""))) o.put("name", w.out().get(o.path("name").asText()));
            }
            body.set("messages", copy);
        }
        if (body.path("tool_choice").path("function") instanceof ObjectNode f && w.out().containsKey(f.path("name").asText(""))) f.put("name", w.out().get(f.path("name").asText()));
    }
}
