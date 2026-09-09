package org.researchzosho.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A client for The Librarian over HTTP — the library protocol, contract 1.0
 * ({@code docs/LIBRARY_PROTOCOL.md}). Transport and types only; every call returns the daemon's
 * JSON, every protocol error is a {@link LibraryException} with the stable code.
 *
 * <pre>
 *   var lib = new LibrarianClient(URI.create("http://127.0.0.1:4649"), token);
 *   JsonNode pkg = lib.ask("How do subtitlers handle keigo?", 6);
 *   if (pkg.get("holds_nothing").asBoolean()) …
 * </pre>
 */
public final class LibrarianClient {

    public static final String CONTRACT = "1.0";   // the wire protocol; it moves only when something on the wire changes shape
    private static final ObjectMapper M = new ObjectMapper();

    private final URI base;
    private final String token;
    private final String runtime;
    private final HttpClient http;
    private final Duration timeout;

    /** {@code token} null = anonymous. */
    public LibrarianClient(URI base, String token) { this(base, token, "java", Duration.ofSeconds(60)); }

    public LibrarianClient(URI base, String token, String runtime, Duration timeout) {
        String b = base.toString();
        this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
        this.token = token;
        this.runtime = runtime;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // A bearer token over plain http to another machine travels in the clear. The daemon's default
        // bind is loopback, where that is fine; anywhere else, say so once rather than silently.
        String host = this.base.getHost() == null ? "" : this.base.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
        if (token != null && !token.isBlank() && "http".equalsIgnoreCase(this.base.getScheme()) && !loopback && WARNED.compareAndSet(false, true)) {
            System.err.println("researchzosho client: sending the bearer token over plain http to " + host + " — use https or an ssh tunnel for a remote librarian");
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    // ---- the nine calls ----

    public JsonNode ask(String question, int k) { return post("ask", obj().put("question", question).put("k", k)); }

    public JsonNode search(String query, int k, String subject, String cursor) {
        ObjectNode a = obj().put("query", query).put("k", k);
        if (subject != null) a.put("subject", subject);
        if (cursor != null) a.put("cursor", cursor);
        return post("search", a);
    }

    /** Follow {@code next_cursor} to the end. */
    public List<JsonNode> searchAll(String query, String subject) {
        List<JsonNode> hits = new ArrayList<>();
        String cursor = null;
        while (true) {
            JsonNode r = search(query, 50, subject, cursor);
            r.get("hits").forEach(hits::add);
            JsonNode next = r.get("next_cursor");
            if (next == null || next.isNull()) return hits;
            cursor = next.asText();
        }
    }

    public JsonNode get(String id) { return post("get", obj().put("id", id)).get("entry"); }

    public JsonNode read(String locator, int maxChars) { return post("read", obj().put("locator", locator).put("max_chars", maxChars)); }

    public JsonNode established(String claim) { return post("established", obj().put("claim", claim)); }

    /** {@code sources}: locators (strings) or {@code {locator, edition, why}} objects. */
    public JsonNode submit(String claim, List<?> sources, String claimType, String confidence, String title) {
        return submit(claim, sources, claimType, confidence, title, null, null, null);
    }
    /** With a triple, the finding is an edge of the graph ({@link #map}). */
    public JsonNode submit(String claim, List<?> sources, String claimType, String confidence, String title, String subject, String predicate, String object) {
        ObjectNode a = obj().put("claim", claim).put("claim_type", claimType).put("confidence", confidence);
        ArrayNode s = a.putArray("sources");
        for (Object src : sources) s.add(M.valueToTree(src));
        if (title != null) a.put("title", title);
        if (subject != null && predicate != null && object != null) a.putObject("triple").put("subject", subject).put("predicate", predicate).put("object", object);
        return post("submit", a);
    }

    public JsonNode frontier() { return post("frontier", obj().put("op", "list")).get("questions"); }
    public JsonNode frontierAdd(String question) { return post("frontier", obj().put("op", "add").put("question", question)); }
    public JsonNode subjects() { return post("subjects", obj()).get("subjects"); }
    public JsonNode status() { return post("status", obj()); }
    /** Recall notices after a cursor: {changes[], next_cursor, latest, more}. Keep next_cursor between runs. */
    public JsonNode changes(String since, int limit) { return post("changes", obj().put("since", since == null ? "0" : since).put("limit", limit)); }

    // ---- resources ----

    public JsonNode resources(String cursor) { return httpGet("resources" + (cursor == null ? "" : "?cursor=" + enc(cursor))); }
    public String resource(String uri) { return httpGet("resource?uri=" + enc(uri)).get("contents").get(0).get("text").asText(); }

    // ---- research and job are contract 1.2; the jobs page and crews/run are the daemon's own ----

    /** Who studies this and what each would ask (contract 1.4): {perspectives[], sub_questions[]} for {@link #research}. */
    public JsonNode perspectives(String question, int max) { return post("perspectives", obj().put("question", question).put("max", max)); }

    /** The graph around a node name or an entry id (contract 1.4): nodes, edges (= findings), open questions. */
    public JsonNode map(String focus, int depth, int k) { return post("map", obj().put("focus", focus).put("depth", depth).put("k", k)); }

    /** File an overnight ask. {@code maxTurns} is the whole run's model-turn budget (its parallel workers, critic and synthesis share it). */
    public JsonNode research(String question, String mode, int maxTurns) {
        return research(question, mode, maxTurns, null);
    }
    /** As above, with the plan: {@code subQuestions} (at most 8) become the run's sub-investigations instead of a decompose step. */
    public JsonNode research(String question, String mode, int maxTurns, java.util.List<String> subQuestions) {
        return research(question, mode, maxTurns, subQuestions, "both", null);
    }
    /** {@code sources}: both (shelves first) | shelves (the person's corpus only) | web; {@code collections} scope the shelves. */
    public JsonNode research(String question, String mode, int maxTurns, java.util.List<String> subQuestions, String sources, java.util.List<String> collections) {
        return research(question, mode, maxTurns, 0, subQuestions, sources, collections);
    }
    /** {@code maxTurns} and {@code maxMinutes} are ceilings, 0 = none: the run goes until the work is done. */
    public JsonNode research(String question, String mode, int maxTurns, int maxMinutes, java.util.List<String> subQuestions, String sources, java.util.List<String> collections) {
        ObjectNode body = obj().put("question", question).put("mode", mode).put("sources", sources == null ? "both" : sources);
        if (maxTurns > 0) body.put("max_turns", maxTurns);
        if (maxMinutes > 0) body.put("max_minutes", maxMinutes);
        if (collections != null && !collections.isEmpty()) { var cs = body.putArray("collections"); for (String c : collections) cs.add(c); }
        if (subQuestions != null && !subQuestions.isEmpty()) {
            var arr = body.putArray("sub_questions");
            for (String s : subQuestions) if (arr.size() < 8) arr.add(s);
        }
        return post("research", body);
    }
    public JsonNode job(String jobId) { return httpGet("jobs/" + enc(jobId)); }
    /** A page of this patron's jobs: {active[], finished[] newest first, next_cursor, finished_total, running, queued}. */
    public JsonNode jobs(int limit, String cursor) { return httpGet("jobs?limit=" + limit + (cursor == null ? "" : "&cursor=" + enc(cursor))); }
    public JsonNode jobs() { return jobs(20, null); }

    /** Block until the job leaves {@code running}; {@code timeout} null = wait as long as it takes. */
    public JsonNode await(String jobId, Duration poll, Duration timeout) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (true) {
            JsonNode j = job(jobId);
            String st = j.get("state").asText();
            if (!"running".equals(st) && !"queued".equals(st)) return j;
            if (timeout != null && System.currentTimeMillis() - t0 > timeout.toMillis()) {
                throw new LibraryException("unavailable", 0, "Job " + jobId + " is still running after " + timeout);
            }
            Thread.sleep(poll.toMillis());
        }
    }
    public JsonNode runCrews() { return post("crews/run", obj()); }

    // ---- plumbing ----

    private static ObjectNode obj() { return M.createObjectNode(); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private JsonNode post(String route, ObjectNode args) {
        args.putObject("patron").put("runtime", runtime);   // the did comes from the token, never asserted here
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/" + route)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(args)));
            return send(b);
        } catch (IOException e) {
            throw new LibraryException("unavailable", 0, "The request could not be encoded: " + e.getMessage());
        }
    }

    private JsonNode httpGet(String routeAndQuery) {
        return send(HttpRequest.newBuilder(URI.create(base + "/v1/" + routeAndQuery)).timeout(timeout).GET());
    }

    private JsonNode send(HttpRequest.Builder b) {
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LibraryException("unavailable", 0, "The Librarian did not answer at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LibraryException("unavailable", 0, "Interrupted while waiting for " + base);
        }
        JsonNode body;
        try { body = M.readTree(res.body()); }
        catch (IOException e) { throw new LibraryException("unavailable", res.statusCode(), "The Librarian answered with something that is not JSON."); }
        if (res.statusCode() >= 400) {
            JsonNode err = body.path("error");
            throw new LibraryException(err.path("code").asText("unavailable"), res.statusCode(),
                    err.path("message").asText("HTTP " + res.statusCode()));
        }
        return body;
    }
}
