package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Six defects of the linking found on a whole library, each fixed generally ({@link FamilyLinks}), with invented families: the one-character
 * rule holds through a name in Latin letters that reads as both; a parent two sources give differently does not keep apart two entries that
 * agree in three facts; one page that writes a woman under her maiden and her married name joins them; a name in lower case is the same name;
 * "X's mother" is the mother X's own source records, and "the speaker in <a talk>" its writer; a given name alone whose only relation is to
 * another given name alone on the same page resolves through that page.
 */
class FamilyLinksSixTest {

    private static final String NOTES = "file:///family/notes.txt";
    private static final String REGISTER = "file:///family/register.txt";
    private static final String BOOK = "file:///family/morita-memoir.txt";
    private static final String PAGE = "https://ja.example.org/wiki/morita";
    private static final String TREE = "https://www.geni.com/people/Morita/1";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /** A model that reads the Moritas' names, family and given, and 源三郎 and 源四郎 both as げんざぶろう. */
    private static FamilyLinks.Model reads() {
        Map<String, String> r = Map.of("family name 森田", "もりた", "given name 源三郎", "げんざぶろう", "given name 源四郎", "げんざぶろう", "given name 健二", "けんじ");
        return prompt -> { for (Map.Entry<String, String> e : r.entrySet()) if (prompt.contains(e.getKey())) return "{\"readings\": [\"" + e.getValue() + "\"]}"; return "{}"; };
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static List<String> sentences(LibraryStore store) throws IOException { return FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList(); }

    // ── 1. the one-character rule through a name in Latin letters ────────────────────────────────────────────────────

    @Test
    void twoNamesOneCharacterApartStayTwoPeopleThoughOneNameInLatinLettersReadsAsBoth(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        // a father both scripts give: one man by the evidence, so L7 can pair through him
        claim(store, "森田一郎", "born-on", "1870", REGISTER);
        claim(store, "森田一郎", "born-on", "1870", BOOK);
        Graph.alias(store, "森田一郎", List.of("もりた いちろう"), REGISTER);
        claim(store, "森田源三郎", "child-of", "森田一郎", PAGE);
        claim(store, "森田源三郎", "born-on", "1848", PAGE);
        claim(store, "森田源四郎", "child-of", "森田一郎", REGISTER);
        claim(store, "森田源四郎", "born-on", "1848", REGISTER);
        claim(store, "Genzaburo Morita", "child-of", "森田一郎", BOOK);
        claim(store, "Genzaburo Morita", "born-on", "1848", BOOK);
        claim(store, "Genzaburo Morita", "died-on", "1919", BOOK);
        claim(store, "森田源三郎", "died-on", "1919", PAGE);
        FamilyLinks.update(store, reads(), x -> { });
        assertNotEquals(entry(store, "森田源三郎"), entry(store, "森田源四郎"), "one character apart: two people, whatever reads as both: " + sentences(store));
        String romaji = entry(store, "Genzaburo Morita");
        assertTrue(romaji.equals(entry(store, "森田源三郎")) || romaji.equals(entry(store, "森田源四郎")), "the name in Latin letters joins one of them: " + sentences(store));
        assertEquals(entry(store, "森田源三郎"), romaji, "the one whose death year agrees with it");
        assertTrue(FamilyLinks.current(store).links().stream().anyMatch(l -> l.grade() == FamilyLinks.Grade.possible && l.why().contains("differ by one character")), "and the other stays a possible link: " + sentences(store));
    }

    // ── 2. a parent given differently, beside three agreeing facts ───────────────────────────────────────────────────

    @Test
    void aParentTwoSourcesGiveDifferentlyIsNotedNotHeldAgainstAJoinThreeFactsSupport(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        for (String src : new String[]{REGISTER, TREE}) { claim(store, "Isamu Morita", "born-on", "1870", src); claim(store, "Haru Morita", "born-on", "1875", src); claim(store, "Ann Ellis", "born-on", "1892", src); }
        claim(store, "Kenji Morita", "born-on", "1889", BOOK);
        claim(store, "Kenji Morita", "born-in", "the Koishikawa ward of Tokyo", BOOK);
        claim(store, "Morita, Kenji", "born-in", "Koishikawa, Tokyo, Japan", TREE);
        claim(store, "Kenji Morita", "died-on", "1988", BOOK);
        claim(store, "Kenji Morita", "child-of", "Isamu Morita", BOOK);
        claim(store, "Kenji Morita", "child-of", "Rin Morita", BOOK);
        claim(store, "Kenji Morita", "married-to", "Ann Ellis", BOOK);
        claim(store, "Morita, Kenji", "born-on", "1889", TREE);
        claim(store, "Morita, Kenji", "died-on", "1988", TREE);
        claim(store, "Morita, Kenji", "child-of", "Isamu Morita", TREE);
        claim(store, "Morita, Kenji", "child-of", "Haru Morita", TREE);
        claim(store, "Morita, Kenji", "married-to", "Ann Ellis", TREE);
        FamilyLinks.update(store);
        assertEquals(entry(store, "Kenji Morita"), entry(store, "Morita, Kenji"), "birth, death, father and wife agree; the mothers differ: " + sentences(store));
        FamilyLinks.Link l = FamilyLinks.current(store).links().stream().filter(x -> x.joins() && !x.mention()).findFirst().orElseThrow();
        assertTrue(l.why().contains("name a parent differently"), "the join says what differs: " + l.why());

        LibraryStore few = family(tmp.resolve("few"));
        for (String src : new String[]{REGISTER, TREE}) { claim(few, "Isamu Morita", "born-on", "1870", src); claim(few, "Haru Morita", "born-on", "1875", src); }
        claim(few, "Kenji Morita", "child-of", "Isamu Morita", BOOK);
        claim(few, "Kenji Morita", "child-of", "Rin Morita", BOOK);
        claim(few, "Morita, Kenji", "child-of", "Isamu Morita", TREE);
        claim(few, "Morita, Kenji", "child-of", "Haru Morita", TREE);
        FamilyLinks.update(few);
        assertNotEquals(entry(few, "Kenji Morita"), entry(few, "Morita, Kenji"), "one fact agrees and the mothers differ: still two");
    }

    // ── 3. one page, maiden and married names ────────────────────────────────────────────────────────────────────────

    @Test
    void onePageThatWritesAWomanUnderHerMaidenAndMarriedNamesWithTheSameHusbandAndDeathJoinsThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1875", PAGE);
        claim(store, "森田健二", "born-on", "1875", REGISTER);
        FamilyNameHistoryTest.file(store, REGISTER, List.of(), List.of(FamilyNameHistoryTest.name("森田健二", "森田健二", "森田", "健二", "birth", "", "森田健二。")));
        claim(store, "森田健二", "married-to", "森田花子", PAGE);
        claim(store, "森田花子", "died-on", "1935", PAGE);
        claim(store, "森田花子", "died-in", "London", PAGE);
        claim(store, "森田健二", "married-to", "遠藤花子", PAGE);
        claim(store, "遠藤花子", "died-on", "1935", PAGE);
        claim(store, "遠藤花子", "died-in", "London", PAGE);
        claim(store, "森田健二", "married-to", "花子", PAGE);
        claim(store, "遠藤源三郎", "parent-of", "遠藤花子", PAGE);
        FamilyNameHistoryTest.file(store, PAGE, List.of(), List.of(FamilyNameHistoryTest.name("遠藤源三郎", "遠藤源三郎", "遠藤", "源三郎", "birth", "", "遠藤源三郎。")));
        FamilyLinks.update(store);
        assertEquals(entry(store, "森田花子"), entry(store, "遠藤花子"), "one given name, two family names, the same husband and death year, on one page: " + sentences(store));
        Finding alone = null;
        for (Finding f : store.scanFindings().findings()) if (f.triple() != null && f.triple().object().equals("花子")) alone = f;
        assertNotNull(alone);
        assertEquals(entry(store, "森田花子"), FamilyPeople.view(store).nodeOf(alone, false), "and the given name alone on the same page is she: " + sentences(store));
    }

