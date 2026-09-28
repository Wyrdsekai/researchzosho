package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a text addresses a person is no name of theirs: a title or an honorific with the family name alone ("Father Morita", "Morita-sensei",
 * "Rev. Morita"), or an initial with it that could abbreviate any of two names ("K. Morita" beside Kenji and Kazuo). A read files no name
 * claim for it, an older claim or other name that holds one is no name, no question asks how it came, and the person's page shows it once
 * under "Also addressed as", with its source. A title with a whole name ("Father Kenji Morita") is that name written with the title. An
 * index's relation note after a name in its "Family, Given" form ("Morita, Kenji (father)") is no part of the name.
 */
class FamilyFormsOfAddressTest {

    private static String id(LibraryStore store, String person) throws Exception { return FamilyPeople.view(store).nodeIdOf(person); }

    private static FamilyNameHistory.Index index(LibraryStore store) throws Exception { return FamilyNameHistory.of(FamilyPeople.view(store)); }

    private static List<String> written(LibraryStore store, String person) throws Exception {
        return index(store).names(id(store, person)).stream().map(FamilyNameHistory.Name::written).toList();
    }

    private static List<String> texts(LibraryStore store, String person) throws Exception {
        return index(store).names(id(store, person)).stream().flatMap(n -> n.texts().stream()).toList();
    }

    private static List<String> addressed(LibraryStore store, String person) throws Exception {
        return index(store).addressedAs(id(store, person)).stream().map(FamilyNameHistory.Index.Address::text).toList();
    }

    private static List<FamilyNameQuestions.Question> how(LibraryStore store, String person) throws Exception {
        String id = id(store, person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how") && q.people().contains(id)).toList();
    }

    private static List<String> nameClaims(LibraryStore store, String person) {
        return store.scanFindings().findings().stream().filter(f -> FamilyNameHistory.isNameClaim(f) && f.triple().subject().equals(person)).map(FamilyNameHistory::written).toList();
    }

    @Test
    void theOneListOfTitlesKnowsAPriestsAMinistersAndAJapaneseHonorificInLatinLetters() {
        assertEquals("Morita", FamilyNames.untitled("Father Morita"));
        assertEquals("Morita", FamilyNames.untitled("Rev. Morita"));
        assertEquals("Kenji Morita", FamilyNames.untitled("Reverend Kenji Morita"));
        assertEquals("Morita", FamilyNames.untitled("Morita-sensei"));
        assertEquals("Haru", FamilyNames.untitled("Haru-chan"));
        assertEquals("遠藤", FamilyNames.untitled("遠藤先生"));
        assertEquals("Morita", FamilyLinks.untitled("Pastor Morita"), "the link pass takes the same words off");
        assertTrue(FamilyNames.formOfAddress("Pastor Morita") && FamilyNames.formOfAddress("Morita-sensei") && FamilyNames.formOfAddress("遠藤さん"));
        assertFalse(FamilyNames.formOfAddress("Reverend Kenji Morita"), "a title with a whole name is that name");
        assertFalse(FamilyNames.formOfAddress("Kenji Morita") || FamilyNames.formOfAddress("Morita"), "no title: not this");
        assertTrue(FamilyNames.initials("K. Morita") && FamilyNames.initials("T. H. Hale") && FamilyNames.initials("Morita, K."));
        assertFalse(FamilyNames.initials("Kenji Morita"));
    }

    @Test
    void aTitleWithTheFamilyNameAloneIsNoNameAtReadTimeAndNoNameOnAnOlderClaimOrOtherName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "Father Morita, as the parish called Kenji Morita, kept the school in Leeds.";
        FamilyNameKindFromWordsTest.file(store, "file:///family/parish.txt", List.of(FamilyNameKindFromWordsTest.fact("Kenji Morita", "lived-in", "Leeds", q)),
                List.of(FamilyNameKindFromWordsTest.name("Kenji Morita", "Father Morita", "Morita", "", q),
                        FamilyNameKindFromWordsTest.name("Kenji Morita", "Morita-sensei", "Morita", "sensei", "The pupils wrote to Morita-sensei."),
                        FamilyNameKindFromWordsTest.name("Kenji Morita", "遠藤さん", "遠藤", "", "遠藤さんと呼ばれた。")));
        assertEquals(List.of(), nameClaims(store, "Kenji Morita"), "a read files no name for how the text addresses him");
        assertEquals(List.of("Kenji Morita"), written(store, "Kenji Morita"));
        // an older library filed one, or kept one among the other names
        FamilyNameKindFromWordsTest.olderNameClaim(store, "F-0910-kenji-morita-has-name", "Kenji Morita", "Pastor Morita", "Nebraska's Pastor Morita answered the letter.");
        Graph.alias(store, "Kenji Morita", List.of("Rev. Morita"));
        assertEquals(List.of("Kenji Morita"), written(store, "Kenji Morita"), "neither is a name of his");
        assertFalse(texts(store, "Kenji Morita").contains("Pastor Morita") || texts(store, "Kenji Morita").contains("Rev. Morita"), "nor a form of one: " + texts(store, "Kenji Morita"));
        assertEquals(List.of("Pastor Morita", "遠藤さん", "Rev. Morita"), addressed(store, "Kenji Morita"), "each once, apart from the names; the honorific the read kept as an other name among them");
        assertEquals(List.of("F-0910-kenji-morita-has-name"), index(store).addressedAs(id(store, "Kenji Morita")).get(0).claims(), "with the claim that wrote it");
        assertEquals(List.of(), how(store, "Kenji Morita"), "no question asks how a form of address came");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNamePages.cliNames(store, "Kenji Morita", new PrintStream(out, true, StandardCharsets.UTF_8));
        String page = out.toString(StandardCharsets.UTF_8);
        assertTrue(page.contains(FamilyNamePages.ADDRESSED_AS) && page.contains("Pastor Morita (from book.txt, a clue only)"), page);
        assertFalse(page.contains("Pastor Morita: a name"), "not listed among the names: " + page);
    }

