package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.tools.SearchTally;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a home search's listings come from, in the United States, as the survey of 2026-11-01 found the ground: no portal can be read by
 * a program, with or without a key, so the sources are RentCast's listings when the person has a key (paid per call after the free
 * fifty a month, counted in the search tally), the search engines' own snippets of the portals' pages as leads (the library never opens
 * the page behind a lead; the person does), the estate agents' and builders' own sites found through the map, HUD's list of the homes the
 * government sells, and what the person saves from their own browser through the Add page. The portals get an exact search link each.
 */
public final class HomeSources {
    private HomeSources() { }
    private static final ObjectMapper M = new ObjectMapper();

    public static final String RENTCAST_KEY = "RESEARCHZOSHO_RENTCAST_KEY";
    public static final String RENTCAST = "RentCast";
    /** RentCast's free Developer plan: this many requests a month, then paid per call. */
    public static final int RENTCAST_FREE = 50;

    /** The fetch of a data service's address with headers, the answer with its headers or null; a test puts its own here. */
    static volatile BiFunction<String, Map<String, String>, CareerSources.Answer> HTTP = CareerSources::getAnswer;
    /** RentCast's page: the most it gives in one call. */
    public static final int RENTCAST_PAGE = 500;
    /** A web search through the library's ladder; a test puts its own here. */
    static volatile Function<String, List<CareerSources.Hit>> SEARCH = q -> CareerSources.SEARCH.apply(q);
    /** The estate agents of a place from the map, websites first; a test puts its own here. */
    static volatile BiFunction<String, Integer, List<CareerRegisters.Employer>> AGENTS = HomeSources::agentsFromMap;
    /** A plain fetch of a page, the body or null; a test puts its own here. */
    static volatile Function<String, String> RAW = u -> CareerSources.RAW.apply(u);

    /** What one source gave: its name for the Sources page, its kind there, its address, the listings, a note for the log; and for a paged source, how many fit in all (-1 when it did not say) and where this page began. */
    public record Yield(String name, String kind, String address, List<Home.Listing> listings, String note, int total, int offset) {
        public Yield(String name, String kind, String address, List<Home.Listing> listings, String note) { this(name, kind, address, listings, note, -1, 0); }
        /** How many the source holds beyond what this and the pages before it gave. */
        public int left() { return total < 0 ? 0 : Math.max(0, total - offset - listings.size()); }
    }

    /** The RentCast key as set; a test puts its own here. */
    static volatile Supplier<String> KEY = () -> Config.get(RENTCAST_KEY);

    public static boolean rentCastKeySet() { String k = KEY.get(); return k != null && !k.isBlank(); }

    /** This month's RentCast calls against the free fifty, in a sentence. */
    public static String rentCastMonth() {
        int[] t = SearchTally.thisMonth().get(RENTCAST);
        int calls = t == null ? 0 : t[0];
        return "RentCast this month: " + calls + " of the " + RENTCAST_FREE + " free calls" + (calls > RENTCAST_FREE ? ", so the ones beyond are paid" : "") + ".";
    }

    // ---------------------------------------------------------------- RentCast

