package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asking beside the reading: at a terminal a question a file raises is put while the reading goes on to the next file, and its answer is
 * filed at once; Enter leaves it open and the next is asked; no line within the time leaves it open, every question gets its own wait, and
 * the questions that got no answer while the reading went on are asked once more at its end. A question a later file settled is not put.
 * Where nobody can answer, or the setting is 0, nothing is asked and nothing waits.
 */
class FamilyAskingTest {

    static final String BOOK = "In 1932 森田健二 was adopted by 森田勇 as 婿養子.\nIn 1940 髙橋正二 was adopted by 髙橋源三郎 as 婿養子.\n";

    private String home;

    @BeforeEach void reader() {
        GenealogyProfile.useReader(prompt -> "{\"people\": [], \"facts\": ["
                + "{\"subject\": \"森田健二\", \"relation\": \"adopted-by\", \"object\": \"森田勇\", \"date\": \"1932\", \"quote\": \"In 1932 森田健二 was adopted by 森田勇 as 婿養子.\"},"
                + "{\"subject\": \"髙橋正二\", \"relation\": \"adopted-by\", \"object\": \"髙橋源三郎\", \"date\": \"1940\", \"quote\": \"In 1940 髙橋正二 was adopted by 髙橋源三郎 as 婿養子.\"}]}");
        home = System.getProperty("user.home");
    }

    @AfterEach void restore() {
        GenealogyProfile.useReader(null);
        Interaction.OVERRIDE = null;
        Interaction.INPUT = null;
        System.setProperty("user.home", home);
        Config.invalidate();
    }

    /** The setting in a scratch config file, as a person writes it: genealogy.ask.seconds = N. */
    static void seconds(Path tmp, int n) throws Exception {
        Path h = tmp.resolve("home");
        Files.createDirectories(h.resolve(".researchzosho"));
        Files.writeString(h.resolve(".researchzosho").resolve("config"), "genealogy.ask.seconds = " + n + "\n", StandardCharsets.UTF_8);
        System.setProperty("user.home", h.toString());
        Config.invalidate();
    }

    /** A person at the keyboard who types nothing: no line is ever there, and the input never blocks. */
    static BufferedReader silent() {
        return new BufferedReader(new Reader() {
            @Override public int read(char[] b, int off, int len) { return -1; }
            @Override public boolean ready() { return false; }
            @Override public void close() { }
        });
    }

    static String read(LibraryStore store, Path tmp) throws Exception {
        Path book = tmp.resolve("book.txt");
        Files.writeString(book, BOOK, StandardCharsets.UTF_8);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", book.toString(), "--by", "an aunt"}); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    static LibraryStore store(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init();
        new LibrarianIndex(s, Embeddings.none()).rebuild();
        return s;
    }

    static int count(String s, String what) { int n = 0, at = 0; while ((at = s.indexOf(what, at)) >= 0) { n++; at += what.length(); } return n; }

