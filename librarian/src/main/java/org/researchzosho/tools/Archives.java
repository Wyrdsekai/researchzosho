package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web archives and the Internet Archive's collections, as clients: an archived copy of a page nearest a date (the Wayback Machine,
 * then archive.today), a page's snapshots over time, the pages an archive holds under a dead site (the Wayback Machine and Common
 * Crawl), the Internet Archive's catalogue and full-text search, Wikipedia's page history, and two heritage catalogues that need a key.
 * Every call is one request with a short limit, under the run's stop; nothing retries. Measured 2026-10-07: the Wayback Machine's
 * availability API answers 429 from a home address after a few calls, its timegate ({@code /web/<time>id_/<url>}) redirects to the
 * nearest copy at once, and its CDX index takes 6–10 s per query — so lookups are cached per process and capped per run.
 */
public final class Archives {
    private Archives() { }

    private static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    static final String UA = ScholarSearch.UA;
    static final Duration QUICK = Duration.ofSeconds(15), SLOW = Duration.ofSeconds(40);

    // the services' addresses; a test points them at its own server
    static volatile String WAYBACK = "https://web.archive.org", ARCHIVE_TODAY = "https://archive.ph", COMMON_CRAWL = "https://index.commoncrawl.org",
            IA = "https://archive.org", IA_FTS = "https://be-api.us.archive.org", WIKIPEDIA = "https://%s.wikipedia.org",
            EUROPEANA = "https://api.europeana.eu", DPLA = "https://api.dp.la";

    /** How many archived copies replaced a dead or walled page, and how many lookups were made, session-wide: the report says so. */
    public static final AtomicInteger COPIES_FOUND = new AtomicInteger(), LOOKUPS = new AtomicInteger();

    /** An archived copy: which archive, its 14-digit timestamp, the address of the copy (raw, without the archive's own toolbar), the page it is of. */
    public record Copy(String archive, String timestamp, String copyUrl, String original) {
        public String date() { return timestamp.length() >= 8 ? timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-" + timestamp.substring(6, 8) : timestamp; }
    }
    /** One snapshot of a page: when, the status the archive saw, the content digest (a new digest is a changed page), the size. */
    public record Snapshot(String timestamp, String status, String digest, String length) {
        public String date() { return new Copy("", timestamp, "", "").date(); }
        public String copyUrl(String original) { return WAYBACK + "/web/" + timestamp + "id_/" + original; }
    }
    /** A page an archive holds under a site: its address, the first time it was seen, which archive. */
    public record SitePage(String url, String timestamp, String archive, String copyUrl) { }
    /** An item in the Internet Archive's catalogue. */
    public record Item(String identifier, String title, String creator, String date, String description) {
        public String link() { return IA + "/details/" + identifier; }
        public String textLink() { return IA + "/stream/" + identifier + "/" + identifier + "_djvu.txt"; }
    }
    /** A full-text hit in the Internet Archive's texts: the item, the page, a line of the text around the words. */
    public record Hit(String identifier, String title, String creator, String date, int page, String snippet) {
        public String link() { return IA + "/details/" + identifier + (page > 0 ? "/page/n" + Math.max(0, page - 1) : ""); }
    }
    /** One revision of a Wikipedia article. */
    public record Revision(long revid, String timestamp, long size, String user, String comment) { }
    /** An item in a heritage catalogue (Europeana, DPLA). */
    public record Heritage(String title, String provider, String date, String url, String source) { }

