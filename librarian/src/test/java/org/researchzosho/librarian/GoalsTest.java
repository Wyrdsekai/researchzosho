package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fields sorted into goals, sources and conventions (DESIGN_BRAIN §5): every former field is reachable by its new kind, a goal says
 * with a reason whether a question is its own, home and work carry answer rules, the generic goal is the basis alone, several
 * conventions can apply at once, and a module's goal never joins a run by the question's words.
 */
class GoalsTest {

    static final String HOMES = "Find homes currently for sale in Cambridge, Massachusetts: 2,000 to 4,000 square feet, 3 to 4 bedrooms, under $800 per square foot.";
    static final String JOBS = "Find staff engineer openings near Boston at employers that allow remote work.";
    static final String PLAIN = "Why did the Saros cycle matter to Babylonian astronomers?";
    static final String FAMILY = "Who were my great-grandfather's parents, and what did he do for a living in Hiroshima?";

    @Test
    void everyFormerFieldIsReachableByItsNewKind() {
        Map<String, String> goalOf = Map.of("home", "home", "career", "work", "person", "dossier", "organization", "dossier", "shopping", "buy", "patents", "prior-art",
                "law", "what-the-law-says", "genealogy", "family", "crafts", "learn-a-craft", "books", "choose");
        for (var e : goalOf.entrySet()) {
            Profile p = Profiles.named(e.getKey());
            assertNotNull(p, e.getKey());
            Goal g = Goals.named(e.getValue());
            assertNotNull(g, e.getValue());
            assertTrue(g.profiles().stream().anyMatch(x -> x.name().equals(p.name())), p.name() + " backs " + g.name());
        }
        for (String s : List.of("podcasts", "youtube", "software")) assertEquals("choose", Profiles.named(s).goalName(), s);
        assertEquals(List.of("law"), Conventions.named("law").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("science"), Conventions.named("science").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("patents"), Conventions.named("patents").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("youtube"), Places.named("videos").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("books"), Places.named("books").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("podcasts"), Places.named("podcasts").profiles().stream().map(Profile::name).toList());
        assertEquals(List.of("software"), Places.named("software").profiles().stream().map(Profile::name).toList());
        assertTrue(Places.names().containsAll(List.of("web", "scholar", "records", "archives", "old-newspapers", "japanese-fulltext", "forums", "videos", "video-sites", "podcasts", "shops", "books", "software", "shelves")), Places.names().toString());
        assertEquals("generic", Goals.known().get(Goals.known().size() - 1).name(), "the generic goal is last");
        assertTrue(Goals.generic().profiles().isEmpty() && Goals.generic().instruments().isEmpty());
    }

    @Test
    void aGoalSaysWithAReasonWhetherAQuestionIsItsOwn() {
        for (Goal g : Goals.known()) for (String q : List.of(HOMES, JOBS, PLAIN, FAMILY)) {
            Goal.Applies a = g.appliesTo(q);
            assertFalse(a.why().isBlank(), g.name() + " gives no reason for: " + q);
        }
        assertTrue(Goals.named("home").appliesTo(HOMES).yes(), Goals.named("home").appliesTo(HOMES).why());
        assertFalse(Goals.named("home").appliesTo(PLAIN).yes());
        assertTrue(Goals.named("work").appliesTo(JOBS).yes(), Goals.named("work").appliesTo(JOBS).why());
        assertFalse(Goals.named("work").appliesTo(HOMES).yes());
        assertEquals(List.of("home"), Goals.forQuestion(HOMES).stream().map(Goal::name).toList());
        assertEquals(List.of("work"), Goals.forQuestion(JOBS).stream().map(Goal::name).toList());
        assertEquals(List.of("generic"), Goals.forQuestion(PLAIN).stream().map(Goal::name).toList(), "the generic goal when no other applies");
        for (Goal g : Goals.known()) { assertFalse(g.words().isBlank(), g.name()); assertFalse(g.shape().isBlank(), g.name()); assertFalse(g.decisionRules().isBlank(), g.name()); }
        assertTrue(Goals.named("home").instruments().containsAll(List.of("homes", "records")) && Goals.named("work").instruments().contains("board") && Goals.named("choose").instruments().contains("look"));
    }

    @Test
    void theFamilyGoalNeverJoinsByTheQuestionsWords() {
        Goal family = Goals.named("family");
        assertFalse(family.joinsByWords());
        Goal.Applies a = family.appliesTo(FAMILY);
        assertFalse(a.yes());
        assertTrue(a.why().contains("joins only when somebody asks"), a.why());
        assertFalse(Goals.forQuestion(FAMILY).stream().anyMatch(g -> g.name().equals("family")));
        Researcher.Ask ask = new Researcher.Ask(FAMILY, "broad", 0, List.of());
        assertTrue(Fields.forRun(null, ask).stream().noneMatch(p -> p.name().equals("genealogy")), "the module's rules never join by words");
    }

