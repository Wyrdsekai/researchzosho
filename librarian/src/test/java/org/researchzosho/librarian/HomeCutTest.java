package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.HomeCut.Place;
import org.researchzosho.librarian.HomeCut.Range;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The home search's cut on stand-ins: a place is a town in its state, so the Cambridges of other states are set aside each with its own
 * reason; a price per square foot is a range of its own and never a budget; a listing that does not show something decided is a near
 * miss that says what. No web, no model, no map.
 */
class HomeCutTest {

    final List<List<String>> within = new CopyOnWriteArrayList<>();
    final List<String> centred = new CopyOnWriteArrayList<>();

    @BeforeEach void standIns() {
        CareerCut.NEAR = (city, w, km) -> { within.add(w); return new CareerCut.Towns("United States, Massachusetts, Middlesex County", List.of("Somerville", "Arlington"), 42.375, -71.106111); };
        HomeCut.CENTRE = place -> { centred.add(place); return null; };
    }

    @AfterEach void back() { CareerCut.NEAR = CareerCut::nearFromWikidata; HomeCut.CENTRE = HomeCut::centreFromMap; }

    static Range r(double min, double max) { return new Range(min, max); }

    static Home.Listing listing(String address, String city, String state, long price, double beds, double baths, int sqft, String kind) {
        return new Home.Listing(Home.idOf(address, city, state, ""), "a test", "sale", address, city, state, "", price, beds, baths, sqft, kind, "", 0, 0, "", "", "", "");
    }

    static HomeCut.Cut cut(List<String> places, List<String> nearby, Range price, Range perArea, String unit, Range beds, Range baths, Range sqft) {
        return new HomeCut.Cut(places, nearby, nearby.isEmpty() ? 0 : 10, 0, 0, "sale", price, perArea, unit, beds, baths, sqft, Range.OPEN, Range.OPEN, List.of(), List.of(), List.of(), "", "");
    }

