package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CareerSearchTest {

    /** A stand-in model: the closing answers name a company to look at, one to rule out, and an industry to keep out. */
    static final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String prose(ArrayNode m, int max) { return CareerInterviewTest.READING; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText();
            if (p.startsWith("A person looking for work answered questions")) {
                assertTrue(p.contains("each company it rules out") && p.contains("\"keep_out\""), "the model is asked for companies and what to keep out");
                return "{\"topics\": [], \"followups\": [], \"companies\": [{\"answer\": \"4\", \"company\": \"Formlabs\", \"wanted\": true}, {\"answer\": \"5\", \"company\": \"DraftKings\", \"wanted\": false}],"
                        + " \"keep_out\": [{\"answer\": \"5\", \"keep_out\": \"gambling\"}, {\"answer\": \"9\", \"keep_out\": \"not this question's\"}]}";
            }
            return "[]";
        }
    }

    @Test
    void theInterviewEndsAskingWhatElseToLookForAndWhatToKeepOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        try {
            Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
            JsonNode o = CareerInterview.state(store, s);
            List<CareerInterview.Question> qs = CareerInterview.questions(o);
            assertEquals(5, qs.size(), "the reading's three, then the two closing questions");
            assertEquals(CareerInterview.LOOK_FOR_Q, qs.get(3).text()); assertEquals("4", qs.get(3).n());
            assertEquals(CareerInterview.KEEP_OUT_Q, qs.get(4).text()); assertEquals("5", qs.get(4).n());
            assertTrue(CareerInterview.start(store, s, "terminal").startsWith("The library has 5 questions for you"));
            CareerInterview.take(store, "terminal", "skip"); CareerInterview.take(store, "terminal", "skip"); CareerInterview.take(store, "terminal", "skip");
            String looks = CareerInterview.take(store, "terminal", "Formlabs, and anything building physical things.");
            assertTrue(looks.contains("Companies the search will look at: Formlabs."), looks);
            String outs = CareerInterview.take(store, "terminal", "No gambling, so not DraftKings.");
            assertTrue(outs.contains("The search will keep out: DraftKings and gambling.") && !outs.contains("not this question's"), outs);
            assertEquals(List.of("Formlabs"), CareerSearch.companies(store, s));
            assertEquals(List.of("DraftKings", "gambling"), CareerSearch.keepOut(store, s));
            assertTrue(Career.text(store, s, "/career").contains("For the search:\n  Companies you want looked at: Formlabs.\n  Kept out: DraftKings and gambling."));

            // an interview begun before the closing questions existed gets them
            ObjectNode old = CareerInterview.state(store, s);
            ArrayNode qa = (ArrayNode) old.get("questions"); qa.remove(4); qa.remove(3);
            CareerInterview.save(store, s, old);
            assertEquals(5, CareerInterview.questions(CareerInterview.state(store, s)).size(), "added to an interview begun before them");

            // the chat and the page add and take off
            CareerProfile cp = new CareerProfile();
            assertEquals("The search will look at Klaviyo.", cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career company Klaviyo"));
            assertEquals("Klaviyo is on the list already.", cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career company klaviyo"));
            assertEquals("The search will keep out on-call rotations.", cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career keep-out on-call rotations"));
            assertEquals("The search no longer looks at Klaviyo for you.", cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career company drop Klaviyo"));
            cp.page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "company", "seeker", s.slug(), "words", "Whoop"));
            cp.page(store, Patrons.Patron.PERSON, "/career", "POST", Map.of(), Map.of("op", "remove", "seeker", s.slug(), "list", "keep_out", "words", "gambling"));
            assertEquals(List.of("Formlabs", "Whoop"), CareerSearch.companies(store, s));
            assertEquals(List.of("DraftKings", "on-call rotations"), CareerSearch.keepOut(store, s));
            String page = CareerPage.body(store, s, true);
            assertTrue(page.contains("<details class=\"sec\" id=\"search\"><summary><h2>What the search will use</h2></summary>") && page.contains("<li>Whoop") && page.contains("<li>on-call rotations") && page.contains("placeholder=\"something to keep out\""), page);
            assertFalse(CareerPage.body(store, s, false).contains("placeholder=\"a company to look at\""), "a reader sees the lists, not the forms");
        } finally { Career.DRIVE = Explain::drive; }
    }
}
