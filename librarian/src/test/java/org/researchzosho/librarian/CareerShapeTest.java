package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How seats are filled, drawn by a stand-in model, kept, corrected by the person, and the library's default when the model is silent. */
class CareerShapeTest {
    final List<String> prompts = new ArrayList<>(), all = new ArrayList<>();   // the shape prompts; every prompt
    String answer = "{\"how\": [\"licence\", \"posted\"], \"boards\": [\"agency boards\"], \"lists\": \"the state's hospital directory and the nursing agencies' lists\", "
            + "\"need\": \"a registered nurse licence from the state board of nursing\", \"now\": \"an opening on a hospital's own page or with an agency\", "
            + "\"summary\": \"Your seats are posted by hospitals and filled through agencies, after the state licence. Look at the hospitals' own pages and the agencies.\"}";

    final class StandIn implements Researcher.Drive {
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText(); all.add(p);
            if (p.contains("Say how seats for this work in this place are usually filled")) { prompts.add(p); return answer; }
            return "[]";   // the wants draft and the rest: nothing
        }
        @Override public String prose(ArrayNode m, int max) {
            String p = m.get(0).path("content").asText(); all.add(p);
            if (p.contains("Write a short reading of the opening")) { prompts.add(p); return answer; }
            return "## The arc\nYou lead a team [E2][E3].\n## Questions for you\n1. Which size of company suited you best, and why? (preference) [E2][E3]"; }
    }

    @AfterEach void back() { Career.DRIVE = Explain::drive; }

    @Test void withNoPlaceTheShapeNamesNoBodyAndSaysSo() {
        Career.DRIVE = StandIn::new;
        CareerShape.Shape sh = CareerShape.draw("lawyer", "", "", "");
        String p = all.stream().filter(x -> x.startsWith("A person is looking for work as lawyer")).reduce((a, b) -> b).orElseThrow();
        assertTrue(p.contains("The place is not known yet, so answer for this work in general and name no country's bodies, boards or registers."), p);
        assertTrue(p.contains("the kind of licence or registration in general words, since the place is not known"), p);
        assertFalse(p.contains("in this place are usually filled"), p);
        assertEquals("", sh.place());
    }

    @Test void theShapeIsDrawnOnceForTheRoleAndPlaceKeptAndReadWithoutTheModelAfter(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        CareerShape.Shape sh = CareerShape.current(store, s);
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).startsWith("A person is looking for work as Engineering lead in Chicago, Illinois. Say how seats for this work in this place are usually filled"), prompts.get(0));
        assertEquals(List.of("licence", "posted"), sh.hows()); assertEquals(List.of("agency boards"), sh.boards()); assertEquals("the model", sh.by());
        assertTrue(Files.isRegularFile(s.dir(store).resolve("search").resolve("shape.json")));
        assertEquals(sh, CareerShape.current(store, s)); assertEquals(1, prompts.size(), "drawn for the same role and place, the kept shape serves");
        assertEquals(sh, CareerShape.kept(store, s));
        String text = CareerShape.text(sh);
        assertTrue(text.startsWith("Jobs for an Engineering lead in Chicago, Illinois are filled after a licence or registration and through openly posted openings, as the library understands it. Your seats are posted by hospitals"), text);
        assertTrue(text.contains("What you need: a registered nurse licence from the state board of nursing. Where such employers are listed: the state's hospital directory"), text);
        assertTrue(text.endsWith("The search reads the openings of the employers on the list, the lists where such employers are listed and the hiring sites, and skips the venture portfolio boards and Hacker News's Who is hiring, which list other trades' jobs; the agency boards it has no reader for yet, so it looks for them among the lists."), text);
        assertFalse(sh.reads(CareerShape.READS_HN)); assertFalse(sh.reads(CareerShape.READS_PORTFOLIO)); assertTrue(sh.posted()); assertTrue(sh.has("licence"));
        assertTrue(CareerShape.logLine(sh).startsWith("Jobs for an Engineering lead in Chicago, Illinois are filled after a licence or registration and through openly posted openings; what you need: a registered nurse licence from the state board of nursing. The search reads"), CareerShape.logLine(sh));
    }

    @Test void thePersonsOwnWordOutranksTheModelAndIsDrawnWith(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        answer = "{\"how\": [\"referral\"], \"boards\": [\"none\"], \"summary\": \"Your seats go through people you know.\", \"need\": \"\", \"lists\": \"\", \"now\": \"\"}";
        String said = CareerShape.tell(store, s, "every job I had came through someone I knew; boards are useless for me");
        assertTrue(prompts.get(0).contains("The person's own word on how seats are filled for them, which outranks what you know:") && prompts.get(0).contains("boards are useless for me"), prompts.get(0));
        assertTrue(said.startsWith("Kept, in your words: every job I had came through someone I knew; boards are useless for me\nJobs for an Engineering lead in Chicago, Illinois are filled through people who know people, as you said. Your seats go through people you know."), said);
        CareerShape.Shape sh = CareerShape.kept(store, s);
        assertEquals("you", sh.by()); assertEquals(List.of("referral"), sh.hows()); assertTrue(sh.boards().isEmpty(), "none is no board"); assertFalse(sh.posted());
        assertEquals(sh, CareerShape.current(store, s)); assertEquals(1, prompts.size(), "the person's shape stands until the role or the place changes");
        assertTrue(Files.readString(s.dir(store).resolve("log.jsonl")).contains("You said how seats are filled for you: every job I had came through someone I knew"));
    }

    @Test void whenTheModelIsSilentTheSearchReadsEverythingAsBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.DRIVE = StandIn::new;
        Career.Seeker s = CareerInterviewTest.seeker(store, tmp);
        answer = "I cannot say.";
        CareerShape.Shape sh = CareerShape.current(store, s);
        assertEquals("the library's default", sh.by()); assertTrue(sh.posted()); assertTrue(sh.reads(CareerShape.READS_HN) && sh.reads(CareerShape.READS_PORTFOLIO) && sh.reads(CareerShape.READS_USAJOBS));
        assertTrue(CareerShape.text(sh).startsWith("The library could not work out how jobs are filled for an Engineering lead, so the search reads everything it can. The search reads"), CareerShape.text(sh));
        answer = "{\"how\": [\"posted\", \"niche board\"], \"boards\": [\"venture portfolio boards\", \"hacker news who is hiring\"], \"summary\": \"Posted openly.\", \"need\": \"nothing beyond the work itself\", \"lists\": \"\", \"now\": \"an opening\"}";
        assertEquals("the model", CareerShape.current(store, s).by(), "a default is drawn again next time, when the model may answer");
        assertEquals(2, prompts.size());
    }

    @Test void theJudgeReadsAnOpeningWithWhatThePersonLikedAndPassedOnBefore() {
        Career.DRIVE = StandIn::new;
        answer = "Fits: the role.\nAgainst: nothing.\nUnknown: the pay.";
        CareerSources.Opening o = CareerSources.opening("", "Staff nurse, nights", "Austin, Texas", false, "about:nurse", "Mercy Hospital", "", "", "", "", "");
        ObjectNode r = CareerFind.reading(o, "- place, DECIDED: Austin", List.of(), "", "", "Openings they liked, with why:\n- Charge nurse at Harbor Clinic — a small team that runs itself\nOpenings they passed on, with why:\n- Travel nurse at Agency One — away from home");
        assertEquals("the role.", r.path("fits").asText());
        String p = prompts.get(0);
        assertTrue(p.contains("WHAT THEY LIKED AND PASSED ON BEFORE, in their own words: read the opening with these in view, the liked ones showing what fits them\nOpenings they liked, with why:\n- Charge nurse at Harbor Clinic — a small team that runs itself\nOpenings they passed on"), p);
        assertTrue(p.indexOf("WHAT THEY LIKED") < p.indexOf("OPENING"), "the examples come before the fenced opening");
    }
}