    @Test void aTownAndItsStateAreOnePlace() {
        assertEquals(List.of(new Place("Cambridge", "MA", "Massachusetts", "Massachusetts")), HomeCut.places("Cambridge, Massachusetts"), "one place, not Cambridge or Massachusetts");
        for (String said : List.of("Cambridge, MA", "Cambridge MA", "Cambridge, Mass.", "Cambridge, ma", "Cambridge, MA 02139", "Cambridge, Middlesex County, Massachusetts", "Cambridge (MA)",
                "near Cambridge, MA", "within 10 km of Cambridge, MA", "the Cambridge area, Massachusetts", "Cambridge, Massachusetts, USA")) {
            List<Place> ps = HomeCut.places(said);
            assertEquals(1, ps.size(), said + ": " + ps);
            assertEquals("Cambridge", ps.get(0).town(), said);
            assertEquals("MA", ps.get(0).region(), said);
        }
        assertEquals(List.of(new Place("Somerville", "MA", "Massachusetts", "MA")), HomeCut.places("Somerville, MA, or the next towns within 8 km"), "how far is not a place");
        assertEquals(List.of(new Place("Portland", "OR", "Oregon", "OR")), HomeCut.places("Portland, OR"), "OR is Oregon, not a second place");
        assertEquals(List.of(new Place("Fort Wayne", "IN", "Indiana", "IN")), HomeCut.places("Fort Wayne, IN"));
        assertEquals(List.of(new Place("Ann Arbor", "MI", "Michigan", "MI")), HomeCut.places("within 5 mi of Ann Arbor, MI"), "MI is Michigan, not miles");
        Place on = HomeCut.places("Cambridge, Ontario, Canada").get(0);
        assertEquals(List.of("Cambridge", "ON", "province"), List.of(on.town(), on.region(), on.regionKind()));
        assertEquals(1, HomeCut.places("Cambridge, Ontario, Canada").size(), "the country of the province named is no place of its own");
        assertEquals("England", HomeCut.places("Cambridge, England").get(0).region());
        assertEquals("United Kingdom", HomeCut.places("Cambridge UK").get(0).region());
        // a region alone, when the person says so
        Place ma = HomeCut.places("anywhere in Massachusetts").get(0);
        assertTrue(ma.regionAlone()); assertEquals("MA", ma.region());
        assertTrue(HomeCut.places("Massachusetts").get(0).regionAlone());
        assertTrue(HomeCut.places("the state of New York").get(0).regionAlone());
        // a name that is a state's and a city's is the city, unless the words say the state
        assertEquals(List.of(new Place("New York", "", "", "")), HomeCut.places("New York"));
        assertTrue(HomeCut.places("anywhere in New York").get(0).regionAlone());
        assertEquals(List.of(new Place("New York", "NY", "New York", "NY")), HomeCut.places("New York, NY"));
        assertEquals(List.of(new Place("Albany", "NY", "New York", "New York")), HomeCut.places("Albany, New York"));
        assertEquals(List.of(new Place("Washington", "DC", "District of Columbia", "D.C.")), HomeCut.places("Washington, D.C."));
        assertEquals(List.of(new Place("Ontario", "CA", "California", "CA")), HomeCut.places("Ontario, CA"), "a town named as a province, in a state");
        assertEquals(List.of(new Place("Port Washington", "", "", "")), HomeCut.places("Port Washington"), "a town whose name ends in a state's");
        // two places; one state named for a list of towns is each town's
        List<Place> two = HomeCut.places("Somerville, Medford or Cambridge, MA");
        assertEquals(List.of("Somerville", "Medford", "Cambridge"), two.stream().map(Place::town).toList());
        assertTrue(two.stream().allMatch(p -> p.region().equals("MA")), two.toString());
        List<Place> apart = HomeCut.places("Cambridge, MA or Cambridge, England");
        assertEquals(List.of("MA", "England"), apart.stream().map(Place::region).toList());
        List<Place> orState = HomeCut.places("Cambridge or anywhere in New Hampshire");
        assertEquals(2, orState.size(), orState.toString());
        assertEquals("", orState.get(0).region(), "a state said as a place of its own is not the town's");
        assertTrue(orState.get(1).regionAlone());
        // kept by their words, read back the same
        for (String said : List.of("Cambridge, Massachusetts", "Somerville, MA", "anywhere in New York", "New York", "Ontario, CA", "Cambridge, Ontario, Canada", "Boston or Cambridge, MA")) {
            List<Place> ps = HomeCut.places(said);
            assertEquals(ps, HomeCut.places(ps.stream().map(Place::key).toList()), said);
        }
    }

