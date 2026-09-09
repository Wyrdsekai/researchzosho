package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Language lanes: a question about a place gets a worker on that place's language, and the run says what it read. */
class LanesTest {

    /** A judge that names Japanese for anything about Tokyo and writes seed queries in Japanese. */
    static final class Judge implements Researcher.Drive {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String y) { throw new UnsupportedOperationException(); }
        @Override public int contextWindow() { return 32_000; }
        @Override public String classify(ArrayNode m, int max) {
            String p = m.get(m.size() - 1).path("content").asText(); prompts.add(p);
            if (p.startsWith("Other than")) return "[{\"code\":\"ja\",\"name\":\"Japanese\"}]";
            if (p.startsWith("Write 6 web search queries IN Japanese")) return "[\"東京ヴァイス 撮影 現場\", \"Tokyo Vice set in English (wrong script)\", \"東京ヴァイス 渡辺謙 インタビュー\"]";
            if (p.startsWith("Decompose")) return "[\"How were the permits obtained?\"]";
            if (p.startsWith("You are reviewing research COVERAGE")) return "{\"sufficient\": true}";
            return "[]";
        }
    }

    @Test
    void aQuestionAboutTokyoGetsAJapaneseLane() {
        List<Lanes.Lane> lanes = Lanes.detect("What was it like to work on the set of Tokyo Vice? How did the Japanese crew experience it?", null);
        assertEquals(1, lanes.size());
        assertEquals("ja", lanes.get(0).code()); assertEquals("cjk", lanes.get(0).script());
        assertTrue(lanes.get(0).subQuestion("q").contains("In Japanese-language sources"));
        assertTrue(lanes.get(0).inLanguage("東京ヴァイス 撮影 現場"));
        assertFalse(lanes.get(0).inLanguage("Tokyo Vice filming Akasaka"));
        // the question's own language is never a lane; a Japanese question about Hollywood gets an English one
        List<Lanes.Lane> en = Lanes.detect("ハリウッドの撮影現場はどのように運営されているか", null);
        assertEquals(List.of("en"), en.stream().map(Lanes.Lane::code).toList());
        assertTrue(en.get(0).inLanguage("how Hollywood sets are run"), "a Latin-script lane cannot be told from another by script, so it accepts");
        // nothing named: no lane
        assertTrue(Lanes.detect("How are gear teeth cut by hand?", null).isEmpty());
        // Paris and Berlin: two lanes, the cap
        List<Lanes.Lane> two = Lanes.detect("Compare the Paris and Berlin and Rome subway systems", null);
        assertEquals(2, two.size());
    }

    @Test
    void theJudgeAddsSeedsInTheRightScript() {
        Judge judge = new Judge();
        List<Lanes.Lane> lanes = Lanes.detect("What was it like to work on the set of Tokyo Vice?", judge);
        assertEquals(1, lanes.size());
        assertFalse(judge.prompts.stream().anyMatch(q -> q.startsWith("Other than")), "the table decides the lanes; the judge is not asked which");
        assertEquals(List.of("東京ヴァイス 撮影 現場", "東京ヴァイス 渡辺謙 インタビュー"), lanes.get(0).seeds(), "a seed in the wrong script is dropped");
        assertTrue(lanes.get(0).register().contains("YOUR LANE: sources written in Japanese") && lanes.get(0).register().contains("東京ヴァイス 撮影 現場"));
        assertTrue(lanes.get(0).wrongLanguageNote().contains("not in Japanese"));
    }

    @Test
    void theLanguagesOfTheSourcesAreReadOffTheNotes() {
        String evidence = "- Twenty staff for a night — source: https://www.hollywoodreporter.com/a — quote: \"could count as many as 20\"\n"
                + "- Permits took months — source: https://natalie.mu/eiga/news/1 — quote: \"許可には数か月かかった\"\n"
                + "- Shot in Akasaka — source: https://eiga.com/news/2 — quote: \"\"\n"
                + "- In English on a .jp site — source: https://www.nippon.com/en/japan-topics/g02394/ — quote: \"the locations team\"\n";
        Map<String, Integer> read = Lanes.languagesRead(evidence);
        assertEquals(3, read.get("en"), read.toString());
        assertEquals(1, read.get("ja"), "a Japanese quote is Japanese; a bare .com host with no quote is English");
        assertEquals("English 3 · Japanese 1", Lanes.describe(read));
        assertEquals("ja", Lanes.languageOf("https://www.nhk.or.jp/news/x", ""), "a .jp host with no quote is Japanese");
        assertEquals("en", Lanes.languageOf("https://www.nippon.com/en/japan-topics/", ""), "the English edition of a Japanese site is English");
        assertEquals("zh", Lanes.languageOf("https://x.example/", "汉字没有假名"));
    }

    @Test
    void thePlanCarriesTheLaneTheCriticSendsItRoundAgainAndTheWriteUpSaysWhatWasRead() {
        Judge judge = new Judge();
        Researcher r = new Researcher(judge, judge, new ResearcherTest.FakeTools(), null, 2, null);
        Researcher.Ask ask = new Researcher.Ask("What was it like to work on the set of Tokyo Vice?", "broad", 0, List.of());
        Researcher.Budget budget = new Researcher.Budget(0, 0);
        List<String> open = r.plan(ask, "", budget);
        assertTrue(open.stream().anyMatch(o -> o.startsWith("In Japanese-language sources:")), open.toString());
        // round one read only English: the critic sends the lane round again, once, before asking the model anything
        List<String> evidence = new ArrayList<>(List.of("- Twenty staff — source: https://www.hollywoodreporter.com/a — quote: \"as many as 20\""));
        List<String> missing = r.critic(ask, evidence, budget);
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).contains("read no Japanese-language source"), missing.get(0));
        assertTrue(r.critic(ask, evidence, budget).isEmpty(), "only once");
        // the write-up's languages section
        String section = r.languagesSection(String.join("\n", evidence));
        assertTrue(section.startsWith("## Languages of the sources") && section.contains("English 1") && section.contains("Japanese-language lane did not run"), "no lane worker ran in this test, and the write-up says so: " + section);
        r.investigate(ask, open.get(0), new Researcher.Budget(1, 0));   // the lane worker starts (and stops at once: one turn)
        String ran = r.languagesSection(String.join("\n", evidence));
        assertTrue(ran.contains("No Japanese-language source was found in two tries"), ran);
        String both = r.languagesSection(evidence.get(0) + "\n- 許可 — source: https://natalie.mu/x — quote: \"数か月\"");
        assertTrue(both.contains("Japanese 1") && both.contains("searched on their own lane"), both);
    }

    @Test
    void theReviewReadsTheAnswerNotTheWorkerNotebooks() {
        String body = "## Answer\n\nThe answer.\n\n## Evidence\n\n| a | b | c |\n\n## References\n\n[1] x — https://a.example\n\n## Worker findings (fan sub-investigations, verbatim)\n\n" + "note ".repeat(50_000) + "\n\n## Sources cited\n\nhttps://a.example\n";
        String cut = LibrarianReview.forExtraction(body, 32_768);
        assertTrue(cut.contains("The answer.") && cut.contains("[1] x") && !cut.contains("Worker findings") && !cut.contains("Sources cited"), cut.substring(0, Math.min(300, cut.length())));
        String huge = LibrarianReview.forExtraction("## Answer\n\nHEAD " + "x".repeat(400_000) + " TAIL\n", 32_768);
        assertTrue(huge.startsWith("## Answer") && huge.endsWith("TAIL\n") && huge.contains("cut from the middle") && huge.length() < 100_000, "over the window: head and tail kept");
    }
}
