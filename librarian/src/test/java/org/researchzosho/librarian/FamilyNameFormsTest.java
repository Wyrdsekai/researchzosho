package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A spelling in Latin letters that reads as a person's name in characters is a way of writing that name ({@link FamilyNameHistory}), with
 * an invented family: the readings the link pass kept (L7), or a reading in kana filed with the name (L6), say so; a title comes off first;
 * a spelling that reads as no name of the person stays a name of its own. The view asks nobody: the readings come from links.json.
 */
class FamilyNameFormsTest {

    private static final String REGISTER = "file:///family/register.txt";
    private static final String BOOK = "file:///family/morita-memoir.txt";

    /** A model that reads the Moritas' names, and counts what it is asked. */
    private static final class Reads implements FamilyLinks.Model {
        final Map<String, String> r;
        final List<String> asked = new ArrayList<>();
        Reads(Map<String, String> r) { this.r = r; }
        @Override public String ask(String prompt) {
            asked.add(prompt);
            for (Map.Entry<String, String> e : r.entrySet()) if (prompt.contains(e.getKey())) return "{\"readings\": [\"" + e.getValue() + "\"]}";
            return "{}";
        }
    }

    /** 森田健二 in the register, and a book that writes him Kenji Morita, with a title too, and also names Kenzo Morita as a name of his. */
    private static LibraryStore kenji(Path tmp, String... kanaForms) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        FamilyNameHistoryTest.file(store, REGISTER, List.of(
                        new FamilyAccount.Fact("森田健二", "born-on", "1905", "", "森田健二、明治三十八年生。")),
                List.of(FamilyNameHistoryTest.name("森田健二", "森田健二", "森田", "健二", "birth", "1905", "森田健二、明治三十八年生。", kanaForms)));
        FamilyNameHistoryTest.file(store, BOOK, List.of(
                        new FamilyAccount.Fact("森田健二", "occupation", "shopkeeper", "", "Kenji Morita kept the shop.")),
                List.of(FamilyNameHistoryTest.name("森田健二", "Kenji Morita", "", "", "", "", "Kenji Morita kept the shop."),
                        FamilyNameHistoryTest.name("森田健二", "Kenzo Morita", "", "", "", "", "Kenzo Morita, as the book once misprints him.")));
        Graph.alias(store, "森田健二", List.of("Mr. Kenji Morita"), BOOK);
        return store;
    }

    private static FamilyNameHistory.Name named(List<FamilyNameHistory.Name> ns, String written) {
        return ns.stream().filter(n -> n.written().equals(written)).findFirst().orElse(null);
    }

    @Test
    void aSpellingThatReadsAsTheNameInCharactersByTheKeptReadingsIsAFormOfItAndRaisesNoQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        Reads model = new Reads(Map.of("family name 森田", "もりた", "given name 健二", "けんじ"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(2, model.asked.size(), "the link pass asks for the two parts of the name the book spells in Latin letters: " + model.asked);
        assertTrue(FamilyLinks.cachedReadings(store).containsKey("健二 (given name)"), "and keeps the answers");
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<FamilyNameHistory.Name> ns = idx.names(kenji);
        FamilyNameHistory.Name han = named(ns, "森田健二");
        assertNotNull(han, ns.toString());
        assertTrue(han.isForm("Kenji Morita"), "Kenji Morita reads もりた けんじ: a way of writing 森田健二: " + ns);
        assertTrue(han.isForm("Mr. Kenji Morita"), "the title comes off first: " + ns);
        assertEquals("birth", han.kind());
        assertEquals(1905, han.from().year());
        assertNull(named(ns, "Kenji Morita"), "not a name of its own: " + ns);
        FamilyNameHistory.Name kenzo = named(ns, "Kenzo Morita");
        assertNotNull(kenzo, "a spelling that reads as no name of his stays a name of its own: " + ns);
        assertEquals("unknown", kenzo.kind());
        List<String> qs = FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList();
        assertTrue(qs.stream().noneMatch(q -> q.contains("get the name Kenji Morita")), qs.toString());
    }

    @Test
    void aReadingInKanaFiledWithTheNameSaysItToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp, "もりた けんじ");
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<FamilyNameHistory.Name> ns = idx.names(g.nodeIdOf("森田健二"));
        FamilyNameHistory.Name han = named(ns, "森田健二");
        assertNotNull(han, ns.toString());
        assertTrue(han.isForm("Kenji Morita"), "the reading on file: " + ns);
        assertNotNull(named(ns, "Kenzo Morita"));
        assertTrue(FamilyLinks.cachedReadings(store).isEmpty(), "nobody was asked");
    }

    @Test
    void aFamilyNameOnlyGuessedFromTwoPeopleSplitsAMarriedNameHoweverManyFamiliesTheLibraryHolds(@TempDir Path tmp) throws Exception {
        // twenty-four families a claim names, more than the list a book is read for holds: the family name guessed from a husband and a
        // wife who share it must still split her married name, or the marriage is never worked out
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        List<FamilyAccount.Fact> facts = new ArrayList<>();
        List<FamilyAccount.NameRead> names = new ArrayList<>();
        String[] families = {"Baker", "Cook", "Hill", "Wood", "Stone", "Field", "Brook", "Green", "White", "Black", "Brown", "Gray", "Fox", "Wolf", "Lamb", "Bird", "Fish", "Rose", "Bell", "Ford", "Marsh", "Dale", "Frost", "Snow"};
        for (String f : families) {
            facts.add(new FamilyAccount.Fact("Ann " + f, "married-to", "Tom " + f, "", "Ann " + f + " married Tom " + f + "."));
            names.add(FamilyNameHistoryTest.name("Ann " + f, "Ann " + f, f, "Ann", "birth", "", "Ann " + f + " married Tom " + f + "."));
            names.add(FamilyNameHistoryTest.name("Tom " + f, "Tom " + f, f, "Tom", "birth", "", "Ann " + f + " married Tom " + f + "."));
        }
        FamilyNameHistoryTest.file(store, REGISTER, facts, names);
        FamilyNameHistoryTest.file(store, BOOK, List.of(
                        new FamilyAccount.Fact("森田花子", "born-on", "1905", "", "森田花子、明治三十八年生。"),
                        new FamilyAccount.Fact("森田花子", "sex", "female", "", "森田花子、女。"),
                        new FamilyAccount.Fact("森田花子", "child-of", "森田一郎", "", "森田一郎の長女 森田花子。"),
                        new FamilyAccount.Fact("森田花子", "married-to", "遠藤健吾", "1932", "昭和七年 遠藤健吾と婚姻。"),
                        new FamilyAccount.Fact("遠藤健吾", "sex", "male", "", "遠藤健吾、男。")),
                List.of(FamilyNameHistoryTest.name("森田一郎", "森田一郎", "森田", "一郎", "birth", "", "森田一郎、男。")));
        Graph.alias(store, "森田花子", List.of("遠藤花子"), BOOK);
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyFolder.familyNames(g).size() <= 24 && !FamilyFolder.familyNames(g).contains("遠藤"), "the list a book is read for is cut short: " + FamilyFolder.familyNames(g).size());
        assertTrue(FamilyFolder.everyFamilyName(g).contains("遠藤"), "but every family name the library guesses is there: " + FamilyFolder.everyFamilyName(g));
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        String hanako = g.nodeIdOf("森田花子");
        List<FamilyNameHistory.Name> ns = idx.names(hanako);
        FamilyNameHistory.Name married = named(ns, "遠藤花子");
        assertNotNull(married, ns.toString());
        assertEquals("遠藤", married.family(), "split by the guessed family name: " + ns);
        assertEquals("marriage", married.kind(), ns.toString());
        assertEquals(1932, married.from().year());
        assertEquals("birth", named(ns, "森田花子").kind(), ns.toString());
    }

    @Test
    void withNoReadingAtAllTheSpellingStaysANameOfItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        Graph g = FamilyPeople.view(store);
        List<FamilyNameHistory.Name> ns = FamilyNameHistory.of(g).names(g.nodeIdOf("森田健二"));
        assertNotNull(named(ns, "Kenji Morita"), "no reading on file and none kept: the library cannot tell, and asks: " + ns);
    }
}