    /**
     * RentCast's active listings for sale or for rent that fit the cut, one call of up to 500: the budget, the rooms, the size, the lot,
     * the year and the kind go as the service's own filters, and the radius around the place's centre when the map placed it, else the
     * town by name. The first page asks for the count of all that fit; a later page starts at {@code offset}. Counted in the search tally.
     */
    public static Yield rentCast(HomeCut.Cut cut, String city, String state, String tenure, int offset) {
        String key = KEY.get();
        if (key == null || key.isBlank()) return new Yield(RENTCAST, "feed", "https://api.rentcast.io/", List.of(), "no key (" + RENTCAST_KEY + ")");
        String path = tenure.equals("rent") ? "/v1/listings/rental/long-term" : "/v1/listings/sale";
        String url = "https://api.rentcast.io" + path + "?" + rentCastQuery(cut, city, state, offset);
        CareerSources.Answer a;
        try { a = HTTP.apply(url, Map.of("X-Api-Key", key, "Accept", "application/json")); } catch (RuntimeException e) { a = null; }
        SearchTally.count(RENTCAST, 1);
        String where = cut.placed() && cut.nearKm() > 0 ? "within " + cut.nearKm() + " km of " + city + ", " + state : "in " + city + ", " + state;
        if (a == null || a.body() == null) return new Yield(RENTCAST, "feed", "https://api.rentcast.io/", List.of(), "RentCast did not answer for " + where + (tenure.equals("rent") ? " (rentals)" : " (for sale)"));
        int total = -1;
        try { String t = a.header("X-Total-Count"); if (!t.isBlank()) total = Integer.parseInt(t.strip()); } catch (NumberFormatException ignored) { }
        List<Home.Listing> out = new ArrayList<>();
        try {
            JsonNode arr = M.readTree(a.body());
            if (arr.isObject() && arr.has("message")) return new Yield(RENTCAST, "feed", "https://api.rentcast.io/", List.of(), "RentCast refused: " + arr.path("message").asText(""));
            for (JsonNode j : arr) {
                String addr = j.path("addressLine1").asText(j.path("formattedAddress").asText("")).strip(), unit = j.path("addressLine2").asText("").strip(), c = j.path("city").asText("").strip(), st = j.path("state").asText("").strip();
                if (addr.isEmpty()) continue;
                if (!unit.isEmpty() && !unit.equals("null")) addr = addr + ", " + unit;   // the unit is a home of its own
                String id = Home.idOf(addr, c, st, "");
                String mls = j.path("mlsName").asText("").strip();
                String find = findLink(addr, c, st);
                out.add(new Home.Listing(id, RENTCAST, tenure, addr, c, st, j.path("zipCode").asText(""), Math.round(j.path("price").asDouble(0)), j.path("bedrooms").asDouble(0), j.path("bathrooms").asDouble(0),
                        (int) Math.round(j.path("squareFootage").asDouble(0)), kindOf(j.path("propertyType").asText("")), find, j.path("latitude").asDouble(0), j.path("longitude").asDouble(0),
                        j.path("listedDate").asText("").replaceFirst("T.*$", ""), "", "", (mls.isEmpty() ? "" : "listed on " + mls + "; ") + "the link searches the web for the address"));
            }
        } catch (IOException e) { return new Yield(RENTCAST, "feed", "https://api.rentcast.io/", List.of(), "RentCast's answer could not be read"); }
        String what = tenure.equals("rent") ? " rentals" : " homes for sale";
        String note = out.size() + what + " that fit what you decided, " + where + (offset > 0 ? ", from the " + ordinal(offset + 1) : "");
        if (total > offset + out.size()) note += "; RentCast holds " + String.format(Locale.ROOT, "%,d", total) + " in all";
        return new Yield(RENTCAST, "feed", "https://api.rentcast.io/", out, note, total, offset);
    }

    /** A web search for a home's full address, which finds its own listing pages: the link of a home whose own page the library does not have. */
    public static String findLink(String address, String city, String state) { return "https://search.brave.com/search?q=" + enc(String.join(" ", List.of(address, city, state)).replaceAll("\\s+", " ").strip()); }

    /**
     * The query for RentCast from the cut: the place as a circle around its centre or by name, each figure as the service's range ("min:max",
     * "*" for no bound), the price narrowed by the price per square foot times the size when both are decided, the kinds joined, the page.
     */
    static String rentCastQuery(HomeCut.Cut cut, String city, String state, int offset) {
        StringBuilder q = new StringBuilder("status=Active&limit=" + RENTCAST_PAGE);
        if (cut.placed() && cut.nearKm() > 0) q.append("&latitude=").append(String.format(Locale.ROOT, "%.5f", cut.lat())).append("&longitude=").append(String.format(Locale.ROOT, "%.5f", cut.lon())).append("&radius=").append(String.format(Locale.ROOT, "%.1f", cut.nearKm() / 1.609344));
        else q.append("&city=").append(enc(city)).append("&state=").append(enc(state));
        rentCastRange(q, "price", HomeCut.priceBound(cut)); rentCastRange(q, "bedrooms", cut.beds()); rentCastRange(q, "bathrooms", cut.baths());   // a price per square foot with a size bounds the price too
        rentCastRange(q, "squareFootage", cut.sqft()); rentCastRange(q, "lotSize", cut.lot()); rentCastRange(q, "yearBuilt", cut.built());
        List<String> types = new ArrayList<>();
        for (String k : cut.kinds()) { String t = rentCastType(k); if (!t.isEmpty() && !types.contains(t)) types.add(t); }
        if (!types.isEmpty()) q.append("&propertyType=").append(enc(String.join("|", types)));
        if (offset > 0) q.append("&offset=").append(offset); else q.append("&includeTotalCount=true");
        return q.toString();
    }

