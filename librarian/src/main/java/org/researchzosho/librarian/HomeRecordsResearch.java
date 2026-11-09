package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a county publishes its property records, found the way the library answers any question: a research run on the configured
 * drive, bounded, its report filed on the shelves with its sources, asked once per county and kept a month. Every web address the
 * answer and its evidence name is read out deterministically and told apart by its shape, a dataset on a platform the library reads
 * (Socrata, ArcGIS, CKAN, Carto), a file (CSV, TSV, JSON, GeoJSON, a zip), or a page, which is a lookup page a person opens. The
 * records search then tries the datasets and the files as it tries any candidate, and a card names the lookup page when nothing else
 * answers. Nothing about any county is in the code.
 */
public final class HomeRecordsResearch {
    private HomeRecordsResearch() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** How long the run may take, in minutes; its turns; how long its answer serves; the most addresses taken from it. */
    public static final int MINUTES = Config.getInt("RESEARCHZOSHO_HOME_RECORDS_MINUTES", 8), TURNS = 12, DAYS = 30, MOST = 25;

    /** One address the research named: its kind by its shape (socrata, arcgis, ckan, carto, csv, tsv, json, geojson, zip, page, unknown), the site and the dataset's id where the kind has one. */
    public record Source(String url, String kind, String site, String id) {
        public boolean dataset() { return kind.equals("socrata") || kind.equals("arcgis") || kind.equals("ckan") || kind.equals("carto"); }
        public boolean file() { return kind.equals("csv") || kind.equals("tsv") || kind.equals("json") || kind.equals("geojson") || kind.equals("zip") || kind.equals("dbf"); }
        public boolean page() { return kind.equals("page"); }
    }

    /** What the research found for a county: the sources by kind, when, how long the run took, and a note when it could not run. */
    public record Researched(String county, String state, String city, List<Source> sources, String at, long tookSeconds, String note) {
        public List<Source> datasets() { List<Source> out = new ArrayList<>(); for (Source s : sources) if (s.dataset()) out.add(s); return out; }
        public List<Source> files() { List<Source> out = new ArrayList<>(); for (Source s : sources) if (s.file()) out.add(s); return out; }
        public List<Source> pages() { List<Source> out = new ArrayList<>(); for (Source s : sources) if (s.page()) out.add(s); return out; }
    }

    /** The research as the library runs it: the answer's text, with its evidence, for an ask; a test puts a stand-in here. */
    public interface Research { String answer(LibraryStore store, Researcher.Ask ask, String title, BooleanSupplier stop); }
    static volatile Research RESEARCH = HomeRecordsResearch::research;

    /** What a web address answers with, {content type, its first bytes}, or null when it does not answer; a test puts its own here. */
    static volatile Function<String, String[]> PROBE = HomeRecordsResearch::probe;

    static Path file(LibraryStore store, String county, String state) {
        String who = (county == null || county.isBlank() ? "no-county" : county) + "-" + state;
        return store.root().resolve("catalog").resolve("home-cache").resolve("research-" + who.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".json");
    }

    /** The county's sources: the month's answer when there is one (a week's when the run found nothing), else researched now. */
    public static Researched sources(LibraryStore store, String city, String state, String county, BooleanSupplier stop) { return sources(store, city, state, county, stop, new ArrayList<>()); }

