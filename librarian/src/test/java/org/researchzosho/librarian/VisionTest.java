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
import org.researchzosho.drive.DriveClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whether a model sees pictures is measured with a small coloured square and kept on its entry: a model that names the colour of a red
 * square and then of a blue one sees; one that says red to both is guessing; one that answers nothing does not see. The entry that
 * pictures go to is chosen local first, and the sentence for no eyes says what the library has and how one is added.
 */
class VisionTest {
    static final ObjectMapper M = new ObjectMapper();

    String home;
    BiFunction<String, String, Researcher.Drive> drives;
    Function<ModelEntries.Entry, Crews.DriveState> probe;
    Function<ModelEntries.Entry, Vision.Probe> vision;

    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        drives = Vision.DRIVES; probe = LibraryProtocol.MODEL_PROBE; vision = LibraryProtocol.VISION_PROBE;
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Vision.DRIVES = drives; LibraryProtocol.MODEL_PROBE = probe; LibraryProtocol.VISION_PROBE = vision; }

    /** The colour of the picture in a message, read from its bytes: what a model that sees would say. */
    static String colourOf(ArrayNode messages) {
        for (JsonNode part : messages.get(0).path("content")) {
            if (!part.path("type").asText().equals("image_url")) continue;
            String url = part.path("image_url").path("url").asText();
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1))));
                int rgb = img.getRGB(0, 0) & 0xffffff;
                return rgb == 0xff0000 ? "Red." : rgb == 0x0000ff ? "Blue." : "Green.";
            } catch (Exception e) { return "unreadable"; }
        }
        return "I see no picture.";
    }

    /** A drive that answers {@code answer} to every picture, or by the picture's colour when {@code answer} is null; the calls are counted. */
    static Researcher.Drive drive(String answer, List<ArrayNode> calls) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new AssertionError("the probe asks for one word, not a chat turn"); }
            @Override public String classify(ArrayNode messages, int maxTokens) { calls.add(messages); return answer == null ? colourOf(messages) : answer; }
            @Override public int contextWindow() { return 16384; }
        };
    }

    @Test void aModelThatNamesBothSquaresSeesAGuesserDoesNotAndSilenceIsNoSight() {
        List<ArrayNode> calls = new ArrayList<>();
        Vision.DRIVES = (d, m) -> drive(null, calls);
        ModelEntries.Entry local = new ModelEntries.Entry("gemma", "http://homebox:8213", "gemma-4-e4b", null, "", 0, "");
        Vision.Probe p = Vision.probe(local);
        assertEquals(Boolean.TRUE, p.sees(), p.toString()); assertEquals("Red. / Blue.", p.said()); assertEquals("it named a red square and a blue one", p.why()); assertEquals("sees images", p.word());
        assertEquals(2, calls.size(), "a local entry is asked twice, both free");
        assertEquals(Vision.QUESTION, calls.get(0).get(0).path("content").get(0).path("text").asText());
        assertTrue(calls.get(0).get(0).path("content").get(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,"));
        // a model that says red to every picture is guessing: the blue square finds it out
        calls.clear(); Vision.DRIVES = (d, m) -> drive("Red", calls);
        p = Vision.probe(local);
        assertEquals(Boolean.FALSE, p.sees()); assertTrue(p.why().contains("called a blue one \"Red\": a guess, not a look"), p.why()); assertEquals("does not see images", p.word());
        // a hosted entry is asked once, the red square: one small paid call
        calls.clear();
        ModelEntries.Entry hosted = new ModelEntries.Entry("deepseek", "https://openrouter.ai/api/v1", "deepseek/deepseek-v4-flash-vision-exp", null, "", 0, "");
        p = Vision.probe(hosted);
        assertEquals(Boolean.TRUE, p.sees()); assertEquals(1, calls.size()); assertEquals("it named the red square", p.why());
        // nothing answered: a server that drops the picture with an error, or no model at all
        Vision.DRIVES = (d, m) -> drive("", calls);
        p = Vision.probe(local);
        assertEquals(Boolean.FALSE, p.sees()); assertTrue(p.why().startsWith("it gave no answer to a picture"), p.why());
        Vision.DRIVES = (d, m) -> drive("I cannot see images, only text.", calls);
        p = Vision.probe(local);
        assertEquals(Boolean.FALSE, p.sees()); assertTrue(p.why().startsWith("it called a red square \"I cannot see images"), p.why());
        // the square itself: 16×16, one colour, a PNG
        byte[] png = Vision.square(255, 0, 0);
        assertTrue(png.length > 16 && (png[0] & 0xff) == 0x89 && png[1] == 'P');
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2}), Vision.dataUrl(new byte[]{1, 2}, "image/jpeg; charset=binary"));
        assertEquals("not probed for pictures", Vision.Probe.word(null));
    }

    @Test void seesIsKeptOnTheEntryAndTheSeeingEntryIsChosenLocalFirst() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        assertNull(ModelEntries.defaultEntry().sees()); assertEquals("not probed for pictures", ModelEntries.defaultEntry().seesText());
        assertNull(ModelEntries.seeing(), "nothing probed, nothing sees");
        ModelEntries.put("kimi", "openrouter", "moonshotai/kimi-k3", null, null, null, null, null, "no");
        ModelEntries.put("deepseek", "openrouter", "deepseek/deepseek-v4-flash-vision-exp", null, null, null, null, null, "yes");
        ModelEntries.put("gemma", "http://homebox:8213", "gemma-4-e4b", null, null, null, null, "C", "yes");
        ModelEntries.put("qwen9", "http://homebox:8214", "qwen3.5-9b", null, null, null, null, "B", "yes");
        assertEquals(Boolean.FALSE, ModelEntries.get("kimi").sees()); assertEquals(Boolean.TRUE, ModelEntries.get("gemma").sees());
        assertEquals("sees images", ModelEntries.get("gemma").seesText()); assertEquals("does not see images", ModelEntries.get("kimi").seesText());
        assertThrows(IllegalArgumentException.class, () -> ModelEntries.put("gemma", null, null, null, null, null, null, null, "maybe"));
        // the default does not see yet: the local entries that see, the best tier first; a hosted one only when no local one does
        assertEquals("qwen9", ModelEntries.seeing().name(), "tier B before tier C");
        ModelEntries.sees("qwen9", false); ModelEntries.sees("gemma", null);
        assertNull(ModelEntries.get("gemma").sees(), "blank forgets the probe's answer");
        assertNull(ModelEntries.seeing(), "no local entry sees, and deepseek sees but has no key: nothing can run");
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
        assertEquals("deepseek", ModelEntries.seeing().name(), "a hosted entry that sees, once its key is kept");
        ModelEntries.sees(null, true);
        assertEquals(Boolean.TRUE, ModelEntries.defaultEntry().sees()); assertEquals("yes", Config.get(ModelEntries.SEES));
        assertEquals("default", ModelEntries.seeing().name(), "the default first when it sees");
        // the server set for pictures is taken at its word
        Config.set("RESEARCHZOSHO_VISION_DRIVE", "http://homebox:8299"); Config.set("RESEARCHZOSHO_VISION_MODEL", "gemma-4-26b-a4b");
        ModelEntries.Entry v = ModelEntries.seeing();
        assertEquals("vision", v.name()); assertEquals("http://homebox:8299", v.drive()); assertEquals("gemma-4-26b-a4b", v.model()); assertEquals(Boolean.TRUE, v.sees());
        Config.set("RESEARCHZOSHO_VISION_DRIVE", ""); Config.set("RESEARCHZOSHO_VISION_MODEL", "");
        // the list carries it, and remove takes it away with the rest
        JsonNode list = ModelEntries.list(M);
        JsonNode kimi = null; for (JsonNode e : list) if (e.path("name").asText().equals("kimi")) kimi = e;
        assertNotNull(kimi); assertFalse(kimi.path("sees").asBoolean(true)); assertEquals("does not see images", kimi.path("sees_text").asText());
        ModelEntries.remove("kimi");
        assertNull(ModelEntries.get("kimi"));
        String left = Config.get(ModelEntries.key("kimi", "SEES"));
        assertTrue(left == null || left.isBlank(), "remove takes sees with the rest: " + left);
    }

    @Test void noEyesSaysWhatTheLibraryHasWhoIsUnprobedAndHowToAddOne() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "gpt-oss-20b"); Config.set(ModelEntries.SEES, "no");
        ModelEntries.put("kimi", "openrouter", "moonshotai/kimi-k3", null, null, null, null);
        String s = ModelEntries.noEyes();
        assertTrue(s.startsWith("No model that sees pictures is set up. The library has: default (gpt-oss-20b, does not see images), kimi (moonshotai/kimi-k3, not probed for pictures, no key kept)."), s);
        assertTrue(s.contains("Not yet probed for pictures: kimi — researchzosho models test <name> asks each with a small picture (a hosted entry is one small paid call)."), s);
        assertTrue(s.contains("researchzosho model install sets up (qwen3.8-27b, gemma-4-e4b and gemma-4-e2b come with theirs"), s);
        assertTrue(s.contains("researchzosho models add vision openrouter deepseek/deepseek-v4-flash-vision-exp, then researchzosho models key openrouter <key>"), s);
        assertTrue(s.contains("RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL"), s);
    }

    @Test void theProtocolProbesALocalEntryOnAddAndEveryEntryOnTestAndKeepsTheAnswer(@TempDir Path tmp) throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        List<String> probed = new ArrayList<>();
        LibraryProtocol.MODEL_PROBE = e -> e.drive().contains("8213") || e.drive().contains("8212") ? Crews.DriveState.ANSWERS : Crews.DriveState.DOWN;
        LibraryProtocol.VISION_PROBE = e -> { probed.add(e.name()); return e.model().startsWith("gemma") ? new Vision.Probe(true, "Red. / Blue.", "it named a red square and a blue one") : new Vision.Probe(false, "Red / Red", "it named the red square but called a blue one \"Red\": a guess, not a look"); };
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "").put("runtime", "test");
        // a local entry whose server answers: probed at once, the answer kept
        JsonNode r = p.models(a.deepCopy().put("op", "add").put("name", "gemma").put("drive", "http://homebox:8213").put("model", "gemma-4-e4b"));
        assertEquals(List.of("gemma"), probed); assertTrue(r.path("sees").asBoolean(false)); assertTrue(r.path("note").asText().endsWith("sees images (it named a red square and a blue one)."), r.path("note").asText());
        assertEquals(Boolean.TRUE, ModelEntries.get("gemma").sees());
        // a local entry whose server is down: not probed, said
        r = p.models(a.deepCopy().put("op", "add").put("name", "down").put("drive", "http://homebox:8299").put("model", "x"));
        assertEquals(List.of("gemma"), probed); assertTrue(r.path("note").asText().contains("sees: not probed, its server does not answer now; researchzosho models test down asks it when it does."), r.path("note").asText());
        // a hosted entry: never probed on add, the call is paid
        r = p.models(a.deepCopy().put("op", "add").put("name", "deepseek").put("drive", "openrouter").put("model", "deepseek/deepseek-v4-flash-vision-exp"));
        assertEquals(List.of("gemma"), probed); assertTrue(r.path("note").asText().contains("sees: not probed; researchzosho models test deepseek asks it (one small paid call)."), r.path("note").asText());
        assertNull(ModelEntries.get("deepseek").sees());
        // said outright: no probe
        r = p.models(a.deepCopy().put("op", "add").put("name", "kimi").put("drive", "openrouter").put("model", "moonshotai/kimi-k3").put("sees", "no"));
        assertEquals(List.of("gemma"), probed); assertTrue(r.path("note").asText().contains("does not see images, as said."), r.path("note").asText());   // the prices sentence follows it on openrouter
        // test: the completion probe, then the picture probe, kept on the entry
        r = p.models(a.deepCopy().put("op", "test").put("name", "default"));
        assertEquals(List.of("gemma", "default"), probed); assertEquals("answers", r.path("state").asText()); assertFalse(r.path("sees").asBoolean(true));
        assertTrue(r.path("note").asText().endsWith("answers. Does not see images (it named the red square but called a blue one \"Red\": a guess, not a look)."), r.path("note").asText());
        assertEquals("no", Config.get(ModelEntries.SEES));
        // the command line's test says the same, and its list shows the three words
        PrintStream out = System.out; ByteArrayOutputStream buf = new ByteArrayOutputStream(); System.setOut(new PrintStream(buf, true));
        try {
            assertEquals(0, LibrarianCli.modelsCommand(store, new String[]{"researchzosho", "models", "test", "gemma"}));
            assertEquals(0, LibrarianCli.modelsCommand(store, new String[]{"researchzosho", "models", "list"}));
        } finally { System.setOut(out); }
        String said = buf.toString();
        assertTrue(said.contains("  answers\n  sees images (it named a red square and a blue one)"), said);
        assertTrue(said.contains("gemma: gemma-4-e4b via homebox, tier C; sees images"), said);
        assertTrue(said.contains("kimi: moonshotai/kimi-k3 via openrouter.ai, tier A; does not see images  — no key kept for openrouter.ai"), said);
        assertTrue(said.contains("deepseek: deepseek/deepseek-v4-flash-vision-exp via openrouter.ai, tier C; not probed for pictures"), said);
    }
}