    @Test void aStateAfterAListOfTownsIsATownsOnlyWhereATownOfThatNameIs(@TempDir Path tmp) throws Exception {
        CareerCut.NEAR = (city, w, km) -> {
            within.add(List.of(city + " in " + String.join("+", w)));
            return switch (city) {
                case "Boston" -> new CareerCut.Towns("United States, Massachusetts, Suffolk County", List.of("Cambridge", "Somerville"), 42.3601, -71.0589);   // the largest Boston; none is in Rhode Island
                case "Providence" -> new CareerCut.Towns("United States, Rhode Island, Providence County", List.of("Pawtucket", "Cranston"), 41.824, -71.4128);
                default -> new CareerCut.Towns("United States, Massachusetts, Middlesex County", List.of("Arlington"), 42.39, -71.1);
            };
        };
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Boston or Providence, RI", "told", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertEquals(List.of("Boston, MA", "Providence, RI"), c.places(), "no Boston is in Rhode Island: Boston keeps the state of the largest town of its name");
        assertEquals(List.of(List.of("Boston in Rhode Island"), List.of("Providence in Rhode Island")), within, "Boston is asked for once, and its towns near come from that answer");
        assertEquals(List.of("Cambridge", "Somerville", "Pawtucket", "Cranston"), c.nearby());
        String log = String.join("\n", Home.lately(store, s, 10));
        assertTrue(log.contains("No town called Boston was found in Rhode Island, the state written after the list of towns, so the search takes Boston to be the one in Massachusetts, the largest town of that name. If you mean a different Boston, write its state right after it."), log);
        assertTrue(HomeCut.judge(c, listing("1 Beacon St", "Boston", "MA", 0, 0, 0, 0, "")).passes(), "a home in Boston, Massachusetts is in the place");
        assertTrue(HomeCut.judge(c, listing("10 Hope St", "Providence", "RI", 0, 0, 0, 0, "")).passes());
        assertEquals(List.of("Boston", "MA"), List.of(HomeFind.cityState(store, s, c)), "the sources are read for Boston, Massachusetts");
        // a list whose towns are all in the state written after it: each town has it, and nothing is said
        Home.setWant(store, s, "place", "decided", "Somerville, Medford or Cambridge, MA", "told", "");
        assertEquals(List.of("Somerville, MA", "Medford, MA", "Cambridge, MA"), HomeCut.current(store, s).places());
        assertFalse(String.join("\n", Home.lately(store, s, 10)).contains("No town called Somerville"));
        // when Wikidata does not answer, the state is given as written
        CareerCut.NEAR = (city, w, km) -> new CareerCut.Towns("", List.of());
        LibraryStore other = new LibraryStore(tmp.resolve("lib2")); other.init();
        Home.Search t = Home.start(other, "Ada Example");
        Home.setWant(other, t, "place", "decided", "Boston or Providence, RI", "told", "");
        assertEquals(List.of("Boston, RI", "Providence, RI"), HomeCut.current(other, t).places());
        // read back from the cut's words, the places are the same
        assertEquals(List.of(new Place("Boston", "MA", "Massachusetts", "MA"), new Place("Providence", "RI", "Rhode Island", "RI")), HomeCut.places(c.places()));
    }

    @Test void aNearMissThatShowsMoreOfWhatWasDecidedComesFirst() {
        HomeCut.Cut c = cut(List.of("Cambridge, Massachusetts"), List.of(), Range.OPEN, r(0, 850), HomeCut.SQ_FT, r(3, 4), r(2, 0), r(2_000, 3_500));
        assertEquals(0, HomeCut.notShown(c, listing("170 Garden St", "Cambridge", "MA", 1_800_000, 4, 2, 2_310, "house")));
        assertEquals(1, HomeCut.notShown(c, listing("1783 Massachusetts Ave #B", "Cambridge", "MA", 1_595_000, 4, 3, 0, "")), "its size alone");
        assertEquals(4, HomeCut.notShown(c, listing("159 Brattle St", "Cambridge", "MA", 0, 0, 0, 0, "land")), "the price, the size, the bedrooms and the bathrooms");
        assertEquals(2, HomeCut.notShown(c, listing("2 High St", "Cambridge", "MA", 0, 3, 2, 0, "house")), "a price per square foot needs the price and the size, each counted");
        assertEquals(1, HomeCut.notShown(c, listing("5 Main St", "Cambridge", "", 1_000_000, 3, 2, 2_000, "house")), "the state of a town of the name");
        assertEquals(0, HomeCut.notShown(cut(List.of(), List.of(), Range.OPEN, Range.OPEN, HomeCut.SQ_FT, Range.OPEN, Range.OPEN, Range.OPEN), listing("6 Main St", "Cambridge", "MA", 0, 0, 0, 0, "")),
                "nothing decided, nothing missing");
    }

