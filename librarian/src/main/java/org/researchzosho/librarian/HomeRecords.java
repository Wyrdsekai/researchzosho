package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A city's own records of its homes, read by the platform the city publishes on rather than by the city: most cities and counties in
 * the United States put their assessor's rolls, their sales and their permits on one of three platforms, Socrata, ArcGIS or CKAN, each
 * with one API. For a home, the library first finds the city's, the county's and the state's own portals (their addresses follow a
 * few patterns, and a web search names the rest), then asks each portal for its datasets (the sales or the values with an address, the
 * permits), reads which columns hold what off the column names and off the first rows, checks each candidate by asking it for the home's
 * street, keeps what answered for a month, and then reads the one street. The figures are read as the records write them, commas and
 * all, with the kind of sale kept beside the price, since a quit claim is not a market sale.
 */
public final class HomeRecords {
    private HomeRecords() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** One dataset the library reads: where, which, and which of its columns hold what; a sales dataset without addresses names the one that joins them by a key. */
    public record Dataset(String platform, String site, String id, String name, Map<String, String> fields, String joinId, Map<String, String> joinFields, String latest) {
        public Dataset(String platform, String site, String id, String name, Map<String, String> fields, String joinId, Map<String, String> joinFields) { this(platform, site, id, name, fields, joinId, joinFields, ""); }
        public String field(String what) { return fields.getOrDefault(what, ""); }
        /** The year of the newest row, or of the catalogue's last change, or in the title; 0 when nothing said. */
        public int latestYear() { try { Matcher m = Pattern.compile("(19|20)\\d\\d").matcher(latest); int y = m.find() ? Integer.parseInt(m.group()) : 0; return y > LocalDate.now().getYear() + 1 ? 0 : y; } catch (NumberFormatException e) { return 0; } }   // a typo's year (2206) or a date set ahead is no newest row
        /** Whether the dataset is an assessor's values roll: a value and no sale price. A roll is yearly and is said by its year, never set below a current sales set. */
        public boolean roll() { return latest.startsWith("roll "); }
        /** Whether the records stop two or more years back: for sales and permits, where the newest row's date is the truth. */
        public boolean stale() { if (roll()) return false; int y = latestYear(); return y > 0 && y <= LocalDate.now().getYear() - 2; }
        public String reader() { return name + (roll() && !latest.endsWith("?") ? ", " + latest.replace("roll ", "roll of ") : !roll() && stale() ? ", last updated " + latest.substring(0, 4) : "") + " (" + platform + ", " + site + ")"; }
        public Dataset withLatest(String l) { return new Dataset(platform, site, id, name, fields, joinId, joinFields, l == null ? "" : l); }
    }

    /** What the library found for a city: the sales or values dataset, the permits dataset, what it looked at, when, and the address of a lookup page the county publishes its records through, when the research named one ("" when none). */
    public record Found(Dataset sales, Dataset permits, List<String> looked, String at, String lookup, String mapFile) {
        public Found(Dataset sales, Dataset permits, List<String> looked, String at) { this(sales, permits, looked, at, "", ""); }
        public Found(Dataset sales, Dataset permits, List<String> looked, String at, String lookup) { this(sales, permits, looked, at, lookup, ""); }
        public Found { lookup = lookup == null ? "" : lookup; mapFile = mapFile == null ? "" : mapFile; }
    }

    /** The portals a city publishes on: Socrata domains, ArcGIS Hub sites, CKAN sites. */
    record Portals(List<String> socrata, List<String> arcgis, List<String> ckan) { }

    /** A web search through the library's ladder, for a city's own portal; a test puts its own here. */
    static volatile Function<String, List<CareerSources.Hit>> SEARCH = q -> HomeSources.SEARCH.apply(q);

    static Path file(LibraryStore store, String city, String state) {
        return store.root().resolve("catalog").resolve("home-cache").resolve("records-" + (city + "-" + state).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".json");
    }

    /** The datasets for a city, found once and kept a month; a search is made again after a week when nothing was found. The platforms and the state's layer only: no research run. */
    public static Found find(LibraryStore store, String city, String state, String county, double lat, double lon, String street, int number) { return find(store, city, state, county, lat, lon, street, number, "", false); }

    /** The same, with the home's zip, which tells its town apart on a county-wide set with no city column. */
    public static Found find(LibraryStore store, String city, String state, String county, double lat, double lon, String street, int number, String zip) { return find(store, city, state, county, lat, lon, street, number, zip, false); }

    /**
     * The datasets for a city, found once and kept a month; a search is made again after a week when nothing was found. With {@code research},
     * a county the platforms and the state's layer give nothing for (no sales, or no permits) has the library research where it publishes
     * its records, once a month, on the library's model when one is configured: the datasets the answer names are tried as any candidate
     * is, and a lookup page it names is kept for the card to say.
     */
    public static Found find(LibraryStore store, String city, String state, String county, double lat, double lon, String street, int number, boolean research) { return find(store, city, state, county, lat, lon, street, number, "", research); }

    public static Found find(LibraryStore store, String city, String state, String county, double lat, double lon, String street, int number, String zip, boolean research) {
        ZIP.set(zip == null ? "" : zip);
        try { return findWith(store, city, state, county, lat, lon, street, number, research); } finally { ZIP.remove(); }
    }

