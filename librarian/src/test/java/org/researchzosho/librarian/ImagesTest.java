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
import org.researchzosho.drive.DriveClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures: the two engines' answers read from saved responses, merged rank by rank and told apart; a picture kept on the shelves with
 * its page, its engine, when it was found and its verdict; the seeing model's verdicts and descriptions parsed; and every surface over a
 * stand-in — the command line, the protocol, the chat's own turn, and the page that shows a Markdown picture small.
 */
class ImagesTest {
    static final ObjectMapper M = new ObjectMapper();

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; }

    /** The bytes a stand-in fetch gives an address: the red square for the first Brave fixture and anything named Wild-Dog, else a square coloured by the address, so two addresses are two pictures. */
    static byte[] square(String url) {
        if (url.contains("Wild-Dog")) return Vision.square(255, 0, 0);
        int h = url.hashCode();
        return Vision.square(32 + (h & 0x7f), 32 + ((h >> 8) & 0x7f), 32 + ((h >> 16) & 0x7f));
    }

    static String fixture(String name) {
        try (var in = ImagesTest.class.getResourceAsStream("/images/" + name)) { return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new AssertionError(e); }
    }

    /** A seeing drive that answers {@code answer} to every picture. */
    static Researcher.Drive eyes(String answer, List<String> questions) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("one picture, one question: not a chat turn"); }
            @Override public String classify(ArrayNode messages, int maxTokens) { questions.add(messages.get(0).path("content").get(0).path("text").asText()); assertTrue(messages.get(0).path("content").get(1).path("image_url").path("url").asText().startsWith("data:image/"), "the picture rides as a data address"); return answer; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    static ModelEntries.Entry seeingDefault() throws IOException {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b"); Config.set(ModelEntries.SEES, "yes");
        return ModelEntries.seeing();
    }

    static ObjectNode person() { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "").put("runtime", "test"); return a; }

    @Test void theTwoEnginesAnswersAreReadAsTheyCameBack() throws Exception {
        List<Images.Found> brave = Images.parseBrave(fixture("brave-images.json"));
        assertEquals(3, brave.size());
        Images.Found b = brave.get(0);
        assertEquals("https://a-z-animals.com/media/2021/05/Wild-Dog-Breeds_-Red-Fox-768x401.jpg", b.image());
        assertEquals("https://a-z-animals.com/animals/red-fox/pictures/", b.page()); assertEquals("Wild Dog Breeds: Red Fox", b.title()); assertEquals("Brave", b.source());
        assertEquals(768, b.width()); assertEquals(401, b.height()); assertTrue(b.thumbnail().startsWith("https://imgs.search.brave.com/"), b.thumbnail());
        assertEquals("\"Wild Dog Breeds: Red Fox\" from a-z-animals.com, 768×401, found through Brave.", b.said());
        assertTrue(b.show().startsWith("![Wild Dog Breeds: Red Fox](https://imgs.search.brave.com/"), b.show());
        assertFalse(b.found().isEmpty(), "when it was found");
        List<Images.Found> searx = Images.parseSearx(fixture("searx-images.json"));
        assertEquals(4, searx.size());
        Images.Found s = searx.get(0);
        assertTrue(s.image().startsWith("https://upload.wikimedia.org/"), s.image()); assertTrue(s.page().startsWith("https://commons.wikimedia.org/wiki/File:Alaska_Red_Fox"), s.page());
        assertEquals("Alaska Red Fox (Vulpes vulpes)", s.title()); assertEquals("SearXNG (wikicommons.images)", s.source()); assertEquals(3805, s.width()); assertEquals(2750, s.height());
        assertTrue(s.thumbnail().startsWith("https://thumb.wikimedia.org/"), "thumbnail_src is the small copy: " + s.thumbnail());
        assertEquals(2664, searx.get(1).width(), "a resolution written with ×"); assertEquals("SearXNG (bing images)", searx.get(1).source());
        assertEquals(0, searx.get(2).width(), "no resolution given"); assertEquals("", searx.get(2).thumbnail());
        assertArrayEquals(new int[]{0, 0}, Images.resolution("")); assertArrayEquals(new int[]{640, 480}, Images.resolution("640 x 480"));
        // the round trip through JSON keeps every field
        Images.Found back = Images.Found.from(b.json(M));
        assertEquals(b, back);
    }

    @Test void theAnswersAreMergedRankByRankAndEachPictureIsShownOnce() throws Exception {
        Images.Found a1 = new Images.Found("https://a.example/1.jpg", "https://a.example/p1", "One", "Brave", 0, 0, "");
        Images.Found a2 = new Images.Found("https://a.example/2.jpg", "https://a.example/p2", "Two", "Brave", 0, 0, "");
        Images.Found b1 = new Images.Found("HTTPS://A.EXAMPLE/1.jpg/", "https://b.example/q1", "Same picture, other page", "SearXNG", 0, 0, "");
        Images.Found b2 = new Images.Found("https://b.example/2.jpg", "https://a.example/p2", "two", "SearXNG", 0, 0, "");
        Images.Found b3 = new Images.Found("https://b.example/3.jpg", "https://b.example/q3", "Three", "SearXNG", 0, 0, "");
        Images.Found bad = new Images.Found("data:image/png;base64,AAAA", "https://b.example/q4", "Not a web address", "SearXNG", 0, 0, "");
        List<Images.Found> merged = Images.merge(List.of(List.of(a1, a2), List.of(b1, b2, b3, bad)), 10);
        assertEquals(List.of(a1, a2, b3), merged, "b1 is a1's address in other letters, b2 is a2's page and title, bad is no address");
        assertEquals(List.of(a1), Images.merge(List.of(List.of(a1, a2), List.of(b3)), 1), "count caps it");
        // the live shape: the two fixtures merged, Brave's first first
        Images.Result r = Images.find(null, "red fox", 5);
        assertEquals(5, r.found().size()); assertEquals("Brave", r.found().get(0).source()); assertTrue(r.found().get(1).source().startsWith("SearXNG"));
        assertEquals(List.of("Brave", "SearXNG"), r.answered()); assertEquals(List.of(), r.silent());
        assertEquals("5 pictures found for \"red fox\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).", r.said("red fox"), "the SVG among the answers is said as skipped");
        // an engine that is not set up, and one that does not answer, are said and the others carry on
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> null), new Images.Engine("SearXNG", (w, n) -> { throw new IOException("connection refused"); }));
        r = Images.find(null, "red fox", 5);
        assertTrue(r.found().isEmpty()); assertEquals(List.of("Brave (not set up)", "SearXNG (did not answer: connection refused)"), r.silent());
        assertEquals("No picture was found for \"red fox\"; Brave (not set up), SearXNG (did not answer: connection refused).", r.said("red fox"));
        assertTrue(Images.braveUrl("red fox", 8).startsWith("https://api.search.brave.com/res/v1/images/search?count=8&safesearch=strict&q=red+fox"), Images.braveUrl("red fox", 8));
        assertTrue(Images.braveUrl("赤いキツネ", 8).endsWith("&search_lang=jp"), "Brave's word for Japanese");
        assertTrue(Images.searxUrl("red fox").endsWith("/search?format=json&categories=images&q=red+fox&safesearch=1"), Images.searxUrl("red fox"));
    }

    @Test void aKeptPictureIsAFileUnderTheLibraryAndAnEntryWithItsPageEngineTimeAndVerdict(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Found f = Images.parseBrave(fixture("brave-images.json")).get(0);
        List<String> checked = new ArrayList<>();
        Images.CHECK = (e, got) -> { checked.add(e.name() + ":" + got.contentType()); return new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test"); };
        // no model sees: the check cannot run; the picture is kept as chosen
        Images.Kept k = Images.keep(store, f, "", "foxes");
        assertEquals(List.of(), checked);
        String sha16 = Images.sha256(Vision.square(255, 0, 0)).substring(0, 16);
        assertEquals("catalog/images/" + sha16 + ".png", k.file()); assertEquals("/images/" + sha16 + ".png", k.url());
        assertTrue(Files.isRegularFile(store.root().resolve(k.file()))); assertNotNull(Images.kept(store, sha16 + ".png")); assertNull(Images.kept(store, "../" + sha16 + ".png"));
        assertEquals(768, k.found().width(), "the engine's size is kept when it gave one");
        Images.Found unsized = new Images.Found("https://a.example/sq.png", "https://a.example/", "A square", "SearXNG", 0, 0, "");
        assertEquals(16, Images.keep(store, unsized, "", "").found().width(), "else the size is read from the bytes' header");
        String[] parts = RawCapture.read(k.raw());
        assertEquals(f.image(), parts[0]); assertEquals("Wild Dog Breeds: Red Fox", parts[1]);
        assertTrue(parts[2].startsWith("Wild Dog Breeds: Red Fox\n\n![Wild Dog Breeds: Red Fox](/images/" + sha16 + ".png)\n\nFrom https://a-z-animals.com/animals/red-fox/pictures/, found through Brave."), parts[2]);
        assertEquals("image", RawCapture.field(k.raw(), "kind")); assertEquals("catalog/images/" + sha16 + ".png", RawCapture.field(k.raw(), "image"));
        assertEquals("https://a-z-animals.com/animals/red-fox/pictures/", RawCapture.field(k.raw(), "page")); assertEquals("Brave", RawCapture.field(k.raw(), "source"));
        assertEquals(f.found(), RawCapture.field(k.raw(), "found")); assertEquals("768", RawCapture.field(k.raw(), "width")); assertEquals("foxes", RawCapture.collectionOf(k.raw()));
        assertEquals("", RawCapture.field(k.raw(), "verdict"));
        assertEquals(Images.sha256(Vision.square(255, 0, 0)), RawCapture.field(k.raw(), "hash"), "the whole hash in the head, under a name the head keeps");
        assertEquals(Integer.toString(Vision.square(255, 0, 0).length), RawCapture.field(k.raw(), "bytes"));
        // the bytes come back by the served address, the file name, or the entry
        assertArrayEquals(Vision.square(255, 0, 0), Images.bytesOf(store, k.url(), false).bytes()); assertEquals("image/png", Images.bytesOf(store, sha16 + ".png", false).contentType());
        assertArrayEquals(Vision.square(255, 0, 0), Images.bytesOf(store, k.id(), false).bytes());
        assertTrue(assertThrows(IOException.class, () -> Images.bytesOf(store, "nothing-like-this", false)).getMessage().startsWith("No picture is nothing-like-this"));
        assertTrue(assertThrows(IOException.class, () -> Images.bytesOf(store, tmp.resolve("x.png").toString(), false)).getMessage().startsWith("No picture is"), "a path from someone who may not name one");
        // a model sees: the one question is asked before the picture is written, and a yes is not kept
        ModelEntries.Entry eyes = seeingDefault();
        assertEquals("default", eyes.name());
        Images.Found f2 = Images.parseBrave(fixture("brave-images.json")).get(1);
        Images.Judged j = new Images.Judged(f2, Images.Verdict.YES, "a fox stands in snow", "qwen3.8-27b", "a fox in snow");
        Images.Kept k2 = Images.keep(store, f2, "A red fox, photographed in snow.", "foxes", j);
        assertEquals(List.of("default:image/png"), checked);
        assertNotEquals(k.file(), k2.file(), "other bytes, another file"); assertNotEquals(k.id(), k2.id(), "and another entry");
        // the same bytes under another address are the picture already on the shelves: its entry, nothing fetched, checked or written again (0.7.0, a coin kept twice on a live run)
        Images.Kept twin = Images.keep(store, new Images.Found("https://twin.example/pics/Wild-Dog-again.jpg", "https://twin.example/", "The same fox elsewhere", "SearXNG", 0, 0, ""), "", "", j);
        assertEquals(k.id(), twin.id()); assertEquals(k.file(), twin.file()); assertEquals(List.of("default:image/png"), checked, "no second check");
        assertEquals("", RawCapture.field(twin.raw(), "verdict"), "the entry stands as it was");
        assertEquals("yes", RawCapture.field(k2.raw(), "verdict")); assertEquals("a fox stands in snow", RawCapture.field(k2.raw(), "verdict_why")); assertEquals("a fox in snow", RawCapture.field(k2.raw(), "criteria")); assertEquals("qwen3.8-27b", RawCapture.field(k2.raw(), "verdict_model"));
        assertTrue(RawCapture.read(k2.raw())[2].startsWith("A red fox, photographed in snow.\n\n!["), RawCapture.read(k2.raw())[2]);
        assertTrue(RawCapture.read(k2.raw())[2].contains("judged against \"a fox in snow\": yes — a fox stands in snow."));
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.YES, 1.0, "test");
        Images.Found f3 = Images.parseBrave(fixture("brave-images.json")).get(2);
        assertTrue(assertThrows(IOException.class, () -> Images.keep(store, f3, "", "")).getMessage().contains("was not kept: the check every picture from the web goes through before it is kept said no"));
        // what is not a picture, and what is too heavy, is not kept
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        Images.Found svg = new Images.Found("https://cdn.example/icon.svg", "https://cdn.example/", "An icon", "SearXNG", 0, 0, "");
        assertTrue(assertThrows(IOException.class, () -> Images.keep(store, svg, "", "")).getMessage().contains("is not a picture the library keeps (image/svg+xml)"));
        Images.FETCHER = url -> new Images.Fetched(new byte[(int) Images.MAX_BYTES + 1], "image/jpeg");
        assertTrue(assertThrows(IOException.class, () -> Images.keep(store, f3, "", "")).getMessage().contains("the library keeps pictures up to 8 MB"));
        Images.FETCHER = url -> { throw new IOException("the server answered HTTP 404"); };
        assertTrue(assertThrows(IOException.class, () -> Images.keep(store, f3, "", "")).getMessage().startsWith("The picture at " + f3.image() + " could not be fetched: the server answered HTTP 404."));
        assertEquals("jpg", Images.extension("text/html", new byte[]{(byte) 0xff, (byte) 0xd8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}), "the bytes first, the server's type second");
        assertNull(Images.extension("text/html", "<html>".getBytes(StandardCharsets.UTF_8))); assertEquals("webp", Images.extension("image/webp", new byte[0]));
        assertEquals("image/jpeg", Images.contentType("a.JPG")); assertEquals("application/octet-stream", Images.contentType("a.txt"));
    }

    @Test void theSeeingModelsWordsAreReadAsAVerdictOrADescription() throws Exception {
        ModelEntries.Entry eyes = seeingDefault();
        List<String> questions = new ArrayList<>();
        Images.Found f = Images.parseBrave(fixture("brave-images.json")).get(0);
        Vision.DRIVES = (d, m) -> eyes("Yes — a red fox stands in deep snow, looking at the camera.", questions);
        List<Images.Judged> js = Images.judge(eyes, "a fox in snow?", List.of(f));
        assertEquals(1, js.size()); Images.Judged j = js.get(0);
        assertEquals(Images.Verdict.YES, j.verdict()); assertEquals("a red fox stands in deep snow, looking at the camera", j.why()); assertEquals("qwen3.8-27b", j.model()); assertEquals("a fox in snow?", j.criteria()); assertTrue(j.yes());
        assertEquals("Does this picture show a fox in snow? Answer: yes, no or unsure, then one sentence why.", questions.get(0));
        assertEquals("\"Wild Dog Breeds: Red Fox\": yes — a red fox stands in deep snow, looking at the camera", j.said());
        assertEquals("yes", j.json(M).path("verdict").asText()); assertEquals(f.image(), j.json(M).path("image").asText()); assertFalse(j.json(M).path("judged").asText().isEmpty());
        assertEquals("yes", j.more().get("verdict")); assertEquals("a fox in snow?", j.more().get("criteria"));
        assertEquals(Images.Verdict.NO, Images.verdict(f, "c", "m", "No. The animal is a dog.").verdict()); assertEquals("The animal is a dog", Images.verdict(f, "c", "m", "No. The animal is a dog.").why());
        assertEquals(Images.Verdict.UNSURE, Images.verdict(f, "c", "m", "Unsure: the picture is too small to tell.").verdict()); assertEquals("the picture is too small to tell", Images.verdict(f, "c", "m", "Unsure: the picture is too small to tell.").why());
        Images.Judged odd = Images.verdict(f, "c", "m", "I think it might be a fox but the snow could be sand.");
        assertEquals(Images.Verdict.UNSURE, odd.verdict(), "a sentence that says 'could be' and no yes or no is read as unsure (the second layer, 0.7.0)"); assertEquals("I think it might be a fox but the snow could be sand", odd.why()); assertFalse(odd.yes());
        Images.Judged none = Images.verdict(f, "c", "m", "The picture is small and dark.");
        assertEquals(Images.Verdict.UNJUDGED, none.verdict()); assertTrue(none.why().startsWith("the model answered \"The picture is small"), none.why());
        DriveClient.clearClassifyProblem();
        assertEquals("the model gave no answer", Images.verdict(f, "c", "m", "").why());
        assertEquals("\"Wild Dog Breeds: Red Fox\": not judged — the model gave no answer", Images.verdict(f, "c", "m", "").said());
        // a picture that cannot be fetched is unjudged with the reason, and the others are judged
        Images.FETCHER = url -> { if (url.contains("Wild-Dog")) throw new IOException("the server answered HTTP 403"); return new Images.Fetched(Vision.square(255, 0, 0), "image/png"); };
        js = Images.judge(eyes, "a fox", Images.parseBrave(fixture("brave-images.json")).subList(0, 2));
        assertEquals(Images.Verdict.UNJUDGED, js.get(0).verdict()); assertEquals("the picture could not be fetched: the server answered HTTP 403", js.get(0).why()); assertEquals(Images.Verdict.YES, js.get(1).verdict());
        // a description: the words a later search can be built from, the question after
        questions.clear();
        Vision.DRIVES = (d, m) -> eyes("A red fox (Vulpes vulpes) stands in snow in a boreal forest, winter; no people, no writing.", questions);
        String said = Images.describe(eyes, Vision.square(255, 0, 0), "image/png", "Is it winter?");
        assertEquals("A red fox (Vulpes vulpes) stands in snow in a boreal forest, winter; no people, no writing.", said);
        assertEquals(Images.DESCRIBE + " Then answer this: Is it winter?", questions.get(0));
        assertTrue(Images.DESCRIBE.contains("the people in it") && Images.DESCRIBE.contains("the place") && Images.DESCRIBE.contains("the time or period") && Images.DESCRIBE.contains("any writing"));
        // the seam for the third use is there and says it is not built
        assertFalse(Images.likenessAvailable());
        assertEquals(Images.NO_LIKENESS, assertThrows(UnsupportedOperationException.class, () -> Images.alike(new byte[0], "image/png", List.of())).getMessage());
    }

    @Test void theProtocolFindsKeepsDescribesAndJudgesAndSaysHowToAddEyes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        JsonNode r = p.images(person().put("op", "find").put("words", "red fox").put("count", 4));
        assertEquals(4, r.path("count").asInt()); assertEquals(4, r.path("found").size()); assertEquals("4 pictures found for \"red fox\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).", r.path("summary").asText());
        assertEquals("Brave", r.path("found").get(0).path("source").asText()); assertTrue(r.path("found").get(0).has("said") && r.path("found").get(0).has("found"));
        assertEquals("Brave", r.path("engines").get(0).asText()); assertFalse(r.has("kept"));
        assertEquals("Say what pictures to find: words.", assertThrows(ProtocolError.class, () -> p.images(person().put("op", "find"))).getMessage());
        assertEquals("op is find, keep, describe, judge or like.", assertThrows(ProtocolError.class, () -> p.images(person().put("op", "no-such-op"))).getMessage());
        // no model sees: judging and describing say what the library has and how one is added
        String noEyes = assertThrows(ProtocolError.class, () -> p.images(person().put("op", "find").put("words", "red fox").put("criteria", "a fox"))).getMessage();
        assertTrue(noEyes.startsWith("No model that sees pictures is set up. The library has: default (qwen3.8-27b, not probed for pictures)."), noEyes);
        assertTrue(assertThrows(ProtocolError.class, () -> { ObjectNode a = person().put("op", "describe"); a.putArray("images").add("https://x.example/a.jpg"); p.images(a); }).getMessage().startsWith("No model that sees pictures"));
        // keeping, with the find or after it
        r = p.images(person().put("op", "find").put("words", "red fox").put("count", 2).put("keep", true).put("collection", "foxes"));
        assertEquals(2, r.path("kept").size()); assertEquals(0, r.path("not_kept").size());
        assertTrue(r.path("summary").asText().endsWith("2 of 2 pictures are on the shelves under foxes."), r.path("summary").asText());
        String url = r.path("kept").get(0).path("url").asText(); assertTrue(url.startsWith("/images/") && url.endsWith(".png"), url);
        assertTrue(Files.isRegularFile(store.root().resolve(r.path("kept").get(0).path("file").asText())));
        assertNotNull(RawCapture.find(store, r.path("kept").get(0).path("raw").asText()));
        ObjectNode keep = person().put("op", "keep"); keep.putArray("images").add("https://cdn.example/icon.svg").add(r.path("found").get(1));
        r = p.images(keep);
        assertEquals(1, r.path("kept").size()); assertEquals(1, r.path("not_kept").size()); assertTrue(r.path("not_kept").get(0).path("why").asText().contains("not a picture the library keeps"));
        assertTrue(assertThrows(ProtocolError.class, () -> p.images(person().put("op", "keep"))).getMessage().startsWith("keep needs images"));
        // a model sees: the verdicts ride with the find and go onto the kept entries; a description comes back as prose
        ModelEntries.Entry eyes = seeingDefault();
        Vision.DRIVES = (d, m) -> eyes("Yes, a red fox on snow.", new ArrayList<>());
        r = p.images(person().put("op", "find").put("words", "red fox").put("count", 3).put("criteria", "a fox").put("keep", true));
        assertEquals(3, r.path("verdicts").size()); assertEquals("yes", r.path("verdicts").get(0).path("verdict").asText()); assertEquals("a red fox on snow", r.path("verdicts").get(0).path("why").asText());
        assertTrue(r.path("summary").asText().contains("Judged against \"a fox\" by default: 3 of 3 show it."), r.path("summary").asText());
        assertEquals(3, r.path("kept").size());
        // the first two were kept before this judgement: the raw tier keeps the first capture of an address, so their entries stand as they were; the third is new and carries its verdict
        Path raw = RawCapture.find(store, r.path("kept").get(2).path("raw").asText());
        assertEquals("yes", RawCapture.field(raw, "verdict")); assertEquals("a red fox on snow", RawCapture.field(raw, "verdict_why"));
        assertEquals("", RawCapture.field(RawCapture.find(store, r.path("kept").get(0).path("raw").asText()), "verdict"), "kept earlier without a judgement: the first entry stands");
        ObjectNode judge = person().put("op", "judge").put("criteria", "a fox"); judge.putArray("images").add(url).add("https://x.example/b.jpg");
        r = p.images(judge);
        assertEquals(2, r.path("verdicts").size()); assertEquals(url, r.path("verdicts").get(0).path("image").asText()); assertEquals("yes", r.path("verdicts").get(1).path("verdict").asText());
        assertEquals("Judged against \"a fox\" by default (qwen3.8-27b): 2 of 2 show it.", r.path("summary").asText());
        assertTrue(assertThrows(ProtocolError.class, () -> p.images(person().put("op", "judge"))).getMessage().startsWith("judge needs criteria"));
        Vision.DRIVES = (d, m) -> eyes("A red square fills the whole frame; no people, no place, no writing.", new ArrayList<>());
        ObjectNode describe = person().put("op", "describe").put("question", "Is anything written?"); describe.putArray("images").add(url).add("no-such-picture");
        r = p.images(describe);
        assertEquals("A red square fills the whole frame; no people, no place, no writing.", r.path("descriptions").get(0).path("description").asText());
        assertEquals("", r.path("descriptions").get(1).path("description").asText()); assertTrue(r.path("descriptions").get(1).path("why").asText().startsWith("Nothing is at "), "the keeper may name a path, so an unknown name is looked for on the machine: " + r.path("descriptions").get(1).path("why").asText());
        assertEquals("1 of 2 pictures described by default (qwen3.8-27b).", r.path("summary").asText());
        // a reader may find and judge, not keep
        Patrons.set(store, "did:key:zReader", "A reader", Patrons.Level.read);
        ObjectNode reader = M.createObjectNode(); reader.putObject("patron").put("did", "did:key:zReader").put("name", "A reader").put("runtime", "test");
        assertEquals(2, p.images(reader.deepCopy().put("op", "find").put("words", "red fox").put("count", 2)).path("count").asInt());
        assertThrows(ProtocolError.class, () -> p.images(reader.deepCopy().put("op", "find").put("words", "red fox").put("keep", true)));
    }

    @Test void theCommandLineSaysEachCandidateWithItsPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        PrintStream out = System.out, err = System.err; ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o, true)); System.setErr(new PrintStream(e, true));
        try {
            assertEquals(0, ImagesCli.run(store, new String[]{"researchzosho", "images", "find", "red", "fox", "--count", "3"}));
            String said = o.toString();
            assertTrue(said.startsWith("3 pictures found for \"red fox\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).\n1. \"Wild Dog Breeds: Red Fox\" from a-z-animals.com, 768×401, found through Brave.\n   page: https://a-z-animals.com/animals/red-fox/pictures/\n   image: https://a-z-animals.com/media/2021/05/Wild-Dog-Breeds_-Red-Fox-768x401.jpg\n2. "), said);
            o.reset();
            assertEquals(1, ImagesCli.run(store, new String[]{"researchzosho", "images", "find", "red", "fox", "--judge", "a fox"}), "no model sees");
            assertTrue(e.toString().startsWith("No model that sees pictures is set up."), e.toString());
            e.reset();
            assertEquals(0, ImagesCli.run(store, new String[]{"researchzosho", "images", "find", "red", "fox", "--count", "1", "--keep"}));
            assertTrue(o.toString().contains("   kept as 20") && o.toString().contains(".md, shown at /images/"), o.toString());
            assertEquals(2, ImagesCli.run(store, new String[]{"researchzosho", "images"})); assertTrue(e.toString().startsWith("usage: researchzosho images find"), e.toString());
            o.reset(); e.reset();
            ModelEntries.Entry eyes = seeingDefault();
            Vision.DRIVES = (d, m) -> eyes("No — a red square, not an animal.", new ArrayList<>());
            assertEquals(0, ImagesCli.run(store, new String[]{"researchzosho", "images", "judge", "a fox", "https://x.example/a.jpg"}));
            assertEquals("Judged against \"a fox\" by default (qwen3.8-27b): 0 of 1 show it.\n  https://x.example/a.jpg: no — a red square, not an animal\n", o.toString());
            o.reset();
            Path pic = tmp.resolve("square.png"); Files.write(pic, Vision.square(255, 0, 0));
            Vision.DRIVES = (d, m) -> eyes("A solid red square.", new ArrayList<>());
            assertEquals(0, ImagesCli.run(store, new String[]{"researchzosho", "images", "describe", pic.toString(), "what", "colour?"}));
            assertEquals("A solid red square.\n(qwen3.8-27b)\n", o.toString());
        } finally { System.setOut(out); System.setErr(err); }
    }

    @Test void theChatFindsOnItsOwnShowsThemSmallKeepsThemOnAWordAndSaysHowToAddEyes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), session);
        assertNull(ImagesTurn.reply(store, session, Librarian.person(), "what is the saros?"), "not this turn's words");
        assertNull(ImagesTurn.reply(store, session, Librarian.person(), "keep them"), "nothing found yet: not this turn's words either");
        String reply = lib.say("find me some pictures of red fox");
        assertTrue(reply.startsWith("6 pictures found for \"red fox\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).\n\n1. \"Wild Dog Breeds: Red Fox\" from a-z-animals.com, 768×401, found through Brave.\n   https://a-z-animals.com/animals/red-fox/pictures/\n   ![Wild Dog Breeds: Red Fox](https://imgs.search.brave.com/"), reply);
        assertTrue(reply.endsWith(ImagesTurn.OFFER), reply);
        assertEquals(6, session.imagesFound().size(), "the fixtures hold seven pictures between them, and the SVG is not one the model can look at");
        String html = Pages.md(reply);
        assertTrue(html.contains("<img class=\"thumb\" src=\"https://imgs.search.brave.com/"), html); assertTrue(html.contains("alt=\"Wild Dog Breeds: Red Fox\" loading=\"lazy\" referrerpolicy=\"no-referrer\">"), html);
        // a count in the words, and the Japanese words
        assertTrue(lib.say("show me 2 photos of a red fox").startsWith("2 pictures found for \"a red fox\""));
        assertTrue(lib.say("赤いキツネの写真を3枚探して").startsWith("3 pictures found for \"赤いキツネ\""));
        assertEquals(3, session.imagesFound().size());
        // "keep 1, 3": the ones numbered go onto the shelves, each with where it is shown
        String kept = lib.say("keep 1, 3");
        assertTrue(kept.startsWith("2 pictures are on the shelves now, each with the page it came from; researchzosho search finds them by their words.\nKept \"Wild Dog Breeds: Red Fox\" as 20"), kept);
        assertTrue(kept.contains(".md, shown at /images/"), kept);
        try (var files = Files.list(store.root().resolve("catalog").resolve("images"))) { assertEquals(2, files.count(), "two pictures, two files (the same bytes twice would be one: the keep test)"); }
        try (var raws = Files.list(store.rawDir())) { assertEquals(2, raws.filter(f -> f.toString().endsWith(".md")).count(), "two entries, one per picture found"); }
        assertEquals("Which ones? The last search found 3 pictures, numbered 1 to 3.", lib.say("keep 9"));
        // a judgement with no eyes says how to add some; with eyes it judges the last search's pictures
        String noEyes = lib.say("which of these show a fox?");
        assertTrue(noEyes.startsWith("No model that sees pictures is set up."), noEyes);
        seeingDefault();
        Vision.DRIVES = (d, m) -> eyes("Yes — a red fox.", new ArrayList<>());
        String judged = lib.say("do these pictures show a fox?");
        assertTrue(judged.startsWith("Judged against \"a fox\" by default (qwen3.8-27b): 3 of 3 show it.\n1. \"Wild Dog Breeds: Red Fox\": yes — a red fox"), judged);
        // "describe this picture" wants the picture attached
        assertTrue(lib.say("describe this picture").startsWith("Attach the picture to the message and ask again"));
        Path pic = tmp.resolve("square.png"); Files.write(pic, Vision.square(255, 0, 0));
        session.att = M.createObjectNode().put("name", "square.png").put("path", pic.toString());
        Vision.DRIVES = (d, m) -> eyes("A solid red square, nothing else.", new ArrayList<>());
        assertEquals("A solid red square, nothing else.\n\n(default, qwen3.8-27b)", lib.say("what is in this picture?"));
        // a reader may not keep
        Patrons.set(store, "did:key:zReader", "A reader", Patrons.Level.read);
        Librarian.Session rs = Librarian.Session.open(store);
        rs.imagesFound(Images.parseBrave(fixture("brave-images.json")));
        assertEquals("Only the person who keeps this library and those who may write to it put pictures on the shelves.", ImagesTurn.reply(store, rs, new Patrons.Patron("did:key:zReader", "A reader", "test"), "keep them"));
    }

    @Test void thePageShowsAMarkdownPictureSmallAndFromThisLibraryOrTheWebOnly() {
        String own = Pages.md("A fox.\n\n![A red fox](/images/ab12.png)\n\nFrom https://example.org/fox (the page)");
        // a picture on a line of its own is at reading size since 2026-11-08 (ReportPicturesTest); among words, or under a list item as the chat's candidates are, it is small
        assertTrue(own.contains("<img class=\"figure\" src=\"/images/ab12.png\" alt=\"A red fox\" loading=\"lazy\" referrerpolicy=\"no-referrer\">"), own);
        assertTrue(Pages.md("A fox, ![A red fox](/images/ab12.png) small.").contains("<img class=\"thumb\" src=\"/images/ab12.png\" alt=\"A red fox\" loading=\"lazy\" referrerpolicy=\"no-referrer\">"), own);
        assertTrue(own.contains("<a href=\"https://example.org/fox\" rel=\"noreferrer\">"), own);
        String web = Pages.inline("![t](https://imgs.search.brave.com/x) and [a link](https://example.org/) and **bold**");
        assertTrue(web.startsWith("<img class=\"thumb\" src=\"https://imgs.search.brave.com/x\" alt=\"t\""), web); assertTrue(web.contains("<a href=\"https://example.org/\" rel=\"noreferrer\">a link</a>") && web.contains("<b>bold</b>"), web);
        assertFalse(Pages.inline("![t](//evil.example/x)").contains("<img"), "a protocol-relative address is no picture");
        assertEquals("![t](javascript:alert(1))", Pages.inline("![t](javascript:alert(1))"));
    }
}
