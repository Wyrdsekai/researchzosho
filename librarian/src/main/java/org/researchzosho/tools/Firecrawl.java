package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A page read through Firecrawl's browser (firecrawl.dev) when a plain fetch cannot read it: a page built by scripts, a bot wall, a
 * 403. Paid per page after the free credits, so only a key the person set turns it on, and a run reads at most a few pages this way.
 * The page comes back as markdown and goes through the same page check as any fetch; the key is sent only to Firecrawl.
 */
public final class Firecrawl {
    private Firecrawl() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String KEY = "RESEARCHZOSHO_FIRECRAWL_KEY";
    /** The setting that holds the most pages a month the library reads through Firecrawl; there is none unless one is set. */
    public static final String LIMIT = "RESEARCHZOSHO_FIRECRAWL_MONTHLY_LIMIT";
    /** The name the tally and the monthly limit count these pages under. */
    public static final String TALLY = "Firecrawl";
    /** The service's address; a test points it at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("scrape", "https://api.firecrawl.dev/v2/scrape"); }
    /** Pages read through Firecrawl, session-wide: the report says so. */
    public static final AtomicInteger USED = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public static boolean on() { String k = Config.get(KEY); return k != null && !k.isBlank(); }

    /**
     * The page as Firecrawl's browser saw it, as a fetch result holding markdown, or null: no key, this month's pages used, a failure, or
     * a page Firecrawl could not read either (its status 400 or more). A page is reserved before it is asked for and counted once
     * Firecrawl read it, so workers fetching at once never pass the month's limit together.
     */
    public static Fetch.Result scrape(String url, Duration timeout) {
        String key = Config.get(KEY);
        if (key == null || key.isBlank() || url == null || url.isBlank()) return null;
        if (!SearchTally.reserve(TALLY)) return null;   // the month's limit is reached: the fetch goes on without it, and says so
        boolean made = false;
        int credits = 0;
        try {
            var body = M.createObjectNode().put("url", url).put("onlyMainContent", true).put("timeout", (int) Math.min(timeout.toMillis(), 60_000));
            body.putArray("formats").add("markdown");
            HttpResponse<String> resp = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(BASE.get("scrape"))).timeout(timeout.plusSeconds(10))
                    .header("Authorization", "Bearer " + key.strip()).header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(), timeout.plusSeconds(10), "Firecrawl");
            if (resp.statusCode() != 200) return null;
            JsonNode j = M.readTree(resp.body());
            if (!j.path("success").asBoolean(false)) return null;
            JsonNode d = j.path("data");
            made = true; credits = d.path("metadata").path("creditsUsed").asInt(j.path("creditsUsed").asInt(1));   // Firecrawl read the page and charged for it
            String md = d.path("markdown").asText("");
            int status = d.path("metadata").path("statusCode").asInt(200);
            if (md.isBlank() || status >= 400) return null;
            String title = d.path("metadata").path("title").asText("").strip(), source = d.path("metadata").path("sourceURL").asText("").strip();
            String text = (title.isEmpty() || md.startsWith("# ") ? "" : "# " + title + "\n\n") + md;
            USED.incrementAndGet();
            return new Fetch.Result(source.isEmpty() ? url : source, 200, text.getBytes(StandardCharsets.UTF_8), "text/markdown; charset=utf-8");
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
        finally { SearchTally.settle(TALLY, made, credits, 0); }
    }

    /** What a fetch that Firecrawl would have read says when this month's pages through it are used; "" otherwise. */
    static String usedUp() { return on() && SearchTally.reached(TALLY) ? " " + SearchTally.usedUp(TALLY, "Firecrawl") : ""; }
}
