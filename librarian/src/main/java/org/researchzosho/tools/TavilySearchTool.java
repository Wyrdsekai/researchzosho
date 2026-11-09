package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code tavily_search}: the tavily place, the web through Tavily (tavily.com), each hit with the passages of its page that answer the
 * query. As read on Tavily's pricing page on 2026-11-04: 1,000 credits a month free, a basic search one credit, then $0.008 a credit pay as
 * you go; so only a key the keeper set turns it on, and the library stops at a monthly limit ({@code tavily_monthly_limit}, 1,000 unless
 * set, the free allowance). The same search answers the general web search first when the keeper sets {@code web_first=tavily}. The key is
 * sent only to Tavily.
 */
public final class TavilySearchTool implements Tool {

    static final ObjectMapper M = new ObjectMapper();
    public static final String KEY = "RESEARCHZOSHO_TAVILY_KEY";
    public static final String LIMIT = "RESEARCHZOSHO_TAVILY_MONTHLY_LIMIT";
    /** The name the tally and the monthly limit count these calls under. */
    public static final String TALLY = "Tavily";
    public static final String SIGN_UP = "https://app.tavily.com/";
    /** The service's addresses; a test points them at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("search", "https://api.tavily.com/search"); BASE.put("usage", "https://api.tavily.com/usage"); }
    public static final AtomicInteger USED = new AtomicInteger();

    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public TavilySearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    public static boolean keySet() { String k = Config.get(KEY); return k != null && !k.isBlank(); }

    /** Whether the place is on the table now: a key is set and this month's searches are not used up. */
    public static boolean open() { return keySet() && !SearchTally.reached(TALLY); }

    /** Why the place is off the table, in one sentence; "" when it is on. */
    public static String closedBecause() {
        if (!keySet()) return "Tavily needs a key: the library's keeper gets one at " + SIGN_UP + " and sets it as tavily_key in the settings.";
        return SearchTally.reached(TALLY) ? SearchTally.usedUp(TALLY, "Tavily") : "";
    }

    /** A search's outcome: the hits, or why there are none ({@code said}: the sentence for the model; {@code status}: what Tavily answered, -1 when no call went). */
    public record Answer(List<PlaceRows.Hit> hits, int status, String said) {
        public boolean answered() { return status >= 200 && status < 300; }
    }

    /** One basic search (one credit), within this month's limit; {@code safe} asks Tavily to leave out adult content. */
    public static Answer search(String query, int limit, boolean safe) {
        String key = Config.get(KEY);
        if (key == null || key.isBlank()) return new Answer(List.of(), -1, closedBecause());
        if (!SearchTally.reserve(TALLY)) return new Answer(List.of(), -1, SearchTally.usedUp(TALLY, "Tavily"));
        boolean made = false;
        int credits = 0;
        try {
            ObjectNode body = M.createObjectNode().put("query", query).put("search_depth", "basic").put("max_results", Math.min(Math.max(limit, 1), 20))
                    .put("include_usage", true).put("safe_search", safe);
            PlaceHttp.Res r = PlaceHttp.post(BASE.get("search"), Map.of("Authorization", "Bearer " + key.strip()), M.writeValueAsString(body), Duration.ofSeconds(30), "Tavily's search");
            if (!r.ok()) return new Answer(List.of(), r.status(), why(r.status()));
            made = true;
            JsonNode j = M.readTree(r.body());
            credits = j.path("usage").path("credits").asInt(1);
            USED.incrementAndGet();
            List<PlaceRows.Hit> hits = new ArrayList<>();
            for (JsonNode h : j.path("results")) {
                String url = h.path("url").asText("");
                if (!url.matches("(?i)^https?://.+")) continue;
                List<String> lines = new ArrayList<>();
                String date = h.path("published_date").asText("");
                if (!date.isBlank()) lines.add("published " + PlaceRows.cut(date, 40));
                String text = h.path("content").asText("");
                if (text.isBlank()) text = h.path("raw_content").asText("");
                if (!text.isBlank()) lines.add(PlaceRows.cut(text, 600));
                hits.add(new PlaceRows.Hit(h.path("title").asText("").strip(), url, lines));
            }
            return new Answer(hits, r.status(), "");
        } catch (Exception e) {
            return new Answer(List.of(), made ? 200 : 0, made ? "" : why(0));
        } finally {
            SearchTally.settle(TALLY, made, made ? Math.max(credits, 1) : 0, 0);
        }
    }

    static String why(int status) {
        return switch (status) {
            case 401, 403 -> "Tavily refused the key";
            case 429 -> "Tavily says too many searches came at once";
            case 432 -> "the Tavily plan's credits for this month are used up";
            case 433 -> "the Tavily account's pay-as-you-go limit is reached";
            case 0 -> "Tavily did not answer";
            default -> "Tavily answered " + status;
        };
    }

    @Override public String name() { return "tavily_search"; }

    @Override public String description() {
        return "Search the web through Tavily: each hit with the passages of its page that answer the query, longer than a web search's snippet, "
                + "so a page can be judged before it is read. Paid: each search counts against the month's limit.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "What to find, in words.");
        props.putObject("limit").put("type", "integer").put("description", "How many pages (default 8, at most 20).");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        Answer a = search(query, limit, policy.siteList());
        if (a.status() == -1) return a.said();
        if (!a.answered()) return "ERROR: " + a.said() + ". This is not a search that found nothing: search the web instead.";
        if (a.hits().isEmpty()) return "No pages from Tavily for: " + query + ".";
        return PlaceRows.render(query, " (Tavily)", a.hits(), limit, policy);
    }
}
