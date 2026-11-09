package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The home field (docs/public/LIBRARIAN_HOWTOUSE.md §26d): a person looking for a home to buy or rent. Shaped like the career field:
 * a named search kept under {@code home/<slug>/}, what the person wants topic by topic, each decided, a guess or open, their words read
 * at once for what they settle, a cut from the decisions, listings from the sources the library can read, each on the Sources page with
 * its yield, a page in tiers, and a card per home by a bounded research run with the records read first. Nothing about any place is in
 * the code: the sources are generic readers and the portals are links.
 */
public final class Home {
    private Home() { }
    static final ObjectMapper M = new ObjectMapper();

    /** The topics of what a person wants in a home, in the order the page shows them. */
    public static final List<String> DIMENSIONS = List.of("place", "distance", "tenure", "budget", "bedrooms", "bathrooms", "size", "lot", "built", "walk", "floor", "kind", "commute", "when", "must-haves");
    static final Map<String, String> LABELS = Map.ofEntries(Map.entry("place", "Where"), Map.entry("distance", "How far from there"), Map.entry("tenure", "Buy or rent"),
            Map.entry("budget", "The budget"), Map.entry("bedrooms", "Bedrooms"), Map.entry("bathrooms", "Bathrooms"), Map.entry("size", "The size"), Map.entry("lot", "The lot"), Map.entry("built", "Built when"),
            Map.entry("walk", "Walk to the station"), Map.entry("floor", "The floor"),
            Map.entry("kind", "The kind of home"), Map.entry("commute", "The commute, to where"), Map.entry("when", "When"), Map.entry("must-haves", "Must-haves"));
    /** The topics' labels in Japanese, for a search asked in Japanese. */
    static final Map<String, String> LABELS_JA = Map.ofEntries(Map.entry("place", "場所"), Map.entry("distance", "そこからの距離"), Map.entry("tenure", "購入か賃貸か"),
            Map.entry("budget", "予算"), Map.entry("bedrooms", "間取り"), Map.entry("bathrooms", "浴室"), Map.entry("size", "広さ"), Map.entry("lot", "敷地"), Map.entry("built", "築年"),
            Map.entry("walk", "駅徒歩"), Map.entry("floor", "階"), Map.entry("kind", "種別"), Map.entry("commute", "通勤先"), Map.entry("when", "時期"), Map.entry("must-haves", "必須条件"));
    public static final List<String> STATES = List.of("decided", "leaning", "open");
    public static String label(String dimension) { return LABELS.getOrDefault(dimension, dimension); }
    /** The topic's label in the language asked: Japanese for a Japanese search. */
    public static String label(String dimension, String lang) { return "ja".equals(lang) ? LABELS_JA.getOrDefault(dimension, label(dimension)) : label(dimension); }

    /** One search for a home, by the name the person gave it ("Our move to Somerville", or their own name). */
    public record Search(String slug, String name) {
        public Path dir(LibraryStore store) { return Home.dir(store).resolve(slug); }
    }

    public static Path dir(LibraryStore store) { return store.root().resolve("home"); }

    /** The searches this library holds, by the folders with a record. */
    public static List<Search> searches(LibraryStore store) throws IOException {
        List<Search> out = new ArrayList<>();
        if (!Files.isDirectory(dir(store))) return out;
        try (Stream<Path> s = Files.list(dir(store))) {
            for (Path p : s.sorted().toList()) {
                Path rec = p.resolve("record.json");
                if (Files.isRegularFile(rec)) out.add(new Search(p.getFileName().toString(), M.readTree(rec.toFile()).path("name").asText(p.getFileName().toString())));
            }
        }
        return out;
    }

    /** The search named, by name or by its folder; with no name, the only one there is; null when there is none. */
    public static Search search(LibraryStore store, String name) throws IOException {
        List<Search> all = searches(store);
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return all.size() == 1 ? all.get(0) : null;
        for (Search s : all) if (s.name().equalsIgnoreCase(n) || s.slug().equals(LibraryStore.slug(n))) return s;
        return null;
    }

