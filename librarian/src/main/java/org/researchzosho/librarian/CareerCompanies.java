package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerCut.Cut;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Companies first (docs/DESIGN_CAREER.md, the company list): for the place a person decided, the companies there that match what they
 * want, found in public records of companies rather than in job postings, since for the roles this field serves the posting is the
 * rare and late signal. A company source yields companies with dated facts, name, website, city, size, stage, founded, industry, what it
 * does, what it raised and when, who runs it, for a place; the sources are rows in a table by country, not code paths: for the United
 * States the SEC's private-offering filings (Form D), which every company files when it raises money; everywhere, Wikidata's companies
 * by headquarters, and the open mirror of Y Combinator's directory; and the hiring boards the search already reads. The cut on what the
 * person decided is deterministic; then the model reads each company against the wants and writes why; the person's likes and passes
 * are what check it. The set is refreshed weekly and kept under {@code career/<person>/search/companies-first.json}.
 */
public final class CareerCompanies {
    private CareerCompanies() { }

    private static final ObjectMapper M = new ObjectMapper();
    /** The most companies one refresh takes from one source; the most filings read from the SEC, one at a time; how far back; how many companies one model call reads. */
    static final int PER_SOURCE = 150, FILINGS_READ = 120, DAYS = 365, BATCH = 10;
    /** The reading prompt's version, part of each reading's basis: raised when the prompt changes, so every company is read again. */
    static final int READING = 6;

    /** A place as the sources need it: the country, the region (a US state code), and the city words the person gave. */
    public record Place(String country, String region, String regionName, List<String> cities) {
        boolean known() { return !country.isEmpty() || !cities.isEmpty(); }
    }

    /** One company as a source gives it; facts the source does not give are "" or 0. */
    public record Company(String name, String legal, String site, String city, String region, String country, int people, String stage, String founded, String age, String industry, String about,
                          String raised, String raisedOn, String revenue, List<String> officers, String source, String at) { }

    static final Pattern LEGAL = Pattern.compile("(?i)[,\\s]+(inc|incorporated|corp|corporation|llc|l\\.l\\.c|ltd|limited|plc|pbc|p\\.b\\.c|gmbh|ag|sa|bv|b\\.v|pty|llp|lp|l\\.p)\\.?\\s*$");

    /** The name people use: the legal form dropped ("Sentonix, Inc." is Sentonix); a name that is nothing but a legal form stays. */
    static String shortName(String legal) {
        String n = legal == null ? "" : legal.strip();
        for (int i = 0; i < 2; i++) { String m = LEGAL.matcher(n).replaceFirst("").strip(); if (m.isEmpty() || m.equals(n)) break; n = m; }
        return n;
    }

    /** The key two sources' spellings of one company share: the short name, its case, spaces and marks dropped. */
    static String key(String name) { return CareerSearch.key(shortName(name)); }

    /** A company source: a row of the table, for the places it covers. */
    public interface Source {
        String name();
        boolean covers(Place p);
        List<Company> companies(Place p, BooleanSupplier stop);
        /** The address the sources list keeps for this row, and its kind. */
        default String address() { return ""; }
        default String kind() { return "register"; }
    }