    @Test
    void theGoalsWordsJoinTheHomeAndCareerRulesToARun() {
        Researcher.Ask homes = new Researcher.Ask(HOMES, "broad", 0, List.of());
        assertTrue(Fields.forRun(null, homes).stream().anyMatch(p -> p.name().equals("home")), "the home field's rules join a run about homes");
        assertEquals(List.of("home"), Fields.goalsForRun(null, homes).stream().map(Goal::name).toList());
        Researcher.Ask jobs = new Researcher.Ask(JOBS, "broad", 0, List.of());
        assertTrue(Fields.forRun(null, jobs).stream().anyMatch(p -> p.name().equals("career")));
        Researcher.Ask plain = new Researcher.Ask(PLAIN, "broad", 0, List.of());
        assertTrue(Fields.forRun(null, plain).stream().noneMatch(p -> p.name().equals("home") || p.name().equals("career")));
        assertEquals(List.of("generic"), Fields.goalsForRun(null, plain).stream().map(Goal::name).toList());
        assertTrue(Goals.generic().planRules().isEmpty() && Goals.generic().criticRules().isEmpty() && Goals.generic().writerRules().isEmpty(), "the generic goal is the basis alone");
    }

    @Test
    void homeAndWorkCarryAnswerRulesAndTheyReachARunAskedInPlainWords() {
        for (String name : List.of("home", "work")) {
            Goal g = Goals.named(name);
            assertTrue(g.planRules().contains("restate") && g.criticRules().contains("dropped, not kept with a guess") && g.writerRules().contains("link on its line"), name);
        }
        FieldRulesTest.Recording drive = new FieldRulesTest.Recording();
        var r = new Researcher(drive, new ResearcherTest.FakeTools(), null, 2).run(new Researcher.Ask(HOMES, "depth", 60, List.of()), "");
        assertNotNull(r);
        String plan = drive.prompts.stream().filter(p -> p.contains("Decompose")).findFirst().orElse("");
        assertTrue(plan.contains("HOME: This is a question about homes"), "the home rules reach the planner of a run asked in plain words: " + plan);
    }

    @Test
    void severalConventionsCanApplyAtOnce() {
        String q = "Is patent US12450652B1 enforceable under California zoning law, and what does the statute of limitations say?";
        List<String> names = Conventions.forQuestion(q).stream().map(Convention::name).toList();
        assertTrue(names.contains("law") && names.contains("patents"), names.toString());
        for (Convention c : Conventions.known()) { assertFalse(c.claimRules().isBlank(), c.name()); assertFalse(c.appliesTo(PLAIN).why().isBlank(), c.name()); }
        assertTrue(Fields.conventionsForRun(null, new Researcher.Ask(q, "broad", 0, List.of())).stream().map(Convention::name).toList().containsAll(List.of("law", "patents")));
    }

    @Test
    void aPlaceNamesItsToolsAndItsKeys() {
        assertTrue(Places.named("shops").keys().contains("RESEARCHZOSHO_EBAY_CLIENT_ID"));
        assertTrue(Places.named("web").keys().contains("RESEARCHZOSHO_BRAVE_KEY"));
        assertEquals("web_search", Places.named("web").tools("a question", null).get(0).name());
        assertEquals("scholar_search", Places.named("scholar").tools("a question", null).get(0).name());
        assertEquals("book_search", Places.named("books").tools("a question", null).get(0).name());
        assertTrue(Places.named("shelves").tools("a question", null).isEmpty(), "the shelves are the run's own");
        for (Source s : Places.known()) { assertFalse(s.words().isBlank(), s.name()); assertNotNull(s.tools("a question", null), s.name()); }
    }

    @Test
    void scienceIsCarriedByAQuestionOnTheScientificRecordAndNeverByAnAmbiguousWord() {
        for (String q : List.of("What does the scientific literature say about intermittent fasting?", "Is there a peer-reviewed study on standing desks?",
                "Find the preprint behind arXiv:2401.01234", "What did doi 10.1038/nature12373 measure?", "この論文の主張は先行研究と合うか", "Has a clinical trial tested it?"))
            assertTrue(Conventions.forQuestion(q).stream().anyMatch(c -> c.name().equals("science")), q);
        for (String q : List.of("Find me a scientific calculator under $20", "Busco un estudio en Madrid", "a paper bag tutorial", "How do I set up database replication?", PLAIN))
            assertFalse(Conventions.forQuestion(q).stream().anyMatch(c -> c.name().equals("science")), q);
    }

    @Test
    void aRunsWorkersAndWriterCarryTheConventionsItsClaimsDo() {
        RunBrainTest.Drive drive = new RunBrainTest.Drive();
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, null)
                .run(new Researcher.Ask("Under which statute may a New York tenant sublet, and what does the ordinance say?", "depth", 80, List.of()), "");
        boolean worker = false, writer = false;
        for (var h : drive.histories) {
            String sys = h.get(0).path("content").asText();
            String all = h.toString();
            if (!sys.contains("THE LAW CONVENTION. Carried when a claim is about what a law")) continue;
            if (all.contains("write_section") || sys.contains("Then call done.")) writer = true; else worker = true;
        }
        assertTrue(worker, "a worker reads how a claim of law is written");
        assertTrue(drive.histories.stream().anyMatch(h -> h.toString().contains("THE LAW CONVENTION.") && h.toString().contains("Then call done.")), "and so does the writer");
        RunBrainTest.Drive plain = new RunBrainTest.Drive();
        new Researcher(plain, plain, new ResearcherTest.FakeTools(), s -> { }, 1, null).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 80, List.of()), "");
        assertFalse(plain.histories.get(0).get(0).path("content").asText().contains(" CONVENTION. "), "a question of no convention starts with none");
        assertTrue(plain.histories.stream().anyMatch(h -> h.size() > 1 && h.get(1).path("content").asText().contains("Was the wreck's salvage disputed in court?")
                && h.get(0).path("content").asText().contains("THE LAW CONVENTION.")), "a law sub-goal pursued mid-run brings the law convention with it");
    }
}

