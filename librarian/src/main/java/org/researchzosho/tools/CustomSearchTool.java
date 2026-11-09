package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code custom_search}: a search service the keeper points the library at, as a place of its own on the table, named by the keeper
 * ({@code custom_search_name}, "custom" unless set). Its address is the keeper's configuration, so it is asked as it is, even on the home
 * network or this computer, and never through the research fetch's address check; the pages its hits name are read like any page, through
 * that check. The answer is read in the common shapes: a list of hits, or an object whose {@code results} or {@code items} hold them, each
 * with {@code url} (or {@code link}), {@code title} and {@code content}, {@code snippet} or {@code raw_content}; an answer in none of them
 * is said plainly. A key, when set, goes only to that address, as {@code Authorization: Bearer} or in the header the keeper names.
 */
public final class CustomSearchTool implements Tool {

    static final ObjectMapper M = new ObjectMapper();
    public static final String URL = "RESEARCHZOSHO_CUSTOM_SEARCH_URL", NAME = "RESEARCHZOSHO_CUSTOM_SEARCH_NAME", KEY = "RESEARCHZOSHO_CUSTOM_SEARCH_KEY",
            HEADER = "RESEARCHZOSHO_CUSTOM_SEARCH_HEADER", LIMIT = "RESEARCHZOSHO_CUSTOM_SEARCH_MONTHLY_LIMIT";
    /** The name the tally counts these calls under, whatever the place is called. */
    public static final String TALLY = "your own search service";
    public static final AtomicInteger USED = new AtomicInteger();

    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;
    private final String place;

    public CustomSearchTool() { this(rawName()); }
    public CustomSearchTool(String place) { this.place = place == null || place.isBlank() ? "custom" : place; }

    public CustomSearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    public static boolean set() { String u = Config.get(URL); return u != null && !u.isBlank(); }

    /** Whether the place is on the table now: an address is set and this month's limit, when one is set, is not reached. */
    public static boolean open() { return set() && !SearchTally.reached(TALLY); }

    /** The place's name as the keeper wrote it, made one word: lower case, hyphens for spaces; "custom" when unset. */
    public static String rawName() {
        String n = Config.get(NAME);
        String w = n == null ? "" : n.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-").replaceAll("[^\\p{L}\\p{N}-]", "").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return w.isEmpty() ? "custom" : w;
    }

    /** The address a query is asked at: {q} replaced by the words, {n} by the count; else q= and the words added. */
    static String address(String base, String query, int limit) {
        String q = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        String b = base.strip();
        if (b.contains("{q}")) return b.replace("{q}", q).replace("{n}", String.valueOf(limit)).replace("{limit}", String.valueOf(limit));
        return b + (b.contains("?") ? (b.endsWith("?") || b.endsWith("&") ? "" : "&") : "?") + "q=" + q;
    }

    static String host(String url) { try { String h = URI.create(url).getHost(); return h == null ? url : h; } catch (Exception e) { return url; } }

    /** The hits in an answer, or null when the answer is in none of the shapes the library reads. */
    static List<PlaceRows.Hit> hits(String body) {
        JsonNode j;
        try { j = M.readTree(body); } catch (Exception e) { return null; }
        if (j == null) return null;
        JsonNode list = j.isArray() ? j : null;
        if (list == null) for (String k : List.of("results", "items", "data", "hits", "organic")) if (j.path(k).isArray()) { list = j.path(k); break; }
        if (list == null && j.path("web").path("results").isArray()) list = j.path("web").path("results");
        if (list == null) return null;
        List<PlaceRows.Hit> out = new ArrayList<>();
        int unread = 0;
        for (JsonNode h : list) {
            String url = first(h, "url", "link", "href");
            if (!url.matches("(?i)^https?://.+")) { unread++; continue; }
            String title = first(h, "title", "name");
            String text = first(h, "content", "snippet", "raw_content", "description", "text", "summary");
            out.add(new PlaceRows.Hit(title, url, text.isBlank() ? List.of() : List.of(PlaceRows.cut(text, 500))));
        }
        if (out.isEmpty() && unread > 0) return null;   // a list, but nothing in it with an address
        return out;
    }

