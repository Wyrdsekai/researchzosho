package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A comic on the surfaces beside the command line (0.7.5): the chat with a file, and the protocol's call that the API, MCP, the Java
 * client and the Python SDK send. Each says a comic is one, files its job as the command line does, and where the comic helper is not
 * set up says what a set-up fetches in the same three sentences and starts nothing without the person's yes. Each carries the language
 * of the lettering when the person names it (the chat's "from Korean", {@code from} in a program's call) and says the one the program
 * found when they do not.
 */
class ComicSurfacesTest {
    static final ObjectMapper M = new ObjectMapper();
    String home; BooleanSupplier installed, any; Function<Consumer<String>, String> install; Predicate<String> answers; Translation.SeatMaker seats; Supplier<ComicHelper.Reader> reader;

    @BeforeEach void isolate(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        installed = ComicHelper.INSTALLED; install = ComicHelper.INSTALL; answers = Translation.DRIVE_ANSWERS; seats = Translation.SEATS; any = Translation.ANY_MODEL; reader = ComicHelper.READER;
        ComicHelper.INSTALLED = () -> false;
        ComicHelper.READER = () -> ComicPlanTest.standIn(tmp, tmp.resolve("script"));   // where a test sets the helper up, the stand-in says which language a comic is lettered in (Japanese, for a page it has no script for)
        Translation.DRIVE_ANSWERS = d -> true; Translation.ANY_MODEL = () -> true;
        Translation.SEATS = (drive, model, judge, back) -> ComicTranslationTest.seats(new ComicTranslationTest.Model()).withBack(false);
        Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:1"); Config.set("RESEARCHZOSHO_MODEL", "stand-in");
    }

    @AfterEach void restore() {
        System.setProperty("user.home", home); Config.invalidate();
        ComicHelper.INSTALLED = installed; ComicHelper.INSTALL = install; Translation.DRIVE_ANSWERS = answers; Translation.SEATS = seats; Translation.ANY_MODEL = any; ComicHelper.READER = reader;
    }

