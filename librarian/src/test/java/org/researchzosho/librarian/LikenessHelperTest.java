package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The likeness helper (0.7.0): its thresholds stand on 26 public-domain portraits measured once; its measure folds into a verdict the
 * number first; the candidates are seen in the measured order; the words say whether it is there; the protocol and a run's seeded pass
 * carry the measure; and, with the real helper named by {@code RESEARCHZOSHO_LIKENESS_PYTHON} and {@code RESEARCHZOSHO_LIKENESS_DIR},
 * the helper itself separates the pairs.
 */
class LikenessHelperTest {
    static final ObjectMapper J = new ObjectMapper();
    static final String[] PEOPLE = {"lincoln", "einstein", "curie", "twain", "tesla", "darwin", "douglass", "nightingale", "tubman", "whitman", "roosevelt", "victoria", "sittingbull"};
    /** The scores measured on 2026-11-08 with the helper on the CPU: the same person's two sittings, and each person's first sitting against the next person's second. NaN where a face was not found. */
    static final double[] SAME_FACE = {0.9061, 0.6496, 0.6829, 0.5069, 0.5617, Double.NaN, 0.6609, 0.5941, 0.6415, 0.4995, 0.9231, 0.7078, 0.9095};
    static final double[] DIFF_FACE = {0.1796, 0.2092, 0.1857, 0.1868, Double.NaN, 0.1399, 0.2225, 0.3355, 0.1977, 0.0929, 0.1061, 0.1074, 0.0865};
    static final double[] SAME_PICTURE = {0.946, 0.5859, 0.8923, 0.7633, 0.7807, 0.7114, 0.6943, 0.8166, 0.8032, 0.7785, 0.8399, 0.7535, 0.9435};
    static final double[] DIFF_PICTURE = {0.3607, 0.582, 0.5337, 0.6694, 0.4965, 0.604, 0.3986, 0.5876, 0.4178, 0.5878, 0.3662, 0.5003, 0.5663};

    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;
    LikenessHelper.Scorer scorer; LongSupplier free; Supplier<String> install;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK; scorer = LikenessHelper.SCORER; free = LikenessHelper.FREE; install = LikenessHelper.INSTALL;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        LikenessHelper.SCORER = null;
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; LikenessHelper.SCORER = scorer; LikenessHelper.FREE = free; LikenessHelper.INSTALL = install; }

    static byte[] resource(String name) {
        try (InputStream in = LikenessHelperTest.class.getResourceAsStream("/images/likeness/" + name)) { return Objects.requireNonNull(in, name).readAllBytes(); } catch (Exception e) { throw new AssertionError(e); }
    }
    static byte[] portrait(String person, int sitting) {
        try (InputStream in = LikenessHelperTest.class.getResourceAsStream("/images/likeness/" + person + "-" + sitting + ".jpg")) { if (in != null) return in.readAllBytes(); } catch (Exception ignored) { }
        return resource(person + "-" + sitting + ".png");
    }
    static Images.Judged judged(Images.Verdict v, String why) { return new Images.Judged(new Images.Found("https://x.example/c.jpg", "https://x.example/p", "A candidate", "Brave", 0, 0, ""), v, why, "m", "the same person as the picture kept as X"); }
    static LikenessHelper.Measure m(double picture, Double face) { return new LikenessHelper.Measure(picture, face, true, face != null); }

    @Test void theThresholdsStandOnTheMeasuredPortraitPairs() throws Exception {
        assertEquals(0.45, LikenessHelper.FACE_SAME); assertEquals(0.30, LikenessHelper.FACE_DIFFERENT);
        int sameFaces = 0, diffFaces = 0, inTheBand = 0;
        for (double s : SAME_FACE) if (!Double.isNaN(s)) { sameFaces++; assertTrue(s >= LikenessHelper.FACE_SAME, "the same person's two sittings measure at or above the same-threshold: " + s); }
        for (double d : DIFF_FACE) if (!Double.isNaN(d)) { diffFaces++; assertTrue(d < LikenessHelper.FACE_SAME, "two different people never reach the same-threshold: " + d); if (d >= LikenessHelper.FACE_DIFFERENT) inTheBand++; }
        assertEquals(12, sameFaces); assertEquals(12, diffFaces);
        assertEquals(1, inTheBand, "one different-person pair (Nightingale against Tubman, 0.3355) lands in the band where the model decides; the rest measure different");
        double sameMin = Arrays.stream(SAME_PICTURE).min().orElseThrow(), diffMax = Arrays.stream(DIFF_PICTURE).max().orElseThrow();
        assertTrue(sameMin < diffMax, "the picture embedding does not separate people (" + sameMin + " for the same, up to " + diffMax + " for different ones): it ranks and is said, never overrides");
        // the pairs are on the record with their sources, and every file is in the resources
        String sources = new String(resource("SOURCES.md"), StandardCharsets.UTF_8);
        assertTrue(sources.contains("Public domain") && sources.contains("commons.wikimedia.org"), sources);
        for (String p : PEOPLE) { assertTrue(portrait(p, 1).length > 1000, p); assertTrue(portrait(p, 2).length > 1000, p); assertTrue(sources.contains("| " + p + "-1."), p); }
    }

    @Test void theMeasureFoldsIntoTheVerdictTheNumberFirst() {
        Images.Judged no = judged(Images.Verdict.NO, "a thinner moustache"), yes = judged(Images.Verdict.YES, "the same face");
        Images.Judged up = Images.measured(no, m(0.7, 0.62), Images.SAME, true);
        assertEquals(Images.Verdict.UNSURE, up.verdict()); assertEquals("the faces measure the same (0.62) while the model says no → unsure: a thinner moustache", up.why());
        Images.Judged down = Images.measured(yes, m(0.7, 0.21), Images.SAME, true);
        assertEquals(Images.Verdict.UNSURE, down.verdict()); assertEquals("the faces measure different (0.21) while the model says yes → unsure: the same face", down.why());
        Images.Judged agreed = Images.measured(yes, m(0.7, 0.66), Images.SAME, true);
        assertEquals(Images.Verdict.YES, agreed.verdict()); assertEquals("the same face; faces measure 0.66", agreed.why());
        Images.Judged band = Images.measured(no, m(0.7, 0.36), Images.SAME, true);
        assertEquals(Images.Verdict.NO, band.verdict()); assertEquals("a thinner moustache; faces measure 0.36", band.why(), "in the band the model decides and the number is said");
        Images.Judged noFace = Images.measured(yes, new LikenessHelper.Measure(0.78, null, true, false), Images.SAME, true);
        assertEquals(Images.Verdict.YES, noFace.verdict()); assertEquals("the same face; pictures measure 0.78 (no face found in the candidate)", noFace.why());
        Images.Judged concept = Images.measured(no, m(0.7, 0.62), Images.CONCEPT, true);
        assertEquals(Images.Verdict.NO, concept.verdict()); assertEquals("a thinner moustache; faces measure 0.62", concept.why(), "the concept mode gets the number said only");
        Images.Judged thing = Images.measured(no, m(0.91, 0.62), Images.SAME, false);
        assertEquals(Images.Verdict.NO, thing.verdict()); assertEquals("a thinner moustache; faces measure 0.62", thing.why(), "a thing that is not a person gets the number said only");
        assertSame(no, Images.measured(no, null, Images.SAME, true)); assertEquals(Images.Verdict.UNJUDGED, Images.measured(judged(Images.Verdict.UNJUDGED, "no answer"), m(0.7, 0.9), Images.SAME, true).verdict());
        // writing and titles outrank the measure (the Stais likeness live, 2026-11-08: two true photographs of the man decades younger measured 0.22–0.23 and were demoted): a yes that rests on the picture's own words stays
        for (String textYes : List.of("faces: yes — writing in the picture names Valerios Stais", "faces: yes — the same man; its page title names Valerios Stais", "faces: yes — the caption; its caption names Valerios Stais", "named by its address; faces: no → yes")) {
            Images.Judged kept = Images.measured(judged(Images.Verdict.YES, textYes), m(0.6, 0.22), Images.SAME, true, "Valerios Stais");
            assertEquals(Images.Verdict.YES, kept.verdict(), textYes); assertEquals(textYes + "; faces measure 0.22, but the picture's own words name Valerios Stais", kept.why());
        }
        assertEquals("faces: yes — writing in the picture names him; faces measure 0.22, but the picture's own words name the person", Images.measured(judged(Images.Verdict.YES, "faces: yes — writing in the picture names him"), m(0.6, 0.22), Images.SAME, true).why());
        assertEquals(Images.Verdict.UNSURE, Images.measured(judged(Images.Verdict.YES, "faces: yes — the same face and build"), m(0.6, 0.22), Images.SAME, true, "Valerios Stais").verdict(), "a yes that rests on the faces alone is demoted");
        // the yes branch of the ranking says the naming the yes also rests on, once
        Images.Judged byFaces = new Images.Judged(judged(Images.Verdict.YES, "").found(), Images.Verdict.YES, "the same face. writing: yes", "m", "c");
        assertEquals("faces: yes — the same face; writing in the picture names Valerios Stais", Images.ranked(byFaces, true, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("faces: yes — the same face; its page title names Valerios Stais", Images.ranked(byFaces, false, Images.BY_TITLE, "Valerios Stais").why());
        assertEquals("faces: yes — the same face; its caption names Valerios Stais", Images.ranked(byFaces, null, Images.BY_CAPTION, "Valerios Stais").why());
        assertEquals("faces: yes — the same face", Images.ranked(byFaces, false, Images.BY_NONE, "Valerios Stais").why());
        assertEquals("faces: yes — writing in the picture names Valerios Stais", Images.ranked(new Images.Judged(byFaces.found(), Images.Verdict.YES, "writing in the picture names Valerios Stais", "m", "c"), true, Images.BY_ADDRESS, "Valerios Stais").why(), "said once");
        // a small or unclear face is not measured: the picture score is said and the verdict stands
        LikenessHelper.Measure small = new LikenessHelper.Measure(0.78, 0.22, true, true, 184, 41, 0.91, 0.89);
        assertFalse(small.faceUsable()); assertEquals(0.78, small.rank()); assertEquals("the face in the candidate is too small to measure (41 px); pictures measure 0.78", small.said());
        Images.Judged unchanged = Images.measured(yes, small, Images.SAME, true, "Valerios Stais");
        assertEquals(Images.Verdict.YES, unchanged.verdict()); assertEquals("the same face; the face in the candidate is too small to measure (41 px); pictures measure 0.78", unchanged.why());
        assertEquals("the face in the reference is too small to measure (60 px); pictures measure 0.78", new LikenessHelper.Measure(0.78, 0.9, true, true, 60, 184, 0.9, 0.9).said());
        assertEquals("the face in the candidate is not clear enough to measure (confidence 0.65); pictures measure 0.78", new LikenessHelper.Measure(0.78, 0.9, true, true, 184, 184, 0.9, 0.65).said());
        assertTrue(new LikenessHelper.Measure(0.78, 0.9, true, true, 80, 80, 0.7, 0.7).faceUsable(), "the floors are inclusive");
        ObjectNode sj = small.json(J); assertFalse(sj.path("face_used").asBoolean(true)); assertEquals(41, sj.path("face_px").get(1).asInt()); assertEquals(0.89, sj.path("face_conf").get(1).asDouble());
        // the ranking: the faces when both have one, else the pictures; the unmeasured after
        Images.Found a = new Images.Found("https://x.example/a.jpg", "", "", "Brave", 0, 0, ""), b = new Images.Found("https://x.example/b.jpg", "", "", "Brave", 0, 0, ""), c = new Images.Found("https://x.example/c.jpg", "", "", "Brave", 0, 0, "");
        List<Images.Scored> order = Images.inMeasuredOrder(List.of(new Images.Scored(a, null, m(0.5, 0.2)), new Images.Scored(b, null, null), new Images.Scored(c, null, m(0.5, 0.7))));
        assertEquals(List.of(c, a, b), order.stream().map(Images.Scored::found).toList());
        assertEquals(0.7, m(0.5, 0.7).rank()); assertEquals(0.5, new LikenessHelper.Measure(0.5, null, false, true).rank());
        assertEquals("pictures measure 0.50 (no face found in the reference)", new LikenessHelper.Measure(0.5, null, false, true).said());
        assertEquals("pictures measure 0.50 (no face found in either picture)", new LikenessHelper.Measure(0.5, null, false, false).said());
    }

    @Test void theWordsSayWhetherTheHelperIsThereAndTheSetupSaysWhatStoodInTheWay() throws Exception {
        assertNull(LikenessHelper.scorer()); assertFalse(LikenessHelper.installed());
        assertEquals("no likeness helper is set up; the model's reading alone", LikenessHelper.said());
        assertTrue(LikenessHelper.status().startsWith("The likeness helper is not set up: judgements of likeness rest on the seeing model's reading alone. researchzosho images likeness install sets it up (about 1.3 GB of Python packages (torch's CPU build, torchvision, open_clip, OpenCV), 605 MB for the picture model's weights and 39 MB for the two face models; open_clip's code and the LAION weights are MIT, YuNet is MIT, SFace is Apache-2.0) on the CPU; RESEARCHZOSHO_LIKENESS=off leaves it out."), LikenessHelper.status());
        assertFalse(Images.likenessAvailable());
        LikenessHelper.SCORER = (r, rt, c, ct) -> m(0.5, 0.5);
        assertTrue(Images.likenessAvailable());
        assertEquals("the likeness helper (CLIP ViT-B-32 for pictures, SFace for faces) measured each candidate against the reference first, and the model that sees judged them in that order", LikenessHelper.said());
        LikenessHelper.SCORER = null;
        // the setup in the background: no room is said with the numbers; a failure leaves its mark and waits a day
        LikenessHelper.FREE = () -> 1L << 30;
        assertTrue(LikenessHelper.setupInBackground("the daemon started").startsWith("the disk under ") && LikenessHelper.setupInBackground("x").contains(" has 1 GB free and the likeness helper needs about 4 GB, so it is not set up; RESEARCHZOSHO_LIKENESS=off leaves it out"), LikenessHelper.setupInBackground("x"));
        LikenessHelper.FREE = () -> 100L << 30;
        LikenessHelper.INSTALL = () -> "!installing torch failed: no network";
        String started = LikenessHelper.setupInBackground("the daemon started and found it missing");
        assertEquals("setting up the likeness helper in the background (" + LikenessHelper.SIZES + "), the daemon started and found it missing", started);
        LikenessHelper.awaitSetup();
        assertEquals("installing torch failed: no network", Files.readString(LikenessHelper.triedMark()).strip());
        assertEquals("a setup of the likeness helper failed within the day (installing torch failed: no network); researchzosho images likeness install tries now", LikenessHelper.setupInBackground("again"));
        assertTrue(LikenessHelper.status().endsWith(" The last setup failed: installing torch failed: no network."), LikenessHelper.status());
        Config.set(LikenessHelper.SETTING, "off");
        assertEquals("", LikenessHelper.setupInBackground("off"));
        assertEquals("The likeness helper is off (RESEARCHZOSHO_LIKENESS=off): judgements of likeness rest on the seeing model's reading alone.", LikenessHelper.status());
        Config.set(LikenessHelper.SETTING, "");
    }

    @Test void theProtocolAndASeededRunCarryTheMeasureAndSeeTheBestMeasuredFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ImagesTest.seeingDefault();
        List<LikenessTest.Asked> asked = new ArrayList<>();
        Vision.DRIVES = (d, mdl) -> LikenessTest.seeing(asked);
        byte[] fox = ImagesTest.square(RunPicturesTest.FOX), getty = ImagesTest.square(RunPicturesTest.GETTY);
        // the stand-in helper: the fox's bytes measure as the same face (0.90), Getty's as the same too (0.80) though the model says no, the rest as different (0.20)
        LikenessHelper.SCORER = (r, rt, c, ct) -> m(0.6, Arrays.equals(c, fox) ? 0.90 : Arrays.equals(c, getty) ? 0.80 : 0.20);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = ImagesTest.person().put("op", "like").put("bytes", Base64.getEncoder().encodeToString(LikenessTest.portrait())).put("content_type", "image/png").put("name", "stais.png").put("what", "person").put("count", 4);
        JsonNode r = p.images(a);
        assertEquals("the likeness helper (CLIP ViT-B-32 for pictures, SFace for faces) measured each candidate against the reference first, and the model that sees judged them in that order", r.path("likeness_helper").asText());
        assertTrue(r.path("how").asText().endsWith(" The likeness helper (CLIP ViT-B-32 for pictures, SFace for faces) measured each candidate against the reference first, and the model that sees judged them in that order."), r.path("how").asText());
        JsonNode first = r.path("verdicts").get(0);
        assertEquals(RunPicturesTest.FOX, first.path("image").asText(), "the best-measured candidate is judged first, whatever the engines' order: " + r.path("verdicts"));
        assertEquals("yes", first.path("verdict").asText()); assertTrue(first.path("why").asText().endsWith("; faces measure 0.90"), first.toString());
        assertEquals(0.9, first.path("likeness").path("face").asDouble()); assertEquals(0.6, first.path("likeness").path("picture").asDouble()); assertEquals("faces measure 0.90", first.path("likeness").path("said").asText());
        String refId = r.path("reference").path("id").asText();
        boolean raised = false;
        for (JsonNode v : r.path("verdicts")) {
            if (v.path("image").asText().equals(RunPicturesTest.GETTY)) { raised = true; assertEquals("unsure", v.path("verdict").asText(), v.toString()); assertTrue(v.path("why").asText().startsWith("the faces measure the same (0.80) while the model says no → unsure: "), v.toString()); }
            else if (!v.path("image").asText().equals(RunPicturesTest.FOX) && v.path("verdict").asText().equals("no")) assertTrue(v.path("why").asText().endsWith("; faces measure 0.20"), v.toString());
        }
        assertTrue(raised, "Getty's candidate was judged: " + r.path("verdicts"));
        // judge with a reference carries the same
        ObjectNode judge = ImagesTest.person().put("op", "judge").put("criteria", "person").put("reference", refId);
        ArrayNode imgs = judge.putArray("images"); imgs.addObject().put("image", RunPicturesTest.GETTY).put("title", "red fox side"); imgs.addObject().put("image", RunPicturesTest.FOX).put("page", RunPicturesTest.FOX_PAGE).put("title", "Wild Dog Breeds: Red Fox");
        r = p.images(judge);
        assertEquals("unsure", r.path("verdicts").get(0).path("verdict").asText(), r.toString()); assertEquals(0.8, r.path("verdicts").get(0).path("likeness").path("face").asDouble());
        assertEquals("yes", r.path("verdicts").get(1).path("verdict").asText()); assertTrue(r.path("likeness_helper").asText().startsWith("the likeness helper ("));
        // a run's pass beside the kept portrait: the candidates fetched and measured first, the fox seen before Getty though Getty came first, the trace carrying the measure
        asked.clear();
        List<ObjectNode> events = new ArrayList<>();
        RunPictures pictures = new RunPictures(store, l -> { }, (t, o) -> { if (t.equals("picture")) events.add(o); });
        RunPictures.Pass pass = pictures.new Pass("Valerios Stais");
        pass.reference(pictures.referenceFor(refId));
        List<Images.Found> web = Images.parseBrave(ImagesTest.fixture("brave-images.json"));
        Images.Found foxFound = web.stream().filter(f -> f.image().equals(RunPicturesTest.FOX)).findFirst().orElseThrow(), gettyFound = web.stream().filter(f -> f.image().equals(RunPicturesTest.GETTY)).findFirst().orElseThrow();
        List<RunPictures.Looked> looked = pictures.judgeAll("Valerios Stais", List.of(gettyFound, foxFound), pass, l -> { });
        assertEquals(List.of(RunPicturesTest.FOX, RunPicturesTest.GETTY), looked.stream().map(l -> l.found().image()).toList(), "the better-measured fox is seen first");
        List<String> twoPictureQuestions = asked.stream().filter(x -> x.pictures() == 2).map(LikenessTest.Asked::text).toList();
        assertTrue(twoPictureQuestions.get(0).contains("titled \"Wild Dog Breeds: Red Fox\""), twoPictureQuestions.toString());
        assertEquals(Images.Verdict.YES, looked.get(0).judged().verdict()); assertTrue(looked.get(0).judged().why().endsWith("; faces measure 0.90"), looked.get(0).judged().why());
        assertEquals(Images.Verdict.UNSURE, looked.get(1).judged().verdict()); assertTrue(looked.get(1).judged().why().startsWith("the faces measure the same (0.80) while the model says no → unsure: "), looked.get(1).judged().why());
        assertTrue(events.stream().anyMatch(o -> o.path("image").asText().equals(RunPicturesTest.GETTY) && o.path("likeness").asText().equals("faces measure 0.80")), events.toString());
    }

    /** The real helper over the 26 portraits, when a Python with its packages and a folder with its models are named; skipped otherwise. */
    @Test void theHelperItselfSeparatesThePortraitPairs() throws Exception {
        String py = System.getenv("RESEARCHZOSHO_LIKENESS_PYTHON"), dir = System.getenv("RESEARCHZOSHO_LIKENESS_DIR");
        Assumptions.assumeTrue(py != null && !py.isBlank() && dir != null && Files.isDirectory(Path.of(dir)), "RESEARCHZOSHO_LIKENESS_PYTHON and RESEARCHZOSHO_LIKENESS_DIR name the helper");
        Config.set("RESEARCHZOSHO_LIKENESS_PYTHON", py); Config.set("RESEARCHZOSHO_LIKENESS_DIR", dir);
        try (InputStream in = LikenessHelper.class.getResourceAsStream("/org/researchzosho/librarian/likeness_helper.py")) { Files.write(LikenessHelper.script(), Objects.requireNonNull(in).readAllBytes()); }
        LikenessHelper.Helper h = new LikenessHelper.Helper();
        try {
            int sameFaces = 0, diffFaces = 0, band = 0;
            for (int i = 0; i < PEOPLE.length; i++) {
                LikenessHelper.Measure same = h.score(portrait(PEOPLE[i], 1), "image/jpeg", portrait(PEOPLE[i], 2), "image/jpeg");
                LikenessHelper.Measure diff = h.score(portrait(PEOPLE[i], 1), "image/jpeg", portrait(PEOPLE[(i + 1) % PEOPLE.length], 2), "image/jpeg");
                if (same.face() != null) { sameFaces++; assertTrue(same.face() >= LikenessHelper.FACE_SAME, PEOPLE[i] + " same: " + same.said()); }
                if (diff.face() != null) { diffFaces++; assertTrue(diff.face() < LikenessHelper.FACE_SAME, PEOPLE[i] + " different: " + diff.said()); if (diff.face() >= LikenessHelper.FACE_DIFFERENT) band++; }
                assertTrue(same.picture() > 0.5 && diff.picture() < 0.7, PEOPLE[i] + ": " + same.picture() + " / " + diff.picture());
            }
            assertTrue(sameFaces >= 10 && diffFaces >= 10, sameFaces + " / " + diffFaces + " (one sitting's face is under 80 px and one detection under 0.7: not measured)"); assertTrue(band <= 2, "different pairs in the band: " + band);
            // a sitting shrunk by four, its face about 40 px: not measured against its own full-size sitting, and the why says the pixels
            BufferedImage full = ImageIO.read(new ByteArrayInputStream(portrait("twain", 1)));
            BufferedImage shrunk = new BufferedImage(full.getWidth() / 4, full.getHeight() / 4, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = shrunk.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(full, 0, 0, shrunk.getWidth(), shrunk.getHeight(), null); g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); ImageIO.write(shrunk, "png", bo);
            LikenessHelper.Measure tiny = h.score(portrait("twain", 2), "image/jpeg", bo.toByteArray(), "image/png");
            assertFalse(tiny.faceUsable(), tiny.toString());
            assertTrue(tiny.said().startsWith("the face in the candidate is too small to measure (") || tiny.said().startsWith("pictures measure"), tiny.said());
            if (tiny.face() != null) assertTrue(tiny.candPx() < LikenessHelper.MIN_FACE_PX && tiny.candPx() > 20, tiny.toString());
        } finally { h.stop(); }
    }
}
