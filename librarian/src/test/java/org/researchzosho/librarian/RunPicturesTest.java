package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures inside a research run and inside read-in documents (0.7.0, the second increment), with the drive, the engines, the fetcher
 * and the seeing model stood in: a worker finds, judges, keeps and cites a picture and the report shows it; the chain after the workers
 * looks for the entities the findings name; without a seeing model the tool says how one is added and the run goes on; an EPUB's picture
 * is kept, placed in its text and found on the shelves first; the Settings page says which entries see; the pictures page shows tiles;
 * the PDF and Word exports embed a kept picture and caption one they cannot.
 */
class RunPicturesTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String FOX = "https://a-z-animals.com/media/2021/05/Wild-Dog-Breeds_-Red-Fox-768x401.jpg", FOX_PAGE = "https://a-z-animals.com/animals/red-fox/pictures/";
    static final String GETTY = "https://media.gettyimages.com/id/123672119/photo/red-fox.jpg?s=612x612&w=0&k=20&c=YKji6QGmvVlOtGlWwYf4QJe7RJsMfVRe89hozyPfl7o=";

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");   // two addresses are two pictures, unless named Wild-Dog
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; }

    /** A seeing drive that says yes when the question carries one of {@code yesWords}, no otherwise; its questions kept. */
    static Researcher.Drive eyesFor(List<String> questions, String... yesWords) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("one picture, one question: not a chat turn"); }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String q = messages.get(0).path("content").get(0).path("text").asText();
                questions.add(q);
                for (String w : yesWords) if (q.contains(w)) return "Yes — a red fox stands in snow.";
                return "No — a plain square.";
            }
            @Override public int contextWindow() { return 16384; }
        };
    }

    static String sha16(byte[] b) { return Images.sha256(b).substring(0, 16); }

    @Test void aWorkerFindsJudgesKeepsAndCitesAPictureAndTheReportShowsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "a red fox");
        String line = "![Wild Dog Breeds: Red Fox](/images/" + sha16(Vision.square(255, 0, 0)) + ".png)";
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done"))) return call("done", J.createObjectNode().put("summary", "closed on the deadline"));
                int turn = assistantTurns(history) + 1;
                if (n.contains("write_section")) {
                    return switch (turn) {
                        case 1 -> call("write_section", J.createObjectNode().put("heading", "Answer").put("text", "A red fox in snow is rust-red with a white chest, as the kept picture shows [1].\n\n" + line + "\n\nIts tail is bushy and white-tipped [1]."));
                        case 2 -> call("write_section", J.createObjectNode().put("heading", "Sources").put("text", FOX));
                        default -> call("done", J.createObjectNode().put("summary", "one picture, one source"));
                    };
                }
                return switch (turn) {
                    case 1 -> call(RunPictures.SEARCH, J.createObjectNode().put("query", "red fox in snow"));
                    case 2 -> { ObjectNode a = J.createObjectNode().put("criteria", "a red fox"); a.putArray("images").add(FOX).add(GETTY); yield call(RunPictures.JUDGE, a); }
                    case 3 -> call("note", J.createObjectNode().put("claim", "A red fox stands in snow, as the kept picture shows").put("source", FOX).put("quote", "a red fox stands in snow"));
                    default -> call("done", J.createObjectNode().put("summary", "one picture judged and kept"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-pictures");
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        researcher.trace(trace);
        var r = researcher.run(new Researcher.Ask("What does a red fox look like in snow?", "depth", 60, List.of("what does a red fox look like in snow?")), "");
        // the tools were on the table and the register said what they do
        assertTrue(drive.offered.stream().anyMatch(o -> o.contains("image_search") && o.contains("image_judge")), drive.offered.toString());
        assertTrue(drive.histories.get(0).get(0).path("content").asText().contains("PICTURES: image_search finds pictures on the web"), "the worker's register");
        // what the worker saw: the candidates with their pages, then the verdicts and the keeping
        String search = toolAnswer(drive, RunPictures.SEARCH), judge = toolAnswer(drive, RunPictures.JUDGE);
        assertTrue(search.startsWith("6 pictures found for \"red fox in snow\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like)."), "the SVG among the fixtures is skipped before anything is fetched: " + search);
        assertTrue(search.contains("1. \"Wild Dog Breeds: Red Fox\" from a-z-animals.com, 768×401, found through Brave.\n   page: " + FOX_PAGE + "\n   picture: " + FOX), search);
        assertTrue(judge.startsWith("Judged against \"a red fox\" by default (qwen3.8-27b): 2 of 2 show it."), judge);
        assertTrue(judge.contains("1. \"Wild Dog Breeds: Red Fox\": yes — a red fox stands in snow KEPT as "), judge);
        assertTrue(judge.contains("; note it with source: " + FOX + " (its page: " + FOX_PAGE + ")."), judge);
        assertTrue(judge.endsWith("Note each kept picture now: note(claim: what it shows, source: the picture address printed above, quote: the verdict's sentence)."), judge);
        assertEquals(2, questions.size()); assertTrue(questions.get(0).startsWith("Does this picture show a red fox?"), questions.get(0));
        // the kept entries: two pictures (the same bytes, two pages), each with its verdict
        List<Images.Shelved> on = Images.onShelves(store);
        assertEquals(2, on.size());
        Images.Shelved fox = on.stream().filter(s -> s.page().equals(FOX_PAGE)).findFirst().orElseThrow();
        assertEquals(Images.Verdict.YES, fox.verdict()); assertEquals("a red fox", fox.criteria()); assertEquals("a red fox stands in snow", fox.why()); assertEquals("Brave", fox.source());
        assertEquals(2, researcher.pictures.kept.size()); assertEquals(2, researcher.pictures.judged()); assertEquals(1, researcher.pictures.searches());
        // the writer was handed the picture as a numbered source with the one line that shows it
        String writerPrompt = drive.histories.stream().filter(h -> h.size() > 1 && h.get(1).path("content").asText().contains("SOURCES (cite by number")).map(h -> h.get(1).path("content").asText()).findFirst().orElseThrow();
        assertTrue(writerPrompt.contains("[1] Wild Dog Breeds: Red Fox — " + FOX + "  — a picture, judged \"a red fox\": yes; to show it, put this line on a line of its own under the paragraph that cites it: " + line), writerPrompt);
        assertTrue(writerPrompt.contains("A source marked as a picture may be shown: copy its ![…](/images/…) line, as given, onto a line of its own under the paragraph that cites it, at most 5 pictures in the report."), writerPrompt);
        // the report: the picture under the paragraph, the reference saying it is a picture, the evidence row
        assertTrue(r.done(), r.summary());
        assertTrue(r.answer().contains("as the kept picture shows [1].\n\n" + line + "\n\nIts tail"), r.answer());
        assertTrue(r.answer().contains("[1] Wild Dog Breeds: Red Fox — " + FOX + "  (a picture, kept as " + fox.id() + ", from the page " + FOX_PAGE + "; judged \"a red fox\": yes — a red fox stands in snow)"), r.answer());
        assertTrue(r.answer().contains("| A red fox stands in snow, as the kept picture shows | [1] | a red fox stands in snow |"), r.answer());
        // the second yes, not noted by the worker, is listed by the library so the references carry it
        assertTrue(r.evidence().contains("SUB-QUESTION: pictures kept this run"), r.evidence());
        assertTrue(r.evidence().contains("- A picture judged to show a red fox: \"red fox - red fox side stock pictures, royalty-free photos & images\", from gettyimages.com — source: " + GETTY + " — quote: \"a red fox stands in snow\""), r.evidence());
        assertTrue(r.answer().contains("[2] red fox - red fox side stock pictures"), r.answer());
        assertTrue(log.stream().anyMatch(l -> l.equals("pictures: 2 kept as evidence, 2 judged by qwen3.8-27b over 1 search(es)")), log.toString());
        // the page shows it at reading size, on its line of its own (ReportPicturesTest), as a link to the whole file
        assertTrue(Pages.md(r.answer()).contains("<img class=\"figure\" src=\"/images/" + sha16(Vision.square(255, 0, 0)) + ".png\" alt=\"Wild Dog Breeds: Red Fox\""), "the report's page");
        // the trace and the research log carry the pictures
        String traced = Files.readString(RunTrace.fileFor(store, "J-pictures"));
        assertTrue(traced.contains("\"capture\":\"picture_search\"") && traced.contains("\"words\":\"red fox in snow\""), traced);
        assertTrue(traced.contains("\"capture\":\"picture\"") && traced.contains("\"verdict\":\"yes\"") && traced.contains("\"kept\":\"" + fox.id() + "\""), traced);
        assertTrue(researcher.searches().stream().anyMatch(s -> s.where().equals("images") && s.query().equals("red fox in snow") && s.found() == 6), researcher.searches().toString());
    }

    /** The index of the first log line that carries {@code part}, -1 for none. */
    static int indexOf(List<String> log, String part) { for (int i = 0; i < log.size(); i++) if (log.get(i).contains(part)) return i; return -1; }

    /** The first tool answer of {@code tool} in the worker's histories. */
    static String toolAnswer(ResearcherTest.ScriptedDrive drive, String tool) {
        for (ArrayNode h : drive.histories) {
            for (int i = 1; i < h.size(); i++) {
                JsonNode m = h.get(i);
                if (!"tool".equals(m.path("role").asText())) continue;
                JsonNode prev = h.get(i - 1);
                for (JsonNode c : prev.path("tool_calls")) if (c.path("function").path("name").asText().equals(tool) && c.path("id").asText().equals(m.path("tool_call_id").asText())) return m.path("content").asText();
            }
        }
        return "";
    }

    @Test void theChainLooksForPicturesOfTheEntitiesTheFindingsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "Valerios Stais");
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive(); drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-chain");
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        researcher.trace(trace);
        var r = researcher.run(new Researcher.Ask("Who was Valerios Stais, and what did he find in the Antikythera wreck?", "depth", 60, List.of("who was Valerios Stais?")), "");
        assertEquals(List.of("Valerios Stais", "Antikythera"), RunPictures.entities(r.evidence().isEmpty() ? "" : "Who was Valerios Stais, and what did he find in the Antikythera wreck?", r.evidence()).subList(0, 2));
        // no worker called a picture tool: the run has its pictures from the step after them, whose turns were kept back before the workers drew
        assertTrue(drive.histories.stream().flatMap(h -> h.findValues("tool_calls").stream()).flatMap(tc -> tc.findValues("name").stream()).map(JsonNode::asText).noneMatch(n -> n.startsWith("image_")), "the scripted workers never touch the picture tools");
        assertTrue(log.contains("pictures: 2 turns kept back from the workers for pictures of Valerios Stais, Antikythera, so the step after them has its turns"), log.toString());
        assertTrue(log.contains("pictures: \"Valerios Stais\" searched because the question names Valerios Stais: 6 found through Brave and SearXNG, 6 judged against \"Valerios Stais\", 2 kept"), log.toString());
        // the second entity's six candidates are the same seven fixtures: the two pictures kept for Stais are the same bytes and are not judged again; the criteria carry the ask's subject
        assertTrue(log.contains("pictures: \"Antikythera Valerios Stais\" searched because the question names Antikythera beside Valerios Stais: 6 found through Brave and SearXNG, 4 judged against \"Antikythera, in connection with Valerios Stais\", 2 kept"), log.toString());
        // the order: the turns kept back, then the question's subjects searched, then the first worker's line — the pictures come before any worker drew a turn
        int keptBack = indexOf(log, "pictures: 2 turns kept back"), searchedAt = indexOf(log, "searched because the question names Valerios Stais"), firstWorker = indexOf(log, "worker \"");
        assertTrue(keptBack >= 0 && keptBack < searchedAt && searchedAt < firstWorker, "kept back → searched → workers: " + log);
        assertEquals(10, questions.size(), "six candidates for the first entity, four for the second (two were pictures kept this run)");
        assertEquals("Does this picture show Valerios Stais? The page it stands on is titled \"Wild Dog Breeds: Red Fox\". " + Images.SHOWS_RULE + " Answer: yes, no or unsure, then one sentence why.", questions.get(0), "the run's question: the page's title as evidence, and what a yes means");
        assertTrue(questions.stream().anyMatch(q -> q.startsWith("Does this picture show Antikythera, in connection with Valerios Stais? The page it stands on is titled")), questions.toString());
        assertTrue(log.contains("pictures: 4 kept as evidence, 10 judged by qwen3.8-27b over 2 search(es)"), "the chain's searches are counted: " + log);
        assertTrue(r.evidence().contains("SUB-QUESTION: pictures of Valerios Stais, Antikythera\nSUMMARY: The library looked for pictures of what the question names"), r.evidence());
        assertTrue(r.evidence().contains("- A picture judged to show Valerios Stais: \"Wild Dog Breeds: Red Fox\", from a-z-animals.com — source: " + FOX + " — quote: \"a red fox stands in snow\""), r.evidence());
        assertTrue(r.answer().contains("(a picture, kept as ") && r.answer().contains("; judged \"Valerios Stais\": yes — a red fox stands in snow)"), r.answer());
        assertEquals(4, Images.onShelves(store).size());
        String traced = Files.readString(RunTrace.fileFor(store, "J-chain"));
        assertTrue(traced.contains("\"capture\":\"picture_chain\"") && traced.contains("\"entity\":\"Valerios Stais\"") && traced.contains("\"why\":\"the question names Valerios Stais\"") && traced.contains("\"when\":\"before the workers\""), traced);
        // no entity, no chain: a question about a thing in lower case; the section's own picture is still asked for, since the sub-question names a mechanism (0.7.0)
        questions.clear(); log.clear();
        ResearcherTest.ScriptedDrive plain = new ResearcherTest.ScriptedDrive(); plain.criticWantsMore = false;
        new Researcher(plain, plain, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask("how were the gears cut?", "depth", 60, List.of("how were the gears cut?")), "");
        assertTrue(log.stream().noneMatch(l -> l.contains("searched because the question names")), log.toString());
        assertTrue(log.contains("pictures: 1 turn kept back from the workers for the sections on 1 sub-question, so the step after them has its turn"), log.toString());
        assertTrue(log.contains("pictures: a picture for \"how were the gears cut?\" (a diagram of the gears): 6 found for \"a diagram of the gears\" through Brave and SearXNG, 6 judged against \"a diagram of the gears\", none kept"), log.toString());
        assertEquals(6, questions.size(), "the six candidates judged for the section's diagram, none a yes");
        // the setting leaves the tools, the chain and the sections' asks out
        questions.clear();
        Config.set(RunPictures.SETTING, "off");
        ResearcherTest.ScriptedDrive off = new ResearcherTest.ScriptedDrive(); off.criticWantsMore = false;
        new Researcher(off, off, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask("Who was Valerios Stais?", "depth", 60, List.of("who was Valerios Stais?")), "");
        assertTrue(off.offered.stream().noneMatch(o -> o.contains("image_search")), off.offered.toString()); assertEquals(0, questions.size());
    }

    @Test void theHeldTurnsSurviveTheWorkersAndTheStepAfterThemDrawsOnThem() {
        // the live run of 2026-11-08: eight workers spent the share down to the write-up's reserve and the picture step found no turn
        Researcher.Budget b = new Researcher.Budget(16, 0);
        assertEquals(8, b.reserve());
        b.hold(1);
        assertEquals(9, b.kept());
        int n = 0; while (b.takeWorker()) n++;
        assertEquals(7, n, "the workers draw everything but the write-up's reserve and the held turn");
        assertTrue(b.takeHeld(), "the held turn is there for the step"); assertEquals(0, b.held());
        assertFalse(b.takeHeld(), "and only that one: the write-up's reserve stands");
        assertTrue(b.takeSynthesis(), "the write-up still has its turns");
        b.hold(0);
        assertEquals(0, new Researcher.Budget(0, 0).kept(), "no ceiling, nothing kept");
    }

    @Test void theHeldTurnsComeWithHeldTimeAndTheStepIsBoundByTheWriteUpsMarginAlone() {
        // J-0003 (2026-11-08): the turns were there (18 of 40 used) and the step was refused by the workers' time check, whose wrap-up ran to 22 of the 25 minutes
        Researcher.Budget b = new Researcher.Budget(40, 1);   // a minute's ceiling, from now
        b.wrapUp(30_000); b.hold(1); b.stepTime(40_000);
        assertEquals(40_000, b.heldMs());
        assertTrue(b.workersTimeUp(), "the workers stop when the step's held time and the write-up no longer fit: 30 s + 40 s of a 60 s ceiling");
        assertFalse(b.writeUpTimeUp(), "the write-up itself still fits");
        assertTrue(b.minutesLeftForWorkers() < 0, "and the minutes left for the workers say so");
        assertTrue(b.takeHeld(), "the step draws within its own reserve although the workers' time is up");
        b.hold(1); b.wrapUp(61_000);
        assertFalse(b.takeHeld(), "only the write-up's margin binds the step: when the write-up would not fit, the step does not start");
        assertTrue(b.takeSynthesis(), "the write-up's turns are untouched");
    }

    @Test void theWrapUpIsEstimatedFromWhatTheWriteUpsCallsCost() {
        // J-0003's shape: worker calls at 55 s, classify calls at 9 s; the write-up then took 4 minutes
        ResearchSettings.Throttle t = new ResearchSettings.Throttle(null);
        for (int i = 0; i < 8; i++) t.observe(55_000, 6_000);
        assertEquals(0, t.shortCallMs(), "no short call observed yet");
        assertEquals(11_000, Researcher.shortCallMs(t), "then a fifth of a long turn stands in");
        for (int i = 0; i < 17; i++) t.observeShort(9_000);
        assertEquals(9_000, t.shortCallMs()); assertEquals(55_000, t.longTurnMs());
        long measured = 240_000, old = (Researcher.SYNTH_TURNS + 1 + Researcher.CHECK_TURNS) * 55_000L, now = Researcher.wrapUpEstimate(t.longTurnMs(), Researcher.shortCallMs(t), 0);
        assertEquals(8 * 55_000L + 7 * 9_000L, now, "the sections at a long turn, the closing call and the six check turns at a short call");
        assertTrue(now >= measured, "never under what the write-up took: " + now);
        assertTrue(now <= old * 2 / 3, "from 3.4 times the measured four minutes (" + old / 60_000.0 + " min) to about 2.1 (" + now / 60_000.0 + " min)");
        assertEquals((8 + 2) * 55_000L + 7 * 9_000L, Researcher.wrapUpEstimate(t.longTurnMs(), Researcher.shortCallMs(t), 2), "the workers' closing turns stay at a long turn");
    }

    @Test void whenTheWorkersExhaustTheTurnsTheStepAfterThemStillKeepsAPicture(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "Valerios Stais");
        // a worker that spends every turn it can get: an empty done (bounced), a fetch with no note (bounced again), then searches until the turns end
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done")) || n.equals(List.of("note", "done"))) return call("done", J.createObjectNode().put("summary", "cut off on the deadline"));
                if (n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                int turn = assistantTurns(history) + 1;
                return switch (turn) {
                    case 1 -> call("done", J.createObjectNode().put("summary", ""));
                    case 2 -> call("web_fetch", J.createObjectNode().put("url", "https://example.org/stais"));
                    case 3 -> call("done", J.createObjectNode().put("summary", "read one page"));
                    case 4 -> call("note", J.createObjectNode().put("claim", "Valerios Stais directed the National Archaeological Museum, where the National Archaeological Museum kept the fragments").put("source", "https://example.org/stais").put("quote", "National Archaeological Museum"));
                    default -> call("web_search", J.createObjectNode().put("query", "Valerios Stais " + turn));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        var r = researcher.run(new Researcher.Ask("Who was Valerios Stais?", "depth", 16, List.of("who was Valerios Stais?")), "");
        assertTrue(log.contains("pictures: 1 turn kept back from the workers for pictures of Valerios Stais, so the step after them has its turn"), log.toString());
        // 16 turns: 8 are the write-up's reserve, 1 is held for the step, so the worker may draw 7; its two bounces carry it to the budget's edge, and the
        // eighth turn is the one grace turn a spent budget gives for the summary — the workers' share spent to the last turn, as on the live run
        String worker = log.stream().filter(l -> l.startsWith("worker \"who was Valerios Stais?\"")).findFirst().orElse("");
        assertTrue(worker.startsWith("worker \"who was Valerios Stais?\": 8 turn(s), calls {done=3, web_fetch=1, note=1, web_search=3}"), "the worker spent every turn the budget allowed and its grace turn: " + worker);
        assertTrue(r.turnsUsed() >= 16 - 8, "the workers reached the write-up's reserve: " + r.turnsUsed() + " turns, " + log);
        // the question's own pictures came before the worker drew, on the held turn; the full name the findings raised afterwards found no turn, and the log says the numbers
        assertTrue(log.contains("pictures: \"Valerios Stais\" searched because the question names Valerios Stais: 6 found through Brave and SearXNG, 6 judged against \"Valerios Stais\", 2 kept"), log.toString());
        assertTrue(indexOf(log, "searched because the question names") < indexOf(log, "worker \""), log.toString());
        assertTrue(log.contains("pictures: no turn left for pictures of National Archaeological Museum: 9 of 16 turns used, 8 kept for the write-up"), log.toString());
        assertTrue(r.answer().contains("; judged \"Valerios Stais\": yes — a red fox stands in snow)"), r.answer());
        assertEquals(2, Images.onShelves(store).size());
    }

    @Test void anEventSaidByAYearAndAnEventWordIsASubjectAndADemonymIsNotAName() {
        assertEquals("eruption 79 AD", RunPictures.eventPhrase("What happened in the eruption of 79 AD?"));
        assertEquals("great fire 1666", RunPictures.eventPhrase("Tell me about the great fire of 1666."));
        assertEquals("", RunPictures.eventPhrase("How were the gears cut?")); assertEquals("", RunPictures.eventPhrase("What was the battle about?"), "an event word with no year is not an event");
        assertEquals(List.of("eruption 79 AD"), RunPictures.entities("What happened in the eruption of 79 AD?", ""));
        assertEquals(List.of("Valerios Stais"), RunPictures.entities("Who was Valerios Stais?", "SUB-QUESTION: who\nEVIDENCE:\n- The Greek archaeologist Valerios Stais saw the gear, as the Greek press said — source: https://example.org/x\n"), "Greek is a people's adjective, not a name");
        assertEquals("", RunPictures.eventPhrase(null));
    }

    @Test void theFindingsNamesStepSaysTheNumbersWhenTheWriteUpsTimeHasCome(@TempDir Path tmp) throws Exception {
        // J-0005 (2026-11-08): 17 minutes left on the clock, the write-up's estimate 17.7 — a reader had to compute that from the trace
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> log = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "Valerios Stais");
        RunPictures pictures = new RunPictures(store, log::add, (t, o) -> { });
        Researcher.Budget b = new Researcher.Budget(40, 25);
        Researcher.Ask ask = new Researcher.Ask("Who was Valerios Stais?", "depth", 40, List.of("who was he?"));
        b.hold(1);
        String before = pictures.first(ask, b, List.of());   // as the run does, before any worker: the question's own subject
        assertTrue(before.startsWith("SUB-QUESTION: pictures of Valerios Stais\nSUMMARY: The library looked for pictures of what the question names"), before);
        // the workers ran long: the write-up's estimate outgrew the clock, and the findings raised a full name
        log.clear(); b.wrapUp(26 * 60_000L);
        String evidence = before + "\n\n- Valerios Stais directed the National Archaeological Museum, and the National Archaeological Museum kept the fragments — source: https://example.org/a\n";
        assertEquals("", pictures.later(ask, evidence, b, List.of()));
        assertEquals(List.of("pictures: no time left for pictures of National Archaeological Museum: 25.0 min left, the write-up's estimate is 26.0 min"), log, "the question's own subject was done before the workers; the pictures' titles in the evidence are not names");
        // with time, the full name the findings raise is searched in the question's connection
        log.clear(); b.wrapUp(60_000L);
        String piece = pictures.later(ask, evidence, b, List.of());
        // six candidates, the same fixtures: the two kept for Stais before the workers are the same bytes and are not judged again
        assertTrue(log.contains("pictures: \"National Archaeological Museum Valerios Stais\" searched because the findings name National Archaeological Museum beside Valerios Stais: 6 found through Brave and SearXNG, 4 judged against \"National Archaeological Museum, in connection with Valerios Stais\", 2 kept"), log.toString());
        assertTrue(piece.startsWith("SUB-QUESTION: pictures of National Archaeological Museum\nSUMMARY: The library looked for pictures of the people, organisations and things the findings name"), piece);
    }

    @Test void aPictureFromTheShelvesIsCitedAsAFreshOneIs(@TempDir Path tmp) throws Exception {
        // J-0006 (2026-11-08): the pictures the shelves answered with were referenced as "[1] /images/<file>" — no title, no web address, no page, no verdict
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        Images.Found fox = Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0);
        Images.Kept earlier = Images.keep(store, fox, "Valerios Stais at his desk, as a reader kept it on an earlier run.", "");   // on the shelves before this run, unjudged
        String served = earlier.url();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "Valerios Stais");
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done")) || n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                return switch (assistantTurns(history) + 1) {
                    case 1 -> call("web_search", J.createObjectNode().put("query", "Valerios Stais"));
                    case 2 -> call("note", J.createObjectNode().put("claim", "Stais sat at his desk, as the kept picture shows").put("source", served).put("quote", "at his desk"));   // the served address, as a worker may write it
                    default -> call("done", J.createObjectNode().put("summary", "one picture"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        var r = researcher.run(new Researcher.Ask("Who was Valerios Stais?", "depth", 60, List.of("who was Valerios Stais?")), "");
        // the shelves answered first with the earlier picture; the web's copy of it (the same page, the same title) was told apart from it in the merge; the next fresh one was kept
        assertTrue(log.contains("pictures: \"Valerios Stais\" searched because the question names Valerios Stais: 6 found through the shelves and Brave and SearXNG, 6 judged against \"Valerios Stais\", 2 kept"), log.toString());
        assertTrue(questions.get(0).contains("titled \"Wild Dog Breeds: Red Fox\"") && questions.stream().filter(q -> q.contains("titled \"Wild Dog Breeds: Red Fox\"")).count() == 1, "the shelf picture judged once, its web copy not at all: " + questions);
        List<Images.Shelved> on = Images.onShelves(store);
        assertEquals(2, on.size(), "the earlier entry stands, one fresh entry beside it: " + on);
        Images.Shelved fresh = on.stream().filter(s -> !s.id().equals(earlier.id())).findFirst().orElseThrow();
        assertEquals("Alaska Red Fox (Vulpes vulpes)", fresh.title());
        // both reference lines have the one shape: title, the web address, kept as, the page, this run's verdict
        String answer = r.answer();
        assertTrue(answer.contains("] Wild Dog Breeds: Red Fox — " + FOX + "  (a picture, kept as " + earlier.id() + ", from the page " + FOX_PAGE + "; judged \"Valerios Stais\": yes — a red fox stands in snow)"), answer);
        assertTrue(answer.contains("] Alaska Red Fox (Vulpes vulpes) — https://upload.wikimedia.org/wikipedia/commons/8/8e/Alaska_Red_Fox_%28Vulpes_vulpes%29.jpg"), answer);
        assertTrue(answer.contains("(a picture, kept as " + fresh.id() + ", from the page https://commons.wikimedia.org/wiki/File:Alaska_Red_Fox_(Vulpes_vulpes).jpg; judged \"Valerios Stais\": yes — a red fox stands in snow)"), answer);
        assertFalse(answer.matches("(?s).*\\[\\d+\\] /images/.*"), "no reference is a served address: " + answer);
        assertFalse(answer.contains("— source: /images/"), answer);
        // the worker's note by the served address resolved to the same entry: one reference for the picture, and the evidence row cites it by number
        assertEquals(1, answer.split("\\] Wild Dog Breeds: Red Fox — ", -1).length - 1, "one reference for the shelf picture, not one for the served address and one for the web address: " + answer);
        assertTrue(answer.contains("| Stais sat at his desk, as the kept picture shows | [1] | at his desk |"), answer);
        // the writer was told the picture's address on the web and its line, with this run's verdict
        String writerPrompt = drive.histories.stream().filter(h -> h.size() > 1 && h.get(1).path("content").asText().contains("SOURCES (cite by number")).map(h -> h.get(1).path("content").asText()).findFirst().orElseThrow();
        assertTrue(writerPrompt.contains("[1] Wild Dog Breeds: Red Fox — " + FOX + "  — a picture, judged \"Valerios Stais\": yes; to show it, put this line on a line of its own under the paragraph that cites it: ![Wild Dog Breeds: Red Fox](" + served + ")"), writerPrompt);
        // the evidence names the entry's web address, not the served one, for the shelf picture the step kept
        assertTrue(r.evidence().contains("- A picture judged to show Valerios Stais: \"Wild Dog Breeds: Red Fox\", from a-z-animals.com — source: " + FOX + " — quote: \"a red fox stands in snow\""), r.evidence());
    }

    @Test void aPassingPlaceInTheFindingsIsNotChasedButAFullNameIs() {
        // J-0004 (2026-11-08): "Athens", the museum's city, was the third entity by count, and its search brought a coin from Lykosura
        String evidence = "SUB-QUESTION: who was he?\nSUMMARY: a director in Athens\nEVIDENCE:\n- He worked in Athens, at the National Archaeological Museum in Athens, as its director — source: https://example.org/a\n"
                + "- The fragments were taken to Athens in 1901; the museum in Athens kept them, and Athens saw the gear wheel found there — source: https://example.org/b\n"
                + "- The National Archaeological Museum published the report — source: https://example.org/c\n";
        assertEquals(List.of("Valerios Stais", "National Archaeological Museum"), RunPictures.entities("Who was Valerios Stais?", evidence), "the question's own name first; a full name from the findings; the passing place not at all");
        assertEquals(List.of("Antikythera"), RunPictures.entities("What was found at Antikythera?", "- It went to Athens and stayed in Athens — source: https://example.org/a\n"), "a place the question names is chased; one it does not is not");
    }

    @Test void theSamePictureUnderTwoAddressesIsJudgedAndKeptOnce(@TempDir Path tmp) throws Exception {
        assertEquals(Images.imageKey("https://x.example/pics/a.jpg?utm_source=1"), Images.imageKey("https://x.example/pics/a.jpg?utm_source=2"), "a query string after a picture file is sizing or tracking");
        assertNotEquals(Images.imageKey("https://x.example/img.php?id=1"), Images.imageKey("https://x.example/img.php?id=2"), "a path with no file extension keeps its query");
        Images.Found a = new Images.Found("https://x.example/pics/a.jpg?utm=1", "https://x.example/p1", "A", "Brave", 0, 0, ""), b = new Images.Found("https://x.example/pics/a.jpg?utm=2", "https://y.example/p2", "B", "SearXNG", 0, 0, "");
        assertEquals(List.of(a), Images.merge(List.of(List.of(a), List.of(b)), 10), "the engines' answers are told apart a query string apart");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "a red fox");
        String twin = "https://twin.example/pics/Wild-Dog-twin.jpg";   // the same bytes as the first Brave fixture, under another address
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done")) || n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                return switch (assistantTurns(history) + 1) {
                    case 1 -> call(RunPictures.SEARCH, J.createObjectNode().put("query", "red fox in snow"));
                    case 2 -> { ObjectNode a2 = J.createObjectNode().put("criteria", "a red fox"); a2.putArray("images").add(FOX).add(FOX + "?utm_source=share").add(twin); yield call(RunPictures.JUDGE, a2); }
                    default -> call("done", J.createObjectNode().put("summary", "one picture"));
                };
            }
        };
        drive.criticWantsMore = false;
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 2, store);
        researcher.run(new Researcher.Ask("What does a red fox look like in snow?", "depth", 60, List.of("what does a red fox look like in snow?")), "");
        String judge = toolAnswer(drive, RunPictures.JUDGE);
        String id = Images.onShelves(store).get(0).id();
        assertTrue(judge.startsWith("Judged against \"a red fox\" by default (qwen3.8-27b): 1 of 1 shows it. 2 candidates were the same picture as one already judged."), judge);
        assertTrue(judge.contains("\n2. " + FOX + "?utm_source=share: the same picture as one judged already in this call, the same address a query string apart."), judge);
        assertTrue(judge.contains("\n3. " + twin + ": the same picture as \"Wild Dog Breeds: Red Fox\", kept as " + id + "."), "the first yes is kept before the next candidate is looked at: " + judge);
        assertEquals(1, questions.size(), "judged once"); assertEquals(1, Images.onShelves(store).size(), "kept once"); assertEquals(1, researcher.pictures.judged());
        // the same bytes kept again through the library's own keep are the existing entry
        Images.Kept again = Images.keep(store, new Images.Found(twin, "https://twin.example/", "Twin", "SearXNG", 0, 0, ""), "", "");
        assertEquals(id, again.id());
    }

    @Test void withNoSeeingModelTheToolSaysHowOneIsAddedAndTheRunGoesOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");   // the default entry, not probed for pictures
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> eyesFor(questions, "anything");
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done")) || n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                return switch (assistantTurns(history) + 1) {
                    case 1 -> call(RunPictures.SEARCH, J.createObjectNode().put("query", "Valerios Stais"));
                    case 2 -> { ObjectNode a = J.createObjectNode().put("criteria", "Valerios Stais"); a.putArray("images").add(FOX); yield call(RunPictures.JUDGE, a); }
                    default -> call("done", J.createObjectNode().put("summary", "no pictures could be judged"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        var r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask("Who was Valerios Stais?", "depth", 60, List.of("who was Valerios Stais?")), "");
        String judge = toolAnswer(drive, RunPictures.JUDGE);
        assertTrue(judge.startsWith("No model that sees pictures is set up. The library has: default (qwen3.8-27b, not probed for pictures)."), judge);
        assertTrue(judge.endsWith(" This run goes on without pictures: note what the pages say instead."), judge);
        assertTrue(log.contains("pictures: the question names Valerios Stais; no model sees pictures, so none were looked for (researchzosho models test <name> probes one)"), log.toString());
        assertEquals(0, questions.size()); assertTrue(Images.onShelves(store).isEmpty()); assertTrue(r.done());
    }

    /** A picture of random colours, large enough not to be furniture. */
    static byte[] noisyPng(long seed) throws Exception {
        BufferedImage img = new BufferedImage(96, 72, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < 72; y++) for (int x = 0; x < 96; x++) img.setRGB(x, y, rnd.nextInt(0xffffff));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A small EPUB as a publisher's tool writes one, carrying {@code png} as its one picture with a caption under it. */
    static byte[] epubWith(byte[] png) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"EPUB/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
        files.put("EPUB/package.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"u\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"u\">urn:uuid:2</dc:identifier><dc:title>An Invented Clock</dc:title><dc:language>en</dc:language><dc:creator>An Invented Hand</dc:creator></metadata>"
                + "<manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"c1\" href=\"text/one.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"i1\" href=\"img/dial.png\" media-type=\"image/png\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>");
        files.put("EPUB/nav.xhtml", "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>nav</title></head><body><nav epub:type=\"toc\"><ol><li><a href=\"text/one.xhtml\">The dial</a></li></ol></nav></body></html>");
        files.put("EPUB/text/one.xhtml", "<?xml version=\"1.0\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>one</title></head><body><section><h1>The Saros dial</h1>"
                + "<p>The dial predicts eclipses over 223 lunar months, about 18 years, and the Saros cycle it follows was known to the Babylonian astronomers long before the mechanism was made. The gear train behind it turns a pointer through a spiral of 223 cells, each cell naming the month an eclipse may fall in and the hour of the day it is expected.</p>"
                + "<figure><img src=\"../img/dial.png\" alt=\"the dial\"/><figcaption>The dial, drawn in 1902.</figcaption></figure>"
                + "<p>The spiral's four turns carry the glyphs Σ and Η for a lunar and a solar eclipse, and the letters under them point to the inscriptions on the back plate that give the hour. The dial is the part of the mechanism whose purpose was read first, because the number 223 is the Saros and nothing else.</p></section></body></html>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("mimetype")); z.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII)); z.closeEntry();
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            z.putNextEntry(new ZipEntry("EPUB/img/dial.png")); z.write(png); z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test void aDocumentsPicturesAreKeptPlacedInItsTextDescribedOnceAndFoundOnTheShelvesFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        List<String> questions = new ArrayList<>();
        Vision.DRIVES = (d, m) -> ImagesTest.eyes("A drawing of a bronze dial with Greek letters around its rim, dated 1902; no people.", questions);
        byte[] png = noisyPng(1);
        Path f = tmp.resolve("clock.epub"); Files.write(f, epubWith(png));
        Path raw = Corpus.addFile(store, f, "", false);
        assertNotNull(raw);
        String text = RawCapture.read(raw)[2];
        String file = sha16(png) + ".png";
        assertTrue(text.contains("# The Saros dial\n"), text);
        assertTrue(text.contains("![the dial](/images/" + file + ")\n\n*The dial, drawn in 1902.*"), "the picture line points at the library's copy, the caption under it: " + text);
        assertTrue(text.contains("The spiral's four turns carry the glyphs"), text);
        assertEquals("1 picture kept from it, 1 described by the model that sees.", Corpus.lastPictures());
        assertEquals(1, questions.size()); assertTrue(questions.get(0).startsWith(Images.DESCRIBE), questions.get(0));
        List<Images.Shelved> on = Images.onShelves(store);
        assertEquals(1, on.size());
        Images.Shelved s = on.get(0);
        assertEquals("the dial", s.title()); assertEquals(Images.DOCUMENT, s.source()); assertEquals("file://" + f, s.page()); assertEquals("/images/" + file, s.url());
        assertTrue(s.description().startsWith("A drawing of a bronze dial with Greek letters"), s.description()); assertTrue(s.description().endsWith("The dial, drawn in 1902."), "the caption joins the description: " + s.description());
        assertEquals(96, s.width()); assertEquals(72, s.height());
        assertEquals("file://" + f + "#EPUB/img/dial.png", RawCapture.read(s.raw())[0], "the picture's own locator: the document's and its path inside it");
        assertEquals("EPUB/img/dial.png", RawCapture.field(s.raw(), "within"));
        assertTrue(Pages.md(text).contains("<img class=\"figure\" src=\"/images/" + file + "\" alt=\"the dial\""), "the document's page shows it at reading size, on a line of its own");
        // the shelves answer a search first, by the description's words
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> List.of()), new Images.Engine("SearXNG", (w, n) -> List.of()));
        Images.Result r = Images.find(store, "bronze dial", 8);
        assertEquals(1, r.found().size()); Images.Found held = r.found().get(0);
        assertEquals(Images.SHELVES, held.source()); assertEquals("/images/" + file, held.image()); assertEquals("the dial", held.title()); assertEquals("file://" + f, held.page());
        assertEquals(List.of(Images.SHELVES), r.answered());
        assertEquals("1 picture found for \"bronze dial\" through the shelves (Brave (nothing found), SearXNG (nothing found)).", r.said("bronze dial"));
        assertTrue(Images.find(store, "bronze saxophone", 8).found().isEmpty(), "every word must be there");
        // a shelf picture is judged from the library's copy and kept without a fetch
        Vision.DRIVES = (d, m) -> ImagesTest.eyes("Yes — a dial with Greek letters.", questions);
        Images.FETCHER = url -> { throw new AssertionError("a kept picture is read from the library, not fetched: " + url); };
        assertEquals(Images.Verdict.YES, Images.judge(store, eyes, "a dial", r.found()).get(0).verdict());
        Images.Kept k = Images.keep(store, held, "", "");
        assertEquals(s.id(), k.id()); assertEquals(s.url(), k.url());
        // added again: the picture is on the shelves already, so it is not described again and no second entry is written
        questions.clear();
        assertNotNull(Corpus.addFile(store, f, "", false));
        assertEquals(0, questions.size(), "described once"); assertEquals(1, Images.onShelves(store).size());
        // descriptions off: the picture is kept with its alt text and caption, and the model is not asked
        Config.set(DocPictures.SETTING, "off");
        byte[] png2 = noisyPng(2);
        Path g = tmp.resolve("clock-2.epub"); Files.write(g, epubWith(png2));
        assertNotNull(Corpus.addFile(store, g, "", false));
        assertEquals(0, questions.size());
        Images.Shelved quiet = Images.onShelves(store).stream().filter(x -> x.page().equals("file://" + g)).findFirst().orElseThrow();
        assertEquals("The dial, drawn in 1902.", quiet.description()); assertEquals("1 picture kept from it.", Corpus.lastPictures());
        // the Word file and the PDF: their pictures listed at the end with their pages
        DocUnits.Doc doc = DocUnits.cut(Files.readAllBytes(f), "clock.epub");
        byte[] docx = DocWriters.write(new DocUnits.Doc("docx", doc.title(), doc.lang(), doc.chapters(), doc.images(), doc.meta()), "docx", "").bytes();
        Path w = tmp.resolve("clock.docx"); Files.write(w, docx);
        Path rawW = Corpus.addFile(store, w, "", false);
        String textW = rawW == null ? "" : RawCapture.read(rawW)[2];
        assertFalse(textW.contains(DocPictures.HEADING), "the library's own Word writer carries no picture, so none is listed: " + textW);
    }

    @Test void thePdfReaderKeepsThePicturesDrawnOnThePages(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set(DocPictures.SETTING, "off");
        // a PDF the library's own writer made, with a kept picture embedded in it, is a PDF with a picture on page one
        byte[] png = noisyPng(3);
        Images.Kept kept = Images.keepBytes(store, png, "image/png", "A noisy square", "", "test://noise", "test://noise-doc", "", Map.of());
        String md = "# Noise\n\nA paragraph of words long enough to be text, about a square of random colours that stands for a picture in a document, so that the reader finds words and a picture both.\n\n![A noisy square](" + kept.url() + ")\n\nMore words after the picture, enough for the text to be read as a document and not as nothing.\n";
        byte[] pdf = ExportPdf.write(ExportDoc.of("Noise", "en", "", md, Map.of(), src -> Export.picture(store, src))).bytes();
        Path f = tmp.resolve("noise.pdf"); Files.write(f, pdf);
        Path raw = Corpus.addFile(store, f, "", false);
        assertNotNull(raw);
        String text = RawCapture.read(raw)[2];
        assertTrue(text.contains(DocPictures.HEADING + "\n\n![A noisy square](/images/" + sha16(png) + ".png)"), "the picture drawn on page one, read back as the same bytes as the entry kept before, under that entry's title: " + text);
        assertTrue(text.contains(")\n*Page 1*"), text);
        assertEquals(1, Images.onShelves(store).size(), "the same bytes as the picture kept before the PDF was made: the existing entry, not a second one");
        assertEquals("1 picture kept from it.", Corpus.lastPictures());
        // a PDF whose picture is new to the shelves gets an entry of its own, with its page
        byte[] png2 = noisyPng(5);
        byte[] pdf2 = ExportPdf.write(ExportDoc.of("Noise two", "en", "", md.replace("A noisy square", "Another square"), Map.of(), src -> new ExportDoc.Image("Another square", src, png2, ""))).bytes();
        Path g = tmp.resolve("noise-2.pdf"); Files.write(g, pdf2);
        assertNotNull(Corpus.addFile(store, g, "", false));
        Images.Shelved s = Images.onShelves(store).stream().filter(x -> x.page().equals("file://" + g)).findFirst().orElseThrow();
        assertEquals("Picture 1 of Noise two", s.title()); assertEquals("page=1&picture=1", RawCapture.field(s.raw(), "within")); assertEquals(Images.DOCUMENT, s.source());
    }

    @Test void theSettingsPageSaysWhichEntriesSeeAndWherePicturesGo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        String before = Pages.modelsSection(store);
        assertTrue(before.contains("<th>pictures</th>") && before.contains("<td>not probed for pictures</td>"), before);
        assertTrue(before.contains("No entry is known to see pictures: researchzosho models test &lt;name&gt; probes one with a small coloured square"), before);
        ImagesTest.seeingDefault();
        String after = Pages.modelsSection(store);
        assertTrue(after.contains("<td>sees images</td>"), after);
        assertTrue(after.contains("Pictures go to default (qwen3.8-27b): the chat's judgements, the research runs' picture tools and the descriptions of the pictures in added documents."), after);
        assertTrue(Settings.KNOWN.stream().anyMatch(s -> s.key().equals(RunPictures.SETTING)) && Settings.KNOWN.stream().anyMatch(s -> s.key().equals(DocPictures.SETTING)), "the two settings are listed");
    }

    @Test void thePicturesPageShowsTheShelvesAsTilesAndFindsByWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Pages.isPage(ImagesPage.PATH));
        String empty = ImagesPage.body(store, Patrons.Patron.PERSON, Map.of());
        assertTrue(empty.contains("No picture is kept yet."), empty);
        Images.Found f = Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0);
        Images.Kept k = Images.keep(store, f, "A red fox in snow.", "", new Images.Judged(f, Images.Verdict.YES, "a red fox stands in snow", "qwen3.8-27b", "a red fox"));
        String page = ImagesPage.body(store, Patrons.Patron.PERSON, Map.of());
        assertTrue(page.contains("<img class=\"thumb\" src=\"" + k.url() + "\" alt=\"Wild Dog Breeds: Red Fox\""), page);
        assertTrue(page.contains("judged &quot;a red fox&quot;: yes — a red fox stands in snow") || page.contains("judged \"a red fox\": yes — a red fox stands in snow"), page);
        assertTrue(page.contains("from <a href=\"" + FOX_PAGE + "\" rel=\"noreferrer\">a-z-animals.com</a>, found through Brave"), page);
        assertTrue(page.contains("1 picture</p>"), page);
        assertTrue(page.contains("href=\"/entry/" + k.id() + "\""), page);
        // a search: the shelves first, then the engines; a writer may keep one
        String found = ImagesPage.body(store, Patrons.Patron.PERSON, Map.of("q", "red fox"));
        assertTrue(found.contains("Found for &quot;red fox&quot;") || found.contains("Found for \"red fox\""), found);
        assertTrue(found.contains("on the shelves"), "the kept picture answers first: " + found);
        assertTrue(found.contains("<input type=\"hidden\" name=\"op\" value=\"keep\">") && found.contains(GETTY.replace("&", "&amp;")), found);
        String note = ImagesPage.post(store, Patrons.Patron.PERSON, Map.of("op", "keep", "image", GETTY, "page", "https://www.gettyimages.com/photos/red-fox-side", "title", "red fox side", "source", "Brave"));
        assertTrue(note.startsWith("Kept \"red fox side\" as ") && note.contains(", shown at /images/"), note);
        assertEquals(2, Images.onShelves(store).size());
        assertTrue(Pages.CSS.contains(".thumb") || ImagesPage.STYLE.contains(".thumb"));
    }

    @Test void theExportsEmbedAKeptPictureAndCaptionOneTheyCannot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        byte[] png = noisyPng(4);
        Images.FETCHER = url -> new Images.Fetched(png, "image/png");
        Images.Found f = Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0);
        Images.Kept k = Images.keep(store, f, "", "");
        String md = "# A report\n\nA red fox in snow is rust-red [1].\n\n![Wild Dog Breeds: Red Fox](" + k.url() + ")\n\n![Fox on the web](https://example.org/fox.jpg)\n\n## References\n\n[1] Wild Dog Breeds: Red Fox — " + FOX + "\n";
        ExportDoc d = ExportDoc.of("A report", "en", "a footer", md, Map.of(), src -> Export.picture(store, src));
        List<ExportDoc.Image> imgs = d.blocks.stream().filter(b -> b instanceof ExportDoc.Image).map(b -> (ExportDoc.Image) b).toList();
        assertEquals(2, imgs.size());
        assertArrayEquals(png, imgs.get(0).bytes()); assertEquals(FOX_PAGE, imgs.get(0).page()); assertEquals("Wild Dog Breeds: Red Fox — " + FOX_PAGE, imgs.get(0).caption());
        assertNull(imgs.get(1).bytes(), "a picture on the web is not fetched for an export"); assertEquals("Fox on the web — https://example.org/fox.jpg", imgs.get(1).caption());
        assertTrue(d.text().contains("Fox on the web — https://example.org/fox.jpg"), "the captions are text the fonts must cover");
        // the PDF: one picture drawn, the other its caption
        byte[] pdf = ExportPdf.write(d).bytes();
        int drawn = 0;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage p : doc.getPages()) for (COSName n : p.getResources().getXObjectNames()) if (p.getResources().getXObject(n) instanceof PDImageXObject) drawn++;
            String words = new PDFTextStripper().getText(doc);
            assertTrue(words.contains("Wild Dog Breeds: Red Fox — " + FOX_PAGE), words);
            assertTrue(words.contains("[Fox on the web — https://example.org/fox.jpg]"), words);
        }
        assertEquals(1, drawn);
        // the Word file: the picture a part under word/media with its relationship and a drawing in the body; the other a caption
        byte[] docx = ExportDocx.write(d);
        Map<String, byte[]> z = DocUnits.unzip(docx);
        assertArrayEquals(png, z.get("word/media/image1.png"));
        String body = new String(z.get("word/document.xml"), StandardCharsets.UTF_8), rels = new String(z.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8), types = new String(z.get("[Content_Types].xml"), StandardCharsets.UTF_8);
        assertTrue(body.contains("<w:drawing><wp:inline") && body.contains("<a:blip r:embed=\"rIdImg1\"/>") && body.contains("descr=\"Wild Dog Breeds: Red Fox\""), body);
        assertTrue(body.contains("Wild Dog Breeds: Red Fox — " + FOX_PAGE) && body.contains("[Fox on the web — https://example.org/fox.jpg]"), body);
        assertTrue(rels.contains("Id=\"rIdImg1\"") && rels.contains("Target=\"media/image1.png\""), rels);
        assertTrue(types.contains("Extension=\"png\""), types);
        assertEquals(1, body.split("<w:drawing>", -1).length - 1, "one drawing: the web picture is a caption");
        // a report's own export, through the library
        Investigation inv = new Investigation("I-0001-a-red-fox", "A red fox", Finding.State.accepted, "model:test", Instant.now().toString(), List.of(), List.of(), md);
        store.write(inv);
        Export.File out = Export.pdf(store, "I-0001-a-red-fox", null);
        try (PDDocument doc = Loader.loadPDF(out.bytes())) { int n = 0; for (PDPage p : doc.getPages()) for (COSName x : p.getResources().getXObjectNames()) if (p.getResources().getXObject(x) instanceof PDImageXObject) n++; assertEquals(1, n); }
    }
}
