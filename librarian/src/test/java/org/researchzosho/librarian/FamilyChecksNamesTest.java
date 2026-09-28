package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checks about names: one given name under two family names with something that agrees is "one person under two family names?", a
 * record under a name the person did not carry then is "name-at-date", and two heads of a family who carried its hereditary head name are
 * two men, never "same-person?".
 */
class FamilyChecksNamesTest {

    @Test
    void oneGivenNameUnderTwoFamilyNamesIsItsOwnKindAndTheFamilyIsSentToTheQuestions(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/letters.txt", List.of(
                FamilyNameQuestionsTest.fact("Kenji Endo", "child-of", "Shoichi Endo", "", "Kenji Endo, son of Shoichi Endo"),
                FamilyNameQuestionsTest.fact("Kenji Morita", "child-of", "Shoichi Endo", "", "Kenji Morita, who was a son of Shoichi Endo")), List.of());
        List<FamilyChecks.Problem> ps = FamilyChecks.check(store);
        FamilyChecks.Problem p = ps.stream().filter(x -> x.kind().equals("one-person?")).findFirst().orElse(null);
        assertNotNull(p, FamilyChecks.render(ps));
        assertTrue(p.text().contains("“Kenji Endo” and “Kenji Morita” carry one given name under two family names.") && p.text().contains("researchzosho genealogy who asks it"), p.text());
        String said = FamilyChecks.forPerson(store, ps);
        assertTrue(said.contains("ONE PERSON UNDER TWO FAMILY NAMES? (1)"), said);
        // answered "two people": the check says it no more
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, q.code(), "two", "", "Ann");
        assertTrue(FamilyChecks.check(store).stream().noneMatch(x -> x.kind().equals("one-person?")));
    }

    @Test
    void twoHeadsWhoCarriedTheFamilysHereditaryNameAreTwoMen(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "森田家の当主は代々、森田勇を名乗った。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                new FamilyAccount.Fact("森田勇 (head from 1875)", "member-of", "森田家", "1875", q, Map.of("role", "head", "how", "succession", "from", "1875")),
                new FamilyAccount.Fact("森田勇 (head from 1910)", "member-of", "森田家", "1910", q, Map.of("role", "head", "how", "succession", "from", "1910"))), List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("森田家", "森田勇", "森田", "勇", List.of(), "hereditary", "", "", q)), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))),
                "file:///family/book.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertEquals("森田勇", FamilyHouses.hereditaryName(g, g.nodeIdOf("森田 family")), FamilyHouses.all(g).toString());
        List<FamilyChecks.Problem> ps = FamilyChecks.check(store);
        assertTrue(ps.stream().anyMatch(p -> p.kind().equals("same-name") && p.text().contains("the hereditary head name of the 森田 family") && p.text().contains("they became its heads in 1875 and in 1910")), FamilyChecks.render(ps));
        assertTrue(ps.stream().noneMatch(p -> p.kind().equals("same-person?")), "the same name is not the same man: " + FamilyChecks.render(ps));
    }

    @Test
    void aRecordUnderANameNotCarriedThenIsListedAndGroupedForAPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        FamilyNameQuestionsTest.name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年、森田家に婿養子として入った。")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(FamilyNameQuestionsTest.fact("森田健二", "lived-in", "広島", "1920", "森田健二 広島 1920")), List.of()), "a register", f -> List.of("cite:register of 1920, p. 4"), f -> List.of());
        List<FamilyChecks.Problem> ps = FamilyChecks.check(store);
        FamilyChecks.Problem p = ps.stream().filter(x -> x.kind().equals("name-at-date")).findFirst().orElse(null);
        assertNotNull(p, FamilyChecks.render(ps));
        assertTrue(p.text().endsWith("The record is kept as it is. researchzosho genealogy who asks the family which is right. [question " + FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-at-date")).findFirst().orElseThrow().code() + "]"), p.text());
        assertEquals(1, p.text().split("The record is kept as it is\\.", -1).length - 1, "said once: " + p.text());
        assertTrue(FamilyChecks.forPerson(store, ps).contains("RECORDS WRITTEN UNDER A NAME THE PERSON DID NOT CARRY THEN (1)"));
    }
}