    static final Map<String, String> US_STATES = Map.ofEntries(Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"), Map.entry("AR", "Arkansas"), Map.entry("CA", "California"),
            Map.entry("CO", "Colorado"), Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("FL", "Florida"), Map.entry("GA", "Georgia"), Map.entry("HI", "Hawaii"), Map.entry("ID", "Idaho"),
            Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"), Map.entry("IA", "Iowa"), Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"), Map.entry("LA", "Louisiana"), Map.entry("ME", "Maine"),
            Map.entry("MD", "Maryland"), Map.entry("MA", "Massachusetts"), Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"), Map.entry("MS", "Mississippi"), Map.entry("MO", "Missouri"),
            Map.entry("MT", "Montana"), Map.entry("NE", "Nebraska"), Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"), Map.entry("NJ", "New Jersey"), Map.entry("NM", "New Mexico"),
            Map.entry("NY", "New York"), Map.entry("NC", "North Carolina"), Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"), Map.entry("OK", "Oklahoma"), Map.entry("OR", "Oregon"),
            Map.entry("PA", "Pennsylvania"), Map.entry("RI", "Rhode Island"), Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"), Map.entry("TN", "Tennessee"), Map.entry("TX", "Texas"),
            Map.entry("UT", "Utah"), Map.entry("VT", "Vermont"), Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"), Map.entry("WV", "West Virginia"), Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"), Map.entry("DC", "District of Columbia"));

    /** The place from the cut's place words: a US state by its code or its name makes the country the US; the other words are cities. */
    public static Place placeOf(Cut cut) {
        String region = "", regionName = "", country = "";
        List<String> cities = new ArrayList<>();
        for (String w : cut.places()) {
            String x = w.strip();
            if (US_STATES.containsKey(x.toUpperCase(Locale.ROOT)) && x.length() == 2) { region = x.toUpperCase(Locale.ROOT); regionName = US_STATES.get(region); country = "US"; continue; }
            boolean state = false;
            for (Map.Entry<String, String> e : US_STATES.entrySet()) if (e.getValue().equalsIgnoreCase(x)) { region = e.getKey(); regionName = e.getValue(); country = "US"; state = true; }
            if (!state && !x.isEmpty()) cities.add(x);
        }
        return new Place(country, region, regionName, cities);
    }

    // ---------------------------------------------------------------- the sources

    /** The SEC's Form D filings: every US company that raises money from investors files one, with its city, the amount, the date and its officers. */
    static final class SecFormD implements Source {
        static final Pattern FUND = Pattern.compile("(?i)\\b(fund|l\\.?p\\.?|limited partnership|partners|capital|holdings|trust|co-?invest|spv|opportunit|investors|investment|acquisition corp|reit|real estate|bancorp|bank)\\b");
        static final List<String> NOT_A_COMPANY = List.of("Pooled Investment Fund", "REITS and Finance", "Commercial Banking", "Insurance", "Investing", "Other Banking and Financial Services", "Residential", "Commercial", "Other Real Estate", "Oil and Gas");
        @Override public String name() { return "the SEC's Form D filings"; }
        @Override public String address() { return "https://efts.sec.gov/LATEST/search-index"; }
        @Override public String kind() { return "register"; }
        @Override public boolean covers(Place p) { return p.country().equals("US") && !p.region().isEmpty(); }
        @Override public List<Company> companies(Place p, BooleanSupplier stop) {
            List<Company> out = new ArrayList<>();
            String today = LocalDate.now().toString(), since = LocalDate.now().minusDays(DAYS).toString();
            int read = 0;
            for (int from = 0; from < 1000 && out.size() < PER_SOURCE && read < FILINGS_READ; from += 100) {
                if (stop.getAsBoolean()) break;
                String url = "https://efts.sec.gov/LATEST/search-index?q=" + URLEncoder.encode(p.regionName(), StandardCharsets.UTF_8) + "&forms=D&dateRange=custom&startdt=" + since + "&enddt=" + today
                        + "&locationCodes=" + p.region() + (from > 0 ? "&from=" + from : "");
                String b = CareerSources.get(url);
                if (b == null) break;
                JsonNode hits;
                try { hits = M.readTree(b).path("hits").path("hits"); } catch (Exception e) { break; }
                if (!hits.isArray() || hits.isEmpty()) break;
                for (JsonNode h : hits) {
                    if (stop.getAsBoolean() || out.size() >= PER_SOURCE || read >= FILINGS_READ) break;
                    JsonNode src = h.path("_source");
                    String display = src.path("display_names").path(0).asText(""), entity = display.replaceAll("\\s*\\(CIK [^)]*\\)\\s*$", "").replaceAll("\\s*\\([A-Z]{1,5}\\)\\s*$", "").strip();
                    String loc = src.path("biz_locations").path(0).asText(""), city = loc.contains(",") ? loc.substring(0, loc.indexOf(',')).strip() : loc.strip();
                    if (entity.isEmpty() || FUND.matcher(entity).find()) continue;
                    String adsh = src.path("adsh").asText("").replace("-", ""), cik = src.path("ciks").path(0).asText("").replaceFirst("^0+", "");
                    if (adsh.isEmpty() || cik.isEmpty()) continue;
                    read++;
                    String xml = CareerSources.get("https://www.sec.gov/Archives/edgar/data/" + cik + "/" + adsh + "/primary_doc.xml");
                    if (xml == null) continue;
                    String industry = tag(xml, "industryGroupType");
                    if (NOT_A_COMPANY.stream().anyMatch(industry::equalsIgnoreCase)) continue;
                    String sold = tag(xml, "totalAmountSold"), offered = tag(xml, "totalOfferingAmount"), first = tag(xml, "dateOfFirstSale").replaceAll("<[^>]+>", "").strip();
                    String raised = money(sold.isEmpty() || sold.equals("0") ? offered : sold) + (sold.isEmpty() || sold.equals("0") ? " offered" : " sold");
                    String age = xml.contains("<withinFiveYears>true</withinFiveYears>") ? "formed within the last five years" : xml.contains("<withinFiveYears>false</withinFiveYears>") ? "formed more than five years ago" : "";
                    String revenue = tag(xml, "revenueRange").strip();
                    revenue = revenue.equalsIgnoreCase("No Revenues") ? "no revenue yet" : revenue.toLowerCase(Locale.ROOT).contains("decline") || revenue.equalsIgnoreCase("Not Applicable") ? "" : revenue.isEmpty() ? "" : "revenue " + revenue.replace(" - ", " to ");
                    List<String> officers = new ArrayList<>();
                    Matcher rp = Pattern.compile("(?s)<relatedPersonInfo>(.*?)</relatedPersonInfo>").matcher(xml);
                    while (rp.find() && officers.size() < 6) {
                        String person = rp.group(1), fn = tag(person, "firstName"), ln = tag(person, "lastName");
                        List<String> roles = new ArrayList<>(); Matcher r = Pattern.compile("<relationship>(.*?)</relationship>").matcher(person); while (r.find()) roles.add(r.group(1).strip());
                        if (!fn.isEmpty() || !ln.isEmpty()) officers.add((fn + " " + ln).strip() + (roles.isEmpty() ? "" : ", " + String.join(" and ", roles).toLowerCase(Locale.ROOT)));
                    }
                    String fileDate = src.path("file_date").asText("");
                    out.add(new Company(shortName(entity), entity, "", city, p.region(), "US", 0, "", "", age, industry.equalsIgnoreCase("Other") ? "" : industry, "",
                            raised, first.matches("\\d{4}-\\d{2}-\\d{2}") ? first : fileDate, revenue, officers, name() + ", filed " + fileDate, today));
                }
                if (hits.size() < 100) break;
            }
            return out;
        }
    }

    /** The open mirror of Y Combinator's directory: every YC company with its places, team size, batch, industries and website. */
    static final class YcDirectory implements Source {
        @Override public String name() { return "Y Combinator's directory"; }
        @Override public String address() { return "https://yc-oss.github.io/api/companies/all.json"; }
        @Override public String kind() { return "directory"; }
        @Override public boolean covers(Place p) { return !p.cities().isEmpty() || !p.regionName().isEmpty(); }
        @Override public List<Company> companies(Place p, BooleanSupplier stop) {
            List<Company> out = new ArrayList<>();
            String body = cached("yc-companies.json", "https://yc-oss.github.io/api/companies/all.json");
            if (body == null) return out;
            JsonNode all;
            try { all = M.readTree(body); } catch (Exception e) { return out; }
            String today = LocalDate.now().toString();
            for (JsonNode c : all) {
                if (out.size() >= PER_SOURCE) break;
                String locs = c.path("all_locations").asText("") + " " + join(c.path("regions"));
                boolean here = p.cities().stream().anyMatch(city -> CareerCut.has(locs, city)) || (!p.regionName().isEmpty() && (CareerCut.has(locs, p.regionName()) || CareerCut.has(locs, p.region())));
                if (!here || !c.path("status").asText("").equalsIgnoreCase("Active")) continue;
                int team = c.path("team_size").asInt(0);
                String city = ""; for (String part : c.path("all_locations").asText("").split(";")) { String t = part.strip(); if (p.cities().stream().anyMatch(x -> CareerCut.has(t, x))) { city = t.contains(",") ? t.substring(0, t.indexOf(',')).strip() : t; break; } }
                out.add(new Company(c.path("name").asText(""), "", CareerSize.site(c.path("website").asText("")), city, p.region(), p.country(), team, (c.path("stage").asText("").isEmpty() ? "" : c.path("stage").asText("").toLowerCase(Locale.ROOT) + ", ") + "YC " + c.path("batch").asText(""),
                        c.path("launched_at").isNumber() ? Instant.ofEpochSecond(c.path("launched_at").asLong()).toString().substring(0, 4) : "", "", join(c.path("industries")), c.path("one_liner").asText(""), "", "", "", List.of(),
                        name() + (c.path("isHiring").asBoolean(false) ? ", which says it is hiring" : ""), today));
            }
            return out;
        }
    }

    /** Wikidata: the companies it records as headquartered in the place, with their site, employees and founding; works everywhere, thin for the young. */
    static final class WikidataCompanies implements Source {
        @Override public String name() { return "Wikidata"; }
        @Override public String address() { return "https://query.wikidata.org/sparql"; }
        @Override public String kind() { return "index"; }
        @Override public boolean covers(Place p) { return !p.cities().isEmpty(); }
        @Override public List<Company> companies(Place p, BooleanSupplier stop) {
            List<Company> out = new ArrayList<>();
            String today = LocalDate.now().toString();
            for (String city : p.cities().subList(0, Math.min(3, p.cities().size()))) {
                if (stop.getAsBoolean()) break;
                String q = "SELECT ?c ?cLabel ?site ?emp ?inc ?indLabel WHERE { ?city rdfs:label \"" + city.replace("\"", "") + "\"@en . ?city wdt:P31/wdt:P279* wd:Q515 . "
                        + (p.regionName().isEmpty() ? "" : "?city wdt:P131* ?region . ?region rdfs:label \"" + p.regionName().replace("\"", "") + "\"@en . ")
                        + "VALUES ?type { wd:Q4830453 wd:Q6881511 wd:Q1058914 wd:Q891723 wd:Q18388277 wd:Q2995256 } ?c wdt:P159 ?city . ?c wdt:P31 ?type . FILTER NOT EXISTS { ?c wdt:P576 ?gone } "
                        + "OPTIONAL { ?c wdt:P856 ?site } OPTIONAL { ?c wdt:P1128 ?emp } OPTIONAL { ?c wdt:P571 ?inc } OPTIONAL { ?c wdt:P452 ?ind } "
                        + "SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } } LIMIT 300";
                String b = CareerSources.get("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
                if (b == null) continue;
                try {
                    for (JsonNode r : M.readTree(b).path("results").path("bindings")) {
                        String name = r.path("cLabel").path("value").asText("");
                        String plain = name.replaceAll("\\s*\\((United States|United Kingdom|[A-Z][a-z]+)\\)\\s*$", "");   // Wikidata's "(United States)" tells namesakes apart
                        if (plain.isEmpty() || plain.matches("Q\\d+") || out.stream().anyMatch(c -> c.name().equalsIgnoreCase(plain))) continue;
                        int emp = (int) r.path("emp").path("value").asDouble(0);
                        out.add(new Company(plain, "", CareerSize.site(r.path("site").path("value").asText("")), city, p.region(), p.country(), emp, "", r.path("inc").path("value").asText("").length() >= 4 ? r.path("inc").path("value").asText("").substring(0, 4) : "", "",
                                r.path("indLabel").path("value").asText(""), "", "", "", "", List.of(), name() + (emp > 0 ? ", which gives its employees" : ""), today));
                        if (out.size() >= PER_SOURCE) break;
                    }
                } catch (Exception ignored) { }
            }
            return out;
        }
    }

    static final List<Source> SOURCES = List.of(new SecFormD(), new YcDirectory(), new WikidataCompanies());

    static String tag(String xml, String name) { return CareerSources.tag(xml, name); }
    static String join(JsonNode arr) { List<String> xs = new ArrayList<>(); if (arr != null && arr.isArray()) for (JsonNode x : arr) if (!x.asText("").isBlank()) xs.add(x.asText()); return String.join(", ", xs); }
    /** "$12,000,000" from a filing's amount; "an undisclosed amount" when the filing gives none. */
    static String money(String amount) {
        try { long v = Long.parseLong((amount == null ? "" : amount).replaceAll("[^0-9]", "")); return v > 0 ? "$" + String.format("%,d", v) : "an undisclosed amount"; } catch (NumberFormatException e) { return "an undisclosed amount"; }
    }

    // ---------------------------------------------------------------- the set

    static Path file(LibraryStore store, Career.Seeker s) { return CareerFind.dir(store, s).resolve("companies-first.json"); }

    /** The library whose cache a source's big file goes to; set for the refresh. */
    static final ThreadLocal<LibraryStore> STORE = new ThreadLocal<>();

    /** A source's big file, kept with the library for a week: the YC mirror is ten megabytes. */
    static String cached(String name, String url) {
        try {
            LibraryStore st = STORE.get();
            Path dir = (st == null ? Config.home() : st.root().resolve("catalog")).resolve("career-cache"), f = dir.resolve(name);
            Files.createDirectories(dir);
            if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isAfter(Instant.now().minus(Duration.ofDays(7)))) return Files.readString(f, StandardCharsets.UTF_8);
            String b = CareerSources.get(url);
            if (b != null) Files.writeString(f, b, StandardCharsets.UTF_8);
            return b != null ? b : (Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null);
        } catch (IOException e) { return null; }
    }

    /** The set as kept: each company by its key, with its facts and the sources that gave them. */
    public static ObjectNode kept(LibraryStore store, Career.Seeker s) throws IOException { return CareerFind.readObject(file(store, s)); }

    /**
     * The set refreshed from every source that covers the place, merged by name: a fact the first source gives stands, the rest fill in.
     * Companies seen before keep their first day, their reading and the person's reaction. The number of companies the set holds.
     */
    public static int refresh(LibraryStore store, Career.Seeker s, Cut cut, BooleanSupplier stop) throws IOException { return refresh(store, s, cut, stop, false); }

    /** As above; {@code force} refreshes although the set is less than a week old. */
    public static int refresh(LibraryStore store, Career.Seeker s, Cut cut, BooleanSupplier stop, boolean force) throws IOException {
        Place p = placeOf(cut);
        ObjectNode doc = kept(store, s);
        if (!force && doc.has("companies") && !weekOld(doc.path("refreshed").asText("")) && doc.path("place").asText("").equals(placeWords(p))) return doc.path("companies").size();
        ObjectNode all = doc.has("companies") && doc.get("companies").isObject() ? (ObjectNode) doc.get("companies") : doc.putObject("companies");
        String today = LocalDate.now().toString();
        List<String> notes = new ArrayList<>();
        if (!p.known()) { doc.put("refreshed", today).put("place", "").putArray("notes").add("no place decided: the company sources need one"); CareerFind.write(file(store, s), doc); return all.size(); }
        STORE.set(store);
        try {
        for (Source src : SOURCES) {
            if (!src.covers(p) || stop.getAsBoolean()) continue;
            boolean off = false;
            for (Sources.Source x : Sources.all(store)) if (Sources.key(x.address()).equals(Sources.key(src.address())) && x.off()) off = true;
            if (off) { notes.add(src.name() + ": taken off by you"); continue; }
            List<Company> got;
            try { got = src.companies(p, stop); } catch (RuntimeException e) { notes.add(src.name() + ": " + e.getMessage()); continue; }
            Sources.read(store, src.address(), src.kind(), "employers", "career", placeWords(p), "", "the field's table", got.size());
            int added = 0;
            for (Company c : got) {
                String key = key(c.name());
                if (key.isEmpty()) continue;
                boolean fresh = !all.has(key);
                ObjectNode e = fresh ? all.putObject(key).put("name", c.name()).put("first_seen", today) : (ObjectNode) all.get(key);
                if (fresh) added++;
                merge(e, c);
                e.put("last_seen", today);
            }
            notes.add(src.name() + ": " + got.size() + (got.size() == 1 ? " company" : " companies") + ", " + added + " new");
        }
        } finally { STORE.remove(); }
        doc.put("refreshed", today).put("place", placeWords(p));
        ArrayNode ns = doc.putArray("notes"); notes.forEach(ns::add);
        CareerFind.write(file(store, s), doc);
        Career.log(store, s, "The companies of your place, from the public records: " + String.join("; ", notes) + ".");
        return all.size();
    }

    /** A place as the record wrote it, read for a person: no empty parts ("Pune, , " is "Pune"). */
    static String placeText(String place) { return place == null ? "" : place.replaceAll("(,\\s*)+,", ",").replaceAll("^[,\\s]+|[,\\s]+$", "").replaceAll(",\\s*,", ",").strip(); }

    static String placeWords(Place p) { return (p.cities().isEmpty() ? "" : String.join(", ", p.cities()) + ", ") + (p.regionName().isEmpty() ? p.country() : p.regionName()); }

    static void merge(ObjectNode e, Company c) {
        if (!e.has("first_seen")) e.put("first_seen", c.at());
        put(e, "name", c.name()); put(e, "legal", c.legal()); put(e, "site", c.site()); put(e, "city", c.city()); put(e, "region", c.region()); put(e, "country", c.country());
        if (c.people() > 0 && e.path("people").asInt(0) == 0) e.put("people", c.people()).put("people_by", c.source());
        put(e, "stage", c.stage()); put(e, "founded", c.founded()); put(e, "age", c.age()); put(e, "industry", c.industry()); put(e, "about", c.about()); put(e, "revenue", c.revenue());
        if (!c.raised().isEmpty() && (e.path("raised_on").asText("").isEmpty() || c.raisedOn().compareTo(e.path("raised_on").asText("")) > 0)) e.put("raised", c.raised()).put("raised_on", c.raisedOn());
        if (!c.officers().isEmpty() && !e.has("officers")) { ArrayNode a = e.putArray("officers"); c.officers().forEach(a::add); }
        ArrayNode srcs = e.has("sources") && e.get("sources").isArray() ? (ArrayNode) e.get("sources") : e.putArray("sources");
        boolean had = false; for (JsonNode x : srcs) if (x.asText().equals(c.source())) had = true;
        if (!had) srcs.add(c.source());
    }

    private static void put(ObjectNode e, String k, String v) { if (v != null && !v.isEmpty() && e.path(k).asText("").isEmpty()) e.put(k, v); }

    static boolean weekOld(String day) { try { return LocalDate.parse(day).isBefore(LocalDate.now().minusDays(6)); } catch (Exception e) { return true; } }

    // ---------------------------------------------------------------- the cut and the reading

    /** Why a company is out by what the person decided, or "" when it stands: a known size far over the most decided, a word kept out in its name, industry or words. */
    public static String cutBy(Cut cut, ObjectNode e, List<String> keepOut) {
        int people = e.path("people").asInt(0);
        if (cut.maxPeople() > 0 && people > 2L * cut.maxPeople()) return peopleWords(e) + ", by " + e.path("people_by").asText("") + ", more than twice the " + String.format("%,d", cut.maxPeople()) + " you decided";
        if (cut.minPeople() > 0 && people > 0 && Math.max(people, e.path("people_high").asInt(0)) * 2L < cut.minPeople()) return peopleWords(e) + ", fewer than half the " + String.format("%,d", cut.minPeople()) + " you decided";
        String words = e.path("name").asText("") + " " + e.path("industry").asText("") + " " + e.path("about").asText("");
        for (String k : keepOut) {
            if (CareerCut.has(words, k)) return "it is in " + k + ", which you keep out";
            String sk = CareerSearch.key(k);   // "healthcare" is "Health Care" in a filing's industry line: the words squashed, for a keep-out long enough to be no accident
            if (sk.length() >= 5 && CareerSearch.key(words).contains(sk)) return "it is in " + k + ", which you keep out";
        }
        return "";
    }

    /** The companies that stand after the cut, newest raise first, then newest seen; with the reason each other one is out. */
    public static List<ObjectNode> standing(LibraryStore store, Career.Seeker s, Cut cut) throws IOException { return standing(kept(store, s), cut, CareerSearch.keepOut(store, s)); }

    static List<ObjectNode> standing(ObjectNode doc, Cut cut, List<String> keepOut) {
        List<ObjectNode> out = new ArrayList<>();
        doc.path("companies").properties().forEach(en -> {
            ObjectNode e = (ObjectNode) en.getValue();
            String why = cutBy(cut, e, keepOut);
            e.put("cut_by", why);
            if (why.isEmpty() && !e.path("reaction").asText("").equals("pass") && !e.path("state").asText("").equals("dropped")) out.add(e);
        });
        out.sort((a, b) -> { int c = b.path("raised_on").asText("").compareTo(a.path("raised_on").asText("")); return c != 0 ? c : b.path("first_seen").asText("").compareTo(a.path("first_seen").asText("")); });
        return out;
    }

    /**
     * The companies that stand, read against what the person wants: what the records answer is computed, the place, the size against the
     * most and the least decided, the reasons a record gives to approach now (money raised within the year, formed within five years, a
     * small team); the model judges only what has no sensor, whether what the company does fits the domain wanted and whether its stage
     * fits the stage wanted, each from that topic's facts alone, so that a raise is never read as a size nor a signature as a team. The
     * rest the records leave open is listed as unknown. Readings are kept with the company and made again when what the person wants
     * changes. The number read.
     */
    public static int read(LibraryStore store, Career.Seeker s, Cut cut, int max, BooleanSupplier stop) throws IOException { return read(store, s, cut, max, stop, null); }

    /** As above; with {@code only}, the companies with those keys alone (the ones a search just learned the size or stage of). */
    public static int read(LibraryStore store, Career.Seeker s, Cut cut, int max, BooleanSupplier stop, Set<String> only) throws IOException {
        ObjectNode doc = kept(store, s);
        List<String> keepOut = CareerSearch.keepOut(store, s);
        String basis = CareerCut.basis(store, s) + "#" + READING;   // the reading's own version: a changed reading reads every company again
        Map<String, Want> wants = wantsOf(store, s);
        List<ObjectNode> todo = new ArrayList<>();
        for (ObjectNode e : standing(doc, cut, keepOut)) if (!e.path("reading").path("basis").asText("").equals(basis) && (only == null || only.contains(key(e.path("name").asText(""))))) todo.add(e);
        if (todo.size() > max) todo = todo.subList(0, max);
        Want domain = wants.get("domain"), stage = wants.get("company stage");
        Map<ObjectNode, JsonNode> judged = new LinkedHashMap<>();
        List<ObjectNode> ask = new ArrayList<>();
        for (ObjectNode e : todo) if ((domain != null && !domainFacts(e).isEmpty()) || (stage != null && !stageFacts(e).isEmpty())) ask.add(e);
        boolean first = true;
        for (int i = 0; i < ask.size() && !stop.getAsBoolean(); i += BATCH) {
            List<ObjectNode> batch = ask.subList(i, Math.min(ask.size(), i + BATCH));
            StringBuilder list = new StringBuilder();
            for (int k = 0; k < batch.size(); k++) {
                ObjectNode e = batch.get(k);
                list.append(k + 1).append(". ").append(e.path("name").asText("")).append('\n');
                if (domain != null) list.append("   what it does: ").append(domainFacts(e).isEmpty() ? "(the records do not say)" : domainFacts(e)).append('\n');
                if (stage != null) list.append("   its stage: ").append(stageFacts(e).isEmpty() ? "(the records do not say)" : stageFacts(e)).append('\n');
            }
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "A person is looking for work. For each numbered company below, judge " + (domain != null && stage != null ? "two things" : "one thing") + ", each from the facts under its own heading and nothing else:"
                    + (domain == null ? "" : "\n- domain: whether what the company does fits the field they want to work in. They want: " + domain.text() + ".")
                    + (stage == null ? "" : "\n- stage: whether the company's stage fits the stage they want. They want: " + stage.text() + ".")
                    + "\nThey keep out: " + (keepOut.isEmpty() ? "nothing" : String.join("; ", keepOut)) + "."
                    + "\nAnswer fits when the facts answer what they want; against when the facts go against it or name something they keep out; unknown when the facts under the heading do not "
                    + "say. A want marked a guess is not theirs yet: it fits or is unknown, never against. Judge from the heading's facts only, never from the company's name or from memory. "
                    + "Give one plain sentence of why, naming the fact. Answer with a JSON array and nothing else: "
                    + "[{\"n\": the number" + (domain == null ? "" : ", \"domain\": \"fits\" | \"against\" | \"unknown\", \"domain_why\": \"…\"") + (stage == null ? "" : ", \"stage\": \"fits\" | \"against\" | \"unknown\", \"stage_why\": \"…\"") + "}, …]"
                    + "\n\n" + Fence.rule("the companies") + "\n" + Fence.wrap("THE COMPANIES", list.toString()));
            String said;
            try { said = Career.DRIVE.get().classify(msgs, 120 * batch.size() + 200); } catch (RuntimeException e) { said = null; }
            if (said == null) break;
            Files.writeString(CareerFind.dir(store, s).resolve("companies-reading.txt"), (first ? "# the model's judgments of the companies, " + LocalDate.now() + "\n" : "") + "\n## companies " + (i + 1) + " to " + (i + batch.size()) + "\n" + said + "\n",
                    StandardCharsets.UTF_8, first ? StandardOpenOption.CREATE : StandardOpenOption.APPEND, first ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.WRITE);   // every batch's raw answer, so a reading can be made again without the model
            first = false;
            for (JsonNode r : Career.parseArray(said)) {
                int n = r.path("n").asInt(0);
                if (n >= 1 && n <= batch.size()) judged.put(batch.get(n - 1), r);
            }
        }
        int read = 0;
        for (ObjectNode e : todo) {
            if (ask.contains(e) && !judged.containsKey(e)) continue;   // the model did not answer for it: read next time
            e.set("reading", compose(e, cut, wants, judged.get(e)).put("at", LocalDate.now().toString()).put("basis", basis));
            read++;
        }
        CareerFind.write(file(store, s), doc);
        return read;
    }

    /**
     * What a search learned of the companies of the place from their cards and their sites, their size and their stage, written back to
     * the record: a size or stage that changed drops the company's reading, so it is composed again with them, and the cut sees the size.
     * The keys of the companies changed.
     */
    public static Set<String> learn(LibraryStore store, Career.Seeker s, List<ObjectNode> listed) throws IOException {
        ObjectNode doc = kept(store, s);
        Set<String> changed = new LinkedHashSet<>();
        if (!doc.has("companies")) return changed;
        for (ObjectNode c : listed) {
            if (!CareerFind.fromCompanies(c)) continue;
            String k = key(c.path("name").asText(""));
            ObjectNode e = (ObjectNode) doc.path("companies").get(k);
            if (e == null) continue;
            boolean ch = false;
            JsonNode p = c.path("people");
            if (p.isObject() && p.path("low").asInt(0) > 0 && (e.path("people").asInt(0) != p.path("low").asInt() || e.path("people_high").asInt(0) != p.path("high").asInt() || !e.path("people_by").asText("").equals(p.path("source").asText("")))) {
                e.put("people", p.path("low").asInt()).put("people_high", p.path("high").asInt()).put("people_by", p.path("source").asText("")); ch = true;
            }
            String st = c.path("stage").asText("");
            if (!st.isEmpty() && !e.path("stage").asText("").equals(st)) { e.put("stage", st); ch = true; }
            if (c.has("card")) {   // the card's own words on what the company does: the domain is judged from them, not from a filing's category
                String what = CareerCompany.whatTheyDo(store, c.path("name").asText(""));
                if (!what.isEmpty() && !e.path("card_about").asText("").equals(what)) { e.put("card_about", what); ch = true; }
            }
            if (ch) { e.remove("reading"); changed.add(k); }
        }
        if (!changed.isEmpty()) CareerFind.write(file(store, s), doc);
        return changed;
    }

    /**
     * The cards on the shelves of companies of the place that the search's list no longer carries (a company read as maybe is not
     * listed, yet its card may have landed from an earlier search): their size, stage and words go to the record as {@link #learn} does.
     * Only companies read once and without the card's words yet are looked up, so the shelves are not searched for every company.
     */
    public static Set<String> learnFromShelves(LibraryStore store, Career.Seeker s) throws IOException {
        ObjectNode doc = kept(store, s);
        Set<String> changed = new LinkedHashSet<>();
        if (!doc.has("companies")) return changed;
        for (Map.Entry<String, JsonNode> en : doc.path("companies").properties()) {
            ObjectNode e = (ObjectNode) en.getValue();
            if (!e.has("reading") || !e.path("card_about").asText("").isEmpty()) continue;
            String name = e.path("name").asText("");
            if (name.isEmpty() || !CareerCompany.known(store, name)) continue;
            boolean ch = false;
            CareerSize.People p = CareerCompany.people(store, name);
            if (p != null && (e.path("people").asInt(0) != p.low() || e.path("people_high").asInt(0) != p.high() || !e.path("people_by").asText("").equals(p.source()))) { e.put("people", p.low()).put("people_high", p.high()).put("people_by", p.source()); ch = true; }
            String st = CareerCompany.stage(store, name);
            if (!st.isEmpty() && !e.path("stage").asText("").equals(st)) { e.put("stage", st); ch = true; }
            String what = CareerCompany.whatTheyDo(store, name);
            if (!what.isEmpty()) { e.put("card_about", what); ch = true; }
            if (ch) { e.remove("reading"); changed.add(en.getKey()); }
        }
        if (!changed.isEmpty()) CareerFind.write(file(store, s), doc);
        return changed;
    }

    /** "about 545 people", "about 50 to 199 people". */
    static String peopleWords(ObjectNode e) {
        int low = e.path("people").asInt(0), high = e.path("people_high").asInt(0);
        return high > low ? "about " + String.format("%,d", low) + " to " + String.format("%,d", high) + " people" : "about " + String.format("%,d", low) + " people";
    }

    /** What the person wants on a topic: decided (their word) or a guess (the library's). */
    record Want(String text, boolean decided) { }

    /** The wants by topic, decided ones and guesses; an open topic is absent. */
    static Map<String, Want> wantsOf(LibraryStore store, Career.Seeker s) throws IOException { return wantsOf(Career.wantsKept(store, s)); }

    /** As above, from the topics given: a run's own, with a change for that search applied. */
    static Map<String, Want> wantsOf(JsonNode wantsNode) {
        Map<String, Want> out = new LinkedHashMap<>();
        for (JsonNode d : wantsNode.path("dimensions")) {
            String st = d.path("state").asText("open"), want = d.path("want").asText("").strip();
            if (want.isEmpty() || !(st.equals("decided") || st.equals("leaning"))) continue;
            out.put(d.path("dimension").asText(""), new Want(st.equals("decided") ? want : want + " (a guess)", st.equals("decided")));
        }
        return out;
    }

    /** The facts of what a company does: its industry line, its own words, and its card's words when a card has landed. */
    static String domainFacts(ObjectNode e) {
        List<String> f = new ArrayList<>();
        if (!e.path("industry").asText("").isEmpty()) f.add("industry: " + e.path("industry").asText());
        if (!e.path("about").asText("").isEmpty()) f.add("in its words: \"" + e.path("about").asText() + "\"");
        if (!e.path("card_about").asText("").isEmpty()) f.add("its card says: \"" + e.path("card_about").asText() + "\"");
        return String.join("; ", f);
    }

    /** The facts of a company's stage: the stage named, its age, its revenue, what it raised and when. */
    static String stageFacts(ObjectNode e) {
        List<String> f = new ArrayList<>();
        if (!e.path("stage").asText("").isEmpty()) f.add(e.path("stage").asText());
        if (!e.path("age").asText("").isEmpty()) f.add(e.path("age").asText());
        if (!e.path("founded").asText("").isEmpty()) f.add("founded " + e.path("founded").asText());
        if (!e.path("revenue").asText("").isEmpty()) f.add(e.path("revenue").asText());
        if (!e.path("raised").asText("").isEmpty()) f.add("raised " + e.path("raised").asText() + (e.path("raised_on").asText("").isEmpty() ? "" : " on " + e.path("raised_on").asText()));
        return String.join("; ", f);
    }

    /** The source a fact came from, for a line: the first source named, in short. */
    static String by(ObjectNode e) {
        String src = e.path("sources").path(0).asText("");
        return src.startsWith("the SEC") ? "the filing" : src.startsWith("Y Combinator") ? "the directory" : src.startsWith("Wikidata") ? "Wikidata" : src.isEmpty() ? "the records" : src;
    }

    /**
     * The reading composed: the computed lines, the model's two judgments where they were asked, the unknowns; and the word, no when
     * anything goes against, yes when nothing does and a record gives a reason to approach now with a fit beside it, maybe otherwise.
     */
    static ObjectNode compose(ObjectNode e, Cut cut, Map<String, Want> wants, JsonNode judged) {
        List<String> fits = new ArrayList<>(), against = new ArrayList<>(), unknown = new ArrayList<>();
        boolean reason = false, fit = false;
        String by = by(e);
        if (!e.path("city").asText("").isEmpty()) fits.add("in " + e.path("city").asText() + (e.path("region").asText("").isEmpty() ? "" : ", " + e.path("region").asText()) + " (" + by + ")");
        int people = e.path("people").asInt(0);
        Want size = wants.get("company size");
        if (people > 0) {
            String line = peopleWords(e) + " (" + e.path("people_by").asText("") + ")";
            if (cut.maxPeople() > 0 && people > cut.maxPeople()) against.add(line + ", over the " + String.format("%,d", cut.maxPeople()) + " you decided");
            else if (cut.minPeople() > 0 && people < cut.minPeople()) against.add(line + ", under the " + String.format("%,d", cut.minPeople()) + " you decided");
            else { fits.add(line + (cut.maxPeople() > 0 ? ", within the " + String.format("%,d", cut.maxPeople()) + " you decided" : "")); if (cut.maxPeople() > 0) reason = true; }   // a small team is a reason to approach, not a fit on its own
        } else if (size != null) unknown.add("its size");
        String raised = e.path("raised").asText(""), raisedOn = e.path("raised_on").asText("");
        if (!raised.isEmpty()) { fits.add("raised " + raised + (raisedOn.isEmpty() ? "" : " on " + raisedOn) + " (" + by + ")"); if (!raisedOn.isEmpty() && withinDays(raisedOn, DAYS)) reason = true; }
        if (e.path("age").asText("").startsWith("formed within")) { fits.add(e.path("age").asText() + " (" + by + ")"); reason = true; }
        if (!e.path("revenue").asText("").isEmpty()) fits.add(e.path("revenue").asText() + " (" + by + ")");
        for (String topic : List.of("domain", "company stage")) {
            Want w = wants.get(topic);
            if (w == null) continue;
            String key = topic.equals("domain") ? "domain" : "stage", facts = topic.equals("domain") ? domainFacts(e) : stageFacts(e), what = topic.equals("domain") ? "what it does" : "its stage";
            String verdict = judged == null ? "" : judged.path(key).asText("").strip().toLowerCase(Locale.ROOT), why = judged == null ? "" : words(judged.path(key + "_why"));
            if (facts.isEmpty() || verdict.isEmpty() || verdict.equals("unknown")) { unknown.add(what + (why.isEmpty() || facts.isEmpty() ? "" : ": " + why)); continue; }
            if (w.decided()) {   // what they decided: a fit counts towards approaching, a miss is against
                String line = what + " " + (verdict.equals("fits") ? "fits" : "goes against") + " what you decided, " + w.text() + (why.isEmpty() ? "" : ": " + why);
                if (verdict.equals("against")) against.add(line); else { fits.add(line); fit = true; }
            } else {   // the library's guess, not yet theirs: noted, never a reason and never against
                String line = what + " " + (verdict.equals("fits") ? "fits" : "may not fit") + " the library's guess" + (why.isEmpty() ? "" : ": " + why);
                if (verdict.equals("fits")) fits.add(line); else unknown.add(line);
            }
        }
        for (String topic : List.of("kind of work", "ownership", "culture", "remote", "pace", "pay")) if (wants.containsKey(topic) && wants.get(topic).decided()) unknown.add(topic.equals("kind of work") ? "the kind of work" : topic.equals("pay") ? "the pay" : topic);
        if (!(wants.containsKey("domain") && wants.get("domain").decided()) && !(wants.containsKey("company stage") && wants.get("company stage").decided())) fit = true;   // nothing decided for the model to judge: a reason to approach is enough
        String approach = !against.isEmpty() ? "no" : (reason && fit) ? "yes" : "maybe";
        return M.createObjectNode().put("fits", String.join("; ", fits)).put("against", String.join("; ", against)).put("unknown", String.join("; ", unknown)).put("approach", approach);
    }

    static boolean withinDays(String day, int days) { try { return !LocalDate.parse(day).isBefore(LocalDate.now().minusDays(days)); } catch (Exception x) { return false; } }

    /** A reading's line as the model wrote it: a sentence, or a list of them (the model writes lists when asked to name each fact), or an object. */
    static String words(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        if (n.isArray()) { List<String> xs = new ArrayList<>(); for (JsonNode x : n) { String w = words(x); if (!w.isEmpty()) xs.add(w); } return String.join("; ", xs); }
        if (n.isObject()) { List<String> xs = new ArrayList<>(); n.properties().forEach(en -> { String w = words(en.getValue()); if (!w.isEmpty()) xs.add(en.getKey() + ": " + w); }); return String.join("; ", xs); }
        return n.asText("").strip();
    }

    /** The record's own words that a board or a page must carry to be the company's: its city, its state, its officers' surnames. */
    public static List<String> anchors(ObjectNode e) {
        List<String> out = new ArrayList<>();
        if (!e.path("city").asText("").isBlank()) out.add(e.path("city").asText().strip());
        if (!e.path("region").asText("").isBlank()) out.add(e.path("region").asText().strip());
        for (JsonNode o : e.path("officers")) {
            String name = o.asText("").split(",")[0].strip();
            String[] parts = name.replaceAll("(?i),?\\s*(jr|sr|ii|iii|iv|m\\.d|ph\\.d|md|phd|esq)\\.?$", "").strip().split("\\s+");
            String last = parts[parts.length - 1].replaceAll("[^\\p{L}'-]", "");
            if (last.length() >= 3 && !out.contains(last)) out.add(last);
        }
        return out;
    }

    /** A company's facts in one line, each with its source, for the model and the page. */
    public static String facts(ObjectNode e) {
        List<String> f = new ArrayList<>();
        f.add(e.path("name").asText("") + (e.path("site").asText("").isEmpty() ? "" : " (" + e.path("site").asText("") + ")"));
        if (!e.path("city").asText("").isEmpty()) f.add("in " + e.path("city").asText("") + (e.path("region").asText("").isEmpty() ? "" : ", " + e.path("region").asText("")));
        if (e.path("people").asInt(0) > 0) f.add(peopleWords(e) + " (" + e.path("people_by").asText("") + ")");
        if (!e.path("stage").asText("").isEmpty()) f.add(e.path("stage").asText(""));
        if (!e.path("founded").asText("").isEmpty()) f.add("founded " + e.path("founded").asText(""));
        if (!e.path("age").asText("").isEmpty()) f.add(e.path("age").asText(""));
        if (!e.path("revenue").asText("").isEmpty()) f.add(e.path("revenue").asText(""));
        if (!e.path("industry").asText("").isEmpty()) f.add(e.path("industry").asText(""));
        if (!e.path("about").asText("").isEmpty()) f.add("\"" + e.path("about").asText("") + "\"");
        if (!e.path("card_about").asText("").isEmpty()) f.add("its card: \"" + CareerSources.clip(e.path("card_about").asText(""), 200) + "\"");
        if (!e.path("raised").asText("").isEmpty()) f.add("raised " + e.path("raised").asText("") + (e.path("raised_on").asText("").isEmpty() ? "" : " on " + e.path("raised_on").asText("")));
        if (e.path("officers").isArray() && !e.path("officers").isEmpty()) { List<String> os = new ArrayList<>(); for (JsonNode o : e.path("officers")) os.add(o.asText()); f.add("officers: " + String.join("; ", os)); }
        List<String> srcs = new ArrayList<>(); for (JsonNode x : e.path("sources")) srcs.add(x.asText()); if (!srcs.isEmpty()) f.add("by " + String.join(" and ", srcs));
        return String.join("; ", f);
    }

    /** The person's reaction to a company: like, pass, or "" to take it back; and keep or drop as for the list. */
    public static String react(LibraryStore store, Career.Seeker s, String name, String reaction, String why) throws IOException {
        ObjectNode doc = kept(store, s);
        ObjectNode e = (ObjectNode) doc.path("companies").get(key(name));
        if (e == null) throw new IllegalArgumentException("There is no company called " + name + " among the companies of your place.");
        String r = reaction == null ? "" : reaction.strip().toLowerCase(Locale.ROOT);
        if (!List.of("like", "pass", "").contains(r)) throw new IllegalArgumentException("A reaction is like or pass.");
        if (r.isEmpty()) { e.remove("reaction"); e.remove("reaction_why"); } else { e.put("reaction", r).put("reaction_why", why == null ? "" : why.strip()).put("reaction_at", LocalDate.now().toString()); }
        CareerFind.write(file(store, s), doc);
        String shown = e.path("name").asText();
        Career.log(store, s, r.isEmpty() ? "You took back your reaction to " + shown + "." : (r.equals("like") ? "You liked " : "You passed on ") + shown + (why == null || why.isBlank() ? "." : ": " + why.strip()));
        return r.isEmpty() ? "Taken back: your reaction to " + shown + "." : (r.equals("like") ? "Liked: " : "Passed on: ") + shown + ". " + (r.equals("like") ? "Keeping it puts it on the list the search reads; its card has the library research it." : "");
    }

    /** The companies in words, for the chat and the command line: those to approach first, with the reading; then the maybes; counts of the rest. */
    public static String text(LibraryStore store, Career.Seeker s, Cut cut, String how, int show) throws IOException {
        ObjectNode doc = kept(store, s);
        if (!doc.has("companies") || doc.path("companies").isEmpty()) {
            String where = placeText(doc.path("place").asText(""));
            return where.isEmpty() ? "The library has not gathered the employers of your place yet. Decide the place on the Career page, then " + how + " search gathers them from the public records."
                    : "The public records the library knows gave no employer for " + where + ". The lists for your work, hospitals, schools, universities, the map's places of business, are read by the search itself; " + how + " search tries them again.";
        }
        List<ObjectNode> standing = standing(doc, cut, CareerSearch.keepOut(store, s));
        int total = doc.path("companies").size(), cut_ = total - standing.size();
        StringBuilder b = new StringBuilder("Employers of " + placeText(doc.path("place").asText("your place")) + " from the public records, refreshed " + doc.path("refreshed").asText("") + ": " + total + " known, " + standing.size() + " fit what you decided, " + cut_ + " set aside by it.\n");
        for (String klass : List.of("yes", "maybe")) {
            List<ObjectNode> xs = standing.stream().filter(e -> e.path("reading").path("approach").asText("").equals(klass)).toList();
            if (xs.isEmpty()) continue;
            b.append('\n').append(klass.equals("yes") ? "To approach now:" : "Maybe, something decided is unknown:").append('\n');
            int n = 0;
            for (ObjectNode e : xs) {
                if (n++ >= show) { b.append("   …and ").append(xs.size() - show).append(" more on the page.\n"); break; }
                b.append("- ").append(facts(e)).append('\n');
                b.append("   Fits: ").append(e.path("reading").path("fits").asText("")).append('\n');
                if (!e.path("reading").path("against").asText("").isEmpty()) b.append("   Against: ").append(e.path("reading").path("against").asText("")).append('\n');
                if (!e.path("reading").path("unknown").asText("").isEmpty()) b.append("   Unknown: ").append(e.path("reading").path("unknown").asText("")).append('\n');
            }
        }
        long unread = standing.stream().filter(e -> !e.has("reading")).count();
        if (unread > 0) b.append("\n").append(unread).append(" of them the model has not read yet; ").append(how).append(" search reads more.\n");
        b.append("\nTo react, type ").append(how).append(" like or ").append(how).append(" pass and the company's name, with why. The page lists them all: /career/companies.");
        return b.toString();
    }
}
