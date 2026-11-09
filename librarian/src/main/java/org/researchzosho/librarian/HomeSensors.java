package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import java.util.Arrays;
/**
 * What the records say of a home, read by sensors before any model: where it is (the Census geocoder, no key), the flood zone (FEMA's
 * flood layer), the schools around it (the Urban Institute's copy of the federal school directory), the nearest transit stops (the city's
 * GTFS feed from the Mobility Database catalogue), and the sales or assessed values and the permits on the street from the city's or
 * the county's own records: three cities by readers written for them, any other by the platform it publishes on (Socrata, ArcGIS, CKAN),
 * found once per city and checked by the street. Each fact carries its source and the day it was read; what no sensor answers is unknown, never guessed.
 */
public final class HomeSensors {
    private HomeSensors() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** A plain fetch, the body or null; a test puts its own here. */
    static volatile Function<String, String> FETCH = CareerSources::get;
    /** A fetch of bytes (a GTFS zip), or null; a test puts its own here. */
    static volatile Function<String, byte[]> BYTES = HomeSensors::fetchBytes;

    /** The facts as an object, and the same as lines for the model and the page. */
    public record Sheet(ObjectNode facts, String text) { }

    public static Sheet sheet(LibraryStore store, Home.Listing l) {
        ObjectNode f = M.createObjectNode();
        List<String> lines = new ArrayList<>();
        String today = LocalDate.now().toString();
        double lat = l.lat(), lon = l.lon();
        String tract = "", county = "", placedZip = "";
        if (!l.address().isEmpty()) {
            Placed g = place(l.address(), l.city(), l.state());
            if (g != null) { if (lat == 0 || lon == 0) { lat = g.lat(); lon = g.lon(); } tract = g.tract(); county = g.county(); placedZip = g.zip(); }
        }
        if (lat != 0 && lon != 0) { f.put("lat", lat).put("lon", lon); if (!tract.isEmpty()) f.put("tract", tract); }
        else lines.add("Where it is: unknown; the Census geocoder did not place the address, so the flood zone, the schools and the stops could not be read.");
        // the flood zone
        if (lat != 0) {
            String zone = flood(lat, lon);
            f.put("flood_zone", zone);
            lines.add("Flood zone: " + (zone.isEmpty() ? "unknown; FEMA's flood layer did not answer" : zone + " (FEMA NFHL, " + today + ")"));
        }
        // the schools around
        if (lat != 0) {
            List<String> schools = schools(lat, lon, l.city(), l.state(), 6);
            ArrayNode a = f.putArray("schools"); for (String s : schools) a.add(s);
            lines.add("Schools within about 1.5 km: " + (schools.isEmpty() ? "none found in the federal directory for that city, or it did not answer" : String.join("; ", schools) + " (NCES directory 2022 via the Urban Institute, " + today + ")"));
        }
        // the stops around
        if (lat != 0) {
            List<String> stops = stops(store, lat, lon, 5);
            ArrayNode a = f.putArray("stops"); for (String s : stops) a.add(s);
            lines.add("Nearest transit stops: " + (stops.isEmpty() ? "none within 1.2 km in the feeds that cover the point, or no feed covers it" : String.join("; ", stops) + " (GTFS from the Mobility Database catalogue, " + today + ")"));
        }
        // the street's records, where a city's portal answers
        String street = street(l.address());
        int number = houseNumber(l.address());
        List<String> prices = List.of(), permits = List.of();
        ArrayNode pa = f.putArray("prices_paid"); for (String p : prices) pa.add(p);
        ArrayNode pe = f.putArray("permits"); for (String p : permits) pe.add(p);
        String reader = recordReader(l.city(), l.state(), county), salesReader = reader, permitsReader = reader;
        if (!reader.isEmpty()) { prices = pricesPaid(l.city(), l.state(), county, street, number); permits = permits(l.city(), l.state(), county, street, number); }
        HomeRecords.Found found = null;
        if (reader.isEmpty() && !street.isEmpty()) {   // no reader written for the city: its records by the platform it publishes on
            String zip = !l.zip().isBlank() ? l.zip() : placedZip;
            found = HomeRecords.find(store, l.city(), l.state(), county, lat, lon, street, number, zip, true);   // a person's card: the county researched when the platforms give nothing
            if (found.sales() != null) { salesReader = found.sales().reader(); prices = HomeRecords.prices(found.sales(), street, number, l.city(), zip); }
            if (found.permits() != null) { permitsReader = found.permits().reader(); permits = HomeRecords.permits(found.permits(), street, number, l.city(), zip); }
            ArrayNode lk = f.putArray("records_looked"); for (String x : found.looked()) lk.add(x);
        }
        // the one line that says which source answered, or that none is known and what was looked at
        String recordsLine = HomeRecordsLine.line(l.city(), l.state(), county, reader, found == null ? null : found.sales(), found == null ? null : found.permits(), found == null ? List.of() : found.looked(), found == null ? "" : found.lookup(), found == null ? "" : found.mapFile());
        f.put("records_line", recordsLine);
        lines.add(recordsLine);
        lines.add("Sales or values on the street: " + (salesReader.isEmpty() ? "unknown; no dataset of sales or values with addresses answered for " + l.city() + " on Socrata, ArcGIS or CKAN" : prices.isEmpty() ? "none found on " + street + " (" + salesReader + ", " + today + ")" : String.join("; ", prices) + " (" + salesReader + ", " + today + ")"));
        lines.add("Permits on the street: " + (permitsReader.isEmpty() ? "unknown; no dataset of permits with addresses answered for " + l.city() + " on Socrata, ArcGIS or CKAN" : permitsReader.startsWith("WPRDC") ? "unknown; the county's portal has no permit records the library reads" : permits.isEmpty() ? "none found on " + street + " (" + permitsReader + ", " + today + ")" : String.join("; ", permits) + " (" + permitsReader + ", " + today + ")"));
        if (f.has("tract")) {   // the tract's figures from the Census Bureau's survey, with a key; without one the line says where a free one comes from
            HomeCensus.Figures cf = HomeCensus.tract(store, f.path("tract").asText());
            if (cf != null) f.putObject("census").put("year", cf.year()).put("income", cf.income()).put("age", cf.age()).put("owners", cf.owners()).put("renters", cf.renters()).put("value", cf.value()).put("rent", cf.rent());
            lines.add(HomeCensus.line(f.path("tract").asText(), cf));
        }
        f.put("at", today);
        return new Sheet(f, String.join("\n", lines));
    }