    // ── 4. a name in lower case ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aNameInLowerCaseIsTheSameNameAsTheFormANameClaimLists(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "rose yamada", "child-of", "Kenji Yamada", NOTES);
        claim(store, "rose yamada", "born-on", "1950", NOTES);
        FamilyNameHistoryTest.file(store, NOTES, List.of(), List.of(FamilyNameHistoryTest.name("Rose Yoko Hale Yamada", "Rose Yoko Hale Yamada", "Yamada", "Rose Yoko", "marriage", "1975", "Rose Yoko Hale Yamada, known as rose yamada.", "rose yamada")));
        claim(store, "Rose Yoko Hale Yamada", "born-on", "1950", TREE);
        FamilyLinks.update(store);
        assertEquals(entry(store, "rose yamada"), entry(store, "Rose Yoko Hale Yamada"), "the same name, case aside, that one text writes both ways: " + sentences(store));
    }

    // ── 5. descriptions: "X's mother" by X's own source, and "the speaker in <a talk>" ───────────────────────────────

    @Test
    void aMemoirsMyMotherIsTheMotherTheMemoirRecordsAndTheSpeakerInATalkIsItsWriter(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "born-on", "1889", BOOK);
        claim(store, "Kenji Morita", "born-on", "1889", TREE);
        claim(store, "Kenji Morita", "child-of", "Morita, Rin", BOOK);
        claim(store, "Morita, Rin", "sex", "female", BOOK);
        claim(store, "Kenji Morita", "child-of", "Haru Morita", TREE);
        claim(store, "Haru Morita", "sex", "female", TREE);
        claim(store, "Kenji Morita's mother", "occupation", "teacher", BOOK);
        claim(store, "Kenji Morita's mother", "lived-in", "Kobe", BOOK);
        claim(store, "Kimie Hale", "life-event", "wrote two-homes-across-the-sea.pdf", NOTES);
        claim(store, "the speaker in the talk Two Homes Across the Sea", "lived-in", "Leeds", "file:///family/two-homes-across-the-sea.pdf");
        claim(store, "the writer of the talk Two Homes Across the Sea's mother", "born-in", "Kobe", "file:///family/two-homes-across-the-sea.pdf");
        claim(store, "Kimie Hale", "child-of", "Ann Hale", REGISTER);
        claim(store, "Kimie Hale", "child-of", "Ann Hale", TREE);
        claim(store, "Ann Hale", "sex", "female", REGISTER);
        claim(store, "the speaker in morita-memoir.txt, part 3", "lived-in", "Kobe", BOOK);
        FamilyLinks.update(store);
        assertEquals(entry(store, "Morita, Rin"), entry(store, "Kenji Morita's mother"), "the memoir's mother, not the tree's: " + sentences(store));
        assertNotEquals(entry(store, "Haru Morita"), entry(store, "Kenji Morita's mother"));
        assertEquals(entry(store, "Kimie Hale"), entry(store, "the speaker in the talk Two Homes Across the Sea"), "your notes say she wrote the talk: " + sentences(store));
        assertEquals(entry(store, "Ann Hale"), entry(store, "the writer of the talk Two Homes Across the Sea's mother"), "and the talk's writer's mother walks from her: " + sentences(store));
        assertNotEquals(entry(store, "Kenji Morita"), entry(store, "the speaker in morita-memoir.txt, part 3"), "somebody a book quotes is not its writer");
    }

    // ── 6. a given name alone whose only relation is to another given name alone ─────────────────────────────────────

    @Test
    void aGivenNameAloneMarriedToAnotherGivenNameAloneOnOnePageIsTheFullNameThatPageMarriesToTheSame(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "大野太郎", "born-on", "1880", PAGE);
        claim(store, "森田太郎", "born-on", "1890", PAGE);
        claim(store, "ハル", "married-to", "大野太郎", PAGE);
        Finding f = claim(store, "ハル", "married-to", "太郎", PAGE);
        FamilyLinks.update(store);
        assertEquals(entry(store, "大野太郎"), FamilyPeople.view(store).nodeOf(store.finding(f.id()), false), "of the two 太郎 on the page, the one ハル is married to: " + sentences(store));
    }
}
