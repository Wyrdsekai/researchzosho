package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.ComicTranslationTest.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the first real runs of comics in other languages than Japanese showed (2026-11-14), each held by a test. A page whose one size
 * is held down by its original lettering sent every one of its lines back to be said shorter, and the shorter lines were kept though
 * they were set no larger: 61 of 126 balloons of a Korean comic lost words for nothing. A page of the same comic came back in Chinese
 * where Japanese was asked for, and no check saw it. The helper is the stand-in, the model a stand-in: no helper, no model, no page.
 */
class ComicFitAndLanguageTest {
    String home; BooleanSupplier installed; Supplier<ComicHelper.Reader> reader; Function<ModelEntries.Entry, Vision.Probe> probe;
    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        installed = ComicHelper.INSTALLED; reader = ComicHelper.READER; probe = LibraryProtocol.VISION_PROBE;
        ComicHelper.INSTALLED = () -> true;
        LibraryProtocol.VISION_PROBE = e -> new Vision.Probe(true, "red / blue", "it named a red square and a blue one");
    }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); ComicHelper.INSTALLED = installed; ComicHelper.READER = reader; LibraryProtocol.VISION_PROBE = probe; ComicHelper.manifestFrom(null); }

    @Test void aLineIsNotSentBackWhenSayingItShorterCouldNotMakeItLarger(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        // a page the helper sets at 14 px, its original lettering allowing no more: each line would take 28 px in its own balloon; the floor here is 20 px (two thirds of the 30 px Japanese it replaces)
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        int[][] b = ComicStandIn.balloons(500, 700);
        ObjectNode tight = ComicStandIn.block("b03", "speech", "明日の朝に来てください", b[2]); tight.put("fits", 20);   // and one balloon too small for its line at any size but 9 px
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, ComicStandIn.block("b01", "speech", "先生はどこですか", b[0]), ComicStandIn.block("b02", "speech", "手術は午後からです", b[1]), tight));
        Files.createDirectories(script); Files.writeString(script.resolve("page-px"), "14");
        Model m = new Model();
        m.english.put("先生はどこですか", "Where is the doctor?"); m.english.put("手術は午後からです", "The operation is this afternoon."); m.english.put("明日の朝に来てください", "Please come tomorrow morning."); m.name = s -> "none";
        m.shorter = line -> "Come tomorrow.";
        Translation.Outcome o;
        try (Checkpoint cp = Checkpoint.open(store, "J-0901", Translation.JOB_KIND, "b")) { o = Translation.run(store, cbz, "en", "", "checked", null, null, Translation.kept(ComicTranslationTest.seats(m), cp).withBack(false), null, () -> false, cp, "", "J-0901", "", ComicTranslation.Said.NOTHING); }
        assertEquals(List.of("12 | Please come tomorrow morning."), m.shortened, "the one line its own balloon is too small for goes back; the two that are small only because their page is do not: said shorter they would be set no larger");
        List<ComicTranslation.Fitted> fitted = o.comic().fitted();
        assertEquals(1, fitted.size()); assertEquals(new ComicTranslation.Fitted(2, "Please come tomorrow morning.", 9, 12, "Come tomorrow.", 14, true, ""), fitted.get(0), "it now goes in at the page's size");
        assertEquals(3, o.comic().belowFloor(), "all three are under the 20 px a line goes back at"); assertEquals(3, o.comic().heldByPage(), "and each as large as its page lets it be");
        String note = Files.readString(o.note());
        assertTrue(note.contains(" Went back: 1; kept: 1; lines still that small: 3, all of them as large as their page's own size lets them be (a page is set no larger than its original lettering): saying these shorter would not set them larger, so they are not sent back.\n"), note);
        assertTrue(note.contains("| 1 | speech | 先生はどこですか | Where is the doctor? | 14 px, the size of its whole page (a line goes back under 20 px when its own balloon is what holds it down) |  |"), note);
        // the helper says nothing of what a block's own text would take (a helper of an earlier release): the rule is as it was, a line under the floor goes back
        assertTrue(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", true).put("px", 14), 20));
        assertFalse(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", true).put("px", 14).put("largest_px", 28), 20));
        assertTrue(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", true).put("px", 14).put("largest_px", 14), 20));
        assertFalse(ComicTranslation.tooSmall(ComicStandIn.M.createObjectNode().put("set", true).put("px", 22).put("largest_px", 22), 20));
    }

    @Test void aShorterLineThatIsSetNoLargerIsNotKeptAndTheLineStandsAsItWas(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        int[][] b = ComicStandIn.balloons(500, 700);
        ObjectNode tight = ComicStandIn.block("b01", "speech", "先生は今日は手術で来られません", b[0]); tight.put("fits", 24);
        ObjectNode tighter = ComicStandIn.block("b02", "speech", "明日の朝に必ず来てください", b[1]); tighter.put("fits", 12);
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, tight, tighter));
        Model m = new Model();
        m.english.put("先生は今日は手術で来られません", "The doctor cannot come today because he is in surgery."); m.english.put("明日の朝に必ず来てください", "Please be sure to come tomorrow morning."); m.name = s -> "none";
        m.shorter = line -> line.startsWith("The doctor") ? "He's in surgery today." : "Come tomorrow morning.";   // the second is shorter, and still does not go into its balloon at more than 9 px
        Translation.Outcome o;
        try (Checkpoint cp = Checkpoint.open(store, "J-0902", Translation.JOB_KIND, "b")) { o = Translation.run(store, cbz, "en", "", "checked", null, null, Translation.kept(ComicTranslationTest.seats(m), cp).withBack(false), null, () -> false, cp, "", "J-0902", "", ComicTranslation.Said.NOTHING); }
        List<ComicTranslation.Fitted> fitted = o.comic().fitted();
        assertEquals(2, fitted.size());
        assertEquals(new ComicTranslation.Fitted(0, "The doctor cannot come today because he is in surgery.", 9, 12, "He's in surgery today.", 28, true, ""), fitted.get(0));
        assertEquals(new ComicTranslation.Fitted(1, "Please be sure to come tomorrow morning.", 9, 12, "Come tomorrow morning.", 9, false, "the shorter line was set no larger (9 px), so the line stands as it was translated"), fitted.get(1));
        // the page as it is written holds the whole line again: nothing was lost for a size that did not change
        JsonNode set = ComicStandIn.M.readTree(Files.readString(Checkpoint.dir(store, "J-0902").resolve("comic/out/p0001.png.set.json")));
        assertEquals("He's in surgery today.", set.path("texts").path("b01").path("text").asText()); assertEquals("Please be sure to come tomorrow morning.", set.path("texts").path("b02").path("text").asText());
        assertEquals(3, ComicStandIn.asked(script).stream().filter("set"::equals).count(), "the page set, set with the shorter lines, and set once more with the line that gained nothing as it was");
        String note = Files.readString(o.note());
        assertTrue(note.contains("| 1 | 2 | Please be sure to come tomorrow morning. | 9 px | 12 characters | Come tomorrow morning. |  | no: the shorter line was set no larger (9 px), so the line stands as it was translated |"), note);
        assertTrue(note.contains("| 2 | speech | 明日の朝に必ず来てください | Please be sure to come tomorrow morning. | 9 px, small beside the rest of its page (under 22 px) |"), note);
        assertTrue(note.contains(" Went back: 2; kept: 1; lines still that small: 1.\n"), note);
        // "at one size a page" is said with what it leaves out: the balloon whose text takes no more than 9 px; the range is the pages' sizes, not that balloon's
        assertTrue(note.contains("- Lettering: 2 of 2 balloons set, in capitals, at one size a page (from 28 px to 28 px on these pages) but for 1 balloon set smaller than its page (its text takes no more in its balloon, or a smaller size spared a bad line end); "), note);
        assertEquals(1, o.comic().ownSize());
    }

    @Test void theNoteCountsTheWordsBrokenAtALinesEnd(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.READER = () -> ComicTranslationTest.standIn(tmp, script);
        ComicStandIn.grown();
        // a manga into Korean: the helper breaks a Korean word between two syllables where a narrow balloon leaves no other way, and says so in the block's report
        Path cbz = tmp.resolve("volume.cbz");
        List<byte[]> p = ComicStandIn.volume(cbz, 1);
        int[][] b = ComicStandIn.balloons(500, 700);
        ObjectNode narrow = ComicStandIn.block("b01", "speech", "すみません", b[0]); narrow.put("hyphenations", 1);
        ComicStandIn.says(script, p.get(0), ComicStandIn.read(500, 700, narrow, ComicStandIn.block("b02", "speech", "今からだ", b[1])));
        Model m = new Model(); m.english.put("すみません", "죄송합니다..."); m.english.put("今からだ", "지금부터다..."); m.name = s -> "none";
        Translation.Outcome o = Translation.run(store, cbz, "ko", "", "quick", null, null, ComicTranslationTest.seats(m), null, () -> false, Checkpoint.NONE, "", "", "", ComicTranslation.Said.NOTHING);
        assertEquals(1, o.comic().brokenWords());
        String note = Files.readString(o.note());
        assertTrue(note.contains("; no line ends inside a phrase; 1 word is broken at a line's end (the tables below say in which balloon)."), "the head says what the tables mark: " + note);
        assertTrue(note.contains("| 1 | speech | すみません | 죄송합니다... | 28 px, 1 word broken |"), note);
    }

    @Test void aLineWrittenInAnotherWritingThanTheOneAskedForIsFlaggedForRevision() {
        // Japanese: a line of six Chinese characters or more with no kana is Chinese; hangul is Korean left as it was
        assertEquals("", ComicTranslation.otherWriting("あちゃ、また窓を開けたまま寝ちゃった…", "ja")); assertEquals("", ComicTranslation.otherWriting("絶体絶命！", "ja"), "four characters and no kana is a Japanese phrase"); assertEquals("", ComicTranslation.otherWriting("OK!", "ja")); assertEquals("", ComicTranslation.otherWriting("……", "ja"));
        assertEquals("the line is written in Chinese characters alone (13 of them, no kana), not in Japanese", ComicTranslation.otherWriting("胡椒，你真是我们卡奥萨的耻辱。", "ja"));
        assertEquals("the line holds hangul, not Japanese", ComicTranslation.otherWriting("의사 선생님은 어디에", "ja"));
        // Chinese holds no kana and no hangul
        assertEquals("", ComicTranslation.otherWriting("医生在哪里？", "zh")); assertEquals("the line holds kana, not Chinese", ComicTranslation.otherWriting("先生はどこですか", "zh")); assertEquals("the line holds hangul, not Chinese", ComicTranslation.otherWriting("의사", "zh"));
        // Korean is written in hangul
        assertEquals("", ComicTranslation.otherWriting("의사 선생님은 어디에 계세요?", "ko")); assertEquals("", ComicTranslation.otherWriting("OK!", "ko")); assertEquals("the line holds kana, not Korean", ComicTranslation.otherWriting("先生は", "ko"));
        assertEquals("the line is written in Chinese characters (5 of them) with no hangul, not in Korean", ComicTranslation.otherWriting("医生在哪里", "ko"));
        // an alphabet: Chinese characters, kana or hangul in place of its letters; or a whole line in another alphabet
        assertEquals("", ComicTranslation.otherWriting("Where is the doctor?", "en")); assertEquals("", ComicTranslation.otherWriting("Saito (斉藤) is here.", "en"), "a name's original beside it is not another language");
        assertEquals("the line is written in Chinese characters, kana or hangul (5 letters of 5), not in English", ComicTranslation.otherWriting("医生在哪里？", "en"));
        assertEquals("", ComicTranslation.otherWriting("Где доктор?", "ru")); assertEquals("", ComicTranslation.otherWriting("Pepper!", "ru"), "a name left in its own letters is no line in another language");
        assertEquals("the line is written in another alphabet (16 letters, none of Russian's), not in Russian", ComicTranslation.otherWriting("Where is the doctor?", "ru"));
        assertEquals("the line is written in another alphabet (9 letters, none of English's), not in English", ComicTranslation.otherWriting("Где доктор?", "en"));
        assertEquals("", ComicTranslation.otherWriting(null, "ja")); assertEquals("", ComicTranslation.otherWriting("", "en"));
        // the program's checks hold it: major, so the balloon is translated again in a fresh call that is told why
        List<Translation.Flag> flags = ComicTranslation.flagsOf(4, "당근아, 넌 정말 우리 카오사의 수치야.", "胡椒，你真是我们卡奥萨的耻辱。", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "ja");
        assertEquals(List.of(new Translation.Flag(4, "accuracy", "language", Translation.MAJOR, "", "the line is written in Chinese characters alone (13 of them, no kana), not in Japanese", "program", false, false)), flags);
        assertTrue(ComicTranslation.flagsOf(4, "당근아, 넌 정말 우리 카오사의 수치야.", "キャロット、お前は本当に我らカオサの恥だ。", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "ja").isEmpty());
        // a line that came back as its source, letter for letter, was not translated: "Ma-gni-fi-que !" stood in an English page as "MA-GNI-FI-QUE!"
        assertEquals(List.of(new Translation.Flag(2, "accuracy", "untranslated", Translation.MAJOR, "", "the line is its source letter for letter: it was not translated", "program", false, false)), ComicTranslation.flagsOf(2, "Ma-gni-fi-que !", "MA-GNI-FI-QUE!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en"));
        assertFalse(ComicTranslation.flagsOf(2, "Que le concours commence !", "QUE LE CONCOURS COMMENCE!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en").isEmpty());
        assertTrue(ComicTranslation.flagsOf(2, "Excellent !", "EXCELLENT!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en").isEmpty(), "one word the two languages share is no line left untranslated");
        assertTrue(ComicTranslation.flagsOf(2, "Komona !", "KOMONA!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en").isEmpty()); assertTrue(ComicTranslation.flagsOf(2, "OK, OK !", "OK, OK!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en").isEmpty(), "nor a short one");
        assertTrue(ComicTranslation.flagsOf(2, "Que le concours commence !", "Let the contest begin!", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "en").isEmpty());
        // the reviser of a line in another language is not shown that line: two of three such balloons came back in Chinese a second time with their Chinese before the model
        Translation.Flag language = new Translation.Flag(4, "accuracy", "language", Translation.MAJOR, "", "the line holds hangul, not Japanese", "program", false, false), number = new Translation.Flag(4, "accuracy", "number", Translation.MAJOR, "7", "the number 7 is not in the translation", "program", false, false);
        assertNull(ComicTranslation.shownEarlier(List.of(language), "胡椒，你真是我们卡奥萨的耻辱。")); assertNull(ComicTranslation.shownEarlier(List.of(number, language), "x"));
        assertEquals("Come tomorrow.", ComicTranslation.shownEarlier(List.of(number), "Come tomorrow."), "any other flag is revised against the line it was raised on");
        assertEquals("the shorter line lost what the program checks: the line is written in Chinese characters alone (6 of them, no kana), not in Japanese", ComicTranslation.seriousAfter(4, "당근아, 넌 정말 우리 카오사의 수치야.", "卡奥萨的耻辱", "キャロット、お前は本当に我らカオサの恥だ。", Map.of(), Set.of(), DocNames.Decided.NONE_FOUND, "ja"), "and a shorter line in another language is not taken for the fit");
    }
}
