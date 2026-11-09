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
 * {@code exa_search}: the exa place, the web searched by meaning through Exa (exa.ai), each hit with the passages of its page that match.
 * Paid from the account's credits: $7 per 1,000 searches with up to ten results and their passages, as read on Exa's pricing page on
 * 2026-11-04, so only a key the keeper set turns it on, and the library stops at a monthly limit ({@code exa_monthly_limit}, 714 unless
 * set, which keeps it under $5 a month). The same search answers the general web search first when the keeper sets
 * {@code web_first=exa}. The key is sent only to Exa.
 */
public final class ExaSearchTool implements Tool {

    static final ObjectMapper M = new ObjectMapper();
    public static final String KEY = "RESEARCHZOSHO_EXA_KEY";
    public static final String LIMIT = "RESEARCHZOSHO_EXA_MONTHLY_LIMIT";
    /** The name the tally and the monthly limit count these calls under. */
    public static final String TALLY = "Exa";
    public static final String SIGN_UP = "https://dashboard.exa.ai/api-keys";
    /** A search's price when the answer does not say: an auto search with up to ten results, $7 per 1,000, as read on 2026-11-04. */
    static final double PRICE = 0.007;
    /** The service's address; a test points it at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("search", "https://api.exa.ai/search"); }
    public static final AtomicInteger USED = new AtomicInteger();
    /** What the last search cost, as Exa said: for the command that runs one search for a person. */
    public static volatile double lastCost = 0;

    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public ExaSearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    public static boolean keySet() { String k = Config.get(KEY); return k != null && !k.isBlank(); }

    /** Whether the place is on the table now: a key is set and this month's searches are not used up. */
    public static boolean open() { return keySet() && !SearchTally.reached(TALLY); }

    /** Why the place is off the table, in one sentence; "" when it is on. */
    public static String closedBecause() {
        if (!keySet()) return "Exa needs a key: the library's keeper gets one at " + SIGN_UP + " and sets it as exa_key in the settings.";
        return SearchTally.reached(TALLY) ? SearchTally.usedUp(TALLY, "Exa") : "";
    }

    /** A search's outcome: the hits, or why there are none ({@code said}: the sentence for the model; {@code status}: what Exa answered, -1 when no call went). */
    public record Answer(List<PlaceRows.Hit> hits, int status, String said) {
        public boolean answered() { return status >= 200 && status < 300; }
    }

    /** One search, within this month's limit; {@code safe} asks Exa to leave out unsafe content. */
    public static Answer search(String query, int limit, boolean safe) {
        String key = Config.get(KEY);
        if (key == null || key.isBlank()) return new Answer(List.of(), -1, closedBecause());
        if (!SearchTally.reserve(TALLY)) return new Answer(List.of(), -1, SearchTally.usedUp(TALLY, "Exa"));
        boolean made = false;
        double cost = 0;
        try {
            ObjectNode body = M.createObjectNode().put("query", query).put("type", "auto").put("numResults", Math.min(Math.max(limit, 1), 10)).put("moderation", safe);
            body.putObject("contents").putObject("highlights").put("maxCharacters", 600);
            PlaceHttp.Res r = PlaceHttp.post(BASE.get("search"), Map.of("x-api-key", key.strip()), M.writeValueAsString(body), Duration.ofSeconds(30), "Exa's search");
            if (!r.ok()) return new Answer(List.of(), r.status(), why(r.status()));
            made = true;
            JsonNode j = M.readTree(r.body());
            cost = j.path("costDollars").path("total").asDouble(PRICE);
            lastCost = cost;
            USED.incrementAndGet();
            List<PlaceRows.Hit> hits = new ArrayList<>();
            for (JsonNode h : j.path("results")) {
                String url = h.path("url").asText("");
                if (!url.matches("(?i)^https?://.+")) continue;
                List<String> lines = new ArrayList<>();
                String date = h.path("publishedDate").asText("");
                String by = h.path("author").asText("").strip();
                String meta = (date.length() >= 10 ? "published " + date.substring(0, 10) : "") + (by.isEmpty() ? "" : (date.length() >= 10 ? " · " : "") + "by " + PlaceRows.cut(by, 80));
                if (!meta.isBlank()) lines.add(meta);
                List<String> passages = new ArrayList<>();
                for (JsonNode p : h.path("highlights")) if (!p.asText("").isBlank()) passages.add(p.asText());
                String text = !passages.isEmpty() ? String.join(" … ", passages) : h.path("summary").asText(h.path("text").asText(""));
                if (!text.isBlank()) lines.add(PlaceRows.cut(text, 600));
                hits.add(new PlaceRows.Hit(h.path("title").asText("").strip(), url, lines));
            }
            return new Answer(hits, r.status(), "");
        } catch (Exception e) {
            return new Answer(List.of(), made ? 200 : 0, made ? "" : why(0));
        } finally {
            SearchTally.settle(TALLY, made, 0, cost);
        }
    }

    static String why(int status) {
        return switch (status) {
            case 401, 403 -> "Exa refused the key";
            case 402 -> "the Exa account's credits are used up";
            case 429 -> "Exa says too many searches came at once";
            case 0 -> "Exa did not answer";
            default -> "Exa answered " + status;
        };
    }

    @Override public String name() { return "exa_search"; }

    @Override public String description() {
        return "Search the web by meaning through Exa: each hit with the passages of its page that match, so a page can be judged before it is read. "
                + "It finds pages about an idea or like a description when the words of a web search miss them. Paid: each search counts against the month's limit.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "What the pages are about, in words; a description works as well as keywords.");
        props.putObject("limit").put("type", "integer").put("description", "How many pages (default 8, at most 10).");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 10);
        Answer a = search(query, limit, policy.siteList());
        if (a.status() == -1) return a.said();
        if (!a.answered()) return "ERROR: " + a.said() + ". This is not a search that found nothing: search the web instead.";
        if (a.hits().isEmpty()) return "No pages from Exa for: " + query + ".";
        return PlaceRows.render(query, " (Exa, the web by meaning)", a.hits(), limit, policy);
    }
}
