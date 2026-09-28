package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A book's index writes a relation word beside a name: "Hale, Ann (mother), 12, 45". In a text whose writer is known, the person "Hale, Ann"
 * is filed in that relation to the writer, from the index line as its words. An index line with no relation word, a note that is no relation
 * word, a line outside the index, and a text whose writer nobody named file nothing.
 */
class FamilyIndexRelativesTest {

    static final String BOOK = "THE HALE FARM\n\nA Memoir\n\nTom Hale\n\nChapter One\n\nI was born in Leeds in 1901. Hale, Ann (mother) is not an index line here.\n\n"
            + "The farm grew year by year. ".repeat(1600) + "\n\nIndex\n\nEllis, Ken, 33, 40\nHale, Ann (mother), 12, 45, 60 farm work, 12; letters to, 45\nHale, Ruth (Ivy; wife), 82, 84: church window dedicated to, 90;\n"
            + "Hale, Ned\n(Adeline; daughter), 90,91-93,95 Hart, Tom (father-in-law): advice to Tom on farming, 91\nMorita, Isamu (Sendai farmer), 41\nHale , Ken (brother), 7\nEndō, Haru (grandmother)\nHale, Sam (stepfather), 3\n";

    private static FamilyAccount.Read read(String text, String writer) {
        FamilyAccount.Voice voice = writer == null ? null : FamilyAccount.Voice.of(writer, "file:///family/farm.epub");
        return FamilyAccount.read(text, writer == null ? "the writer of farm.epub" : writer, new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : "{\"people\": [], \"facts\": []}",
                List.of(), null, null, voice);
    }

    private static List<String> triples(FamilyAccount.Read r) { return r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).sorted().toList(); }

    @Test
    void anIndexLineWithARelationWordFilesTheWritersRelative() {
        FamilyAccount.Read r = read(BOOK, "Tom Hale");
        assertEquals(List.of("Hale, Sam | step-parent-of | Tom Hale", "Hart, Tom | parent-in-law-of | Tom Hale", "Tom Hale | child-of | Hale, Ann", "Tom Hale | married-to | Hale, Ruth",
                "Tom Hale | parent-of | Hale, Ned", "Tom Hale | relative-of | Endō, Haru", "Tom Hale | sibling-of | Hale, Ken"), triples(r), "seven relatives; Ken Ellis and the Sendai farmer are none");
        FamilyAccount.Fact mother = r.facts().stream().filter(f -> f.object().equals("Hale, Ann")).findFirst().orElseThrow();
        assertEquals("Hale, Ann (mother), 12, 45, 60", mother.quote(), "the entry with its pages is the words, not the sub-entries run on after them");
        FamilyAccount.Fact ned = r.facts().stream().filter(f -> f.object().equals("Hale, Ned")).findFirst().orElseThrow();
        assertEquals("Hale, Ned (Adeline; daughter), 90,91-93,95", ned.quote(), "a note wrapped onto the next line is joined to its name");
        FamilyAccount.Person ruth = r.people().stream().filter(p -> p.name().equals("Hale, Ruth")).findFirst().orElseThrow();
        assertEquals(List.of("Ivy"), ruth.also(), "the other given name in the note is another name of hers");
        assertEquals("Hale", ruth.family());
        assertTrue(r.dropped().stream().anyMatch(d -> d.startsWith("The book's index names 7 relatives of Tom Hale")), r.dropped().toString());
        assertTrue(GenealogyProfile.droppedSaid(r.dropped()).contains("From the book's index"));
    }

    @Test
    void nothingIsFiledWithoutAWriterOrOutsideTheIndex() {
        assertTrue(read(BOOK, null).facts().isEmpty(), "the writer is not known");
        String noIndex = BOOK.replace("\n\nIndex\n\n", "\n\nChapter Two\n\n");
        assertTrue(read(noIndex, "Tom Hale").facts().isEmpty(), "the same lines outside an index file nothing");
        String page = "Hale, Ann (mother), 12, 45\n";
        FamilyAccount.Read r = FamilyAccount.read("Index\n\n" + page, "the writer of the page Tom Hale - Wikipedia (en.wikipedia.org)", new GenealogyProfile().predicates(), p -> "{\"people\": [], \"facts\": []}",
                List.of(), null, null, FamilyAccount.Voice.page("Tom Hale - Wikipedia (en.wikipedia.org)"));
        assertTrue(r.facts().isEmpty(), "a web page has no writer to relate to");
    }

    @Test
    void theFamilyNamesFilterAndTheModelsOwnFactStillApply() {
        FamilyAccount.Read kept = FamilyAccount.read(BOOK, "Tom Hale", new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : "{\"people\": [], \"facts\": []}",
                List.of("Hale"), null, null, FamilyAccount.Voice.of("Tom Hale", "file:///family/farm.epub"));
        assertTrue(triples(kept).stream().allMatch(t -> t.contains("Hale")), "kept to the family's names: " + triples(kept));
        assertTrue(triples(kept).contains("Tom Hale | child-of | Hale, Ann"));
        FamilyAccount.Read twice = FamilyAccount.read(BOOK, "Tom Hale", new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : p.contains("Index") ? """
                {"people": [], "facts": [{"subject": "Tom Hale", "relation": "child-of", "object": "Hale, Ann", "date": "", "quote": "Hale, Ann (mother), 12, 45"}]}""" : "{\"people\": [], \"facts\": []}",
                List.of(), null, null, FamilyAccount.Voice.of("Tom Hale", "file:///family/farm.epub"));
        assertEquals(1, triples(twice).stream().filter(t -> t.equals("Tom Hale | child-of | Hale, Ann")).count(), "the model's own fact is not filed twice");
    }
}
