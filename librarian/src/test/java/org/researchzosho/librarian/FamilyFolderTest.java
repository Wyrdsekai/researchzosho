package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A folder of family material is read in the order that helps: notes, a tree file, links, pictures, then the books kept to the family's names. */
class FamilyFolderTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    @Test
    void theOrderTheNamesAndEachFileOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path dir = Files.createDirectories(tmp.resolve("sources"));
        Files.write(dir.resolve("town-history.epub"), new byte[5000]);
        Files.writeString(dir.resolve("links.txt"), "https://example.org/a  # my grandfather\nhttps://example.org/b\n");
        Files.writeString(dir.resolve("notes.txt"), "My grandfather 森田勇 was born in 津.");
        Files.writeString(dir.resolve("aunt.ged"), "0 HEAD\n0 TRLR\n");
        Files.write(dir.resolve("register.jpg"), new byte[100]);
        Files.writeString(dir.resolve(".hidden.txt"), "x"); Files.writeString(dir.resolve("sheet.xlsx"), "x");
        List<FamilyFolder.Item> plan = FamilyFolder.plan(store, dir);
        assertEquals(List.of("notes.txt", "aunt.ged", "links.txt", "register.jpg", "town-history.epub"), plan.stream().map(i -> i.file().getFileName().toString()).toList());
        FamilyFolder.markRead(store, dir.resolve("notes.txt"));
        assertTrue(FamilyFolder.plan(store, dir).get(0).readBefore());
        Files.writeString(dir.resolve("notes.txt"), "My grandfather 森田勇 was born in 津. His wife was ふさ.");
        assertFalse(FamilyFolder.plan(store, dir).get(0).readBefore(), "notes that were added to are read again");

        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎"), fact("森田勇", "parent-of", "森田一美"), fact("森田勇", "married-to", "森田ふさ"),
                fact("Ken Morita", "child-of", "森田一郎"), fact("Ann Morita", "married-to", "Ken Morita"), fact("林正", "child-of", "森田一美"), fact("Tom Hart", "child-of", "Ann Morita")), List.of()), "file:///n.txt", "an aunt");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Hisa Endō", "parent-of", "Robert Endō"), fact("Endo Taro", "child-of", "Hisa Endō"), fact("森一", "child-of", "森田勇"), fact("中山正", "married-to", "中川花"),
                fact("The Bishop", "parent-of", "The Doctor"), fact("Doctor Reed", "married-to", "Doctor Lane"), fact("源次", "child-of", "源美"), fact("源子", "child-of", "源美")), List.of()), "file:///n2.txt", "an aunt");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Professor Hale", "born-in", "Leeds"), fact("Professor Reed", "born-in", "York")), List.of()), "file:///n3.txt", "a book");
        List<String> names = FamilyFolder.familyNames(store);
        assertTrue(names.contains("Endo") && names.contains("森田") && names.contains("Morita"), "a name with a line over a letter is found in a book that writes it without, whichever way round the name is written: " + names);
        assertTrue(names.stream().noneMatch(n -> n.length() == 1) && !names.contains("森田一") && !names.contains("Hisa") && !names.contains("The") && !names.contains("Doctor") && !names.contains("Professor"), "one character shared by strangers (中山, 中川) is not a family, nor is a first name: " + names);
        assertEquals(List.of("森田", "Morita"), names.stream().filter(n -> n.contains("orita") || n.contains("森田")).toList(), "a name part two people share; 森田一 is shared by two, 森田 by more; one Hart and one 林 are not a family here");
    }

    @Test
    void aThreeCharacterFamilyNameIsNotCutToTwoAndTheNotesSayWhoWroteABook(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("中御門経之", "parent-of", "中御門経明"), fact("中御門経明", "married-to", "中御門はな"),
                fact("森田勇", "parent-of", "森田一郎")), List.of()), "file:///n.txt", "an aunt");
        List<String> names = FamilyFolder.familyNames(store);
        assertTrue(names.contains("中御門") && !names.contains("中御"), "everybody who begins 中御 also shares the third character: " + names);
        assertTrue(names.contains("森田"), names.toString());

        Path dir = tmp.resolve("family"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("notes.txt"), "My grandfather 森田勇 was born in 津.\nKimie Hale also wrote community.pdf\nvillage.epub was written by Tom Hart.\n");
        Files.writeString(dir.resolve("community.pdf"), "x"); Files.writeString(dir.resolve("village.epub"), "x"); Files.writeString(dir.resolve("other.pdf"), "x");
        Map<String, String> writers = FamilyFolder.writers(FamilyFolder.plan(store, dir));
        assertEquals("Kimie Hale", writers.get("community.pdf"));
        assertEquals("Tom Hart", writers.get("village.epub"));
        assertNull(writers.get("other.pdf"));
    }

    @Test
    void aFileIsLeftOutBySkipOrChosenByOnlyByAWordOfItsNameOrItsExtension() {
        Path a = Path.of("/f/Village Farmer.epub"), b = Path.of("/f/notes.txt"), c = Path.of("/f/community.PDF");
        assertTrue(GenealogyProfile.matches(a, List.of("epub")) && GenealogyProfile.matches(a, List.of(".EPUB")) && GenealogyProfile.matches(a, List.of("farmer")));
        assertFalse(GenealogyProfile.matches(b, List.of("epub", "pdf")));
        assertTrue(GenealogyProfile.matches(c, List.of("pdf")));
    }
}
