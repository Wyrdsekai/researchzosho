package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** More than one person on the career page: the root lists them and opens a page for another by name; a person's page links back. */
class CareerPeoplePageTest {
    /** The model's part: told words read for what they settle; everything else answers nothing. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode tools, int max, String choice) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.contains("THE QUESTIONS AND ANSWERS") && p.contains("Answer: I want to teach, near Lyon, and never nights."))
                return "{\"topics\": [{\"answer\": \"T\", \"dimension\": \"kind of work\", \"state\": \"decided\", \"want\": \"teaching\"}, {\"answer\": \"T\", \"dimension\": \"place\", \"state\": \"decided\", \"want\": \"near Lyon\"}], "
                        + "\"followups\": [], \"companies\": [], \"keep_out\": [{\"answer\": \"T\", \"keep_out\": \"nights\"}]}";
            return "{}";
        }
    }
    @BeforeEach void standIn() { Career.DRIVE = StandIn::new; }
    @AfterEach void back() { Career.DRIVE = Explain::drive; }

    @Test void theRootPageListsThePeopleAndOpensAPageForAnotherByName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Pages.fieldMenu(store, Patrons.Patron.PERSON).contains("href=\"/career\""), "the Career page is in the menu before anyone is on the shelves: it is where a first person begins");
        String empty = CareerPage.body(store, null, true);
        assertTrue(empty.contains("<form class=\"big\" action=\"/career\">") && empty.contains("Nobody's working life is kept here yet. Start one below.") && empty.contains("<h2 id=\"new\">A new person</h2>") && empty.contains("name=\"name\"") && empty.contains("<button>Add</button>"), empty);
        Career.Seeker first = Career.provide(store, "I want to keep leading a team, in Chicago.", "First Person", false).seeker();
        String root = CareerPage.body(store, null, true);
        assertTrue(root.contains("<h2 id=\"all\">The people on the shelves</h2>") && root.contains("<tr><td><a href=\"/career?seeker=first-person\">First Person</a></td><td>1</td><td></td><td>") && root.contains("<h2 id=\"new\">A new person</h2>"), root);
        // the address with no name is the front door even when the library holds one person; the name opens that person
        var door = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "GET", Map.of(), Map.of());
        assertTrue(door.body().contains("<h2 id=\"all\">The people on the shelves</h2>") && door.body().contains(">First Person</a>"), door.body());
        var one = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "GET", Map.of("seeker", "first-person"), Map.of());
        assertTrue(one.body().contains("<details class=\"sec\" id=\"reading\"><summary><h2>The reading</h2></summary>") && one.title().startsWith("First Person"), one.body());
        String mine = CareerPage.body(store, first, true);
        assertTrue(mine.contains("<a href=\"/career\">another person</a>"), mine);
        // the search stands at the top of the person's page, with the button that starts it; pressed, the page says the search is on and the button is gone
        int stand = mine.indexOf("<div class=\"notice\"><b>No search yet.</b>");
        assertTrue(stand >= 0 && stand < mine.indexOf("<details class=\"sec\" id=\"reading\">") && mine.contains("<button>Look for openings</button>") && mine.contains("name=\"op\" value=\"search\""), mine);
        assertTrue(mine.contains("name=\"vary\"") && mine.contains("a change for this search only"), "the button carries the box for a change for this search only");
        var started = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "search", "seeker", "first-person", "vary", "and New Hampshire as well"));
        assertEquals("/career?seeker=first-person", started.redirect());
        var job = new Jobs(store, j -> { throw new IllegalStateException("filed only"); }).active().stream().filter(j -> j.path("kind").asText().equals("career-search") && j.path("args").path("seeker").asText().equals("first-person")).findFirst().orElseThrow();
        assertEquals("and New Hampshire as well", job.path("args").path("vary").asText(), "the change for this search goes with the job");
        String on = CareerPage.body(store, first, true);
        assertTrue(on.contains("<div class=\"notice\"><b>The search is on.</b>") && !on.contains("Look for openings"), on);
        // the page opens with its table of contents: the sections it holds, in order, then the pages beside it
        int nav = mine.indexOf("<nav class=\"in\">");
        assertTrue(nav >= 0 && nav < mine.indexOf("<details class=\"sec\""), "the contents come before the first section: " + mine);
        String contents = mine.substring(nav, mine.indexOf("</nav>", nav));
        assertTrue(contents.contains("<a href=\"#reading\">The reading</a>") && contents.contains("<a href=\"#wants\">What you want</a>")
                && contents.contains("<a href=\"/career/openings?seeker=first-person\">The openings page</a>") && contents.contains("<a href=\"/career\">People</a>"), contents);
        assertTrue(mine.contains("<details class=\"sec\" id=\"reading\"><summary><h2>The reading</h2></summary>") && mine.contains("<details class=\"sec\" id=\"wants\"><summary><h2>What you want</h2></summary>"), mine);
        assertTrue(!contents.contains("#lately") || mine.contains("<details class=\"sec\" id=\"lately\">"), "only sections the page holds are listed");
        // the form posted with a name opens that person's page, new or known
        var page = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "provide", "name", "Second Person", "text", "I want to teach, near Lyon, and never nights."));
        assertEquals("/career?seeker=second-person", page.redirect());
        assertEquals(2, Career.seekers(store).size());
        // the words are read at once for what they decide: topics set "by told", what they rule out kept out, and the page says so
        Career.Seeker second = Career.seeker(store, "Second Person");
        var kind = Career.find(Career.wantsKept(store, second).path("dimensions"), "kind of work");
        assertTrue(kind != null && kind.path("state").asText().equals("decided") && kind.path("by").asText().equals("told") && kind.path("want").asText().equals("teaching"), String.valueOf(kind));
        assertTrue(CareerSearch.keepOut(store, second).contains("nights"), CareerSearch.keepOut(store, second).toString());
        String theirs = CareerPage.body(store, second, true);
        assertTrue(theirs.contains("Read from your words: ") && theirs.contains("Kept out: nights. The next search starts from this."), theirs);
        String both = CareerPage.body(store, first, true);
        assertTrue(both.contains("the other people, or another person"), both);
        var again = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "provide", "name", "Second Person", "text", "And I would refuse nights."));
        assertEquals("/career?seeker=second-person", again.redirect());
        assertEquals(2, Career.seekers(store).size(), "the same name adds to the same person");
        assertTrue(Career.evidence(store, Career.seeker(store, "Second Person")).size() == 2);
        // a file posted with the form is read as the command line reads one, under its own name, and the upload folder is dropped after
        Path up = Files.createDirectories(tmp.resolve("researchzosho-upload-test"));
        Path json = up.resolve("profile.json"); Files.writeString(json, CareerTest.linkedin(), StandardCharsets.UTF_8);
        var third = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "provide", "name", "Third Person", "text", "", "file.path", json.toString(), "file.name", "profile.json"));
        assertEquals("/career?seeker=third-person", third.redirect());
        var facts = Career.evidence(store, Career.seeker(store, "Third Person"));
        assertTrue(facts.size() > 3 && facts.get(0).path("source").asText().equals("the LinkedIn file profile.json"), facts.toString());
        assertFalse(Files.exists(up), "the upload folder is removed once the file is read");
        var neither = new CareerProfile().page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "provide", "name", "Fourth Person", "text", ""));
        assertTrue(neither.body().contains("Write something, or choose a file."), neither.body());
    }
}
