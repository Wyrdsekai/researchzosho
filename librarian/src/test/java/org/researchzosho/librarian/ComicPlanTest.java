package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What is taken for a comic, its plan, and the one question (0.7.5). An archive or a folder of page pictures is a comic by itself and
 * brings the question where the comic helper is not set up; a PDF is a document unless the person says it is a comic, or the helper,
 * set up already, finds speech balloons on its pages, and a person with no helper is never asked to fetch one for a PDF. The language
 * of the lettering is the one the person names, else the one the helper finds on a sample of the pages, else found when the run starts;
 * a source that is not read, a target that is not lettered and a comic already in the language asked for are refused before any work.
 * The helper here is a stand-in; the pages are drawn by the test.
 */
class ComicPlanTest {
    String home; BooleanSupplier installed; Function<Consumer<String>, String> install; Supplier<ComicHelper.Reader> reader; Predicate<String> answers; Translation.SeatMaker seats;
    @BeforeEach void isolate(@TempDir Path tmp) {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        installed = ComicHelper.INSTALLED; install = ComicHelper.INSTALL; reader = ComicHelper.READER; answers = Translation.DRIVE_ANSWERS; seats = Translation.SEATS;
        ComicHelper.INSTALLED = () -> false;
    }
    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        ComicHelper.INSTALLED = installed; ComicHelper.INSTALL = install; ComicHelper.READER = reader; Translation.DRIVE_ANSWERS = answers; Translation.SEATS = seats;
        ComicHelper.manifestFrom(null);
        Interaction.OVERRIDE = null; Interaction.INPUT = null;
    }

    static ComicHelper.Reader standIn(Path tmp, Path script) {
        return new ComicHelper.Local(() -> { try { return ComicStandIn.command(tmp, script); } catch (IOException e) { throw new UncheckedIOException(e); } });
    }

    @Test void anArchiveOrAFolderOfPagePicturesIsAComicByItselfAndAPdfOnlyOnThePersonsWord(@TempDir Path tmp) throws Exception {
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 3);
        assertEquals(ComicTranslation.ITSELF, ComicTranslation.taken(cbz, "").why());
        assertEquals(ComicTranslation.SAID, ComicTranslation.taken(cbz, "on").why());
        Translation.Refused notADocument = assertThrows(Translation.Refused.class, () -> ComicTranslation.taken(cbz, "off"));
        assertEquals("\"volume.cbz\" holds page pictures and no text of its own: it is translated as a comic, or not at all.", notADocument.getMessage());
        // a PDF of pictures, on a machine with no helper: a document, and nothing is asked of the helper
        Path pdf = ComicPagesTest.pictures(tmp.resolve("scan.pdf"), 4);
        ComicHelper.READER = () -> { throw new AssertionError("a PDF on a machine with no comic helper is never looked at by one"); };
        assertNull(ComicTranslation.taken(pdf, ""), "a scanned paper is a document: the person is not asked to fetch a helper for a manga they do not have");
        assertNull(ComicTranslation.taken(pdf, "off"));
        assertEquals(ComicTranslation.SAID, ComicTranslation.taken(pdf, "on").why(), "the person's word makes it a comic");
        // a document is a document; said to be a comic, it is refused with what a comic is
        Path md = tmp.resolve("notes.md"); Files.writeString(md, "# notes\n\ntext\n");
        assertNull(ComicTranslation.taken(md, ""));
        assertEquals("\"notes.md\" is not a comic this program opens: a CBZ, a CBR, a ZIP or a folder of page pictures, or a PDF whose pages are pictures.", assertThrows(Translation.Refused.class, () -> ComicTranslation.taken(md, "on")).getMessage());
        Path empty = Files.createDirectories(tmp.resolve("empty folder"));
        assertTrue(assertThrows(Translation.Refused.class, () -> ComicTranslation.taken(empty, "")).getMessage().startsWith("\"empty folder\" is a folder with no page pictures in it"));
        Path docx = ComicStandIn.zip(tmp.resolve("a.docx"), "word/document.xml", "<w/>".getBytes(StandardCharsets.UTF_8));
        assertNull(ComicTranslation.taken(docx, ""), "a Word file is a zip, and a document");
    }

    @Test void aPdfIsTakenForAComicWhenTheHelperIsSetUpAlreadyAndFindsBalloonsOnItsPages(@TempDir Path tmp) throws Exception {
        Path script = tmp.resolve("script");
        Path comic = ComicPagesTest.pictures(tmp.resolve("volume.pdf"), 6);
        ComicHelper.INSTALLED = () -> true;
        ComicHelper.READER = () -> standIn(tmp, script);   // the stand-in reads one speech balloon on any page it has no script for
        ComicTranslation.Taken t = ComicTranslation.taken(comic, "");
        assertNotNull(t); assertEquals(ComicTranslation.FOUND, t.why()); assertEquals(3, t.sampled()); assertEquals(3, t.withBalloons());
        long reads = ComicStandIn.asked(script).stream().filter("read"::equals).count();
        assertEquals(3, reads, "three pages are looked at, not the whole PDF");
        ComicTranslation.taken(comic, "");
        assertEquals(3, ComicStandIn.asked(script).stream().filter("read"::equals).count(), "the same file is looked at once: a form's plan and then its job do not look twice");
        // its plan says how it was taken for one, and how to say otherwise
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Translation.Plan plan = Translation.plan(store, comic, "en", "", "checked", "stand-in", "", "", "", "");
        assertNotNull(plan.comic()); assertEquals("pdf", plan.as(), "a PDF comes back as a PDF"); assertEquals(6, plan.comic().pages());
        assertTrue(plan.saidFor().contains("it is taken for a comic, because the comic helper found speech balloons on 3 of the 3 pages it looked at (if it is a document, say so: --comic off on the command line, \"it is not a comic\" in the chat)"), plan.saidFor());
        // the same look said which language the lettering is in: the plan has it, and no second helper was started for it
        assertEquals("ja", plan.from()); assertEquals(ComicTranslation.SAMPLED, plan.comic().source().how());
        assertEquals(1, ComicStandIn.asked(script).stream().filter("script"::equals).count(), ComicStandIn.asked(script).toString());
        // said to be a document, it is planned as one: here, with no model that sees, the document's own refusal
        assertTrue(assertThrows(Translation.Refused.class, () -> Translation.plan(store, comic, "en", "", "checked", "stand-in", "", "", "", "off")).getMessage().startsWith("No text can be read from \"volume.pdf\" as it is."));
        // a PDF whose pages the helper finds no balloon on stays a document: a scanned book
        Path book = ComicPagesTest.pictures(tmp.resolve("book.pdf"), 5);
        ComicHelper.READER = () -> new ComicHelper.Reader() {
            @Override public JsonNode script(List<Path> images) { return ComicStandIn.M.createObjectNode().put("script", "japanese").put("share", 1.0); }
            @Override public JsonNode read(Path image, String language, String order) { return ComicStandIn.read(300, 420, ComicStandIn.block("b01", "outside", "第一章", new int[]{10, 10, 60, 200})); }
            @Override public JsonNode cut(Path image, Path out) { return ComicStandIn.M.createObjectNode().put("strip", false); }
            @Override public void join(List<ComicHelper.Piece> pieces, int width, int height, Path out) { }
            @Override public JsonNode set(Path image, JsonNode page, ObjectNode texts, String s, List<String> keep, Path out) { return null; }
            @Override public void picture(Path image, List<Map.Entry<String, double[]>> boxes, Path out) { }
            @Override public String loaded() { return ""; }
            @Override public void abort() { }
            @Override public void close() { }
        };
        assertNull(ComicTranslation.taken(book, ""), "text on its pages, and no speech balloon: a document, read as before");
    }

    @Test void theSourceAndTheTargetThatAreNotDoneAreRefusedBeforeAnyWork(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 2);
        ComicHelper.READER = () -> { throw new AssertionError("a refusal comes before any page is read"); };
        Function<String[], String> refusal = a -> assertThrows(Translation.Refused.class, () -> Translation.plan(store, cbz, a[0], a[1], "checked", "stand-in", "", "", a[2], "")).getMessage();
        // a source the helper has no reader for
        assertEquals("A comic's lettering is read in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Arabic is not read.", refusal.apply(new String[]{"en", "", "Arabic"}));
        assertEquals("A comic's lettering is read in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Thai is not read.", refusal.apply(new String[]{"en", "", "th"}));
        // a target the lettering does not do: what is lettered is said first
        assertEquals("A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Arabic is written from right to left, which the lettering does not do yet.", refusal.apply(new String[]{"Arabic", "", ""}));
        assertEquals("A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Hebrew is written from right to left, which the lettering does not do yet.", refusal.apply(new String[]{"he", "", ""}));
        assertEquals("A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Thai is not lettered yet.", refusal.apply(new String[]{"Thai", "", ""}));
        assertEquals("A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Hindi is not lettered yet.", refusal.apply(new String[]{"hi", "", ""}));
        assertEquals("A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Vietnamese needs letters the lettering fonts do not have.", refusal.apply(new String[]{"vi", "", ""}));
        // a comic the person says is in the language asked for: one sentence
        assertEquals("\"volume.cbz\" is written in Japanese already.", refusal.apply(new String[]{"ja", "", "Japanese"}));
        assertEquals("\"volume.cbz\" is written in English already.", refusal.apply(new String[]{"English", "", "en"}));
        assertEquals("A comic's translation is written as cbz or pdf, not docx.", refusal.apply(new String[]{"en", "docx", ""}));
        // in the person's language, as every refusal before a translation is
        Translation.Refused r = assertThrows(Translation.Refused.class, () -> Translation.plan(store, cbz, "ar", "", "checked", "stand-in", "", "", "", ""));
        assertEquals("この版では、コミックの吹き出しの文字は、日本語、中国語、韓国語、およびラテン文字、キリル文字、ギリシャ文字で書く言語で入れます。アラビア語は右から左に書きますが、文字入れはまだ対応していません。", r.in(Chrome.in("ja")));
        // the languages that are lettered: the Latin alphabet, Cyrillic and Greek in the font that has them, and Chinese, Korean and Japanese each in its own
        for (String to : List.of("English", "German", "fr", "es", "pt", "it", "nl", "pl", "tr", "Russian", "Ukrainian", "be", "Bulgarian", "Serbian", "mk", "Greek", "Chinese", "zh", "Korean", "ko", "Japanese", "ja")) assertNotNull(Translation.plan(store, cbz, to, "", "checked", "stand-in", "", "", to.equals("Japanese") || to.equals("ja") ? "ko" : "", "").comic(), to);
        assertEquals("latin", ComicTranslation.script("de")); assertEquals("cyrillic", ComicTranslation.script("ru")); assertEquals("cyrillic", ComicTranslation.script("uk")); assertEquals("greek", ComicTranslation.script("el"));
        assertEquals("japanese", ComicTranslation.script("ja")); assertEquals("chinese-simplified", ComicTranslation.script("zh")); assertEquals("korean", ComicTranslation.script("ko"));
        // every language of the ten is a source, and so are the other alphabets' languages; the plan holds the one named, as named
        for (String from : List.of("Japanese", "Chinese", "Korean", "English", "German", "French", "Spanish", "Portuguese", "Italian", "Russian", "nl", "uk", "el")) {
            Translation.Plan plan = Translation.plan(store, cbz, from.equals("English") ? "ja" : "en", "", "checked", "stand-in", "", "", from, "");
            assertEquals(Translate.code(from), plan.from(), from); assertEquals(ComicTranslation.BY_PERSON, plan.comic().source().how());
            assertEquals("its lettering read as " + ReadIn.name(from), plan.comic().lettering(Chrome.ENGLISH)); assertEquals("", plan.comic().sourceSaid(Chrome.ENGLISH), "a language the person named needs no word on how to name another");
        }
        // a helper this machine may not run is a refusal too, said once, before any question
        Config.set(ComicHelper.SETTING, "off");
        assertTrue(assertThrows(Translation.Refused.class, () -> Translation.plan(store, cbz, "en", "", "checked", "stand-in", "", "", "", "")).getMessage().startsWith("The comic helper is off (RESEARCHZOSHO_COMIC=off)"));
        Config.set(ComicHelper.SETTING, "");
    }

    /** A volume of {@code pages} pages whose every page the stand-in reads as one speech balloon holding {@code text}; {@code columns}: lettered in columns. */
    static Path lettered(Path cbz, Path script, int pages, String text, boolean columns) throws IOException {
        int[][] b = ComicStandIn.balloons(500, 700);
        for (byte[] page : ComicStandIn.volume(cbz, pages)) { ObjectNode block = ComicStandIn.block("b01", "speech", text, b[0]); ComicStandIn.says(script, page, ComicStandIn.read(500, 700, columns ? ComicStandIn.columns(block) : block)); }
        return cbz;
    }

    @Test void theLanguageOfTheLetteringIsFoundOnASampleSaidWithTheWayToNameAnotherAndOverruled(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path script = tmp.resolve("script");
        ComicHelper.INSTALLED = () -> true;
        ComicHelper.READER = () -> standIn(tmp, script);
        // hangul is Korean: three pages spread through the volume are looked at, none of them read, and the plan says what was found and how to say otherwise
        Path manhwa = lettered(tmp.resolve("manhwa.cbz"), script, 9, "의사 선생님은 어디에 계세요?", false);
        Translation.Plan ko = Translation.plan(store, manhwa, "English", "", "checked", "stand-in", "", "", "", "");
        assertEquals("ko", ko.from()); assertEquals(ComicTranslation.SAMPLED, ko.comic().source().how()); assertEquals(3, ko.comic().source().sampled()); assertEquals("korean", ko.comic().source().script());
        assertEquals("its lettering read as Korean", ko.comic().lettering(Chrome.ENGLISH));
        assertTrue(ko.saidFor().contains("; the comic helper read its lettering as Korean (pages looked at: 3; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat); "), ko.saidFor());
        assertEquals(List.of("script", "quit"), ComicStandIn.asked(script), "one look, of three pages; no page is read for a plan");
        assertEquals(3, ComicStandIn.M.readTree(ComicStandIn.requests(script, "script").get(0)).path("images").size());
        Translation.plan(store, manhwa, "en", "", "quick", "stand-in", "", "", "", "");
        assertEquals(1, ComicStandIn.requests(script, "script").size(), "the same file is looked at once: a form's plan and then its job do not look twice");
        // what the job keeps of it: the language as found, so that the run does not look again
        ObjectNode a = Translation.args(manhwa, "manhwa.cbz", ko, null, "", "cli", "", "", "", "", "");
        assertFalse(a.has("from"), "the person named no language: the job's own from stays theirs to give"); assertEquals("ko", a.path("comic_plan").path("from").asText()); assertEquals("sampled", a.path("comic_plan").path("from_how").asText());
        ComicTranslation.Said kept = ComicTranslation.Said.of(a);
        assertEquals("ko", kept.source().code()); assertEquals(3, kept.source().sampled());
        // the person's word overrules what was found, and nothing is looked at
        Translation.Plan said = Translation.plan(store, manhwa, "en", "", "checked", "stand-in", "", "", "Chinese", "");
        assertEquals("zh", said.from()); assertEquals(ComicTranslation.BY_PERSON, said.comic().source().how()); assertFalse(said.saidFor().contains("the comic helper read its lettering"), said.saidFor());
        assertEquals(1, ComicStandIn.requests(script, "script").size());
        // kana are Japanese, han alone Chinese, Greek letters Greek, Cyrillic words Russian
        assertEquals("ja", Translation.plan(store, lettered(tmp.resolve("manga.cbz"), script, 2, "先生はどこですか", true), "en", "", "checked", "stand-in", "", "", "", "").from());
        Translation.Plan zh = Translation.plan(store, lettered(tmp.resolve("manhua.cbz"), script, 2, "医生在哪里？手术三点开始。", true), "en", "", "checked", "stand-in", "", "", "", "");
        assertEquals("zh", zh.from()); assertTrue(zh.comic().source().vertical(), "and most of its blocks run in columns");
        assertEquals("el", Translation.plan(store, lettered(tmp.resolve("greek.cbz"), script, 2, "ΠΟΥ ΕΙΝΑΙ Ο ΓΙΑΤΡΟΣ;", false), "en", "", "checked", "stand-in", "", "", "", "").from());
        assertEquals("ru", Translation.plan(store, lettered(tmp.resolve("russian.cbz"), script, 2, "ГДЕ ДОКТОР? ОН УЖЕ В ОПЕРАЦИОННОЙ, И ЭТО НЕ ВСЁ, ЧТО Я ЗНАЮ.", false), "en", "", "checked", "stand-in", "", "", "", "").from());
        // an alphabet: the words read say which language, in capitals as a comic letters them
        Translation.Plan fr = Translation.plan(store, lettered(tmp.resolve("bd.cbz"), script, 4, "OÙ EST LE DOCTEUR ? IL EST DANS LA SALLE AVEC LES AUTRES, ET NOUS NE POUVONS PAS ENTRER.", false), "English", "", "checked", "stand-in", "", "", "", "");
        assertEquals("fr", fr.from()); assertTrue(fr.comic().source().clear()); assertEquals("latin", fr.comic().source().script());
        assertTrue(fr.saidFor().contains("the comic helper read its lettering as French (pages looked at: 3; "), fr.saidFor());
        // a comic the helper reads as the language asked for is refused in one sentence that says how to name another
        Translation.Refused same = assertThrows(Translation.Refused.class, () -> Translation.plan(store, tmp.resolve("bd.cbz"), "French", "", "checked", "stand-in", "", "", "", ""));
        assertEquals("The comic helper read the lettering of \"bd.cbz\" as French, the language asked for; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat.", same.getMessage());
        assertNotNull(Translation.plan(store, tmp.resolve("bd.cbz"), "French", "", "checked", "stand-in", "", "", "Spanish", "").comic(), "named as another language, it is translated");
        // lettering too short to say its language clearly: taken as the best guess, the other it could be said, and never refused on a guess
        Translation.Plan unclear = Translation.plan(store, lettered(tmp.resolve("short.cbz"), script, 2, "NO!", false), "English", "", "checked", "stand-in", "", "", "", "");
        assertFalse(unclear.comic().source().clear()); assertFalse(unclear.from().isEmpty());
        assertTrue(unclear.saidFor().contains(", though it could be ") && unclear.saidFor().contains("(pages looked at: 2; if it is in another language, name it: --from <language> on the command line"), unclear.saidFor());
        // pages with no lettering at all: nothing found, so the plan leaves it to the run's start, as where the helper is not set up
        Path silent = tmp.resolve("silent.cbz");
        for (byte[] page : ComicStandIn.volume(silent, 2)) ComicStandIn.says(script, page, ComicStandIn.read(500, 700));
        Translation.Plan later = Translation.plan(store, silent, "en", "", "checked", "stand-in", "", "", "", "");
        assertEquals("", later.from()); assertEquals(ComicTranslation.AT_START, later.comic().source().how());
        assertEquals("the language of its lettering not read yet", later.comic().lettering(Chrome.ENGLISH));
        assertTrue(later.saidFor().contains("the language of its lettering is found on a sample of its pages when the run starts (to name it yourself: --from <language> on the command line, \"from <language>\" in the chat)"), later.saidFor());
        // the sample: a folder's own files, a zip's entries under names of the program's own, spread past the first page
        assertEquals(List.of(2, 5, 7), ComicPages.spread(9, 3)); assertEquals(List.of(0, 1), ComicPages.spread(2, 3)); assertEquals(List.of(), ComicPages.spread(0, 3));
        Path look = Files.createDirectories(tmp.resolve("look"));
        assertEquals(List.of("s1.png", "s2.png", "s3.png"), ComicPages.sample(manhwa, look, 3).stream().map(p -> p.getFileName().toString()).toList());
        assertArrayEquals(ComicStandIn.png(ComicStandIn.page(3, 500, 700)), Files.readAllBytes(look.resolve("s1.png")), "the third page of nine, as the archive holds it");
    }

    @Test void aComicsPlanCountsItsPagesAndSaysHowTheyReachTheTranslator(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 30);
        Translation.Plan plan = Translation.plan(store, cbz, "English", "", "", "stand-in", "", "", "", "");
        assertEquals("cbz", plan.format()); assertEquals("cbz", plan.as()); assertEquals("", plan.from(), "no helper to look with: the language is the run's to find"); assertEquals("en", plan.to()); assertEquals("checked", plan.quality());
        assertEquals(30, plan.comic().pages()); assertEquals(ComicTranslation.ITSELF, plan.comic().why()); assertEquals(ComicTranslation.NEEDS, plan.comic().helper());
        assertEquals(30 * 14, plan.checkedSeconds(), "with nothing timed, the helper's reading and one call a page"); assertEquals(30 * 11, plan.quickSeconds()); assertFalse(plan.measured());
        assertEquals("about 7 minutes checked (a first guess: nothing has been timed on this model yet); the language of its lettering is found on a sample of its pages when the run starts (to name it yourself: --from <language> on the command line, \"from <language>\" in the chat); which way its pages are read is found when the run starts (to say it yourself: --reads right-to-left or --reads left-to-right on the command line, \"it reads right to left\" in the chat); whether the translator sees pictures is found when the run starts: if it does, each page goes to it as a picture beside the text read from it; whether the lettering is read a second time, by the translator from a picture of each balloon, is found when the run starts: it is for a comic that is not Japanese when the translator sees pictures", plan.saidFor());
        assertEquals("", plan.setupSaid(), "a comic's balloons are not scored: nothing is said of the scorers"); assertTrue(plan.placed().isEmpty());
        // a translator known to see, and one known not to
        Config.set(ModelEntries.SEES, "yes");
        assertTrue(Translation.plan(store, cbz, "en", "", "quick", "stand-in", "", "", "", "").saidFor().endsWith("each page goes to the translator as a picture, beside the text read from it; the lettering is read once, by the comic helper: the quick tier has no second reading of it"));
        Config.set(ModelEntries.SEES, "no");
        assertTrue(Translation.plan(store, cbz, "en", "", "quick", "stand-in", "", "", "", "").saidFor().endsWith("the translator does not see pictures, so each page is translated from the text read from it alone; the lettering is read once, by the comic helper: the quick tier has no second reading of it"));
        Config.set(ModelEntries.SEES, "");
        // a speed measured on comics is the next plan's
        Translation.recordSpeed(store, "stand-in", ComicTranslation.speedKey("checked"), 30, 240);
        Translation.Plan timed = Translation.plan(store, cbz, "en", "", "checked", "stand-in", "", "", "", "");
        assertTrue(timed.measured()); assertEquals(240, timed.checkedSeconds());
        // the job keeps how the file was taken, and who reads the pages; nothing of the scorers
        ObjectNode a = Translation.args(cbz, "volume.cbz", plan, null, "", "cli", "", "", "", "", "");
        assertEquals("on", a.path("comic").asText()); assertEquals("itself", a.path("comic_why").asText()); assertEquals(30, a.path("comic_plan").path("pages").asInt());
        assertFalse(a.has("comic_setup"), "the yes to the set-up is the person's to give, on the surface that asked");
        JsonNode facts = TranslationWho.facts(Translation.JOB_DRAFT.apply(a));
        assertTrue(facts.path("said").asText().startsWith("The pages are read by the comic helper on this machine. Translated by "), facts.path("said").asText());
        assertFalse(facts.path("said").asText().contains("Scores:"), facts.path("said").asText());
        assertTrue(TranslationWho.seatsSaid(facts, Chrome.in("ja")).startsWith("ページを読むのは、このマシンのコミックヘルパーです。"));
        // a folder's job is told from another folder's by its pages' names
        Path folder = Files.createDirectories(tmp.resolve("pages")); Files.write(folder.resolve("1.png"), ComicStandIn.png(ComicStandIn.page(1, 200, 280)));
        ObjectNode fa = Translation.args(folder, "pages", Translation.plan(store, folder, "en", "", "", "stand-in", "", "", "", ""), null, "", "cli", "", "", "", "", "");
        String before = Translation.basis(Translation.JOB_DRAFT.apply(fa));
        Files.write(folder.resolve("2.png"), ComicStandIn.png(ComicStandIn.page(2, 200, 280)));
        assertNotEquals(before, Translation.basis(Translation.JOB_DRAFT.apply(fa)), "another page in the folder is another translation");
    }

    @Test void theCommandLineSaysWhatASetUpFetchesAndAsksOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 3);
        Path script = tmp.resolve("script");
        AtomicBoolean setUp = new AtomicBoolean();
        List<String> installs = new ArrayList<>();
        ComicHelper.INSTALLED = setUp::get;
        ComicHelper.INSTALL = say -> { installs.add("install"); say.accept("Fetching the models, the fonts and the hyphenation patterns (489 MB)."); setUp.set(true); return "ready"; };
        ComicHelper.READER = () -> standIn(tmp, script);
        Translation.DRIVE_ANSWERS = d -> true;
        Translation.SEATS = (drive, model, judge, back) -> ComicTranslationTest.seats(new ComicTranslationTest.Model()).withBack(false);
        Function<ModelEntries.Entry, Vision.Probe> probe = LibraryProtocol.VISION_PROBE;
        LibraryProtocol.VISION_PROBE = e -> new Vision.Probe(true, "red / blue", "it named a red square and a blue one");
        String[] args = {"researchzosho", "translate", cbz.toString(), "--to", "English"};
        int[] rc = {-9};
        // nobody at the keyboard: the list is said, nothing is fetched, and the command says how to say yes
        Interaction.OVERRIDE = false;
        String said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertEquals(1, rc[0]);
        assertTrue(said.startsWith("\"volume.cbz\": a comic of 3 pages, the language of its lettering not read yet. Into English as cbz, checked: under a minute checked (a first guess: nothing has been timed on this model yet); the language of its lettering is found on a sample of its pages when the run starts (to name it yourself: --from <language> on the command line, \"from <language>\" in the chat); which way its pages are read is found when the run starts (to say it yourself: --reads right-to-left or --reads left-to-right on the command line, \"it reads right to left\" in the chat); whether the translator sees pictures is found when the run starts"), said);
        assertTrue(said.contains("\n  The pages are read by the comic helper on this machine. Translated by "), said);
        assertTrue(said.contains("\nReading a comic's pages needs the comic helper, which is not set up on this machine yet."), said);
        assertTrue(said.contains("\n" + ComicHelper.question(Chrome.ENGLISH).get(1) + "\n") && ComicHelper.question(Chrome.ENGLISH).get(1).startsWith("Setting it up fetches, once: its models ("), said);
        assertTrue(said.contains("\nAll of it is under open licences; researchzosho translate comics status --list names each piece with its licence.\n"), said);
        assertFalse(said.contains("tqdm") || said.contains("Apache"), "the list of pieces is behind its own command, not before the question: " + said);
        assertFalse(said.contains("Set the comic helper up now?"), "nobody could answer, so nothing is asked: " + said);
        assertTrue(installs.isEmpty());
        String err = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), true);
        assertEquals("Nobody is at the keyboard to answer, so nothing was fetched and the comic was not translated. Add --yes to this command to set the comic helper up with it, or run researchzosho translate comics install first.\n", err);
        // a person at the keyboard who presses Enter: no
        Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("\n"));
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertEquals(1, rc[0]);
        assertTrue(said.contains("Set the comic helper up now? (y/N) "), said);
        assertTrue(said.endsWith("Nothing was fetched, and the comic was not translated. The same command asks again; researchzosho translate comics install sets the helper up on its own.\n"), said);
        assertTrue(installs.isEmpty()); assertTrue(ComicStandIn.asked(script).isEmpty(), "no page was read");
        // yes: the helper is set up as the run's first step, each step said, and the pages are read
        Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertEquals(List.of("install"), installs);
        assertTrue(said.contains("  Setting the comic helper up.\n  Fetching the models, the fonts and the hyphenation patterns (489 MB).\n  The comic helper is set up.\n"), said);
        assertTrue(said.contains("  reading the pages, 1 of 3\n  reading the pages, 2 of 3\n  reading the pages, 3 of 3\n"), said);
        assertTrue(said.contains("  translating, page 1 of 3\n  translating, page 2 of 3\n  translating, page 3 of 3\n") && said.contains("  setting the lines into the balloons, page 3 of 3\n  writing the file\n"), said);
        assertEquals(0, rc[0], said);
        assertTrue(said.contains("\nTranslated \"volume\" into English (checked): 3 pages, 3 speech balloons, under a minute on stand-in.") && said.contains("\nThe file: " + tmp.resolve("volume.en.cbz") + "\nThe note: " + tmp.resolve("volume.en.translation.md") + "\n"), said);
        assertTrue(said.contains("The pages are read by the comic helper on this machine. Translated by "), "who did what, after the summary: " + said);
        assertEquals(3, ComicStandIn.asked(script).stream().filter("read"::equals).count());
        // set up now: the same command asks nothing
        Interaction.INPUT = new BufferedReader(new StringReader(""));
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertFalse(said.contains("not set up") || said.contains("Set the comic helper up now?"), said);
        assertEquals(List.of("install"), installs, "set up once");
        // no model answering: said before the question, so nothing is fetched for a run that could not start
        setUp.set(false); Translation.DRIVE_ANSWERS = d -> false;
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, args), false);
        assertEquals(1, rc[0]); assertFalse(said.contains("Reading a comic's pages needs the comic helper"), said);
        // a script's yes
        Translation.DRIVE_ANSWERS = d -> true; Interaction.OVERRIDE = false;
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--yes"}), false);
        assertEquals(List.of("install", "install"), installs); assertTrue(said.contains("  The comic helper is set up.\n"), said);
        // --from names the language the comic is lettered in: the first line says it with no word on how it was found, and every page is read with that language's reader
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--from", "Korean", "--quick", "--out", tmp.resolve("from-korean.cbz").toString()}), false);
        assertEquals(0, rc[0], said);
        assertTrue(said.startsWith("\"volume.cbz\": a comic of 3 pages, its lettering read as Korean. Into English as cbz, quick: "), said);
        assertFalse(said.contains("the comic helper read its lettering") || said.contains("found on a sample"), said);
        List<String> reads = ComicStandIn.requests(script, "read");
        for (String r : reads.subList(reads.size() - 3, reads.size())) assertTrue(r.contains("\"language\":\"ko\""), r);
        // --reads says which way the pages are read where the person knows better than the language: an English edition of a manga runs from the right
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "German", "--from", "English", "--reads", "right-to-left", "--quick", "--out", tmp.resolve("reads-rtl.cbz").toString()}), false);
        assertEquals(0, rc[0], said);
        assertTrue(said.startsWith("\"volume.cbz\": a comic of 3 pages, its lettering read as English. Into German as cbz, quick: ") && said.contains("; its pages are read from the right to the left, as you said; "), said);
        reads = ComicStandIn.requests(script, "read");
        for (String r : reads.subList(reads.size() - 3, reads.size())) assertTrue(r.contains("\"language\":\"en\"") && r.contains("\"order\":\"rtl\""), r);
        assertTrue(Files.readString(tmp.resolve("reads-rtl.translation.md")).contains("its pages read from the right to the left, as the person said)"), "and the note says which way, and that it was said");
        // rtl and ltr are taken too; a Japanese comic said to read from the left is read so; a word that is neither is refused before anything else
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--reads", "ltr", "--quick", "--out", tmp.resolve("reads-ltr.cbz").toString()}), false);
        assertEquals(0, rc[0], said);
        assertTrue(said.contains("its lettering read as Japanese. Into English as cbz, quick: ") && said.contains("; its pages are read from the left to the right, as you said; "), said);
        reads = ComicStandIn.requests(script, "read");
        for (String r : reads.subList(reads.size() - 3, reads.size())) assertTrue(r.contains("\"order\":\"ltr\""), r);
        err = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--reads", "sideways"}), true);
        assertEquals(2, rc[0]); assertEquals("--reads is right-to-left or left-to-right (rtl and ltr are taken too): the way a comic's pages are read.\n", err);
        // with nothing said, a Japanese comic's plan says its pages run from the right, and how to say otherwise
        said = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--quick", "--out", tmp.resolve("unsaid.cbz").toString()}), false);
        assertTrue(said.contains("; its pages are read from the right to the left (if they run from the left, say so: --reads left-to-right on the command line, \"it reads left to right\" in the chat); "), said);
        // and a comic named as being in the language asked for is refused in one sentence, before anything is asked
        err = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--from", "en"}), true);
        assertEquals(1, rc[0]); assertEquals("\"volume.cbz\" is written in English already.\n", err);
        // a word that is neither on nor off, and a document said to be a comic
        assertEquals(2, TranslateCli.translate(store, new String[]{"researchzosho", "translate", cbz.toString(), "--to", "English", "--comic", "maybe"}));
        Path md = tmp.resolve("notes.md"); Files.writeString(md, "# notes\n");
        err = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", md.toString(), "--to", "English", "--comic", "on"}), true);
        assertEquals(1, rc[0]); assertTrue(err.startsWith("\"notes.md\" is not a comic this program opens"), err);
        LibraryProtocol.VISION_PROBE = probe;
    }

    @Test void theComicsCommandSaysHowTheHelperStandsAndSetsItUpOnAYes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        int[] rc = {-9};
        ComicHelper.INSTALLED = installed;   // as the files under the test's own home say: nothing there
        String status = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics"}), false);
        assertEquals(0, rc[0]);
        assertTrue(status.startsWith("The comic helper is not set up: a comic (a CBZ, a CBR, a ZIP or a folder of page pictures) cannot be translated until it is. researchzosho translate comics install sets it up"), status);
        String list = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "status", "--list"}), false);
        assertTrue(list.contains("  to fetch    the model that reads Japanese lettering: manga-ocr-base, 444 MB, licence Apache 2.0\n") && list.contains("  to fetch    hyphenation patterns for Russian, 66 KB, licence LPPL 1.2 or later\n")
                && list.contains("  to fetch    the lettering font for the Cyrillic and Greek alphabets: Comic Relief Bold, 95 KB, licence SIL Open Font License 1.1\n") && list.contains("  to install  Python packages in an environment of the helper's own"), list);
        // install: the list, the question, and on a no nothing is touched
        List<String> installs = new ArrayList<>();
        ComicHelper.INSTALL = say -> { installs.add("install"); say.accept("Loading every piece once, to be sure it works."); return "ready"; };
        Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("n\n"));
        String no = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install"}), false);
        assertEquals(1, rc[0]); assertTrue(no.startsWith(ComicHelper.question(Chrome.ENGLISH).get(1) + "\n"), no);
        assertTrue(no.endsWith("Set the comic helper up now? (y/N) Nothing was fetched or changed.\n"), no); assertTrue(installs.isEmpty());
        Interaction.INPUT = new BufferedReader(new StringReader("yes\n"));
        String yes = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install"}), false);
        assertEquals(0, rc[0]); assertEquals(List.of("install"), installs);
        assertTrue(yes.endsWith("  Loading every piece once, to be sure it works.\nThe comic helper is set up; a comic can be translated now: researchzosho translate <volume.cbz> --to <language>.\n"), yes);
        Interaction.OVERRIDE = false;
        assertEquals(0, TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "install", "--yes"})); assertEquals(2, installs.size());
        assertEquals(2, TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "remove"}));
        // a file that is really called comics is a file to translate
        assertEquals(1, TranslateCli.translate(store, new String[]{"researchzosho", "translate", tmp.resolve("comics-not-there.txt").toString(), "--to", "en"}));
    }
}
