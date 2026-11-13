package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.records.Ops;
import org.researchzosho.tools.CustomSearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PubMed;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.SemanticScholarTool;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.WebSearchTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The outside services the library can use with a key, in one place: what each adds, whether it is free or paid and what the free
 * allowance is, where the key comes from, the settings that hold it, the day its terms were last read off the service's own page, this
 * month's use from the tally, and what the last check of the key said. The Keep page, the settings command, the keys command and the
 * library_settings op read it; nothing else names a service's terms.
 */
public final class OutsideServices {
    private OutsideServices() { }
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * One service: its terms as read on {@code checkedOn} from its own page (or what stood in the way of reading it), the settings that
     * hold its key, and the settings it reads when they are set and works without ({@code optional}: the key of the keeper's own search
     * service, whose address is what turns it on).
     */
    public record Service(String name, String adds, String terms, String link, String checkedOn, List<String> settings, String tally, List<String> optional) {
        public Service(String name, String adds, String terms, String link, String checkedOn, List<String> settings, String tally) { this(name, adds, terms, link, checkedOn, settings, tally, List.of()); }
        public String setting() { return settings.get(0); }
        public boolean set() { for (String s : settings) { String v = Config.get(s); if (v == null || v.isBlank()) return false; } return true; }
        /** Every setting the service reads: its own, then the optional ones. */
        public List<String> allSettings() { List<String> a = new ArrayList<>(settings); a.addAll(optional); return a; }
        /** The name its calls are counted under in the tally. */
        public String tallyName() { return tally.isEmpty() ? name : tally; }
    }

    /** The day the terms below were read: every price and allowance says its date, as the house rule has it. */
    static final String READ = "2026-11-03";
    /** The day the terms of the search places of 0.7.0 were read off their own pages. */
    static final String READ_PLACES = "2026-11-04";
    /** The day the terms of the EPO's Open Patent Services were read off its reference guide (version 1.3.19) and its answers' own headers. */
    static final String READ_EPO = "2026-11-05";
    /** The day the YouTube Data API's quota page was read. */
    static final String READ_YOUTUBE = "2026-11-05";
    /** The day the terms of MLIT's 不動産情報ライブラリ and its API were read off its own pages. */
    static final String READ_REINFOLIB = "2026-11-05";
    /** Where the guide says how to point the library at a search service of one's own. */
    static final String CUSTOM_GUIDE = "https://github.com/Wyrdsekai/researchzosho/blob/main/docs/LIBRARIAN_HOWTOUSE.md#web-search";