    @Test void theCambridgesOfOtherStatesAreSetAsideEachWithItsOwnReason() {
        HomeCut.Cut c = cut(List.of("Cambridge, Massachusetts"), List.of("Somerville", "Arlington"), Range.OPEN, Range.OPEN, HomeCut.SQ_FT, Range.OPEN, Range.OPEN, Range.OPEN);
        assertEquals("it is in Cambridge, Maryland, not Cambridge, Massachusetts", HomeCut.judge(c, listing("1022 River Point Rd", "Cambridge", "MD", 0, 0, 0, 0, "")).why());
        assertEquals("it is in Cambridge, Wisconsin, not Cambridge, Massachusetts", HomeCut.judge(c, listing("825-827 Vineyard Ct", "Cambridge", "WI", 0, 0, 0, 0, "")).why());
        assertEquals("it is in Cambridge, Ontario, not Cambridge, Massachusetts", HomeCut.judge(c, listing("687 Champlain Blvd", "Cambridge", "ON", 0, 0, 0, 0, "")).why());
        assertEquals("it is in Arlington, Virginia, not Arlington, Massachusetts", HomeCut.judge(c, listing("9 Lee Hwy", "Arlington", "VA", 0, 0, 0, 0, "")).why(), "a town near the place, of a name another state has too");
        assertTrue(HomeCut.judge(c, listing("108 Auburn St", "Cambridge", "MA", 0, 0, 0, 0, "")).passes());
        assertTrue(HomeCut.judge(c, listing("108 Auburn St", "Cambridge", "Massachusetts", 0, 0, 0, 0, "")).passes(), "the state by its name");
        assertTrue(HomeCut.judge(c, listing("14 Elm St", "Somerville", "MA", 0, 0, 0, 0, "")).passes(), "a town within the radius");
        assertTrue(HomeCut.placeShown(c, listing("14 Elm St", "Somerville", "MA", 0, 0, 0, 0, "")));
        assertEquals("it is not in a place you want", HomeCut.judge(c, listing("12 Cambridge St", "Boston", "MA", 0, 0, 0, 0, "")).why(), "a street named for the town is not the town");
        // a town of the name with no state given: never set aside for it, never a fit, never a card
        Home.Listing noState = listing("5 Main St", "Cambridge", "", 900_000, 3, 2, 1_500, "house");
        assertTrue(HomeCut.judge(c, noState).passes());
        assertEquals(List.of("state not given"), HomeCut.unknown(c, noState));
        assertEquals(1, HomeCut.tier(c, noState));
        assertFalse(HomeCut.placeShown(c, noState));
        // on the map within the radius, whatever the town is called; a Cambridge far away is still in another state
        HomeCut.Cut placed = new HomeCut.Cut(c.places(), c.nearby(), 10, 42.375, -71.106111, "sale", Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, List.of(), List.of(), List.of(), "", "");
        assertTrue(HomeCut.judge(placed, new Home.Listing("x", "RentCast", "sale", "1 Beacon St", "Boston", "MA", "", 0, 0, 0, 0, "", "", 42.3584, -71.0636, "", "", "", "")).passes(), "4 km from the centre");
        assertEquals("it is in Cambridge, Maryland, not Cambridge, Massachusetts", HomeCut.judge(placed, new Home.Listing("y", "RentCast", "sale", "702 Travers St", "Cambridge", "MD", "", 685_000, 4, 3, 2_316, "house", "", 38.5632, -76.0788, "", "", "", "")).why());
        // a region alone, when the person said so
        HomeCut.Cut state = cut(List.of("anywhere in Massachusetts"), List.of(), Range.OPEN, Range.OPEN, HomeCut.SQ_FT, Range.OPEN, Range.OPEN, Range.OPEN);
        assertTrue(HomeCut.judge(state, listing("1 Main St", "Worcester", "MA", 0, 0, 0, 0, "")).passes());
        assertTrue(HomeCut.placeShown(state, listing("1 Main St", "Worcester", "MA", 0, 0, 0, 0, "")));
        assertEquals("it is in Cambridge, Maryland, not in Massachusetts", HomeCut.judge(state, listing("702 Travers St", "Cambridge", "MD", 0, 0, 0, 0, "")).why());
        assertEquals("anywhere in Massachusetts", HomeCut.summary(state).replace("to buy; ", ""));
        // a town said with no state is any town of the name, as before
        HomeCut.Cut any = cut(List.of("Cambridge"), List.of(), Range.OPEN, Range.OPEN, HomeCut.SQ_FT, Range.OPEN, Range.OPEN, Range.OPEN);
        assertTrue(HomeCut.judge(any, listing("702 Travers St", "Cambridge", "MD", 0, 0, 0, 0, "")).passes());
    }