    static String first(JsonNode h, String... keys) {
        for (String k : keys) if (h.path(k).isTextual() && !h.path(k).asText().isBlank()) return h.path(k).asText().strip();
        return "";
    }

    /** The shapes said in words, for an answer the library cannot read. */
    static final String SHAPES = "The library reads a list of hits, or an object whose results or items hold them, each hit with url, title and content (or snippet or raw_content).";

    /** One search: what came back and what the model is told; {@code made} when the service answered at all. */
    record Outcome(String said, boolean made, List<PlaceRows.Hit> hits) { }

    Outcome ask(String query, int limit) {
        String base = Config.get(URL);
        if (base == null || base.isBlank()) return new Outcome("No search service of the library's own is set: the library's keeper sets its address as custom_search_url in the settings.", false, null);
        String url = address(base, query, limit), host = host(url);
        String key = Config.get(KEY), header = Config.get(HEADER);
        Map<String, String> headers = key == null || key.isBlank() ? Map.of()
                : header != null && !header.isBlank() ? Map.of(header.strip(), key.strip()) : Map.of("Authorization", "Bearer " + key.strip());
        PlaceHttp.Res r = PlaceHttp.get(url, headers, Duration.ofSeconds(20), "the search service at " + host);
        if (r.status() == 0) return new Outcome("ERROR: the search service at " + host + " did not answer. This is not a search that found nothing: try once more, or search the web instead.", false, null);
        if (r.status() >= 300 && r.status() < 400) return new Outcome("ERROR: the search service at " + host + " answered with a move to " + PlaceRows.cut(r.header("location"), 200)
                + ". The library follows no move with its key; the keeper sets that address as custom_search_url instead.", true, null);
        if (r.status() == 401 || r.status() == 403) return new Outcome("ERROR: the search service at " + host + " refused the key (HTTP " + r.status() + "). This is not a search that found nothing: search the web instead.", true, null);
        if (!r.ok()) return new Outcome("ERROR: the search service at " + host + " answered HTTP " + r.status() + ". This is not a search that found nothing: search the web instead.", true, null);
        List<PlaceRows.Hit> hits = hits(r.body());
        if (hits == null) return new Outcome("ERROR: the search service at " + host + " answered, but not in a shape the library reads. " + SHAPES, true, null);
        return new Outcome("", true, hits);
    }

    /** What the check of the service found: whether it answered in a shape the library reads (null: it said nothing about that), in words. */
    public record Checked(Boolean answers, String said) { }

    /** The check for the Sources and keys table: one search for "researchzosho", asked the way a run asks, and counted like one. */
    public static Checked check() {
        if (!set()) return new Checked(null, "no address is set");
        if (!SearchTally.reserve(TALLY)) return new Checked(null, "not checked: " + SearchTally.usedUp(TALLY, rawName()));
        Outcome o = null;
        try { o = new CustomSearchTool().ask("researchzosho", 3); }
        finally { SearchTally.settle(TALLY, o != null && o.made(), 0, 0); }
        if (o.hits() != null) return new Checked(true, "it answers: " + o.hits().size() + (o.hits().size() == 1 ? " hit" : " hits") + " for a test search, in a shape the library reads");
        String said = o.said().replaceFirst("^ERROR: ", "");
        if (said.contains("refused the key")) return new Checked(false, "refused: " + said);
        return new Checked(null, said);
    }

    @Override public String name() { return "custom_search"; }

    @Override public String description() {
        return "Search " + place + ", the search service the keeper set up for this library: each hit with its address, its title and the text the service gives with it.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "What to find, in words.");
        props.putObject("limit").put("type", "integer").put("description", "How many hits (default 8, at most 20).");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        if (!set()) return ask(query, limit).said();
        if (!SearchTally.reserve(TALLY)) return SearchTally.usedUp(TALLY, place);
        Outcome o = null;
        try { o = ask(query, limit); }
        finally { SearchTally.settle(TALLY, o != null && o.made(), 0, 0); }
        if (o.hits() == null) return o.said();
        USED.incrementAndGet();
        String host = host(address(Config.get(URL), query, limit));
        if (o.hits().isEmpty()) return "No hits from " + place + " for: " + query + ".";
        return PlaceRows.render(query, " (" + place + ", the search service at " + host + " that the keeper set up for this library)", o.hits(), limit, policy);
    }
}