    static Found findWith(LibraryStore store, String city, String state, String county, double lat, double lon, String street, int number, boolean research) {
        Path f = file(store, city, state);
        try {
            if (Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                LocalDate at = LocalDate.parse(j.path("at").asText("2000-01-01"));
                boolean found = j.has("sales") || j.has("permits");
                boolean researched = j.path("researched").asBoolean(false);
                if (at.plusDays(found ? 31 : 7).isAfter(LocalDate.now()) && (!research || researched || (j.has("sales") && j.has("permits")))) return new Found(dataset(j.path("sales")), dataset(j.path("permits")), list(j.path("looked")), at.toString(), j.path("lookup").asText(""), j.path("map_file").asText(""));
            }
        } catch (IOException | RuntimeException stale) { /* look again */ }
        List<String> looked = new ArrayList<>();
        Dataset sales = null, permits = null;
        String lookup = "", mapFile = "";
        boolean researched = false;
        HomeFiles.MAP_FILE.remove();
        try {
            Portals portals = portals(city, state, county, looked);
            Dataset stateLayer = stateLayer(state);
            List<Dataset> candidates = new ArrayList<>();
            if (stateLayer != null && !stateLayer.field("price").isEmpty()) candidates.add(stateLayer);   // a state's own parcels with the sales on them come first
            candidates.addAll(socrata(portals.socrata(), city, state, county, "sales", looked));
            candidates.addAll(arcgis(portals.arcgis(), city, state, county, lat, lon, "sales", looked));
            candidates.addAll(ckan(portals.ckan(), city, county, state, "sales", looked));
            if (stateLayer != null && !candidates.contains(stateLayer)) candidates.add(stateLayer);
            if (candidates.isEmpty() && portals.socrata().isEmpty()) candidates.addAll(socrataAnywhere(city, state, county, "sales", looked));
            if (stateLayer != null) looked.add("the state's own parcels: " + stateLayer.reader());
            candidates = freshFirst(candidates);
            for (Dataset d : candidates) {
                if (!tellsTheCity(d, city)) { looked.add("passed over " + d.reader() + ": " + (d.field("scope").equals("county") ? "county-wide" : "another place's") + " with no city column, for a home in " + city); continue; }
                if (!rowsOrAlt(d, street, number, city).isEmpty()) { sales = d; looked.add("kept " + d.reader() + ": it answered for " + street); break; }
                looked.add("tried " + d.reader() + ": nothing on " + street);
            }
            if (sales == null && !candidates.isEmpty()) looked.add("none of the " + candidates.size() + " answered for " + street);
            List<Dataset> p = new ArrayList<>();
            p.addAll(socrata(portals.socrata(), city, state, county, "permits", looked));
            p.addAll(arcgis(portals.arcgis(), city, state, county, lat, lon, "permits", looked));
            p.addAll(ckan(portals.ckan(), city, county, state, "permits", looked));
            if (p.isEmpty() && portals.socrata().isEmpty()) p.addAll(socrataAnywhere(city, state, county, "permits", looked));
            p = freshFirst(p);
            for (Dataset d : p) {
                if (!tellsTheCity(d, city)) { looked.add("passed over " + d.reader() + ": " + (d.field("scope").equals("county") ? "county-wide" : "another place's") + " with no city column, for a home in " + city); continue; }
                if (!rowsOrAlt(d, street, number, city).isEmpty()) { permits = d; looked.add("kept " + d.reader() + ": it answered for " + street); break; }
                looked.add("tried " + d.reader() + ": nothing on " + street);
            }
            if (permits == null && !p.isEmpty()) looked.add("none of the " + p.size() + " answered for " + street);
            if (research && (sales == null || permits == null) && Career.DRIVE.get() != null) {   // the library researches where the county publishes its records, once a month
                researched = true;
                HomeRecordsResearch.Researched rs = HomeRecordsResearch.sources(store, city, state, county, () -> false, looked);
                looked.add("the library's research on where " + (county.isEmpty() ? city : county) + " publishes its records" + (rs.tookSeconds() > 0 ? " (" + rs.tookSeconds() + " s)" : "") + ": " + rs.sources().size() + " addresses named, "
                        + rs.datasets().size() + " datasets, " + rs.files().size() + " files, " + rs.pages().size() + " pages" + (rs.note().isEmpty() ? "" : "; " + rs.note()));
                int notTheRecords = 0;
                for (HomeRecordsResearch.Source src : rs.sources()) {
                    if (src.page()) { if (lookup.isEmpty()) lookup = src.url(); continue; }
                    if (!src.dataset() && !src.file()) continue;
                    for (String what : List.of("sales", "permits")) {
                        if (what.equals("sales") ? sales != null : permits != null) continue;
                        int before = looked.size();
                        Dataset d = src.file() ? HomeFiles.dataset(store, src.url(), src.kind(), "", what, looked) : fromSource(src, what, looked);
                        if (d == null) { if (looked.size() == before) notTheRecords++; continue; }
                        if (rowsOrAlt(d, street, number, city).isEmpty()) { looked.add(d.reader() + ", from the research, did not answer for " + street); continue; }
                        looked.add("kept " + d.reader() + ", from the research: it answered for " + street);
                        if (what.equals("sales")) sales = d; else permits = d;
                    }
                }
                if (notTheRecords > 0) looked.add(notTheRecords + " of the addresses the research named hold no address and figures the reading needs, by their columns");
            }
        } catch (RuntimeException e) { looked.add("the search for datasets stopped: " + e.getMessage()); }
        if (HomeFiles.MAP_FILE.get() != null) { mapFile = HomeFiles.MAP_FILE.get(); HomeFiles.MAP_FILE.remove(); }
        Found out = new Found(sales, permits, looked, LocalDate.now().toString(), lookup, mapFile);
        try {
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode().put("city", city).put("state", state).put("county", county).put("at", out.at()).put("lookup", lookup).put("map_file", mapFile).put("researched", researched);
            if (sales != null) o.set("sales", json(sales));
            if (permits != null) o.set("permits", json(permits));
            ArrayNode a = o.putArray("looked"); for (String x : looked) a.add(x);
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), o);
        } catch (IOException ignored) { }
        return out;
    }

    /** A dataset from an address the research named, for sales or for permits: its name and columns asked of the platform, settled by its first rows as any candidate is; null when it is not such a dataset. */
    static Dataset fromSource(HomeRecordsResearch.Source src, String what, List<String> looked) {
        try {
            List<String> cols = new ArrayList<>();
            String name = src.id(), site = src.site(), id = src.id();
            switch (src.kind()) {
                case "socrata" -> {
                    String body = HomeSensors.get("https://" + site + "/api/views/" + id + ".json");
                    if (body == null) { looked.add("the research's dataset " + id + " on " + site + " did not answer"); return null; }
                    JsonNode v = M.readTree(body);
                    name = v.path("name").asText(id);
                    for (JsonNode c : v.path("columns")) cols.add(c.path("fieldName").asText(""));
                }
                case "arcgis" -> {
                    String body = HomeSensors.get(id + "?f=json");
                    try { if (body != null) name = M.readTree(body).path("name").asText(""); } catch (IOException ignored) { }
                    if (name.isEmpty()) name = id.replaceFirst("^https?://[^/]+/", "").replaceFirst("/(FeatureServer|MapServer)/\\d+$", "").replaceFirst("^.*/", "");   // the service's own name when the layer's description errs
                    cols.addAll(layerFields(id));
                    if (cols.isEmpty()) { looked.add("the research's dataset " + id + " did not answer"); return null; }
                }
                case "ckan" -> {
                    String body = HomeSensors.get("https://" + site + "/api/3/action/datastore_search?resource_id=" + enc(id) + "&limit=5");
                    if (body == null) return null;
                    JsonNode res = M.readTree(body).path("result");
                    for (JsonNode fld : res.path("fields")) cols.add(fld.path("id").asText(""));
                    List<Map<String, String>> rows = new ArrayList<>(); for (JsonNode row : res.path("records")) rows.add(flat(row));
                    Map<String, String> fs = columns(cols, what, rows.isEmpty() ? null : rows);
                    if (fs == null || (!fs.containsKey("address") && !fs.containsKey("number"))) return null;
                    if (what.equals("permits") && !buildingWork(rows, fs)) return null;
                    String title = id;
                    try { String rs = HomeSensors.get("https://" + site + "/api/3/action/resource_show?id=" + enc(id)); if (rs != null) { JsonNode rr = M.readTree(rs).path("result"); title = rr.path("name").asText(""); if (title.isEmpty()) title = rr.path("description").asText(id); } } catch (IOException ignored) { }
                    Dataset d = new Dataset("CKAN", site, id, title.isEmpty() ? id : title, fs, "", Map.of());
                    return d.withLatest(latest(d));
                }
                default -> { return null; }   // Carto: a table the research cannot name a shape for
            }
            if (cols.isEmpty() || !nameFits(name, what)) return null;
            Map<String, String> fs = columns(cols, what);
            if (fs == null) return null;
            Dataset d = new Dataset(src.kind().equals("socrata") ? "Socrata" : "ArcGIS", site, id, name, fs, "", Map.of());
            return settleByRows(d, cols, what, looked);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** Whose a dataset is, by the portal, the organisation or the name that gave it: "city" when the home's city is named, "county" when the county is, "state", else "other". */
    static String scopeOf(String text, String city, String county, String state) {
        String t = text.toLowerCase(Locale.ROOT), c = city.toLowerCase(Locale.ROOT), cs = slug(city), co = county.toLowerCase(Locale.ROOT).replaceAll("\\s+(county|parish|borough)$", "");
        String rest = co.isEmpty() ? t : t.replaceAll("\\b(county|parish|borough) of " + Pattern.quote(co) + "\\b", " ").replaceAll("\\b" + Pattern.quote(co) + " (county|parish|borough)\\b", " ");   // "County of Los Angeles" names the county, not the city of the same name; "City and County of Denver" names both
        boolean countyNamed = !rest.equals(t), cityNamed = !c.isEmpty() && (rest.contains(c) || (cs.length() > 3 && rest.replaceAll("[^a-z]", "").contains(cs)) || t.contains("city of " + c) || t.contains("city and county of " + c));
        if (cityNamed) return "city";
        if (countyNamed || (!co.isEmpty() && t.contains(co))) return "county";
        if (!state.isEmpty() && statePortal(text.strip().split("\\s+")[0], state)) return "state";
        return "other";
    }

    /** Whether a dataset of another place's or a county's can tell the home's city apart: it has a city or a zip column; the home's own city's needs none. */
    static boolean tellsTheCity(Dataset d, String city) {
        String scope = d.field("scope");
        if (scope.equals("city") || scope.isEmpty()) return true;
        if (!d.field("city").isEmpty() || (!d.field("zip").isEmpty() && !zip().isEmpty())) return true;
        return !d.joinFields().getOrDefault("city", "").isEmpty();
    }

    /** The candidates with the current ones first: a dataset whose rows stop two or more years back loses to any current one, and is kept only when it is alone. */
    static List<Dataset> freshFirst(List<Dataset> ds) {
        List<Dataset> fresh = new ArrayList<>(), stale = new ArrayList<>();
        for (Dataset d : ds) (d.stale() ? stale : fresh).add(d);
        fresh.addAll(stale);
        return fresh;
    }

    /** The state's own parcel layer, from the table the library carries of the states that publish one with addresses and values or sales (each proved live on the day the table says); null for the rest. */
    static Dataset stateLayer(String state) {
        try (var in = HomeRecords.class.getResourceAsStream("/org/researchzosho/librarian/records/state-parcels.json")) {
            if (in == null) return null;
            JsonNode j = M.readTree(in).path(state.toUpperCase(Locale.ROOT));
            if (j.isMissingNode() || j.path("url").asText("").isEmpty()) return null;
            Map<String, String> fs = new LinkedHashMap<>();
            j.path("fields").fields().forEachRemaining(e -> fs.put(e.getKey(), e.getValue().asText("")));
            String url = j.path("url").asText("");
            fs.put("scope", "state");
            return new Dataset("ArcGIS", url.replaceFirst("^https?://", "").replaceFirst("/.*$", ""), url, j.path("name").asText("the state's parcels"), fs, "", Map.of(), j.path("latest").asText(""));
        } catch (IOException | RuntimeException e) { return null; }
    }

    static ObjectNode json(Dataset d) {
        ObjectNode o = M.createObjectNode().put("platform", d.platform()).put("site", d.site()).put("id", d.id()).put("name", d.name()).put("join_id", d.joinId()).put("latest", d.latest());
        ObjectNode fs = o.putObject("fields"); d.fields().forEach(fs::put);
        ObjectNode js = o.putObject("join_fields"); if (d.joinFields() != null) d.joinFields().forEach(js::put);
        return o;
    }

    static Dataset dataset(JsonNode j) {
        if (j == null || j.isMissingNode() || j.isNull()) return null;
        Map<String, String> fs = new LinkedHashMap<>(), js = new LinkedHashMap<>();
        j.path("fields").fields().forEachRemaining(e -> fs.put(e.getKey(), e.getValue().asText("")));
        j.path("join_fields").fields().forEachRemaining(e -> js.put(e.getKey(), e.getValue().asText("")));
        return new Dataset(j.path("platform").asText(""), j.path("site").asText(""), j.path("id").asText(""), j.path("name").asText(""), fs, j.path("join_id").asText(""), js, j.path("latest").asText(""));
    }

    static List<String> list(JsonNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) out.add(x.asText("")); return out; }

    // ---------------------------------------------------------------- the portals of a place

    static final Map<String, String> STATE_NAMES = Map.ofEntries(Map.entry("AL", "alabama"), Map.entry("AK", "alaska"), Map.entry("AZ", "arizona"), Map.entry("AR", "arkansas"), Map.entry("CA", "california"),
            Map.entry("CO", "colorado"), Map.entry("CT", "ct"), Map.entry("DE", "delaware"), Map.entry("FL", "florida"), Map.entry("GA", "georgia"), Map.entry("HI", "hawaii"), Map.entry("ID", "idaho"), Map.entry("IL", "illinois"),
            Map.entry("IN", "indiana"), Map.entry("IA", "iowa"), Map.entry("KS", "kansas"), Map.entry("KY", "kentucky"), Map.entry("LA", "louisiana"), Map.entry("ME", "maine"), Map.entry("MD", "maryland"), Map.entry("MA", "mass"),
            Map.entry("MI", "michigan"), Map.entry("MN", "mn"), Map.entry("MS", "mississippi"), Map.entry("MO", "mo"), Map.entry("MT", "mt"), Map.entry("NE", "nebraska"), Map.entry("NV", "nv"), Map.entry("NH", "nh"), Map.entry("NJ", "nj"),
            Map.entry("NM", "newmexico"), Map.entry("NY", "ny"), Map.entry("NC", "nc"), Map.entry("ND", "nd"), Map.entry("OH", "ohio"), Map.entry("OK", "ok"), Map.entry("OR", "oregon"), Map.entry("PA", "pa"), Map.entry("RI", "ri"),
            Map.entry("SC", "sc"), Map.entry("SD", "sd"), Map.entry("TN", "tn"), Map.entry("TX", "texas"), Map.entry("UT", "utah"), Map.entry("VT", "vermont"), Map.entry("VA", "virginia"), Map.entry("WA", "wa"), Map.entry("WV", "wv"),
            Map.entry("WI", "wisconsin"), Map.entry("WY", "wyoming"), Map.entry("DC", "dc"));

    static String slug(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); }

    /** The addresses a city's, a county's or a state's Socrata portal is likely to have, by the patterns the platform's customers follow. */
    static List<String> socrataGuesses(String city, String state, String county) {
        List<String> out = new ArrayList<>();
        String c = slug(city), st = state.toLowerCase(Locale.ROOT), sn = STATE_NAMES.getOrDefault(state.toUpperCase(Locale.ROOT), st), co = slug(county.replaceAll("(?i)\\s+(county|parish|borough)$", ""));
        if (!c.isEmpty()) for (String p : List.of("data.%s.gov", "data.%sgov.org", "data.cityof%s.org", "data.cityof%s.gov", "data.%s" + sn + ".gov", "data.%s" + st + ".gov", "opendata.%s.gov", "data.%s.org")) out.add(String.format(p, c));
        if (!co.isEmpty() && !co.equals(c)) for (String p : List.of("data.%scounty.gov", "data.%scounty" + st + ".gov", "datacatalog.%scounty" + st + ".gov", "data.%s.gov", "data.%scounty.org")) out.add(String.format(p, co));
        out.add("data." + sn + ".gov"); if (!sn.equals(st)) out.add("data." + st + ".gov");
        return out;
    }

    /** Whether the portal is the state's rather than the place's own. */
    static boolean statePortal(String domain, String state) {
        String st = state.toLowerCase(Locale.ROOT), sn = STATE_NAMES.getOrDefault(state.toUpperCase(Locale.ROOT), st);
        return domain.equals("data." + sn + ".gov") || domain.equals("data." + st + ".gov") || domain.equals("geo." + st + ".gov");
    }

    /** Whether a Socrata portal answers at this address: its catalogue, asked for one dataset. */
    static boolean socrataPortal(String domain) {
        String body = HomeSensors.get("https://api.us.socrata.com/api/catalog/v1?search_context=" + enc(domain) + "&only=datasets&limit=1");
        if (body == null) return false;
        try { JsonNode j = M.readTree(body); return !j.has("error") && j.path("resultSetSize").asInt(0) > 0; } catch (IOException e) { return false; }
    }

    /** Whether an ArcGIS Hub site answers at this address: its own search, asked for one dataset. */
    static boolean arcgisSite(String host) {
        String body = HomeSensors.get("https://" + host + "/api/v3/datasets?q=parcels&page%5Bsize%5D=1");
        if (body == null) return false;
        try { return M.readTree(body).path("data").isArray(); } catch (IOException e) { return false; }
    }

    /** Whether a CKAN portal answers at this address. */
    static boolean ckanSite(String host) {
        String body = HomeSensors.get("https://" + host + "/api/3/action/package_search?q=property&rows=1");
        if (body == null) return false;
        try { return M.readTree(body).path("success").asBoolean(false); } catch (IOException e) { return false; }
    }

    /**
     * The portals of the place: the Socrata addresses the patterns give, checked; and the hosts a web search for the city's and the
     * county's open data names, each probed for the platform it runs. The place's own portals come first, the state's last.
     */
    static Portals portals(String city, String state, String county, List<String> looked) {
        List<String> socrata = new ArrayList<>(), arcgis = new ArrayList<>(), ckan = new ArrayList<>(), hosts = new ArrayList<>();
        List<String> guesses = socrataGuesses(city, state, county);
        for (String q : List.of(city + " " + state + " open data portal", (county.isEmpty() ? city : county) + " " + state + " GIS open data parcels")) {
            List<CareerSources.Hit> hits;
            try { hits = SEARCH.apply(q); } catch (RuntimeException e) { hits = List.of(); }
            if (hits == null) continue;
            for (CareerSources.Hit h : hits) {
                String host = CareerSources.host(h.url() == null ? "" : h.url());
                if (host.isEmpty() || hosts.contains(host) || guesses.contains(host) || any(host, "wikipedia", "data.gov", "github", "facebook", "linkedin", "youtube", "socrata.com", "zillow", "redfin", "realtor")) continue;
                if (!any(host, "data", "open", "gis", "hub.arcgis", ".gov", ".us")) continue;
                hosts.add(host);
                if (hosts.size() >= 6) break;
            }
        }
        int ckanTried = 0;
        for (String d : guesses) { if (socrataPortal(d)) socrata.add(d); else if (ckanTried++ < 4 && ckanSite(d)) ckan.add(d); }   // the same addresses may run CKAN (data.boston.gov, data.milwaukee.gov)
        for (String h : hosts) {
            if (h.endsWith("hub.arcgis.com") || h.endsWith("opendata.arcgis.com")) { if (arcgisSite(h)) arcgis.add(h); continue; }
            if (socrataPortal(h)) { socrata.add(h); continue; }
            if (arcgisSite(h)) { arcgis.add(h); continue; }
            if (ckanSite(h)) ckan.add(h);
        }
        looked.add("the place's portals: " + guesses.size() + " Socrata addresses tried by pattern and " + hosts.size() + " hosts from the web search; Socrata " + (socrata.isEmpty() ? "none" : String.join(", ", socrata)) + "; ArcGIS Hub " + (arcgis.isEmpty() ? "none" : String.join(", ", arcgis)) + "; CKAN " + (ckan.isEmpty() ? "none" : String.join(", ", ckan)));
        return new Portals(socrata, arcgis, ckan);
    }

    // ---------------------------------------------------------------- the columns, by their names and their first rows

    static String norm(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    static boolean any(String n, String... words) { for (String w : words) if (n.contains(w)) return true; return false; }
    /** A column of someone else's address: the owner's, the mailing one, a contact's, the buyer's or the seller's. */
    static boolean someoneElses(String n) { return any(n, "mail", "owner", "contact", "buyer", "seller", "grantor", "grantee", "applicant", "contractor", "taxpayer", "agent", "architect", "engineer") || n.startsWith("own") && !n.startsWith("owned") && !n.equals("own"); }   // "OwnCity", "OwnAddr": the owner's
    /** A part of an address, not the address: the state, the zip, the city, a direction, a suffix. */
    static boolean partOfOne(String n) { return any(n, "zip", "city", "state", "line2", "name", "number", "unit", "apt", "suite", "direction", "suffix", "prefix", "type", "code", "id", "lat", "lon", "geom", "point", "coord"); }

    /** How well a column's name says what a sale or a permit was: the fuller description first; 0 for a column that says nothing of it. */
    static int descRank(String n) {
        if (n.isEmpty()) return 99;
        if (any(n, "saledesc", "deedtype", "instrument", "transfertype", "salecode", "workdescription", "jobdescription")) return 1;
        if (any(n, "saletype", "doctype", "description", "worktype", "workclass")) return 2;
        if (any(n, "permittype", "permitdescription", "permitclass", "jobtype")) return 3;
        return 0;
    }

    /** How well a column's name says it holds the home's own size: the living or building area first, never the lot's or the basement's. */
    static int sqftRank(String n) {
        if (n.isEmpty()) return 99;
        if (any(n, "lot", "basement", "garage", "land", "parking", "deck", "porch")) return 0;
        if (any(n, "grosssquarefeet", "livingarea", "livingsqft", "finishedarea", "buildingarea", "bldgsqft", "grossarea", "floorarea", "totalsqft", "squarefootage")) return 1;
        if (any(n, "sqft", "squarefeet", "propertyarea", "bldgarea", "sizesqft")) return 2;
        if (n.endsWith("area")) return 3;
        return 0;
    }

    /**
     * Which columns hold what, by their names: the full address or a house number with a street name, the city, the price or the value
     * (several assessed parts, joined by "+", when the roll gives no total), the date, the kind of sale or the work, the size, the year
     * built, the parcel key. Null when the dataset has no address and no key, or, for sales, no price or value; for permits, no date or
     * description. The first rows, when given, settle which address-like column holds addresses.
     */
    static Map<String, String> columns(List<String> names, String what) { return columns(names, what, null); }

    static Map<String, String> columns(List<String> names, String what, List<Map<String, String>> firstRows) {
        Map<String, String> out = new LinkedHashMap<>();
        String number = "", streetName = "", city = "", zip = "", price = "", value = "", date = "", desc = "", sqft = "", year = "", key = "", prefix = "";
        int numberRank = 0;
        List<String> suffixParts = new ArrayList<>();
        List<String> addressLike = new ArrayList<>(), assessedParts = new ArrayList<>();
        if (firstRows != null && !firstRows.isEmpty()) {   // a column the catalogue lists but no row carries answers nothing when asked: Seattle's location1_city beside originalcity; the ones the rows carry go first, the rest stay for a column null in every sample row
            Set<String> present = new HashSet<>(); for (Map<String, String> r : firstRows) present.addAll(r.keySet());
            List<String> ordered = new ArrayList<>(); for (String name : names) if (present.contains(name)) ordered.add(name); for (String name : names) if (!present.contains(name)) ordered.add(name);
            names = ordered;
        }
        for (String name : names) {
            String n = norm(name);
            if (n.isEmpty() || n.startsWith("computedregion")) continue;
            if (!someoneElses(n)) {
                if (!partOfOne(n) && any(n, "addr", "address", "situs", "location", "street", "site") && !any(n, "streetname", "stname", "strname", "streetnum", "streetnumber", "streetdir", "streetsuf", "locationdesc", "sitearea", "siteid", "strpfx", "strsfx", "sfxdir", "predir", "postdir", "stpre", "stpos", "strnum") && !n.endsWith("pfx") && !n.endsWith("sfx")) addressLike.add(name);   // a split roll's parts are not the address
                int nr = any(n, "housenumber", "houseno", "housenum", "stnum", "strnum", "streetnumber", "addressnumber", "addrnum", "addressno", "addrno", "propertyhousenum", "housenbr", "housenr") ? 3 : any(n, "sitenum", "situsnum") || n.equals("anumber") || n.equals("stno") || n.equals("stnbr") || n.equals("sstrnbr") || n.equals("sitnumber") ? 2 : n.equals("house") || n.equals("number") ? 1 : 0;
                if (nr > 0 && nr > numberRank) { number = name; numberRank = nr; }   // "site_str_num" over "SiteNumber": the house number's own names first
                if (prefix.isEmpty() && (any(n, "strpfx", "streetprefix", "stpredir", "predir", "prefixdir", "stpretyp", "stpremod") || n.equals("pfx") || n.equals("prefix") || n.equals("sitestrpfx"))) prefix = name;   // the direction before the number, as a roll splits it
                if (any(n, "strsfx", "streetsuffix", "stsuffix", "sttype", "streettype", "stpostyp", "stpostdir", "stposdir", "postdir", "sfxdir", "strtype", "stpos") || n.equals("sfx") || n.equals("suffix") || n.equals("sttyp")) suffixParts.add(name);   // the suffix and the direction after, as a roll splits them
                if ((streetName.isEmpty() || (n.contains("full") && !norm(streetName).contains("full"))) && (any(n, "streetname", "stname", "strname", "sstrnm", "propertystreet", "sitestreet", "situsstreet", "sitstreet") || n.equals("street") || n.equals("stnm"))) streetName = name;   // the full street name, with its direction and suffix, before the bare one
                if (city.isEmpty() && any(n, "city", "town", "municipality") && !any(n, "citizen", "township", "code", "id", "zip")) city = name;   // a township code is no city
                if (zip.isEmpty() && any(n, "zip", "postal") && !any(n, "zip4", "plus4")) zip = name;
            }
            if (price.isEmpty() && (any(n, "saleprice", "saleamount", "salesprice", "consideration", "priceamount", "transferamount") || n.equals("price") || n.equals("amount") || n.equals("saleamt"))) price = name;
            if (value.isEmpty() && (any(n, "totalvalue", "totalvalu", "totval", "totalval", "assdval", "mktval", "apprval", "asmtval", "totasmt", "totassd", "assessedvalue", "assessedtotal", "totalassessed", "marketvalue", "appraisedvalue", "appraised", "avtotal", "assessedv", "totalappraised", "fullvalue") || n.equals("assessed") || n.equals("value")
                    || (any(n, "roll", "appr", "assess") && n.contains("total") && !any(n, "exempt", "personal", "fixture", "date", "code") && any(n, "land", "lnd") == any(n, "imp", "bldg", "building", "struct")))) value = name;   // a land total alone, or an improvements total alone, is a part, summed below when no total is given; one naming both is the total
            if (any(n, "assessed", "appraised", "appr", "roll") && any(n, "land", "lnd", "impr", "imp", "bldg", "building", "struct") && !any(n, "exempt", "personal", "fixture", "total", "date", "year", "code", "desc", "type", "pct", "percent", "ratio", "eff")) assessedParts.add(name);
            if (date.isEmpty() && (any(n, "saledate", "salesdate", "daterecorded", "recordeddate", "recordingdate", "docdate", "transferdate", "issuedate", "issueddate", "dateissued", "issuance", "permitdate") || n.equals("date"))) date = name;
            int dr = any(n, "filter", "flag", "indicator", "ind") || n.startsWith("is") ? 0 : descRank(n); if (dr > 0 && dr < descRank(norm(desc))) desc = name;   // "sale_filter_deed_type" is a yes or no, not the kind of deed
            int sr = sqftRank(n); if (sr > 0 && sr < sqftRank(norm(sqft))) sqft = name;
            if (year.isEmpty() && ((n.contains("year") && n.contains("built")) || any(n, "yrbuilt", "yearblt", "builtyear", "yrblt") || n.equals("built"))) year = name;
            if (key.isEmpty() && (n.equals("pin") || n.equals("pin10") || n.equals("apn") || n.equals("pid") || n.equals("parcelid") || n.equals("parcelnum") || n.equals("parcelnumber") || n.equals("parcel") || n.equals("parcelno") || n.equals("bbl") || n.equals("folio") || n.equals("ain"))) key = name;
        }
        String address = chooseAddress(addressLike, firstRows);
        if (value.isEmpty() && assessedParts.size() >= 2) value = String.join("+", assessedParts);   // the land and the improvement, no total given
        boolean addressed = !address.isEmpty() || (!number.isEmpty() && !streetName.isEmpty());
        if (what.equals("sales")) {
            if (price.isEmpty() && value.isEmpty()) return null;
            if (!addressed && key.isEmpty()) return null;
        } else if (what.equals("addresses")) {
            if (!addressed || key.isEmpty()) return null;
        } else if (!addressed || (date.isEmpty() && desc.isEmpty())) return null;
        if (!address.isEmpty()) out.put("address", address); if (!number.isEmpty()) out.put("number", number); if (!streetName.isEmpty()) out.put("street", streetName);
        if (address.isEmpty() && !streetName.isEmpty()) { if (!prefix.isEmpty()) out.put("prefix", prefix); if (!suffixParts.isEmpty()) out.put("suffix", String.join("+", suffixParts)); }
        if (!city.isEmpty()) out.put("city", city); if (!zip.isEmpty()) out.put("zip", zip); if (!price.isEmpty()) out.put("price", price); if (!value.isEmpty()) out.put("value", value);
        if (!date.isEmpty()) out.put("date", date); if (!desc.isEmpty()) out.put("desc", desc); if (!sqft.isEmpty()) out.put("sqft", sqft); if (!year.isEmpty()) out.put("year", year); if (!key.isEmpty()) out.put("key", key);
        return out;
    }

    static final Pattern ADDRESS_SHAPE = Pattern.compile("^\\s*\\d{1,6}[A-Za-z]?(?:-\\d{1,6})?\\s+\\S|\\b(ST|STREET|AVE|AVENUE|RD|ROAD|DR|DRIVE|BLVD|LN|LANE|WAY|CT|PL|HWY)\\b");

    /** Of the address-like columns, the one whose first rows hold addresses (a number and then words); with no rows given, the first by name. */
    static int addressRank(String a) { String n = norm(a); return any(n, "situs", "sitetype") ? 0 : any(n, "full", "prop", "site", "primary", "original", "permit", "service") ? 1 : any(n, "location", "street") ? 3 : 2; }

    static String chooseAddress(List<String> given, List<Map<String, String>> firstRows) {
        if (given.isEmpty()) return "";
        List<String> addressLike = new ArrayList<>(given);
        addressLike.sort((a, b) -> Integer.compare(addressRank(a), addressRank(b)));
        if (firstRows == null || firstRows.isEmpty()) return addressLike.get(0);
        int anySeen = 0;
        for (String a : addressLike) {
            int shaped = 0, seen = 0;
            for (Map<String, String> r : firstRows) { String v = r.getOrDefault(a, ""); if (v.isEmpty() || v.equals("null")) continue; seen++; if (ADDRESS_SHAPE.matcher(v.toUpperCase(Locale.ROOT)).find()) shaped++; }
            anySeen += seen;
            if (seen > 0 && shaped * 2 >= seen) return a;
        }
        return anySeen == 0 ? chooseAddress(addressLike, null) : "";   // a sample of parcels with no situs says nothing either way
    }

    /** Whether a dataset's name says it is the records sought, and not a summary, a tax sale or a city's own land. */
    static boolean nameFits(String name, String what) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (any(n, "summary", "median", "average", "statistic", "tax sale", "scavenger", "owned land", "owned property", "owned vacant", "owned parcel", "publicly owned", "foreclos", "lien", "archived", "modeling", "characteristics", "aggregate", "receivable", "legal description", "codes", "reference:", "viewer", "portal", "search",
                "festival", "counts", "completion", "form 700", "disclosure", "interest", "statement", "defaulted", "sewage", "hazard", "agricultur", "forest", "split parcel", "cluster", "map book", "mapbook", "field book", "district", "centroid", "zoning", "cannabis", "dins", "notice on title", "current use", "boundary", "block", "tract", "census", "fuel", "lightbox",
                "vacant", "debris", "fire", "flood", "public works", "street space", "rainwise", "rebuild", "aerial", "bcp ", "easement", "survey")) return false;
        if (what.equals("sales")) return !n.contains("permit") && !any(n, "t sale", "tax-sale", "treasurer", "sheriff", "auction", "analysis", "wsl", "story", "copy", "test", "demo", "sample", "lien") && any(n, "sale", "sales", "assess", "apprais", "parcel", "property", "real estate", "deed", "transfer", "valuation", "roll", "taxlot", "tax lot");
        return n.contains("permit") && !any(n, "tree", "sign", "event", "parking", "film", "street use", "right of way", "right-of-way", "row ", " row", "sidewalk", "vendor", "food", "noise", "special", "encroach", "excavat", "alarm", "pet", "animal", "liquor", "business", "fee", "comment", "inspection",
                "body art", "loading", "industrial", "storm", "swq", "lane", "closure", "traffic", "detour", "barricade", "obstruction", "occupancy", "mobile", "tattoo", "septic", "well", "water", "sewer", "dumpster", "banner", "block party", "valet", "taxi", "short-term", "rental", "garage sale", "burn", "fire", "hunting", "boat", "dock", "tobacco", "alcohol", "solicit", "peddl", "massage", "pool", "spa", "environmental", "air ", "wetland", "zoning",
                "related cases", "conditional", "match", "cases", "violation", "complaint", "(april)", "(may)", "swpermit", "sw permit", "expired", "cancel", "withdrawn", "void", "denied", "revoked", "closed", "control", "lines");
    }

    /** A permits dataset of buildings before one of some narrower kind; a sales dataset of sales before an assessment roll. */
    static int permitRank(String name) { String n = name == null ? "" : name.toLowerCase(Locale.ROOT); return any(n, "building", "construction", "dob") ? 2 : 0; }
    static int salesRank(String name) { String n = name == null ? "" : name.toLowerCase(Locale.ROOT); return any(n, "sale", "deed", "transfer") ? 2 : any(n, "assess", "roll", "parcel", "taxlot") ? 1 : 0; }

    // ---------------------------------------------------------------- Socrata

    /** The datasets of the place's own Socrata portals for sales or permits: each portal's catalogue asked with a few words, the candidates with the columns the reading needs, their first rows read to settle the address column. */
    static List<Dataset> socrata(List<String> domains, String city, String state, String county, String what, List<String> looked) {
        List<Dataset> out = new ArrayList<>();
        if (domains.isEmpty()) return out;
        List<String> words = what.equals("sales") ? List.of("assessor parcels", "property sales", "tax roll") : List.of("building permits", "permits issued");
        List<String[]> scored = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int asked = 0, raw = 0, passed = 0;
        for (String domain : domains) {
            for (String q : words) {
                String body = HomeSensors.get("https://api.us.socrata.com/api/catalog/v1?search_context=" + enc(domain) + "&q=" + enc(q) + "&only=datasets&limit=20");
                asked++;
                if (body == null) continue;
                try {
                    for (JsonNode r : M.readTree(body).path("results")) {
                        JsonNode res = r.path("resource");
                        String id = res.path("id").asText(""), name = res.path("name").asText(""), site = r.path("metadata").path("domain").asText(domain);
                        if (id.isEmpty() || seen.containsKey(site + "|" + id)) continue;
                        seen.put(site + "|" + id, true); raw++;
                        if (!nameFits(name, what)) continue;
                        List<String> cols = new ArrayList<>(); for (JsonNode c : res.path("columns_field_name")) cols.add(c.asText(""));
                        if (cols.isEmpty()) { passed++; looked.add("passed over " + name + " (" + site + "): no columns, a link or a map rather than a table"); continue; }
                        Map<String, String> fs = columns(cols, what);
                        if (fs == null) continue;
                        boolean inactive = false; for (JsonNode t : r.path("classification").path("domain_tags")) if (t.asText("").equalsIgnoreCase("inactive")) inactive = true;
                        if (inactive) continue;
                        String text = (name + " " + res.path("description").asText("") + " " + res.path("attribution").asText("")).toLowerCase(Locale.ROOT);
                        boolean names = text.contains(city.toLowerCase(Locale.ROOT)) || (!county.isEmpty() && text.contains(county.toLowerCase(Locale.ROOT).replace(" county", "")));
                        if (statePortal(domain, state) && !names) continue;   // a state's portal holds every county's: only what names the place
                        fs.put("scope", scopeOf(domain + " " + name, city, county, state));
                        int score = (domains.indexOf(domain) == 0 ? 3 : Math.max(0, 2 - domains.indexOf(domain)));   // the place's own portal before the state's
                        if (names) score += 2;
                        if (fs.containsKey("address") || fs.containsKey("number")) score += 2;
                        if (fs.containsKey("date")) score += 1;
                        score += what.equals("permits") ? permitRank(name) : salesRank(name);
                        ObjectNode d = M.createObjectNode().put("platform", "Socrata").put("site", site).put("id", id).put("name", name).put("latest", day(res.path("updatedAt").asText(res.path("data_updated_at").asText("")))); ObjectNode f = d.putObject("fields"); fs.forEach(f::put);
                        ArrayNode cs = d.putArray("cols"); for (String c : cols) cs.add(c);
                        scored.add(new String[]{String.valueOf(score), d.toString()});
                    }
                } catch (IOException ignored) { }
            }
        }
        scored.sort((a, b) -> Integer.compare(Integer.parseInt(b[0]), Integer.parseInt(a[0])));
        for (String[] s : scored) {
            try {
                JsonNode j = M.readTree(s[1]);
                Dataset d = dataset(j);
                List<String> cols = list(j.path("cols"));
                d = settleByRows(d, cols, what, looked);
                if (d == null) continue;
                if (d.field("address").isEmpty() && d.field("number").isEmpty()) {   // sales by a parcel key alone: the addresses dataset of the same portal joins them
                    Dataset with = socrataAddresses(d);
                    if (with == null) { looked.add("passed over " + d.reader() + ": it has no addresses, and no dataset of the portal joins them"); continue; }
                    d = with;
                }
                out.add(d);
                if (out.size() >= 4) break;
            } catch (IOException ignored) { }
        }
        looked.add("Socrata, " + domains.size() + (domains.size() == 1 ? " portal" : " portals") + ", " + asked + " queries for " + what + ": " + raw + " datasets seen, " + out.size() + (out.size() == 1 ? " candidate" : " candidates") + (out.isEmpty() ? "" : ", " + out.stream().map(Dataset::reader).toList()));
        return out;
    }

    /** The first five rows of a Socrata or an ArcGIS dataset, to settle which column holds the address; the dataset dropped, and said, when none does. */
    static Dataset settleByRows(Dataset d, List<String> cols, String what, List<String> looked) {
        List<Map<String, String>> rows = firstRows(d);
        if (rows == null) return d;   // the service did not answer the sample: the street check decides
        Map<String, String> fs = columns(cols, what, rows);
        if (fs == null && Career.DRIVE.get() != null) fs = columnsByModel(d, cols, rows, what);
        if (fs == null) { looked.add("passed over " + d.reader() + ": its first rows show no column of addresses"); return null; }
        if (d.field("value").isEmpty() && d.field("price").isEmpty() && !fs.containsKey("price") && !fs.containsKey("value") && what.equals("sales")) return null;
        if (what.equals("permits") && !buildingWork(rows, fs)) { looked.add("passed over " + d.reader() + ": its first rows show no building work"); return null; }
        if (!d.field("scope").isEmpty()) fs.put("scope", d.field("scope"));
        Dataset out = new Dataset(d.platform(), d.site(), d.id(), d.name(), fs, d.joinId(), d.joinFields(), d.latest());
        if (what.equals("sales") && out.field("price").isEmpty() && !out.field("value").isEmpty()) { String y = rollYear(cols, rows, out); return out.withLatest("roll " + (y.isEmpty() ? "?" : y)); }   // a values roll: its own year, else the portal's
        return out.withLatest(latest(out));
    }

    static final Pattern BUILDING_WORK = Pattern.compile("(?i)\\b(build|bldg|construct|remodel|alter|repair|roof|addition|demol|plumb|electric|mechanical|hvac|residen|commercial|tenant|dwelling|new |renovat|interior|foundation|single.?family|multi.?family|garage|deck|window|solar|sprinkler|kitchen|bath|porch|fence|pool|erect|install|replace|house|apartment|condo|structure|change of use|rebuild)");

    /** Whether a permits dataset's first rows describe building work anywhere in them, the kind, the description, the work class, whichever column holds it; rows with no words at all are taken on the name. */
    static boolean buildingWork(List<Map<String, String>> rows, Map<String, String> fs) {
        if (rows == null || rows.isEmpty()) return true;
        int words = 0;
        for (Map<String, String> r : rows) for (Map.Entry<String, String> e : r.entrySet()) {
            String v = e.getValue(); if (v == null || v.isBlank() || !v.matches(".*[A-Za-z]{3,}.*") || e.getKey().startsWith("__")) continue;
            words++; if (BUILDING_WORK.matcher(v).find()) return true;
        }
        return words == 0;
    }

    /** The year of an assessor's roll: the most of its year column (ASSESSORYEAR, TAXYEAR, ROLL_YEAR, FY…) in the first rows, else the year the catalogue gave; "" when neither says. */
    static String rollYear(List<String> cols, List<Map<String, String>> rows, Dataset d) {
        String col = "";
        for (String c : cols) { String n = norm(c); if (any(n, "assessoryear", "assessyear", "taxyear", "rollyear", "assessmentyear", "assessmentyr", "asmntyr", "taxrollyear", "closedrollyear", "valuationyear", "appryear") || n.equals("fy") || n.equals("year") || n.equals("rollyr")) { col = c; break; } }
        int best = 0;
        if (!col.isEmpty() && rows != null) for (Map<String, String> r : rows) { Matcher m = Pattern.compile("(19|20)\\d\\d").matcher(r.getOrDefault(col, "")); if (m.find()) best = Math.max(best, Integer.parseInt(m.group())); }
        if (best > 0) return String.valueOf(best);
        int y = d.latestYear();
        return y > 0 ? String.valueOf(y) : "";
    }

    /**
     * How fresh the records are: the newest value of the date column, asked of the service (the most of a Socrata column, an ArcGIS statistic,
     * a CKAN sort), else what the dataset already carries (the catalogue's last change, or a year in its title); "" when nothing says.
     */
    static String latest(Dataset d) {
        String date = d.field("date");
        if (!date.isEmpty()) {
            try {
                String body = null;
                switch (d.platform()) {
                    case "Socrata" -> { body = HomeSensors.get("https://" + d.site() + "/resource/" + d.id() + ".json?$select=" + enc("max(" + date + ") as m")); if (body != null) { JsonNode a = M.readTree(body); if (a.isArray() && !a.isEmpty()) { String m = day(a.get(0).path("m").asText("")); if (m.matches("\\d{4}.*")) return m; } } }
                    case "ArcGIS" -> { body = HomeSensors.get(d.id() + "/query?where=1%3D1&outStatistics=" + enc("[{\"statisticType\":\"max\",\"onStatisticField\":\"" + date + "\",\"outStatisticFieldName\":\"m\"}]") + "&f=json"); if (body != null) { JsonNode f = M.readTree(body).path("features"); if (f.isArray() && !f.isEmpty()) { String m = day(f.get(0).path("attributes").path("m").asText("")); if (m.matches("\\d{4}.*")) return m; } } }
                    case "File" -> { String m = HomeFiles.latest(d); if (!m.isEmpty()) return m; }
                    case "CKAN" -> { body = HomeSensors.get("https://" + d.site() + "/api/3/action/datastore_search?resource_id=" + enc(d.id()) + "&sort=" + enc(date + " desc") + "&limit=1"); if (body != null) { JsonNode r = M.readTree(body).path("result").path("records"); if (r.isArray() && !r.isEmpty()) { String m = day(r.get(0).path(date).asText("")); if (m.matches("\\d{4}.*")) return m; } } }
                    default -> { }
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        if (!d.latest().isEmpty()) return d.latest();
        Matcher y = Pattern.compile("\\b(19[5-9]\\d|20[0-4]\\d)\\b").matcher(d.name());
        int best = 0; while (y.find()) best = Math.max(best, Integer.parseInt(y.group(1)));
        return best > 0 ? String.valueOf(best) : "";
    }

    static List<Map<String, String>> firstRows(Dataset d) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            String body;
            switch (d.platform()) {
                case "Socrata" -> { body = HomeSensors.get("https://" + d.site() + "/resource/" + d.id() + ".json?$limit=5"); if (body == null) return null; for (JsonNode r : M.readTree(body)) out.add(flat(r)); }
                case "ArcGIS" -> { body = HomeSensors.get(d.id() + "/query?where=1%3D1&outFields=*&resultRecordCount=5&returnGeometry=false&f=json"); if (body == null) return null; for (JsonNode f : M.readTree(body).path("features")) out.add(flat(f.path("attributes"))); }
                case "CKAN" -> { body = HomeSensors.get("https://" + d.site() + "/api/3/action/datastore_search?resource_id=" + enc(d.id()) + "&limit=5"); if (body == null) return null; for (JsonNode r : M.readTree(body).path("result").path("records")) out.add(flat(r)); }
                case "File" -> { return HomeFiles.firstRows(d); }
                default -> { return null; }
            }
        } catch (IOException | RuntimeException e) { return null; }
        return out;
    }

    /** The columns named by the library's model from the column names and five rows, when the names alone did not say: the backstop. */
    static Map<String, String> columnsByModel(Dataset d, List<String> cols, List<Map<String, String>> rows, String what) {
        try {
            StringBuilder sample = new StringBuilder();
            for (Map<String, String> r : rows) { ObjectNode o = M.createObjectNode(); r.forEach((k, v) -> o.put(k, v.length() > 60 ? v.substring(0, 60) : v)); sample.append(o).append('\n'); }
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content", "A table of a city's " + (what.equals("permits") ? "building permits" : "property sales or assessed values") + ": its columns and five rows stand below. Name which column holds each of these, "
                    + "by the column's exact name, and leave out what no column holds: address (the home's own street address, number and street together), number (the house number alone), street (the street name alone), city, price (what was paid), "
                    + "value (the assessed or appraised value), date, desc (the kind of sale, or the work the permit is for), sqft (the home's floor area), year (the year built), key (the parcel number). The owner's and the mailing address are not the address.\n\n"
                    + "Answer with one JSON object and nothing else, like {\"address\": \"situs_addr\", \"value\": \"total_val\"}.\n\nCOLUMNS\n" + String.join(", ", cols) + "\n\nROWS\n" + Fence.rule("the rows") + "\n" + Fence.wrap("ROWS", sample.toString()));
            String said = Career.DRIVE.get().classify(msgs, 400);
            JsonNode j = CareerInterview.parseObject(said);
            Map<String, String> fs = new LinkedHashMap<>();
            for (String k : List.of("address", "number", "street", "city", "price", "value", "date", "desc", "sqft", "year", "key")) { String v = j.path(k).asText("").strip(); if (!v.isEmpty() && cols.contains(v)) fs.put(k, v); }
            boolean addressed = fs.containsKey("address") || (fs.containsKey("number") && fs.containsKey("street"));
            if (!addressed) return null;
            if (what.equals("sales") && !fs.containsKey("price") && !fs.containsKey("value")) return null;
            if (what.equals("permits") && !fs.containsKey("date") && !fs.containsKey("desc")) return null;
            return fs;
        } catch (RuntimeException e) { return null; }
    }

    /** Socrata's catalogue across every portal, for a place with no portal of its own: the datasets whose words name the place. */
    static List<Dataset> socrataAnywhere(String city, String state, String county, String what, List<String> looked) {
        List<Dataset> out = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        queries.add("\"" + city + "\" " + state + " " + (what.equals("sales") ? "property sales" : "building permits"));
        if (!county.isEmpty()) queries.add("\"" + county + "\" " + state + " " + (what.equals("sales") ? "parcel sales" : "building permits"));
        List<String[]> scored = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String q : queries) {
            String body = HomeSensors.get("https://api.us.socrata.com/api/catalog/v1?q=" + enc(q) + "&only=datasets&limit=20");
            if (body == null) continue;
            try {
                for (JsonNode r : M.readTree(body).path("results")) {
                    JsonNode res = r.path("resource");
                    String id = res.path("id").asText(""), name = res.path("name").asText(""), domain = r.path("metadata").path("domain").asText("");
                    if (id.isEmpty() || domain.isEmpty() || seen.containsKey(domain + "|" + id) || !nameFits(name, what)) continue;
                    seen.put(domain + "|" + id, true);
                    List<String> cols = new ArrayList<>(); for (JsonNode c : res.path("columns_field_name")) cols.add(c.asText(""));
                    Map<String, String> fs = columns(cols, what);
                    if (fs == null) continue;
                    String text = (name + " " + res.path("description").asText("") + " " + res.path("attribution").asText("") + " " + domain).toLowerCase(Locale.ROOT);
                    int score = 0;
                    if (text.contains(city.toLowerCase(Locale.ROOT))) score += 3;
                    if (!county.isEmpty() && text.contains(county.toLowerCase(Locale.ROOT).replace(" county", ""))) score += 2;
                    if (score == 0) continue;   // nothing names the place: another city's records
                    if (fs.containsKey("address") || fs.containsKey("number")) score += 2;
                    ObjectNode d = M.createObjectNode().put("platform", "Socrata").put("site", domain).put("id", id).put("name", name); ObjectNode f = d.putObject("fields"); fs.forEach(f::put);
                    ArrayNode cs = d.putArray("cols"); for (String c : cols) cs.add(c);
                    scored.add(new String[]{String.valueOf(score), d.toString()});
                }
            } catch (IOException ignored) { }
        }
        scored.sort((a, b) -> Integer.compare(Integer.parseInt(b[0]), Integer.parseInt(a[0])));
        for (String[] s : scored) {
            try {
                JsonNode j = M.readTree(s[1]); Dataset d = settleByRows(dataset(j), list(j.path("cols")), what, looked);
                if (d == null) continue;
                if (d.field("address").isEmpty() && d.field("number").isEmpty()) { Dataset with = socrataAddresses(d); if (with == null) continue; d = with; }
                out.add(d); if (out.size() >= 3) break;
            } catch (IOException ignored) { }
        }
        looked.add("Socrata's catalogue at large, " + queries.size() + " queries for " + what + " naming the place: " + out.size() + (out.size() == 1 ? " candidate" : " candidates") + (out.isEmpty() ? "" : ", " + out.stream().map(Dataset::reader).toList()));
        return out;
    }

    /** The dataset of the same portal that gives a parcel's address, for a sales dataset keyed by parcel alone. */
    static Dataset socrataAddresses(Dataset sales) {
        String body = HomeSensors.get("https://api.us.socrata.com/api/catalog/v1?search_context=" + enc(sales.site()) + "&q=" + enc("parcel addresses") + "&only=datasets&limit=10");
        if (body == null) return null;
        Dataset best = null; int bestScore = -1;
        try {
            for (JsonNode r : M.readTree(body).path("results")) {
                JsonNode res = r.path("resource");
                String name = res.path("name").asText(""), n = name.toLowerCase(Locale.ROOT);
                if (any(n, "exempt", "vacant", "owned", "sale", "permit", "appeal", "assessment", "characteristics", "archived", "historic")) continue;   // a narrower or an older list of parcels than the addresses of all
                List<String> cols = new ArrayList<>(); for (JsonNode c : res.path("columns_field_name")) cols.add(c.asText(""));
                Map<String, String> fs = columns(cols, "addresses");
                if (fs == null || (!fs.containsKey("address") && !fs.containsKey("number"))) continue;
                String same = cols.stream().filter(c -> norm(c).equals(norm(sales.field("key")))).findFirst().orElse("");   // the sales' own key, not another parcel number of the dataset ("pin10" beside "pin")
                if (same.isEmpty()) continue;
                fs.put("key", same);
                int score = (n.contains("address") ? 2 : 0) + (fs.containsKey("city") ? 1 : 0);
                if (score > bestScore) { bestScore = score; best = new Dataset(sales.platform(), sales.site(), sales.id(), sales.name() + " with " + name, sales.fields(), res.path("id").asText(""), fs); }
            }
        } catch (IOException ignored) { }
        return best;
    }

    // ---------------------------------------------------------------- ArcGIS

    /**
     * The organisations on the Hub that publish for the place: the ones named in the Hub's answers to the city's and the county's names, when
     * their own name carries the city's or the county's word ("King County", "County of Los Angeles", "The City of Portland, Maine").
     */
    static List<String> arcgisOrgs(String city, String state, String county) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        String sn = STATE_NAMES.getOrDefault(state.toUpperCase(Locale.ROOT), state.toLowerCase(Locale.ROOT));
        for (String q : county.isEmpty() ? List.of("\"" + city + ", " + state + "\"") : List.of("\"" + county + "\" " + state, "\"" + city + ", " + state + "\"")) {
            String body = HomeSensors.get("https://hub.arcgis.com/api/v3/datasets?q=" + enc(q) + "&page%5Bsize%5D=40&fields%5Bdatasets%5D=orgName");
            if (body == null) continue;
            try { for (JsonNode d : M.readTree(body).path("data")) { String o = d.path("attributes").path("orgName").asText(""); if (!o.isEmpty()) seen.merge(o, 1, Integer::sum); } } catch (IOException ignored) { }
        }
        String c = city.toLowerCase(Locale.ROOT), co = county.toLowerCase(Locale.ROOT).replaceAll("\\s+(county|parish|borough)$", "");
        List<String> out = new ArrayList<>();
        seen.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).forEach(e -> {
            String o = e.getKey().toLowerCase(Locale.ROOT);
            if (out.size() < 4 && (o.contains(c) || (!co.isEmpty() && o.contains(co))) && !any(o, "university", "college", "school", "esri", "church") && !otherState(o, state)) out.add(e.getKey());
        });
        List<String> guesses = new ArrayList<>();
        if (state.equalsIgnoreCase("DC")) guesses.addAll(List.of("DC GIS", "District of Columbia", "District of Columbia Government"));
        guesses.add("City of " + city); guesses.add(city + " GIS"); guesses.add("City of " + city + " GIS");
        if (!county.isEmpty()) { guesses.add(county); guesses.add("County of " + county.replaceAll("(?i)\\s+county$", "")); guesses.add(county + " GIS"); guesses.add(county + " Government"); }
        for (String g : guesses) if (out.size() < 6 && out.stream().noneMatch(o -> o.equalsIgnoreCase(g))) out.add(g);   // the names such bodies give themselves, beside what the Hub named
        return out;
    }

    /** A layer's fields from the layer itself, when the Hub's listing stops short of the value columns. */
    static List<String> layerFields(String url) {
        List<String> out = new ArrayList<>();
        String body = HomeSensors.get(url + "?f=json");
        try { if (body != null) for (JsonNode f : M.readTree(body).path("fields")) out.add(f.path("name").asText("")); } catch (IOException ignored) { }
        if (out.isEmpty()) {   // a server that errs on the layer's own description (Cuyahoga's does) still names the fields in a query's answer
            body = HomeSensors.get(url + "/query?where=1%3D1&outFields=*&resultRecordCount=1&returnGeometry=false&f=json");
            try { if (body != null) for (JsonNode f : M.readTree(body).path("fields")) out.add(f.path("name").asText("")); } catch (IOException ignored) { }
        }
        return out;
    }

    /** Whether an organisation's name names a state other than the place's: "Cumberland County, NC" is not Maine's. */
    static boolean otherState(String org, String state) {
        String st = state.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : STATE_NAMES.entrySet()) {
            if (e.getKey().equals(st)) continue;
            if (org.matches(".*\\b" + e.getKey().toLowerCase(Locale.ROOT) + "\\b.*") && org.matches(".*,\\s*" + e.getKey().toLowerCase(Locale.ROOT) + "\\b.*")) return true;
            String name = e.getValue(); if (name.length() > 3 && org.contains(name)) return true;
        }
        return false;
    }

    /** ArcGIS Hub: the place's own organisations asked by name for parcels or permits, then the Hub at large for the county's and the city's: the layers whose extent holds the home, no wider than a region, and whose fields the reading needs, a public body's first. */
    /**
     * The order the Hub's layers are tried in, since only the first four that settle are candidates: the place's own organisation's first,
     * a government's over a company's or a student's, the city's own over the county's (Los Angeles's own parcels before the county's rolls,
     * which answer for every town), then the name's rank.
     */
    static int hubScore(boolean ownOrganisation, String org, String scope, String name, String what) {
        int score = ownOrganisation ? 4 : 1;
        if (any(org, "county", "city of", "town of", "consortium", "government", "gis")) score += 3;
        if ("city".equals(scope)) score += 3; else if ("county".equals(scope)) score += 1;
        return score + (what.equals("permits") ? permitRank(name) : salesRank(name));
    }

    static List<Dataset> arcgis(List<String> sites, String city, String state, String county, double lat, double lon, String what, List<String> looked) {
        List<Dataset> out = new ArrayList<>();
        if (lat == 0 || lon == 0) { looked.add("ArcGIS Hub not asked: the home is not placed on the map"); return out; }
        List<String[]> queries = new ArrayList<>();   // {site, q}
        List<String> words = what.equals("sales") ? List.of("parcels", "assessor") : List.of("building permits", "permits");
        List<String> orgs = arcgisOrgs(city, state, county);
        for (String o : orgs) for (String w : words) queries.add(new String[]{"https://hub.arcgis.com", w, o});
        if (!county.isEmpty()) queries.add(new String[]{"https://hub.arcgis.com", county + " " + state + (what.equals("sales") ? " parcels assessor" : " building permits")});
        queries.add(new String[]{"https://hub.arcgis.com", city + " " + state + (what.equals("sales") ? " parcels" : " building permits")});
        List<String[]> scored = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int asked = 0, raw = 0, layers = 0, inside = 0, fetchedFields = 0;
        for (String[] sq : queries) {
            String body = HomeSensors.get(sq[0] + "/api/v3/datasets?q=" + enc(sq[1]) + (sq.length > 2 ? "&filter%5BorgName%5D=" + enc(sq[2]) : "") + "&page%5Bsize%5D=40&fields%5Bdatasets%5D=name,url,fields,extent,orgName,description,modified");
            asked++;
            if (body == null) continue;
            try {
                for (JsonNode d : M.readTree(body).path("data")) {
                    JsonNode a = d.path("attributes");
                    String url = a.path("url").asText(""), name = a.path("name").asText("");
                    if (seen.containsKey(url + "|" + name)) continue;
                    seen.put(url + "|" + name, true); raw++;
                    if (url.isEmpty() || !url.contains("/rest/services/") || !url.matches(".*/(Feature|Map)Server/\\d+$")) continue;
                    layers++;
                    JsonNode ext = a.path("extent").path("coordinates");
                    if (!ext.isArray() || ext.size() < 2) { if (sq.length < 3) continue; }   // a table with no geometry has no extent, Irving's permits: kept when the place's own organisation answered it, since the Hub at large cannot place it
                    else {
                        double minLon = ext.get(0).get(0).asDouble(), minLat = ext.get(0).get(1).asDouble(), maxLon = ext.get(1).get(0).asDouble(), maxLat = ext.get(1).get(1).asDouble();
                        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) continue;
                        if (maxLat - minLat > 3.5 || maxLon - minLon > 4.5) continue;   // a layer of a state or a country, not of a city or a county
                    }
                    inside++;
                    if (!nameFits(name, what)) continue;
                    List<String> cols = new ArrayList<>(); for (JsonNode f : a.path("fields")) cols.add(f.path("name").asText(""));
                    Map<String, String> fs = columns(cols, what);
                    if (fs == null && cols.size() >= 14 && (what.equals("permits") ? permitRank(name) : salesRank(name)) > 0 && fetchedFields++ < 16) { List<String> all = layerFields(url); if (all.size() > cols.size()) { cols = all; fs = columns(cols, what); } }   // the Hub lists a layer's first fields only
                    if (fs == null || (!fs.containsKey("address") && !fs.containsKey("number"))) continue;
                    String org = a.path("orgName").asText("").toLowerCase(Locale.ROOT);
                    fs.put("scope", scopeOf((sq.length > 2 ? sq[2] : "") + " " + a.path("orgName").asText("") + " " + name, city, county, state));
                    int score = hubScore(sq.length > 2, org, fs.get("scope"), name, what);
                    ObjectNode j = M.createObjectNode().put("platform", "ArcGIS").put("site", url.replaceFirst("^https?://", "").replaceFirst("/.*$", "")).put("id", url).put("name", name).put("latest", day(a.path("modified").asText(""))); ObjectNode f = j.putObject("fields"); fs.forEach(f::put);
                    ArrayNode cs = j.putArray("cols"); for (String c : cols) cs.add(c);
                    scored.add(new String[]{String.valueOf(score), j.toString()});
                }
            } catch (IOException ignored) { }
        }
        scored.sort((a, b) -> Integer.compare(Integer.parseInt(b[0]), Integer.parseInt(a[0])));
        Map<String, Integer> perSite = new LinkedHashMap<>();   // two of one site at most: Fort Worth's own four parcel layers, none answering, left no room for the appraisal district's roll
        for (String[] s : scored) {
            try {
                JsonNode j = M.readTree(s[1]); String site = j.path("site").asText("");
                if (perSite.getOrDefault(site, 0) >= 2) continue;
                Dataset d = settleByRows(dataset(j), list(j.path("cols")), what, looked);
                if (d != null) { out.add(d); perSite.merge(site, 1, Integer::sum); }
            } catch (IOException ignored) { }
            if (out.size() >= 6) break;
        }
        looked.add("ArcGIS Hub, " + orgs.size() + (orgs.size() == 1 ? " organisation" : " organisations") + " of the place's own" + (orgs.isEmpty() ? "" : " (" + String.join(", ", orgs) + ")") + " and the Hub at large, " + asked + " queries for " + what + ": " + raw + " hits, " + layers + " layers with an address, " + inside + " whose extent holds the home, " + out.size() + (out.size() == 1 ? " candidate" : " candidates") + (out.isEmpty() ? "" : ", " + out.stream().map(Dataset::reader).toList()));
        return out;
    }

    // ---------------------------------------------------------------- CKAN

    /** A CKAN portal's datasets with the columns the reading needs. */
    static List<Dataset> ckan(List<String> sites, String what, List<String> looked) { return ckan(sites, "", "", "", what, looked); }

    static List<Dataset> ckan(List<String> sites, String city, String county, String state, String what, List<String> looked) {
        List<Dataset> out = new ArrayList<>();
        int probed = 0;
        for (String host : sites) {
            String body = HomeSensors.get("https://" + host + "/api/3/action/package_search?q=" + enc(what.equals("sales") ? "property assessment sales" : "building permits") + "&rows=10");
            probed++;
            if (body == null) continue;
            try {
                JsonNode j = M.readTree(body);
                if (!j.path("success").asBoolean(false)) continue;
                for (JsonNode pkg : j.path("result").path("results")) {
                    String title = pkg.path("title").asText("");
                    if (!nameFits(title, what)) continue;
                    for (JsonNode r : pkg.path("resources")) {
                        if (!r.path("datastore_active").asBoolean(false)) continue;
                        String id = r.path("id").asText("");
                        String first = HomeSensors.get("https://" + host + "/api/3/action/datastore_search?resource_id=" + enc(id) + "&limit=5");
                        if (first == null) continue;
                        JsonNode res = M.readTree(first).path("result");
                        List<String> cols = new ArrayList<>(); for (JsonNode fld : res.path("fields")) cols.add(fld.path("id").asText(""));
                        List<Map<String, String>> rows = new ArrayList<>(); for (JsonNode row : res.path("records")) rows.add(flat(row));
                        Map<String, String> fs = columns(cols, what, rows.isEmpty() ? null : rows);
                        if (fs == null || (!fs.containsKey("address") && !fs.containsKey("number"))) continue;
                        fs.put("scope", scopeOf(host + " " + title, city, county, state));
                        out.add(new Dataset("CKAN", host, id, title + (r.path("name").asText("").isEmpty() ? "" : ", " + r.path("name").asText("")), fs, "", Map.of()));
                        break;
                    }
                    if (out.size() >= 3) break;
                }
            } catch (IOException ignored) { }
        }
        if (probed > 0) looked.add("CKAN, " + probed + (probed == 1 ? " portal" : " portals") + " asked for " + what + ": " + out.size() + (out.size() == 1 ? " candidate" : " candidates") + (out.isEmpty() ? "" : ", " + out.stream().map(Dataset::reader).toList()));
        return out;
    }

    // ---------------------------------------------------------------- the reading of one street

    /** The street without its direction and its suffix: "N LASALLE ST" → "LASALLE", "EAST 3 STREET" → "3", as a street-name column writes it. */
    static String core(String street) {
        String s = street == null ? "" : street.toUpperCase(Locale.ROOT).strip();
        s = s.replaceFirst("^(N|S|E|W|NE|NW|SE|SW|NORTH|SOUTH|EAST|WEST)\\s+", "");
        s = s.replaceFirst("\\s+(ST|STREET|AVE|AVENUE|RD|ROAD|DR|DRIVE|LN|LANE|BLVD|BOULEVARD|WAY|CT|COURT|PL|PLACE|TER|TERRACE|CIR|CIRCLE|PKWY|PARKWAY|HWY|HIGHWAY|SQ|SQUARE)\\.?(\\s+(N|S|E|W|NE|NW|SE|SW))?$", "");
        return s.isBlank() ? street.toUpperCase(Locale.ROOT) : s;
    }

    /** The street's core as some cities write it, in two words: "LASALLE" → "LA SALLE", "DEKALB" → "DE KALB", "MCKINLEY" → "MC KINLEY"; "" when there is no such spelling. */
    static String alt(String core) {
        Matcher m = Pattern.compile("^(LA|LE|DE|DEL|MC|MAC|VAN|VON|SAN|SANTA|ST|LAS|LOS)([A-Z]{3,})$").matcher(core == null ? "" : core);
        return m.find() ? m.group(1) + " " + m.group(2) : "";
    }

    /** The dataset's rows for the street; when the whole street gives none, for its core ("MARKET" for "MARKET ST", which a roll may write with the suffix apart); then for the core's other spelling. */
    static List<Map<String, String>> rowsOrAlt(Dataset d, String street, int number, String city) {
        if (d == null || street == null || street.isBlank()) return new ArrayList<>();
        String s = street.toUpperCase(Locale.ROOT).strip(), noDir = DIRECTION.matcher(s).replaceFirst("");   // the service is asked without the direction: one appraiser writes it before the number
        List<Map<String, String>> out = rows(d, noDir, s, number, city);
        if (out.isEmpty() && LAST_SILENT.get()) { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } out = rows(d, noDir, s, number, city); }   // the service gave no answer at all: asked once more
        if (out.size() < 3 && !core(s).equals(noDir) && !d.field("address").isEmpty()) {   // the whole word gave little: asked loosely, for a suffix written long, a direction after it, or a roll that writes "MARKET   ST"; the rows still kept by the whole street
            LOOSE.set(true);
            try { for (Map<String, String> r : rows(d, noDir, s, number, city)) if (!out.contains(r)) out.add(r); } finally { LOOSE.remove(); }
        }
        if (out.isEmpty()) { String a = alt(core(s)); if (!a.isEmpty()) out = rows(d, a, s.replace(core(s), a), number, city); }
        return out;
    }

    static final Map<String, String> SUFFIX = Map.ofEntries(Map.entry("STREET", "ST"), Map.entry("AVENUE", "AVE"), Map.entry("ROAD", "RD"), Map.entry("DRIVE", "DR"), Map.entry("BOULEVARD", "BLVD"), Map.entry("LANE", "LN"),
            Map.entry("COURT", "CT"), Map.entry("PLACE", "PL"), Map.entry("TERRACE", "TER"), Map.entry("CIRCLE", "CIR"), Map.entry("PARKWAY", "PKWY"), Map.entry("HIGHWAY", "HWY"), Map.entry("SQUARE", "SQ"), Map.entry("TRAIL", "TRL"), Map.entry("WAY", "WAY"));
    static final Pattern DIRECTION = Pattern.compile("^(N|S|E|W|NE|NW|SE|SW|NORTH|SOUTH|EAST|WEST)\\s+");

    /**
     * Whether a record's address is on the street: a house number, then the street (a direction before it may be given or not, its suffix
     * written long or short), then nothing, or a unit. "518 34TH AVE" is not on 4TH AVE, "711 4TH AVE N" is another street, "925 4TH AVE" and
     * "22 N LASALLE ST #1200" are on theirs.
     */
    static boolean onStreet(String address, String street) {
        String a = tidy(address).toUpperCase(Locale.ROOT), s = street == null ? "" : street.toUpperCase(Locale.ROOT).strip();
        if (a.isEmpty() || s.isEmpty()) return false;
        for (Map.Entry<String, String> e : SUFFIX.entrySet()) { a = a.replaceAll("\\b" + e.getKey() + "\\b", e.getValue()); s = s.replaceAll("\\b" + e.getKey() + "\\b", e.getValue()); }
        Matcher dm = DIRECTION.matcher(s); String dir = dm.find() ? dm.group(1) : "";
        String rest = dir.isEmpty() ? s : s.substring(dm.end()).strip();
        Matcher sm = Pattern.compile("^(.*?)\\s+(ST|AVE|RD|DR|BLVD|LN|CT|PL|TER|CIR|PKWY|HWY|SQ|TRL|WAY)$").matcher(rest);
        String core = sm.find() ? sm.group(1) : rest, suffix = sm.find(0) ? sm.group(2) : "";   // a record may leave the suffix off (a street-name column), never swap it: 4TH ST is not 4TH AVE
        String thisDir = dir.isEmpty() ? "(?:(?:N|S|E|W|NE|NW|SE|SW)\\s+)?" : "(?:" + dir + "\\s+)?";   // a direction the person left off, or the one they gave, before the number or after it
        String p = "^" + thisDir + "\\d+[A-Z]?(?:-\\d+)?\\s+" + thisDir + Pattern.quote(core) + (suffix.isEmpty() ? "(?:\\s+(?:ST|AVE|RD|DR|BLVD|LN|CT|PL|TER|CIR|PKWY|HWY|SQ|TRL|WAY))?" : "(?:\\s+" + suffix + ")?")
                + "(?:\\s*(?:#|UNIT|APT|STE|SUITE|FL|FLR|RM|BLDG|RESD|\\d|-|,|&|\\().*)?(?:\\s+[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?)?(?:\\s+\\d{5}(?:-\\d{4})?)?$";   // "S 500 2ND ST TX 78660": an appraiser's state and zip at the end
        return Pattern.compile(p).matcher(a).find();
    }

    /** The dataset's rows for the street asked, the ones whose address is on the street checked: each a map of column to text; the home's city when the dataset has a city column. */
    static List<Map<String, String>> rows(Dataset d, String street, int number, String city) { return rows(d, street, street, number, city); }

    /** The home's zip for the reading, when the caller knows it; a dataset with a zip column and no city column is asked by it. */
    static final ThreadLocal<String> ZIP = new ThreadLocal<>();

    static List<Map<String, String>> rows(Dataset d, String asked, String checked, int number, String city) {
        List<Map<String, String>> all = rowsRaw(d, asked, number, city), out = new ArrayList<>();
        for (Map<String, String> r : all) {
            String a = r.getOrDefault("__address", "");
            if (!a.isEmpty() && !onStreet(a, checked)) continue;
            if (!a.isEmpty() && !city.isEmpty() && a.contains(",") && !inTown(a, city)) continue;   // "238 Garfield Avenue, Pomona CA", "447 Garfield Avenue, South Pasadena CA", "2001 IRVING BLVD, #149, Dallas, TX": another town's
            if (d != null && d.field("city").isEmpty() && !d.field("zip").isEmpty() && !zip().isEmpty()) { String z = r.getOrDefault(d.field("zip"), "").replaceAll("[^0-9]", ""); if (!z.startsWith(zip())) continue; }   // no city column: the home's zip tells its town apart, and a row with none cannot be told
            out.add(r);
        }
        return out;
    }

    /** Whether the last reading of rows got no answer from the service at all (a timeout, an error), as against an answer with no rows. */
    static final ThreadLocal<Boolean> LAST_SILENT = ThreadLocal.withInitial(() -> false);

    /** Whether an address that names a town after a comma names the home's: each segment after the first comma whose leading words are a name (not a unit, not a state code) must begin with the town, after "City of" when so written. */
    static boolean inTown(String address, String city) {
        String[] parts = address.toUpperCase(Locale.ROOT).split(",");
        String c = city.toUpperCase(Locale.ROOT);
        for (int i = 1; i < parts.length; i++) {
            Matcher m = Pattern.compile("^\\s*([A-Z][A-Z .'-]*?)\\s*(?:\\d.*)?$").matcher(parts[i]);
            if (!m.find()) continue;
            String words = m.group(1).strip();
            if (words.length() <= 2 || words.equals("USA") || words.equals("UNITED STATES")) continue;
            if (words.matches("^(CITY OF |TOWN OF |VILLAGE OF )?" + Pattern.quote(c) + "(\\b.*)?$")) continue;
            if (words.matches("^(" + Pattern.quote(c) + ")\\s+[A-Z]{2}$")) continue;
            return false;
        }
        return true;
    }

    /** The home's zip, five digits, or "" when the caller gave none. */
    static String zip() { String z = ZIP.get(); return z == null ? "" : z.replaceAll("[^0-9]", "").length() >= 5 ? z.replaceAll("[^0-9]", "").substring(0, 5) : ""; }

    static List<Map<String, String>> rowsRaw(Dataset d, String street, int number, String city) {
        List<Map<String, String>> out = new ArrayList<>();
        LAST_SILENT.set(false);
        if (d == null || street.isEmpty()) return out;
        try {
            switch (d.platform()) {
                case "Socrata" -> {
                    if (!d.joinId().isEmpty()) {   // the addresses first, then the sales by their keys
                        String addr = d.joinFields().getOrDefault("address", ""), num = d.joinFields().getOrDefault("number", ""), stn = d.joinFields().getOrDefault("street", ""), cityCol = d.joinFields().getOrDefault("city", ""), key = d.joinFields().getOrDefault("key", "");
                        String where = whereSocrata(addr, num, stn, cityCol, street, city);
                        String body = HomeSensors.get("https://" + d.site() + "/resource/" + d.joinId() + ".json?$where=" + enc(where) + "&$limit=40");
                        if (body == null) return out;
                        Map<String, String> addressOf = new LinkedHashMap<>();
                        for (JsonNode r : M.readTree(body)) { String k = r.path(key).asText(""); if (!k.isEmpty()) addressOf.putIfAbsent(k, addressIn(r, d.joinFields())); }
                        if (addressOf.isEmpty()) return out;
                        StringBuilder in = new StringBuilder(); int n = 0; for (String k : addressOf.keySet()) { if (n++ >= 40) break; in.append(in.isEmpty() ? "" : ",").append('\'').append(k.replace("'", "")).append('\''); }
                        String sk = d.field("key");
                        body = HomeSensors.get("https://" + d.site() + "/resource/" + d.id() + ".json?$where=" + enc(sk + " in (" + in + ")") + "&$limit=80" + (d.field("date").isEmpty() ? "" : "&$order=" + enc(d.field("date") + " DESC")));
                        if (body == null) return out;
                        for (JsonNode r : M.readTree(body)) { Map<String, String> row = flat(r); row.put("__address", addressOf.getOrDefault(r.path(sk).asText(""), "")); out.add(row); }
                        return out;
                    }
                    String where = whereSocrata(d.field("address"), d.field("number"), d.field("street"), d.field("city"), d.field("zip"), street, city);
                    String body = HomeSensors.get("https://" + d.site() + "/resource/" + d.id() + ".json?$where=" + enc(where) + "&$limit=1000" + (d.field("date").isEmpty() ? "" : "&$order=" + enc(d.field("date") + " DESC")));
                    if (body == null) { LAST_SILENT.set(true); return out; }
                    for (JsonNode r : M.readTree(body)) { Map<String, String> row = flat(r); row.put("__address", addressIn(r, d.fields())); out.add(row); }
                }
                case "ArcGIS" -> {
                    String col = d.field("address").isEmpty() ? d.field("street") : d.field("address");
                    String body = null;
                    for (boolean upper : new boolean[]{true, false}) {   // some servers refuse UPPER(); their addresses are upper-case already
                        String c = upper ? "UPPER(" + col + ")" : col;
                        String where;
                        if (d.field("address").isEmpty()) where = c + " LIKE '%" + core(street).replace("'", "") + "%'";
                        else { StringBuilder b = new StringBuilder("("); for (String l : likes(street.replace("'", ""))) b.append(b.length() > 1 ? " OR " : "").append(c).append(" LIKE '").append(l.replace("[A-Z][A-Z]", "__")).append("'"); where = b.append(")").toString(); }
                        if (!d.field("city").isEmpty() && !city.isEmpty()) where += " AND " + (upper ? "UPPER(" + d.field("city") + ")" : d.field("city")) + " LIKE '%" + city.toUpperCase(Locale.ROOT).replace("'", "") + "%'";
                        body = HomeSensors.get(d.id() + "/query?where=" + enc(where) + "&outFields=*&resultRecordCount=1000&returnGeometry=false&f=json");
                        if (body != null && !body.contains("\"error\"")) break;
                    }
                    if (body == null || body.contains("\"error\"")) { LAST_SILENT.set(true); return out; }
                    for (JsonNode f : M.readTree(body).path("features")) { Map<String, String> row = flat(f.path("attributes")); row.put("__address", addressIn(f.path("attributes"), d.fields())); out.add(row); }
                }
                case "File" -> { return HomeFiles.rows(d, street, city); }
                case "CKAN" -> {
                    String body = HomeSensors.get("https://" + d.site() + "/api/3/action/datastore_search?resource_id=" + enc(d.id()) + "&q=" + enc(core(street)) + "&limit=500");
                    if (body == null) { LAST_SILENT.set(true); return out; }
                    for (JsonNode r : M.readTree(body).path("result").path("records")) {
                        Map<String, String> row = flat(r); String a = addressIn(r, d.fields());
                        if (!a.toUpperCase(Locale.ROOT).contains(core(street))) continue;   // a free-text search matches more than the street
                        if (!d.field("city").isEmpty() && !city.isEmpty() && !r.path(d.field("city")).asText("").toUpperCase(Locale.ROOT).contains(city.toUpperCase(Locale.ROOT))) continue;
                        row.put("__address", a); out.add(row);
                    }
                }
                default -> { }
            }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** The LIKE patterns for a full-address column: the street at the end, or followed by a unit, a comma, the state; a street with no suffix, anywhere after a space. */
    static final ThreadLocal<Boolean> LOOSE = new ThreadLocal<>();

    static List<String> likes(String s) {
        boolean suffixed = s.matches(".*\\b(ST|AVE|RD|DR|BLVD|LN|CT|PL|TER|CIR|PKWY|HWY|SQ|TRL|WAY|STREET|AVENUE|ROAD|DRIVE)$");
        if (!suffixed || Boolean.TRUE.equals(LOOSE.get())) return List.of("% " + core(s) + "%");   // loosely: for a suffix written long or a direction after it; the rows are kept by the whole street
        return List.of("% " + s, "% " + s + " #%", "% " + s + " UNIT%", "% " + s + " APT%", "% " + s + " STE%", "% " + s + " SUITE%", "% " + s + ",%", "% " + s + "  %", "% " + s + " [A-Z][A-Z] %");
    }

    static String whereSocrata(String addr, String num, String stn, String cityCol, String street, String city) { return whereSocrata(addr, num, stn, cityCol, "", street, city); }

    static String whereSocrata(String addr, String num, String stn, String cityCol, String zipCol, String street, String city) {
        String s = street.replace("'", "");
        String where;
        if (!addr.isEmpty()) { StringBuilder b = new StringBuilder("("); for (String l : likes(s)) b.append(b.length() > 1 ? " OR " : "").append("upper(").append(addr).append(") like '").append(l.replace("[A-Z][A-Z]", "__")).append("'"); where = b.append(")").toString(); }
        else where = "upper(" + stn + ") like '%" + core(s) + "%'";
        if (!cityCol.isEmpty() && !city.isEmpty()) where += " AND upper(" + cityCol + ") like '%" + city.toUpperCase(Locale.ROOT).replace("'", "") + "%'";
        return where;
    }

    static Map<String, String> flat(JsonNode r) {
        Map<String, String> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = r.fields();
        while (it.hasNext()) { Map.Entry<String, JsonNode> e = it.next(); JsonNode v = e.getValue(); out.put(e.getKey(), v.isNull() ? "" : v.isValueNode() ? v.asText("") : v.toString()); }
        return out;
    }

    /** The address as the row writes it, tidied: the full one, or the number and the street joined; runs of spaces made one, a roll's padding zeros taken off ("0000 0425 MARKET   ST0000" → "425 MARKET ST"). */
    static String addressIn(JsonNode r, Map<String, String> fields) {
        String a = fields.getOrDefault("address", "");
        String s;
        if (!a.isEmpty()) {
            JsonNode v = r.path(a);
            s = v.isObject() ? v.path("human_address").asText(v.path("address").asText("")) : v.asText("");
            if (s.startsWith("{")) { try { s = M.readTree(s).path("address").asText(""); } catch (IOException ignored) { } }
        } else {   // the address from its parts, as a roll splits them: the direction, the number, the street, the suffix and the direction after
            StringBuilder b = new StringBuilder();
            for (String part : List.of(fields.getOrDefault("prefix", ""), fields.getOrDefault("number", ""), fields.getOrDefault("street", ""))) if (!part.isEmpty()) b.append(' ').append(r.path(part).asText(""));
            for (String part : fields.getOrDefault("suffix", "").split("\\+")) if (!part.isEmpty()) b.append(' ').append(r.path(part).asText(""));
            s = b.toString().replace(" 0 ", " ").replaceAll("\\s+", " ");
        }
        return tidy(s);
    }

    static String tidy(String s) {
        String t = (s == null ? "" : s).replace("null", "").replaceAll("\\s+", " ").strip();
        t = t.replaceFirst("^(0{2,}\\s+)+", "").replaceFirst("(?<=[A-Za-z])0{3,}$", "").replaceFirst("^0+(\\d)", "$1");
        t = t.replaceFirst("(?i)\\b([A-Z]{2,4})\\s+\\1$", "$1");   // "MARKET ST ST": a roll that writes the suffix with the name and again on its own
        return t.strip();
    }

    /** A date as the records write it: "2026-03-04", "2026-03-04T00:00:00.000", or the milliseconds since 1970 as a number ("863740800000.0"), each to the day. */
    static String day(String s) {
        String t = s == null ? "" : s.strip();
        if (t.matches("\\d{11,}(\\.0+)?")) { try { return Instant.ofEpochMilli(Long.parseLong(t.replaceFirst("\\.0+$", ""))).atZone(ZoneOffset.UTC).toLocalDate().toString(); } catch (NumberFormatException e) { return ""; } }
        if (t.matches("(19|20)\\d{6}(\\.0+)?")) return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);   // 20180629
        return t.replaceFirst("T.*$", "").replaceFirst("\\s.*$", "");
    }

    /** A value column, or several joined by "+" summed. */
    static long valueOf(Map<String, String> r, String field) {
        if (field.isEmpty()) return 0;
        long sum = 0; for (String f : field.split("\\+")) sum += HomeSensors.money(r.getOrDefault(f, ""));
        return sum;
    }

    /** The sales or the values on the street, the nearest house numbers first, as lines with the figures as the records write them. */
    public static List<String> prices(Dataset d, String street, int number, String city, String zip) { ZIP.set(zip == null ? "" : zip); try { return prices(d, street, number, city); } finally { ZIP.remove(); } }

    public static List<String> prices(Dataset d, String street, int number, String city) {
        List<String> lines = new ArrayList<>(); List<int[]> dist = new ArrayList<>();
        for (Map<String, String> r : rowsOrAlt(d, street, number, city)) {
            String addr = r.getOrDefault("__address", "");
            long price = HomeSensors.money(r.getOrDefault(d.field("price"), "")), value = valueOf(r, d.field("value"));
            if (price < 10_000 && value < 10_000) continue;   // a $0 transfer, a blank
            String date = day(r.getOrDefault(d.field("date"), "")), desc = r.getOrDefault(d.field("desc"), "").strip().toLowerCase(Locale.ROOT);
            long sq = HomeSensors.money(r.getOrDefault(d.field("sqft"), "")); String built = r.getOrDefault(d.field("year"), "").replaceFirst("\\.0$", "");
            StringBuilder b = new StringBuilder(addr.isEmpty() ? "a parcel on " + street : addr).append(": ");
            if (price >= 10_000) b.append('$').append(String.format(Locale.ROOT, "%,d", price)).append(date.isEmpty() ? "" : " on " + date).append(desc.isEmpty() ? "" : " (" + desc + ")");
            else b.append("assessed at $").append(String.format(Locale.ROOT, "%,d", value));   // the date column is a sale's: it says nothing of when the value was set
            if (built.matches("1[6-9]\\d\\d|20\\d\\d")) b.append(", built ").append(built);
            if (sq > 0) b.append(", ").append(String.format(Locale.ROOT, "%,d", sq)).append(" sq ft");
            lines.add(b.toString()); dist.add(new int[]{Math.abs(HomeSensors.houseNumber(addr) - number)});
        }
        return HomeSensors.nearest(lines, dist, 8);
    }

    /** The permits on the street, the nearest house numbers first. */
    public static List<String> permits(Dataset d, String street, int number, String city, String zip) { ZIP.set(zip == null ? "" : zip); try { return permits(d, street, number, city); } finally { ZIP.remove(); } }

    public static List<String> permits(Dataset d, String street, int number, String city) {
        List<String> lines = new ArrayList<>(); List<int[]> dist = new ArrayList<>();
        for (Map<String, String> r : rowsOrAlt(d, street, number, city)) {
            String addr = r.getOrDefault("__address", ""), date = day(r.getOrDefault(d.field("date"), "")), desc = r.getOrDefault(d.field("desc"), "").strip();
            if (addr.isEmpty() || (date.isEmpty() && desc.isEmpty())) continue;
            lines.add(addr + ": " + (desc.isEmpty() ? "a permit" : desc.length() > 90 ? desc.substring(0, 90) + "…" : desc) + (date.isEmpty() ? "" : ", " + date));
            dist.add(new int[]{Math.abs(HomeSensors.houseNumber(addr) - number)});
        }
        return HomeSensors.nearest(lines, dist, 8);
    }

    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
