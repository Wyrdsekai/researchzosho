package org.researchzosho.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude through Anthropic's own API (0.6.2): the drive {@code https://api.anthropic.com} with the person's key. Why not the OpenAI-style
 * layer Anthropic also serves: that one has no prompt caching, and a research run sends the whole conversation with every call — about
 * 1.7 million input tokens a run — of which the native API reads nine tenths back from its cache at a twentieth of the price.
 * Rate limits are built in: a 429 is waited out for as long as the {@code retry-after} header says (the spend-cap 429, which carries no
 * such header, is not retried), a 529 or a 5xx is tried again with a growing wait, and when the headers say the minute's tokens are
 * nearly spent the next call waits for the reset rather than running into the limit. The context window comes from the Models API.
 */
public final class Anthropic {

    private static final Logger log = LoggerFactory.getLogger(Anthropic.class);
    private static final ObjectMapper J = new ObjectMapper();
    public static final String BASE = "https://api.anthropic.com", VERSION = "2023-06-01";
    /** The setting for the depth of thinking: low, medium, high, xhigh or max; unset is the model's own default. */
    public static final String EFFORT = "RESEARCHZOSHO_ANTHROPIC_EFFORT";
    /** A context window set by hand, for a model the Models API does not describe. */
    public static final String CONTEXT = "RESEARCHZOSHO_ANTHROPIC_CONTEXT";

    public interface Transport { Answer send(String method, URI uri, Map<String, String> headers, String body, Duration timeout) throws Exception; }
    public record Answer(int status, String body, Map<String, String> headers) {
        public String header(String name) { for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue(); return null; }
    }

