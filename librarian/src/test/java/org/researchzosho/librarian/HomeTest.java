package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The home field's record, its cut and its reading of a line of text: no web, no model server, no map. */
class HomeTest {

    /** The model: reads told words for the topics they settle. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            assertTrue(p.contains("<<<WORDS") || p.contains("WORDS"), "the words are fenced");
            return """
                    {"topics": [{"dimension": "place", "state": "decided", "want": "Somerville, MA"}, {"dimension": "tenure", "state": "decided", "want": "buy"},
                                {"dimension": "budget", "state": "decided", "want": "$900,000"}, {"dimension": "bedrooms", "state": "leaning", "want": "3"},
                                {"dimension": "kind", "state": "decided", "want": "house"}],
                     "keep_out": ["a main road"]}""";
        }
    }

    @BeforeEach void standIns() {
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("Middlesex County, Massachusetts, United States", List.of("Cambridge", "Medford", "Arlington"));
        HomeCut.CENTRE = place -> null;
    }

    @AfterEach void back() { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; HomeCut.CENTRE = HomeCut::centreFromMap; }

    @Test void theRecordFromWordsAndTopics(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Home.searches(store).isEmpty());
        Home.Search s = Home.start(store, "Our move");
        assertEquals("our-move", s.slug());
        assertEquals(s.slug(), Home.start(store, "Our move").slug(), "the same name is the same search");
        assertEquals(s.slug(), Home.search(store, "").slug(), "the only search needs no name");
        // told words, read by the model
        String said = Home.tell(store, s, "We want to buy a house in Somerville, MA, up to $900,000, 3 bedrooms would be nice; nothing on a main road.");
        assertTrue(said.startsWith("Read from your words: Where: decided, Somerville, MA"), said);
        assertTrue(said.contains("kept out: a main road"), said);
        Map<String, String> w = Home.wantsOf(store, s);
        assertEquals("Somerville, MA", w.get("place"));
        assertEquals("buy", w.get("tenure"));
        assertEquals("3 (a guess)", w.get("bedrooms"));
        assertEquals(List.of("a main road"), Home.keepOut(store, s));
        // a decision is never undone by a lean
        Home.setWant(store, s, "bedrooms", "decided", "4", "you", "set on the page");
        Home.tell(store, s, "maybe 3 bedrooms");
        assertEquals("4", Home.wantsOf(store, s).get("bedrooms"));
        assertThrows(IllegalArgumentException.class, () -> Home.setWant(store, s, "garden", "decided", "yes", "you", ""));
        assertTrue(Home.standing(store, s).contains("topics set"), Home.standing(store, s));
        assertTrue(Home.wantsText(store, s).contains("Where: decided, Somerville, MA"));
    }

    @Test void theCutFromTheDecisionsAndTheJudge(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Somerville, MA, or the next towns within 8 km", "you", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "you", "");
        Home.setWant(store, s, "budget", "decided", "$900,000", "you", "");
        Home.setWant(store, s, "bedrooms", "decided", "3", "you", "");
        Home.setWant(store, s, "bathrooms", "guess", "2", "you", "");
        Home.setWant(store, s, "kind", "decided", "a house or a townhouse", "you", "");
        Home.addKeepOut(store, s, "Broadway");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertEquals(List.of("Somerville, MA"), c.places(), "the town and its state are one place");
        assertEquals(List.of("Cambridge", "Medford", "Arlington"), c.nearby());
        assertEquals("sale", c.tenure());
        assertEquals(900_000, c.maxPrice());
        assertEquals(3, c.minBeds());
        assertEquals(0, c.minBaths(), "a guess never drops");
        assertEquals(List.of("house", "townhouse"), c.kinds());
        assertEquals(c.basis(), HomeCut.current(store, s).basis(), "kept while nothing changed");
        Home.Listing fits = listing("12 Elm St", "Somerville", "MA", 875_000, 3, 2, "house", "sale");
        assertTrue(HomeCut.judge(c, fits).passes());
        assertEquals(0, HomeCut.tier(c, fits));
        assertTrue(HomeCut.judge(c, listing("5 Oak St", "Medford", "MA", 700_000, 3, 1, "single family", "sale")).passes(), "a town near enough passes, and 'single family' is a house");
        assertEquals("it costs more than your budget of $900,000", HomeCut.judge(c, listing("1 High St", "Somerville", "MA", 1_200_000, 3, 2, "house", "sale")).why());
        assertEquals("it has fewer bedrooms than the 3 you want", HomeCut.judge(c, listing("2 High St", "Somerville", "MA", 500_000, 2, 1, "house", "sale")).why());
        assertEquals("it is a condo, not the kind you want", HomeCut.judge(c, listing("3 High St", "Somerville", "MA", 500_000, 3, 1, "condo", "sale")).why());
        assertEquals("it is not in a place you want", HomeCut.judge(c, listing("4 High St", "Worcester", "MA", 500_000, 3, 1, "house", "sale")).why());
        assertEquals("it is for rent, and you want to buy", HomeCut.judge(c, listing("6 High St", "Somerville", "MA", 3_000, 3, 1, "house", "rent")).why());
        assertEquals("it has Broadway, which you keep out", HomeCut.judge(c, listing("400 Broadway", "Somerville", "MA", 500_000, 3, 1, "house", "sale")).why());
        assertTrue(HomeCut.judge(c, listing("7 High St", "Somerville", "MA", 0, 0, 0, "", "sale")).passes(), "what the listing does not say does not drop it");
        assertEquals(1, HomeCut.tier(c, listing("7 High St", "Somerville", "MA", 0, 0, 0, "", "sale")), "the place matches and the figures are not given");
        assertEquals(2, HomeCut.tier(c, listing("8 High St", "Worcester", "MA", 500_000, 3, 1, "house", "sale")), "the place misses");
        assertEquals(3, HomeCut.tier(c, listing("9 High St", "Worcester", "MA", 2_000_000, 3, 1, "house", "sale")), "the place and the figures miss");
        String sum = HomeCut.summary(c);
        assertTrue(sum.contains("to buy; in Somerville, MA, or one of 3 towns within 8 km"), sum);
        assertTrue(sum.contains("up to $900,000; at least 3 bedrooms; a house or townhouse; keeping out Broadway"), sum);
        // a change to a decided topic makes the cut again
        Home.setWant(store, s, "budget", "decided", "1.1M", "you", "");
        assertEquals(1_100_000, HomeCut.current(store, s).maxPrice());
        assertEquals(2_800, HomeCut.money("$2,800 a month"));
        assertEquals(750_000, HomeCut.money("750k"));
    }

    static HomeCut.Range r(double min, double max) { return new HomeCut.Range(min, max); }

    @Test void theRangesAreReadFromTheWords() {
        assertEquals(r(400_000, 550_000), HomeCut.range("$400,000 to $550,000", true));
        assertEquals(r(400_000, 550_000), HomeCut.range("400-550k", true), "the unit of the second figure is the first's too");
        assertEquals(r(400_000, 550_000), HomeCut.range("between $400,000 and $550,000", true));
        assertEquals(r(0, 500_000), HomeCut.range("under $500,000", true));
        assertEquals(r(0, 900_000), HomeCut.range("$900,000", true), "a bare sum is the most");
        assertEquals(r(0, 2_800), HomeCut.range("$2,800 a month", true));
        assertEquals(r(382_500, 517_500), HomeCut.range("around $450,000", true), "fifteen per cent either way");
        assertEquals(r(1_000_000, 0), HomeCut.range("at least $1M", true));
        assertEquals(r(0, 900_000), HomeCut.range("$900,000 for a 3 bedroom", true), "the 3 is no sum");
        assertEquals(r(3, 4), HomeCut.range("3 to 4", false));
        assertEquals(r(3, 4), HomeCut.range("3-4 bedrooms", false));
        assertEquals(r(2, 0), HomeCut.range("2 or more", false));
        assertEquals(r(3, 0), HomeCut.range("at least 3", false));
        assertEquals(r(3, 0), HomeCut.range("3+", false));
        assertEquals(r(3, 0), HomeCut.range("3", false), "a bare count is the least");
        assertEquals(r(0, 2), HomeCut.range("at most 2", false));
        assertEquals(r(2, 2), HomeCut.range("exactly 2", false));
        assertEquals(r(3, 3), HomeCut.range("about 3", false), "about a count is the count");
        assertEquals(r(2.5, 0), HomeCut.range("2.5 baths", false));
        assertEquals(HomeCut.Range.OPEN, HomeCut.range("a few", false));
        assertEquals(r(1_400, 0), HomeCut.area("1,400 sq ft"));
        assertEquals(r(1_200, 1_800), HomeCut.area("1,200 to 1,800 sq ft"));
        assertEquals(r(10_890, 0), HomeCut.area("a quarter acre"));
        assertEquals(r(1_399, 0), HomeCut.area("130 square metres"));
        assertEquals(r(2_000, 0), HomeCut.years("after 2000"));
        assertEquals(r(0, 1_950), HomeCut.years("before 1950"));
        assertEquals(r(1_920, 1_929), HomeCut.years("the 1920s"));
        assertEquals(r(1_920, 1_950), HomeCut.years("1920s to 1950s"));
        assertEquals(r(2_010, 0), HomeCut.years("2010 or later"));
        assertEquals("501st", HomeSources.ordinal(501)); assertEquals("1,002nd", HomeSources.ordinal(1002)); assertEquals("12th", HomeSources.ordinal(12)); assertEquals("23rd", HomeSources.ordinal(23));
        HomeCut.Cut flat = new HomeCut.Cut(List.of(), List.of(), 0, 0, 0, "rent", HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, List.of("apartment", "condo"), List.of(), List.of(), "", "");
        assertEquals("to rent; an apartment or condo", HomeCut.summary(flat));
        assertEquals(8, HomeCut.distanceKm("within 5 miles"));
        assertEquals(15, HomeCut.distanceKm("15 km"));
        assertEquals(10, HomeCut.distanceKm("Somerville, MA"), "the usual radius is a town's ten");
    }

    @Test void aRangedCutJudgesBothEndsAndOrdersByTheBudget(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("Middlesex County, Massachusetts, United States", List.of(), 42.3876, -71.0995);   // Wikidata says where the town is, and names no town near
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Somerville, MA", "you", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "you", "");
        Home.setWant(store, s, "budget", "decided", "$400,000 to $550,000", "you", "");
        Home.setWant(store, s, "bedrooms", "decided", "3 to 4", "you", "");
        Home.setWant(store, s, "bathrooms", "decided", "2 or more", "you", "");
        Home.setWant(store, s, "size", "decided", "1,200 to 1,800 sq ft", "you", "");
        Home.setWant(store, s, "lot", "decided", "a quarter acre", "you", "");
        Home.setWant(store, s, "built", "decided", "after 1950", "you", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertEquals(r(400_000, 550_000), c.price()); assertEquals(r(3, 4), c.beds()); assertEquals(r(2, 0), c.baths()); assertEquals(r(1_200, 1_800), c.sqft()); assertEquals(r(10_890, 0), c.lot()); assertEquals(r(1_950, 0), c.built());
        assertEquals(10, c.nearKm()); assertTrue(c.placed()); assertEquals(42.3876, c.lat(), 1e-9);
        String sum = HomeCut.summary(c);
        assertEquals("to buy; in Somerville, MA, or within 10 km of it; from $400,000 to $550,000; 3 to 4 bedrooms; at least 2 bathrooms; 1,200 to 1,800 sq ft; a lot of at least 10,890 sq ft; built 1950 or later", sum);
        assertEquals(c, HomeCut.kept(store, s), "kept as made, ranges and the centre too");
        assertTrue(String.join("\n", Home.lately(store, s, 20)).contains("The search is centred on Somerville, MA, within 10 km of it."));
        assertTrue(HomeCut.judge(c, listing("12 Elm St", "Somerville", "MA", 470_000, 3, 2, "house", "sale")).passes());
        assertEquals("it costs more than your budget of $400,000 to $550,000", HomeCut.judge(c, listing("1 High St", "Somerville", "MA", 600_000, 3, 2, "house", "sale")).why());
        assertEquals("it costs less than your budget of $400,000 to $550,000", HomeCut.judge(c, listing("2 High St", "Somerville", "MA", 300_000, 3, 2, "house", "sale")).why(), "outside the range either way is set aside");
        assertEquals("it has more bedrooms than the 4 you want at most", HomeCut.judge(c, listing("3 High St", "Somerville", "MA", 500_000, 5, 2, "house", "sale")).why());
        assertEquals("it has fewer bathrooms than the 2 you want", HomeCut.judge(c, listing("4 High St", "Somerville", "MA", 500_000, 3, 1, "house", "sale")).why());
        Home.Listing small = new Home.Listing(Home.idOf("5 High St", "Somerville", "MA", ""), "a test", "sale", "5 High St", "Somerville", "MA", "", 500_000, 3, 2, 1_000, "house", "", 0, 0, "", "", "", "");
        assertEquals("it is smaller than the 1,200 sq ft you want", HomeCut.judge(c, small).why());
        // the place: a listing in another town, within the radius of the centre on the map, is in the place
        Home.Listing near = new Home.Listing(Home.idOf("9 Beacon St", "Boston", "MA", ""), "a test", "sale", "9 Beacon St", "Boston", "MA", "", 500_000, 3, 2, 1_500, "house", "", 42.3584, -71.0636, "", "", "", "");
        assertTrue(HomeCut.judge(c, near).passes(), "4 km from the centre");
        Home.Listing far = new Home.Listing(Home.idOf("1 Main St", "Worcester", "MA", ""), "a test", "sale", "1 Main St", "Worcester", "MA", "", 500_000, 3, 2, 1_500, "house", "", 42.2626, -71.8023, "", "", "", "");
        assertEquals("it is not in a place you want", HomeCut.judge(c, far).why());
        // the order: nearest the middle of the budget first, not cheapest first
        assertTrue(HomeCut.offBudget(c, listing("a", "Somerville", "MA", 470_000, 3, 2, "house", "sale")) < HomeCut.offBudget(c, listing("b", "Somerville", "MA", 420_000, 3, 2, "house", "sale")));
        assertEquals(Double.MAX_VALUE, HomeCut.offBudget(c, listing("c", "Somerville", "MA", 0, 3, 2, "house", "sale")), "no price, last");
        HomeCut.Cut ceiling = new HomeCut.Cut(List.of(), List.of(), 0, 0, 0, "", r(0, 900_000), HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, HomeCut.Range.OPEN, List.of(), List.of(), List.of(), "", "");
        assertTrue(HomeCut.offBudget(ceiling, listing("a", "Somerville", "MA", 895_000, 3, 2, "house", "sale")) < HomeCut.offBudget(ceiling, listing("b", "Somerville", "MA", 500_000, 3, 2, "house", "sale")), "with a ceiling, nearest it from below first");
        assertEquals("it costs more than your budget of $900,000", HomeCut.judge(ceiling, listing("a", "Somerville", "MA", 950_000, 3, 2, "house", "sale")).why());
    }

    @Test void aLineOfTextIsReadForAListing() {
        Home.Listing l = HomeSources.fromText("saved by you", "12 Elm St, Somerville, MA 02143 · $875,000 · 3 bd · 2 ba · 1,600 sqft · house", "https://example.org/12-elm", "Somerville", "MA", "", "");
        assertNotNull(l);
        assertEquals("12 Elm St", l.address()); assertEquals("Somerville", l.city()); assertEquals("MA", l.state());
        assertEquals(875_000, l.price()); assertEquals(3, l.beds()); assertEquals(2, l.baths()); assertEquals(1600, l.sqft()); assertEquals("house", l.kind()); assertEquals("sale", l.tenure());
        assertNull(HomeSources.fromText("saved by you", "$875,000 · 3 bd · 2 ba, a nice place", "", "Somerville", "MA", "", ""), "a price with no address is not a home");
        Home.Listing r = HomeSources.fromText("saved by you", "Apartment at 40 Pine Ave, Medford, MA for $2,400/mo, 2 bed 1 bath", "", "Somerville", "MA", "", "");
        assertNotNull(r);
        assertEquals("rent", r.tenure(), "a price under 20,000 is a rent when nothing says");
        assertEquals(2_400, r.price()); assertEquals(2, r.beds());
        assertNull(HomeSources.fromText("x", "Homes for sale in Somerville - 143 results", "https://portal.example/somerville", "Somerville", "MA", "sale", ""), "a page of listings is not a listing");
        assertEquals(Home.idOf("12 Elm St", "Somerville", "MA", ""), Home.idOf("12 elm st", "somerville", "ma", "https://other.example/x"), "the same home from two sources is one");
        assertEquals("12 Elm St, Somerville, MA · $875,000 · 3 beds · 2 baths · 1,600 sq ft · house", l.line());
        // a snippet of a page of listings: each home takes the figures after its own address; a price before the first address is nobody's
        List<Home.Listing> two = HomeSources.homesIn("a snippet", "Homes for sale from $159,000 · 1094 Park Pl, Brooklyn, NY 11213 · $2,150,000 · 4 bds · 3 ba · 3,903 sqft · 2183 Ocean Ave #2E, Brooklyn, NY 11229. $725,000. 2 beds, 2 baths · condo", "https://portal.example/brooklyn", "Brooklyn", "NY", "sale", "");
        assertEquals(2, two.size());
        assertEquals(2_150_000, two.get(0).price()); assertEquals(4, two.get(0).beds()); assertEquals(3903, two.get(0).sqft());
        assertEquals("2183 Ocean Ave #2E", two.get(1).address()); assertEquals(725_000, two.get(1).price()); assertEquals(2, two.get(1).beds()); assertEquals("condo", two.get(1).kind());
        // an agent's page that writes the figures before each address: each home takes the figures before its own
        List<Home.Listing> page = HomeSources.homesIn("an agent", "Featured · $799,000 3 bd 1.5 ba 1,400 sqft 21 Walnut St, Somerville, MA 02143 · $1,250,000 4 bd 3 ba 2,200 sqft 9 Lake Rd, Medford, MA 02155 · Office: 1 Union Sq, Somerville, MA 02143", "https://agent.example/", "Somerville", "MA", "sale", "");
        assertEquals(2, page.size(), String.valueOf(page));
        assertEquals(799_000, page.get(0).price()); assertEquals(1.5, page.get(0).baths());
        assertEquals("9 Lake Rd", page.get(1).address()); assertEquals(1_250_000, page.get(1).price()); assertEquals(4, page.get(1).beds());
        // a price cut is not the price, and a unit is a home of its own
        List<Home.Listing> cut = HomeSources.homesIn("a snippet", "646 Guenevere Dr, Pittsburgh, PA 15237 · Arron Example · More · Price cut: $14,000 (10/7) Save · $325,000 · 3 bds · 2 ba · 1,595 sqftHouse for sale · 8210 Eleanor St, Pittsburgh, PA 15237 · Gina Example · More · Open: Sat", "https://portal.example/p", "Pittsburgh", "PA", "sale", "");
        assertEquals(2, cut.size()); assertEquals(325_000, cut.get(0).price()); assertEquals(3, cut.get(0).beds()); assertEquals(1595, cut.get(0).sqft()); assertEquals(0, cut.get(1).price());
        List<Home.Listing> units = HomeSources.homesIn("an agent", "33-35 Brookdale St, #1 Boston, MA 02131 2 Bed 1 Baths 990 sq ft $199,000 New 33-35 Brookdale St Boston, MA 02131 4 Bed 2 Baths 2,320 sq ft $360,000 Active", "https://agent.example/", "Boston", "MA", "sale", "");
        assertEquals(2, units.size(), String.valueOf(units));
        assertEquals("33-35 Brookdale St, #1", units.get(0).address()); assertEquals(199_000, units.get(0).price()); assertEquals(2, units.get(0).beds());
        assertEquals("33-35 Brookdale St", units.get(1).address()); assertEquals(360_000, units.get(1).price()); assertEquals(4, units.get(1).beds());
        assertTrue(HomeSources.ownPage("https://www.zillow.com/homedetails/12-elm/123_zpid/")); assertFalse(HomeSources.ownPage("https://www.zillow.com/somerville-ma/3-bedrooms/"));
    }

    static Home.Listing listing(String address, String city, String state, long price, double beds, double baths, String kind, String tenure) {
        return new Home.Listing(Home.idOf(address, city, state, ""), "a test", tenure, address, city, state, "", price, beds, baths, 0, kind, "", 0, 0, "", "", "", "");
    }
}
