package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.tools.Fetch;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the career search reads openings (docs/DESIGN_CAREER.md, step 3). A company's own hiring feed first: Greenhouse, Lever, Ashby,
 * Workable, Recruitee, Personio and Workday each publish their customers' openings for anyone to read. Else the job data a careers page
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
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** Forgets the robots rules and the request times read so far; for tests. */
    static void reset() { LAST.clear(); ROBOTS.clear(); ROBOTS_AT.clear(); }

    static String fetch(String url) {
        try {
            Fetch.Result r = Fetch.get(url, Duration.ofSeconds(20), Fetch.Policy.DEFAULT);
            return r.status() == 200 ? new String(r.body(), StandardCharsets.UTF_8) : null;
        } catch (Exception e) { return null; }
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

    /** A page read with the caller's headers, under the same rules and pace as {@link #get(String)}. */
    static String get(String url, Map<String, String> headers) {
        if (!allowed(url)) return null;
        pace(url);
        return RAW_HEADED.apply(url, headers);
    }

    static String post(String url, String json) {
        try {
            URI u = URI.create(url);
            if (Fetch.refusal(u) != null || Fetch.Policy.DEFAULT.leftOut(url) != null) return null;
            HttpRequest r = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).header("User-Agent", Fetch.USER_AGENT)
                    .header("Content-Type", "application/json").header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) { return null; }
    }

    /** The library's own web search, read back as titles and addresses. */
    static List<Hit> search(String query) {
        List<Hit> out = new ArrayList<>();
        try {
            String text = new WebSearchTool().execute(M.createObjectNode().put("query", query).put("limit", 10));
            String title = null;
            for (String line : (text == null ? "" : text).split("\n")) {
                if (line.matches("\\d+\\. .*")) title = line.replaceFirst("^\\d+\\. ", "").strip();
                else if (line.startsWith("   http") && title != null) { out.add(new Hit(title, line.strip().replaceFirst("\\s+\\[[^\\]]*\\]$", ""), "")); title = null; }
                else if (line.startsWith("   ") && title == null && !out.isEmpty()) { Hit last = out.get(out.size() - 1); out.set(out.size() - 1, new Hit(last.title(), last.url(), (last.snippet() + " " + line.strip()).strip())); }   // what the engine says of the page
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** A page or a feed under the site's robots rules and at the polite pace; null when the rules say no or the read fails. */
    static String get(String url) {
        if (!allowed(url)) return null;
        pace(url);
        return RAW.apply(url);
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
        Robots r = ROBOTS.get(h);
        long gap = Math.max(POLITE_MS, r == null ? 0 : Math.min(10_000, r.delayMs()));
        if (gap <= 0) return;
        synchronized (LAST) {
            long now = System.currentTimeMillis(), last = LAST.getOrDefault(h, 0L);
            long wait = last + gap - now;
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            LAST.put(h, System.currentTimeMillis());
        }
    }

    /**
     * Published APIs the library uses under their own terms, with a user agent that names it: the SEC's search and archive, Wikidata's
     * query service and API, the open mirror of YC's directory. Their robots files speak to page crawlers and say nothing of an API
     * call made at a polite pace; a board's data service whose robots file refuses every reader and that publishes no terms stays refused.
     */
    static final Set<String> API_HOSTS = Set.of("efts.sec.gov", "www.sec.gov", "query.wikidata.org", "www.wikidata.org", "yc-oss.github.io", "hn.algolia.com", "data.usajobs.gov");

    /** Whether the site's robots rules let the library read this address; a site with none, or none that answer, allows it. */
    static boolean allowed(String url) {
        String h = host(url);
        if (h.isEmpty()) return false;
        if (API_HOSTS.contains(h)) return true;
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
            Pattern.compile("(?i)(?:job-boards|boards)\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([a-z0-9_-]+)"),
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
            Pattern.compile("(?i)\\b([a-z0-9-]+\\.peopleadmin\\.(?:com|ca))")};
    static final String[] KINDS = {"greenhouse", "greenhouse", "lever", "ashby", "workable", "recruitee", "personio", "workday", "oracle", "icims", "taleo", "peopleadmin"};
    /** Words in a feed's address that are not a company's name in it. */
    static final Set<String> NOT_A_TOKEN = Set.of("embed", "j", "o", "api", "v1", "jobs", "job", "search", "apply", "oauth", "login", "static", "assets",
            "www", "app", "careers", "support", "blog", "help", "docs", "wday", "cxs", "en-us", "privacy", "legal");

    /** Every hiring feed an address or a page names, in the order they appear. */
    static List<Feed> boardsIn(String text, String how) {
        List<Feed> out = new ArrayList<>();
        if (text == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < BOARDS.length; i++) {
            Matcher m = BOARDS[i].matcher(text);
            while (m.find()) {
                String t = m.group(1).toLowerCase(Locale.ROOT);
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
                    if (NOT_A_TOKEN.contains(site)) continue;
                    String[] hostParts = p[0].split("\\.");
                    t = hostParts[0] + "/" + hostParts[1] + "/" + m.group(1).split("/")[m.group(1).split("/").length - 1];   // tenant/wdN/Site, the site's case kept
                } else if (NOT_A_TOKEN.contains(t) || NOT_A_TOKEN.contains(t.split("\\.")[0])) continue;
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
        return k.length() >= 4 && hk.contains(k);
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
        for (Opening o : f.openings()) for (String a : anchors) if (CareerCut.has(o.location(), a) || CareerCut.has(o.text(), a)) return true;
        return false;
    }

    /** The text of the last careers page a name search read, for the portals' kind check; "" when none. */
    private static final ThreadLocal<String> LAST_CAREERS_PAGE = ThreadLocal.withInitial(() -> "");
    static String lastCareersPage() { return LAST_CAREERS_PAGE.get(); }

    static Found openingsByName(String company, List<String> words) {
        LAST_CAREERS_PAGE.set("");
        String c = company == null ? "" : company.strip();
        if (c.isEmpty()) return new Found(c, null, List.of(), "it has no name");
        List<Hit> hits = new ArrayList<>();
        for (Hit h : SEARCH.apply(c + " careers jobs")) if (!aggregator(h.url())) hits.add(h);
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

    /** A company found hiring for a title wanted: its name, its feed, and the posting that showed it. */
    public record Discovered(String company, Feed feed, String posting, String url) { }

    /** The most searches one discovery makes: a few title words on five sites. */
    static final int DISCOVER_QUERIES = 15;

    /** The hiring systems whose job pages a web search can find by title. */
    static final List<String[]> SITES = List.of(new String[]{"jobs.ashbyhq.com", "Ashby"}, new String[]{"job-boards.greenhouse.io", "Greenhouse"}, new String[]{"boards.greenhouse.io", "Greenhouse"},
            new String[]{"jobs.lever.co", "Lever"}, new String[]{"apply.workable.com", "Workable"});

    /**
     * Companies hiring now for a title the person wants: the hiring systems' own job pages searched for each term, as it is given, with
     * the first place when there is one. Each posting found names its company's board, so the company comes with its feed. At most
     * {@code max} companies, from at most {@link #DISCOVER_QUERIES} searches.
     */
    public static List<Discovered> discover(List<String> terms, List<String> places, int max) { return discover(terms, places, max, title -> true, d -> true); }
    public static List<Discovered> discover(List<String> terms, List<String> places, int max, Predicate<String> titleOk) { return discover(terms, places, max, titleOk, d -> true); }

    /**
     * As above, keeping only postings whose own title {@code titleOk} lets through, the cut, so a company is found for a role that passes
     * it, and that {@code live} confirms: the company's own board, read now, still lists the posting, since a search engine keeps a job
     * page long after the board has taken it down (ten of sixteen on the first real run).
     */
    public static List<Discovered> discover(List<String> terms, List<String> places, int max, Predicate<String> titleOk, Predicate<Discovered> live) {
        List<Discovered> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> ps = places == null ? List.of() : places.subList(0, Math.min(3, places.size()));
        String place = ps.isEmpty() ? "" : ps.size() == 1 ? " \"" + ps.get(0) + "\"" : " (\"" + String.join("\" OR \"", ps) + "\")";
        int queries = 0;
        for (String w : terms) {
            for (String[] site : SITES) {
                if (out.size() >= max || queries++ >= DISCOVER_QUERIES) return out;
                List<String> must = new ArrayList<>();
                Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(w);
                while (q.find()) must.add(q.group(1));
                for (Hit h : SEARCH.apply(w + place + " site:" + site[0])) {
                    List<Feed> fs = boardsIn(h.url(), "");
                    if (fs.isEmpty() || !posting(h.url())) continue;   // a posting, not a board's front page
                    String job = jobTitle(h);
                    if (must.stream().anyMatch(m -> !CareerCut.has(job, m)) || !titleOk.test(job)) continue;   // the words searched for are in the job's own title, and it passes the cut
                    Feed f = fs.get(0);
                    if (!seen.add(f.label())) continue;
                    String name = boardName(f);
                    if (name.isEmpty()) name = nameFromHit(h, f);
                    String posting = job;
                    Discovered d = new Discovered(name, new Feed(f.kind(), f.token(), "found hiring: a search of " + site[1] + "'s job pages for " + String.join(" ", must)
                            + (ps.isEmpty() ? "" : " in " + String.join(" or ", ps)) + " gave " + clip(posting, 90)), posting, h.url());
                    if (!live.test(d)) continue;   // the board no longer lists it: the search engine's copy was stale
                    out.add(d);
                    if (out.size() >= max) return out;
                }
            }
        }
        return out;
    }

    /** Whether an address is one posting on a hiring system, past the board's front page. */
    static boolean posting(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.matches(".*jobs\\.ashbyhq\\.com/[^/]+/[^/?#]{6,}.*") || u.matches(".*greenhouse\\.io/[^/]+/jobs/\\d+.*") || u.matches(".*jobs\\.lever\\.co/[^/]+/[^/?#]{6,}.*")
                || u.matches(".*apply\\.workable\\.com/[^/]+/j/[^/?#]+.*") || u.matches(".*greenhouse\\.io/.*[?&]gh_jid=\\d+.*");
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
            return b == null ? "" : M.readTree(b).path("name").asText("").strip();
        } catch (Exception e) { return ""; }
    }

    /** Whether a company's own board, read now, lists the posting a search found; a board that does not answer counts as not listing it. */
    public static boolean stillListed(Discovered d, List<String> words) {
        Found f = read(d.feed(), d.company(), words);
        if (f == null) return false;
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
        String tok = f.token().split("[./]")[0].replace('-', ' ').replace('_', ' ');
        return tok.isEmpty() ? f.token() : Character.toUpperCase(tok.charAt(0)) + tok.substring(1);
    }

    // ---------------------------------------------------------------- reading a feed

    /** A feed's openings, or null when the feed does not answer. */
    static Found read(Feed f, String company, List<String> words) {
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
                case "lever" -> {
                    String b = get("https://api.lever.co/v0/postings/" + f.token() + "?mode=json");
                    if (b == null || !b.strip().startsWith("[")) return null;
                    for (JsonNode j : M.readTree(b)) {
                        String loc = j.path("categories").path("location").asText("").strip();
                        boolean rem = j.path("workplaceType").asText("").equalsIgnoreCase("remote") || remote(loc);
                        long at = j.path("createdAt").asLong(0);
                        String text = j.path("descriptionPlain").asText("") + "\n" + lists(j.path("lists")) + j.path("additionalPlain").asText("");
                        out.add(opening(company, j.path("text").asText(""), loc, rem, j.path("hostedUrl").asText(""), at > 0 ? Instant.ofEpochMilli(at).toString().substring(0, 10) : "", "",
                                f.label(), text, "", pay(j.path("salaryRange"))));
                    }
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
                    for (JsonNode j : got.values()) {
                        String path = j.path("externalPath").asText(""), loc = j.path("locationsText").asText("").strip();
                        String[] posted = workdayPosted(j.path("postedOn").asText(""), LocalDate.now());
                        out.add(opening(company, j.path("title").asText(""), loc.matches("\\d+ Locations") ? "" : loc, remote(loc), base + "/" + t[2] + path, posted[0], posted[1], f.label(), "", api + path, ""));
                    }
                    if (!terms.get(0).isEmpty()) note = "Workday lists its openings only by search, so the library read those matching the titles you want: " + String.join(", ", terms) + ".";
                    else if (total > got.size()) note = "Workday shows " + total + " openings; the library read the first " + got.size() + ".";
                }
                case "phenom", "oracle", "icims", "taleo", "peopleadmin" -> { return CareerPortals.read(f, company, words); }
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
        JsonNode base = n.path("baseSalary").path("value");
        String pay = base.isMissingNode() ? "" : joined(base.path("minValue").asText(""), base.path("maxValue").asText("")).replace(", ", " to ")
                + (base.path("unitText").asText("").isBlank() ? "" : " a " + base.path("unitText").asText("").toLowerCase(Locale.ROOT)) + (n.path("baseSalary").path("currency").asText("").isBlank() ? "" : " " + n.path("baseSalary").path("currency").asText(""));
        String org = n.path("hiringOrganization").path("name").asText("");
        out.add(opening(org.isBlank() || org.matches("\\d+ .*") ? company : org, plain(n.path("title").asText("")), loc, rem, n.path("url").asText(pageUrl), day(n.path("datePosted").asText("")), "",
                "jobposting:" + pageUrl, plain(n.path("description").asText("")), "", pay.strip()));
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

    static boolean remote(String location) { return location != null && location.toLowerCase(Locale.ROOT).matches("(?s).*\\b(remote|anywhere|distributed|work from home|wfh)\\b.*"); }
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
