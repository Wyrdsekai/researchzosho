package org.researchzosho.drive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.researchzosho.drive.aws.AwsCredentials;
import org.researchzosho.drive.aws.Bedrock;
/**
 * Thin client over the llama.cpp drive at :8200 (OpenAI-compatible).
 *
 * <p>When {@code tools} are present we set
 * {@code tool_choice="required"}, which (with {@code --jinja}) structurally forbids prose
 * runaways — the model can only act. The per-turn {@code max_tokens} cap is derived from the
 * live {@code /props} {@code n_ctx} so the output can never overflow the window.
 *
 * <p>The client transforms nothing on the way in or out beyond JSON framing — the loop owns
 * the raw conversation. (RESET §2/§3: do not pre-digest what the model sees.) The ONE exception is
 * malformed-tool-call RECOVERY: when {@code --jinja} 500s on the model's own bad tool-call JSON (a known
 * llama.cpp bug, ggml-org #20359), we re-request the same turn in text mode and salvage the call so a
 * sampling artifact can't spin the run to its turn cap. The recovered message has the normal shape the
 * loop already executes — no downstream change.
 */
public final class DriveClient {
    private static final Logger log = LoggerFactory.getLogger(DriveClient.class);

    private final String baseUrl;
    private final String model;
    /** One HTTP client for every DriveClient: each client owns a selector thread and an executor, and a client per
     *  instance (a Researcher's drive per job, a chat turn's drive per page post) leaked threads until the macOS CI
     *  runner failed with "pthread_create failed (EAGAIN)" at its 472nd HttpClient (2026-09-14). */
    private static final HttpClient SHARED_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    // Tolerant reader for SALVAGED tool calls (text-mode recovery, below): the re-emitted JSON can still
    // carry single quotes / unescaped control chars / a trailing comma. Mirrors the loop's parseArgs reader.
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    public DriveClient(String baseUrl, String model) {
        this.baseUrl = Config.driveBase(baseUrl);   // an address pasted with /v1 on the end is the same drive
        this.model = model;
        this.http = SHARED_HTTP;
        // drive = bedrock: the person's own AWS account, through Converse, instead of an OpenAI-style server
        this.bedrock = Bedrock.is(this.baseUrl)
                ? new Bedrock(Bedrock.settings(this.baseUrl, Config::get, System.getenv())) : null;
    }

    /** Amazon Bedrock behind this client, or null for every other drive. */
    private final Bedrock bedrock;

    /**
     * Bearer credential for the endpoint, or null when none is configured.
     *
     * <p>Hosted OpenAI-compatible endpoints need one; a local llama.cpp or Ollama does not, and
     * sending an empty header to those is worse than sending nothing. Read per request rather than
     * cached so a key rotated in the environment takes effect without a restart.
     */
    private static String apiKey() {
        String k = Config.get("RESEARCHZOSHO_API_KEY");
        return (k == null || k.isBlank()) ? null : k.trim();
    }

