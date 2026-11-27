package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PlaceRows;
import org.researchzosho.tools.WebSearchTool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the career search reads openings (docs/DESIGN_CAREER.md, step 3). A company's own hiring feed first: Greenhouse, Lever, Ashby,
 * Workable, Recruitee, Personio and Workday each publish their customers' openings for anyone to read, and since 0.8.4 SmartRecruiters,
 * Teamtailor, Rippling, Paylocity, Pinpoint, BambooHR and JazzHR ({@link CareerSystems}). Else the job data a careers page
 * carries for search engines, else the job links on the page itself. Beside those, the open listings: the month's Hacker News "Who is
 * hiring" thread, and USAJobs for federal work with a key of the person's own. Never a job board in between.
 *
 * <p>A company's feed is found through a search for its careers page: a feed address in the results that carries the company's name,
 * a feed the company's own careers page links to, or, last, the feed by the company's name where the feed says whose it is. A feed found
 * by a loose name alone is not used, because the name can be someone else's.
 *
 * <p>Manners: every request names researchzosho.org; robots rules are read and kept; one request a second to a site, or slower when its
 * robots rules ask.
 */
public final class CareerSources {
    private CareerSources() { }

    /**
     * One opening as read. {@code posted}: the day it was posted, when the feed says ("" when not); {@code postedNote}: what the feed says
     * instead ("more than 30 days ago"); {@code text}: its words, "" when only the list was read; {@code detail}: where its words are
     * read when they are needed; {@code pay}: the pay it states, "" when none.
     */
    public record Opening(String id, String company, String title, String location, boolean remote, String url, String posted, String postedNote,
                          String source, String text, String detail, String pay) {
        public Opening withText(String t) { return new Opening(id, company, title, location, remote, url, posted, postedNote, source, t, detail, pay); }
    }

    /** A company's feed: the hiring system, the company's name in it, and how it was found. */
    public record Feed(String kind, String token, String how) {
        public String label() { return kind + ":" + token; }
        /** The hiring system's name as people say it. */
        public String system() {
            return switch (kind) {
                case "greenhouse" -> "Greenhouse"; case "lever" -> "Lever"; case "ashby" -> "Ashby"; case "workable" -> "Workable";
                case "recruitee" -> "Recruitee"; case "personio" -> "Personio"; case "workday" -> "Workday";
                case "phenom" -> "own careers site"; case "oracle" -> "Oracle"; case "icims" -> "iCIMS"; case "taleo" -> "Taleo"; case "peopleadmin" -> "PeopleAdmin";
                case "smartrecruiters" -> "SmartRecruiters"; case "teamtailor" -> "Teamtailor"; case "rippling" -> "Rippling"; case "paylocity" -> "Paylocity";
                case "pinpoint" -> "Pinpoint"; case "bamboohr" -> "BambooHR"; case "jazzhr" -> "JazzHR"; case "levereu" -> "Lever";
                case "jobposting" -> "the job data on its careers page"; case "links" -> "the job links on its careers page";
                default -> kind;
            };
        }
    }

    /** What reading a company gave: its feed (null when none turned up), its openings, and a note on what could not be read. */
    public record Found(String company, Feed feed, List<Opening> openings, String note) { }

    /** A search result: its title and address. */
    public record Hit(String title, String url, String snippet) {
        public Hit(String title, String url) { this(title, url, ""); }
    }

    static final ObjectMapper M = new ObjectMapper();
    static final int TEXT_MAX = 8000;