    /** The same, with what the resolving looked at added to {@code looked}. */
    public static Researched sources(LibraryStore store, String city, String state, String county, BooleanSupplier stop, List<String> looked) {
        Path f = file(store, county, state);
        try {
            if (Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                LocalDate at = LocalDate.parse(j.path("at").asText("2000-01-01"));
                List<Source> kept = read(j.path("sources"));
                if (at.plusDays(kept.isEmpty() ? 7 : DAYS).isAfter(LocalDate.now())) {
                    if (!j.path("expanded").asBoolean(false)) {   // an answer kept before the shapes resolved: its pages asked again, its roots, catalogues and download pages opened, once
                        List<Source> again = new ArrayList<>();
                        for (Source s : kept) again.add(s.page() || s.kind().equals("unknown") ? classify(s.url()) : s);
                        kept = expand(again, looked);
                        write(f, county, state, city, at.toString(), kept, j.path("took_seconds").asLong(0), j.path("note").asText(""), j.path("answer_chars").asInt(0));
                    }
                    return new Researched(j.path("county").asText(""), j.path("state").asText(""), j.path("city").asText(""), kept, at.toString(), j.path("took_seconds").asLong(0), j.path("note").asText(""));
                }
            }
        } catch (IOException | RuntimeException stale) { /* ask again */ }
        String place = (county == null || county.isBlank() ? city : county) + ", " + state;
        Researcher.Ask ask = new Researcher.Ask(question(city, state, county), "broad", TURNS, subQuestions(city, state, county), "web", List.of(), MINUTES, List.of(), List.of());
        long t0 = System.currentTimeMillis();
        String answer, note = "";
        try { answer = RESEARCH.answer(store, ask, "Property records of " + place + ": where they are published", stop); }
        catch (RuntimeException e) { answer = ""; note = "the research could not run: " + e.getMessage(); }
        long took = (System.currentTimeMillis() - t0) / 1000;
        List<Source> sources = new ArrayList<>();
        for (String u : urls(answer == null ? "" : answer)) sources.add(classify(u));
        if (answer == null || answer.isBlank()) { if (note.isEmpty()) note = "the research gave no answer"; }
        sources = expand(sources, looked);
        Researched out = new Researched(county, state, city, sources, LocalDate.now().toString(), took, note);
        write(f, county, state, city, out.at(), sources, took, note, answer == null ? 0 : answer.length());
        Crews.log(store, "home", "Researched where " + place + " publishes its property records: " + sources.size() + " addresses named, " + out.datasets().size() + " datasets, " + out.files().size() + " files, " + out.pages().size() + " pages; " + took + " s" + (note.isEmpty() ? "" : "; " + note), took * 1000);
        return out;
    }

