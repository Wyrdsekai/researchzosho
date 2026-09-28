package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A family search searches each period of a life under the name the person carried then, and both names together for the record of the
 * change: the research question, what the run is told no search used yet, and the record sites' addresses. Invented names only.
 */
class FamilyQuestionsPeriodsTest {

    static final String BORN = "健二は1905年に遠藤家に生まれた。";
    static final String ENTERED = "In 1932 he entered the Morita family (森田家), whose head was Morita Isamu, as mukoyōshi (婿養子) of Isamu.";

    /** 森田健二: born 遠藤健二 in 1905, 森田健二 from 1932 on entering the 森田 family as 婿養子, died 1980; both names with their forms, from one book. */
    static LibraryStore kenji(Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("森田健二", "born-on", "1905", "", BORN),
                        new FamilyAccount.Fact("森田健二", "died-on", "1980", "", "健二は1980年に亡くなった。"),
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", ENTERED, Map.of("kind", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", ENTERED, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田勇", "died-on", "1950", "", "勇は1950年に亡くなった。")),
                List.of(), List.of(),
                List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", BORN, "えんどう けんじ", "Endō Kenji"),
                        FamilyNameHistoryTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", ENTERED, "もりた けんじ", "Morita Kenji")),
                List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", ENTERED))), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        return store;
    }

    static FamilyQuestions.Ask ask(LibraryStore store, String person) throws Exception {
        return FamilyQuestions.around(store, person, 2, 2, true).stream().filter(a -> a.person().equals(person)).findFirst().orElseThrow();
    }

