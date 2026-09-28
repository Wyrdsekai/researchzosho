package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A link note that changed replaces the old one: the claims the old note gave are superseded, with the reason, and a note that says the same keeps them. */
class FamilyLinkNoteChangedTest {

    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    @Test
    void theOldNotesClaimsAreSupersededWhenTheNoteChanges(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String locator = "told://link-note/https://example.org/tom-hale";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("Tom Hale", "parent-of", "kimie hale", "About Tom Hale: kimie hale's father"), said("Tom Hale", "sex", "male", "About Tom Hale: kimie hale's father")), List.of()), locator, "the owner of this library");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(said("Tom Hale", "occupation", "printer", "Tom Hale was a printer")), List.of()), "https://example.org/tom-hale", "a page");
        assertEquals(0, FamilyAccount.noteChanged(store, locator, "kimie hale's father"), "the same note again supersedes nothing");
        assertEquals(0, FamilyAccount.noteChanged(store, locator, "kimie hale's father, born 1920"), "a note that still says it supersedes nothing");
        assertEquals(2, FamilyAccount.noteChanged(store, locator, "kimie hale's brother"));
        List<Finding> all = store.scanFindings().findings();
        List<Finding> gone = all.stream().filter(f -> f.state() == Finding.State.superseded).toList();
        assertEquals(2, gone.size());
        assertTrue(gone.stream().allMatch(f -> f.notes().stream().anyMatch(n -> n.text().contains("the note beside this link changed: it now says \"kimie hale's brother\""))), gone.toString());
        assertTrue(all.stream().filter(f -> f.triple() != null && f.triple().predicate().equals("occupation")).allMatch(f -> f.state() == Finding.State.draft), "the page's own claims stay");
        Graph g = FamilyPeople.view(store);
        assertTrue(g.edges().stream().noneMatch(e -> e.predicate().equals("parent-of")), "a superseded claim is no edge");
    }
}
