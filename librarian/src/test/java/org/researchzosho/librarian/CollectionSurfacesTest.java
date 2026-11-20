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
import org.researchzosho.mcp.McpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A collection on every surface (0.8.0): the chat holds the model's form for the person's yes, said in plain words with no raw call beside
 * it, and files it on the yes, naming the Pictures page; the protocol files it as a job; MCP offers its fields; the command line files it
 * and lists it; the Pictures page shows it as a gallery with each picture's size, page and why it passed, and serves its small copies;
 * the chat's notice when it ends names the gallery. And the older path: a find that keeps keeps only the pictures judged to show what was
 * asked (2026-11-19: all 40 were kept, the 10 judged wrong among them).
 */
class CollectionSurfacesTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String home;
    Supplier<List<PicturePlaces.Place>> places; Collection.Looker looker; Collection.Eyes eyes; BiFunction<String, String, Researcher.Drive> drives;
    BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check; Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> visionDrives;

    @BeforeEach void isolate() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        places = Collection.PLACES; looker = Collection.LOOKER; eyes = Collection.EYES; drives = Collection.DRIVES; check = Images.CHECK; engines = Images.ENGINES; fetcher = Images.FETCHER; visionDrives = Vision.DRIVES;
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b"); Config.set(ModelEntries.SEES, "yes"); Config.set("RESEARCHZOSHO_LIKENESS", "off");
    }
    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        Collection.PLACES = places; Collection.LOOKER = looker; Collection.EYES = eyes; Collection.DRIVES = drives; Images.CHECK = check; Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = visionDrives;
    }

    LibraryStore store() throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }
    static ObjectNode person() { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "").put("runtime", "test"); return a; }

    /** The person's words of the owner's own test of 2026-11-19, and the form a model would fill from them. */
    static final String ASK = "im looking for 20 distinct and really good 4k wallpaper (horizontal not vertical orientation) images of altered carbon (ai generated is ok). i want to use them for backgrounds on my computer. do not spend more than 15 min on this";
    static final String FORM = "{\"op\":\"collect\",\"words\":\"Altered Carbon\",\"shows\":\"really good 4k wallpaper\",\"count\":20,\"min_width\":3840,\"min_height\":2160,\"orientation\":\"landscape\",\"ai\":\"ok\",\"minutes\":15}";

    @Test void theChatHoldsTheFormInPlainWordsAndFilesItOnTheYesNamingThePicturesPage() throws Exception {
        LibraryStore store = store();
        var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_images", FORM), LibrarianChatTest.say("The library asks for your yes before it starts.")), new ArrayList<>());
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say(ASK);
        assertTrue(reply.contains("The model asks to collect up to 20 pictures of Altered Carbon: each showing really good 4k wallpaper, horizontal, at least 3840×2160, AI-made allowed, each a different picture, for at most 15 minutes, kept as links and small copies. Do it? (yes, no, or tell me more)"), reply);
        assertFalse(reply.contains("Exactly:") || reply.contains("library_images op="), "the form in plain words, no raw call beside it: " + reply);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        assertEquals(0, jobs.active().size(), "nothing before the person's yes");
        String yes = lib.say("yes");
        assertEquals(1, jobs.active().size());
        ObjectNode job = jobs.active().get(0);
        assertEquals(Collection.JOB_KIND, job.path("kind").asText());
        assertEquals(15, job.path("args").path("minutes").asInt()); assertEquals(3840, job.path("args").path("min_width").asInt()); assertEquals("landscape", job.path("args").path("orientation").asText());
        assertEquals(ASK, job.path("args").path("ask").asText(), "the person's own words go with the form, for the judge");
        String id = job.path("job_id").asText();
        assertTrue(yes.contains("/images?collection=" + id) && !yes.contains("folder"), yes);
    }

    @Test void aNarrowingTheModelReadInIsNeitherAskedNorRun() throws Exception {
        LibraryStore store = store();
        String overread = "{\"op\":\"collect\",\"words\":\"Altered Carbon wallpaper\",\"name\":\"altered carbon\",\"shows\":\"a scene in the style of the series\",\"count\":20,\"min_width\":3840,\"min_height\":2160,\"orientation\":\"landscape\",\"ai\":\"ok\",\"differ_by\":\"scene\",\"differ_quote\":\"20 distinct\",\"minutes\":15}";
        var drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_images", overread), LibrarianChatTest.say("The library asks for your yes.")), new ArrayList<>());
        Librarian lib = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say(ASK);
        assertTrue(reply.contains("The model asks to collect up to 20 pictures of Altered Carbon wallpaper: horizontal, at least 3840×2160, AI-made allowed, each a different picture, for at most 15 minutes"), reply);
        assertFalse(reply.contains("style of") || reply.contains("with a different"), "the readings his words do not say are not asked: " + reply);
        lib.say("yes");
        ObjectNode job = new Jobs(store, j -> { throw new IllegalStateException("read only"); }).active().get(0);
        assertFalse(job.path("args").has("differ_by") || job.path("args").has("shows"), job.path("args").toString());
        assertEquals("altered carbon", job.path("args").path("name").asText(), "the name he wrote stays");
    }

    @Test void aRequestPassedAloneIsReadIntoTheFormBeforeTheQuestionSoTheQuestionShowsItsRules() throws Exception {
        LibraryStore store = store();
        String alone = "{\"op\":\"collect\",\"ask\":\"" + ASK.replace("\"", "\\\"") + "\"}";
        String form = "{\"words\":\"Altered Carbon\",\"name\":\"altered carbon\",\"count\":20,\"min_width\":3840,\"min_height\":2160,\"orientation\":\"landscape\",\"ai\":\"ok\",\"minutes\":15,\"differ_by\":\"scene\",\"differ_quote\":\"20 distinct\"}";
        List<String> tools = new ArrayList<>();
        Researcher.Drive drive = new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode t, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (i++ == 0) { m.put("content", ""); ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c1"); tc.put("type", "function"); tc.putObject("function").put("name", "library_images").put("arguments", alone); }
                else m.put("content", "The library asks for your yes.");
                return m;
            }
            @Override public ObjectNode decide(ArrayNode messages, ArrayNode t, int maxTokens, String toolChoice) {   // the form read from the request, as the job's model reads it
                tools.add(t.get(0).path("function").path("name").asText());
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "f1"); tc.put("type", "function"); tc.putObject("function").put("name", "collect").put("arguments", form);
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        String reply = new Librarian(store, drive, Librarian.person(), Librarian.Session.open(store)).say(ASK);
        assertEquals(List.of("collect"), tools, "the request read into the form once, before the question");
        assertTrue(reply.contains("The model asks to collect up to 20 pictures of Altered Carbon: horizontal, at least 3840×2160, AI-made allowed, each a different picture, for at most 15 minutes"), reply);
        assertFalse(reply.contains("with a different"), "and held to his words: " + reply);
    }

    @Test void theProtocolFilesACollectionAndRefusesOneWithNoSeeingModel() throws Exception {
        LibraryStore store = store();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = (ObjectNode) person().setAll((ObjectNode) M.readTree(FORM));
        JsonNode r = p.images(a);
        String id = r.path("job_id").asText();
        assertTrue(id.startsWith("J-"), r.toString()); assertEquals("/images?collection=" + id, r.path("gallery").asText());
        assertTrue(r.path("summary").asText().contains("collect up to 20 pictures of Altered Carbon"), r.toString());
        ObjectNode j = new Jobs(store, x -> { throw new IllegalStateException("read only"); }).get(id);
        assertEquals("queued", j.path("state").asText()); assertEquals("Pictures: Altered Carbon", j.path("args").path("title").asText());
        Config.set(ModelEntries.SEES, "no");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.images((ObjectNode) person().setAll((ObjectNode) M.readTree(FORM))));
        assertTrue(e.getMessage().toLowerCase().contains("pictures"), e.getMessage());
    }

    @Test void mcpOffersTheFormsFields() {
        JsonNode images = null;
        for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_images")) images = t;
        assertNotNull(images);
        JsonNode props = images.path("inputSchema").path("properties");
        for (String f : List.of("words", "shows", "count", "min_width", "min_height", "orientation", "format", "ai", "differ_by", "minutes", "keep_full", "ask")) assertTrue(props.has(f), f + " in " + props);
        assertTrue(props.path("op").path("enum").toString().contains("collect"), props.path("op").toString());
    }

    @Test void aFindThatKeepsIsACollectionAndAFindWithoutKeepIsALook() throws Exception {
        LibraryStore store = store();
        Images.ENGINES = () -> List.of(new Images.Engine("SearXNG", (w, n) -> List.of(
                new Images.Found("https://a.example/scene.png", "https://a.example/s", "a scene", "SearXNG", 0, 0, ""),
                new Images.Found("https://a.example/logo.png", "https://a.example/l", "a logo", "SearXNG", 0, 0, ""))));
        Images.FETCHER = url -> new Images.Fetched(ImagesTest.square(url), "image/png");
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "yes — a scene"; }
            @Override public int contextWindow() { return 16384; }
        };
        // on 2026-11-19 a find that kept put 5 of 35 on the shelves, nothing measured, no gallery: now it is filed as the collection it is
        ObjectNode a = person(); a.put("op", "find").put("words", "the series").put("count", 2).put("keep", true).put("criteria", "a scene from the series");
        JsonNode r = new LibraryProtocol(store).images(a);
        assertEquals("collect", r.path("op").asText()); assertEquals("/images?collection=" + r.path("job_id").asText(), r.path("gallery").asText());
        assertEquals(0, Images.onShelves(store).size(), "nothing kept before the collection runs");
        ObjectNode job = new Jobs(store, j -> { throw new IllegalStateException("read only"); }).get(r.path("job_id").asText());
        assertEquals(Collection.JOB_KIND, job.path("kind").asText()); assertEquals("a scene from the series", job.path("args").path("shows").asText()); assertEquals(2, job.path("args").path("count").asInt());
        // control: a find without keep is a look: candidates, nothing filed, nothing kept
        JsonNode look = new LibraryProtocol(store).images(person().put("op", "find").put("words", "the series").put("count", 2));
        assertEquals(2, look.path("found").size()); assertFalse(look.has("job_id")); assertEquals(1, new Jobs(store, j -> { throw new IllegalStateException("read only"); }).active().size());
    }

    @Test void thePicturesPageShowsTheCollectionAsAGalleryAndTheCommandLineListsIt() throws Exception {
        LibraryStore store = store();
        // a finished collection, made through the job with stand-ins
        CollectionTest c = new CollectionTest();
        c.answers.put("wallhaven|altered carbon|1", List.of(c.pic("https://w.example/ac1.jpg", "city at night", PicturesMeasuredTest.art(61, 61, 3840, 2160), 0, 0),
                c.pic("https://w.example/ac2.jpg", "title card", PicturesMeasuredTest.art(62, 62, 3840, 2160), 0, 0)));
        c.serve(List.of("wallhaven", "web"));
        c.eyes(t -> t.contains("city") ? "yes — a neon city from the series" : "no — the title on black, no scene");
        c.seat("wallhaven|altered carbon", "done no more found");
        ObjectNode job = c.job("J-0042", new CollectionForm("Altered Carbon", "", "", 20, 3840, 2160, "landscape", "", "ok", "", 0, false));
        Collection.runJob(store, job, "http://x", "m", x -> { }, () -> false);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient h = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            String page = h.send(HttpRequest.newBuilder(URI.create(base + "/images?collection=J-0042")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("Collect up to 20 pictures of Altered Carbon: horizontal, at least 3840×2160"), "the form in plain words");
            assertTrue(page.contains("1 of 20 pictures of Altered Carbon kept: the model searching for them was sure there were no more: no more found."), page);
            assertTrue(page.contains("3840×2160 · JPG") && page.contains("why it passed: a neon city from the series") && page.contains("href=\"https://pages.example/city-at-night\""), "each picture's size, page and why it passed");
            assertFalse(page.contains("title card\" loading"), "what was judged wrong is not in the gallery");
            assertTrue(page.contains("Keep these pictures at full size in the library"), "the keeper may keep the files in a library of their own");
            assertTrue(page.contains("The searches the model chose (1)") && page.contains("wallhaven &quot;altered carbon&quot;"), "the searches the model chose, each with what it kept");
            String thumb = Collection.manifest(store, "J-0042").path("passed").get(0).path("thumb").asText();
            HttpResponse<byte[]> img = h.send(HttpRequest.newBuilder(URI.create(base + "/images/collections/J-0042/" + thumb)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, img.statusCode()); assertEquals("image/jpeg", img.headers().firstValue("Content-Type").orElse(""));
            assertEquals(404, h.send(HttpRequest.newBuilder(URI.create(base + "/images/collections/J-0042/..%2Fmanifest.json")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "only the small copies are served");
            // a collection whose job the service ended before the collection could say so (stopped three times in a row): ended, not still collecting
            ObjectNode cut = Collection.manifest(store, "J-0042"); cut.put("state", "running"); cut.remove("stopped");
            Files.writeString(Collection.manifestFile(store, "J-0042"), cut.toString());
            String ended = h.send(HttpRequest.newBuilder(URI.create(base + "/images?collection=J-0042")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(ended.contains("its job ended before it finished; the Runs page says why") && !ended.contains("Collecting:"), "no job runs it: " + ended);
            String list = h.send(HttpRequest.newBuilder(URI.create(base + "/images")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(list.contains("href=\"/images?collection=J-0042\">Altered Carbon</a>") && list.contains("1 of 20 kept"), "the Pictures page lists the collections");
        } finally { d.stop(); }
        // the command line lists it
        PrintStream was = System.out; ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { assertEquals(0, ImagesCli.run(store, new String[]{"librarian", "images", "collection", "J-0042"})); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("1. city at night — 3840×2160 JPG") && said.contains("image: https://w.example/ac1.jpg") && said.contains("why: a neon city from the series"), said);
        // the chat's notice when it ends names the gallery
        ObjectNode m = Collection.manifest(store, "J-0042");
        assertTrue(Collection.told(m, Chrome.ENGLISH).endsWith("See them on the Pictures page: /images?collection=J-0042"), Collection.told(m, Chrome.ENGLISH));
    }
}
