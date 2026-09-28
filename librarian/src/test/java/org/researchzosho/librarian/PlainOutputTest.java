package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the program says to the person at the command line: a command it suggests works when it is typed, it takes the short codes it
 * prints, it says what an import did in sentences, and its listings name every command the guide describes.
 */
class PlainOutputTest {

    private static LibraryStore store(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); new LibrarianIndex(s, Embeddings.none()).rebuild(); return s; }

    private static String genealogy(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theShortCodeTheProgramPrintsIsEnoughToDecideAClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Hisa Endo", "died-on", "1920", "", "Hisa Endo died in 1920")), List.of()), "file:///family/a.txt", "an aunt");
        Finding f = store.scanFindings().findings().get(0);
        String code = f.id().replaceFirst("^(F-\\d+).*", "$1");
        assertNotEquals(code, f.id());
        String listed = Evidence.restingForPerson("a.txt", Evidence.restingOn(store, "a.txt", ""), false);
        Matcher m = Pattern.compile("researchzosho dispute (F-\\d+) ").matcher(listed);
        assertTrue(m.find() && m.group(1).equals(code), "the example gives the code of a fact in the list: " + listed);
        Finding disputed = new Council(store).dispute(m.group(1), "the register gives another year");
        assertEquals(f.id(), disputed.id());
        assertEquals(Finding.State.disputed, store.finding(f.id()).state());
        Exception e = assertThrows(Exception.class, () -> new Council(store).accept("F-9999"));
        assertEquals("The library has no claim with the code F-9999. The command researchzosho inbox lists the claims that wait for you, each with its code.", e.getMessage());
    }

    @Test
    void theChecksExamplesAreTheLibrarysOwnCodesAndSources(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("John Ellis", "born-on", "1850", "", "John Ellis was born in 1850"),
                new FamilyAccount.Fact("John Ellis", "died-on", "1840", "", "John Ellis died in 1840")), List.of()), "file:///family/aunt-notes.txt", "an aunt");
        String said = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        Matcher accept = Pattern.compile("researchzosho genealogy check accept (\\w+) ").matcher(said);
        assertTrue(accept.find(), said);
        FamilyChecks.accept(store, accept.group(1), "the dates are right as they stand", "person");   // the code in the example is one the check knows
        assertTrue(said.contains("researchzosho genealogy source aunt-notes.txt"), said);
        assertTrue(genealogy(store, "source", "aunt-notes.txt").contains("rest on aunt-notes.txt"), "and the source in the example has facts resting on it");
    }

    @Test
    void anImportSaysWhatItDidInSentences(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path ged = tmp.resolve("a.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Hisa /Endo/\n1 FAMS @F1@\n1 DEAT\n2 DATE 1920\n0 @I2@ INDI\n1 NAME Taro /Endo/\n1 FAMS @F1@\n1 BIRT\n2 DATE 1990\n"
                + "0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n0 TRLR\n", StandardCharsets.UTF_8);
        String said = genealogy(store, "import", ged.toString());
        assertTrue(said.startsWith("The library read 2 people and 1 family from a.ged, and wrote down "), said);
        assertTrue(said.contains(" facts about them as drafts: facts nobody has checked yet, which wait for you in researchzosho inbox. 1 person in the file may still be living, by the dates: a search asks about their work and public life, not about their death."), said);
        assertFalse(said.contains("private"), said);
        assertFalse(said.contains("(s)") || said.contains("(ies)"), said);
    }

    @Test
    void theListingsNameEveryCommandTheGuideDescribesAndTheChatSaysWhoSharesConversations() {
        String usage = new GenealogyProfile().usage();
        assertTrue(usage.contains("genealogy hold <person> [<number> --until \"<what it waits for>\"] [--release <number>]"), usage);
        assertFalse(usage.contains("nodes + draft findings") || usage.contains("subgraph"), usage);
        assertTrue(LibrarianCli.USAGE.contains("kind <node> <kind> ·"), LibrarianCli.USAGE);
        assertFalse(LibrarianCli.USAGE.contains("--private") || usage.contains("--include-living"), usage);
        assertTrue(Pages.CHAT_SIGN_IN.contains("Someone who may write shares the conversations of the person who keeps the library."), Pages.CHAT_SIGN_IN);
        assertFalse(Pages.CHAT_SIGN_IN.contains("Everyone who signs in has conversations of their own"));
    }
}
