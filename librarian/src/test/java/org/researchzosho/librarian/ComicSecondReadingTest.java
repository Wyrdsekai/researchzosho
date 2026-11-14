package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Declined;
import org.researchzosho.librarian.ComicTranslationTest.Model;
import org.researchzosho.librarian.Translation.Seats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lettering read a second time (0.7.5): for a comic that is not Japanese, at the checked tier and above, a translator that sees
 * pictures reads each speech block again from a picture of it that the helper cuts out, one block a call; its answer becomes the
 * block's text when it is a reading, and the helper's stands when it is not. Both readings are kept in the page's reading, a job that
 * resumes asks for none of them again, the plan says which it will be and counts the calls, and the note lists the blocks the two
 * readers read differently. Japanese is never read twice. A block whose lettering is drawn into the art is left as art. The helper is
 * the stand-in ({@link ComicStandIn}), whose picture of a block carries what is lettered there; the model is a stand-in that reads it
 * back, or misreads it as a test says: no helper, no model server but one the test starts on a port of its own, no comic's page.
 */
class ComicSecondReadingTest {
    String home; BooleanSupplier installed; Supplier<ComicHelper.Reader> reader; Function<ModelEntries.Entry, Vision.Probe> probe;
    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        installed = ComicHelper.INSTALLED; reader = ComicHelper.READER; probe = LibraryProtocol.VISION_PROBE;
        ComicHelper.INSTALLED = () -> true;
        LibraryProtocol.VISION_PROBE = e -> new Vision.Probe(true, "red / blue", "it named a red square and a blue one");
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); ComicHelper.INSTALLED = installed; ComicHelper.READER = reader; LibraryProtocol.VISION_PROBE = probe; ComicHelper.manifestFrom(null); }

    static final String ASK = "This image is one block of text cut from a comic page in English. Write out the text exactly as it is written, keeping its own spelling, capitals and punctuation. Output only the text, on one line, and nothing else.";

    /**
     * An English comic of two pages as the stand-in helper reads it: on the first it misreads a letter of the one balloon, beside a
     * drawn sound word and a sign; on the second it reads one balloon right and nothing at all in the other.
     */
    static Path english(Path dir, Path script) throws IOException {
        Path cbz = dir.resolve("issue.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 2);
        int[][] b = ComicStandIn.balloons(500, 700);
        ObjectNode misread = ComicStandIn.block("b01", "speech", "WHERE IS THE DOCTOB?", b[0]); misread.put("lettered", "WHERE IS THE DOCTOR?");
        ObjectNode unread = ComicStandIn.block("b02", "speech", "", b[1]); unread.put("lettered", "HURRY!");
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, misread, ComicStandIn.block("b02", "sound", "KRAK", b[1]), ComicStandIn.block("b03", "outside", "CITY HOSPITAL", b[2])));
        ComicStandIn.says(script, p.get(1), ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "THE PATIENT IS WAITING.", b[0]), unread));
        return cbz;
    }

    static Model german() {
        Model m = new Model();
        m.english.put("WHERE IS THE DOCTOR?", "Wo ist der Arzt?"); m.english.put("WHERE IS THE DOCTOB?", "Wo ist der Doctob?"); m.english.put("THE PATIENT IS WAITING.", "Der Patient wartet.");
        m.english.put("HURRY!", "Schnell!"); m.english.put("CITY HOSPITAL", "Stadtkrankenhaus");
        return m;
    }

    @Test void whatMakesAnAnswerAReadingOfABlock() {
        ComicTranslation.Answer same = new ComicTranslation.Answer("WHERE IS THE DOCTOR?", false, false);
        assertEquals("", ComicTranslation.notAReading("WHERE IS THE DOCTOB?", same, "latin"));
        assertEquals("", ComicTranslation.notAReading("WHERE IS THE DOCTOB?", new ComicTranslation.Answer("WHERE IS THE DOCTOR?\n", false, false), "latin"), "a line's own end is no second line");
        // refused, silent or cut off at the limit: the helper's reading stands
        assertEquals("the translator declined to read it", ComicTranslation.notAReading("HELLO", new ComicTranslation.Answer("", false, true), "latin"));
        assertEquals("no answer came", ComicTranslation.notAReading("HELLO", new ComicTranslation.Answer("  ", false, false), "latin"));
        assertEquals("the answer ran to the limit of 300 tokens", ComicTranslation.notAReading("HELLO", new ComicTranslation.Answer("HELLO", true, false), "latin"));
        // an answer that keeps the balloon's own line breaks is a reading: its lines are joined as the helper joins a block's (a space between them; nothing in Chinese and Japanese, which write none). The helper's side measured the readers on such answers, joined: 16 of 45 Korean ones came in several lines
        assertEquals("", ComicTranslation.notAReading("HELLO THERE", new ComicTranslation.Answer("HELLO\nTHERE", false, false), "latin"));
        assertEquals("HELLO THERE", ComicTranslation.joined("HELLO\nTHERE\n", "latin")); assertEquals("당근아! 이런 귀여운 것~ 나를 대회 장소까지", ComicTranslation.joined("당근아!\n이런 귀여운 것~\n\n나를 대회 장소까지", "korean"));
        assertEquals("小心！！！这瓶魔药是大型怪兽！", ComicTranslation.joined("小心！！！\n这瓶魔药是\n 大型怪兽！", "chinese")); assertEquals("先生はどこですか", ComicTranslation.joined("先生は\nどこですか", "japanese"));
        assertEquals("", ComicTranslation.notAReading("小心！！！这瓶魔药是大型怪兽！", new ComicTranslation.Answer("小心！！！\n这瓶魔药是\n大型怪兽！", false, false), "chinese"));
        assertEquals("the answer is much longer than what the comic helper read", ComicTranslation.notAReading("HELLO THERE", new ComicTranslation.Answer("The image shows a speech balloon.\nIt reads:\nHELLO THERE", false, false), "latin"), "lines that are not the balloon's are held to its length as any answer is");
        // a balloon whose own words read like a refusal ("I cannot… SORRY!"): the watch on the seat takes the answer for a decline by its words alone; they are the page's words, and a reading, when they are letter for letter what the helper read there
        assertEquals("", ComicTranslation.notAReading("NO! I cannot, it is too dangerous SORRY!", new ComicTranslation.Answer("NO! I cannot, it is too dangerous. SORRY!", false, true), "latin"));
        assertEquals("the translator declined to read it", ComicTranslation.notAReading("Where is the doctor?", new ComicTranslation.Answer("I'm sorry, I cannot read the text in this image.", false, true), "latin"));
        Researcher.Drive byWords = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode ms, ArrayNode t, int x, String c) { return null; }
            @Override public int contextWindow() { return 16384; }
            @Override public String classify(ArrayNode ms, int x) { throw new Declined("stand-in", "NO! I cannot, it is too dangerous. SORRY!", Declined.How.WORDS); }
        };
        assertEquals(new ComicTranslation.Answer("NO! I cannot, it is too dangerous. SORRY!", false, true), ComicTranslation.second(new Seats(byWords, "stand-in", byWords, "stand-in"), "data:image/png;base64,iVBORw0KGgo=", "English", false), "the words come with the decline, to be held against what the helper read");
        Researcher.Drive marked = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode ms, ArrayNode t, int x, String c) { return null; }
            @Override public int contextWindow() { return 16384; }
            @Override public String classify(ArrayNode ms, int x) { throw new Declined("stand-in", "NO! I cannot, it is too dangerous. SORRY!", Declined.How.REFUSAL); }
        };
        assertEquals(new ComicTranslation.Answer("", false, true), ComicTranslation.second(new Seats(marked, "stand-in", marked, "stand-in"), "data:image/png;base64,iVBORw0KGgo=", "English", false), "a reply the model itself marked as a refusal is one, whatever its words");
        // the comic's writing: at least half the answer's letters
        assertEquals("most of the answer's letters are not in the comic's writing", ComicTranslation.notAReading("HELLO THERE", new ComicTranslation.Answer("ПРИВЕТ ТАМ", false, false), "latin"));
        assertEquals("", ComicTranslation.notAReading("ПРИВЕТ ТАМ", new ComicTranslation.Answer("ПРИВЕТ, ТАМ!", false, false), "cyrillic"));
        assertEquals("", ComicTranslation.notAReading("의사 선생님은 OK?", new ComicTranslation.Answer("의사 선생님은 OK?", false, false), "korean"), "a word of another alphabet in a balloon is not another writing");
        assertEquals("most of the answer's letters are not in the comic's writing", ComicTranslation.notAReading("医生在哪里", new ComicTranslation.Answer("Where is the doctor", false, false), "chinese"), "a model that translates what it was to read");
        assertEquals("", ComicTranslation.notAReading("医生在那里", new ComicTranslation.Answer("医生在哪里？", false, false), "chinese"));
        assertEquals("", ComicTranslation.notAReading("?!", new ComicTranslation.Answer("?!", false, false), "latin"), "marks alone are in every writing");
        // the length: at most twice and at least half what the helper read, four characters either way allowed to any block
        String twenty = "ABCDE FGHIJ KLMNO PQRST";   // 20 characters that are no space
        assertEquals("", ComicTranslation.notAReading(twenty, new ComicTranslation.Answer("A".repeat(40), false, false), "latin"));
        assertEquals("the answer is much longer than what the comic helper read", ComicTranslation.notAReading(twenty, new ComicTranslation.Answer("A".repeat(41), false, false), "latin"));
        assertEquals("", ComicTranslation.notAReading(twenty, new ComicTranslation.Answer("A".repeat(10), false, false), "latin"));
        assertEquals("the answer is much shorter than what the comic helper read", ComicTranslation.notAReading(twenty, new ComicTranslation.Answer("A".repeat(9), false, false), "latin"));
        assertEquals("", ComicTranslation.notAReading("OK", new ComicTranslation.Answer("O.K.!!", false, false), "latin"), "two letters read as six characters is no runaway");
        assertEquals("the answer is much longer than what the comic helper read", ComicTranslation.notAReading("OK", new ComicTranslation.Answer("OKAY NOW", false, false), "latin"));
        assertEquals("", ComicTranslation.notAReading("WHAT", new ComicTranslation.Answer("?", false, false), "latin"));
        // the length is the letters and digits: the dots and marks a reader drops or adds are no length ("근" read by the helper where the page says "...근데...": the right reading was refused as much longer)
        assertEquals("", ComicTranslation.notAReading("근", new ComicTranslation.Answer("...근데...", false, false), "korean"));
        assertEquals("", ComicTranslation.notAReading("……", new ComicTranslation.Answer("...그런데 말이야...", false, false), "korean"), "marks alone are a block the helper read no letter in");
        // a block the helper read nothing in has no length to hold an answer to: any line of up to 200 characters
        assertEquals("", ComicTranslation.notAReading("", new ComicTranslation.Answer("HURRY!", false, false), "latin"));
        assertEquals("", ComicTranslation.notAReading("", new ComicTranslation.Answer("A".repeat(200), false, false), "latin"));
        assertEquals("the answer is over 200 letters for a block the comic helper read nothing in", ComicTranslation.notAReading("", new ComicTranslation.Answer("A".repeat(201), false, false), "latin"));
        // two readings differ in letters, or only in what stands between them
        assertEquals(ComicTranslation.letters("Hello, World!"), ComicTranslation.letters("HELLO WORLD")); assertEquals(ComicTranslation.letters("ＯＫ３"), ComicTranslation.letters("ok3"));
        assertNotEquals(ComicTranslation.letters("HELLO WORID"), ComicTranslation.letters("HELLO WORLD"));
        ComicTranslation.Second marks = new ComicTranslation.Second("HELLO WORLD", "Hello, world!", true, ""), letters = new ComicTranslation.Second("HELLO WORID", "HELLO WORLD", true, ""), silent = new ComicTranslation.Second("HELLO", "", false, "no answer came");
        assertTrue(marks.differs()); assertFalse(marks.inLetters()); assertTrue(letters.differs()); assertTrue(letters.inLetters()); assertFalse(silent.differs()); assertFalse(silent.inLetters());
        // the words the helper's side measured with, and the sentence more for a block in columns
        assertEquals(ASK, ComicTranslation.secondAsk("English", false));
        assertEquals("This image is one block of text cut from a comic page in Chinese. Write out the text exactly as it is written, keeping its own spelling, capitals and punctuation. The text is set in columns: each column reads from top to bottom, and the columns from right to left. Output only the text, on one line, and nothing else.", ComicTranslation.secondAsk("Chinese", true));
        // the comic's writing: what the helper found on the pages it looked at, else what the language is written in
        assertEquals("cyrillic", ComicTranslation.writing(new ComicTranslation.Source("sr", ComicTranslation.SAMPLED, 3, "cyrillic", 1.0, false, null)));
        assertEquals("latin", ComicTranslation.writing(new ComicTranslation.Source("sr", ComicTranslation.SAMPLED, 3, "latin", 1.0, false, null)), "Serbian in Latin letters, as the helper found it");
        for (var e : Map.of("ja", "japanese", "zh", "chinese", "ko", "korean", "el", "greek", "ru", "cyrillic", "en", "latin", "fr", "latin").entrySet()) assertEquals(e.getValue(), ComicTranslation.writing(ComicTranslation.Source.named(e.getKey())), e.getKey());
        // when the lettering is read twice: never Japanese, not at the quick tier, not by a translator that does not see; found at the start while the language or the sight is
        ComicTranslation.Source en = ComicTranslation.Source.named("en"), ja = ComicTranslation.Source.named("ja"), later = ComicTranslation.Source.LATER;
        assertEquals(ComicTranslation.TWICE, ComicTranslation.second(en, "checked", ComicTranslation.SEES)); assertEquals(ComicTranslation.TWICE, ComicTranslation.second(en, "thorough", ComicTranslation.SEES));
        assertEquals(ComicTranslation.ONCE_QUICK, ComicTranslation.second(en, "quick", ComicTranslation.SEES)); assertEquals(ComicTranslation.ONCE_BLIND, ComicTranslation.second(en, "checked", ComicTranslation.BLIND));
        assertEquals(ComicTranslation.ONCE_BLIND, ComicTranslation.second(en, "reviewed", ComicTranslation.UNPROBED_HOSTED)); assertEquals(ComicTranslation.TWICE_LATER, ComicTranslation.second(en, "checked", ComicTranslation.UNPROBED));
        assertEquals(ComicTranslation.TWICE_LATER, ComicTranslation.second(later, "checked", ComicTranslation.SEES)); assertEquals(ComicTranslation.ONCE_QUICK, ComicTranslation.second(later, "quick", ComicTranslation.SEES));
        for (String tier : List.of("quick", "checked", "reviewed", "thorough")) assertEquals(ComicTranslation.ONCE_JAPANESE, ComicTranslation.second(ja, tier, ComicTranslation.SEES), tier);
    }

    @Test void aComicThatIsNotJapaneseIsReadTwiceAtCheckedByATranslatorThatSeesAndOnlyThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set(ModelEntries.SEES, "yes");
        // checked, a translator that sees: each speech block is cut out and read again, a block a call; the sound word and the sign are not
        Path first = Files.createDirectories(tmp.resolve("first")), script = first.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(first, script);
        Path cbz = english(first, script);
        Translation.Plan plan = Translation.plan(store, cbz, "German", "", "checked", "stand-in", "", "", "English", "");
        assertEquals(ComicTranslation.TWICE, plan.comic().second());
        Model m = german();
        Translation.Outcome o = Translation.run(store, cbz, "German", "", "checked", null, null, ComicTranslationTest.seats(m).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        List<String> crops = ComicStandIn.requests(script, "crops");
        assertEquals(2, crops.size(), "one request a page");
        assertTrue(crops.get(0).contains("\"ids\":[\"b01\"]") && crops.get(1).contains("\"ids\":[\"b01\",\"b02\"]"), "the speech blocks, and no other: " + crops);
        assertEquals(List.of(ASK, ASK, ASK), m.blocks, "the words the readers were measured with, one block a call");
        assertEquals(List.of("WHERE IS THE DOCTOR?", "THE PATIENT IS WAITING.", "HURRY!"), m.letteredAsked);
        assertTrue(ComicStandIn.asked(script).indexOf("crops") > ComicStandIn.asked(script).lastIndexOf("read"), "the pages are read by the helper before any block is cut out: " + ComicStandIn.asked(script));
        // the translator's reading is the block's text: the page's call translates from it, and a block the helper read nothing in has words now
        assertTrue(TranslationTest.inside(m.users.get(0)).contains("LINES:\n1. WHERE IS THE DOCTOR?\n"), m.users.get(0));
        assertTrue(TranslationTest.inside(m.users.get(1)).contains("LINES:\n1. THE PATIENT IS WAITING.\n2. HURRY!"), m.users.get(1));
        assertEquals(3, o.units()); assertEquals(3, o.translated());
        ComicTranslation.Done d = o.comic();
        assertEquals(new ComicTranslation.Twice(ComicTranslation.TWICE, 3, 3, 2, 2, Map.of(), List.of()), d.twice());
        // both readings are held with the block
        ComicTranslation.Block corrected = d.rows().get(0).get(0).block(), found = d.rows().get(1).get(1).block(), sound = d.rows().get(0).get(1).block();
        assertEquals("WHERE IS THE DOCTOR?", corrected.text()); assertEquals(new ComicTranslation.Second("WHERE IS THE DOCTOB?", "WHERE IS THE DOCTOR?", true, ""), corrected.second());
        assertEquals(new ComicTranslation.Second("", "HURRY!", true, ""), found.second()); assertNull(sound.second(), "a drawn sound word is read by the helper alone");
        // the note: who read a second time and what came of it, each page's count, the blocks read differently with both readings
        String note = Files.readString(o.note());
        assertTrue(note.contains("- Lettering read a second time: by stand-in, the translator, each speech block from a picture of it that the helper cut out, a block a call. Blocks read twice: 3; readings taken as the block's text: 3; readings that differ from the comic helper's in any character (a mark, a space or a capital counts): 2; in a letter or a digit: 2 (listed under Readings in doubt below). "
                + "An answer is taken as the block's text when it was not cut off at 300 tokens, has at least half its letters in the comic's writing, and is neither more than twice nor less than half as long as what the comic helper read, counted in letters and digits (4 either way are allowed to any block; where the helper read no letter, any answer of up to 200 is taken; the upper bound is held only where the helper's reader is at least 0.8 sure of the block (under that, any answer of up to 200 letters is taken)); an answer that keeps the balloon's own line breaks is joined into one line. Otherwise the comic helper's reading stands; and it stands where the helper's reader is at least 0.93 sure of a block and the answer differs from its reading in a letter or a digit (there the helper's letters were the right ones more often when measured; an answer that differs in marks alone is taken).\n"), note);
        assertTrue(note.contains("### Page 1 (1.png)\n\nRead a second time: 1 block; 1 reading differs from the comic helper's.\n\n| block | kind | English, as read | translation | set | look |\n| --- | --- | --- | --- | --- | --- |\n| 1 | speech | WHERE IS THE DOCTOR? | Wo ist der Arzt? | 28 px |  |\n"), note);
        assertTrue(note.contains("### Page 2 (2.png)\n\nRead a second time: 2 blocks; 1 reading differs from the comic helper's.\n\n"), note);
        assertTrue(note.contains("## Readings in doubt\n\nThe comic helper and the translator read the letters of 2 blocks differently. Each is here with both readings and the one the translation was made from. This is a doubt about what the page says, which a look at the page settles; the checks above do not see it.\n\n"
                + "| page | block | the comic helper read | the translator read | translated from |\n| --- | --- | --- | --- | --- |\n| 1 | 1 | WHERE IS THE DOCTOB? | WHERE IS THE DOCTOR? | the translator's reading |\n| 2 | 2 | (nothing) | HURRY! | the translator's reading |\n"), note);
        assertTrue(note.contains("A block read a second time is given as the translation was made from it: in the translator's reading where that was taken, in the comic helper's where it was not.\n"), note);
        assertTrue(note.contains("- A misreading that the comic helper and the translator share stands, and nothing marks it.") && !note.contains("read once"), note);
        String result = Translation.result(store, o, false, "cli");
        assertTrue(result.contains(" The translator read the lettering of 3 balloons a second time, from a picture of each: 2 were read differently in letters, and the note lists each with both readings."), result);
        // the speed of a comic read twice is kept apart from one read once
        assertTrue(Translation.charsPerSecond(store, "stand-in", ComicTranslation.speedKey("checked", true)) > 0); assertEquals(0, Translation.charsPerSecond(store, "stand-in", ComicTranslation.speedKey("checked")));

        // the quick tier reads, translates and sets: nothing is cut out, the model is asked for no block, and the helper's reading stands
        Path second = Files.createDirectories(tmp.resolve("second")), script2 = second.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(second, script2);
        Model quick = german();
        Translation.Outcome q = Translation.run(store, english(second, script2), "German", "", "quick", null, null, ComicTranslationTest.seats(quick), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        assertFalse(ComicStandIn.asked(script2).contains("crops")); assertTrue(quick.blocks.isEmpty());
        assertTrue(TranslationTest.inside(quick.users.get(0)).contains("LINES:\n1. WHERE IS THE DOCTOB?\n"), quick.users.get(0));
        assertEquals(2, q.units(), "the block the helper read nothing in has no words");
        assertEquals(new ComicTranslation.Twice(ComicTranslation.ONCE_QUICK, 0, 0, 0, 0, Map.of(), List.of()), q.comic().twice());
        String quickNote = Files.readString(q.note());
        assertTrue(quickNote.contains("- Lettering read once, by the comic helper: the quick tier has no second reading of it.\n") && !quickNote.contains("Readings in doubt") && !quickNote.contains("Read a second time"), quickNote);
        assertTrue(quickNote.contains("- The lettering was read once in this run: a misreading stands, marked only where the helper says \"look\". At the checked tier and above a translator that sees pictures reads each balloon a second time.\n"), quickNote);
        assertFalse(Translation.result(store, q, false, "cli").contains("a second time"));

        // a translator that does not see pictures cannot read a balloon: the helper's reading stands at every tier
        Config.set(ModelEntries.SEES, "no");
        Path third = Files.createDirectories(tmp.resolve("third")), script3 = third.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(third, script3);
        Model blind = german();
        Translation.Outcome b = Translation.run(store, english(third, script3), "German", "", "checked", null, null, ComicTranslationTest.seats(blind).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        assertFalse(ComicStandIn.asked(script3).contains("crops")); assertTrue(blind.blocks.isEmpty()); assertEquals(ComicTranslation.ONCE_BLIND, b.comic().twice().how());
        assertTrue(Files.readString(b.note()).contains("- Lettering read once, by the comic helper: the translator does not see pictures, so it could not read the balloons a second time.\n"), Files.readString(b.note()));

        // Japanese is never read a second time, whatever the tier and the translator: its own reader is the better one
        Config.set(ModelEntries.SEES, "yes");
        for (String tier : List.of("checked", "thorough")) {
            Path dir = Files.createDirectories(tmp.resolve("ja-" + tier)), scriptJa = dir.resolve("script");
            ComicHelper.READER = () -> ComicTranslationTest.standIn(dir, scriptJa);
            Path manga = ComicPlanTest.lettered(dir.resolve("manga.cbz"), scriptJa, 2, "先生はどこですか", true);
            Model ja = new Model(); ja.english.put("先生はどこですか", "Where is the doctor?"); ja.name = s -> "none";
            Translation.Outcome j = Translation.run(store, manga, "en", "", tier, null, null, ComicTranslationTest.seats(ja).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "", ComicTranslation.Said.NOTHING);
            assertFalse(ComicStandIn.asked(scriptJa).contains("crops"), tier); assertTrue(ja.blocks.isEmpty(), tier);
            assertEquals(new ComicTranslation.Twice(ComicTranslation.ONCE_JAPANESE, 0, 0, 0, 0, Map.of(), List.of()), j.comic().twice());
            String jaNote = Files.readString(j.note());
            assertFalse(jaNote.contains("- Lettering read") || jaNote.contains("Readings in doubt") || jaNote.contains("Read a second time"), jaNote);
            assertTrue(jaNote.contains("- Japanese lettering is read once, by the comic helper: a model that sees reads it worse than the helper's reader does, so it is not read a second time. A misreading stands, marked only where the helper says \"look\"."), jaNote);
        }
    }

    @Test void anAnswerThatIsNoReadingLeavesTheHelpersAndTheNoteSaysWhy(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        // one page of seven balloons: the model declines one, rambles on one, says nothing of one, answers one in two lines and one in another writing, reads one; the helper cuts no picture of the last
        Path cbz = tmp.resolve("issue.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        String[] lines = {"FIRST LINE HERE", "SECOND LINE HERE", "THIRD LINE HERE", "FOURTH LINE HERE", "FIFTH LINE HERE", "SIXTH LINE HERE", "SEVENTH LINE HERE"};
        ObjectNode[] blocks = new ObjectNode[7];
        for (int k = 0; k < 7; k++) blocks[k] = ComicStandIn.block("b0" + (k + 1), "speech", lines[k], new int[]{40, 20 + 90 * k, 240, 90 + 90 * k});
        blocks[6].put("no_crop", true);
        blocks[1].put("sure", 0.85); blocks[5].put("sure", 0.9031);   // how sure the helper's reader says it is: printed; from 0.8 its length holds an answer, under 0.93 its letters do not
        ((ObjectNode) blocks[2].get("balloon")).put("found", "open ground");   // a sentence lettered on open flat ground, which the helper reads and letters as a balloon
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, blocks));
        Model m = new Model();
        m.second = lettered -> switch (lettered) {
            case "FIRST LINE HERE" -> throw new Declined("stand-in", "I am not able to help with that.", Declined.How.REFUSAL);
            case "SECOND LINE HERE" -> "SECOND LINE HERE AND THEN A GREAT DEAL MORE THAT NO BALLOON HOLDS";
            case "THIRD LINE HERE" -> "";
            case "FOURTH LINE HERE" -> "FOURTH LINE\nHERE";
            case "FIFTH LINE HERE" -> "ПЯТАЯ СТРОКА ТУТ";
            default -> lettered + "!";
        };
        Translation.Outcome o = Translation.run(store, cbz, "German", "", "checked", null, null, ComicTranslationTest.seats(m).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        assertEquals(6, m.blocks.size(), "a block the helper cut no picture of is not asked");
        String page = TranslationTest.inside(m.users.get(0));
        assertTrue(page.contains("LINES:\n1. FIRST LINE HERE\n2. SECOND LINE HERE\n3. THIRD LINE HERE\n4. FOURTH LINE HERE\n5. FIFTH LINE HERE\n6. SIXTH LINE HERE!\n7. SEVENTH LINE HERE"), "the helper's reading stands wherever the answer is no reading: " + page);
        Map<String, Integer> why = Map.of("the translator declined to read it", 1, "the answer is much longer than what the comic helper read", 1, "no answer came", 1, "most of the answer's letters are not in the comic's writing", 1);
        assertEquals(new ComicTranslation.Twice(ComicTranslation.TWICE, 6, 2, 3, 2, why, List.of()), o.comic().twice(), "the answer in two lines is the balloon's own two lines: a reading, the same as the helper's");
        String note = Files.readString(o.note());
        assertTrue(note.contains("Blocks read twice: 6; readings taken as the block's text: 2; readings that differ from the comic helper's in any character (a mark, a space or a capital counts): 3; in a letter or a digit: 2 (listed under Readings in doubt below); answers not taken: 1 (the translator declined to read it), 1 (the answer is much longer than what the comic helper read), 1 (no answer came), 1 (most of the answer's letters are not in the comic's writing). An answer is taken"), note);
        assertTrue(note.contains("Read a second time: 6 blocks; 3 readings differ from the comic helper's. Not taken, the comic helper's reading standing: block 1 (the translator declined to read it), block 2 (the answer is much longer than what the comic helper read), block 3 (no answer came), block 5 (most of the answer's letters are not in the comic's writing).\n\n"), "a block whose answer was not taken is named, also where no answer came and nothing else lists it: " + note);
        assertTrue(note.contains("| 1 | 2 | SECOND LINE HERE (sure 0.85) | SECOND LINE HERE AND THEN A GREAT DEAL MORE THAT NO BALLOON HOLDS | the comic helper's reading: the answer is much longer than what the comic helper read |\n"
                + "| 1 | 5 | FIFTH LINE HERE | ПЯТАЯ СТРОКА ТУТ | the comic helper's reading: most of the answer's letters are not in the comic's writing |\n"), note);
        assertFalse(note.contains("| 1 | 6 | SIXTH LINE HERE"), "a reading that differs in a mark alone is no doubt about the letters: " + note);
        // the helper's own number stands beside the reading where it gave one, and under 0.93 changes nothing: a block it was unsure of keeps its reading when the answer is no reading, one it was surer of takes the translator's
        assertTrue(note.contains("\"sure\" is the helper's reader's own confidence in what it read in the block itself, from 0 to 1 (the lowest of its lines'), also where the text beside it is the translator's reading. Where it is at least 0.93 the helper's letters stand against a second reading that differs in one; under that the number is a hint, which decides nothing."), note);
        assertTrue(note.contains("| block | kind | English, as read | sure | translation | set | look |\n| --- | --- | --- | --- | --- | --- | --- |\n| 1 | speech | FIRST LINE HERE |  | EN FIRST LINE HERE | 28 px |  |\n| 2 | speech | SECOND LINE HERE | 0.85 | EN SECOND LINE HERE | 28 px |  |\n| 3 | speech on open ground | THIRD LINE HERE |  | EN THIRD LINE HERE | 28 px |  |\n"), note);
        assertTrue(note.contains("| 6 | speech | SIXTH LINE HERE! | 0.90 | EN SIXTH LINE HERE! | 28 px |  |\n"), note);

        // a helper that has no crops (one set up by an earlier version of the package): every page stands as it was read, and the note says so
        Path old = Files.createDirectories(tmp.resolve("old")), scriptOld = old.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(old, scriptOld);
        Path issue = english(old, scriptOld);
        Files.writeString(scriptOld.resolve("no-crops"), "");
        Model none = german();
        Translation.Outcome stands = Translation.run(store, issue, "German", "", "checked", null, null, ComicTranslationTest.seats(none).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        assertTrue(none.blocks.isEmpty()); assertEquals(2, stands.units());
        assertEquals(2, stands.comic().twice().notCut().size()); assertEquals(0, stands.comic().twice().asked());
        assertTrue(stands.comic().twice().on() && !stands.comic().twice().made(), "it was to be read twice, and nothing was");
        String standsNote = Files.readString(stands.note());
        assertTrue(standsNote.contains("- Lettering read once, by the comic helper: it was to be read a second time by the translator, and the helper could not cut the blocks out of page 1 (unknown op crops: script, read, cut, join, set, picture or quit); page 2 (unknown op crops: script, read, cut, join, set, picture or quit).\n"), standsNote);
        assertFalse(standsNote.contains("Readings in doubt") || standsNote.contains("Read a second time:") || standsNote.contains("share stands"), standsNote);
        assertTrue(standsNote.contains("- The lettering was read once in this run: a misreading stands, marked only where the helper says \"look\"."), standsNote);
        assertFalse(Translation.result(store, stands, false, "cli").contains("a second time"), "nothing was read a second time, so nothing says it was");
    }

    /**
     * Read live (2026-11-14): on a manga in Korean set in a printed face the helper's reader was right and sure, and the translator's
     * reading of the same block took its place with a wrong syllable ("came up" became "crashed"). Of the blocks the two read
     * differently in a letter where the helper was at least 0.93 sure, a reader found the helper's the page's in 8 and the
     * translator's in 1. Where the text is known (80 pages in four languages) the rule leaves fewer characters wrong than taking
     * every answer (103, 18, 14, 16 against 107, 19, 18, 16), and not asking at all loses the marks the translator reads better.
     */
    @Test void whereTheHelperIsSureItsLettersStandAndTheTranslatorStillCorrectsTheMarks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        Path cbz = tmp.resolve("issue.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        String[] lines = {"FIRST LINE HERE", "SECOND LINE HERE", "THIRD LINE HERE", "FOURTH LINE HERE", "FIFTH LINE HERE"};
        ObjectNode[] blocks = new ObjectNode[5];
        for (int k = 0; k < 5; k++) blocks[k] = ComicStandIn.block("b0" + (k + 1), "speech", lines[k], new int[]{40, 20 + 90 * k, 240, 90 + 90 * k});
        blocks[0].put("sure", 0.99); blocks[1].put("sure", 0.93); blocks[2].put("sure", 0.97); blocks[3].put("sure", 0.92);   // the fifth has no number: an older helper, or a block no line was read in
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, blocks));
        Model m = new Model();
        m.second = lettered -> lettered.startsWith("THIRD") ? "Third line, here!" : lettered.replace("LINE", "LIME");   // a letter read otherwise in every block but the third, whose marks and capitals alone differ
        Translation.Outcome o = Translation.run(store, cbz, "German", "", "checked", null, null, ComicTranslationTest.seats(m).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "English", ComicTranslation.Said.NOTHING);
        assertEquals(List.of(lines), m.letteredAsked, "every block is read a second time, the sure ones too");
        String page = TranslationTest.inside(m.users.get(0));
        assertTrue(page.contains("LINES:\n1. FIRST LINE HERE\n2. SECOND LINE HERE\n3. Third line, here!\n4. FOURTH LIME HERE\n5. FIFTH LIME HERE"), "the helper's letters stand where it is sure, its marks give way, and under 0.93 the answer is taken: " + page);
        assertEquals(new ComicTranslation.Twice(ComicTranslation.TWICE, 5, 3, 5, 4, Map.of(ComicTranslation.SURE_OF_ITS_LETTERS, 2), List.of()), o.comic().twice());
        String note = Files.readString(o.note());
        assertTrue(note.contains("answers not taken: 2 (the comic helper is at least 0.93 sure of its own reading, and the answer differs from it in a letter or a digit)"), note);
        assertTrue(note.contains("| 1 | 1 | FIRST LINE HERE (sure 0.99) | FIRST LIME HERE | the comic helper's reading: the comic helper is at least 0.93 sure of its own reading, and the answer differs from it in a letter or a digit |"), note);
        assertTrue(note.contains("and it stands where the helper's reader is at least 0.93 sure of a block and the answer differs from its reading in a letter or a digit"), note);
        // the rule alone
        ComicTranslation.Answer lime = new ComicTranslation.Answer("FIRST LIME HERE", false, false), marks = new ComicTranslation.Answer("first line... here?", false, false);
        assertEquals(ComicTranslation.SURE_OF_ITS_LETTERS, ComicTranslation.notAReading("FIRST LINE HERE", lime, "latin", 0.93));
        assertEquals("", ComicTranslation.notAReading("FIRST LINE HERE", lime, "latin", 0.9299)); assertEquals("", ComicTranslation.notAReading("FIRST LINE HERE", lime, "latin", -1));
        assertEquals("", ComicTranslation.notAReading("FIRST LINE HERE", marks, "latin", 1.0), "marks, spaces and capitals are the translator's to correct");
        assertEquals("", ComicTranslation.notAReading("", lime, "latin", 1.0), "a block the helper read no letter in has no letters to stand");
    }

    /**
     * Read live (2026-11-14): on hand-lettered pages the helper read "N" of a caption that says "MEANWHILE--" and "o B u" of a whole
     * sentence, and the translator's right reading was refused as much longer than that; six such answers on three comics, the
     * helper 0.11 to 0.77 sure of what it had read, every one the right reading. A reading the helper itself is not sure of is no
     * measure of how long the block is: the upper bound is held only where the helper is at least 0.8 sure.
     */
    @Test void anAnswerIsNotHeldToTheLengthOfAReadingTheHelperIsNotSureOf() {
        ComicTranslation.Answer whole = new ComicTranslation.Answer("MEANWHILE--", false, false), runaway = new ComicTranslation.Answer("A".repeat(201), false, false);
        String longer = "the answer is much longer than what the comic helper read";
        assertEquals("", ComicTranslation.notAReading("N", whole, "latin", 0.13)); assertEquals("", ComicTranslation.notAReading("N", whole, "latin", 0.77));
        assertEquals(longer, ComicTranslation.notAReading("N", whole, "latin", 0.8)); assertEquals(longer, ComicTranslation.notAReading("N", whole, "latin", 0.92));
        assertEquals(longer, ComicTranslation.notAReading("N", whole, "latin", -1), "a helper that gives no number is held to as before");
        assertEquals("the answer is over 200 letters for a block the comic helper was under 0.8 sure of", ComicTranslation.notAReading("N", runaway, "latin", 0.13));
        assertEquals("the answer is much shorter than what the comic helper read", ComicTranslation.notAReading("A LONG LINE OF LETTERING HERE", new ComicTranslation.Answer("A", false, false), "latin", 0.13), "the lower bound stands");
        assertTrue(ComicTranslation.SECOND_RULE.contains("the upper bound is held only where the helper's reader is at least 0.8 sure of the block (under that, any answer of up to 200 letters is taken)"), ComicTranslation.SECOND_RULE);
    }

    @Test void aPageReadTwiceIsNotReadTwiceAgainByAJobThatResumes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        // three pages of two balloons; the helper misreads the first balloon of each page
        Path cbz = tmp.resolve("issue.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 3);
        int[][] b = ComicStandIn.balloons(500, 700);
        Model m = new Model();
        for (int k = 0; k < 3; k++) {
            ObjectNode misread = ComicStandIn.block("b01", "speech", "PAGE " + "ABC".charAt(k) + " FIRST BALLOOM", b[0]); misread.put("lettered", "PAGE " + "ABC".charAt(k) + " FIRST BALLOON");
            ComicStandIn.says(script, p.get(k), ComicStandIn.read(500, 700, misread, ComicStandIn.block("b02", "speech", "PAGE " + "ABC".charAt(k) + " SECOND BALLOON", b[1])));
        }
        // the first process is stopped inside the second page: its first balloon read a second time, its second not yet
        try (Checkpoint cp = Checkpoint.open(store, "J-0701", Translation.JOB_KIND, "b")) {
            IOException stopped = assertThrows(IOException.class, () -> Translation.run(store, cbz, "German", "", "checked", null, null, Translation.kept(ComicTranslationTest.seats(m), cp).withBack(false),
                    null, () -> m.blocks.size() >= 3, cp, "", "J-0701", "English", ComicTranslation.Said.NOTHING));
            assertEquals("Stopped while the lettering was being read a second time; nothing was written.", stopped.getMessage());
        }
        assertEquals(List.of("PAGE A FIRST BALLOON", "PAGE A SECOND BALLOON", "PAGE B FIRST BALLOON"), m.letteredAsked);
        assertEquals(3, ComicStandIn.requests(script, "read").size()); assertEquals(2, ComicStandIn.requests(script, "crops").size());
        // the process that resumes: the first page's readings come from the page's own kept reading, the second page's first from the job's
        // kept calls, and the model is asked only for what it had not read
        Model again = new Model();
        Translation.Outcome o;
        try (Checkpoint cp = Checkpoint.open(store, "J-0701", Translation.JOB_KIND, "b")) {
            assertTrue(cp.resumed());
            o = Translation.run(store, cbz, "German", "", "checked", null, null, Translation.kept(ComicTranslationTest.seats(again), cp).withBack(false), null, () -> false, cp, "", "J-0701", "English", ComicTranslation.Said.NOTHING);
        }
        assertEquals(List.of("PAGE B SECOND BALLOON", "PAGE C FIRST BALLOON", "PAGE C SECOND BALLOON"), again.letteredAsked, "no block is read a second time twice");
        assertEquals(3, ComicStandIn.requests(script, "read").size(), "and no page is read by the helper again");
        assertEquals(4, ComicStandIn.requests(script, "crops").size(), "the first page's blocks are not cut out again; the second page's are, for the balloon still to read");
        assertEquals(new ComicTranslation.Twice(ComicTranslation.TWICE, 6, 6, 3, 3, Map.of(), List.of()), o.comic().twice());
        for (int k = 0; k < 3; k++) {
            assertEquals(new ComicTranslation.Second("PAGE " + "ABC".charAt(k) + " FIRST BALLOOM", "PAGE " + "ABC".charAt(k) + " FIRST BALLOON", true, ""), o.comic().rows().get(k).get(0).block().second(), "both readings, from whichever process made them");
            assertTrue(TranslationTest.inside(again.users.get(k)).contains("LINES:\n1. PAGE " + "ABC".charAt(k) + " FIRST BALLOON\n2. PAGE " + "ABC".charAt(k) + " SECOND BALLOON"), again.users.get(k));
        }
        // a third process on the finished job's work reads nothing a second time at all
        Model third = new Model();
        try (Checkpoint cp = Checkpoint.open(store, "J-0701", Translation.JOB_KIND, "b")) {
            Translation.run(store, cbz, "German", "", "checked", tmp.resolve("again.cbz"), null, Translation.kept(ComicTranslationTest.seats(third), cp).withBack(false), null, () -> false, cp, "", "J-0701", "English", ComicTranslation.Said.NOTHING);
        }
        assertTrue(third.letteredAsked.isEmpty()); assertEquals(4, ComicStandIn.requests(script, "crops").size());
    }

    @Test void aJobWhoseLanguageIsFoundAtItsStartSaysWhichAsSoonAsItIsFoundAndInItsResult(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        Path cbz = ComicPlanTest.lettered(tmp.resolve("manhwa.cbz"), script, 2, "의사 선생님은 어디에 계세요?", false);
        // filed while the plan could not look at the pages (a helper that an earlier release set up, still short of its readers): the language is the run's to find
        ComicHelper.INSTALLED = () -> false;
        Translation.Plan plan = Translation.plan(store, cbz, "English", "", "quick", "stand-in", "", "", "", "");
        assertEquals("", plan.from());
        ObjectNode job = ComicStandIn.M.createObjectNode(); job.put("job_id", "J-0801"); job.put("kind", Translation.JOB_KIND);
        job.set("args", Translation.args(cbz, "manhwa.cbz", plan, null, "", "cli", "", "", "", "", "").put("comic_setup", "yes"));
        ComicHelper.INSTALLED = () -> true;
        Model m = new Model(); m.english.put("의사 선생님은 어디에 계세요?", "Where is the doctor?");
        Translation.SeatMaker seats = Translation.SEATS; Predicate<String> answers = Translation.DRIVE_ANSWERS;
        Translation.SEATS = (drive, model, judge, back) -> ComicTranslationTest.seats(m); Translation.DRIVE_ANSWERS = url -> true;
        List<ObjectNode> facts = new ArrayList<>(); List<String> told = new ArrayList<>();
        try {
            String result = Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", p -> { String w = TranslateCli.progressWords(p); if (!told.contains(w)) told.add(w); }, () -> false, Checkpoint.NONE, f -> facts.add(f.deepCopy()));
            assertTrue(result.contains(" Quick, from Korean into English, as cbz."), "the result says which language the run found: " + result);
        } finally { Translation.SEATS = seats; Translation.DRIVE_ANSWERS = answers; }
        assertTrue(told.contains("the comic helper read its lettering as Korean (to name another language: --from <language>); reading the pages, 0 of 2"), "the person watching is told at once, before any page is read: " + told);
        assertTrue(told.indexOf("the comic helper read its lettering as Korean (to name another language: --from <language>); reading the pages, 0 of 2") < told.indexOf("reading the pages, 1 of 2"), told.toString());
        // the job's record holds it from the moment it is found, so every place that shows the job says it while the pages are still being read
        ObjectNode found = facts.stream().filter(f -> f.path("ran").has("from")).findFirst().orElseThrow();
        assertEquals("ko", found.path("ran").path("from").asText());
        assertTrue(facts.indexOf(found) < facts.size() - 1 || facts.size() == 2, facts.toString());
        // a language the person named, or the plan found, is said from the start and nothing is told twice
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        List<String> named = new ArrayList<>();
        Translation.run(store, cbz, "English", "", "quick", tmp.resolve("named.cbz"), null, ComicTranslationTest.seats(m), p -> named.add(TranslateCli.progressWords(p)), () -> false, Checkpoint.NONE, "", "", "Korean", ComicTranslation.Said.NOTHING);
        assertTrue(named.stream().noneMatch(w -> w.contains("read its lettering as")), named.toString());
    }

    @Test void theHelperCutsABlockOutForAModelThatSeesAndAHelperWithoutItSaysSo(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Path cbz = english(tmp, script);
        try (ComicHelper.Reader helper = ComicTranslationTest.standIn(tmp, script)) {
            ComicPages.Volume volume = ComicPages.open(cbz, Files.createDirectories(tmp.resolve("work")));
            Path page = volume.pages().get(1).file();
            JsonNode read = helper.read(page, "en");
            List<ComicHelper.Crop> all = helper.crops(page, read, List.of("b01", "b02"), tmp.resolve("crops"));
            assertEquals(List.of("b01", "b02"), all.stream().map(ComicHelper.Crop::id).toList());
            for (ComicHelper.Crop c : all) assertTrue(Files.isRegularFile(c.file()) && c.file().startsWith(tmp.resolve("crops")) && c.file().getFileName().toString().endsWith(".png"), c.toString());
            assertEquals("HURRY!", ComicStandIn.lettered(Vision.dataUrl(Files.readAllBytes(all.get(1).file()), "image/png")), "the picture carries what is lettered on the block, not what the helper read there");
            assertEquals(List.of("b02"), helper.crops(page, read, List.of("b02"), tmp.resolve("one")).stream().map(ComicHelper.Crop::id).toList());
            String request = ComicStandIn.requests(script, "crops").get(0);
            assertTrue(request.contains("\"op\":\"crops\"") && request.contains("\"image\":\"" + page.toAbsolutePath()) && request.contains("\"ids\":[\"b01\",\"b02\"]") && request.contains("\"out\":\"" + tmp.resolve("crops").toAbsolutePath()), request);
            // a helper that knows no such request answers with its error, and the process lives on
            Files.writeString(script.resolve("no-crops"), "");
            ComicHelper.Failed none = assertThrows(ComicHelper.Failed.class, () -> helper.crops(page, read, List.of("b01"), tmp.resolve("none")));
            assertTrue(none.getMessage().contains("unknown op crops"), none.getMessage());
            assertEquals("THE PATIENT IS WAITING.", helper.read(page, "en").path("blocks").path(0).path("text").asText());
        }
        // a reader that is no local process, and has no way to cut a block out, says so the same way
        ComicHelper.Reader bare = new ComicHelper.Reader() {
            @Override public JsonNode script(List<Path> images) { return null; }
            @Override public JsonNode read(Path image, String language, String order) { return null; }
            @Override public JsonNode cut(Path image, Path out) { return null; }
            @Override public void join(List<ComicHelper.Piece> pieces, int width, int height, Path out) { }
            @Override public JsonNode set(Path image, JsonNode page, ObjectNode texts, String script, List<String> keep, Path out) { return null; }
            @Override public void picture(Path image, List<Map.Entry<String, double[]>> boxes, Path out) { }
            @Override public String loaded() { return ""; }
            @Override public void abort() { }
            @Override public void close() { }
        };
        assertEquals("this helper cuts no blocks out of a page", assertThrows(ComicHelper.Failed.class, () -> bare.crops(cbz, null, List.of(), tmp)).getMessage());
    }

    @Test void anAnswerThatRanToTheLimitIsKnownFromTheServersOwnCountAndTheCallIsColdAndSmall() throws Exception {
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        int[] written = {300};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // a port of the test's own
        server.createContext("/v1/chat/completions", x -> {
            requests.add(ComicStandIn.M.readTree(x.getRequestBody().readAllBytes()));
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"WHERE IS THE DOCTOR?\"},\"finish_reason\":\"" + (written[0] >= 300 ? "length" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":700,\"completion_tokens\":" + written[0] + "}}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            try (var os = x.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            Researcher.Drive drive = Researcher.drive("http://127.0.0.1:" + server.getAddress().getPort(), "m");
            Seats seats = new Seats(drive, "m", drive, "m");
            ComicTranslation.Answer cut = ComicTranslation.second(seats, "data:image/png;base64,iVBORw0KGgo=", "English", false);
            assertTrue(cut.atLimit(), "300 tokens written of 300 allowed: the answer ran to the limit");
            assertEquals("the answer ran to the limit of 300 tokens", ComicTranslation.notAReading("WHERE IS THE DOCTOB?", cut, "latin"));
            written[0] = 9;
            ComicTranslation.Answer whole = ComicTranslation.second(seats, "data:image/png;base64,iVBORw0KGgo=", "English", false);
            assertFalse(whole.atLimit()); assertEquals("WHERE IS THE DOCTOR?", whole.text()); assertEquals("", ComicTranslation.notAReading("WHERE IS THE DOCTOB?", whole, "latin"));
            JsonNode sent = requests.stream().filter(r -> r.has("messages")).findFirst().orElseThrow();
            assertEquals(300, sent.path("max_tokens").asInt()); assertEquals(0.0, sent.path("temperature").asDouble(1)); assertFalse(sent.path("chat_template_kwargs").path("enable_thinking").asBoolean(true), "no thinking, where the server takes the word");
            assertEquals(1, sent.path("messages").size());
            JsonNode content = sent.path("messages").path(0).path("content");
            assertEquals("data:image/png;base64,iVBORw0KGgo=", content.path(0).path("image_url").path("url").asText()); assertEquals(ASK, content.path(1).path("text").asText());
        } finally { server.stop(0); }
    }

    @Test void aBlockWhoseLetteringIsDrawnIntoTheArtIsLeftAsArtAndTranslatedInTheNote(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        // a sound drawn through a balloon, which the helper's reader took for speech: the setter neither paints it over nor sets it
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        int[][] b = ComicStandIn.balloons(500, 700);
        ObjectNode drawn = ComicStandIn.block("b02", "speech", "ドドドド", b[1]); drawn.put("drawn_into_art", true);
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "先生はどこですか", b[0]), drawn));
        Model m = new Model(); m.english.put("先生はどこですか", "Where is the doctor?"); m.english.put("ドドドド", "Rumble"); m.name = s -> "none";
        Translation.Outcome o = Translation.run(store, cbz, "en", "", "checked", null, null, ComicTranslationTest.seats(m).withBack(false), null, () -> false, Checkpoint.NONE, "", "", "", ComicTranslation.Said.NOTHING);
        ComicTranslation.Done d = o.comic();
        assertEquals(1, d.leftAsArt()); assertEquals(1, d.set()); assertEquals(0, d.notSetCount(), "art that stays is no line that was not set");
        assertTrue(m.shortened.isEmpty() && d.fitted().isEmpty(), "and no line that did not fit: it is not sent back to be said shorter");
        String note = Files.readString(o.note());
        assertTrue(note.contains("| 2 | speech | ドドドド | Rumble | lettering drawn into the art: left as art |  |\n"), note);
        assertTrue(note.contains(" Left as art, as a drawn sound word is: 1 balloon whose lettering is drawn into the art; the tables below hold its translation.\n") && !note.contains("Not set:"), note);
        assertTrue(note.contains("A balloon whose lettering joins the art is left as art too, and translated in the tables above.\n"), note);
        assertTrue(Translation.result(store, o, false, "cli").contains(" Lettered into their balloons: 1 of 2; 1 is left as art, its lettering being drawn into the art, and translated in the note."), Translation.result(store, o, false, "cli"));
        // another reason is a block that was not set, said with its reason as before
        assertFalse(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", false).put("why_not", "the lettering is drawn into the art: left as art"), 20));
        assertTrue(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", false).put("why_not", "the text does not go into the balloon at the smallest size"), 20));
    }

    @Test void thePlanSaysWhoReadsTheLetteringCountsTheBlocksCallsAndTheJobSaysWhoDidWhat(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ComicHelper.INSTALLED = () -> false;   // no helper to look with: the language is the person's word or the run's to find
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 30);
        // a Korean comic, a translator that sees: read twice at checked and above, and the plan's time counts a call for each speech block
        Config.set(ModelEntries.SEES, "yes");
        Translation.Plan twice = Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "");
        assertEquals(ComicTranslation.TWICE, twice.comic().second()); assertEquals("twice", twice.comic().json().path("second_reading").asText());
        assertTrue(twice.saidFor().endsWith("; each page goes to the translator as a picture, beside the text read from it; each balloon's lettering is read a second time, by the translator from a picture of the balloon; where the two readings differ, the translator's is the one used and the note lists both"), twice.saidFor());
        assertEquals(30 * 11, twice.quickSeconds(), "the quick tier reads once"); assertEquals(Math.round(30 * (14 + 1.35 * 6.5)), twice.checkedSeconds(), "1.35 s a block and 6.5 blocks a page, until a comic read twice has been timed");
        assertEquals(Math.round(30 * (22 + 1.35 * 6.5)), twice.reviewedSeconds()); assertEquals(Math.round(30 * (30 + 1.35 * 6.5)), twice.thoroughSeconds());
        Translation.Plan quick = Translation.plan(store, cbz, "English", "", "quick", "stand-in", "", "", "Korean", "");
        assertEquals(ComicTranslation.ONCE_QUICK, quick.comic().second()); assertTrue(quick.saidFor().endsWith("; the lettering is read once, by the comic helper: the quick tier has no second reading of it"), quick.saidFor());
        // Japanese: read once, and the plan is as it always was
        Translation.Plan japanese = Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Japanese", "");
        assertEquals(ComicTranslation.ONCE_JAPANESE, japanese.comic().second()); assertEquals(30 * 14, japanese.checkedSeconds());
        assertTrue(japanese.saidFor().endsWith("each page goes to the translator as a picture, beside the text read from it"), japanese.saidFor());
        // the language still to be found: so is this
        Translation.Plan later = Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "", "");
        assertEquals(ComicTranslation.TWICE_LATER, later.comic().second()); assertEquals(30 * 14, later.checkedSeconds(), "nothing is added for what may not be done");
        assertTrue(later.saidFor().endsWith("; whether the lettering is read a second time, by the translator from a picture of each balloon, is found when the run starts: it is for a comic that is not Japanese when the translator sees pictures"), later.saidFor());
        // a translator that does not see
        Config.set(ModelEntries.SEES, "no");
        Translation.Plan blind = Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "");
        assertEquals(ComicTranslation.ONCE_BLIND, blind.comic().second()); assertEquals(30 * 14, blind.checkedSeconds());
        assertTrue(blind.saidFor().endsWith("; the lettering is read once, by the comic helper: a second reading takes a translator that sees pictures"), blind.saidFor());
        Config.set(ModelEntries.SEES, "yes");
        // the speed of a comic read twice, once one has run, is the next such plan's; a comic read once keeps its own
        Translation.recordSpeed(store, "stand-in", ComicTranslation.speedKey("checked"), 30, 240);
        assertEquals(240, Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Japanese", "").checkedSeconds());
        assertEquals(Math.round(240 + 30 * 1.35 * 6.5), Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "").checkedSeconds(), "the page as timed on a comic read once, and the blocks' calls");
        Translation.recordSpeed(store, "stand-in", ComicTranslation.speedKey("checked", true), 30, 600);
        assertEquals(600, Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "").checkedSeconds());
        assertEquals(240, Translation.plan(store, cbz, "English", "", "checked", "stand-in", "", "", "Japanese", "").checkedSeconds());
        // a library where only a comic read twice has been timed: that plan's time is the measured one, and is not called a first guess
        LibraryStore only = new LibraryStore(tmp.resolve("lib-twice")); only.init();
        assertFalse(Translation.plan(only, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "").measured());
        assertTrue(Translation.plan(only, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "").saidFor().contains("a first guess: nothing has been timed on this model yet"));
        Translation.recordSpeed(only, "stand-in", ComicTranslation.speedKey("checked", true), 30, 600);
        Translation.Plan timedTwice = Translation.plan(only, cbz, "English", "", "checked", "stand-in", "", "", "Korean", "");
        assertEquals(600, timedTwice.checkedSeconds()); assertTrue(timedTwice.measured(), "the time of a comic read twice on this model is a measured time");
        assertFalse(timedTwice.saidFor().contains("a first guess"), timedTwice.saidFor());
        assertFalse(Translation.plan(only, cbz, "English", "", "checked", "stand-in", "", "", "Japanese", "").measured(), "a comic read once has not been timed there");
        // who does what: the job filed from a plan that knows says it; a run says what it did, whatever the plan knew
        ObjectNode a = Translation.args(cbz, "volume.cbz", twice, null, "", "cli", "", "", "", "", "Korean");
        ObjectNode job = Translation.JOB_DRAFT.apply(a);
        assertTrue(TranslationWho.facts(job).path("said").asText().startsWith("The pages are read by the comic helper on this machine, and the translator reads the lettering a second time. Translated by "), TranslationWho.facts(job).path("said").asText());
        assertTrue(TranslationWho.seatsSaid(TranslationWho.facts(job), Chrome.in("ja")).startsWith("ページを読むのは、このマシンのコミックヘルパーです。文字は翻訳モデルがもう一度読み取ります。"));
        ObjectNode unknown = Translation.JOB_DRAFT.apply(Translation.args(cbz, "volume.cbz", later, null, "", "cli", "", "", "", "", ""));
        assertTrue(TranslationWho.facts(unknown).path("said").asText().startsWith("The pages are read by the comic helper on this machine. Translated by "));
        assertTrue(TranslationWho.facts(unknown).path("said").asText().contains(" Checked, into English, as cbz."), "the language is still to be found: " + TranslationWho.facts(unknown).path("said").asText());
        unknown.putObject("ran").put("second_reading", true).put("from", "ko");
        assertTrue(TranslationWho.facts(unknown).path("said").asText().startsWith("The pages are read by the comic helper on this machine, and the translator reads the lettering a second time. "), TranslationWho.facts(unknown).path("said").asText());
        assertEquals("ko", TranslationWho.facts(unknown).path("from").asText()); assertTrue(TranslationWho.facts(unknown).path("said").asText().contains(" Checked, from Korean into English, as cbz."), "the language the run found is said once it is found: " + TranslationWho.facts(unknown).path("said").asText());
        job.putObject("ran").put("second_reading", false);
        assertTrue(TranslationWho.facts(job).path("said").asText().startsWith("The pages are read by the comic helper on this machine. "), "a run that could not (the helper cut nothing, the translator turned out not to see) says what it did");
        // the stage of a running job, as each surface words it
        ObjectNode stage = ComicStandIn.M.createObjectNode().put("phase", "reading-twice").put("units_done", 3).put("units_total", 30).put("quality", "checked");
        assertEquals("Reading the lettering a second time, page 3 of 30", RunProgress.stage(stage)); assertEquals("reading the lettering a second time, page 3 of 30", RunProgress.stageInSentence(stage));
        assertEquals("reading the lettering a second time, page 3 of 30", TranslateCli.progressWords(stage)); assertEquals("page 3 of 30 read a second time by the translator", Translation.said(stage));
        ObjectNode pages = ComicStandIn.M.createObjectNode().put("phase", "page-translating").put("units_done", 0).put("units_total", 30).put("quality", "checked");
        int once = RunProgress.percent(pages, 0, 1000), after = RunProgress.percent(pages.deepCopy().put("pass", "twice"), 0, 1000), during = RunProgress.percent(stage.deepCopy().put("units_done", 30), 0, 1000);
        assertTrue(after >= during && after > once, "the pages' share begins where the second reading's ended: " + once + " " + during + " " + after);
    }
}
