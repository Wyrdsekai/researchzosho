package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.researchzosho.Config;

/**
 * Web search via a self-hosted SearXNG meta-search instance (keyless, aggregates many engines — fits
 * CodeZaiku's self-hosted ethos: local model, local embeddings, local search). Returns a compact ranked list
 * of {title, url, snippet} for the research loop to triage before fetching.
 *
 * <p>Endpoint from {@code RESEARCHZOSHO_SEARXNG} (default {@code http://localhost:8888}); the instance must have
 * {@code json} in its {@code search.formats}.
 */
public final class WebSearchTool implements Tool {

    /** Session-wide count of degraded-backend events — the acquisitions gate diffs this around a
     *  run to judge the run's SUBSTRATE (a refused draft names infrastructure, not the model).
     *  Same pattern as DriveClient's SESSION_*_TOKENS. Shared across parallel fan workers on
     *  purpose: the gate judges the whole run's substrate, not one worker's. */
    public static final java.util.concurrent.atomic.AtomicInteger DEGRADED_EVENTS =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    // Repetition guard (see WebFetchTool): don't let a fixating model re-run the identical query.
    private final Set<String> queried = new HashSet<>();
    private final SearchSteer steer = new SearchSteer();

    /** The steerer, for the loop's early-finish hook and the run summary. */
    public SearchSteer steer() { return steer; }

    /** The question, so the steerer can name the language axis. */
    public WebSearchTool focus(String question) {
        steer.focus(question);
        return this;
    }

    /** Append the steerer's note (if any) to a formatted result; hosts parsed from its url lines. */
    private String steered(String query, String result) {
        var urls = new java.util.ArrayList<String>();
        for (String line : result.split("\n")) if (line.startsWith("   http")) urls.add(line.strip());
        return result + steer.observe(query, urls);
    }
    // One strategy note per run (sparse — over-injection dilutes a weak model's attention).
    private boolean sweepNoted = false;

    /** ≥4 distinct capitalized terms ≈ several entities crammed into one query — engines AND terms
     *  together, so these return homepages or nothing (measured: 6 consecutive useless mega-queries). */
    private static boolean looksBatched(String query) {
        Set<String> caps = new HashSet<>();
        for (String w : query.split("[^A-Za-z]+"))
            if (w.length() > 2 && Character.isUpperCase(w.charAt(0))) caps.add(w);
        return caps.size() >= 4;
    }

    /** A test points the tool at a local server; production leaves this null. */
    static volatile String endpointOverride = null;

    public static String endpoint() {
        if (endpointOverride != null) return endpointOverride;
        String e = Config.get("RESEARCHZOSHO_SEARXNG");
        return (e == null || e.isBlank()) ? "http://localhost:8888" : e.replaceAll("/+$", "");
    }

