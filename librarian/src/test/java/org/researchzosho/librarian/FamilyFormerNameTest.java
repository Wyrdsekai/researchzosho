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
 * "X (formerly Y)", "X, formerly Y", "formerly known as Y" and 旧姓 Y say Y is the earlier name. When X is the name taken at a marriage and
 * no other change is known, Y is the name at birth, worked out, carried up to the marriage. When how X came is not known, Y is the earlier
 * name and X the later, and nothing is asked.
 */
class FamilyFormerNameTest {

    private static String id(LibraryStore store, String person) throws Exception { return FamilyPeople.view(store).nodeIdOf(person); }

    private static List<FamilyNameHistory.Name> names(LibraryStore store, String person) throws Exception { return FamilyNameHistory.of(FamilyPeople.view(store)).names(id(store, person)); }

    private static FamilyNameHistory.Name named(LibraryStore store, String person, String written) throws Exception {
        List<FamilyNameHistory.Name> all = names(store, person);
        return all.stream().filter(n -> n.written().equals(written)).findFirst().orElseThrow(() -> new AssertionError(person + " has no name " + written + ": " + all));
    }

    private static List<FamilyNameQuestions.Question> about(LibraryStore store, String person) throws Exception {
        String id = id(store, person);
        // what is asked by itself: naming the person also offers a married name the library worked out, so that one answer can change it
        return FamilyNameQuestions.open(store).stream().filter(q -> q.people().contains(id)).toList();
    }

    private static String page(LibraryStore store, String person) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FamilyNamePages.cliNames(store, person, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theWordsBeforeANameSayItIsTheEarlierOneAndNameTheLater() {
        assertEquals("my mother is Yasuko Hale", FamilyNameHistory.formerName("my mother is Yasuko Hale (formerly Yasuko Ellis)", "Yasuko Ellis"));
        assertEquals("Yasuko Hale", FamilyNameHistory.formerName("Yasuko Hale, formerly Yasuko Ellis, kept the shop.", "Yasuko Ellis"));
        assertEquals("Yasuko Hale", FamilyNameHistory.formerName("Yasuko Hale, formerly known as Yasuko Ellis", "Yasuko Ellis"));
        assertEquals("森田ハル", FamilyNameHistory.formerName("森田ハル（旧姓 遠藤ハル）は1948年に結婚した。", "遠藤ハル"));
        assertNull(FamilyNameHistory.formerName("Yasuko Ellis was formerly a teacher.", "Yasuko Ellis"), "formerly before something else says nothing of the name");
        assertNull(FamilyNameHistory.kindInWords("Yasuko Hale, formerly known as Yasuko Ellis", "Yasuko Ellis", "Yasuko"), "\"formerly known as\" is no name carried beside the first");
    }

    @Test
    void besideANameTakenAtAMarriageTheEarlierNameIsTheNameAtBirthCarriedUpToTheMarriage(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "my mother is Yasuko Hale (formerly Yasuko Ellis)";
        FamilyNameKindFromWordsTest.file(store, "file:///family/notes.txt", List.of(
                        new FamilyAccount.Fact("Yasuko Hale", "married-to", "Tom Hale", "1948", "Yasuko Hale married Tom Hale in 1948."),
                        FamilyNameKindFromWordsTest.fact("Yasuko Hale", "sex", "female", q), FamilyNameKindFromWordsTest.fact("Tom Hale", "sex", "male", "Tom Hale, a miller."),
                        FamilyNameKindFromWordsTest.fact("Yasuko Hale", "born-on", "1921", "Yasuko Hale was born in 1921.")),
                List.of(FamilyNameKindFromWordsTest.name("Yasuko Hale", "Yasuko Ellis", "Ellis", "Yasuko", q)));
        FamilyNameHistory.Name ellis = named(store, "Yasuko Hale", "Yasuko Ellis"), hale = named(store, "Yasuko Hale", "Yasuko Hale");
        assertEquals("marriage", hale.kind(), hale.toString());
        assertEquals("birth", ellis.kind(), "the earlier name beside the married one is the name at birth: " + ellis);
        assertTrue(ellis.workedOut() && ellis.basis().equals(FamilyNameHistory.BASIS_FORMERLY), ellis.toString());
        assertEquals(1921, ellis.from().year(), "from the birth");
        assertEquals(1948, ellis.to().year(), "up to the marriage");
        assertEquals(List.of("Yasuko Ellis", "Yasuko Hale"), names(store, "Yasuko Hale").stream().map(FamilyNameHistory.Name::written).toList());
        assertEquals("Yasuko Hale (born Ellis)", FamilyNameHistory.of(FamilyPeople.view(store)).heading(id(store, "Yasuko Hale")));
        assertEquals(List.of(), about(store, "Yasuko Hale"), "nothing to ask");
        String page = page(store, "Yasuko Hale");
        assertTrue(page.contains("Yasuko Ellis: the name at birth, from 1921 to 1948.") && page.contains("the words that say which name came first"), page);
    }

    @Test
    void whereHowTheLaterNameCameIsNotKnownTheTwoAreTheEarlierAndTheLaterNameAndNothingIsAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "my mother is Yasuko Hale (formerly Yasuko Ellis)";
        FamilyNameKindFromWordsTest.file(store, "file:///family/notes.txt", List.of(FamilyNameKindFromWordsTest.fact("Yasuko Hale", "sex", "female", q)),
                List.of(FamilyNameKindFromWordsTest.name("Yasuko Hale", "Yasuko Ellis", "Ellis", "Yasuko", q)));
        FamilyNameHistory.Name ellis = named(store, "Yasuko Hale", "Yasuko Ellis"), hale = named(store, "Yasuko Hale", "Yasuko Hale");
        assertEquals("earlier", ellis.kind(), ellis.toString());
        assertEquals("later", hale.kind(), hale.toString());
        assertTrue(ellis.explained() && hale.explained(), "neither is a question of how");
        assertEquals(List.of("Yasuko Ellis", "Yasuko Hale"), names(store, "Yasuko Hale").stream().map(FamilyNameHistory.Name::written).toList(), "the earlier first");
        assertEquals(List.of(), about(store, "Yasuko Hale"), "nothing is asked, how or when");
        String page = page(store, "Yasuko Hale");
        assertTrue(page.contains("Yasuko Ellis: the earlier name; how it changed is not known.") && page.contains("Yasuko Hale: the later name; how it changed is not known."), page);
        // a birth name the words do not give stays unsettled: the sources say only which came first
        assertNull(FamilyNameHistory.of(FamilyPeople.view(store)).birth(id(store, "Yasuko Hale")));
    }
}