    static void rentCastRange(StringBuilder q, String name, HomeCut.Range r) {
        if (r.open()) return;
        q.append('&').append(name).append('=').append(r.min() > 0 ? num(r.min()) : "*").append(':').append(r.max() > 0 ? num(r.max()) : "*");
    }

    static String num(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }

    /** 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, 501st. */
    static String ordinal(int n) {
        int last = n % 100;
        String end = last >= 11 && last <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return String.format(Locale.ROOT, "%,d", n) + end;
    }

    /** The kind as RentCast names it; "" for a kind it has no name for. */
    static String rentCastType(String kind) {
        return switch (kind) {
            case "house" -> "Single Family";
            case "condo" -> "Condo";
            case "townhouse" -> "Townhouse";
            case "multi-family", "duplex" -> "Multi-Family";
            case "apartment" -> "Apartment";
            case "land" -> "Land";
            default -> "";
        };
    }

    static String kindOf(String propertyType) {
        String t = propertyType.toLowerCase(Locale.ROOT);
        if (t.contains("single")) return "house";
        if (t.contains("condo")) return "condo";
        if (t.contains("town")) return "townhouse";
        if (t.contains("multi")) return "multi-family";
        if (t.contains("apartment")) return "apartment";
        if (t.contains("land")) return "land";
        return t.isEmpty() ? "" : t;
    }

    // ---------------------------------------------------------------- the search engines' snippets

    static final Pattern PRICE = Pattern.compile("\\$\\s?(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?\\s?[kKmM]|\\d{4,7})");
    static final Pattern BEDS = Pattern.compile("(?i)(\\d+(?:\\.5)?)\\s*(?:-|\\s)?(?:bd|bds|bed|beds|bedroom|bedrooms|br)\\b");
    static final Pattern BATHS = Pattern.compile("(?i)(\\d+(?:\\.5)?)\\s*(?:-|\\s)?(?:ba|bath|baths|bathroom|bathrooms)\\b");
    static final Pattern SQFT = Pattern.compile("(?i)(\\d{1,2},?\\d{3})\\s*(?:sq\\.?\\s?ft|sqft|square feet)");
    /** "33-35 Brookdale St, #1 Boston, MA": the number or a range of numbers, the street, a unit when there is one (a unit is a home of its own), then the town and the state. */
    static final Pattern ADDRESS = Pattern.compile("(\\d{1,6}(?:-\\d{1,6})?[A-Za-z]?\\s+(?:[A-Z][A-Za-z0-9.'-]*\\s+){1,4}(?:St|Street|Ave|Avenue|Rd|Road|Dr|Drive|Ln|Lane|Blvd|Boulevard|Way|Ct|Court|Pl|Place|Ter|Terrace|Cir|Circle|Pkwy|Parkway|Hwy|Sq|Square)\\b\\.?(?:,?\\s*(?:#|Apt\\.?|Unit|Ste\\.?|Suite)\\s*[\\w-]+)?)[,\\s]+([A-Z][a-z]+(?:\\s[A-Z][a-z]+)?),\\s*([A-Z]{2})\\b");
    /** A sum beside a home that is not its price: a cut, a drop, a reduction, an amount off, a deposit, a fee, a tax. */
    static final Pattern NOT_THE_PRICE = Pattern.compile("(?i)(?:price\\s+(?:cut|drop|reduc\\w*|change)|cut|drop|reduced(?:\\s+by)?|down|off|deposit|fee|tax(?:es)?|hoa)\\s*:?\\s*(?:by\\s+)?\\$\\s?[\\d,.]+\\s?[kKmM]?(?:\\s*\\([^)]*\\))?|\\$\\s?[\\d,.]+\\s?[kKmM]?\\s*(?:off|down|cut|price\\s+cut|below)");