    static final List<Service> KNOWN = List.of(
            new Service("The model server", "the model the library runs on, when a hosted one wants a key (OpenRouter, Anthropic, OpenAI and the rest)", "paid per token at the provider's list prices; researchzosho models keeps the prices and researchzosho spend the bill", "https://openrouter.ai/keys", READ, List.of("RESEARCHZOSHO_API_KEY"), ""),
            new Service("Serper", "Google's own results for web search, used first when set", "2,500 free queries on sign-up, no card; then credits bought in advance, from $50 for 50,000 ($1 per 1,000, a tenth of a cent each), valid six months", "https://serper.dev/", READ, List.of("RESEARCHZOSHO_SERPER_KEY"), "Serper"),
            new Service("SerpApi", "Google's own results, used first when set and no Serper key is", "free: 250 searches a month; paid from $25 a month for 1,000 (2.5 cents each)", "https://serpapi.com/users/sign_up", READ, List.of("RESEARCHZOSHO_SERPAPI_KEY"), "SerpApi"),
            new Service("Firecrawl", "a page a plain fetch cannot read, built by scripts or behind a bot wall, read through its browser", "free: 1,000 credits a month, one credit a page; paid from $19 a month for 5,000 (about 0.4 cents a page)", "https://www.firecrawl.dev/", READ, List.of("RESEARCHZOSHO_FIRECRAWL_KEY"), "Firecrawl"),
            new Service("Brave Search", "web search before SearXNG, after a Google key when one is set", "free: $5 of calls every month, about 1,000 searches; then $5 per 1,000 (half a cent each)", "https://brave.com/search/api/", READ, List.of("RESEARCHZOSHO_BRAVE_KEY"), "Brave"),
            new Service("YouTube Data API", "videos in date order for the candidate search of a video you remember, which YouTube's own ranking never gives; made in the Google Cloud console by enabling the YouTube Data API v3 for a project and making an API key", "free: 10,000 units a day for a project, a search page 100 units (so about ninety pages a day), a video's details 1; the day turns at midnight Pacific time; more only by request to Google", "https://console.cloud.google.com/apis/library/youtube.googleapis.com", READ_YOUTUBE, List.of(Candidates.KEY), Candidates.TALLY),
            new Service(Ops.TALLY, "the patent offices' own records for prior art: every publication of a classification in a span of years enumerated whole, each family's abstract, and the claims from the office's own full text; and the patent collections by inventor, by applicant and by classification",
                    "free by registration at the European Patent Office: four gigabytes of answers a week and about 450 megabytes an hour; a search gives a hundred records a request and the first two thousand of a query, and the office asks for no more than ten searches a minute; it holds the claims of EP, WO and some thirty national offices' publications, not of the United States, China, Japan or Korea",
                    "https://developers.epo.org", READ_EPO, List.of(Ops.KEY_NAME, Ops.SECRET_NAME), ""),
            new Service("RentCast", "homes for sale and for rent across the United States, in the home search", "free: 50 calls a month on the developer plan; paid from $74 a month for 1,000", "https://app.rentcast.io/app/api", READ, List.of("RESEARCHZOSHO_RENTCAST_KEY"), "RentCast"),
            new Service("Census Bureau", "a Census tract's figures on a home's card: income, age, owners and renters, home value and rent", "free with a key; the guide names no daily limit for calls with a key", "https://api.census.gov/data/key_signup.html", READ, List.of("RESEARCHZOSHO_CENSUS_KEY"), "Census"),
            new Service("不動産情報ライブラリ (MLIT)", "a home search in Japan: the prices paid nearby, the nearest 地価公示 point and the flood, sediment and danger areas on each card, from the ministry's records",
                    "free, by application on the ministry's site (about five working days); its API terms (2025-06-18) set no numeric limit, ask that calls be spaced out, that the key be kept secret, and that a service using the API show the sentence: " + HomeJapanRecords.CREDIT + "; the data is under the Public Data License 1.0 with the source named",
                    "https://www.reinfolib.mlit.go.jp/api/request/", READ_REINFOLIB, List.of(HomeJapanRecords.KEY), HomeJapanRecords.TALLY),
            new Service("Trove", "Australian newspapers in old_newspapers", "free with a key applied for in a Trove account; the terms page could not be read on " + READ + " (a bot wall)", "https://trove.nla.gov.au/about/create-something/using-api", READ, List.of("RESEARCHZOSHO_TROVE_KEY"), ""),
            new Service("DigitalNZ", "New Zealand's Papers Past and heritage items", "free; no key is needed now, and a key, by request, raises the rate by negotiation", "https://digitalnz.org/developers/getting-started", READ, List.of("RESEARCHZOSHO_DIGITALNZ_KEY"), ""),
            new Service("Europeana", "European heritage items in archive_search", "free with a key; the key page refused the library's reader on " + READ, "https://pro.europeana.eu/page/get-api", READ, List.of("RESEARCHZOSHO_EUROPEANA_KEY"), ""),
            new Service("DPLA", "American heritage items in archive_search", "free with a key, requested through the API with your email; the terms page refused the library's reader on " + READ, "https://pro.dp.la/developers/api-codex", READ, List.of("RESEARCHZOSHO_DPLA_KEY"), ""),
            new Service("USAJobs", "federal openings in the career search", "free with a key, by application; up to 10,000 rows a query, 500 a page", "https://developer.usajobs.gov/apirequest/", READ, List.of("RESEARCHZOSHO_USAJOBS_KEY"), ""),
            new Service("Podcast Index", "the open podcast directory and search by person", "free; a key and a secret by email sign-up; the rate limit is not stated", "https://api.podcastindex.org/signup", READ, List.of("RESEARCHZOSHO_PODCASTINDEX_KEY", "RESEARCHZOSHO_PODCASTINDEX_SECRET"), ""),
            new Service("Yahoo! JAPAN", "what Yahoo!ショッピング lists, with prices, for a shopping question in Japan", "free; the limit is set per service on the developer network's own page", "https://developer.yahoo.co.jp/", READ, List.of("RESEARCHZOSHO_YAHOO_JP_CLIENT_ID"), ""),
            new Service("Rakuten", "what 楽天市場 lists, with prices", "free with an application id and an access key; the guide page is built by scripts and was not read on " + READ, "https://webservice.rakuten.co.jp/", READ, List.of("RESEARCHZOSHO_RAKUTEN_APP_ID", "RESEARCHZOSHO_RAKUTEN_ACCESS_KEY"), ""),
            new Service("Reddit", "what owners wrote about a product, through Reddit's API", "free for a script app of your own, approved by ticket; Reddit closes its public API in March 2027; the wiki page was not read on " + READ, "https://www.reddit.com/prefs/apps", READ, List.of("RESEARCHZOSHO_REDDIT_CLIENT_ID", "RESEARCHZOSHO_REDDIT_CLIENT_SECRET"), ""),
            new Service("Companies House", "the UK company register, read through its API for the career search", "free with a key made in the developer hub; the rate-limit page was not read on " + READ, "https://developer.company-information.service.gov.uk/", READ, List.of("RESEARCHZOSHO_COMPANIES_HOUSE_KEY"), ""),
            new Service("gBizINFO", "Japan's company information for the career search", "free with a token by registration; the page is built by scripts and was not read on " + READ, "https://info.gbiz.go.jp/api/", READ, List.of("RESEARCHZOSHO_GBIZINFO_TOKEN"), ""),
            new Service("Stack Exchange", "the forum search and research on the Stack Exchange sites", "free: 300 calls a day without a key, 10,000 a day with one", "https://stackapps.com/apps/oauth/register", READ, List.of("RESEARCHZOSHO_STACKEXCHANGE_KEY"), ""),
            new Service("eBay", "eBay listings and auctions in product_search", "free; production access by a short application; the call-limits page is built by scripts and was not read on " + READ, "https://developer.ebay.com/", READ, List.of("RESEARCHZOSHO_EBAY_CLIENT_ID", "RESEARCHZOSHO_EBAY_CLIENT_SECRET"), ""),
            new Service("Etsy", "Etsy listings, the handmade and vintage tail", "free for a personal app; the limits are per app, shown in the developer portal", "https://www.etsy.com/developers/register", READ, List.of("RESEARCHZOSHO_ETSY_KEY"), ""),
            new Service("CiNii", "CiNii Books in book_search, Japanese books by subject heading, author and words", "free by registration", "https://support.nii.ac.jp/ja/cinii/api/developer", READ, List.of("RESEARCHZOSHO_CINII_APPID"), ""),
            new Service("PubMed (NCBI)", "the medical and life-science literature in the pubmed place: PubMed's articles with their abstracts, and the full text of PubMed Central's open articles",
                    "free, with no key needed; three requests a second from one address without a key, ten with a free key from your NCBI account", "https://www.ncbi.nlm.nih.gov/account/settings/", READ_PLACES, List.of(PubMed.KEY), PubMed.TALLY),
            new Service("Semantic Scholar", "papers in every field in the semantic-scholar place, with how often each is cited and a free PDF where there is one",
                    "free, with no key needed; without a key the calls share 1,000 a second with every other program that has none and are slowed when it is busy; a free key, sent by email on request, gives one call a second of your own", "https://www.semanticscholar.org/product/api#api-key-form", READ_PLACES, List.of(SemanticScholarTool.KEY), SemanticScholarTool.TALLY),
            new Service("Exa", "the web searched by meaning in the exa place, each page with the passages that match; first in web search when web_first is exa",
                    "paid from credits bought in advance: $7 per 1,000 searches with up to ten results and their passages (0.7 cents each), $4 per 1,000 for the lighter search; on Exa's free tier a user's first team gets $10 of credits a month; the library stops at 714 searches a month, under $5, unless exa_monthly_limit is raised", ExaSearchTool.SIGN_UP, READ_PLACES, List.of(ExaSearchTool.KEY), ExaSearchTool.TALLY),
            new Service("Tavily", "the web in the tavily place, each page with the passages that answer the query; first in web search when web_first is tavily",
                    "free: 1,000 credits a month, no card, one credit a search; then $0.008 a credit pay as you go, or plans from $30 a month for 4,000; the library stops at 1,000 searches a month unless tavily_monthly_limit is raised", TavilySearchTool.SIGN_UP, READ_PLACES, List.of(TavilySearchTool.KEY), TavilySearchTool.TALLY),
            new Service("Your own search service", "a search service you point the library at, as a place of its own that a run can pick",
                    "free or paid as that service has it; the library sends its key only to that address, and stops at custom_search_monthly_limit searches a month when that is set", CUSTOM_GUIDE, READ_PLACES, List.of(CustomSearchTool.URL), CustomSearchTool.TALLY, List.of(CustomSearchTool.KEY)));

