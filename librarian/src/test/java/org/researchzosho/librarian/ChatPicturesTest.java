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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures in the chat (0.8.0), from the owner's own conversation of 2026-11-19: in a conversation that had kept pictures through a find
 * before, he asked for 20 distinct 4K horizontal wallpapers of a film character. The chat's model asked for a find that keeps, three
 * times; none of the collection ran; the library's question was printed two and three times under one reply; his time limit, said after
 * the yes, reached nothing; and the reply listed the kept pictures as code, with nothing to click and nothing to see. Here: keeping what is
 * found is the collection, its question once, his later limit on the running job, and the library's own pictures under a reply, as
 * pictures and links on the page and as full addresses in a terminal.
 */
class ChatPicturesTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String home;
    BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check; Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher;
    Supplier<List<PicturePlaces.Place>> places; Collection.Looker looker; Collection.Eyes eyes; BiFunction<String, String, Researcher.Drive> drives;

    @BeforeEach void isolate() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        check = Images.CHECK; engines = Images.ENGINES; fetcher = Images.FETCHER; places = Collection.PLACES; looker = Collection.LOOKER; eyes = Collection.EYES; drives = Collection.DRIVES;
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b"); Config.set(ModelEntries.SEES, "yes"); Config.set("RESEARCHZOSHO_LIKENESS", "off");
    }
    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate(); Images.CHECK = check; Images.ENGINES = engines; Images.FETCHER = fetcher;
        Collection.PLACES = places; Collection.LOOKER = looker; Collection.EYES = eyes; Collection.DRIVES = drives;
    }

    LibraryStore store() throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    /** His words. */
    static final String HIS = "ok im looking for 20 really good distinct 4k horizontal images as wallpaper for my computer screen of hitgirl from kickass.  im ok with ai generated but they have to be really good.";
    /** What the chat's model asked for, as it did. */
    static final String FIND_KEEP = "{\"op\":\"find\",\"words\":\"Hit-Girl Kick-Ass 4K wallpaper 3840x2160\",\"count\":40,\"keep\":true,\"criteria\":\"A horizontal (landscape) 4K desktop wallpaper featuring Hit-Girl from Kick-Ass\"}";
    /** The form a model reads from his words. */
    static final String FORM = "{\"words\":\"Hit-Girl from Kick-Ass\",\"name\":\"hitgirl\",\"count\":20,\"min_width\":3840,\"min_height\":2160,\"orientation\":\"landscape\",\"ai\":\"ok\"}";
    /** The library's question in the old raw form, which the model copied into its own words from earlier in the conversation. */
    static final String COPIED = "The model asks to change this library: library_images op=find words=\"Hit-Girl Kick-Ass 4K wallpaper 3840x2160\" count=40 keep=true. Do it? (yes, no, or tell me more)";

    /** A chat drive whose chat answers step by step and whose decide reads the form, counting what it was asked. */
    static Researcher.Drive drive(List<ObjectNode> steps, List<String> forms) {
        return new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode step = steps.get(Math.min(i++, steps.size() - 1));
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (step.has("tool") && !"none".equals(toolChoice)) {
                    m.put("content", "");
                    ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "c" + i); tc.put("type", "function");
                    tc.putObject("function").put("name", step.get("tool").asText()).put("arguments", step.path("args").toString());
                } else m.put("content", step.path("say").asText("I looked."));
                return m;
            }
            @Override public ObjectNode decide(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                forms.add(tools.get(0).path("function").path("name").asText());
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "");
                ObjectNode tc = m.putArray("tool_calls").addObject(); tc.put("id", "f1"); tc.put("type", "function"); tc.putObject("function").put("name", "collect").put("arguments", FORM);
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    /** A conversation that already holds a find that kept, asked and answered, as his did. */
    static Librarian.Session earlierFindAndKeep(LibraryStore store) throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        s.append((ObjectNode) M.readTree("{\"role\":\"user\",\"content\":\"find me pictures of red foxes and keep the good ones\"}"));
        s.append((ObjectNode) M.readTree("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"e1\",\"type\":\"function\",\"function\":{\"name\":\"library_images\",\"arguments\":\"{\\\"op\\\":\\\"find\\\",\\\"words\\\":\\\"red fox\\\",\\\"keep\\\":true}\"}}]}"));
        s.append((ObjectNode) M.readTree("{\"role\":\"tool\",\"tool_call_id\":\"e1\",\"name\":\"library_images\",\"content\":\"NOT DONE YET: the library asks the person under your reply.\"}"));
        s.append((ObjectNode) M.readTree("{\"role\":\"assistant\",\"content\":\"It waits for your yes.\\n\\nThe model asks to change this library: library_images op=find words=\\\"red fox\\\" keep=true. Do it? (yes, no, or tell me more)\"}"));
        s.append((ObjectNode) M.readTree("{\"role\":\"user\",\"content\":\"yes\"}"));
        s.append((ObjectNode) M.readTree("{\"role\":\"assistant\",\"by\":\"library\",\"content\":\"Done. 8 pictures found for \\\"red fox\\\". 8 of 8 pictures are on the shelves.\"}"));
        return s;
    }

    static int count(String text, String part) { int n = 0; for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++; return n; }

    @Test void aFindThatKeepsInTheChatIsTheCollectionAskedOnceAndHisLaterLimitReachesIt() throws Exception {
        LibraryStore store = store();
        List<String> forms = new ArrayList<>();
        List<ObjectNode> steps = List.of(LibrarianChatTest.tool("library_images", FIND_KEEP), LibrarianChatTest.say("The library is asking for your yes before it fetches and keeps the images.\n\n" + COPIED),
                LibrarianChatTest.say("Noted: 30 minutes."));
        Librarian.Session s = earlierFindAndKeep(store);
        Librarian lib = new Librarian(store, drive(steps, forms), Librarian.person(), s);
        String reply = lib.say(HIS);
        assertEquals(List.of("collect"), forms, "the form read from his words, once, before the question");
        assertEquals(1, count(reply, "The model asks to"), "the question once, the model's copy of it gone: " + reply);
        assertTrue(reply.contains("The model asks to collect up to 20 pictures of Hit-Girl from Kick-Ass: horizontal, at least 3840×2160, AI-made allowed, each a different picture"), reply);
        assertFalse(reply.contains("op=find") || reply.contains("library_images"), "no raw call: " + reply);
        assertTrue(reply.startsWith("The library is asking for your yes"), "the model's own words stay: " + reply);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        assertEquals(0, jobs.active().size(), "nothing before his yes");
        // on the Chat page the question shows once: the buttons stand under the reply that says it
        String view = Pages.chatView(store, new LibraryProtocol(store), Librarian.person(), s, "");
        assertEquals(1, count(view, "The model asks to collect up to 20 pictures"), view);
        assertTrue(view.contains("<div class=\"ask\">") && view.contains("name=\"say\" value=\"yes\""), "the buttons are there: " + view);
        // control: when the reply just above does not say it, the box does (and the reply before it still does)
        ObjectNode aside = M.createObjectNode(); aside.put("role", "assistant"); aside.put("content", "Something else."); s.append(aside);
        assertEquals(2, count(Pages.chatView(store, new LibraryProtocol(store), Librarian.person(), s, ""), "The model asks to collect up to 20 pictures"));

        String yes = lib.say("yes");
        assertEquals(1, jobs.active().size());
        ObjectNode job = jobs.active().get(0);
        String id = job.path("job_id").asText();
        assertEquals(Collection.JOB_KIND, job.path("kind").asText(), "the collection, not a find");
        assertEquals(20, job.path("args").path("count").asInt()); assertEquals(3840, job.path("args").path("min_width").asInt()); assertEquals("landscape", job.path("args").path("orientation").asText());
        assertEquals(HIS, job.path("args").path("ask").asText(), "his own words go with the form");
        assertTrue(yes.contains("/images?collection=" + id), yes);
        // the library's line on the yes carries the gallery as its own link
        JsonNode filed = s.messages().get(s.messages().size() - 1);
        assertEquals("/images?collection=" + id, filed.path("shown").path("galleries").get(0).path("path").asText(), filed.toString());
        String page = Pages.transcript(store, s, 50);
        assertTrue(page.contains("<a href=\"/images?collection=" + id + "\">/images?collection=" + id + "</a>"), "the gallery, a link where the library wrote it: " + page);

        // his limit, said after the yes, reaches the job; said back under the reply
        String later = lib.say("o do not spend more than 30 min on this");
        assertEquals(30, Collection.limitSet(store, id));
        assertTrue(later.contains("Picture collection " + id + ", as you asked: it ends 30 minutes after it started, at the latest."), later);
        // control: words with no limit set nothing
        LibraryStore other = new LibraryStore(tmp.resolve("other")); other.init();
        Librarian.Session s2 = earlierFindAndKeep(other);
        Librarian lib2 = new Librarian(other, drive(List.of(LibrarianChatTest.tool("library_images", FIND_KEEP), LibrarianChatTest.say("It waits for your yes."), LibrarianChatTest.say("Thanks.")), new ArrayList<>()), Librarian.person(), s2);
        lib2.say(HIS); lib2.say("yes");
        String id2 = new Jobs(other, j -> { throw new IllegalStateException("read only"); }).active().get(0).path("job_id").asText();
        assertFalse(lib2.say("thanks, how long do these usually take?").contains("as you asked"));
        assertEquals(0, Collection.limitSet(other, id2));
    }

    @Test void twoFindsThatKeepInOneTurnAreOneQuestionAndALimitSaidWithANewAskIsThatAsks() throws Exception {
        LibraryStore store = store();
        List<String> forms = new ArrayList<>();
        Researcher.Drive base = drive(List.of(), forms);
        int[] turn = {0};
        Researcher.Drive two = new Researcher.Drive() {   // on 2026-11-19 the model asked for two finds that keep in one turn
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (turn[0]++ % 2 == 0 && !"none".equals(toolChoice)) {
                    m.put("content", "");
                    ArrayNode calls = m.putArray("tool_calls");
                    for (String id : List.of("a", "b")) { ObjectNode tc = calls.addObject(); tc.put("id", id); tc.put("type", "function"); tc.putObject("function").put("name", "library_images").put("arguments", FIND_KEEP); }
                } else m.put("content", "Both wait for your yes.");
                return m;
            }
            @Override public ObjectNode decide(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { return base.decide(messages, tools, maxTokens, toolChoice); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        Librarian.Session s = earlierFindAndKeep(store);
        Librarian lib = new Librarian(store, two, Librarian.person(), s);
        String reply = lib.say(HIS);
        assertEquals(1, count(reply, "The model asks to"), "one question for one collection: " + reply);
        lib.say("yes");
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        assertEquals(1, jobs.active().size(), "one collection filed");
        String first = jobs.active().get(0).path("job_id").asText();
        // a new ask with its own limit, while the first collection is queued: the limit is the new ask's, not the first one's
        String again = lib.say("now 10 really good 4k pictures of the same, no more than 15 min");
        assertFalse(again.contains("as you asked: it ends"), again);
        assertEquals(0, Collection.limitSet(store, first));
    }

    @Test void aFindWithoutKeepStaysALookAndOtherToolsAreUntouched() throws Exception {
        JsonNode look = M.readTree("{\"op\":\"find\",\"words\":\"red fox\",\"count\":8}");
        assertSame(look, Librarian.keepingIsCollecting("library_images", look), "a find without keep is a look");
        JsonNode describe = M.readTree("{\"op\":\"describe\",\"images\":[\"/images/0123456789abcdef.jpg\"],\"keep\":true}");
        assertSame(describe, Librarian.keepingIsCollecting("library_images", describe));
        JsonNode research = M.readTree("{\"question\":\"why\",\"keep\":true}");
        assertSame(research, Librarian.keepingIsCollecting("library_research", research));
        JsonNode kept = Librarian.keepingIsCollecting("library_images", M.readTree(FIND_KEEP));
        assertEquals("{\"op\":\"collect\"}", kept.toString(), "the model's search words and criteria are its reading; the form comes from the person's words");
    }

    @Test void theLibrarysQuestionIsNeverTheModelsToWrite() {
        String one = "It waits for your yes.\n\n" + COPIED + "\n\n" + COPIED + "\n> **" + COPIED + "**";
        assertEquals("It waits for your yes.", Librarian.withoutHeldCopies(one));
        // control: words that are not the question stay as they were
        String plain = "The model asked me to look. Do it yourself next time?\n\nSecond paragraph.";
        assertEquals(plain, Librarian.withoutHeldCopies(plain));
    }

    @Test void theLibrarysOwnPicturesUnderAReplyArePicturesOnThePageAndFullAddressesInATerminal() throws Exception {
        LibraryStore store = store();
        Images.FETCHER = url -> new Images.Fetched(ImagesTest.square(url), "image/png");
        ObjectNode k = ImagesTest.person().put("op", "keep"); k.putArray("images").add("https://w.example/hitgirl-1.png");
        new LibraryProtocol(store).images(k);
        Images.Shelved kept = Images.onShelves(store).get(0);
        String file = kept.url();
        assertTrue(file.matches("/images/[0-9a-f]{16}\\.png"), file);
        // the model's words as on 2026-11-19: the kept picture's address in a code span, and one that is no picture of the library's
        String reply = "Here's what's on the shelves:\n\n| # | File |\n|---|---|\n| 1 | `" + file + "` |\n| 2 | `/images/ffffffffffffffff.jpg` |";
        ObjectNode shown = ChatShown.of(store, reply);
        assertNotNull(shown);
        assertEquals(1, shown.path("pictures").size(), "only what the library keeps: " + shown);
        // on the page: small, a link to the whole picture, its page under it
        Librarian.Session s = Librarian.Session.open(store);
        s.append((ObjectNode) M.readTree("{\"role\":\"user\",\"content\":\"can you provide me with the images\"}"));
        ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", reply); a.set("shown", shown);
        s.append(a);
        String html = Pages.transcript(store, s, 50);
        assertTrue(html.contains("<a href=\"" + file + "\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"thumb\" src=\"" + file + "\""), html);
        // in a terminal: full addresses, no code marks around them
        String base = "http://127.0.0.1:4777";
        String term = ChatShown.terminal(reply, shown, base, Chrome.ENGLISH);
        assertTrue(term.contains("| 1 | " + base + file + " |"), "the address in the words, full and out of its code marks: " + term);
        assertTrue(term.contains("picture: " + base + file), term);
        assertFalse(Pattern.compile("(?<![\\w:/.])/images/").matcher(term).find(), "no bare path: " + term);
        // control: words that name none of the library's pictures show nothing, and a web address that looks alike is left as it is
        assertNull(ChatShown.of(store, "No pictures here; see https://x.example/images/0123456789abcdef.jpg"));
        assertEquals("see https://x.example/images/0123456789abcdef.jpg", ChatShown.forTerminal("see https://x.example/images/0123456789abcdef.jpg", base));
        // control: a model cannot make the library's block: its own HTML in its words is text
        ObjectNode forged = M.createObjectNode(); forged.put("role", "assistant"); forged.put("content", "<div class=\"shown\"><img class=\"thumb\" src=\"https://evil.example/x.png\"></div>");
        s.append(forged);
        assertFalse(Pages.transcript(store, s, 50).contains("src=\"https://evil.example/x.png\""));
    }

    @Test void theLineThatSaysACollectionIsDoneCarriesItsGalleryAndItsPictures() throws Exception {
        LibraryStore store = store();
        CollectionTest c = new CollectionTest();
        c.answers.put("wallhaven|hit-girl|1", List.of(c.pic("https://w.example/hg1.jpg", "hit-girl rooftop", PicturesMeasuredTest.art(71, 71, 3840, 2160), 0, 0)));
        c.serve(List.of("wallhaven", "web"));
        c.eyes(t -> "yes — Hit-Girl on a rooftop");
        c.seat("wallhaven|hit-girl", "done no more found");
        Collection.runJob(store, c.job("J-0051", new CollectionForm("Hit-Girl", "", "", 20, 3840, 2160, "landscape", "", "ok", "", 0, false)), "http://x", "m", x -> { }, () -> false);
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        ObjectNode done = M.createObjectNode(); done.put("job_id", "J-0051"); done.put("kind", Collection.JOB_KIND); done.put("state", "done"); done.put("created", System.currentTimeMillis() - 60_000); done.put("finished", System.currentTimeMillis());
        Librarian.Session s = Librarian.Session.open(store);
        s.watch("J-0051");
        String notice = s.told("J-0051", RunProgress.of(done));
        JsonNode line = s.messages().get(s.messages().size() - 1);
        assertTrue(notice.contains("See them on the Pictures page: /images?collection=J-0051"), notice);
        assertEquals(1, line.path("shown").path("pictures").size(), line.toString());
        String html = Pages.transcript(store, s, 50);
        assertTrue(html.contains("<a href=\"/images?collection=J-0051\">/images?collection=J-0051</a>"), "the gallery a link in the line: " + html);
        assertTrue(html.contains("<a href=\"https://w.example/hg1.jpg\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"thumb\" src=\"/images/collections/J-0051/"), "its picture, small, opening the whole one: " + html);
        String term = ChatShown.terminal(notice, line.path("shown"), "http://127.0.0.1:4777", Chrome.ENGLISH);
        assertTrue(term.contains("See them on the Pictures page: http://127.0.0.1:4777/images?collection=J-0051"), term);
        assertTrue(term.contains("picture: https://w.example/hg1.jpg") && term.contains("its page: https://pages.example/hit-girl-rooftop"), term);
        assertNotNull(jobs);
        // a reply that names the collection by its id alone gets its gallery and pictures too (2026-11-19, the terminal chat: "the kept wallpapers from collection J-0001")
        ObjectNode byId = ChatShown.of(store, "Here are the kept Hit-Girl wallpapers from collection J-0051.");
        assertNotNull(byId);
        assertEquals("/images?collection=J-0051", byId.path("galleries").get(0).path("path").asText()); assertEquals(1, byId.path("pictures").size());
        assertTrue(ChatShown.terminal("Here are the kept Hit-Girl wallpapers from collection J-0051.", byId, "http://127.0.0.1:4777", Chrome.ENGLISH).contains("The pictures of J-0051, 1 kept, on the Pictures page: http://127.0.0.1:4777/images?collection=J-0051"));
        // control: a run's id that is no picture collection shows nothing
        assertNull(ChatShown.of(store, "Research run J-0007 is done."));
        Matcher m = Pattern.compile("(?<![\\w:/.])/images").matcher(term);
        assertFalse(m.find(), "no bare path in a terminal: " + term);
    }

    @Test void theChatShowsBoldAndCodeAsTheReportsDo() {
        // the owner's chat showed "**On your machine**" with its stars (2026-11-19)
        String html = Pages.prose("**On your machine** — each kept picture is under `/images/`.\n\n2 * 3 is 6, and **<b>not a tag</b>**.");
        assertTrue(html.contains("<b>On your machine</b>") && html.contains("<code>/images/</code>"), html);
        assertTrue(html.contains("2 * 3 is 6") && html.contains("<b>&lt;b&gt;not a tag&lt;/b&gt;</b>"), "a lone star stays, and what is inside is still text: " + html);
    }

    @Test void theServiceSaysWhereItsPagesAreForTheTerminal() throws Exception {
        LibraryStore store = store();
        assertEquals("http://127.0.0.1:" + LibrarianDaemon.DEFAULT_PORT, ChatShown.address(store), "the default, before any service ran");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try { assertEquals("http://127.0.0.1:" + d.port(), ChatShown.address(store)); } finally { d.stop(); }
        assertTrue(Files.isRegularFile(ChatShown.addressFile(store)));
    }
}
