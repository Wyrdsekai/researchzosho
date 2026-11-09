package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerSources.Opening;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The towns near a place, on stand-in Wikidata answers: a town counts by where its centre is, whatever its size, so Boston, across the
 * river, is near Cambridge, Massachusetts, for a home and for a job; what the size rule of before kept out, a region, a county or a
 * metropolitan area around the city, stays out. No web.
 */
class NearTownsTest {

    static final ObjectMapper M = new ObjectMapper();
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** Whether the answer carries the column that says a place is a city or a town; an answer of before did not. */
    volatile boolean townColumn = true;

    /** The entity search for Cambridge: the one in Massachusetts and the one in Maryland. */
    static final String CAMBRIDGES = """
            {"results": {"bindings": [
              {"c": {"value": "http://www.wikidata.org/entity/Q49111"}, "cLabel": {"value": "Cambridge"}, "cpop": {"value": "118403"}, "within": {"value": "United States; Massachusetts; Middlesex County"}, "countryLabel": {"value": "United States"}, "at": {"value": "Point(-71.106111111 42.375)"}},
              {"c": {"value": "http://www.wikidata.org/entity/Q1012753"}, "cLabel": {"value": "Cambridge"}, "cpop": {"value": "13096"}, "within": {"value": "Maryland; Dorchester County"}, "countryLabel": {"value": "United States"}, "at": {"value": "Point(-76.0788 38.5632)"}}]}}""";

    /** The places within 10 km of Cambridge, Massachusetts, largest first, as Wikidata answered on 2026-11-04 (a few of them). */
    static final String[][] AROUND = {{"Q1346561", "Melkite Greek Catholic Eparchy of Newton", "322755353", "false"}, {"Q1190137", "Greater Boston", "4941632", "false"},
            {"Q123566801", "Boston urban area", "4382009", "false"}, {"Q54072", "Suffolk County", "797936", "false"}, {"Q100", "Boston", "675647", "true"},
            {"Q49199", "Somerville", "81045", "true"}, {"Q7157211", "Neighborhood Nine", "12937", "true"}};

    String around() {
        ObjectNode root = M.createObjectNode();
        ArrayNode rows = root.putObject("results").putArray("bindings");
        for (String[] r : AROUND) {
            ObjectNode row = rows.addObject();
            row.putObject("t").put("value", "http://www.wikidata.org/entity/" + r[0]);
            row.putObject("tLabel").put("value", r[1]);
            row.putObject("pop").put("value", r[2]);
            if (townColumn) row.putObject("town").put("datatype", "http://www.w3.org/2001/XMLSchema#boolean").put("value", r[3]);
        }
        return root.toString();
    }

    @BeforeEach void standIns() {
        CareerSources.POLITE_MS = 0;
        CareerSources.RAW = url -> {
            String q = URLDecoder.decode(url.substring(url.indexOf("query=") + 6), StandardCharsets.UTF_8);
            asked.add(q);
            if (q.contains("EntitySearch")) return q.contains("mwapi:search \"Cambridge\"") ? CAMBRIDGES : "{\"results\": {\"bindings\": []}}";
            if (q.contains("wikibase:around")) return around();
            return null;
        };
        HomeCut.CENTRE = place -> null;
    }

    @AfterEach void back() {
        CareerSources.RAW = CareerSources::fetch; CareerSources.POLITE_MS = 1000; CareerSources.reset();
        CareerCut.NEAR = CareerCut::nearFromWikidata; HomeCut.CENTRE = HomeCut::centreFromMap; Career.DRIVE = Explain::drive;
    }