    /**
     * Brave Search API, first choice when a key is configured. Measured reason (2026-08-29,
     * first probe of each backend, same query): SearXNG's surviving free engine put spam at
     * ranks 1-2 with brave/ddg/startpage rate-limited or CAPTCHA'd; the Brave API returned the
     * paper, the official site and the dataset as its top three. Falls back to SearXNG on ANY
     * failure — a search tool that dies with its billing dies at the worst moment.
     * Returns null when Brave is unconfigured or unusable, and the caller falls through.
     */
    private String braveSearch(String query, int limit) {
        String key = Config.get("RESEARCHZOSHO_BRAVE_KEY");
        if (key == null || key.isBlank()) return null;
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(
                            "https://api.search.brave.com/res/v1/web/search?count="
                            + Math.min(limit, 20) + "&q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + (languageOf(query) == null ? "" : "&search_lang=" + languageOf(query))))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", key.strip())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;   // 401/429/5xx → SearXNG carries on
            JsonNode rs = M.readTree(resp.body()).path("web").path("results");
            if (!rs.isArray() || rs.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("results for \"" + query + "\":\n" + org.researchzosho.librarian.Fence.open("SEARCH RESULTS") + "\n");
            int shown = 0, refused = 0;
            var rules = org.researchzosho.librarian.SourceRules.live();
            for (int i = 0; i < rs.size() && shown < limit; i++) {
                JsonNode r = rs.get(i);
                String url = r.path("url").asText("");
                if (rules.refused(url)) { refused++; continue; }   // the person's refused list: never shown, never cited
                String desc = r.path("description").asText("").replaceAll("<[^>]+>", "")
                        .replaceAll("\\s+", " ").strip();
                if (desc.length() > 240) desc = desc.substring(0, 240) + "…";
                shown++;
                sb.append(shown).append(". ").append(r.path("title").asText("")).append('\n')
                  .append("   ").append(url).append("  [").append(org.researchzosho.librarian.SourceTier.of(url)).append(rules.trusted(url) ? ", trusted by the person" : "").append("]\n");
                if (!desc.isEmpty()) sb.append("   ").append(desc).append('\n');
            }
            if (refused > 0) sb.append("(").append(refused).append(" result(s) left out: on the person's refused-sources list)\n");
            sb.append(org.researchzosho.librarian.Fence.close("SEARCH RESULTS")).append('\n').append(org.researchzosho.librarian.Fence.rule("SEARCH RESULTS")).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "Search the web (returns ranked title/url/snippet results). Use to FIND sources; then use "
                + "web_fetch to read the promising ones.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("limit").put("type", "integer");
        p.putArray("required").add("query");
        return p;
    }

    /** "brave: too many requests, duckduckgo: timeout" — SearXNG reports which upstreams are down. */
    private static String degradedEngines(JsonNode body) {
        JsonNode ue = body.path("unresponsive_engines");
        if (!ue.isArray() || ue.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : ue) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.path(0).asText("?"));
            String why = e.path(1).asText("");
            if (!why.isBlank()) sb.append(": ").append(why);
        }
        return sb.toString();
    }

    /** The language a query's script gives away: ja, zh, ko, ru, ar, el, he, hi, th — or null for Latin script, which tells nothing. */
    static String languageOf(String q) {
        if (q == null) return null;
        boolean kana = q.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x30ff);
        if (kana) return "ja";
        if (q.codePoints().anyMatch(c -> (c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf))) return "zh";
        if (q.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "ko";
        if (q.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "ru";
        if (q.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06ff)) return "ar";
        if (q.codePoints().anyMatch(c -> c >= 0x0370 && c <= 0x03ff)) return "el";
        if (q.codePoints().anyMatch(c -> c >= 0x0590 && c <= 0x05ff)) return "he";
        if (q.codePoints().anyMatch(c -> c >= 0x0900 && c <= 0x097f)) return "hi";
        if (q.codePoints().anyMatch(c -> c >= 0x0e00 && c <= 0x0e7f)) return "th";
        return null;
    }

    @Override public String execute(JsonNode args) throws Exception {
        String query = args.path("query").asText("");
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        if (!queried.add(query.strip().toLowerCase()))
            return "ALREADY SEARCHED: you already ran this exact query; its results are above in your history. "
                    + "Use a DIFFERENT query, web_fetch one of the results you have not read yet, or write your "
                    + "answer and call task_done.";
        String brave = endpointOverride == null ? braveSearch(query, limit) : null;   // a test's local server, never Brave
        if (brave != null) {
            if (!sweepNoted && looksBatched(query)) {
                sweepNoted = true;
                brave += "\nNOTE: this query names several distinct items at once — engines require ALL "
                        + "terms, so batched queries surface homepages, not data. Search for ONE page "
                        + "listing all the items (\"list of …\" / \"comparison of …\"), or query ONE "
                        + "item at a time.";
            }
            return steered(query, brave);
        }
        String lang = languageOf(query);
        // a query in a non-Latin script tells the engine its language, or it answers with whatever matches the bytes
        // (measured: a Japanese query with no language came back as Brazilian news and a Microsoft forum)
        String url = endpoint() + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + (lang == null ? "" : "&language=" + lang);
        JsonNode body = null;
        // The free upstream engines rate-limit under sustained load, and SearXNG then SUSPENDS them —
        // every engine down comes back as an empty result list, which reads exactly like "the web does not
        // know this". Retry once through the backoff before believing an empty answer.
        for (int attempt = 1; attempt <= 2 && body == null; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                queried.remove(query.strip().toLowerCase());   // a failed search must stay retryable
                return "ERROR: search backend unreachable at " + endpoint() + " (" + e + "). Is the SearXNG "
                        + "container running (docker start searxng)?";
            }
            if (resp.statusCode() != 200) {
                queried.remove(query.strip().toLowerCase());
                return "ERROR: search returned HTTP " + resp.statusCode();
            }
            JsonNode parsed = M.readTree(resp.body());
            boolean empty = !parsed.path("results").isArray() || parsed.path("results").isEmpty();
            if (empty && attempt == 1 && parsed.path("unresponsive_engines").size() > 0) {
                try { Thread.sleep(4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                continue;   // engines were suspended a moment ago; give the cooldown a chance
            }
            body = parsed;
        }
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) {
            // A failed search must not burn its slot in the repetition guard — the query was never answered.
            queried.remove(query.strip().toLowerCase());
            String down = degradedEngines(body);
            if (!down.isEmpty()) {
                DEGRADED_EVENTS.incrementAndGet();
                return "SEARCH BACKEND DEGRADED — no results came back because the upstream engines are "
                        + "currently rate-limited or blocked (" + down + "). This is a TRANSIENT infrastructure "
                        + "problem, not evidence that the information does not exist: do NOT conclude the answer "
                        + "is unavailable and do NOT answer from memory. Try a different phrasing, or fetch a "
                        + "likely source URL directly with web_fetch (e.g. the relevant Wikipedia page).";
            }
            return "no results for: " + query;
        }
        StringBuilder sb = new StringBuilder("results for \"" + query + "\":\n" + org.researchzosho.librarian.Fence.open("SEARCH RESULTS") + "\n");
        int shown = 0, refused = 0;
        var rules = org.researchzosho.librarian.SourceRules.live();
        for (int i = 0; i < results.size() && shown < limit; i++) {
            JsonNode r = results.get(i);
            String ru = r.path("url").asText("");
            if (rules.refused(ru)) { refused++; continue; }   // the person's refused list: never shown, never cited
            String content = r.path("content").asText("").replaceAll("\\s+", " ").strip();
            if (content.length() > 240) content = content.substring(0, 240) + "…";
            shown++;
            sb.append(shown).append(". ").append(r.path("title").asText("")).append('\n')
              .append("   ").append(ru).append("  [").append(org.researchzosho.librarian.SourceTier.of(ru)).append(rules.trusted(ru) ? ", trusted by the person" : "").append("]\n");
            if (!content.isEmpty()) sb.append("   ").append(content).append('\n');
        }
        if (refused > 0) sb.append("(").append(refused).append(" result(s) left out: on the person's refused-sources list)\n");
        if (!sweepNoted && looksBatched(query)) {
            sweepNoted = true;
            sb.append("\nNOTE: this query names several distinct items at once — engines require ALL terms, "
                    + "so batched queries surface homepages, not data. Search for ONE page listing all the "
                    + "items (\"list of …\" / \"comparison of …\"), or query ONE item at a time.");
        }
        sb.append(org.researchzosho.librarian.Fence.close("SEARCH RESULTS")).append('\n').append(org.researchzosho.librarian.Fence.rule("SEARCH RESULTS")).append('\n');
        return steered(query, sb.toString());
    }
}