    @Test
    void anAnswerIsFiledDuringTheReadAndEnterLeavesTheNextOpen(@TempDir Path tmp) throws Exception {
        seconds(tmp, 5);
        LibraryStore store = store(tmp);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n\n"));
        String out = read(store, tmp);
        assertEquals(2, count(out, "HOW DID THE NAME CHANGE?"), "both questions the file raised are put: " + out);
        assertTrue(out.contains("Press Enter alone to answer it later, with researchzosho genealogy who.") && out.contains("If no answer comes in 5 seconds, the question stays open."), out);
        assertFalse(out.contains("The reading goes on while this question waits."), "one file: nothing more is read while its questions wait: " + out);
        assertTrue(out.contains("Saved as your answer:"), "the first is answered at once: " + out);
        assertTrue(out.contains("Left for later. It waits for you: researchzosho genealogy who"), out);
        assertTrue(out.contains("One question about names and families that this read raised waits for you. To answer it: researchzosho genealogy who. It shows the answers"), "the summary: " + out);
        List<String> asked = Files.readAllLines(FamilyNameQuestions.askedFile(store));
        assertEquals(1, asked.size());
        assertEquals("answered", asked.get(0).split("\t")[1]);
        Finding member = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("member-of")).findFirst().orElseThrow();
        assertTrue(member.sources().get(0).locator().startsWith(FamilyNameQuestions.SOURCE), "the family's word: " + member.sources());
        assertEquals(Finding.State.accepted, member.state());
        assertEquals(1, FamilyNameQuestions.open(store).size(), "the one left with Enter is open for the sitting");
    }

    @Test
    void enterLeavesTheFirstAndTheNextIsAskedAndAnswered(@TempDir Path tmp) throws Exception {
        seconds(tmp, 5);
        LibraryStore store = store(tmp);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("\n1\n"));
        String out = read(store, tmp);
        assertTrue(out.indexOf("Left for later.") < out.indexOf("Saved as your answer:"), out);
        List<FamilyNameQuestions.Question> open = FamilyNameQuestions.open(store);
        assertEquals(1, open.size());
        assertTrue(open.get(0).text().contains("森田健二"), "the first one waits: " + open);
    }

    // the owner, 0.5.0: the first question left unanswered no longer ends the asking of the rest; every question gets its wait
    @Test
    void noLineWithinTheTimeLeavesEachQuestionOpenAndEveryQuestionGetsItsWait(@TempDir Path tmp) throws Exception {
        seconds(tmp, 1);
        LibraryStore store = store(tmp);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = silent();
        long start = System.nanoTime();
        String out = read(store, tmp);
        long took = (System.nanoTime() - start) / 1_000_000;
        assertEquals(2, count(out, "HOW DID THE NAME CHANGE?"), "both questions are put, each once: " + out);
        assertEquals(2, count(out, "No answer came within 1 second. The question waits for you: researchzosho genealogy who"), out);
        assertFalse(out.contains("asks once more"), "one file: the reading was finished when they were put, so neither is put again: " + out);
        assertTrue(out.contains("2 questions about names and families that this read raised wait for you. To answer them one at a time: researchzosho genealogy who."), "the summary: " + out);
        assertTrue(out.contains("WHAT HAPPENS NEXT"), "the read finished: " + out);
        assertEquals(2, FamilyNameQuestions.open(store).size());
        assertFalse(Files.exists(FamilyNameQuestions.askedFile(store)), "nothing was answered or put off");
        assertTrue(took >= 2_000 && took < 30_000, "one wait per question: " + took + " ms");
    }

    @Test
    void nobodyAtTheKeyboardOrASettingOfZeroNeverAsksAndNeverWaits(@TempDir Path tmp) throws Exception {
        seconds(tmp, 60);
        LibraryStore store = store(tmp);
        Interaction.OVERRIDE = false;
        String out = read(store, tmp);
        assertFalse(out.contains("HOW DID THE NAME CHANGE?") || out.contains("Your answer:"), out);
        assertEquals(2, FamilyNameQuestions.open(store).size(), "the questions are simply open");
        assertFalse(FamilyAsking.on(new FamilyAsking.Session()));

        seconds(tmp.resolve("zero"), 0);
        assertEquals(0, FamilyAsking.seconds());
        LibraryStore other = store(tmp.resolve("zero"));
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n"));
        String said = read(other, tmp.resolve("zero"));
        assertFalse(said.contains("HOW DID THE NAME CHANGE?"), said);
        assertEquals(2, FamilyNameQuestions.open(other).size());
    }

    // e-owner-6: a tree file raised questions about several people, and after the first answer only those about its people were asked; the
    // others were neither asked nor counted
    @Test
    void aTreeFileAsksEveryQuestionItRaisedAfterTheFirstAnswer(@TempDir Path tmp) throws Exception {
        seconds(tmp, 5);
        LibraryStore store = store(tmp);
        String[] lines = BOOK.split("\n");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", lines[0], Map.of("kind", "mukoyoshi")),
                        new FamilyAccount.Fact("髙橋正二", "adopted-by", "髙橋源三郎", "1940", lines[1], Map.of("kind", "mukoyoshi"))), List.of()),
                "file:///family/tree.ged", "a tree file");
        assertEquals(2, FamilyNameQuestions.open(store).size());
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n1\n"));
        FamilyAsking.Session s = new FamilyAsking.Session();
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { FamilyAsking.afterFiling(store, Set.of(), Set.of(), s, true); FamilyAsking.end(s); } finally { System.setOut(was); }
        assertEquals(2, s.asked(), "an import is about anybody: every question it raised is asked: " + out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(), FamilyNameQuestions.open(store));
    }

    @Test
    void theWaitLooksForAWholeLineAndReadsItOnlyThen() throws Exception {
        Interaction.INPUT = new BufferedReader(new StringReader("1932\n"));
        FamilyAsking.Typed t = FamilyAsking.lineWithin(1);
        assertFalse(t.timedOut());
        assertEquals("1932", t.line());
        Interaction.INPUT = silent();
        long start = System.nanoTime();
        assertTrue(FamilyAsking.lineWithin(1).timedOut());
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 900, "it waited the time");
    }

    // ── beside the reading: a folder of three files ───────────────────────────────────────────────────────────────────

    static final String ADOPTED = "In 1932 森田健二 was adopted by 森田勇 as 婿養子.";
    static final String HALE = "Mary Hale was born in York. She kept a shop on the high street for forty years, and her letters to her sister are in the blue box.";
    static final String HART = "Tom Hart was a carpenter in York. He built the benches of the chapel, and his tools are still in the shed behind the house where he lived.";

    /** The reader's answer for the first file: the adoption as 婿養子, which raises how 森田健二 came into the 森田 family. */
    static String adoptedRead() {
        return "{\"people\": [], \"facts\": [{\"subject\": \"森田健二\", \"relation\": \"adopted-by\", \"object\": \"森田勇\", \"kind\": \"mukoyoshi\", \"date\": \"1932\", \"quote\": \"" + ADOPTED + "\"}]}";
    }

    static final String NOTHING = "{\"people\": [], \"facts\": []}";

    /** A folder of three notes, read in this order (the smallest first): the adoption, Mary Hale's, Tom Hart's. */
    static Path folder(Path tmp, String first, String second, String third) throws Exception {
        Path dir = tmp.resolve("notes");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1-morita.txt"), first + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2-hale.txt"), second + "\n", StandardCharsets.UTF_8);
        if (third != null) Files.writeString(dir.resolve("3-hart.txt"), third + "\n", StandardCharsets.UTF_8);
        return dir;
    }

    /** Reads a folder as the command does, with what it prints kept in {@code out}, which the scripted reader may look at while it reads. */
    static String readFolder(LibraryStore store, Path dir, ByteArrayOutputStream out) throws Exception {
        PrintStream was = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", dir.toString()}); } finally { System.setOut(was); }
        assertSame(was, System.out, "the terminal is given back");
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Waits until {@code what} holds, looking every 20 ms, for at most ten seconds; whether it held. */
    static boolean until(BooleanSupplier what) {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            if (what.getAsBoolean()) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return what.getAsBoolean();
    }

    /** A person who types {@code line} once {@code when} holds, as a person answers a question once it is on the screen; nothing more after. */
    static BufferedReader typesWhen(BooleanSupplier when, String line) {
        return new BufferedReader(new Reader() {
            private String left = null;
            private boolean given = false;
            private synchronized void look() { if (!given && when.getAsBoolean()) { left = line; given = true; } }
            @Override public synchronized int read(char[] b, int off, int len) {
                look();
                if (left == null || left.isEmpty()) return -1;
                int n = Math.min(len, left.length());
                left.getChars(0, n, b, off);
                left = left.substring(n);
                return n;
            }
            @Override public synchronized boolean ready() { look(); return left != null && !left.isEmpty(); }
            @Override public void close() { }
        });
    }

    /** What stands between the first question's heading and the end of its wait: the question block as the terminal showed it. */
    static String firstBlock(String out, String heading, String end) {
        int at = out.indexOf(heading), to = out.indexOf(end, Math.max(at, 0));
        return at < 0 || to < 0 ? "" : out.substring(at, to);
    }

    // (a) the second and third files are read while the first file's question waits; nobody answers; it is asked once more at the end
    @Test
    void theNextFilesAreReadWhileAQuestionWaitsAndItIsAskedOnceMoreAtTheEnd(@TempDir Path tmp) throws Exception {
        int secs = 4;
        seconds(tmp, secs);
        LibraryStore store = store(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Supplier<String> shown = () -> out.toString(StandardCharsets.UTF_8);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        long[] modelMs = {0};
        GenealogyProfile.useReader(prompt -> {
            if (prompt.endsWith("Answer with one word: yes or no.")) return "no";
            if (prompt.contains("Mary Hale")) {
                long t = System.nanoTime();
                // the second file is read once the first file's question is on the screen
                assertTrue(until(() -> shown.get().contains("Your answer:")), "the question is put while the reading goes on: " + shown.get());
                events.add("2-hale read while the question waits: " + !shown.get().contains("No answer came within"));
                modelMs[0] += (System.nanoTime() - t) / 1_000_000;
                return NOTHING;
            }
            if (prompt.contains("Tom Hart")) {
                events.add("3-hart read while the question waits: " + !shown.get().contains("No answer came within"));
                // a long file: its reading goes on past the question's wait
                long t = System.nanoTime();
                try { Thread.sleep((secs + 1) * 1000L); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                modelMs[0] += (System.nanoTime() - t) / 1_000_000;
                events.add("3-hart done after the wait ran out: " + shown.get().contains("No answer came within"));
                return NOTHING;
            }
            return adoptedRead();
        });
        Interaction.OVERRIDE = true;
        Interaction.INPUT = silent();
        long start = System.nanoTime();
        String said = readFolder(store, folder(tmp, ADOPTED, HALE, HART), out);
        long took = (System.nanoTime() - start) / 1_000_000;
        assertEquals(List.of("2-hale read while the question waits: true", "3-hart read while the question waits: true", "3-hart done after the wait ran out: true"), events, said);
        assertEquals(2, count(said, "HOW DID THE NAME CHANGE?"), "put while reading, and once more at the end: " + said);
        assertTrue(said.contains("The reading goes on while this question waits."), said);
        assertTrue(said.contains("No answer came within 4 seconds. The question stays open, and the library asks it once more when the reading is finished. The reading goes on."), said);
        int again = said.indexOf("The reading is finished. The library asks once more the questions that got no answer while it was reading.");
        assertTrue(again > said.indexOf("3 of 3: 3-hart.txt") && said.indexOf("3 of 3: 3-hart.txt") > 0, "asked once more after the last file: " + said);
        assertTrue(said.indexOf("HOW DID THE NAME CHANGE?", again) > again, said);
        assertTrue(said.contains("No answer came within 4 seconds. The question waits for you: researchzosho genealogy who"), "the second time it is not asked again: " + said);
        // the terminal stays readable: the reading's own lines wait while the question is on the screen, and come after it
        String block = firstBlock(said, "HOW DID THE NAME CHANGE?", "No answer came within");
        assertFalse(block.isEmpty() || block.contains("2 of 3") || block.contains("3 of 3") || block.contains("reading part"), "nothing of the reading inside the question: " + block);
        assertTrue(said.indexOf("3 of 3: 3-hart.txt") > said.indexOf("No answer came within"), "the reading's lines, held while the question was on the screen, come after it: " + said);
        assertTrue(said.contains("One question about names and families that this read raised waits for you. To answer it: researchzosho genealogy who."), "the summary: " + said);
        assertTrue(said.indexOf("WHAT HAPPENS NEXT") > said.lastIndexOf("Your answer:"), said);
        assertEquals(1, FamilyNameQuestions.open(store).size());
        // two waits and the reading of the long files overlap: the read takes less than the waits (each with its pause after the time
        // runs out, so a late answer is not taken for the next question) and the reading one after the other
        long pauses = 2L * Math.min(3, secs) * 1000;
        assertTrue(took < 2L * secs * 1000 + pauses + modelMs[0], "took " + took + " ms; two waits of " + secs + " s, " + pauses + " ms of pauses and " + modelMs[0] + " ms of reading");
    }

    // (b) an answer typed while the reading goes on is filed at once, and the question is gone
    @Test
    void anAnswerTypedDuringTheReadIsFiledAtOnceAndTheQuestionIsGone(@TempDir Path tmp) throws Exception {
        seconds(tmp, 5);
        LibraryStore store = store(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Supplier<String> shown = () -> out.toString(StandardCharsets.UTF_8);
        BooleanSupplier filed = () -> {
            try { return Files.exists(FamilyNameQuestions.askedFile(store)) && Files.readString(FamilyNameQuestions.askedFile(store)).contains("\tanswered\t"); }
            catch (Exception e) { return false; }
        };
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        GenealogyProfile.useReader(prompt -> {
            if (prompt.endsWith("Answer with one word: yes or no.")) return "no";
            if (prompt.contains("Mary Hale")) {
                // the second file is being read when the answer is filed
                events.add("answer filed while 2-hale is read: " + until(filed));
                return NOTHING;
            }
            if (prompt.contains("Tom Hart")) return NOTHING;
            return adoptedRead();
        });
        Interaction.OVERRIDE = true;
        Interaction.INPUT = typesWhen(() -> shown.get().contains("Your answer:"), "1\n");
        String said = readFolder(store, folder(tmp, ADOPTED, HALE, HART), out);
        assertEquals(List.of("answer filed while 2-hale is read: true"), events, said);
        assertEquals(1, count(said, "HOW DID THE NAME CHANGE?"), "asked once: " + said);
        assertTrue(said.contains("Saved as your answer:"), said);
        assertFalse(said.contains("asks once more"), said);
        List<String> asked = Files.readAllLines(FamilyNameQuestions.askedFile(store));
        assertEquals(1, asked.size());
        assertEquals("answered", asked.get(0).split("\t")[1]);
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-how")), "the question is gone: " + FamilyNameQuestions.open(store));
        assertFalse(said.contains("that this read raised wait"), "nothing waits: " + said);
    }

    static final String ENDO = "Endo's son, 森田健二, was born in 1905. " + ADOPTED;
    static final String MARRIED = "In 1932 森田健二 married 森田ハル, the daughter of 森田勇, and entered the 森田 family (森田家) as 婿養子. The wedding was in the spring.";

    /** The first file raises two questions: who the "Endo" is (put first) and how 森田健二 came into the 森田 family (put next). */
    static String endoRead() {
        return "{\"people\": [{\"name\": \"Endo\", \"family\": \"Endo\"}, {\"name\": \"森田健二\", \"family\": \"森田\", \"given\": \"健二\"}], \"facts\": ["
                + "{\"subject\": \"森田健二\", \"relation\": \"child-of\", \"object\": \"Endo\", \"date\": \"\", \"quote\": \"Endo's son, 森田健二, was born in 1905.\", \"only_family_name\": true},"
                + "{\"subject\": \"森田健二\", \"relation\": \"adopted-by\", \"object\": \"森田勇\", \"kind\": \"mukoyoshi\", \"date\": \"1932\", \"quote\": \"" + ADOPTED + "\"}]}";
    }

    /** The second file says he married 森田ハル and entered the 森田 family: that settles how he came into it. */
    static String marriedRead() {
        String q = MARRIED.substring(0, MARRIED.indexOf(" The wedding"));
        return "{\"people\": [], \"facts\": [{\"subject\": \"森田健二\", \"relation\": \"member-of\", \"object\": \"the 森田 family (森田家)\", \"how\": \"mukoyoshi\", \"date\": \"1932\", \"quote\": \"" + q + "\"},"
                + "{\"subject\": \"森田健二\", \"relation\": \"married-to\", \"object\": \"森田ハル\", \"date\": \"1932\", \"quote\": \"" + q + "\"},"
                + "{\"subject\": \"森田ハル\", \"relation\": \"child-of\", \"object\": \"森田勇\", \"date\": \"\", \"quote\": \"" + q + "\"}]}";
    }

    // (c) a question the first file raised that a later file settles before its turn is not put
    @Test
    void aQuestionALaterFileSettlesIsNotAsked(@TempDir Path tmp) throws Exception {
        // the control: with a second file that settles nothing, both questions are put
        seconds(tmp.resolve("control"), 1);
        LibraryStore control = store(tmp.resolve("control"));
        GenealogyProfile.useReader(prompt -> prompt.endsWith("Answer with one word: yes or no.") ? "no" : prompt.contains("Tom Hart") ? NOTHING : endoRead());
        Interaction.OVERRIDE = true;
        Interaction.INPUT = silent();
        String plain = readFolder(control, folder(tmp.resolve("control"), ENDO, HART, null), new ByteArrayOutputStream());
        assertTrue(plain.indexOf("A PERSON WRITTEN ONLY BY A FAMILY NAME") >= 0 && plain.indexOf("A PERSON WRITTEN ONLY BY A FAMILY NAME") < plain.indexOf("HOW DID THE NAME CHANGE?"), "both are put, who the Endo is first: " + plain);

        seconds(tmp, 3);
        LibraryStore store = store(tmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Supplier<String> shown = () -> out.toString(StandardCharsets.UTF_8);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        GenealogyProfile.useReader(prompt -> {
            if (prompt.endsWith("Answer with one word: yes or no.")) return "no";
            if (prompt.contains("森田ハル")) {
                // the second file is read while the first question is on the screen, and the second question has not been put
                assertTrue(until(() -> shown.get().contains("Your answer:")), shown.get());
                events.add("married read while the first question waits: " + (!shown.get().contains("No answer came within") && !shown.get().contains("HOW DID THE NAME CHANGE?")));
                return marriedRead();
            }
            return endoRead();
        });
        String said = readFolder(store, folder(tmp, ENDO, MARRIED, null), out);
        assertEquals(List.of("married read while the first question waits: true"), events, said);
        assertEquals(1, count(said, "A PERSON WRITTEN ONLY BY A FAMILY NAME"), said);
        assertFalse(said.contains("HOW DID THE NAME CHANGE?"), "settled by the second file before its turn, so it is not put: " + said);
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-how")), FamilyNameQuestions.open(store).toString());
        assertTrue(said.contains("One question about names and families that this read raised waits for you."), "the one about Endo waits: " + said);
    }

    // (d) nobody at the keyboard, and (e) a setting of 0: a folder read asks nothing and waits for nothing
    @Test
    void aFolderReadWhereNobodyCanAnswerOrWithASettingOfZeroAsksNothingAndWaitsForNothing(@TempDir Path tmp) throws Exception {
        GenealogyProfile.useReader(prompt -> prompt.endsWith("Answer with one word: yes or no.") ? "no" : prompt.contains("Mary Hale") || prompt.contains("Tom Hart") ? NOTHING : adoptedRead());
        seconds(tmp, 60);
        LibraryStore store = store(tmp);
        Interaction.OVERRIDE = false;
        long start = System.nanoTime();
        String said = readFolder(store, folder(tmp, ADOPTED, HALE, HART), new ByteArrayOutputStream());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 30_000, "nothing waited");
        assertFalse(said.contains("HOW DID THE NAME CHANGE?") || said.contains("Your answer:"), said);
        assertTrue(said.contains("3 of 3: 3-hart.txt") && said.contains("WHAT HAPPENS NEXT"), said);
        assertEquals(1, FamilyNameQuestions.open(store).size(), "the question is simply open");

        Path zero = tmp.resolve("zero");
        seconds(zero, 0);
        LibraryStore other = store(zero);
        Interaction.OVERRIDE = true;
        Interaction.INPUT = new BufferedReader(new StringReader("1\n"));
        said = readFolder(other, folder(zero, ADOPTED, HALE, HART), new ByteArrayOutputStream());
        assertFalse(said.contains("HOW DID THE NAME CHANGE?") || said.contains("Your answer:"), said);
        assertEquals(1, FamilyNameQuestions.open(other).size());
        assertFalse(Files.exists(FamilyNameQuestions.askedFile(other)), "nothing was answered");
    }
}
