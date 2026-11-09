package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cut of a home search: what a listing must show to pass, made from the topics the person decided and nothing else, without a
 * model. A guess orders and never drops. The place names a town with the state, province or country it is in, or a region alone, where
 * the town is on the map, and the towns within the radius (the same lookup the career field uses, with a home's radius rather than a
 * job's); the budget, the price per square foot, the bedrooms, the bathrooms, the size, the lot and the year built are each a range, with
 * one bound or both; the kind words; and what is kept out.
 */
public final class HomeCut {
    private HomeCut() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** How far from the place named still counts, when the person does not say: in kilometres. A home's radius is a town's, not a job's thirty. */
    public static final int NEAR_KM = 10;
    /** The units a price per area is said in. */
    public static final String SQ_FT = "sq ft", SQ_M = "m²";
    /** Square feet in a square metre. */
    static final double SQ_FT_PER_M2 = 10.764;

    /** A range of a figure: the least and the most that fit, 0 where there is no bound. */
    public record Range(double min, double max) {
        public static final Range OPEN = new Range(0, 0);
        public boolean open() { return min <= 0 && max <= 0; }
        public boolean holds(double v) { return (min <= 0 || v >= min) && (max <= 0 || v <= max); }
        /** The figure a listing is measured against for the order: the middle of a range, the ceiling of a budget, the floor of a floor. */
        public double mid() { return min > 0 && max > 0 ? (min + max) / 2 : max > 0 ? max : min; }
        /** How far a figure is from what the person wants, for the order: from the middle of a range, from a ceiling, from a floor; with no bound, the figure itself, so the smallest comes first; a figure not given comes last. */
        public double off(double v) { if (v <= 0) return Double.MAX_VALUE; if (min > 0 && max > 0) return Math.abs(v - mid()); if (max > 0) return Math.abs(max - v); if (min > 0) return Math.abs(v - min); return v; }
    }

    /**
     * {@code places}: the places decided, each a town with its region or a region alone, in the words {@link #places(String)} reads back;
     * {@code perArea}: the price per square foot, or per square metre when {@code perAreaUnit} is {@link #SQ_M}, as the person said it.
     */
    public record Cut(List<String> places, List<String> nearby, int nearKm, double lat, double lon, String tenure, Range price, Range perArea, String perAreaUnit, Range beds, Range baths, Range sqft, Range lot, Range built,
                      List<String> kinds, List<String> keepOut, List<String> preferPlaces, String at, String basis, String lang, Japan japan) {
        /** A cut in English, outside Japan. */
        public Cut(List<String> places, List<String> nearby, int nearKm, double lat, double lon, String tenure, Range price, Range perArea, String perAreaUnit, Range beds, Range baths, Range sqft, Range lot, Range built,
                   List<String> kinds, List<String> keepOut, List<String> preferPlaces, String at, String basis) {
            this(places, nearby, nearKm, lat, lon, tenure, price, perArea, perAreaUnit, beds, baths, sqft, lot, built, kinds, keepOut, preferPlaces, at, basis, "en", Japan.NONE);
        }
        /** A cut with no price per area. */
        public Cut(List<String> places, List<String> nearby, int nearKm, double lat, double lon, String tenure, Range price, Range beds, Range baths, Range sqft, Range lot, Range built,
                   List<String> kinds, List<String> keepOut, List<String> preferPlaces, String at, String basis) {
            this(places, nearby, nearKm, lat, lon, tenure, price, Range.OPEN, SQ_FT, beds, baths, sqft, lot, built, kinds, keepOut, preferPlaces, at, basis);
        }
        public Cut { if (lang == null || lang.isBlank()) lang = "en"; if (japan == null) japan = Japan.NONE; }
        public static final Cut NONE = new Cut(List.of(), List.of(), 0, 0, 0, "", Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, List.of(), List.of(), List.of(), "", "");
        public boolean cutsNothing() { return places.isEmpty() && tenure.isEmpty() && price.open() && perArea.open() && beds.open() && baths.open() && sqft.open() && lot.open() && built.open() && kinds.isEmpty() && japan.walk().open() && japan.floor().open(); }
        /** The money the search is in: yen in Japan, dollars elsewhere. */
        public String currency() { return japan.on() ? "JPY" : "USD"; }
        /** Whether the search is in Japan. */
        public boolean inJapan() { return japan.on(); }
        /** The most the person would pay, 0 when the budget has no ceiling. */
        public long maxPrice() { return Math.round(price.max()); }
        public long minPrice() { return Math.round(price.min()); }
        public double minBeds() { return beds.min(); }
        public double minBaths() { return baths.min(); }
        /** Whether the place is on the map: a centre to measure from. */
        public boolean placed() { return lat != 0 && lon != 0; }
        /** Whether the price per area is per square metre. */
        public boolean metric() { return SQ_M.equals(perAreaUnit) || japan.on(); }
    }

    /**
     * What a search in Japan carries beyond the American cut: the municipality's five-digit code (the portals' and the ministry's key for it;
     * "" when the lookup did not give one), its Japanese and English names, its prefecture, the walk to the station and the floor as ranges,
     * the 間取り words the person wrote, and the station named.
     */
    public record Japan(boolean on, String code, String name, String en, String pref, Range walk, Range floor, List<String> layouts, String station) {
        public static final Japan NONE = new Japan(false, "", "", "", "", Range.OPEN, Range.OPEN, List.of(), "");
        public Japan { if (walk == null) walk = Range.OPEN; if (floor == null) floor = Range.OPEN; if (layouts == null) layouts = List.of(); }
    }

    public record Verdict(boolean passes, String why) { }

    static Path file(LibraryStore store, Home.Search s) { return s.dir(store).resolve("search").resolve("cut.json"); }

    /** Where a place is on the map, {lat, lon}, or null; a test puts its own here. */
    static volatile Function<String, double[]> CENTRE = HomeCut::centreFromMap;

    /** What the cut is made from: the topics with their states and words, and what is kept out; a change makes the cut again. */
    static String basis(LibraryStore store, Home.Search s) throws IOException {
        StringBuilder b = new StringBuilder();
        for (JsonNode d : Home.wantsKept(store, s).path("dimensions")) b.append(d.path("dimension").asText()).append('|').append(d.path("state").asText()).append('|').append(d.path("want").asText()).append('\n');
        for (String k : Home.keepOut(store, s)) b.append("out|").append(k).append('\n');
        b.append("made|5\n");   // 3: a place is a town with its state, and a price per square foot is its own range; 4: a town near it counts whatever its size, and a state after a list of towns is checked; 5: a place in Japan, the walk and the floor; a cut made before is made again
        return CareerSources.id(b.toString());
    }

    /** The cut for a search: the kept one while what the person wants has not changed; else made again. */
    public static Cut current(LibraryStore store, Home.Search s) throws IOException {
        Cut c = kept(store, s);
        if (c != null && c.basis().equals(basis(store, s)) && !(c.inJapan() && c.japan().code().isEmpty() && !c.places().isEmpty())) return c;   // a cut in Japan made while the lookup did not answer is made again: the lookup is kept once it answers
        return make(store, s);
    }

    public static Cut kept(LibraryStore store, Home.Search s) throws IOException {
        Path f = file(store, s);
        if (!Files.isRegularFile(f)) return null;
        JsonNode j = M.readTree(f.toFile());
        JsonNode jp = j.path("japan");
        Japan japan = jp.isObject() ? new Japan(jp.path("on").asBoolean(false), jp.path("code").asText(""), jp.path("name").asText(""), jp.path("en").asText(""), jp.path("pref").asText(""), range(jp, "walk"), range(jp, "floor"), list(jp.path("layouts")), jp.path("station").asText("")) : Japan.NONE;
        return new Cut(list(j.path("places")), list(j.path("nearby")), j.path("near_km").asInt(0), j.path("lat").asDouble(0), j.path("lon").asDouble(0), j.path("tenure").asText(""),
                range(j, "price"), range(j, "per_area"), j.path("per_area_unit").asText(SQ_FT), range(j, "beds"), range(j, "baths"), range(j, "sqft"), range(j, "lot"), range(j, "built"),
                list(j.path("kinds")), list(j.path("keep_out")), list(j.path("prefer_places")), j.path("at").asText(""), j.path("basis").asText(""), j.path("lang").asText("en"), japan);
    }

    static Range range(JsonNode j, String name) { return new Range(j.path(name + "_min").asDouble(0), j.path(name + "_max").asDouble(0)); }
    static List<String> list(JsonNode a) { List<String> out = new ArrayList<>(); for (JsonNode x : a) out.add(x.asText("")); return out; }

    /** The cut made from the decisions: deterministic, logged, kept. */
    public static Cut make(LibraryStore store, Home.Search s) throws IOException {
        ObjectNode wants = Home.wantsKept(store, s);
        Map<String, String[]> w = new HashMap<>();
        for (JsonNode d : wants.path("dimensions")) w.put(d.path("dimension").asText(""), new String[]{d.path("state").asText("open"), d.path("want").asText("")});
        String[] place = w.getOrDefault("place", new String[]{"open", ""});
        int km = distanceKm(w.getOrDefault("distance", new String[]{"open", ""})[1] + " " + place[1]);   // "within 8 km" said with the place counts too
        Cut c = HomeJapan.looks(place[1]) ? japanCut(store, s, w, place, km) : americanCut(store, s, w, place, km);
        ObjectNode o = M.createObjectNode();
        put(o, "places", c.places()); put(o, "nearby", c.nearby()); o.put("near_km", c.nearKm()).put("lat", c.lat()).put("lon", c.lon()).put("tenure", c.tenure());
        put(o, "price", c.price()); put(o, "per_area", c.perArea()); o.put("per_area_unit", c.perAreaUnit());
        put(o, "beds", c.beds()); put(o, "baths", c.baths()); put(o, "sqft", c.sqft()); put(o, "lot", c.lot()); put(o, "built", c.built());
        put(o, "kinds", c.kinds()); put(o, "keep_out", c.keepOut()); put(o, "prefer_places", c.preferPlaces()); o.put("at", c.at()).put("basis", c.basis()).put("lang", c.lang());
        if (c.japan().on()) {
            ObjectNode jp = o.putObject("japan");
            jp.put("on", true).put("code", c.japan().code()).put("name", c.japan().name()).put("en", c.japan().en()).put("pref", c.japan().pref()).put("station", c.japan().station());
            put(jp, "walk", c.japan().walk()); put(jp, "floor", c.japan().floor()); put(jp, "layouts", c.japan().layouts());
        }
        Files.createDirectories(file(store, s).getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(file(store, s).toFile(), o);
        Home.log(store, s, c.lang().equals("ja") ? "決めた条件から検索の基準を作りました：" + summary(c) + "。" : "The library made the search's cut from what you decided: " + summary(c) + ".");
        return c;
    }

    /**
     * The cut for a place in Japan: the municipality looked up by its name in the prefecture (its code, its centre, the municipalities
     * within the radius, from Wikidata), the station named with it, the money in yen, the rooms from the 間取り, the walk and the floor.
     * The language is the ask's: Japanese words make a Japanese cut, English ones an English cut about Japan.
     */
    static Cut japanCut(LibraryStore store, Home.Search s, Map<String, String[]> w, String[] place, int km) throws IOException {
        String lang = HomeJapan.isJapanese(place[1]) || HomeJapan.isJapanese(w.getOrDefault("budget", new String[]{"", ""})[1]) ? "ja" : "en";
        boolean ja = lang.equals("ja");
        List<Place> said = distinct(HomeJapan.places(place[1]));
        String station = HomeJapan.station(place[1]);
        List<String> places = new ArrayList<>(), prefer = new ArrayList<>();
        for (Place p : said) (place[0].equals("decided") ? places : prefer).add(p.key());
        List<String> nearby = new ArrayList<>();
        double lat = 0, lon = 0;
        String code = "", name = "", en = "", pref = "";
        List<Place> towns = new ArrayList<>(); for (Place p : said) if (!p.regionAlone()) towns.add(p);
        int looked = 0;
        for (Place p : towns) {
            if (looked >= 2) break;
            looked++;
            HomeJapan.Prefecture pf = HomeJapan.prefecture(p.region());
            HomeJapan.Lookup l = HomeJapan.lookup(store, p.town(), pf == null ? "" : pf.ja(), km);
            if (l.silent()) { Home.log(store, s, ja ? "Wikidataが" + p.town() + "の照会に応答しませんでした。1分ほどおいて、もう一度検索してください。" : "Wikidata did not answer the lookup of " + p.town() + "; search again in a minute."); continue; }   // a slow service is not a wrong name
            if (!l.found()) { Home.log(store, s, ja ? p.town() + "という市区町村が見つかりませんでした。都道府県を添えて、市区町村名で書いてください（例：東京都杉並区）。" : "No municipality called " + p.town() + " was found in Japan's records; write it with its prefecture, as 杉並区, 東京都 or Suginami, Tokyo."); continue; }
            if (code.isEmpty()) { code = l.code(); name = l.ja(); en = l.en(); lat = l.lat(); lon = l.lon(); pref = pf != null ? pf.ja() : l.where().replaceAll("^.*（|）$", ""); }
            for (String x : l.towns()) if (!nearby.contains(x) && towns.stream().noneMatch(y -> HomeJapan.in(x, y.town()) || HomeJapan.in(y.town(), x))) nearby.add(x);
            Home.log(store, s, ja ? p.town() + "は" + l.where() + "として検索します（市区町村コード " + l.code() + "）。" + (l.towns().isEmpty() ? "" : "半径" + km + "km以内の" + String.join("、", l.towns().subList(0, Math.min(l.towns().size(), 30))) + (l.towns().size() > 30 ? "ほか" + (l.towns().size() - 30) + "市区町村" : "") + "も場所に含めます（Wikidata より）。")
                    : p.town() + " is taken to be " + l.where() + " (municipality code " + l.code() + ")." + (l.towns().isEmpty() ? "" : " Within " + km + " km, these count as the place too: " + String.join(", ", l.towns().subList(0, Math.min(l.towns().size(), 30))) + (l.towns().size() > 30 ? " and " + (l.towns().size() - 30) + " more" : "") + " (from Wikidata)."));
        }
        if (lat != 0) Home.log(store, s, ja ? "検索は" + name + "を中心に、半径" + km + "km以内です。" : "The search is centred on " + name + ", within " + km + " km of it.");
        String[] ten = w.getOrDefault("tenure", new String[]{"open", ""});
        String tenure = ten[0].equals("decided") ? tenureOf(ten[1]) : "";
        String budgetWords = decided(w, "budget") ? w.get("budget")[1] : "", sizeWords = decided(w, "size") ? w.get("size")[1] : "";
        PerArea pa = perAreaOf(budgetWords);
        if (pa == null) pa = perAreaOf(sizeWords);
        Range price = range(withoutPerArea(budgetWords), true);
        String bedWords = decided(w, "bedrooms") ? w.get("bedrooms")[1] : "";
        Range beds = Range.OPEN; List<String> layouts = new ArrayList<>();
        if (!bedWords.isEmpty()) {
            Range r = HomeJapan.roomsRange(bedWords);
            if (r != null) { beds = r; Matcher m = HomeJapan.LAYOUT.matcher(HomeJapan.halfwidth(bedWords)); while (m.find()) layouts.add(m.group(1).replace(" ", "").toUpperCase(Locale.ROOT) + (m.group(2) == null ? "" : m.group(2))); }
            else beds = range(bedWords, false);
        }
        Range baths = decided(w, "bathrooms") ? range(w.get("bathrooms")[1], false) : Range.OPEN;
        Range sqft = area(withoutPerArea(sizeWords)), lot = decided(w, "lot") ? area(w.get("lot")[1]) : Range.OPEN;
        Range built = decided(w, "built") ? years(w.get("built")[1]) : Range.OPEN;
        Range walk = decided(w, "walk") ? HomeJapan.walk(w.get("walk")[1]) : null, floor = decided(w, "floor") ? HomeJapan.floor(w.get("floor")[1]) : null;
        String[] kind = w.getOrDefault("kind", new String[]{"open", ""});
        List<String> kinds = kind[0].equals("decided") ? kindWords(kind[1]) : List.of();
        Japan japan = new Japan(true, code, name, en, pref, walk == null ? Range.OPEN : walk, floor == null ? Range.OPEN : floor, layouts, station);
        return new Cut(places, nearby, nearby.isEmpty() && lat == 0 ? 0 : km, lat, lon, tenure, price, pa == null ? Range.OPEN : pa.range(), pa == null ? SQ_M : pa.unit(), beds, baths, sqft, lot, built,
                kinds, Home.keepOut(store, s), prefer, LocalDate.now().toString(), basis(store, s), lang, japan);
    }

    /** The cut for a place in the United States or Canada, as before. */
    static Cut americanCut(LibraryStore store, Home.Search s, Map<String, String[]> w, String[] place, int km) throws IOException {
        List<Place> said = placesChecked(store, s, place[1], km);
        List<String> places = new ArrayList<>(), prefer = new ArrayList<>();
        for (Place p : said) (place[0].equals("decided") ? places : prefer).add(p.key());
        List<String> nearby = new ArrayList<>();
        double lat = 0, lon = 0;
        List<Place> towns = new ArrayList<>(); for (Place p : said) if (!p.regionAlone()) towns.add(p);
        if (!places.isEmpty() && !towns.isEmpty()) {
            int looked = 0;
            for (Place p : towns) {
                if (looked >= 2 || p.town().length() < 3) continue;
                looked++;
                List<String> decided = new ArrayList<>();   // which town of the name: its region, else the other places the person named
                if (!p.regionName().isEmpty()) decided.add(p.regionName());
                else for (Place x : said) if (x != p) decided.add(x.full());
                CareerCut.Towns t = CareerCut.near(store, p.town(), decided, List.of(), km);
                if (lat == 0 && t.lat() != 0) { lat = t.lat(); lon = t.lon(); }
                for (String x : t.towns()) if (nearby.stream().noneMatch(y -> y.equalsIgnoreCase(x)) && towns.stream().noneMatch(y -> y.town().equalsIgnoreCase(x))) nearby.add(x);
                if (!t.towns().isEmpty()) Home.log(store, s, "Near " + p.town() + (t.where().isEmpty() ? "" : " (" + t.where() + ")") + ", within " + km + " km, these count as the place too: " + String.join(", ", t.towns().subList(0, Math.min(t.towns().size(), 30))) + (t.towns().size() > 30 ? " and " + (t.towns().size() - 30) + " more" : "") + " (from Wikidata).");
            }
            Place first = towns.get(0);
            if (lat == 0) {   // the place on the map, from the map itself, when Wikidata did not say where the town is
                double[] c = null;
                try { c = CENTRE.apply(first.full()); } catch (RuntimeException ignored) { }
                if (c != null && c.length >= 2 && c[0] != 0) { lat = c[0]; lon = c[1]; }
            }
            if (lat != 0) Home.log(store, s, "The search is centred on " + first.words() + ", within " + km + " km of it.");
            else Home.log(store, s, "The map did not say where " + first.words() + " is, so the search goes by the town's name" + (nearby.isEmpty() ? "" : " and the towns near it") + " rather than a radius.");
        }
        String[] ten = w.getOrDefault("tenure", new String[]{"open", ""});
        String tenure = ten[0].equals("decided") ? tenureOf(ten[1]) : "";
        String budgetWords = decided(w, "budget") ? w.get("budget")[1] : "", sizeWords = decided(w, "size") ? w.get("size")[1] : "";
        PerArea pa = perAreaOf(budgetWords);   // "under $850 a square foot" is a price per square foot, never a budget of $850
        if (pa == null) pa = perAreaOf(sizeWords);
        Range price = range(withoutPerArea(budgetWords), true);
        Range beds = decided(w, "bedrooms") ? range(w.get("bedrooms")[1], false) : Range.OPEN, baths = decided(w, "bathrooms") ? range(w.get("bathrooms")[1], false) : Range.OPEN;
        Range sqft = area(withoutPerArea(sizeWords)), lot = decided(w, "lot") ? area(w.get("lot")[1]) : Range.OPEN;
        Range built = decided(w, "built") ? years(w.get("built")[1]) : Range.OPEN;
        String[] kind = w.getOrDefault("kind", new String[]{"open", ""});
        List<String> kinds = kind[0].equals("decided") ? kindWords(kind[1]) : List.of();
        return new Cut(places, nearby, nearby.isEmpty() && lat == 0 ? 0 : km, lat, lon, tenure, price, pa == null ? Range.OPEN : pa.range(), pa == null ? SQ_FT : pa.unit(), beds, baths, sqft, lot, built,
                kinds, Home.keepOut(store, s), prefer, LocalDate.now().toString(), basis(store, s));
    }

    static boolean decided(Map<String, String[]> w, String dim) { String[] x = w.get(dim); return x != null && x[0].equals("decided"); }
    static void put(ObjectNode o, String name, List<String> xs) { var a = o.putArray(name); for (String x : xs) a.add(x); }
    static void put(ObjectNode o, String name, Range r) { o.put(name + "_min", r.min()).put(name + "_max", r.max()); }

    /** Where a place is, from the map's place search (OpenStreetMap's Nominatim), kept a month with the library's other map answers. */
    static double[] centreFromMap(String place) {
        try {
            String[] a = new CareerRegisters.MapPlaces().areaOf(place);
            if (a == null || a[1].isEmpty() || a[2].isEmpty()) return null;
            return new double[]{Double.parseDouble(a[1]), Double.parseDouble(a[2])};
        } catch (IOException | NumberFormatException e) { return null; }
    }

    // ---------------------------------------------------------------- the place

    /**
     * A place the person named: a town with the state, province or country it is in, or a region alone ("anywhere in Massachusetts").
     * {@code region} is the two-letter code of a US state or a Canadian province, or a country's name, "" when none was said;
     * {@code regionName} the region's own name; {@code regionSaid} the region as the person wrote it.
     */
    public record Place(String town, String region, String regionName, String regionSaid) {
        public boolean regionAlone() { return town.isEmpty(); }
        /** The words the cut keeps the place by, which {@link #places(String)} reads back as the same place. */
        public String key() { return town.isEmpty() ? "anywhere in " + regionSaid : town + (regionSaid.isEmpty() ? "" : ", " + regionSaid); }
        /** The place as the person wrote it: "Somerville, MA", "Cambridge, Massachusetts"; a region alone by its name. */
        public String words() { return town.isEmpty() ? regionName : key(); }
        /** The place with its region's own name: "Cambridge, Massachusetts". */
        public String full() { return town.isEmpty() ? regionName : town + (regionName.isEmpty() ? "" : ", " + regionName); }
        /** What the region is, for a sentence: state, province, prefecture or country. */
        public String regionKind() { return CareerPortals.US_STATES.containsKey(region) ? "state" : PROVINCES.containsKey(region) ? "province" : region.startsWith("JP-") ? "prefecture" : "country"; }
        /** Whether the place is in Japan: its region is a prefecture, or its words are Japanese. */
        public boolean japan() { return region.startsWith("JP-") || HomeJapan.isJapanese(town) || HomeJapan.isJapanese(regionSaid); }
        Place in(String code, String name, String said) { return new Place(town, code, name, said); }
    }

    /** Canada's provinces and territories by their codes. */
    static final Map<String, String> PROVINCES = Map.ofEntries(Map.entry("AB", "Alberta"), Map.entry("BC", "British Columbia"), Map.entry("MB", "Manitoba"), Map.entry("NB", "New Brunswick"),
            Map.entry("NL", "Newfoundland and Labrador"), Map.entry("NS", "Nova Scotia"), Map.entry("NT", "Northwest Territories"), Map.entry("NU", "Nunavut"), Map.entry("ON", "Ontario"),
            Map.entry("PE", "Prince Edward Island"), Map.entry("QC", "Quebec"), Map.entry("SK", "Saskatchewan"), Map.entry("YT", "Yukon"));
    /** The short forms the press writes for a state or a province: "Cambridge, Mass.", "Annapolis, Md.". */
    static final Map<String, String> PRESS_FORMS = Map.ofEntries(Map.entry("ala", "AL"), Map.entry("ariz", "AZ"), Map.entry("ark", "AR"), Map.entry("calif", "CA"), Map.entry("colo", "CO"),
            Map.entry("conn", "CT"), Map.entry("del", "DE"), Map.entry("fla", "FL"), Map.entry("ga", "GA"), Map.entry("ill", "IL"), Map.entry("ind", "IN"), Map.entry("kan", "KS"), Map.entry("ky", "KY"),
            Map.entry("la", "LA"), Map.entry("md", "MD"), Map.entry("mass", "MA"), Map.entry("mich", "MI"), Map.entry("minn", "MN"), Map.entry("miss", "MS"), Map.entry("mo", "MO"), Map.entry("mont", "MT"),
            Map.entry("neb", "NE"), Map.entry("nev", "NV"), Map.entry("n.h", "NH"), Map.entry("n.j", "NJ"), Map.entry("n.m", "NM"), Map.entry("n.y", "NY"), Map.entry("n.c", "NC"), Map.entry("n.d", "ND"),
            Map.entry("okla", "OK"), Map.entry("ore", "OR"), Map.entry("pa", "PA"), Map.entry("r.i", "RI"), Map.entry("s.c", "SC"), Map.entry("s.d", "SD"), Map.entry("tenn", "TN"), Map.entry("vt", "VT"),
            Map.entry("va", "VA"), Map.entry("wash", "WA"), Map.entry("w.va", "WV"), Map.entry("wis", "WI"), Map.entry("wyo", "WY"), Map.entry("d.c", "DC"), Map.entry("ont", "ON"), Map.entry("que", "QC"));
    /** Names that are a state's and a big city's both: alone, the city. "Anywhere in New York" is the state. */
    static final Set<String> CITY_FIRST = Set.of("new york", "washington");
    /** The first word of a town's name that ends in a region's: "Fort Washington", "Mount Vernon", "West Virginia" is a state. */
    static final Set<String> NAME_STARTS = Set.of("mount", "mt", "fort", "ft", "port", "lake", "new", "east", "west", "north", "south", "upper", "lower", "old", "little", "big", "saint", "st", "san", "santa", "el", "la", "los", "las");

    /** The words around a place that say how far rather than where: "within 8 km of", "or the next towns over". */
    static final Pattern DISTANCE_PHRASE = Pattern.compile("(?i)\\b(?:within|up to|under|less than|no more than)?\\s*\\d+(?:\\.\\d+)?\\s*(?:km|kilomet\\w*|mi|miles?)\\b(?:\\s+(?:of|from|around))?"
            + "|\\b(?:the\\s+)?(?:next|nearby|neighbou?ring|surrounding|nearest|close|closer|further)\\s+towns?(?:\\s+over)?\\b|\\btowns?\\s+(?:nearby|around|near(?:by)?)\\b");
    /** The words that make what follows a region alone: "anywhere in Massachusetts", "the state of New York". */
    static final Pattern ALONE_WORDS = Pattern.compile("(?i)\\b(?:anywhere|everywhere|all|throughout)\\s+(?:in|of|across)\\b|\\b(?:the\\s+)?(?:whole|entire)\\s+(?:state|province|country)\\s+of\\b|\\b(?:the\\s+)?(?:state|province|commonwealth)\\s+of\\b|\\bstatewide\\b|\\banywhere\\b");
    static final Pattern FILLER = Pattern.compile("(?i)\\b(?:the|area|region|greater|metro|around|near)\\b");
    static final Pattern ZIP = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b");
    /** Between the pieces of a place: a comma, a bracket or "in" qualifies the town before; "or", "and", a semicolon or a slash starts another place. */
    static final Pattern PLACE_SEP = Pattern.compile("\\s*(,|\\(|\\)|;|/|&|\\b(?:or|Or|and|And|AND|in|In)\\b)\\s*");   // "OR" and "IN" are Oregon and Indiana
    static final Pattern COUNTY = Pattern.compile("(?i)\\b(county|parish|borough|district)\\b");
    static final Pattern DISTANCE_WORDS = Pattern.compile("\\b((?i:within|towns?|next|nearby|closer?|further|kilomet\\w*|miles?)|km|mi)\\b");   // "MI" is Michigan

    /**
     * The places in the words: "Cambridge, Massachusetts" is ONE place, the town in that state, and so are "Cambridge, MA", "Cambridge MA",
     * "Cambridge, Mass." and "Cambridge, Ontario, Canada"; "Boston or Cambridge" two; "anywhere in Massachusetts" or "Massachusetts" a state
     * alone. One state named for a list of towns is each town's ("Somerville, Medford or Cambridge, MA").
     */
    public static List<Place> places(String want) {
        if (HomeJapan.looks(want)) return distinct(HomeJapan.places(want));   // a place in Japan: a municipality with its prefecture
        List<Place> out = written(want);
        Place one = listState(out);
        if (one != null) for (int i = 0; i < out.size(); i++) if (out.get(i).region().isEmpty()) out.set(i, out.get(i).in(one.region(), one.regionName(), one.regionSaid()));   // "Somerville, Medford or Cambridge, MA"
        return distinct(out);
    }

    /** The places in the words as they are written: each town with the region written with it and no other. */
    static List<Place> written(String want) {
        List<Place> out = new ArrayList<>();
        if (want == null || want.isBlank()) return out;
        String w = DISTANCE_PHRASE.matcher(" " + want + " ").replaceAll(" ");
        w = w.replaceAll("(?i)\\bnewfoundland\\s+(?:and|&)\\s+labrador\\b", "NL");   // the one region with "and" in its name
        w = ALONE_WORDS.matcher(w).replaceAll(" ; @");
        w = FILLER.matcher(w).replaceAll(" ");
        w = ZIP.matcher(w).replaceAll(" ");
        List<String[]> pieces = new ArrayList<>();   // {the separator before it, the piece}
        Matcher m = PLACE_SEP.matcher(w);
        int at = 0; String sep = ";";
        while (m.find()) { pieces.add(new String[]{sep, w.substring(at, m.start())}); sep = m.group(1).toLowerCase(Locale.ROOT); at = m.end(); }
        pieces.add(new String[]{sep, w.substring(at)});
        int last = -1;   // the place a qualifier after it belongs to
        Set<Integer> forced = new HashSet<>();   // the regions said to be alone, which no qualifier makes a town
        for (String[] pc : pieces) {
            String piece = pc[1].replaceAll("\\s+", " ").strip();
            boolean alone = piece.startsWith("@");
            if (alone) piece = piece.substring(1).strip();
            if (piece.isEmpty()) continue;
            if (piece.length() < 2 || DISTANCE_WORDS.matcher(piece).find()) { last = -1; continue; }
            String[] r = region(piece, true);
            boolean qual = qualifier(pc[0]) && !alone;
            if (r != null && qual && last >= 0) {
                Place p = out.get(last);
                if (!p.regionAlone() && p.region().isEmpty()) { out.set(last, p.in(r[0], r[1], piece)); continue; }   // "Cambridge, Massachusetts"
                if (p.regionAlone() && r[0].length() == 2 && !forced.contains(last)) { out.set(last, new Place(p.regionSaid(), r[0], r[1], piece)); continue; }   // "Ontario, CA": a town named as a region is, in a state
                if (!p.region().isEmpty() && r[0].length() > 2) continue;   // "Cambridge, Ontario, Canada": the country of the province named
            }
            if (r == null && qual && last >= 0 && COUNTY.matcher(piece).find()) continue;   // "Cambridge, Middlesex County, MA"
            if (r != null && (alone || !CITY_FIRST.contains(piece.toLowerCase(Locale.ROOT)) || (qual && last >= 0))) {
                out.add(new Place("", r[0], r[1], piece)); last = out.size() - 1; if (alone) forced.add(last); continue;
            }
            Place t = trailing(piece);
            out.add(t != null ? t : new Place(piece, "", "", "")); last = out.size() - 1;
        }
        return out;
    }

    /** The one state, province or country the towns are written with, when there is one: written once after a list of towns, it is each town's. Null otherwise. */
    static Place listState(List<Place> ps) {
        Set<String> regions = new LinkedHashSet<>(); Place one = null;
        for (Place p : ps) if (!p.regionAlone() && !p.region().isEmpty() && regions.add(p.region())) one = p;   // a state said as a place of its own is no town's
        return regions.size() == 1 ? one : null;
    }

    static List<Place> distinct(List<Place> ps) {
        List<Place> kept = new ArrayList<>();
        for (Place p : ps) if (kept.stream().noneMatch(x -> x.key().equalsIgnoreCase(p.key()))) kept.add(p);
        return kept;
    }

    /**
     * The places in the words as {@link #places(String)} reads them, but a state written once after a list of towns is given to a listed
     * town only when Wikidata has a town of that name in that state: "Somerville, Medford or Cambridge, MA" is three towns in Massachusetts,
     * while in "Boston or Providence, RI" Boston keeps the state of the largest town of its name, Massachusetts, and the search's log says so.
     * When Wikidata does not answer, the state is given as written. The lookup is the one the towns near the place come from, kept a month.
     */
    static List<Place> placesChecked(LibraryStore store, Home.Search s, String want, int km) throws IOException {
        List<Place> out = written(want);
        Place one = listState(out);
        if (one == null) return distinct(out);
        for (int i = 0; i < out.size(); i++) {
            Place p = out.get(i);
            if (!p.region().isEmpty()) continue;
            out.set(i, p.in(one.region(), one.regionName(), one.regionSaid()));
            if (p.town().length() < 3) continue;
            CareerCut.Towns t = CareerCut.near(store, p.town(), List.of(one.regionName()), List.of(), km);
            if (t.where().isBlank() || CareerCut.has(t.where(), one.regionName())) continue;   // a town of the name is in that state, or the lookup could not say
            Place own = ownRegion(p, t.where());
            out.set(i, own);
            Home.log(store, s, "No town called " + p.town() + " was found in " + one.regionName() + ", the " + one.regionKind() + " written after the list of towns, so the search takes "
                    + p.town() + " to be the one in " + (own.region().isEmpty() ? t.where() : own.regionName()) + ", the largest town of that name. If you mean a different " + p.town()
                    + ", write its " + one.regionKind() + " right after it.");
        }
        return distinct(out);
    }

    /** The town in the region Wikidata places it in: a US state or a Canadian province first, else a country; the town alone when the words name none. */
    static Place ownRegion(Place p, String where) {
        String[] parts = where.split("\\s*[,;]\\s*");
        for (String part : parts) {
            for (Map.Entry<String, String> e : CareerPortals.US_STATES.entrySet()) if (e.getValue().equalsIgnoreCase(part.strip())) return new Place(p.town(), e.getKey(), e.getValue(), e.getKey());
            for (Map.Entry<String, String> e : PROVINCES.entrySet()) if (e.getValue().equalsIgnoreCase(part.strip())) return new Place(p.town(), e.getKey(), e.getValue(), e.getKey());
        }
        for (String part : parts) { String[] r = part.isBlank() ? null : region(part.strip(), true); if (r != null) return new Place(p.town(), r[0], r[1], r[1]); }
        return p;
    }

    /** The places kept by their words, read back. */
    public static List<Place> places(List<String> keys) { List<Place> out = new ArrayList<>(); for (String k : keys) for (Place p : places(k)) if (out.stream().noneMatch(x -> x.key().equalsIgnoreCase(p.key()))) out.add(p); return out; }

    static boolean qualifier(String sep) { return sep.equals(",") || sep.equals("(") || sep.equals(")") || sep.equals("in"); }

    /** A town with its region at its end and no comma: "Cambridge MA", "Cambridge Massachusetts", "Albany New York"; null when the end names none. */
    static Place trailing(String piece) {
        String[] w = piece.split("\\s+");
        for (int n = Math.min(3, w.length - 1); n >= 1; n--) {
            String end = String.join(" ", Arrays.asList(w).subList(w.length - n, w.length)), town = String.join(" ", Arrays.asList(w).subList(0, w.length - n));
            if (NAME_STARTS.contains(town.toLowerCase(Locale.ROOT))) continue;
            String[] r = region(end, false);
            if (r != null) return new Place(town, r[0], r[1], end);
        }
        return null;
    }

    /**
     * The region a piece names: {the code of a US state or a Canadian province, or a country's name; the region's own name}, or null. A code
     * is written in capitals ("MA", not "ma", which is a word); {@code whole} allows the press's short forms ("Mass.") and a country's
     * short forms, which a piece of its own can be and the end of a town's name cannot.
     */
    static String[] region(String piece, boolean whole) {
        String p = piece.strip(), low = p.toLowerCase(Locale.ROOT), up = p.toUpperCase(Locale.ROOT), bare = low.replaceAll("\\.$", "");
        boolean capitals = p.equals(up);
        if (up.matches("[A-Z]{2}") && (whole || capitals)) {
            if (CareerPortals.US_STATES.containsKey(up)) return new String[]{up, CareerPortals.US_STATES.get(up)};
            if (PROVINCES.containsKey(up)) return new String[]{up, PROVINCES.get(up)};
        }
        for (Map.Entry<String, String> e : CareerPortals.US_STATES.entrySet()) if (e.getValue().equalsIgnoreCase(p)) return new String[]{e.getKey(), e.getValue()};
        for (Map.Entry<String, String> e : PROVINCES.entrySet()) if (e.getValue().equalsIgnoreCase(p) || (e.getKey().equals("QC") && low.equals("québec"))) return new String[]{e.getKey(), e.getValue()};
        String press = PRESS_FORMS.get(bare);
        if (press != null && (whole || low.endsWith("."))) return new String[]{press, CareerPortals.US_STATES.getOrDefault(press, PROVINCES.getOrDefault(press, press))};
        if (whole || capitals || p.length() > 3) {
            String country = CareerCut.COUNTRY_SHORT.get(low);
            if (country == null && CareerPortals.COUNTRIES.contains(low) && !low.equals("remote")) country = Character.toUpperCase(p.charAt(0)) + p.substring(1);
            if (country == null && (low.equals("u.s.a.") || low.equals("america") || low.equals("united states of america"))) country = "United States";
            if (country != null) return new String[]{country, country};
        }
        return null;
    }

    /** A region by its code or its name, as a listing writes it: "MD" or "Maryland" is Maryland; else as written. */
    static String regionName(String st) {
        String s = st == null ? "" : st.strip(), up = s.toUpperCase(Locale.ROOT);
        return CareerPortals.US_STATES.getOrDefault(up, PROVINCES.getOrDefault(up, s));
    }

    /** The code of the region a listing writes, by its code or its name; else as written. */
    static String codeOf(String st) {
        String s = st == null ? "" : st.strip();
        String[] r = region(s, true);
        return r != null && r[0].length() == 2 ? r[0] : s.toUpperCase(Locale.ROOT);
    }

    /** Whether a listing's region is the place's: 1 it is, -1 it is another, 0 the listing does not say. */
    static int regionIs(Place p, String st) {
        String s = st == null ? "" : st.strip();
        if (s.isEmpty()) return 0;
        if (p.region().startsWith("JP-")) { String jp = HomeJapan.prefCode(s); return jp.isEmpty() ? (HomeJapan.isJapanese(s) ? -1 : 0) : jp.equals(p.region()) ? 1 : -1; }   // a prefecture, written either way
        String code = codeOf(s);
        if (p.region().length() == 2) return code.equals(p.region()) ? 1 : -1;
        if (p.region().equals("United States")) return CareerPortals.US_STATES.containsKey(code) ? 1 : -1;
        if (p.region().equals("Canada")) return PROVINCES.containsKey(code) ? 1 : -1;
        if (CareerPortals.US_STATES.containsKey(code) || PROVINCES.containsKey(code)) return -1;   // a state or a province is in neither England nor France
        return s.equalsIgnoreCase(p.region()) || s.equalsIgnoreCase(p.regionSaid()) ? 1 : 0;
    }

    /** Where a listing stands against the places: in one (1), in another (-1, with the reason), or in a town of the name whose region it does not give (0, with what is not given). */
    public record At(int in, String why) { }
    static final At IN = new At(1, "");

    /** Where a listing stands against the places decided. */
    public static At at(Cut c, Home.Listing l) { return at(c, places(c.places()), l); }

    static At at(Cut c, List<Place> ps, Home.Listing l) {
        if (ps.isEmpty()) return IN;
        if (c.placed() && c.nearKm() > 0 && l.lat() != 0 && l.lon() != 0 && HomeSensors.km(c.lat(), c.lon(), l.lat(), l.lon()) <= c.nearKm() + 0.5) return IN;   // on the map within the radius, whatever its town is called
        String town = l.city().isBlank() ? l.address() : l.city();   // the town a listing gives, not a street named for one: 12 Cambridge St is in Boston
        String elsewhere = "", unknown = "";
        for (Place p : ps) {
            if (p.regionAlone()) {
                int r = regionIs(p, l.state());
                if (r > 0) return IN;
                if (r == 0) unknown = HomeWords.of(c).regionNotGiven(p.regionKind());
                else if (elsewhere.isEmpty()) elsewhere = HomeWords.of(c).elsewhere(l.city(), regionName(l.state()), p.regionName());
                continue;
            }
            String named;
            if (p.japan()) {   // a municipality's name is written inside the listing's address: 東京都杉並区浜田山３ for 杉並区, or the lookup's own name for a town asked in English
                named = HomeJapan.in(town, p.town()) ? p.town() : !c.japan().name().isEmpty() && HomeJapan.in(town, c.japan().name()) ? c.japan().name() : null;
                if (named == null) for (String t : c.nearby()) if (HomeJapan.in(town, t)) { named = t; break; }
            } else {
                named = CareerCut.has(town, p.town()) ? p.town() : null;
                if (named == null) for (String t : c.nearby()) if (CareerCut.has(town, t)) { named = t; break; }
            }
            if (named == null) continue;
            int r = p.region().isEmpty() ? 1 : regionIs(p, l.state());
            if (r > 0) return IN;
            if (r == 0) unknown = HomeWords.of(c).regionNotGiven(p.regionKind());
            else if (elsewhere.isEmpty()) elsewhere = HomeWords.of(c).elsewhereTown(l.city().isBlank() ? named : l.city(), regionName(l.state()), named, p.regionName());
        }
        if (!unknown.isEmpty()) return new At(0, unknown);
        return new At(-1, elsewhere.isEmpty() ? HomeWords.of(c).notInPlace() : elsewhere);
    }

    /** Whether a listing shows it is in the place: the one decided, else the one the library guesses; never only a town of the same name with its state not given. */
    public static boolean placeShown(Cut c, Home.Listing l) {
        if (!c.places().isEmpty()) return at(c, l).in() > 0;
        List<Place> prefer = places(c.preferPlaces());
        return !prefer.isEmpty() && at(c, prefer, l).in() > 0;
    }

    // ---------------------------------------------------------------- the ranges, read from the words

    static final Pattern MAX_WORDS = Pattern.compile("(?i)\\b(under|up to|at most|no more than|not more than|below|max(?:imum)?|or less|or under|less than|within|top|before|by|older than|or earlier|or fewer|or smaller)\\b");
    static final Pattern MIN_WORDS = Pattern.compile("(?i)\\b(over|at least|or more|from|min(?:imum)?|more than|above|starting at|after|since|newer than|or later|or newer|or larger|or bigger|upwards)\\b|\\d\\s*\\+");
    static final Pattern ABOUT_WORDS = Pattern.compile("(?i)\\b(around|about|roughly|approximately|circa|near|close to|ish)\\b|~");
    static final Pattern EXACT_WORDS = Pattern.compile("(?i)\\b(exactly|just|precisely)\\b");
    static final Pattern JOINED = Pattern.compile("(?i)\\d\\s*[km]?\\s*(?:to|and|or|through|-|~)\\s*\\$?\\s*\\d");

    /**
     * The least and the most, read from the words. Two figures joined ("$400,000 to $550,000", "3-4", "between 1,200 and 1,800") are the
     * range. One figure with a word of a ceiling ("under", "up to", "at most") is the most; with a word of a floor ("at least", "or more",
     * "3+", "over") the least; with "around" or "about", fifteen per cent either way for money and the figure itself for a count; with
     * "exactly", the figure itself. A bare figure is the most for money and the least for a count, as the topics' own words say.
     */
    static Range range(String want, boolean money) {
        if (want == null || want.isBlank()) return Range.OPEN;
        if (HomeJapan.hasJapaneseFigures(want)) want = HomeJapan.normal(want);   // "15万円以下" is "150000 or less"
        String w = numbers(want.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-').replace("yen", " "));
        return range(w, money ? moneys(w, 100) : counts(w), money);
    }

    static final List<String> NUMBER_WORDS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve");
    static final Pattern NUMBER_WORD = Pattern.compile("\\b(" + String.join("|", NUMBER_WORDS) + ")\\b");

    /** The counts written as words written as figures: "three or four" is "3 or 4", "two or more" is "2 or more". */
    static String numbers(String w) {
        Matcher m = NUMBER_WORD.matcher(w);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, String.valueOf(NUMBER_WORDS.indexOf(m.group(1))));
        return m.appendTail(b).toString();
    }

    static Range range(String w, List<Double> ns, boolean money) {
        if (ns.isEmpty()) return Range.OPEN;
        double a = ns.get(0), b = ns.size() > 1 ? ns.get(1) : 0;
        boolean max = MAX_WORDS.matcher(w).find(), min = MIN_WORDS.matcher(w).find();
        if (ns.size() >= 2 && (JOINED.matcher(w).find() || (max && min))) return new Range(Math.min(a, b), Math.max(a, b));
        if (ABOUT_WORDS.matcher(w).find() && !max && !min) return money ? new Range(Math.round(a * 0.85), Math.round(a * 1.15)) : new Range(a, a);
        if (EXACT_WORDS.matcher(w).find()) return new Range(a, a);
        if (max && !min) return new Range(0, a);
        if (min && !max) return new Range(a, 0);
        return money ? new Range(0, a) : new Range(a, 0);
    }

    /** A size in square feet: "1,400 sq ft", "1,200 to 1,800 sq ft", "130 square metres" (made square feet), "a quarter acre" or "0.3 acres" for a lot. A bare figure is the least. */
    static Range area(String want) {
        if (want == null || want.isBlank()) return Range.OPEN;
        if (HomeJapan.isJapanese(want) || want.contains("㎡") || want.contains("m2")) want = HomeJapan.normalArea(want);   // "50㎡以上", "20坪"
        String w = want.toLowerCase(Locale.ROOT).replace("a quarter of an acre", "0.25 acre").replace("a quarter acre", "0.25 acre").replace("half an acre", "0.5 acre").replace("a half acre", "0.5 acre").replace("a third of an acre", "0.33 acre").replace("an acre", "1 acre");
        Range r = range(w, false);
        if (r.open()) return r;
        double factor = w.matches(".*\\b(acres?)\\b.*") ? 43_560 : w.matches(".*(\\bsq\\.?\\s*m\\b|\\bsquare met\\w+\\b|\\bm2\\b|m²).*") ? SQ_FT_PER_M2 : 1;   // "m²" ends in no word character, so no boundary after it
        return new Range(Math.round(r.min() * factor), Math.round(r.max() * factor));
    }

    /** A price per area as the person said it: the range, its unit, and where the words are. */
    record PerArea(Range range, String unit) { }

    static final String FIGURE = "(?:\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*[kK]?";
    static final String SQ_FT_WORDS = "sq\\.?\\s*(?:ft|feet|foot)\\.?|square\\s+(?:foot|feet)|ft²|ft2|sf";
    static final String SQ_M_WORDS = "m²|m2|sq\\.?\\s*m(?:et(?:er|re)s?)?\\.?|sqm|square\\s+met(?:er|re)s?";
    /** "under $850 a square foot", "$800/sq ft", "between $600 and $800 per square foot", "$850 psf", "€9,000 per m²": a price per area with its words of a range. */
    static final Pattern PER_AREA = Pattern.compile("(?i)(?:\\b(?:between|from|under|over|up to|at most|at least|below|above|less than|more than|no more than|not more than|no less than|max(?:imum)?|min(?:imum)?|around|about|roughly|approximately|starting at)\\s+)?"
            + "[$€£]?\\s*" + FIGURE + "(?:\\s*(?:to|and|-|–|through)\\s*[$€£]?\\s*" + FIGURE + ")?\\s*(?:dollars?\\s*)?"
            + "(?:(?:per|an?|each|/)\\s*(?:(" + SQ_M_WORDS + ")|(?:" + SQ_FT_WORDS + "))(?![\\p{L}])|psf\\b)(?:\\s+or\\s+(?:less|more|under|over|below|above)\\b)?");

    /** The price per area in the words, the first said; null when they say none. */
    /** "㎡単価80万円以下", "坪単価300万円まで", "1㎡あたり80万円": a price per square metre in Japanese (a price per 坪 made per ㎡). */
    static final Pattern PER_AREA_JA = Pattern.compile("(?:(㎡|平米|坪)\\s*単価|1\\s*(㎡|平米|坪)\\s*(?:あたり|当たり|につき))\\s*(?:は|が|で|:|：)?\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:億|万)?\\s*円?(?:\\s*(?:~|から)\\s*\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:億|万)?\\s*円?)?)\\s*(以下|以内|まで|未満|以上|から|前後|程度|くらい)?");

    static PerArea perAreaOf(String words) {
        if (words == null || words.isBlank()) return null;
        if (HomeJapan.isJapanese(words)) {
            Matcher m = PER_AREA_JA.matcher(HomeJapan.halfwidth(words));
            if (m.find()) {
                boolean tsubo = "坪".equals(m.group(1)) || "坪".equals(m.group(2));
                Range r = range(HomeJapan.normal(m.group(3) + (m.group(4) == null ? "" : m.group(4))), moneys(HomeJapan.normal(m.group(3) + (m.group(4) == null ? "" : m.group(4))), 0), true);
                if (r.open()) return null;
                if (tsubo) r = new Range(Math.round(r.min() / HomeJapan.TSUBO_M2), Math.round(r.max() / HomeJapan.TSUBO_M2));
                return new PerArea(r, SQ_M);
            }
            return null;
        }
        String w = words.replace('–', '-').replace('—', '-');
        Matcher m = PER_AREA.matcher(w);
        if (!m.find()) return null;
        String clause = m.group().toLowerCase(Locale.ROOT);
        Range r = range(clause, moneys(clause.replaceAll("(?i)(" + SQ_FT_WORDS + "|" + SQ_M_WORDS + ")", " "), 0), true);
        return r.open() ? null : new PerArea(r, m.group(1) != null ? SQ_M : SQ_FT);
    }

    /** The words without their price per area: what is left is the budget, or the size. */
    static String withoutPerArea(String words) {
        if (words == null) return "";
        if (HomeJapan.isJapanese(words)) return PER_AREA_JA.matcher(HomeJapan.halfwidth(words)).replaceAll(" ").strip();
        return PER_AREA.matcher(words.replace('–', '-').replace('—', '-')).replaceAll(" ").strip();
    }

    /** When the home was built, as years: "after 2000", "before 1950", "1920 to 1960", "the 1920s" (that decade), "2010 or later". A bare year is the earliest. */
    static Range years(String want) {
        if (want == null || want.isBlank()) return Range.OPEN;
        if (HomeJapan.isJapanese(want)) { String b = HomeJapan.normalBuilt(want, LocalDate.now().getYear()); if (b.isEmpty()) return Range.OPEN; want = b; }   // "築10年以内" is "after <ten years ago>"
        String w = want.toLowerCase(Locale.ROOT);
        String flat = w.replaceAll("\\b(1[6-9]\\d|20\\d)0s\\b", "$10");   // "the 1920s" → 1920; "1920s to 1950s" → 1920 to 1950
        Matcher d = Pattern.compile("\\b(1[6-9]\\d|20\\d)0s\\b").matcher(w);
        if (d.find() && !JOINED.matcher(flat).find() && !MIN_WORDS.matcher(flat).find() && !MAX_WORDS.matcher(flat).find()) { int from = Integer.parseInt(d.group(1)) * 10; return new Range(from, from + 9); }
        return range(flat, false);
    }

    /** The sums in the words, in order: "$400,000 to $550,000" → both; "400-550k" → the unit of the second on the first too; "$2.8k a month" → 2800; "1.2M" → 1200000. */
    static List<Double> moneys(String w) { return moneys(w, 100); }

    /** As above, with the least a figure must be to count as a sum: a budget's figures under 100 are rooms; a price per square foot's are not. */
    static List<Double> moneys(String w, double least) {
        List<Double> out = new ArrayList<>(); List<String> units = new ArrayList<>();
        Matcher m = Pattern.compile("\\$?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(k|m|million|thousand)?\\b").matcher(w);
        while (m.find()) {
            String u = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            out.add(Double.parseDouble(m.group(1).replace(",", ""))); units.add(u);
        }
        String unit = units.stream().filter(u -> !u.isEmpty()).findFirst().orElse("");
        List<Double> sums = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            double n = out.get(i); String u = units.get(i).isEmpty() && n < 1000 ? unit : units.get(i);   // "400-550k": the k is both figures'
            if (u.startsWith("k") || u.equals("thousand")) n *= 1_000; else if (u.startsWith("m")) n *= 1_000_000;
            if (n >= least && n > 0) sums.add(least >= 100 ? (double) Math.round(n) : n);   // "3 bedrooms" said with the budget is not a sum
        }
        return sums;
    }

    /** The counts in the words, in order, with a thousands comma allowed ("1,400"). */
    static List<Double> counts(String w) {
        List<Double> out = new ArrayList<>();
        Matcher m = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)").matcher(w);
        while (m.find()) out.add(Double.parseDouble(m.group(1).replace(",", "")));
        return out;
    }

    /** "$750,000" → 750000; "$2.8k a month" → 2800; "1.2M" → 1200000; 0 when no number. The largest sum in the words. */
    static long money(String want) {
        if (want == null) return 0;
        Matcher m = Pattern.compile("(?i)\\$?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(k|m|million|thousand)?").matcher(want);
        long best = 0;
        while (m.find()) {
            double n = Double.parseDouble(m.group(1).replace(",", ""));
            String u = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            if (u.startsWith("k") || u.equals("thousand")) n *= 1_000; else if (u.startsWith("m")) n *= 1_000_000;
            best = Math.max(best, Math.round(n));
        }
        return best;
    }

    static double number(String want) {
        if (want == null) return 0;
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(want);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /** "within 5 miles" → 8; "15 km" → 15; the usual ten when the words name none. */
    static int distanceKm(String want) {
        if (want == null) return NEAR_KM;
        Matcher m = Pattern.compile("(?i)(\\d{1,3}(?:\\.\\d+)?)\\s*(km|kilomet\\w*|mi\\b|miles?)").matcher(want);
        if (m.find()) { double n = Double.parseDouble(m.group(1)); return (int) Math.max(1, Math.min(150, Math.round(m.group(2).toLowerCase(Locale.ROOT).startsWith("mi") ? n * 1.609 : n))); }
        return NEAR_KM;
    }

    static String tenureOf(String want) {
        String w = want == null ? "" : want.toLowerCase(Locale.ROOT);
        if (w.contains("rent") || w.contains("lease") || w.contains("賃貸") || w.contains("借り") || w.contains("家賃")) return "rent";
        if (w.contains("buy") || w.contains("purchase") || w.contains("own") || w.contains("購入") || w.contains("買") || w.contains("売買") || w.contains("分譲")) return "sale";
        return "";
    }

    static final List<String> KINDS = List.of("house", "condo", "apartment", "townhouse", "multi-family", "land", "duplex", "loft", "studio");

    static List<String> kindWords(String want) {
        if (HomeJapan.isJapanese(want)) return HomeJapan.kindWords(want);
        List<String> out = new ArrayList<>();
        String w = want == null ? "" : want.toLowerCase(Locale.ROOT);
        for (String k : KINDS) if (w.contains(k) || (k.equals("house") && w.contains("single")) || (k.equals("multi-family") && w.contains("multi"))) out.add(k);
        return out;
    }

    /** The total price the cut allows, for a service's own filter: the budget, narrowed by the price per area times the size when both are decided. */
    public static Range priceBound(Cut c) {
        double min = c.price().min(), max = c.price().max(), per = c.metric() ? 1 / SQ_FT_PER_M2 : 1;   // the size is kept in square feet
        if (c.perArea().max() > 0 && c.sqft().max() > 0) { double m = Math.round(c.perArea().max() * per * c.sqft().max()); max = max > 0 ? Math.min(max, m) : m; }
        if (c.perArea().min() > 0 && c.sqft().min() > 0) min = Math.max(min, Math.round(c.perArea().min() * per * c.sqft().min()));
        return new Range(min, max);
    }

    // ---------------------------------------------------------------- the judge

    /**
     * Whether a listing passes the cut, and why not. Only what the person decided keeps or drops; what a listing does not say does not drop
     * it, and a town of the same name in another state is set aside with its own reason.
     */
    public static Verdict judge(Cut c, Home.Listing l) {
        HomeWords w = HomeWords.of(c);
        for (String k : c.keepOut()) {
            String kk = k.toLowerCase(Locale.ROOT);
            if (!kk.isEmpty() && (l.address().toLowerCase(Locale.ROOT).contains(kk) || l.kind().toLowerCase(Locale.ROOT).contains(kk) || l.note().toLowerCase(Locale.ROOT).contains(kk) || l.extra().name().toLowerCase(Locale.ROOT).contains(kk))) return new Verdict(false, w.keepOut(k));
        }
        if (!c.tenure().isEmpty() && !l.tenure().isEmpty() && !c.tenure().equals(l.tenure())) return new Verdict(false, w.tenure(c.tenure().equals("rent")));
        At at = at(c, l);
        if (at.in() < 0) return new Verdict(false, at.why());
        if (!c.price().open() && l.price() > 0 && !c.price().holds(l.price())) return new Verdict(false, w.costs(c.price().max() > 0 && l.price() > c.price().max(), c.price(), c.tenure().equals("rent")));
        double per = perArea(c, l);
        if (!c.perArea().open() && per > 0 && !c.perArea().holds(per)) {
            boolean more = c.perArea().max() > 0 && per > c.perArea().max();
            return new Verdict(false, w.costsPer(per, more, more ? c.perArea().max() : c.perArea().min(), c.metric()));
        }
        if (!c.beds().open() && l.beds() > 0 && !c.beds().holds(l.beds())) return new Verdict(false, w.rooms(l.beds() < c.beds().min(), l.beds() < c.beds().min() ? c.beds().min() : c.beds().max(), l.extra().layout(), c.japan().layouts()));
        if (!c.baths().open() && l.baths() > 0 && !c.baths().holds(l.baths())) return new Verdict(false, w.baths(l.baths() < c.baths().min(), l.baths() < c.baths().min() ? c.baths().min() : c.baths().max()));
        if (!c.sqft().open() && l.sqft() > 0 && !c.sqft().holds(l.sqft())) return new Verdict(false, w.size(l.sqft() < c.sqft().min(), l.sqft() < c.sqft().min() ? c.sqft().min() : c.sqft().max(), c.metric()));
        if (!c.kinds().isEmpty() && !l.kind().isEmpty() && c.kinds().stream().noneMatch(k -> kindIs(l.kind(), k))) return new Verdict(false, w.kind(l.kind()));
        Range walk = c.japan().walk(), floor = c.japan().floor();
        if (!walk.open() && l.extra().walk() > 0 && !walk.holds(l.extra().walk())) return new Verdict(false, w.walkOff(l.extra().walk(), walk));
        if (!floor.open() && l.extra().floor() > 0 && !floor.holds(l.extra().floor())) return new Verdict(false, w.floorOff(l.extra().floor(), floor));
        return new Verdict(true, "");
    }

    /** A listing's price per square foot, or per square metre when the person asked by the metre; 0 when its price or its size is not given. */
    static double perArea(Cut c, Home.Listing l) {
        if (l.price() <= 0 || l.sqft() <= 0) return 0;
        double v = (double) l.price() / l.sqft() * (c.metric() ? SQ_FT_PER_M2 : 1);
        return v >= 100 ? Math.round(v) : Math.round(v * 100) / 100.0;   // to the dollar, or the cent under a hundred, as the reason says it
    }

    static String areaWord(Cut c) { return c.metric() ? "square metre" : "square foot"; }

    /**
     * What a listing does not show of what was decided, a few words each, for a near miss: its state when the town is the one wanted, its
     * price, its price per square foot ("price per square foot not known: the price is not given"), its size, its rooms, its kind. Empty
     * when it shows all of it. A listing with any is never a fit and never set aside for it.
     */
    public static List<String> unknown(Cut c, Home.Listing l) {
        List<String> out = new ArrayList<>();
        HomeWords w = HomeWords.of(c);
        At at = at(c, l);
        if (at.in() == 0) out.add(at.why());
        boolean noPrice = l.price() <= 0, noSize = l.sqft() <= 0, per = !c.perArea().open() && (noPrice || noSize);
        if (per) out.add(w.perAreaUnknown(noPrice, noSize, c.metric()));
        if (!c.price().open() && noPrice && !per) out.add(w.priceNotGiven());
        if (!c.sqft().open() && noSize && !per) out.add(w.sizeNotGiven());
        if (!c.beds().open() && l.beds() <= 0) out.add(w.bedsNotGiven());
        if (!c.baths().open() && l.baths() <= 0) out.add(w.bathsNotGiven());
        if (!c.kinds().isEmpty() && l.kind().isBlank()) out.add(w.kindNotGiven());
        if (!c.japan().walk().open() && l.extra().walk() <= 0) out.add(w.walkNotGiven());
        if (!c.japan().floor().open() && l.extra().floor() <= 0) out.add(w.floorNotGiven());
        return out;
    }

    /**
     * How many of the things decided a listing does not show, for the order of the near misses, the fewest first: the state of a town of
     * the name, the price, the size, the bedrooms, the bathrooms, the kind. A price per square foot needs the price and the size, and each
     * counts once: a home that gives neither shows less than one that gives its price alone.
     */
    public static int notShown(Cut c, Home.Listing l) {
        int n = at(c, l).in() == 0 ? 1 : 0;
        if ((!c.price().open() || !c.perArea().open()) && l.price() <= 0) n++;
        if ((!c.sqft().open() || !c.perArea().open()) && l.sqft() <= 0) n++;
        if (!c.beds().open() && l.beds() <= 0) n++;
        if (!c.baths().open() && l.baths() <= 0) n++;
        if (!c.kinds().isEmpty() && l.kind().isBlank()) n++;
        if (!c.japan().walk().open() && l.extra().walk() <= 0) n++;
        if (!c.japan().floor().open() && l.extra().floor() <= 0) n++;
        return n;
    }

    static boolean kindIs(String listed, String wanted) {
        if (HomeJapan.isJapanese(listed) || HomeJapan.japaneseKind(wanted)) return HomeJapan.kindIs(listed, wanted);
        String a = listed.toLowerCase(Locale.ROOT), b = wanted.toLowerCase(Locale.ROOT);
        if (a.contains(b)) return true;
        if (b.equals("house")) return a.contains("single") || a.contains("detached");
        if (b.equals("condo")) return a.contains("condominium");
        if (b.equals("multi-family")) return a.contains("multi");
        return false;
    }

    /**
     * How closely a listing matches what the person wants or the library guesses, for the page's order and its headings: 0 it is in the place
     * and shows everything decided, and all of it fits; 1 it is in the place and does not show something decided, or a figure misses; 2 it
     * misses the place; 3 that, and a figure is not shown or misses. A listing that passes the cut misses only a place the library guesses.
     */
    public static int tier(Cut c, Home.Listing l) {
        boolean place;
        if (!c.places().isEmpty()) place = at(c, l).in() >= 0;   // a town of the name with its state not given is a near miss, said in what is not known
        else if (!c.preferPlaces().isEmpty()) place = at(c, places(c.preferPlaces()), l).in() > 0;
        else place = true;
        boolean all = unknown(c, l).isEmpty() && figuresHold(c, l);
        return (place ? 0 : 2) + (all ? 0 : 1);
    }

    /** Whether every figure the listing gives fits what was decided; one it does not give is not judged here. */
    static boolean figuresHold(Cut c, Home.Listing l) {
        double per = perArea(c, l);
        return (c.price().open() || l.price() <= 0 || c.price().holds(l.price())) && (c.perArea().open() || per <= 0 || c.perArea().holds(per))
                && (c.beds().open() || l.beds() <= 0 || c.beds().holds(l.beds())) && (c.baths().open() || l.baths() <= 0 || c.baths().holds(l.baths()))
                && (c.sqft().open() || l.sqft() <= 0 || c.sqft().holds(l.sqft())) && (c.kinds().isEmpty() || l.kind().isEmpty() || c.kinds().stream().anyMatch(k -> kindIs(l.kind(), k)))
                && (c.japan().walk().open() || l.extra().walk() <= 0 || c.japan().walk().holds(l.extra().walk())) && (c.japan().floor().open() || l.extra().floor() <= 0 || c.japan().floor().holds(l.extra().floor()));
    }
    public static final String[] TIERS = {"Match the place and everything you decided", "Match the place; the listing does not give everything you decided", "Miss the place the library guesses", "Miss the place the library guesses, and do not give everything you decided"};

    /** How far a listing's price is from the budget, for the order: nearest the middle of the range first, or nearest the ceiling from below; with only a price per area decided, by that; with neither, the cheapest first; a listing with no price last. */
    public static double offBudget(Cut c, Home.Listing l) { return c.price().open() && !c.perArea().open() ? c.perArea().off(perArea(c, l)) : c.price().off(l.price()); }

    // ---------------------------------------------------------------- in words

    /** The cut in a sentence's parts. */
    public static String summary(Cut c) {
        HomeWords w = HomeWords.of(c);
        List<String> parts = new ArrayList<>();
        boolean rent = c.tenure().equals("rent");
        if (!c.tenure().isEmpty()) parts.add(w.tenureWords(c.tenure()));
        if (!c.places().isEmpty()) {
            List<String> towns = new ArrayList<>(), regions = new ArrayList<>();
            for (Place p : places(c.places())) (p.regionAlone() ? regions : towns).add(p.words());
            parts.add(w.place(towns, regions, c.nearby().size(), c.nearKm(), c.placed(), c.japan().station()));
        }
        if (!c.price().open()) parts.add(w.budget(c.price(), rent));
        if (!c.perArea().open()) parts.add(w.perArea(c.perArea(), c.metric(), rent));
        if (!c.beds().open()) parts.add(w.count(c.beds(), "bedroom", c.japan().layouts()));
        if (!c.baths().open()) parts.add(w.count(c.baths(), "bathroom", List.of()));
        if (!c.sqft().open()) parts.add(w.span(c.sqft(), c.metric()));
        if (!c.lot().open()) parts.add(w.lot(c.lot(), c.metric()));
        if (!c.built().open()) parts.add(w.built(c.built()));
        if (!c.japan().walk().open()) parts.add(w.walk(c.japan().walk()));
        if (!c.japan().floor().open()) parts.add(w.floor(c.japan().floor()));
        if (!c.kinds().isEmpty()) parts.add(w.kinds(c.kinds()));
        if (!c.keepOut().isEmpty()) parts.add(w.keepingOut(c.keepOut()));
        List<String> prefer = new ArrayList<>(); for (Place p : places(c.preferPlaces())) prefer.add(p.words());
        if (parts.isEmpty()) return w.nothingDecided() + (prefer.isEmpty() ? "" : w.join(List.of("", w.firstInOrder(prefer))));
        return w.join(parts) + (prefer.isEmpty() ? "" : w.join(List.of("", w.firstInOrder(prefer))));
    }

    /** "up to $900,000", "from $400,000 to $550,000", "from $400,000". */
    static String budget(Cut c) {
        Range r = c.price();
        if (r.min() > 0 && r.max() > 0) return "from " + dollars(r.min()) + " to " + dollars(r.max());
        return r.max() > 0 ? "up to " + dollars(r.max()) : "from " + dollars(r.min());
    }
    /** "up to $850 a square foot", "from $600 to $800 a square foot", "from $600 a square metre". */
    static String perAreaWords(Cut c) {
        Range r = c.perArea();
        String unit = " a " + areaWord(c);
        if (r.min() > 0 && r.max() > 0) return "from " + dollars(r.min()) + " to " + dollars(r.max()) + unit;
        return (r.max() > 0 ? "up to " + dollars(r.max()) : "from " + dollars(r.min())) + unit;
    }
    /** The budget as a verdict names it: "$900,000", "$400,000 to $550,000", "$400,000 or more". */
    static String budgetWords(Range r) {
        if (r.min() > 0 && r.max() > 0) return dollars(r.min()) + " to " + dollars(r.max());
        return r.max() > 0 ? dollars(r.max()) : dollars(r.min()) + " or more";
    }
    /** "$750,000"; a figure under a hundred keeps its cents when it has some: "$3.25". */
    static String dollars(double n) { return n < 100 && n != Math.floor(n) ? "$" + String.format(Locale.ROOT, "%.2f", n) : "$" + String.format(Locale.ROOT, "%,d", Math.round(n)); }
    static String sqft(double n) { return String.format(Locale.ROOT, "%,d", Math.round(n)) + " sq ft"; }

    /** "3 to 4 bedrooms", "at least 3 bedrooms", "at most 2 bedrooms", "3 bedrooms". */
    static String count(Range r, String noun) {
        String a = Home.Listing.num(r.min()), b = Home.Listing.num(r.max());
        if (r.min() > 0 && r.max() > 0) return r.min() == r.max() ? a + " " + noun + (r.min() == 1 ? "" : "s") : a + " to " + b + " " + noun + "s";
        return r.min() > 0 ? "at least " + a + " " + noun + (r.min() == 1 ? "" : "s") : "at most " + b + " " + noun + (r.max() == 1 ? "" : "s");
    }

    /** "1,400 to 1,800 sq ft", "at least 1,400 sq ft", "at most 1,800 sq ft". */
    static String span(Range r, String unit) {
        String a = String.format(Locale.ROOT, "%,d", Math.round(r.min())), b = String.format(Locale.ROOT, "%,d", Math.round(r.max()));
        if (r.min() > 0 && r.max() > 0) return a + " to " + b + " " + unit;
        return r.min() > 0 ? "at least " + a + " " + unit : "at most " + b + " " + unit;
    }

    static String or(List<String> xs) { return xs.size() <= 1 ? String.join("", xs) : String.join(", ", xs.subList(0, xs.size() - 1)) + " or " + xs.get(xs.size() - 1); }
}