    public static List<Service> known() { return KNOWN; }

    /** Secrets that are no outside service's key: the library's own sign-in secrets, which the registry does not list. */
    public static boolean notAService(String setting) { return setting != null && (setting.contains("_OIDC_") || setting.endsWith("_TOKEN_SECRET") || setting.equals(Helpers.SECRET_NAME)); }   // the helpers' secret is between two machines of one person

    /** The service a setting belongs to, or null for a setting that is no outside service's key. */
    public static Service of(String setting) {
        for (Service s : KNOWN) if (s.settings().contains(setting) || s.optional().contains(setting)) return s;
        return null;
    }

    // ---------------------------------------------------------------- the checks

    /** A request the check makes; a test's stand-in answers them. */
    public record Req(String method, String url, Map<String, String> headers, String body) { }
    /** What came back: the status, the headers (lower-cased names) and the body; status 0 when nothing did. */
    public record Res(int status, Map<String, String> headers, String body) { }
    /** What a check said of one service, kept with its time. */
    public record Result(String service, Boolean accepted, String said, String at) { }

    /** The HTTP the checks use; a test puts its own here. */
    static volatile Function<Req, Res> HTTP = OutsideServices::send;
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    static Res send(Req r) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(r.url())).timeout(Duration.ofSeconds(25)).header("User-Agent", Fetch.USER_AGENT);
            for (Map.Entry<String, String> h : r.headers().entrySet()) b.header(h.getKey(), h.getValue());
            if (r.method().equals("POST")) b.POST(HttpRequest.BodyPublishers.ofString(r.body() == null ? "" : r.body())); else b.GET();
            HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new HashMap<>();
            res.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
            return new Res(res.statusCode(), headers, res.body());
        } catch (Exception e) { return new Res(0, Map.of(), ""); }
    }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("services-check.json"); }

    /** The results kept from the last check, by service. */
    public static Map<String, Result> kept(LibraryStore store) {
        Map<String, Result> out = new LinkedHashMap<>();
        try {
            Path f = file(store);
            if (!Files.isRegularFile(f)) return out;
            JsonNode j = M.readTree(f.toFile());
            j.fields().forEachRemaining(e -> out.put(e.getKey(), new Result(e.getKey(), e.getValue().hasNonNull("accepted") ? e.getValue().path("accepted").asBoolean() : null, e.getValue().path("said").asText(""), e.getValue().path("at").asText(""))));
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** Every service whose key is set, asked whether the key is accepted and what is left; the answers kept with their time, and returned. Each probe is one call in the tally. */
    public static List<Result> check(LibraryStore store) throws IOException {
        Map<String, Result> all = kept(store);
        List<Result> now = new ArrayList<>();
        for (Service s : KNOWN) {
            if (!s.set()) continue;
            Result r = probe(s);
            now.add(r);
            all.put(s.name(), r);
        }
        ObjectNode o = M.createObjectNode();
        for (Result r : all.values()) { ObjectNode e = o.putObject(r.service()); if (r.accepted() != null) e.put("accepted", r.accepted()); e.put("said", r.said()); e.put("at", r.at()); }
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(o));
        return now;
    }

    static String key(Service s, int i) { String v = Config.get(s.settings().get(i)); return v == null ? "" : v.strip(); }

    /** One service's check: a cheap call, or its account's own usage line where it has one; what came back, in words. */
    static Result probe(Service s) {
        String at = Instant.now().toString().replaceFirst("\\.\\d+Z$", "Z");
        if (s.name().equals("Your own search service")) return customProbe(s, at);
        try {
            String k = key(s, 0);
            switch (s.name()) {
                case "The model server" -> {
                    String drive = Config.get("RESEARCHZOSHO_DRIVE", ""), host = drive == null ? "" : drive.toLowerCase(Locale.ROOT);
                    if (host.contains("openrouter.ai")) {
                        Res r = call(s, new Req("GET", "https://openrouter.ai/api/v1/auth/key", Map.of("Authorization", "Bearer " + k), null));
                        if (ok(r)) {
                            JsonNode d = M.readTree(r.body()).path("data");
                            double used = d.path("usage").asDouble(0); JsonNode lim = d.path("limit");
                            return new Result(s.name(), true, "accepted; " + dollars(used) + " used" + (lim.isNull() || lim.isMissingNode() ? ", no spending limit set on the key" : ", " + dollars(d.path("limit_remaining").asDouble(0)) + " left of a " + dollars(lim.asDouble(0)) + " limit"), at);
                        }
                        return refusedOrSilent(s, r, at);
                    }
                    if (host.contains("api.anthropic.com")) return plain(s, new Req("GET", "https://api.anthropic.com/v1/models", Map.of("x-api-key", k, "anthropic-version", "2023-06-01"), null), at);
                    if (host.contains("api.openai.com")) return plain(s, new Req("GET", "https://api.openai.com/v1/models", Map.of("Authorization", "Bearer " + k), null), at);
                    return new Result(s.name(), null, "no check is written for the model server at " + (drive == null || drive.isBlank() ? "(unset)" : drive) + "; the key is tried at the next run", at);
                }
                case "Serper" -> {
                    // the check is one search, and costs what one costs; never past the month's limit
                    if (!SearchTally.reserve(WebSearchTool.SERPER)) return new Result(s.name(), null, "not checked: " + SearchTally.usedUp(WebSearchTool.SERPER, "Serper"), at);
                    Res r = new Res(0, Map.of(), "");
                    try { r = HTTP.apply(new Req("POST", "https://google.serper.dev/search", Map.of("X-API-KEY", k, "Content-Type", "application/json"), "{\"q\":\"researchzosho\",\"num\":1}")); }
                    finally { SearchTally.settle(WebSearchTool.SERPER, ok(r), ok(r) ? 1 : 0, 0); }
                    return ok(r) ? new Result(s.name(), true, "accepted; this service does not say what is left", at) : refusedOrSilent(s, r, at);
                }
                case Ops.TALLY -> {
                    // the key and the secret exchanged for a token, which costs nothing of the allowance; what the office last said of the week's use beside it
                    try { String said = Ops.accepted(); return new Result(s.name(), true, "accepted" + (said.isEmpty() ? "" : "; " + said), at); }
                    catch (Ops.Refused e) { return new Result(s.name(), false, e.getMessage(), at); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Result(s.name(), null, "the check was stopped before the office answered", at); }
                }
                case "YouTube Data API" -> {
                    // one videos.list call, a unit of the day's ten thousand; the key goes only to Google, as a query parameter, the way Google takes it
                    Res r = call(s, new Req("GET", Candidates.apiBase + "/videos?part=id&id=dQw4w9WgXcQ&key=" + enc(k), Map.of("User-Agent", Candidates.USER_AGENT), null));
                    SearchTally.countToday(Candidates.TALLY, Candidates.LIST_UNITS);
                    if (ok(r)) return new Result(s.name(), true, "accepted; this check cost 1 unit, and " + SearchTally.usedToday(Candidates.TALLY) + " of the day's " + Candidates.QUOTA + " units are counted as used (the day turns at midnight Pacific time)", at);
                    if (r.status() == 403 && r.body() != null && r.body().contains("quotaExceeded")) return new Result(s.name(), true, "accepted, but the day's " + Candidates.QUOTA + " units are used up", at);
                    if (r.status() == 400 || r.status() == 403) return new Result(s.name(), false, "refused: the key is not accepted (is the YouTube Data API v3 enabled for its project?)", at);
                    return refusedOrSilent(s, r, at);
                }
                case "SerpApi" -> {
                    Res r = call(s, new Req("GET", "https://serpapi.com/account.json?api_key=" + enc(k), Map.of(), null));
                    if (ok(r)) { JsonNode j = M.readTree(r.body()); return new Result(s.name(), true, "accepted, " + j.path("plan_searches_left").asInt() + " of " + j.path("searches_per_month").asInt() + " searches left this month", at); }
                    return refusedOrSilent(s, r, at);
                }
                case "Firecrawl" -> {
                    // the second version of the API, as the page reads go (Firecrawl.BASE): the first one answers 410 from 10 December 2026.
                    // The count is remainingCredits there; the first version's remaining_credits is still read, for an answer in that form.
                    Res r = call(s, new Req("GET", "https://api.firecrawl.dev/v2/team/credit-usage", Map.of("Authorization", "Bearer " + k), null));
                    if (ok(r)) { JsonNode d = M.readTree(r.body()).path("data"); return new Result(s.name(), true, "accepted, " + d.path("remainingCredits").asInt(d.path("remaining_credits").asInt()) + " credits left", at); }
                    return refusedOrSilent(s, r, at);
                }
                case "Brave Search" -> {
                    // the check is one search on the plan; never past the month's limit
                    if (!SearchTally.reserve(WebSearchTool.BRAVE)) return new Result(s.name(), null, "not checked: " + SearchTally.usedUp(WebSearchTool.BRAVE, "Brave"), at);
                    Res r = new Res(0, Map.of(), "");
                    try { r = HTTP.apply(new Req("GET", WebSearchTool.BRAVE_API.get("search") + "?q=researchzosho&count=1", Map.of("X-Subscription-Token", k, "Accept", "application/json"), null)); }
                    finally { SearchTally.settle(WebSearchTool.BRAVE, ok(r), 0, 0); }
                    if (ok(r)) {
                        String rem = r.headers().getOrDefault("x-ratelimit-remaining", ""), lim = r.headers().getOrDefault("x-ratelimit-limit", "");
                        String[] rs = rem.split(","), ls = lim.split(",");
                        if (rs.length > 1 && ls.length > 1 && !ls[1].strip().equals("0")) return new Result(s.name(), true, "accepted, " + rs[1].strip() + " of " + ls[1].strip() + " calls left this month", at);
                        if (ls.length > 1) return new Result(s.name(), true, "accepted; this plan counts in dollars, not calls ($5 of free calls a month, then $5 per 1,000), and allows " + ls[0].strip() + " calls a second", at);   // the headers' monthly figure is 0 on the credit plans
                        return new Result(s.name(), true, "accepted; this service did not say what is left", at);
                    }
                    return refusedOrSilent(s, r, at);
                }
                case "RentCast" -> {
                    Res r = call(s, new Req("GET", "https://api.rentcast.io/v1/listings/sale?city=Austin&state=TX&status=Active&limit=1", Map.of("X-Api-Key", k, "Accept", "application/json"), null));
                    if (ok(r)) { int[] t = SearchTally.thisMonth().getOrDefault("RentCast", new int[]{0, 0}); return new Result(s.name(), true, "accepted; this service does not say what is left; the tally counts " + t[0] + " of the 50 free calls this month, this check among them", at); }
                    return refusedOrSilent(s, r, at);
                }
                case "不動産情報ライブラリ (MLIT)" -> {
                    // the list of Tokyo's municipalities, the smallest call the API has; the key goes in the header the ministry names, to its host alone
                    Res r = call(s, new Req("GET", HomeJapanRecords.BASE + "XIT002?area=13", Map.of("Ocp-Apim-Subscription-Key", k, "Accept-Encoding", "identity"), null));
                    if (ok(r)) return new Result(s.name(), true, "accepted; this service does not say what is left, and sets no numeric limit (space the calls out)", at);
                    return refusedOrSilent(s, r, at);
                }
                case "Census Bureau" -> {
                    Res r = call(s, new Req("GET", "https://api.census.gov/data/2023/acs/acs5?get=NAME&for=state:25&key=" + enc(k), Map.of(), null));
                    if (ok(r)) return new Result(s.name(), true, "accepted; this service does not say what is left", at);
                    if (r.status() == 400 && r.body().toLowerCase(Locale.ROOT).contains("key")) return new Result(s.name(), false, "refused: the key is not accepted", at);
                    return refusedOrSilent(s, r, at);
                }
                case "Trove" -> { return plain(s, new Req("GET", "https://api.trove.nla.gov.au/v3/result?category=newspaper&q=library&n=0&encoding=json", Map.of("X-API-KEY", k), null), at); }
                case "DigitalNZ" -> { return plain(s, new Req("GET", "https://api.digitalnz.org/records.json?api_key=" + enc(k) + "&text=library&per_page=0", Map.of(), null), at); }
                case "Europeana" -> {
                    Res r = call(s, new Req("GET", "https://api.europeana.eu/record/v2/search.json?wskey=" + enc(k) + "&query=library&rows=0", Map.of(), null));
                    if (ok(r) && M.readTree(r.body()).path("success").asBoolean(false)) return new Result(s.name(), true, "accepted; this service does not say what is left", at);
                    return refusedOrSilent(s, r, at);
                }
                case "DPLA" -> { return plain(s, new Req("GET", "https://api.dp.la/v2/items?q=library&page_size=1&api_key=" + enc(k), Map.of(), null), at); }
                case "USAJobs" -> {
                    String email = Config.get("RESEARCHZOSHO_USAJOBS_EMAIL", "");
                    return plain(s, new Req("GET", "https://data.usajobs.gov/api/search?ResultsPerPage=1", Map.of("Authorization-Key", k, "User-Agent", email == null ? "" : email, "Host", "data.usajobs.gov"), null), at);
                }
                case "Podcast Index" -> {
                    String secret = key(s, 1), date = String.valueOf(Instant.now().getEpochSecond());
                    return plain(s, new Req("GET", "https://api.podcastindex.org/api/1.0/stats/current", Map.of("X-Auth-Key", k, "X-Auth-Date", date, "Authorization", sha1(k + secret + date)), null), at);
                }
                case "Yahoo! JAPAN" -> { return plain(s, new Req("GET", "https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch?appid=" + enc(k) + "&query=%E6%9C%AC&results=1", Map.of(), null), at); }
                case "Rakuten" -> { return plain(s, new Req("GET", "https://app.rakuten.co.jp/services/api/IchibaItem/Search/20220601?applicationId=" + enc(k) + "&accessKey=" + enc(key(s, 1)) + "&keyword=%E6%9C%AC&hits=1", Map.of(), null), at); }
                case "Reddit" -> {
                    String basic = Base64.getEncoder().encodeToString((k + ":" + key(s, 1)).getBytes(StandardCharsets.UTF_8));
                    Res r = call(s, new Req("POST", "https://www.reddit.com/api/v1/access_token", Map.of("Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded"), "grant_type=client_credentials"));
                    if (ok(r) && M.readTree(r.body()).hasNonNull("access_token")) return new Result(s.name(), true, "accepted; this service does not say what is left", at);
                    return refusedOrSilent(s, r, at);
                }
                case "Companies House" -> {
                    String basic = Base64.getEncoder().encodeToString((k + ":").getBytes(StandardCharsets.UTF_8));
                    Res r = call(s, new Req("GET", "https://api.company-information.service.gov.uk/company/00000006", Map.of("Authorization", "Basic " + basic), null));
                    if (ok(r)) { String rem = r.headers().getOrDefault("x-ratelimit-remain", ""), lim = r.headers().getOrDefault("x-ratelimit-limit", ""); return new Result(s.name(), true, rem.isEmpty() ? "accepted; this service did not say what is left" : "accepted, " + rem + " of " + lim + " calls left in the five-minute window", at); }
                    return refusedOrSilent(s, r, at);
                }
                case "gBizINFO" -> { return plain(s, new Req("GET", "https://info.gbiz.go.jp/hojin/v1/hojin?name=%E6%97%A5%E6%9C%AC&limit=1", Map.of("X-hojinInfo-api-token", k), null), at); }
                case "Stack Exchange" -> {
                    Res r = call(s, new Req("GET", "https://api.stackexchange.com/2.3/info?site=stackoverflow&key=" + enc(k), Map.of("Accept-Encoding", "identity"), null));
                    if (ok(r)) { JsonNode j = M.readTree(r.body()); return new Result(s.name(), true, "accepted, " + j.path("quota_remaining").asInt() + " of " + j.path("quota_max").asInt() + " calls left today", at); }
                    return refusedOrSilent(s, r, at);
                }
                case "eBay" -> {
                    String basic = Base64.getEncoder().encodeToString((k + ":" + key(s, 1)).getBytes(StandardCharsets.UTF_8));
                    return plain(s, new Req("POST", "https://api.ebay.com/identity/v1/oauth2/token", Map.of("Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded"), "grant_type=client_credentials&scope=" + enc("https://api.ebay.com/oauth/api_scope")), at);
                }
                case "Etsy" -> { return plain(s, new Req("GET", "https://openapi.etsy.com/v3/application/openapi-ping", Map.of("x-api-key", k), null), at); }
                case "CiNii" -> { return plain(s, new Req("GET", "https://cir.nii.ac.jp/opensearch/books?appid=" + enc(k) + "&q=%E6%9C%AC&count=1&format=json", Map.of(), null), at); }
                case "PubMed (NCBI)" -> {
                    Res r = call(s, new Req("GET", PubMed.BASE.get("eutils") + "/einfo.fcgi?db=pubmed&retmode=json&tool=researchzosho&api_key=" + enc(k), Map.of(), null));
                    if (ok(r)) { String lim = r.headers().getOrDefault("x-ratelimit-limit", ""); return new Result(s.name(), true, lim.isBlank() ? "accepted; this service does not say what is left" : "accepted, " + lim + " requests a second", at); }
                    if (r.status() == 400 && r.body().contains("API key invalid")) return new Result(s.name(), false, "refused: the key is not accepted", at);
                    return refusedOrSilent(s, r, at);
                }
                case "Semantic Scholar" -> { return plain(s, new Req("GET", SemanticScholarTool.BASE.get("search") + "?query=researchzosho&limit=1&fields=title", Map.of("x-api-key", k), null), at); }
                case "Exa" -> {
                    // a check is one search, the lighter kind, and costs what one costs; never past the month's limit
                    if (!SearchTally.reserve(ExaSearchTool.TALLY)) return new Result(s.name(), null, "not checked: " + SearchTally.usedUp(ExaSearchTool.TALLY, "Exa"), at);
                    Res r = new Res(0, Map.of(), "");
                    double cost = 0;
                    try {
                        r = HTTP.apply(new Req("POST", ExaSearchTool.BASE.get("search"), Map.of("x-api-key", k, "Content-Type", "application/json"), "{\"query\":\"researchzosho\",\"type\":\"lite\",\"numResults\":1}"));
                        if (ok(r)) { try { cost = M.readTree(r.body()).path("costDollars").path("total").asDouble(0.004); } catch (IOException e) { cost = 0.004; } }
                    } finally { SearchTally.settle(ExaSearchTool.TALLY, ok(r), 0, cost); }
                    if (ok(r)) return new Result(s.name(), true, "accepted; Exa does not say what is left, and this check's search cost " + SearchTally.money(cost), at);
                    if (r.status() == 402) return new Result(s.name(), true, "accepted, but the account's credits are used up", at);
                    return refusedOrSilent(s, r, at);
                }
                case "Tavily" -> {
                    Res r = call(s, new Req("GET", TavilySearchTool.BASE.get("usage"), Map.of("Authorization", "Bearer " + k), null));
                    if (ok(r)) {
                        JsonNode j = M.readTree(r.body()), acct = j.path("account"), key = j.path("key");
                        String plan = acct.path("current_plan").asText("");
                        if (acct.path("plan_limit").isNumber()) return new Result(s.name(), true, "accepted, " + acct.path("plan_usage").asInt() + " of " + acct.path("plan_limit").asInt() + " credits used this month" + (plan.isEmpty() ? "" : " on the " + plan + " plan"), at);
                        if (key.path("limit").isNumber()) return new Result(s.name(), true, "accepted, " + key.path("usage").asInt() + " of " + key.path("limit").asInt() + " credits used by this key", at);
                        return new Result(s.name(), true, "accepted; this service did not say what is left", at);
                    }
                    return refusedOrSilent(s, r, at);
                }
                default -> { return new Result(s.name(), null, "no check is written for this service", at); }
            }
        } catch (IOException | RuntimeException e) { return new Result(s.name(), null, "the check could not be made: " + e.getClass().getSimpleName(), at); }
    }

    static Res call(Service s, Req r) { Res res = HTTP.apply(r); SearchTally.count(s.tallyName(), 0); return res; }

    /** The keeper's own search service, asked one search for "researchzosho" the way a run asks it: what came back, in words. */
    static Result customProbe(Service s, String at) {
        CustomSearchTool.Checked c = CustomSearchTool.check();
        return new Result(s.name(), c.answers(), c.said(), at);
    }
    static boolean ok(Res r) { return r.status() >= 200 && r.status() < 300; }
    static Result plain(Service s, Req r, String at) { Res res = call(s, r); return ok(res) ? new Result(s.name(), true, "accepted; this service does not say what is left", at) : refusedOrSilent(s, res, at); }
    static Result refusedOrSilent(Service s, Res r, String at) {
        if (r.status() == 401 || r.status() == 403) return new Result(s.name(), false, "refused: the key is not accepted", at);
        if (r.status() == 429) return new Result(s.name(), true, "accepted, but the service says the allowance is used up for now", at);
        if (r.status() == 0) return new Result(s.name(), null, "the service did not answer", at);
        return new Result(s.name(), null, "the service answered " + r.status() + ", which says nothing about the key", at);
    }
    static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String dollars(double d) { return d >= 100 ? String.format(Locale.US, "$%,.0f", d) : String.format(Locale.US, "$%.2f", d); }
    static String sha1(String s) {
        try { byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString(); }
        catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- the table

    /** One row per service, for the page, the commands and the protocol: this month's calls, credits and dollars, and the monthly limit (-1: none). */
    public record Row(Service service, boolean set, int calls, int credits, Result last, double dollars, int limit) {
        public Row(Service service, boolean set, int calls, int credits, Result last) { this(service, set, calls, credits, last, 0, -1); }
    }

    public static List<Row> rows(LibraryStore store) {
        Map<String, int[]> month = SearchTally.thisMonth();
        Map<String, Result> last = store == null ? Map.of() : kept(store);
        List<Row> out = new ArrayList<>();
        for (Service s : KNOWN) {
            int[] t = month.getOrDefault(s.tallyName(), new int[]{0, 0});
            out.add(new Row(s, s.set(), t[0], t[1], last.get(s.name()), SearchTally.dollarsThisMonth(s.tallyName()), SearchTally.limit(s.tallyName())));
        }
        return out;
    }

    /** The use this month, in English: what a program reads (use_this_month). */
    public static String use(Row r) { return use(r, Chrome.ENGLISH); }

    /**
     * The use this month, in words, in {@code c}'s language: "21 calls" / "none"; the credits when charged, the dollars when the service
     * says, and the monthly limit beside them when there is one, each a whole phrase of its own in a list.
     */
    public static String use(Row r, Chrome c) {
        String limit = r.limit() >= 0 ? c.number(r.limit()) : null;
        if (r.calls() == 0) return limit != null ? c.t("none of the {0} a month the library stops at", limit) : c.tc("none", NO_USE);
        List<String> parts = new ArrayList<>();
        parts.add(limit != null ? c.t("{0} of the {1} a month the library stops at", c.number(r.calls()), limit) : c.n(r.calls(), "{0} call", "{0} calls"));
        if (r.credits() > 0) parts.add(c.n(r.credits(), "{0} credit", "{0} credits"));
        if (r.dollars() > 0) parts.add(SearchTally.money(r.dollars()));
        return String.join(c.sep(), parts);
    }

    /** The meaning beside "none" in the table's use column. */
    static final String NO_USE = "this month's use of an outside service: no calls";

    /** A service's name as the keeper's page shows it: the two named in plain words in the person's language, every other by its own name. */
    public static String nameSaid(Service s, Chrome c) {
        return switch (s.name()) {
            case "The model server" -> c.t("The model server");
            case "Your own search service" -> c.t("Your own search service");
            default -> s.name();
        };
    }

    /** The last check, in words with its day; "" when none. */
    public static String lastSaid(Row r) {
        if (r.last() == null) return "";
        return r.last().said() + " (" + r.last().at().replace("T", " ").replaceFirst("Z$", " UTC") + ")";
    }

    /** The rows as JSON, the protocol's and the page's: name, adds, terms, checked_on, link, settings, set, use_this_month, last_check. */
    public static ArrayNode json(LibraryStore store) {
        ArrayNode a = M.createArrayNode();
        for (Row r : rows(store)) {
            ObjectNode o = a.addObject();
            o.put("service", r.service().name()).put("adds", r.service().adds()).put("terms", r.service().terms()).put("checked_on", r.service().checkedOn()).put("link", r.service().link());
            ArrayNode st = o.putArray("settings"); for (String s : r.service().allSettings()) st.add(s);
            o.put("set", r.set()).put("calls_this_month", r.calls()).put("credits_this_month", r.credits()).put("use_this_month", use(r));
            if (r.dollars() > 0) o.put("dollars_this_month", r.dollars());
            if (r.limit() >= 0) o.put("monthly_limit", r.limit());
            if (r.last() != null) { ObjectNode l = o.putObject("last_check"); if (r.last().accepted() != null) l.put("accepted", r.last().accepted()); l.put("said", r.last().said()); l.put("at", r.last().at()); }
        }
        return a;
    }

    /** The table as text, for the commands. */
    public static String text(LibraryStore store) {
        StringBuilder b = new StringBuilder("The outside services the library can use with a key, and what each gives. Prices and allowances as read on the services' own pages, each on the day shown.\n");
        for (Row r : rows(store)) {
            Service s = r.service();
            b.append("\n  ").append(s.name()).append(r.set() ? "  (key set)" : "  (no key)").append('\n');
            b.append("      ").append(cap(s.adds())).append(".\n");
            b.append("      ").append(cap(s.terms())).append(". Read on ").append(s.checkedOn()).append(".\n");
            b.append("      Key: ").append(s.link()).append(" → ").append(String.join(" and ", s.allSettings())).append(".\n");
            b.append("      This month: ").append(use(r)).append('.').append(r.last() == null ? "" : " Last check: " + lastSaid(r) + ".").append('\n');
        }
        b.append("\nresearchzosho services check asks each service with a key whether it is accepted and what is left; the Keep page and library_settings op=check do the same.\n");
        return b.toString();
    }

    /** The keys that the last check refused, for a page's notice, in English; "" when none. */
    public static String refusedNotice(LibraryStore store) { return refusedNotice(store, Chrome.ENGLISH); }

    /** The same in {@code c}'s language: a whole sentence for one key and for several. */
    public static String refusedNotice(LibraryStore store, Chrome c) {
        List<String> bad = new ArrayList<>();
        for (Result r : kept(store).values()) if (r.accepted() != null && !r.accepted()) bad.add(r.service());
        if (bad.isEmpty()) return "";
        List<String> named = new ArrayList<>(); for (Service s : KNOWN) if (bad.contains(s.name())) named.add(nameSaid(s, c));
        for (String b : bad) if (KNOWN.stream().noneMatch(s -> s.name().equals(b))) named.add(b);
        return bad.size() == 1 ? c.t("At the last check the key for {0} was refused: see Sources and keys below.", named.get(0))
                : c.t("At the last check the keys for {0} were refused: see Sources and keys below.", c.and(named));
    }
}