    @Test
    void aTitleWithAWholeNameIsThatNameWrittenWithTheTitle(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "Father Kenji Morita's account of the mission";
        FamilyNameKindFromWordsTest.file(store, "file:///family/mission.txt", List.of(FamilyNameKindFromWordsTest.fact("Kenji Morita", "lived-in", "Leeds", "Kenji Morita lived in Leeds.")),
                List.of(FamilyNameKindFromWordsTest.name("Kenji Morita", "Father Kenji Morita", "Morita", "Kenji", q)));
        Graph.alias(store, "Kenji Morita", List.of("Reverend Kenji Morita"));
        assertEquals(List.of("Kenji Morita"), written(store, "Kenji Morita"), "one name, the title no part of it");
        assertTrue(texts(store, "Kenji Morita").containsAll(List.of("Father Kenji Morita", "Reverend Kenji Morita")), "written with the title, as forms of it: " + texts(store, "Kenji Morita"));
        assertEquals(List.of(), addressed(store, "Kenji Morita"));
        assertEquals(List.of(), how(store, "Kenji Morita"));
    }

    @Test
    void anInitialWithTheFamilyNameIsTheOneNameItAbbreviatesElseHowATextAddressesThePerson(@TempDir Path tmp) throws Exception {
        // one name it fits: a way of writing that name, read in or kept as an other name
        LibraryStore one = FamilyNameKindFromWordsTest.store(tmp.resolve("one"));
        FamilyNameKindFromWordsTest.file(one, "file:///family/letters.txt", List.of(FamilyNameKindFromWordsTest.fact("Kenji Morita", "lived-in", "Leeds", "Kenji Morita lived in Leeds.")),
                List.of(FamilyNameKindFromWordsTest.name("Kenji Morita", "K. Morita", "Morita", "K.", "Signed K. Morita.")));
        assertEquals(1, index(one).names(id(one, "Kenji Morita")).size(), "one name, written both ways: " + texts(one, "Kenji Morita"));
        assertTrue(texts(one, "Kenji Morita").containsAll(List.of("K. Morita", "Kenji Morita")), texts(one, "Kenji Morita").toString());
        assertEquals(List.of(), addressed(one, "Kenji Morita"));
        // two names it could abbreviate: how the text addresses one of them, and which is not known; a read files no name for it
        LibraryStore two = FamilyNameKindFromWordsTest.store(tmp.resolve("two"));
        FamilyNameKindFromWordsTest.file(two, "file:///family/letters.txt", List.of(FamilyNameKindFromWordsTest.fact("Kenji Morita", "lived-in", "Leeds", "Kenji Morita lived in Leeds.")),
                List.of(FamilyNameKindFromWordsTest.name("Kenji Morita", "Kazuo Morita", "Morita", "Kazuo", "In the register he is Kazuo Morita."),
                        FamilyNameKindFromWordsTest.name("Kenji Morita", "K. Morita", "Morita", "K.", "Mr. K. Morita of Leeds")));
        assertEquals(List.of("Kazuo Morita"), nameClaims(two, "Kenji Morita"), "no claim for the initial that could be either name");
        FamilyNameKindFromWordsTest.olderNameClaim(two, "F-0911-kenji-morita-has-name", "Kenji Morita", "K. Morita", "Mr. K. Morita of Leeds");
        assertEquals(2, written(two, "Kenji Morita").size(), written(two, "Kenji Morita").toString());
        assertTrue(written(two, "Kenji Morita").containsAll(List.of("Kenji Morita", "Kazuo Morita")) && !texts(two, "Kenji Morita").contains("K. Morita"), texts(two, "Kenji Morita").toString());
        assertEquals(List.of("K. Morita"), addressed(two, "Kenji Morita"), "an older claim of it is how the text addresses him");
        // a person filed under a name in characters, with no name in letters yet: an initial abbreviates nothing the library knows
        LibraryStore none = FamilyNameKindFromWordsTest.store(tmp.resolve("none"));
        FamilyNameKindFromWordsTest.file(none, "file:///family/letters.txt", List.of(FamilyNameKindFromWordsTest.fact("森田健二", "lived-in", "Leeds", "森田健二 lived in Leeds.")),
                List.of(FamilyNameKindFromWordsTest.name("森田健二", "K. Morita", "Morita", "K.", "Mr. K. Morita of Leeds")));
        assertEquals(List.of(), nameClaims(none, "森田健二"));
        // a record that says more than the form, the name she took at her marriage and its year, is a name claim, written with her initial
        LibraryStore dated = FamilyNameKindFromWordsTest.store(tmp.resolve("dated"));
        String q = "Mary Ellis was born Mary Hale in 1850 and signed her letters M. Ellis from her marriage in 1875.";
        FamilyNameKindFromWordsTest.file(dated, "file:///family/letter.txt", List.of(FamilyNameKindFromWordsTest.fact("Mary Ellis", "married-to", "Tom Ellis", q)),
                List.of(new FamilyAccount.NameRead("Mary Ellis", "Mary Hale", "Hale", "Mary", List.of(), "birth", "", "1850", q),
                        new FamilyAccount.NameRead("Mary Ellis", "M. Ellis", "Ellis", "M.", List.of(), "marriage", "", "1875", q)));
        assertTrue(nameClaims(dated, "Mary Ellis").contains("M. Ellis"), nameClaims(dated, "Mary Ellis").toString());
        assertTrue(written(dated, "Mary Ellis").contains("M. Ellis") && addressed(dated, "Mary Ellis").isEmpty(), written(dated, "Mary Ellis").toString());
    }

