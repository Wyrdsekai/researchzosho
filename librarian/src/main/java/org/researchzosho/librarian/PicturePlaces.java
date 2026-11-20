package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.Version;
import org.researchzosho.drive.Gated;
import org.researchzosho.tools.WebSearchTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The places a collection of pictures looks in (0.8.0), each a search a model chooses by name, as a research run's {@code search}
 * chooses its places: pictures from across the web through the library's engines, and four free collections that answer without a
 * key: Wikimedia Commons, the Library of Congress's photographs, Openverse, and wallhaven. Each says what it holds and what it costs,
 * nothing about which subjects it suits; the model learns that from what each search kept.
 *
 * <p>A place that keeps records (Commons, the Library of Congress) gives each picture's author, date and licence as the record has them:
 * a dated record is what "not made by AI" rests on. Every call is one request with a short limit; a place whose own limit is low has a
 * number of calls for one collection ({@link Place#most}), and leaves the collection's table when they are used.
 */
final class PicturePlaces {
    private PicturePlaces() { }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Who asks: the program and its address, never a person. */
    static final String UA = "ResearchZosho/" + Version.string() + " (a research library; https://researchzosho.org)";
    static final Duration QUICK = Duration.ofSeconds(25), SLOW = Duration.ofSeconds(60);
    /** How many a page of a place gives. */
    static final int PAGE = 20;

    /** What a record says of a picture: who made it, when, under what licence; {@code dated} when the record names the day or the year it was made; {@code aiSaid} when the record files it as made by AI. */
    record Record(String author, String date, String licence, boolean dated, String aiSaid) {
        static final Record NONE = new Record("", "", "", false, "");
        Record { author = author == null ? "" : author; date = date == null ? "" : date; licence = licence == null ? "" : licence; aiSaid = aiSaid == null ? "" : aiSaid; }
    }

    /** One candidate: the picture as found, and its record when the place keeps one. */
    record Candidate(Images.Found found, Record record) { }

    /** One place: its name for {@code where}, its line (what it holds · what it costs), the most calls one collection makes there (0: no limit of its own), and the search. */
    record Place(String name, String line, int most, Searcher searcher) { }

    @FunctionalInterface interface Searcher { List<Candidate> search(String query, int page, CollectionForm form) throws Exception; }

    /** The places of this library, in no order of preference. */
    static List<Place> live() {
        List<Place> out = new ArrayList<>();
        boolean brave = !Config.get("RESEARCHZOSHO_BRAVE_KEY", "").isBlank();
        out.add(new Place("web", "pictures from across the web through the library's search engines (SearXNG" + (brave ? ", and Brave with your key" : "") + "); any kind of picture, from any site · one call each"
                + (brave ? "; Brave's calls count against your key's month, at most 10 in one collection" : ""), 0, PicturePlaces::web));
        out.add(new Place("commons", "Wikimedia Commons: freely licensed photographs, scans and artworks, each with its author, date and licence as its record gives them; searches by size · one call each", 0, PicturePlaces::commons));
        out.add(new Place("loc", "the Library of Congress's photographs and prints, each a dated record; its copies are at most about 1,000 pixels on the long side · slow, about half a minute a call; at most 6 calls", 6, PicturePlaces::loc));
        out.add(new Place("openverse", "Openverse: openly licensed pictures from Flickr, museums and other collections, each with its creator and licence · at most 20 calls (its free limit)", 20, PicturePlaces::openverse));
        out.add(new Place("wallhaven", "wallhaven: wallpapers, artworks and photographs in large sizes, family-safe only; searches by size and shape · one call each", 0, PicturePlaces::wallhaven));
        out.add(new Place("shelves", "the pictures this library already keeps · instant", 0, null));
        return out;
    }

    // ---- the calls ----

    /** The places' own limits on how often they are asked: Openverse 20 a minute, wallhaven 45 a minute; a second between the rest. */
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    static volatile boolean PACED = true;
    private static void pace(String place, long gapMs) {
        if (!PACED) return;
        synchronized (LAST) {
            long now = System.currentTimeMillis(), last = LAST.getOrDefault(place, 0L), wait = last + gapMs - now;
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            LAST.put(place, System.currentTimeMillis());
        }
    }

    static String get(String url, Duration timeout, String what) throws IOException, InterruptedException {
        HttpResponse<String> r = Stopping.send(HTTP, Gated.request(url).timeout(timeout).header("User-Agent", UA).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), timeout, what);
        if (r.statusCode() == 429) throw new IOException(what + " asked the library to slow down (HTTP 429)");
        if (r.statusCode() != 200) throw new IOException(what + " answered HTTP " + r.statusCode());
        return r.body();
    }

    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    // ---- the web: SearXNG's pages, and Brave's first page with the person's key ----

    /** How many Brave calls the collection on this thread has left: at most 10 in one collection (the owner's word, 2026-11-19). */
    static final ThreadLocal<int[]> BRAVE_LEFT = ThreadLocal.withInitial(() -> new int[]{10});

    static List<Candidate> web(String query, int page, CollectionForm form) throws Exception {
        List<List<Images.Found>> ranked = new ArrayList<>();
        Exception last = null;
        int[] brave = BRAVE_LEFT.get();
        if (page <= 1 && brave[0] > 0) {
            try { List<Images.Found> b = Images.brave(query, 50); if (b != null) { ranked.add(b); brave[0]--; } } catch (Stopping.Requested stop) { throw stop; } catch (Exception e) { last = e; }
        }
        try {
            String body = get(Images.searxUrl(query) + "&pageno=" + Math.max(1, page), QUICK, "the search service at " + WebSearchTool.endpoint());
            ranked.add(Images.parseSearx(body));
        } catch (Stopping.Requested stop) { throw stop; } catch (Exception e) { if (ranked.isEmpty()) last = e; }
        if (ranked.isEmpty() && last != null) throw last;
        List<Candidate> out = new ArrayList<>();
        for (Images.Found f : Images.merge(ranked, 100)) out.add(new Candidate(f, Record.NONE));
        return out;
    }

    // ---- Wikimedia Commons ----

    static volatile String COMMONS = "https://commons.wikimedia.org";

    static String commonsUrl(String query, int page, CollectionForm form) {
        StringBuilder q = new StringBuilder(query.strip()).append(" filetype:bitmap");
        int w = form.minWidth(), h = form.minHeight();
        if (form.orientation().isEmpty() && w > 0 && h > 0) { int s = Math.min(w, h); w = s; h = s; }   // either way round: the short side on both
        if (w > 0) q.append(" filew:>").append(w - 1);
        if (h > 0) q.append(" fileh:>").append(h - 1);
        return COMMONS + "/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=" + PAGE + "&gsroffset=" + (Math.max(1, page) - 1) * PAGE
                + "&gsrsearch=" + enc(q.toString()) + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1024"
                + "&iiextmetadatafilter=Artist%7CDateTimeOriginal%7CLicenseShortName%7CImageDescription%7CCategories%7CObjectName";
    }

    static List<Candidate> commons(String query, int page, CollectionForm form) throws Exception {
        pace("commons", 1000);
        return parseCommons(get(commonsUrl(query, page, form), QUICK, "Wikimedia Commons"));
    }

    /** A record's AI category: Commons files a picture made by a generator under categories that say so. */
    static final Pattern AI_CATEGORY = Pattern.compile("(?i)\\b(ai[- ]generated|generated by (?:ai|artificial intelligence|dall|midjourney|stable diffusion)|artificial intelligence[- ]generated|images? (?:made|created) (?:with|by) (?:ai|dall|midjourney|stable diffusion))");

    static List<Candidate> parseCommons(String json) throws IOException {
        List<JsonNode> pages = new ArrayList<>();
        M.readTree(json).path("query").path("pages").forEach(pages::add);
        pages.sort(Comparator.comparingInt(p -> p.path("index").asInt(0)));
        List<Candidate> out = new ArrayList<>();
        for (JsonNode p : pages) {
            JsonNode ii = p.path("imageinfo").path(0);
            String url = ii.path("url").asText("");
            if (url.isBlank()) continue;
            JsonNode md = ii.path("extmetadata");
            String title = p.path("title").asText("").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z0-9]{2,5}$", "");
            String page = COMMONS + "/wiki/" + enc(p.path("title").asText("").replace(' ', '_')).replace("%3A", ":").replace("%2F", "/");
            String author = text(md.path("Artist").path("value").asText("")), date = text(md.path("DateTimeOriginal").path("value").asText("")).replaceAll("\\s*date QS:.*$", "").strip();   // the record's hidden machine form of the date is not said
            String licence = text(md.path("LicenseShortName").path("value").asText("")), cats = md.path("Categories").path("value").asText("");
            String caption = text(md.path("ImageDescription").path("value").asText(""));
            Matcher ai = AI_CATEGORY.matcher(cats + " " + caption);
            Images.Found f = new Images.Found(url, page, title, "Wikimedia Commons", ii.path("width").asInt(0), ii.path("height").asInt(0), ii.path("thumburl").asText(""), Instant.now().toString(), Images.cut(caption, 300));
            out.add(new Candidate(f, new Record(author, date, licence, year(date) > 0, ai.find() ? "its record files it under \"" + ai.group(1) + "\"" : "")));
        }
        return out;
    }

    // ---- the Library of Congress ----

    static volatile String LOC = "https://www.loc.gov";

    static List<Candidate> loc(String query, int page, CollectionForm form) throws Exception {
        pace("loc", 2000);
        String url = LOC + "/photos/?q=" + enc(query) + "&fo=json&c=" + PAGE + "&sp=" + Math.max(1, page) + "&at=results";
        return parseLoc(get(url, SLOW, "the Library of Congress"));
    }

    private static final Pattern FRAGMENT_SIZE = Pattern.compile("#h=(\\d+)&w=(\\d+)");

    static List<Candidate> parseLoc(String json) throws IOException {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode r : M.readTree(json).path("results")) {
            if (r.path("access_restricted").asBoolean(false)) continue;
            String best = "", small = ""; int bw = 0, bh = 0, sw = 0;
            for (JsonNode u : r.path("image_url")) {
                String s = u.asText("");
                Matcher m = FRAGMENT_SIZE.matcher(s);
                int h = m.find() ? Integer.parseInt(m.group(1)) : 0, w = m.groupCount() >= 2 && h > 0 ? Integer.parseInt(m.group(2)) : 0;
                if (!s.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)(#.*)?$")) continue;
                if (w > sw && w <= 700) { small = s.replaceAll("#.*$", ""); sw = w; }   // the largest copy under 700 wide: the small one
                if (w * h >= bw * bh) { best = s.replaceAll("#.*$", ""); bw = w; bh = h; }
            }
            if (best.isEmpty()) continue;
            if (best.startsWith("//")) best = "https:" + best;
            List<String> who = new ArrayList<>(); for (JsonNode c : r.path("contributor")) who.add(c.asText(""));
            String date = r.path("date").asText("");
            Images.Found f = new Images.Found(best, r.path("url").asText(""), r.path("title").asText(""), "the Library of Congress", bw, bh, small, Instant.now().toString(), Images.cut(r.path("description").path(0).asText(""), 300));
            out.add(new Candidate(f, new Record(String.join("; ", who), date, "", year(date) > 0, "")));
        }
        return out;
    }

    // ---- Openverse ----

    static volatile String OPENVERSE = "https://api.openverse.org";

    static List<Candidate> openverse(String query, int page, CollectionForm form) throws Exception {
        pace("openverse", 3100);
        String shape = switch (form.orientation()) { case CollectionForm.LANDSCAPE -> "&aspect_ratio=wide"; case CollectionForm.PORTRAIT -> "&aspect_ratio=tall"; case CollectionForm.SQUARE -> "&aspect_ratio=square"; default -> ""; };
        String size = Math.max(form.minWidth(), form.minHeight()) >= 1000 ? "&size=large" : "";
        String url = OPENVERSE + "/v1/images/?q=" + enc(query) + "&page_size=" + PAGE + "&page=" + Math.max(1, page) + "&mature=false" + shape + size;
        return parseOpenverse(get(url, QUICK, "Openverse"));
    }

    static List<Candidate> parseOpenverse(String json) throws IOException {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode r : M.readTree(json).path("results")) {
            String url = r.path("url").asText("");
            if (url.isBlank()) continue;
            String licence = r.path("license").asText("").toUpperCase(Locale.ROOT) + (r.path("license_version").asText("").isBlank() ? "" : " " + r.path("license_version").asText());
            Images.Found f = new Images.Found(url, r.path("foreign_landing_url").asText(""), r.path("title").asText(""), "Openverse (" + r.path("provider").asText("") + ")", r.path("width").asInt(0), r.path("height").asInt(0), r.path("thumbnail").asText(""), Instant.now().toString(), "");
            out.add(new Candidate(f, new Record(r.path("creator").asText(""), "", licence.strip(), false, "")));
        }
        return out;
    }

    // ---- wallhaven ----

    static volatile String WALLHAVEN = "https://wallhaven.cc";

    static List<Candidate> wallhaven(String query, int page, CollectionForm form) throws Exception {
        pace("wallhaven", 1400);
        StringBuilder u = new StringBuilder(WALLHAVEN + "/api/v1/search?q=" + enc(query) + "&purity=100&categories=111&sorting=relevance&page=" + Math.max(1, page));
        int w = form.minWidth(), h = form.minHeight();
        if (form.orientation().isEmpty() && w > 0 && h > 0) { int s = Math.min(w, h); w = s; h = s; }
        if (w > 0 || h > 0) u.append("&atleast=").append(Math.max(w, 1)).append('x').append(Math.max(h, 1));
        if (form.orientation().equals(CollectionForm.LANDSCAPE)) u.append("&ratios=landscape"); else if (form.orientation().equals(CollectionForm.PORTRAIT)) u.append("&ratios=portrait");
        return parseWallhaven(get(u.toString(), QUICK, "wallhaven"));
    }

    static List<Candidate> parseWallhaven(String json) throws IOException {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode r : M.readTree(json).path("data")) {
            String path = r.path("path").asText("");
            if (path.isBlank() || !"sfw".equals(r.path("purity").asText("sfw"))) continue;
            Images.Found f = new Images.Found(path, r.path("url").asText(""), "", "wallhaven", r.path("dimension_x").asInt(0), r.path("dimension_y").asInt(0), r.path("thumbs").path("large").asText(""), Instant.now().toString(), "");
            out.add(new Candidate(f, Record.NONE));
        }
        return out;
    }

    // ---- what a record's words say ----

    /** A record's HTML as plain words. */
    static String text(String html) { return html == null ? "" : html.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replaceAll("\\s+", " ").strip(); }

    /** The year a date's words name (1800 to 2099), 0 when none. */
    static int year(String date) {
        Matcher m = Pattern.compile("(?<!\\d)(1[89]\\d\\d|20\\d\\d)(?!\\d)").matcher(date == null ? "" : date);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** A caption naming who took the picture ("photo by", "photographer", "©", "credit") and a year before 2022: the other evidence "not made by AI" rests on (the owner's rule, 2026-11-19). */
    static String photographerAndYear(String text) {
        String t = text == null ? "" : text;
        Matcher by = Pattern.compile("(?:\\b(?i:photo(?:graph)?(?:ed)? by|photographer|taken by|credit)|©|\\((?i:c)\\))\\s*:?\\s*(\\p{Lu}[\\p{L}.'-]+(?:\\s+\\p{Lu}[\\p{L}.'-]+){0,3})").matcher(t);
        if (!by.find()) return "";
        int y = year(t);
        return y > 0 && y < 2022 ? "its caption names " + by.group(1) + " and the year " + y : "";
    }
}