    /** A PDF whose pages are the pictures of a drawn volume. */
    static Path pdf(Path tmp, int pages) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("pdf-pages"));
        List<Path> files = new ArrayList<>();
        int n = 0;
        for (byte[] png : ComicStandIn.volume(dir.resolve("made.cbz"), pages)) { Path f = dir.resolve(String.format("%03d.png", ++n)); Files.write(f, png); files.add(f); }
        Path out = tmp.resolve("pages.pdf");
        ComicPages.writePdf(files, out);
        return out;
    }

    /** A call's arguments as the library's owner sends them, from the terminal. */
    static ObjectNode owner() { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "me").put("runtime", "cli"); return a; }

    @Test void theWordsThatSayAFileIsAComicOrIsNotAreReadInEnglishAndJapanese() {
        assertEquals("on", TranslateTurn.comicIn("translate this into English, this is a manga", "this"));
        assertEquals("on", TranslateTurn.comicIn("Translate this file into English. It's a comic book.", "this file"));
        assertEquals("on", TranslateTurn.comicIn("translate this pdf into French as a graphic novel", "this pdf"));
        assertEquals("on", TranslateTurn.comicIn("translate this manga into English", "this manga"), "the file pointed at as a manga");
        assertEquals("on", TranslateTurn.comicIn("これは漫画です。英語に翻訳して", "これ")); assertEquals("on", TranslateTurn.comicIn("この漫画を英語に翻訳して", "この漫画"));
        // the words a person has for a comic: manga, manhwa, manhua, a webtoon, a comic, a graphic novel, a bande dessinée
        for (String word : List.of("manga", "manhwa", "manhua", "webtoon", "web comic", "comic", "comic book", "comic strip", "graphic novel", "bande dessinée")) {
            assertEquals("on", TranslateTurn.comicIn("translate this " + word + " into English", "this " + word), word);
            assertEquals("on", TranslateTurn.comicIn("translate this into English, it's a " + word, "this"), word);
            assertEquals("off", TranslateTurn.comicIn("translate this into English, it is not a " + word, "this"), word);
            assertTrue(TranslateTurn.pointsAtTheFile("this " + word), word);
            assertEquals("translate this into English", TranslateTurn.withoutComic("translate this into English, this is a " + word), word);
        }
        assertEquals("on", TranslateTurn.comicIn("translate this into French as a webtoon", "this")); assertEquals("on", TranslateTurn.comicIn("このウェブトゥーンを日本語に翻訳して", "このウェブトゥーン")); assertEquals("on", TranslateTurn.comicIn("これは縦読み漫画です。英語に翻訳して", "これ"));
        assertEquals("", TranslateTurn.comicIn("translate \"The Webtoon Business\" into English", "The Webtoon Business"), "a title that holds the word says nothing of the file");
        // the way the pages are read, in the person's words; the sentence is about the comic, and is taken out of the ask
        for (String said : List.of("translate this into German, quick. It reads right to left.", "translate this into German, it reads from right to left", "translate this into German (right-to-left)", "translate this comic into German, its pages are read right to left", "これをドイツ語に翻訳して。右から左に読みます。", "右から左で、これを英語に翻訳して"))
            assertEquals("rtl", TranslateTurn.readsIn(said), said);
        for (String said : List.of("translate this into German. It reads left to right.", "translate this into German, reads from left-to-right", "これを英語に翻訳して。左から右に読みます。")) assertEquals("ltr", TranslateTurn.readsIn(said), said);
        assertEquals("", TranslateTurn.readsIn("translate this into German, quick")); assertEquals("", TranslateTurn.readsIn("translate the right chapter into German, and leave the rest"));
        assertEquals("translate this into German, quick", TranslateTurn.withoutReads("translate this into German, quick. It reads right to left."));
        assertEquals("translate this into German", TranslateTurn.withoutReads("translate this into German, it reads from right to left"));
        assertEquals("これをドイツ語に翻訳して", TranslateTurn.withoutReads("これをドイツ語に翻訳して。右から左に読みます。"));
        assertEquals("translate this into German, quick", TranslateTurn.withoutReads("translate this into German (it reads right to left), quick"), "said in brackets, the brackets go with it");
        assertEquals("translate this into German", TranslateTurn.withoutReads("translate this into German (right-to-left)")); assertEquals("rtl", TranslateTurn.readsIn("translate this into German (it reads right to left), quick"));
        assertEquals("translate this into German, quick", TranslateTurn.withoutReads("translate this into German, quick"), "words that say nothing of the way are left as they were");
        // calling the file a manga says the way too, in English; the Japanese words for a comic are what any comic is called
        assertTrue(TranslateTurn.mangaSaid("translate this manga from English into German", "this manga") && TranslateTurn.mangaSaid("translate this from English into German, it's a manga", "this"));
        assertFalse(TranslateTurn.mangaSaid("translate this manhwa into English", "this manhwa") || TranslateTurn.mangaSaid("translate this comic into English", "this comic") || TranslateTurn.mangaSaid("translate this into English, it is not a manga", "this"));
        assertFalse(TranslateTurn.mangaSaid("この漫画を英語に翻訳して", "この漫画") || TranslateTurn.mangaSaid("translate \"A History of Manga\" into English", "A History of Manga"));
        assertEquals("off", TranslateTurn.comicIn("translate this zip into English, it is not a comic", "this zip"));
        assertEquals("off", TranslateTurn.comicIn("translate this into English; it's a document", "this")); assertEquals("off", TranslateTurn.comicIn("これは漫画ではありません。英語に翻訳して", "これ"));
        assertEquals("", TranslateTurn.comicIn("translate this into English, checked", "this")); assertEquals("", TranslateTurn.comicIn("translate this volume into English", "this volume"), "a volume may be a book");
        assertEquals("", TranslateTurn.comicIn("translate \"A History of Manga\" into English", "A History of Manga"), "a title that holds the word says nothing of the file");
        // the sentence about the file is taken out of the ask, and the file pointed at as a comic is the file
        assertEquals("translate this into English", TranslateTurn.withoutComic("translate this into English, this is a manga"));
        assertEquals("translate this into English", TranslateTurn.withoutComic("translate this into English; it is not a comic."));
        assertEquals("英語に翻訳して", TranslateTurn.withoutComic("これは漫画です。英語に翻訳して"));
        assertTrue(TranslateTurn.pointsAtTheFile("this manga") && TranslateTurn.pointsAtTheFile("the attached comic") && TranslateTurn.pointsAtTheFile("この漫画") && TranslateTurn.pointsAtTheFile("this cbz") && TranslateTurn.pointsAtTheFile("this file"));
        assertFalse(TranslateTurn.pointsAtTheFile("manga history") || TranslateTurn.pointsAtTheFile("A History of Manga"));
    }

    @Test void theChatSaysAComicIsOneAsksBeforeTheHelperIsSetUpAndStartsOnTheYes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 3);
        Librarian.Session session = Librarian.Session.open(store);
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String asked = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this manga into English, checked");
        assertTrue(asked.startsWith("\"volume.cbz\" is a comic of 3 pages, the language of its lettering not read yet. Into English as cbz: "), "no helper to look with yet: " + asked);
        assertTrue(asked.contains("the language of its lettering is found on a sample of its pages when the run starts (to name it yourself: --from <language> on the command line, \"from <language>\" in the chat)"), asked);
        assertTrue(asked.contains("Reading a comic's pages needs the comic helper, which is not set up on this machine yet.") && asked.contains("researchzosho translate comics status --list names each piece with its licence."), "the same three sentences as the command line: " + asked);
        assertTrue(asked.contains("Set the comic helper up and translate the comic? (yes/no) (J-0001)"), asked);
        assertFalse(asked.contains("units with words"), "a comic is not counted in units: " + asked);
        ObjectNode job = new Jobs(store, j -> "").get("J-0001");
        assertEquals("yes", job.path("args").path("comic_setup").asText(), "the job that starts on the yes sets the helper up first"); assertEquals("on", job.path("args").path("comic").asText());
        assertEquals("checked", job.path("args").path("quality").asText());
        assertEquals(Jobs.OFFERED, job.path("state").asText(), "nothing starts before the yes");
        // set up: the comic is translated as any file is, no question about the helper
        ComicHelper.INSTALLED = () -> true;
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String filed = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into English, quick");
        assertTrue(filed.startsWith("\"volume.cbz\" is a comic of 3 pages, its lettering read as Japanese. Into English as cbz: ") && filed.contains("Translating it now, quick (J-0002)"), filed);
        assertFalse(filed.contains("comic helper, which is not set up"), filed);
        assertTrue(filed.contains("the comic helper read its lettering as Japanese (pages looked at: 3; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat)"), "what the helper found, with the way to say otherwise: " + filed);
        assertFalse(new Jobs(store, j -> "").get("J-0002").path("args").has("comic_setup"));
        assertFalse(new Jobs(store, j -> "").get("J-0002").path("args").has("from"), "found by the program, not named by the person");
        assertEquals("ja", new Jobs(store, j -> "").get("J-0002").path("args").path("comic_plan").path("source").path("code").asText(), "the job keeps the language as it was found, and does not look again");
        // the person's words name the language: "from Korean" is the comic's source, said without a word on how it was found
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String named = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this manhwa from Korean into English, quick");
        assertTrue(named.startsWith("\"volume.cbz\" is a comic of 3 pages, its lettering read as Korean. Into English as cbz: "), named);
        assertFalse(named.contains("the comic helper read its lettering"), named);
        Matcher nid = Pattern.compile("Translating it now, quick \\((J-\\d+)\\)").matcher(named);
        assertTrue(nid.find(), named);
        assertEquals("ko", new Jobs(store, j -> "").get(nid.group(1)).path("args").path("from").asText());
        // the way the pages are read: "it reads right to left" is the person's word, kept with the job and out of the ask; calling an English edition a manga says the same
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String rtl = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this from English into German, quick. It reads right to left.");
        assertTrue(rtl.startsWith("\"volume.cbz\" is a comic of 3 pages, its lettering read as English. Into German as cbz: ") && rtl.contains("; its pages are read from the right to the left, as you said; "), rtl);
        assertFalse(rtl.contains("were not used") || rtl.contains("In the direction you gave"), "the sentence about the pages is no direction for the voice: " + rtl);
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String bracketed = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this from English into German (it reads right to left), quick");
        assertTrue(bracketed.contains("; its pages are read from the right to the left, as you said; ") && bracketed.contains("Translating it now, quick (") && !bracketed.contains("were not used"), "said in brackets, it is taken the same and nothing is left over: " + bracketed);
        Matcher rid = Pattern.compile("Translating it now, quick \\((J-\\d+)\\)").matcher(rtl);
        assertTrue(rid.find(), rtl);
        assertEquals("rtl", new Jobs(store, j -> "").get(rid.group(1)).path("args").path("reads").asText()); assertFalse(new Jobs(store, j -> "").get(rid.group(1)).path("args").has("direction"));
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String manga = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this manga from English into German, quick");
        assertTrue(manga.contains("; its pages are read from the right to the left, as a manga's are (if they run from the left, say so: --reads left-to-right on the command line, \"it reads left to right\" in the chat); "), manga);
        Matcher mid = Pattern.compile("Translating it now, quick \\((J-\\d+)\\)").matcher(manga);
        assertTrue(mid.find(), manga);
        assertEquals("manga", new Jobs(store, j -> "").get(mid.group(1)).path("args").path("reads").asText());
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String ltr = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this manga into English, quick. It reads left to right.");
        assertTrue(ltr.contains("its lettering read as Japanese. Into English as cbz: ") && ltr.contains("; its pages are read from the left to the right, as you said; "), "what the person says of the way is taken for a Japanese comic too, over the word manga: " + ltr);
        // with nothing said: a Japanese comic from the right, a Korean one from the left, each with the words that say otherwise
        assertTrue(filed.contains("; its pages are read from the right to the left (if they run from the left, say so: --reads left-to-right on the command line, \"it reads left to right\" in the chat); "), filed);
        assertTrue(named.contains("; its pages are read from the left to the right (if they run from the right, as a manga's do in any language, say so: --reads right-to-left on the command line, \"it reads right to left\" in the chat); "), named);
        // a comic already in the language asked for is refused in the chat's own reply
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        assertEquals("The comic helper read the lettering of \"volume.cbz\" as Japanese, the language asked for; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat.", TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into Japanese, quick"));
        // a comic has no end to number notes at: asked for them or not, its job is filed with notes off and the reply says where the doubts are listed
        for (String notes : List.of(", with the notes at the end", ", no notes")) {
            session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
            String r = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into English, checked" + notes);
            Matcher id = Pattern.compile("Translating it now, checked \\((J-\\d+)\\)").matcher(r);
            assertTrue(id.find(), r);
            assertEquals("off", new Jobs(store, j -> "").get(id.group(1)).path("args").path("notes").asText(), notes);
            assertTrue(r.endsWith("What the checks still doubt is listed in the note beside the translation, by page and balloon; the pages hold the translation alone."), r);
            assertFalse(r.contains("at the end of the document"), "nothing is promised at the end of a comic: " + r);
        }
        ComicHelper.INSTALLED = () -> false;   // and so where the helper is to be set up first
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String waits = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into English, checked, with the notes at the end");
        Matcher wid = Pattern.compile("\\(yes/no\\) \\((J-\\d+)\\)").matcher(waits);
        assertTrue(wid.find(), waits);
        assertEquals("off", new Jobs(store, j -> "").get(wid.group(1)).path("args").path("notes").asText());
        ComicHelper.INSTALLED = () -> true;
        // the tier not said: the question is a comic's, about balloons and the fit, and says where the doubts are listed
        session.att = M.createObjectNode().put("name", "volume.cbz").put("path", cbz.toString());
        String tier = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into English");
        assertTrue(tier.contains("Checked, quick, reviewed or thorough? Checked, the default, adds the program's checks, a sample translated back and the fit of each line to its balloon;") && tier.contains("thorough reads every balloon. What the checks still doubt is listed in the note beside the translation, by page and balloon; the pages hold the translation alone."), tier);
        assertFalse(tier.contains("scores") || tier.contains("at the end of the document"), tier);
        // a PDF of page pictures is a document unless the words say it is a comic
        Path pdf = pdf(tmp, 3);
        session.att = M.createObjectNode().put("name", "pages.pdf").put("path", pdf.toString());
        String said = TranslateTurn.reply(store, session, Patrons.Patron.PERSON, "translate this into English, quick. This is a manga.");
        assertTrue(said.startsWith("\"pages.pdf\" is a comic of 3 pages, its lettering read as Japanese. Into English as pdf: "), said);
    }

    @Test void aProgramsCallIsToldWhatASetUpFetchesAndSendsTheYesWithSetup(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path cbz = tmp.resolve("volume.cbz"); ComicStandIn.volume(cbz, 3);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode call = owner().put("path", cbz.toString()).put("to", "English").put("quality", "quick");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.translate(call));
        assertEquals(ProtocolError.CONFIRM, e.code, "the person's yes is asked for, not a program's");
        assertTrue(e.getMessage().startsWith("Reading a comic's pages needs the comic helper, which is not set up on this machine yet.") && e.getMessage().endsWith("Nothing was fetched, and the comic was not translated: send the same call with setup=true to set the comic helper up and translate it."), e.getMessage());
        JsonNode helper = e.extra.path("comic_helper");
        assertFalse(helper.path("set_up").asBoolean(true)); assertTrue(helper.path("download_bytes").asLong() > 0 && helper.path("fetches").size() >= 3, "each piece with its size and its licence, as data: " + helper);
        assertTrue(helper.path("fetches").get(0).hasNonNull("licence") && helper.path("fetches").get(0).hasNonNull("bytes"), helper.toString());
        assertTrue(new Jobs(store, j -> "").active().isEmpty(), "nothing is filed");
        // the same call with the yes: filed, the job sets the helper up first; the answer says it is a comic
        ObjectNode r = p.translate(call.deepCopy().put("setup", true));
        assertEquals("J-0001", r.path("job_id").asText()); assertTrue(r.path("comic_setup").asBoolean());
        assertEquals(3, r.path("comic").path("pages").asInt()); assertEquals("cbz", r.path("comic").path("container").asText()); assertEquals(ComicTranslation.NEEDS, r.path("comic").path("helper").asText());
        assertTrue(r.path("summary").asText().contains("\"volume.cbz\" (a comic of 3 pages, the language of its lettering not read yet) into English as cbz, quick"), r.path("summary").asText());
        assertEquals("", r.path("from").asText()); assertEquals("", r.path("comic").path("from").asText()); assertEquals(ComicTranslation.AT_START, r.path("comic").path("from_how").asText(), "found when the run starts: no helper to look with yet");
        assertEquals("yes", new Jobs(store, j -> "").get("J-0001").path("args").path("comic_setup").asText());
        // set up: no confirm, no setup needed; comic=off says an archive is a document, and a word that is neither is refused
        ComicHelper.INSTALLED = () -> true;
        ObjectNode ready = p.translate(call);
        assertFalse(ready.has("comic_setup")); assertEquals(ComicTranslation.READY, ready.path("comic").path("helper").asText());
        // the language of the lettering: found by the helper and said as data, or named in the call with from, a name or a code
        assertEquals("ja", ready.path("from").asText()); assertEquals("ja", ready.path("comic").path("from").asText()); assertEquals(ComicTranslation.SAMPLED, ready.path("comic").path("from_how").asText()); assertEquals(3, ready.path("comic").path("source").path("pages_looked_at").asInt());
        assertTrue(ready.path("summary").asText().contains("(a comic of 3 pages, its lettering read as Japanese) into English as cbz, quick") && ready.path("estimate").asText().contains("the comic helper read its lettering as Japanese (pages looked at: 3; "), ready.toString());
        ObjectNode korean = p.translate(call.deepCopy().put("from", "Korean"));
        assertEquals("ko", korean.path("from").asText()); assertEquals(ComicTranslation.BY_PERSON, korean.path("comic").path("from_how").asText());
        assertEquals("ko", new Jobs(store, j -> "").get(korean.path("job_id").asText()).path("args").path("from").asText());
        assertTrue(korean.path("summary").asText().contains("its lettering read as Korean"), korean.path("summary").asText());
        assertEquals("A comic's lettering is read in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version. Arabic is not read.", assertThrows(ProtocolError.class, () -> p.translate(call.deepCopy().put("from", "ar"))).getMessage());
        assertEquals("\"volume.cbz\" is written in English already.", assertThrows(ProtocolError.class, () -> p.translate(call.deepCopy().put("from", "en"))).getMessage());
        // reads: the way the pages are read, as the person says it; said back as data with how it is known; kept with the job; a word that is neither refused
        assertEquals("rtl", ready.path("comic").path("reads").asText()); assertEquals("found", ready.path("comic").path("reads_how").asText(), "a Japanese comic, nothing said");
        assertEquals("ltr", korean.path("comic").path("reads").asText()); assertEquals("found", korean.path("comic").path("reads_how").asText());
        for (String word : List.of("right-to-left", "rtl")) {
            ObjectNode said = p.translate(call.deepCopy().put("from", "English").put("to", "German").put("reads", word));
            assertEquals("rtl", said.path("comic").path("reads").asText(), word); assertEquals("said", said.path("comic").path("reads_how").asText());
            assertEquals("rtl", new Jobs(store, j -> "").get(said.path("job_id").asText()).path("args").path("reads").asText());
            assertTrue(said.path("estimate").asText().contains("its pages are read from the right to the left, as you said"), said.path("estimate").asText());
        }
        assertEquals("ltr", p.translate(call.deepCopy().put("reads", "left-to-right")).path("comic").path("reads").asText(), "said of a Japanese comic, it is taken as said");
        assertFalse(new Jobs(store, j -> "").get(ready.path("job_id").asText()).path("args").has("reads"), "nothing said, nothing kept as said");
        ProtocolError sideways = assertThrows(ProtocolError.class, () -> p.translate(call.deepCopy().put("reads", "sideways")));
        assertEquals("invalid_args", sideways.code); assertEquals("reads is right-to-left or left-to-right (rtl and ltr are taken too): the way a comic's pages are read.", sideways.getMessage());
        // the MCP tool names the argument, so that a host's model can pass what the person said
        JsonNode translate = null;
        for (JsonNode t : McpServer.handle("tools/list", M.createObjectNode()).get("tools")) if (t.path("name").asText().equals("library_translate")) translate = t;
        assertTrue(translate.path("inputSchema").path("properties").path("from").path("description").asText().startsWith("The language the document is written in, or a comic is lettered in"), String.valueOf(translate));
        assertTrue(translate.path("description").asText().contains("from names the language of its lettering, and without it the helper finds the language on a sample of the pages"), translate.path("description").asText());
        assertEquals("[\"right-to-left\",\"left-to-right\"]", translate.path("inputSchema").path("properties").path("reads").path("enum").toString(), String.valueOf(translate));
        assertTrue(translate.path("description").asText().contains("pass reads=right-to-left when the person says the comic reads from the right"), translate.path("description").asText());
        ProtocolError notOne = assertThrows(ProtocolError.class, () -> p.translate(call.deepCopy().put("comic", "off")));
        assertEquals("invalid_args", notOne.code, "a ZIP of pictures said to be a document is no format the document reader takes: " + notOne.getMessage());
        assertEquals("comic is on (the file is a comic, whatever the program finds) or off (it is a document).", assertThrows(ProtocolError.class, () -> p.translate(call.deepCopy().put("comic", "maybe"))).getMessage());
        // a folder of page pictures is a comic by itself, and a PDF is one on the word: true is read as on
        Path folder = Files.createDirectories(tmp.resolve("chapter"));
        int n = 0; for (byte[] png : ComicStandIn.volume(tmp.resolve("two.cbz"), 2)) Files.write(folder.resolve(String.format("%03d.png", ++n)), png);
        assertEquals("folder", p.translate(owner().put("path", folder.toString()).put("to", "en").put("quality", "quick")).path("comic").path("container").asText());
        Path pdf = pdf(tmp, 3);
        assertEquals("invalid_args", assertThrows(ProtocolError.class, () -> p.translate(owner().put("path", pdf.toString()).put("to", "en").put("quality", "quick").put("comic", false))).code, "said to be a document, and a document of pictures alone has nothing the document reader takes");
        assertEquals(ComicTranslation.SAID, p.translate(owner().put("path", pdf.toString()).put("to", "en").put("quality", "quick").put("comic", true)).path("comic").path("taken").asText());
    }
}