    static void write(Path f, String county, String state, String city, String at, List<Source> sources, long took, String note, int answerChars) {
        try {
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode().put("county", county).put("state", state).put("city", city).put("at", at).put("took_seconds", took).put("note", note).put("answer_chars", answerChars).put("expanded", true);
            ArrayNode a = o.putArray("sources");
            for (Source s : sources) a.addObject().put("url", s.url()).put("kind", s.kind()).put("site", s.site()).put("id", s.id());
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), o);
        } catch (IOException ignored) { }
    }

    static List<Source> read(JsonNode a) {
        List<Source> out = new ArrayList<>();
        for (JsonNode s : a) out.add(new Source(s.path("url").asText(""), s.path("kind").asText("unknown"), s.path("site").asText(""), s.path("id").asText("")));
        return out;
    }

    static String question(String city, String state, String county) {
        String place = county == null || county.isBlank() ? city + ", " + state : county + ", " + state + (city.isBlank() ? "" : ", and the city of " + city);
        return "Where does " + place + " publish its property records online: the assessor's values of each parcel, the property sales (deeds, transfers), and the building permits? "
                + "For each one: the dataset's name, the exact web address of its API or its download file (a CSV, a JSON or GeoJSON file, a zipped file, an ArcGIS layer, a Socrata or CKAN dataset), its format, "
                + "and whether it is only a lookup page where one address at a time is searched. Prefer the county's and the city's own open data portals and pages over third parties, and give each address in full.";
    }

    static List<String> subQuestions(String city, String state, String county) {
        String place = county == null || county.isBlank() ? city + ", " + state : county + ", " + state;
        return List.of("Where is the assessor's parcel data for " + place + " downloaded or queried, and in what format?",
                "Where are the property sales or deed transfers of " + place + " published as data, with the web address?",
                "Where does " + (city.isBlank() ? place : "the city of " + city + ", " + state) + " publish its building permits as data, with the web address?");
    }

    static final Pattern URL = Pattern.compile("https?://[^\\s<>()\\[\\]\"'`]+");

    /** Every web address in the text, once each, the trailing punctuation off, at most {@link #MOST}. */
    static List<String> urls(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(text == null ? "" : text);
        while (m.find()) {
            String u = m.group().replaceAll("[.,;:!?*]+$", "");
            while (u.endsWith(")") && count(u, '(') < count(u, ')')) u = u.substring(0, u.length() - 1);
            if (u.length() < 12) continue;
            out.add(u);
            if (out.size() >= MOST) break;
        }
        return new ArrayList<>(out);
    }

    static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    static final Pattern SOCRATA_ID = Pattern.compile("(?<![a-z0-9-])([a-z0-9]{4}-[a-z0-9]{4})(?![a-z0-9-])"), ARCGIS_LAYER = Pattern.compile("^(https?://[^?#]*?/(?:FeatureServer|MapServer)/\\d+)"),
            CKAN_RESOURCE = Pattern.compile("resource_id=([0-9a-f-]{36})"), CKAN_PAGE = Pattern.compile("^(https?://[^/]+)/dataset/([^/?#]+)/resource/([0-9a-f-]{36})"), CARTO = Pattern.compile("^https?://([^.]+)\\.carto\\.com/api/v\\d/sql");

    /** What an address is, by its shape first, a dataset on a platform the library reads or a file by its ending, and by what it answers with when the shape says nothing. */
    static Source classify(String url) { return classify(url, true); }

    /** With {@code probe} false the address is not asked what it answers with: its shape alone says, or it is unknown. */
    static Source classify(String url, boolean probe) {
        Matcher ph = PLACEHOLDER.matcher(url);
        if (ph.find()) url = url.substring(0, ph.start()).replaceFirst("[/?&=]+$", "");   // "…/views/{id}.json": the address up to the placeholder, a service root when that is what is left
        String host = host(url), lower = url.toLowerCase(Locale.ROOT), path = lower.replaceFirst("^https?://[^/]+", "").replaceFirst("[?#].*$", "");
        Matcher m;
        if ((m = ARCGIS_LAYER.matcher(url)).find()) return new Source(url, "arcgis", host, m.group(1));
        if ((m = ARCGIS_ROOT.matcher(url)).find()) return new Source(url, "arcgis-root", host, m.group(1));
        if ((m = HUB_ITEM_ID.matcher(url)).find() && (lower.contains("arcgis") || lower.contains("hub") || lower.contains("/api/download/"))) {
            Matcher lp = HUB_LAYER_PARAM.matcher(url);
            Source s = hubLayer(url, host, m.group(1) + "_" + (lp.find() ? lp.group(1) : "0"));
            if (s != null) return s;
        }
        if ((m = SOCRATA_VIEW.matcher(path)).find()) return new Source(url, "socrata", host, m.group(1));
        if (path.endsWith("/data.json") || lower.contains("dcat")) return new Source(url, "dcat", host, "");
        if ((m = CKAN_RESOURCE.matcher(url)).find()) return new Source(url, "ckan", host, m.group(1));
        if ((m = CKAN_PAGE.matcher(url)).find()) return new Source(url, "ckan", host, m.group(3));
        if (lower.contains("/resource/") && (m = SOCRATA_ID.matcher(path)).find()) return new Source(url, "socrata", host, m.group(1));
        if ((lower.contains("/d/") || lower.contains("/dataset/") || lower.contains("/datasets/")) && (m = SOCRATA_ID.matcher(path)).find() && !lower.contains("arcgis")) return new Source(url, "socrata", host, m.group(1));
        if (CARTO.matcher(url).find()) return new Source(url, "carto", host, "");
        if (path.endsWith(".csv") || path.endsWith(".csv.gz")) return new Source(url, "csv", host, "");
        if (path.endsWith(".tsv") || path.endsWith(".txt")) return new Source(url, "tsv", host, "");
        if (path.endsWith(".geojson")) return new Source(url, "geojson", host, "");
        if (path.endsWith(".json")) return new Source(url, "json", host, "");
        if (path.endsWith(".zip")) return new Source(url, "zip", host, "");
        if (path.endsWith(".dbf")) return new Source(url, "dbf", host, "");
        if (path.endsWith(".pdf") || path.endsWith(".xlsx") || path.endsWith(".xls")) return new Source(url, "page", host, "");   // a file the library does not read: for a person to open
        Source resolved = hubPage(url, host);   // a dataset's page on a Hub site, or on a CKAN portal: the dataset itself
        if (resolved == null) resolved = ckanPage(url, host);
        if (resolved != null) return resolved;
        if (!probe) return new Source(url, "unknown", host, "");
        String[] p;
        try { p = PROBE.apply(url); } catch (RuntimeException e) { p = null; }
        if (p == null) return new Source(url, "unknown", host, "");
        String type = p[0] == null ? "" : p[0].toLowerCase(Locale.ROOT), head = p[1] == null ? "" : p[1].strip();
        if (type.contains("text/csv") || type.contains("application/csv")) return new Source(url, "csv", host, "");
        if (type.contains("tab-separated")) return new Source(url, "tsv", host, "");
        if (type.contains("zip")) return new Source(url, "zip", host, "");
        if (type.contains("geo+json") || head.contains("\"FeatureCollection\"")) return new Source(url, "geojson", host, "");
        if (type.contains("json")) return new Source(url, head.startsWith("[") ? "json" : "page", host, "");
        if (type.contains("html") || head.toLowerCase(Locale.ROOT).startsWith("<!doctype") || head.toLowerCase(Locale.ROOT).startsWith("<html")) return new Source(url, "page", host, "");
        if (type.contains("text/plain") && head.contains(",") && head.contains("\n")) return new Source(url, "csv", host, "");
        return new Source(url, "unknown", host, "");
    }

    static final Pattern HUB_ITEM = Pattern.compile("\\b([0-9a-f]{32}_\\d+)\\b"), CKAN_DATASET = Pattern.compile("^(https?://[^/]+)/dataset/([^/?#]+)/?(?:[?#].*)?$");

    /**
     * A dataset's page on an ArcGIS Hub site ("…/datasets/parcels-1/about", "…/datasets/&lt;item id&gt;_0/explore") resolved to its layer: the item's id
     * from the address or from the page itself, then the Hub's own API for the layer's address. Null when it is no such page or does not resolve.
     */
    static Source hubPage(String url, String host) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("/datasets/")) return null;
        Matcher m = HUB_ITEM.matcher(lower);
        String id = m.find() ? m.group(1) : "";
        if (id.isEmpty()) {
            String page = HomeSensors.get(url);
            if (page == null) return null;
            Map<String, Integer> seen = new LinkedHashMap<>();
            Matcher p = HUB_ITEM.matcher(page);
            while (p.find()) seen.merge(p.group(1), 1, Integer::sum);
            for (Map.Entry<String, Integer> e : seen.entrySet()) if (id.isEmpty() || e.getValue() > seen.get(id)) id = e.getKey();
            if (id.isEmpty()) return null;
        }
        return hubLayer(url, host, id);
    }

    /** A dataset's page on a CKAN portal ("…/dataset/building-permits") resolved through the portal's API to its table, or to a file of it when the portal keeps none. */
    static Source ckanPage(String url, String host) {
        Matcher m = CKAN_DATASET.matcher(url);
        if (!m.find()) return null;
        String body = HomeSensors.get(m.group(1) + "/api/3/action/package_show?id=" + m.group(2));
        if (body == null) return null;
        try {
            JsonNode j = M.readTree(body);
            if (!j.path("success").asBoolean(false)) return null;
            Source file = null;
            for (JsonNode r : j.path("result").path("resources")) {
                if (r.path("datastore_active").asBoolean(false)) return new Source(url, "ckan", host, r.path("id").asText(""));
                String f = r.path("format").asText("").toLowerCase(Locale.ROOT), u = r.path("url").asText("");
                if (file == null && !u.isEmpty() && (f.equals("csv") || f.equals("geojson") || f.equals("json") || f.equals("zip") || f.equals("tsv"))) file = new Source(u, f, host(u), "");
            }
            return file;
        } catch (IOException e) { return null; }
    }

    // ---------------------------------------------------------------- the shapes an answer names, resolved to datasets

    static final Pattern ARCGIS_ROOT = Pattern.compile("^(https?://[^?#]*?/(?:FeatureServer|MapServer))/?(?:[?#].*)?$"),
            HUB_ITEM_ID = Pattern.compile("(?:/items/|item\\.html\\?id=)([0-9a-f]{32})\\b"), HUB_LAYER_PARAM = Pattern.compile("[?&]layers?=(\\d+)"),
            SOCRATA_VIEW = Pattern.compile("/api/(?:v3/)?views/([a-z0-9]{4}-[a-z0-9]{4})\\b"), PLACEHOLDER = Pattern.compile("\\{[^}]*\\}"),
            LAYER_WORDS = Pattern.compile("(?i)parcel|assess|apprais|sale|deed|transfer|permit|tax|property|situs|cama|roll|building"),
            HREF = Pattern.compile("(?i)(?:href|src)\\s*=\\s*[\"']([^\"'#]+)[\"']"), YEAR_DIR = Pattern.compile("(19|20)\\d\\d/?$");
    static final int MOST_PAGES = 8, MOST_LINKS = 25, MOST_LAYERS = 6, MOST_DCAT = 10;

    /** A Hub item's layer through the Hub's own API, by the item's id with its layer index: null when it does not resolve to a layer. */
    static Source hubLayer(String url, String host, String idWithLayer) {
        String body = HomeSensors.get("https://hub.arcgis.com/api/v3/datasets/" + idWithLayer);
        if (body == null) return null;
        try {
            JsonNode a = M.readTree(body).path("data").path("attributes");
            Matcher l = ARCGIS_LAYER.matcher(a.path("url").asText(""));
            return l.find() ? new Source(url, "arcgis", host, l.group(1)) : null;
        } catch (IOException e) { return null; }
    }

    /** The layers of an ArcGIS service root ("…/Parcels/MapServer"): those whose names speak of parcels, values, sales or permits, or all of a service with three or fewer. */
    static List<Source> layersOf(Source root) {
        List<Source> out = new ArrayList<>();
        String body = HomeSensors.get(root.id() + "?f=json");
        if (body == null) return out;
        try {
            JsonNode j = M.readTree(body);
            List<JsonNode> layers = new ArrayList<>();
            for (JsonNode l : j.path("layers")) layers.add(l);
            for (JsonNode l : j.path("tables")) layers.add(l);
            for (JsonNode l : layers) {
                String name = l.path("name").asText("");
                if (layers.size() > 3 && !LAYER_WORDS.matcher(name).find()) continue;
                out.add(new Source(root.url(), "arcgis", root.site(), root.id() + "/" + l.path("id").asInt(0)));
                if (out.size() >= MOST_LAYERS) break;
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** A portal's catalogue in the DCAT shape (data.json): its datasets of parcels, values, sales or permits, by title, each distribution told apart by its shape. */
    static List<Source> dcat(Source feed, List<String> looked) {
        List<Source> out = new ArrayList<>();
        String body = HomeSensors.get(feed.url());
        if (body == null) { looked.add("the catalogue at " + feed.url() + " did not answer"); return out; }
        int datasets = 0, taken = 0;
        try {
            for (JsonNode d : M.readTree(body).path("dataset")) {
                datasets++;
                String title = d.path("title").asText("");
                if (!(HomeRecords.nameFits(title, "sales") || HomeRecords.nameFits(title, "permits"))) continue;
                boolean any = false;
                for (JsonNode dist : d.path("distribution")) {
                    for (String k : List.of("accessURL", "downloadURL")) {
                        String u = dist.path(k).asText("");
                        if (u.isEmpty() || u.contains("/datasets/") && !HUB_ITEM.matcher(u).find()) continue;   // a dataset's page on the portal says nothing its API address does not
                        Source s = classify(u, false);
                        if (s.kind().equals("arcgis-root")) { for (Source l : layersOf(s)) { out.add(l); any = true; } }
                        else if (s.dataset() || s.file()) { out.add(s); any = true; }
                    }
                }
                if (any && ++taken >= MOST_DCAT) break;
            }
        } catch (IOException ignored) { }
        looked.add("the portal's catalogue at " + feed.url() + ": " + datasets + " datasets, " + taken + " of parcels, sales or permits, " + out.size() + " addresses to try");
        return out;
    }

    /** Whether a page's address says it is where data is downloaded from, rather than a search, a map or a department's home. */
    static boolean downloadish(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains("download") || l.contains("data") || l.contains("gis") || l.contains("open") || l.contains("export") || l.contains("file") || l.contains("csv") || l.contains("parcel")
                || l.contains("pdata") || l.contains("assessor") || l.contains("apprais") || l.contains("property") || l.contains("record");
    }

    /** The links of a download page, read once: the files and the endpoints a program reads, each told apart by its shape; a listing of year folders is followed into its newest. */
    static List<Source> linksOf(Source page, List<String> looked) {
        List<Source> out = new ArrayList<>();
        List<String> links = links(page.url());
        if (links == null) { looked.add("the page " + page.url() + " could not be read"); return out; }
        String newest = ""; int newestYear = 0;
        for (String l : links) { Matcher y = YEAR_DIR.matcher(l); if (y.find()) { int yr = Integer.parseInt(y.group().replace("/", "")); if (yr > newestYear && yr <= LocalDate.now().getYear() + 1) { newestYear = yr; newest = l.endsWith("/") ? l : l + "/"; } } }
        if (!newest.isEmpty()) { List<String> more = links(newest); if (more != null) links.addAll(more); }
        Set<String> seen = new LinkedHashSet<>();
        for (String l : links) {
            if (!seen.add(l) || !(fileLike(l) || endpointLike(l))) continue;
            Source s = classify(l, false);
            if (s.kind().equals("arcgis-root")) out.addAll(layersOf(s));
            else if (s.dataset() || s.file()) out.add(s);
            if (out.size() >= MOST_LINKS) break;
        }
        looked.add("the page " + page.url() + ": " + links.size() + " links, " + out.size() + " files or datasets to try" + (newest.isEmpty() ? "" : ", the newest folder " + newest + " read too"));
        return out;
    }

    static boolean fileLike(String l) { String p = l.toLowerCase(Locale.ROOT).replaceFirst("[?#].*$", ""); return p.endsWith(".csv") || p.endsWith(".tsv") || p.endsWith(".txt") || p.endsWith(".zip") || p.endsWith(".gz") || p.endsWith(".json") || p.endsWith(".geojson") || p.endsWith(".dbf"); }
    static boolean endpointLike(String l) { String p = l.toLowerCase(Locale.ROOT); return p.contains("/rest/services/") || p.contains("/resource/") || p.contains("/api/views/") || p.contains("/api/download/") || p.contains("datastore_search") || p.contains("/datasets/") || p.contains("/dataset/") || p.contains("data.json"); }

    /** A page's links as absolute addresses, from its href and src attributes and its bare addresses; null when the page could not be read. */
    static List<String> links(String url) {
        String body = HomeSensors.get(url);
        if (body == null) return null;
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(body);
        URI base;
        try { base = URI.create(url); } catch (RuntimeException e) { return out; }
        while (m.find()) {
            String h = m.group(1).strip();
            if (h.startsWith("javascript:") || h.startsWith("mailto:")) continue;
            try { String abs = base.resolve(h.replace(" ", "%20")).toString(); if (abs.startsWith("http")) out.add(abs); } catch (RuntimeException ignored) { }
            if (out.size() >= 400) break;
        }
        for (String u : urls(body)) if (!out.contains(u)) out.add(u);
        return out;
    }

    /** The sources with the ones that resolve to datasets added: a service root's layers, a catalogue's datasets, a download page's files and endpoints, at most eight pages read. */
    static List<Source> expand(List<Source> in, List<String> looked) {
        List<Source> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (Source s : in) add(out, seen, s);
        int pages = 0;
        for (Source s : in) {
            switch (s.kind()) {
                case "arcgis-root" -> { for (Source l : layersOf(s)) add(out, seen, l); }
                case "dcat" -> { for (Source d : dcat(s, looked)) add(out, seen, d); }
                case "page", "unknown" -> { if (pages < MOST_PAGES && downloadish(s.url())) { pages++; for (Source l : linksOf(s, looked)) add(out, seen, l); } }
                default -> { }
            }
        }
        return out;
    }

    static void add(List<Source> out, Set<String> seen, Source s) { if (seen.add(s.kind() + "|" + (s.id().isEmpty() ? s.url() : s.id()))) out.add(s); }

    static String host(String url) { try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (RuntimeException e) { return ""; } }

    /** The address asked with HEAD, and with GET for its first four kilobytes when HEAD is refused: {content type, first bytes}; null when it does not answer. */
    static String[] probe(String url) {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest head = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "researchzosho.org (research library)").method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> h = http.send(head, HttpResponse.BodyHandlers.discarding());
            String type = h.headers().firstValue("content-type").orElse("");
            if (h.statusCode() == 200 && !type.isEmpty() && !type.toLowerCase(Locale.ROOT).contains("json") && !type.toLowerCase(Locale.ROOT).contains("text/plain")) return new String[]{type, ""};
            HttpRequest get = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "researchzosho.org (research library)").header("Range", "bytes=0-4095").GET().build();
            HttpResponse<InputStream> g = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
            byte[] first;
            try (InputStream in = g.body()) { first = in.readNBytes(4096); }
            if (g.statusCode() != 200 && g.statusCode() != 206) return null;
            return new String[]{g.headers().firstValue("content-type").orElse(type), new String(first, StandardCharsets.UTF_8)};
        } catch (Exception e) { return null; }
    }

    /** The run as the library runs every research question: on the configured drive, both seats watched, the pages it fetches checked by its model, the report filed on the shelves; the answer with its evidence, so every address named is seen. */
    static String research(LibraryStore store, Researcher.Ask ask, String title, BooleanSupplier stop) {
        // the setting names a model entry for these runs (researchzosho models lists them); else the library's drive
        ModelEntries.Entry entry = null;
        String named = Config.get("RESEARCHZOSHO_HOME_RECORDS_MODEL", "");
        if (!named.isBlank()) { entry = ModelEntries.get(named); if (entry == null) throw new IllegalStateException("no model is named " + named + "; researchzosho models lists the names"); }
        String drive = entry != null ? entry.drive() : Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), model = entry != null ? entry.model() : Config.get("RESEARCHZOSHO_MODEL", "local-model");
        Researcher.Drive workers = Researcher.watched(drive, model, "workers", null), judge = Researcher.watchedJudge(drive, model, null);
        Researcher r = new Researcher(workers, judge, Researcher.webTools(), line -> Crews.log(store, "research", line, 0), store).onDrive(drive);
        r.contentJudge(ContentJudge.of(new DriveClient(drive, model)));
        if (entry != null) r.entry(entry);   // the run's model, for its report and its cost in the spend ledger
        r.title(title);
        r.stopWhen(stop);
        try {
            Researcher.Filed filed = Researcher.file(store, r, ask, "patron:home", "", "asked");
            if (filed.result() == null) return "";
            return filed.result().answer() + "\n" + (filed.result().evidence() == null ? "" : filed.result().evidence());
        } catch (IOException e) { throw new RuntimeException(e.getMessage(), e); }
    }
}