    /**
     * The engines' snippets as leads: every home with a street address in a snippet, with the figures that follow its address and no other
     * home's, for the person to open; the library reads the snippet and nothing more. A snippet with no address is a page of listings.
     */
    public static Yield engineLeads(HomeCut.Cut cut, String city, String state, String tenure, int maxQueries) {
        List<String> queries = new ArrayList<>();
        String what = (cut.minBeds() > 0 ? Home.Listing.num(cut.minBeds()) + " bedroom " : "") + (cut.kinds().isEmpty() ? (tenure.equals("rent") ? "apartment" : "house") : cut.kinds().get(0)) + (tenure.equals("rent") ? " for rent " : " for sale ") + city + " " + state;
        queries.add(what);
        for (String site : tenure.equals("rent") ? List.of("zillow.com", "apartments.com", "trulia.com", "realtor.com") : List.of("zillow.com", "redfin.com", "realtor.com", "trulia.com")) queries.add(what + " site:" + site);
        if (queries.size() > maxQueries) queries = queries.subList(0, maxQueries);
        Map<String, Home.Listing> out = new LinkedHashMap<>();
        int pages = 0;
        for (String q : queries) {
            List<CareerSources.Hit> hits;
            try { hits = SEARCH.apply(q); } catch (RuntimeException e) { hits = List.of(); }
            if (hits == null) hits = List.of();
            for (CareerSources.Hit h : hits) {
                String text = (h.title() == null ? "" : h.title()) + " · " + (h.snippet() == null ? "" : h.snippet());
                String url = h.url() == null ? "" : h.url();
                String host = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/.*$", "");
                boolean own = ownPage(url);
                List<Home.Listing> found = homesIn("the web search's snippet of " + host, text, url, city, state, tenure,
                        own ? "from the search engine's snippet of the home's own page; open it yourself, the library does not read " + host : "from the search engine's snippet of a page of listings; open the page yourself, the library does not read " + host);
                if (found.isEmpty()) { pages++; continue; }
                for (Home.Listing l : found) out.putIfAbsent(l.id(), l);
            }
        }
        return new Yield("the search engines' snippets of the portals", "list page", "", new ArrayList<>(out.values()), out.size() + (out.size() == 1 ? " lead" : " leads") + " with a street address from " + queries.size() + (queries.size() == 1 ? " search" : " searches") + "; " + pages + " results had no home's address, pages of listings or figures alone");
    }

    /** Whether a portal address is one home's own page rather than a list. */
    static boolean ownPage(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return u.contains("/homedetails/") || u.contains("/realestateandhomes-detail/") || u.contains("/home/") || u.matches(".*trulia\\.com/(p|home)/.*") || u.contains("/property/") || u.contains("/listing/");
    }

    /**
     * Every home in a piece of text, a snippet or a page: each street address ("12 Elm St, Somerville, MA") is a home, and the price, the
     * beds, the baths and the size beside it are its own. A snippet writes the address and then its figures; an agent's page may write the
     * figures and then the address, so the text is read whichever way most of its addresses have a price. Figures beside no address belong
     * to no home and are left. Empty when the text names no address.
     */
    public static List<Home.Listing> homesIn(String source, String text, String url, String city, String state, String tenure, String note) {
        List<Home.Listing> out = new ArrayList<>();
        if (text == null) return out;
        List<int[]> at = new ArrayList<>();
        Matcher a = ADDRESS.matcher(text);
        while (a.find()) at.add(new int[]{a.start(), a.end()});
        if (at.isEmpty()) return out;
        int after = 0, before = 0;
        for (int i = 0; i < at.size(); i++) {
            if (PRICE.matcher(text.substring(at.get(i)[1], i + 1 < at.size() ? at.get(i + 1)[0] : text.length())).find()) after++;
            if (PRICE.matcher(text.substring(i > 0 ? at.get(i - 1)[1] : 0, at.get(i)[0])).find()) before++;
        }
        boolean figuresFirst = before > after;
        for (int i = 0; i < at.size() && out.size() < 20; i++) {
            int from = at.get(i)[0], end = at.get(i)[1];
            if (from >= 6 && text.substring(Math.max(0, from - 40), from).toLowerCase(Locale.ROOT).contains("office")) continue;   // an agent's office, not a listing
            String figures = figuresFirst ? text.substring(Math.max(i > 0 ? at.get(i - 1)[1] : 0, from - 400), from) : text.substring(end, Math.min(i + 1 < at.size() ? at.get(i + 1)[0] : text.length(), end + 400));
            Home.Listing l = one(source, text.substring(from, end), figures, url, city, state, tenure, note);
            if (l != null && out.stream().noneMatch(x -> x.id().equals(l.id()))) out.add(l);
        }
        return out;
    }

