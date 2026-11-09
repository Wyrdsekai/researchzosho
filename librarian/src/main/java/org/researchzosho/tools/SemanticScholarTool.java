package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code semantic_scholar_search}: the semantic-scholar place, Semantic Scholar's Academic Graph (Ai2): papers in every field with their
 * venue, year, authors, how often they are cited, the start of the abstract, and a free PDF where one exists, which the ordinary page
 * reading opens. Free. Read on 2026-11-04 on Semantic Scholar's API page: without a key, calls share a pool of 1,000 a second with every
 * other program that has none and are slowed at busy times; a key, free on request, gives one call a second of one's own. The program
 * keeps to one call a second either way, waits out a busy answer three times, longer each time, and then says the service is busy rather
 * than that it found nothing. The API's licence asks for Semantic Scholar to be named where its data is used: every answer names it.
 */
public final class SemanticScholarTool implements Tool {

    static final ObjectMapper M = new ObjectMapper();
    public static final String KEY = "RESEARCHZOSHO_S2_KEY";
    /** The name the tally counts these calls under. */
    public static final String TALLY = "Semantic Scholar";
    /** The service's address; a test points it at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("search", "https://api.semanticscholar.org/graph/v1/paper/search"); }
    static final String FIELDS = "title,abstract,year,venue,citationCount,openAccessPdf,externalIds,url,authors,publicationTypes,journal";
    public static final AtomicInteger USED = new AtomicInteger();
    /** How long each busy answer is waited out before the next try; a test shortens them. The shared pool answered about one call in three on 2026-11-04. */
    static volatile long[] BUSY_WAITS = {2000, 4000, 8000};

    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public SemanticScholarTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    public static boolean keySet() { String k = Config.get(KEY); return k != null && !k.isBlank(); }

    private static long nextAt = 0;
    /** The gap between two calls, in milliseconds; a test sets it to nothing. */
    static volatile long GAP = 1050;

    /** One call a second from the whole program: the key's own rate, and easy on the shared pool without one. */
    static synchronized long slot(long now) {
        long at = Math.max(now, nextAt);
        nextAt = at + GAP;
        return at - now;
    }

    @Override public String name() { return "semantic_scholar_search"; }

    @Override public String description() {
        return "Search Semantic Scholar's index of papers in every field: each paper with its venue, year, authors, how often it is cited, the "
                + "start of its abstract and, when there is one, a free PDF that reading the address opens. Use it beside scholar_search when the "
                + "question has a literature and it matters which papers others cite.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "The words a paper's title or abstract would use; a topic, not a sentence.");
        props.putObject("limit").put("type", "integer").put("description", "How many papers (default 8, at most 20).");
        p.putArray("required").add("query");
        return p;
    }

    /** What a search came back with: the hits and how many matched, or the status that stopped it (429 busy, 0 no answer). */
    record Answer(List<PlaceRows.Hit> hits, int total, int status) { }

    static Answer search(String query, int limit) {
        String key = Config.get(KEY);
        Map<String, String> headers = key == null || key.isBlank() ? Map.of() : Map.of("x-api-key", key.strip());
        String url = BASE.get("search") + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + limit + "&fields=" + FIELDS;
        PlaceHttp.Res r = null;
        for (int attempt = 0; attempt <= BUSY_WAITS.length; attempt++) {
            PlaceHttp.sleep(slot(System.currentTimeMillis()));
            r = PlaceHttp.get(url, headers, Duration.ofSeconds(25), "Semantic Scholar");
            if (r.status() != 429 || attempt == BUSY_WAITS.length) break;
            PlaceHttp.sleep(BUSY_WAITS[attempt]);
        }
        if (r == null || !r.ok()) return new Answer(List.of(), 0, r == null ? 0 : r.status());
        try {
            JsonNode j = M.readTree(r.body());
            List<PlaceRows.Hit> hits = new ArrayList<>();
            for (JsonNode p : j.path("data")) { PlaceRows.Hit h = hit(p); if (h != null) hits.add(h); }
            return new Answer(hits, j.path("total").asInt(hits.size()), 200);
        } catch (Exception e) { return new Answer(List.of(), 0, 0); }
    }

    /** One paper: at its DOI when it has one, else its arXiv page, else its Semantic Scholar page; the free PDF beside it. */
    static PlaceRows.Hit hit(JsonNode p) {
        String title = p.path("title").asText("").replaceAll("\\s+", " ").strip();
        if (title.isEmpty()) return null;
        JsonNode ids = p.path("externalIds");
        String doi = ids.path("DOI").asText(""), arxiv = ids.path("ArXiv").asText("");
        String url = !doi.isEmpty() ? "https://doi.org/" + doi : !arxiv.isEmpty() ? "https://arxiv.org/abs/" + arxiv : p.path("url").asText("");
        if (url.isEmpty()) return null;
        List<String> what = new ArrayList<>();
        String venue = p.path("venue").asText("");
        if (venue.isEmpty()) venue = p.path("journal").path("name").asText("");
        String src = (venue + (p.path("year").isNumber() ? (venue.isEmpty() ? "" : ", ") + p.path("year").asInt() : "")).strip();
        if (!src.isEmpty()) what.add(src);
        List<String> names = new ArrayList<>();
        for (JsonNode a : p.path("authors")) { String n = a.path("name").asText(""); if (!n.isEmpty()) names.add(n); }
        if (!names.isEmpty()) what.add(PlaceRows.authors(names));
        if (p.path("citationCount").isNumber()) { int c = p.path("citationCount").asInt(); what.add(c == 1 ? "cited once" : "cited " + String.format(Locale.US, "%,d", c) + " times"); }
        List<String> types = new ArrayList<>();
        for (JsonNode t : p.path("publicationTypes")) if (!t.asText("").isEmpty() && !t.asText("").equals("JournalArticle")) types.add(t.asText().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
        if (!types.isEmpty()) what.add(String.join(", ", types));
        List<String> lines = new ArrayList<>();
        lines.add(String.join(" · ", what));
        String abs = p.path("abstract").asText("");
        if (!abs.isBlank()) lines.add(PlaceRows.cut(abs, 320));
        String pdf = p.path("openAccessPdf").path("url").asText("").strip();
        List<String[]> more = pdf.matches("(?i)^https?://.+") ? List.<String[]>of(new String[]{"free PDF", pdf}) : List.of();
        return new PlaceRows.Hit(title, url, lines, more);
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        Answer a = search(query, limit);
        if (a.status() == 429) return "ERROR: Semantic Scholar is busy: the calls it shares among programs without a key are used up for the moment. "
                + "This is not a search that found nothing: try once more later, or search scholar instead.";
        if (a.status() == 403 || a.status() == 401) return "ERROR: Semantic Scholar refused the key the library sent (" + a.status() + "). This is not a search that found nothing: search scholar instead.";
        if (a.status() != 200) return "ERROR: Semantic Scholar did not answer. This is not a search that found nothing: try once more, or search scholar instead.";
        USED.incrementAndGet();
        SearchTally.count(TALLY, 0);
        if (a.hits().isEmpty()) return "No papers in Semantic Scholar for: " + query + ". Try the words a title would use, or fewer of them.";
        return PlaceRows.render(query, " (Semantic Scholar: " + Math.min(a.hits().size(), limit) + " of " + a.total() + ")", a.hits(), limit, policy);
    }
}