    @Test void theCutKnowsWhichCambridgeAndCentresOnIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Cambridge, Massachusetts", "told", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "told", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertEquals(List.of("Cambridge, Massachusetts"), c.places());
        assertEquals(List.of(List.of("Massachusetts")), within, "the town is looked up once, with its state's name, which picks the Cambridge of Middlesex County");
        assertEquals(List.of("Somerville", "Arlington"), c.nearby());
        assertTrue(c.placed());
        assertEquals("to buy; in Cambridge, Massachusetts, or one of 2 towns within 10 km", HomeCut.summary(c));
        String log = String.join("\n", Home.lately(store, s, 10));
        assertTrue(log.contains("The search is centred on Cambridge, Massachusetts, within 10 km of it."), log);
        assertEquals(List.of("Cambridge", "MA"), List.of(HomeFind.cityState(store, s, c)), "the sources are read for Cambridge, MA");
        // with no centre from Wikidata, the map is asked for the town in its state
        CareerCut.NEAR = (city, w, km) -> new CareerCut.Towns("", List.of());
        LibraryStore other = new LibraryStore(tmp.resolve("lib2")); other.init();
        Home.Search t = Home.start(other, "Ada Example");
        Home.setWant(other, t, "place", "decided", "Cambridge, MA", "you", "");
        HomeCut.current(other, t);
        assertEquals(List.of("Cambridge, Massachusetts"), centred);
    }

    @Test void aPricePerSquareFootIsItsOwnRangeAndNeverABudget(@TempDir Path tmp) throws Exception {
        assertEquals(r(0, 850), HomeCut.perAreaOf("under $850 a square foot").range());
        assertEquals(HomeCut.SQ_FT, HomeCut.perAreaOf("under $850 a square foot").unit());
        assertEquals(r(0, 850), HomeCut.perAreaOf("under $850 per square foot").range());
        assertEquals(r(0, 800), HomeCut.perAreaOf("$800/sq ft").range());
        assertEquals(r(0, 800), HomeCut.perAreaOf("$800 / sqft").range());
        assertEquals(r(600, 800), HomeCut.perAreaOf("between $600 and $800 per square foot").range());
        assertEquals(r(0, 850), HomeCut.perAreaOf("$850 psf").range());
        assertEquals(r(500, 0), HomeCut.perAreaOf("at least $500 a sq. ft.").range());
        assertEquals(r(0, 9_000), HomeCut.perAreaOf("under €9,000 per m²").range());
        assertEquals(HomeCut.SQ_M, HomeCut.perAreaOf("under €9,000 per m²").unit());
        assertEquals(HomeCut.SQ_M, HomeCut.perAreaOf("at most $9,000 a square metre").unit());
        assertNull(HomeCut.perAreaOf("$900,000"), "a total is no price per area");
        assertNull(HomeCut.perAreaOf("1,200 to 1,800 sq ft"), "a size is no price");
        assertEquals(r(0, 1_500_000), HomeCut.range(HomeCut.withoutPerArea("up to $1.5M, under $850 a square foot"), true), "a total said beside it stays the budget");
        assertEquals(Range.OPEN, HomeCut.range(HomeCut.withoutPerArea("under $850 per square foot"), true), "never a budget of $850");
        // the cut made from the topics as the person's words set them
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Cambridge, Massachusetts", "told", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "told", "");
        Home.setWant(store, s, "budget", "decided", "under $850 per square foot", "told", "");
        Home.setWant(store, s, "bedrooms", "decided", "three or four", "told", "");
        Home.setWant(store, s, "bathrooms", "decided", "two or more", "told", "");
        Home.setWant(store, s, "size", "decided", "between 2000 and 3500 square feet", "told", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertTrue(c.price().open(), "no total budget: " + c.price());
        assertEquals(r(0, 850), c.perArea()); assertEquals(HomeCut.SQ_FT, c.perAreaUnit());
        assertEquals(r(3, 4), c.beds(), "three or four"); assertEquals(r(2, 0), c.baths(), "two or more"); assertEquals(r(2_000, 3_500), c.sqft());
        assertEquals("to buy; in Cambridge, Massachusetts, or one of 2 towns within 10 km; up to $850 a square foot; 3 to 4 bedrooms; at least 2 bathrooms; 2,000 to 3,500 sq ft", HomeCut.summary(c));
        assertEquals(c, HomeCut.kept(store, s), "kept as made, the price per area too");
        assertEquals(r(0, 2_975_000), HomeCut.priceBound(c), "the most a square foot times the largest size");
        assertTrue(HomeSources.rentCastQuery(c, "Cambridge", "MA", 0).contains("&price=*:2975000&bedrooms=3:4&bathrooms=2:*&squareFootage=2000:3500"), HomeSources.rentCastQuery(c, "Cambridge", "MA", 0));
        // judged as the price over the size when a listing gives both
        Home.Listing banks = listing("136 Banks St", "Cambridge", "MA", 1_670_000, 4, 4, 2_327, "house");
        assertTrue(HomeCut.judge(c, banks).passes(), "$718 a square foot");
        assertEquals(0, HomeCut.tier(c, banks));
        assertEquals("it costs $1,250 a square foot, more than the $850 a square foot you want at most", HomeCut.judge(c, listing("1 High St", "Cambridge", "MA", 3_000_000, 4, 3, 2_400, "house")).why());
        // a lead that does not give the price is a near miss that says so, never a fit, never set aside for it
        Home.Listing auburn = listing("108 Auburn St", "Cambridge", "MA", 0, 0, 0, 0, "");
        assertTrue(HomeCut.judge(c, auburn).passes());
        assertEquals(1, HomeCut.tier(c, auburn));
        assertEquals(List.of("price per square foot not known: neither the price nor the size is given", "bedrooms not given", "bathrooms not given"), HomeCut.unknown(c, auburn));
        assertEquals(List.of("price per square foot not known: the price is not given"), HomeCut.unknown(c, listing("2 High St", "Cambridge", "MA", 0, 3, 2, 2_400, "house")));
        assertEquals(List.of("price per square foot not known: the size is not given"), HomeCut.unknown(c, listing("3 High St", "Cambridge", "MA", 1_900_000, 3, 2, 0, "house")));
        // a range, and the metre
        HomeCut.Cut ranged = cut(List.of(), List.of(), Range.OPEN, r(600, 800), HomeCut.SQ_FT, Range.OPEN, Range.OPEN, Range.OPEN);
        assertEquals("it costs $500 a square foot, less than the $600 a square foot you want at least", HomeCut.judge(ranged, listing("4 High St", "Cambridge", "MA", 1_000_000, 3, 2, 2_000, "house")).why());
        assertEquals("from $600 to $800 a square foot", HomeCut.summary(ranged).replace("to buy; ", ""));
        HomeCut.Cut metric = cut(List.of(), List.of(), Range.OPEN, r(0, 9_000), HomeCut.SQ_M, Range.OPEN, Range.OPEN, Range.OPEN);
        assertEquals("it costs $10,764 a square metre, more than the $9,000 a square metre you want at most", HomeCut.judge(metric, listing("5 High St", "Cambridge", "MA", 1_000_000, 3, 2, 1_000, "house")).why());
        // the order, with only a price per area decided: nearest its ceiling from below first, one not known last
        List<Home.Listing> order = new ArrayList<>(List.of(auburn, listing("6 High St", "Cambridge", "MA", 1_000_000, 3, 2, 2_000, "house"), listing("7 High St", "Cambridge", "MA", 1_680_000, 3, 2, 2_000, "house")));
        order.sort((a, b) -> Double.compare(HomeCut.offBudget(c, a), HomeCut.offBudget(c, b)));
        assertEquals(List.of("7 High St", "6 High St", "108 Auburn St"), order.stream().map(Home.Listing::address).toList());
    }
}
