package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    static String answer, asked;

    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) {
            if (!m.get(0).path("content").asText().startsWith("Turn what a person wants")) return "[]";
            asked = m.get(0).path("content").asText();
            return answer;
        }
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

    static Cut wanting(List<String> words, List<String> searchTitles, List<String> alsoTitles) {
        return new Cut(words, List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "library", "", "", List.of(), 0, searchTitles, alsoTitles);
    }

    @Test void aTitlePassesWhenItHoldsEveryWordThatTellsTheWorkOfATitleWanted() {
        // the cut's single words passed a generic word at every employer: 68 other engineers among 118 openings for a security engineer (0.8.4)
        Cut security = wanting(List.of("Engineer", "Architect"), List.of("Security Engineer", "Security Architect"), List.of("DevSecOps Engineer"));
        for (String t : List.of("Senior Security Software Engineer", "Engineer, Cloud Security", "Cybersecurity Engineer II", "Information Security Platform Architect", "Sr. DevSecOps Engineer"))
            assertTrue(CareerCut.judge(security, titled(t), List.of()).passes(), t);
        for (String t : List.of("Platform Engineer", "Traffic Engineer, PE", "Security Analyst", "Solutions Architect"))
            assertFalse(CareerCut.judge(security, titled(t), List.of()).passes(), t);
        assertEquals("its title is none of the titles you want (Security Engineer, Security Architect, DevSecOps Engineer)", CareerCut.judge(security, titled("Platform Engineer"), List.of()).why());
        Opening secops = CareerSources.opening("Harbor Security", "SecOps Engineer - North region", "Remote", true, "https://co.example/secops", "", "", "t", "", "", "");
        assertTrue(CareerCut.judge(security, secops, List.of()).passes(), "a firm named for the specialty leaves it out of its titles: its name holds the word");
        assertFalse(CareerCut.judge(security, CareerSources.opening("Harbor Security", "Account Executive", "Remote", true, "https://co.example/ae", "", "", "t", "", "", ""), List.of()).passes(), "the title still holds a word of it");
        Cut nurse = wanting(List.of("Registered", "Nurse"), List.of("Registered Nurse"), List.of());
        for (String t : List.of("Registered Nurse - ICU", "RN, Med/Surg", "Registered Nurses, Float Pool")) assertTrue(CareerCut.judge(nurse, titled(t), List.of()).passes(), t);
        for (String t : List.of("Registered Dietitian", "Registered Respiratory Therapist", "Licensed Vocational Nurse", "Nurse Practitioner")) assertFalse(CareerCut.judge(nurse, titled(t), List.of()).passes(), t);
        assertEquals(0, CareerCut.tier(nurse, titled("RN, Med/Surg")), "the page lists it among the openings that fit, by the same rule");
        assertTrue(CareerCut.judge(wanting(List.of("Designer"), List.of("User Experience Designer"), List.of()), titled("Senior UX Designer"), List.of()).passes(), "initials for the words");
        assertTrue(CareerCut.judge(wanting(List.of("Designer"), List.of("UX Designer"), List.of()), titled("User Experience Designer"), List.of()).passes(), "and the words for initials");
        assertFalse(CareerCut.judge(wanting(List.of("Designer"), List.of("UX Designer"), List.of()), titled("Piping Designer"), List.of()).passes());
        assertFalse(CareerCut.judge(wanting(List.of("Designer"), List.of("Graphic Designer"), List.of("Art Director")), titled("Ad Creative Video Editor"), List.of()).passes(),
                "initials are written in capitals: Ad is a word, not Art Director");
        assertTrue(CareerCut.judge(wanting(List.of("Designer"), List.of("Graphic Designer"), List.of("Art Director")), titled("AD, Brand Campaigns"), List.of()).passes());
        assertTrue(CareerCut.judge(wanting(List.of("看護師"), List.of("看護師"), List.of()), titled("正看護師（病棟）"), List.of()).passes(), "a word inside a longer one where words are not spaced");
        assertTrue(CareerCut.judge(wanting(List.of("Engineer", "Architect"), List.of(), List.of()), titled("Platform Engineer"), List.of()).passes(), "with no whole titles, the cut's words as before");
        assertEquals("titles that hold every word of Security Engineer or Security Architect, or of DevSecOps Engineer, in any order, a level such as Senior aside", CareerCut.summary(security));
    }

    @Test void theModelNamesTheOtherTitlesOfTheSameWorkAndAPersonsOwnWordsReplaceThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "role", "decided", "UX designer");
            answer = "{\"role_titles\": [\"Designer\", \"UX\"], \"search_titles\": [\"User Experience Designer\", \"UX Designer\"], "
                    + "\"also_titles\": [\"Product Designer\", \"UI Designer\", \"Senior Designer\", \"Design Lead\"], \"places\": [], \"remote\": \"\"}";
            Cut c = CareerCut.make(store, s);
            assertTrue(asked.contains("- also_titles: the other whole titles under which other employers post the same work") && asked.contains("\"Staff Pharmacist\"")
                    && asked.contains("\"also_titles\": ["), "the model is asked for them, with an example of another profession");
            assertEquals(List.of("Product Designer", "UI Designer"), c.alsoTitles(), "a title of a level and the cut's own word names no work of its own: it would pass every designer");
            assertEquals(c, CareerCut.kept(store, s), "kept with the cut");
            assertTrue(CareerCut.judge(c, titled("Product Designer II"), List.of()).passes());
            assertFalse(CareerCut.judge(c, titled("Graphic Designer"), List.of()).passes());

            Cut places = CareerCut.set(store, s, Map.of("places", "Boston"));
            assertEquals(c.searchTitles(), places.searchTitles(), "setting another field keeps the titles");
            String form = CareerOpeningsPage.cut(places, s, true);
            assertTrue(form.contains("name=\"search_titles\" value=\"User Experience Designer, UX Designer\"") && form.contains("name=\"also_titles\" value=\"Product Designer, UI Designer\""), "the page shows the titles that decide, to change: " + form);
            Cut saved = CareerCut.set(store, s, Map.of("search_titles", "User Experience Designer, UX Designer", "also_titles", "Product Designer, UI Designer", "titles", "Designer, UX", "places", "Boston"));
            assertEquals(c.searchTitles(), saved.searchTitles(), "the page's form, which sends every field, keeps them"); assertEquals(c.alsoTitles(), saved.alsoTitles());
            Cut mine = CareerCut.set(store, s, Map.of("titles", "Designer"));
            assertEquals(List.of(), mine.searchTitles()); assertEquals(List.of(), mine.alsoTitles());
            assertTrue(CareerCut.judge(mine, titled("Graphic Designer"), List.of()).passes(), "the person's own words decide, as they set them");
        } finally { Career.DRIVE = Explain::drive; }
    }

    static void recordPlace(LibraryStore store, Career.Seeker s, String where) throws Exception {
        Path f = s.dir(store).resolve("record.json");
        ObjectNode r = (ObjectNode) new ObjectMapper().readTree(f.toFile());
        r.put("location", where);
        Files.writeString(f, r.toString());
    }

    @Test void aPlaceOnlyGuessedPutsTheOpeningsThereFirstAsAGroupOfTheirOwnAndCutsNone(@TempDir Path tmp) throws Exception {
        // a nurse with no place decided was shown 547 openings from everywhere, the ones in her own town not told from the rest (0.8.4)
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> city.equals("Chicago") ? new CareerCut.Towns("United States, Illinois, Cook County, United States", List.of("Evanston", "Oak Park")) : new CareerCut.Towns("", List.of());
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);   // the record puts the person in Chicago, Illinois
            Career.setWant(store, s, "role", "decided", "Registered nurse");
            answer = "{\"role_titles\": [\"Registered\", \"Nurse\"], \"search_titles\": [\"Registered Nurse\"], \"places\": [], \"remote\": \"\", \"prefer_places\": []}";
            Cut c = CareerCut.make(store, s);
            assertEquals("Chicago, Illinois", c.guessedPlace(), "the library knows the guess itself; the model named no place");
            assertTrue(c.preferPlaces().contains("Chicago") && c.nearby().containsAll(List.of("Evanston", "Oak Park")), c.toString());
            Opening here = CareerSources.opening("North Shore", "Registered Nurse - ICU", "Evanston, IL", false, "https://co.example/here", "", "", "t", "", "", "");
            Opening there = CareerSources.opening("Gulf Health", "Registered Nurse", "Houston, TX", false, "https://co.example/there", "", "", "t", "", "", "");
            assertTrue(CareerCut.judge(c, there, List.of()).passes(), "a guess cuts nothing");
            assertEquals(0, CareerCut.tier(c, here), "near the guessed place: the page's first group");
            assertEquals(1, CareerCut.tier(c, there));
            assertTrue(CareerCut.summary(c).contains("every place, Chicago, Illinois and the towns near it first, as no place is decided"), CareerCut.summary(c));
            List<CareerFind.Row> rows = new ArrayList<>();
            for (Opening o : List.of(here, there)) rows.add(new CareerFind.Row(o, null, CareerCut.judge(c, o, List.of()), 0, null, null));
            String said = CareerFind.summary(new CareerFind.View(c, List.of(), rows, 2, 0, Map.of(), "", List.of()), 2, 0, 0, 0);
            assertTrue(said.contains("1 opening fits what you decided in or near Chicago, Illinois, where your record says you are, at 1 employer. 1 more is elsewhere or gives no place, "
                    + "because you have not decided where you want to work: decide it and the search keeps to it."), said);
            assertEquals("", CareerCut.set(store, s, Map.of("places", "Boston")).guessedPlace(), "a decided place: no guess");
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void whereARemoteOpeningMayBeBasedComesFromThePersonNeverFromAnExample(@TempDir Path tmp) throws Exception {
        // remote "when based in US" for a person in Perth who named no country: the model copied the prompt's example (0.8.4)
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> switch (city) {
            case "Perth" -> new CareerCut.Towns("Australia, Western Australia, Australia", List.of("Fremantle"));
            case "Chicago" -> new CareerCut.Towns("United States, Illinois, Cook County, United States", List.of());
            default -> new CareerCut.Towns("", List.of());
        };
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "role", "decided", "Graphic designer");
            Career.setWant(store, s, "remote", "decided", "Remote would be good if the job allows it");
            recordPlace(store, s, "Perth");
            answer = "{\"role_titles\": [\"Designer\"], \"search_titles\": [\"Graphic Designer\"], \"places\": [], \"remote\": \"fine\", \"remote_where\": [\"US\", \"USA\", \"United States\"]}";
            Cut c = CareerCut.make(store, s);
            assertEquals(List.of("Australia", "AU", "AUS"), c.remoteWhere(), "the country of the place the record gives, as postings write it; the example's country is gone");
            assertTrue(CareerCut.judge(c, CareerSources.opening("Kiln", "Graphic Designer", "Remote - Australia", true, "https://co.example/au", "", "", "t", "", "", ""), List.of()).passes());
            assertTrue(CareerCut.summary(c).contains("remote counts when based in Australia, AU or AUS"), CareerCut.summary(c));
            assertTrue(Career.logTail(store, s, 50).stream().anyMatch(l -> l.contains("US, USA or United States are left out, as nothing you said or your record names it")), String.join("\n", Career.logTail(store, s, 50)));

            recordPlace(store, s, "Chicago, Illinois");
            Career.setWant(store, s, "remote", "decided", "It has to be fully remote, working from the United Kingdom");
            answer = "{\"role_titles\": [\"Designer\"], \"search_titles\": [\"Graphic Designer\"], \"places\": [], \"remote\": \"only\", \"remote_where\": [\"United Kingdom\", \"UK\"]}";
            assertEquals(List.of("United Kingdom", "UK", "United States", "US", "USA"), CareerCut.make(store, s).remoteWhere(), "the country they named, its initials, and the record's country");

            recordPlace(store, s, "");
            answer = "{\"role_titles\": [\"Designer\"], \"search_titles\": [\"Graphic Designer\"], \"places\": [], \"remote\": \"only\", \"remote_where\": [\"US\"]}";
            Career.setWant(store, s, "remote", "decided", "Fully remote");
            Cut none = CareerCut.make(store, s);
            assertEquals(List.of(), none.remoteWhere(), "nothing names a country: remote counts wherever the posting says it is open");
            assertTrue(CareerCut.summary(none).contains("remote only, wherever the posting says it is open"), CareerCut.summary(none));
            assertTrue(Career.logTail(store, s, 50).stream().anyMatch(l -> l.contains("No country is known for you")));
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void remoteIsReadInTheProgramsLanguagesNotInEnglishAlone() {
        for (String loc : List.of("Homeoffice, Deutschland", "Télétravail (France)", "Remoto - España", "Da remoto", "Удалённо", "フルリモート可", "재택근무", "远程办公", "Remote - US"))
            assertTrue(CareerSources.remote(loc), loc);
        for (String loc : List.of("Remotely, Ohio", "Lyon, France", "Office in Bremen")) assertFalse(CareerSources.remote(loc), loc);
        assertTrue(CareerCut.bareRemote("Télétravail") && CareerCut.bareRemote("Homeoffice (flexible)") && !CareerCut.bareRemote("Télétravail, Lyon"));
        Cut fr = new Cut(List.of("Designer"), List.of(), List.of(), List.of(), List.of(), "only", List.of("France", "FR", "FRA"), List.of(), List.of(), 0, 0, "library", "", "");
        assertTrue(CareerCut.judge(fr, CareerSources.opening("Kiln", "Designer", "Télétravail, France", false, "https://co.example/fr", "", "", "t", "", "", ""), List.of()).passes(),
                "a posting the reader did not mark remote, whose place says so in French");
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
            assertEquals("titles with Engineer, Engineering or CTO and with Founding, Co-founder or Founder; every place, Chicago, Illinois and the towns near it first, as no place is decided; "
                    + "first in the order: places in Chicago", CareerCut.summary(c), "no place decided: the record's place first, none cut");
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
            assertEquals("titles with CTO or CEO, or VP or SVP of Engineering or Technology; every place, Chicago, Illinois and the towns near it first, as no place is decided; first in the order: titles with Engineering, Founding or Co-founder and places in Chicago", CareerCut.summary(c), "the long forms stay out of the sentence");
            assertEquals(List.of("\"CTO\"", "\"CEO\"", "\"VP\" \"Engineering\""), CareerFind.searchTerms(c), "a VP is searched for with the area the person's roles name");
            CareerCut.Verdict ok = CareerCut.judge(c, titled("CTO"), List.of());
            int cto = CareerCut.rank(c, titled("CTO"), ok, Set.of(), Set.of(), LocalDate.now()), field = CareerCut.rank(c, titled("Field CTO"), ok, Set.of(), Set.of(), LocalDate.now()),
                    office = CareerCut.rank(c, titled("Analyst, CEO Office"), ok, Set.of(), Set.of(), LocalDate.now());
            assertTrue(cto > field && cto > office, "a role beside an executive is read after the executives: " + cto + " " + field + " " + office);

        } finally { Career.DRIVE = Explain::drive; }
    }

    /** The 27B's own cut for a UX designer in Austin, 2026-11-27 (two setups of three of the same decisions), trimmed: it keeps out the seeker's own kinds of designer. */
    static final String UX_DRAFT = "{\"role_titles\": [\"Designer\"], \"ownership_titles\": [], \"titles_not\": [\"UX\", \"UI\", \"Product\", \"Graphic\", \"Visual\", \"Industrial\", "
            + "\"Interior\", \"Fashion\", \"Game\", \"Motion\", \"Tobacco\", \"Defence\"], \"areas\": [\"Design\"], \"places\": [\"Austin\", \"Texas\", \"TX\"], \"remote\": \"no\", "
            + "\"remote_where\": [], \"max_people\": 0, \"min_people\": 0, \"search_titles\": [\"User Experience Designer\", \"UX Designer\"], \"also_titles\": [\"UX Researcher\", "
            + "\"Product Designer\", \"Interaction Designer\", \"UI Designer\", \"Design Lead\", \"Senior UX Designer\"], \"prefer_titles\": [\"Design\"], \"prefer_places\": []}";

    @Test void aWordTheCutWantsIsNeverAWordItKeepsOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("", List.of());
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            Career.setWant(store, s, "role", "decided", "User experience designer");
            Career.setWant(store, s, "place", "decided", "Austin, Texas");
            answer = UX_DRAFT;
            Cut c = CareerCut.make(store, s);
            assertEquals(List.of("Graphic", "Visual", "Industrial", "Interior", "Fashion", "Game", "Motion", "Tobacco", "Defence"), c.titlesNot(), "UX, UI and Product are in the titles it wants");
            Opening seat = CareerSources.opening("Co", "Lead Product Designer", "Austin, TX", false, "https://co.example/1", "", "", "t", "", "", "");
            Opening other = CareerSources.opening("Co", "Senior Graphic Designer", "Austin, TX", false, "https://co.example/2", "", "", "t", "", "", "");
            assertTrue(CareerCut.judge(c, seat, List.of()).passes(), "the seat the earlier run showed for the same decisions");
            assertFalse(CareerCut.judge(c, other, List.of()).passes(), "another kind of designer stays out");
            String log = Files.readString(s.dir(store).resolve("log.jsonl"));
            assertTrue(log.contains("The cut the model wrote kept out UX, UI and Product, which is in the titles it wants (User Experience Designer, UX Designer, UX Researcher, Senior UX Designer, UI Designer, Product Designer):"), log);
            assertEquals(c.titlesNot(), CareerCut.kept(store, s).titlesNot(), "as saved");
            // a cut the model wrote before this check, read back: healed; the person's own, read back: as they set it
            Path f = s.dir(store).resolve("search").resolve("cut.json");
            ObjectNode j = (ObjectNode) new ObjectMapper().readTree(f.toFile());
            j.set("titles_not", new ObjectMapper().createArrayNode().add("UX").add("Graphic"));
            Files.writeString(f, j.toString());
            assertEquals(List.of("Graphic"), CareerCut.kept(store, s).titlesNot());
            j.put("by", "you");
            Files.writeString(f, j.toString());
            assertEquals(List.of("UX", "Graphic"), CareerCut.kept(store, s).titlesNot());
            assertTrue(CareerCut.hides(CareerCut.kept(store, s)).startsWith("You keep out UX, which is in the titles you want (User Experience Designer, UX Designer, UX Researcher, Senior UX Designer)"),
                    CareerCut.hides(CareerCut.kept(store, s)));
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void aContradictionInThePersonsOwnWordsIsSaidAndTheirLatestWordWins(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        CareerCut.NEAR = (city, within, km) -> new CareerCut.Towns("", List.of());
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            CareerCut.set(store, s, Map.of("search_titles", "Product Designer, UX Designer"));
            CareerCut.Setting set = CareerCut.setting(store, s, Map.of("titles_not", "Product, Intern"));   // their latest word: Product kept out
            assertEquals(List.of("Product", "Intern"), set.cut().titlesNot());
            assertEquals("You keep out Product, which is in the titles you want (Product Designer): the cut keeps your latest word, so openings with those titles do not pass. "
                    + "Take one of the two out to change it.", set.said());
            assertFalse(CareerCut.judge(set.cut(), titled("Product Designer"), List.of()).passes());
            assertEquals(set.said(), CareerCut.hides(CareerCut.kept(store, s)), "said again wherever the cut is shown");
            set = CareerCut.setting(store, s, Map.of("also_titles", "Product Designer, Interaction Designer"));   // now their latest word: the title
            assertEquals(List.of("Intern"), set.cut().titlesNot());
            assertEquals("The cut kept out Product, which is in the titles you want (Product Designer): your latest word wins, so the cut no longer keeps out Product.", set.said());
            assertTrue(CareerCut.judge(set.cut(), titled("Product Designer"), List.of()).passes());
            assertEquals("", CareerCut.hides(CareerCut.kept(store, s)));
            assertEquals("", CareerCut.setting(store, s, Map.of("titles_not", "Intern, Junior UX Designer")).said(), "a kept-out title that holds a wanted one and more names other work");
        } finally { Career.DRIVE = Explain::drive; CareerCut.NEAR = CareerCut::nearFromWikidata; }
    }

    @Test void aKeptOutWordIsInAWantedTitleAsTheJudgeReadsIt() {
        assertTrue(CareerCut.wantedHolds("User Experience Designer", "UX"), "initials kept out");
        assertTrue(CareerCut.wantedHolds("UX Designer", "User Experience"), "words kept out, in a title of their initials");
        assertTrue(CareerCut.wantedHolds("Product Designer", "product"));
        assertFalse(CareerCut.wantedHolds("UX Designer", "Junior UX Designer"));
        assertFalse(CareerCut.wantedHolds("Registered Nurse", "Nursery"), "another word that begins the same");
        assertFalse(CareerCut.wantedHolds("CTO", "Field"));
        assertFalse(CareerCut.wantedHolds("Security Engineer", "ux"), "initials are written in capitals");
    }

    @Test void aSearchWhoseCutLetsAlmostNothingThroughSaysSoWithTheCut() {
        Cut c = new Cut(List.of("Designer"), List.of(), List.of("UX", "UI", "Product"), List.of(), List.of("Austin"), "no", List.of(), List.of(), List.of(), 0, 0, "library", "2026-11-27", "",
                List.of(), 0, List.of("UX Designer"), List.of());
        List<ObjectNode> cs = new ArrayList<>();
        ObjectMapper m = new ObjectMapper();
        for (int n : new int[]{312, 200, 87, 81}) { ObjectNode o = m.createObjectNode().put("name", "Co" + n).put("openings", n); o.putObject("feed").put("kind", "greenhouse"); cs.add(o); }
        Opening one = CareerSources.opening("Co", "Designer", "Austin, TX", false, "https://co.example/1", "", "", "t", "", "", "");
        List<CareerFind.Row> rows = List.of(new CareerFind.Row(one, null, CareerCut.judge(c, one, List.of()), 0, null, null));
        String said = CareerFind.summary(new CareerFind.View(c, cs, rows, 680, 0, Map.of(), "", List.of()), 4, 0, 0, 0);
        assertTrue(said.contains("The cut let 1 of the 680 openings read through: titles that hold every word of UX Designer"), said);
        assertTrue(said.contains("titles without UX, UI or Product"), said);
        List<ObjectNode> few = List.of(cs.get(2));
        assertFalse(CareerFind.summary(new CareerFind.View(c, few, List.of(), 87, 0, Map.of(), "", List.of()), 1, 0, 0, 0).contains("The cut let"), "few openings read: no claim");
        List<CareerFind.Row> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) many.add(rows.get(0));
        assertFalse(CareerFind.summary(new CareerFind.View(c, cs, many, 680, 0, Map.of(), "", List.of()), 4, 0, 0, 0).contains("The cut let"), "four of 680 through: more than one in two hundred");
    }

    @Test void twoCapitalsStandForAWholeOtherTitleOnlyAtTheTitlesHead() {
        // the saved runs of 2026-11-27: "PE" took "Traffic Engineer, PE" for Platform Engineer, "PA" a director's seat in Pennsylvania for
        // Platform Architect, "ID" a requisition's id for Interaction Designer; none of them that work (0.8.4)
        Cut c = new Cut(List.of("Engineer"), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "library", "2026-11-27", "",
                List.of(), 0, List.of("Registered Nurse", "Site Reliability Engineer"), List.of("Platform Engineer", "Platform Architect", "Interaction Designer", "User Experience Designer"));
        for (String t : List.of("Traffic Engineer, PE", "Director, Data Center Construction (Salem Township, PA)", "Production Support Engineer Austin, TX Requisition ID: 2026-131769"))
            assertFalse(CareerCut.judge(c, titled(t), List.of()).passes(), t);
        assertTrue(CareerCut.judge(c, titled("RN - ICU"), List.of()).passes(), "a title the search looks for keeps its initials");
        assertTrue(CareerCut.judge(c, titled("Senior SRE"), List.of()).passes());
        assertTrue(CareerCut.judge(c, titled("UX Designer, Mobile"), List.of()).passes(), "initials for part of an other title, the rest spelled out");
        assertTrue(CareerCut.judge(c, titled("Senior Platform Engineer"), List.of()).passes());
        assertTrue(CareerCut.judge(c, titled("PE, Developer Experience"), List.of()).passes(), "two capitals at the title's head stand for it, as AD for Art Director");
    }

    static Opening post(String title) { return CareerSources.opening("Co", title, "", true, "https://news.ycombinator.com/item?id=" + title.hashCode(), "", "", "hn:November 2026", "", "", ""); }

    @Test void aPostThatNamesSeveralRolesIsJudgedOnEachRole() {
        // Hacker News's "Who is hiring", 2026-11-27: a post's role line lists several roles, and a title made of words from two of them passed (0.8.4)
        Cut ml = new Cut(List.of("Engineer", "ML"), List.of(), List.of("Senior", "Staff", "Junior"), List.of(), List.of(), "only", List.of(), List.of(), List.of(), 0, 0, "library", "2026-11-27", "",
                List.of(), 0, List.of("Machine Learning Engineer", "ML Engineer"), List.of("AI Engineer", "Data Scientist"));
        assertFalse(CareerCut.judge(ml, post("Multiple Engineering, AI/ML Data Science, and Revenue roles"), List.of()).passes(), "ML from one role, Engineering from another");
        assertFalse(CareerCut.judge(ml, post("AI tech leads, Software and Platform engineers"), List.of()).passes());
        assertTrue(CareerCut.judge(ml, post("Founding ML Engineer + Data roles"), List.of()).passes());
        assertEquals(1, CareerCut.group(ml, post("Software Engineer & Data Scientist")), "the role that passes is the model's other title");
        assertEquals(1, CareerCut.group(ml, post("Sr/Staff Software Engineers, Data Scientist, FDE, PM")), "a word kept out cuts the role that has it, not the post");
        assertEquals(0, CareerCut.group(ml, post("Software Engineers (SRE, ML, backend, full-stack, mobile, security, QA)")), "a list in brackets stays with its role");
        assertEquals(List.of("Research & Development Engineer, ML"), CareerCut.roles(titled("Research & Development Engineer, ML")), "a title anywhere else is one role");
    }
}
