package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The family goal through the brain's shape, as the module it is: its words and rules reach the planner and every worker only when the
 * person names it, its shape reaches the writer and binds the answer to the records, {@code who} is on the chat's table only where the
 * library holds family work and never on a run's, naming the goal names the module, and the suggestion fires on plain family asks in English
 * and Japanese and not on their neighbours. An ordinary run with a family question stays ordinary (pinned again here; the differential
 * tests pin the whole library).
 */
class FamilyGoalTest {
    static final String ASK = "Who were the parents of Tom Hale of Leeds, who died in 1901, what did he do for a living, and under which names should his children Ann and Arthur be searched?";

    @Test
    void theGoalsWordsAndRulesReachThePlannerAndEveryWorkerOnlyWhenNamed() {
        Goal g = Goals.named("family");
        String block = "THIS RUN SERVES THE GOAL family: " + g.words() + " " + g.decisionRules();
        PriorArtGoalTest.Seen named = PriorArtGoalTest.run(ASK, List.of("genealogy"), List.of("family"));
        assertFalse(named.plans.isEmpty(), "the run planned");
        assertTrue(named.plans.get(0).contains(block), "the planner reads the goal's words and rules: " + named.plans.get(0));
        assertTrue(named.plans.get(0).contains("GENEALOGY: This is a family-history question"), "and the module's own plan rules");
        assertFalse(named.workers().isEmpty());
        for (String sys : named.workers()) assertTrue(sys.contains(block) && sys.contains("Work from records."), "every worker reads the goal and the module's register");
        assertEquals("genealogy", LibraryProtocol.goalField("family"), "naming the goal names the module: the person asked for it");
        // the same ask with nothing named runs as ordinary research: no goal block, no module rules
        PriorArtGoalTest.Seen plain = PriorArtGoalTest.run(ASK, List.of(), List.of());
        assertTrue(plain.plans.get(0).contains("Decompose") && !plain.plans.get(0).contains("THE GOAL family") && !plain.plans.get(0).contains("GENEALOGY:"), "an ordinary run on a family-sounding ask carries nothing of the module: " + plain.plans.get(0));
        for (String sys : plain.workers()) assertFalse(sys.contains("THE GOAL family") || sys.contains("Work from records."), "nor do its workers");
        assertTrue(plain.writer().isEmpty() || !plain.writer().contains("THE GOAL family"), "nor its writer");
    }

    @Test
    void theRulesAreLivesNotDatesNamesOverALifeAndIdentityByTheFamily() {
        String rules = Goals.named("family").decisionRules();
        assertTrue(rules.contains("identified by the family's own account before any search") && rules.contains("who answers who they are"), "identity by the family: " + rules);
        assertTrue(rules.contains("tied to a person by a second identifier the family gave") && rules.contains("never by the name alone"), rules);
        assertTrue(rules.contains("every form the person carried in each period of their life") && rules.contains("a question about when the name changed, not a fact"), "names over a life: " + rules);
        assertTrue(rules.contains("What the person did, the trades, the moves, the offices, the public events, is sought as much as when they were born and died"), "lives not dates: " + rules);
        assertTrue(rules.contains("A tree site or an index is a clue, followed to the record it points to"), "a hint is a clue, never the pool: " + rules);
        String words = Goals.named("family").words();
        assertTrue(words.contains("never by a question's words alone") && words.contains("--goal family"), words);
        assertEquals(List.of("who", "search", "shelf"), Goals.named("family").instruments());
    }

    @Test
    void theShapeReachesTheWriterAndBindsTheAnswerToTheRecords() {
        Goal g = Goals.named("family");
        PriorArtGoalTest.Seen drive = PriorArtGoalTest.run(ASK, List.of("genealogy"), List.of("family"));
        String writer = drive.writer();
        assertTrue(writer.contains("THE REPORT SERVES THE GOAL family: " + g.shape()), "the goal's shape reaches the writer");
        assertTrue(writer.contains("GENEALOGY: Open with a section 'Answers'"), "beside the module's own writer rules");
        String shape = g.shape();
        assertTrue(shape.contains("each numbered question of the brief in one of three ways and no other: answered by a record, with the record linked on the line; not found, with the collections, the name forms and the years looked in; or the records disagree, with both sides"), "it names what was checked and what was not: " + shape);
        assertTrue(shape.contains("which record was tied to which person and by what second identifier, and the candidates that could not be tied, named as candidates"), shape);
        assertTrue(shape.contains("Nothing is said of a namesake as of the person, and nothing of a person beyond what a record or the family's own account gave"), "it claims nothing beyond the evidence: " + shape);
    }