    /** The records of one address: the line that says which source answered, the rows on the street, the tract's figures, and what was looked at. */
    public record Records(String place, String line, List<String> sales, List<String> permits, String census, List<String> looked) { }

    /**
     * The records of an address given as "street, city, state" (a zip may follow the state): the same reading a home's card makes, for
     * the records op, the command, the page's form and the MCP tool. The county is researched when the platforms give nothing, as for a card.
     */
    public static Records records(LibraryStore store, String address) {
        String[] parts = (address == null ? "" : address).split(",");
        if (parts.length < 2 || parts[0].isBlank()) throw new IllegalArgumentException("Give the address as street, city, state, for example 108 Example St, Cambridge, MA.");
        String streetPart = parts[0].strip(), city, stateZip;
        if (parts.length >= 3) { city = parts[1].strip(); stateZip = String.join(" ", Arrays.asList(parts).subList(2, parts.length)).strip(); }
        else {
            String[] cs = parts[1].strip().split("\\s+");
            int cut = cs.length - 1; while (cut > 0 && cs[cut].matches("\\d{5}(-\\d{4})?")) cut--;
            if (cut < 1) throw new IllegalArgumentException("Give the address as street, city, state, for example 108 Example St, Cambridge, MA.");
            city = String.join(" ", Arrays.asList(cs).subList(0, cut)); stateZip = String.join(" ", Arrays.asList(cs).subList(cut, cs.length));
        }
        Matcher sz = Pattern.compile("^([A-Za-z]{2})\\.?(?:\\s+(\\d{5})(?:-\\d{4})?)?\\s*$").matcher(stateZip);
        if (!sz.matches() || city.isEmpty()) throw new IllegalArgumentException("Give the state as its two letters after the city, for example 108 Example St, Cambridge, MA.");
        String state = sz.group(1).toUpperCase(Locale.ROOT), zip = sz.group(2) == null ? "" : sz.group(2);
        Placed p = place(streetPart, city, state);
        String county = p == null ? "" : p.county();
        double lat = p == null ? 0 : p.lat(), lon = p == null ? 0 : p.lon();
        if (zip.isEmpty() && p != null) zip = p.zip() == null ? "" : p.zip();
        String street = street(streetPart); int number = houseNumber(streetPart);
        String reader = recordReader(city, state, county);
        List<String> prices = List.of(), permits = List.of();
        HomeRecords.Found found = null;
        if (!reader.isEmpty()) { prices = pricesPaid(city, state, county, street, number); permits = permits(city, state, county, street, number); }
        else if (!street.isEmpty()) {
            found = HomeRecords.find(store, city, state, county, lat, lon, street, number, zip, true);
            if (found.sales() != null) prices = HomeRecords.prices(found.sales(), street, number, city, zip);
            if (found.permits() != null) permits = HomeRecords.permits(found.permits(), street, number, city, zip);
        }
        String line = HomeRecordsLine.line(city, state, county, reader, found == null ? null : found.sales(), found == null ? null : found.permits(), found == null ? List.of() : found.looked(), found == null ? "" : found.lookup(), found == null ? "" : found.mapFile());
        String census = p == null || p.tract() == null || p.tract().isEmpty() ? "" : HomeCensus.line(p.tract(), HomeCensus.tract(store, p.tract()));
        return new Records(HomeRecordsLine.place(city, state, county), line, prices, permits, census, found == null ? List.of() : found.looked());
    }

