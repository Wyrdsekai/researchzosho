package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.librarian.HomeFind.View;
import org.researchzosho.librarian.profiles.HomeProfile;
import org.researchzosho.tools.SearchTally;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The home search from end to end on stand-ins: a RentCast that answers, a web search with snippets, a map with an agent, an agent's page; and the same with no key. No web, no model, no map. */
class HomeFindTest {

    static final ObjectMapper M = new ObjectMapper();
    final Map<String, String> pages = new HashMap<>();
    final Map<String, List<Hit>> searches = new HashMap<>();
    final List<String> rentCastCalls = new CopyOnWriteArrayList<>();
    final List<ObjectNode> filed = new CopyOnWriteArrayList<>();
    volatile String key = "test-key";
    volatile int total = 5;
    final List<String> searched = new CopyOnWriteArrayList<>();
    BooleanSupplier serviceWas;

    @BeforeEach void standIns(@TempDir Path tmp) {
        SearchTally.override = tmp.resolve("tally.json");
        HomeSources.KEY = () -> key;
        HomeCut.CENTRE = place -> null;
        HomeSources.HTTP = (url, headers) -> {
            if (url.startsWith("https://api.rentcast.io/")) {
                rentCastCalls.add(url);
                assertEquals("test-key", headers.get("X-Api-Key"));
                if (url.contains("offset=")) return new CareerSources.Answer("[]", Map.of());
                if (url.contains("/listings/sale")) return new CareerSources.Answer("""
                        [{"addressLine1": "12 Elm St", "city": "Somerville", "state": "MA", "zipCode": "02143", "price": 875000, "bedrooms": 3, "bathrooms": 2, "squareFootage": 1600, "propertyType": "Single Family", "listedDate": "2026-10-20T00:00:00.000Z", "latitude": 42.39, "longitude": -71.10, "mlsName": "MLS PIN"},
                         {"addressLine1": "400 Broadway", "city": "Somerville", "state": "MA", "zipCode": "02145", "price": 650000, "bedrooms": 3, "bathrooms": 1, "propertyType": "Condo"},
                         {"addressLine1": "9 Lake Rd", "city": "Medford", "state": "MA", "zipCode": "02155", "price": 1250000, "bedrooms": 4, "bathrooms": 3, "propertyType": "Single Family"},
                         {"addressLine1": "77 Pine St", "city": "Cambridge", "state": "MA", "zipCode": "02139", "price": 840000, "bedrooms": 2, "bathrooms": 1, "propertyType": "Townhouse"},
                         {"addressLine1": "77 Pine St", "addressLine2": "Apt 2", "city": "Cambridge", "state": "MA", "zipCode": "02139", "price": 790000, "bedrooms": 3, "bathrooms": 1, "propertyType": "Condo"}]""", Map.of("x-total-count", String.valueOf(total)));
                return new CareerSources.Answer("[]", Map.of("x-total-count", "0"));
            }
            String body = pages.get(url);
            return body == null ? null : new CareerSources.Answer(body, Map.of());
        };
        HomeSources.SEARCH = q -> { searched.add(q); return searches.getOrDefault(q, List.of()); };
        HomeSources.AGENTS = (place, max) -> List.of(new CareerRegisters.Employer("Union Square Homes", "Somerville", "estate agent", "", "https://unionsquarehomes.example/"), new CareerRegisters.Employer("Scripted Realty", "Somerville", "estate agent", "", "https://scripted.example/"));
        HomeSources.RAW = pages::get;
        pages.put("https://unionsquarehomes.example/", "<html><body><h1>Union Square Homes</h1><div class='l'><a href='/x'>21 Walnut St, Somerville, MA 02143</a> <span>$799,000</span> 3 bd 1.5 ba 1,400 sqft</div><div class='l'><a href='/y'>8 Oak St #2, Somerville, MA 02143</a> <span>$2,900/mo</span> 3 bd 1 ba</div><div class='l'>Office: 1 Union Sq, Somerville, MA 02143</div></body></html>");
        pages.put("https://scripted.example/", "<html><head><script src='app.js'></script></head><body><div id='app'></div></body></html>");
        searches.put("3 bedroom house for sale Somerville MA", List.of(
                new Hit("12 Elm St, Somerville, MA 02143 | MLS #1 | Zillow", "https://www.zillow.com/homedetails/12-elm", "3 bds, 2 ba, 1,500 sqft - House for sale. $870,000."),
                new Hit("Somerville MA Real Estate - 143 Homes For Sale | Zillow", "https://www.zillow.com/somerville-ma/", "Zillow has 143 homes for sale in Somerville MA."),
                new Hit("30 Cedar St, Somerville, MA 02143 - Redfin", "https://www.redfin.com/MA/Somerville/30-Cedar-St/home/1", "$895,000 · 3 beds · 2 baths · 1,500 sq ft · house")));
        HomeCard.FILE = args -> { filed.add(args); return "job-" + filed.size(); };
        HomeCard.SENSORS = l -> new HomeSensors.Sheet(M.createObjectNode().put("flood_zone", "X"), "Flood zone: X (FEMA NFHL)\nSchools within about 1.5 km: unknown; a stand-in");
        Career.DRIVE = () -> null;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("Middlesex County, Massachusetts, United States", List.of("Cambridge", "Medford"));
        serviceWas = Share.serviceRunning; Share.serviceRunning = () -> true;
    }