    /** The API refused the request (a 4xx that is not a rate limit, or a rate limit that cannot be waited out). */
    public static final class Refused extends RuntimeException {
        public final int status;
        public Refused(int status, String message) { super(message); this.status = status; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    private static final Transport REAL = (method, uri, headers, body, timeout) -> {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> h = new ConcurrentHashMap<>();
        r.headers().map().forEach((k, v) -> { if (!v.isEmpty()) h.put(k, v.get(0)); });
        return new Answer(r.statusCode(), r.body(), h);
    };

    private final String base, key;
    private final Transport transport;
    private final LongConsumer sleeper;
    /** The moment the minute's input tokens are back, when the last answer said they were nearly spent; 0 when they were not. */
    private volatile long waitUntilMillis;

    public Anthropic(String base, String key) { this(base, key, REAL, ms -> { try { Stopping.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); }

    Anthropic(String base, String key, Transport transport, LongConsumer sleeper) {
        this.base = (base == null || base.isBlank() ? BASE : base.strip()).replaceAll("/+$", "").replaceAll("/v1$", "");
        this.key = key == null ? "" : key.strip();
        this.transport = transport; this.sleeper = sleeper;
    }

    /** Whether a drive setting names Anthropic's API: its address, or the word. */
    public static boolean is(String drive) {
        if (drive == null) return false;
        String d = drive.strip().toLowerCase(Locale.ROOT);
        return d.equals("anthropic") || d.equals("claude") || d.contains("://api.anthropic.com");
    }

    public static String base(String drive) { return is(drive) && drive.contains("://") ? drive.strip() : BASE; }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new ConcurrentHashMap<>();
        if (!key.isEmpty()) h.put("x-api-key", key);
        h.put("anthropic-version", VERSION);
        if (json) h.put("content-type", "application/json");
        return h;
    }

    /** A chat-completions request in, a chat completion out. The output asked for is capped at what the model can write (the library asks for a
     *  share of the context window, which is a million tokens here, and the API refuses more than the model's output cap). */
    public ObjectNode chat(String model, JsonNode chatCompletionsBody, Duration timeout) {
        if (key.isEmpty()) throw new Refused(401, "Anthropic needs a key: set api_key (researchzosho settings set api_key …) to the key from platform.claude.com.");
        ObjectNode request = Messages.request(chatCompletionsBody, model, Config.get(EFFORT));
        int cap = maxOutput(model);
        if (request.path("max_tokens").asInt(0) > cap) request.put("max_tokens", cap);
        Answer a;
        try { a = call("POST", base + "/v1/messages", request.toString(), timeout); }
        catch (Refused r) {
            // the cap the API names, when ours was wrong: learnt, and asked once more
            Matcher m = Pattern.compile("max_tokens: \\d+ > (\\d+)").matcher(r.getMessage());
            if (r.status != 400 || !m.find()) throw r;
            int learnt = Integer.parseInt(m.group(1));
            OUTPUTS.put(model, learnt);
            request.put("max_tokens", Math.min(request.path("max_tokens").asInt(), learnt));
            a = call("POST", base + "/v1/messages", request.toString(), timeout);
        }
        try { return Messages.response(J.readTree(a.body()), model); }
        catch (Exception e) { throw new Refused(a.status(), "Anthropic answered something that is not a reply: " + head(a.body())); }
    }

    /** The call, with the waiting the limits ask for: retry-after on a 429, a growing wait on a 529 or a 5xx, and the minute's reset when the headers say it is nearly spent. */
    Answer call(String method, String url, String body, Duration timeout) {
        long wait = waitUntilMillis - System.currentTimeMillis();
        if (wait > 0) { log.info("anthropic: the minute's tokens are nearly spent; waiting {} s for the reset", wait / 1000); sleeper.accept(Math.min(wait, 60_000)); }
        Answer a = null;
        long overloadWait = 2_000;
        for (int attempt = 1; ; attempt++) {
            try { a = transport.send(method, URI.create(url), headers(body != null), body, timeout); }
            catch (Exception e) { throw new RuntimeException("Anthropic could not be reached: " + e.getMessage(), e); }
            int s = a.status();
            if (s == 200) { pace(a); return a; }
            String message = message(a.body());
            if (s == 429) {
                if (message.contains("enforced_spend_limit_reached") || body(a.body()).path("error").path("details").path("error_code").asText("").equals("enforced_spend_limit_reached"))
                    throw new Refused(429, "Anthropic: the organisation's monthly spend cap is reached; " + message);
                long seconds = retryAfter(a);
                if (attempt > 6) throw new Refused(429, "Anthropic rate limit, still after six waits: " + message);
                long ms = seconds > 0 ? Math.min(seconds, 120) * 1000 : Math.min(5_000L * attempt, 60_000);
                log.warn("anthropic 429 (try {}/6), waiting {} s: {}", attempt, ms / 1000, message);
                sleeper.accept(ms);
                continue;
            }
            if (s == 529 || s >= 500) {
                if (attempt > 5) throw new Refused(s, "Anthropic HTTP " + s + " after five tries: " + message);
                log.warn("anthropic HTTP {} (try {}/5), waiting {} s: {}", s, attempt, overloadWait / 1000, message);
                sleeper.accept(overloadWait);
                overloadWait = Math.min(overloadWait * 2, 30_000);
                continue;
            }
            throw new Refused(s, "Anthropic HTTP " + s + ": " + message);
        }
    }

    /** The next call waits for the reset when fewer than 20,000 input tokens are left in the minute (the headers round to the thousand). */
    private void pace(Answer a) {
        String remaining = a.header("anthropic-ratelimit-input-tokens-remaining"), reset = a.header("anthropic-ratelimit-input-tokens-reset");
        if (remaining == null || reset == null) return;
        try {
            if (Long.parseLong(remaining.strip()) < 20_000) waitUntilMillis = Instant.parse(reset.strip()).toEpochMilli();
            else waitUntilMillis = 0;
        } catch (NumberFormatException | DateTimeParseException ignored) { }
    }

    static long retryAfter(Answer a) {
        String v = a.header("retry-after");
        if (v == null || v.isBlank()) return 0;
        try { return Long.parseLong(v.strip()); } catch (NumberFormatException e) { return 0; }
    }

    private static JsonNode body(String s) { try { return J.readTree(s); } catch (Exception e) { return J.createObjectNode(); } }

    static String message(String body) {
        JsonNode n = body(body);
        String m = n.path("error").path("message").asText("");
        return m.isBlank() ? head(body) : m;
    }

    private static String head(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s; }

    // ---- models ----

    public record Model(String id, String name, int contextWindow, int maxOutput) { }

    /** The models this key may use, newest first as the API lists them. */
    public List<Model> models() {
        Answer a = call("GET", base + "/v1/models?limit=100", null, Duration.ofSeconds(20));
        List<Model> out = new ArrayList<>();
        for (JsonNode m : body(a.body()).path("data")) out.add(new Model(m.path("id").asText(""), m.path("display_name").asText(""), m.path("max_input_tokens").asInt(0), m.path("max_tokens").asInt(0)));
        return out;
    }

    private static final Map<String, Integer> WINDOWS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> OUTPUTS = new ConcurrentHashMap<>();
    /** What the current models can write at most in one reply, when the Models API does not say. */
    static final int DEFAULT_MAX_OUTPUT = 128_000;

    /** The most output tokens one reply may have: what the Models API said of the model when its window was asked, or what a refusal named, else 128,000. */
    public int maxOutput(String model) { return OUTPUTS.getOrDefault(model, DEFAULT_MAX_OUTPUT); }

    /** The model's context window: the setting when one is given, else what the Models API says of it, else 200,000. */
    public int contextWindow(String model) {
        int set = Config.getInt(CONTEXT, 0);
        if (set > 0) return set;
        Integer known = WINDOWS.get(model);
        if (known != null) return known;
        int n = 200_000;
        try {
            Answer a = call("GET", base + "/v1/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8), null, Duration.ofSeconds(20));
            JsonNode m = body(a.body());
            int got = m.path("max_input_tokens").asInt(0);
            if (got > 0) n = got;
            int out = m.path("max_tokens").asInt(0);
            if (out > 0) OUTPUTS.put(model, out);
        } catch (RuntimeException e) { log.warn("anthropic: the context window of {} could not be asked ({}); taking {}", model, e.getMessage(), n); }
        WINDOWS.put(model, n);
        return n;
    }

    /** Whether the key opens the API: the models list answers. */
    public boolean reachable() {
        try { call("GET", base + "/v1/models?limit=1", null, Duration.ofSeconds(15)); return true; } catch (RuntimeException e) { return false; }
    }
}
