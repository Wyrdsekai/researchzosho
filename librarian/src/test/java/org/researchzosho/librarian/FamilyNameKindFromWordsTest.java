package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kind of a name from the text's own words, before any question is made: "my Christian name is Paul" is a religious name, "born in
 * Leeds as Tom Hart" the name at birth, "adopted and named" a name on adoption, a Western given name in brackets beside a Japanese one a name
 * carried beside it. A woman written by her husband's name ("Rev. and Mrs. Tom Hale") and a thing's name ("I named the farm Willow Farm")
 * are no names of the person; an index's "See" says two forms are one person's and nothing of how the name came. Two names of one person
 * are asked about once, not once from each side.
 */
class FamilyNameKindFromWordsTest {

    private static String kind(String quote, String name, String given) {
        String[] k = FamilyNameHistory.kindInWords(quote, name, given);
        return k == null ? "unknown" : k[0];
    }

    private static String kind(String quote, String name) { return kind(quote, name, ""); }

    static LibraryStore store(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init();
        new LibrarianIndex(s, Embeddings.none()).rebuild();
        return s;
    }

    static void file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.Fact fact(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String quote) {
        return new FamilyAccount.NameRead(person, name, family, given, List.of(), "unknown", "", "", quote);
    }

    /** A name claim as an older library holds it, filed before the rules that would not file it now, with the other name it gave the person. */
    static void olderNameClaim(LibraryStore store, String id, String person, String name, String quote) throws Exception {
        Map<String, String> d = FamilyNameHistory.detail("", "", "unknown", "", "", "", List.of(new FamilyNameHistory.Form(name, "")), "", "");
        String line = person + " was named " + name + ".";
        store.write(new Finding(id, line, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low, "family-account",
                "2026-09-01T00:00:00Z", LocalDate.now().toString(), Finding.Volatility.stable, "", List.of(new Finding.Source("file:///family/book.txt", "as told by an aunt", "the family's own account")),
                List.of(), null, line + "\n\nThe account says: \"" + quote + "\"\n", new Finding.Triple(person, FamilyNameHistory.PREDICATE, FamilyNameHistory.VALUE + name),
                List.of(FamilyDetail.note(d, "family-account"))));
        Graph.alias(store, person, List.of(name));
    }

    private static List<FamilyNameQuestions.Question> how(LibraryStore store, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how") && q.people().contains(id)).toList();
    }

    private static List<String> written(LibraryStore store, String person) throws Exception {
        Graph g = FamilyPeople.view(store);
        return FamilyNameHistory.of(g).names(g.nodeIdOf(person)).stream().map(FamilyNameHistory.Name::written).toList();
    }

    @Test
    void aChristianNameOrABaptismalNameIsAReligiousName() {
        assertEquals("religious", kind("My name is Kenji Morita, and my Christian name is Paul.", "Paul"));
        assertEquals("religious", kind("She was baptised Mary in 1901.", "Mary"));
        assertEquals("religious", kind("健二の洗礼名はパウロ。", "パウロ"));
        assertEquals("unknown", kind("Paul was my Christian friend at school.", "Paul"), "Christian beside a name is no Christian name");
    }

    @Test
    void bornAsAndNeeGiveTheNameAtBirth() {
        assertEquals("birth", kind("Her father was born in Leeds as Tom Hart.", "Tom Hart"));
        assertEquals("birth", kind("Ruth Hart, née Ellis, died in 1920.", "Ellis"));
        assertEquals("unknown", kind("Ruth was born to Tom Hart in Leeds.", "Tom Hart"), "the father's name is no name of hers");
        assertEquals("unknown", kind("Geni: Ruth Hart, born Ruth Ellis (birth surname Ellis), on Geni now Ruth Hart", "Ruth Hart", "Ruth"),
                "the given part of another name says nothing of this one");
    }

    @Test
    void adoptedAndNamedGivesTheNameOnAdoption() {
        assertEquals("adoptive", kind("In 1850 he was adopted by his uncle and named Edmund.", "Edmund"));
        assertEquals("adoptive", kind("健二は遠藤家の養子となり健一と改名した。", "健一"));
        assertEquals("unknown", kind("He was adopted in 1850; his brother was named Edmund.", "Edmund"), "the naming is another sentence's");
    }

    @Test
    void aWesternGivenNameInBracketsOrKnownAsIsCarriedBesideTheFirst() {
        assertEquals("aka", kind("Mr. Morita with his wife, Haru (Helen) Morita, in 1921", "Helen Morita", "Helen"));
        assertEquals("aka", kind("Morita, Haru (Helen; wife), 12", "Helen"));
        assertEquals("aka", kind("Haru, known as Helen at school, taught music.", "Helen"));
        assertEquals("unknown", kind("Kenjirō (Shōichi) Morita", "Shōichi"), "a Japanese name in the brackets is another name of its own, or a reading");
        assertEquals("unknown", kind("He was later known as Tom Hale.", "Tom Hale"), "a name he was later known by took the place of the first");
    }

    @Test
    void aWomanWrittenByHerHusbandsNameAndAThingsNameAreNoNamesOfTheirs() {
        assertTrue(FamilyNameHistory.addressedByHisName("a bench marked “In memory of Rev. and Mrs. Tom Hale”", "Mrs. Tom Hale"));
        assertFalse(FamilyNameHistory.addressedByHisName("Mrs. Ruth Hale wrote to her sister.", "Mrs. Ruth Hale"), "her own name with her title is hers");
        assertNull(FamilyNameHistory.mrsWith("Mrs. Hale"), "a title with the family name alone writes her own family name");
        assertTrue(FamilyNameHistory.namesAThing("I named the farm “Willow Farm.”", "Willow Farm"));
        assertTrue(FamilyNameHistory.namesAThing("We called it Willow Farm after the trees.", "Willow Farm"));
        assertTrue(FamilyNameHistory.namesAThing("農場を「柳農場」と名付けた。", "柳農場"));
        assertFalse(FamilyNameHistory.namesAThing("He named his son Tom after his father.", "Tom"), "a son is a person");
    }