    /** The records in sentences, for a person: the line, then the rows on the street, then the tract's figures. */
    public static String recordsText(Records r) {
        StringBuilder b = new StringBuilder(r.line());
        b.append("\nSales or values on the street: ").append(r.sales().isEmpty() ? "none found." : String.join("; ", r.sales().subList(0, Math.min(8, r.sales().size()))) + (r.sales().size() > 8 ? "; and " + (r.sales().size() - 8) + " more." : "."));
        b.append("\nPermits on the street: ").append(r.permits().isEmpty() ? "none found." : String.join("; ", r.permits().subList(0, Math.min(8, r.permits().size()))) + (r.permits().size() > 8 ? "; and " + (r.permits().size() - 8) + " more." : "."));
        if (!r.census().isEmpty()) b.append("\n").append(r.census());
        return b.toString();
    }

    /** "272 East 3 Street #4" → "EAST 3 STREET": the street for a portal's search, the number and the unit off. */
    static String street(String address) {
        String a = address == null ? "" : address.strip();
        a = a.replaceFirst("^\\d+[A-Za-z]?\\s+", "").replaceFirst("(?i)\\s*(#|apt|unit|suite|ste)\\.?\\s*\\w+$", "").strip();
        return a.toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- the sensors

    /** Where the Census geocoder puts an address: the point, the tract and the county. */
    record Placed(double lat, double lon, String tract, String county, String zip) {
        Placed(double lat, double lon, String tract, String county) { this(lat, lon, tract, county, ""); }
    }

    /** The Census geocoder, no key: {lat, lon, tract} or null. */
    static double[] geocode(String address, String city, String state) {
        Placed p = place(address, city, state);
        return p == null ? null : new double[]{p.lat(), p.lon(), p.tract().isEmpty() ? 0 : Double.parseDouble(p.tract())};
    }

    /** Where the map's place search puts an address, {lat, lon} or null; a test puts its own here. */
    static volatile Function<String, double[]> MAP_POINT = HomeSensors::pointFromMap;

    /**
     * Where the address is: the Census geocoder first, with the tract and the county; when it does not place the address (it misses some,
     * a city hall in Maine among them), the map's own place search places it and the Census says which tract and county hold the point.
     * Null when neither places it.
     */
    static Placed place(String address, String city, String state) {
        String q = address + ", " + city + ", " + state;
        String body = get("https://geocoding.geo.census.gov/geocoder/geographies/onelineaddress?address=" + enc(q) + "&benchmark=Public_AR_Current&vintage=Current_Current&format=json");
        if (body != null) {
            try {
                JsonNode m = M.readTree(body).path("result").path("addressMatches");
                if (m.isArray() && !m.isEmpty()) {
                    JsonNode first = m.get(0);
                    double lon = first.path("coordinates").path("x").asDouble(0), lat = first.path("coordinates").path("y").asDouble(0);
                    Matcher z = Pattern.compile("\\b(\\d{5})\\s*$").matcher(first.path("matchedAddress").asText(""));
                    if (lat != 0) return new Placed(lat, lon, geography(first.path("geographies"), "Census Tracts", "GEOID"), geography(first.path("geographies"), "Counties", "NAME"), z.find() ? z.group(1) : "");
                }
            } catch (IOException ignored) { }
        }
        double[] p = null;
        try { p = MAP_POINT.apply(q); } catch (RuntimeException ignored) { }
        if (p == null || p.length < 2 || p[0] == 0) return null;
        String tract = "", county = "";
        String g = get("https://geocoding.geo.census.gov/geocoder/geographies/coordinates?x=" + p[1] + "&y=" + p[0] + "&benchmark=Public_AR_Current&vintage=Current_Current&format=json");
        if (g != null) { try { JsonNode gs = M.readTree(g).path("result").path("geographies"); tract = geography(gs, "Census Tracts", "GEOID"); county = geography(gs, "Counties", "NAME"); } catch (IOException ignored) { } }
        return new Placed(p[0], p[1], tract, county);
    }

    static String geography(JsonNode geographies, String layer, String field) {
        JsonNode a = geographies.path(layer);
        return a.isArray() && !a.isEmpty() ? a.get(0).path(field).asText("") : "";
    }

    /** The map's place search (OpenStreetMap's Nominatim) for an address, kept a month with the library's other map answers. */
    static double[] pointFromMap(String q) {
        String body = CareerRegisters.MapPlaces.cachedPlace("osm-addr-" + CareerSources.id(q.toLowerCase(Locale.ROOT)) + ".json", CareerRegisters.BASE.get("nominatim") + "/search?format=jsonv2&limit=1&q=" + enc(q));
        if (body == null) return null;
        try {
            JsonNode a = M.readTree(body);
            if (!a.isArray() || a.isEmpty()) return null;
            double lat = a.get(0).path("lat").asDouble(0), lon = a.get(0).path("lon").asDouble(0);
            return lat == 0 ? null : new double[]{lat, lon};
        } catch (IOException e) { return null; }
    }

    /** FEMA's flood layer at the point: the zone ("X", "AE"), with its subtype when given; "" when it did not answer. */
    static String flood(double lat, double lon) {
        String body = get("https://hazards.fema.gov/arcgis/rest/services/public/NFHL/MapServer/28/query?geometry=" + lon + "," + lat + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=FLD_ZONE,ZONE_SUBTY&returnGeometry=false&f=json");
        if (body == null) return "";
        try {
            JsonNode fs = M.readTree(body).path("features");
            if (!fs.isArray() || fs.isEmpty()) return "not in a mapped flood zone";
            JsonNode a = fs.get(0).path("attributes");
            String sub = a.path("ZONE_SUBTY").asText("");
            return a.path("FLD_ZONE").asText("") + (sub.isEmpty() || sub.equals("null") ? "" : " (" + sub.toLowerCase(Locale.ROOT) + ")");
        } catch (IOException e) { return ""; }
    }

    /** The schools around the point from the federal directory, which answers by state (its city filter is not applied), a page at a time; the nearest first. */
    static List<String> schools(double lat, double lon, String city, String state, int max) {
        String url = "https://educationdata.urban.org/api/v1/schools/ccd/directory/2022/?state_location=" + enc(state.toUpperCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        List<double[]> near = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (int page = 0; page < 8 && url != null && !url.isEmpty(); page++) {
            String body = get(url);
            if (body == null) break;
            try {
                JsonNode j = M.readTree(body);
                for (JsonNode s : j.path("results")) {
                    double la = s.path("latitude").asDouble(0), lo = s.path("longitude").asDouble(0);
                    if (la == 0 || lo == 0) continue;
                    double km = km(lat, lon, la, lo);
                    if (km > 1.5) continue;
                    near.add(new double[]{km}); names.add(s.path("school_name").asText("") + " (" + Math.round(km * 1000) + " m, " + level(s) + ")");
                }
                url = j.path("next").isNull() ? null : j.path("next").asText("");
            } catch (IOException e) { break; }
        }
        List<Integer> idx = new ArrayList<>(); for (int i = 0; i < names.size(); i++) idx.add(i);
        idx.sort((x, y) -> Double.compare(near.get(x)[0], near.get(y)[0]));
        for (int i : idx) { if (out.size() >= max) break; out.add(names.get(i)); }
        return out;
    }

    static String level(JsonNode s) {
        int lo = s.path("lowest_grade_offered").asInt(-9), hi = s.path("highest_grade_offered").asInt(-9);
        if (lo == -9 && hi == -9) return "a school";
        return (lo <= 0 ? "K" : String.valueOf(lo)) + "–" + (hi < 0 ? "?" : String.valueOf(hi));
    }

    /** The nearest stops within 1.2 km from the GTFS feeds whose box holds the point: up to six, the tightest boxes first, a box wider than a region left out (Amtrak's is the continent), each feed kept a month. */
    static List<String> stops(LibraryStore store, double lat, double lon, int max) {
        List<String> out = new ArrayList<>();
        Path cache = store.root().resolve("catalog").resolve("home-cache");
        try {
            Files.createDirectories(cache);
            Path cat = cache.resolve("feeds_v2.csv");
            if (!Files.isRegularFile(cat) || Files.getLastModifiedTime(cat).toMillis() < System.currentTimeMillis() - 31L * 86_400_000) {
                String body = get("https://files.mobilitydatabase.org/feeds_v2.csv");
                if (body != null) Files.writeString(cat, body, StandardCharsets.UTF_8);
            }
            if (!Files.isRegularFile(cat)) return out;
            List<String[]> feeds = new ArrayList<>();
            String[] header = null;
            for (String line : Files.readAllLines(cat, StandardCharsets.UTF_8)) {
                String[] c = csv(line);
                if (header == null) { header = c; continue; }
                if (c.length < header.length) continue;
                if (!col(header, c, "data_type").equals("gtfs")) continue;
                String status = col(header, c, "status");
                if (status.equals("inactive") || status.equals("deprecated") || status.equals("future")) continue;
                try {
                    double minLat = Double.parseDouble(col(header, c, "location.bounding_box.minimum_latitude")), maxLat = Double.parseDouble(col(header, c, "location.bounding_box.maximum_latitude"));
                    double minLon = Double.parseDouble(col(header, c, "location.bounding_box.minimum_longitude")), maxLon = Double.parseDouble(col(header, c, "location.bounding_box.maximum_longitude"));
                    if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) continue;
                    if ((maxLat - minLat) * (maxLon - minLon) > 3.0) continue;   // a national or a cross-country feed: its stops are not a neighbourhood's
                    String url = col(header, c, "urls.latest").isEmpty() ? col(header, c, "urls.direct_download") : col(header, c, "urls.latest");
                    if (url.isEmpty()) continue;
                    // the tightest boxes first: a city's feed before a state's or a country's
                    feeds.add(new String[]{col(header, c, "id"), col(header, c, "provider"), url, String.valueOf((maxLat - minLat) * (maxLon - minLon))});
                } catch (NumberFormatException ignored) { }
            }
            feeds.sort((a, b) -> Double.compare(Double.parseDouble(a[3]), Double.parseDouble(b[3])));
            List<double[]> dist = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (String[] fd : feeds.subList(0, Math.min(6, feeds.size()))) {
                Path zip = cache.resolve("gtfs-" + fd[0].replaceAll("[^A-Za-z0-9_-]", "_") + ".zip");
                if (!Files.isRegularFile(zip) || Files.getLastModifiedTime(zip).toMillis() < System.currentTimeMillis() - 31L * 86_400_000) {
                    byte[] bytes = BYTES.apply(fd[2]);
                    if (bytes == null || bytes.length > 120L * 1024 * 1024) continue;
                    Files.write(zip, bytes);
                }
                String stopsTxt = entry(Files.readAllBytes(zip), "stops.txt");
                if (stopsTxt == null) continue;
                String[] h = null;
                for (String line : stopsTxt.split("\r?\n")) {
                    String[] c = csv(line);
                    if (h == null) { h = c; continue; }
                    if (c.length < h.length) continue;
                    try {
                        double la = Double.parseDouble(col(h, c, "stop_lat")), lo = Double.parseDouble(col(h, c, "stop_lon"));
                        double km = km(lat, lon, la, lo);
                        if (km > 1.2) continue;
                        dist.add(new double[]{km}); names.add(col(h, c, "stop_name") + " (" + Math.round(km * 1000) + " m, " + fd[1] + ")");
                    } catch (NumberFormatException ignored) { }
                }
            }
            List<Integer> idx = new ArrayList<>(); for (int i = 0; i < names.size(); i++) idx.add(i);
            idx.sort((x, y) -> Double.compare(dist.get(x)[0], dist.get(y)[0]));
            for (int i : idx) { if (out.size() >= max) break; if (!out.contains(names.get(i))) out.add(names.get(i)); }
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    /** Which portal the library has a reader written for, by the city or the county; "" when none. */
    static String recordReader(String city, String state) { return recordReader(city, state, ""); }

    static String recordReader(String city, String state, String county) {
        String c = city.toLowerCase(Locale.ROOT), st = state.toUpperCase(Locale.ROOT), co = county == null ? "" : county.toLowerCase(Locale.ROOT);
        if (st.equals("NY") && (c.equals("new york") || c.equals("brooklyn") || c.equals("queens") || c.equals("bronx") || c.equals("manhattan") || c.equals("staten island") || co.equals("new york county") || co.equals("kings county") || co.equals("queens county") || co.equals("bronx county") || co.equals("richmond county"))) return "NYC Open Data: the city's rolling sales and DOB permits";
        if (st.equals("MA") && (c.equals("boston") || co.equals("suffolk county"))) return "Analyze Boston: the city's property assessment and permits";
        if (st.equals("PA") && (c.equals("pittsburgh") || co.equals("allegheny county"))) return "WPRDC: Allegheny County's property sales";
        if (st.equals("PA") && (c.equals("philadelphia") || co.equals("philadelphia county"))) return "Philadelphia's OPA property records and L&I permits (Carto)";
        return "";
    }

    /** Sales on the street from the city's portal: NYC's rolling sales; Boston's assessed values (the city publishes values, not sales); Allegheny's sales. The nearest house numbers first. */
    static List<String> pricesPaid(String city, String state, String street, int number) { return pricesPaid(city, state, "", street, number); }

    static List<String> pricesPaid(String city, String state, String county, String street, int number) {
        List<String> out = new ArrayList<>();
        if (street.isEmpty()) return out;
        String reader = recordReader(city, state, county);
        List<int[]> dist = new ArrayList<>(); List<String> lines = new ArrayList<>();
        try {
            if (reader.startsWith("NYC")) {
                String body = get("https://data.cityofnewyork.us/resource/usep-8jbt.json?$where=upper(address)%20like%20%27%25" + enc(street) + "%25%27&$order=sale_date%20DESC&$limit=80");
                if (body == null) return out;
                for (JsonNode r : M.readTree(body)) {
                    long price = money(r.path("sale_price").asText(""));
                    if (price < 10_000) continue;
                    String addr = r.path("address").asText("").strip();
                    lines.add(addr + ": $" + String.format(Locale.ROOT, "%,d", price) + " on " + r.path("sale_date").asText("").replaceFirst("T.*$", "") + (r.path("gross_square_feet").asText("").isEmpty() || money(r.path("gross_square_feet").asText("")) == 0 ? "" : ", " + money(r.path("gross_square_feet").asText("")) + " sq ft"));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            } else if (reader.startsWith("Analyze Boston")) {
                String res = bostonResource("property assessment");
                if (res.isEmpty()) return out;
                String body = get("https://data.boston.gov/api/3/action/datastore_search?resource_id=" + res + "&q=" + enc(street) + "&limit=80");
                if (body == null) return out;
                for (JsonNode r : M.readTree(body).path("result").path("records")) {
                    long value = money(r.path("TOTAL_VALUE").asText(r.path("AV_TOTAL").asText("")));
                    if (value < 10_000) continue;
                    String addr = (r.path("ST_NUM").asText("") + " " + r.path("ST_NAME").asText("") + " " + r.path("ST_NAME_SUF").asText("")).replace("null", "").replaceAll("\\s+", " ").strip();
                    long area = money(r.path("LIVING_AREA").asText("")); String built = r.path("YR_BUILT").asText("").replaceFirst("\\.0$", "");
                    lines.add(addr + ": assessed at $" + String.format(Locale.ROOT, "%,d", value) + (r.path("LU").asText("").isEmpty() ? "" : " (" + r.path("LU").asText("")) + (built.isEmpty() || built.equals("0") ? "" : ", built " + built) + (area > 0 ? ", " + String.format(Locale.ROOT, "%,d", area) + " sq ft" : "") + (r.path("LU").asText("").isEmpty() ? "" : ")"));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            } else if (reader.startsWith("Philadelphia")) {   // the city's open data runs on Carto: one SQL call to the assessor's table, the sale and the market value on each parcel
                String sql = "SELECT location, sale_price, sale_date, market_value, year_built, total_livable_area, category_code_description FROM opa_properties_public WHERE location ILIKE '%" + HomeRecords.core(street).replace("'", "") + "%' ORDER BY sale_date DESC NULLS LAST LIMIT 120";
                String body = get("https://phl.carto.com/api/v2/sql?q=" + enc(sql));
                if (body == null) return out;
                for (JsonNode r : M.readTree(body).path("rows")) {
                    String addr = r.path("location").asText("").strip();
                    if (!HomeRecords.onStreet(addr, street)) continue;
                    long price = money(r.path("sale_price").asText("")), value = money(r.path("market_value").asText(""));
                    if (price < 10_000 && value < 10_000) continue;
                    String built = r.path("year_built").asText("").replaceFirst("\\.0$", ""), kind = r.path("category_code_description").asText("").strip().toLowerCase(Locale.ROOT);
                    long area = money(r.path("total_livable_area").asText(""));
                    lines.add(addr + ": " + (price >= 10_000 ? "$" + String.format(Locale.ROOT, "%,d", price) + " on " + HomeRecords.day(r.path("sale_date").asText("")) + (value >= 10_000 ? ", market value $" + String.format(Locale.ROOT, "%,d", value) : "") : "market value $" + String.format(Locale.ROOT, "%,d", value))
                            + (kind.isEmpty() ? "" : " (" + kind + ")") + (built.matches("1[6-9]\\d\\d|20\\d\\d") ? ", built " + built : "") + (area > 0 ? ", " + String.format(Locale.ROOT, "%,d", area) + " sq ft" : ""));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            } else if (reader.startsWith("WPRDC")) {
                String body = get("https://data.wprdc.org/api/3/action/datastore_search?resource_id=5bbe6c55-bce6-4edb-9d04-68edeb6bf7b1&q=" + enc(street) + "&limit=80");
                if (body == null) return out;
                for (JsonNode r : M.readTree(body).path("result").path("records")) {
                    long price = money(r.path("PRICE").asText(r.path("SALEPRICE").asText("")));
                    if (price < 10_000) continue;
                    String addr = r.path("FULL_ADDRESS").asText("").strip().replaceFirst(",.*$", ""), desc = r.path("SALEDESC").asText("").strip().toLowerCase(Locale.ROOT);
                    lines.add(addr + ": $" + String.format(Locale.ROOT, "%,d", price) + " on " + r.path("SALEDATE").asText("").replaceFirst("T.*$", "") + (desc.isEmpty() ? "" : " (" + desc + ")"));
                    dist.add(new int[]{Math.abs(r.path("PROPERTYHOUSENUM").asInt(houseNumber(addr)) - number)});
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return nearest(lines, dist, 8);
    }

    /** Permits on the street from the city's portal: NYC's DOB permits, Boston's permits; WPRDC has none the library reads. The nearest house numbers first. */
    static List<String> permits(String city, String state, String street, int number) { return permits(city, state, "", street, number); }

    static List<String> permits(String city, String state, String county, String street, int number) {
        List<String> out = new ArrayList<>();
        if (street.isEmpty()) return out;
        String reader = recordReader(city, state, county);
        List<int[]> dist = new ArrayList<>(); List<String> lines = new ArrayList<>();
        try {
            if (reader.startsWith("NYC")) {
                String body = get("https://data.cityofnewyork.us/resource/ipu4-2q9a.json?$where=upper(street_name)%20like%20%27%25" + enc(street) + "%25%27%20AND%20issuance_date%20IS%20NOT%20NULL&$order=issuance_date%20DESC&$limit=60");
                if (body == null) return out;
                for (JsonNode r : M.readTree(body)) {
                    String addr = (r.path("house__").asText("") + " " + r.path("street_name").asText("")).strip();
                    lines.add(addr + ": " + (r.path("job_type").asText("") + " " + r.path("work_type").asText("")).strip() + " permit, " + r.path("issuance_date").asText("").replaceFirst("T.*$", ""));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            } else if (reader.startsWith("Philadelphia")) {
                String sql = "SELECT address, permitissuedate, permitdescription, typeofwork FROM permits WHERE address ILIKE '%" + HomeRecords.core(street).replace("'", "") + "%' ORDER BY permitissuedate DESC NULLS LAST LIMIT 120";
                String body = get("https://phl.carto.com/api/v2/sql?q=" + enc(sql));
                if (body == null) return out;
                for (JsonNode r : M.readTree(body).path("rows")) {
                    String addr = r.path("address").asText("").strip();
                    if (!HomeRecords.onStreet(addr, street)) continue;
                    lines.add(addr + ": " + (r.path("permitdescription").asText("") + (r.path("typeofwork").asText("").isEmpty() ? "" : ", " + r.path("typeofwork").asText(""))).strip() + ", " + HomeRecords.day(r.path("permitissuedate").asText("")));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            } else if (reader.startsWith("Analyze Boston")) {
                String body = get("https://data.boston.gov/api/3/action/datastore_search?resource_id=6ddcd912-32a0-43df-9908-63574f8c7e77&q=" + enc(street) + "&limit=60");
                if (body == null) return out;
                for (JsonNode r : M.readTree(body).path("result").path("records")) {
                    String addr = r.path("address").asText("").strip();
                    lines.add(addr + ": " + r.path("description").asText("") + ", " + r.path("issued_date").asText("").replaceFirst("T.*$", "") + (r.path("worktype").asText("").isEmpty() ? "" : " (" + r.path("worktype").asText("") + ")"));
                    dist.add(new int[]{Math.abs(houseNumber(addr) - number)});
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return nearest(lines, dist, 8);
    }

    /** The lines whose house numbers are nearest the home's, {@code max} of them. */
    static List<String> nearest(List<String> lines, List<int[]> dist, int max) {
        List<Integer> idx = new ArrayList<>(); for (int i = 0; i < lines.size(); i++) idx.add(i);
        idx.sort((x, y) -> Integer.compare(dist.get(x)[0], dist.get(y)[0]));
        List<String> out = new ArrayList<>();
        for (int i : idx) { if (out.size() >= max) break; if (!out.contains(lines.get(i))) out.add(lines.get(i)); }
        return out;
    }

    /** "272 East 3 Street" → 272; 0 when the address has no number. */
    static int houseNumber(String address) {
        Matcher m = Pattern.compile("^\\s*(\\d{1,6})").matcher(address == null ? "" : address);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** "456,000" or "2150000" or "$1,200" → the number; 0 when there is none. */
    static long money(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("(\\d[\\d,]*)(?:\\.\\d+)?").matcher(s);
        if (!m.find()) return 0;
        try { return Long.parseLong(m.group(1).replace(",", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** The newest resource of a Boston open-data package: the last-modified of those the portal's datastore serves; "" when the portal did not answer. */
    static String bostonResource(String packageWords) {
        String body = get("https://data.boston.gov/api/3/action/package_search?q=" + enc(packageWords) + "&rows=1");
        if (body == null) return "";
        try {
            JsonNode res = M.readTree(body).path("result").path("results");
            if (!res.isArray() || res.isEmpty()) return "";
            String best = "", bestWhen = "";
            for (JsonNode r : res.get(0).path("resources")) {
                if (!r.path("datastore_active").asBoolean(false)) continue;
                String when = r.path("last_modified").asText(r.path("created").asText(""));
                Matcher fy = Pattern.compile("fy(\\d{4})").matcher(r.path("url").asText(""));
                if (fy.find()) when = fy.group(1) + "|" + when;   // the fiscal year in the file's name outranks the day it was touched
                if (bestWhen.isEmpty() || when.compareTo(bestWhen) > 0) { best = r.path("id").asText(""); bestWhen = when; }
            }
            return best;
        } catch (IOException e) { return ""; }
    }

    static String get(String url) { try { return FETCH.apply(url); } catch (RuntimeException e) { return null; } }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    static byte[] fetchBytes(String url) {
        try {
            var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
            var r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).header("User-Agent", "researchzosho.org (research library)").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }

    static String entry(byte[] zip, String name) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) if (e.getName().equals(name) || e.getName().endsWith("/" + name)) return new String(z.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
        return null;
    }

    static String[] csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
            else if (ch == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    static String col(String[] header, String[] row, String name) {
        for (int i = 0; i < header.length && i < row.length; i++) if (header[i].strip().replace("﻿", "").equals(name)) return row[i].strip();
        return "";
    }

    static double km(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