    @Test void aTownCountsByItsCentreWhateverItsSize() {
        CareerCut.Towns t = CareerCut.nearFromWikidata("Cambridge", List.of("Massachusetts"), 10);
        assertEquals(List.of("Boston", "Somerville", "Neighborhood Nine"), t.towns(),
                "Boston, five times Cambridge's people, is a city whose centre is 4 km away; the eparchy, Greater Boston, the urban area and the county are not towns");
        assertEquals(42.375, t.lat(), 1e-6);
        String q = asked.get(1);
        assertTrue(q.contains("wikibase:around") && q.contains("?pop > 236806") && q.contains("VALUES ?k { wd:Q515 wd:Q3957 }"),
                "only a place more than twice the city's people is asked whether it is a city or a town: " + q);
        // an answer without that column, as the query of before gave: a place more than twice the city's people stays out, as it did
        townColumn = false;
        assertEquals(List.of("Somerville", "Neighborhood Nine"), CareerCut.nearFromWikidata("Cambridge", List.of("Massachusetts"), 10).towns());
    }

    @Test void bostonIsNearCambridgeForAHome(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "cambridge-ma-buy");
        Home.setWant(store, s, "place", "decided", "Cambridge, Massachusetts", "told", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "told", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertEquals(List.of("Boston", "Somerville", "Neighborhood Nine"), c.nearby());
        // a lead from an agent's page with no place on the map: in Boston, so in the place
        Home.Listing boston = new Home.Listing(Home.idOf("55 Commonwealth Avenue", "Boston", "MA", ""), "RE/MAX", "sale", "55 Commonwealth Avenue", "Boston", "MA", "", 3_800_000, 5, 6, 10_803, "",
                "http://agent.example/", 0, 0, "", "", "", "");
        assertTrue(HomeCut.judge(c, boston).passes(), HomeCut.judge(c, boston).why());
        assertTrue(HomeCut.placeShown(c, boston), "a card may go to it");
        assertEquals("it is in Boston, Georgia, not Boston, Massachusetts",
                HomeCut.judge(c, new Home.Listing("y", "a test", "sale", "1 Main St", "Boston", "GA", "", 0, 0, 0, 0, "", "", 0, 0, "", "", "", "")).why(), "a Boston of another state is still elsewhere");
        String log = String.join("\n", Home.lately(store, s, 10));
        assertTrue(log.contains("Near Cambridge (United States, Massachusetts, Middlesex County, United States), within 10 km, these count as the place too: Boston, Somerville, Neighborhood Nine (from Wikidata)."), log);
    }

    @Test void bostonIsNearCambridgeForAJobAndAKeptCutLooksItsTownsUpAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = CareerCutTest.StandIn::new;
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        // a cut kept before this lookup: its towns as the size rule of before left them, and no mark of how they were looked up
        Cut before = new Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of("Cambridge"), "", List.of(), List.of(), List.of(), 0, 0, "you", "2026-11-01", "",
                List.of("Somerville", "Neighborhood Nine"), 10);
        ObjectNode old = CareerCut.json(before); old.remove("near_lookup");
        Files.createDirectories(CareerCut.file(store, s).getParent());
        M.writeValue(CareerCut.file(store, s).toFile(), old);
        Opening inBoston = CareerSources.opening("Co", "Software Engineer", "Boston, MA", false, "https://co.example/1", "", "", "t", "", "", "");
        assertFalse(CareerCut.judge(before, inBoston, List.of()).passes(), "the cut as it was kept set Boston aside");
        Career.DRIVE = () -> { throw new IllegalStateException("no model: the towns are looked up again without one"); };
        Cut c = CareerCut.current(store, s);
        assertEquals(List.of("Boston", "Somerville", "Neighborhood Nine"), c.nearby());
        assertEquals(10, c.nearKm(), "the distance the cut was kept with");
        assertEquals(List.of("Engineer"), c.titles(), "nothing else in the cut changes");
        assertEquals("you", c.by());
        assertEquals(CareerCut.NEAR_LOOKUP, M.readTree(CareerCut.file(store, s).toFile()).path("near_lookup").asInt(), "kept with the mark");
        assertTrue(CareerCut.judge(c, inBoston, List.of()).passes(), CareerCut.judge(c, inBoston, List.of()).why());
        // looked up once: the next search reads the kept cut as it is
        int n = asked.size();
        assertEquals(c, CareerCut.current(store, s));
        assertEquals(n, asked.size());
    }
}
