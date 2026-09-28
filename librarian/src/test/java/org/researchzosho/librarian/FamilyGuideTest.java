package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the family guide says a command or a page shows, held against what it shows. */
class FamilyGuideTest {

    private static String doc(String name) throws Exception {
        for (String c : new String[]{"../docs/public/" + name, "docs/public/" + name, "../docs/" + name, "docs/" + name, "../" + name, name}) if (Files.exists(Path.of(c))) return Files.readString(Path.of(c), StandardCharsets.UTF_8);
        if (name.startsWith("RELEASE_NOTES_")) return null;   // the release notes stay in the private tree; the public export has only the changelog
        fail(name + " not found from " + Path.of("").toAbsolutePath());
        return "";
    }

    @Test
    void theGuidesAndTheUsagePromiseNobodyIsHiddenOneByOne() throws Exception {
        String family = doc("FAMILY_HISTORY.md"), librarian = doc("LIBRARIAN_HOWTOUSE.md"), usage = LibrarianCli.USAGE + "\n" + new GenealogyProfile().usage();
        for (String gone : List.of("--private", "--include-children", "--include-living", "kept private", "RESN privacy", "under eighteen"))
            for (var text : List.of(List.of("FAMILY_HISTORY.md", family), List.of("LIBRARIAN_HOWTOUSE.md", librarian), List.of("the usage", usage)))
                assertFalse(text.get(1).contains(gone), text.get(0) + " still says " + gone);
        assertTrue(family.contains("### 3.7 Who may be living") && family.contains("`researchzosho reader` decides who may read"), "the guide says who sees the library");
        assertTrue(family.contains("`--skip-living` chooses whom the"), "and that --skip-living chooses whom the research searches for");
    }

    @Test
    void nothingPromisesToNameTheFactThatDecidedWhoIsLiving() throws Exception {
        // nothing the library prints names that fact any more, so the guide and the notes do not send the family to look for it
        for (String name : List.of("FAMILY_HISTORY.md", "CHANGELOG.md", "RELEASE_NOTES_0.5.0.md")) {
            String raw = doc(name);
            if (raw == null) continue;
            String text = raw.replaceAll("\\s+", " ");
            assertFalse(text.contains("the fact that decided"), name);
            assertTrue(text.contains("counts at once"), name + " still says a disputed fact stops counting at once");
        }
    }

    @Test
    void theCodeAndItsTestsDescribeNobodyHiddenOneByOne() throws Exception {
        // the comments and the test descriptions a maintainer reads say what the program does now: who may read sees everything
        List<String> gone = List.of("private person", "persons private", "hidden from anyone", "kept somebody in it private", "everybody in the file is marked",
                "a reader is shown a claim", "a reader is not shown", "see the living named", "living: private", "still private");
        Path src = Files.exists(Path.of("src")) ? Path.of("src") : Path.of("librarian/src");
        List<String> said = new ArrayList<>();
        try (var files = Files.walk(src)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java") && !x.getFileName().toString().equals("FamilyGuideTest.java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String g : gone) if (text.contains(g)) said.add(f.getFileName() + " says \"" + g + "\"");
            }
        }
        assertEquals(List.of(), said);
    }

    @Test
    void theNotesSayOnceThatAJobAsksForReadAccess() throws Exception {
        for (String name : List.of("CHANGELOG.md", "RELEASE_NOTES_0.5.0.md")) {
            String text = doc(name);
            if (text == null) continue;
            assertEquals(1, text.split("asks for read access, as `library_job` does", -1).length - 1, name);
        }
    }

    @Test
    void laterSaysThePersonIsNotAskedAgainByItselfAndHowToAnswer(@TempDir Path tmp) throws Exception {
        assertFalse(FamilyWho.HOW.contains("will ask you again next time"), FamilyWho.HOW);
        assertTrue(FamilyWho.HOW.contains("not asked about again by itself") && FamilyWho.HOW.contains("researchzosho genealogy who"), FamilyWho.HOW);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "died-on", "1920", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        FamilyIdentity.find(store, Graph.build(store), "Tom Hale", q -> List.of(new FamilyIdentity.Page("https://example.org/a", "Tom Hale", "a teacher"), new FamilyIdentity.Page("https://example.org/b", "Tom Hale", "a singer")), null);
        String page = WhoPage.body(store, Patrons.Patron.PERSON, "Tom Hale", "");
        assertFalse(page.contains("ask me later"), page);
        assertTrue(page.contains("is not asked about again by itself"), page);
        assertTrue(page.contains("is given these pages as pages about other people"), "none says what the research is told: " + page);
    }

}