    /** A page or a feed, read as it is, with no robots check: an address to its body, or null. A test puts a stand-in here. */
    static volatile Function<String, String> RAW = CareerSources::fetch;
    /** A page read with request headers of the caller's (a key, a token), for the registers that ask for one; a test puts its own here. */
    static volatile BiFunction<String, Map<String, String>, String> RAW_HEADED = CareerSources::fetchWith;
    /** A POST with a JSON body, as Workday's careers sites ask for their job lists; null when it fails. A test puts a stand-in here. */
    static volatile BiFunction<String, String, String> RAW_POST = CareerSources::post;
    /** The web search: a query to its results. A test puts a stand-in here. */
    static volatile Function<String, List<Hit>> SEARCH = CareerSources::search;
    /** The least time between two requests to one site, in milliseconds; a site's robots rules may ask for more. Tests set 0. */
    static volatile long POLITE_MS = 1000;

    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<String, Robots> ROBOTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> ROBOTS_AT = new ConcurrentHashMap<>();
    /** A site's robots rules are read again after a day: the service runs for weeks, and a site may change its rules. */
    static final long ROBOTS_DAY_MS = 86_400_000L;
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());

    /** Forgets the robots rules, the request times and the sites' "later" read so far; for tests. */
    static void reset() { LAST.clear(); ROBOTS.clear(); ROBOTS_AT.clear(); LATER.clear(); RESTING.clear(); CareerSystems.forget(); }

    // ---------------------------------------------------------------- a site that says "later"

    /** A site that answered "later" (429 too many requests, 503 busy): when it may be asked again, in milliseconds since the epoch. */
    private static final Map<String, Long> LATER = new ConcurrentHashMap<>();
    /** Whether a read on this thread ended with its site still saying "later": its board is there, its list was not read this time. */
    private static final ThreadLocal<Boolean> SAID_LATER = ThreadLocal.withInitial(() -> false);
    /** How many times a request is asked again after a "later", the wait when the site names none, and the longest wait it may name. */
    static final int LATER_TRIES = 2;
    static final long LATER_DEFAULT_MS = 10_000, LATER_MAX_MS = 30_000;
    /** What a board's note says when its system said "later" every time: the board is kept and read at the next search. */
    static final String LATER_NOTE = "asked the library to come back later";
    /** How the career code waits for a site; a test puts its own here. */
    static volatile LongConsumer SLEEP = ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
    /** The library's own GET, whatever it answers, redirects followed; a test puts its own here. */
    static volatile BiFunction<String, Map<String, String>, Fetch.Result> GET = (url, headers) -> {
        try { return Fetch.get(url, Duration.ofSeconds(30), headers, Fetch.Policy.DEFAULT); } catch (Exception e) { return null; }
    };

    static boolean later(int status) { return status == 429 || status == 503; }

    /** The wait a site's Retry-After asks for, in milliseconds: seconds, or an HTTP date; the default when it names none; never more than the most. */
    static long laterMs(String retryAfter) {
        String v = retryAfter == null ? "" : retryAfter.strip();
        long ms = LATER_DEFAULT_MS;
        if (v.matches("\\d{1,6}")) ms = Long.parseLong(v) * 1000;
        else if (!v.isEmpty()) {
            try { ms = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis(); } catch (Exception ignored) { }
        }
        return Math.max(1000, Math.min(LATER_MAX_MS, ms));
    }

    /**
     * A request asked again when its site says "later": after the wait the site names (Retry-After, at most {@link #LATER_MAX_MS}; else
     * {@link #LATER_DEFAULT_MS}), at most {@link #LATER_TRIES} more times, and the site's other requests wait as long ({@link #pace}).
     * When it still says "later", the read on this thread is marked so (its board is not taken for no board).
     */
    static <T> T askAgainIfLater(String url, Supplier<T> ask, ToIntFunction<T> status, Function<T, String> retryAfter) {
        String h = host(url);
        Wanted w = WANTED.get();
        if (resting(url) || w != null && LATER.getOrDefault(h, 0L) > System.currentTimeMillis()) { laterSaid(); return null; }   // answered "later" at once: no request, no wait
        T r = ask.get();
        if (w != null) {   // in a career search: a site that says "later" rests until the time it named, and the search goes on with the others
            if (r != null && later(status.applyAsInt(r))) {
                int times = w.laterTimes().merge(h, 1, Integer::sum);   // each time it says so again in this search, it rests twice as long
                long span = Math.max(LATER_DEFAULT_MS, laterAsked(retryAfter.apply(r))) << Math.min(10, times - 1);
                long until = System.currentTimeMillis() + Math.min(REST_MAX_MS, span);
                LATER.merge(h, until, Math::max);
                w.laterSites().merge(h, until, Math::max);
                laterSaid();
            }
            return r;
        }
        for (int i = 0; i < LATER_TRIES && r != null && later(status.applyAsInt(r)); i++) {
            long wait = laterMs(retryAfter.apply(r));
            if (i > 0 && laterAsked(retryAfter.apply(r)) > LATER_MAX_MS) break;   // it asked again for longer than a search waits: one wait of the most is all it gets
            LATER.put(host(url), System.currentTimeMillis() + wait);
            SLEEP.accept(wait);
            r = ask.get();
        }
        if (r != null && later(status.applyAsInt(r))) {
            long asked = laterAsked(retryAfter.apply(r));
            LATER.put(host(url), System.currentTimeMillis() + laterMs(retryAfter.apply(r)));
            RESTING.put(host(url), System.currentTimeMillis() + Math.max(REST_MIN_MS, asked));
            laterSaid();
        }
        return r;
    }

    /**
     * Whether a site said "later" on this thread since {@link #ANY_LATER} was last set false, or a found posting's check could not be
     * made (its board did not answer, or refused): a board guessed then is not known yet, never none, and a candidate is carried unchecked.
     */
    static final ThreadLocal<Boolean> ANY_LATER = ThreadLocal.withInitial(() -> false);
    /** The status of this thread's last answer through {@link #getAgainIfLater}; -1 when none came. */
    static final ThreadLocal<Integer> LAST_STATUS = ThreadLocal.withInitial(() -> -1);

    private static void laterSaid() { SAID_LATER.set(true); ANY_LATER.set(true); }

    /**
     * Sites that still said "later" when asked again, until when (0.8.4): no request is made to one before then, at least two minutes,
     * so a search does not wait on it again for each of its employers (Workable answered seeker 34's paid search 429 for every account
     * asked, each costing two waits of thirty seconds: 8.5 of that search's 11.6 minutes). Its employers keep their boards, read next time.
     */
    private static final Map<String, Long> RESTING = new ConcurrentHashMap<>();
    static final long REST_MIN_MS = 120_000;

    static boolean resting(String url) { return RESTING.getOrDefault(host(url), 0L) > System.currentTimeMillis(); }

    /** The wait a site asked for, uncapped, in milliseconds; the default when it named none. */
    static long laterAsked(String retryAfter) {
        String v = retryAfter == null ? "" : retryAfter.strip();
        if (v.matches("\\d{1,9}")) return Long.parseLong(v) * 1000;
        if (!v.isEmpty()) { try { return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis(); } catch (Exception ignored) { } }
        return LATER_DEFAULT_MS;
    }

    static Fetch.Result getAgainIfLater(String url, Map<String, String> headers) {
        Wanted w = WANTED.get();
        String key = url + " " + new TreeMap<>(headers);
        if (w != null) { Fetch.Result had = w.answers().get(key); if (had != null) { LAST_STATUS.set(had.status()); return had; } }   // asked already in this search: a board's name, then its list (0.8.4)
        Fetch.Result r = askAgainIfLater(url, () -> GET.apply(url, headers), Fetch.Result::status, Fetch.Result::retryAfter);
        LAST_STATUS.set(r == null ? -1 : r.status());
        if (w != null && r != null && !later(r.status()) && r.body() != null && r.body().length <= 2_000_000 && w.answers().size() < 2000) w.answers().put(key, r);
        return r;
    }

    static String fetch(String url) {
        Fetch.Result r = getAgainIfLater(url, Map.of());
        return r != null && r.status() == 200 ? read(new String(r.body(), StandardCharsets.UTF_8)) : null;
    }

    static String fetchWith(String url, Map<String, String> headers) {
        try {
            URI u = URI.create(url);
            if (Fetch.refusal(u) != null || Fetch.Policy.DEFAULT.leftOut(url) != null) return null;
            HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).header("User-Agent", Fetch.USER_AGENT).header("Accept", "application/json");
            headers.forEach(b::header);
            HttpResponse<String> resp = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    }

    /** What a data service answered: the body, and its headers with lower-case names (a count of the whole, a page's bounds). */
    public record Answer(String body, Map<String, String> headers) {
        public String header(String name) { return headers == null ? "" : headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }

    /** A fetch that keeps the answer's headers; a test puts its own here. */
    static volatile BiFunction<String, Map<String, String>, Answer> RAW_ANSWER = CareerSources::fetchAnswer;

    static Answer fetchAnswer(String url, Map<String, String> headers) {
        try {
            URI u = URI.create(url);
            if (Fetch.refusal(u) != null || Fetch.Policy.DEFAULT.leftOut(url) != null) return null;
            HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(30)).header("User-Agent", Fetch.USER_AGENT).header("Accept", "application/json");
            headers.forEach(b::header);
            HttpResponse<String> resp = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return null;
            Map<String, String> hs = new HashMap<>();
            resp.headers().map().forEach((k, v) -> { if (k != null && !v.isEmpty()) hs.put(k.toLowerCase(Locale.ROOT), v.get(0)); });
            return new Answer(resp.body(), hs);
        } catch (Exception e) { return null; }
    }

    /** A data service read with the caller's headers, the answer's headers kept, under the same rules and pace as {@link #get(String)}; null when it did not answer. */
    static Answer getAnswer(String url, Map<String, String> headers) {
        if (!allowed(url)) return null;
        pace(url);
        return RAW_ANSWER.apply(url, headers);
    }

    /** A page read with the caller's headers, under the same rules and pace as {@link #get(String)}. */
    static String get(String url, Map<String, String> headers) {
        if (!allowed(url)) return null;
        pace(url);
        return read(RAW_HEADED.apply(url, headers));
    }

    /** What a site answered, whatever its status: the address it came from after any redirect, the status, the body. */
    public record Page(String url, int status, String body) { }

    /** A page read with the caller's headers, redirects followed, its status kept; null when nothing answered. A test puts a stand-in here. */
    static volatile BiFunction<String, Map<String, String>, Page> RAW_PAGE = CareerSources::fetchPage;

    static Page fetchPage(String url, Map<String, String> headers) {
        Fetch.Result r = getAgainIfLater(url, headers);
        return r == null ? null : new Page(r.url(), r.status(), new String(r.body(), StandardCharsets.UTF_8));
    }

    /** A page or a data service under the site's robots rules and at the polite pace, whatever it answers: a posting that is gone says so by its status. */
    static Page page(String url, Map<String, String> headers) {
        if (!allowed(url)) return null;
        pace(url);
        Page p = RAW_PAGE.apply(url, headers);
        if (p != null) read(p.body());
        return p;
    }

    /** A page's body when it answers 200, under the same rules and pace, redirects followed; null otherwise. */
    static String body(String url, Map<String, String> headers) {
        Page p = page(url, headers);
        return p != null && p.status() == 200 ? p.body() : null;
    }

    /**
     * One posting read from its own address (0.8.4): the board it belongs to, the opening (title, employer, place, remote or not, the day
     * it was posted, its words), and whether the system still lists it, null when its answer did not say. For the systems read since 0.8.4
     * (SmartRecruiters, Teamtailor, Rippling, Paylocity, Pinpoint, BambooHR, JazzHR, Lever's European service), Workday, and Workable's
     * short addresses; null for any other address, or when the site's robots rules close it.
     */
    public record Posting(Feed feed, Opening opening, Boolean listed) { }

    public static Posting postingAt(String url) { return CareerSystems.postingAt(url); }

    static String post(String url, String json) {
        try {
            URI u = URI.create(url);
            if (Fetch.refusal(u) != null || Fetch.Policy.DEFAULT.leftOut(url) != null) return null;
            HttpRequest r = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).header("User-Agent", Fetch.USER_AGENT)
                    .header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = askAgainIfLater(url, () -> { try { return HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); } catch (Exception e) { return null; } },
                    HttpResponse::statusCode, x -> x.headers().firstValue("Retry-After").orElse(""));   // a Workday site that says "later" is asked again, as every site is
            return resp != null && resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    }

    /** The library's own web search, read back as titles and addresses. */
    static List<Hit> search(String query) {
        List<Hit> out = new ArrayList<>();
        try {
            String text = new WebSearchTool().execute(M.createObjectNode().put("query", query).put("limit", ROWS.get()));
            LAST_ENGINE.set(WebSearchTool.ANSWERED.get());
            noted(text);
            String title = null;
            for (String line : (text == null ? "" : text).split("\n")) {
                if (line.matches("\\d+\\. .*")) title = line.replaceFirst("^\\d+\\. ", "").strip();
                else if (line.startsWith("   http") && title != null) { out.add(new Hit(title, line.strip().replaceFirst("\\s+\\[[^\\]]*\\]$", ""), "")); title = null; }
                else if (line.startsWith("   ") && title == null && !out.isEmpty()) { Hit last = out.get(out.size() - 1); out.set(out.size() - 1, new Hit(last.title(), last.url(), (last.snippet() + " " + line.strip()).strip())); }   // what the engine says of the page
            }
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * How many hits one web search asks for: ten, and twenty for the search by site ({@link #discover}), where three systems share one
     * search's hits and an engine that answers ten crowded the third out (Brave gives twenty at the same price, its documented most;
     * Google through Serper or SerpApi twenty as the web search asks them; SearXNG answers what its engines give whatever is asked).
     */
    static final ThreadLocal<Integer> ROWS = ThreadLocal.withInitial(() -> 10);

    /** Which engine answered this thread's last web search ({@link WebSearchTool#ANSWERED}); the search by site takes no answer of the built-in fallback. */
    static final ThreadLocal<String> LAST_ENGINE = ThreadLocal.withInitial(() -> "");

    /** What the web search said of the engines it passed over in a career search ("Brave is left off …"), once each. */
    private static final Set<String> NOTES = ConcurrentHashMap.newKeySet();

    /** Forgets the notes: a career search begins. */
    public static void forgetNotes() { NOTES.clear(); }

    /** The web search's notes since {@link #forgetNotes()}. */
    public static Set<String> notesSoFar() { return new LinkedHashSet<>(NOTES); }

    static void noted(String text) {
        if (text == null || text.isBlank()) return;
        String[] lines = text.strip().split("\n");
        String last = lines[lines.length - 1].strip();   // the web search's own note comes last, in parentheses: a paid engine passed over at its month's limit
        if (last.startsWith("(") && last.endsWith(")") && last.length() > 2 && !last.startsWith("(The text between the ")) NOTES.add(last.substring(1, last.length() - 1).strip());
    }

    /** A page or a feed under the site's robots rules and at the polite pace; null when the rules say no or the read fails. */
    static String get(String url) {
        if (!allowed(url)) return null;
        pace(url);
        return read(RAW.apply(url));
    }

    /** A page the careers code read: for the board lookup ({@code Outbound.FINDS}) the hosts it names may be asked next; nothing for any other tool. */
    private static String read(String body) { Outbound.found(body); return body; }

    /** A web search of the careers code's own: its hits' hosts may be asked next by the board lookup. */
    static List<Hit> searched(String query) {
        List<Hit> hits = SEARCH.apply(query);
        if (hits != null) for (Hit h : hits) if (h != null && h.url() != null) Outbound.found(h.url());
        return hits;
    }

    static String postJson(String url, String json) {
        if (!allowed(url)) return null;
        pace(url);
        return RAW_POST.apply(url, json);
    }

    static String host(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (Exception e) { return ""; }
    }

    private static void pace(String url) {
        String h = host(url);
        if (resting(url) || WANTED.get() != null && LATER.getOrDefault(h, 0L) > System.currentTimeMillis()) return;   // no request will be made to it: nothing to wait for
        Robots r = ROBOTS.get(h);
        long gap = Math.max(POLITE_MS, r == null ? 0 : Math.min(10_000, r.delayMs()));
        long until = LATER.getOrDefault(h, 0L);   // a site that said "later" is not asked before the time it named
        if (gap <= 0 && until <= System.currentTimeMillis()) return;
        long wait;
        synchronized (LAST) {   // the request's own time at its site is taken here; the wait for it is outside, so a site's wait holds up no other site (0.8.4: it held every reading thread)
            long now = System.currentTimeMillis(), last = LAST.getOrDefault(h, 0L);
            long at = Math.max(now, Math.max(last + gap, now + Math.min(LATER_MAX_MS, until - now)));
            LAST.put(h, at);
            wait = at - now;
        }
        if (wait > 0) SLEEP.accept(wait);
    }

    /**
     * Published APIs the library uses under their own terms, with a user agent that names it: the SEC's search and archive, Wikidata's
     * query service and API, the open mirror of YC's directory. Their robots files speak to page crawlers and say nothing of an API
     * call made at a polite pace; a board's data service whose robots file refuses every reader and that publishes no terms stays refused.
     */
    static final Set<String> API_HOSTS = Set.of("api.census.gov", "efts.sec.gov", "www.sec.gov", "query.wikidata.org", "www.wikidata.org", "yc-oss.github.io", "hn.algolia.com", "data.usajobs.gov",
            // the records a home's card reads: documented data services for programs, whose robots rules are written for page crawlers (FEMA's map service disallows /arcgis to them; the
            // Mobility Database's download host disallows everything; the Urban Institute's data API disallows /api). The library reads each once per card, paced as every host is.
            "hazards.fema.gov", "files.mobilitydatabase.org", "educationdata.urban.org", "geocoding.geo.census.gov", "data.cityofnewyork.us", "data.boston.gov", "data.wprdc.org",
            // RentCast's listings API, read with the person's own key; the catalogues of the platforms cities publish their records on
            "api.rentcast.io", "api.us.socrata.com", "hub.arcgis.com", "phl.carto.com",
            // Japan: the ministry's records API, read with the person's own key, and the Geospatial Information Authority's address search; neither publishes a robots file
            "www.reinfolib.mlit.go.jp", "msearch.gsi.go.jp");

    /** Whether an address is a documented data API by its shape: a Socrata resource, a CKAN action, an ArcGIS service. Their robots rules are written for page crawlers. */
    static boolean dataApi(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String h = host(url);
        return h.endsWith(".arcgis.com") || u.contains("/resource/") && u.contains(".json") || u.contains("/api/3/action/") || u.contains("/rest/services/");
    }

    /** Whether the site's robots rules let the library read this address; a site with none, or none that answer, allows it. */
    static boolean allowed(String url) {
        String h = host(url);
        if (h.isEmpty()) return false;
        if (API_HOSTS.contains(h) || dataApi(url)) return true;
        Long at = ROBOTS_AT.get(h);
        if (at != null && System.currentTimeMillis() - at > ROBOTS_DAY_MS) ROBOTS.remove(h);
        Robots r = ROBOTS.computeIfAbsent(h, k -> {
            ROBOTS_AT.put(k, System.currentTimeMillis());
            String scheme;
            try { scheme = URI.create(url).getScheme(); } catch (Exception e) { scheme = "https"; }
            return Robots.parse(RAW.apply(scheme + "://" + k + "/robots.txt"));
        });
        String path;
        try { URI u = URI.create(url); path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()); }
        catch (Exception e) { return false; }
        return r.allows(path);
    }

    /** A site's robots rules for the library: the group that names ResearchZosho, else the one for every reader. */
    record Robots(List<String[]> rules, long delayMs) {
        static final Robots NONE = new Robots(List.of(), 0);

        static Robots parse(String text) {
            if (text == null || text.isBlank() || text.strip().startsWith("<")) return NONE;
            List<String[]> star = new ArrayList<>(), ours = new ArrayList<>();
            long starDelay = 0, ourDelay = 0;
            boolean inStar = false, inOurs = false, lastWasAgent = false;
            for (String raw : text.split("\\R")) {
                String line = raw.replaceFirst("#.*", "").strip();
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).strip().toLowerCase(Locale.ROOT), v = line.substring(c + 1).strip();
                if (k.equals("user-agent")) {
                    if (!lastWasAgent) { inStar = false; inOurs = false; }
                    String a = v.toLowerCase(Locale.ROOT);
                    if (a.equals("*")) inStar = true;
                    if (a.contains("researchzosho")) inOurs = true;
                    lastWasAgent = true;
                    continue;
                }
                lastWasAgent = false;
                if (k.equals("allow") || k.equals("disallow")) {
                    String[] rule = {k, v};
                    if (inOurs) ours.add(rule);
                    if (inStar) star.add(rule);
                } else if (k.equals("crawl-delay")) {
                    long ms;
                    try { ms = (long) (Double.parseDouble(v) * 1000); } catch (NumberFormatException e) { continue; }
                    if (inOurs) ourDelay = ms;
                    if (inStar) starDelay = ms;
                }
            }
            return ours.isEmpty() && ourDelay == 0 ? new Robots(star, starDelay) : new Robots(ours, ourDelay);
        }

        /** The longest rule that matches wins; at equal length, allow; an empty disallow allows everything. */
        boolean allows(String path) {
            int best = -1; boolean allow = true;
            for (String[] r : rules) {
                if (r[1].isEmpty()) continue;
                if (!matches(r[1], path)) continue;
                int len = r[1].length();
                boolean a = r[0].equals("allow");
                if (len > best || (len == best && a)) { best = len; allow = a; }
            }
            return allow;
        }

        static boolean matches(String rule, String path) {
            boolean end = rule.endsWith("$");
            String body = end ? rule.substring(0, rule.length() - 1) : rule;
            StringBuilder re = new StringBuilder();
            for (String part : body.split("\\*", -1)) { if (!re.isEmpty()) re.append(".*"); re.append(Pattern.quote(part)); }
            return Pattern.compile(re + (end ? "$" : ".*"), Pattern.DOTALL).matcher(path).matches();
        }
    }

    // ---------------------------------------------------------------- finding a company's feed

    static final Pattern[] BOARDS = {
            Pattern.compile("(?i)(?:job-boards|boards)(?:\\.eu)?\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([a-z0-9_-]+)"),   // a board on Greenhouse's European host is read through the same service
            Pattern.compile("(?i)boards-api\\.greenhouse\\.io/v1/boards/([a-z0-9_-]+)"),
            Pattern.compile("(?i)jobs\\.lever\\.co/([a-z0-9_.-]+)"),
            Pattern.compile("(?i)jobs\\.ashbyhq\\.com/([a-z0-9_.%-]+)"),
            Pattern.compile("(?i)apply\\.workable\\.com/([a-z0-9_-]+)"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.recruitee\\.com"),
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.jobs\\.personio\\.(?:de|com))"),
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.wd\\d+\\.myworkdayjobs\\.com/(?:[a-z]{2}-[a-z]{2}/)?[a-z0-9_-]+)"),
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.fa\\.[a-z0-9]+\\.oraclecloud\\.com/hcmUI/CandidateExperience/[a-z]{2}(?:-[A-Z]{2})?/sites/[A-Za-z0-9_]+)"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.icims\\.com"),
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.taleo\\.net/careersection/[a-z0-9]+)/"),
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.peopleadmin\\.(?:com|ca))"),
            // 0.8.4: the employer's name in each system's addresses (SmartRecruiters keeps its spelling, as Oracle's and Workday's sites do)
            Pattern.compile("(?i)(?:jobs|careers)\\.smartrecruiters\\.com/(?:oneclick-ui/company/)?([a-z0-9_-]+)"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.teamtailor\\.com(?![a-z0-9.-])"),
            Pattern.compile("(?i)ats\\.rippling\\.com/(?:[a-z]{2}-[a-z]{2}/)?([a-z0-9_-]+)"),
            Pattern.compile("(?i)recruiting\\.paylocity\\.com/recruiting/jobs/all/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.pinpointhq\\.com(?![a-z0-9.-])"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.bamboohr\\.com/(?:careers|jobs)"),
            Pattern.compile("(?i)\\b([a-z0-9-]+)\\.applytojob\\.com(?![a-z0-9.-])"),
            Pattern.compile("(?i)jobs\\.eu\\.lever\\.co/([a-z0-9_.-]+)")};
    static final String[] KINDS = {"greenhouse", "greenhouse", "lever", "ashby", "workable", "recruitee", "personio", "workday", "oracle", "icims", "taleo", "peopleadmin",
            "smartrecruiters", "teamtailor", "rippling", "paylocity", "pinpoint", "bamboohr", "jazzhr", "levereu"};
    /** Words in a feed's address that are not a company's name in it. */
    static final Set<String> NOT_A_TOKEN = Set.of("embed", "j", "o", "api", "v1", "jobs", "job", "search", "apply", "oauth", "login", "static", "assets",
            "www", "app", "careers", "support", "blog", "help", "docs", "wday", "cxs", "en-us", "privacy", "legal");
    /** The systems whose employer's name in an address is spelled as the system spells it: their sites tell Xplor from xplor. */
    static final Set<String> SPELLING_KEPT = Set.of("smartrecruiters");

    /** Every hiring feed an address or a page names, in the order they appear. */
    static List<Feed> boardsIn(String text, String how) {
        List<Feed> out = new ArrayList<>();
        if (text == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < BOARDS.length; i++) {
            Matcher m = BOARDS[i].matcher(text);
            while (m.find()) {
                String t = SPELLING_KEPT.contains(KINDS[i]) ? m.group(1) : m.group(1).toLowerCase(Locale.ROOT);
                if (KINDS[i].equals("oracle")) {   // host/site, the site's own spelling kept: Oracle tells CX from cx
                    String[] p = m.group(1).split("/");
                    t = p[0].toLowerCase(Locale.ROOT) + "/" + p[p.length - 1];
                } else if (KINDS[i].equals("taleo")) {   // host/section
                    String[] p = t.split("/");
                    t = p[0] + "/" + p[p.length - 1];
                }
                if (KINDS[i].equals("workday")) {
                    String[] p = t.split("/");
                    String site = p[p.length - 1];
                    boolean postingOf = text.startsWith("/job/", m.end()) || text.startsWith("/details/", m.end());   // in a posting's address the part before /job/ is the site, whatever its name: Expedia's is "search", CNO's "Careers"
                    if (NOT_A_TOKEN.contains(site.toLowerCase(Locale.ROOT)) && !postingOf) continue;
                    String[] hostParts = p[0].split("\\.");
                    t = hostParts[0] + "/" + hostParts[1] + "/" + m.group(1).split("/")[m.group(1).split("/").length - 1];   // tenant/wdN/Site, the site's case kept
                } else if (NOT_A_TOKEN.contains(t.toLowerCase(Locale.ROOT)) || NOT_A_TOKEN.contains(t.split("\\.")[0].toLowerCase(Locale.ROOT))) continue;
                if (seen.add(KINDS[i] + ":" + t)) out.add(new Feed(KINDS[i], t, how));
            }
        }
        return out;
    }

    /** The company's name squashed: letters and digits only, lower case. */
    static String key(String s) { return CareerSearch.key(s); }

    /** Whether a feed's name in its system is the company's name: the same letters, or the name with a short tail (toast, toasttab). */
    static boolean tokenIs(Feed f, String company) {
        String t = key(f.kind().equals("workday") ? f.token().split("/")[0] : f.token().split("\\.")[0]), k = key(company);
        if (k.isEmpty() || t.isEmpty()) return false;
        if (t.equals(k)) return true;
        String bare = bare(company);
        return !bare.isEmpty() && (t.equals(bare) || (t.startsWith(bare) && t.length() - bare.length() <= 3));
    }

    /** The company's name without "The" and without the words its address leaves out: Notion Labs, Inc. is notion. */
    static String bare(String company) {
        String n = (company == null ? "" : company).strip().replaceFirst("(?i)^the\\s+", ""), was;
        do { was = n; n = n.replaceAll("(?i)[,.]?\\s+(inc|llc|ltd|corp|corporation|company|co|technologies|labs|software|group|hq)\\.?$", "").strip(); } while (!n.equals(was));
        return key(n);
    }

    /** Whether a search result looks like the company's own careers page: careers or jobs in it, and the company's own site or name. */
    static boolean looksLikeCareers(Hit h, String company) {
        String u = h.url().toLowerCase(Locale.ROOT), t = h.title().toLowerCase(Locale.ROOT);
        boolean careers = u.contains("career") || u.contains("/jobs") || u.contains("jobs.") || u.contains("/join") || t.contains("career") || t.contains("jobs");
        if (!careers) return false;
        if (SourceClass.Own.of(List.of(company), "organization").owns(h.url())) return true;
        String k = key(company), hk = key(host(h.url()));
        if (k.length() >= 4 && hk.contains(k)) return true;
        return institutionNamed(h, company);
    }

    /**
     * An institution's own careers page, whose host rarely carries its name (careers.umich.edu for the University of Michigan): a result
     * whose title or address carries the name whole, or the phrase before its first generic word ("Johns Hopkins" of Johns Hopkins
     * Hospital), on a host that is no aggregator. Only for a name of an institution's kind; "Michigan State University" does not carry
     * "University of Michigan".
     */
    static boolean institutionNamed(Hit h, String company) {
        if (company == null || company.isBlank() || CareerPortals.kindWords(company).isEmpty() || aggregator(h.url())) return false;
        List<String> phrases = new ArrayList<>();
        phrases.add(company.strip().replaceFirst("(?i)^the\\s+", ""));
        phrases.addAll(CareerPortals.systems(company));
        String t = (h.title() + " " + h.url().replace('-', ' ').replace('/', ' ').replace('.', ' ')).replace("'s", "").replace("'", "");
        for (String p : phrases) if (p.length() >= 4 && CareerCut.has(t, p.replace("'s", "").replace("'", ""))) return true;
        return false;
    }

    /** Job boards and aggregators: their pages list other companies' jobs, so the library never reads a company's openings from them. */
    static final List<String> AGGREGATORS = List.of("indeed.", "linkedin.", "glassdoor.", "ziprecruiter.", "monster.", "builtin.", "wellfound.", "angel.co",
            "simplyhired.", "careerbuilder.", "dice.com", "levels.fyi", "jooble.", "talent.com", "adzuna.", "naukri.", "otta.", "welcometothejungle.", "jenprace.", "theladders.");

    static boolean aggregator(String url) { String h = host(url); for (String a : AGGREGATORS) if (h.contains(a)) return true; return false; }

    /**
     * A company's openings. {@code words}: the title words the person's cut asks for, used where a site can only be searched (Workday's
     * long lists); empty reads the whole list. What could not be read is said in the note, in a sentence.
     */
    public static Found openings(String company, List<String> words) { return openings(company, words, List.of()); }

    /**
     * As above, for a company that came from a public record: {@code anchors} are the record's own words, its city, its state, its
     * officers' surnames. A board found for a name alone may be a namesake's, and a namesake's size would cut the company, so a board
     * is taken only when one of its postings names an anchor; otherwise the company has no board until its card says more.
     */
    public static Found openings(String company, List<String> words, List<String> anchors) {
        Found f = openingsByName(company, words);
        if (anchors == null || anchors.isEmpty() || f == null || f.feed() == null || anchored(f, anchors)) return f;
        return new Found(f.company(), null, List.of(), "a board found for its name (" + f.feed().label() + ") names none of the record's city, state or officers in its postings, so it is not taken as its own");
    }

    /** Whether one of the board's postings names one of the record's words, in its place or its text. */
    static boolean anchored(Found f, List<String> anchors) {
        if (saidLater(f)) return true;   // a board whose system said "later" kept no openings to look in: it stays the company's until it is read
        for (Opening o : f.openings()) for (String a : anchors) if (CareerCut.has(o.location(), a) || CareerCut.has(o.text(), a)) return true;
        return false;
    }

    /** The text of the last careers page a name search read, for the portals' kind check; "" when none. */
    private static final ThreadLocal<String> LAST_CAREERS_PAGE = ThreadLocal.withInitial(() -> "");
    static String lastCareersPage() { return LAST_CAREERS_PAGE.get(); }

    /**
     * A company's board found by its name (0.8.4: through what the library keeps of the employer, {@link CareerLookups}). A board kept
     * for the name is read without a search, and one that does not answer is looked up again at once; a name a search found nothing
     * readable for lately is not searched for again before a month is out; what a search finds is kept, with the day.
     */
    static Found openingsByName(String company, List<String> words) {
        LAST_CAREERS_PAGE.set("");
        String c = company == null ? "" : company.strip();
        if (c.isEmpty()) return new Found(c, null, List.of(), "it has no name");
        Feed kept = CareerLookups.board(c);
        if (kept != null) {
            Found x = read(kept, c, words);
            if (x != null) { CareerLookups.fromKept(); LAST_CAREERS_PAGE.set(CareerLookups.pageWords(c)); return x; }
            CareerLookups.boardFailed(kept);   // the kept board did not answer: looked up again now
        } else {
            String none = CareerLookups.noBoard(c);
            if (!none.isEmpty()) { CareerLookups.fromKept(); return new Found(c, null, List.of(), "a search for its careers page on " + none + " found nothing the library can read, so it is searched for again after a month"); }
        }
        CareerLookups.Asked asked = CareerLookups.asking();
        ANY_LATER.set(false);
        Found f = searchByName(c, words);
        if (f.feed() == null && ANY_LATER.get())   // a board guessed by its name could not be asked: not known yet, never none (0.8.4), and nothing is kept of it
            return new Found(c, null, List.of(), "its board is not known yet: a hiring system it may be on asked the library to come back later, so the next search looks again");
        if (f.feed() != null || !asked.unanswered()) CareerLookups.boardFound(c, f, lastCareersPage());   // a board found is kept; that none was found, only after a search that answered
        else if (asked.starved()) return new Found(c, null, List.of(), "it was not looked up in this search: its paid look-ups were made and no free search engine answered, so the next search looks it up");
        return f;
    }

    /** The search for a company's careers page by its name, and what the page and its hiring feed give. */
    private static Found searchByName(String c, List<String> words) {
        List<Hit> hits = new ArrayList<>();
        for (Hit h : CareerLookups.search(c + " careers jobs")) if (!aggregator(h.url())) hits.add(h);
        for (Hit h : hits) for (Feed f : boardsIn(h.url(), "found in a search for its careers page"))   // a feed address with the company's own name
            if (tokenIs(f, c)) { Found x = read(f, c, words); if (x != null) return x; }
        Map<String, String> careers = new LinkedHashMap<>();   // the company's own careers pages, read once
        for (Hit h : hits) {
            if (!looksLikeCareers(h, c) || !boardsIn(h.url(), "").isEmpty() || careers.size() >= 2) continue;
            String page = get(h.url());
            if (page == null) continue;
            careers.put(h.url(), page);
            LAST_CAREERS_PAGE.set(plain(page));
            for (Feed f : boardsIn(page, "linked from its careers page, " + h.url())) { Found x = read(f, c, words); if (x != null && !x.openings().isEmpty()) return x; }
            Feed portal = CareerPortals.feedOfPage(h.url(), page);   // the portals institutions use: a Phenom careers site, Oracle, iCIMS, Taleo, PeopleAdmin
            if (portal != null) { Found x = read(portal, c, words); if (x != null && !x.openings().isEmpty()) return x; }
            List<Opening> posted = jobPostings(page, h.url(), c);
            if (!posted.isEmpty()) return new Found(c, new Feed("jobposting", h.url(), "the job data on its careers page"), posted, "");
        }
        Found named = byName(c, words);
        if (named != null) return named;
        for (Hit h : hits) for (Feed f : boardsIn(h.url(), "found in a search for its careers page; its name there is close to the company's"))   // a feed whose title names the company
            if (h.title().toLowerCase(Locale.ROOT).contains(c.toLowerCase(Locale.ROOT)) && key(f.token()).contains(key(c)) && key(c).length() >= 4) { Found x = read(f, c, words); if (x != null && !x.openings().isEmpty()) return x; }
        for (Map.Entry<String, String> e : careers.entrySet()) {   // last: the job links on its careers page, which may be a few featured roles
            List<Opening> links = jobLinks(e.getValue(), e.getKey(), c);
            if (links.size() >= LINKS_MIN) return new Found(c, new Feed("links", e.getKey(), "the job links on its careers page"), links, "");
        }
        return new Found(c, null, List.of(), hits.isEmpty() ? "the search found no careers page for it"
                : "no hiring feed or job list turned up on its careers pages; some companies build their job lists in the browser, which the library does not run");
    }

    /**
     * The company's board by its name, with openings on it. Greenhouse and Workable say whose a board is, and a board is used only when it
     * gives the company's name; Ashby and Lever do not say, so one of theirs is used only when most of its postings name the company.
     */
    static Found byName(String company, List<String> words) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String t : List.of(key(company), bare(company))) if (t.length() >= 3) tokens.add(t);
        for (String t : tokens) {
            try {
                String gh = get("https://boards-api.greenhouse.io/v1/boards/" + t);
                if (gh != null && sameName(M.readTree(gh).path("name").asText(""), company)) {
                    Found x = read(new Feed("greenhouse", t, "found by its name; the board calls itself " + M.readTree(gh).path("name").asText()), company, words);
                    if (x != null && !x.openings().isEmpty()) return x;
                }
                String wk = get("https://apply.workable.com/api/v1/widget/accounts/" + t);
                if (wk != null && sameName(M.readTree(wk).path("name").asText(""), company)) {
                    Found x = read(new Feed("workable", t, "found by its name; the account calls itself " + M.readTree(wk).path("name").asText()), company, words);
                    if (x != null && !x.openings().isEmpty()) return x;
                }
                for (String kind : List.of("ashby", "lever")) {
                    Found x = read(new Feed(kind, t, "found by its name; most of its postings name the company"), company, words);
                    if (x != null && namesItself(x.openings(), company)) return x;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Whether a board's own name is the company's: the same letters, with or without the words a company's address leaves out. */
    static boolean sameName(String boardName, String company) {
        String b = key(boardName);
        return !b.isEmpty() && (b.equals(key(company)) || b.equals(bare(company)) || bare(boardName).equals(bare(company)));
    }

    /** Whether most of a board's postings, of the first ten with words, name the company: a board under a name alone may be a namesake's. */
    static boolean namesItself(List<Opening> os, String company) {
        int with = 0, named = 0;
        String b = company.replaceFirst("(?i)^the\\s+", "").replaceAll("(?i)[,.]?\\s+(inc|llc|ltd|corp|corporation)\\.?$", "").strip();
        for (Opening o : os) {
            if (o.text().isBlank()) continue;
            if (++with > 10) break;
            if (o.text().toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) named++;
        }
        return with > 0 && named * 2 > Math.min(with, 10);
    }

    // ---------------------------------------------------------------- finding companies by what they are hiring for

    /**
     * A company found hiring for a title wanted: its name, its feed, the posting that showed it, and the way it was found: "search", a
     * search of the hiring systems' own job pages by site; "exa", the search by meaning through Exa; "" for any other way.
     */
    public record Discovered(String company, Feed feed, String posting, String url, String way, boolean checked) {
        public Discovered(String company, Feed feed, String posting, String url, String way) { this(company, feed, posting, url, way, true); }
        public Discovered(String company, Feed feed, String posting, String url) { this(company, feed, posting, url, ""); }
        /** The same, carried without its check: its board said "later" (0.8.4). */
        Discovered unchecked() { return new Discovered(company, feed, posting, url, way, false); }
    }

    /**
     * THE table of the hiring systems the career search looks through for postings (0.8.4): the host their job pages stand on (a "*." host
     * stands for every employer's own part of it, acme.wd5.myworkdayjobs.com), the system's name as people say it, and the shape of one
     * posting's address there. The search by site ({@link #discover}), the search by meaning (Exa's includeDomains, {@link #byMeaning})
     * and the check that a hit is one posting ({@link #posting}) all read it, so a system added here, with its address in {@link #BOARDS}
     * and its reader in {@link #read}, is searched by meaning without another change, and by site while it is among the rows the
     * search by site names ({@link #siteGroups}): adding a row adds no search.
     * The rows after Personio are the systems read since 0.8.4, in the order of how often the trial of 2026-11-26 met them.
     */
    public record Site(String host, String system, Pattern posting) {
        Site(String host, String system, String posting) { this(host, system, Pattern.compile("(?i)" + posting)); }
        /** The host as a search engine's site operator takes it: the whole domain for an employer's own part of it. */
        public String searched() { return host.startsWith("*.") ? host.substring(2) : host; }
        /** Whether an address stands on this system's host. */
        boolean hosts(String url) { String h = CareerSources.host(url); return host.startsWith("*.") ? h.endsWith(host.substring(1)) : h.equals(host); }
    }

    public static final List<Site> SITES = List.of(
            new Site("jobs.ashbyhq.com", "Ashby", "jobs\\.ashbyhq\\.com/[^/]+/[^/?#]{6,}"),
            new Site("job-boards.greenhouse.io", "Greenhouse", "greenhouse\\.io/[^/]+/jobs/\\d+"),
            new Site("boards.greenhouse.io", "Greenhouse", "greenhouse\\.io/[^/]+/jobs/\\d+"),
            new Site("jobs.lever.co", "Lever", "jobs\\.(?:eu\\.)?lever\\.co/[^/]+/[^/?#]{6,}"),
            new Site("apply.workable.com", "Workable", "apply\\.workable\\.com/(?:[^/]+/)?j/[^/?#]+"),
            new Site("*.myworkdayjobs.com", "Workday", "\\.myworkdayjobs\\.com/(?:[a-z]{2}-[a-z]{2}/)?[^/?#]+/(?:job|details)/[^?#]+"),
            new Site("*.recruitee.com", "Recruitee", "\\.recruitee\\.com/o/[^/?#]+"),
            new Site("*.jobs.personio.de", "Personio", "\\.jobs\\.personio\\.(?:de|com)/job/\\d+"),
            new Site("*.jobs.personio.com", "Personio", "\\.jobs\\.personio\\.(?:de|com)/job/\\d+"),
            new Site("jobs.smartrecruiters.com", "SmartRecruiters", "jobs\\.smartrecruiters\\.com/[^/?#]+/\\d{6,}"),
            new Site("*.teamtailor.com", "Teamtailor", "\\.teamtailor\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?jobs/\\d+"),
            new Site("ats.rippling.com", "Rippling", "ats\\.rippling\\.com/(?:[a-z]{2}-[a-z]{2}/)?[^/?#]+/jobs/[0-9a-f-]{36}"),
            new Site("recruiting.paylocity.com", "Paylocity", "recruiting\\.paylocity\\.com/recruiting/jobs/details/\\d+"),
            new Site("*.pinpointhq.com", "Pinpoint", "\\.pinpointhq\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?postings/[0-9a-f-]{36}"),
            new Site("*.applytojob.com", "JazzHR", "\\.applytojob\\.com/apply/[a-z0-9]{6,}"),
            new Site("*.bamboohr.com", "BambooHR", "\\.bamboohr\\.com/careers/\\d+"));


    /** The system of the table an address stands on; null when none. */
    static Site siteOf(String url) { return SITES.stream().filter(x -> x.hosts(url)).findFirst().orElse(null); }

    /** The hosts of the table, once each, as Exa's includeDomains takes them. */
    public static List<String> siteHosts() { return SITES.stream().map(Site::host).distinct().toList(); }

    /** How many whole titles the search looks for: each by site on the commonest systems of the table, and each a search by meaning when Exa is on. */
    public static final int SEARCH_TITLES = 3;

    /**
     * How many systems one search by site names, joined by OR: "(site:jobs.ashbyhq.com OR site:job-boards.greenhouse.io OR …)". Google,
     * Bing (and so Yahoo) and Brave document OR, Bing in capitals and inside parentheses beside other words, and Bing reads only the first
     * ten terms of a query, so three sites beside a title and up to three places (tried on Yahoo through SearXNG, 2026-11-26: one such
     * search answered from all three hosts).
     */
    static final int SITES_PER_SEARCH = 3;
    /**
     * How many searches by site each title gets. With {@link #SITES_PER_SEARCH} they reach the first nine systems of the table, the
     * commonest first; the rest are reached by the search by meaning, whose includeDomains takes every host of the table in one search,
     * and through the employers found any way, whose own lists are read. So a reader added to the table adds no search.
     */
    static final int SEARCHES_PER_TITLE = 3;

    /** The most searches by site one discovery makes: three titles, three searches each (15 before 0.8.4, one site a search). */
    static int discoverQueries() { return SEARCH_TITLES * SEARCHES_PER_TITLE; }

    /** The systems the search by site names, in groups of {@link #SITES_PER_SEARCH}: the first rows of the table, each domain once, as many groups as {@link #SEARCHES_PER_TITLE}. */
    static List<List<Site>> siteGroups(List<Site> rows) {
        // rows of one system on one domain are searched as that domain, once: job-boards.greenhouse.io and boards.greenhouse.io as
        // site:greenhouse.io, which an engine's site operator takes with every host under it (the European one too), so the second host
        // does not take a slot of its own (in the acceptance run of 2026-11-26 it was crowded out of every grouped search it was in)
        Map<String, Integer> perDomain = new HashMap<>();
        for (Site r : rows) perDomain.merge(r.system() + "|" + registrable(r.searched()), 1, Integer::sum);
        List<Site> first = new ArrayList<>();
        Set<String> domains = new LinkedHashSet<>();
        for (Site r : rows) {
            String dom = registrable(r.searched());
            Site term = perDomain.get(r.system() + "|" + dom) > 1 ? new Site("*." + dom, r.system(), r.posting()) : r;
            if (domains.add(term.searched()) && first.size() < SITES_PER_SEARCH * SEARCHES_PER_TITLE) first.add(term);
        }
        List<List<Site>> out = new ArrayList<>();
        for (int i = 0; i < first.size(); i += SITES_PER_SEARCH) out.add(first.subList(i, Math.min(first.size(), i + SITES_PER_SEARCH)));
        return out;
    }

    /** A group's sites as the query names them: one site alone, several joined by OR in parentheses. */
    static String sitesOf(List<Site> group) {
        if (group.size() == 1) return "site:" + group.get(0).searched();
        StringBuilder b = new StringBuilder("(");
        for (Site x : group) b.append(b.length() > 1 ? " OR " : "").append("site:").append(x.searched());
        return b.append(')').toString();
    }

    /**
     * The least time between two of the discovery's searches, in milliseconds: a free search engine suspends a client that asks too often
     * (SearXNG's upstreams answered "too many requests" in the trial of 2026-11-26), so the nine searches take at least 24 seconds. Tests set 0.
     */
    static volatile long SEARCH_GAP_MS = 3000;

    /** The gap in force: none where a test turned the polite pace off ({@link #POLITE_MS} 0). */
    static long searchGap() { return POLITE_MS <= 0 ? 0 : SEARCH_GAP_MS; }

    /** How many days back a posting found by meaning may have been published: older ones were mostly gone in the trial of 2026-11-26. */
    static final int EXA_DAYS = 30;

    /**
     * Companies hiring now for a title the person wants: the hiring systems' own job pages searched for each term, as it is given, with
     * the first place when there is one. Each posting found names its company's board, so the company comes with its feed. At most
     * {@code max} companies, from at most {@link #discoverQueries} searches.
     */
    public static List<Discovered> discover(List<String> terms, List<String> places, int max) { return discover(terms, places, max, title -> true, d -> true); }
    public static List<Discovered> discover(List<String> terms, List<String> places, int max, Predicate<String> titleOk) { return discover(terms, places, max, titleOk, d -> true); }

    /**
     * As above, keeping only postings whose own title {@code titleOk} lets through, the cut, so a company is found for a role that passes
     * it, and that {@code live} confirms: the company's own board, read now, still lists the posting, since a search engine keeps a job
     * page long after the board has taken it down (ten of sixteen on the first real run). The searches are {@link #SEARCH_GAP_MS} apart,
     * each naming a group of the table's systems ({@link #siteGroups}).
     */
    public static List<Discovered> discover(List<String> terms, List<String> places, int max, Predicate<String> titleOk, Predicate<Discovered> live) { return discover(terms, places, max, titleOk, live, new int[1]); }

    /** As above; {@code made[0]} says how many searches it made, and {@code made[1]}, when given, how many of them no search engine answered. */
    public static List<Discovered> discover(List<String> terms, List<String> places, int max, Predicate<String> titleOk, Predicate<Discovered> live, int[] made) {
        ROWS.set(20);
        try { return discovered(terms, places, max, titleOk, live, made); }
        finally { ROWS.remove(); }
    }

    private static List<Discovered> discovered(List<String> terms, List<String> places, int max, Predicate<String> titleOk, Predicate<Discovered> live, int[] made) {
        List<Discovered> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> ps = places == null ? List.of() : places.subList(0, Math.min(3, places.size()));
        String place = ps.isEmpty() ? "" : ps.size() == 1 ? " \"" + ps.get(0) + "\"" : " (\"" + String.join("\" OR \"", ps) + "\")";
        int queries = 0;
        long last = 0;
        Set<String> asked = new LinkedHashSet<>();
        List<Discovered> pending = new ArrayList<>();   // candidates whose board said "later": checked once at the end
        outer:
        for (String w : terms) {
            for (List<Site> group : siteGroups(SITES)) {
                if (out.size() >= max || queries >= discoverQueries()) break outer;
                String query = w + place + " " + sitesOf(group);
                if (!asked.add(query)) continue;   // a title given twice
                queries++;
                made[0] = queries;
                long wait = last + searchGap() - System.currentTimeMillis();
                if (last > 0 && wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break outer; } }
                last = System.currentTimeMillis();
                List<String> must = new ArrayList<>();
                Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(w);
                while (q.find()) must.add(q.group(1));
                LAST_ENGINE.set("");
                List<Hit> hits = searched(query);
                if (WebSearchTool.FALLBACK_NAME.equals(LAST_ENGINE.get())) { if (made.length > 1) made[1]++; continue; }   // no search engine answered: the fallback holds no posting
                for (Hit h : hits) {
                    Site site = siteOf(h.url());
                    String how = "found hiring: a search of " + (site == null ? "the hiring systems'" : site.system() + "'s") + " job pages for " + String.join(" ", must) + (ps.isEmpty() ? "" : " in " + String.join(" or ", ps)) + " gave ";
                    ANY_LATER.set(false);
                    Discovered d = hiring(h, jobTitle(h), must, titleOk, seen, how, "search");
                    if (d == null) continue;   // not a posting the cut lets through, or a company found already
                    if (!live.test(d)) { if (ANY_LATER.get()) pending.add(d); continue; }   // the board no longer lists it (the search engine's copy was stale), or its site said "later"
                    out.add(d);
                    if (out.size() >= max) break outer;
                }
            }
        }
        out.addAll(again(pending, live, max - out.size()));
        return out;
    }

    /**
     * The candidates a site that said "later" left unchecked, checked once (0.8.4): when the latest of those sites' times has come, or
     * comes within what the search may still wait ({@link #LATER_SEARCH_MS} in all), after that one wait; a candidate whose site still
     * says "later", or whose time is beyond the search's wait, is carried unchecked, its board kept to be read at the next search.
     */
    static List<Discovered> again(List<Discovered> pending, Predicate<Discovered> live, int room) {
        List<Discovered> out = new ArrayList<>();
        if (pending.isEmpty() || room <= 0) return out;
        Wanted w = WANTED.get();
        long until = 0;
        if (w != null) for (long u : w.laterSites().values()) until = Math.max(until, u);
        long wait = until - System.currentTimeMillis();
        boolean ask = w == null || wait <= w.laterLeft().get();
        if (ask && wait > 0) { w.laterLeft().addAndGet(-wait); SLEEP.accept(wait); }
        if (ask && w != null) { final long u = until; for (String h : w.laterSites().keySet()) LATER.computeIfPresent(h, (k, v) -> v <= u ? null : v); }   // their time has come
        for (Discovered d : pending) {
            if (out.size() >= room) break;
            if (ask) {
                ANY_LATER.set(false);
                if (live.test(d)) { out.add(d); continue; }
                if (!ANY_LATER.get()) continue;   // checked now: its board no longer lists it
            }
            out.add(d.unchecked());
        }
        return out;
    }

    /** What the search by meaning gave: the companies, how many searches it made, and why it made none or stopped ("" when it did not). */
    public record ByMeaning(List<Discovered> found, int searches, String note) { }

    /**
     * Companies hiring now, found by meaning through Exa (0.8.4): each query (a description of the posting wanted, from the role's words and
     * the place, nothing else of the person's) searched on the hosts of {@link #SITES} for postings published in the last {@link #EXA_DAYS}
     * days, through {@link ExaSearchTool}'s key, tally and monthly limit. Every hit goes through what a hit of the search by site goes
     * through: a page the site list or the person's refused list leaves out is dropped, it must be one posting on a system of the table,
     * its own title must pass the cut, and the company's own board, read now, must still list it. So Exa adds candidates and never a new
     * kind of trust. At most {@code max} companies, one search per query, the queries in order.
     */
    public static ByMeaning byMeaning(List<String> queries, int max, Predicate<String> titleOk, Predicate<Discovered> live) {
        List<Discovered> out = new ArrayList<>(), pending = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int made = 0;
        String note = "";
        LocalDate since = LocalDate.now().minusDays(EXA_DAYS);
        for (String query : queries) {
            if (out.size() >= max) break;
            ExaSearchTool.Answer a = ExaSearchTool.postings(query, siteHosts(), since, Fetch.Policy.DEFAULT.siteList());
            if (a.status() == -1) { note = a.said(); break; }   // no key, or this month's searches are used: no call went
            made++;
            if (!a.answered()) { note = "Exa did not answer: " + a.said(); continue; }
            for (PlaceRows.Hit ph : a.hits()) {
                if (ph == null || ph.url() == null || Fetch.Policy.DEFAULT.leftOut(ph.url()) != null) continue;   // a page from outside, under the same rules as any other
                Outbound.found(ph.url());
                Hit h = new Hit(ph.title(), ph.url());
                Site site = siteOf(ph.url());
                List<Feed> fs = boardsIn(h.url(), "");   // the board its address names, for the title only: no request here
                String title = fs.isEmpty() ? jobTitle(h) : exaTitle(h, fs.get(0));
                ANY_LATER.set(false);
                Discovered d = hiring(h, title, List.of(), titleOk, seen, "found hiring: a search by meaning through Exa of " + (site == null ? "the hiring systems'" : site.system() + "'s") + " job pages gave ", "exa");
                if (d == null) continue;
                if (!live.test(d)) { if (ANY_LATER.get()) pending.add(d); continue; }
                out.add(d);
                if (out.size() >= max) break;
            }
        }
        out.addAll(again(pending, live, max - out.size()));
        return new ByMeaning(out, made, note);
    }

    /**
     * A hit that is one posting on a hiring system the library reads, whose own title carries the words searched for and passes the cut,
     * at a company not found already: the company with its feed; null otherwise.
     */
    static Discovered hiring(Hit h, String job, List<String> must, Predicate<String> titleOk, Set<String> seen, String how, String way) {
        if (!posting(h.url())) return null;   // a posting, not a board's front page
        if (must.stream().anyMatch(m -> !CareerCut.has(job, m)) || !titleOk.test(job)) return null;   // the words searched for are in the job's own title, and it passes the cut
        List<Feed> fs = feedsOf(h.url());   // the board its address names, else the one it leads to (one request, for an address that names none)
        if (fs.isEmpty()) return null;
        Feed f = fs.get(0);
        if (!seen.add(f.label())) return null;
        String name = boardName(f);
        if (name.isEmpty()) name = nameFromHit(h, f);
        return new Discovered(name, new Feed(f.kind(), f.token(), how + clip(job, 90)), job, h.url(), way);
    }

    /**
     * The job's own title in a hit Exa gave: "Staff Security Engineer at River" is "Staff Security Engineer" when River is the board's own
     * company; the shapes a search engine gives ("Job Application for X at Y", "X @ Y") are read as {@link #jobTitle} reads them.
     */
    static String exaTitle(Hit h, Feed f) {
        String t = h.title() == null ? "" : h.title().replaceAll("\\s+", " ").strip();
        if (!t.toLowerCase(Locale.ROOT).startsWith("job application for ") && !t.contains(" @ ")) {
            Matcher m = Pattern.compile("(?i)^(.+)\\s+at\\s+([^|@]+)$").matcher(t);
            if (m.find() && tokenIs(f, m.group(2).strip())) return m.group(1).strip();
        }
        return jobTitle(h);
    }

    /** Whether an address is one posting on a hiring system, past the board's front page. */
    static boolean posting(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        for (Site s : SITES) if (s.posting().matcher(u).find()) return true;
        return u.matches(".*greenhouse\\.io/.*[?&]gh_jid=\\d+.*");
    }

    /**
     * The board a posting's address belongs to: the one its address names; else, for an address that names none (Workable's short
     * address, a Paylocity posting), the one where the address leads or its page links, read once. Empty when there is none.
     */
    static List<Feed> feedsOf(String url) {
        List<Feed> fs = boardsIn(url, "");
        if (!fs.isEmpty()) return fs;
        Feed f = CareerSystems.feedOfPosting(url);
        return f == null ? List.of() : List.of(f);
    }

    /**
     * A posting's own id in its address, the same in a search hit and in its board's list: Greenhouse's number, the id Lever, Ashby,
     * Rippling and Pinpoint give as a UUID, Workable's code, Workday's requisition, Recruitee's and Personio's, SmartRecruiters', Teamtailor's,
     * Paylocity's, BambooHR's and JazzHR's; "" when the address carries none of these.
     */
    static String postingId(String url) {
        if (url == null) return "";
        if (url.contains("myworkdayjobs.com")) url = CareerSystems.workdayPostingUrl(url);   // an apply page's address names the posting before /apply
        String[] shapes = {"[?&]gh_jid=(\\d+)", "greenhouse\\.io/[^/]+/jobs/(\\d+)", "apply\\.workable\\.com/(?:[^/]+/)?j/([0-9a-z]{6,})", "myworkdayjobs\\.com/.*/(?:job|details)/(?:.*/)?([^/?#]+)",
                "recruitee\\.com/o/([^/?#]+)", "jobs\\.personio\\.(?:de|com)/job/(\\d+)", "jobs\\.smartrecruiters\\.com/[^/]+/(\\d{6,})", "teamtailor\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?jobs/(\\d+)",
                "paylocity\\.com/recruiting/jobs/details/(\\d+)", "bamboohr\\.com/careers/(\\d+)", "applytojob\\.com/apply/([a-z0-9]{6,})",
                "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"};
        for (String s : shapes) { Matcher m = Pattern.compile("(?i)" + s).matcher(url); if (m.find()) return m.group(1).toLowerCase(Locale.ROOT); }
        return "";
    }

    /** The job's own title in a search result for its posting: "Job Application for X at Y", "X @ Y", "Y - X". */
    static String jobTitle(Hit h) {
        String t = h.title().replaceAll("\\s+", " ").strip();
        Matcher m = Pattern.compile("(?i)^job application for (.+?) at .+$").matcher(t);
        if (m.find()) return m.group(1).strip();
        if (t.contains(" @ ")) return t.substring(0, t.lastIndexOf(" @ ")).strip();
        if (h.url().contains("lever.co") && t.contains(" - ")) return t.substring(t.indexOf(" - ") + 3).strip();
        return t;
    }

    /** The name a board gives itself, where its system says it: Greenhouse and Workable; "" elsewhere or when it does not answer. */
    static String boardName(Feed f) {
        try {
            String b = switch (f.kind()) {
                case "greenhouse" -> get("https://boards-api.greenhouse.io/v1/boards/" + f.token());
                case "workable" -> get("https://apply.workable.com/api/v1/widget/accounts/" + f.token());
                default -> null;
            };
            if (b == null) return CareerSystems.boardName(f);
            return M.readTree(b).path("name").asText("").strip();
        } catch (Exception e) { return ""; }
    }

    /** Whether a company's own board, read now, lists the posting a search found; a board that does not answer counts as not listing it. */
    public static boolean stillListed(Discovered d, List<String> words) {
        Boolean own = CareerSystems.listedAt(d.url());   // the posting's own answer, where its system gives one and says it: Workday's record, SmartRecruiters', Rippling's, BambooHR's
        if (own != null) return own;
        LAST_STATUS.set(-1);
        Found f = read(d.feed(), d.company(), words);
        if (f == null) {   // the board itself: "not here" is the system's answer; a refusal, a timeout or an answer that cannot be read says nothing (0.8.4)
            int st = LAST_STATUS.get();
            if (st != 404 && st != 410) ANY_LATER.set(true);
            return false;
        }
        if (saidLater(f) || CareerSystems.NO_CAREERS_PAGE.equals(f.note())) { ANY_LATER.set(true); return false; }   // its board asked to be read later, or has no list to look in: not checked, and the caller carries it (0.8.4)
        String id = postingId(d.url());
        if (!id.isEmpty()) {   // by the posting's id where the board's list gives ids: another posting with the same title is not this one
            boolean ids = false;
            for (Opening o : f.openings()) { String oid = postingId(o.url()); if (oid.isEmpty()) continue; ids = true; if (oid.equals(id)) return true; }
            if (ids && !d.feed().kind().equals("workday")) return false;   // Workday's list is read only by search, so a posting missing from it may lie beyond it
        }
        String k = CareerSearch.key(d.posting());
        for (Opening o : f.openings()) { String t = CareerSearch.key(o.title()); if (!t.isEmpty() && (t.contains(k) || k.contains(t))) return true; }
        return false;
    }

    /** A company's name from a search result for one of its postings: "Job Application for X at Acme", "X @ Acme", "Acme - X"; else its board's name. */
    static String nameFromHit(Hit h, Feed f) {
        String t = h.title().replaceAll("\\s+", " ").strip();
        Matcher m = Pattern.compile("(?i)^job application for .+? at (.+)$").matcher(t);
        if (m.find()) return m.group(1).strip();
        if (t.contains(" @ ")) return t.substring(t.lastIndexOf(" @ ") + 3).replaceFirst("\\s*[|–-].*$", "").strip();
        if (f.kind().equals("lever") && t.contains(" - ")) return t.substring(0, t.indexOf(" - ")).strip();
        if (f.kind().equals("workday")) { String org = CareerSystems.workdayEmployer(h.url()); if (!org.isEmpty()) return org; }   // the hiring organisation its own record names, not the site's name in its address; a refused record leaves the name its address gives
        String tok = f.token().split("[./]")[0].replaceFirst("-\\d{6,}$", "").replace('-', ' ').replace('_', ' ');   // a Teamtailor site's numbered tail is not part of the name
        return tok.isEmpty() ? f.token() : Character.toUpperCase(tok.charAt(0)) + tok.substring(1);
    }

    // ---------------------------------------------------------------- reading a feed

    /**
     * A feed's openings, or null when the feed does not answer. A board that is its hiring system's own demonstration board gives none
     * ({@link CareerSystems#ownBoard}); a board whose system still said "later" after being asked again gives none with a note saying so,
     * never null: it is the company's board, read at the next search, and no search for another board is made in its place.
     */
    static Found read(Feed f, String company, List<String> words) {
        if (f != null && CareerSystems.ownBoard(f, company)) return new Found(company, f, List.of(), CareerSystems.ownBoardNote(f));
        Wanted w = WANTED.get();
        String key = f == null ? "" : f.kind() + ":" + f.token() + "|" + company;
        if (w != null && f != null) {   // a board read once in a search serves the next purpose: a found posting's check, then the employer's read
            Found had = w.boards().get(key);
            if (had != null) return new Found(had.company(), f, had.openings(), had.note());
        }
        SAID_LATER.set(false);
        try {
            Found x = readFeed(f, company, words);
            if (w != null && x != null && f != null && !saidLater(x)) w.boards().put(key, x);
            if (x == null && SAID_LATER.get() && f != null) return new Found(company, f, List.of(), f.system() + " " + LATER_NOTE + " (it answered that it was busy or asked too often), so its openings are read at the next search.");
            return x;
        } finally { SAID_LATER.set(false); }
    }

    // ---------------------------------------------------------------- what a search wants of a board (0.8.4)

    /**
     * What a person's search wants of the boards it reads, set on the threads that read them: which titles can be shown (the cut's own
     * title rule, the one the page uses), which places (its place rule, for a row whose list line names one), how many reads past a
     * board's list the whole search may still make, and the boards read so far in it. The readers never see the cut: a row past a list
     * (a Workday record for a row in several places, a SmartRecruiters posting for its place and day) is read only for a row that
     * {@link #worthReading} says can be shown. Measured on 2026-11-26 before this: 83 Workday records read for seeker 34, 2 of them for
     * a row the cut lets through; 57 SmartRecruiters postings, 4 of them.
     */
    public record Wanted(Predicate<String> title, Predicate<String> place, AtomicInteger reads, Map<String, Found> boards, Map<String, Fetch.Result> answers,
                         AtomicLong laterLeft, Map<String, Long> laterSites, Map<String, Integer> laterTimes) {
        public Wanted(Predicate<String> title, Predicate<String> place, int reads) {
            this(title, place, new AtomicInteger(reads), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new AtomicLong(LATER_SEARCH_MS), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    /**
     * The most a whole career search waits on sites that said "later", in all (0.8.4): once, at the end of the search by site (and of
     * Exa's), for the candidates such a site left unchecked. Everywhere else in a search a site that said "later" is answered "later" at
     * once until its time, with no request and no wait.
     */
    static final long LATER_SEARCH_MS = 60_000;
    /** The longest a site rests after saying "later" with a long Retry-After: half an hour. */
    static final long REST_MAX_MS = 1_800_000;

    /** The search's wants on this thread; null outside a career search's reading, where the readers keep their own words. */
    static final ThreadLocal<Wanted> WANTED = new ThreadLocal<>();

    /** The most reads past the boards' lists one whole search makes (each one request, at a second a site): {@value} (0.8.4). */
    static final int EXTRA_READS_MAX = 60;

    /** {@code read} run with the search's wants on this thread. */
    static <T> T wanting(Wanted w, Supplier<T> read) {
        Wanted was = WANTED.get();
        WANTED.set(w);
        try { return read.get(); } finally { if (was == null) WANTED.remove(); else WANTED.set(was); }
    }

    /**
     * Whether one more request past a board's list is worth making for a row: in a search, its title can be shown, its place too where
     * the list line names one ("N Locations" names none), and the search has such reads left; outside one, its title holds a word given.
     */
    static boolean worthReading(String title, String place, List<String> words) {
        Wanted w = WANTED.get();
        if (w == null) return words == null || words.isEmpty() || words.stream().anyMatch(x -> CareerCut.has(title, x));
        if (!w.title().test(title)) return false;
        String p = place == null ? "" : place.strip();
        if (!p.isEmpty() && !p.matches("\\d+ Locations") && !w.place().test(p)) return false;
        return w.reads().getAndDecrement() > 0;
    }

    /** Whether a board's read ended with its system saying "later": the board is there, its list was not read this time. */
    static boolean saidLater(Found f) { return f != null && f.note() != null && f.note().contains(LATER_NOTE); }

    private static Found readFeed(Feed f, String company, List<String> words) {
        try {
            List<Opening> out = new ArrayList<>();
            String note = "";
            switch (f.kind()) {
                case "greenhouse" -> {
                    String b = get("https://boards-api.greenhouse.io/v1/boards/" + f.token() + "/jobs?content=true");
                    if (b == null) return null;
                    for (JsonNode j : M.readTree(b).path("jobs")) {
                        String loc = j.path("location").path("name").asText("").strip();
                        out.add(opening(j.path("company_name").asText(company), j.path("title").asText(""), loc, remote(loc), j.path("absolute_url").asText(""),
                                day(j.path("first_published").asText(j.path("updated_at").asText(""))), "", f.label(), plain(unescape(j.path("content").asText(""))), "", ""));
                    }
                }
                case "lever", "levereu" -> {   // Lever's European customers are served by its European host, in the same shape
                    String b = get((f.kind().equals("levereu") ? "https://api.eu.lever.co" : "https://api.lever.co") + "/v0/postings/" + f.token() + "?mode=json");
                    if (b == null || !b.strip().startsWith("[")) return null;
                    for (JsonNode j : M.readTree(b)) out.add(lever(j, company, f.label()));
                }
                case "ashby" -> {
                    String b = get("https://api.ashbyhq.com/posting-api/job-board/" + f.token() + "?includeCompensation=true");
                    if (b == null) return null;
                    for (JsonNode j : M.readTree(b).path("jobs")) {
                        if (j.has("isListed") && !j.path("isListed").asBoolean(true)) continue;
                        String loc = j.path("location").asText("").strip();
                        boolean rem = j.path("isRemote").asBoolean(false) || j.path("workplaceType").asText("").equalsIgnoreCase("remote") || remote(loc);
                        out.add(opening(company, j.path("title").asText(""), loc, rem, j.path("jobUrl").asText(""), day(j.path("publishedAt").asText("")), "", f.label(),
                                j.path("descriptionPlain").asText(""), "", j.path("compensation").path("compensationTierSummary").asText("")));
                    }
                }
                case "workable" -> {
                    String b = get("https://apply.workable.com/api/v1/widget/accounts/" + f.token());
                    if (b == null) return null;
                    JsonNode d = M.readTree(b);
                    for (JsonNode j : d.path("jobs")) {
                        String loc = joined(j.path("city").asText(""), j.path("state").asText(""), j.path("country").asText(""));
                        out.add(opening(d.path("name").asText(company), j.path("title").asText(""), loc, j.path("telecommuting").asBoolean(false) || remote(loc), j.path("url").asText(""),
                                day(j.path("published_on").asText("")), "", f.label(), "", j.path("url").asText(""), ""));
                    }
                }
                case "recruitee" -> {
                    String b = get("https://" + f.token() + ".recruitee.com/api/offers/");
                    if (b == null) return null;
                    for (JsonNode j : M.readTree(b).path("offers")) {
                        String loc = j.path("location").asText("").strip();
                        out.add(opening(j.path("company_name").asText(company), j.path("title").asText(""), loc, j.path("remote").asBoolean(false) || remote(loc), j.path("careers_url").asText(""),
                                day(j.path("published_at").asText("")), "", f.label(), plain(j.path("description").asText("") + "\n" + j.path("requirements").asText("")), "", salary(j.path("salary"))));
                    }
                }
                case "personio" -> {
                    String b = get("https://" + f.token() + "/xml?language=en");
                    if (b == null || !b.contains("<workzag-jobs")) return null;
                    Matcher m = Pattern.compile("(?s)<position>(.*?)</position>").matcher(b);
                    while (m.find()) {
                        String p = m.group(1), id = tag(p, "id");
                        List<String> offices = new ArrayList<>(List.of(tag(p, "office")));
                        Matcher more = Pattern.compile("(?s)<additionalOffices>(.*?)</additionalOffices>").matcher(p);
                        if (more.find()) { Matcher o = Pattern.compile("<office>(.*?)</office>").matcher(more.group(1)); while (o.find()) offices.add(o.group(1).strip()); }
                        String loc = String.join(", ", offices.stream().filter(x -> !x.isBlank()).toList());
                        StringBuilder text = new StringBuilder();
                        Matcher v = Pattern.compile("(?s)<jobDescription>\\s*<name>(.*?)</name>\\s*<value>(.*?)</value>").matcher(p);
                        while (v.find()) text.append(v.group(1).strip()).append('\n').append(plain(v.group(2).replace("<![CDATA[", "").replace("]]>", ""))).append('\n');
                        out.add(opening(company, unescape(tag(p, "name")), loc, remote(loc), "https://" + f.token() + "/job/" + id + "?language=en", day(tag(p, "createdAt")), "", f.label(), text.toString(), "", ""));
                    }
                }
                case "workday" -> {
                    String[] t = f.token().split("/");
                    if (t.length != 3) return null;
                    String base = "https://" + t[0] + "." + t[1] + ".myworkdayjobs.com", api = base + "/wday/cxs/" + t[0] + "/" + t[2];
                    Map<String, JsonNode> got = new LinkedHashMap<>();
                    int total = -1;
                    List<String> terms = words == null || words.isEmpty() ? List.of("") : words.subList(0, Math.min(4, words.size()));
                    for (String term : terms) {
                        for (int page = 0; page < (term.isEmpty() ? 10 : 3); page++) {
                            String b = postJson(api + "/jobs", M.createObjectNode().put("limit", 20).put("offset", page * 20).put("searchText", term).set("appliedFacets", M.createObjectNode()).toString());
                            if (b == null) { if (got.isEmpty() && page == 0 && term.equals(terms.get(0))) return null; break; }
                            JsonNode d = M.readTree(b);
                            if (term.isEmpty() && page == 0) total = d.path("total").asInt(-1);
                            int n = 0;
                            for (JsonNode j : d.path("jobPostings")) { n++; got.putIfAbsent(j.path("externalPath").asText(""), j); }
                            if (n < 20) break;
                        }
                    }
                    int several = 0, asked = 0, placed = 0;
                    for (JsonNode j : got.values()) {
                        String path = j.path("externalPath").asText(""), loc = j.path("locationsText").asText("").strip(), title = j.path("title").asText("");
                        String[] posted = workdayPosted(j.path("postedOn").asText(""), LocalDate.now());
                        boolean rem = remote(loc);
                        if (loc.matches("\\d+ Locations")) {   // a row in several places names none of them: its record names them all, read for a row whose title holds a word wanted
                            several++;
                            String[] at = null;
                            if (asked < CareerSystems.WORKDAY_PLACES_MAX && worthReading(title, null, words)) { asked++; at = CareerSystems.workdayPlaces(api + path); }
                            if (at != null && at[0].equals("gone")) { several--; continue; }   // its record says it is no longer posted
                            if (at != null) { loc = at[0]; rem = rem || at[1].equals("remote"); placed++; }
                            else loc = CareerSystems.workdayFirstPlace(path, loc);   // not read: the first place, as its address spells it, and how many more
                        }
                        out.add(opening(company, title, loc, rem || remote(loc), base + "/" + t[2] + path, posted[0], posted[1], f.label(), "", api + path, ""));
                    }
                    if (!terms.get(0).isEmpty()) note = "Workday lists its openings only by search, so the library read those matching the titles you want: " + String.join(", ", terms) + ".";
                    else if (total > got.size()) note = "Workday shows " + total + " openings; the library read the first " + got.size() + ".";
                    if (several > placed) note = (note + " " + several + " of its openings are in several places, and the library read the places of " + placed + "; the others show their first place.").strip();
                }
                case "phenom", "oracle", "icims", "taleo", "peopleadmin" -> { return CareerPortals.read(f, company, words); }
                case "smartrecruiters", "teamtailor", "rippling", "paylocity", "pinpoint", "bamboohr", "jazzhr" -> { return CareerSystems.read(f, company, words); }
                case "jobposting", "links" -> {   // the careers page itself, its address kept as the feed's name
                    String page = get(f.token());
                    if (page == null) return null;
                    out.addAll(f.kind().equals("links") ? jobLinks(page, f.token(), company) : jobPostings(page, f.token(), company));
                    if (out.isEmpty() || (f.kind().equals("links") && out.size() < LINKS_MIN)) return null;
                }
                default -> { return null; }
            }
            return new Found(company, f, out, note);
        } catch (Exception e) { return null; }
    }

    /** One Lever posting as its service gives it, in a board's list or alone. */
    static Opening lever(JsonNode j, String company, String source) {
        String loc = j.path("categories").path("location").asText("").strip();
        boolean rem = j.path("workplaceType").asText("").equalsIgnoreCase("remote") || remote(loc);
        long at = j.path("createdAt").asLong(0);
        String text = j.path("descriptionPlain").asText("") + "\n" + lists(j.path("lists")) + j.path("additionalPlain").asText("");
        return opening(company, j.path("text").asText(""), loc, rem, j.path("hostedUrl").asText(""), at > 0 ? Instant.ofEpochMilli(at).toString().substring(0, 10) : "", "",
                source, text, "", pay(j.path("salaryRange")));
    }

    /** What Workday says of a posting's age, as a day and a note: "Posted Today", "Posted Yesterday", "Posted 5 Days Ago", "Posted 30+ Days Ago". */
    static String[] workdayPosted(String s, LocalDate today) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (t.contains("today")) return new String[]{today.toString(), ""};
        if (t.contains("yesterday")) return new String[]{today.minusDays(1).toString(), ""};
        Matcher m = Pattern.compile("(\\d+)(\\+)?\\s+days").matcher(t);
        if (m.find()) {
            if (m.group(2) != null) return new String[]{"", "more than " + m.group(1) + " days before " + today};
            return new String[]{today.minusDays(Long.parseLong(m.group(1))).toString(), ""};
        }
        return new String[]{"", ""};
    }

    /** An opening's own words, read from where they are kept when the list gave none: Workday's posting, Workable's page, a careers page's job page. */
    public static String detail(Opening o) {
        if (!o.text().isBlank() || o.detail().isBlank()) return o.text();
        String own = CareerSystems.detail(o);   // SmartRecruiters', Rippling's and BambooHR's posting answers
        if (own != null) return clip(own);
        String b = get(o.detail());
        if (b == null) return "";
        try {
            if (o.source().startsWith("workday:")) {
                JsonNode info = M.readTree(b).path("jobPostingInfo");
                return clip(plain(info.path("jobDescription").asText("")) + (info.path("remoteType").asText("").isBlank() ? "" : "\nWorkplace: " + info.path("remoteType").asText("")));
            }
        } catch (Exception e) { return ""; }
        List<Opening> posted = jobPostings(b, o.detail(), o.company());
        if (!posted.isEmpty() && !posted.get(0).text().isBlank()) return clip(posted.get(0).text());
        return clip(plain(b.replaceAll("(?is)<(script|style|nav|header|footer)[^>]*>.*?</\\1>", "")));
    }

    /** The job data a page carries for search engines: schema.org JobPosting, in its JSON-LD blocks. */
    static List<Opening> jobPostings(String html, String pageUrl, String company) {
        List<Opening> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<script[^>]+application/ld\\+json[^>]*>(.*?)</script>").matcher(html == null ? "" : html);
        while (m.find()) {
            try { collect(M.readTree(m.group(1).strip()), pageUrl, company, out); } catch (Exception ignored) { }
        }
        return out;
    }

    private static void collect(JsonNode n, String pageUrl, String company, List<Opening> out) {
        if (n == null) return;
        if (n.isArray()) { for (JsonNode x : n) collect(x, pageUrl, company, out); return; }
        if (!n.isObject()) return;
        if (n.has("@graph")) collect(n.get("@graph"), pageUrl, company, out);
        if (!n.path("@type").asText("").equalsIgnoreCase("JobPosting")) return;
        JsonNode where = n.path("jobLocation").isArray() ? n.path("jobLocation").path(0) : n.path("jobLocation");
        JsonNode addr = where.path("address");
        String country = addr.path("addressCountry").isTextual() ? addr.path("addressCountry").asText("") : addr.path("addressCountry").path("name").asText("");
        String loc = joined(addr.path("addressLocality").asText(""), addr.path("addressRegion").asText(""), country);
        boolean rem = n.path("jobLocationType").asText("").equalsIgnoreCase("TELECOMMUTE") || remote(loc);
        List<String> from = applicants(n.path("applicantLocationRequirements"));
        if (rem && !from.isEmpty()) loc = (loc.isEmpty() ? "" : loc + "; ") + "Remote, " + String.join(", ", from);   // remote, but only from the countries the posting names: never remote from anywhere
        JsonNode base = n.path("baseSalary").path("value");
        String pay = base.isMissingNode() ? "" : joined(base.path("minValue").asText(""), base.path("maxValue").asText("")).replace(", ", " to ")
                + (base.path("unitText").asText("").isBlank() ? "" : " a " + base.path("unitText").asText("").toLowerCase(Locale.ROOT)) + (n.path("baseSalary").path("currency").asText("").isBlank() ? "" : " " + n.path("baseSalary").path("currency").asText(""));
        String org = n.path("hiringOrganization").path("name").asText("");
        out.add(opening(org.isBlank() || org.matches("\\d+ .*") ? company : org, plain(n.path("title").asText("")), loc, rem, n.path("url").asText(pageUrl), day(n.path("datePosted").asText("")), "",
                "jobposting:" + pageUrl, plain(n.path("description").asText("")), "", pay.strip()));
    }

    /**
     * The countries a remote posting's JobPosting data takes applicants from (applicantLocationRequirements: one or a list, each a Country
     * or a State, by name or by its two-letter code), as names: "PH" is the Philippines.
     */
    static List<String> applicants(JsonNode req) {
        List<String> out = new ArrayList<>();
        if (req == null || req.isMissingNode() || req.isNull()) return out;
        for (JsonNode r : req.isArray() ? req : List.of(req)) {
            String name = r.isTextual() ? r.asText("") : r.path("name").asText("");
            name = name.strip();
            if (name.matches("[A-Za-z]{2}")) { String full = Locale.of("", name.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH); if (!full.isBlank() && !full.equalsIgnoreCase(name)) name = full; }
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /** A careers page with fewer job links than this is a landing page with a featured role or two, not the company's list of openings. */
    static final int LINKS_MIN = 3;

    /** Words in a link that make it the page's own furniture, not an opening. */
    static final Pattern FURNITURE = Pattern.compile("(?i)^(view|see|search|browse|explore|find|apply|join|all|open|our|life at|saved|your|back|next|previous|more|learn|read|sign|log|benefits|culture|teams?|locations?|students?|university|early career|internships?|jobs?|careers?|positions?|openings?|roles?)\\b.*");

    /** The job links on a careers page: links into its jobs whose words read as a job title. */
    static List<Opening> jobLinks(String html, String pageUrl, String company) {
        List<Opening> out = new ArrayList<>();
        if (html == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?is)<a\\b[^>]*\\bhref\\s*=\\s*\"([^\"#]+)\"[^>]*>(.*?)</a>").matcher(html);
        URI base;
        try { base = URI.create(pageUrl); } catch (Exception e) { return out; }
        while (m.find() && out.size() < 400) {
            String href = unescape(m.group(1).strip()), words = plain(m.group(2)).replaceAll("\\s+", " ").strip();
            if (!href.matches("(?i).*/(jobs?|positions?|openings?|careers?|vacanc(y|ies)|roles?)/[^/?]+.*")) continue;
            String[] w = words.split(" ");
            if (w.length < 2 || w.length > 14 || words.length() > 120 || FURNITURE.matcher(words).matches() || words.matches(".*\\d+\\s+(jobs|openings|positions).*")) continue;
            String url;
            try { url = base.resolve(href).toString(); } catch (Exception e) { continue; }
            if (!host(url).equals(host(pageUrl)) && !host(url).endsWith("." + registrable(host(pageUrl)))) continue;
            if (!seen.add(url)) continue;
            out.add(opening(company, words, "", false, url, "", "", "links:" + pageUrl, "", url, ""));
        }
        return out;
    }

    static String registrable(String host) { String[] p = host.split("\\."); return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : host; }

    // ---------------------------------------------------------------- the open listings

    /** The month's Hacker News "Who is hiring" thread: each top-level comment one company's post, its first line company | role | place. */
    public static List<Opening> hackerNews() {
        List<Opening> out = new ArrayList<>();
        try {
            String list = get("https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=10");
            if (list == null) return out;
            String id = null, month = "";
            for (JsonNode h : M.readTree(list).path("hits")) {
                String t = h.path("title").asText("");
                if (t.startsWith("Ask HN: Who is hiring?")) { id = h.path("objectID").asText(); month = t.replaceFirst(".*\\(", "").replace(")", "").strip(); break; }
            }
            if (id == null) return out;
            String thread = get("https://hn.algolia.com/api/v1/items/" + id);
            if (thread == null) return out;
            for (JsonNode c : M.readTree(thread).path("children")) {
                String text = plain(c.path("text").asText(""));
                if (text.isBlank()) continue;
                String head = text.lines().findFirst().orElse("").strip();
                String[] parts = head.split("\\s*\\|\\s*");
                if (parts.length < 2) continue;   // a post that does not open company | role is somebody's reply, not an opening
                String company = clip(parts[0].replaceAll("\\s*\\(https?://[^)]*\\)", "").strip(), 80);
                String title = clip(parts[1].strip(), 160), where = "";
                for (int i = 2; i < parts.length; i++)
                    if (!parts[i].matches("(?i).*(remote|onsite|on-site|in-office|hybrid|full[- ]?time|part[- ]?time|contract|intern|\\$|€|£|https?://|salary|visa|equity|\\d+k).*")) { where = clip(parts[i].strip(), 120); break; }
                String h = head.toLowerCase(Locale.ROOT);
                boolean rem = h.contains("remote") && !h.matches(".*\\b(no|not)\\s+remote\\b.*");
                out.add(opening(company, title, where, rem, "https://news.ycombinator.com/item?id=" + c.path("id").asText(), day(c.path("created_at").asText("")), "",
                        "hn:" + month, text, "", ""));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Whether USAJobs can be read: it needs a key of the person's own, and the email address the key was issued to. */
    public static boolean usaJobsReady() { return !Config.get("RESEARCHZOSHO_USAJOBS_KEY", "").isBlank() && !Config.get("RESEARCHZOSHO_USAJOBS_EMAIL", "").isBlank(); }

    /** USAJobs for federal openings, one search for each title word; USAJobs asks for the key and its email address with every request. */
    public static List<Opening> usaJobs(List<String> words) {
        List<Opening> out = new ArrayList<>();
        if (!usaJobsReady()) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String w : (words == null || words.isEmpty() ? List.of("") : words.subList(0, Math.min(3, words.size())))) {
            String url = "https://data.usajobs.gov/api/search?ResultsPerPage=250" + (w.isBlank() ? "" : "&Keyword=" + URLEncoder.encode(w, StandardCharsets.UTF_8));
            String b = USAJOBS.apply(url);
            if (b == null) continue;
            try {
                for (JsonNode item : M.readTree(b).path("SearchResult").path("SearchResultItems")) {
                    JsonNode d = item.path("MatchedObjectDescriptor");
                    String uri = d.path("PositionURI").asText("");
                    if (uri.isBlank() || !seen.add(uri)) continue;
                    JsonNode det = d.path("UserArea").path("Details");
                    JsonNode rem = d.path("PositionRemuneration").path(0);
                    String pay = rem.isMissingNode() ? "" : ("$" + rem.path("MinimumRange").asText("") + " to $" + rem.path("MaximumRange").asText("") + " " + rem.path("Description").asText("").toLowerCase(Locale.ROOT)).strip();
                    String loc = d.path("PositionLocationDisplay").asText("");
                    String text = det.path("JobSummary").asText("") + "\n" + d.path("QualificationSummary").asText("") + (d.path("DepartmentName").asText("").isBlank() ? "" : "\nDepartment: " + d.path("DepartmentName").asText(""));
                    out.add(opening(d.path("OrganizationName").asText("US federal government"), d.path("PositionTitle").asText(""), loc, det.path("RemoteIndicator").asBoolean(false) || remote(loc), uri,
                            day(d.path("PublicationStartDate").asText("")), "", "usajobs", text, "", pay));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** The USAJobs read, with the headers it asks for; a test puts a stand-in here. */
    static volatile Function<String, String> USAJOBS = url -> {
        try {
            pace(url);
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).header("User-Agent", Config.get("RESEARCHZOSHO_USAJOBS_EMAIL", ""))
                    .header("Authorization-Key", Config.get("RESEARCHZOSHO_USAJOBS_KEY", "")).GET().build();
            HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    };

    // ---------------------------------------------------------------- small things

    static Opening opening(String company, String title, String location, boolean remote, String url, String posted, String postedNote, String source, String text, String detail, String pay) {
        String t = plain(title).replaceAll("\\s+", " ").strip();
        return new Opening(id(url.isBlank() ? source + "|" + company + "|" + t : url), company == null ? "" : company.strip(), t, location == null ? "" : location.strip(), remote, url,
                posted, postedNote, source, clip(text == null ? "" : text.strip()), detail == null ? "" : detail, pay == null ? "" : pay.strip());
    }

    /** An opening's short name: ten letters of the hash of its own address, the same on every read. */
    static String id(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 10); }
        catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    static boolean remote(String location) {
        if (location == null || location.isBlank()) return false;
        String low = location.toLowerCase(Locale.ROOT);
        return REMOTE_WORDS.matcher(low).find() || REMOTE_RUNS.stream().anyMatch(low::contains);
    }
    /**
     * Remote as postings write it in the languages the program speaks (0.8.4): English alone missed "Homeoffice", "Télétravail",
     * "Remoto", "Удалённо"; the scripts written without spaces are matched inside a run ("リモート可", "远程办公").
     */
    static final Pattern REMOTE_WORDS = Pattern.compile("(?<![\\p{L}\\p{N}])(remote|anywhere|distributed|work from home|wfh|home[- ]?office|mobiles arbeiten|t[ée]l[ée]travail|à distance|remoto|teletrabajo|a distancia|"
            + "da remoto|telelavoro|smart working|teletrabalho|удал[её]нн?о|удал[её]нная|дистанционн\\p{L}*)(?![\\p{L}\\p{N}])");
    static final List<String> REMOTE_RUNS = List.of("リモート", "在宅", "テレワーク", "원격", "재택", "远程", "遠端", "居家办公");
    static String day(String iso) { return iso == null || iso.length() < 10 || !iso.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}") ? "" : iso.substring(0, 10); }
    static String joined(String... parts) { List<String> xs = new ArrayList<>(); for (String p : parts) if (p != null && !p.isBlank()) xs.add(p.strip()); return String.join(", ", xs); }
    static String clip(String s) { return clip(s, TEXT_MAX); }
    static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }
    static String tag(String xml, String tag) { Matcher m = Pattern.compile("(?s)<" + tag + ">(.*?)</" + tag + ">").matcher(xml); return m.find() ? m.group(1).replace("<![CDATA[", "").replace("]]>", "").strip() : ""; }
    static String unescape(String s) { return s == null ? "" : s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&#x2F;", "/").replace("&nbsp;", " ").replace("&amp;", "&"); }

    /** HTML as plain text: paragraphs, list items and line breaks kept as lines, tags dropped, entities read. */
    static String plain(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<(br|/p|/li|/h[1-6]|p|li|/div|/tr)\\b[^>]*>", "\n").replaceAll("<[^>]+>", "");
        s = unescape(s).replaceAll("&#(\\d+);", " ");
        Matcher hex = Pattern.compile("&#[xX]([0-9a-fA-F]{1,6});").matcher(s);   // a character written by its number in hexadecimal: "&#xa0;" is a space
        StringBuilder sb = new StringBuilder();
        while (hex.find()) { int c = Integer.parseInt(hex.group(1), 16); hex.appendReplacement(sb, Matcher.quoteReplacement(c == 0xa0 || !Character.isValidCodePoint(c) ? " " : new String(Character.toChars(c)))); }
        s = hex.appendTail(sb).toString();
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").strip();
    }

    private static String lists(JsonNode lists) {
        StringBuilder b = new StringBuilder();
        for (JsonNode l : lists) b.append(l.path("text").asText("")).append('\n').append(plain(l.path("content").asText(""))).append('\n');
        return b.toString();
    }

    private static String pay(JsonNode range) {
        if (range == null || range.isMissingNode() || range.isNull() || !range.has("min")) return "";
        return (range.path("currency").asText("") + " " + range.path("min").asText("") + " to " + range.path("max").asText("") + (range.path("interval").asText("").isBlank() ? "" : " " + range.path("interval").asText("").replace("-", " "))).strip();
    }

    private static String salary(JsonNode s) {
        if (s == null || !s.isObject() || s.path("min").asText("").isBlank()) return "";
        return (s.path("currency").asText("") + " " + s.path("min").asText("") + " to " + s.path("max").asText("") + (s.path("period").asText("").isBlank() ? "" : " a " + s.path("period").asText(""))).strip();
    }
}
