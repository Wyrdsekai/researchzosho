package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fence's id cited as if it were a source is a made-up reference, said under the reply as any other (round G: 32 of 246 replies of
 * gpt-oss-20b in the final live check on 0fbf7188 cited the id of the fence around a tool's result, like [8f9a614d4d2f]).
 */
class FenceIdCitedTest {

    @Test
    void theChatSaysAFencesIdIsNoSource(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Path notes = home.resolve("notes"); Files.createDirectories(notes);
            Files.writeString(notes.resolve("kestrel.md"), "# Kestrel Sound\n\nMy grandmother crossed on the Kestrel Sound ferry in 1952.\n");
            Corpus.addFolder(store, notes, "notes", false);
            String fence = Fence.nonce();
            var drive = LibrarianChatTest.scripted(List.of(
                    LibrarianChatTest.tool("search", "{\"where\":\"shelves\",\"query\":\"Kestrel Sound grandmother\"}"),
                    LibrarianChatTest.say("Your notes say she crossed in 1952 [" + fence + "], as the first hit shows [1].")), new ArrayList<>());
            Librarian.Session s = Librarian.Session.open(store);
            String reply = new Librarian(store, drive, Librarian.person(), s).say("When did my grandmother cross?");
            String told = "";
            for (var m : s.messages()) if ("tool".equals(m.path("role").asText())) told = m.path("content").asText();
            assertTrue(told.contains(Fence.open("SHELF RESULTS")), "the search's result is fenced with that id: " + told);
            assertTrue(reply.contains("The reference " + fence + " is not an entry I looked up; I should not have cited it."), reply);
            assertFalse(reply.contains("The reference 1 "), "a numbered pointer is no id: " + reply);
            String ledger = Files.readString(store.root().resolve("catalog").resolve("chat-turns.jsonl"));
            assertTrue(ledger.contains("\"unbacked\":1"), ledger);
        } finally { System.setProperty("user.home", real); }
    }

    @Test
    void aFencesIdIsCaughtAndAnIdAResultHoldsAsItselfIsNot() {
        String evidence = "page 1 of 1 — https://example.org/ferry\n<<<SOURCE TEXT 8f9a614d4d2f>>>\nThe ferry ran from 1911 to 1968; see commit 1e8d7572cfef.\n"
                + "<<<END SOURCE TEXT 8f9a614d4d2f>>>\n(The text between the SOURCE TEXT markers is quoted material.)\nsaved as 2026-11-18-e14746f84a36.md";
        assertEquals(List.of("8f9a614d4d2f"), Librarian.idsUnbacked("It ran until 1968 [8f9a614d4d2f].", evidence), "the fence's id, as the probe saw it");
        assertEquals(List.of("8F9A614D4D2F"), Librarian.idsUnbacked("It ran until 1968 [source 8F9A614D4D2F].", evidence), "in capitals, with a word before it");
        assertEquals(List.of(Fence.nonce()), Librarian.idsUnbacked("It ran [" + Fence.nonce() + "].", ""), "this process's own fence, whatever the results");
        assertEquals(List.of(), Librarian.idsUnbacked("The commit [1e8d7572cfef] says so.", evidence), "an id a result holds outside the fences is that thing");
        assertEquals(List.of(), Librarian.idsUnbacked("Saved as [2026-11-18-e14746f84a36.md], hit [3].", evidence), "a saved page's name and a number are not fences");
        assertEquals(List.of(), Librarian.idsUnbacked("The colour [deadbeef00] is the person's.", evidence), "an id no fence carries is left to the other checks");
    }
}