    /** One home from its address and the text that carries its figures; null when the address does not read. */
    static Home.Listing one(String source, String address, String figures, String url, String city, String state, String tenure, String note) {
        Matcher a = ADDRESS.matcher(address);
        if (!a.find()) return null;
        String addr = a.group(1).strip(), c = a.group(2).strip(), st = a.group(3);
        figures = NOT_THE_PRICE.matcher(figures).replaceAll(" ");   // "Price cut: $14,000 (10/7) · $325,000": the cut is not the price
        long price = 0; Matcher p = PRICE.matcher(figures); if (p.find()) price = HomeCut.money(p.group(0));
        double beds = 0, baths = 0; int sqft = 0;
        Matcher b = BEDS.matcher(figures); if (b.find()) beds = Double.parseDouble(b.group(1));
        Matcher ba = BATHS.matcher(figures); if (ba.find()) baths = Double.parseDouble(ba.group(1));
        Matcher sf = SQFT.matcher(figures); if (sf.find()) sqft = Integer.parseInt(sf.group(1).replace(",", ""));
        String kind = "";
        for (String k : HomeCut.KINDS) if (figures.toLowerCase(Locale.ROOT).contains(k)) { kind = k; break; }
        String t = tenure == null || tenure.isEmpty() ? (price > 0 && price < 20_000 ? "rent" : "sale") : tenure;
        String id = Home.idOf(addr, c.isEmpty() ? city : c, st.isEmpty() ? state : st, url);
        return new Home.Listing(id, source, t, addr, c.isEmpty() ? city : c, st.isEmpty() ? state : st, "", price, beds, baths, sqft, kind, url == null ? "" : url, 0, 0, "", "", "", note);
    }

    /** A listing from a line the person typed, as the portal shows it: the address with the town and the state, then the figures. Null without an address. */
    public static Home.Listing fromText(String source, String text, String url, String city, String state, String tenure, String note) {
        List<Home.Listing> found = homesIn(source, text, url, city, state, tenure, note);
        return found.isEmpty() ? null : found.get(0);
    }

    // ---------------------------------------------------------------- the agents' and builders' own sites, through the map

    static List<CareerRegisters.Employer> agentsFromMap(String place, int max) {
        try { return new CareerRegisters.MapPlaces().read(place, List.of("estate agent"), "", max); } catch (IOException e) { return List.of(); }
    }

    /** The agents' own sites as read, and how many of the agents the map named have no site of their own. */
    public record Agents(List<Yield> read, int withoutSite) { }

    /**
     * Whether an address is the map's own page of a place rather than a site: the map gives an agent with no website of its own as its
     * page on openstreetmap.org, which carries no listings and is drawn by scripts.
     */
    static boolean mapPage(String url) {
        String h = CareerSources.host(url);
        return h.equals("openstreetmap.org") || h.endsWith(".openstreetmap.org") || h.equals("osm.org") || h.endsWith(".osm.org");
    }