    @Test
    void anIndexCrossReferenceIsNoAnswerToHowTheNameCame() {
        assertTrue(FamilyNameHistory.crossReference("Endō, Kenji. See Morita, Kenji", "Morita, Kenji"));
        assertTrue(FamilyNameHistory.crossReference("Edmund. See Morita, Kenji", "Morita Kenji"));
        assertFalse(FamilyNameHistory.crossReference("Kenji moved to Leeds; see chapter 4.", "Kenji"));
    }

    @Test
    void theWordsGiveTheKindAndANameWrittenWithTheChristianNameIsAFormOfIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/memoir.txt", List.of(fact("Kenji Morita", "lived-in", "Leeds", "Kenji Morita lived in Leeds.")),
                List.of(name("Kenji Morita", "Paul", "", "", "My name is Kenji Morita, and my Christian name is Paul."),
                        name("Kenji Morita", "Paul Morita", "Morita", "Paul", "Paul Morita, a student at Leeds, 1925"),
                        name("Kenji Morita", "Paul Kenji Morita", "Morita", "Paul Kenji", "letters to Paul Kenji Morita, kept by his daughter.")));
        Graph g = FamilyPeople.view(store);
        List<FamilyNameHistory.Name> names = FamilyNameHistory.of(g).names(g.nodeIdOf("Kenji Morita"));
        FamilyNameHistory.Name paul = names.stream().filter(n -> n.written().equals("Paul")).findFirst().orElseThrow();
        assertEquals("religious", paul.kind(), names.toString());
        assertTrue(paul.texts().containsAll(List.of("Paul Morita", "Paul Kenji Morita")), "written with the Christian name and his own names, it is a form of it: " + names);
        assertEquals(List.of(), how(store, "Kenji Morita"), "the words settle it: nothing is asked");
    }

    @Test
    void mrsWithHerHusbandsNameIsNoNameOfHersReadNowOrFiledBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/memoir.txt", List.of(fact("Haru Hale", "married-to", "Tom Hale", "Haru Hale married Tom Hale in 1920."), fact("Haru Hale", "sex", "female", "Haru Hale married Tom Hale in 1920.")),
                List.of(name("Haru Hale", "Mrs. Tom Hale", "Hale", "Tom", "a bench marked “In memory of Rev. and Mrs. Tom Hale”"),
                        name("Haru Hale", "Mrs. Tom Hale", "Hale", "Tom", "Mrs. Tom Hale sent flowers from Leeds.")));
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> FamilyNameHistory.isNameClaim(f) && FamilyNameHistory.written(f).equals("Mrs. Tom Hale")), "no name claim is made");
        // a claim an older library made is no name of hers either, and asks nothing
        olderNameClaim(store, "F-0900-haru-hale-has-name", "Haru Hale", "Mrs. Tom Hale", "a bench marked “In memory of Rev. and Mrs. Tom Hale”");
        assertFalse(written(store, "Haru Hale").contains("Mrs. Tom Hale"), written(store, "Haru Hale").toString());
        assertEquals(List.of(), how(store, "Haru Hale"));
    }

    @Test
    void aThingsNameIsNoNameOfThePersonReadNowOrFiledBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/memoir.txt", List.of(fact("Tom Hale", "lived-in", "Leeds", "Tom Hale lived in Leeds.")),
                List.of(name("Tom Hale", "Willow Farm", "", "", "I named the farm “Willow Farm.”")));
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> FamilyNameHistory.isNameClaim(f)), "no name claim is made");
        olderNameClaim(store, "F-0901-tom-hale-has-name", "Tom Hale", "Willow Farm", "I named the farm “Willow Farm.”");
        assertEquals(List.of("Tom Hale"), written(store, "Tom Hale"), "neither the claim nor the other name it wrote is a name of his");
        assertEquals(List.of(), how(store, "Tom Hale"));
    }

    @Test
    void anIndexCrossReferenceAsksNothingAboutHowTheNameCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/memoir.txt", List.of(fact("Kenji Endō", "lived-in", "Leeds", "Kenji Endō lived in Leeds.")),
                List.of(name("Kenji Endō", "Morita, Kenji", "", "", "Endō, Kenji. See Morita, Kenji")));
        assertTrue(written(store, "Kenji Endō").contains("Morita, Kenji"), "the other form is kept: " + written(store, "Kenji Endō"));
        assertEquals(List.of(), how(store, "Kenji Endō"), "the index says nothing of how the name came");
    }

    @Test
    void twoNamesAreAskedAboutOnceNotOnceFromEachSide(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/letters.txt", List.of(fact("Ruth Hart", "lived-in", "York", "Ruth Hart lived in York.")),
                List.of(name("Ruth Hart", "Ruth Ellis", "Ellis", "Ruth", "Ruth Hart signed her first letters Ruth Ellis.")));
        List<FamilyNameQuestions.Question> how = how(store, "Ruth Hart");
        assertEquals(1, how.size(), how.toString());
        assertTrue(how.get(0).text().contains("get the name Ruth Ellis?"), how.get(0).text());
        assertTrue(how.get(0).text().contains("also the name Ruth Hart"), "the question names the other side: " + how.get(0).text());
    }
}
