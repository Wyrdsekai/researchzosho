package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Gated;
import org.researchzosho.drive.anthropic.Anthropic;
import org.researchzosho.drive.openai.OpenAI;
import org.researchzosho.tools.Addresses;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.Firecrawl;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The node's two gates (0.8.0, stage 3): every call of its libraries that costs the service money goes through one, which holds the
 * key, counts the call against the use it carries, and passes it on. A library has no key: it is given the gates' addresses in place
 * of the model servers' and the paid search places', and the keys of the paid places as a word that stands for them.
 *
 * <p>The model gate ({@code /gate/<scheme>/<host>/…}) passes a call to the model server its address stands for, one of the servers
 * the service's settings name and no other. A call that costs (an answer, embeddings, a reranking) must carry a use the service let
 * start ({@link Gated#USE}); it is refused when its use has spent its ceiling and a tenth more, or its account its month and a tenth
 * more, and otherwise waits for a slot in its lane at that server ({@link AccountStore#laneTake}): a person who waits (a chat turn)
 * in one lane, the background work (runs, translations, nights) in the other. What it spent is read from the server's answer, or
 * worked out from the sizes when the answer says nothing of it, and written to the account's month in the store.
 *
 * <p>The search gate ({@code /search/<place>/…}) does the same for the paid search places, a search a call, against the month's
 * searches.
 */
public final class NodeGates implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(NodeGates.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build());

    /** A paid place: its name in the gate's path, where it is, the setting of its key, where the key goes, and what a call to it is. */
    record Place(String name, String base, String keySetting, String keyGoes, String kind) { }

    static final List<Place> PLACES = List.of(
            new Place("brave", "https://api.search.brave.com", "RESEARCHZOSHO_BRAVE_KEY", "X-Subscription-Token", "search"),
            new Place("serper", "https://google.serper.dev", WebSearchTool.SERPER_KEY, "X-API-KEY", "search"),
            new Place("serpapi", "https://serpapi.com", WebSearchTool.SERPAPI_KEY, "query:api_key", "search"),
            new Place("exa", "https://api.exa.ai", ExaSearchTool.KEY, "x-api-key", "search"),
            new Place("tavily", "https://api.tavily.com", TavilySearchTool.KEY, "Authorization", "search"),
            new Place("firecrawl", "https://api.firecrawl.dev", Firecrawl.KEY, "Authorization", "fetch"));

    /**
     * What one call to each paid place costs, in dollars, as each place's own pricing page said on 2026-11-17 (docs/public/ACCOUNTS.md
     * names the page, the plan and the figures): Brave's search plan, $5 a thousand requests; Serper's smallest package, $50 for 50,000
     * queries; SerpApi's smallest paid plan, $25 a month for 1,000 searches; Exa's auto search, $7 a thousand requests (an answer of
     * Exa's that says what it cost is counted at that); Tavily's pay-as-you-go credit, $0.008, a basic search one credit and an advanced
     * one two; Firecrawl's smallest paid plan, $19 a month for 5,000 credits, a page one credit. {@code search_price.<place>} sets another.
     */
    static final Map<String, String> PRICE_READ = Map.of("brave", "0.005", "serper", "0.001", "serpapi", "0.025", "exa", "0.007", "tavily", "0.008", "firecrawl", "0.0038");

    /** One call's price at a place, in millionths of a dollar: its setting, else the price read from the place's page. */
    static long priceMicros(String place) {
        String set = Config.get(Settings.SEARCH_PRICE + place.toUpperCase(Locale.ROOT));
        String v = set != null && set.strip().matches("\\d+(\\.\\d+)?") ? set.strip() : PRICE_READ.getOrDefault(place, "0");
        return Math.round(Double.parseDouble(v) * 1_000_000);
    }

    /**
     * What a call to a paid place cost, in millionths of a dollar: what the place's answer says when it says (Exa's costDollars), else
     * its price a call times the credits the call takes (Tavily's advanced search two, Firecrawl's search two for ten results).
     */
    static long callMicros(String place, String path, byte[] request, byte[] reply) {
        if (place.equals("exa")) {
            try { JsonNode c = M.readTree(reply).path("costDollars").path("total"); if (c.isNumber() && c.asDouble() >= 0) return Math.round(c.asDouble() * 1_000_000); }
            catch (IOException | RuntimeException notJson) { /* its price */ }
        }
        long credits = 1;
        String body = new String(request, StandardCharsets.UTF_8);
        if (place.equals("tavily") && body.matches("(?s).*\"search_depth\"\\s*:\\s*\"advanced\".*")) credits = 2;
        if (place.equals("firecrawl") && path.contains("/search")) credits = 2;
        return credits * priceMicros(place);
    }

    /** What a model with no prices, at an API that charges, is counted at (dollars a million tokens read, written): a frontier model's list price. */
    static final double[] UNPRICED = {3.0, 15.0};
    private static final Set<String> UNPRICED_SAID = ConcurrentHashMap.newKeySet();

    static String hostOf(String url) { try { String h = URI.create(url == null ? "" : url.strip()).getHost(); return h == null ? (url == null ? "" : url) : h; } catch (RuntimeException e) { return url == null ? "" : url; } }

    /** A hold's ping through llama-swap: its way to one model's own server, asked for the server's health. */
    static final Pattern HOLD_PATH = Pattern.compile(".*/upstream/([^/]+)/health");
    /** When each library last had a model pinged through the gate, by library, server and model. */
    private static final Map<String, Long> PINGED = new ConcurrentHashMap<>();

    /** Whether a model is one the library's entries name: the model it is set to, a model entry's, or the embeddings model. */
    static boolean named(String model) {
        if (model == null || model.isBlank()) return false;
        if (model.equals(Config.get("RESEARCHZOSHO_MODEL")) || model.equals(Config.get("RESEARCHZOSHO_EMBED_MODEL", "embed")) || model.equals(ModelServer.EMBED_NAME)) return true;
        for (ModelEntries.Entry e : ModelEntries.all().values()) if (model.equals(e.model())) return true;
        return false;
    }

    /** Whether a model server's address is an API on the internet that charges; a test says yes for its own stand-in. */
    static final Predicate<String> HOSTED_BY_ADDRESS = d -> DriveClient.hostedApi(d) || OpenAI.is(d) || Anthropic.is(d);
    static volatile Predicate<String> HOSTED = HOSTED_BY_ADDRESS;

    /**
     * What a call at a paid place is counted as: {@code search} or {@code fetch}, or null for what costs nothing there (an account's usage,
     * its credits, its status). Firecrawl's search is a search (held to the searches cap); its scrape, crawl and map are fetches.
     */
    static String countedKind(Place p, String tail) {
        String t = tail.toLowerCase(Locale.ROOT);
        return switch (p.name()) {
            case "brave" -> t.startsWith("/res/") && t.contains("search") ? "search" : null;
            case "serper" -> t.startsWith("/account") || t.isEmpty() ? null : "search";
            case "serpapi" -> t.startsWith("/search") ? "search" : null;
            case "exa" -> t.startsWith("/search") || t.startsWith("/contents") || t.startsWith("/findsimilar") || t.startsWith("/answer") || t.startsWith("/research") ? "search" : null;
            case "tavily" -> t.startsWith("/search") || t.startsWith("/extract") || t.startsWith("/crawl") || t.startsWith("/map") ? "search" : null;
            case "firecrawl" -> t.contains("/search") ? "search" : t.contains("/scrape") || t.contains("/crawl") || t.contains("/map") || t.contains("/extract") || t.contains("/batch") ? "fetch" : null;
            default -> p.kind();
        };
    }

    /** Where each paid place is, by its name; a test points one at its own server. */
    /**
     * The calls the program itself makes at each paid place, by method and path; the gate passes no other with the service's key (round
     * F, the second review: an account call answered with the key itself, a crawl of ten thousand pages was counted as one fetch, and an
     * unknown path as nothing). A call that takes many credits at once is not on it, because the program never makes one.
     */
    static final Map<String, Set<String>> CALLS = Map.of(
            "brave", Set.of("GET /res/v1/web/search", "GET /res/v1/images/search"),
            "serper", Set.of("POST /search", "POST /resolve"),
            "serpapi", Set.of("GET /search.json"),
            "exa", Set.of("POST /search"),
            "tavily", Set.of("POST /search"),
            "firecrawl", Set.of("POST /v2/scrape"));

    static boolean madeByTheProgram(Place p, String method, String tail) { return CALLS.getOrDefault(p.name(), Set.of()).contains(method.toUpperCase(Locale.ROOT) + " " + tail); }

    static final Map<String, String> BASES = new ConcurrentHashMap<>();
    static { for (Place p : PLACES) BASES.put(p.name(), p.base()); }

    /** Whether the calls wait their turn in the two lanes; off only to measure what the lanes do (a property, never a setting). */
    static volatile boolean LANES = !"off".equalsIgnoreCase(System.getProperty("researchzosho.accounts.lanes", ""));

    /** What a library is given in place of a paid place's key: a word, so it knows the place may be used, and no key. */
    static final String KEPT = "kept-by-the-node";
    /** The setting that tells a library where the search gate is. */
    static final String SEARCH_GATE = "RESEARCHZOSHO_SEARCH_GATE";
    /** The settings that name a model server (an address, or several with commas). */
    static final List<String> MODEL_SETTINGS = List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_JUDGE_DRIVE", "RESEARCHZOSHO_VISION_DRIVE", "RESEARCHZOSHO_JOB_DRIVES", "RESEARCHZOSHO_EMBED", "RESEARCHZOSHO_RERANK");
    /** How many calls at once a model server of one's own is given when it does not say and its entry sets none ({@code entry.<name>.calls}). */
    static final int CALLS_UNSET = 4;
    /** How many of a server's slots are kept for a person who waits, unless its entry says ({@code entry.<name>.kept}). */
    static final int KEPT_UNSET = 1;
    /** A call's slot is held this long at most, if the call never says it ended. */
    static final long SLOT_MS = 15 * 60_000L;
    /** How long a call waits for its slot before it is told the model is busy. */
    static volatile long WAIT_MS = 10 * 60_000L;
    /** A use or a month may go past its ceiling by a tenth, at least this much, so the work's unit can finish. */
    static final long MARGIN_MICROS = 250_000;
    /** Bytes for a token, where a server's answer does not say how many it read and wrote. */
    static final int BYTES_PER_TOKEN = 4;

    private static final Map<AccountStore, NodeGates> RUNNING = new ConcurrentHashMap<>();

    private final AccountStore store;
    private final HttpServer server;
    /** The model servers the settings name, by their gate's path, and their origins (scheme://host:port), which alone are passed to. */
    private final Map<String, String> drives = new ConcurrentHashMap<>();
    private final Set<String> origins = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> capacity = new ConcurrentHashMap<>();
    /** How many calls that cost came with no use the service let start, and how many were passed on: for whoever runs the service, and a test. */
    final AtomicLong refusedNoUse = new AtomicLong(), passed = new AtomicLong();

    /** The gates for the libraries of {@code store}, started the first time. */
    static synchronized NodeGates of(AccountStore store) throws IOException {
        NodeGates g = RUNNING.get(store);
        if (g == null) { g = new NodeGates(store); RUNNING.put(store, g); }
        return g;
    }

    private NodeGates(AccountStore store) throws IOException {
        this.store = store;
        server = HttpServers.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", x -> { try { handle(x); } finally { x.close(); } });
        server.start();
    }

    /** Where the gates are, on this machine's loopback: a library in a container reaches it through a relay of its own. */
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    // ---- what a library is given ----

    /**
     * The settings a library is given, put through the gates: each model server's address as its gate's, the paid places' keys as
     * {@link #KEPT} (only for the places whose keys the service has), and where the search gate is. No key is in them.
     */
    void rewrite(Map<String, String> env) { rewrite(env, null); }

    /**
     * The same for the library {@code libraryId}: its heavy helpers too, each the gate's address under the library's id (the service's
     * own helper where the service has one, else off), so a helper's call is counted and the helpers' secret stays at the node.
     */
    void rewrite(Map<String, String> env, String libraryId) {
        if (libraryId != null) helpersThrough(env, libraryId);
        String at = within(libraryId);
        for (String name : MODEL_SETTINGS) {
            for (String key : List.of(name, name + Config.DEFAULT_SUFFIX)) {
                String v = env.get(key);
                if (v == null || v.isBlank() || v.strip().equalsIgnoreCase("off") || v.strip().equalsIgnoreCase("none")) continue;
                List<String> out = new ArrayList<>();
                for (String one : v.split(",")) {
                    String u = one.strip();
                    // only a server by its address goes through the gate; Amazon Bedrock (a word, signed with AWS's keys) is not offered to
                    // a library under the service in this version, and a library given it would reach for the service's own AWS files
                    if (u.startsWith("http://") || u.startsWith("https://")) out.add(through(u, at));
                    else if (!u.isEmpty()) LOG.warn("{} is {} in the service's settings; a library under the service is not given it (only a model server by its address goes through the node's gate).", key, u);
                }
                if (out.isEmpty()) env.remove(key); else env.put(key, String.join(",", out));
            }
        }
        for (Place p : PLACES) {
            String k = Config.get(p.keySetting());
            if (k != null && !k.isBlank()) env.put(p.keySetting(), KEPT); else env.remove(p.keySetting());
        }
        env.put(SEARCH_GATE, at + Gated.SEARCH.substring(0, Gated.SEARCH.length() - 1));
    }

    /**
     * The gates' address a library is given: under its own id ({@code /lib/<id>}), so a call that costs is counted only against a use of
     * the library that makes it, as the helpers' and the web's gates do; the gates' own address for no library (a call of that is refused).
     */
    String within(String libraryId) { return libraryId == null || !LibraryPlaces.idOk(libraryId) ? base() : base() + Gated.LIB + libraryId; }

    /** The heavy helpers the service reaches, by their name: where the gate passes a library's call to each. */
    private final Map<String, String> helpers = new ConcurrentHashMap<>();
    /** The helpers a library is given through the gate; the embeddings server is a model setting, and goes through the model gate. */
    static final List<Helpers.Helper> GATED_HELPERS = List.of(Helpers.Helper.METRICX, Helpers.Helper.LABSE, Helpers.Helper.LIKENESS, Helpers.Helper.WHISPER, Helpers.Helper.COMIC);

    private void helpersThrough(Map<String, String> env, String libraryId) {
        for (Helpers.Helper h : GATED_HELPERS) {
            String at = h == Helpers.Helper.COMIC ? ComicHelper.remote() : Helpers.address(h);
            if (at == null || at.isBlank()) { env.put(h.setting, "off"); continue; }
            helpers.put(h.path, at.replaceAll("/+$", ""));
            env.put(h.setting, base() + Gated.LIB + libraryId + Gated.HELPER + libraryId + "/" + h.path);   // under the library's own path, as its other gates are (round F, money 9)
        }
        String embed = env.get("RESEARCHZOSHO_EMBED");
        if ((embed == null || embed.isBlank()) && !Helpers.off(Helpers.Helper.EMBED)) {   // the embeddings server the helpers machine serves, through the model gate
            String at = Helpers.address(Helpers.Helper.EMBED);
            if (at != null) env.put("RESEARCHZOSHO_EMBED", through(at, within(libraryId)));
        }
        env.put(Helpers.HELPERS, "off");   // nothing else of the helpers machine: the library looks for none of its own
    }

    /** A model server's address as its gate's, at {@code at} (the gates' address a library is given); an address that is not one (bedrock, a word) as it is. */
    String through(String url, String at) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url;
        drives.put(URI.create(Gated.via(base(), url.replaceAll("/+$", ""))).getRawPath(), url.replaceAll("/+$", ""));   // by its path under the gate, whatever library is given it
        origins.add(origin(URI.create(url)));
        return Gated.via(at, url.replaceAll("/+$", ""));
    }

    static String origin(URI u) {
        String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
        int port = u.getPort() >= 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + (u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT)) + ":" + port;
    }

    // ---- one call ----

    private void handle(HttpExchange x) throws IOException {
        String whole = x.getRequestURI().getRawPath(), path = Gated.inner(whole), library = Gated.libraryOf(whole);
        String lang = x.getRequestHeaders().getFirst(Gated.LANG);
        if (lang != null && lang.matches("[a-z]{2}(-[A-Za-z]{2,4})?")) Chrome.here(lang);   // a refusal is said in the person's language
        try {
            if (whole.startsWith(Gated.LIB) && library == null) refuse(x, 404, "not_found", "Nothing is here.");
            else if (path.startsWith(Gated.PATH)) model(x, path, library);
            else if (path.startsWith(Gated.HELPER) && library != null) helper(x, path, library);
            else if (path.startsWith(Gated.SEARCH)) place(x, path, library);
            else refuse(x, 404, "not_found", "Nothing is here.");
        } catch (IOException | RuntimeException e) {
            LOG.warn("The gate could not pass a call on: {}", e.getMessage());
            try { refuse(x, 502, "unavailable", "The service's model or search place did not answer: " + e.getMessage()); } catch (IOException | RuntimeException sent) { /* the answer had begun */ }
        } finally { Chrome.clear(); }
    }

    private void model(HttpExchange x, String path, String library) throws IOException {
        String raw = path + (x.getRequestURI().getRawQuery() == null ? "" : "?" + x.getRequestURI().getRawQuery());
        String upstream = Gated.upstream(base() + raw);
        URI up = URI.create(upstream).normalize();   // "/./v1" and "/a/../v1" are the path they stand for, counted and requested as such
        upstream = up.toString();
        if (!origins.contains(origin(up))) { refuse(x, 403, "forbidden", "That is not a model server of this service."); return; }
        byte[] body = x.getRequestBody().readAllBytes();
        Matcher hold = HOLD_PATH.matcher(up.getRawPath());
        if (hold.matches() && x.getRequestMethod().equals("GET")) {   // a hold's or an intent's ping (round C2): llama-swap loads the model it names
            String model = URLDecoder.decode(hold.group(1), StandardCharsets.UTF_8);
            if (!named(model)) { refuse(x, 403, "forbidden", Pages.t("That model is not one this library's entries name.")); return; }
            String key = (library == null ? "" : library) + " " + origin(up) + " " + model;
            long now = System.currentTimeMillis();
            Long last = PINGED.get(key);
            if (last != null && now - last < ModelsAwake.PING_MS) {   // once a minute per library and model: more often is answered here, not passed on
                byte[] held = "{\"held\":true}".getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(200, held.length);
                try (OutputStream o = x.getResponseBody()) { o.write(held); }
                return;
            }
            PINGED.put(key, now);
        }
        // only the calls the program itself makes pass, with the service's key: one that costs (asked for by POST), or a look at the server
        boolean costs = Gated.costs(path) && x.getRequestMethod().equals("POST");
        if (!costs && !Gated.free(x.getRequestMethod(), path)) {
            LOG.warn("A call to {} ({} {}) is not one this program makes; it is refused.", origin(up), x.getRequestMethod(), path);
            refuse(x, 403, "forbidden", Pages.t("The service passes on only the calls this program makes to a model server, and this is not one of them."));
            return;
        }
        AccountStore.Use use = null;
        String slot = null, own = null, payer = "operator", reservation = null;
        String drive = driveOf(up), model = modelOf(body);
        if (costs) {
            use = store.use(x.getRequestHeaders().getFirst(Gated.USE));
            if (use != null && (library == null || !use.library().equals(library))) use = null;   // another library's use is not this one's to spend
            if (use != null && use.open()) { own = OwnKeys.keyFor(store, use.account(), up.getHost()); if (own != null) payer = "own"; }   // the person's own key for this host: their work, their dollars
            if (use == null || !use.open()) { refusedNoUse.incrementAndGet(); LOG.warn("A call to {} ({}) came with no use the service let start; it is refused.", origin(up), path); refuse(x, 403, "forbidden", Pages.t("A call that costs must be part of work the service let start, and this one is not.")); return; }
            // a call on the service's key names a model one of the service's entries names, at the server that entry names (round F: a model
            // no entry names was counted at nothing, or at a flat price far under its own)
            if (!"own".equals(payer) && !entryNames(drive, model, path)) {
                LOG.warn("A call to {} for the model {} names no entry of the service; it is refused.", origin(up), model == null ? "(none)" : model);
                refuse(x, 403, "forbidden", Pages.t("That model is not one this library's entries name."));
                return;
            }
            String over = over(use, payer);
            if (over != null) { refuse(x, 402, "budget_exceeded", over, use.id()); return; }
            if (!"own".equals(payer)) {   // what the call may cost, put aside against the use's ceiling and the month first: calls at once see each other (BILL 11)
                reservation = reserve(use, drive, body);
                if (reservation.startsWith("!")) { refuse(x, 402, "budget_exceeded", reservation.equals("!use") ? ceilingSaid(use) : CapsSpending.monthUsed(Caps.of(store, use.account()).get(Caps.MONTH_DOLLARS)), use.id()); return; }
            }
            boolean handedOn = false;   // what was put aside is given back on every way out before the call goes (round F: a call that got no slot kept it 35 minutes)
            try {
                String lane = Gated.WAITING.equals(x.getRequestHeaders().getFirst(Gated.LANE)) ? Gated.WAITING : Gated.BACKGROUND;
                int[] cap = LANES ? capacity(origin(up), up, model) : null;
                if (cap != null) {   // a server with lanes: one of one's own, or an entry whose calls are set
                    slot = slot(origin(up), lane, use.account(), cap);
                    if (slot == null) { refuse(x, 503, "unavailable", Pages.t("The service's model is busy. Ask again in a minute.")); return; }
                    // it may have waited minutes for the slot: an account suspended or deleted meanwhile makes no call (round F, money 10)
                    String meanwhile = over(use, payer);
                    if (meanwhile != null) { store.laneGiveBack(slot); slot = null; refuse(x, 402, "budget_exceeded", meanwhile, use.id()); return; }
                }
                handedOn = true;
            } finally { if (!handedOn && reservation != null) { try { store.unreserve(reservation); } catch (IOException e) { /* it runs out by itself */ } } }
        } else if (library != null) {
            // a look (the model list, a server's health) costs nothing: where the service keeps no key for the host, the library's keeper's
            // own key carries it, as it carries their work. Gemini's list answers no call without a key, so with own keys alone the library
            // read its model as down while every turn worked (the Gemini check, 2026-11-18).
            String operator = DriveClient.keyFor(drive.isEmpty() ? upstream : drive);
            if (operator == null) operator = DriveClient.hostKey(up.getHost());
            AccountStore.Library lib = operator == null || operator.isBlank() ? store.library(library) : null;
            if (lib != null && lib.active()) own = OwnKeys.keyFor(store, lib.owner(), up.getHost());
        }
        HttpResponse<InputStream> r = null;
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        boolean whole = false;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(up).timeout(Duration.ofMinutes(30)).method(x.getRequestMethod(), body.length == 0 && !x.getRequestMethod().equals("POST") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            copyRequestHeaders(x, b);
            String key = own != null ? own : DriveClient.keyFor(drive.isEmpty() ? upstream : drive);
            if (key == null) key = DriveClient.hostKey(up.getHost());
            if (key != null && !key.isBlank()) {
                if (Anthropic.is(upstream)) b.header("x-api-key", key.strip());
                else b.header("Authorization", key.strip().regionMatches(true, 0, "Bearer ", 0, 7) ? key.strip() : "Bearer " + key.strip());
            } else if (Helpers.ours(upstream) && !Config.get(Helpers.SECRET_NAME, "").isBlank()) b.header("Authorization", "Bearer " + Config.get(Helpers.SECRET_NAME, "").strip());   // the helpers machine's embeddings
            try { r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            pass(x, r, kept);
            whole = true;
            if (costs) passed.incrementAndGet();
        } finally {
            if (slot != null) store.laneGiveBack(slot);
            // counted from what passed, whether it ended or not: a reply the caller left (a run stopped, a time limit) or the server broke
            // off was answered, and is paid for, all the same
            if (costs && r != null && r.statusCode() >= 200 && r.statusCode() < 300)
                count(use, payer, drive, model, body, kept.toByteArray(), r.headers().firstValue("content-type").orElse(""), whole);
            if (reservation != null) { try { store.unreserve(reservation); } catch (IOException e) { /* it runs out by itself */ } }   // settled: what it cost is written
        }
    }

    /** The most a call may write when it does not say: what it puts aside for its answer. */
    static final long OUTPUT_UNSAID = 4_096;

    /**
     * What a call may cost (BILL 11), at its entry's prices: what it sends (four bytes a token) read, and the most it may write as it says
     * ({@code max_tokens}, {@code max_completion_tokens}, {@code max_output_tokens}), else {@link #OUTPUT_UNSAID}; put aside against the use's
     * ceiling and the account's month, with their margins. The reservation's id, or "!use" or "!month" when one is reached.
     */
    String reserve(AccountStore.Use use, String drive, byte[] body) throws IOException {
        String model = modelOf(body);
        ModelEntries.Entry e = Spend.entryOf(drive, model == null ? "" : model);
        long out = OUTPUT_UNSAID;
        try {
            JsonNode j = M.readTree(body);
            for (String k : List.of("max_tokens", "max_completion_tokens", "max_output_tokens")) if (j.path(k).canConvertToLong() && j.path(k).asLong() > 0) { out = j.path(k).asLong(); break; }
        } catch (IOException | RuntimeException notJson) { /* the default */ }
        long in = Math.max(1, body.length / BYTES_PER_TOKEN);
        // a model with no prices at an API that charges is put aside at what count() bills it at (round F: it put aside nothing)
        long micros = (e == null || e.prices() == null) && HOSTED.test(drive) ? Math.round(in * UNPRICED[0] + out * UNPRICED[1])
                : e == null ? 0 : Math.round(e.cost(in, 0, out) * 1_000_000);
        long useLimit = use.ceilingMicros() >= 0 ? use.ceilingMicros() + Math.max(use.ceilingMicros() / 10, MARGIN_MICROS) : -1, monthLimit = -1;
        if (Uses.builtIn()) { long cap = Caps.of(store, use.account()).get(Caps.MONTH_DOLLARS); if (cap >= 0) monthLimit = cap + Math.max(cap / 10, MARGIN_MICROS); }
        return store.reserve(use.account(), use.id(), micros, useLimit, monthLimit, AccountStore.month(System.currentTimeMillis()));
    }

    /** What a use that has spent its ceiling is told. */
    private static String ceilingSaid(AccountStore.Use use) { return Pages.t("This work has spent the most it may ({0}), so it stops here. Whoever runs the service can raise the cap.", Caps.dollars(use.ceilingMicros())); }

    /** Why a use may not spend more now, as the person is told it; null while it may. */
    String over(AccountStore.Use use) throws IOException { return over(use, "operator"); }

    /**
     * Whether a use has reached its cap, without the margin the gate allows: what it spent of the operator's against its ceiling, and
     * its account's month against the month's cap (the built-in caps'). The library stops the work after its call at this, and the
     * gate refuses at the cap and a tenth more.
     */
    static String reached(AccountStore store, AccountStore.Use use) throws IOException { return reached(store, use, use.openedAt()); }

    /** The same, its minutes counted from {@code startedMs}: when the work began to run (round F, money 10: from its filing, its time in the line counted). */
    static String reached(AccountStore store, AccountStore.Use use, long startedMs) throws IOException {
        AccountStore.Account a = store.account(use.account());
        if (a != null && !a.active()) return AccountActs.notActiveSaid(store, a);   // suspended or being deleted: its running work stops as at a cap
        if (use.ceilingMicros() >= 0 && store.useSpentBy(use.id(), "operator") >= use.ceilingMicros())
            return Pages.t("This work has spent the most it may ({0}), so it stops here. Whoever runs the service can raise the cap.", Caps.dollars(use.ceilingMicros()));
        // a job's minutes (job_minutes, or the plug-in's ceiling_minutes): past them, the watch stops it as at a cap (BILL 4)
        if (use.ceilingMinutes() > 0 && System.currentTimeMillis() - Math.max(use.openedAt(), startedMs) > use.ceilingMinutes() * 60_000L)
            return Pages.t("This work has run as long as it may ({0}), so it stops here. Whoever runs the service can raise the cap.", Pages.n(use.ceilingMinutes(), "{0} minute", "{0} minutes"));
        if (Uses.builtIn() && !"own".equals(use.payer())) {   // the month is the operator's dollars: work on the person's own key is not stopped by it (round F)
            long cap = Caps.of(store, use.account()).get(Caps.MONTH_DOLLARS);
            if (cap >= 0) {
                long month = 0;
                for (AccountStore.MonthTotal t : store.month(use.account(), AccountStore.month(System.currentTimeMillis()))) if (t.payer().equals("operator")) month += t.micros();
                if (month >= cap) return CapsSpending.monthUsed(cap);
            }
        }
        return null;
    }

    /**
     * Why a use may not spend more of the operator's now; null while it may, and always for a call the person pays for with their own
     * key. The use's ceiling holds what it spent of the operator's; the month's cap is the built-in caps' (a spending plug-in sets each
     * use's ceiling itself, and holds the month itself).
     */
    String over(AccountStore.Use use, String payer) throws IOException {
        AccountStore.Account a = store.account(use.account());
        if (a != null && !a.active()) return AccountActs.notActiveSaid(store, a);   // suspended or being deleted: nothing more, on any key (stage 4)
        if ("own".equals(payer)) return null;
        long spent = store.useSpentBy(use.id(), "operator");
        if (use.ceilingMicros() >= 0 && spent >= use.ceilingMicros() + Math.max(use.ceilingMicros() / 10, MARGIN_MICROS))
            return Pages.t("This work has spent the most it may ({0}), so it stops here. Whoever runs the service can raise the cap.", Caps.dollars(use.ceilingMicros()));
        if (Uses.builtIn()) {
            long cap = Caps.of(store, use.account()).get(Caps.MONTH_DOLLARS);
            if (cap >= 0) {
                long month = 0;
                for (AccountStore.MonthTotal t : store.month(use.account(), AccountStore.month(System.currentTimeMillis()))) if (t.payer().equals("operator")) month += t.micros();
                if (month >= cap + Math.max(cap / 10, MARGIN_MICROS)) return CapsSpending.monthUsed(cap);
            }
        }
        return null;
    }

    /**
     * The model server the settings named that a call's address is under, matched by its origin (scheme, host in any case, port) and its
     * path, both normalized, on a whole part of the path; the longest wins; "" when none is (round F: a path written another way, "/./v1"
     * or the host in capitals, matched none and was counted at nothing).
     */
    String driveOf(URI up) {
        String o = origin(up), path = up.getRawPath() == null ? "" : up.getRawPath();
        String best = "", bestPath = null;
        for (String d : drives.values()) {
            URI du;
            try { du = URI.create(d).normalize(); } catch (IllegalArgumentException e) { continue; }
            if (!origin(du).equals(o)) continue;
            String dp = du.getRawPath() == null ? "" : du.getRawPath().replaceAll("/+$", "");
            if (!(path.equals(dp) || path.startsWith(dp + "/"))) continue;
            if (bestPath == null || dp.length() > bestPath.length()) { best = d; bestPath = dp; }
        }
        return best;
    }

    /**
     * Whether a call that costs names a model one of the service's entries names at this server ({@link Spend#entryOf}), or the embeddings or
     * reranking model the settings name for such a call.
     */
    static boolean entryNames(String drive, String model, String path) {
        if (drive == null || drive.isEmpty() || model == null || model.isBlank()) return false;
        if (Spend.entryOf(drive, model) != null) return true;
        boolean side = path.endsWith("/embeddings") || path.endsWith("/rerank");
        return side && (model.equals(Config.get("RESEARCHZOSHO_EMBED_MODEL", "embed")) || model.equals(ModelServer.EMBED_NAME) || model.equals(Config.get("RESEARCHZOSHO_RERANK_MODEL", "rerank")));
    }

    /** A slot in {@code lane} at the server, waited for; null when none came within {@link #WAIT_MS}. */
    private String slot(String origin, String lane, String account, int[] cap) throws IOException {
        String wait = AccountStore.id("wait_");
        store.laneWait(wait, origin, lane, account);
        long until = System.currentTimeMillis() + WAIT_MS;
        try {
            while (true) {
                String slot = store.laneTake(wait, origin, lane, account, cap[0], cap[1], cap[2], SLOT_MS);
                if (slot != null) return slot;
                if (System.currentTimeMillis() >= until) return null;
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            }
        } finally { store.laneWaitGone(wait); }
    }

    /**
     * A server's calls at once, the slots its waiting lane keeps, and an account's share of its background; null when the server has
     * no lanes. Asked again every ten minutes. See {@link #lanes}.
     */
    int[] capacity(String origin, URI up, String model) {
        String key = origin + "|" + (model == null ? "" : model);
        int[] c = capacity.get(key);
        if (c != null && c[3] > System.currentTimeMillis() / 60_000 - 10) return c[0] <= 0 ? null : c;
        String drive = drives.values().stream().filter(d -> origin(URI.create(d)).equals(origin)).findFirst().orElse(up.getScheme() + "://" + up.getRawAuthority());
        ModelEntries.Entry e = Spend.entryOf(drive, model == null ? "" : model);
        Integer calls = whole(e == null ? null : Config.get(ModelEntries.key(e.name(), "CALLS"))), kept = whole(e == null ? null : Config.get(ModelEntries.key(e.name(), "KEPT")));
        boolean hosted = DriveClient.hostedApi(drive) || OpenAI.is(drive) || Anthropic.is(drive);
        Integer said = calls != null || hosted ? null : ModelSlots.saidSlots(drive, model);
        boolean own = !hosted && calls == null && said == null && ownAddress(up.getHost());
        int guessed = own ? ModelSlots.probe(drive, model).slots() : 0;
        int[] l = lanes(calls, kept, hosted, said, own, guessed);
        c = new int[]{l == null ? 0 : l[0], l == null ? 0 : l[1], l == null ? 0 : l[2], (int) (System.currentTimeMillis() / 60_000)};
        capacity.put(key, c);
        return l == null ? null : c;
    }

    /**
     * The lanes of a model server, by the rule: the entry's {@code calls} when set; else none for a hosted API (an API on the internet,
     * whose provider takes the load); else the slots llama.cpp says it has (also through llama-swap); else, for a server of one's own
     * (this machine, a private network), what the server is guessed to answer, or {@link #CALLS_UNSET}; else none (an address on the
     * internet that says nothing). Of the slots, {@code kept} (1 unless set, 0 for none) are kept for a person who waits, never all of
     * them; an account's background holds at most three. {calls at once, kept, an account's share}, or null for no lanes.
     */
    static int[] lanes(Integer calls, Integer kept, boolean hosted, Integer said, boolean own, int guessed) {
        int slots;
        if (calls != null && calls > 0) slots = calls;
        else if (hosted) return null;
        else if (said != null && said > 0) slots = said;
        else if (own) slots = guessed > 0 ? guessed : CALLS_UNSET;
        else return null;
        int keep = Math.max(0, Math.min(kept == null ? KEPT_UNSET : kept, slots - 1));
        return new int[]{slots, keep, Math.min(ModelSlots.READERS_MAX, slots)};
    }

    private static Integer whole(String v) { return v != null && v.strip().matches("\\d+") ? Integer.valueOf(v.strip()) : null; }

    /** Whether a host is a server of one's own: every address it has is this machine's or a private network's. */
    static boolean ownAddress(String host) {
        if (host == null || host.isBlank()) return false;
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) { Addresses.Kind k = Addresses.kind(a); if (k == null || k == Addresses.Kind.UNUSABLE) return false; }
            return all.length > 0;
        } catch (IOException e) { return false; }
    }

    /**
     * What a call spent, from the server's answer, else from the sizes (marked so), at its entry's prices; written to the store. A reply
     * that did not reach its end ({@code whole} false) is counted from what passed: the usage it said so far, and its output at least
     * as much as the text that passed; marked estimated.
     */
    /** Tests: what runs just before a call is written down, to show that a caller with the whole answer finds it written. */
    static volatile Runnable COUNTING = () -> { };

    private void count(AccountStore.Use use, String payer, String drive, String model, byte[] request, byte[] reply, String type, boolean whole) {
        COUNTING.run();
        long[] t = usage(reply, type);
        long streamed = streamedTokens(reply, type);
        boolean estimated = t == null || !whole;
        if (t == null) t = new long[]{Math.max(1, request.length / BYTES_PER_TOKEN), 0, streamed >= 0 ? streamed : Math.max(whole ? 1 : 0, reply.length / BYTES_PER_TOKEN)};   // never none for a call that was made
        else if (!whole && streamed > t[2]) t[2] = streamed;
        ModelEntries.Entry e = Spend.entryOf(drive, model == null ? "" : model);
        long written = t.length > 3 ? t[3] : 0;
        long micros = e == null ? 0 : Math.round(e.cost(t[0], t[1], t[2], written) * 1_000_000);
        if ((e == null || e.prices() == null) && HOSTED.test(drive)) {
            // an API that charges, called for a model its entry gives no prices for: counted at an estimate, marked so, and said on the
            // operator's page and in the log once, not counted as nothing (BILL 9)
            micros = Math.round(t[0] * UNPRICED[0] + t[2] * UNPRICED[1]);   // dollars a million tokens: millionths of a dollar a token
            estimated = true;
            String origin = hostOf(drive), m = model == null ? "" : model;
            if (UNPRICED_SAID.add(origin + " " + m)) {
                LOG.warn("Calls to {} for the model {} have no prices in the model entries; they are counted at ${} and ${} a million tokens until the entry has its own.", origin, m.isEmpty() ? "(unnamed)" : m, UNPRICED[0], UNPRICED[1]);
                try { store.unpricedSeen(origin, m); } catch (IOException ex) { LOG.warn("The unpriced model could not be written down: {}", ex.getMessage()); }
            }
        }
        try {
            store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), use.account(), use.library(), use.id(), "model", payer, e == null ? (model == null ? "" : model) : e.name(),
                    t[0], t[1], t[2], 1, reply.length, micros, estimated)));
        } catch (IOException ex) { LOG.warn("What a call of the use {} spent could not be written: {}", use.id(), ex.getMessage()); }
    }

    /**
     * Tokens read, of them cached, and written, as a server's answer says them: OpenAI's chat completions and responses, Anthropic's
     * messages, embeddings; a streamed answer's events or a whole one. Null when it says nothing of them.
     */
    static long[] usage(byte[] reply, String type) {
        String s = new String(reply, StandardCharsets.UTF_8);
        long in = 0, cached = 0, out = 0, written = 0;
        boolean found = false;
        List<JsonNode> parts = new ArrayList<>();
        if (type.contains("event-stream") || s.startsWith("data:") || s.startsWith("event:")) {
            for (String line : s.split("\n")) {
                if (!line.startsWith("data:")) continue;
                String d = line.substring(5).strip();
                if (d.isEmpty() || d.equals("[DONE]")) continue;
                try { parts.add(M.readTree(d)); } catch (IOException ignored) { }
            }
        } else {
            try { parts.add(M.readTree(s)); } catch (IOException ignored) { return null; }
        }
        for (JsonNode p : parts) {
            JsonNode u = p.path("usage");
            if (p.path("type").asText("").equals("message_start")) u = p.path("message").path("usage");
            if (p.path("type").asText("").equals("response.completed")) u = p.path("response").path("usage");
            if (!u.isObject()) continue;
            // what the model wrote is billed as output, its thinking among it: OpenAI counts that in completion_tokens (and says it again in its
            // details), Gemini's OpenAI door leaves it out and has it in total_tokens (849 of 2,203 on the check of 2026-11-18); the larger of the
            // two counts it once either way
            if (u.has("prompt_tokens")) { in = u.path("prompt_tokens").asLong(); cached = u.path("prompt_tokens_details").path("cached_tokens").asLong(0); out = written(u.path("completion_tokens").asLong(0), u, in); found = true; }
            else if (u.has("input_tokens") && u.has("input_tokens_details")) { in = u.path("input_tokens").asLong(); cached = u.path("input_tokens_details").path("cached_tokens").asLong(0); out = written(u.path("output_tokens").asLong(0), u, in); found = true; }
            else if (u.has("input_tokens") || u.has("output_tokens")) {
                long read = u.path("cache_read_input_tokens").asLong(0), made = u.path("cache_creation_input_tokens").asLong(0);
                if (u.has("input_tokens")) { in = u.path("input_tokens").asLong() + read + made; cached = read; written = made; }
                if (u.has("output_tokens")) out = Math.max(out, u.path("output_tokens").asLong());
                found = true;
            }
        }
        return found && (in > 0 || out > 0) ? new long[]{in, cached, out, written} : null;   // written: of the read, written to the provider's cache (Anthropic)
    }

    /** The tokens written, its thinking among them: the count said, or what the total says beyond the tokens read when that is more. */
    private static long written(long said, JsonNode usage, long read) {
        return usage.path("total_tokens").canConvertToLong() ? Math.max(said, usage.path("total_tokens").asLong() - read) : said;
    }

    /**
     * The tokens of the text a streamed answer carried, worked out from the text alone (four bytes a token), not from the stream's
     * framing: OpenAI's chat and responses deltas, Anthropic's content deltas, with the words of tool calls and of thinking. -1 when
     * the answer is not a stream.
     */
    static long streamedTokens(byte[] reply, String type) {
        String s = new String(reply, StandardCharsets.UTF_8);
        if (!(type.contains("event-stream") || s.startsWith("data:") || s.startsWith("event:"))) return -1;
        long bytes = 0;
        for (String line : s.split("\n")) {
            if (!line.startsWith("data:")) continue;
            String d = line.substring(5).strip();
            if (d.isEmpty() || d.equals("[DONE]")) continue;
            JsonNode p;
            try { p = M.readTree(d); } catch (IOException e) { continue; }   // the last line, cut
            for (JsonNode ch : p.path("choices")) {
                JsonNode delta = ch.path("delta");
                bytes += text(delta.path("content")) + text(delta.path("reasoning_content")) + text(delta.path("reasoning"));
                for (JsonNode call : delta.path("tool_calls")) bytes += text(call.path("function").path("arguments")) + text(call.path("function").path("name"));
            }
            JsonNode delta = p.path("delta");   // Anthropic's content_block_delta; the responses API's output text delta is a string
            if (delta.isTextual()) bytes += text(delta);
            else bytes += text(delta.path("text")) + text(delta.path("partial_json")) + text(delta.path("thinking"));
        }
        return (bytes + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN;
    }

    private static long text(JsonNode n) { return n.isTextual() ? n.asText().getBytes(StandardCharsets.UTF_8).length : 0; }

    static String modelOf(byte[] body) {
        try { return M.readTree(body).path("model").asText(""); } catch (IOException | RuntimeException e) { return ""; }
    }

    private void place(HttpExchange x, String path, String library) throws IOException {
        String rest = path.substring(Gated.SEARCH.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash), tail = slash < 0 ? "" : rest.substring(slash);
        Place p = PLACES.stream().filter(pl -> pl.name().equals(name)).findFirst().orElse(null);
        if (p == null) { refuse(x, 403, "forbidden", "That is not a search place of this service."); return; }
        if (!madeByTheProgram(p, x.getRequestMethod(), tail)) {
            LOG.warn("A call to {} ({} {}) is not one this program makes; it is refused.", p.name(), x.getRequestMethod(), tail);
            refuse(x, 403, "forbidden", Pages.t("The service passes on only the calls this program makes to a search service, and this is not one of them."));
            return;
        }
        byte[] body = x.getRequestBody().readAllBytes();
        AccountStore.Use use = store.use(x.getRequestHeaders().getFirst(Gated.USE));
        if (use != null && (library == null || !use.library().equals(library))) use = null;   // another library's use is not this one's to spend
        if (use == null || !use.open()) { refuse(x, 403, "forbidden", Pages.t("A call that costs must be part of work the service let start, and this one is not.")); return; }
        String own = OwnKeys.keyFor(store, use.account(), p.name()), payer = own != null ? "own" : "operator";   // the person's own key for this place: their search
        String key = own != null ? own : Config.get(p.keySetting());
        if (key == null || key.isBlank()) { refuse(x, 403, "forbidden", "That is not a search place of this service."); return; }
        String kind = countedKind(p, tail);   // what this call is: a search, a page fetched, or nothing that costs (an account's usage, its status)
        String held = null;   // a search put aside against the month's count while it runs (round F)
        if ("search".equals(kind) && payer.equals("operator") && Uses.builtIn()) {
            long cap = Caps.of(store, use.account()).get(Caps.SEARCHES);
            if (cap >= 0) {
                held = store.reserveCount(use.account(), "search", cap, AccountStore.month(System.currentTimeMillis()));
                if (held.equals("!")) { refuse(x, 402, "budget_exceeded", CapsSpending.searchesUsed(cap)); return; }   // the work goes on without the search; only dollars stop it
            }
        }
        try { placeCall(x, p, use, payer, key, kind, tail, body); }
        finally { if (held != null) { try { store.unreserve(held); } catch (IOException e) { /* it runs out by itself */ } } }
    }

    /** The search place's call itself, counted when it has passed. */
    private void placeCall(HttpExchange x, Place p, AccountStore.Use use, String payer, String key, String kind, String tail, byte[] body) throws IOException {
        String over = over(use, payer);   // a search costs dollars as well: the use's ceiling and the month's hold for it as for the model
        if (over != null) { refuse(x, 402, "budget_exceeded", over, use.id()); return; }
        String query = x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery();
        if (p.keyGoes().startsWith("query:")) {
            String param = p.keyGoes().substring(6);
            query = query.replaceAll("(^|&)" + param + "=[^&]*", "");
            query = (query.isEmpty() ? "" : query + "&") + param + "=" + URLEncoder.encode(key.strip(), StandardCharsets.UTF_8);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASES.getOrDefault(p.name(), p.base()) + tail + (query.isEmpty() ? "" : "?" + query))).timeout(Duration.ofMinutes(2))
                .method(x.getRequestMethod(), body.length == 0 && !x.getRequestMethod().equals("POST") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        copyRequestHeaders(x, b);
        if (!p.keyGoes().startsWith("query:")) b.header(p.keyGoes(), p.keyGoes().equals("Authorization") ? "Bearer " + key.strip() : key.strip());
        HttpResponse<InputStream> r;
        try { r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        try { pass(x, r, kept); }
        finally {   // a search the caller left before its answer was made all the same: counted
            if (r.statusCode() >= 200 && r.statusCode() < 300 && kind != null) {
                byte[] reply = kept.toByteArray();
                COUNTING.run();
                try { store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), use.account(), use.library(), use.id(), kind, payer, p.name(), 0, 0, 0, 1, reply.length, callMicros(p.name(), tail, body, reply), false))); }
                catch (IOException ex) { LOG.warn("A search of the use {} could not be written: {}", use.id(), ex.getMessage()); }
            }
        }
    }

    /** What is not passed on: what belongs to one connection, what says who the caller is, and the gate's own marks. */
    private static final Set<String> NOT_PASSED = Set.of("host", "connection", "content-length", "expect", "upgrade", "keep-alive", "te", "trailer", "transfer-encoding", "proxy-authorization",
            "authorization", "x-api-key", "x-subscription-token", "x-rz-use", "x-rz-lane", "accept-encoding", "http2-settings");

    private static void copyRequestHeaders(HttpExchange x, HttpRequest.Builder b) {
        for (Map.Entry<String, List<String>> h : x.getRequestHeaders().entrySet()) {
            if (NOT_PASSED.contains(h.getKey().toLowerCase(Locale.ROOT))) continue;
            for (String v : h.getValue()) { try { b.header(h.getKey(), v); } catch (IllegalArgumentException restricted) { /* a header the client sets itself */ } }
        }
    }

    /** The server's answer passed to the caller as it comes; what it was, kept for counting (at most 16 MB of it). */
    private static byte[] pass(HttpExchange x, HttpResponse<InputStream> r) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        pass(x, r, kept);
        return kept.toByteArray();
    }

    /**
     * The same, kept in {@code kept} as it comes, so what passed is there to count when either end goes away before the answer's end (the
     * caller left, the server broke off): each piece is kept before it is passed on.
     */
    private static void pass(HttpExchange x, HttpResponse<InputStream> r, ByteArrayOutputStream kept) throws IOException {
        r.headers().map().forEach((k, vs) -> { String lk = k.toLowerCase(Locale.ROOT); if (!lk.equals("content-length") && !lk.equals("transfer-encoding") && !lk.equals("connection") && !lk.startsWith(":")) for (String v : vs) x.getResponseHeaders().add(k, v); });
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = r.body()) {
            x.sendResponseHeaders(r.statusCode(), 0);
            OutputStream out = x.getResponseBody();
            // an answer the server made whole before sending it (not a stream) is read on to its end when the caller leaves, for the usage it
            // ends with (round F, money 10); a stream is cut, so the server stops writing what nobody reads, and it is counted from what passed
            boolean streamed = r.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT).contains("event-stream");
            boolean caller = true;
            for (int n; (n = in.read(buf)) > 0; ) {
                if (kept.size() < 16 * 1024 * 1024) kept.write(buf, 0, n);
                if (!caller) continue;
                try { out.write(buf, 0, n); out.flush(); } catch (IOException left) { if (streamed) throw left; caller = false; }
            }
            // the answer's end is not written here: the exchange ends it once the call is counted, so a caller that has the whole answer finds
            // it counted (round G: Java 21's server sent the end at once, and its counting tests read the month before the count was written)
        }
    }

    /** A refusal, as a gate says one: its code, its sentence, and the mark that says it is the gate's ({@link Gated#MARK}). */
    private static void refuse(HttpExchange x, int status, String code, String said) throws IOException { refuse(x, status, code, said, null); }

    /** The same, naming the use a cap stops (402), so the library stops the work that use is and keeps it to resume. */
    private static void refuse(HttpExchange x, int status, String code, String said, String use) throws IOException {
        ObjectNode o = M.createObjectNode();
        o.put(Gated.MARK, true);
        if (use != null) o.put("use", use);
        o.putObject("error").put("code", code).put("message", said);
        byte[] b = M.writeValueAsBytes(o);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) { out.write(b); }
    }

    // ---- the heavy helpers ----

    /**
     * A library's call to a heavy helper ({@code /helper/<library>/<helper>/…}): passed to the helper the service reaches, with the
     * helpers' secret, which no library has. A call that does work (any but GET) is counted when it has ended: the helper's seconds,
     * against the use it carries, else the library's keeper's; speech-to-text also the seconds of speech its answer says it heard.
     */
    private void helper(HttpExchange x, String path, String library) throws IOException {
        String[] parts = path.substring(Gated.HELPER.length()).split("/", 3);
        if (parts.length < 2) { refuse(x, 404, "not_found", "Nothing is here."); return; }
        String libraryId = parts[0], name = parts[1], tail = parts.length > 2 ? "/" + parts[2] : "";
        // the library a call is charged to is the one whose gate it came through: never another named in the path (round F, money 9)
        if (!libraryId.equals(library)) { refuse(x, 403, "forbidden", "That is not a helper of this service."); return; }
        String to = helpers.get(name);
        AccountStore.Library lib = store.library(libraryId);
        if (to == null || lib == null) { refuse(x, 403, "forbidden", "That is not a helper of this service."); return; }
        byte[] body = x.getRequestBody().readAllBytes();
        String query = x.getRequestURI().getRawQuery();
        ModelsAwake.used(to);   // a container of this machine's: its use written down for its idle rule (round C2)
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(to + tail + (query == null ? "" : "?" + query))).timeout(Duration.ofMinutes(30))
                .method(x.getRequestMethod(), body.length == 0 && !x.getRequestMethod().equals("POST") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        copyRequestHeaders(x, b);
        String secret = Config.get(Helpers.SECRET_NAME, "").strip();
        if (!secret.isEmpty() && Helpers.ours(to)) b.header("Authorization", "Bearer " + secret);
        long t0 = System.nanoTime();
        HttpResponse<InputStream> r;
        try { r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        byte[] reply = pass(x, r);
        if (x.getRequestMethod().equals("GET") || r.statusCode() >= 300) return;   // a look at it, or work it did not do: nothing to count
        long seconds = Math.max(1, (System.nanoTime() - t0 + 999_999_999L) / 1_000_000_000L);
        AccountStore.Use use = store.use(x.getRequestHeaders().getFirst(Gated.USE));
        if (use != null && (!use.open() || !use.library().equals(libraryId))) use = null;
        String account = use != null ? use.account() : lib.owner(), payer = "operator", useId = use == null ? "" : use.id();   // the helpers are the operator's machine
        List<AccountStore.Usage> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        rows.add(new AccountStore.Usage(now, account, libraryId, useId, "helper", payer, name, 0, 0, 0, seconds, body.length + reply.length, 0, false));
        if (name.equals(Helpers.Helper.WHISPER.path) && tail.endsWith("/audio/transcriptions")) {
            double heard = heard(reply);
            rows.add(new AccountStore.Usage(now, account, libraryId, useId, "speech", payer, name, 0, 0, 0, heard >= 0 ? Math.max(1, Math.round(Math.ceil(heard))) : seconds, 0, 0, heard < 0));
        }
        COUNTING.run();
        try { store.addUsage(rows); } catch (IOException e) { LOG.warn("A helper's call of the library {} could not be written: {}", libraryId, e.getMessage()); }
    }

    /** The seconds of speech a transcription's answer says it heard (its duration, else its last segment's end); -1 when it says neither. */
    static double heard(byte[] reply) {
        try {
            JsonNode j = M.readTree(reply);
            if (j.path("duration").isNumber()) return j.path("duration").asDouble();
            JsonNode segs = j.path("segments");
            if (segs.isArray() && !segs.isEmpty() && segs.get(segs.size() - 1).path("end").isNumber()) return segs.get(segs.size() - 1).path("end").asDouble();
        } catch (IOException | RuntimeException notJson) { /* said nothing */ }
        return -1;
    }

    // ---- the web gate's counting ----

    /**
     * What the web gate of the library {@code libraryId} counts with (both forms): each connection to the web against the use it carries,
     * a fetch, refused when that account has fetched as many pages this month as it may, or when the store cannot be asked. A connection
     * that carries no use of this library's (the library's own: a check for a newer version, its help pages) is its keeper's, written
     * as background: counted with its bytes, and neither held to the fetches cap nor refused by it.
     */
    WebGate.Counting fetches(String libraryId) {
        return new WebGate.Counting() {
            @Override public String refused(String useId) {
                try {
                    AccountStore.Use use = whose(useId);
                    if (use == null || !Uses.builtIn()) return null;   // the library's own, or a spending plug-in that holds the month itself
                    String account = use.account();
                    long cap = Caps.of(store, account).get(Caps.FETCHES);
                    return cap >= 0 && CapsSpending.fetched(store, account) >= cap ? CapsSpending.fetchesUsed(cap) : null;
                } catch (IOException e) {
                    LOG.warn("The web gate of the library {} could not ask the store, so it refused a request: {}", libraryId, e.getMessage());
                    return ServiceUses.couldNotAsk();
                }
            }
            @Override public void counted(String useId, long bytes) { write(useId, 1, bytes); }
            @Override public void opened(String useId) { write(useId, 1, 0); }   // counted as it opens: a cap holds for connections made together
            @Override public void closed(String useId, long bytes) { if (bytes > 0) write(useId, 0, bytes); }   // and its bytes when it closes
            private void write(String useId, long quantity, long bytes) {
                try {
                    AccountStore.Use use = whose(useId);
                    String account = use != null ? use.account() : keeper();
                    if (account == null) return;
                    store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), account, libraryId, use == null ? "" : use.id(), use == null ? "background" : "fetch", "operator", "",
                            0, 0, 0, quantity, bytes, 0, false)));
                } catch (IOException e) { LOG.warn("A fetch of the library {} could not be written: {}", libraryId, e.getMessage()); }
            }
            private AccountStore.Use whose(String useId) throws IOException {
                AccountStore.Use use = useId == null || useId.isBlank() ? null : store.use(useId);
                return use != null && use.open() && use.library().equals(libraryId) ? use : null;   // another library's use is not this one's to spend
            }
            private String keeper() throws IOException { AccountStore.Library l = store.library(libraryId); return l == null ? null : l.owner(); }
        };
    }

    /** In a library under a service: the paid places reached at the node's search gate, each under its name there. */
    static void placesThrough(String gate) {
        if (gate == null || gate.isBlank()) return;
        String g = gate.strip().replaceAll("/+$", "");
        for (Map<String, String> m : List.of(WebSearchTool.GOOGLE, WebSearchTool.BRAVE_API, ExaSearchTool.BASE, TavilySearchTool.BASE, Firecrawl.BASE)) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                for (Place p : PLACES) if (e.getValue().startsWith(p.base())) { e.setValue(g + "/" + p.name() + e.getValue().substring(p.base().length())); break; }
            }
        }
    }

    @Override public void close() { server.stop(0); RUNNING.values().remove(this); }
}
