package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SubjectsProposedTest {
    @Test
    void proposalsCanBeAcceptedByNumberOrSlugOrDropped(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Cataloger.propose(store, "production--japan-permitting", "securing filming permits in Japan");
        Cataloger.propose(store, "production--logistics", "physical constraints of a shoot");
        Cataloger.propose(store, "performance--language-mentorship", "on-set language coaching");
        Cataloger.propose(store, "production--japan-permitting", "a duplicate that must not be added twice");
        assertEquals(0, LibrarianCli.subjectsProposed(store, new String[]{"researchzosho", "subjects", "proposed"}));
        assertEquals(0, LibrarianCli.subjectsProposed(store, new String[]{"researchzosho", "subjects", "accept", "1", "performance--language-mentorship"}));
        var vocab = Cataloger.vocabulary(store);
        assertTrue(vocab.containsKey("production--japan-permitting") && vocab.containsKey("performance--language-mentorship"), vocab.toString());
        assertFalse(vocab.containsKey("production--logistics"));
        String left = Files.readString(store.subjectsFile().resolveSibling("subjects.proposed.md"), StandardCharsets.UTF_8);
        assertTrue(left.contains("- production--logistics") && !left.contains("japan-permitting") && left.startsWith("# Proposed subjects"), left);
        assertEquals(0, LibrarianCli.subjectsProposed(store, new String[]{"researchzosho", "subjects", "drop", "all"}));
        left = Files.readString(store.subjectsFile().resolveSibling("subjects.proposed.md"), StandardCharsets.UTF_8);
        assertFalse(left.contains("- "), "nothing proposed is left: " + left);
        assertEquals(2, LibrarianCli.subjectsProposed(store, new String[]{"researchzosho", "subjects", "accept"}), "accept with nothing named is a usage error");
    }
}
