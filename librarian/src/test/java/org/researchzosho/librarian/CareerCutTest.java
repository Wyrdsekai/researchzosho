package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerSources.Opening;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The cut: each decided topic that a title can show is a set of words, and a title must show every set given. */
class CareerCutTest {

    static String answer;

    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) { return m.get(0).path("content").asText().startsWith("Turn what a person wants") ? answer : "[]"; }
    }

    static Opening titled(String title) { return CareerSources.opening("Co", title, "Boston, MA", false, "https://co.example/" + title.hashCode(), "", "", "t", "", "", ""); }

    @Test void aPlaceDecidedAsACityMeansTheTownsNearItToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        List<String> looked = new ArrayList<>();
        CareerCut.NEAR = (city, within, km) -> { looked.add(city + "/" + km + "/" + String.join("+", within)); return new CareerCut.Towns(city.equals("Lyon") ? "Auvergne-Rhône-Alpes, France" : "", city.equals("Lyon") ? List.of("Villeurbanne", "Vénissieux", "Lyon") : List.of()); };
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "place", "decided", "Lyon, or within 20 miles of it");
            answer = "{\"role_titles\": [\"Engineer\"], \"ownership_titles\": [], \"titles_not\": [], \"areas\": [], \"places\": [\"Lyon\", \"Rhône\", \"FR\"], \"remote\": \"no\", \"remote_where\": [], \"max_people\": 0, \"min_people\": 0, \"prefer_titles\": [], \"prefer_places\": []}";
            Cut c = CareerCut.make(store, s);
            assertEquals(List.of("Villeurbanne", "Vénissieux"), c.nearby(), "the towns near each city, the city itself not twice"); assertEquals(32, c.nearKm(), "20 miles, in kilometres");
            assertEquals(List.of("Lyon/32/Rhône+FR+Chicago+Illinois", "Rhône/32/Lyon+FR+Chicago+Illinois"), looked, "a two-letter code is no city's name; the rest are looked up, each with the other places decided and then where the record puts the person, which say which city it is");
            assertTrue(CareerCut.judge(c, CareerSources.opening("Co", "Software Engineer", "Villeurbanne, France", false, "https://co.example/1", "", "", "t", "", "", ""), List.of()).passes(), "near Lyon counts as Lyon");
            assertFalse(CareerCut.judge(c, CareerSources.opening("Co", "Software Engineer", "Marseille, France", false, "https://co.example/2", "", "", "t", "", "", ""), List.of()).passes());
            assertTrue(CareerCut.summary(c).contains("in Lyon, Rhône or FR, or one of 2 towns within 32 km"), CareerCut.summary(c));
            assertEquals(c, CareerCut.kept(store, s), "the towns are kept with the cut");
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("Near Lyon (Auvergne-Rhône-Alpes, France), within 32 km, these count as the place too: Villeurbanne, Vénissieux (from Wikidata)."));
            // the towns are kept with the library for a month: the same city is not asked again
            CareerCut.NEAR = (city, within, km) -> { throw new IllegalStateException("asked again"); };
            Cut mine = CareerCut.set(store, s, Map.of("places", "Lyon"));
            assertEquals(List.of("Villeurbanne", "Vénissieux"), mine.nearby()); assertEquals(32, mine.nearKm());
            assertEquals(List.of(), CareerCut.set(store, s, Map.of("places", "Massachusetts", "near_km", "30")).nearby(), "a state is no city: the stand-in gives nothing for it, and the library asked once");
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void aDivisionIsNoTownAnOpeningIsIn() {
        for (String label : List.of("Archdiocese of Newark", "Archdiocese of New York", "Middlesex County", "Greater London Urban Area", "Diocese of Lyon", "Canton de Vaud"))
            assertTrue(CareerCut.DIVISION.matcher(label).find(), label);
        for (String label : List.of("Newark", "Brooklyn", "Jersey City", "Somerville"))
            assertFalse(CareerCut.DIVISION.matcher(label).find(), label);
    }

    @Test void theCityOfThatNameIsTheOneTheOtherPlacesSay() {
        List<CareerCut.Candidate> cambridges = List.of(new CareerCut.Candidate("Q182272", 138479, "Canada; Ontario; Regional Municipality of Waterloo; Canada"), new CareerCut.Candidate("Q350", 123867, "England; United Kingdom; Cambridgeshire; East of England; United Kingdom"),
                new CareerCut.Candidate("Q49111", 118403, "United States; Massachusetts; Middlesex County; United States"), new CareerCut.Candidate("Q1012753", 13096, "United States; Maryland; Dorchester County; United States"));
        assertEquals("Q49111", CareerCut.choose(cambridges, List.of("Boston", "MA", "Massachusetts")).item(), "Massachusetts says which Cambridge");
        assertEquals("Q49111", CareerCut.choose(cambridges, List.of("Cambridge", "Massachusetts", "United States")).item(), "the record's own place says it too");
        assertEquals("Q49111", CareerCut.choose(cambridges, List.of("MA", "Massachusetts", "London", "United Kingdom")).item(), "the places decided outrank where the record puts the person");
        assertEquals("Q350", CareerCut.choose(cambridges, List.of("UK")).item(), "a short form of the country");
        assertEquals("Q182272", CareerCut.choose(cambridges, List.of("MA")).item(), "a two-letter code names nothing on Wikidata's labels: the most populous, as before");
        assertEquals("Q182272", CareerCut.choose(cambridges, List.of()).item());
        // the name search matches by prefix: the settlement whose label is the name comes before a region that only begins with it, and before namesakes elsewhere
        List<CareerCut.Candidate> sanJoses = List.of(new CareerCut.Candidate("Q213205", "San Francisco Bay Area", 7765640, "United States; California; United States"), new CareerCut.Candidate("Q16553", "San Jose", 1013240, "United States; California; Santa Clara County; United States"),
                new CareerCut.Candidate("Q3070", "San José", 335007, "Costa Rica; San José Province; Costa Rica"), new CareerCut.Candidate("Q492619", "San Jose de Buenavista", 69522, "Antique; Philippines; Western Visayas; Philippines"));
        assertEquals("Q16553", CareerCut.choose(sanJoses, "San Jose", List.of("San Francisco", "Palo Alto", "California")).item(), "the Bay Area is no city, whatever its people");
        assertEquals("Q3070", CareerCut.choose(sanJoses, "San José", List.of("Costa Rica")).item(), "accents aside, the other places decide among the namesakes");
        assertEquals("Q16553", CareerCut.choose(sanJoses, "san jose", List.of()).item(), "with nothing to say which, the most populous of the name");
        assertEquals("Q213205", CareerCut.choose(sanJoses, "Bay", List.of()).item(), "a name no label is: the most populous of all, as before");
    }

    @Test void aPlaceDecidedBesideRemoteMeansRemoteCountsNotRemoteOnly(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("", List.of());
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "place", "decided", "Boston or Cambridge, MA");
            Career.setWant(store, s, "remote", "decided", "remote");
            answer = "{\"role_titles\": [\"Engineer\"], \"ownership_titles\": [], \"titles_not\": [], \"areas\": [], \"places\": [\"Boston\", \"Cambridge\", \"MA\"], \"remote\": \"only\", \"remote_where\": [], \"max_people\": 0, \"min_people\": 0, \"prefer_titles\": [], \"prefer_places\": []}";
            Cut c = CareerCut.make(store, s);
            assertEquals("fine", c.remote(), "the model said only; a place decided beside remote means remote counts");
            assertTrue(CareerCut.judge(c, CareerSources.opening("Co", "Software Engineer", "Boston, MA", false, "https://co.example/1", "", "", "t", "", "", ""), List.of()).passes(), "in the place, not remote: passes");
            assertTrue(CareerCut.judge(c, CareerSources.opening("Co", "Software Engineer", "Remote", true, "https://co.example/2", "", "", "t", "", "", ""), List.of()).passes(), "remote: passes");
            assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("You decided a place and remote both, so remote openings count beside the ones in your place."));
            // a cut the library made before this rule, kept as remote only beside places, is read as remote counts; the person's own "only" stands
            CareerCut.save(store, s, new Cut(c.titles(), c.titlesAlso(), c.titlesNot(), c.areas(), c.places(), "only", c.remoteWhere(), c.preferTitles(), c.preferPlaces(), 0, 0, "library", c.at(), c.basis()));
            assertEquals("fine", CareerCut.kept(store, s).remote());
            CareerCut.save(store, s, new Cut(c.titles(), c.titlesAlso(), c.titlesNot(), c.areas(), c.places(), "only", c.remoteWhere(), c.preferTitles(), c.preferPlaces(), 0, 0, "you", c.at(), c.basis()));
            assertEquals("only", CareerCut.kept(store, s).remote());
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void aFoundingRoleThatTouchesTheCodeNeedsBoth(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "kind of work", "decided", "touch the code");
            Career.setWant(store, s, "ownership", "decided", "founding or co-founding roles");
            answer = "{\"role_titles\": [\"Engineer\", \"Engineering\", \"CTO\"], \"ownership_titles\": [\"Founding\", \"Co-founder\", \"Founder\"], \"titles_not\": [], \"places\": [], \"remote\": \"\"}";
            Cut c = CareerCut.make(store, s);
            assertEquals(List.of("Engineer", "Engineering", "CTO", "Chief Technology Officer"), c.titles(), "the long form beside the abbreviation");
            assertEquals(List.of("Founding", "Co-founder", "Founder"), c.titlesAlso());
            assertTrue(CareerCut.judge(c, titled("Founding Engineer"), List.of()).passes());
            assertTrue(CareerCut.judge(c, titled("Co-founder & CTO"), List.of()).passes());
            assertEquals("its title has none of the second words you want", CareerCut.judge(c, titled("Principal Software Engineer"), List.of()).why(), "an employee role is not a founding one");
            assertFalse(CareerCut.judge(c, titled("Founding Account Executive"), List.of()).passes(), "a founding role that is not the work decided");
            assertEquals("titles with Engineer, Engineering or CTO and with Founding, Co-founder or Founder", CareerCut.summary(c));
            assertEquals(List.of("\"Engineer\" \"Founding\"", "\"Engineer\" \"Co-founder\"", "\"Engineering\" \"Founding\"", "\"Engineering\" \"Co-founder\""), CareerFind.searchTerms(c));
            assertEquals(List.of(), CareerFind.searchPlaces(c), "no place decided: the discovery searches everywhere");
            assertEquals(List.of("Boston", "MA", "Remote"), CareerFind.searchPlaces(new Cut(List.of(), List.of(), List.of(), List.of(), List.of("Boston", "MA", "Cambridge"), "fine", List.of(), List.of(), List.of("Denver"), 0, 0, "library", "", "")),
                    "a decided place, and remote when it counts; never the guessed one");

            answer = "{\"role_titles\": [], \"ownership_titles\": [\"Founding\", \"Founder\"], \"places\": []}";
            c = CareerCut.make(store, s);
            assertEquals(List.of("Founding", "Founder"), c.titles(), "a founding role alone is the one set");
            assertTrue(c.titlesAlso().isEmpty());
            assertTrue(CareerCut.judge(c, titled("Founding Account Executive"), List.of()).passes());

            answer = "{\"titles\": [\"VP\"], \"titles_also\": [\"Engineering\"]}";   // the older form of the answer is read too
            c = CareerCut.make(store, s);
            assertEquals(List.of("VP", "Vice President"), c.titles()); assertEquals(List.of("Engineering"), c.titlesAlso());

            // the person told the search which roles to look for, naming no founding role: those pass on their own, founding ones come first
            ObjectNode iv = CareerInterview.state(store, s);
            String lookFor = CareerInterview.questions(iv).stream().filter(q -> q.tag().equals(CareerInterview.LOOK_FOR)).findFirst().orElseThrow().n();
            CareerInterview.saveAnswers(store, s, Map.of(lookFor, "CTO, CEO, VP, SVP: I want executive power, over a division or a research centre."));
            answer = "{\"role_titles\": [\"CTO\", \"CEO\", \"VP\", \"SVP\", \"Executive\"], \"ownership_titles\": [\"Founding\", \"Co-founder\"], \"areas\": [\"Engineering\", \"Technology\"], \"prefer_titles\": [\"Engineering\"]}";
            c = CareerCut.make(store, s);
            assertEquals(List.of("CTO", "Chief Technology Officer", "CEO", "Chief Executive Officer", "VP", "Vice President", "SVP", "Senior Vice President"), c.titles(), "each abbreviation with its long form; Executive is no role on its own");
            assertTrue(c.titlesAlso().isEmpty(), "the roles named pass on their own");
            assertTrue(CareerCut.judge(c, titled("Chief Technology Officer"), List.of()).passes()); assertFalse(CareerCut.judge(c, titled("Account Executive"), List.of()).passes());
            assertEquals("its title gives a rank without the area you work in", CareerCut.judge(c, titled("Regional Vice President"), List.of()).why(), "a VP is a VP of something");
            assertTrue(CareerCut.judge(c, titled("Vice President, Technology"), List.of()).passes()); assertTrue(CareerCut.judge(c, titled("CEO"), List.of()).passes(), "a role by itself needs no area");
            assertEquals(List.of("Engineering", "Founding", "Co-founder"), c.preferTitles(), "founding roles go first in the order");
            assertTrue(CareerCut.judge(c, titled("VP of Engineering"), List.of()).passes());
            assertEquals("titles with CTO or CEO, or VP or SVP of Engineering or Technology; first in the order: titles with Engineering, Founding or Co-founder", CareerCut.summary(c), "the long forms stay out of the sentence");
            assertEquals(List.of("\"CTO\"", "\"CEO\"", "\"VP\" \"Engineering\""), CareerFind.searchTerms(c), "a VP is searched for with the area the person's roles name");
            CareerCut.Verdict ok = CareerCut.judge(c, titled("CTO"), List.of());
            int cto = CareerCut.rank(c, titled("CTO"), ok, Set.of(), Set.of(), LocalDate.now()), field = CareerCut.rank(c, titled("Field CTO"), ok, Set.of(), Set.of(), LocalDate.now()),
                    office = CareerCut.rank(c, titled("Analyst, CEO Office"), ok, Set.of(), Set.of(), LocalDate.now());
            assertTrue(cto > field && cto > office, "a role beside an executive is read after the executives: " + cto + " " + field + " " + office);

        } finally { Career.DRIVE = Explain::drive; }
    }
}