    @Test
    void anIndexRelationNoteIsNoPartOfTheName(@TempDir Path tmp) throws Exception {
        assertArrayEquals(new String[]{"Morita, Kenji", "father"}, FamilyNames.indexNote("Morita, Kenji (father)"));
        assertArrayEquals(new String[]{"Morita, Haru (Helen)", "wife"}, FamilyNames.indexNote("Morita, Haru (Helen; wife)"), "a name in the bracket stays");
        assertArrayEquals(new String[]{"Morita, Tom", "brother; brother-in-law"}, FamilyNames.indexNote("Morita, Tom (brother; brother-in-law)"));
        assertArrayEquals(new String[]{"John Ellis (his son)", ""}, FamilyNames.indexNote("John Ellis (his son)"), "no index form: the bracket tells one John Ellis from another");
        assertArrayEquals(new String[]{"Morita, Kenji (born 1850)", ""}, FamilyNames.indexNote("Morita, Kenji (born 1850)"), "a year is no relation");
        assertEquals("Endō Kenji", FamilyNames.withoutRelationNote("Endō Kenji (brother)"), "an other name kept from an index form, without its comma");
        assertEquals("John Ellis (his son)", FamilyNames.withoutRelationNote("John Ellis (his son)"), "a pronoun: how a text tells two of one name apart");
        assertEquals("Morita Kenji", FamilyNames.asOtherName("Morita, Kenji (father)"));
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "Morita, Kenji (father), 12, 40";
        FamilyNameKindFromWordsTest.file(store, "file:///family/memoir.txt", List.of(FamilyNameKindFromWordsTest.fact("Kenji Morita", "lived-in", "Leeds", "Kenji Morita lived in Leeds.")),
                List.of(FamilyNameKindFromWordsTest.name("Kenji Morita", "Morita, Kenji (father)", "", "", q)));
        Finding claim = store.scanFindings().findings().stream().filter(f -> FamilyNameHistory.isNameClaim(f) && f.triple().subject().equals("Kenji Morita")).findFirst().orElseThrow();
        assertEquals("name: Morita, Kenji", claim.triple().object(), "the claim's name has no note");
        assertEquals("father", FamilyDetail.get(claim, "relation"), "the relation is kept for the link pass, which knows the book's subject");
        assertEquals(1, index(store).names(id(store, "Kenji Morita")).size(), "the index form and the name he is filed under are one name: " + texts(store, "Kenji Morita"));
        assertTrue(texts(store, "Kenji Morita").containsAll(List.of("Morita, Kenji", "Kenji Morita")) && texts(store, "Kenji Morita").stream().noneMatch(t -> t.contains("(father)")), texts(store, "Kenji Morita").toString());
        // an older claim and an other name that still carry the note
        FamilyNameKindFromWordsTest.olderNameClaim(store, "F-0912-kenji-morita-has-name", "Kenji Morita", "Endō, Kenji (brother)", "Endō, Kenji (brother), 7");
        Graph.alias(store, "Kenji Morita", List.of("Morita, K. (son)"));
        List<String> texts = texts(store, "Kenji Morita");
        assertTrue(texts.contains("Endō, Kenji") && texts.stream().noneMatch(t -> t.contains("(brother)") || t.contains("(son)")), texts.toString());
    }
}
