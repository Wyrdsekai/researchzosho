package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The names offered when a typed name is not found: a relative the family knows by one given name alone is offered; an entry that is a
 * family name alone (a word that is the family part of a name here, which a source writes as a family) is not, since it is nobody in
 * particular. The command says what that entry is, and that the questions about names ask who it is.
 */
class FamilySimilarNamesTest {

    @Test
    void aRelativeKnownByOneGivenNameIsOfferedAndAFamilyNameAloneIsNotButSaid(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // Ruth: the aunt the letters call by her given name only, beside a Ruth Ellis; Hart: a family name an older read filed as a person
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Ruth", "lived-in", "Leeds", "1920", "Aunt Ruth lived in Leeds in 1920"),
                new FamilyAccount.Fact("Ken Hart", "born-in", "Leeds", "1930", "Ken Hart was born in Leeds in 1930"),
                new FamilyAccount.Fact("Hart", "lived-in", "York", "1900", "the Harts lived in York in 1900")), List.of()),
                "file:///family/letters.txt", "an aunt");
        // Ruth Ellis from another text: the letters' Ruth is not settled by it (a text that named her in full would link the two)
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("Ruth Ellis", "born-in", "York", "1890", "Ruth Ellis was born in York in 1890")), List.of()),
                "file:///family/register.txt", "an aunt");
        FamilyQuestions.Found ruth = FamilyQuestions.find(store, "Ruth Morita");
        assertFalse(ruth.found());
        assertTrue(ruth.could().contains("Ruth"), "a relative known by one given name alone is offered, beside Ruth Ellis too: " + ruth.could());
        assertEquals("", ruth.note());
        FamilyQuestions.Found mary = FamilyQuestions.find(store, "Mary Hart");
        assertFalse(mary.found());
        assertFalse(mary.could().contains("Hart"), "a family name alone is never offered as a similar person: " + mary.could());
        assertTrue(mary.could().contains("Ken Hart"), "a person of a whole name is: " + mary.could());
        assertEquals("\"Hart\" is written in your library as a person with no given name. Hart is a family name there, and the questions about names ask who that is: researchzosho genealogy who", mary.note(),
                "never dropped in silence");
        // typed as it is, the entry is found, and the command says the same
        FamilyQuestions.Found hart = FamilyQuestions.find(store, "Hart");
        assertEquals("Hart", hart.person());
        assertTrue(hart.note().contains("a person with no given name"), hart.note());
        // and the question that asks it waits
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(q -> q.kind().equals("family-name-alone") && q.text().startsWith("“Hart” is written in your library as a person with no given name")),
                FamilyNameQuestions.open(store).toString());
    }

    @Test
    void aTypedNameThatIsAnotherNameOverALifeSaysWhichNameItIs(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田健二", "married-to", "森田ハル", "1932", "健二はハルと1932年に結婚した。")), List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "1905", "健二は1905年に遠藤家に生まれた。"),
                        new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of(), "mukoyoshi", "", "1932", "1932年、森田家に婿養子として入った。")), List.of()), "file:///family/book.txt", "an aunt");
        FamilyQuestions.Found f = FamilyQuestions.find(store, "遠藤健二");
        assertEquals("森田健二", f.person());
        assertEquals("\"遠藤健二\" is the birth name of 森田健二 (born 遠藤) in your library, written \"森田健二\", so that is the person the library takes.", f.note());
        assertEquals("", FamilyQuestions.find(store, "森田健二").note(), "the name as the library writes it needs no word");
    }
}