    private static final Map<String, Copy> NEAREST = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Copy> e) { return size() > 500; }
    });
    private static final Pattern WAYBACK_LOC = Pattern.compile("/web/(\\d{14})(?:id_)?/(.*)$");
    private static final Pattern TODAY_LOC = Pattern.compile("/(\\d{14})/(.*)$");

    /**
     * The archived copy of {@code url} nearest {@code yyyymmdd} (blank: the latest), from the Wayback Machine, else archive.today; null
     * when neither holds one or neither answered. A miss is cached too, so a run does not ask twice.
     */
    public static Copy nearest(String url, String yyyymmdd) {
        String when = yyyymmdd == null || yyyymmdd.isBlank() ? "" : yyyymmdd.replaceAll("[^0-9]", "");
        String key = when + "|" + url;
        synchronized (NEAREST) { if (NEAREST.containsKey(key)) return NEAREST.get(key); }
        LOOKUPS.incrementAndGet();
        Copy c = wayback(url, when);
        if (c == null) c = archiveToday(url, when);
        synchronized (NEAREST) { NEAREST.put(key, c); }
        return c;
    }

    private static Copy wayback(String url, String when) {
        // the timegate: no copy at that exact moment, and the archive answers with the nearest one's address; "99999999" is the latest
        String ts = when.isEmpty() ? "99999999" : when;
        String target = WAYBACK + "/web/" + ts + "id_/" + url;
        try {
            HttpResponse<Void> r = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(target)).timeout(QUICK).header("User-Agent", UA).GET().build(),
                    HttpResponse.BodyHandlers.discarding(), QUICK, "the Wayback Machine");
            if (r.statusCode() == 200) return new Copy("Wayback Machine", ts.length() == 14 ? ts : "", target, url);
            if (r.statusCode() / 100 == 3) {
                String loc = r.headers().firstValue("location").orElse("");
                Matcher m = WAYBACK_LOC.matcher(loc);
                if (m.find()) return new Copy("Wayback Machine", m.group(1), WAYBACK + "/web/" + m.group(1) + "id_/" + m.group(2), url);
            }
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception ignored) { }
        return null;
    }

    private static Copy archiveToday(String url, String when) {
        String target = ARCHIVE_TODAY + "/timegate/" + url;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target)).timeout(QUICK).header("User-Agent", UA);
            if (!when.isEmpty() && when.length() >= 8) {
                ZonedDateTime at = LocalDate.parse(when.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC);
                b.header("Accept-Datetime", DateTimeFormatter.RFC_1123_DATE_TIME.format(at));
            }
            HttpResponse<Void> r = Stopping.send(HTTP, b.GET().build(), HttpResponse.BodyHandlers.discarding(), QUICK, "archive.today");
            if (r.statusCode() / 100 == 3) {
                String loc = r.headers().firstValue("location").orElse("");
                Matcher m = TODAY_LOC.matcher(loc);
                if (m.find() && !loc.contains("/timegate/")) return new Copy("archive.today", m.group(1), loc, url);
            }
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception ignored) { }
        return null;
    }

    /** A page's snapshots over time, oldest first, one per change of content; empty when the archive has none or did not answer. */
    public static List<Snapshot> history(String url, int limit) {
        List<Snapshot> out = new ArrayList<>();
        String body = text(WAYBACK + "/cdx/search/cdx?url=" + enc(url) + "&output=json&fl=timestamp,statuscode,digest,length&collapse=digest&limit=" + Math.max(1, limit), SLOW, "the Wayback Machine's index");
        if (body == null) return out;
        try {
            JsonNode rows = M.readTree(body);
            for (int i = 1; i < rows.size(); i++) { JsonNode r = rows.get(i); out.add(new Snapshot(r.get(0).asText(), r.get(1).asText(), r.get(2).asText(), r.get(3).asText())); }
        } catch (Exception ignored) { }
        return out;
    }

    /** The pages an archive holds under {@code prefix} (a site or a path), from the Wayback Machine and the latest Common Crawl, one row per address. */
    public static List<SitePage> site(String prefix, int limit) {
        Map<String, SitePage> out = new LinkedHashMap<>();
        String p = prefix.replaceFirst("^https?://", "").replaceAll("\\*+$", "");
        String body = text(WAYBACK + "/cdx/search/cdx?url=" + enc(p + "*") + "&output=json&fl=original,timestamp,statuscode&filter=statuscode:200&collapse=urlkey&limit=" + Math.max(1, limit), SLOW, "the Wayback Machine's index");
        if (body != null) try {
            JsonNode rows = M.readTree(body);
            for (int i = 1; i < rows.size() && out.size() < limit; i++) {
                String original = rows.get(i).get(0).asText(), ts = rows.get(i).get(1).asText();
                String plain = original.replace(":80/", "/");
                out.putIfAbsent(plain, new SitePage(plain, ts, "Wayback Machine", WAYBACK + "/web/" + ts + "id_/" + original));
            }
        } catch (Exception ignored) { }
        if (out.size() < limit) {
            String index = latestCrawl();
            if (index != null) {
                String cc = text(index + "?url=" + enc(p + "*") + "&output=json&filter==status:200&limit=" + Math.max(1, limit - out.size()), SLOW, "Common Crawl's index");
                if (cc != null) for (String line : cc.split("\n")) {
                    try {
                        JsonNode r = M.readTree(line);
                        String u = r.path("url").asText(""), ts = r.path("timestamp").asText("");
                        if (!u.isEmpty()) out.putIfAbsent(u, new SitePage(u, ts, "Common Crawl", u));
                    } catch (Exception ignored) { }
                    if (out.size() >= limit) break;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static volatile String crawlIndex;
    /** The latest Common Crawl index's CDX address, read once per process. */
    static String latestCrawl() {
        if (crawlIndex != null) return crawlIndex;
        String body = text(COMMON_CRAWL + "/collinfo.json", QUICK, "Common Crawl");
        if (body == null) return null;
        try { JsonNode list = M.readTree(body); if (list.size() > 0) crawlIndex = list.get(0).path("cdx-api").asText(null); } catch (Exception ignored) { }
        return crawlIndex;
    }

    /** The Internet Archive's catalogue: {@code kind} texts, tv (television news with its captions), video, or audio. */
    public static List<Item> search(String query, String kind, int limit) {
        String scope = switch (kind) { case "tv" -> "collection:tvarchive"; case "video" -> "mediatype:movies"; case "audio" -> "mediatype:audio"; default -> "mediatype:texts"; };
        String q = scope + " AND (" + query + ")";
        String body = text(IA + "/advancedsearch.php?q=" + enc(q) + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=date&fl[]=description&rows=" + Math.max(1, limit) + "&output=json", QUICK, "the Internet Archive");
        List<Item> out = new ArrayList<>();
        if (body == null) return out;
        try {
            for (JsonNode d : M.readTree(body).path("response").path("docs"))
                out.add(new Item(d.path("identifier").asText(""), first(d.get("title")), first(d.get("creator")), first(d.get("date")).replaceAll("T.*", ""), first(d.get("description"))));
        } catch (Exception ignored) { }
        return out;
    }

    /** Full-text search of the Internet Archive's texts: the items and pages where the words occur, with a line of the text. */
    public static List<Hit> fulltext(String query, int limit) {
        String body = text(IA_FTS + "/fts/v1/search?q=" + enc(query) + "&size=" + Math.max(1, limit), SLOW, "the Internet Archive's full-text search");
        List<Hit> out = new ArrayList<>();
        if (body == null) return out;
        try {
            for (JsonNode h : M.readTree(body).path("hits").path("hits")) {
                JsonNode f = h.path("fields");
                String snippet = "";
                for (JsonNode s : h.path("highlight").path("text")) { snippet = s.asText("").replace("{{{", "").replace("}}}", "").replaceAll("\\s+", " ").strip(); if (!snippet.isEmpty()) break; }
                out.add(new Hit(first(f.get("identifier")), first(f.get("meta_title")), first(f.get("meta_creator")), first(f.get("meta_publicdate")).replaceAll("T.*", ""), first(f.get("page_num")).isEmpty() ? 0 : (int) Double.parseDouble(first(f.get("page_num"))), snippet));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** A Wikipedia article's revisions, newest first from {@code at} (a date, or blank for now). */
    public static List<Revision> revisions(String lang, String title, String at, int limit) {
        String base = String.format(WIKIPEDIA, lang == null || lang.isBlank() ? "en" : lang);
        String url = base + "/w/api.php?action=query&prop=revisions&titles=" + enc(title) + "&rvlimit=" + Math.max(1, limit) + "&rvprop=" + enc("ids|timestamp|size|comment|user") + "&rvdir=older&format=json"
                + (at == null || at.isBlank() ? "" : "&rvstart=" + enc(at.length() == 4 ? at + "-12-31T23:59:59Z" : at.length() == 10 ? at + "T23:59:59Z" : at));
        String body = text(url, QUICK, "Wikipedia");
        List<Revision> out = new ArrayList<>();
        if (body == null) return out;
        try {
            for (JsonNode page : M.readTree(body).path("query").path("pages"))
                for (JsonNode r : page.path("revisions")) out.add(new Revision(r.path("revid").asLong(), r.path("timestamp").asText(""), r.path("size").asLong(), r.path("user").asText(""), r.path("comment").asText("")));
        } catch (Exception ignored) { }
        return out;
    }

    /** The address of an article as it was at a revision. */
    public static String revisionUrl(String lang, String title, long revid) {
        return String.format(WIKIPEDIA, lang == null || lang.isBlank() ? "en" : lang) + "/w/index.php?title=" + enc(title) + "&oldid=" + revid;
    }

    /** Europeana and DPLA, each only when its key is set (RESEARCHZOSHO_EUROPEANA_KEY, RESEARCHZOSHO_DPLA_KEY); the names of the ones without a key are returned in {@code noKey}. */
    public static List<Heritage> heritage(String query, int limit, List<String> noKey) {
        List<Heritage> out = new ArrayList<>();
        String ek = Config.get("RESEARCHZOSHO_EUROPEANA_KEY"), dk = Config.get("RESEARCHZOSHO_DPLA_KEY");
        if (ek == null || ek.isBlank()) noKey.add("Europeana (RESEARCHZOSHO_EUROPEANA_KEY)");
        else {
            String body = text(EUROPEANA + "/record/v2/search.json?wskey=" + enc(ek) + "&query=" + enc(query) + "&rows=" + Math.max(1, limit), QUICK, "Europeana");
            if (body != null) try {
                for (JsonNode it : M.readTree(body).path("items"))
                    out.add(new Heritage(first(it.get("title")), first(it.get("dataProvider")), first(it.get("year")), it.path("guid").asText(first(it.get("edmIsShownAt"))), "Europeana"));
            } catch (Exception ignored) { }
        }
        if (dk == null || dk.isBlank()) noKey.add("DPLA (RESEARCHZOSHO_DPLA_KEY)");
        else {
            String body = text(DPLA + "/v2/items?q=" + enc(query) + "&api_key=" + enc(dk) + "&page_size=" + Math.max(1, limit), QUICK, "DPLA");
            if (body != null) try {
                for (JsonNode d : M.readTree(body).path("docs")) {
                    JsonNode sr = d.path("sourceResource");
                    out.add(new Heritage(first(sr.get("title")), d.path("provider").path("name").asText(""), first(sr.path("date").get("displayDate")), d.path("isShownAt").asText(""), "DPLA"));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** The body of a GET that answered 200, else null; a stop while waiting is thrown as such. */
    static String text(String url, Duration limit, String what) {
        try {
            HttpResponse<String> r = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(url)).timeout(limit).header("User-Agent", UA).header("Accept", "application/json, */*").GET().build(),
                    HttpResponse.BodyHandlers.ofString(), limit, what);
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    static String first(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return "";
        if (n.isArray()) return n.size() == 0 ? "" : n.get(0).asText("");
        return n.asText("");
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