    /**
     * The credential for THIS client's endpoint: the configured key, only when the endpoint's host is the
     * configured drive's host (RESEARCHZOSHO_DRIVE, or a host in RESEARCHZOSHO_API_KEY_HOSTS). A URL typed on
     * the command line — `researchzosho catalog http://elsewhere` — used to be sent the key too
     * (Wyrdsekai, 2026-09-07).
     */
    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        return auth(b, keyFor(baseUrl));
    }

    static String keyFor(String endpoint) {
        String judge = Config.get("RESEARCHZOSHO_JUDGE_DRIVE");
        String extra = Config.get("RESEARCHZOSHO_API_KEY_HOSTS", "");
        if (judge != null && hostOf(judge) != null) extra = extra.isBlank() ? hostOf(judge) : extra + "," + hostOf(judge);
        return keyFor(endpoint, apiKey(), Config.get("RESEARCHZOSHO_DRIVE"), extra);
    }

    /** The decision itself, pure: the key goes only to the configured drive's host or a listed extra host. */
    public static String keyFor(String endpoint, String key, String configuredDrive, String extraHosts) {
        if (key == null || key.isBlank()) return null;
        String host = hostOf(endpoint);
        if (host == null) return null;
        List<String> allowed = new ArrayList<>();
        if (configuredDrive != null && hostOf(configuredDrive) != null) allowed.add(hostOf(configuredDrive));
        for (String h : (extraHosts == null ? "" : extraHosts).split(",")) if (!h.isBlank()) allowed.add(h.strip().toLowerCase(Locale.ROOT));
        return allowed.contains(host) ? key.trim() : null;
    }

    static String hostOf(String url) {
        try {
            String h = URI.create(url.strip()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** The decision itself, separated so it can be exercised without the process environment. */
    static HttpRequest.Builder auth(HttpRequest.Builder b, String key) {
        if (key == null || key.isBlank()) return b;          // a local server wants no header at all
        String k = key.trim();
        // Accept a key given either bare or already prefixed, because both are in circulation and a
        // doubled "Bearer Bearer sk-..." fails in a way that looks like a bad key rather than a typo.
        return b.header("Authorization", k.regionMatches(true, 0, "Bearer ", 0, 7) ? k : "Bearer " + k);
    }

    public ObjectMapper json() {
        return json;
    }

    /**
     * On macOS, name the real cause of an unreachable LAN drive instead of leaving a dead end.
     *
     * <p>macOS 14+ gates local-network access per application. A JVM launched from a terminal — and
     * especially over ssh — cannot show the permission prompt, so the connection is refused with
     * {@code NoRouteToHostException} even though the host is plainly reachable: measured on macOS 26.5,
     * where {@code curl} returned 200 for the same URL, in the same shell, a second apart. Without this
     * note the message points at the endpoint, which is the one thing that is NOT wrong.
     */
    private static String localNetworkHint(Exception e) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return "";
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof NoRouteToHostException || c instanceof ConnectException) {
                return " — on macOS this is usually the Local Network privacy permission rather than the "
                        + "network: grant it to your terminal app under System Settings > Privacy & "
                        + "Security > Local Network, then retry. (If `curl` reaches the same URL, that is "
                        + "the cause.) A drive on localhost or a remote host outside the LAN is unaffected.";
            }
        }
        return "";
    }

    /** Process-lifetime token counters, for /cost on metered drives. Static on purpose: a chat
     *  session swaps DriveClient instances on /model, and the person's question is "what has this
     *  SESSION spent", not "this client object". */
    public static final AtomicLong SESSION_PROMPT_TOKENS =
            new AtomicLong();
    /** The usage the server reported for the last call on THIS thread (prompt, completion), or null: a per-run trace reads it right after the call. */
    private static final ThreadLocal<long[]> LAST_USAGE = new ThreadLocal<>();
    public static long[] lastUsage() { long[] u = LAST_USAGE.get(); return u == null ? null : u.clone(); }
    /** Forget this thread's last usage before a call whose usage is read next: a drive that reports none leaves no earlier call's reading behind. */
    public static void forgetUsage() { LAST_USAGE.remove(); }
    /** Put back a reading taken with {@link #lastUsage}: a question asked about a reply (the decline judge's) never takes the turn's place. */
    static void restoreUsage(long[] u) { if (u == null) LAST_USAGE.remove(); else LAST_USAGE.set(u.clone()); }
    public static final AtomicLong SESSION_COMPLETION_TOKENS =
            new AtomicLong();

    /** The least one call to the model is given in all, from the request to the last of its answer, streamed or not. Five minutes
     *  suits every drive we had — until a 744B with CPU-resident experts needed 10-20 min per long generation and every call
     *  "failed" at exactly 300s (measured 2026-08-31: 8 identical 5-minute-spaced failures ended the run).
     *  RESEARCHZOSHO_DRIVE_TIMEOUT (seconds) raises it for slow drives. Every drive the library uses sends the head of its answer
     *  only when the whole answer is ready, so this was also the ceiling on how long an answer could be: a call is given more when
     *  its prompt and its max_tokens need it ({@link #limitFor}). The limit is kept by {@link Stopping#send}. */
    private static Duration driveTimeout() {
        int s = Config.getInt("RESEARCHZOSHO_DRIVE_TIMEOUT", 300);
        return Duration.ofSeconds(Math.max(30, s));
    }

    /** Context window from the live server: /props → default_generation_settings.n_ctx. */
    /** Used when no server will tell us. Deliberately small: guessing LOW costs an early compaction,
     *  guessing HIGH overflows the window mid-run, and only one of those is recoverable. */
    private static final int FALLBACK_CTX = 8192;

    public int contextWindow() {
        // An explicit setting wins: the operator knows what they launched the server with, and a
        // server's self-report can be the model's maximum rather than the context it was started with.
        int forced = Config.getInt("RESEARCHZOSHO_CTX", 0);
        if (forced > 0) return forced;
        // Bedrock has no way to ask a model for its window: a setting, or what is known of the model's family
        if (bedrock != null) return Bedrock.contextWindow(model, Config.getInt("RESEARCHZOSHO_BEDROCK_CONTEXT", 0));

        Integer n = fromLlamaCppProps();
        if (n != null) return n;

        n = fromOllamaShow();
        if (n != null) {
            log.info("context window {} (from ollama /api/show; llama.cpp /props not served here)", n);
            return n;
        }

        n = fromModelsContextLength();
        if (n != null) {
            log.info("context window {} (from /v1/models context_length — FreeToken/vLLM style)", n);
            return n;
        }

        // Only degrade when a server ANSWERED but does not serve these endpoints. An unreachable
        // drive must still fail here, immediately and by name.
        //
        // My first version of this fallback did not make that distinction, and it turned a clear
        // instant failure into a slow confusing one: with no drive at all the run proceeded on the
        // assumed window and burned its whole turn budget on chat calls that could never succeed,
        // ending in "max turns reached without task_done" with the real cause nowhere in sight.
        // Measured while verifying WSL2. Degrading is right for a DIFFERENT api; it is wrong for a
        // dead endpoint.
        if (!reachable()) {
            throw new RuntimeException("no model server is answering at " + baseUrl
                    + " — check RESEARCHZOSHO_DRIVE, or start one (`researchzosho status` names the fix)");
        }
        log.warn("no server reported a context window at {} — assuming {}. Set RESEARCHZOSHO_CTX if your "
                + "server's window differs; too small only costs an early compaction.", baseUrl, FALLBACK_CTX);
        return FALLBACK_CTX;
    }

    /**
     * FreeToken/vLLM-family servers put the window in {@code /v1/models} as {@code context_length}
     * (or {@code max_model_len}). NOTE the caveat from {@link #contextWindow}: this is the MODEL's
     * maximum, which for a long-context model can be far beyond what the KV cache actually holds —
     * so it is capped at 131072 here, and RESEARCHZOSHO_CTX remains the operator override. Added
     * 2026-08-29 after FreeToken fell through to the 8192 fallback and strangled a whole battery
     * run (out_budget 553, compaction every turn) while serving a 262k model.
     */
    private Integer fromModelsContextLength() {
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models")))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(5));
            if (resp.statusCode() != 200) return null;
            JsonNode m = json.readTree(resp.body()).path("data").path(0);
            int n = m.path("context_length").asInt(m.path("max_model_len").asInt(0));
            return n > 0 ? Math.min(n, 131072) : null;
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (Exception e) {
            return null;
        }
    }

    /** Did anything answer at all? Any HTTP status counts — a 404 is a live server with another API. */
    private boolean reachable() {
        if (bedrock != null) { try { AwsCredentials.get(bedrock.settings().profile()); return true; } catch (RuntimeException e) { return false; } }
        for (String path : new String[]{"/v1/models", "/"}) {
            try {
                HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + path)))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                send(req, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(8));
                return true;
            } catch (Stopping.Requested stop) {
                throw stop;
            } catch (Exception ignored) {
                // try the next probe
            }
        }
        return false;
    }

    /** llama.cpp: {@code /props → default_generation_settings.n_ctx}. The most accurate source. */
    Integer fromLlamaCppProps() {
        try {
            Integer n = propsAt(baseUrl + "/props");
            if (n != null) return n;
            // llama-swap routes by model name and answers /props with "no model id could be identified"; the upstream
            // server's own properties are at /upstream/<model>/props (every `model install` sits behind llama-swap, and
            // the fallback of 8192 had the runner and the chat clearing context at a quarter of the real window, 2026-09-14)
            if (model != null && !model.isBlank()) {
                n = propsAt(baseUrl + "/upstream/" + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20") + "/props");
                if (n != null) { log.info("context window {} (from llama-swap's upstream /props for {})", n, model); return n; }
            }
            return null;
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (Exception e) {
            // A connection-level failure here is worth naming, because on macOS it is usually the
            // Local Network permission rather than the endpoint — but it is not fatal on its own.
            String hint = localNetworkHint(e);
            if (!hint.isEmpty()) log.warn("reaching {}{}", baseUrl, hint);
            return null;
        }
    }

    /** {@code default_generation_settings.n_ctx} from a llama.cpp /props page at {@code url}, or null. */
    private Integer propsAt(String url) throws Exception {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url))).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(10));
        if (resp.statusCode() != 200) return null;
        JsonNode n = json.readTree(resp.body()).path("default_generation_settings").path("n_ctx");
        return n.isInt() ? n.asInt() : null;
    }

    /** Ollama: {@code /api/show → model_info["<family>.context_length"]}, keyed by model family. */
    private Integer fromOllamaShow() {
        try {
            String body = "{\"model\":\"" + model + "\"}";
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/api/show")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(10));
            if (resp.statusCode() != 200) return null;
            JsonNode info = json.readTree(resp.body()).path("model_info");
            var fields = info.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (e.getKey().endsWith(".context_length") && e.getValue().isInt()) {
                    return e.getValue().asInt();
                }
            }
            return null;
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One chat turn. Returns {@code choices[0].message} (the assistant message, which carries
     * {@code tool_calls} when tools were offered). The caller appends it to the conversation
     * verbatim and executes the tool calls.
     */
    /**
     * Where streamed prose goes, when streaming is on. Null (the default) means nobody is watching
     * and requests stay non-streaming.
     *
     * <p>Streaming is opt-in twice: {@code RESEARCHZOSHO_STREAM} must be truthy AND a sink must be set.
     * Config alone is not enough because most callers — `run`, MCP, the batteries — have no screen,
     * and parsing SSE for nobody is pure risk. A sink alone is not enough because the phone and the
     * chat want the same binary to behave identically until the config says otherwise. The loop and
     * every existing call site are untouched: the streamed request is reassembled into exactly the
     * message shape the non-streaming path returns, tool-call deltas included, so downstream code
     * cannot tell which path ran.
     */
    private static volatile Consumer<String> onDelta;
    private static volatile Consumer<String> onThink;

    public static void streamTo(Consumer<String> sink) { streamTo(sink, sink); }

    /**
     * Separate channels for the reply and the thinking. They are different voices: the reply is the
     * model talking TO the person, the reasoning is it talking to itself — and the operator, watching
     * both arrive in identical text, read the second as the injected-prompt bug all over again. The
     * renderer decides how the inner voice looks (dimmed, in a terminal); this layer only keeps the
     * two from arriving indistinguishable.
     */
    public static void streamTo(Consumer<String> sink,
                                Consumer<String> thinking) {
        onDelta = sink;
        onThink = sink == null ? null : thinking;
    }

    private static boolean streamingOn() {
        String v = Config.get("RESEARCHZOSHO_STREAM", "");
        return onDelta != null && !v.isBlank() && !v.equalsIgnoreCase("off")
                && !v.equalsIgnoreCase("false") && !v.equals("0");
    }

    // Whether thinking is SHOWN is the renderer's decision, not this layer's: it owns the screen
    // and can change its mind mid-session (/thinking). This layer only keeps the channels apart.

    public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens) {
        return chat(messages, tools, maxTokens, "required");
    }

    /**
     * One chat turn with an explicit {@code wantToolChoice} so the LOOP can control prose-vs-act per turn:
     * "none" forces prose-only (used for the smallcode planning turn — the model emits the numbered PLAN as
     * text, which `required` would forbid), "required" forces a tool call (the work-turn default — no prose
     * runaways, the original reason for `required`). This is the hybrid: force prose only when we want it
     * (planning), require tools the rest of the time.
     */
    public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice) {
        return chat(messages, tools, maxTokens, wantToolChoice, 0.7, false);
    }

    /**
     * DETERMINISTIC tool-calling turn for OPS remediation — temperature 0 + {@code enable_thinking:false}.
     * The coding loop wants temp 0.7 (greedy degrades creative code gen, RESET §10.4), but ops remediation
     * is the opposite task: FOLLOW a matched fix procedure exactly. At 0.7 the 9B improvised its own SQL and
     * ignored the card (postgres read-only fixed 0/3); the measured refstack driver ran remediation at temp 0
     * and followed the card (5/5). Same shape as {@link #chat}, only the sampling differs.
     */
    public ObjectNode chatOps(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice) {
        return chat(messages, tools, maxTokens, wantToolChoice, 0.0, true);
    }

    private ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice,
                            double temperature, boolean noThink) {
        // SANITIZE history before every send: llama.cpp --jinja re-parses EVERY historical tool_call's
        // `arguments` while rendering the chat template, so a single malformed one (the model emitted bad
        // JSON that the output parser let through, or compaction rewrote args into invalid JSON) makes EVERY
        // subsequent request 500 with "Failed to parse tool call arguments" — poisoning the whole run into a
        // spin to the turn cap (battery56 py-n1-pass2: 792 such 500s). Repairing the args to valid JSON
        // prevents the 500 at the source (proven against the live drive: malformed history → 500, repaired
        // history → 200). Mutates in place, which also heals the loop's own history for later turns.
        sanitizeToolCallArgs(messages);
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        String toolChoice = "none";
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            toolChoice = wantToolChoice;
            body.put("tool_choice", toolChoice);
        }
        body.put("max_tokens", maxTokens);
        // RESEARCHZOSHO_TEMP=none omits the field entirely — claude-fable-5 400s on ANY temperature
        // ("deprecated for this model", measured 2026-08-29); a number overrides the caller's
        // choice for engines whose default sampling is better left alone.
        String tempCfg = Config.get("RESEARCHZOSHO_TEMP");
        if ("none".equalsIgnoreCase(tempCfg)) {
            // omitted
        } else if (tempCfg != null && !tempCfg.isBlank()) {
            body.put("temperature", Double.parseDouble(tempCfg));
        } else {
            body.put("temperature", temperature); // 0.7 for the coding loop (greedy degrades it, RESET §10.4);
                                                  // ops remediation passes 0.0 to follow a fix procedure exactly
        }
        // Operator-set template kwargs, merged into every request. This is the per-drive twin of
        // llama.cpp's --chat-template-kwargs: cz-chat runs the 27B with reasoning_effort=low at the
        // CONTAINER, but an engine with no such flag (FreeToken 0.1.2) leaves a reasoning model
        // thinking at full length through every loop turn — measured 2026-08-29: the same weights
        // spent a 25-turn budget mid-write. An explicit noThink turn still wins the merge.
        String tk = Config.get("RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS");
        if (tk != null && !tk.isBlank()) {
            try {
                JsonNode kw = json.readTree(tk);
                if (kw.isObject()) body.set("chat_template_kwargs", kw.deepCopy());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS is not a JSON object: " + tk);
            }
        }
        if (noThink) {
            ObjectNode kw = body.has("chat_template_kwargs")
                    ? (ObjectNode) body.get("chat_template_kwargs")
                    : body.putObject("chat_template_kwargs");
            kw.put("enable_thinking", false);
        }
        if (bedrock != null) {
            Duration limit = limitFor(body.toString().length(), maxTokens);
            log.info("drive → bedrock {} in {}: {} msgs, max_tokens={}, tools={}, tool_choice={}, time limit {} s", model, bedrock.settings().region(), messages.size(), maxTokens, tools == null ? 0 : tools.size(), toolChoice, limit.toSeconds());
            long t0 = System.nanoTime();
            try { ObjectNode msg = accept(bedrock.chat(model, body, limit), "bedrock"); learnPace(t0); return msg; }
            catch (Bedrock.Refused | AwsCredentials.Unavailable e) { throw new IllegalStateException(e.getMessage(), e); }
        }
        body.put("stream", streamingOn());
        try {
            String payload = json.writeValueAsString(body);
            if (streamingOn()) {
                ObjectNode streamed = chatStreaming(payload, limitFor(payload.length(), maxTokens));
                if (streamed != null) return streamed;
                // A failed stream falls through to the plain request rather than failing the turn:
                // streaming is presentation, and presentation must never cost an answer.
                body.put("stream", false);
                payload = json.writeValueAsString(body);
            }
            // Log the REAL outgoing request shape so we never have to infer which knob applied (L11).
            Duration limit = limitFor(payload.length(), maxTokens);
            log.info("drive → {} msgs, {} req-chars, max_tokens={}, tools={}, tool_choice={}, time limit {} s",
                    messages.size(), payload.length(), maxTokens,
                    tools == null ? 0 : tools.size(), toolChoice, limit.toSeconds());
            long t0 = System.nanoTime();
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(limit)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            // Retry a 5xx: llama.cpp (--jinja) returns HTTP 500 when the MODEL emits a malformed tool call
            // (bad JSON args — an unescaped quote/newline), and that's the model's sampling, not a real
            // server fault. At temp 0.7 a re-send re-samples and usually produces valid JSON. (A single bad
            // tool call must NOT abort the run — the loop also catches, as a backstop.)
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), limit);
            for (int attempt = 2; attempt <= 4 && resp.statusCode() >= 500; attempt++) {
                String b = resp.body();
                log.warn("drive HTTP {} (retry {}/4): {}", resp.statusCode(), attempt,
                        b.length() > 200 ? b.substring(0, 200) : b);
                try { Thread.sleep(300L); } catch (InterruptedException ignored) { }
                t0 = System.nanoTime();
                resp = send(req, HttpResponse.BodyHandlers.ofString(), limit);
            }
            if (resp.statusCode() != 200) {
                Declined filtered = filteredRequest(resp.statusCode(), resp.body());
                if (filtered != null) throw filtered;   // the service's content filter stopped the request: a decline, never an outage to retry
                // RECOVERY (A): a 5xx whose body says the MODEL's tool call wouldn't parse is a known
                // llama.cpp --jinja bug (ggml-org #20359/#22948): the grammar tool-call parser emits/rejects
                // invalid JSON args for code-ish payloads (mixed quote escaping, unterminated strings),
                // amplified at sub-Q5 quants. Re-sampling deterministically re-fails (it re-emits the same
                // payload), so the run spins to its turn cap. The fix is to re-request the SAME turn in
                // TEXT mode (tool_choice="none" → no grammar parse → no 500) and SALVAGE the intended call
                // from the prose. parseArgs downstream is already lenient; we only need name + args here.
                if (looksLikeToolCallParseError(resp.body())) {
                    ObjectNode recovered = recoverMalformedToolCall(messages, tools, maxTokens);
                    if (recovered != null) return recovered;
                }
                throw new IllegalStateException("drive HTTP " + resp.statusCode() + ": " + resp.body());
            }
            ObjectNode msg = accept(json.readTree(resp.body()), resp.body());
            learnPace(t0);
            return msg;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("chat() failed against " + baseUrl, e);
        }
    }

    /**
     * The model server's own word that the model declined, or null. OpenAI-style servers (and the hosted ones behind that API, Gemini's
     * among them) say {@code finish_reason: content_filter} or put the model's words in {@code message.refusal}; a proxy in front of
     * Anthropic's API may pass its {@code refusal} stop reason through as the finish reason; Bedrock's Converse, as {@code Converse.response}
     * maps it, says {@code content_filter} with the native stop reason beside it, where a guardrail
     * on the person's account is {@code guardrail_intervened}. llama.cpp and llama-swap send none of these: a local model's decline is
     * plain text, read by {@link DeclineJudge}.
     */
    public static Declined.How declineSignal(JsonNode completion) {
        JsonNode c0 = completion.path("choices").path(0);
        JsonNode refusal = c0.path("message").path("refusal");
        if (refusal.isTextual() && !refusal.asText().isBlank()) return Declined.How.REFUSAL;
        String finish = c0.path("finish_reason").asText("");
        if ("guardrail_intervened".equals(c0.path("native_finish_reason").asText(""))) return Declined.How.GUARDRAIL;
        if ("content_filter".equals(finish)) return Declined.How.FILTERED;
        if ("refusal".equals(finish)) return Declined.How.REFUSAL;
        return null;
    }

    /**
     * The service's content filter stopped the request before the model answered, or null: Azure OpenAI answers HTTP 400 with
     * {@code error.code content_filter} (its inner error {@code ResponsibleAIPolicyViolation}) when the prompt trips its filter.
     */
    Declined filteredRequest(int status, String body) {
        if (status != 400 || body == null || body.isBlank()) return null;
        try {
            JsonNode e = json.readTree(body).path("error");
            if (!"content_filter".equals(e.path("code").asText("")) && !"ResponsibleAIPolicyViolation".equals(e.path("innererror").path("code").asText(""))) return null;
        } catch (Exception notJson) { return null; }
        log.warn("drive ← the service's content filter stopped the request to {}", model);
        return new Declined(model, "", Declined.How.FILTERED_REQUEST);
    }

    /** What the model said with a decline: its refusal words when it gave them, else the reply's text. */
    static String declinedWords(JsonNode completion) {
        JsonNode msg = completion.path("choices").path(0).path("message");
        String refusal = msg.path("refusal").asText("");
        return !refusal.isBlank() ? refusal : msg.path("content").asText("");
    }

    /** Raise the server's signal of a decline as a {@link Declined}: the model's words and its name, never a second request. */
    private void raiseDecline(JsonNode completion) {
        Declined.How how = declineSignal(completion);
        if (how == null) return;
        log.warn("drive ← the model {} declined ({})", model, how);
        throw new Declined(model, declinedWords(completion), how);
    }

    /** A chat completion taken in: its message handed back, and what it cost counted. */
    private ObjectNode accept(JsonNode parsed, String raw) {
        {
            // Usage, when the server reports it. On a metered drive this line IS the meter:
            // grep 'usage ←' over a run log and sum. Local llama.cpp reports it too — harmless.
            JsonNode u = parsed.path("usage");
            if (u.isObject()) {
                log.info("usage ← prompt {} completion {} total {}",
                        u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(),
                        u.path("total_tokens").asInt());
                SESSION_PROMPT_TOKENS.addAndGet(u.path("prompt_tokens").asLong(0));
                SESSION_COMPLETION_TOKENS.addAndGet(u.path("completion_tokens").asLong(0));
                LAST_USAGE.set(new long[]{u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0)});
            } else LAST_USAGE.set(null);
            // the model declined: said once, where the caller can say it; the reply is not handed on as if it were an answer
            raiseDecline(parsed);
            JsonNode msg = parsed.path("choices").path(0).path("message");
            if (!msg.isObject()) {
                throw new IllegalStateException("no choices[0].message in response: " + raw);
            }
            return (ObjectNode) msg;
        }
    }

    /**
     * A DETERMINISTIC, no-thinking prose completion for classification/localization steps — temperature 0
     * and {@code enable_thinking:false}. This 9B is a reasoning model: left thinking-on it fills
     * {@code reasoning_content} and varies its surface answer run-to-run, so a "name the single root-cause
     * service" step misses intermittently (measured ~1 in 3 on the refstack localizer). A classifier wants
     * the SAME answer for the same evidence — unlike the creative loop path, which stays temp 0.7. Returns
     * the message content (falling back to reasoning_content), or "" on any failure.
     */
    /** Whether a typed decision can be read from this drive: token probabilities under a grammar, which llama.cpp gives and Bedrock does not. */
    public boolean givesTokenProbabilities() { return bedrock == null && Judge.grammarWorks(decisionKey()); }

    /** Which drive a typed decision goes to, for remembering that its server does not keep to a grammar ({@link Judge#grammarWorks}). */
    public String decisionKey() { return baseUrl + " " + model; }

    /** A limit for every call of this client, when it is not the drive's own ({@link #driveTimeout}); null: the drive's own. */
    private volatile Duration callLimit = null;

    /**
     * A total limit for every call of this client instead of the drive's own: the whole answer must have arrived within it, or the call is
     * given up, its connection closed, and it fails as a request that did not get through.
     */
    public DriveClient timeLimit(Duration limit) { this.callLimit = limit; return this; }

    /** The pace a drive is given time for until three of its answers were measured: ten tokens a second. */
    static final double DEFAULT_FLOOR = 10;

    /**
     * How fast a drive's answers came, in tokens a second, from the last twenty calls that came back with enough tokens to measure. The
     * slowest pace a call is given time for is half the median of them.
     */
    static final class Pace {
        private final ArrayDeque<Double> rates = new ArrayDeque<>();
        synchronized void add(double tokensPerSecond) { if (tokensPerSecond > 0) { rates.addLast(tokensPerSecond); while (rates.size() > 20) rates.removeFirst(); } }
        synchronized double floor() {
            if (rates.size() < 3) return DEFAULT_FLOOR;
            double[] a = rates.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            double median = a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
            return Math.max(1, median / 2);
        }
    }

    /** Each drive's pace, by its address and model, for every client of it in this process. */
    private static final Map<String, Pace> PACES = new ConcurrentHashMap<>();
    private Pace pace() { return PACES.computeIfAbsent(decisionKey(), k -> new Pace()); }

    /**
     * How long one call may take in all: the limit set on this client when there is one; else the time reading the prompt ({@code
     * promptChars}, at about three characters a token, read twenty times as fast as tokens are written) and writing {@code maxTokens} take
     * at the drive's slowest pace ({@link Pace}), and never less than RESEARCHZOSHO_DRIVE_TIMEOUT.
     */
    Duration limitFor(int promptChars, int maxTokens) {
        Duration set = callLimit;
        return set != null ? set : limitFor(driveTimeout(), promptChars, maxTokens, pace().floor());
    }

    /** The rule itself: {@code floor} is the slowest pace in tokens a second. */
    static Duration limitFor(Duration least, int promptChars, int maxTokens, double floor) {
        double seconds = Math.max(0, promptChars) / 3.0 / (20 * floor) + Math.max(0, maxTokens) / floor;
        long s = (long) Math.ceil(seconds);
        return s > least.toSeconds() ? Duration.ofSeconds(s) : least;
    }

    /** A call that came back: its pace goes into the drive's, for the next calls' limits. An answer of a few tokens says little of the pace, and is left out. */
    private void learnPace(long startedNanos) {
        long[] u = LAST_USAGE.get();
        double seconds = (System.nanoTime() - startedNanos) / 1e9;
        if (u == null || u[1] < 32 || seconds <= 0) return;
        pace().add(u[1] / seconds);
    }

    /** The limit of a decision: the one set for decisions, else the one set on this client, else the rule of {@link #limitFor}. */
    private Duration decisionLimitFor(int promptChars, int maxTokens) { Duration d = decisionLimit; return d != null ? d : limitFor(promptChars, maxTokens); }

    /** The other side, as a sentence names it when it did not answer. */
    private String who() { return "the model server at " + baseUrl; }

    /**
     * One request to the model server, waited for under the stop of the run it is for and a total limit ({@link Stopping#send}): a stop
     * ends it within a second, and a server that took it and never answers is given up at the limit. Each is logged.
     */
    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> body, Duration limit) throws IOException, InterruptedException {
        Semaphore slot = req.uri().getPath().endsWith("/chat/completions") ? takeSlot() : null;
        try { return Stopping.send(http, req, body, limit, who()); }
        catch (HttpTimeoutException t) { log.warn("drive ← {}", t.getMessage()); throw t; }
        catch (Stopping.Requested stop) { log.info("drive ← the request to {} was given up and its connection closed, because the run was stopped", baseUrl); throw stop; }
        finally { if (slot != null) slot.release(); }
    }

    /** What a llama.cpp server says of itself that changes how the library sends to it: how many requests it serves at once ({@code /props} total_slots, 0 when it does not say). */
    record Served(int slots) { static final Served UNKNOWN = new Served(0); }
    private static final Map<String, Served> SERVED = new ConcurrentHashMap<>();
    /** When a server that did not answer is asked again (System.nanoTime), so a server that hangs costs one short wait a minute, not one a request. */
    private static final Map<String, Long> ASK_AGAIN = new ConcurrentHashMap<>();
    /** A llama.cpp server answers these pages at once; one that does not is taken as saying nothing until it is asked again. */
    private static final Duration PROBE = Duration.ofSeconds(2);
    private static final Map<String, Semaphore> SLOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SAID_WAITING = new ConcurrentHashMap<>();
    /** The chat requests this thread is inside of: a stream holds its slot while it is read, and the requests it makes meanwhile take none. */
    private static final ThreadLocal<int[]> HELD = ThreadLocal.withInitial(() -> new int[1]);

    /** What this client's server says of itself, asked once in this process (again later when it could not be reached). */
    Served served() {
        if (bedrock != null) return Served.UNKNOWN;
        String key = baseUrl + " " + (model == null ? "" : model);
        Served s = SERVED.get(key);
        if (s != null) return s;
        Long again = ASK_AGAIN.get(key);
        if (again != null && System.nanoTime() < again) return Served.UNKNOWN;
        try {
            JsonNode props = pageOf("/props");
            s = new Served(props == null ? 0 : props.path("total_slots").asInt(0));
            SERVED.put(key, s);
            if (s.slots() > 0) log.info("the model server at {} serves {} request{} at once; the library sends it no more than that", baseUrl, s.slots(), s.slots() == 1 ? "" : "s");
            return s;
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (Exception e) {
            ASK_AGAIN.put(key, System.nanoTime() + Duration.ofMinutes(1).toNanos());
            return Served.UNKNOWN;
        }
    }

    /** A llama.cpp page as JSON: the server's own, or behind llama-swap its upstream's for this model; null when neither is there (another kind of server). */
    private JsonNode pageOf(String path) throws Exception {
        HttpResponse<String> r = send(auth(HttpRequest.newBuilder(URI.create(baseUrl + path))).timeout(PROBE).GET().build(), HttpResponse.BodyHandlers.ofString(), PROBE);
        if (r.statusCode() == 200) return json.readTree(r.body());
        if (model == null || model.isBlank()) return null;
        r = send(auth(HttpRequest.newBuilder(URI.create(baseUrl + "/upstream/" + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20") + path))).timeout(PROBE).GET().build(), HttpResponse.BodyHandlers.ofString(), PROBE);
        return r.statusCode() == 200 ? json.readTree(r.body()) : null;
    }

    /**
     * A place among the requests the server serves at once, waited for here rather than in the server's queue: a server shared with
     * other programs serves theirs between the library's, and the wait does not count against a request's time limit. Null when the
     * server does not say how many it serves, or this thread is already inside a request that holds one.
     */
    private Semaphore takeSlot() throws InterruptedException {
        if (HELD.get()[0] > 0) return null;
        int n = served().slots();
        if (n <= 0) return null;
        String key = baseUrl + " " + (model == null ? "" : model);
        Semaphore s = SLOTS.computeIfAbsent(key, k -> new Semaphore(n, true));
        if (!s.tryAcquire()) {
            long now = System.nanoTime(), last = SAID_WAITING.getOrDefault(key, now - Duration.ofMinutes(2).toNanos());
            if (now - last > Duration.ofMinutes(1).toNanos()) {   // once a minute at most: with several workers every request waits
                SAID_WAITING.put(key, now);
                log.info("requests wait their turn for the model server at {}: it serves {} at once", baseUrl, n);
            }
            while (!s.tryAcquire(1, TimeUnit.SECONDS)) Stopping.check();
        }
        return s;
    }

    /** A shorter limit for the decisions asked inside a request a person or a program waits on; null: the drive's own. */
    private volatile Duration decisionLimit = null;
    public DriveClient decisionTimeout(Duration limit) { this.decisionLimit = limit; return this; }

    /**
     * One chat-completions request as it stands, the whole response back: the seat for a typed decision ({@link Judge}). Not for Bedrock,
     * which carries no grammar or token probabilities. A request that got no answer throws an IOException, an error status in place of a
     * reply among them ({@link #noAnswer}); the server's refusal of the request's shape (400, 404 …) an IllegalStateException.
     */
    public JsonNode postChat(ObjectNode body) throws Exception {
        if (!body.has("model")) body.put("model", model);
        if (bedrock != null) throw new IllegalStateException("a typed decision needs token probabilities, which Bedrock does not give");
        String payload = json.writeValueAsString(body);
        Duration limit = decisionLimitFor(payload.length(), body.path("max_tokens").asInt(0));
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                .timeout(limit).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        HttpResponse<String> resp = sendWaitingWhileLoading(req, limit);
        if (resp.statusCode() != 200) {
            String said = "HTTP " + resp.statusCode() + ": " + (resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
            // an error in place of a reply is no answer, as a request that did not get through; the server's word about the request's shape stays what it was
            IOException none = Judge.refusesTheRequest(said) ? null : noAnswer(resp.statusCode(), who() + " answered HTTP " + resp.statusCode() + ": " + serverSaid(resp.body()));
            if (none != null) throw none;
            throw new IllegalStateException(said);
        }
        return json.readTree(resp.body());
    }

    public String model() { return model; }

    public String classify(ArrayNode messages, int maxTokens) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("max_tokens", maxTokens);
        decisionSettings(body);
        body.put("stream", false);
        CLASSIFY_PROBLEM.set("");
        CLASSIFY_UNANSWERED.remove();
        Duration limit = decisionLimitFor(body.toString().length(), maxTokens);
        if (bedrock != null) {
            try {
                ObjectNode msg = accept(bedrock.chat(model, body, limit), "bedrock");
                String content = msg.path("content").asText("");
                if (content.isBlank() && !msg.path("reasoning_content").asText("").isBlank()) CLASSIFY_PROBLEM.set("the model thought before answering and gave no answer");
                return content.isBlank() ? msg.path("reasoning_content").asText("") : content;
            } catch (Declined | Stopping.Requested d) { throw d; }   // a decline is the model's answer, never an empty one; a stop ends the call
            catch (RuntimeException e) {
                log.warn("classify() failed against bedrock: {}", e.getMessage());
                CLASSIFY_PROBLEM.set("the server answered: " + e.getMessage());
                IOException none = bedrockUnanswered(e);
                if (none != null) CLASSIFY_UNANSWERED.set(none);
                return "";
            }
        }
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(limit).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            long t0 = System.nanoTime();
            HttpResponse<String> resp = sendWaitingWhileLoading(req, limit);
            try { JsonNode cu = json.readTree(resp.body()).path("usage"); LAST_USAGE.set(cu.isObject() ? new long[]{cu.path("prompt_tokens").asLong(0), cu.path("completion_tokens").asLong(0)} : null); } catch (Exception e) { LAST_USAGE.set(null); }
            if (resp.statusCode() != 200) {
                Declined filtered = filteredRequest(resp.statusCode(), resp.body());
                if (filtered != null) throw filtered;   // a decline, never an empty answer
                String said = resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body();
                log.warn("classify HTTP {}: {}", resp.statusCode(), said);
                CLASSIFY_PROBLEM.set("the server answered HTTP " + resp.statusCode() + ": " + said.replaceAll("\\s+", " ").strip());
                // an error in place of a reply is no answer: the model said nothing about the text
                IOException none = noAnswer(resp.statusCode(), who() + " answered HTTP " + resp.statusCode() + ": " + serverSaid(resp.body()));
                if (none != null) CLASSIFY_UNANSWERED.set(none);
                return "";
            }
            JsonNode parsed = json.readTree(resp.body());
            learnPace(t0);
            raiseDecline(parsed);
            JsonNode msg = parsed.path("choices").path(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank() && !msg.path("reasoning_content").asText("").isBlank()) CLASSIFY_PROBLEM.set("the model thought before answering and gave no answer");
            if (content.isBlank()) content = msg.path("reasoning_content").asText("");
            return content;
        } catch (Declined d) {
            throw d;   // a decline is the model's answer, never an empty one
        } catch (Stopping.Requested stop) {
            throw stop;   // a person stopped the run: no empty answer, the run ends
        } catch (Exception e) {
            log.warn("classify() failed against {}: {}", baseUrl, e.toString());
            CLASSIFY_PROBLEM.set("the server could not be reached or did not answer in time (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
            if (e instanceof IOException io && !(e instanceof JsonProcessingException)) CLASSIFY_UNANSWERED.set(io);
            return "";
        }
    }

    /**
     * The model server answered with an error status that every other request would get too, in place of a reply: 401 or 403 (a key it
     * does not take), 404 (no such model or address), 429 (too many requests), 502, 503 (the model is still loading, or the server behind a
     * proxy is down), 504. It is no answer, and a read treats it as a server that cannot be reached, so it is a {@link ConnectException}.
     */
    public static final class ErrorStatus extends ConnectException {
        public final int status;
        ErrorStatus(int status, String message) { super(message); this.status = status; }
    }

    /**
     * Why an error status is no answer at all ({@code message} says it), or null when it is the server's answer about this request (a
     * 400): an {@link ErrorStatus} for a status every request would get, an IOException for any other 5xx, which is about this request.
     */
    static IOException noAnswer(int status, String message) {
        if (status == 401 || status == 403 || status == 404 || status == 429 || status == 502 || status == 503 || status == 504) return new ErrorStatus(status, message);
        return status >= 500 ? new IOException(message) : null;
    }

    /**
     * Why a Bedrock call got no answer at all, or null when Bedrock answered about this request. No AWS sign-in: no request can go out, as
     * for a server out of reach. A refusal every request would get ({@link #noAnswer}). Status 0: the request went out and nothing came
     * back, or it could not go out; kept as the cause, so that a slow answer is not taken for a server out of reach.
     */
    static IOException bedrockUnanswered(RuntimeException e) {
        if (e instanceof AwsCredentials.Unavailable) return new ConnectException(e.getMessage());
        if (!(e instanceof Bedrock.Refused r)) return null;
        return r.status == 0 ? new IOException(e.getMessage(), e) : noAnswer(r.status, e.getMessage());
    }

    /** What an error answer's body says: its error message when it is JSON that has one, else the body, cut to 200 characters. */
    static String serverSaid(String body) {
        String b = body == null ? "" : body;
        try {
            JsonNode n = SETTINGS_JSON.readTree(b);
            JsonNode m = n.path("error").isTextual() ? n.path("error") : n.path("error").path("message").isTextual() ? n.path("error").path("message") : n.path("message");
            if (m.isTextual() && !m.asText().isBlank()) b = m.asText();
        } catch (Exception notJson) { /* the body as it is */ }
        b = b.replaceAll("\\s+", " ").strip();
        return b.length() > 200 ? b.substring(0, 200) : b;
    }

    /**
     * The waits before asking again while the model server answers 503, which a server that is still loading its model does for its first
     * minute or so: about a minute in all. A wait that would end past the call's own limit is not taken.
     */
    static volatile long[] loadingWaitsMs = {5_000, 10_000, 20_000, 30_000};

    /** One request, read whole; a 503 is waited out ({@link #loadingWaitsMs}) and the request sent again. A stop ends the wait. */
    private HttpResponse<String> sendWaitingWhileLoading(HttpRequest req, Duration limit) throws IOException, InterruptedException {
        long end = System.nanoTime() + limit.toNanos();
        HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), limit);
        long[] waits = loadingWaitsMs;
        for (int i = 0; i < waits.length && resp.statusCode() == 503; i++) {
            if (System.nanoTime() + waits[i] * 1_000_000L >= end) break;
            log.warn("The model server at {} is not ready yet. It answered HTTP 503: {}. The library waits {} and asks again ({} of {}).",
                    baseUrl, serverSaid(resp.body()), waits[i] >= 2000 ? waits[i] / 1000 + " seconds" : waits[i] + " ms", i + 1, waits.length);
            Stopping.sleep(waits[i]);
            resp = send(req, HttpResponse.BodyHandlers.ofString(), limit);
        }
        return resp;
    }

    /** What went wrong with the last {@link #classify} on this thread, in words a person reads; "" when it answered. */
    private static final ThreadLocal<String> CLASSIFY_PROBLEM = ThreadLocal.withInitial(() -> "");
    public static String lastClassifyProblem() { return CLASSIFY_PROBLEM.get(); }

    /**
     * Why the last {@link #classify} on this thread got no answer at all: the server could not be reached, it took the request and did
     * not answer in time, or it answered with an error in place of a reply ({@link #noAnswer}; an {@link ErrorStatus} when every request
     * would get it). Null when an answer came, even an empty one, or a 400 about the request itself.
     */
    private static final ThreadLocal<IOException> CLASSIFY_UNANSWERED = new ThreadLocal<>();
    public static IOException lastClassifyUnanswered() { return CLASSIFY_UNANSWERED.get(); }
    /** Forget why an earlier call on this thread got no answer, before a call whose own outcome is to be read. */
    public static void forgetUnanswered() { CLASSIFY_UNANSWERED.remove(); }
    static void clearClassifyProblem() { CLASSIFY_PROBLEM.set(""); }

    private static final ObjectMapper SETTINGS_JSON = new ObjectMapper();

    /**
     * The settings of a decision the library asks of the model (a one-word answer, a typed judge), the same the research drive uses:
     * temperature 0 unless {@code RESEARCHZOSHO_TEMP} says otherwise ({@code none} leaves the field out, for a hosted model that refuses
     * any temperature; a number is used as it is); the template settings set for the drive ({@code RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS}),
     * with thinking off on top, as every no-thinking turn of the drive has it.
     */
    public static void decisionSettings(ObjectNode body) {
        String temp = Config.get("RESEARCHZOSHO_TEMP");
        if ("none".equalsIgnoreCase(temp == null ? "" : temp.strip())) body.remove("temperature");
        else if (temp != null && !temp.isBlank()) {
            try { body.put("temperature", Double.parseDouble(temp.strip())); } catch (NumberFormatException e) { body.put("temperature", 0.0); }
        } else body.put("temperature", 0.0);
        ObjectNode kw = SETTINGS_JSON.createObjectNode();
        String tk = Config.get("RESEARCHZOSHO_DRIVE_TEMPLATE_KWARGS");
        if (tk != null && !tk.isBlank()) {
            try { JsonNode k = SETTINGS_JSON.readTree(tk); if (k.isObject()) kw.setAll((ObjectNode) k); } catch (Exception notJson) { /* the drive's own turns say so */ }
        }
        kw.put("enable_thinking", false);
        body.set("chat_template_kwargs", kw);
    }

    /**
     * Ensure every tool_call's {@code function.arguments} in the history is VALID JSON, so the --jinja
     * template can re-render it without 500ing. Empty → "{}"; malformed → lenient-parse-and-reserialize,
     * else a valid placeholder that preserves a slice of the raw for debuggability. Mutates in place.
     */
    private void sanitizeToolCallArgs(ArrayNode messages) {
        if (messages == null) return;
        for (JsonNode m : messages) {
            JsonNode tcs = m.path("tool_calls");
            if (!tcs.isArray()) continue;
            for (JsonNode tc : tcs) {
                JsonNode fn = tc.path("function");
                if (!fn.isObject() || !fn.path("arguments").isTextual()) continue;
                String args = fn.path("arguments").asText();
                if (args.isBlank()) { ((ObjectNode) fn).put("arguments", "{}"); continue; }
                try { json.readTree(args); continue; } catch (Exception malformed) { /* repair below */ }
                ((ObjectNode) fn).put("arguments", repairJson(args));
                log.warn("sanitized malformed tool_call args in history ({} chars) — prevented --jinja 500",
                        args.length());
            }
        }
    }

    /** Best-effort: lenient-parse then re-serialize to strict JSON; else a valid placeholder. Never throws. */
    private String repairJson(String raw) {
        try {
            JsonNode n = LENIENT.readTree(raw);
            if (n.isObject() || n.isArray()) return json.writeValueAsString(n);
        } catch (Exception ignored) { }
        try {
            return json.writeValueAsString(json.createObjectNode()
                    .put("_unparsed", raw.length() > 200 ? raw.substring(0, 200) : raw));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Does this 5xx body signal that the MODEL's emitted tool call failed to parse (vs a real fault)? */
    private static boolean looksLikeToolCallParseError(String body) {
        if (body == null) return false;
        String b = body.toLowerCase();
        return b.contains("tool call") || b.contains("tool_call")
                || (b.contains("parse") && b.contains("arguments"));
    }

    /**
     * RECOVERY for the --jinja malformed-tool-call 500: re-request the same turn in TEXT mode
     * (tool_choice="none", so the server does NO grammar tool parse and cannot 500) with a nudge to
     * re-emit the intended action as one fenced json block, then salvage it into a normal tool_calls
     * message the loop executes unchanged. Returns null on any failure (caller then throws as before).
     */
    private ObjectNode recoverMalformedToolCall(ArrayNode messages, ArrayNode tools, int maxTokens) {
        try {
            log.info("drive 500 on tool-call parse — attempting text-mode recovery (tools omitted)");
            ArrayNode recov = messages.deepCopy();
            recov.addObject().put("role", "user").put("content",
                    "Your previous tool call could not be parsed (malformed JSON arguments). Re-emit your "
                    + "intended action as ONE fenced json block and nothing else:\n```json\n"
                    + "{\"name\": \"<tool_name>\", \"arguments\": { ... }}\n```\n"
                    + "Every string value MUST be valid JSON: escape each embedded double-quote as \\\" and "
                    + "each newline as \\n. If the action writes a large file, write a SMALLER piece now "
                    + "(you can append the rest with follow-up edits).");
            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.set("messages", recov);
            // Deliberately send NO `tools` and NO `tool_choice`: with `tools` present, llama.cpp --jinja keeps
            // the tool grammar active and 500s AGAIN on the model's output (even at tool_choice="none") — which
            // silently re-failed the whole recovery (battery55 py-n2: 20 parse-500s, 0 recoveries). Without
            // tools there is no grammar to trip, so the server returns plain prose we salvage the call from.
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.7);
            body.put("stream", false);
            String payload = json.writeValueAsString(body);
            Duration limit = limitFor(payload.length(), maxTokens);
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(limit).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), limit);
            if (resp.statusCode() != 200) {
                log.warn("recovery re-request returned HTTP {} — cannot salvage", resp.statusCode());
                return null;
            }
            JsonNode msg = json.readTree(resp.body()).path("choices").path(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank()) content = msg.path("reasoning_content").asText("");
            ObjectNode synth = salvageToolCall(content);
            if (synth != null) log.info("recovered malformed tool call via text-mode re-request");
            else log.warn("recovery re-request succeeded (HTTP 200) but no tool call salvaged from prose");
            return synth;
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (Exception e) {
            log.warn("tool-call recovery attempt failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Turn re-emitted prose ({@code {"name":..,"arguments":{..}}}, possibly fenced) into an assistant
     * message carrying a single {@code tool_calls} entry — the exact shape the loop consumes. Args are
     * stringified (the loop's parseArgs re-parses them, leniently). Null if no usable call is present.
     */
    private ObjectNode salvageToolCall(String content) {
        if (content == null || content.isBlank()) return null;
        String s = content.strip();
        int fence = s.indexOf("```");
        if (fence >= 0) {                              // strip a leading ```json fence + its closer
            int nl = s.indexOf('\n', fence);
            if (nl > 0) s = s.substring(nl + 1);
            int close = s.lastIndexOf("```");
            if (close >= 0) s = s.substring(0, close);
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        s = s.substring(a, b + 1);
        JsonNode node;
        try { node = json.readTree(s); }
        catch (Exception e) { try { node = LENIENT.readTree(s); } catch (Exception e2) { return null; } }
        String name = node.path("name").asText("");
        if (name.isBlank()) return null;
        JsonNode argsNode = node.path("arguments");
        String argsStr = (argsNode.isObject() || argsNode.isArray()) ? argsNode.toString()
                : argsNode.isTextual() ? argsNode.asText() : "{}";
        ObjectNode out = json.createObjectNode();
        out.put("role", "assistant");
        out.putNull("content");
        ObjectNode call = out.putArray("tool_calls").addObject();
        call.put("id", "recovered_1");
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", name);
        fn.put("arguments", argsStr);
        return out;
    }

    /**
     * One streamed chat request, reassembled into the same {@code choices[0].message} object the
     * non-streaming path returns. Null on any other failure — the caller falls back to a plain request. A stop and the call's time
     * limit end it as they end a plain request, and nothing is asked again.
     *
     * <p>The fiddly part is tool calls: the OpenAI stream format delivers them as DELTAS — a first
     * chunk carrying {@code index}, {@code id} and the function {@code name}, then chunks each
     * carrying a fragment of {@code arguments} — and the fragments are raw string pieces of a JSON
     * document, split anywhere. They are concatenated per index and only parsed by whoever consumes
     * the final message, exactly as with the non-streamed shape. Prose deltas go to the sink as they
     * arrive; tool-call deltas do not, because half a JSON argument is noise on a screen.
     */
    private ObjectNode chatStreaming(String payload, Duration limit) {
        var sink = onDelta;
        if (sink == null) return null;
        Semaphore slot;
        try { slot = takeSlot(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        HELD.get()[0]++;
        try { return readStream(sink, payload, limit); }
        finally { HELD.get()[0]--; if (slot != null) slot.release(); }
    }

    private ObjectNode readStream(Consumer<String> sink, String payload, Duration limit) {
        try {
            long t0 = System.nanoTime();
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(limit)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<InputStream> resp = send(req, HttpResponse.BodyHandlers.ofInputStream(), limit);
            if (resp.statusCode() != 200) { resp.body().close(); return null; }
            // the answer arrives in pieces: the reading ends when the run is stopped or the call's limit has passed, whatever the server does
            Duration left = limit.minusNanos(System.nanoTime() - t0);
            try (var guard = Stopping.guard(resp.body(), left.isNegative() || left.isZero() ? Duration.ofSeconds(1) : left);
                 var reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                try { return streamed(reader, sink); }
                catch (IOException e) { guard.rethrow(who()); throw e; }
                finally { guard.rethrow(who()); }
            }
        } catch (Declined | Stopping.Requested d) {
            throw d;   // a decline is the answer: asking again without the stream would be asking twice; a stop ends the call
        } catch (HttpTimeoutException t) {
            log.warn("drive ← {}", t.getMessage());
            throw new RuntimeException("chat() failed against " + baseUrl, t);   // a call that ran out of time is not asked again without the stream
        } catch (Exception e) {
            log.warn("streaming failed ({}), falling back to a plain request", e.toString());
            return null;
        }
    }

    /** A streamed answer read to its end and put together as the non-streaming path returns it. */
    private ObjectNode streamed(BufferedReader reader, Consumer<String> sink) throws IOException {
        var content = new StringBuilder();
        var toolNames = new TreeMap<Integer, String>();
        var toolIds = new TreeMap<Integer, String>();
        var toolArgs = new TreeMap<Integer, StringBuilder>();
        var summaries = new TreeMap<Integer, SummaryStream>();
        String finish = "";
        var refusal = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) break;
            JsonNode choice0 = json.readTree(data).path("choices").path(0);
            if (!choice0.path("finish_reason").asText("").isEmpty()) finish = choice0.path("finish_reason").asText("");
            JsonNode delta = choice0.path("delta");
            if (delta.path("refusal").isTextual()) refusal.append(delta.path("refusal").asText(""));
            String piece = delta.path("content").asText(null);
            if (piece != null && !piece.isEmpty()) {
                content.append(piece);
                sink.accept(piece);
            }
            // Reasoning models (gemma under --jinja, the 27B) think out loud in
            // reasoning_content before the tool call — verified against the live server,
            // where an entire turn's visible streaming WAS the reasoning. It goes to the
            // sink so the person watches the model think, but never into the message:
            // downstream must see exactly the non-streamed shape, which drops reasoning.
            String think = delta.path("reasoning_content").asText(null);
            if (think != null && !think.isEmpty()) {
                var t = onThink;
                (t != null ? t : sink).accept(think);
            }
            for (JsonNode tc : delta.path("tool_calls")) {
                int idx = tc.path("index").asInt(0);
                if (tc.hasNonNull("id")) toolIds.put(idx, tc.get("id").asText());
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name")) toolNames.put(idx, fn.get("name").asText());
                if (fn.hasNonNull("arguments")) {
                    String frag = fn.get("arguments").asText();
                    toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(frag);
                    // The loop runs with tool_choice=required, so the model NEVER produces
                    // plain content — measured on the first live streaming run, where a
                    // whole turn streamed and the screen showed nothing. The prose a person
                    // actually reads is the summary argument of the finishing tool, so THAT
                    // is what streams: decoded incrementally out of the JSON fragments,
                    // which split anywhere, including mid-escape.
                    String name = toolNames.get(idx);
                    if ("task_done".equals(name) || "task_blocked".equals(name)) {
                        summaries.computeIfAbsent(idx,
                                        k -> new SummaryStream("task_done".equals(name)
                                                ? "summary" : "reason", sink))
                                .feed(frag);
                    }
                }
            }
        }
        ObjectNode msg = json.createObjectNode();
        msg.put("role", "assistant");
        msg.put("content", content.toString());
        ObjectNode whole = json.createObjectNode();
        ObjectNode c0 = whole.putArray("choices").addObject();
        c0.put("finish_reason", finish);
        c0.putObject("message").put("content", content.toString()).put("refusal", refusal.toString());
        raiseDecline(whole);   // the same signal as a plain request, read from the stream's last chunk
        if (!toolNames.isEmpty()) {
            var arr = msg.putArray("tool_calls");
            toolNames.forEach((idx, name) -> {
                var one = arr.addObject();
                one.put("id", toolIds.getOrDefault(idx, "call_" + idx));
                one.put("type", "function");
                var fn = one.putObject("function");
                fn.put("name", name);
                fn.put("arguments", toolArgs.getOrDefault(idx, new StringBuilder()).toString());
            });
        }
        return msg;
    }

    /**
     * Streams ONE string field's value out of a JSON object that arrives in arbitrary fragments.
     *
     * <p>Looks for {@code "<field>"}, then the colon, then the opening quote, then emits decoded
     * characters until the unescaped closing quote. Fragments split anywhere — inside the key,
     * inside an escape, inside a {@code \\u} sequence — because that is how tool-call deltas
     * actually arrive. Unknown escapes pass through as their literal character; a malformed tail is
     * simply not emitted, since the fully reassembled arguments are parsed downstream anyway and
     * this exists only so a person watches the answer appear instead of a silent turn.
     */
    static final class SummaryStream {
        private final String needle;
        private final Consumer<String> sink;
        private final StringBuilder window = new StringBuilder();
        private int state = 0;         // 0=looking for key, 1=looking for opening quote, 2=in value, 3=done
        private boolean escaping = false;
        private int uLeft = 0;
        private final StringBuilder uHex = new StringBuilder();

        SummaryStream(String field, Consumer<String> sink) {
            this.needle = "\"" + field + "\"";
            this.sink = sink;
        }

        void feed(String fragment) {
            for (int i = 0; i < fragment.length() && state != 3; i++) {
                char c = fragment.charAt(i);
                switch (state) {
                    case 0 -> {
                        window.append(c);
                        if (window.length() > needle.length()) window.deleteCharAt(0);
                        if (window.toString().equals(needle)) state = 1;
                    }
                    case 1 -> { if (c == '"') state = 2; }
                    case 2 -> {
                        if (uLeft > 0) {
                            uHex.append(c);
                            if (--uLeft == 0) {
                                try { sink.accept(String.valueOf((char) Integer.parseInt(uHex.toString(), 16))); }
                                catch (NumberFormatException ignored) { }
                                uHex.setLength(0);
                            }
                        } else if (escaping) {
                            escaping = false;
                            switch (c) {
                                case 'n' -> sink.accept("\n");
                                case 't' -> sink.accept("\t");
                                case 'r' -> { }
                                case 'u' -> uLeft = 4;
                                default -> sink.accept(String.valueOf(c));
                            }
                        } else if (c == '\\') {
                            escaping = true;
                        } else if (c == '"') {
                            state = 3;
                        } else {
                            sink.accept(String.valueOf(c));
                        }
                    }
                    default -> { }
                }
            }
        }
    }
}