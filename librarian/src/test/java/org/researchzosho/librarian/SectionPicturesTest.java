package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library decides the sections' pictures (0.7.0): at the plan, each sub-question whose words name a thing a picture can show gets one
 * ask — a diagram of a mechanism, a map of a place, a portrait, a photograph of an object — made after the workers on a held turn, judged
 * under the shows-rule in the question's connection, the first yes kept and handed to the writer with the section it belongs to; a
 * sub-question that argues or interprets gets none, and the trace says why. Bounded at five a report, and stopped with the numbers when the
 * write-up's time is up. The drive, the engines, the fetcher and the seeing model are stood in.
 */
class SectionPicturesTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String GEARS = "How does the gear train of the Antikythera Mechanism model the Moon's varying speed?", WRECK = "Where exactly was the Antikythera wreck found?", WHY = "What is the significance of the Antikythera Mechanism for the history of science?";
    /** A SOURCES line of a picture the library kept for a section: its number, its section, its line. */
    static final Pattern HANDED = Pattern.compile("(?m)^\\[(\\d+)\\] .*— a picture, judged \"[^\"]*\": yes; it belongs to the section on \"([^\"]+)\": to show it, put this line on a line of its own under the paragraph of that section: (!\\[[^\\]]*\\]\\(/images/[^)]+\\))$");

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; }

    @Test void thePlanReadsWhatEachSectionNeedsShownFromTheSubQuestionsWordsAlone() {
        Set<String> chain = Set.of("antikythera mechanism");
        RunPictures.SectionAsk a = RunPictures.sectionAsk(GEARS, chain);
        assertNull(a.skipped(), a.toString()); assertEquals("a diagram of the gear train of the Antikythera Mechanism", a.what()); assertEquals("diagram", a.kind());
        a = RunPictures.sectionAsk(WRECK, chain);
        assertNull(a.skipped(), a.toString()); assertEquals("a map of the Antikythera wreck site", a.what()); assertEquals("map", a.kind());
        a = RunPictures.sectionAsk(WHY, chain);
        assertEquals("it asks why, or weighs significance or interpretation; a section of argument stands on its words", a.skipped());
        assertEquals("it asks why, or weighs significance or interpretation; a section of argument stands on its words", RunPictures.sectionAsk("Why does the gear train give the Moon a varying speed?", chain).skipped());
        a = RunPictures.sectionAsk("how did the pin-and-slot mechanism produce the anomaly?", chain);
        assertEquals("a diagram of the pin-and-slot mechanism", a.what(), "a sub-question in lower case, its hyphenated head read");
        a = RunPictures.sectionAsk("What do the inscriptions say?", chain);
        assertEquals("a photograph of the inscriptions", a.what()); assertEquals("photo", a.kind());
        a = RunPictures.sectionAsk("Who was Valerios Stais?", Set.of());
        assertEquals("a portrait of Valerios Stais", a.what()); assertEquals("photo", a.kind());
        assertEquals("the run looks for pictures of valerios stais already", RunPictures.sectionAsk("Who was Valerios Stais?", Set.of("valerios stais")).skipped(), "the question's own subject has the chain's pictures; no second ask");
        assertEquals("the run looks for pictures of antikythera mechanism already", RunPictures.sectionAsk("What is the Antikythera Mechanism?", chain).skipped());
        assertEquals("its words name no thing a picture can show", RunPictures.sectionAsk("What did the Ephor General decide?", chain).skipped(), "a title is no thing to show");
        assertEquals("its words name no thing a picture can show", RunPictures.sectionAsk("what happened next?", chain).skipped());
        assertEquals("a map of the shipwreck site near Antikythera", RunPictures.sectionAsk("Where is the shipwreck site near Antikythera?", chain).what(), "\"site\" is not said twice (J-0013)");
        // J-0014 (2026-11-08): two sub-questions of argument that slipped through, and a name inside one that earns a portrait
        assertEquals("it asks why, or weighs significance or interpretation; a section of argument stands on its words", RunPictures.sectionAsk("To what extent is the current understanding of the lunar gear train based on speculative reconstruction rather than surviving evidence?", chain).skipped());
        assertEquals("it asks why, or weighs significance or interpretation; a section of argument stands on its words", RunPictures.sectionAsk("Who was the likely creator or patron of the Antikythera Mechanism, and how does its existence challenge previous assumptions about ancient technology?", chain).skipped(), "a role word alone names nobody");
        a = RunPictures.sectionAsk("Who was the likely creator or patron of the mechanism — Valerios Stais, as some have assumed?", chain);
        assertEquals("a portrait of Valerios Stais", a.what(), "a person NAMED in a sub-question of argument earns a portrait"); assertEquals("photo", a.kind());
        // Japanese sub-questions: the heads name the thing, the kind's word follows the phrase
        a = RunPictures.sectionAsk("ピンとスロット機構の仕組みは？", chain); assertEquals("ピンとスロット機構の図", a.what()); assertEquals("diagram", a.kind());
        a = RunPictures.sectionAsk("アンティキティラの沈没船はどこで見つかった？", chain); assertEquals("アンティキティラの沈没船の地図", a.what()); assertEquals("map", a.kind());
        assertEquals("it asks why, or weighs significance or interpretation; a section of argument stands on its words", RunPictures.sectionAsk("なぜ歯車が重要なのか？", chain).skipped());
        a = RunPictures.sectionAsk("Which museum in Athens holds the fragments today?", chain);
        assertEquals("a map of the museum in Athens", a.what(), "a place wins over an object; the phrase is the words around the head, with \"the\" before a bare head");
        assertEquals("a photograph of the inscriptions on the fragments", RunPictures.sectionAsk("What do the inscriptions on the fragments say?", chain).what(), "the phrase runs on over a preposition to what follows");
    }

    @Test void theLibraryAsksForTheSectionsPicturesBeforeTheWriteUpAndTheWriterPlacesThemUnderTheirSections(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram of the gear train", "a map of the Antikythera wreck");
        Map<String, String> handedLines = new LinkedHashMap<>(), handedNumbers = new LinkedHashMap<>();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools);
                if (!n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);   // the workers: web_search, web_fetch, note on example.org/gears
                offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (handedLines.isEmpty()) {   // the writer reads the SOURCES list once: which picture belongs to which section, and its number
                    Matcher m = HANDED.matcher(history.get(1).path("content").asText());
                    while (m.find()) { handedLines.put(m.group(2), m.group(3)); handedNumbers.put(m.group(2), "[" + m.group(1) + "]"); }
                }
                int turn = assistantTurns(history) + 1;
                return switch (turn) {
                    // the sections' pictures were kept before the workers drew, so they are the first sources, [1] and [2]; the workers' page is [3]
                    case 1 -> call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "The mechanism modelled the heavens with bronze gears [3]."));
                    case 2 -> call("write_section", J.createObjectNode().put("heading", "The gear train").put("text", "A pin-and-slot pair gave the Moon's pointer its varying speed [3]" + handedNumbers.getOrDefault(GEARS, "") + ".\n\n" + handedLines.getOrDefault(GEARS, "(no picture handed)") + "\n\nThe largest wheel drove the rest [3]."));
                    case 3 -> call("write_section", J.createObjectNode().put("heading", "The wreck site").put("text", "The wreck lies off the island's north-east coast [3]" + handedNumbers.getOrDefault(WRECK, "") + ".\n\n" + handedLines.getOrDefault(WRECK, "(no picture handed)") + "\n\nSponge divers found it [3]."));
                    case 4 -> call("write_section", J.createObjectNode().put("heading", "Significance").put("text", "It is the oldest known geared computer [3]."));
                    case 5 -> call("write_section", J.createObjectNode().put("heading", "Sources").put("text", "https://example.org/gears"));
                    default -> call("done", J.createObjectNode().put("summary", "two pictures placed where the library said"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-sections");
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 3, store);
        researcher.trace(trace);
        var r = researcher.run(new Researcher.Ask("How does the Antikythera Mechanism work, and where was its wreck found?", "depth", 60, List.of(GEARS, WRECK, WHY)), "");
        assertTrue(r.done(), r.summary());
        // the plan: two asks, the abstract sub-question none, and the turns held for them
        assertTrue(log.stream().anyMatch(l -> l.startsWith("pictures: 2 of 3 sub-question(s) name a thing a picture can show; the library will ask for \"a diagram of the gear train of the Antikythera Mechanism\" for \"How does the gear train") && l.contains("\"a map of the Antikythera wreck site\" for \"Where exactly was the Antikythera wreck found?\" before the write-up")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("pictures: 3 turns kept back from the workers for pictures of Antikythera Mechanism and for the sections on 2 sub-questions, so the step after them has its turns")), log.toString());
        // the asks ran after the workers, each kept one picture, said with the words and the criteria
        List<RunPictures.Picture> forSections = researcher.pictures.kept.stream().filter(p -> !p.section().isEmpty()).toList();
        assertEquals(List.of(GEARS, WRECK), forSections.stream().map(RunPictures.Picture::section).toList(), researcher.pictures.kept.toString());
        RunPictures.Picture gears = forSections.get(0), wreck = forSections.get(1);
        assertEquals("a diagram of the gear train of the Antikythera Mechanism", gears.judged().criteria(), "the words carry the subject: no connection added");
        assertEquals("a map of the Antikythera wreck site, in connection with Antikythera Mechanism", wreck.judged().criteria(), "the words do not carry the whole subject: the connection is added");
        assertTrue(questions.stream().anyMatch(q -> q.startsWith("Does this picture show a map of the Antikythera wreck site, in connection with Antikythera Mechanism? ") && q.contains(Images.SHOWS_RULE)), questions.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("pictures: a picture for \"" + GEARS + "\" (a diagram of the gear train of the Antikythera Mechanism): ") && l.contains(" judged against \"a diagram of the gear train of the Antikythera Mechanism\", kept as " + gears.kept().id())), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("pictures: a picture for \"" + WRECK + "\" (a map of the Antikythera wreck site): ") && l.contains("found for \"a map of the Antikythera wreck site Antikythera Mechanism\" through Brave and SearXNG, ") && l.contains(", kept as " + wreck.kept().id())), log.toString());
        assertNotEquals(gears.kept().url(), wreck.kept().url(), "two pictures: the one kept for the gearing is not judged again for the wreck");
        // the writer was handed the lines with their sections and told where they go
        assertEquals(Set.of(GEARS, WRECK), handedLines.keySet(), handedLines.toString());
        assertEquals(gears.line(), handedLines.get(GEARS)); assertEquals(wreck.line(), handedLines.get(WRECK));
        String writerPrompt = drive.histories.stream().filter(h -> h.size() > 1 && h.get(1).path("content").asText().contains("SOURCES (cite by number")).map(h -> h.get(1).path("content").asText()).findFirst().orElseThrow();
        assertTrue(writerPrompt.contains("A picture marked as belonging to a section goes under that section's paragraph, cited there by its number."), writerPrompt);
        // the report: each picture under the paragraph of its section, cited by the number the library gave, and its full reference line
        assertEquals("[1]", handedNumbers.get(GEARS)); assertEquals("[2]", handedNumbers.get(WRECK));
        assertTrue(r.answer().contains("## The gear train\n\nA pin-and-slot pair gave the Moon's pointer its varying speed [3][1].\n\n" + gears.line() + "\n\nThe largest wheel drove the rest [3]."), r.answer());
        assertTrue(r.answer().contains("## The wreck site\n\nThe wreck lies off the island's north-east coast [3][2].\n\n" + wreck.line() + "\n\nSponge divers found it [3]."), r.answer());
        assertTrue(r.answer().contains("[1] Wild Dog Breeds: Red Fox — " + RunPicturesTest.FOX + "  (a picture, kept as " + gears.kept().id() + ", from the page " + RunPicturesTest.FOX_PAGE + "; judged \"a diagram of the gear train of the Antikythera Mechanism\": yes — a red fox stands in snow)"), r.answer());
        assertTrue(r.answer().contains("[2] " + wreck.title()) && r.answer().contains("(a picture, kept as " + wreck.kept().id() + ", from the page " + wreck.kept().found().page() + "; judged \"a map of the Antikythera wreck site, in connection with Antikythera Mechanism\": yes — a red fox stands in snow)"), r.answer());
        assertTrue(r.answer().contains("[3] ") && r.answer().contains("https://example.org/gears"), "the workers' page is numbered after the sections' pictures: " + r.answer());
        assertFalse(r.answer().contains("## Significance\n\nIt is the oldest known geared computer [3].\n\n!["), "the abstract section has no picture");
        assertEquals(Set.of(gears.kept().url(), wreck.kept().url()), researcher.pictures.forSections);
        // the trace: each ask by the library with its outcome, the skipped sub-question with its reason
        String traced = Files.readString(RunTrace.fileFor(store, "J-sections"));
        assertTrue(traced.contains("\"capture\":\"picture_for\"") && traced.contains("\"by\":\"the library\"") && traced.contains("\"section\":\"" + WRECK + "\"") && traced.contains("\"outcome\":\"kept as " + wreck.kept().id() + "\"") && traced.contains("\"outcome\":\"kept as " + gears.kept().id() + "\""), traced);
        assertTrue(traced.contains("\"section\":\"" + WHY + "\"") && traced.contains("\"outcome\":\"skipped: it asks why, or weighs significance or interpretation; a section of argument stands on its words\""), traced);
        assertTrue(traced.contains("\"why_searched\":\"the library asked for a map of the Antikythera wreck site for the section on \\\"" + WRECK + "\\\"\""), traced);
        // the writer's own tool stayed on the table, and the library's two count toward its five
        assertTrue(drive.offered.stream().anyMatch(o -> o.contains("write_section") && o.contains(RunPictures.FOR)), drive.offered.toString());
        assertEquals(2, researcher.pictures.keptForSections());
        // the order: the plan, the turns kept back, the subjects' pictures, the sections' pictures, and only then the workers (J-0013: after them, the clock was gone)
        int plan = first(log, "plan: "), held = first(log, "pictures: 3 turns kept back"), subjects = first(log, "pictures: \"Antikythera Mechanism\" searched because the question names"), sections = first(log, "pictures: a picture for \""), workers = first(log, "critic: ");
        assertTrue(plan >= 0 && plan < held && held < subjects && subjects < sections && sections < workers, "plan " + plan + " < held " + held + " < subjects " + subjects + " < sections " + sections + " < workers " + workers + "\n" + String.join("\n", log));
    }

    static int first(List<String> log, String prefix) { for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(prefix)) return i; return -1; }

    @Test void aTwentyFiveMinuteRunOnAFiftyFiveSecondModelMakesItsSectionAsksAndKeepsItsWorkersPastMinuteTwelve(@TempDir Path tmp) throws Exception {
        // J-0003's and J-0013's model: worker turns 55 s typical, a long one at 131 s, classify calls 9 s; a 25-minute ceiling, 60 turns
        ResearchSettings.Throttle t = new ResearchSettings.Throttle(null);
        for (int i = 0; i < 8; i++) t.observe(55_000, 6_000);
        t.observe(131_000, 6_000);
        for (int i = 0; i < 17; i++) t.observeShort(9_000);
        Researcher.Budget budget = new Researcher.Budget(60, 25);
        budget.wrapUp(Researcher.wrapUpEstimate(t.typicalTurnMs(), Researcher.shortCallMs(t), 1)); budget.stepTime(t.longTurnMs());
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram", "a map");
        List<String> log = new ArrayList<>();
        RunPictures pictures = new RunPictures(store, log::add, (k, o) -> { });
        Researcher.Ask ask = new Researcher.Ask("How does the Antikythera Mechanism work, and where was its wreck found?", "depth", 60, List.of(GEARS, WRECK, WHY), null, null, 25, null, null, null);
        assertEquals(2, pictures.planSections(List.of(GEARS, WRECK, WHY), ask));
        budget.hold(pictures.sectionTurnsToReserve(budget) + 1);
        assertEquals(3, budget.held());
        assertTrue(budget.minutesLeftForWorkers() > 12, "the workers keep past minute 12 of 25: " + budget.minutesLeftForWorkers() + " min left for them, the write-up reckoned at " + budget.wrapUpMs() / 60_000.0 + " min");
        assertFalse(budget.writeUpTimeUp());
        String piece = pictures.sections(ask, budget);
        assertFalse(piece.isEmpty(), String.join("\n", log));
        assertEquals(2, log.stream().filter(l -> l.startsWith("pictures: a picture for \"") && l.contains(", kept as ")).count(), String.join("\n", log));
        assertEquals(2, budget.used()); assertEquals(1, budget.held(), "the two asks took two of the three held turns; the subjects' one is still held");
    }

    @Test void aKeptPictureWithNoTitleIsNamedByWhatItWasAskedForNeverByItsFile(@TempDir Path tmp) throws Exception {
        // J-0014: an engine gave no title and the line read ![94ebfd5d3f99c353.png](/images/94ebfd5d3f99c353.png)
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Found untitled = new Images.Found("https://pictures.example/gears/train.png", "https://pictures.example/gears", "", "Brave", 0, 0, "");
        Images.Judged j = new Images.Judged(untitled, Images.Verdict.YES, "the gears in a train", "m", "a diagram of the gear train, in connection with Antikythera Mechanism");
        Images.Kept k = Images.keep(store, untitled, Images.fetch(store, untitled), "", "", j);
        assertEquals("a diagram of the gear train", k.found().title(), "what it was asked for stands as the title");
        assertEquals("a diagram of the gear train", Images.entryFor(store, k.url()).title(), "and on the shelves");
        RunPictures.Picture p = new RunPictures.Picture(k, j, "a picture for the section on \"x\"", "why", "x");
        assertEquals("![a diagram of the gear train](" + k.url() + ")", p.line());
        // judged beside a reference, the criteria name no thing a reader wants: the page's host stands in
        Images.Found untitled2 = new Images.Found("https://pictures.example/faces/two.png", "https://pictures.example/faces", "", "Brave", 0, 0, "");
        Images.Kept k2 = Images.keep(store, untitled2, Images.fetch(store, untitled2), "", "", new Images.Judged(untitled2, Images.Verdict.YES, "the same face", "m", "the same person (Valerios Stais) as the picture kept as 2026-x"));
        assertEquals("pictures.example", k2.found().title());
        assertEquals("pictures.example", Images.keep(store, new Images.Found("https://pictures.example/no-judgement.png", "https://pictures.example/p", "", "Brave", 0, 0, ""), Images.fetch(store, new Images.Found("https://pictures.example/no-judgement.png", "", "", "Brave", 0, 0, "")), "", "", null).found().title(), "no judgement: the host");
    }

    @Test void theAsksAreFiveAReportAtMostAndStopWithTheNumbersWhenTheWriteUpsTimeIsUp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram", "a map", "a photograph");
        List<String> log = new ArrayList<>(); List<ObjectNode> events = new ArrayList<>();
        RunPictures pictures = new RunPictures(store, log::add, (t, o) -> { if (t.equals("picture_for")) events.add(o); });
        Researcher.Ask ask = new Researcher.Ask("How does the Antikythera Mechanism work?", "depth", 60, List.of());
        List<String> subs = List.of("How does the gear train work?", "Where is the wreck site?", "How do the dials read?", "What do the inscriptions say?", "How does the pin-and-slot mechanism move?", "Where is the museum that holds it?", "How does the escapement work?", WHY);
        assertEquals(5, pictures.planSections(subs, ask));
        assertEquals(List.of("a diagram of the gear train", "a map of the wreck site", "a diagram of the dials", "a photograph of the inscriptions", "a diagram of the pin-and-slot mechanism"), pictures.planned().stream().map(RunPictures.SectionAsk::what).toList());
        assertEquals(3, events.stream().filter(o -> o.path("outcome").asText().startsWith("skipped: ")).count(), events.toString());
        assertEquals(2, events.stream().filter(o -> o.path("outcome").asText().equals("skipped: 5 pictures for the sections are asked for already, the most for a report")).count(), events.toString());
        // the held turns: one an ask, bounded by a sixth of the workers' share
        Researcher.Budget budget = new Researcher.Budget(60);
        assertEquals(5, pictures.sectionTurnsToReserve(budget));
        assertEquals(1, pictures.sectionTurnsToReserve(new Researcher.Budget(20)), "a small run holds one");
        assertEquals("pictures: 5 turns kept back from the workers for the sections on 5 sub-questions, so the step after them has its turns", pictures.reservedLine(ask, 0, 5));
        assertEquals("pictures: 3 turns kept back from the workers for pictures of Antikythera Mechanism and for the sections on 2 sub-questions, so the step after them has its turns", pictures.reservedLine(ask, 1, 2));
        // no time: the step says so with the numbers and asks nothing
        Researcher.Budget late = new Researcher.Budget(60, 1); late.wrapUp(10 * 60_000); late.hold(5);
        events.clear();
        assertEquals("", pictures.sections(ask, late));
        String noTime = log.get(log.size() - 1);
        assertTrue(noTime.startsWith("pictures: no time left for pictures for the sections on \"How does the gear train work?\", \"Where is the wreck site?\", \"How do the dials read?\", \"What do the inscriptions say?\", \"How does the pin-and-slot mechanism move?\": ") && noTime.endsWith(" min left, the write-up's estimate is 10.0 min"), noTime);
        assertEquals(5, events.stream().filter(o -> o.path("outcome").asText().equals("refused: no time")).count(), events.toString());
        assertEquals(0, late.used()); assertTrue(questions.isEmpty(), "nothing went to the model");
        // with time, the asks run on the held turns, the first yes kept each
        events.clear();
        budget.hold(5);
        String piece = pictures.sections(ask, budget);
        assertTrue(piece.startsWith("SUB-QUESTION: pictures for the report's sections\nSUMMARY: The library looked for a picture for each sub-question whose words name a thing a picture can show"), piece);
        assertTrue(piece.contains("- A picture judged to show a diagram of the gear train, in connection with Antikythera Mechanism: \"Wild Dog Breeds: Red Fox\", from a-z-animals.com — source: " + RunPicturesTest.FOX), piece);
        assertEquals(5, budget.used(), "one held turn an ask"); assertEquals(0, budget.held());
        assertTrue(pictures.keptForSections() >= 2 && pictures.keptForSections() <= 5, "the fixtures hold a few distinct pictures; each ask keeps a new one until they run out: " + pictures.keptForSections());
        assertEquals(5, events.stream().filter(o -> o.path("by").asText().equals("the library") && !o.path("words").asText().isEmpty()).count(), events.toString());
    }
}