    /** A search started, or the one of that name when it exists. */
    public static Search start(LibraryStore store, String name) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) throw new IllegalArgumentException("Give the search a name: yours, or what it is for.");
        Search had = search(store, n);
        if (had != null) return had;
        Search s = new Search(LibraryStore.slug(n), n);
        Files.createDirectories(s.dir(store));
        ObjectNode rec = M.createObjectNode().put("name", n).put("started", LocalDate.now().toString());
        M.writerWithDefaultPrettyPrinter().writeValue(s.dir(store).resolve("record.json").toFile(), rec);
        log(store, s, "The search \"" + n + "\" is started. Say what you want, topic by topic, or in your own words.");
        return s;
    }

    public static String state(String typed) {
        String t = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (t.equals("guess") || t.equals("a guess")) return "leaning";
        return STATES.contains(t) ? t : "";
    }

    // ---------------------------------------------------------------- what the person wants

    static Path wantsFile(LibraryStore store, Search s) { return s.dir(store).resolve("wants.json"); }

    /** The topics as kept: an object with "dimensions", one per topic in order, each {dimension, state, want, by, from, at}. */
    public static ObjectNode wantsKept(LibraryStore store, Search s) throws IOException {
        Path f = wantsFile(store, s);
        ObjectNode o = Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
        ArrayNode dims = o.withArray("dimensions");
        for (String d : DIMENSIONS) if (find(dims, d) == null) dims.addObject().put("dimension", d).put("state", "open").put("want", "").put("by", "").put("from", "").put("at", "");
        return o;
    }

    static ObjectNode find(JsonNode dims, String dimension) {
        for (JsonNode d : dims) if (d.path("dimension").asText("").equals(dimension)) return (ObjectNode) d;
        return null;
    }

    /** One topic set: decided, leaning or open, with the want in the person's words; by whom and from what. */
    public static ObjectNode setWant(LibraryStore store, Search s, String dimension, String typedState, String want, String by, String from) throws IOException {
        String d = dimension == null ? "" : dimension.strip().toLowerCase(Locale.ROOT), st = state(typedState);
        if (!DIMENSIONS.contains(d)) throw new IllegalArgumentException("There is no topic called " + dimension + ". The topics are: " + String.join(", ", DIMENSIONS) + ".");
        if (st.isEmpty()) throw new IllegalArgumentException("The state is decided, guess or open.");
        ObjectNode o = wantsKept(store, s);
        ObjectNode e = find(o.path("dimensions"), d);
        e.put("state", st).put("want", st.equals("open") ? "" : (want == null ? "" : want.strip())).put("by", by).put("from", from).put("at", LocalDate.now().toString());
        M.writerWithDefaultPrettyPrinter().writeValue(wantsFile(store, s).toFile(), o);
        return e;
    }

    /** The decided and leaning topics, dimension → want (with "(a guess)" after a leaning one). */
    public static Map<String, String> wantsOf(LibraryStore store, Search s) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode d : wantsKept(store, s).path("dimensions")) {
            String st = d.path("state").asText("open"), w = d.path("want").asText("").strip();
            if (w.isEmpty() || st.equals("open")) continue;
            out.put(d.path("dimension").asText(""), st.equals("decided") ? w : w + " (a guess)");
        }
        return out;
    }

    /** The topics in lines for the page and the log. */
    public static String wantsText(LibraryStore store, Search s) throws IOException {
        StringBuilder b = new StringBuilder();
        for (JsonNode d : wantsKept(store, s).path("dimensions")) {
            String st = d.path("state").asText("open");
            b.append(label(d.path("dimension").asText(""))).append(": ").append(st.equals("open") ? "open" : (st.equals("decided") ? "decided, " : "a guess, ") + d.path("want").asText("")).append('\n');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- what the person keeps out

    static Path profileFile(LibraryStore store, Search s) { return s.dir(store).resolve("profile.json"); }

    static ObjectNode profile(LibraryStore store, Search s) throws IOException {
        Path f = profileFile(store, s);
        return Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
    }

    public static List<String> keepOut(LibraryStore store, Search s) throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonNode k : profile(store, s).path("keep_out")) out.add(k.asText(""));
        return out;
    }

    public static boolean addKeepOut(LibraryStore store, Search s, String words) throws IOException {
        String w = words == null ? "" : words.strip();
        if (w.isEmpty()) return false;
        ObjectNode p = profile(store, s);
        ArrayNode a = p.withArray("keep_out");
        for (JsonNode k : a) if (k.asText("").equalsIgnoreCase(w)) return false;
        a.add(w);
        M.writerWithDefaultPrettyPrinter().writeValue(profileFile(store, s).toFile(), p);
        log(store, s, "The search keeps out " + w + ".");
        return true;
    }

    public static boolean removeKeepOut(LibraryStore store, Search s, String words) throws IOException {
        ObjectNode p = profile(store, s);
        ArrayNode a = p.withArray("keep_out"), kept = M.createArrayNode();
        boolean gone = false;
        for (JsonNode k : a) { if (k.asText("").equalsIgnoreCase(words == null ? "" : words.strip())) gone = true; else kept.add(k); }
        p.set("keep_out", kept);
        M.writerWithDefaultPrettyPrinter().writeValue(profileFile(store, s).toFile(), p);
        return gone;
    }

    // ---------------------------------------------------------------- the person's words

    /**
     * What the person says in their own words, kept, and read at once with the model for the topics it settles and what it keeps out,
     * as the career field reads told words. A sentence on what was set; "" when nothing was, or no model answers (the words are kept).
     */
    public static String tell(LibraryStore store, Search s, String words) throws IOException {
        String w = words == null ? "" : words.strip();
        if (w.isEmpty()) throw new IllegalArgumentException("Write something.");
        Files.createDirectories(s.dir(store));
        Files.writeString(s.dir(store).resolve("told.md"), "## " + LocalDate.now() + "\n\n" + w + "\n\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log(store, s, "You said what you want, in your own words.");
        return settle(store, s, w);
    }

    static String settle(LibraryStore store, Search s, String words) throws IOException {
        // a Japanese ask is read by the program first: the place, buy or rent, the budget in 万円, the 間取り, the size, the age, the walk, the floor and the kind; a model is asked only for the rest
        Map<String, String> read = HomeJapan.read(words);
        List<String> readSet = new ArrayList<>();
        for (Map.Entry<String, String> e : read.entrySet()) {
            setWant(store, s, e.getKey(), "decided", e.getValue(), "told", "what you said on " + LocalDate.now());
            readSet.add(label(e.getKey(), "ja") + "：" + e.getValue());
        }
        String readSaid = readSet.isEmpty() ? "" : "あなたの言葉から読み取りました：" + String.join("；", readSet) + "。次の検索はここから始まります。";
        if (!readSaid.isEmpty()) log(store, s, readSaid);
        String modelSaid = settleWithModel(store, s, words, read.keySet());
        if (readSaid.isEmpty()) return modelSaid;
        return modelSaid.isEmpty() ? readSaid : readSaid + "\n" + modelSaid;
    }

    /** The model's reading of the words for the topics they settle, the ones in {@code already} aside; "" when none answers or nothing is settled. */
    static String settleWithModel(LibraryStore store, Search s, String words, Set<String> already) throws IOException {
        StringBuilder standing = new StringBuilder();
        for (JsonNode d : wantsKept(store, s).path("dimensions")) {
            String st = d.path("state").asText("open");
            if (!st.equals("open")) standing.append("- ").append(d.path("dimension").asText()).append(": ").append(st.equals("decided") ? "decided" : "a guess").append(", ").append(d.path("want").asText("")).append('\n');
        }
        List<String> topics = new ArrayList<>(); for (String d : DIMENSIONS) topics.add(d + " (" + label(d) + ")");
        List<String> out = keepOut(store, s);
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "A person looking for a home to buy or rent says what they want, in their own words. Read the words for the topics they settle, in a form a search through "
                + "listings can use.\n\nThe topics: " + String.join("; ", topics) + ".\n\n"
                + "One entry per topic the words settle: {\"dimension\": \"the topic, as named before its brackets\", \"state\": \"decided\" or \"leaning\", \"want\": \"the want, one short "
                + "phrase in the person's words\"}. place is the town or area with its state, province or country, as listings write it (\"Somerville, MA\"); distance is how far from there still counts, in miles or km, when "
                + "they say; tenure is \"buy\" or \"rent\"; budget is what they would pay, a figure with its unit or a range (\"$750,000\", \"$400,000 to $550,000\", \"around $450,000\", \"$2,800 a month\"), "
                + "and a price per square foot or per square metre in their words with its unit when they give one (\"under $850 a square foot\"); "
                + "bedrooms and bathrooms are how many, a figure or a range (\"3\", \"3 to 4\", \"2 or more\"); size in square feet or square metres, a figure or a range; lot is the lot's size when they say it "
                + "(\"a quarter acre\", \"5,000 sq ft\"); built is when the home was built (\"after 2000\", \"the 1920s\", \"1950 to 1980\"); walk is the most minutes on foot to the nearest station when they say (\"within 10 minutes of a station\"); "
                + "floor is the floor they want when they say (\"2nd floor or higher\"); kind is house, condo, apartment, townhouse, multi-family or land; commute is the address "
                + "or place they travel to; when is the month or season; must-haves are the things a home must have (a yard, parking, a washer). Decided when the words say it "
                + "plainly, leaning when they hedge. Words that rule something out, a street, a kind of home, a condition, go to keep_out, each in a few of the person's words. "
                + "A topic the words do not touch gets no entry.\n\nAnswer with one JSON object and nothing else: {\"topics\": [...], \"keep_out\": [...]}\n\n"
                + "DECIDED ALREADY\n" + (standing.isEmpty() ? "(nothing yet)\n" : standing)
                + "\nKEPT OUT ALREADY\n" + (out.isEmpty() ? "(nothing)\n" : "- " + String.join("\n- ", out) + "\n")
                + "\nTHEIR WORDS\n" + Fence.rule("their words") + "\n" + Fence.wrap("WORDS", words));
        String said;
        try { Researcher.Drive d = Career.DRIVE.get(); if (d == null) { if (already.isEmpty()) log(store, s, "No model answers, so what you said is only kept; set the topics on the page, or say it again when a model answers."); return ""; } said = d.classify(msgs, 1200); }
        catch (RuntimeException e) { log(store, s, "The library could not read what you said for what it decides: " + e.getMessage() + ". Your words are kept."); return ""; }
        JsonNode j = CareerInterview.parseObject(said);
        List<String> set = new ArrayList<>();
        for (JsonNode t : j.path("topics")) {
            String d = t.path("dimension").asText("").strip().toLowerCase(Locale.ROOT), st = state(t.path("state").asText("")), w = t.path("want").asText("").strip();
            if (!DIMENSIONS.contains(d) || st.isEmpty() || st.equals("open") || w.isEmpty() || already.contains(d)) continue;   // what the program read from Japanese words stands
            ObjectNode was = find(wantsKept(store, s).path("dimensions"), d);
            if (was != null && was.path("state").asText("").equals("decided") && st.equals("leaning")) continue;   // a lean never undoes a decision
            setWant(store, s, d, st, w, "told", "what you said on " + LocalDate.now());
            set.add(label(d) + ": " + (st.equals("decided") ? "decided" : "a guess") + ", " + w);
        }
        List<String> kept = new ArrayList<>();
        for (JsonNode k : j.path("keep_out")) { String w = k.asText("").strip(); if (!w.isEmpty() && addKeepOut(store, s, w)) kept.add(w); }
        if (set.isEmpty() && kept.isEmpty()) { if (already.isEmpty()) log(store, s, "What you said settles no topic; it is kept with your words."); return ""; }
        String out2 = "Read from your words: " + String.join("; ", set) + (kept.isEmpty() ? "" : (set.isEmpty() ? "" : "; ") + "kept out: " + Career.and(kept)) + ". The next search starts from this.";
        log(store, s, out2);
        return out2;
    }

    // ---------------------------------------------------------------- the listings kept

    /** A home listed somewhere: what the source said of it, and where the person can open it. */
    public record Listing(String id, String source, String tenure, String address, String city, String state, String zip, long price, double beds, double baths, int sqft, String kind,
                          String url, double lat, double lon, String listed, String firstSeen, String lastSeen, String note, Extra extra) {
        /** A listing with nothing beyond the American fields. */
        public Listing(String id, String source, String tenure, String address, String city, String state, String zip, long price, double beds, double baths, int sqft, String kind,
                       String url, double lat, double lon, String listed, String firstSeen, String lastSeen, String note) {
            this(id, source, tenure, address, city, state, zip, price, beds, baths, sqft, kind, url, lat, lon, listed, firstSeen, lastSeen, note, Extra.NONE);
        }
        public Listing { if (extra == null) extra = Extra.NONE; }

        /**
         * What a listing in Japan carries beyond the American fields: its money (JPY), the building's name, the 間取り, the size in ㎡ as
         * the portal gave it (the {@code sqft} field keeps it in square feet for the judge), the year built, the line and the station with
         * the minutes on foot, and the floor. 0 or "" where the portal did not say.
         */
        public record Extra(String currency, String name, String layout, double areaM2, int built, String line, String station, int walk, int floor) {
            public static final Extra NONE = new Extra("", "", "", 0, 0, "", "", 0, 0);
            public boolean none() { return currency.isEmpty() && name.isEmpty() && layout.isEmpty() && areaM2 == 0 && built == 0 && station.isEmpty() && walk == 0 && floor == 0; }
            /** This, with what it lacks filled from another. */
            Extra filledFrom(Extra o) {
                if (o == null || o.none()) return this;
                return new Extra(currency.isEmpty() ? o.currency : currency, name.isEmpty() ? o.name : name, layout.isEmpty() ? o.layout : layout, areaM2 > 0 ? areaM2 : o.areaM2, built > 0 ? built : o.built,
                        line.isEmpty() ? o.line : line, station.isEmpty() ? o.station : station, walk > 0 ? walk : o.walk, floor > 0 ? floor : o.floor);
            }
        }

        /** Whether the listing is in Japan: its money is yen. */
        public boolean japan() { return extra.currency().equals("JPY"); }
        public String place() { return (city.isEmpty() ? "" : city) + (state.isEmpty() ? "" : (city.isEmpty() ? "" : ", ") + state); }
        public String priceText() {
            if (price <= 0) return japan() ? "価格の記載なし" : "price not given";
            if (japan()) return HomeJapan.yenWords(price) + (tenure.equals("rent") ? "/月" : "");
            return "$" + String.format(Locale.ROOT, "%,d", price) + (tenure.equals("rent") ? " a month" : "");
        }
        /** The price per square metre of a home in Japan, in words ("85万円/㎡"); "" when the price or the size is not given, or for a rental. */
        public String perM2Text() {
            if (!japan() || tenure.equals("rent") || price <= 0 || extra.areaM2() <= 0) return "";
            return HomeJapan.yenWords(price / extra.areaM2()) + "/㎡";
        }
        /** The figures after the address, each a part: the price, the rooms, the size, and for Japan the 間取り, the age, the station and the floor. */
        public List<String> figures() {
            List<String> parts = new ArrayList<>();
            parts.add(priceText());
            if (japan()) {
                String per = perM2Text(); if (!per.isEmpty()) parts.add(per);
                if (!extra.layout().isEmpty()) parts.add(extra.layout()); else if (beds > 0) parts.add(num(beds) + "部屋");
                if (extra.areaM2() > 0) parts.add((extra.areaM2() == Math.floor(extra.areaM2()) ? String.valueOf((long) extra.areaM2()) : String.format(Locale.ROOT, "%.2f", extra.areaM2()).replaceAll("0$", "")) + "㎡");
                if (extra.built() > 0) parts.add("築" + Math.max(0, LocalDate.now().getYear() - extra.built()) + "年（" + extra.built() + "年）");
                if (!extra.station().isEmpty()) parts.add((extra.line().isEmpty() ? "" : extra.line() + " ") + extra.station() + (extra.walk() > 0 ? " 徒歩" + extra.walk() + "分" : ""));
                if (extra.floor() > 0) parts.add(extra.floor() + "階");
                if (!kind.isEmpty()) parts.add(kind);
                return parts;
            }
            if (beds > 0) parts.add(num(beds) + (beds == 1 ? " bed" : " beds"));
            if (baths > 0) parts.add(num(baths) + (baths == 1 ? " bath" : " baths"));
            if (sqft > 0) parts.add(String.format(Locale.ROOT, "%,d", sqft) + " sq ft");
            if (!kind.isEmpty()) parts.add(kind);
            return parts;
        }
        /** The address as the line names it: for Japan the building's name and the floor before the address. */
        public String addressWords() {
            if (address.isEmpty()) return japan() ? "住所の記載なし" : "an address not given";
            return japan() && !extra.name().isEmpty() ? extra.name() + "（" + address + "）" : address;
        }
        public String line() {
            StringBuilder b = new StringBuilder(addressWords());
            if (!japan() && !place().isEmpty()) b.append(", ").append(place());
            for (String f : figures()) b.append(" · ").append(f);
            return b.toString();
        }
        static String num(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }
        /** This listing seen first on one day and last on another. */
        Listing seen(String first, String last) { return new Listing(id, source, tenure, address, city, state, zip, price, beds, baths, sqft, kind, url, lat, lon, listed, first, last, note, extra); }
        /** This listing with what it lacks filled from another one of the same home: the other's figures where this gives none, the better page. */
        Listing filledFrom(Listing o) {
            return new Listing(id, source, tenure, address, city, state, zip, price > 0 ? price : o.price, beds > 0 ? beds : o.beds, baths > 0 ? baths : o.baths, sqft > 0 ? sqft : o.sqft, kind.isEmpty() ? o.kind : kind,
                    betterUrl(url, o.url), lat != 0 ? lat : o.lat, lon != 0 ? lon : o.lon, listed.isEmpty() ? o.listed : listed, firstSeen, lastSeen, note.isEmpty() ? o.note : note, extra.filledFrom(o.extra));
        }

        /** Whether the page kept is the home's own: a listing page, the page the person saved, or a search for its address already. */
        public boolean ownPage() { return !url.isBlank() && (source.equals("saved by you") || url.contains("/search?q=") || HomeSources.ownPage(url) || (japan() && url.matches(".*/(jnc|nc)_\\d+/.*"))); }   // SUUMO's room and home pages
        /** The link that opens this home: its own page, else a web search for its full address, which finds the home's own listings. */
        public String link() { return ownPage() || address.isBlank() ? url : HomeSources.findLink(address, city, state); }
        /** The page of listings the home was seen on, when that page is not its own: "" otherwise. */
        public String seenOn() { return ownPage() || address.isBlank() ? "" : url; }
        /** Whose page it was seen on, for "seen on zillow.com's page": an agent by name, else the site. */
        public String seenOnName() {
            String host = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("[/?#].*$", "");
            return source.startsWith("the web search") || source.isBlank() ? host : source;
        }
    }

    static Path listingsFile(LibraryStore store, Search s) { return s.dir(store).resolve("search").resolve("listings.json"); }

    public static Map<String, Listing> listings(LibraryStore store, Search s) throws IOException {
        Map<String, Listing> out = new LinkedHashMap<>();
        Path f = listingsFile(store, s);
        if (!Files.isRegularFile(f)) return out;
        for (JsonNode j : M.readTree(f.toFile()).path("listings")) { Listing l = of(j); out.put(l.id(), l); }
        return out;
    }

    static Listing of(JsonNode j) {
        JsonNode x = j.path("extra");
        Listing.Extra extra = x.isObject() ? new Listing.Extra(x.path("currency").asText(""), x.path("name").asText(""), x.path("layout").asText(""), x.path("area_m2").asDouble(0), x.path("built").asInt(0),
                x.path("line").asText(""), x.path("station").asText(""), x.path("walk").asInt(0), x.path("floor").asInt(0)) : Listing.Extra.NONE;
        return new Listing(j.path("id").asText(""), j.path("source").asText(""), j.path("tenure").asText("sale"), j.path("address").asText(""), j.path("city").asText(""), j.path("state").asText(""),
                j.path("zip").asText(""), j.path("price").asLong(0), j.path("beds").asDouble(0), j.path("baths").asDouble(0), j.path("sqft").asInt(0), j.path("kind").asText(""),
                j.path("url").asText(""), j.path("lat").asDouble(0), j.path("lon").asDouble(0), j.path("listed").asText(""), j.path("first_seen").asText(""), j.path("last_seen").asText(""), j.path("note").asText(""), extra);
    }

    static ObjectNode json(Listing l) {
        ObjectNode o = M.createObjectNode().put("id", l.id()).put("source", l.source()).put("tenure", l.tenure()).put("address", l.address()).put("city", l.city()).put("state", l.state()).put("zip", l.zip())
                .put("price", l.price()).put("beds", l.beds()).put("baths", l.baths()).put("sqft", l.sqft()).put("kind", l.kind()).put("url", l.url()).put("lat", l.lat()).put("lon", l.lon())
                .put("listed", l.listed()).put("first_seen", l.firstSeen()).put("last_seen", l.lastSeen()).put("note", l.note());
        if (!l.extra().none()) {
            Listing.Extra x = l.extra();
            o.putObject("extra").put("currency", x.currency()).put("name", x.name()).put("layout", x.layout()).put("area_m2", x.areaM2()).put("built", x.built()).put("line", x.line()).put("station", x.station()).put("walk", x.walk()).put("floor", x.floor());
        }
        return o;
    }

    /** The listings found this time merged into those kept: a known one keeps its first day and takes today as its last; the number new. */
    public static int keep(LibraryStore store, Search s, List<Listing> found) throws IOException {
        Map<String, Listing> all = listings(store, s);
        String today = LocalDate.now().toString();
        int added = 0;
        for (Listing l : found) {
            Listing was = all.get(l.id());
            if (was == null) { added++; all.put(l.id(), l.seen(today, today)); }
            else if (firm(was.source()) && !firm(l.source())) all.put(l.id(), was.seen(was.firstSeen(), today).filledFrom(l));   // a record keeps its figures; a snippet fills what it left blank
            else all.put(l.id(), new Listing(l.id(), was.source(), l.tenure(), l.address(), l.city(), l.state(), l.zip(), l.price(), l.beds(), l.baths(), l.sqft(), l.kind(), betterUrl(was.url(), l.url()), l.lat(), l.lon(), l.listed(), was.firstSeen(), today, l.note(), l.extra()).filledFrom(was));
        }
        ObjectNode o = M.createObjectNode(); ArrayNode a = o.putArray("listings");
        for (Listing l : all.values()) a.add(json(l));
        Files.createDirectories(listingsFile(store, s).getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(listingsFile(store, s).toFile(), o);
        return added;
    }

    /** Whether a source gives a home's figures as a record (RentCast's listing, what the person typed) rather than read out of a snippet or a page's text. */
    static boolean firm(String source) { return source != null && (source.equals("RentCast") || source.equals("saved by you")); }

    /** The page to open: the home's own page beats a page of listings, a page of listings beats a search link, and what was kept beats nothing. */
    static String betterUrl(String was, String now) {
        if (now == null || now.isEmpty()) return was;
        if (was == null || was.isEmpty()) return now;
        return rank(now) > rank(was) ? now : was;
    }

    static int rank(String url) { return url.contains("/search?q=") ? 1 : HomeSources.ownPage(url) ? 3 : 2; }

    /** A listing's id: the address and the place, else the url, as a short hash, so the same home from two sources is one. */
    public static String idOf(String address, String city, String state, String url) {
        String key = !address.isBlank() ? (address + "|" + city + "|" + state).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}|]+", " ").strip() : url.toLowerCase(Locale.ROOT);   // letters of any script: a Japanese address is not blank
        return CareerSources.id(key);
    }

    // ---------------------------------------------------------------- reactions and the log

    static Path reactionsFile(LibraryStore store, Search s) { return s.dir(store).resolve("search").resolve("reactions.json"); }

    public static ObjectNode reactions(LibraryStore store, Search s) throws IOException {
        Path f = reactionsFile(store, s);
        return Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
    }

    /** Like or pass on a listing, with why; "" undoes. The sentence said. */
    public static String react(LibraryStore store, Search s, String id, String reaction, String why) throws IOException {
        Listing l = listings(store, s).get(id);
        if (l == null) { for (Listing x : listings(store, s).values()) if (x.id().startsWith(id)) { l = x; break; } }
        if (l == null) throw new IllegalArgumentException("There is no listing " + id + ".");
        ObjectNode r = reactions(store, s);
        if (reaction == null || reaction.isBlank()) r.remove(l.id());
        else r.putObject(l.id()).put("reaction", reaction).put("why", why == null ? "" : why.strip()).put("at", LocalDate.now().toString());
        Files.createDirectories(reactionsFile(store, s).getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(reactionsFile(store, s).toFile(), r);
        String said = (reaction == null || reaction.isBlank() ? "Undone: " : reaction.equals("like") ? "Liked: " : "Passed on: ") + l.line() + ".";
        log(store, s, said);
        return said;
    }

    public static void log(LibraryStore store, Search s, String what) throws IOException {
        Files.createDirectories(s.dir(store));
        ObjectNode o = M.createObjectNode().put("at", Instant.now().toString()).put("what", what);
        Files.writeString(s.dir(store).resolve("log.jsonl"), M.writeValueAsString(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The last lines of the log, newest last. */
    public static List<String> lately(LibraryStore store, Search s, int n) throws IOException {
        Path f = s.dir(store).resolve("log.jsonl");
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) return out;
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) {
            try { JsonNode j = M.readTree(lines.get(i)); out.add(j.path("at").asText("").replaceFirst("T(\\d\\d:\\d\\d).*$", " $1") + " " + j.path("what").asText("")); } catch (IOException ignored) { }
        }
        return out;
    }

    /** The whole sentence the page and the chat say of where a search stands. */
    public static String standing(LibraryStore store, Search s) throws IOException {
        Map<String, String> w = wantsOf(store, s);
        int listings = listings(store, s).size();
        if (lang(store, s).equals("ja")) {
            StringBuilder b = new StringBuilder("検索「").append(s.name()).append("」：");
            b.append(w.isEmpty() ? "まだ何も決まっていません" : w.size() + "項目が決まっています（" + String.join("；", w.entrySet().stream().map(e -> label(e.getKey(), "ja") + " " + e.getValue().replace(" (a guess)", "（推測）")).toList()) + "）");
            b.append("；").append(listings == 0 ? "物件はまだありません" : listings + "件の物件を保存しています").append('。');
            return b.toString();
        }
        StringBuilder b = new StringBuilder("The search \"").append(s.name()).append("\": ");
        b.append(w.isEmpty() ? "nothing decided yet" : w.size() + (w.size() == 1 ? " topic" : " topics") + " set (" + String.join("; ", w.entrySet().stream().map(e -> label(e.getKey()) + " " + e.getValue()).toList()) + ")");
        b.append("; ").append(listings == 0 ? "no listings found yet" : listings + " listings kept").append('.');
        return b.toString();
    }

    /** The language a search was asked in: Japanese when its place or its budget is written in Japanese, else English. */
    public static String lang(LibraryStore store, Search s) throws IOException {
        Map<String, String> w = wantsOf(store, s);
        return HomeJapan.isJapanese(w.getOrDefault("place", "")) || HomeJapan.isJapanese(w.getOrDefault("budget", "")) ? "ja" : "en";
    }
}