    /**
     * The estate agents of the place with a site of their own, each site read once for listings with an address and a price; a site drawn
     * by scripts is said. An agent the map names with no website, only its page on the map, is not read: it is counted.
     */
    public static Agents agentSites(String place, String city, String state, String tenure, int maxSites) {
        List<Yield> out = new ArrayList<>();
        List<CareerRegisters.Employer> agents;
        try { agents = AGENTS.apply(place, maxSites * 3); } catch (RuntimeException e) { agents = List.of(); }
        int read = 0, withoutSite = 0;
        for (CareerRegisters.Employer a : agents) {
            if (a.url() == null || a.url().isBlank() || mapPage(a.url())) { withoutSite++; continue; }
            if (read >= maxSites) continue;
            read++;
            String body;
            try { body = RAW.apply(a.url()); } catch (RuntimeException e) { body = null; }
            if (body == null) { out.add(new Yield(a.name(), "list page", a.url(), List.of(), "could not be read")); continue; }
            List<Home.Listing> found = listingsOnPage(body, a.name(), a.url(), city, state, tenure);
            boolean scripted = found.isEmpty() && body.contains("<script") && !PRICE.matcher(body.replaceAll("<[^>]+>", " ")).find();
            out.add(new Yield(a.name(), "list page", a.url(), found, found.isEmpty() ? (scripted ? "its site is drawn by scripts, which a plain fetch cannot read" : "no listing with an address and a price on its front page") : found.size() + (found.size() == 1 ? " listing" : " listings") + " on its site"));
        }
        return new Agents(out, withoutSite);
    }

    /** Listings on an agent's page: every address with a price beside it in the page's text; {@code tenure} is what the search is for and is not put on a listing. */
    static List<Home.Listing> listingsOnPage(String html, String agent, String pageUrl, String city, String state, String tenure) {
        String text = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ").replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ");
        List<Home.Listing> out = new ArrayList<>();
        for (Home.Listing l : homesIn(agent, text, pageUrl, city, state, "", "on the agent's own site")) {
            if (l.price() == 0) continue;   // on a page, an address with no price is the office's, a map's or a past sale's
            out.add(l);   // for sale or for rent as its price says, whatever the search is for: a sale on a page read for a rental is set aside with that reason
        }
        return out;
    }

    // ---------------------------------------------------------------- the links the person opens themselves

    /** The portals' own search pages for the place and the figures: the library cannot read them, the person can. Name and address pairs. */
    public static List<String[]> portalLinks(HomeCut.Cut cut, String city, String state, String tenure) {
        String slug = (city + "-" + state).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-"), beds = cut.minBeds() > 0 ? Home.Listing.num(cut.minBeds()) : "";
        List<String[]> out = new ArrayList<>();
        if (tenure.equals("rent")) {
            out.add(new String[]{"Zillow rentals", "https://www.zillow.com/" + slug + "/rentals/" + (beds.isEmpty() ? "" : beds + "-bedrooms/")});
            out.add(new String[]{"Apartments.com", "https://www.apartments.com/" + slug + "/" + (beds.isEmpty() ? "" : beds + "-bedrooms/")});
            out.add(new String[]{"Realtor.com rentals", "https://www.realtor.com/apartments/" + cap(city) + "_" + state.toUpperCase(Locale.ROOT) + (beds.isEmpty() ? "" : "/beds-" + beds)});
            out.add(new String[]{"Trulia rentals", "https://www.trulia.com/for_rent/" + cap(city) + "," + state.toUpperCase(Locale.ROOT) + "/"});
        } else {
            out.add(new String[]{"Zillow", "https://www.zillow.com/" + slug + "/" + (beds.isEmpty() ? "" : beds + "-bedrooms/")});
            out.add(new String[]{"Redfin", "https://www.redfin.com/city/search?query=" + enc(city + ", " + state)});
            out.add(new String[]{"Realtor.com", "https://www.realtor.com/realestateandhomes-search/" + cap(city) + "_" + state.toUpperCase(Locale.ROOT) + (beds.isEmpty() ? "" : "/beds-" + beds)});
            out.add(new String[]{"Trulia", "https://www.trulia.com/" + state.toUpperCase(Locale.ROOT) + "/" + cap(city) + "/"});
            out.add(new String[]{"HUD's homes for sale (HUD HomeStore)", hudLink(city, state)});
        }
        return out;
    }

    /** HUD's own list of the homes the government sells: a page drawn by scripts, so a link for the person. */
    public static String hudLink(String city, String state) { return "https://www.hudhomestore.gov/searchresult?citystate=" + enc(city + ", " + state); }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).replace(' ', '-'); }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
