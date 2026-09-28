package org.researchzosho.librarian.profiles;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.FamilyAccount;
import org.researchzosho.librarian.FamilyIdentity;
import org.researchzosho.librarian.Gedcom;
import org.researchzosho.librarian.Graph;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.drive.Declined;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Telling the library something about a relative in the middle of the who-is-who sitting files it and hands the keyboard back to the
 * sitting: no read summary and no question about starting the search, which would take the next answer of the sitting.
 */
class WhoSittingTellTest {

    @AfterEach void restore() { GenealogyProfile.useReader(null); }

    @Test
    void whatIsToldInASittingIsFiledQuietly(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        GenealogyProfile.useReader(prompt -> "{\"people\": [], \"facts\": [{\"subject\": \"Mari Endo\", \"relation\": \"occupation\", \"object\": \"teacher\", \"date\": \"\", \"quote\": \"She taught at a school in Sendai.\"}]}");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().tellAbout(store, "Mari Endo", "She taught at a school in Sendai."); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertFalse(said.contains("WHAT HAPPENS NEXT") || said.contains("start the search"), said);
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.writer().equals("family-account") && f.title().contains("teacher")), "the told fact is filed");
    }

    @Test
    void whatIsToldWhileTheModelIsAwayIsKeptAndNotAskedForAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1920\n2 PLAC York\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        assertNotNull(FamilyIdentity.find(store, Graph.build(store), "Tom Hale", q -> List.of(), null), "the question the sitting asks about him");
        GenealogyProfile.useReader(prompt -> { throw new FamilyAccount.Unanswered("http://127.0.0.1:9", new ConnectException("Connection refused")); });
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().tellAbout(store, "Tom Hale", "He taught at a school in Leeds."); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertFalse(said.contains("give the same command again"), "telling it again would keep it twice: " + said);
        assertTrue(said.contains("researchzosho genealogy research"), "the next research files it: " + said);
        assertEquals(List.of("He taught at a school in Leeds."), FamilyIdentity.toldNotFiled(FamilyIdentity.read(store, "Tom Hale")), "the words wait to be filed");
    }

    @Test
    void theFactsOfANamesakeWhoseNameHasABracketAreShownWhole(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1920\n2 PLAC York\n1 DEAT\n2 DATE 1990\n0 @I2@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1985\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        assertEquals("born 1920 in York; died 1990", GenealogyProfile.knownOf(store, "Tom Hale (born 1920)"));
    }

    @Test
    void aDeclineIsSaidAsTheStatementNotAsTheExceptionsMessage(@TempDir Path tmp) throws Exception {
        // L9: the genealogy commands printed "could not be read: the model m declined …: "…" (words)", the exception's own message
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        GenealogyProfile.useReader(prompt -> { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); });
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().tellAbout(store, "Mari Endo", "She taught at a school in Sendai."); } finally { System.setOut(was); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("The model this library uses (placeholder-model) declined") && said.contains("ResearchZosho did not try to get around it."), said);
        assertFalse(said.contains("(words)") || said.contains("could not file that"), said);
    }
}
