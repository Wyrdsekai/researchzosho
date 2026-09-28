package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A book's index writes "Family, Given (relation)" ({@link FamilyLinks}): the relation word in brackets, "(father)", "(Ivy; wife)", is no part
 * of the name and tells nobody apart, so the name matches as usual (L1), a Western name before the semicolon stays a name of the person,
 * and the relation word is a fact about the book's narrator that can agree with another source. A note that tells namesakes apart,
 * "(born 1880)", still keeps the name to itself.
 */
class FamilyLinksIndexTest {

    private static final String NOTES = "file:///family/notes.txt";
    private static final String REGISTER = "file:///family/register.txt";
    private static final String BOOK = "file:///family/The Morita Shop.epub";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static void claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        store.write(new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of()));
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    @Test
    void theNoteInBracketsIsReadForWhatItIs() {
        assertEquals(List.of("relation", ""), List.of(FamilyLinks.note("father")));
        assertEquals(List.of("relation", "Ivy"), List.of(FamilyLinks.note("Ivy; wife")));
        assertEquals(List.of("relation", ""), List.of(FamilyLinks.note("elder brother")));
        assertEquals(List.of("namesake", ""), List.of(FamilyLinks.note("daughter; later Mrs Hale")));
        assertEquals(List.of("name", "Hisa"), List.of(FamilyLinks.note("Hisa")));
        assertEquals(List.of("namesake", ""), List.of(FamilyLinks.note("born 1880")));
        assertEquals(List.of("namesake", ""), List.of(FamilyLinks.note("the shopkeeper")));
        assertEquals(List.of("Morita, Isamu"), FamilyLinks.forms("Morita, Isamu (father)"));
        assertEquals(List.of("Morita, Sumiko", "Morita, Ivy"), FamilyLinks.forms("Morita, Sumiko (Ivy; wife)"));
        assertEquals(List.of("Morita, Isamu (born 1880)"), FamilyLinks.forms("Morita, Isamu (born 1880)"));
        assertEquals(FamilyLinks.key("Isamu Morita"), FamilyLinks.key("Morita, Isamu (father)"));
        assertEquals("", FamilyLinks.key("Morita, Isamu (born 1880)"));
        assertEquals(List.of("wife"), FamilyLinks.relationNotes("Morita, Sumiko (Ivy; wife)"));
        assertEquals("Morita, Sumiko (Ivy)", FamilyLinks.unnoted("Morita, Sumiko (Ivy; wife)"));
    }

    @Test
    void anIndexEntryWithARelationWordJoinsTheNamedPersonAndTheWordAgreesWithTheRegister(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "life-event", "wrote the book - The Morita Shop", NOTES);
        claim(store, "Isamu Morita", "born-on", "1905", REGISTER);
        claim(store, "Isamu Morita", "parent-of", "Kenji Morita", REGISTER);
        claim(store, "Sumiko Morita", "married-to", "Kenji Morita", REGISTER);
        claim(store, "Morita, Isamu (father)", "occupation", "shopkeeper", BOOK);
        claim(store, "Morita, Sumiko (Ivy; wife)", "occupation", "bookkeeper", BOOK);
        claim(store, "Morita, Isamu (born 1880)", "occupation", "farmer", BOOK);
        FamilyLinks.update(store);
        assertEquals(entry(store, "Isamu Morita"), entry(store, "Morita, Isamu (father)"), "the same name, and the index's \"father\" is the register's parent of the narrator: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
        FamilyLinks.Link l = FamilyLinks.current(store).links().stream().filter(x -> x.written().equals("Morita, Isamu (father)")).findFirst().orElseThrow();
        assertEquals(FamilyLinks.Grade.probable, l.grade(), l.why());
        assertTrue(l.why().contains("father of Kenji Morita, as the book's index says"), l.why());
        assertEquals(entry(store, "Sumiko Morita"), entry(store, "Morita, Sumiko (Ivy; wife)"), "and his wife, whose Western name the index keeps");
        assertNotEquals(entry(store, "Isamu Morita"), entry(store, "Morita, Isamu (born 1880)"), "a note that tells namesakes apart keeps the name to itself");
    }
}
