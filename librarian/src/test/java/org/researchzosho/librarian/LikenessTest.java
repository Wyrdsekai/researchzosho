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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A picture as the ask (0.7.0, the third increment of pictures), over the real surfaces: a picture sent with a chat turn as a browser
 * sends one, the Japanese and the describing forms, the two modes (the same thing, or like it in an aspect), the fall-back for a model
 * that takes one picture at a time, the protocol's like and judge with a reference, the command line; and likeness inside a research run,
 * a confirmed portrait becoming the reference for the rest of the search and a worker's reference on image_judge. The seeing model and the
 * engines are stood in; the stand-in says yes to the candidate whose page is titled for the fox, and names two colours to the two-squares probe.
 */
class LikenessTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String FOX = RunPicturesTest.FOX, FOX_PAGE = RunPicturesTest.FOX_PAGE, GETTY = RunPicturesTest.GETTY;
    static final String DESCRIBED = "A man of about fifty with a dark moustache, in a dark suit; a studio portrait, no writing.";

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        Vision.TWO.clear(); Vision.TWO_IMAGES = null;
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; Vision.TWO.clear(); Vision.TWO_IMAGES = null; }

    /** The questions asked and how many pictures each carried. */
    record Asked(String text, int pictures) { }

    /**
     * A seeing drive: names both colours to the two-squares probe; describes a reference as a man with a moustache and gives search words;
     * beside a reference says yes to the candidate whose page is titled for the fox; alone says yes to that candidate too, with a sentence
     * that names the face and the title (what a likeness rests on).
     */
    static Researcher.Drive seeing(List<Asked> asked) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("one question, one answer: not a chat turn"); }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                JsonNode content = messages.get(0).path("content");
                String text = content.get(0).path("text").asText();
                int pictures = content.size() - 1;
                asked.add(new Asked(text, pictures));
                if (text.equals(Vision.TWO_QUESTION)) return "red blue";
                if (text.startsWith(Images.DESCRIBE_REFERENCE.substring(0, 40))) return DESCRIBED + "\nSearch words: man moustache suit portrait studio";
                if (text.startsWith(Images.DESCRIBE.substring(0, 40))) return DESCRIBED;
                boolean fox = text.contains("titled \"Wild Dog Breeds: Red Fox\"") || text.contains("titled \"Alaska Red Fox");
                if (pictures == 2) return fox ? "Yes — the same face and moustache in both." : "No — a different face.";
                return fox ? "Yes — the page title names him and the face is a clear portrait." : "No — not him.";
            }
            @Override public String prose(ArrayNode messages, int maxTokens) { return classify(messages, maxTokens); }
            @Override public int contextWindow() { return 16384; }
        };
    }

    static byte[] portrait() { return Vision.square(40, 90, 160); }

    static String lastReply(LibraryStore store, String sessionId) throws Exception {
        List<ObjectNode> ms = Librarian.Session.resume(store, sessionId).messages();
        for (int i = ms.size() - 1; i >= 0; i--) if ("assistant".equals(ms.get(i).path("role").asText())) return ms.get(i).path("content").asText();
        return "";
    }

    @Test void aPictureSentWithAChatTurnIsKeptAsYoursDescribedAndTheSamePersonLookedForBesideIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            Map<String, byte[]> files = new LinkedHashMap<>(); files.put("stais.png", portrait());
            HttpResponse<String> r = ChatAttachmentTest.post(c, base + "/chat", "----rzboundary", ChatAttachmentTest.multipart("----rzboundary", s.id, "find more pictures of this person", files), null);
            assertEquals(200, r.statusCode(), r.body());
            String reply = lastReply(store, s.id);
            // kept as the person's own, described, searched by the description's words
            Images.Shelved given = Images.onShelves(store).stream().filter(x -> Images.GIVEN.equals(x.source())).findFirst().orElseThrow();
            assertEquals("stais.png", given.title()); assertEquals("given by you", given.origin()); assertEquals("given:" + Images.sha256(portrait()), given.locator());
            assertTrue(reply.startsWith("Your picture is kept as " + given.id() + " (shown at " + given.url() + "). default (qwen3.8-27b) described it: " + DESCRIBED + "\nSearched for \"man moustache suit portrait studio\" (the description's words, since you gave none): 6 pictures found for \"man moustache suit portrait studio\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).\n"), "REPLY: " + reply + "\nBODY: " + r.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "));
            assertTrue(reply.contains("Looking for the same person as in your picture, because the words asked for more pictures of this person, so the same one is looked for. The model that sees (default, qwen3.8-27b) compared each candidate with your picture, both in one look. No likeness helper is set up; the model's reading alone.\n"), reply);
            assertTrue(reply.contains("\n1. \"Wild Dog Breeds: Red Fox\": yes — the same face and moustache in both\n   " + FOX_PAGE + "\n"), reply);
            assertTrue(reply.contains("\n2. \"Alaska Red Fox (Vulpes vulpes)\": yes — the same face and moustache in both\n"), reply);
            assertTrue(reply.contains("\n3. \"red fox - red fox side stock pictures, royalty-free photos & images\": no — a different face\n"), reply);
            assertTrue(reply.endsWith("2 of 6 are the same. " + ImagesTurn.OFFER), reply);
            // the model was probed once with two squares, asked once to describe, and shown both pictures for each of the seven
            assertEquals(1, asked.stream().filter(a -> a.text().equals(Vision.TWO_QUESTION) && a.pictures() == 2).count(), asked.toString());
            assertEquals(1, asked.stream().filter(a -> a.text().startsWith(Images.DESCRIBE_REFERENCE.substring(0, 40)) && a.pictures() == 1).count());
            List<Asked> beside = asked.stream().filter(a -> a.pictures() == 2 && !a.text().equals(Vision.TWO_QUESTION)).toList();
            assertEquals(6, beside.size(), "the six pictures the model can look at; the SVG never reached it: " + asked);
            assertEquals(String.format(Locale.ROOT, Images.SAME_RULE, "person") + " The reference was described as: " + DESCRIBED + " The candidate's page is titled \"Wild Dog Breeds: Red Fox\".", beside.get(0).text());
            // the turn carried the picture as its attachment, by the kept file
            var user = Librarian.Session.resume(store, s.id).messages().stream().filter(m -> "user".equals(m.path("role").asText())).findFirst().orElseThrow();
            assertEquals("find more pictures of this person", user.path("content").asText()); assertTrue(user.path("att").path("picture").asBoolean()); assertEquals(given.id(), user.path("att").path("id").asText());
            // "keep them" afterwards keeps the candidates of that search; the pictures page says the given one is yours
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            String kept = new Librarian(store, d.chatDrive(again), Patrons.Patron.PERSON, again).say("keep 1");
            assertTrue(kept.startsWith("1 picture is on the shelves now") && kept.contains("Kept \"Wild Dog Breeds: Red Fox\" as "), kept);
            assertTrue(ImagesPage.body(store, Patrons.Patron.PERSON, Map.of()).contains("given by you"), ImagesPage.body(store, Patrons.Patron.PERSON, Map.of()));
            assertTrue(user.path("att").path("picture").asBoolean(), user.toString());
        } finally { d.stop(); }
    }

    @Test void theJapaneseWordsTheDescribingWordsTheConceptModeAndTheOnePictureFallBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        Librarian.Session session = Librarian.Session.open(store);
        Path pic = tmp.resolve("portrait.png"); Files.write(pic, portrait());
        // no picture: told to attach one
        assertEquals(ImagesTurn.ATTACH, ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find more pictures of this person"));
        assertTrue(ImagesTurn.wantsPicture("この人の写真をもっと探して") && ImagesTurn.wantsPicture("find places like this") && ImagesTurn.wantsPicture("what is this?") && !ImagesTurn.wantsPicture("find me some pictures of foxes"));
        // in Japanese, with the picture: the same person
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String ja = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "この人の写真をもっと探して");
        assertTrue(ja.contains("Looking for the same person as in your picture, because the words asked for more pictures of this person"), ja);
        assertTrue(ja.endsWith("2 of 6 are the same. " + ImagesTurn.OFFER), ja);
        // "what is this?" describes it
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String what = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "what is this?");
        assertTrue(what.startsWith(DESCRIBED), what);
        assertTrue(ImagesTurn.DESCRIBE.matcher("これは何？").matches() && ImagesTurn.DESCRIBE.matcher("who is this").matches());
        // the concept mode: like it in the kind of place, the judge asked for that aspect
        asked.clear();
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String places = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find places like this");
        assertTrue(places.contains("Looking for pictures like yours in the kind of place, because the words named an aspect to match, the kind of place. The model that sees (default, qwen3.8-27b) compared each candidate with your picture, both in one look. No likeness helper is set up; the model's reading alone."), places);
        assertTrue(asked.stream().anyMatch(a -> a.pictures() == 2 && a.text().startsWith("Two pictures follow: the first is the reference, the second a candidate. Is the second like the first in this: the kind of place? Answer: yes, no or unsure, then one sentence why, naming what the two share or do not.")), asked.toString());
        assertTrue(places.contains(" like it. " + ImagesTurn.OFFER), places);
        // "use this as the reference for pictures of Kyoto": the person's words join the search, the aspect is look and style
        asked.clear();
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String kyoto = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "use this as the reference for pictures of Kyoto");
        assertTrue(kyoto.contains("Searched for \"Kyoto\" (your words): ") && kyoto.contains("Looking for pictures like yours in look and style, showing Kyoto"), "the person's words are the search, not the description's: " + kyoto);
        assertEquals("the kind of place", ImagesTurn.aspect("places")); assertEquals("the kind of city", ImagesTurn.aspect("cities")); assertEquals("look and subject", ImagesTurn.aspect("pictures")); assertEquals("person", ImagesTurn.kind("woman")); assertEquals("place", ImagesTurn.kind("建物"));
        // a model that takes one picture at a time: the reference described in words, the candidates judged against them, and the reply says so
        Vision.TWO_IMAGES = false; asked.clear();
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String words = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find more pictures of this person");
        assertTrue(words.contains("The model that sees (default, qwen3.8-27b) takes one picture at a time, so your picture was described in words and each candidate judged against those words. No likeness helper is set up; the model's reading alone."), words);
        assertTrue(asked.stream().filter(a -> !a.text().startsWith(Images.DESCRIBE_REFERENCE.substring(0, 40))).allMatch(a -> a.pictures() == 1), "one picture a call: " + asked);
        assertTrue(asked.stream().anyMatch(a -> a.text().startsWith("Does this picture show the same person as this: " + DESCRIBED.replaceAll("\\.$", "") + "? The page it stands on is titled")), asked.toString());
        assertTrue(words.contains("\n1. \"Wild Dog Breeds: Red Fox\": yes — the page title names him and the face is a clear portrait\n"), words);
    }

    @Test void theWordsGivenAreTheSearchAndTheDescriptionsOnlyWhenNoneAre(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: "Valerios Stais" joined to the description's words made "Valerios Stais vintage man portrait mustache suit", which found stock portraits; the name alone had found two real ones
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        Images.Reference ref = Images.describeReference(eyes, portrait(), "image/png", null);
        assertEquals("man moustache suit portrait studio", ref.searchWords());
        Images.Liked named = Images.like(store, eyes, ref, Images.SAME, "person", "Valerios Stais", 6, true);
        assertEquals("Valerios Stais portrait", named.words()); assertEquals("your words, with \"portrait\" for a person", named.wordsWhy());
        Images.Liked place = Images.like(store, eyes, ref, Images.SAME, "place", "Antikythera museum case", 6, true);
        assertEquals("Antikythera museum case", place.words()); assertEquals("your words", place.wordsWhy());
        Images.Liked already = Images.like(store, eyes, ref, Images.SAME, "person", "Stais portrait 1902", 6, true);
        assertEquals("Stais portrait 1902", already.words(), "the kind's word is not added twice");
        Images.Liked none = Images.like(store, eyes, ref, Images.SAME, "person", "", 6, true);
        assertEquals("man moustache suit portrait studio", none.words()); assertEquals("the description's words, since you gave none", none.wordsWhy());
        // the chat says which words and why
        Librarian.Session session = Librarian.Session.open(store);
        Path pic = tmp.resolve("portrait.png"); Files.write(pic, portrait());
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String reply = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find 5 more pictures of this man in Athens");
        assertTrue(reply.contains("Searched for \"in Athens portrait\" (your words, with \"portrait\" for a person): "), reply);
    }

    @Test void theJudgeAdmitsTheCaptionAndTheTitleAndAnUnsureIsReportedAndNotKept(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: the seated photograph with "Archaeologist Valerios Stais" written on it was refused as "a thinner mustache" — the same man years apart, and the writing thrown away
        assertEquals("Two pictures follow: the first is the reference, the second a candidate. Does the second picture show the SAME person as the first? Compare what the pictures show — a person by face and build, an object by its shape and marks, a place by its features — allowing for age, pose, dress and the picture's quality. "
                + "A caption or a label written in the candidate, and its page's title, are evidence you may rely on too. Answer: yes when they are the same; no when they plainly are not; unsure when they could be the same person at another age or from another angle; then one sentence why, saying what you relied on: the faces, the writing in the picture, or the page's title. A likeness of style, period or setting alone is a no.",
                String.format(Locale.ROOT, Images.SAME_RULE, "person"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {   // yes to the fox's page, unsure to the wikimedia one (the same man, younger), no to the rest
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String text = messages.get(0).path("content").get(0).path("text").asText(); asked.add(new Asked(text, messages.get(0).path("content").size() - 1));
                if (text.equals(Vision.TWO_QUESTION)) return "red blue";
                if (text.startsWith(Images.DESCRIBE_REFERENCE.substring(0, 40))) return DESCRIBED + "\nSearch words: man moustache";
                if (text.contains("titled \"Wild Dog Breeds: Red Fox\"")) return "Yes — the same face, and the page's title names him.";
                if (text.contains("titled \"Alaska Red Fox")) return "Unsure — the same moustache but a fuller head of hair; it could be him younger.";
                return "No — a different face.";
            }
            @Override public String prose(ArrayNode messages, int maxTokens) { return classify(messages, maxTokens); }
            @Override public int contextWindow() { return 16384; }
        };
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode r = p.images(ImagesTest.person().put("op", "like").put("bytes", Base64.getEncoder().encodeToString(portrait())).put("content_type", "image/png").put("what", "person").put("words", "Valerios Stais").put("count", 4).put("keep", true));
        assertEquals("yes", r.path("verdicts").get(0).path("verdict").asText()); assertEquals("unsure", r.path("verdicts").get(1).path("verdict").asText(), r.toString());
        assertEquals("faces: unsure — the same moustache but a fuller head of hair; it could be him younger", r.path("verdicts").get(1).path("why").asText(), "the components lead the why");
        assertEquals(1, r.path("unsure").asInt()); assertEquals(1, r.path("kept").size(), "the unsure one is reported, not kept: " + r);
        assertTrue(r.path("summary").asText().contains("Searched for \"Valerios Stais portrait\" (your words, with \"portrait\" for a person): ") && r.path("summary").asText().contains(" 1 of 4 is the same person, 1 unsure. 1 of 1 picture is on the shelves"), r.path("summary").asText());
        assertTrue(asked.stream().anyMatch(a -> a.pictures() == 2 && a.text().contains("A caption or a label written in the candidate, and its page's title, are evidence you may rely on too.")), asked.toString());
    }

    @Test void anSvgCandidateIsSkippedBeforeAnyFetchOrCallAndCounted(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: devicon SVGs reached the model server and came back HTTP 400, four of eight in one call
        assertTrue(Images.notRaster("https://cdn.jsdelivr.net/gh/devicons/devicon@latest/icons/bash/bash-original.svg") && Images.notRaster("https://x.example/a.pdf?dl=1") && Images.notRaster("https://x.example/favicon.ico"));
        assertFalse(Images.notRaster(FOX) || Images.notRaster(GETTY) || Images.notRaster("https://x.example/img.php?id=3") || Images.notRaster("https://x.example/photo.avif"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Result r = Images.find(store, "red fox", 8);
        assertEquals(6, r.found().size()); assertEquals(1, r.skipped()); assertTrue(r.found().stream().noneMatch(f -> f.image().endsWith(".svg")));
        assertEquals("6 pictures found for \"red fox\" through Brave and SearXNG." + " 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).", r.said("red fox"));
        List<String> fetched = new ArrayList<>();
        Images.FETCHER = url -> { fetched.add(url); return new Images.Fetched(url.endsWith(".svg") ? "<svg/>".getBytes(StandardCharsets.UTF_8) : ImagesTest.square(url), url.endsWith(".svg") ? "image/svg+xml" : "image/png"); };
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        Images.Reference ref = Images.describeReference(ModelEntries.seeing(), portrait(), "image/png", null);
        Images.Liked liked = Images.like(store, ModelEntries.seeing(), ref, Images.SAME, "person", "", 8, true);
        assertTrue(fetched.stream().noneMatch(u -> u.endsWith(".svg")), "never fetched: " + fetched);
        assertTrue(asked.stream().noneMatch(a -> a.text().contains("azuredatafactory")), "never shown to the model: " + asked);
        assertEquals(6, liked.judged().size()); assertEquals(0, liked.skipped()); assertEquals(1, liked.found().skipped());
        // a candidate whose address says nothing but whose bytes are no picture is skipped after the fetch, and counted
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> List.of(new Images.Found("https://x.example/img.php?id=7", "https://x.example/p", "An icon served without an extension", "Brave", 0, 0, ""), Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0))));
        Images.FETCHER = url -> url.contains("img.php") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        liked = Images.like(store, ModelEntries.seeing(), ref, Images.SAME, "person", "", 8, true);
        assertEquals(1, liked.skipped()); assertEquals(1, liked.judged().size());
        assertEquals(" 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like).", liked.skippedSaid());
        // the protocol's judge of such a picture says why, instead of asking the model
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode judge = ImagesTest.person().put("op", "judge").put("criteria", "a fox"); judge.putArray("images").add("https://x.example/img.php?id=7");
        JsonNode j = p.images(judge);
        assertEquals("unjudged", j.path("verdicts").get(0).path("verdict").asText()); assertEquals("not a picture the model can look at (image/svg+xml)", j.path("verdicts").get(0).path("why").asText());
    }

    @Test void theCommandLineMakesOneEyesClientAndWritesNothingToTheErrorStream(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: one `images like` printed the client's context-window line nine times — a client a picture
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>(); int[] made = {0};
        Vision.DRIVES = (d, m) -> { made[0]++; return seeing(asked); };
        Path pic = tmp.resolve("stais.png"); Files.write(pic, portrait());
        PrintStream out = System.out, err = System.err; ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int rc;
        try { rc = ImagesCli.run(store, new String[]{"researchzosho", "images", "like", pic.toString(), "Valerios", "Stais", "--count", "6"}); } finally { System.setOut(out); System.setErr(err); }
        assertEquals(0, rc, bo.toString(StandardCharsets.UTF_8));
        assertEquals(1, made[0], "one client for the whole command: the probe, the description and six judgements through it");
        assertEquals("", be.toString(StandardCharsets.UTF_8), "nothing between the person and the pictures");
        assertTrue(bo.toString(StandardCharsets.UTF_8).contains("Searched for \"Valerios Stais portrait\" (your words, with \"portrait\" for a person). Looking for the same person"), bo.toString(StandardCharsets.UTF_8));
        assertTrue(asked.size() >= 8, "the probe, the description and the judgements all went through the one client: " + asked.size());
    }

    static final String DOYLE = "https://doyles.space/wp-content/uploads/Antikythera-mechanism-archaeologist-Valerios-Stais.jpg", DOYLE_PAGE = "https://doyles.space/antikythera-mechanism/", DOYLE_TITLE = "Antikythera Mechanism - Doyle's Space";
    static final String DOYLE_NO = "No — the faces are distinctly different; the reference shows a man with a receding hairline and a very thick mustache, while the candidate has a full head of hair and a thinner mustache. writing: no";

    @Test void aCandidateNamedByItsTitleAddressOrCaptionIsReadDeterministically() {
        Images.Found doyle = new Images.Found(DOYLE, DOYLE_PAGE, DOYLE_TITLE, "SearXNG", 0, 0, "");
        assertEquals(Images.BY_ADDRESS, Images.namedBy(doyle, "Valerios Stais"), "the page's title does not name him; the file name does, hyphens as spaces");
        assertEquals(Images.BY_TITLE, Images.namedBy(new Images.Found("https://brewminate.com/x.jpg", "https://brewminate.com/p", "The Forgotten Pioneer: Valerios Stais and Archaeological Work in Greece", "Brave", 0, 0, ""), "Valerios Stais"));
        assertEquals(Images.BY_ADDRESS, Images.namedBy(new Images.Found("https://x.example/img/Valerios%20Stais%20portrait.jpg", "https://x.example/p", "A page", "Brave", 0, 0, ""), "Valerios Stais"), "percent-escapes as spaces");
        assertEquals(Images.BY_ADDRESS, Images.namedBy(new Images.Found("https://x.example/img/valerios_stais_1902.jpg", "https://x.example/p", "A page", "Brave", 0, 0, ""), "Valerios Stais"), "underscores as spaces, case folded");
        assertEquals(Images.BY_CAPTION, Images.namedBy(new Images.Found("https://x.example/img/a.jpg", "https://x.example/p", "A page", "Brave", 0, 0, "", "2026-11-08T00:00:00Z", "Portrait of Valerios Stais, 1902"), "Valerios Stais"));
        assertEquals(Images.BY_TITLE, Images.namedBy(new Images.Found("https://x.example/a.jpg", "https://x.example/p", "Εμμανουήλ Βαλέριου Στάης", "Brave", 0, 0, ""), "Βαλέριου Στάης"), "Greek as given, the accents folded");
        assertEquals(Images.BY_NONE, Images.namedBy(new Images.Found("https://x.example/a.jpg", "https://x.example/p", "Εμμανουήλ Βαλέριου Στάης", "Brave", 0, 0, ""), "Valerios Stais"), "Greek text does not name a Latin name");
        assertEquals(Images.BY_NONE, Images.namedBy(new Images.Found("https://x.example/stais.jpg", "https://x.example/p", "Stais on the mechanism", "Brave", 0, 0, ""), "Valerios Stais"), "a partial name is not a match");
        assertEquals(Images.BY_NONE, Images.namedBy(doyle, "")); assertEquals(Images.BY_NONE, Images.namedBy(doyle, null));
        assertTrue(Images.looksLikeAName("Valerios Stais") && Images.looksLikeAName("Βαλέριος Στάης") && !Images.looksLikeAName("red fox") && !Images.looksLikeAName("Stais") && !Images.looksLikeAName("Stais 1902"));
        assertEquals("Valerios Stais", Images.nameOf("Valerios Stais", null)); assertNull(Images.nameOf("portrait", null));
        // the ranking table: the machine-read naming outranks the model's face reading
        Images.Found f = doyle;
        Images.Judged yes = new Images.Judged(f, Images.Verdict.YES, "the same face", "m", "c"), no = new Images.Judged(f, Images.Verdict.NO, "the faces differ", "m", "c"), unsure = new Images.Judged(f, Images.Verdict.UNSURE, "could be", "m", "c");
        assertEquals(Images.Verdict.YES, Images.ranked(yes, null, Images.BY_NONE, "Valerios Stais").verdict()); assertEquals("faces: yes — the same face", Images.ranked(yes, null, Images.BY_NONE, "Valerios Stais").why());
        assertEquals(Images.Verdict.YES, Images.ranked(no, true, Images.BY_NONE, "Valerios Stais").verdict()); assertEquals("writing in the picture names Valerios Stais; faces: no → yes — the faces differ", Images.ranked(no, true, Images.BY_NONE, "Valerios Stais").why());
        assertEquals(Images.Verdict.UNSURE, Images.ranked(no, false, Images.BY_ADDRESS, "Valerios Stais").verdict());
        assertEquals("named by its address; faces: no → unsure: the page names Valerios Stais; the faces look different to the model (the faces differ)", Images.ranked(no, false, Images.BY_ADDRESS, "Valerios Stais").why());
        assertEquals("named by its page title; faces: unsure → unsure: the page names Valerios Stais; the faces are unsure to the model (could be)", Images.ranked(unsure, null, Images.BY_TITLE, "Valerios Stais").why());
        assertEquals(Images.Verdict.NO, Images.ranked(no, false, Images.BY_NONE, "Valerios Stais").verdict()); assertEquals("faces: no — the faces differ", Images.ranked(no, false, Images.BY_NONE, "Valerios Stais").why());
        assertEquals(Images.Verdict.NO, Images.ranked(no, null, Images.BY_ADDRESS, null).verdict(), "no name, no naming");
    }

    /** The seeing drive of the live rerun: yes to the fox's page by its face, Doyle's answer to the seated photograph, no to the rest; names both colours; describes. */
    static Researcher.Drive doylesEyes(List<Asked> asked) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                JsonNode content = messages.get(0).path("content"); String text = content.get(0).path("text").asText(); asked.add(new Asked(text, content.size() - 1));
                if (text.equals(Vision.TWO_QUESTION)) return "red blue";
                if (text.startsWith(Images.DESCRIBE_REFERENCE.substring(0, 40)) || text.startsWith(Images.DESCRIBE.substring(0, 40))) return DESCRIBED + "\nSearch words: man moustache";
                if (text.contains("titled \"Wild Dog Breeds: Red Fox\"")) return content.size() == 3 ? "Yes — the same face and moustache in both. writing: no" : "Yes — the page title names him and the face is a clear portrait.";
                if (text.contains("titled \"" + DOYLE_TITLE + "\"")) return DOYLE_NO;
                return "No — a different face. writing: no";
            }
            @Override public String prose(ArrayNode messages, int maxTokens) { return classify(messages, maxTokens); }
            @Override public int contextWindow() { return 16384; }
        };
    }

    @Test void theSeatedPhotographNamedInItsAddressIsUnsureNotNoListedAndNotKept(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: the 27B refused the seated photograph with "Archaeologist Valerios Stais" written on it and named in its file, for a thinner mustache
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> doylesEyes(asked);
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> List.of(Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0), new Images.Found(DOYLE, DOYLE_PAGE, DOYLE_TITLE, "Brave", 1200, 800, "https://doyles.space/thumb.jpg"), Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(1))));
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode r = p.images(ImagesTest.person().put("op", "like").put("bytes", Base64.getEncoder().encodeToString(portrait())).put("content_type", "image/png").put("what", "person").put("words", "Valerios Stais").put("count", 6).put("keep", true));
        JsonNode doyle = r.path("verdicts").get(1);
        assertEquals(DOYLE, doyle.path("image").asText()); assertEquals("unsure", doyle.path("verdict").asText(), r.toString());
        assertTrue(doyle.path("why").asText().startsWith("named by its address; faces: no → unsure: the page names Valerios Stais; the faces look different to the model (the faces are distinctly different; the reference shows a man with a receding hairline"), doyle.path("why").asText());
        assertEquals("yes", r.path("verdicts").get(0).path("verdict").asText()); assertEquals("faces: yes — the same face and moustache in both", r.path("verdicts").get(0).path("why").asText());
        assertEquals("no", r.path("verdicts").get(2).path("verdict").asText()); assertEquals("faces: no — a different face", r.path("verdicts").get(2).path("why").asText());
        assertEquals(1, r.path("unsure").asInt()); assertEquals(1, r.path("kept").size(), "the unsure one is not kept: " + r.path("kept"));
        assertEquals(FOX, r.path("kept").get(0).path("image").asText());
        // the judge was told where the candidate names him and asked about writing in the picture
        Asked q = asked.stream().filter(a -> a.text().contains("titled \"" + DOYLE_TITLE + "\"")).findFirst().orElseThrow();
        assertEquals(2, q.pictures());
        assertTrue(q.text().contains(" The candidate's address names Valerios Stais. After your verdict, say \"writing: yes\" if writing in the picture itself names Valerios Stais, else \"writing: no\"."), q.text());
        // the chat lists it with its small picture and the components, counts it, and keeps only the yes
        Librarian.Session session = Librarian.Session.open(store);
        Path pic = tmp.resolve("portrait.png"); Files.write(pic, portrait());
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String reply = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find more pictures of this person Valerios Stais");
        assertTrue(reply.contains("\n2. \"" + DOYLE_TITLE + "\": unsure — named by its address; faces: no → unsure: the page names Valerios Stais; the faces look different to the model (") && reply.contains("\n   " + DOYLE_PAGE + "\n   ![" + DOYLE_TITLE + "](https://doyles.space/thumb.jpg)\n"), reply);
        assertTrue(reply.endsWith("1 of 3 is the same, 1 unsure. " + ImagesTurn.OFFER), reply);
        String kept = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "keep them");
        assertTrue(kept.contains("Kept \"" + DOYLE_TITLE + "\"") , "keep them keeps what the person chooses, the unsure one too: " + kept);
    }

    @Test void inASeededChainPassANamedCandidateIsUnsureAndAFaceMatchIsKept(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> doylesEyes(asked);
        List<Images.Found> brave = Images.parseBrave(ImagesTest.fixture("brave-images.json"));
        // the fox's page seeds; the seated photograph is named in its address; a second page with the fox's title (other bytes, other page) is a face match
        Images.Found second = new Images.Found("https://a-z-animals.com/media/2022/fox-second.jpg", FOX_PAGE + "second/", "Wild Dog Breeds: Red Fox", "Brave", 800, 600, "");
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> List.of(brave.get(0), new Images.Found(DOYLE, DOYLE_PAGE, DOYLE_TITLE, "Brave", 1200, 800, ""), second)));
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive(); drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        RunTrace trace = RunTrace.open(store, "J-named");
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        researcher.trace(trace);
        var r = researcher.run(new Researcher.Ask("Who was Valerios Stais?", "depth", 60, List.of("who was Valerios Stais?")), "");
        // the fox's page seeds the pass by its face and title; the seated photograph, named in its address, is unsure; the fox's second page is a face match and is kept
        assertTrue(log.contains("pictures: \"Valerios Stais\" searched because the question names Valerios Stais: 3 found through Brave, 3 judged against \"Valerios Stais\" (2 of them beside the picture kept as " + Images.onShelves(store).stream().filter(s -> s.page().equals(FOX_PAGE)).findFirst().orElseThrow().id() + "), 2 kept"), log.toString());
        assertEquals(2, Images.onShelves(store).size(), "the unsure one is not kept: " + Images.onShelves(store));
        assertTrue(Images.onShelves(store).stream().noneMatch(s -> s.page().equals(DOYLE_PAGE)));
        // every judged candidate on the trace: the seed (yes, kept), the named unsure (not kept), the face match (yes, kept)
        String traced = Files.readString(RunTrace.fileFor(store, "J-named"));
        List<String> lines = traced.lines().filter(x -> x.contains("\"capture\":\"picture\"")).toList();
        assertEquals(3, lines.size(), "one line a judged candidate: " + traced);
        assertTrue(lines.get(0).contains("\"verdict\":\"yes\"") && lines.get(0).contains("\"beside\":\"none\"") && lines.get(0).contains("\"kept\":\"2026-") , lines.get(0));
        assertTrue(lines.get(1).contains("\"verdict\":\"unsure\"") && lines.get(1).contains("\"named_by\":\"address\"") && lines.get(1).contains("\"why\":\"named by its address; faces: no → unsure: the page names Valerios Stais; the faces look different to the model") && lines.get(1).contains("\"kept\":\"\"") && lines.get(1).contains("\"beside\":\"2026-"), lines.get(1));
        assertTrue(lines.get(2).contains("\"verdict\":\"yes\"") && lines.get(2).contains("\"named_by\":\"none\"") && lines.get(2).contains("\"kept\":\"2026-"), lines.get(2));
        assertFalse(traced.contains("picture_named"));
        assertTrue(r.answer().contains("(a picture, kept as ") && !r.answer().contains(DOYLE_PAGE), r.answer());
    }

    static final String BREWMINATE_SAID = "Based on the visual evidence, the two pictures show the same person. The man in the candidate image has the same distinctive features — a receding hairline, a thick dark mustache and a formal collar — as the reference.";

    @Test void aVerdictSaidInASentenceIsReadAndAnUnreadOneIsAskedOnceMore() {
        assertEquals(Images.Verdict.YES, Images.readVerdict(BREWMINATE_SAID), "a yes said in a sentence");
        assertEquals(Images.Verdict.NO, Images.readVerdict("The two men are not the same person; the candidate has a different face."), "a no-phrase, read first and taken out, so 'the same person' inside it does not make it both");
        assertEquals(Images.Verdict.UNSURE, Images.readVerdict("It could be him at a younger age; I cannot tell from these pictures."));
        assertNull(Images.readVerdict("The candidate shows the same person as the reference, although the faces look different and it may be another man entirely."), "a yes- and a no-phrase: not read");
        assertNull(Images.readVerdict("A man in a dark suit stands in a studio."), "no phrase at all: not read");
        Images.Found f = new Images.Found(FOX, FOX_PAGE, "Wild Dog Breeds: Red Fox", "Brave", 0, 0, "");
        Images.Judged j = Images.verdict(f, "c", "m", BREWMINATE_SAID);
        assertEquals(Images.Verdict.YES, j.verdict()); assertTrue(j.why().startsWith("Based on the visual evidence, the two pictures show the same person."), j.why());
        assertEquals(Images.Verdict.UNJUDGED, Images.verdict(f, "c", "m", "A man in a dark suit stands in a studio.").verdict());
    }

    @Test void theTwoLayersOfReadingThroughTheJudgeWithStandIns(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        Images.Reference ref = new Images.Reference(portrait(), "image/png", DESCRIBED, "man moustache", null);
        Images.Found brew = new Images.Found("https://brewminate.com/wp-content/uploads/stais.jpg", "https://brewminate.com/the-forgotten-pioneer/", "The Forgotten Pioneer: Valerios Stais and Archaeological Work in Greece", "Brave", 0, 0, "");
        // the Brewminate sentence verbatim: a yes without a second call
        List<String> calls = new ArrayList<>();
        Vision.TWO_IMAGES = true;
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { String t = messages.get(0).path("content").get(0).path("text").asText(); calls.add(t); return BREWMINATE_SAID; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Judged j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", brew, ImagesTest.square(brew.image()), "image/png");
        assertEquals(Images.Verdict.YES, j.verdict()); assertEquals(1, calls.size(), "no second call for a verdict said in a sentence");
        assertTrue(j.why().startsWith("faces: yes — Based on the visual evidence, the two pictures show the same person."), j.why());
        // gibberish once: asked once more, told to begin with the word, and the second answer read
        calls.clear();
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { String t = messages.get(0).path("content").get(0).path("text").asText(); calls.add(t); return t.endsWith(Images.ONE_WORD) ? "No — a different jaw. writing: no" : "A man in a dark suit stands in a studio, looking left."; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Found plain = new Images.Found(GETTY, "https://www.gettyimages.com/photos/red-fox-side", "red fox side", "Brave", 0, 0, "");
        j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", plain, ImagesTest.square(plain.image()), "image/png");
        assertEquals(2, calls.size(), calls.toString()); assertTrue(calls.get(1).endsWith(Images.ONE_WORD) && calls.get(1).startsWith(calls.get(0)), calls.get(1));
        assertEquals(Images.Verdict.NO, j.verdict()); assertEquals("faces: no — a different jaw", j.why());
        // gibberish twice, not named: not judged, with the words; gibberish twice, named by its page title: unsure, named
        calls.clear();
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { calls.add(messages.get(0).path("content").get(0).path("text").asText()); return "A man in a dark suit stands in a studio, looking left."; }
            @Override public int contextWindow() { return 16384; }
        };
        j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", plain, ImagesTest.square(plain.image()), "image/png");
        assertEquals(2, calls.size()); assertEquals(Images.Verdict.UNJUDGED, j.verdict()); assertEquals("the model gave no verdict twice: \"A man in a dark suit stands in a studio, looking left.\"", j.why());
        assertEquals("\"red fox side\": not judged — the model gave no verdict twice: \"A man in a dark suit stands in a studio, looking left.\"", j.said());
        calls.clear();
        j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", brew, ImagesTest.square(brew.image()), "image/png");
        assertEquals(2, calls.size()); assertEquals(Images.Verdict.UNSURE, j.verdict());
        assertEquals("named by its page title; faces: not read → unsure: the page names Valerios Stais; the model gave no verdict (the model gave no verdict twice: \"A man in a dark suit stands in a studio, looking left.\")", j.why());
        // the single-picture judge asks once more the same way
        calls.clear();
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { String t = messages.get(0).path("content").get(0).path("text").asText(); calls.add(t); return t.endsWith(Images.ONE_WORD) ? "Yes. A fox." : "There is an animal."; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Judged one = Images.judge(eyes, "a fox", plain, ImagesTest.square(plain.image()), "image/png", true);
        assertEquals(Images.Verdict.YES, one.verdict()); assertEquals(2, calls.size());
    }

    @Test void theReferenceFoundOnAnotherPageIsSaidAsAnotherPlaceItAppears(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: "not judged — this is the reference picture itself", for the same bytes on an Italian page
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        Images.Found italian = new Images.Found("https://www.storicang.it/medio/2023/stais-ritratto.jpg", "https://www.storicang.it/a/scoperta-la-macchina", "Scoperta la macchina o meccanismo di Anticitera", "Brave", 0, 0, "");
        Images.FETCHER = url -> new Images.Fetched(url.contains("stais-ritratto") ? portrait() : ImagesTest.square(url), "image/png");   // the Italian page carries the reference's own bytes
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> List.of(Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0), italian, Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(1))));
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode r = p.images(ImagesTest.person().put("op", "like").put("bytes", Base64.getEncoder().encodeToString(portrait())).put("content_type", "image/png").put("what", "person").put("words", "Valerios Stais").put("count", 6));
        assertEquals(2, r.path("verdicts").size(), "the reference's own bytes are not a verdict: " + r); assertEquals(1, r.path("same_as_reference").size()); assertEquals(italian.image(), r.path("same_as_reference").get(0).path("image").asText());
        assertTrue(r.path("summary").asText().endsWith("1 of 2 is the same person. Your picture appears also on storicang.it (https://www.storicang.it/a/scoperta-la-macchina)."), r.path("summary").asText());
        assertTrue(r.path("verdicts").toString().indexOf("reference picture itself") < 0);
        // the chat says it as a line of its own, after the tally
        Librarian.Session session = Librarian.Session.open(store);
        Path pic = tmp.resolve("portrait.png"); Files.write(pic, portrait());
        session.att = M.createObjectNode().put("name", "portrait.png").put("path", pic.toString());
        String reply = ImagesTurn.reply(store, session, Patrons.Patron.PERSON, "find more pictures of this person Valerios Stais");
        assertTrue(reply.endsWith("1 of 2 is the same. Your picture appears also on storicang.it (https://www.storicang.it/a/scoperta-la-macchina). " + ImagesTurn.OFFER), reply);
        assertFalse(reply.contains("not judged"), reply);
    }

    @Test void aCaptionNamingTellsTheJudgeButDoesNotOutrankItAndAShelfEntryNamedOnlyByOurWordsIsNotNamed(@TempDir Path tmp) throws Exception {
        // J-0008 (2026-11-08): a coin from Lykosoura, whose Commons description carries the name of the man who dug there, was ranked unsure against his portrait
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.TWO_IMAGES = true;
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { asked.add(new Asked(messages.get(0).path("content").get(0).path("text").asText(), messages.get(0).path("content").size() - 1)); return "No — the first image is a portrait of a man, while the second is an ancient coin depicting a group of figures, so they are plainly not the same person. writing: no"; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Reference ref = new Images.Reference(portrait(), "image/png", DESCRIBED, "man moustache", null);
        Images.Found coin = new Images.Found("https://upload.wikimedia.org/wikipedia/commons/3/35/Lykosuran_group_coin.png", "https://commons.wikimedia.org/wiki/File:Lykosuran_group_coin.png", "Lykosuran group coin", "SearXNG (wikicommons.images)", 609, 605, "", "2026-11-08T05:30:33Z", "Bronze coin of Megalopolis showing the Lykosouran group of Damophon, from the excavations of Valerios Stais at Lykosoura.");
        assertEquals(Images.BY_CAPTION, Images.namedBy(coin, "Valerios Stais"));
        Images.Judged j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", coin, ImagesTest.square(coin.image()), "image/png");
        assertEquals(Images.Verdict.NO, j.verdict(), "a caption names everyone connected with a file: it does not outrank the face reading");
        assertTrue(j.why().startsWith("its caption names Valerios Stais; faces: no — the first image is a portrait of a man"), j.why());
        assertTrue(asked.get(0).text().contains(" The candidate's caption names Valerios Stais."), "the judge is told all the same: " + asked.get(0).text());
        // the title and the address still outrank: the table as before
        Images.Judged no = new Images.Judged(coin, Images.Verdict.NO, "plainly not", "m", "c");
        assertEquals(Images.Verdict.NO, Images.ranked(no, false, Images.BY_CAPTION, "Valerios Stais").verdict()); assertEquals(Images.Verdict.UNSURE, Images.ranked(no, false, Images.BY_TITLE, "Valerios Stais").verdict()); assertEquals(Images.Verdict.UNSURE, Images.ranked(no, false, Images.BY_ADDRESS, "Valerios Stais").verdict());
        assertEquals(Images.Verdict.UNJUDGED, Images.ranked(new Images.Judged(coin, Images.Verdict.UNJUDGED, "x", "m", "c"), null, Images.BY_CAPTION, "Valerios Stais").verdict(), "an unread verdict with a caption naming stays not judged");
        // a kept picture carries the engine's caption, and only that, as its caption: the library's description, search words and criteria are not the picture's text
        Images.Kept k = Images.keep(store, coin, "Athens Valerios Stais: the coin the search for him brought", "", new Images.Judged(coin, Images.Verdict.YES, "the four horsemen", "m", "Athens, in connection with Valerios Stais"));
        Images.Shelved s = Images.onShelves(store).get(0);
        assertEquals(coin.caption(), s.caption()); assertEquals(coin.caption(), s.asFound().caption()); assertEquals(Images.BY_CAPTION, Images.namedBy(s.asFound(), "Valerios Stais"), "the engine's caption, kept at keep time");
        Images.Found plainCoin = new Images.Found(coin.image(), coin.page(), coin.title(), coin.source(), 609, 605, "");   // the same picture kept with no engine caption
        LibraryStore other = new LibraryStore(tmp.resolve("other")); other.init();
        Images.keep(other, plainCoin, "Athens Valerios Stais: the coin the search for him brought", "", new Images.Judged(plainCoin, Images.Verdict.YES, "the four horsemen", "m", "Athens, in connection with Valerios Stais"));
        Images.Shelved t = Images.onShelves(other).get(0);
        assertTrue(t.description().contains("Valerios Stais") && t.criteria().contains("Valerios Stais"), "our words carry the name");
        assertEquals("", t.asFound().caption()); assertEquals(Images.BY_NONE, Images.namedBy(t.asFound(), "Valerios Stais"), "and they are not the picture's text: not named");
        assertTrue(Images.shelves(other, "Valerios Stais", 6).size() == 1, "the shelf search still brings it, by our words, and the judge sees it");
    }

    @Test void aVerdictIsNeverPrintedWithNothingAfterIt(@TempDir Path tmp) throws Exception {
        // 2026-11-08, live: Doyle's line ended in "faces: yes — " — the model answered "yes writing: yes" and the strip of the writing fragment left nothing
        Images.Found f = new Images.Found(DOYLE, DOYLE_PAGE, DOYLE_TITLE, "Brave", 0, 0, "");
        Images.Judged bareYes = Images.verdict(f, "c", "m", "yes writing: yes"), bareNo = Images.verdict(f, "c", "m", "No. writing: no"), bareUnsure = Images.verdict(f, "c", "m", "Unsure"), word = Images.verdict(f, "c", "m", "Yes.");
        assertEquals("writing: yes", bareYes.why()); assertEquals(Images.NO_REASON, word.why(), "a bare word gets a reason of its own");
        assertEquals("faces: yes — writing in the picture names Valerios Stais", Images.ranked(bareYes, true, Images.BY_ADDRESS, "Valerios Stais").why());
        assertEquals("faces: yes — the faces, by the model", Images.ranked(word, null, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("faces: no — the faces, by the model", Images.ranked(bareNo, false, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("faces: unsure — the faces, by the model", Images.ranked(bareUnsure, null, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("writing in the picture names Valerios Stais; faces: no → yes — writing in the picture names Valerios Stais", Images.ranked(bareNo, true, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("named by its address; faces: no → unsure: the page names Valerios Stais; the faces look different to the model (the faces, by the model)", Images.ranked(bareNo, false, Images.BY_ADDRESS, "Valerios Stais").why());
        assertEquals("its caption names Valerios Stais; faces: no — the faces, by the model", Images.ranked(bareNo, false, Images.BY_CAPTION, "Valerios Stais").why());
        // through the two-picture judge with the live answer, every surface's line has a reason after the dash
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ModelEntries.Entry eyes = ImagesTest.seeingDefault();
        Vision.TWO_IMAGES = true;
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "yes writing: yes"; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Reference ref = new Images.Reference(portrait(), "image/png", DESCRIBED, "man moustache", null);
        Images.Judged j = Images.judgeAgainst(eyes, ref, Images.SAME, "person", "Valerios Stais", f, ImagesTest.square(f.image()), "image/png");
        assertEquals(Images.Verdict.YES, j.verdict()); assertEquals("faces: yes — writing in the picture names Valerios Stais", j.why());
        assertEquals("\"" + DOYLE_TITLE + "\": yes — faces: yes — writing in the picture names Valerios Stais", j.said());
        assertFalse(j.said().endsWith("— ") || j.json(M).path("why").asText().isBlank());
        Vision.DRIVES = (d, m) -> new Researcher.Drive() {   // the word alone, to the single-picture judge
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError(); }
            @Override public String classify(ArrayNode messages, int maxTokens) { return "Yes."; }
            @Override public int contextWindow() { return 16384; }
        };
        Images.Judged plain = Images.judge(eyes, "a fox", f, ImagesTest.square(f.image()), "image/png", true);
        assertEquals("\"" + DOYLE_TITLE + "\": yes — " + Images.NO_REASON, plain.said(), "the single-picture judge too");
    }

    @Test void aPhraseOfTitlesPeriodsOrPeoplesAloneIsNotAFindingsNameAndAKeptThingStaysOutOfAPersonPass(@TempDir Path tmp) throws Exception {
        // J-0009 (2026-11-08): "Ephor General" and "Hellenistic Greek" were searched and judged, ten calls for nothing
        assertTrue(RunPictures.onlyRolesPeriodsOrPeoples("Ephor General") && RunPictures.onlyRolesPeriodsOrPeoples("Hellenistic Greek") && RunPictures.onlyRolesPeriodsOrPeoples("Classical Archaeology") && RunPictures.onlyRolesPeriodsOrPeoples("Late Bronze Age"));
        assertFalse(RunPictures.onlyRolesPeriodsOrPeoples("Spyridon Stais") || RunPictures.onlyRolesPeriodsOrPeoples("National Archaeological Museum") || RunPictures.onlyRolesPeriodsOrPeoples("Antikythera Mechanism") || RunPictures.onlyRolesPeriodsOrPeoples("Alexander Jones") || RunPictures.onlyRolesPeriodsOrPeoples("Keeper of the Antiquities"));
        String evidence = "SUB-QUESTION: who\nEVIDENCE:\n- The Ephor General, a Hellenistic Greek scholar of Classical Archaeology, Spyridon Stais, wrote to Alexander Jones at the National Archaeological Museum about the Antikythera Mechanism — source: https://example.org/a\n"
                + "- The Ephor General wrote again; the Hellenistic Greek world and Classical Archaeology were his field — source: https://example.org/b\n";
        List<String> names = RunPictures.entities("Who was Valerios Stais?", evidence, 8);
        assertEquals("Valerios Stais", names.get(0), "the question's name first");
        assertEquals(Set.of("Valerios Stais", "Spyridon Stais", "Alexander Jones", "National Archaeological Museum", "Antikythera Mechanism"), new HashSet<>(names), "the titles, the period and the field go; the people, the museum and the mechanism stay: " + names);
        assertEquals(List.of("Ephor General"), RunPictures.entities("Who was the Ephor General?", "- The Ephor General sat — source: https://example.org/a\n"), "the question's own names stay untouched");
        // a shelf picture kept as a picture of a thing (the fragments, judged yes against "Antikythera, in connection with Valerios Stais") is no candidate for a person pass
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> doylesEyes(asked);
        Images.Found fragments = new Images.Found("https://x.example/fragments.jpg", "https://x.example/mechanism", "Fragment of the Antikythera Mechanism", "Brave", 0, 0, "");
        Images.Kept keptThing = Images.keep(store, fragments, "the three fragments in their case", "", new Images.Judged(fragments, Images.Verdict.YES, "the bronze fragments in a case", "m", "Antikythera, in connection with Valerios Stais"));
        Images.Found portraitFound = Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0);
        Images.Kept portraitKept = Images.keep(store, portraitFound, "", "", new Images.Judged(portraitFound, Images.Verdict.YES, "the face", "m", "Valerios Stais"));
        List<String> log = new ArrayList<>(); List<ObjectNode> events = new ArrayList<>();
        RunPictures pictures = new RunPictures(store, log::add, (t, o) -> { if (t.equals("picture")) events.add(o); });
        RunPictures.Pass pass = pictures.new Pass("Valerios Stais");
        pass.reference(pictures.referenceFor(portraitKept.id()));
        Images.Shelved thing = Images.entryFor(store, keptThing.url());
        List<RunPictures.Looked> looked = pictures.judgeAll("Valerios Stais", List.of(thing.asFound(), Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(1)), pass, l -> { });
        assertEquals("kept as a picture of Antikythera, not a person; not judged for Valerios Stais", looked.get(0).skipped());
        assertNull(looked.get(0).judged()); assertNotNull(looked.get(1).judged(), "the web candidate is judged beside the portrait");
        assertEquals(1, asked.stream().filter(a -> a.pictures() == 2 && a.text().contains("SAME person")).count(), "one judge call with two pictures, for the web candidate, none for the kept thing: " + asked);
        assertTrue(events.stream().anyMatch(o -> o.path("skipped").asText("").startsWith("kept as a picture of Antikythera") && o.path("image").asText().equals(thing.url())), events.toString());
        assertEquals("Antikythera", pictures.keptAsAThing(thing.asFound(), "Valerios Stais")); assertNull(pictures.keptAsAThing(Images.entryFor(store, portraitKept.url()).asFound(), "Valerios Stais"), "a kept portrait of the person is a candidate");
    }

    @Test void theProtocolTakesAReferenceForLikeAndForJudge(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        LibraryProtocol p = new LibraryProtocol(store);
        // the reference as bytes: kept as the person's own, described, the same person looked for
        ObjectNode a = ImagesTest.person().put("op", "like").put("bytes", Base64.getEncoder().encodeToString(portrait())).put("content_type", "image/png").put("name", "stais.png").put("what", "person").put("count", 4);
        JsonNode r = p.images(a);
        assertEquals("same", r.path("mode").asText()); assertTrue(r.path("two_pictures").asBoolean()); assertEquals("man moustache suit portrait studio", r.path("words").asText());
        String refId = r.path("reference").path("id").asText();
        assertTrue(refId.startsWith("2026-") || refId.matches("\\d{4}-.*"), r.toString()); assertEquals(DESCRIBED, r.path("reference").path("description").asText()); assertEquals("man moustache suit portrait studio", r.path("reference").path("search_words").asText());
        assertEquals(4, r.path("count").asInt()); assertEquals(4, r.path("verdicts").size());
        assertEquals("yes", r.path("verdicts").get(0).path("verdict").asText(), r.toString()); assertEquals("the same person as the picture kept as " + refId, r.path("verdicts").get(0).path("criteria").asText());
        assertEquals("The reference is kept as " + refId + ". Searched for \"man moustache suit portrait studio\" (the description's words, since you gave none): 4 pictures found for \"man moustache suit portrait studio\" through Brave and SearXNG. 1 candidate was skipped as not a picture the model can look at (an SVG, a PDF and the like). The model that sees (default, qwen3.8-27b) compared each candidate with your picture, both in one look. No likeness helper is set up; the model's reading alone. 2 of 4 are the same person.", r.path("summary").asText());
        assertEquals("the words asked for more pictures of this person, so the same one is looked for", r.path("mode_why").asText());
        // the reference by its entry, the concept mode, with keep: the yeses kept
        asked.clear();
        r = p.images(ImagesTest.person().put("op", "like").put("reference", refId).put("mode", "concept").put("what", "the kind of portrait").put("words", "studio").put("count", 3).put("keep", true));
        assertEquals("concept", r.path("mode").asText()); assertEquals("the words named an aspect to match, the kind of portrait", r.path("mode_why").asText()); assertEquals("studio", r.path("words").asText(), "the person's word is the search; the description's words only when none is given"); assertEquals("your words", r.path("words_why").asText());
        assertEquals("like the picture kept as " + refId + " in the kind of portrait", r.path("verdicts").get(0).path("criteria").asText());
        assertEquals(2, r.path("kept").size(), r.toString());
        assertTrue(asked.stream().anyMatch(x -> x.pictures() == 2 && x.text().contains("Is the second like the first in this: the kind of portrait?")), asked.toString());
        // judge with a reference: each picture beside it
        ObjectNode judge = ImagesTest.person().put("op", "judge").put("criteria", "person").put("reference", refId);   // the found objects of a find, as a client passes them, each with its page's title
        ArrayNode imgs = judge.putArray("images"); imgs.addObject().put("image", FOX).put("page", FOX_PAGE).put("title", "Wild Dog Breeds: Red Fox"); imgs.addObject().put("image", GETTY).put("title", "red fox side");
        r = p.images(judge);
        assertTrue(r.path("two_pictures").asBoolean()); assertEquals(refId, r.path("reference").path("id").asText());
        assertEquals("yes", r.path("verdicts").get(0).path("verdict").asText()); assertEquals("no", r.path("verdicts").get(1).path("verdict").asText());
        assertEquals("the same person as the picture kept as " + refId, r.path("verdicts").get(0).path("criteria").asText());
        // what is missing for concept, and a wrong mode, are said
        assertTrue(assertThrows(ProtocolError.class, () -> p.images(ImagesTest.person().put("op", "like").put("reference", refId).put("mode", "concept"))).getMessage().contains("concept needs the aspect to match"));
        assertTrue(assertThrows(ProtocolError.class, () -> p.images(ImagesTest.person().put("op", "like").put("reference", refId).put("mode", "alike"))).getMessage().contains("mode is same"));
        assertTrue(assertThrows(ProtocolError.class, () -> p.images(ImagesTest.person().put("op", "like"))).getMessage().contains("like needs the reference"));
        // a reader may not keep the picture given, but may ask with it
        Patrons.set(store, "did:key:zReader", "A reader", Patrons.Level.read);
        ObjectNode reader = M.createObjectNode(); reader.putObject("patron").put("did", "did:key:zReader").put("name", "A reader").put("runtime", "test");
        r = p.images(reader.put("op", "like").put("bytes", Base64.getEncoder().encodeToString(Vision.square(1, 2, 3))).put("content_type", "image/png").put("what", "thing").put("count", 2));
        assertEquals("", r.path("reference").path("id").asText(), "not kept"); assertTrue(r.path("summary").asText().startsWith("The reference was read."), r.path("summary").asText());
    }

    @Test void theCommandLineLikesAPicture(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        Vision.DRIVES = (d, m) -> seeing(new ArrayList<>());
        Path pic = tmp.resolve("stais.png"); Files.write(pic, portrait());
        PrintStream out = System.out; ByteArrayOutputStream bo = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        int rc;
        try { rc = ImagesCli.run(store, new String[]{"researchzosho", "images", "like", pic.toString(), "--count", "3"}); } finally { System.setOut(out); }
        String printed = bo.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, printed);
        assertTrue(printed.startsWith("The reference is kept as 2026-") || printed.matches("(?s)The reference is kept as \\d{4}-.*"), printed);
        assertTrue(printed.contains(", shown at /images/") && printed.contains(" Described as: " + DESCRIBED + "\n"), printed);
        assertTrue(printed.contains("Searched for \"man moustache suit portrait studio\" (the description's words, since you gave none). Looking for the same person, because the words asked for more pictures of this person, so the same one is looked for. The model that sees (default, qwen3.8-27b) compared each candidate with your picture, both in one look. No likeness helper is set up; the model's reading alone. 1 skipped as not pictures the model can look at.\n"), printed);
        assertTrue(printed.contains("1. \"Wild Dog Breeds: Red Fox\": yes — the same face and moustache in both\n   page: " + FOX_PAGE + "\n   image: " + FOX + "\n"), printed);
        assertTrue(printed.contains("2 of 3 are the same person."), printed);
        // judge beside a reference, from the command line
        String refId = Images.onShelves(store).get(0).id();
        bo.reset(); System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        try { rc = ImagesCli.run(store, new String[]{"researchzosho", "images", "judge", "person", "--like", refId, GETTY}); } finally { System.setOut(out); }
        printed = bo.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, printed);
        assertTrue(printed.startsWith("Judged beside the reference kept as " + refId + ", both pictures in one look.\n"), printed);
        assertTrue(printed.contains(": no — a different face"), printed);
    }

    @Test void inARunAConfirmedPortraitBecomesTheReferenceForTheRestAndAWorkerMayNameOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, m) -> seeing(asked);
        // a portrait kept before the run, so the worker can name it as its reference
        Images.Kept earlier = Images.keep(store, Images.parseBrave(ImagesTest.fixture("brave-images.json")).get(0), "", "");
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                List<String> n = names(tools); offered.add(String.join(",", n)); histories.add(history.deepCopy());
                if (n.equals(List.of("done")) || n.contains("write_section")) return super.chat(history, tools, maxTokens, toolChoice);
                return switch (assistantTurns(history) + 1) {
                    case 1 -> call(RunPictures.SEARCH, M.createObjectNode().put("query", "Valerios Stais portrait"));
                    case 2 -> { ObjectNode a = M.createObjectNode().put("criteria", "Valerios Stais").put("reference", earlier.id()); a.putArray("images").add(GETTY); yield call(RunPictures.JUDGE, a); }
                    default -> call("done", M.createObjectNode().put("summary", "judged beside the portrait"));
                };
            }
        };
        drive.criticWantsMore = false;
        List<String> log = new ArrayList<>();
        Researcher researcher = new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store);
        var r = researcher.run(new Researcher.Ask("Who was Valerios Stais?", "depth", 60, List.of("who was Valerios Stais?")), "");
        // the question's own subject, before the workers: the first yes (the fox's page, whose title names him) is the portrait, and the five after it are judged beside it
        assertTrue(log.contains("pictures: \"Valerios Stais\" searched because the question names Valerios Stais: 6 found through Brave and SearXNG, 6 judged against \"Valerios Stais\" (5 of them beside the picture kept as " + earlier.id() + "), 2 kept"), log.toString());
        List<Asked> beside = asked.stream().filter(a -> a.pictures() == 2 && !a.text().equals(Vision.TWO_QUESTION)).toList();
        assertTrue(beside.size() >= 5 && beside.get(0).text().contains("Does the second picture show the SAME person (Valerios Stais) as the first"), asked.toString());
        Images.Shelved second = Images.onShelves(store).stream().filter(s -> !s.id().equals(earlier.id())).findFirst().orElseThrow();
        assertEquals("Alaska Red Fox (Vulpes vulpes)", second.title());
        assertTrue(r.answer().contains("] Alaska Red Fox (Vulpes vulpes) — https://upload.wikimedia.org/") && r.answer().contains("(a picture, kept as " + second.id() + ", from the page https://commons.wikimedia.org/wiki/File:Alaska_Red_Fox_(Vulpes_vulpes).jpg; judged \"the same person (Valerios Stais) as the picture kept as " + earlier.id() + "\": yes — faces: yes — the same face and moustache in both)"), r.answer());
        assertTrue(r.evidence().contains("- A picture judged to show the same person (Valerios Stais) as the picture kept as " + earlier.id() + ": \"Alaska Red Fox (Vulpes vulpes)\""), r.evidence());
        // the worker's judge beside the reference it named
        String judge = RunPicturesTest.toolAnswer(drive, RunPictures.JUDGE);
        assertTrue(judge.startsWith("Judged against \"Valerios Stais\" by default (qwen3.8-27b) (1 of them beside the picture kept as " + earlier.id() + "): 0 of 1 show it."), judge);
        assertTrue(judge.contains("\n1. \"" ) || judge.contains("\n1. https://"), judge);
        // the name reader: a person, not an organisation or an event
        assertTrue(RunPictures.isPersonName("Valerios Stais") && RunPictures.isPersonName("Jean de Florette") && !RunPictures.isPersonName("National Archaeological Museum") && !RunPictures.isPersonName("Antikythera") && !RunPictures.isPersonName("Battle of Hastings") && !RunPictures.isPersonName("Greek Ministry"));
        assertEquals("Valerios Stais", RunPictures.entityOf("Valerios Stais, in connection with the wreck")); assertEquals("Valerios Stais", RunPictures.entityOf("Valerios Stais (against the picture kept as x)"));
        String traced = r.stats() == null ? "" : r.stats().toString();
        assertNotNull(traced);
    }
}
