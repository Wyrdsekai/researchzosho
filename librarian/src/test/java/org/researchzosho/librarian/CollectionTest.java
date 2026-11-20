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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A collection of pictures as a job (0.8.0), over stand-ins for the places, the fetch, the model that chooses the searches and the model
 * that sees: what passed is kept and nothing else, up to the number asked; the size and the shape are measured from the bytes, never taken
 * from a site; a copy counts once; the person's time limit ends it; it is sure there are no more after searches that kept nothing in more
 * than one place; one picture for each value of what must differ; with no AI wanted, only a picture with a record counts; the model is told
 * after each search what was kept and why the rest was set aside. Each mechanism with a control that shows it is the one doing the work.
 */
class CollectionTest {
    static final ObjectMapper M = new ObjectMapper();

    String home;
    Supplier<List<PicturePlaces.Place>> places; Collection.Looker looker; Collection.Eyes eyes; BiFunction<String, String, Researcher.Drive> drives; LongSupplier clock;
    BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check; int same, sure; Collection.TwoEyes twoEyes;

    @BeforeEach void isolate(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        places = Collection.PLACES; looker = Collection.LOOKER; eyes = Collection.EYES; drives = Collection.DRIVES; clock = Collection.CLOCK; check = Images.CHECK; same = PictureHash.SAME; sure = PictureHash.SURE; twoEyes = Collection.TWO_EYES;
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b"); Config.set(ModelEntries.SEES, "yes");
        Config.set("RESEARCHZOSHO_LIKENESS", "off");
    }
    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        Collection.PLACES = places; Collection.LOOKER = looker; Collection.EYES = eyes; Collection.DRIVES = drives; Collection.CLOCK = clock; Images.CHECK = check; PictureHash.SAME = same; PictureHash.SURE = sure; Collection.TWO_EYES = twoEyes;
    }

    // ---- the stand-ins ----

    /** The pictures the stand-in web serves, by address. */
    final Map<String, byte[]> served = new HashMap<>();
    /** What each place answers, by place and words. */
    final Map<String, List<PicturePlaces.Candidate>> answers = new HashMap<>();
    final List<String> judged = new ArrayList<>(), questions = new ArrayList<>();

    PicturePlaces.Candidate pic(String url, String title, BufferedImage img, int claimW, int claimH) { return pic(url, title, PicturesMeasuredTest.jpg(img, 0.9f), claimW, claimH, PicturePlaces.Record.NONE, ""); }
    PicturePlaces.Candidate pic(String url, String title, byte[] bytes, int claimW, int claimH, PicturePlaces.Record record, String caption) {
        served.put(url, bytes);
        return new PicturePlaces.Candidate(new Images.Found(url, "https://pages.example/" + title.replace(' ', '-'), title, "stand-in", claimW, claimH, "", "2026-11-19T00:00:00Z", caption), record);
    }

    void serve(List<String> placeNames) {
        List<PicturePlaces.Place> ps = new ArrayList<>();
        for (String n : placeNames) ps.add(new PicturePlaces.Place(n, n + ": pictures for the test · one call each", 0, (q, page, form) -> answers.getOrDefault(n + "|" + q + "|" + page, List.of())));
        Collection.PLACES = () -> ps;
        Collection.LOOKER = (url, peek, goOn, most) -> {
            byte[] b = served.get(url);
            if (b == null) throw new IOException("the server answered HTTP 404");
            if (peek > 0 && goOn != null && b.length > peek && !goOn.test(Arrays.copyOf(b, peek))) return new Images.Fetched(Arrays.copyOf(b, peek), "image/jpeg");
            return new Images.Fetched(b, "image/jpeg");
        };
    }

    /** The model that sees: its answer from the page's title, which the question carries. */
    void eyes(Function<String, String> byTitle) {
        Collection.EYES = (e, jpeg, q) -> {
            questions.add(q);
            int at = q.indexOf("is titled \""); String title = at < 0 ? "" : q.substring(at + 11, q.indexOf('"', at + 11));
            judged.add(title);
            return byTitle.apply(title);
        };
    }

    /** The model choosing the searches: each step a search (where, query) in turn, then done; what it was shown is kept. */
    final List<ArrayNode> seatSaw = new ArrayList<>();
    void seat(String... steps) {
        Collection.DRIVES = (d, m) -> new Researcher.Drive() {
            int i = 0;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                seatSaw.add(messages.deepCopy());
                String s = steps[Math.min(i++, steps.length - 1)];
                ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", "");
                ObjectNode tc = a.putArray("tool_calls").addObject(); tc.put("id", "c" + i); tc.put("type", "function");
                if (s.startsWith("done")) tc.putObject("function").put("name", "done").put("arguments", "{\"why\":\"" + s.substring(4).strip() + "\"}");
                else { String[] p = s.split("\\|"); ObjectNode args = M.createObjectNode(); args.put("where", p[0]); args.put("query", p[1]); if (p.length > 2) args.put("page", Integer.parseInt(p[2])); tc.putObject("function").put("name", "search").put("arguments", args.toString()); }
                return a;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32768; }
        };
    }

    ObjectNode job(String id, CollectionForm form) { ObjectNode j = M.createObjectNode(); j.put("job_id", id); j.put("kind", Collection.JOB_KIND); j.put("patron", "person"); j.set("args", form.json(M)); return j; }

    static CollectionForm form(String words, int count, int w, int h, String orientation, String ai, String differ, int minutes) {
        return new CollectionForm(words, "", "", count, w, h, orientation, "", ai, differ, minutes, false);
    }

    static String titles(ObjectNode m) { List<String> t = new ArrayList<>(); for (JsonNode p : m.path("passed")) t.add(p.path("title").asText()); return t.toString(); }

    // ---- what passed, measured, once each, up to the number ----

    @Test void onlyWhatPassedIsKeptUpToTheNumberAskedTheSizeMeasuredFromTheBytesACopyOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        BufferedImage one = PicturesMeasuredTest.art(1, 1, 2560, 1440);
        answers.put("web|red kites|1", List.of(
                pic("https://a.example/1.jpg", "red kite good one", one, 0, 0),
                pic("https://a.example/2.jpg", "red kite good but small", PicturesMeasuredTest.art(2, 2, 1600, 844), 3840, 2160),   // a site's claim of 4K, and 1600×844 in the bytes
                pic("https://a.example/3.jpg", "red kite good but vertical", PicturesMeasuredTest.art(3, 3, 1440, 2560), 0, 0),
                pic("https://a.example/4.jpg", "a logo on black, no", PicturesMeasuredTest.art(4, 4, 2560, 1440), 0, 0),
                pic("https://b.example/5.jpg", "red kite good one again", PicturesMeasuredTest.resized(one, 1920, 1080), 0, 0),   // the first, smaller, on another site
                pic("https://a.example/6.jpg", "red kite good two", PicturesMeasuredTest.art(6, 6, 2560, 1440), 800, 600)));        // a site's claim too small, and large in the bytes
        answers.put("commons|red kite|1", List.of(pic("https://c.example/7.jpg", "red kite good three", PicturesMeasuredTest.art(7, 7, 3000, 2000), 0, 0),
                pic("https://c.example/8.jpg", "red kite good four", PicturesMeasuredTest.art(8, 8, 3000, 2000), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> t.contains("good") ? "yes — a red kite in flight" : "no — a logo on a black ground, no bird");
        seat("web|red kites", "commons|red kite", "done nothing more");
        String said = Collection.runJob(store, job("J-0001", form("red kites", 3, 1920, 1080, CollectionForm.LANDSCAPE, "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0001");
        System.out.println("[collection] " + said);
        assertEquals(List.of("red kite good two", "red kite good one", "red kite good three").toString(), titles(m), "the yeses alone, up to the number asked, and no copy; a site's claim of a large size is fetched first, never passed for it");
        assertEquals("count", m.path("stopped").asText());
        assertEquals(List.of("red kite good two", "red kite good one", "a logo on black, no", "red kite good three"), judged, "the model looks only at what was measured and is no copy; the fourth good one is never needed");
        JsonNode n = m.path("counts");
        assertEquals(1, n.path("small").asInt()); assertEquals(1, n.path("shape").asInt()); assertEquals(1, n.path("no").asInt()); assertEquals(1, n.path("copy").asInt());
        String turned = m.path("turned_down").toString();
        assertTrue(turned.contains("measured 1600×844, smaller than 1920×1080"), turned);
        assertTrue(turned.contains("measured 1440×2560, vertical where horizontal was asked"), turned);
        assertEquals(2, m.path("copies").get(0).path("of").asInt(), "the copy is shown beside the picture it copies");
        JsonNode first = m.path("passed").get(0);
        assertEquals(2560, first.path("width").asInt()); assertEquals("red kite good two", first.path("title").asText(), "measured 2560 wide where its site said 800"); assertEquals("jpg", first.path("format").asText()); assertEquals("a red kite in flight", first.path("why").asText());
        assertNotNull(Collection.file(store, "J-0001", first.path("thumb").asText()), "a small copy is kept beside the manifest");
        assertTrue(Files.size(Collection.file(store, "J-0001", first.path("thumb").asText())) < 200_000);
        assertTrue(Images.onShelves(store).isEmpty(), "links and small copies: nothing kept at full size unless asked");
        // the model choosing the searches is told what the search kept and why the rest was set aside
        String told = seatSaw.get(1).get(seatSaw.get(1).size() - 1).path("content").asText();
        assertTrue(told.contains("Kept 2") && told.contains("smaller than asked") && told.contains("the wrong shape") && told.contains("judged not to show it") && told.contains("Kept so far: 2 of 3."), told);
        assertTrue(said.contains("3 of 3 pictures of red kites kept: it had the 3 asked for.") && said.contains("/images?collection=J-0001"), said);
    }

    @Test void controlWithTheHashOffTheCopyIsKeptAsAPictureOfItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        BufferedImage one = PicturesMeasuredTest.art(1, 1, 2560, 1440);
        answers.put("web|red kites|1", List.of(pic("https://a.example/1.jpg", "red kite good one", one, 0, 0), pic("https://b.example/5.jpg", "red kite good one again", PicturesMeasuredTest.resized(one, 1920, 1080), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a red kite");
        seat("web|red kites", "done that is all");
        PictureHash.SAME = -1; PictureHash.SURE = -1;
        Collection.runJob(store, job("J-0002", form("red kites", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals(List.of("red kite good one", "red kite good one again").toString(), titles(Collection.manifest(store, "J-0002")));
    }

    @Test void aPictureCloseToAKeptOneByTheHashIsACopyOnlyWhenTheModelThatSeesSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        BufferedImage one = PicturesMeasuredTest.art(1, 1, 2560, 1440);
        answers.put("web|red kites|1", List.of(pic("https://a.example/1.jpg", "red kite good one", one, 0, 0), pic("https://b.example/5.jpg", "red kite good one again", PicturesMeasuredTest.resized(one, 1920, 1080), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a red kite");
        List<String> compared = new ArrayList<>();
        PictureHash.SURE = -1;   // every match in the middle range, so the model's look decides
        Collection.TWO_EYES = (e, a, b, q) -> { compared.add(q); return "different — another photograph of a kite"; };
        seat("web|red kites", "done that is all");
        Collection.runJob(store, job("J-0012", form("red kites", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals(1, compared.size(), "the two compared once, by the model that sees");
        assertTrue(compared.get(0).startsWith("Two pictures follow."), compared.get(0));
        assertEquals(2, Collection.manifest(store, "J-0012").path("passed").size(), "the model says different: both kept");
        compared.clear();
        Collection.TWO_EYES = (e, a, b, q) -> { compared.add(q); return "Same — the second is the first, smaller"; };
        seat("web|red kites", "done that is all");
        Collection.runJob(store, job("J-0013", form("red kites", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0013");
        assertEquals(1, m.path("passed").size(), "the model says the same: one kept");
        assertTrue(m.path("copies").get(0).path("how").asText().endsWith("the model that sees: the same picture"), m.path("copies").toString());
        // control: within the sure range, no look is asked
        compared.clear(); PictureHash.SURE = 2;
        seat("web|red kites", "done that is all");
        Collection.runJob(store, job("J-0014", form("red kites", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertTrue(compared.isEmpty(), "a copy measured the same outright is not shown to the model");
        assertEquals(1, Collection.manifest(store, "J-0014").path("passed").size());
    }

    // ---- the time, and being sure ----

    @Test void thePersonsTimeLimitEndsTheJobAndWithoutOneTheNumberDoes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<PicturePlaces.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(pic("https://a.example/t" + i + ".jpg", "good " + i, PicturesMeasuredTest.art(100 + i, 200 + i, 800, 600), 0, 0));
        answers.put("web|owls|1", many);
        serve(List.of("web", "commons"));
        long[] now = {1_000_000};
        Collection.CLOCK = () -> now[0];
        eyes(t -> { now[0] += 4 * 60_000; return "yes — an owl"; });   // each look takes four minutes
        seat("web|owls", "done");
        Collection.runJob(store, job("J-0003", form("owls", 8, 0, 0, "", "", "", 10)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0003");
        assertEquals("time", m.path("stopped").asText());
        assertTrue(m.path("passed").size() < 8, "ended by the ten minutes: " + m.path("passed").size());
        // control: no limit set, as a research run has none unless the person sets one: the number ends it
        judged.clear(); now[0] = 1_000_000;
        Collection.runJob(store, job("J-0004", form("owls", 8, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals("count", Collection.manifest(store, "J-0004").path("stopped").asText());
        assertEquals(8, Collection.manifest(store, "J-0004").path("passed").size());
    }

    @Test void aTimeLimitSetWhileItRunsEndsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<PicturePlaces.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(pic("https://a.example/t" + i + ".jpg", "good " + i, PicturesMeasuredTest.art(100 + i, 200 + i, 800, 600), 0, 0));
        answers.put("web|owls|1", many);
        serve(List.of("web", "commons"));
        long[] now = {1_000_000};
        int[] looks = {0};
        Collection.CLOCK = () -> now[0];
        // the person says "do not spend more than 10 min" after the second look (2026-11-19: said after the yes, it reached nothing)
        eyes(t -> { now[0] += 4 * 60_000; if (++looks[0] == 2) { try { Collection.limit(store, "J-0005", 10); } catch (IOException e) { throw new RuntimeException(e); } } return "yes — an owl"; });
        seat("web|owls", "done");
        Collection.runJob(store, job("J-0005", form("owls", 8, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0005");
        assertEquals("time", m.path("stopped").asText(), m.toString());
        assertTrue(m.path("passed").size() < 8, "ended by the ten minutes set while it ran: " + m.path("passed").size());
        assertEquals(10, m.path("form").path("minutes").asInt()); assertTrue(m.path("said").asText().contains("for at most 10 minutes"), m.path("said").asText());
        assertTrue(Collection.told(m, Chrome.ENGLISH).contains("the 10 minutes were up"), Collection.told(m, Chrome.ENGLISH));
    }

    /** One of a spread of subjects: what the pictures are of, its kind, the name looked for, the judge's rule for it. */
    record Subject(String words, String kind, String name, String rule) { }

    @Test void theJudgesQuestionFollowsTheKindOfSubjectAndANamedSubjectIsLookedForByName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a spread, never one subject: a film character, a comic one, a game one, a real actor, a historical person, a thing (2026-11-19: a film
        // character's collection kept the actress out of the role and a stock photograph, with no name looked for)
        List<Subject> spread = List.of(
                new Subject("Ellen Ripley from Alien", CollectionForm.CHARACTER, "Ellen Ripley", Collection.Run.SHOWS_CHARACTER),
                new Subject("Tintin", CollectionForm.CHARACTER, "Tintin", Collection.Run.SHOWS_CHARACTER),
                new Subject("Geralt of Rivia from The Witcher 3", CollectionForm.CHARACTER, "Geralt", Collection.Run.SHOWS_CHARACTER),
                new Subject("Henry Cavill", CollectionForm.PERSON, "Henry Cavill", Collection.Run.SHOWS_PERSON),
                new Subject("Ada Lovelace", CollectionForm.PERSON, "Ada Lovelace", Collection.Run.SHOWS_PERSON),
                new Subject("red foxes in snow", CollectionForm.OTHER, "", Collection.Run.SHOWS_ITSELF));
        int i = 0;
        for (Subject sub : spread) {
            i++; questions.clear(); answers.clear();
            String named = sub.name().isEmpty() ? "a red fox in snow" : sub.name() + " wallpaper";
            answers.put("web|q" + i + "|1", List.of(pic("https://a.example/n" + i + ".jpg", named, PicturesMeasuredTest.art(40 + i, 40 + i, 800, 600), 0, 0),
                    pic("https://a.example/u" + i + ".jpg", "free desktop backgrounds", PicturesMeasuredTest.art(60 + i, 70 + i, 800, 600), 0, 0)));
            serve(List.of("web", "commons"));
            eyes(t -> "yes — it shows it");
            seat("web|q" + i, "done");
            CollectionForm f = new CollectionForm(sub.words(), "", "", 2, 0, 0, "", "", "", "", 0, false, "", "", sub.kind());
            Collection.runJob(store, job("J-01" + (10 + i), f), "http://x", "m", p -> { }, () -> false);
            assertEquals(sub.name(), Collection.Run.nameOf(f), sub.words());
            assertEquals(sub.name(), Collection.manifest(store, "J-01" + (10 + i)).path("name_used").asText(), "the gallery says which name: " + sub.words());
            String onNamed = questions.stream().filter(q -> q.contains("\"" + named + "\"")).findFirst().orElseThrow(), onOther = questions.stream().filter(q -> q.contains("free desktop backgrounds")).findFirst().orElseThrow();
            assertTrue(onNamed.contains(sub.rule()) && onOther.contains(sub.rule()), sub.words() + ": " + onOther);
            assertFalse(onNamed.contains("Nothing on this picture's page names"), "named by its title: " + onNamed);
            assertEquals(!sub.name().isEmpty(), onOther.contains("Nothing on this picture's page names " + sub.name() + ":"), "a named subject's unnamed picture is held to the closer question: " + onOther);
        }
        // the character's rule says the actor out of the role is a no; the person's, that their likeness in a role is theirs
        assertTrue(Collection.Run.SHOWS_CHARACTER.contains("out of the role") && Collection.Run.SHOWS_PERSON.contains("in a photograph, a portrait or a role"));
        // control: with no kind (a program's form, or one from before the kind) the old rule stands: a name only when the words look like one and stand in the request
        assertEquals("", Collection.Run.nameOf(new CollectionForm("Hit-Girl from Kick-Ass", "", "", 20, 0, 0, "", "", "", "", 0, false)));
        assertEquals("Altered Carbon", Collection.Run.nameOf(new CollectionForm("Altered Carbon", "", "pictures of altered carbon", 20, 0, 0, "", "", "", "", 0, false)));
        // the form's own name, the request's words for it, comes first
        assertEquals("hitgirl", Collection.Run.nameOf(new CollectionForm("Hit-Girl from Kick-Ass", "", "", 20, 0, 0, "", "", "", "", 0, false, "hitgirl", "", CollectionForm.CHARACTER)));
    }

    @Test void theFormSaysTheKindItReadSoAWrongReadingIsSeenBeforeTheYes() {
        CollectionForm character = CollectionForm.fromArgs(M.createObjectNode().put("words", "Hit-Girl from Kick-Ass").put("kind", "fictional character").put("count", 20));
        assertEquals(CollectionForm.CHARACTER, character.kind()); assertEquals("character", character.json(M).path("kind").asText());
        assertEquals("collect up to 20 pictures of Hit-Girl from Kick-Ass: the character as the character (in costume, drawn or on screen), each a different picture, kept as links and small copies", character.said(Chrome.ENGLISH));
        CollectionForm person = CollectionForm.fromArgs(M.createObjectNode().put("words", "Ada Lovelace").put("kind", "person"));
        assertTrue(person.said(Chrome.ENGLISH).startsWith("collect up to 20 pictures of Ada Lovelace: the person themselves, "), person.said(Chrome.ENGLISH));
        // control: no kind, or anything else, says nothing of it
        assertEquals("collect up to 20 pictures of red foxes: each a different picture, kept as links and small copies", CollectionForm.fromArgs(M.createObjectNode().put("words", "red foxes").put("kind", "other")).said(Chrome.ENGLISH));
        assertEquals("", CollectionForm.fromArgs(M.createObjectNode().put("words", "red foxes").put("kind", "a vibe")).kind());
    }

    @Test void aNameTheRequestWritesAnotherWayStaysInTheForm() {
        String his = "ok im looking for 20 really good distinct 4k horizontal images as wallpaper for my computer screen of hitgirl from kickass.";
        CollectionForm f = new CollectionForm("Hit-Girl from Kick-Ass", "", his, 20, 0, 0, "", "", "", "", 0, false, "Hit-Girl", "", CollectionForm.CHARACTER);
        CollectionForm.Grounded g = f.grounded(his);
        assertEquals("Hit-Girl", g.form().name(), "his \"hitgirl\" is the name, hyphen aside: " + g.dropped()); assertTrue(g.dropped().isEmpty());
        // control: a name the request does not write at all is still the model's reading, and dropped
        CollectionForm other = new CollectionForm("Hit-Girl from Kick-Ass", "", his, 20, 0, 0, "", "", "", "", 0, false, "Mindy Macready", "", CollectionForm.CHARACTER);
        assertEquals(List.of("name \"Mindy Macready\""), other.grounded(his).dropped());
    }

    @Test void aNameWrittenAsOneWordNamesThePicture() {
        Images.Found joined = new Images.Found("https://images.example/download/hitgirl-h6-3840x2160.jpg", "https://images.example/wallpaper/hitgirl", "", "web", 0, 0, "");
        assertEquals(Images.BY_ADDRESS, Images.namedBy(joined, "Hit-Girl"));
        assertEquals(Images.BY_TITLE, Images.namedBy(new Images.Found("https://x.example/a.jpg", "", "Kick Ass Hit Girl Wallpaper", "web", 0, 0, ""), "Hit-Girl"));
        // control: a page that names the film and not the character does not name her
        assertEquals(Images.BY_NONE, Images.namedBy(new Images.Found("https://x.example/kickass-wallpaper-3840x2160.jpg", "https://x.example/collection/kickass-wallpaper", "Kickass Wallpaper (65+ images)", "web", 0, 0, ""), "Hit-Girl"));
    }

    /** A small WebP picture, lossy (VP8) and lossless (VP8L), made with Pillow: 64 by 48, red with blue lines. */
    static final byte[] WEBP_LOSSY = Base64.getDecoder().decode("UklGRsoAAABXRUJQVlA4IL4AAACQCACdASpAADAAPm02mEkkIyKhIqgAgA2JYgB4N/Vd5QMAv/+4B/Vf4B/AMYB/AP4B+gHv/3Yp1f7fAAUFhygicnO7/j1m10QDW+EUAAD+9Fx3CqQu0F+f/YVmScCpsMA6gwv//6rXcv+VruX/K13L5eAw05qiBC/gBreX49u+gNYs4NylQ/ClCzjPEp7z+ZSwl1D9XUaBIBqmK5q44ghdnLwbckQcuTqqpfn18cqjIRhwtMzoS49NdY1pAAAA"),
            WEBP_LOSSLESS = Base64.getDecoder().decode("UklGRjIAAABXRUJQVlA4TCYAAAAvP8ALAA9wBYpT0N4Kef6jBUrbNoK6/9JFRd8djOj/OkDQ5n1vAg==");

    @Test void aWebPPictureIsOpenedAndWhatIsNoPictureIsSaidAsWhatItIs(@TempDir Path tmp) throws Exception {
        for (byte[] w : List.of(WEBP_LOSSY, WEBP_LOSSLESS)) {
            assertEquals("webp", PictureFile.read(w).format()); assertEquals(64, PictureFile.read(w).width());
            BufferedImage img = PictureHash.small(w, 1024);
            assertNotNull(img, "WebP is opened (it was not, before 0.8.0)"); assertEquals(64, img.getWidth()); assertEquals(48, img.getHeight());
        }
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String html = "<!doctype html><html><head><title>Hotlinking is not allowed</title></head><body>no</body></html>";
        answers.put("web|q|1", List.of(
                pic("https://a.example/icon.svg", "an icon", new byte[]{'<', 's', 'v', 'g', '>'}, 0, 0, PicturePlaces.Record.NONE, ""),
                pic("https://b.example/picture.jpg", "a page instead", html.getBytes(StandardCharsets.UTF_8), 0, 0, PicturePlaces.Record.NONE, ""),
                pic("https://c.example/fox.webp", "a red fox", WEBP_LOSSY, 0, 0, PicturePlaces.Record.NONE, "")));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a red fox");
        seat("web|q", "done");
        Collection.runJob(store, job("J-0120", form("red fox", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0120");
        assertEquals(1, m.path("passed").size(), "the WebP picture is kept: " + m);
        assertEquals("webp", m.path("passed").get(0).path("format").asText());
        assertEquals(2, m.path("counts").path("not_picture").asInt(), "the icon by its address and the web page by its bytes: " + m.path("counts"));
        assertEquals(0, m.path("counts").path("unreadable").asInt());
        String told = Collection.told(m, Chrome.ENGLISH);
        assertTrue(told.contains("not a picture: an icon, a drawing, a document or a web page: 2") && !told.contains("cannot open"), told);
    }

    @Test void itIsSureThereAreNoMoreAfterSearchesThatKeptNothingInMoreThanOnePlace(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|lynx|1", List.of(pic("https://a.example/l.jpg", "lynx good", PicturesMeasuredTest.art(9, 9, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a lynx");
        seat("web|lynx", "web|lynx cat", "commons|lynx", "web|wild lynx", "commons|lynx photo", "web|lynx snow", "done searched enough");
        Collection.runJob(store, job("J-0005", form("lynx", 20, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0005");
        assertEquals("sure", m.path("stopped").asText());
        assertEquals(1 + Collection.SURE_AFTER, m.path("searches").size(), "the first search kept one; then the searches that kept nothing, in two places");
        assertTrue(Collection.told(m, Chrome.ENGLISH).contains("1 of 20 pictures of lynx kept: " + Collection.SURE_AFTER + " searches since the last picture kept, in 2 places, found nothing new to judge."), Collection.told(m, Chrome.ENGLISH));
        // control: the same empty searches all in one place do not make it sure; it goes on until the model is done
        seatSaw.clear();
        seat("web|lynx", "web|lynx cat", "web|lynx b", "web|lynx c", "web|lynx d", "web|lynx e", "done searched enough");
        Collection.runJob(store, job("J-0006", form("lynx", 20, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode one = Collection.manifest(store, "J-0006");
        assertEquals("done", one.path("stopped").asText()); assertEquals(6, one.path("searches").size()); assertEquals("searched enough", one.path("done_why").asText());
    }

    @Test void searchesWhosePicturesWereTurnedDownAreNoEvidenceThatThereAreNoMore(@TempDir Path tmp) throws Exception {
        // 2026-11-19: a film character's collection stopped as sure at 4 of 20 after searches whose new pictures were stock photographs, judged no
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|lynx|1", List.of(pic("https://a.example/l.jpg", "lynx good", PicturesMeasuredTest.art(9, 9, 800, 600), 0, 0)));
        List<String> steps = new ArrayList<>(List.of("web|lynx"));
        for (int i = 0; i < 6; i++) {
            String where = i % 2 == 0 ? "web" : "commons", q = "stock " + i;
            answers.put(where + "|" + q + "|1", List.of(pic("https://s.example/" + i + ".jpg", "stock photo " + i, PicturesMeasuredTest.art(100 + 7 * i, 50 + 11 * i, 800, 600), 0, 0)));
            steps.add(where + "|" + q);
        }
        steps.add("done searched enough");
        serve(List.of("web", "commons"));
        eyes(t -> t.startsWith("lynx") ? "yes — a lynx" : "no — a stock photograph of something else");
        seat(steps.toArray(new String[0]));
        Collection.runJob(store, job("J-0030", form("lynx", 20, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0030");
        assertEquals("done", m.path("stopped").asText(), "six searches of wrong pictures, in two places, and it went on until the model was done: " + m.path("searches"));
        assertEquals(7, m.path("searches").size()); assertEquals(6, m.path("counts").path("no").asInt());
    }

    @Test void theServiceIsToldTheJobGoesForwardSoRestartsDoNotEndIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|owls|1", List.of(pic("https://a.example/o.jpg", "good owl", PicturesMeasuredTest.art(70, 70, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — an owl");
        seat("web|owls", "done");
        try (Checkpoint cp = Checkpoint.open(store, "J-0020", Collection.JOB_KIND, Checkpoint.basisOf("J-0020"))) {
            Collection.runJob(store, job("J-0020", form("owls", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false, cp);
        }
        ObjectNode state = Checkpoint.stateOf(store, "J-0020");
        assertNotNull(state); assertTrue(state.path("seq").asLong() > 0, state.toString());
        assertTrue(state.path("said").asText().contains("1 of 3 kept"), state.toString());
        // control: with no checkpoint given, nothing tells the service, and three restarts in a row would end the job as failed (2026-11-19, J-0002)
        seat("web|owls", "done");
        Collection.runJob(store, job("J-0021", form("owls", 3, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertNull(Checkpoint.stateOf(store, "J-0021"));
    }

    @Test void aPlaceThatDidNotAnswerIsNoEvidenceThereAreNoMore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|lynx|1", List.of(pic("https://a.example/l.jpg", "lynx good", PicturesMeasuredTest.art(9, 9, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        List<PicturePlaces.Place> withBroken = new ArrayList<>(Collection.PLACES.get());
        withBroken.add(new PicturePlaces.Place("broken", "broken: answers HTTP 502 · one call each", 0, (q, page, form) -> { throw new IOException("the place answered HTTP 502"); }));
        Collection.PLACES = () -> withBroken;
        eyes(t -> "yes — a lynx");
        seat("web|lynx", "broken|a", "commons|b", "broken|c", "web|d", "broken|e", "commons|f", "done enough");
        Collection.runJob(store, job("J-0030", form("lynx", 20, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0030");
        assertEquals("done", m.path("stopped").asText(), "three searches that kept nothing, three that did not answer: not sure");
        assertEquals(3, m.path("searches").findValues("failed").size());
        // control: the same searches answering with nothing make it sure
        seat("web|lynx", "commons|a", "commons|b", "web|c", "web|d", "commons|e", "commons|f", "done enough");
        Collection.runJob(store, job("J-0031", form("lynx", 20, 0, 0, "", "", "", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals("sure", Collection.manifest(store, "J-0031").path("stopped").asText());
    }

    // ---- what must differ ----

    @Test void onePictureForEachValueOfWhatMustDifferTheJudgeNamingFromTheValuesTaken(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|lighthouses|1", List.of(
                pic("https://a.example/a.jpg", "lighthouse at Brittany coast", PicturesMeasuredTest.art(31, 31, 800, 600), 0, 0),
                pic("https://a.example/b.jpg", "lighthouse at Cornwall cliff", PicturesMeasuredTest.art(32, 32, 800, 600), 0, 0),
                pic("https://a.example/c.jpg", "lighthouse at Brittany night", PicturesMeasuredTest.art(33, 33, 800, 600), 0, 0),
                pic("https://a.example/d.jpg", "lighthouse somewhere", PicturesMeasuredTest.art(34, 34, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> t.contains("Brittany") ? "yes — a lighthouse on a rocky shore\nWhich: Brittany" : t.contains("Cornwall") ? "yes — a lighthouse on a cliff\n**Which:** Cornwall" : "yes — a lighthouse");
        seat("web|lighthouses", "done");
        Collection.runJob(store, job("J-0007", form("lighthouses", 10, 0, 0, "", "", "the coast", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0007");
        assertEquals(List.of("lighthouse at Brittany coast", "lighthouse at Cornwall cliff").toString(), titles(m), "one for each coast; a second Brittany is set aside, and one that names no coast too");
        assertEquals(1, m.path("counts").path("taken").asInt()); assertEquals(1, m.path("counts").path("no_value").asInt());
        assertTrue(questions.stream().anyMatch(q -> q.startsWith("Name the coast in this picture")), "an answer with no Which line is asked for the name once more");
        assertTrue(questions.get(2).contains("when it is one of these, write its name exactly as here: Brittany; Cornwall"), "the judge names from the values taken: " + questions.get(2));
        assertTrue(questions.get(0).contains("write \"Which:\" and name the coast in this picture, from what it shows, its page's title or its address (https://pages.example/lighthouse-at-Brittany-coast)"), questions.get(0));
        assertEquals("Brittany", m.path("passed").get(0).path("value").asText());
        assertTrue(questions.get(0).contains(Collection.Run.SHOWS_ITSELF), "the thing itself, never a likeness of its style: " + questions.get(0));
        // with the person's own words, they come first and decide over the form's reading of them
        questions.clear();
        seat("web|lighthouses", "done");
        CollectionForm asked = new CollectionForm("lighthouses", "lighthouses in Brittany", "ten pictures of lighthouses in Brittany", 1, 0, 0, "", "", "", "", 0, false);
        Collection.runJob(store, job("J-0040", asked), "http://x", "m", p -> { }, () -> false);
        assertTrue(questions.get(0).startsWith("A person is collecting pictures and asked, in their own words: «ten pictures of lighthouses in Brittany». Does this picture show what they asked for, read as: lighthouses in Brittany? Where that reading and their own words differ, their own words decide."), questions.get(0));
    }

    @Test void aNameLeftOutOfTheAnswerIsAskedForOnceMoreAndKept(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|temples|1", List.of(pic("https://a.example/t.jpg", "Honen-in temple in autumn", PicturesMeasuredTest.art(91, 91, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        List<String> asked = new ArrayList<>();
        Collection.EYES = (e, jpeg, q) -> { asked.add(q); return q.startsWith("Name the temple") ? "Which: Honen-in" : "Yes, the page's title names Honen-in temple and the picture shows its gate under red maples."; };
        seat("web|temples", "done");
        Collection.runJob(store, job("J-0060", form("temples", 5, 0, 0, "", "", "the temple", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0060");
        assertEquals("Honen-in", m.path("passed").get(0).path("value").asText(), "the name from the second ask");
        assertEquals(2, asked.size());
        // control: an answer with its Which line is not asked again
        asked.clear();
        Collection.EYES = (e, jpeg, q) -> { asked.add(q); return "Yes, its gate under red maples.\nWhich: Honen-in"; };
        seat("web|temples", "done");
        Collection.runJob(store, job("J-0061", form("temples", 5, 0, 0, "", "", "the temple", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals(1, asked.size());
    }

    @Test void twoNamesForOneValueAreOneValue() {
        assertTrue(Collection.sameValue("Main Drive Gear (or Fragment A)", "Main Drive Gear"));
        assertTrue(Collection.sameValue("King Ghidorah", "ghidorah"));
        assertTrue(Collection.sameValue("Main Drive Gear; Fragment B", "main drive gear"), "a list: its first name");
        assertFalse(Collection.sameValue("Fragment A", "Fragment B"), "control: other names");
        assertFalse(Collection.sameValue("Godzilla", "Mechagodzilla"));
        assertFalse(Collection.sameValue("Kodai-ji", "Entokuin, sub-temple of Kodaiji"));
        assertFalse(Collection.sameValue("", "anything"));
    }

    /** The owner's request of 2026-11-19, as he wrote it. */
    static final String HIS = "im looking for 20 distinct and really good 4k wallpaper (horizontal not vertical orientation) images of altered carbon (ai generated is ok).  i want to use them for backgrounds on my computer.  do not spend more than 15 min on this";

    static CollectionForm read(String words, String shows, String differ, String quote, String name) {
        return new CollectionForm(words, shows, "", 20, 0, 0, "", "", "", differ, 0, false, name, quote);
    }

    @Test void whatNarrowsACollectionIsKeptOnlyWhenThePersonsOwnWordsSayIt() {
        // the three forms the model filled from his request on the acceptance runs: each narrowing is the model's, not his, and is dropped
        CollectionForm.Grounded a = read("Altered Carbon TV series wallpaper", "", "a different scene or character in each", "20 distinct", "").grounded(HIS);
        assertEquals("", a.form().differBy(), "\"distinct\" names no scene: distinctness is the program's own"); assertEquals(1, a.dropped().size());
        CollectionForm.Grounded a2 = read("Altered Carbon TV series wallpaper", "", "a different scene or character in each", "", "").grounded(HIS);
        assertEquals("", a2.form().differBy(), "no words of the request given for it");
        CollectionForm.Grounded b = read("Altered Carbon 4K wallpaper", "a horizontal 4K wallpaper in the style of the series Altered Carbon: futuristic cyberpunk city", "", "", "").grounded(HIS);
        assertEquals("", b.form().shows(), "\"in the style of\" is not his");
        CollectionForm.Grounded c = read("Altered Carbon wallpaper", "", "scene", "distinct and really good", "altered carbon").grounded(HIS);
        assertEquals("", c.form().differBy()); assertEquals("altered carbon", c.form().name(), "the name he wrote stays");
        assertTrue(c.form().said(Chrome.ENGLISH).contains("each a different picture,") && !c.form().said(Chrome.ENGLISH).contains("with a different"), "the question shows none of it: " + c.form().said(Chrome.ENGLISH));
        assertEquals("", read("x", "", "", "", "Winston Churchill").grounded("i want 100+ distinct images of winston churchhill - historical and non ai generated").form().name(), "control: the name as written, not as corrected");
        // where the rule is said, it stays: in his words, in other words, in another language, whatever the case and the spacing
        String godzilla = "i want 50 images of godzilla fighting another monster, each monster must be different, ai generated is ok. 4k minimum";
        CollectionForm.Grounded g = read("Godzilla fighting another monster", "godzilla fighting another monster", "monster", "Each monster  must be different.", "godzilla").grounded(godzilla);
        assertTrue(g.dropped().isEmpty(), g.dropped().toString());
        assertEquals("monster", g.form().differBy()); assertEquals("godzilla fighting another monster", g.form().shows()); assertEquals("godzilla", g.form().name());
        assertTrue(read("Kyoto temples in autumn", "", "temple", "one per temple", "").grounded("25 photos of Kyoto temples in autumn, one per temple").dropped().isEmpty());
        assertTrue(read("ゴジラ", "", "怪獣", "怪獣はそれぞれ違うものにして", "ゴジラ").grounded("ゴジラが別の怪獣と戦っている画像を50枚、怪獣はそれぞれ違うものにして").dropped().isEmpty(), "a substring's check: any language");
        assertEquals("", read("Godzilla", "", "monster", "4k minimum", "").grounded(godzilla).form().differBy(), "control: words of the request that do not name the thing");
        assertEquals("", read("Godzilla", "", "monster", "each monster must be unique", "").grounded(godzilla).form().differBy(), "control: words that are not in the request");
        assertTrue(read("Godzilla", "", "monster", "", "").grounded("").dropped().isEmpty(), "with no request in words (a program's own form), nothing is dropped");
    }

    @Test void aPictureWhosePageNamesNothingIsAskedWhetherItShowsTheNamedThingItself(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|altered carbon wallpaper|1", List.of(
                pic("https://a.example/ac-city.jpg", "Altered Carbon season 2 wallpaper", PicturesMeasuredTest.art(81, 81, 800, 450), 0, 0),
                pic("https://stock.example/man-neon.jpg", "Man in Black Jacket Standing by a Wall", PicturesMeasuredTest.art(82, 82, 800, 450), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> questions.get(questions.size() - 1).contains("Nothing on this picture's page names") ? "no — a man under neon lights, nothing of the series itself" : "yes — a scene of the series");
        seat("web|altered carbon wallpaper", "done");
        CollectionForm named = new CollectionForm("altered carbon wallpaper", "", HIS, 5, 0, 0, "", "", "", "", 0, false, "altered carbon", "");
        Collection.runJob(store, job("J-0050", named), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0050");
        assertEquals(List.of("Altered Carbon season 2 wallpaper").toString(), titles(m), "named by its page: the judge's yes; named nowhere: the closer question, and its no");
        assertEquals("title", m.path("passed").get(0).path("named_by").asText());
        assertFalse(questions.get(0).contains("Nothing on this picture's page names"), questions.get(0));
        assertTrue(questions.get(1).contains("Nothing on this picture's page names altered carbon: answer yes only when the picture itself shows altered carbon"), questions.get(1));
        // with no name in the form, words that look like a name and stand in his request are the name (the research pictures' rule)
        questions.clear();
        answers.put("web|Altered Carbon|1", answers.get("web|altered carbon wallpaper|1"));
        seat("web|Altered Carbon", "done");
        Collection.runJob(store, job("J-0052", new CollectionForm("Altered Carbon", "", HIS, 5, 0, 0, "", "", "", "", 0, false)), "http://x", "m", p -> { }, () -> false);
        assertEquals(List.of("Altered Carbon season 2 wallpaper").toString(), titles(Collection.manifest(store, "J-0052")));
        // control: words that are no name ("altered carbon wallpaper" in lower case): both are asked the same question and both kept
        questions.clear();
        seat("web|altered carbon wallpaper", "done");
        Collection.runJob(store, job("J-0051", new CollectionForm("altered carbon wallpaper", "", HIS, 5, 0, 0, "", "", "", "", 0, false)), "http://x", "m", p -> { }, () -> false);
        assertEquals(2, Collection.manifest(store, "J-0051").path("passed").size());
        assertTrue(questions.stream().noneMatch(q -> q.contains("Nothing on this picture's page names")));
    }

    // ---- AI, by provenance only ----

    @Test void withNoAIWantedOnlyAPictureWithARecordCountsAndADeclaredOneIsSetAside(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        byte[] declared = PicturesMeasuredTest.withSegment(PicturesMeasuredTest.jpg(PicturesMeasuredTest.art(44, 44, 800, 600), 0.9f), 0xe1,
                PicturesMeasuredTest.xmp("Iptc4xmpExt:DigitalSourceType=\"http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia\""));
        answers.put("commons|harbour 1950s|1", List.of(
                pic("https://c.example/r.jpg", "harbour record", PicturesMeasuredTest.jpg(PicturesMeasuredTest.art(41, 41, 800, 600), 0.9f), 0, 0, new PicturePlaces.Record("A. Photographer", "1954", "Public domain", true, ""), ""),
                pic("https://c.example/ai.jpg", "harbour declared", declared, 0, 0, new PicturePlaces.Record("Someone", "2024", "CC BY", true, ""), ""),
                pic("https://c.example/cat.jpg", "harbour filed as AI", PicturesMeasuredTest.jpg(PicturesMeasuredTest.art(45, 45, 800, 600), 0.9f), 0, 0, new PicturePlaces.Record("Someone", "2023", "", true, "its record files it under \"AI-generated\""), "")));
        answers.put("web|harbour 1950s|1", List.of(
                pic("https://w.example/n.jpg", "harbour no record", PicturesMeasuredTest.jpg(PicturesMeasuredTest.art(42, 42, 800, 600), 0.9f), 0, 0, PicturePlaces.Record.NONE, ""),
                pic("https://w.example/cap.jpg", "harbour captioned", PicturesMeasuredTest.jpg(PicturesMeasuredTest.art(43, 43, 800, 600), 0.9f), 0, 0, PicturePlaces.Record.NONE, "The old harbour. Photo by Jane Rowe, 1957.")));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a harbour");
        seat("commons|harbour 1950s", "web|harbour 1950s", "done");
        Collection.runJob(store, job("J-0008", form("harbour 1950s", 10, 0, 0, "", CollectionForm.AI_NO, "", 0)), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0008");
        assertEquals(List.of("harbour record", "harbour captioned").toString(), titles(m));
        assertEquals("a dated record: 1954, A. Photographer", m.path("passed").get(0).path("provenance").asText());
        assertTrue(m.path("passed").get(1).path("provenance").asText().contains("Jane Rowe") && m.path("passed").get(1).path("provenance").asText().contains("1957"));
        assertEquals(2, m.path("counts").path("ai").asInt(), "declared in its file, and filed as AI by its record");
        assertEquals(1, m.path("counts").path("unverified").asInt());
        assertFalse(judged.contains("harbour no record"), "a picture that cannot count is not looked at");
        assertEquals("https://w.example/n.jpg", m.path("unverified").get(0).path("image").asText(), "listed, not counted");
        // control: AI-made allowed, the same pictures all count
        judged.clear();
        seat("commons|harbour 1950s", "web|harbour 1950s", "done");
        Collection.runJob(store, job("J-0009", form("harbour 1950s", 10, 0, 0, "", CollectionForm.AI_OK, "", 0)), "http://x", "m", p -> { }, () -> false);
        assertEquals(5, Collection.manifest(store, "J-0009").path("passed").size());
    }

    // ---- kept at full size, or links only ----

    @Test void fullSizeOnRequestInALibraryOfOnesOwnAndRefusedWithAccountsOn(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        answers.put("web|foxes|1", List.of(pic("https://a.example/f.jpg", "fox good", PicturesMeasuredTest.art(51, 51, 800, 600), 0, 0)));
        serve(List.of("web", "commons"));
        eyes(t -> "yes — a fox");
        seat("web|foxes", "done");
        CollectionForm full = new CollectionForm("foxes", "", "", 1, 0, 0, "", "", "", "", 0, true);
        Collection.runJob(store, job("J-0010", full), "http://x", "m", p -> { }, () -> false);
        ObjectNode m = Collection.manifest(store, "J-0010");
        assertFalse(m.path("passed").get(0).path("entry").asText().isBlank(), "kept on the shelves as an entry");
        assertEquals(1, Images.onShelves(store).size());
        // under accounts: links and small copies, and the refusal said
        LibraryStore hosted = new LibraryStore(tmp.resolve("hosted")); hosted.init();
        Hosted.begin(hosted);
        try {
            seat("web|foxes", "done");
            String said = Collection.runJob(hosted, job("J-0011", full), "http://x", "m", p -> { }, () -> false);
            ObjectNode h = Collection.manifest(hosted, "J-0011");
            assertTrue(h.path("refused_full").asBoolean()); assertTrue(h.path("passed").get(0).path("entry").asText("").isBlank());
            assertTrue(Images.onShelves(hosted).isEmpty());
            assertTrue(said.contains(Collection.REFUSED_FULL), said);
        } finally { Hosted.end(hosted); }
    }

    // ---- the form in plain words ----

    @Test void theFormIsSaidInPlainWordsFromItsFieldsAndMeasuresEitherWayRoundWithNoOrientation() {
        CollectionForm f = new CollectionForm("Altered Carbon", "", "", 20, 3840, 2160, "landscape", "", "ok", "", 15, false);
        assertEquals("collect up to 20 pictures of Altered Carbon: horizontal, at least 3840×2160, AI-made allowed, each a different picture, for at most 15 minutes, kept as links and small copies", f.said(Chrome.ENGLISH));
        CollectionForm any = new CollectionForm("Godzilla", "Godzilla fighting another monster", "", 50, 3840, 2160, "", "", "ok", "the other monster", 0, false);
        assertEquals("collect up to 50 pictures of Godzilla: each showing Godzilla fighting another monster, at least 3840×2160, either way round, AI-made allowed, each a different picture, with a different monster in each, kept as links and small copies", any.said(Chrome.ENGLISH));
        assertNull(any.sizeFails(2160, 3840), "no orientation asked: a tall 4K passes"); assertNotNull(f.sizeFails(2160, 3840));
        assertNull(f.sizeFails(3840, 2242)); assertNotNull(f.sizeFails(3840, 1600));
        assertTrue(new CollectionForm("x", "", "", 20, 0, 0, "", "", "", "different scene or character in each", 0, false).said(Chrome.ENGLISH).contains("with a different scene or character in each,"), "the thing alone in the sentence");
        CollectionForm none = new CollectionForm("Winston Churchill", "", "", 100, 0, 0, "", "", "no", "", 0, false);
        assertTrue(none.said(Chrome.ENGLISH).contains("none made by AI: each with a record of who made it and when"));
        assertEquals(60, new CollectionForm("x", "", "", 5, 0, 0, "", "", "", "", 0, false).judgeCeiling()); assertEquals(800, none.judgeCeiling());
        assertEquals(20, new CollectionForm("x", "", "", 0, 0, 0, "", "", "", "", 0, false).target(), "no number asked: twenty");
        assertEquals(200, new CollectionForm("x", "", "", 900, 0, 0, "", "", "", "", 0, false).target());
    }
}
