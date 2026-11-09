package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The what-the-law-says goal through the brain's shape: its words and rules reach the planner and every worker beside the law convention and
 * agree with it without repeating it, its shape reaches the writer and binds the answer to the texts read, the jurisdiction's own collections
 * are on the table, and it fires on plain asks in English and Japanese and not on their neighbours.
 */
class LawGoalTest {
    static final String ASK = "How long after a tenant moves out must a landlord in Vermont return the security deposit, and what may be deducted from it? Which statute says so, and as of what date?";
    /** No law word in it: the goal is named, as the protocol names the field for it. */
    static final String PLAIN = "When must a Vermont landlord give back the deposit?";

    @Test
    void theGoalsWordsAndRulesReachThePlannerAndEveryWorkerBesideTheConvention() {
        Goal g = Goals.named("what-the-law-says");
        String block = "THIS RUN SERVES THE GOAL what-the-law-says: " + g.words() + " " + g.decisionRules();
        PriorArtGoalTest.Seen byWords = PriorArtGoalTest.run(ASK, List.of(), List.of());
        assertTrue(byWords.plans.get(0).contains(block), "the planner reads the goal's words and rules: " + byWords.plans.get(0));
        assertTrue(byWords.plans.get(0).contains("LAW: This is a question about law"), "and the field's own plan rules joined by the question's words");
        assertFalse(byWords.workers().isEmpty());
        for (String sys : byWords.workers()) assertTrue(sys.contains(block) && sys.contains("THE LAW CONVENTION. " + Conventions.named("law").words()), "every worker reads the goal and the convention: " + sys.substring(0, Math.min(300, sys.length())));
        assertEquals("law", LibraryProtocol.goalField("what-the-law-says"), "naming the goal names the law field, so its collections and rules join");
        PriorArtGoalTest.Seen named = PriorArtGoalTest.run(PLAIN, List.of("law"), List.of("what-the-law-says"));
        assertTrue(named.plans.get(0).contains(block), "a plain ask with the goal named is planned under it");
        for (String sys : named.workers()) assertTrue(sys.contains(block), "every worker of it reads the goal");
    }

    @Test
    void theRulesSayWhatToCheckByWhichInstrumentAndLeaveTheClaimsFormToTheConvention() {
        String rules = Goals.named("what-the-law-says").decisionRules();
        assertTrue(rules.contains("The primary text is the candidate"), rules);
        assertTrue(rules.contains("courtlistener, ecfr and federal-register for the United States, egov-laws and egov-keyword for Japan"), "the collections by jurisdiction: " + rules);
        assertTrue(rules.contains("fetched by the citation the question or a page gives, and read whole with open"), rules);
        assertTrue(rules.contains("a web search finds the citation, never the rule"), rules);
        assertTrue(rules.contains("each by the date it stood, the latest controlling") && rules.contains("a level or a court not searched is said as not searched"), rules);
        String convention = Conventions.named("law").claimRules();
        assertTrue(convention.contains("carries its jurisdiction and the date the law stood") && convention.contains("by its own citation"), convention);
        assertFalse(rules.contains("Every claim of law carries"), "the goal's rules do not repeat the convention's sentence; they say which instrument finds the text and how it is checked");
    }

    @Test
    void theShapeReachesTheWriterAndBindsTheAnswerToTheTextsRead() {
        Goal g = Goals.named("what-the-law-says");
        PriorArtGoalTest.Seen drive = PriorArtGoalTest.run(ASK, List.of(), List.of());
        String writer = drive.writer();
        assertTrue(writer.contains("THE REPORT SERVES THE GOAL what-the-law-says: " + g.shape()), "the goal's shape reaches the writer");
        assertTrue(writer.contains("THE LAW CONVENTION."), "and the convention");
        String shape = g.shape();
        assertTrue(shape.contains("naming the governing text by its own citation, linked on the line, and the date it stood"), "it names what was checked: " + shape);
        assertTrue(shape.contains("Then what was not checked, one line each: a level not searched, a court not searched, a date past which the text may have changed"), "it says what was not: " + shape);
        assertTrue(shape.contains("Nothing is stated as the law that was not read from a primary text; a claim resting on commentary alone says so"), "it claims nothing beyond the evidence: " + shape);
        assertTrue(shape.contains("Research, not advice"), shape);
    }

    @Test
    void theJurisdictionsCollectionsAreOnTheTable() {
        PriorArtGoalTest.Seen drive = PriorArtGoalTest.run(ASK, List.of(), List.of());
        List<String> offered = GoalRunTest.worker(drive);
        assertTrue(offered.contains("record_search") && offered.contains("search") && offered.contains("open"), offered.toString());
        String records = drive.words("record_search");
        for (String id : List.of("courtlistener", "ecfr", "federal-register", "egov-laws", "egov-keyword")) assertTrue(records.contains("- " + id + ":"), id + " is a collection of the run: " + records);
        assertFalse(records.contains("- epo-query:") || records.contains("- wikitree:"), "the patents' and the family's collections are not: " + records);
        assertTrue(records.contains("- federal-register:") && records.indexOf("Pages, up to 100 a page.", records.indexOf("- federal-register:")) > 0, "the register pages: " + records);
        String search = drive.words("search");
        assertTrue(search.contains("for statutes, regulations and court decisions in the law collections"), "the grouped search names the law collections among its places: " + search);
        assertEquals(List.of("search", "open"), Goals.named("what-the-law-says").instruments());
    }

    @Test
    void itFiresOnPlainAsksInEnglishAndJapaneseAndNotOnNeighbours() {
        Goal g = Goals.named("what-the-law-says");
        for (String q : List.of(
                "What does Massachusetts law require of a landlord before raising the rent?",
                "Is it legal to record a phone call in Vermont without telling the other person?",
                ASK,
                "What notice must my employer give before firing me in Japan?",
                "Can a homeowners association ban a clothesline in my yard?",
                "Is it against the law to feed pigeons in Venice?",
                "What did the Supreme Court decide in Roe v. Wade?",
                "賃貸の敷金は退去後いつまでに返さなければならないと法律で決まっていますか",
                "電話を無断で録音するのは違法ですか",
                "What does 17 U.S.C. § 107 say about fair use?",
                "Am I allowed to fly a drone over my neighbour's garden in England?"))
            assertTrue(g.appliesTo(q).yes(), "fires on: " + q + " (" + g.appliesTo(q).why() + ")");
        for (String q : List.of(
                "Why does Moore's law keep holding for GPUs?",
                "What is the law of large numbers?",
                "Unprecedented rainfall in Kyushu in 2024: what caused it?",
                "How is a clay tennis court maintained between matches?",
                "Which legal pad size fits an A4 folio?",
                "Screen readers for a legally blind person on Linux",
                "The judgment of Paris 1976 wine tasting: which wines won?",
                "ダイヤ改正はいつですか",
                "ケプラーの法則を簡単に説明して",
                "Boyle's law and the ideal gas equation"))
            assertFalse(g.appliesTo(q).yes(), "does not fire on: " + q);
        assertFalse(Conventions.forQuestion("Unprecedented rainfall in Kyushu in 2024: what caused it?").stream().anyMatch(c -> c.name().equals("law")), "nor does the convention");
    }
}