    @Test
    void theQuestionNamesEachPeriodAndAsksForTheRecordsOfEachUnderTheNameCarriedThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        FamilyQuestions.Ask a = ask(store, "森田健二");
        String q = a.question();
        assertTrue(q.startsWith("森田健二 (named 遠藤健二 at birth, until 1932, also written えんどう けんじ, Endō Kenji; "), "the label first, then the names per period, each with the forms its sources wrote: " + q);
        assertTrue(q.contains("; named 森田健二 from 1932, on entering the 森田 family as 婿養子 (adopted and married), also written もりた けんじ, Morita Kenji; born 1905; died 1980;"), q);
        assertTrue(FamilyQuestions.about(q, "森田健二"), "the question is still about the person as the tree writes them");
        assertEquals(1905, FamilyDate.bornOf(q).year(), "and the years of the life are still read from it: " + q);
        assertEquals(1980, FamilyDate.lived(q)[1]);
        String search = a.questions().stream().filter(x -> x.startsWith("Search the records of 森田健二")).findFirst().orElseThrow(() -> new AssertionError(a.questions().toString()));
        assertEquals("Search the records of 森田健二 under the name carried at the time of each: before 1932 under 遠藤健二 (えんどう けんじ, Endō Kenji, Kenji Endō, Endo Kenji, Kenji Endo); "
                + "and from 1932 under 森田健二 (もりた けんじ, Morita Kenji, Kenji Morita). The record of the change from 遠藤健二 to 森田健二 names both: "
                + "search 遠藤健二 together with 森田 or 森田家, and Endō Kenji together with Morita.", search);
        assertTrue(a.gaps().contains("records under each of the person's names"), a.gaps().toString());
    }

    @Test
    void aSearchUnderOneNameOnlyAsksForTheOtherPeriodAndTheRecordOfTheChangeUntilBothAreSearched(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-20", "森田健二 (born 1905): …", "web", "\"森田健二\" 広島", 1932, 1980, 3, "J-1"),
                new SearchLog.Entry("2026-09-20", "森田健二 (born 1905): …", "internet-archive", "\"遠藤健二\"", 0, 0, 0, "J-1", SearchLog.FAILED)));
        List<String> asks = ask(store, "森田健二").questions();
        assertTrue(asks.contains("森田健二 has been searched for only under the name 森田健二 (from 1932). Search for 森田健二 under 遠藤健二 (えんどう けんじ, Endō Kenji, Kenji Endō, Endo Kenji, Kenji Endo) "
                + "for the records before 1932 as well, in the places already searched (web). No search has carried 遠藤健二 and 森田健二 together: the record of the change names both, "
                + "so search 遠藤健二 together with 森田 or 森田家, and Endō Kenji together with Morita."), "a search that did not answer searched nothing: " + asks);
        assertTrue(asks.stream().noneMatch(x -> x.startsWith("Search the records of")), "once the log holds searches, the question names what they left out: " + asks);

        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-21", "森田健二: …", "ndl-fulltext", "遠藤健二 森田家 婿養子", 1920, 1940, 1, "J-2")));
        asks = ask(store, "森田健二").questions();
        assertTrue(asks.stream().noneMatch(x -> x.contains("遠藤健二")), "each name has been searched, and the record of the change under both: the question is done: " + asks);
    }

    @Test
    void theRunIsToldTheFormsAndTheYearsOfEachNameAndTheChangeNoSearchHasUsed(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        String question = ask(store, "森田健二").question();
        List<SearchLog.Entry> answered = List.of(new SearchLog.Entry("2026-09-20", question, "web", "\"森田健二\"", 1932, 1980, 3, "J-1"),
                new SearchLog.Entry("2026-09-20", question, "loc-newspapers", "\"Kenji Endo\"", 0, 0, 0, "J-1"));
        String never = FamilyQuestions.neverSearched(store, question, answered);
        assertTrue(never.startsWith("NOT YET SEARCHED FOR THIS PERSON"), never);
        assertTrue(never.contains("- written forms of the name 遠藤健二, carried 1905–1932, never searched: 遠藤健二, えんどう けんじ, Endou Kenji, Endoh Kenji, Endoo Kenji\n"),
                "Endō Kenji and both its orders were searched as Kenji Endo; each other spelling was not: " + never);
        assertTrue(never.contains("- written forms of the name 森田健二, carried from 1932, never searched: もりた けんじ, Morita Kenji\n"), never);
        assertTrue(never.contains("- no search was held to the years 1905–1932 with the name 遠藤健二: give from_year and to_year"), never);
        assertFalse(never.contains("the years from 1932 with the name 森田健二"), "the search of 1932 to 1980 under 森田健二 was: " + never);
        assertTrue(never.contains("- no search carried 遠藤健二 and 森田健二 together: the record of the change names both, so search 遠藤健二 together with 森田 or 森田家, and Endō Kenji together with Morita\n"), never);
        assertFalse(never.contains("written forms of the name never searched"), "a person of several names is told name by name: " + never);
    }

    @Test
    void theRecordSitesGetOneAddressForEachNameWithItsOwnFamilyPart(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        List<String[]> sites = FamilyQuestions.siteLinks(FamilyPeople.view(store), "森田健二");
        String before = sites.stream().filter(x -> x[0].equals("FamilySearch, under the name 遠藤健二 (1905–1932)")).findFirst().orElseThrow(() -> new AssertionError(names(sites)))[2];
        assertEquals("https://www.familysearch.org/search/record/results?q.givenName=Kenji&q.surname=End%C5%8D&q.birthLikeDate.from=1905&q.birthLikeDate.to=1905&q.deathLikeDate.from=1980&q.deathLikeDate.to=1980", before);
        String after = sites.stream().filter(x -> x[0].equals("FamilySearch, under the name 森田健二 (from 1932)")).findFirst().orElseThrow(() -> new AssertionError(names(sites)))[2];
        assertTrue(after.contains("q.givenName=Kenji&q.surname=Morita&"), after);

        // a woman who took her husband's family name: the name at birth under its own family part, the married name under his
        LibraryStore ellis = FamilyNameHistoryTest.store(tmp.resolve("ellis"));
        FamilyNameHistoryTest.file(ellis, "file:///family/notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "1875", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("Mary Ellis", "died-on", "1920", "", "Mary Ellis died in 1920.")),
                List.of(FamilyNameHistoryTest.name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850.")));
        List<String[]> hers = FamilyQuestions.siteLinks(FamilyPeople.view(ellis), "Mary Ellis");
        String grave = hers.stream().filter(x -> x[0].startsWith("Find a Grave, under the name Mary Hale")).findFirst().orElseThrow(() -> new AssertionError(names(hers)))[2];
        assertTrue(grave.contains("firstname=Mary&lastname=Hale"), grave);
        String married = hers.stream().filter(x -> x[0].startsWith("Find a Grave, under the name Mary Ellis")).findFirst().orElseThrow(() -> new AssertionError(names(hers)))[2];
        assertTrue(married.contains("firstname=Mary&lastname=Ellis"), married);
    }

    @Test
    void aPersonWithOneNameIsAskedAboutAsBeforeAndTheOldOneFamilyNameQuestionIsGone(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Tom Hale", "", List.of("T. Hale"))), List.of(
                new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", "q"), new FamilyAccount.Fact("Tom Hale", "died-on", "1920", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        FamilyQuestions.Ask tom = ask(store, "Tom Hale");
        assertTrue(tom.question().startsWith("Tom Hale (also written T. Hale; born 1850; died 1920):"), tom.question());
        assertTrue(tom.questions().stream().noneMatch(x -> x.startsWith("Search the records of")), "one name, one period: " + tom.questions());
        assertTrue(FamilyQuestions.periods(FamilyPeople.view(store), FamilyPeople.view(store).nodeIdOf("Tom Hale")).size() == 1);
        assertEquals("", FamilyQuestions.carriedName(FamilyQuestions.periods(FamilyPeople.view(store), FamilyPeople.view(store).nodeIdOf("Tom Hale")), "\"Tom Hale\""));
        assertThrows(NoSuchMethodException.class, () -> FamilyQuestions.class.getDeclaredMethod("oneSurnameOnly", List.class, String.class, List.class),
                "the question about one family name is replaced by the periods of the names");
    }

    @Test
    void aBookIsReadForTheFamilyNamesTheClaimsGiveAndTheGuessOnlyForTheRest(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        assertEquals(List.of("遠藤", "森田"), FamilyFolder.familyNames(store),
                "the family parts of the name claims and the family's name; the given name and the kana of a claimed name are no family names");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "child-of", "John Hale", "", "Tom Hale was John Hale's son.")), List.of()),
                "file:///notes.txt", "an aunt");
        assertEquals(List.of("遠藤", "森田", "Hale"), FamilyFolder.familyNames(store), "a name no claim splits is still guessed from the people who share it");
    }

    private static String names(List<String[]> sites) { return String.join("\n", sites.stream().map(x -> x[0] + " " + x[2]).toList()); }
}
