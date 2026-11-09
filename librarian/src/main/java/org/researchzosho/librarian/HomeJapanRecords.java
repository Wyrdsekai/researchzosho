package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.SearchTally;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * What the public records say of a home in Japan, read by sensors before any model, from the ministry's 不動産情報ライブラリ (MLIT, with
 * the person's own key, free by application): the prices paid for homes of the kind in the same district over the latest quarters
 * (不動産取引価格情報, API XIT001, one fetch per municipality and quarter, kept with the library), the nearest 地価公示 point with its
 * price per square metre and its change on the year (XPT002), and whether the point lies in a flood inundation area, a sediment disaster
 * warning area or a disaster danger zone (XKT026, XKT029, XKT016). Where the home is comes from the Geospatial Information Authority's
 * address search, which needs no key. The key goes only in the request header, to the ministry's host; nothing writes it down. Without a
 * key the card says so in one sentence and carries the listing alone. The ministry's API terms (第7条) ask that a service using it show the
 * credit sentence {@link #CREDIT}, so every sheet carries it.
 */
public final class HomeJapanRecords {
    private HomeJapanRecords() { }
    private static final ObjectMapper M = new ObjectMapper();

    public static final String KEY = "RESEARCHZOSHO_REINFOLIB_KEY";
    public static final String NAME = "不動産情報ライブラリ";
    public static final String TALLY = "reinfolib";
    static final String BASE = "https://www.reinfolib.mlit.go.jp/ex-api/external/";
    /** The sentence the ministry's API terms ask every service using the API to show (API規約 第7条, read 2026-11-05). */
    public static final String CREDIT = "このサービスは、国土交通省の不動産情報ライブラリのAPI機能を使用していますが、提供情報の最新性、正確性、完全性等が保証されたものではありません";
    /** How many of the latest quarters with records a card reads. */
    static final int QUARTERS = 4;

    /** The key as set; a test puts its own here. */
    static volatile Supplier<String> KEY_VALUE = () -> Config.get(KEY);
    /** The API read with the key in its header: the address (no key in it) to the JSON body, "" when the service has nothing for it, null when it did not answer; a test puts its own here. */
    static volatile Function<String, String> API = HomeJapanRecords::fetch;
    /** Where an address is, {lat, lon} or null; a test puts its own here. */
    static volatile Function<String, double[]> GEOCODE = HomeJapanRecords::geocode;

    public static boolean keySet() { String k = KEY_VALUE.get(); return k != null && !k.isBlank(); }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Object PACE = new Object();
    private static long lastCall;

    /** One call to the ministry's API, the key in its header and nowhere else, a second apart as its manual asks, counted in the tally. */
    static String fetch(String url) {
        String key = KEY_VALUE.get();
        if (key == null || key.isBlank()) return null;
        synchronized (PACE) {
            long wait = lastCall + 1000 - System.currentTimeMillis();
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; } }
            lastCall = System.currentTimeMillis();
        }
        SearchTally.count(TALLY, 1);
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Ocp-Apim-Subscription-Key", key.strip()).header("User-Agent", Fetch.USER_AGENT).header("Accept-Encoding", "gzip").GET().build();
            HttpResponse<byte[]> res = HTTP.send(r, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 404) return "";
            if (res.statusCode() != 200) return null;
            byte[] body = res.body();
            if (res.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT).contains("gzip") || (body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b)) {
                try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(body))) { body = z.readAllBytes(); }
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------- the cache

    static Path cache(LibraryStore store) { return store.root().resolve("catalog").resolve("home-cache"); }

    /** The body kept under the name while younger than the days, else fetched and kept: {at, body}; null when the fetch gave nothing. */
    static String cached(LibraryStore store, String name, int days, int emptyDays, Supplier<String> fetch) {
        Path f = cache(store).resolve(name + ".json");
        try {
            if (Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                String body = j.path("body").asText("");
                int keep = body.isEmpty() ? emptyDays : days;
                if (LocalDate.parse(j.path("at").asText("2000-01-01")).plusDays(keep).isAfter(LocalDate.now())) return body;
            }
        } catch (IOException | RuntimeException stale) { /* fetched again */ }
        String body = fetch.get();
        if (body == null) return null;
        try {
            Files.createDirectories(f.getParent());
            M.writer().writeValue(f.toFile(), M.createObjectNode().put("at", LocalDate.now().toString()).put("body", body));
        } catch (IOException ignored) { }
        return body;
    }

    // ---------------------------------------------------------------- the municipalities

    /** The municipalities of a prefecture by code, in Japanese or English ("ja", "en"), kept a year; empty without a key or an answer. */
    public static Map<String, String> cities(LibraryStore store, String pref2, String lang) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!keySet() || pref2 == null || pref2.length() != 2) return out;
        String body = cached(store, "reinfolib-cities-" + pref2 + "-" + lang, 365, 1, () -> API.apply(BASE + "XIT002?area=" + pref2 + (lang.equals("en") ? "&language=en" : "")));
        if (body == null || body.isEmpty()) return out;
        try { for (JsonNode c : M.readTree(body).path("data")) out.put(c.path("id").asText(""), c.path("name").asText("")); } catch (IOException ignored) { }
        return out;
    }

    /** The code of a municipality named in Japanese ("杉並区", "横浜市中区") or English ("Suginami", "Suginami Ward") in the prefecture; "" when not found or no key. */
    public static String cityCode(LibraryStore store, String prefJa, String name) {
        HomeJapan.Prefecture p = HomeJapan.prefectureNamed(prefJa);
        if (p == null || name == null || name.isBlank()) return "";
        String n = HomeJapan.halfwidth(name).strip();
        if (HomeJapan.isJapanese(n)) {
            Map<String, String> ja = cities(store, p.code(), "ja");
            for (Map.Entry<String, String> e : ja.entrySet()) if (e.getValue().equals(n)) return e.getKey();
            for (Map.Entry<String, String> e : ja.entrySet()) if (e.getValue().endsWith(n) || n.endsWith(e.getValue())) return e.getKey();
            return "";
        }
        String bare = n.toLowerCase(Locale.ROOT).replaceAll("\\s+(ward|city|town|village)$", "").replaceAll("-(ku|shi|machi|cho|mura|son)$", "").replace("ō", "o").replace("ū", "u");
        for (Map.Entry<String, String> e : cities(store, p.code(), "en").entrySet()) {
            String v = e.getValue().toLowerCase(Locale.ROOT).replaceAll("\\s+(ward|city|town|village)$", "").replaceAll("-(ku|shi)$", "").replace("ō", "o").replace("ū", "u").replace("'", "");
            if (v.equals(bare) || v.replace(" ", "").equals(bare.replace(" ", ""))) return e.getKey();
        }
        return "";
    }

    // ---------------------------------------------------------------- the transaction prices

    /** The quarters to read, newest first: this one and the seven before it; a card keeps the first {@link #QUARTERS} with records. */
    static List<int[]> quarters(LocalDate today) {
        List<int[]> out = new ArrayList<>();
        int y = today.getYear(), q = (today.getMonthValue() - 1) / 3 + 1;
        for (int i = 0; i < 8; i++) { out.add(new int[]{y, q}); q--; if (q == 0) { q = 4; y--; } }
        return out;
    }

    /** One municipality's transaction records for one quarter, kept with the library (an empty quarter a week, since the newest fill in later); empty without a key. */
    public static List<JsonNode> transactions(LibraryStore store, String code, int year, int quarter) {
        List<JsonNode> out = new ArrayList<>();
        if (!keySet() || code == null || code.length() != 5) return out;
        String body = cached(store, "reinfolib-trades-" + code + "-" + year + "q" + quarter, 120, 7, () -> API.apply(BASE + "XIT001?year=" + year + "&quarter=" + quarter + "&city=" + code + "&priceClassification=01"));
        if (body == null || body.isEmpty()) return out;
        try { for (JsonNode r : M.readTree(body).path("data")) out.add(r); } catch (IOException ignored) { }
        return out;
    }

    /** The latest quarters' records of a municipality, newest first, and which quarters they are ("2025年第3四半期〜2026年第2四半期"). */
    public record Trades(List<JsonNode> rows, List<String> periods) {
        public String span() { return periods.isEmpty() ? "" : periods.size() == 1 ? periods.get(0) : periods.get(periods.size() - 1) + "〜" + periods.get(0); }
    }

    public static Trades latest(LibraryStore store, String code) {
        List<JsonNode> rows = new ArrayList<>(); List<String> periods = new ArrayList<>();
        for (int[] q : quarters(LocalDate.now())) {
            if (periods.size() >= QUARTERS) break;
            List<JsonNode> rs = transactions(store, code, q[0], q[1]);
            if (rs.isEmpty()) continue;
            rows.addAll(rs); periods.add(q[0] + "年第" + q[1] + "四半期");
        }
        return new Trades(rows, periods);
    }

    /** The ministry's type of a listing: a flat is 中古マンション等, a house 宅地(土地と建物), land 宅地(土地); a rental is compared with flats. */
    static String typeOf(Home.Listing l) {
        String k = HomeJapan.halfwidth(l.kind());
        if (k.contains("土地")) return "宅地(土地)";
        if (k.contains("戸建") || k.contains("一軒家") || k.contains("テラス") || k.contains("タウン")) return "宅地(土地と建物)";
        return "中古マンション等";
    }

    static long num(JsonNode r, String field) { try { String s = r.path(field).asText("").replace(",", "").strip(); return s.isEmpty() ? 0 : Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return 0; } }
    static int builtYear(JsonNode r) { Matcher m = Pattern.compile("(\\d{4})").matcher(r.path("BuildingYear").asText("")); return m.find() ? Integer.parseInt(m.group(1)) : 0; }

    /** The price per square metre a record gives: the trade price over the floor area for a flat, the ministry's unit price for land; 0 when neither. */
    static double perM2(JsonNode r) {
        long unit = num(r, "UnitPrice");
        if (unit > 0) return unit;
        long price = num(r, "TradePrice"), area = num(r, "Area");
        return price > 0 && area > 0 ? (double) price / area : 0;
    }

    /** The records that compare with a listing: its type, its district first (else the whole municipality), nearest in size first, then newest. */
    static List<JsonNode> comparable(List<JsonNode> rows, String type, String district, double areaM2, int max) {
        List<JsonNode> ofType = new ArrayList<>();
        for (JsonNode r : rows) if (r.path("Type").asText("").equals(type)) ofType.add(r);
        List<JsonNode> same = new ArrayList<>();
        if (!district.isEmpty()) for (JsonNode r : ofType) { String d = r.path("DistrictName").asText(""); if (!d.isEmpty() && (d.equals(district) || district.startsWith(d) || d.startsWith(district))) same.add(r); }
        List<JsonNode> pool = same.size() >= 3 ? same : ofType;
        pool.sort(Comparator.comparingInt((JsonNode r) -> same.contains(r) ? 0 : 1).thenComparingDouble(r -> areaM2 > 0 && num(r, "Area") > 0 ? Math.abs(num(r, "Area") - areaM2) : 1e9).thenComparing(r -> r.path("Period").asText(""), Comparator.reverseOrder()));
        return pool.subList(0, Math.min(max, pool.size()));
    }

    /** The middle price per square metre of the records of the type in the district (three or more), else in the municipality; {the median, how many, "district" or "municipality"}; null when none. */
    static Object[] median(List<JsonNode> rows, String type, String district) {
        List<Double> same = new ArrayList<>(), all = new ArrayList<>();
        for (JsonNode r : rows) {
            if (!r.path("Type").asText("").equals(type)) continue;
            double p = perM2(r); if (p <= 0) continue;
            all.add(p);
            String d = r.path("DistrictName").asText("");
            if (!district.isEmpty() && !d.isEmpty() && (d.equals(district) || district.startsWith(d) || d.startsWith(district))) same.add(p);
        }
        List<Double> pool = same.size() >= 3 ? same : all;
        if (pool.isEmpty()) return null;
        pool.sort(Double::compare);
        double med = pool.size() % 2 == 1 ? pool.get(pool.size() / 2) : (pool.get(pool.size() / 2 - 1) + pool.get(pool.size() / 2)) / 2;
        return new Object[]{med, pool.size(), pool == same ? "district" : "municipality"};
    }

    /** One record in a line: the district, the type, the 間取り, the areas, the price with its per-㎡, the structure, the year built, the quarter. */
    static String line(JsonNode r, boolean ja) {
        List<String> parts = new ArrayList<>();
        String district = r.path("DistrictName").asText(""), type = r.path("Type").asText(""), plan = r.path("FloorPlan").asText(""), structure = r.path("Structure").asText("");
        long price = num(r, "TradePrice"), area = num(r, "Area"), floorArea = num(r, "TotalFloorArea");
        int built = builtYear(r);
        parts.add(district.isEmpty() ? (ja ? "地区不明" : "district not given") : district);
        parts.add(type);
        if (!plan.isEmpty()) parts.add(plan);
        if (area > 0) parts.add(area + "㎡" + (floorArea > 0 && type.contains("建物") ? (ja ? "（延床" + floorArea + "㎡）" : " (floor area " + floorArea + " m²)") : ""));
        if (price > 0) { double per = perM2(r); parts.add(money(price, ja) + (per > 0 ? (ja ? "（" + money(per, ja) + "/㎡）" : " (" + money(per, ja) + "/㎡)") : "")); }
        if (!structure.isEmpty()) parts.add(structure);
        if (built > 0) parts.add(built + (ja ? "年築" : " built"));
        parts.add(r.path("Period").asText(""));
        return String.join(" ", parts);
    }

    // ---------------------------------------------------------------- the map: where the home is, the land price point, the hazards

    /** The Geospatial Information Authority's address search, no key: {lat, lon} or null. */
    static double[] geocode(String address) {
        String body = CareerSources.get("https://msearch.gsi.go.jp/address-search/AddressSearch?q=" + URLEncoder.encode(address, StandardCharsets.UTF_8));
        if (body == null) return null;
        try {
            JsonNode a = M.readTree(body);
            if (!a.isArray() || a.isEmpty()) return null;
            JsonNode c = a.get(0).path("geometry").path("coordinates");
            return c.size() >= 2 ? new double[]{c.get(1).asDouble(), c.get(0).asDouble()} : null;
        } catch (IOException e) { return null; }
    }

    /** Where an address is, kept a month. */
    static double[] placed(LibraryStore store, String address) {
        if (address == null || address.isBlank()) return null;
        String body = cached(store, "jp-geo-" + CareerSources.id(HomeJapan.halfwidth(address)), 31, 7, () -> {
            double[] p; try { p = GEOCODE.apply(address); } catch (RuntimeException e) { p = null; }
            return p == null ? "" : p[0] + "," + p[1];
        });
        if (body == null || body.isEmpty()) return null;
        String[] s = body.split(",");
        try { return new double[]{Double.parseDouble(s[0]), Double.parseDouble(s[1])}; } catch (RuntimeException e) { return null; }
    }

    /** The XYZ tile holding a point at a zoom: {x, y}. */
    static int[] tile(double lat, double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((lon + 180) / 360 * n);
        double latR = Math.toRadians(lat);
        int y = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
        return new int[]{Math.max(0, Math.min(n - 1, x)), Math.max(0, Math.min(n - 1, y))};
    }

    /** One tile of a map API as GeoJSON, kept half a year: the features, or empty. */
    static List<JsonNode> tileFeatures(LibraryStore store, String api, int z, double lat, double lon, String extra) {
        int[] t = tile(lat, lon, z);
        String body = cached(store, "reinfolib-tile-" + api.toLowerCase(Locale.ROOT) + "-" + z + "-" + t[0] + "-" + t[1] + (extra.isEmpty() ? "" : "-" + extra.replaceAll("[^0-9a-z]", "")), 180, 7,
                () -> API.apply(BASE + api + "?response_format=geojson&z=" + z + "&x=" + t[0] + "&y=" + t[1] + (extra.isEmpty() ? "" : "&" + extra)));
        List<JsonNode> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        try { for (JsonNode f : M.readTree(body).path("features")) out.add(f); } catch (IOException ignored) { }
        return out;
    }

    /** Whether a point lies inside a GeoJSON polygon or multipolygon, by the ray rule, holes honoured. */
    static boolean inside(JsonNode geometry, double lon, double lat) {
        String type = geometry.path("type").asText("");
        if (type.equals("Polygon")) return insidePolygon(geometry.path("coordinates"), lon, lat);
        if (type.equals("MultiPolygon")) { for (JsonNode p : geometry.path("coordinates")) if (insidePolygon(p, lon, lat)) return true; }
        return false;
    }

    static boolean insidePolygon(JsonNode rings, double lon, double lat) {
        if (!rings.isArray() || rings.isEmpty()) return false;
        if (!insideRing(rings.get(0), lon, lat)) return false;
        for (int i = 1; i < rings.size(); i++) if (insideRing(rings.get(i), lon, lat)) return false;
        return true;
    }

    static boolean insideRing(JsonNode ring, double lon, double lat) {
        boolean in = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i).get(0).asDouble(), yi = ring.get(i).get(1).asDouble(), xj = ring.get(j).get(0).asDouble(), yj = ring.get(j).get(1).asDouble();
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    /** The flood depth ranks of the ministry's data (洪水浸水想定区域, 想定最大規模), as its code list reads them. */
    static final String[] DEPTH_JA = {"", "0.5m未満（一般的な住宅では床下程度の浸水）", "0.5〜3.0m未満（床上から1階が浸水）", "3.0〜5.0m未満（2階部分が浸水）", "5.0〜10.0m未満（2階部分が水没）", "10.0〜20.0m未満", "20.0m以上"};
    static final String[] DEPTH_EN = {"", "under 0.5 m (below the floor of an ordinary house)", "0.5 to 3 m (the ground floor flooded)", "3 to 5 m (the first floor flooded)", "5 to 10 m (the first floor under water)", "10 to 20 m", "20 m or more"};

    // ---------------------------------------------------------------- the sheet

    /** The records of a listing in Japan as facts and lines, in the language of the search. */
    public static HomeSensors.Sheet sheet(LibraryStore store, Home.Listing l, String lang) {
        boolean ja = !"en".equals(lang);
        ObjectNode f = M.createObjectNode();
        List<String> lines = new ArrayList<>();
        String today = LocalDate.now().toString();
        String[] parts = HomeJapan.split(l.address());
        String pref = parts[0].isEmpty() ? l.state() : parts[0], city = parts[1].isEmpty() ? l.city() : parts[1], district = HomeJapan.district(parts[2]);
        f.put("prefecture", pref).put("city", city).put("district", district);
        double lat = l.lat(), lon = l.lon();
        if (lat == 0 || lon == 0) { double[] p = placed(store, l.address()); if (p != null) { lat = p[0]; lon = p[1]; } }
        if (lat != 0) { f.put("lat", lat).put("lon", lon); lines.add(ja ? "位置: 北緯" + String.format(Locale.ROOT, "%.5f", lat) + " 東経" + String.format(Locale.ROOT, "%.5f", lon) + "（国土地理院の住所検索、" + today + "）" : "Where it is: " + String.format(Locale.ROOT, "%.5f, %.5f", lat, lon) + " (the Geospatial Information Authority's address search, " + today + ")"); }
        else lines.add(ja ? "位置: 不明。国土地理院の住所検索が住所を見つけられなかったため、地価公示とハザード情報は読めませんでした。" : "Where it is: unknown; the Geospatial Information Authority's address search did not place the address, so the land price point and the hazard areas could not be read.");
        if (!keySet()) {
            String said = ja ? NAME + "のキー（" + KEY + "）が設定されていないため、取引価格・地価公示・ハザード情報は読んでいません。キーは国土交通省のサイトで無料で申請でき（約5営業日）、設定ページで設定します。"
                    : "No " + NAME + " key is set (" + KEY + "), so the card has no transaction prices, land price point or hazard areas; the key is free, by application on the ministry's site (about five working days), and goes on the Settings page.";
            f.put("records_line", said).put("at", today);
            lines.add(said);
            return new HomeSensors.Sheet(f, String.join("\n", lines));
        }
        String code = cityCode(store, pref, city);
        f.put("city_code", code);
        String type = typeOf(l);
        Trades t = code.isEmpty() ? new Trades(List.of(), List.of()) : latest(store, code);
        ArrayNode comp = f.putArray("comparable");
        List<String> recordsOf = new ArrayList<>();
        if (!t.rows().isEmpty()) {
            List<JsonNode> cs = comparable(t.rows(), type, district, l.extra().areaM2(), 8);
            for (JsonNode r : cs) comp.add(line(r, ja));
            Object[] med = median(t.rows(), type, district);
            String where = med == null ? "" : med[2].equals("district") ? (ja ? district : district + " (the district)") : (ja ? city + "全体" : "the whole of " + city);
            double asking = l.tenure().equals("rent") || l.price() <= 0 || l.extra().areaM2() <= 0 ? 0 : l.price() / l.extra().areaM2();
            if (med != null) {
                f.put("median_per_m2", (double) med[0]).put("median_of", (int) med[1]).put("median_where", (String) med[2]);
                lines.add(ja ? "取引価格の中央値（" + type + "、" + where + "、" + med[1] + "件、" + t.span() + "）: " + money((double) med[0], true) + "/㎡" + (asking > 0 ? "。この物件の希望価格は" + money(asking, true) + "/㎡で、中央値の" + String.format(Locale.ROOT, "%.0f", asking / (double) med[0] * 100) + "%です" : "") + "（" + NAME + "、" + today + "）"
                        : "The middle price paid per m² for " + type + " in " + where + " (" + med[1] + " sales, " + t.span() + "): " + money((double) med[0], false) + "/㎡" + (asking > 0 ? "; this home asks " + money(asking, false) + "/㎡, " + String.format(Locale.ROOT, "%.0f", asking / (double) med[0] * 100) + "% of it" : "") + " (" + NAME + ", " + today + ")");
            }
            lines.add(ja ? "近くの取引（" + type + "、" + (cs.isEmpty() ? "該当なし" : cs.size() + "件") + "）: " + String.join("； ", textList(comp)) : "Nearby sales (" + type + ", " + cs.size() + "): " + String.join("; ", textList(comp)));
            if (cs.isEmpty()) lines.set(lines.size() - 1, ja ? "近くの取引: " + city + "の" + t.span() + "の記録（" + t.rows().size() + "件）に" + type + "はありません" : "Nearby sales: none of the " + t.rows().size() + " records of " + city + " for " + t.span() + " is " + type);
            recordsOf.add(ja ? "不動産取引価格情報 " + city + " " + t.span() + " " + t.rows().size() + "件" : "transaction prices for " + city + ", " + t.span() + ", " + t.rows().size() + " records");
        } else lines.add(code.isEmpty() ? (ja ? "取引価格: " + city + "の市区町村コードが" + NAME + "の一覧にないため読めませんでした" : "Sales: the municipality " + city + " is not in the ministry's list, so none were read") : (ja ? "取引価格: " + NAME + "に" + city + "の最近の記録がありません" : "Sales: the ministry holds no recent records for " + city));
        if (lat != 0) {
            // the nearest 地価公示 point in the home's tile, this year or last
            int year = LocalDate.now().getYear();
            List<JsonNode> pts = tileFeatures(store, "XPT002", 14, lat, lon, "year=" + year + "&priceClassification=0");
            if (pts.isEmpty()) { year--; pts = tileFeatures(store, "XPT002", 14, lat, lon, "year=" + year + "&priceClassification=0"); }
            JsonNode best = null; double bestKm = 1e9;
            for (JsonNode p : pts) {
                JsonNode c = p.path("geometry").path("coordinates");
                if (c.size() < 2) continue;
                double km = HomeSensors.km(lat, lon, c.get(1).asDouble(), c.get(0).asDouble());
                if (km < bestKm) { bestKm = km; best = p; }
            }
            if (best != null) {
                JsonNode pr = best.path("properties");
                String price = pr.path("u_current_years_price_ja").asText(""), change = pr.path("year_on_year_change_rate").asText(""), use = pr.path("regulations_use_category_name_ja").asText(""), station = pr.path("nearest_station_name_ja").asText(""), dist = pr.path("u_road_distance_to_nearest_station_name_ja").asText("");
                String where = pr.path("residence_display_name_ja").asText(pr.path("location").asText(""));
                f.putObject("land_price").put("year", year).put("where", where).put("price", price).put("change", change).put("use", use).put("station", station).put("km", bestKm);
                lines.add(ja ? "地価公示 " + year + "年、最寄りの標準地（約" + Math.round(bestKm * 1000) + "m）: " + where + " " + price + (change.isEmpty() ? "" : "（前年比 " + (change.startsWith("-") ? "" : "+") + change + "%）") + (station.isEmpty() ? "" : "、最寄り駅 " + station + (dist.isEmpty() ? "" : " " + dist)) + (use.isEmpty() ? "" : "、" + use) + "（" + NAME + "、" + today + "）"
                        : "Official land price " + year + ", the nearest standard lot (about " + Math.round(bestKm * 1000) + " m away): " + where + " " + price + (change.isEmpty() ? "" : " (" + (change.startsWith("-") ? "" : "+") + change + "% on the year)") + (station.isEmpty() ? "" : ", nearest station " + station + (dist.isEmpty() ? "" : " " + dist)) + (use.isEmpty() ? "" : ", " + use) + " (" + NAME + ", " + today + ")");
                recordsOf.add(ja ? "地価公示 " + year + "年" : "official land prices " + year);
            } else lines.add(ja ? "地価公示: この区画に標準地がありません" : "Official land price: no standard lot in this tile");
            // the hazards: the flood depth, the sediment warning area, the disaster danger zone
            List<JsonNode> flood = tileFeatures(store, "XKT026", 14, lat, lon, "");
            int rank = 0; String river = "";
            for (JsonNode p : flood) if (inside(p.path("geometry"), lon, lat)) { int r = p.path("properties").path("A31a_205").asInt(0); if (r > rank) { rank = r; river = p.path("properties").path("A31a_202").asText(""); } }
            f.put("flood_rank", rank).put("flood_river", river);
            lines.add(ja ? "洪水浸水想定区域（想定最大規模）: " + (rank == 0 ? "区域外" : (river.isEmpty() ? "" : river + "、") + "浸水深ランク" + rank + "、" + DEPTH_JA[Math.min(rank, 6)]) + "（国土数値情報、" + NAME + "、" + today + "）"
                    : "Flood inundation area (largest assumed flood): " + (rank == 0 ? "outside" : (river.isEmpty() ? "" : river + ", ") + "depth rank " + rank + ", " + DEPTH_EN[Math.min(rank, 6)]) + " (National Land Numerical Information through " + NAME + ", " + today + ")");
            List<JsonNode> sediment = tileFeatures(store, "XKT029", 14, lat, lon, "");
            String zone = "";
            for (JsonNode p : sediment) if (inside(p.path("geometry"), lon, lat)) { zone = p.path("properties").path("A33_005").asText("区域内"); break; }
            f.put("sediment", zone);
            lines.add(ja ? "土砂災害警戒区域: " + (zone.isEmpty() ? "区域外" : "区域内（" + zone + "）") : "Sediment disaster warning area: " + (zone.isEmpty() ? "outside" : "inside (" + zone + ")"));
            List<JsonNode> danger = tileFeatures(store, "XKT016", 14, lat, lon, "");
            boolean inDanger = false;
            for (JsonNode p : danger) if (inside(p.path("geometry"), lon, lat)) { inDanger = true; break; }
            f.put("danger_zone", inDanger);
            lines.add(ja ? "災害危険区域: " + (inDanger ? "区域内" : "区域外") : "Disaster danger zone: " + (inDanger ? "inside" : "outside"));
            recordsOf.add(ja ? "ハザード情報（洪水・土砂・災害危険区域）" : "hazard areas (flood, sediment, danger zones)");
        }
        String recordsLine = (ja ? "記録: 国土交通省 " + NAME + "（" + String.join("、", recordsOf) + "）。" : "Records: MLIT's " + NAME + " (" + String.join("; ", recordsOf) + "). ") + CREDIT;
        f.put("records_line", recordsLine).put("credit", CREDIT).put("at", today);
        lines.add(0, recordsLine);
        return new HomeSensors.Sheet(f, String.join("\n", lines));
    }

    static List<String> textList(ArrayNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) out.add(x.asText("")); return out; }

    /** A sum in the language of the sheet: "76万円" or "¥760,000". */
    static String money(double yen, boolean ja) { return ja ? HomeJapan.yenWords(yen) : "¥" + String.format(Locale.ROOT, "%,d", Math.round(yen)); }
}
