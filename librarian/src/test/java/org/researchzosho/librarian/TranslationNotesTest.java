package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.DocUnits.Doc;
import org.researchzosho.librarian.DocUnits.Unit;
import org.researchzosho.librarian.Translation.Doubt;
import org.researchzosho.librarian.Translation.Flag;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.TranslationTest.Script;
import org.researchzosho.mcp.McpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a checked translation writes what its checks still doubt after the revision: by default nowhere in the document, which holds the
 * translation alone, and every such passage listed in the note beside it; with the notes at the end, a number in brackets after each and a
 * last section, titled in the document's new language, that lists them by number. The choice on every surface that asks for a translation
 * (the command, the form, the tool), the setting as its default, and a job resumed after a restart keeping what was asked.
 */
class TranslationNotesTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void standIns() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> "";
    }

    @AfterEach void back() {
        TranslationScores.SCORERS = TranslationScores::configured; Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        Translation.SEATS = Translation::seatsFor; Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow;
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static final String KEPT = "The fourth paragraph is one the translator gives nothing back for, so it stays.";

    /** A heading and four paragraphs: the first and third carry a number the stand-in drops, and drops again on revision; the fourth gets no translation at all. */
    static Path clock(Path dir) throws Exception {
        Path f = Files.createDirectories(dir).resolve("clock notes.md");
        Files.writeString(f, "# The clock\n\nThe first wheel turns once in 7 hours and carries the hand around the dial.\n\n"
                + "The second wheel is cut from brass and meshes with the first one at the rim.\n\n"
                + "The third wheel turns once in 17 days and drives the small dial of the moon.\n\n" + KEPT + "\n", StandardCharsets.UTF_8);
        return f;
    }

    /** The translator loses every 7, and loses it again when asked to revise; the fourth paragraph comes back empty both times. */
    static Script dropping() {
        Script sc = new Script();
        sc.translate = t -> t.contains("nothing back") ? null : "訳 " + t.replace("7", "");
        sc.revise = s -> s.contains("nothing back") ? null : "訂正 " + s.replace("7", "");
        return sc;
    }

    Outcome run(Path in, String to, String as, String name, String notes) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        return Translation.run(store, in, to, as, "checked", tmp.resolve("out").resolve(name), null, TranslationTest.seats(dropping()), null, () -> false, Checkpoint.NONE, notes);
    }

    static final Pattern MARK = Pattern.compile("\\[†?\\d+\\]");

    static int marks(String s) { Matcher m = MARK.matcher(s); int n = 0; while (m.find()) n++; return n; }

    @Test void byDefaultTheDocumentHoldsTheTranslationAloneAndTheNoteListsEachDoubt() throws Exception {
        Path in = clock(tmp.resolve("docs"));
        Outcome o = run(in, "ja", "", "clock.ja.md", "");
        assertFalse(o.notesAtEnd(), "off unless asked");
        assertEquals(List.of(1, 3, 4), o.doubts().stream().map(Doubt::unit).toList(), "the two lost numbers and the paragraph with no translation, in order: " + o.doubts());
        assertTrue(o.doubts().get(2).original() && o.keptOriginal() == 1 && o.translated() == 4, o.doubts() + " " + o.summary());
        String out = Files.readString(o.output());
        assertEquals(0, marks(out), "no number in brackets anywhere: " + out);
        assertFalse(out.contains("訳注") || out.contains("［") || out.contains("translation note") || out.contains("check this") || out.contains("not in the translation"), out);
        assertTrue(out.contains("\n訳 The first wheel turns once in  hours and carries the hand around the dial.\n") && out.contains("\n" + KEPT + "\n"), "the translation as it came, and the paragraph kept in its original words with nothing added: " + out);
        assertTrue(out.strip().endsWith(KEPT), "nothing after the last paragraph: " + out);
        // the note lists every passage still in doubt, with its first words and why
        String note = Files.readString(o.note());
        assertTrue(note.contains("## Still in doubt\n\nThe checks still doubt 3 passages after their revision, here in the order of the document. The document holds the translation alone, with no mark: find a passage by its first words. To have them numbered at the end of the document, ask for the notes at the end: --notes end on the command line, the box on the Translate form, \"with the notes at the end\" in the chat, notes=end on the tool, or the setting translate_notes set to end for every translation."), note);
        assertTrue(note.contains("| number | unit | begins | why |\n| --- | --- | --- | --- |\n| 1 | 2 | 訳 The first wheel turns once in hours … | number 「7」: the number 7 is not in the translation |\n"
                + "| 2 | 4 | 訳 The third wheel turns once in 1 … | number 「17」: the number 17 is not in the translation |\n"
                + "| 3 | 5 | The fourth paragraph is one the translator gives … | omission: no translation came back for this unit (it stays in the original) |\n"), note);
        for (Flag f : o.standing()) assertTrue(note.substring(note.indexOf("## Still in doubt")).contains(f.why()), "each flag that stands is in the list: " + f);
        assertTrue(note.contains("- Standing after everything: 3, in 3 passages listed under Still in doubt below, with no mark in the document;"), note);
        assertFalse(Files.exists(Translation.doubtsFor(o.output())), "a short list needs no file of its own");
        // the result says how many, where they are, and how to have them at the end, for the surface that asked
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        assertTrue(Translation.result(store, o, false, "cli").contains("\n\nThe checks still doubt 3 passages after their revision: the translation note lists each with its first words and why, and the document holds the translation alone; to have them numbered at the end of the document, translate with --notes end, or set translate_notes to end for every translation."), Translation.result(store, o, false, "cli"));
        assertTrue(Translation.result(store, o, true, "web").contains("tick \"" + Translation.NOTES_BOX + "\" on the Translate form"));
        assertTrue(Translation.result(store, o, true, "mcp").contains("ask with notes=end"));
        assertTrue(Translation.result(store, o, true, "chat").contains("say \"with the notes at the end\" when you ask for the translation in the chat, or set translate_notes to end on the Settings page for every translation"));
        assertTrue(Translation.result(store, o, true, "").contains("set translate_notes to end on the Settings page (researchzosho settings set translate_notes end) and translate again"), "a job filed before the surface was recorded");
        assertFalse(o.summary().contains("marked"), o.summary());
        // the first words: whole words in a spaced language, forty characters of one written without spaces
        assertEquals("訂正 the hour, as bold letters", Translation.begins("訂正 the hour, as **bold** letters"));
        String ja = "文字盤は十三世紀に生まれた。".repeat(4);
        assertEquals(ja.substring(0, 40) + " …", Translation.begins(ja));
        assertEquals(("短い " + ja).substring(0, 40) + " …", Translation.begins("短い " + ja), "a space near the start does not cut the run short");
        // a Word file the same: the translation alone, no section at its end
        Outcome docx = run(in, "ja", "docx", "clock.ja.docx", "off");
        List<Unit> units = DocUnits.cut(Files.readAllBytes(docx.output()), "clock.ja.docx").units();
        assertTrue(units.stream().noneMatch(u -> marks(u.text()) > 0 || u.text().contains("訳注")), units.toString());
        assertEquals(KEPT, units.get(units.size() - 1).text(), "the last paragraph is the document's own: " + units);
        assertEquals(3, docx.doubts().size());
        // the quick tier flags nothing: no list, no section
        LibraryStore s2 = new LibraryStore(tmp.resolve("lib2")); s2.init();
        Outcome quick = Translation.run(s2, in, "ja", "", "quick", tmp.resolve("out").resolve("quick.md"), null, TranslationTest.seats(dropping()), null, () -> false, Checkpoint.NONE, "end");
        assertTrue(quick.doubts().isEmpty() && !Files.readString(quick.output()).contains("訳注") && !Files.readString(quick.note()).contains("Still in doubt"), Files.readString(quick.note()));
    }

    @Test void withTheNotesAtTheEndEachDoubtIsNumberedInOrderAndListedUnderATitleInTheNewLanguage() throws Exception {
        Path in = clock(tmp.resolve("docs"));
        Outcome o = run(in, "ja", "", "clock.ja.md", "end");
        assertTrue(o.notesAtEnd());
        String out = Files.readString(o.output());
        int one = out.indexOf("訳 The first wheel turns once in  hours and carries the hand around the dial. [1]\n"), two = out.indexOf("訳 The third wheel turns once in 1 days and drives the small dial of the moon. [2]\n"), three = out.indexOf(KEPT + " [3]\n"), section = out.indexOf("\n## 訳注\n");
        assertTrue(one > 0 && two > one && three > two && section > three, "the numbers after their passages, in order, then the section: " + out);
        assertTrue(out.contains("\n訳 The second wheel is cut from brass and meshes with the first one at the rim.\n"), "a passage the checks do not doubt carries no number: " + out);
        assertEquals(section, out.lastIndexOf("\n#"), "the section is the document's last: " + out);
        assertTrue(out.endsWith("## 訳注\n\n[1] number 「7」: the number 7 is not in the translation\n\n[2] number 「17」: the number 17 is not in the translation\n\n[3] omission: no translation came back for this unit\n"), out);
        String after = out.substring(section);
        assertEquals(o.doubts().size(), marks(after), "one entry a passage: " + after);
        assertEquals(2 * o.doubts().size(), marks(out));
        assertTrue(o.translatedTitle().equals("訳 The clock") && out.startsWith("# 訳 The clock\n"), "the title carries no number: " + o.translatedTitle());
        String note = Files.readString(o.note());
        assertTrue(note.contains("Each is marked in the document with its number after its text, [1] to [3], and listed under \"訳注\" at the document's end.") && note.contains("| [1] | 2 | 訳 The first wheel turns once in hours … |"), note);
        assertTrue(note.contains("- Standing after everything: 3, in 3 passages numbered in the document and listed at its end under \"訳注\", and under Still in doubt below;"), note);
        assertTrue(Translation.result(new LibraryStore(tmp.resolve("lib")), o, false, "cli").contains("The checks still doubt 3 passages after their revision: each is marked with its number in brackets and listed at the end of the document under \"訳注\", and the translation note lists them too."));
        // an EPUB gets the section as its last chapter, in its navigation
        Outcome epub = run(in, "ja", "epub", "clock.ja.epub", "end");
        byte[] bytes = Files.readAllBytes(epub.output());
        DocUnitsTest.checkEpubPackage(bytes);
        Doc back = DocUnits.cut(bytes, "clock.ja.epub");
        List<Unit> last = back.chapters().get(back.chapters().size() - 1).units();
        assertEquals("訳注", last.get(0).text()); assertEquals(DocUnits.HEADING, last.get(0).kind());
        assertEquals(List.of("[1] number 「7」: the number 7 is not in the translation", "[2] number 「17」: the number 17 is not in the translation", "[3] omission: no translation came back for this unit"), last.subList(1, last.size()).stream().map(Unit::text).toList());
        assertTrue(new String(DocUnits.unzip(bytes).get("OEBPS/nav.xhtml"), StandardCharsets.UTF_8).contains(">訳注</a>"));
        assertTrue(back.chapters().get(0).units().stream().anyMatch(u -> u.text().endsWith("the small dial of the moon. [2]")), back.chapters().get(0).units().toString());
        // German: the same section under a German title
        Outcome de = run(in, "de", "", "clock.de.md", "end");
        String deOut = Files.readString(de.output());
        assertTrue(deOut.contains("\n## Anmerkungen zur Übersetzung\n\n[1] number 「7」") && deOut.indexOf(" [3]\n") < deOut.indexOf("## Anmerkungen"), deOut);
        assertTrue(Files.readString(de.note()).contains("listed under \"Anmerkungen zur Übersetzung\" at the document's end"));
        assertEquals("Notes de traduction", Translation.notesTitle("fr")); assertEquals("Translation notes", Translation.notesTitle("en"));
    }

    @Test void aDocumentWithNumbersInBracketsOfItsOwnGetsMarksThatCannotBeTakenForThem() throws Exception {
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("cited.md");
        Files.writeString(in, "# The clock\n\nThe first wheel turns once in 7 hours, as the catalogue says [1], and carries the hand.\n\n[1] The museum's catalogue.\n", StandardCharsets.UTF_8);
        Outcome o = run(in, "ja", "", "cited.ja.md", "end");
        String out = Files.readString(o.output());
        assertTrue(out.contains("as the catalogue says [1], and carries the hand. [†1]\n") && out.contains("\n訳 [1] The museum's catalogue.\n") && out.endsWith("## 訳注\n\n[†1] number 「7」: the number 7 is not in the translation\n"), out);
        assertTrue(Files.readString(o.note()).contains("the dagger tells these numbers apart from the numbers in brackets the document's own text has"), Files.readString(o.note()));
    }

    @Test void aLongListIsCutInTheNoteAndKeptWholeInAFileBesideIt() throws Exception {
        StringBuilder b = new StringBuilder("# The gears\n\n");
        for (int i = 1; i <= Translation.DOUBTS_SHOWN + 10; i++) b.append("Gear ").append(i).append(" turns 7 times for every turn of the dial before it.\n\n");
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("gears.md");
        Files.writeString(in, b.toString(), StandardCharsets.UTF_8);
        Outcome o = run(in, "ja", "", "gears.ja.md", "");
        assertEquals(Translation.DOUBTS_SHOWN + 10, o.doubts().size());
        String note = Files.readString(o.note());
        String list = note.substring(note.indexOf("## Still in doubt"), note.indexOf("\n## ", note.indexOf("## Still in doubt") + 5));
        assertEquals(Translation.DOUBTS_SHOWN, list.lines().filter(l -> l.matches("^\\| \\d+ \\| \\d+ \\| .*")).count(), list);
        assertTrue(list.contains("\n10 more are not shown here: the whole list is in gears.ja.translation-doubts.json, beside this note.\n"), list);
        JsonNode whole = M.readTree(Files.readString(Translation.doubtsFor(o.output())));
        assertEquals(Translation.DOUBTS_SHOWN + 10, whole.path("doubts").size());
        assertEquals("off", whole.path("notes").asText());
        JsonNode lastOne = whole.path("doubts").get(Translation.DOUBTS_SHOWN + 9);
        assertTrue(lastOne.path("number").asInt() == Translation.DOUBTS_SHOWN + 10 && lastOne.path("why").asText().contains("the number 7 is not in the translation") && !lastOne.has("mark"), lastOne.toString());
        assertEquals(o.doubtsFile(), Translation.doubtsFor(o.output()));
        assertTrue(Translation.result(new LibraryStore(tmp.resolve("lib")), o, false, "cli").contains("The whole list of what the checks still doubt: " + o.doubtsFile().toAbsolutePath().normalize()));
        assertEquals(0, marks(Files.readString(o.output())));
    }

    @Test void theSettingIsTheDefaultAndEachAskOverridesItBothWays() throws Exception {
        Path in = clock(tmp.resolve("docs"));
        assertEquals("off", Translation.notes("")); assertEquals("end", Translation.notes("End")); assertEquals("off", Translation.notes("no")); assertNull(Translation.notes("sideways"));
        assertEquals("off", Translation.args(in, null, "ja", "", "", null, null, "cli").path("notes").asText(), "unset: off");
        Settings.Setting s = Settings.named("translate.notes");
        assertNotNull(s); assertEquals(Translation.NOTES_SETTING, s.key()); assertEquals(List.of("off", "end"), s.choices());
        assertTrue(Settings.refuse(s, "sideways").startsWith("translate_notes is one of off, end"), Settings.refuse(s, "sideways"));
        assertTrue(Settings.list(M).toString().contains("\"name\":\"translate_notes\""), "listed with the settings, so the Settings page and the command show it");
        Settings.set(s, "end");
        assertEquals("end", Translation.notes(""));
        assertEquals("end", Translation.args(in, null, "ja", "", "", null, null, "cli").path("notes").asText(), "the setting is the default");
        assertEquals("off", Translation.args(in, null, "ja", "", "", null, null, "cli", "", "", "", "off").path("notes").asText(), "an ask for off overrides it");
        Outcome bySetting = run(in, "ja", "", "by-setting.md", "");
        assertTrue(bySetting.notesAtEnd() && Files.readString(bySetting.output()).contains("\n## 訳注\n"));
        Outcome askedOff = run(in, "ja", "", "asked-off.md", "off");
        assertFalse(askedOff.notesAtEnd()); assertEquals(0, marks(Files.readString(askedOff.output())));
        Settings.set(s, "off");
        assertEquals("end", Translation.args(in, null, "ja", "", "", null, null, "cli", "", "", "", "end").path("notes").asText(), "an ask for end overrides the setting's off");
        Outcome askedEnd = run(in, "ja", "", "asked-end.md", "end");
        assertTrue(askedEnd.notesAtEnd() && Files.readString(askedEnd.output()).contains("\n## 訳注\n"));
        // a job reads the choice from its arguments, whatever the setting says when it runs
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Translation.SEATS = (drive, model, judge, backTr) -> TranslationTest.seats(dropping());
        Translation.DRIVE_ANSWERS = url -> true;
        ObjectNode job = M.createObjectNode(); job.put("job_id", "J-0001"); job.put("kind", Translation.JOB_KIND);
        job.set("args", Translation.args(in, "clock notes.md", "ja", "", "checked", tmp.resolve("out").resolve("job.md"), null, "cli", "", "", "", "end"));
        String result = Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", null, () -> false);
        assertTrue(Files.readString(tmp.resolve("out").resolve("job.md")).contains("\n## 訳注\n") && result.contains("listed at the end of the document under \"訳注\""), result);
        assertThrows(IOException.class, () -> Translation.run(store, in, "ja", "", "checked", tmp.resolve("out").resolve("x.md"), null, TranslationTest.seats(dropping()), null, () -> false, Checkpoint.NONE, "sideways"));
    }

    /** A translation job stopped partway by a restart, the setting changed while it was down: the resumed job writes the notes it was asked for. */
    @Test void aResumedJobKeepsTheNotesItWasAskedFor() throws Exception {
        Path in = clock(tmp.resolve("docs"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Settings.set(Settings.named("translate_notes"), "end");
        ObjectNode job = M.createObjectNode(); job.put("job_id", "J-0001"); job.put("kind", Translation.JOB_KIND);
        Path out = tmp.resolve("out").resolve("resumed.ja.md");
        job.set("args", Translation.args(in, "clock notes.md", "ja", "", "checked", out, null, "cli"));
        assertEquals("end", job.path("args").path("notes").asText(), "settled when the job is filed");
        String basis = Translation.basis(job);
        Translation.DRIVE_ANSWERS = url -> true;
        Translation.SEATS = (drive, model, judge, backTr) -> { CheckpointTest.Counting d = new CheckpointTest.Counting(TranslationTest.standIn(dropping(), "stand-in"), 3); return new Translation.Seats(d, "stand-in", d, "stand-in"); };
        Checkpoint first = Checkpoint.open(store, "J-0001", Translation.JOB_KIND, basis);
        assertThrows(IllegalStateException.class, () -> Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", null, () -> false, first), "the process ends at its third call");
        first.close();
        assertFalse(Files.exists(out));
        Settings.set(Settings.named("translate_notes"), "off");   // changed while the job was down
        Translation.SEATS = (drive, model, judge, backTr) -> TranslationTest.seats(dropping());
        Checkpoint second = Checkpoint.open(store, "J-0001", Translation.JOB_KIND, Translation.basis(job));
        assertTrue(second.resumed(), "the same work, resumed: " + second.startedOver());
        String result = Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", null, () -> false, second);
        second.close();
        String written = Files.readString(out);
        assertTrue(written.contains(KEPT + " [3]\n") && written.contains("\n## 訳注\n\n[1] number 「7」"), "the notes at the end, as filed: " + written);
        assertTrue(result.contains("listed at the end of the document under \"訳注\""), result);
    }

    @Test void theFormTheToolAndTheCommandOfferTheChoice() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Translation.ANY_MODEL = () -> true;
        // the form: a box, unticked unless the setting says end, and the post read as the person left it
        String form = Pages.translateForm(store);
        assertTrue(form.contains("<input type=\"hidden\" name=\"notes_box\" value=\"1\"><label><input type=\"checkbox\" name=\"notes\" value=\"end\"> " + Translation.NOTES_BOX + "</label>"), form);
        assertTrue(form.contains("What the checks still doubt is listed in the note beside the translation, never in its text"), form);
        Settings.set(Settings.named("translate_notes"), "end");
        assertTrue(Pages.translateForm(store).contains("name=\"notes\" value=\"end\" checked>"), "ticked when the setting makes it the default");
        Path up = Files.createTempDirectory(Uploads.DIR);
        Files.writeString(up.resolve("a.md"), "# The clock\n\nThe first wheel turns once in 7 hours.\n");
        Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("file.path", up.resolve("a.md").toString(), "file.name", "a.md", "to", "ja", "notes_box", "1"));
        assertEquals("off", new Jobs(store, j -> "").get("J-0001").path("args").path("notes").asText(), "the box left unticked is an off, whatever the setting");
        Path up2 = Files.createTempDirectory(Uploads.DIR);
        Files.writeString(up2.resolve("b.md"), "# The clock\n\nThe first wheel turns once in 7 hours.\n");
        Settings.set(Settings.named("translate_notes"), "off");
        Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("file.path", up2.resolve("b.md").toString(), "file.name", "b.md", "to", "ja", "notes_box", "1", "notes", "end"));
        assertEquals("end", new Jobs(store, j -> "").get("J-0002").path("args").path("notes").asText(), "ticked is an end, whatever the setting");
        // the tool: the argument in its schema, read, refused when it is neither word
        JsonNode tool = null;
        for (JsonNode t : McpServer.handle("tools/list", M.createObjectNode()).get("tools")) if (t.path("name").asText().equals("library_translate")) tool = t;
        assertNotNull(tool);
        JsonNode notes = tool.path("inputSchema").path("properties").path("notes");
        assertEquals("[\"off\",\"end\"]", notes.path("enum").toString(), notes.toString());
        assertTrue(tool.path("description").asText().contains("notes=end numbers those passages and lists them at the end of the document too"), tool.path("description").asText());
        Path f = clock(tmp.resolve("docs"));
        ObjectNode a = M.createObjectNode(); a.put("path", f.toString()); a.put("to", "ja"); a.put("notes", "end"); a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = new LibraryProtocol(store).translate(a);
        assertEquals("end", r.path("notes").asText());
        assertEquals("end", new Jobs(store, j -> "").get(r.path("job_id").asText()).path("args").path("notes").asText());
        a.put("notes", "sideways");
        ProtocolError e = assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).translate(a));
        assertEquals("invalid_args", e.code); assertEquals(Translation.NOTES_SAID, e.getMessage());
        // the command: the flag in both usage texts, refused when it is neither word, and run here when no service serves the library
        assertTrue(LibrarianCli.USAGE.contains("translate <file> --to <language> [--as md|txt|pdf|docx|epub] [--quality checked|quick|reviewed|thorough] [--notes end|off]") && LibrarianCli.USAGE.contains("--notes end numbers those passages and lists them at the end of the document too"), LibrarianCli.USAGE);
        assertTrue(TranslateCli.USAGE.contains("[--notes end|off]") && TranslateCli.USAGE.contains("--notes end numbers those passages and lists them at its end too"), TranslateCli.USAGE);
        Translation.SEATS = (drive, model, judge, backTr) -> TranslationTest.seats(dropping());
        Translation.DRIVE_ANSWERS = url -> true;
        var bo = new ByteArrayOutputStream(); var be = new ByteArrayOutputStream();
        var o = System.out; var err = System.err;
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try {
            assertEquals(2, TranslateCli.translate(store, new String[]{"researchzosho", "translate", f.toString(), "--to", "ja", "--notes", "sideways"}));
            assertTrue(be.toString(StandardCharsets.UTF_8).contains(Translation.NOTES_SAID), be.toString(StandardCharsets.UTF_8));
            assertEquals(0, TranslateCli.translate(store, new String[]{"researchzosho", "translate", f.toString(), "--to", "ja", "--notes", "end", "--out", tmp.resolve("out").resolve("cli.ja.md").toString()}));
        } finally { System.setOut(o); System.setErr(err); }
        assertTrue(Files.readString(tmp.resolve("out").resolve("cli.ja.md")).contains("\n## 訳注\n\n[1] number 「7」"), Files.readString(tmp.resolve("out").resolve("cli.ja.md")));
        assertTrue(bo.toString(StandardCharsets.UTF_8).contains("listed at the end of the document under \"訳注\""), bo.toString(StandardCharsets.UTF_8));
    }
}
