package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who is who on the web, for a person who carried more than one name: the four searches are the latest name and the name at birth first,
 * then the other names, then the other forms of those names. Invented names only.
 */
class FamilyIdentityFormsTest {

    @Test
    void theFourSearchesAreTheLatestNameTheBirthNameThenTheirOtherForms(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyQuestionsPeriodsTest.kenji(tmp);
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田健二", "遠藤健二", "もりた けんじ", "Morita Kenji"), FamilyIdentity.forms(g, g.node(g.nodeIdOf("森田健二"))));
        List<String> searched = new ArrayList<>();
        FamilyIdentity.Question q = FamilyIdentity.find(store, g, "森田健二", query -> { searched.add(query); return List.of(); }, null);
        assertEquals(List.of("\"森田健二\"", "\"遠藤健二\"", "\"もりた けんじ\"", "\"Morita Kenji\""), searched, "the searches the web was asked");
        assertEquals(List.of("森田健二", "遠藤健二", "もりた けんじ", "Morita Kenji"), q.searched(), "and what the question says was searched");
    }

    @Test
    void aWomanWhoMarriedIsSearchedUnderHerMarriedNameThenHerBirthName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyNameHistoryTest.file(store, "file:///family/notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "1875", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "Mary Hale married John Ellis in 1875.")),
                List.of(FamilyNameHistoryTest.name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850."),
                        FamilyNameHistoryTest.name("Mary Ellis", "Ann Ellis", "Ellis", "Ann", "aka", "1880", "From 1880 she wrote as Ann Ellis.")));
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("Mary Ellis", "Mary Hale", "Ann Ellis"), FamilyIdentity.forms(g, g.node(g.nodeIdOf("Mary Ellis"))),
                "a name only in Latin letters is searched in the order the sources write it, and each name once");
    }

    @Test
    void aPersonWithOneNameIsSearchedUnderTheFormsTheSourcesWrote(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田ハル", "", List.of("Haru Morita"))),
                List.of(new FamilyAccount.Fact("森田ハル", "born-on", "1950", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        Graph.Node haru = g.node(g.nodeIdOf("森田ハル"));
        assertEquals(FamilyIdentity.forms(haru), FamilyIdentity.forms(g, haru), "one name: the searches are what they were");
        assertEquals(List.of("森田ハル", "Haru Morita"), FamilyIdentity.forms(g, haru));
    }
}
