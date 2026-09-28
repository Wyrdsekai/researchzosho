package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An old claim that says "nothing was found" is moved to the dated ledger of places somebody has looked, and retired. */
class LookedTest {

    @Test
    void anOldNothingFoundClaimBecomesADatedLineAndIsRetired(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0007-absence-of-patents", "Absence of Patents", List.of(), Finding.State.draft, Finding.ClaimType.synthesis, Finding.Confidence.high,
                "patron:person", "2026-09-20T09:30:06Z", "2026-09-20", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://patents.example/search?q=endo", "n/a", "cited by I-0011-endo")), List.of(), null,
                "No patents by Endo Genzaburo were found.\n", new Finding.Triple("Endo Genzaburo (1872-1945)", "holds patents", "none found"), List.of()));
        assertNull(Looked.move(store, "F-9999-nothing"));
        Looked.Entry e = Looked.move(store, "F-0007-absence-of-patents");
        assertEquals("2026-09-20", e.date(), "the date of the search, not of the move");
        assertEquals("Endo Genzaburo (1872-1945)", e.about());
        assertEquals(List.of("patents.example"), e.where());
        assertEquals("I-0011-endo", e.report());
        assertEquals(Finding.State.retired, store.finding("F-0007-absence-of-patents").state());
        assertEquals(1, Looked.about(store, "Endo Genzaburo: what patents are there, now that the patent office can be searched?", 10).size());
        assertTrue(Looked.line(e).startsWith("on 2026-09-20 a search found nothing about Endo Genzaburo (1872-1945): No patents"), Looked.line(e));
        Looked.move(store, "F-0007-absence-of-patents");
        assertEquals(1, Looked.all(store).size(), "moved twice, written once");
    }

    @Test
    void aPersonWritesDownTheirOwnSearchThatFoundNothing(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Tom Hale", "", List.of())),
                List.of(new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        try {
            assertEquals(2, LibrarianCli.run(new String[]{"librarian", "looked", "add", "tom", "hale", "--where", "familysearch"}, "http://127.0.0.1:1", "m"), "what was searched for is part of the line");
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "looked", "add", "tom", "hale", "--where", "familysearch", "--what", "Tom", "Hale", "born", "1850"}, "http://127.0.0.1:1", "m"));
        } finally { System.setProperty("user.home", real); }
        List<Looked.Entry> all = Looked.all(store);
        assertEquals(1, all.size());
        assertEquals("Tom Hale", all.get(0).about(), "the name as the library writes it");
        assertEquals(List.of("FamilySearch"), all.get(0).where(), "the site's id becomes its name");
        assertTrue(Looked.block(store, "Tom Hale (born 1850): who were the parents?", 12).contains("searched by hand for Tom Hale born 1850. Looked in: FamilySearch."), Looked.block(store, "Tom Hale (born 1850): who were the parents?", 12));
    }
}
