package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What goes out to a search service: --only narrows who is asked about without widening what a question names, and a child is a lead
 * like any living relative unless --skip-living is given.
 */
class WhatGoesOutTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", s + " " + r + " " + o); }

    private static String out(Call c) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { c.run(); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }

    private interface Call { void run() throws Exception; }

    @Test
    void onlyNarrowsWhoIsAskedAboutAndNotWhatTheQuestionNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Kenji Endo", "born-on", "1980"), fact("Kenji Endo", "died-on", "2020"),
                fact("Kenji Endo", "married-to", "Hana Endo"), fact("Hana Endo", "born-on", "1982"), fact("Ren Endo", "child-of", "Kenji Endo"), fact("Ren Endo", "born-on", "2016")), List.of()),
                "file:///family/notes.txt", "an aunt");
        List<String> unknown = new ArrayList<>();
        FamilyQuestions.Ask skip = FamilyQuestions.aroundEach(store, List.of("Kenji Endo"), 6, 3, false, true, unknown).get(0);
        assertFalse(skip.question().contains("Hana Endo") || skip.question().contains("Ren Endo") || String.join(" ", skip.questions()).contains("Hana Endo"), "--only --skip-living: " + skip.question());
        FamilyQuestions.Ask withLiving = FamilyQuestions.aroundEach(store, List.of("Kenji Endo"), 6, 3, true, true, unknown).get(0);
        assertTrue(withLiving.question().contains("Hana Endo") && withLiving.question().contains("Ren Endo"), "with the living, the child is a lead like his mother: " + withLiving.question());
        // the owner may name a child with --skip-living: that child is asked about, and nobody else changes
        assertEquals(List.of("Ren Endo"), FamilyQuestions.aroundEach(store, List.of("Ren Endo"), 6, 3, false, true, unknown).stream().map(FamilyQuestions.Ask::person).toList());
        String said = out(() -> new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "research", "Ren Endo", "--only", "--list"}));
        assertTrue(said.contains("You named Ren Endo yourself") && !said.contains("under eighteen") && !said.contains("--include-children"), said);
    }

    @Test
    void aChildIsALeadLikeAnyLivingRelativeAndNoneWithSkipLiving(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Tom Hale", "born-on", "1940", "", "Tom Hale was born in 1940"),
                new FamilyAccount.Fact("Tom Hale", "died-on", "2020", "", "Tom Hale died in 2020"),
                new FamilyAccount.Fact("Tom Hale", "godparent-of", "Kimie Hale", "2015", "Tom Hale was the godfather of Kimie Hale"),
                new FamilyAccount.Fact("Kimie Hale", "born-on", "2015", "", "Kimie Hale was born in 2015")), List.of()), "file:///family/tree.txt", "an aunt");
        String living = question(store, true);
        assertTrue(living.contains("Kimie Hale"), "with the living, the godchild is a lead: " + living);
        String skip = question(store, false);
        assertFalse(skip.contains("Kimie Hale"), "--skip-living names no living person, a child neither: " + skip);
    }

    private static String question(LibraryStore store, boolean living) throws Exception {
        return FamilyQuestions.around(store, "Tom Hale", 2, 2, living).stream().filter(a -> a.person().equals("Tom Hale")).findFirst().orElseThrow().question();
    }
}