    @AfterEach void back() {
        SearchTally.override = null;
        HomeSources.KEY = () -> Config.get(HomeSources.RENTCAST_KEY);
        HomeCut.CENTRE = HomeCut::centreFromMap;
        HomeSources.HTTP = CareerSources::getAnswer; HomeSources.SEARCH = q -> CareerSources.SEARCH.apply(q); HomeSources.AGENTS = HomeSources::agentsFromMap; HomeSources.RAW = u -> CareerSources.RAW.apply(u);
        HomeCard.FILE = HomeCard::fileRun; HomeCard.SENSORS = l -> HomeSensors.sheet(HomeCard.STORE.get(), l);
        Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata;
        Share.serviceRunning = serviceWas;
    }

    Home.Search search(LibraryStore store) throws Exception {
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Somerville, MA", "you", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "you", "");
        Home.setWant(store, s, "budget", "decided", "$900,000", "you", "");
        Home.setWant(store, s, "bedrooms", "decided", "3", "you", "");
        Home.setWant(store, s, "kind", "decided", "house", "you", "");
        Home.addKeepOut(store, s, "Broadway");
        return s;
    }

    @Test void withTheKeyEverySourceIsReadAndTheCardsAreFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = search(store);
        List<String> progress = new ArrayList<>();
        String said = HomeFind.run(store, s, progress::add, () -> false, "person");
        assertEquals(1, rentCastCalls.size(), "one RentCast call per kind of listing: to buy is one");
        assertTrue(rentCastCalls.get(0).endsWith("/v1/listings/sale?status=Active&limit=500&city=Somerville&state=MA&price=*:900000&bedrooms=3:*&propertyType=Single+Family&includeTotalCount=true"), "the cut as RentCast's own filters, the count asked for: " + rentCastCalls.get(0));
        assertEquals(1, SearchTally.thisMonth().get("RentCast")[0], "counted in the tally");
        assertTrue(HomeSources.rentCastMonth().startsWith("RentCast this month: 1 of the 50 free calls."), HomeSources.rentCastMonth());
        View v = HomeFind.view(store, s);
        Map<String, Home.Listing> all = Home.listings(store, s);
        assertEquals(8, all.size(), "5 from RentCast (a unit is a home of its own), 1 more lead (12 Elm is the same home), 2 from the agent's page: " + all.values().stream().map(Home.Listing::line).toList());
        assertNotNull(all.get(Home.idOf("77 Pine St, Apt 2", "Cambridge", "MA", "")), "the unit from RentCast's second address line");
        assertEquals("rent", all.get(Home.idOf("8 Oak St #2", "Somerville", "MA", "")).tenure(), "a rental on the agent's page stays a rental in a search to buy");
        Home.Listing elm = all.get(Home.idOf("12 Elm St", "Somerville", "MA", ""));
        assertEquals(HomeSources.RENTCAST, elm.source(), "RentCast's record, first in, keeps the listing");
        assertEquals("https://www.zillow.com/homedetails/12-elm", elm.url(), "but the lead's page is kept, since RentCast had none");
        assertEquals(875_000, elm.price()); assertEquals(1600, elm.sqft(), "RentCast's figures stand; the snippet's fill only blanks");
        assertEquals(3, v.passing().size(), "12 Elm, 30 Cedar, 21 Walnut fit; the Pine St unit is a condo: " + v.passing().stream().map(r -> r.listing().line()).toList());
        assertEquals(List.of("30 Cedar St", "12 Elm St", "21 Walnut St"), v.passing().stream().map(r -> r.listing().address()).toList(), "nearest the budget's ceiling first, not cheapest first");
        assertNotNull(v.more()); assertEquals(0, v.more().left(), "RentCast said 5 fit and 5 are kept: nothing more");
        assertTrue(HomeFind.file(store, s, "person", true).startsWith("RentCast gave everything that fits what you decided; there is no next page."));
        assertEquals(5, v.aside().size());
        Map<String, String> why = new HashMap<>(); for (HomeFind.Row r : v.aside()) why.put(r.listing().address(), r.verdict().why());
        assertEquals("it is for rent, and you want to buy", why.get("8 Oak St #2"));
        assertEquals("it has Broadway, which you keep out", why.get("400 Broadway"));
        assertEquals("it costs more than your budget of $900,000", why.get("9 Lake Rd"));
        assertEquals("it has fewer bedrooms than the 3 you want", why.get("77 Pine St"));
        List<Sources.Source> named = Sources.active(store, "home", "Somerville, MA", "");
        assertTrue(named.stream().anyMatch(x -> x.address().equals("https://unionsquarehomes.example/") && x.name().equals("Union Square Homes")), "the agent's name is on the Sources page: " + named);
        // the cards for the three that fit best, each with the records read first and the run filed
        assertEquals(3, filed.size(), "a card per passing listing, up to three");
        String q = filed.get(0).path("question").asText();
        assertTrue(q.contains("THE RECORDS, read by the library") && q.contains("refuse programs"), q);
        assertEquals(2, filed.get(0).path("sub_questions").size(), "two questions, so the run reads rather than searches");
        assertTrue(q.contains("Flood zone: X (FEMA NFHL)"), "the sensors' facts are handed to the run");
        assertEquals(3, HomeCard.cards(store, s).size());
        // the fits are filed as a report of the goal's shape: a list, every home with a link on its line, the near misses, what was set aside
        List<String> reports = new ArrayList<>();
        try (var files = Files.list(store.investigationsDir())) { for (Path f : files.toList()) if (f.getFileName().toString().endsWith(".md")) reports.add(f.getFileName().toString()); }
        assertEquals(1, reports.size(), reports.toString());
        Investigation fits = store.investigation(reports.get(0).replace(".md", ""));
        assertEquals("Homes that fit: Ada Example", fits.title());
        assertTrue(fits.body().contains("## Homes that fit") && fits.body().contains("- [12 Elm St, Somerville, MA](") && fits.body().contains("## Set aside") && fits.body().contains("## How this was made"), fits.body());
        assertTrue(said.contains("The fits are filed as the report " + fits.id()), said);
        assertTrue(HomeCard.state(HomeCard.cards(store, s).get(0)).matches("filed as job-\\d, running"), HomeCard.state(HomeCard.cards(store, s).get(0)));
        // the sources with their yields, on the Sources page
        List<Sources.Source> src = Sources.active(store, "home", "Somerville, MA", "");
        assertTrue(src.stream().anyMatch(x -> x.address().equals("https://api.rentcast.io/") && x.yieldLast() == 5), src.toString());
        assertTrue(src.stream().anyMatch(x -> x.address().equals("https://unionsquarehomes.example/") && x.yieldLast() == 2), src.toString());
        assertTrue(said.startsWith("The search read 4 sources, 3 with listings, 9 listings in all. 2 listings fit what you decided, and 1 is a near miss, which does not show everything you decided; 5 set aside. The library filed 3 cards"), "21 Walnut St does not say it is a house: " + said);
        assertTrue(String.join("\n", Home.lately(store, s, 20)).contains("1 drawn by scripts"), String.join("\n", Home.lately(store, s, 20)));
        // the page
        String body = HomePage.body(store, s, true);
        assertTrue(body.contains("<b>12 Elm St</b>, Somerville, MA · $875,000 · 3 beds · 2 baths · 1,600 sq ft · house"), body);
        assertTrue(body.contains("set aside: it has Broadway, which you keep out"), body);
        assertTrue(body.contains("https://www.zillow.com/somerville-ma/3-bedrooms/"), "the portal links carry the place and the bedrooms");
        assertTrue(body.contains("hudhomestore.gov/searchresult?citystate=Somerville%2C+MA"), body);
        assertTrue(body.contains("Search again"), body);
        assertTrue(body.contains("card filed as job-1, running"), body);
        // like, pass, the text, and the protocol
        assertTrue(Home.react(store, s, elm.id().substring(0, 6), "like", "the yard").startsWith("Liked: 12 Elm St"));
        assertEquals("like", HomeFind.view(store, s).passing().get(0).reactionWord(), "liked first");
        String text = HomeFind.text(store, s, 10);
        assertTrue(text.contains("12 Elm St, Somerville, MA · $875,000") && text.contains("(liked)"), text);
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "person").put("runtime", "test");
        JsonNode r = lp.home(a.deepCopy().put("op", "listings"));
        assertEquals(3, r.path("listings").size()); assertEquals(5, r.path("aside").size()); assertEquals(5, r.path("portals").size());
        assertEquals("like", r.path("listings").get(0).path("reaction").asText());
        assertEquals("Saved: 5 Oak St, Medford, MA · $650,000 · 3 beds · 1 bath. It fits what it shows of what you decided, and is a near miss: kind of home not given.", lp.home(a.deepCopy().put("op", "add").put("words", "5 Oak St, Medford, MA · $650,000 · 3 bd · 1 ba").put("url", "https://example.org/5-oak")).path("summary").asText());
        assertEquals(4, lp.home(a.deepCopy().put("op", "listings")).path("listings").size());
        assertEquals("saved by you", Home.listings(store, s).get(Home.idOf("5 Oak St", "Medford", "MA", "")).source());
    }

    @Test void aWideSearchSaysSoAndTheNextPageComesOnThePersonsWord(@TempDir Path tmp) throws Exception {
        total = 1230;
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = search(store);
        HomeFind.run(store, s, p -> { }, () -> false, "person");
        String log = String.join("\n", Home.lately(store, s, 20));
        assertTrue(log.contains("RentCast: 5 homes for sale that fit what you decided, in Somerville, MA; RentCast holds 1,230 in all."), log);
        assertTrue(log.contains("RentCast holds 1,230 listings that fit what you decided, and 5 are kept here, so the search is wide. Narrow the radius (10 km now), the budget or the kind to see fewer and nearer, or fetch the next 500 with one more call: researchzosho home search --more, or the button on the Home page."), log);
        View v = HomeFind.view(store, s);
        assertEquals(1225, v.more().left());
        String body = HomePage.body(store, s, true);
        assertTrue(body.contains("so the search is wide.") && body.contains("<button>Fetch the next 500 from RentCast</button>"), body);
        assertTrue(HomeFind.text(store, s, 5).contains("fetch the next 500 with researchzosho home search --more (one RentCast call)"), HomeFind.text(store, s, 5));
        int searchesBefore = searched.size();
        // the next page, on the person's word: one RentCast call from where the last page ended, the engines and the agents not read again
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "person").put("runtime", "test");
        assertTrue(lp.home(a.deepCopy().put("op", "search").put("more", true)).path("summary").asText().startsWith("The run fetches the next 500 listings from RentCast, one call, keeps the ones that fit, and files a card for new best fits. It is filed as "));
        HomeFind.run(store, s, p -> { }, () -> false, "person", true);
        assertEquals(2, rentCastCalls.size());
        assertTrue(rentCastCalls.get(1).endsWith("&propertyType=Single+Family&offset=5") && !rentCastCalls.get(1).contains("includeTotalCount"), rentCastCalls.get(1));
        assertEquals(searchesBefore, searched.size(), "no web search for a next page");
        assertEquals(2, SearchTally.thisMonth().get("RentCast")[0]);
        // a change to what is decided starts RentCast afresh rather than paging on
        Home.setWant(store, s, "budget", "decided", "$800,000", "you", "");
        HomeFind.run(store, s, p -> { }, () -> false, "person", true);
        assertTrue(rentCastCalls.get(2).contains("price=*:800000") && rentCastCalls.get(2).contains("includeTotalCount=true"), rentCastCalls.get(2));
        assertTrue(String.join("\n", Home.lately(store, s, 30)).contains("What you decided has changed since RentCast was last asked, so the search asks it afresh rather than for a next page."));
    }

    @Test void withoutTheKeyTheSearchSaysSoAndReadsTheRest(@TempDir Path tmp) throws Exception {
        key = "";
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = search(store);
        String said = HomeFind.run(store, s, p -> { }, () -> false, "person");
        assertTrue(rentCastCalls.isEmpty(), "no key, no call");
        assertNull(SearchTally.thisMonth().get("RentCast"));
        Map<String, Home.Listing> all = Home.listings(store, s);
        assertEquals(4, all.size(), String.valueOf(all.values().stream().map(Home.Listing::line).toList()));
        assertTrue(all.get(Home.idOf("12 Elm St", "Somerville", "MA", "")).source().startsWith("the web search's snippet of zillow.com"));
        assertTrue(said.startsWith("The search read 3 sources, 2 with listings, 4 listings in all. 2 listings fit what you decided, and 1 is a near miss, which does not show everything you decided; 1 set aside."), said);
        String log = String.join("\n", Home.lately(store, s, 20));
        assertTrue(log.contains("No RentCast key is set (RESEARCHZOSHO_RENTCAST_KEY)"), log);
        assertTrue(log.contains("The library reads the engine's snippet and never the portal's page"), log);
        assertTrue(log.contains("2 leads with a street address from 5 searches; 1 results had no home's address") || log.contains("2 leads with a street address from 5 searches"), log);
        String body = HomePage.body(store, s, true);
        assertTrue(body.contains("open the page yourself, the library does not read zillow.com") || body.contains("the web search&#39;s snippet of zillow.com") || body.contains("the web search's snippet of zillow.com"), body);
        assertTrue(body.contains("A RentCast key (RESEARCHZOSHO_RENTCAST_KEY, on the Settings page) adds the listings of a whole city"), body);
        assertTrue(body.contains("These pages cannot be read by a program"), body);
        // the search's record
        List<Path> runs; try (var st = Files.list(s.dir(store).resolve("search").resolve("runs"))) { runs = st.toList(); }
        assertEquals(1, runs.size());
        JsonNode snap = M.readTree(runs.get(0).toFile());
        assertFalse(snap.path("rentcast_key").asBoolean(true));
        assertEquals(3, snap.path("sources").size());
    }

    /**
     * The ask of the live test of 2026-11-04, on stand-ins: houses for sale in Cambridge, Massachusetts, three or four bedrooms, two or more
     * bathrooms, 2,000 to 3,500 sq ft, under $850 a square foot. The engines' snippets name homes in four Cambridges and an Arlington of
     * another state, on the portals' pages of listings, and one home's own page in Somerville.
     */
    @Test void aTownOfTheNameElsewhereIsSetAsideAndTheCardsGoToHomesInThePlace(@TempDir Path tmp) throws Exception {
        key = "";
        List<List<String>> within = new CopyOnWriteArrayList<>();
        CareerCut.NEAR = (city, w, km) -> { within.add(w); return new CareerCut.Towns("United States, Massachusetts, Middlesex County", List.of("Somerville", "Arlington", "Medford"), 42.375, -71.106111); };
        List<String> agentPlaces = new CopyOnWriteArrayList<>();
        HomeSources.AGENTS = (place, max) -> { agentPlaces.add(place); return List.of(); };
        searches.put("3 bedroom house for sale Cambridge MA", List.of(
                new Hit("Cambridge MA Real Estate - Houses For Sale | Zillow", "https://www.zillow.com/cambridge-ma/houses/", "136 Banks St, Cambridge, MA 02138 · $1,670,000 · 4 bds · 4 ba · 2,327 sqft · House for sale · 108 Auburn St, Cambridge, MA 02139 · Contact agent"),
                new Hit("Cambridge MD Real Estate | Zillow", "https://www.zillow.com/cambridge-md/", "702 Travers St, Cambridge, MD 21613 · $685,000 · 4 bds · 3 ba · 2,316 sqft · House for sale · 1022 River Point Rd, Cambridge, MD 21613 · Contact agent"),
                new Hit("Cambridge WI Real Estate | Zillow", "https://www.zillow.com/cambridge-wi/", "825-827 Vineyard Ct, Cambridge, WI 53523 · Contact agent"),
                new Hit("Cambridge ON Houses For Sale | Zillow", "https://www.zillow.com/cambridge-on/houses/", "687 Champlain Blvd, Cambridge, ON · Contact agent"),
                new Hit("Arlington VA Homes For Sale | Zillow", "https://www.zillow.com/arlington-va/", "9 Lee Hwy, Arlington, VA 22201 · $1,900,000 · 4 bds · 3 ba · 2,600 sqft · House for sale"),
                new Hit("14 Elm St, Somerville, MA 02143 | Redfin", "https://www.redfin.com/MA/Somerville/14-Elm-St/home/1", "$2,100,000 · 3 beds · 2.5 baths · 2,500 sq ft · house")));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "cambridge-ma-buy");
        Home.setWant(store, s, "place", "decided", "Cambridge, Massachusetts", "told", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "told", "");
        Home.setWant(store, s, "budget", "decided", "under $850 per square foot", "told", "");
        Home.setWant(store, s, "bedrooms", "decided", "three or four", "told", "");
        Home.setWant(store, s, "bathrooms", "decided", "two or more", "told", "");
        Home.setWant(store, s, "size", "decided", "between 2000 and 3500 square feet", "told", "");
        Home.setWant(store, s, "kind", "guess", "free-standing detached house", "told", "");
        String said = HomeFind.run(store, s, p -> { }, () -> false, "person");
        assertEquals(List.of(List.of("Massachusetts")), within, "the town is looked up with its state");
        assertEquals("3 bedroom house for sale Cambridge MA", searched.get(0), "the engines are asked for the town in its state");
        assertEquals(List.of("Cambridge, MA"), agentPlaces, "the agents of the map are read for the town in its state");
        String log = String.join("\n", Home.lately(store, s, 30));
        assertTrue(log.contains("The library made the search's cut from what you decided: to buy; in Cambridge, Massachusetts, or one of 3 towns within 10 km; up to $850 a square foot; 3 to 4 bedrooms; at least 2 bathrooms; 2,000 to 3,500 sq ft."), log);
        View v = HomeFind.view(store, s);
        Map<String, String> why = new HashMap<>(); for (HomeFind.Row r : v.aside()) why.put(r.listing().address(), r.verdict().why());
        assertEquals("it is in Cambridge, Maryland, not Cambridge, Massachusetts", why.get("1022 River Point Rd"));
        assertEquals("it is in Cambridge, Maryland, not Cambridge, Massachusetts", why.get("702 Travers St"));
        assertEquals("it is in Cambridge, Wisconsin, not Cambridge, Massachusetts", why.get("825-827 Vineyard Ct"));
        assertEquals("it is in Cambridge, Ontario, not Cambridge, Massachusetts", why.get("687 Champlain Blvd"));
        assertEquals("it is in Arlington, Virginia, not Arlington, Massachusetts", why.get("9 Lee Hwy"));
        assertEquals(5, v.aside().size(), why.toString());
        assertEquals(List.of("14 Elm St", "136 Banks St", "108 Auburn St"), v.passing().stream().map(r -> r.listing().address()).toList(), "the fits nearest $850 a square foot first, then the near miss");
        assertEquals(List.of(0, 0, 1), v.passing().stream().map(HomeFind.Row::tier).toList());
        assertTrue(said.startsWith("The search read 1 source, 1 with listings, 8 listings in all. 2 listings fit what you decided, and 1 is a near miss, which does not show everything you decided; 5 set aside. The library filed 3 cards"), said);
        // the cards go to homes shown to be in the place, the near miss among them, and to none of the other Cambridges
        assertEquals(3, filed.size());
        for (ObjectNode f : filed) assertTrue(f.path("title").asText().endsWith(", MA"), f.path("title").asText());
        // the report: the address linked to the home itself, the page it was seen on beside it, and what a near miss does not show
        Investigation fits;
        try (var files = Files.list(store.investigationsDir())) { fits = store.investigation(files.filter(f -> f.getFileName().toString().contains("homes-that-fit")).findFirst().orElseThrow().getFileName().toString().replace(".md", "")); }
        String body = fits.body();
        assertTrue(body.contains("- [14 Elm St, Somerville, MA](https://www.redfin.com/MA/Somerville/14-Elm-St/home/1) · $2,100,000 · 3 beds · 2.5 baths · 2,500 sq ft · house — the web search's snippet of redfin.com\n"), body);
        assertTrue(body.contains("- [136 Banks St, Cambridge, MA](https://search.brave.com/search?q=136+Banks+St+Cambridge+MA) · $1,670,000 · 4 beds · 4 baths · 2,327 sq ft · house — seen on [zillow.com's page](https://www.zillow.com/cambridge-ma/houses/), in the web search's snippet of it\n"), body);
        assertTrue(body.contains("## Near misses"), body);
        assertTrue(body.contains("- [108 Auburn St, Cambridge, MA](https://search.brave.com/search?q=108+Auburn+St+Cambridge+MA) · price not given — seen on [zillow.com's page](https://www.zillow.com/cambridge-ma/houses/), in the web search's snippet of it (price per square foot not known: neither the price nor the size is given; bedrooms not given; bathrooms not given)\n"), body);
        assertTrue(body.contains("1022 River Point Rd (it is in Cambridge, Maryland, not Cambridge, Massachusetts)"), body);
        assertFalse(body.contains("budget of $850"), "never a budget of $850");
        // the page and the protocol say the same
        String page = HomePage.body(store, s, true);
        assertTrue(page.contains("<a href=\"https://search.brave.com/search?q=108+Auburn+St+Cambridge+MA\">search the web for it</a> · <a href=\"https://www.zillow.com/cambridge-ma/houses/\">seen on zillow.com's page</a>"), page);
        assertTrue(page.contains("(price per square foot not known: neither the price nor the size is given; bedrooms not given; bathrooms not given)"), page);
        assertTrue(page.contains("<a href=\"https://www.redfin.com/MA/Somerville/14-Elm-St/home/1\">open its page</a>"), page);
        assertTrue(page.contains("https://www.zillow.com/cambridge-ma/3-bedrooms/"), "the portals' links carry the town in its state");
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "person").put("runtime", "test");
        JsonNode auburn = lp.home(a.put("op", "listings")).path("listings").get(2);
        assertEquals("108 Auburn St", auburn.path("address").asText());
        assertEquals("https://search.brave.com/search?q=108+Auburn+St+Cambridge+MA", auburn.path("link").asText());
        assertEquals("https://www.zillow.com/cambridge-ma/houses/", auburn.path("seen_on").asText());
        assertEquals("price per square foot not known: neither the price nor the size is given", auburn.path("not_known").get(0).asText());
    }

    /**
     * The near misses of the live test of 2026-11-04 in the order they were listed: a plot of land and two homes that give no figures came
     * before a home that gives all but its size, and took the cards after the fit.
     */
    @Test void theNearMissesThatShowTheMostComeFirstAndTheCardsGoInThatOrder(@TempDir Path tmp) throws Exception {
        key = "";
        CareerCut.NEAR = (city, w, km) -> new CareerCut.Towns("United States, Massachusetts, Middlesex County", List.of("Somerville"), 42.375, -71.106111);
        HomeSources.AGENTS = (place, max) -> List.of();
        searches.put("3 bedroom house for sale Cambridge MA", List.of(
                new Hit("Cambridge MA Homes For Sale | Zillow", "https://www.zillow.com/cambridge-ma-02138/houses/", "159 Brattle St, Cambridge, MA 02138 · Lot / Land for sale · 16 Ellery Sq, Cambridge, MA 02138 · Contact agent"),
                new Hit("Cambridge, MA Real Estate | realtor.com", "https://www.realtor.com/realestateandhomes-search/Cambridge_MA",
                        "136 Elm St, Cambridge, MA 02139 · Contact agent · 1783 Massachusetts Ave #B, Cambridge, MA 02140 · $1,595,000 · 4 bds · 3 ba · 170 Garden St, Cambridge, MA 02138 · $1,800,000 · 4 bds · 2 ba · 2,310 sqft · House for sale")));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "cambridge-ma-buy");
        Home.setWant(store, s, "place", "decided", "Cambridge, Massachusetts", "told", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "told", "");
        Home.setWant(store, s, "budget", "decided", "under $850 per square foot", "told", "");
        Home.setWant(store, s, "bedrooms", "decided", "three or four", "told", "");
        Home.setWant(store, s, "bathrooms", "decided", "two or more", "told", "");
        Home.setWant(store, s, "size", "decided", "between 2000 and 3500 square feet", "told", "");
        HomeFind.run(store, s, p -> { }, () -> false, "person");
        View v = HomeFind.view(store, s);
        assertEquals(List.of("170 Garden St", "1783 Massachusetts Ave #B", "159 Brattle St", "16 Ellery Sq", "136 Elm St"), v.passing().stream().map(r -> r.listing().address()).toList(),
                "the fit, then the near miss that does not give its size alone, then the three that give no figures, in the order they were listed");
        assertEquals(List.of(0, 1, 1, 1, 1), v.passing().stream().map(HomeFind.Row::tier).toList());
        assertEquals(3, filed.size());
        assertTrue(filed.get(0).path("title").asText().contains("170 Garden St"), filed.get(0).path("title").asText());
        assertTrue(filed.get(1).path("title").asText().contains("1783 Massachusetts Ave #B"), "the second card goes to the home that shows the most: " + filed.get(1).path("title").asText());
        // the report lists the near misses in the same order
        Investigation fits;
        try (var files = Files.list(store.investigationsDir())) { fits = store.investigation(files.filter(f -> f.getFileName().toString().contains("homes-that-fit")).findFirst().orElseThrow().getFileName().toString().replace(".md", "")); }
        String body = fits.body();
        assertTrue(body.indexOf("1783 Massachusetts Ave #B") < body.indexOf("159 Brattle St") && body.indexOf("159 Brattle St") > 0, body);
    }

    @Test void anAgentWithNoWebsiteOfItsOwnIsNotReadAsASite(@TempDir Path tmp) throws Exception {
        key = "";
        List<String> read = new CopyOnWriteArrayList<>();
        HomeSources.RAW = u -> { read.add(u); return pages.get(u); };
        HomeSources.AGENTS = (place, max) -> List.of(new CareerRegisters.Employer("Union Square Homes", "Somerville", "estate agent", "", "https://unionsquarehomes.example/"),
                new CareerRegisters.Employer("Ellis Realty", "Somerville", "estate agent", "", "https://www.openstreetmap.org/node/4242"),
                new CareerRegisters.Employer("Hale Homes", "Somerville", "estate agent", "", "https://www.openstreetmap.org/way/77"),
                new CareerRegisters.Employer("Morita Properties", "Somerville", "estate agent", "", ""));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = search(store);
        HomeFind.run(store, s, p -> { }, () -> false, "person");
        assertEquals(List.of("https://unionsquarehomes.example/"), read, "an agent's page on the map is not its site");
        String log = String.join("\n", Home.lately(store, s, 20));
        assertTrue(log.contains("The estate agents' own sites, from the map: 1 read, 1 with listings the library could read. 3 agents the map names have no website of their own, only their entry on the map, so they were not read."), log);
        assertTrue(Sources.active(store, "home", "Somerville, MA", "").stream().noneMatch(x -> x.address().contains("openstreetmap.org")), "nor is it on the Sources page");
        // the map names none with a site of its own
        HomeSources.AGENTS = (place, max) -> List.of(new CareerRegisters.Employer("Ellis Realty", "Somerville", "estate agent", "", "https://www.openstreetmap.org/node/4242"));
        LibraryStore other = new LibraryStore(tmp.resolve("lib2")); other.init();
        Home.Search t = search(other);
        HomeFind.run(other, t, p -> { }, () -> false, "person");
        assertTrue(String.join("\n", Home.lately(other, t, 20)).contains("The map names no estate agent with a website of its own in Somerville. 1 agent the map names has no website of its own, only its entry on the map, so it was not read."),
                String.join("\n", Home.lately(other, t, 20)));
        assertTrue(HomeSources.mapPage("https://www.openstreetmap.org/node/1") && HomeSources.mapPage("https://openstreetmap.org/way/2") && !HomeSources.mapPage("https://unionsquarehomes.example/"));
    }

    @Test void aHomesLinkOpensTheHomeAndSaysWhereItWasSeen() {
        Home.Listing fromList = new Home.Listing("a", "the web search's snippet of zillow.com", "sale", "108 Auburn St", "Cambridge", "MA", "", 0, 0, 0, 0, "", "https://www.zillow.com/cambridge-ma/houses/", 0, 0, "", "", "", "");
        assertEquals("https://search.brave.com/search?q=108+Auburn+St+Cambridge+MA", fromList.link(), "a page of listings is not the home's: a search for its full address is");
        assertEquals("https://www.zillow.com/cambridge-ma/houses/", fromList.seenOn());
        assertEquals("zillow.com", fromList.seenOnName());
        Home.Listing own = new Home.Listing("b", "the web search's snippet of zillow.com", "sale", "12 Elm St", "Somerville", "MA", "", 0, 0, 0, 0, "", "https://www.zillow.com/homedetails/12-elm/123_zpid/", 0, 0, "", "", "", "");
        assertEquals(own.url(), own.link(), "the home's own page is its link");
        assertEquals("", own.seenOn());
        Home.Listing agent = new Home.Listing("c", "Union Square Homes", "sale", "21 Walnut St", "Somerville", "MA", "", 799_000, 3, 1.5, 1_400, "", "https://unionsquarehomes.example/", 0, 0, "", "", "", "");
        assertEquals("https://search.brave.com/search?q=21+Walnut+St+Somerville+MA", agent.link());
        assertEquals("Union Square Homes", agent.seenOnName());
        assertEquals("Union Square Homes'", HomeFind.possessive(agent.seenOnName()));
        Home.Listing saved = new Home.Listing("d", "saved by you", "sale", "5 Oak St", "Medford", "MA", "", 650_000, 3, 1, 0, "", "https://example.org/5-oak", 0, 0, "", "", "", "");
        assertEquals("https://example.org/5-oak", saved.link(), "the page the person saved is the home's");
        Home.Listing rentCast = new Home.Listing("e", HomeSources.RENTCAST, "sale", "12 Elm St", "Somerville", "MA", "", 875_000, 3, 2, 1_600, "house", HomeSources.findLink("12 Elm St", "Somerville", "MA"), 0, 0, "", "", "", "");
        assertEquals(rentCast.url(), rentCast.link()); assertEquals("", rentCast.seenOn());
        // what is kept: the home's own page over a page of listings, a page of listings over a search
        assertEquals("https://www.zillow.com/homedetails/1/", Home.betterUrl("https://www.zillow.com/cambridge-ma/", "https://www.zillow.com/homedetails/1/"));
        assertEquals("https://www.zillow.com/homedetails/1/", Home.betterUrl("https://www.zillow.com/homedetails/1/", "https://www.zillow.com/cambridge-ma/"));
        assertEquals("https://www.zillow.com/cambridge-ma/", Home.betterUrl(HomeSources.findLink("1 A St", "Cambridge", "MA"), "https://www.zillow.com/cambridge-ma/"));
    }

    @Test void theChatAndThePageDriveTheRecord(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeProfile p = new HomeProfile();
        assertEquals("No home search is started yet. Type /home start and a name for one.", p.chatReply(store, Patrons.Patron.PERSON, "t", "/home"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home start Our move").startsWith("The search \"Our move\" is started."));
        assertEquals("Saved. Where: decided, Somerville, MA.", p.chatReply(store, Patrons.Patron.PERSON, "t", "/home want place decided Somerville, MA"));
        assertEquals("Saved. Bedrooms: a guess, 3.", p.chatReply(store, Patrons.Patron.PERSON, "t", "/home want bedrooms guess 3"));
        assertEquals("The search will keep out a main road.", p.chatReply(store, Patrons.Patron.PERSON, "t", "/home keep-out a main road"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home cut").contains("in Somerville, MA"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home").startsWith("The search \"Our move\": 2 topics set"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home add 12 Elm St, Somerville, MA · $875,000 · 3 bd · 2 ba https://example.org/12-elm").startsWith("Saved: 12 Elm St, Somerville, MA · $875,000 · 3 beds · 2 baths. It fits"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home listings").contains("12 Elm St, Somerville, MA · $875,000"));
        assertTrue(p.chatReply(store, Patrons.Patron.PERSON, "t", "/home search").contains("It is filed as "), "the search is filed as a run");
        // the page's forms
        Home.Search s = Home.search(store, "");
        Profile.Page page = p.page(store, Patrons.Patron.PERSON, "/homes", "POST", Map.of(), Map.of("op", "want", "search", s.slug(), "dimension", "tenure", "state", "decided", "want", "buy"));
        assertEquals("/homes?search=our-move#decided", page.redirect());
        assertEquals("buy", Home.wantsOf(store, s).get("tenure"));
        Profile.Page front = p.page(store, Patrons.Patron.PERSON, "/homes", "GET", Map.of(), Map.of());
        assertTrue(front.body().contains("What you decided") && front.body().contains("Somerville, MA"), "one search: its page is the front");
        assertNull(p.page(store, Patrons.Patron.PERSON, "/elsewhere", "GET", Map.of(), Map.of()));
        Profile.Page bad = p.page(store, Patrons.Patron.PERSON, "/homes", "POST", Map.of(), Map.of("op", "add", "search", s.slug(), "words", "a nice place for $700,000"));
        assertTrue(bad.body().contains("could not read a street address"), bad.body());
    }
}
