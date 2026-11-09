package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The writer asks for a picture a section needs (0.7.0): {@code picture_for} on the write-up's table, the question's subject beside the
 * writer's words, the candidates judged under the run's own rule, the first yes kept, placed under the paragraph and numbered among the
 * references; one ask a section, five a report, each on a turn of the write-up's reserve, none once the write-up's time is up; the report
 * shows five pictures, the writer's own first. The drive, the engines, the fetcher and the seeing model are stood in.
 */
class PictureForTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String FOX = RunPicturesTest.FOX, FOX_PAGE = RunPicturesTest.FOX_PAGE;

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

    static ObjectNode args(String section, String what, String kind) { return J.createObjectNode().put("section", section).put("what", what).put("kind", kind); }

    /** Every answer the writer got from {@code tool}, in order, read off the longest history the drive saw. */
    static List<String> toolAnswers(ResearcherTest.ScriptedDrive drive, String tool) {
        ArrayNode longest = null;
        for (ArrayNode h : drive.histories) if (longest == null || h.size() > longest.size()) longest = h;
        List<String> out = new ArrayList<>();
        if (longest == null) return out;
        for (int i = 1; i < longest.size(); i++) {
            JsonNode m = longest.get(i);
            if (!"tool".equals(m.path("role").asText())) continue;
            for (JsonNode c : longest.get(i - 1).path("tool_calls")) if (c.path("function").path("name").asText().equals(tool) && c.path("id").asText().equals(m.path("tool_call_id").asText())) out.add(m.path("content").asText());
        }
        return out;
    }

    @Test void theWriterAsksForAPictureASectionNeedsAndTheReportShowsItUnderTheParagraphWithItsReference(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a diagram of the gear train");
        String line = "![Wild Dog Breeds: Red Fox](/images/" + RunPicturesTest.sha16(Vision.square(255, 0, 0)) + ".png)";
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools);
                if (!n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);   // the worker: web_search, web_fetch, note on example.org/gears
                offered.add(String.join(",", n)); histories.add(history.deepCopy());
                int turn = assistantTurns(history) + 1;
                return switch (turn) {
                    case 1 -> call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "The mechanism computed the heavens with bronze gears [1]."));
                    case 2 -> call(RunPictures.FOR, args("The gearing", "a diagram of the gear train", "diagram"));
                    case 3 -> {
                        String got = history.get(history.size() - 1).path("content").asText();   // the tool's answer: the line and the number, copied as the writer would
                        String cite = got.contains("Cite it as [2]") ? "[2]" : "[?]";
                        yield call("write_section", J.createObjectNode().put("heading", "The gearing").put("text", "A train of some thirty gears turned the pointers [1]" + cite + ".\n\n" + line + "\n\nThe largest wheel drove the rest [1]."));
                    }
                    case 4 -> call(RunPictures.FOR, args("the gearing", "another diagram of the same train", "diagram"));   // refused: a section has one
                    case 5 -> call("write_section", J.createObjectNode().put("heading", "Sources").put("text", "https://example.org/gears"));
                    default -> call("done", J.createObjectNode().put("summary", "one picture asked for"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-picture-for");
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        researcher.trace(trace);
        var r = researcher.run(new Researcher.Ask("How did the Antikythera Mechanism work?", "depth", 60, List.of("how did the antikythera mechanism work?")), "");
        // the tool was on the writer's table, and the writer was told where a picture earns its place
        assertTrue(drive.offered.stream().anyMatch(o -> o.contains("write_section") && o.contains(RunPictures.FOR)), drive.offered.toString());
        String system = drive.histories.stream().filter(h -> h.size() > 1 && h.get(1).path("content").asText().contains("SOURCES (cite by number")).map(h -> h.get(0).path("content").asText()).findFirst().orElseThrow();
        assertTrue(system.contains("PICTURES FOR THE REPORT: picture_for brings a picture a section needs — the thing the section describes: a mechanism's diagram, a map of the place, the object itself, the person, a chart that exists. Ask for one where the reader needs to see the thing, one a section and at most 5 in the report, before you write that section;"), system);
        // the judge was asked the run's own question: the writer's words, the question's subject beside them, the shows-rule
        String asked = questions.stream().filter(q -> q.contains("a diagram of the gear train")).findFirst().orElseThrow(() -> new AssertionError(questions.toString()));
        assertTrue(asked.startsWith("Does this picture show a diagram of the gear train, in connection with Antikythera Mechanism? The page it stands on is titled \"Wild Dog Breeds: Red Fox\". " + Images.SHOWS_RULE), asked);
        // the answers: the line to place and the number to cite; then the refusal, in words, for a second ask on the same section
        List<String> answers = toolAnswers(drive, RunPictures.FOR);
        assertEquals(2, answers.size(), answers.toString());
        assertEquals("A picture for the section 'The gearing': \"Wild Dog Breeds: Red Fox\", from a-z-animals.com, judged by default (qwen3.8-27b): yes — a red fox stands in snow. Put this line on a line of its own under the paragraph it belongs to:\n" + line + "\nCite it as [2] after that paragraph; the library writes its reference.", answers.get(0));
        assertEquals("A picture was asked for the section 'the gearing' already, and a section has one at most. Go on with the section.", answers.get(1));
        // the report: the picture under the paragraph of its section, the reference numbered after the worker's source, saying it is a picture and what it was judged against
        assertTrue(r.done(), r.summary());
        assertTrue(r.answer().contains("## The gearing\n\nA train of some thirty gears turned the pointers [1][2].\n\n" + line + "\n\nThe largest wheel drove the rest [1]."), r.answer());
        Images.Shelved fox = Images.onShelves(store).stream().filter(s -> s.page().equals(FOX_PAGE)).findFirst().orElseThrow();
        assertTrue(r.answer().contains("[2] Wild Dog Breeds: Red Fox — " + FOX + "  (a picture, kept as " + fox.id() + ", from the page " + FOX_PAGE + "; judged \"a diagram of the gear train, in connection with Antikythera Mechanism\": yes — a red fox stands in snow)"), r.answer());
        assertTrue(r.answer().contains("[1] ") && r.answer().contains("https://example.org/gears"), r.answer());
        assertEquals(Set.of(fox.url()), researcher.pictures.forSections);
        // the log and the trace say what was asked and what came of it
        assertTrue(log.stream().anyMatch(l -> l.startsWith("write-up: a picture for the section 'The gearing' (a diagram of the gear train): ") && l.contains("judged against \"a diagram of the gear train, in connection with Antikythera Mechanism\", kept as " + fox.id() + ", cited as [2]")), log.toString());
        assertTrue(log.contains("write-up: 1 picture(s) asked for by the writer, 1 found and kept; 1 more asked for past the bounds or the time, and not looked for"), log.toString());
        String traced = Files.readString(RunTrace.fileFor(store, "J-picture-for"));
        assertTrue(traced.contains("\"capture\":\"picture_for\"") && traced.contains("\"section\":\"The gearing\"") && traced.contains("\"what\":\"a diagram of the gear train\"") && traced.contains("\"kind\":\"diagram\"") && traced.contains("\"outcome\":\"kept as " + fox.id() + "\"") && traced.contains("\"outcome\":\"refused: the section has its ask\""), traced);
        assertTrue(traced.contains("\"why_searched\":\"the writer asked for a diagram of the gear train for the section 'The gearing'\""), traced);
        assertEquals(1, r.stats().path("writer_pictures").asInt()); assertEquals(1, r.stats().path("writer_pictures_kept").asInt());
    }

    @Test void theAsksAreBoundedOneASectionFiveAReportAndSaidWhenTimeOrTurnsAreGone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> RunPicturesTest.eyesFor(questions, "a map", "a diagram", "a photograph", "a chart");
        List<String> log = new ArrayList<>(); List<ObjectNode> events = new ArrayList<>();
        RunPictures pictures = new RunPictures(store, log::add, (t, o) -> { if (t.equals("picture_for")) events.add(o); });
        Researcher.Budget budget = new Researcher.Budget(40);
        RunPictures.PictureForTool tool = pictures.pictureFor("Antikythera", budget, p -> "[9]");
        String first = tool.execute(args("The dive", "a map of the wreck site.", "map"));
        assertTrue(first.startsWith("A picture for the section 'The dive': \"Wild Dog Breeds: Red Fox\", from a-z-animals.com, judged by default (qwen3.8-27b): yes — a red fox stands in snow. Put this line on a line of its own under the paragraph it belongs to:\n![Wild Dog Breeds: Red Fox](/images/"), first);
        assertTrue(first.endsWith("\nCite it as [9] after that paragraph; the library writes its reference."), first);
        assertEquals(1, questions.size(), "the first yes is enough: no further candidate goes to the model: " + questions);
        assertTrue(questions.get(0).startsWith("Does this picture show a map of the wreck site, in connection with Antikythera?"), questions.get(0));
        assertEquals("a map of the wreck site Antikythera", events.get(0).path("words").asText(), "the subject joins the words; the kind the words say already is not added twice");
        // the same section, however spelled, has its one ask
        assertEquals("A picture was asked for the section 'the dive' already, and a section has one at most. Go on with the section.", tool.execute(args("the dive", "a photograph of the divers", "photo")));
        for (String s : List.of("The gearing", "The dials", "The inscriptions", "The museum")) tool.execute(args(s, "a diagram of " + s.toLowerCase() + " of the Antikythera mechanism", "any"));
        assertEquals(5, tool.calls);
        assertEquals("No more pictures for this report: 5 were asked for already, its most. Go on with the sections.", tool.execute(args("The wreck", "a photograph of the wreck", "photo")));
        assertEquals(2, tool.refused);
        assertEquals(5, budget.used(), "each ask that ran took one turn of the write-up's reserve; the refusals took none");
        assertEquals("a diagram of the gearing of the Antikythera mechanism", events.stream().filter(o -> o.path("section").asText().equals("The gearing")).findFirst().orElseThrow().path("words").asText(), "words that carry the subject are searched as they are");
        // ERRORS for a missing argument, in words
        assertEquals("ERROR: section is needed: the heading of the section the picture is for.", tool.execute(J.createObjectNode().put("what", "a map")));
        assertEquals("ERROR: what is needed: what the picture should show, in words.", tool.execute(J.createObjectNode().put("section", "The dive")));
        // no turn left: only the cite-check's turns remain
        Researcher.Budget small = new Researcher.Budget(8);
        for (int i = 0; i < 7; i++) small.take();
        RunPictures.PictureForTool t2 = pictures.pictureFor("Antikythera", small, p -> "[1]");
        assertEquals("No turn left for a picture of a map of the wreck site: 7 of 8 turns used, the rest is the cite-check's. Go on with the section.", t2.execute(args("The dive", "a map of the wreck site", "map")));
        assertEquals(7, small.used());
        // no time left: the write-up's own estimate is past the deadline
        Researcher.Budget late = new Researcher.Budget(40, 1); late.wrapUp(10 * 60_000);
        RunPictures.PictureForTool t3 = pictures.pictureFor("Antikythera", late, p -> "[1]");
        String noTime = t3.execute(args("The dive", "a map of the wreck site", "map"));
        assertTrue(noTime.startsWith("No time left for a picture of a map of the wreck site: ") && noTime.endsWith(" min left, the write-up's own estimate is 10.0 min. Go on with the section."), noTime);
        assertEquals(0, late.used());
        assertTrue(events.stream().anyMatch(o -> o.path("outcome").asText().equals("refused: no time")) && events.stream().anyMatch(o -> o.path("outcome").asText().equals("refused: no turn")), events.toString());
    }

    @Test void theReportShowsFivePicturesAndTheWritersOwnFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Map<String, byte[]> bytes = new HashMap<>();
        Images.FETCHER = url -> new Images.Fetched(bytes.get(url), "image/png");
        List<String> lines = new ArrayList<>(); List<String> urls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String url = "https://x.example/p" + i + ".png"; bytes.put(url, Vision.square(40 * i, 0, 0));
            Images.Kept k = Images.keep(store, new Images.Found(url, "https://x.example/" + i, "Picture " + i, "Brave", 0, 0, ""), "", "");
            urls.add(k.url()); lines.add("![Picture " + i + "](" + k.url() + ")");
        }
        String text = "## A\n\nA paragraph [1].\n\n" + lines.get(0) + "\n\n" + lines.get(1) + "\n\n" + lines.get(2) + "\n\n" + lines.get(3) + "\n\n" + lines.get(4) + "\n\n## B\n\nAnother [2].\n\n" + lines.get(5) + "\n\n![a picture of the web](https://elsewhere.example/x.jpg)";
        List<String> log = new ArrayList<>();
        String out = RunPictures.shown(store, text, log::add, Set.of(urls.get(5)));
        assertTrue(out.contains(lines.get(5)), "the writer's own, placed last, is shown: " + out);
        assertFalse(out.contains(lines.get(4)), "the fifth of the others gives way: " + out);
        for (int i = 0; i < 4; i++) assertTrue(out.contains(lines.get(i)), out);
        assertFalse(out.contains("elsewhere.example"), "a picture of the web the writer wrote on its own is taken off");
        assertEquals(List.of("pictures: 2 picture line(s) the writer placed taken off the report (more than 5, or not a picture this library keeps); 5 shown"), log);
        // with no ask of the writer's, the first five in the report's order
        String plain = RunPictures.shown(store, text, s -> { });
        assertTrue(plain.contains(lines.get(4)) && !plain.contains(lines.get(5)), plain);
    }
}