    @Test
    void whoIsOnTheChatsTableOnlyWhereTheLibraryHoldsFamilyWorkAndNeverOnARuns(@TempDir Path tmp) throws Exception {
        LibraryStore empty = new LibraryStore(tmp.resolve("empty")); empty.init();
        List<String> chatEmpty = Instruments.forChat(empty).stream().map(Instrument::name).toList();
        assertFalse(chatEmpty.contains("who"), "an ordinary library's chat has no family instrument: " + chatEmpty);
        LibraryStore family = GenealogyMixedLibraryTest.mixed(tmp.resolve("family").resolve("lib"), true);
        List<String> chatFamily = Instruments.forChat(family).stream().map(Instrument::name).toList();
        assertTrue(chatFamily.contains("who"), "a library that holds family work has who on the chat's table: " + chatFamily);
        LibraryStore off = GenealogyMixedLibraryTest.mixed(tmp.resolve("off").resolve("lib"), false);
        assertFalse(Instruments.forChat(off).stream().map(Instrument::name).toList().contains("who"), "not when the module is switched off");
        Researcher.Ask ask = new Researcher.Ask(ASK, "depth", 60, List.of()).withFields(List.of("genealogy"));
        assertFalse(Instruments.forRun(family, Map.of(), ask).stream().map(Instrument::name).toList().contains("who"), "a run's table never carries who: the module's runs bring their tools through its hooks");
    }

    @Test
    void theFamilysCollectionsJoinARunOfTheModule() {
        PriorArtGoalTest.Seen drive = PriorArtGoalTest.run(ASK, List.of("genealogy"), List.of("family"));
        List<String> offered = GoalRunTest.worker(drive);
        assertTrue(offered.contains("record_search") && offered.contains("search") && offered.contains("open"), offered.toString());
        String records = drive.words("record_search");
        for (String id : List.of("loc-newspapers", "wikitree", "epo-inventor")) assertTrue(records.contains("- " + id + ":"), id + " is a collection of the run: " + records);
        assertFalse(records.contains("- courtlistener:") || records.contains("- github-repos:"), records);
    }

    @Test
    void theSuggestionFiresOnPlainAsksInEnglishAndJapaneseAndNotOnNeighbours() {
        Goal g = Goals.named("family");
        for (String q : List.of(
                "Who were my great-grandfather's parents?",
                "How do I start a family tree for my mother's side?",
                "Family history: where did my grandparents live before they emigrated?",
                "My ancestor came from Leeds; which records would show his trade?",
                "What did my grandmother's brother do in the war?",
                "Is there a genealogy of the Hale family of Leeds?",
                "曾祖父の戸籍はどこで請求できますか",
                "家系図を作りたいのですが、何から始めればいいですか",
                "先祖は広島の出身です。どんな記録が残っていますか",
                "曽祖母の実家について調べたい",
                "Wie beginne ich mit der Ahnenforschung?")) {
            Goal.Applies a = g.appliesTo(q);
            assertFalse(a.yes(), "the module never joins by words: " + q);
            assertTrue(a.why().contains("looks like genealogy's") && a.why().contains("joins only when somebody asks"), "but it is suggested: " + q + " -> " + a.why());
        }
        for (String q : List.of(
                "What is the last common ancestor of whales and hippos, and when did it live?",
                "How does git find the merge base, the best common ancestor of two commits?",
                "How do descendant selectors in CSS differ from child selectors?",
                "What does a dog's pedigree tell a breeder about inbreeding coefficients?",
                "How does a grandfather clock's escapement keep time?",
                "A grandmother's recipe for pickled plums",
                "共通祖先から分岐した時期を分子時計でどう推定するか",
                "遺伝性疾患の家系解析で連鎖解析はどう使われるか",
                "Quel est l'ancêtre commun des langues romanes ?",
                "How were the Antikythera gears cut?")) {
            Goal.Applies a = g.appliesTo(q);
            assertFalse(a.yes(), q);
            assertFalse(a.why().contains("looks like genealogy's"), "not even suggested: " + q + " -> " + a.why());
        }
    }
}
