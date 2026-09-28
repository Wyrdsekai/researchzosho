package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An adoption by a parent whose family part a person carries in a name is the entry into that family: the name was taken on the adoption,
 * worked out, and nothing asks how the person came into the family. A step-parent's family name is not asked about either. An entry
 * written by a name alone (a book's "Morita") is never "another person of that name" beside a whole name.
 */
class FamilyAdoptionEntersFamilyTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    private static List<FamilyNameQuestions.Question> about(LibraryStore store, String kind, String person) throws Exception {
        String id = FamilyPeople.view(store).nodeIdOf(person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind) && q.people().contains(id)).toList();
    }

    @Test
    void anAdoptionByAParentOfTheNamesFamilyPartIsTheEntryIntoTheFamilyAndNothingIsAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String reg = "森田健二、1932年森田勇の婿養子となり、森田ハルと結婚。";
        FamilyNameKindFromWordsTest.file(store, "file:///family/register.txt", List.of(fact("森田健二", "adopted-by", "森田勇", "1932", reg), fact("森田健二", "married-to", "森田ハル", "1932", reg),
                        fact("森田健二", "sex", "male", "", reg), fact("森田勇", "sex", "male", "", reg), fact("森田ハル", "sex", "female", "", reg)),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "旧姓", "1905", "森田健二（旧姓 遠藤健二）は1905年生。")));
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Name now = FamilyNameHistory.of(g).names(g.nodeIdOf("森田健二")).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", now.kind(), "taken on the adoption: " + now);
        assertTrue(now.workedOut() && now.from() != null && now.from().year() == 1932, "dated by the adoption: " + now);
        assertEquals(List.of(), about(store, "name-change-how", "森田健二"), "the adoption is the entry into the 森田 family: nothing to ask");
    }

    @Test
    void anAdoptionByAParentOfAnotherFamilyPartStillAsksHowThePersonCameIntoThatFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String reg = "森田健二、1932年高橋勇の婿養子となり、高橋ハルと結婚。";
        FamilyNameKindFromWordsTest.file(store, "file:///family/register.txt", List.of(fact("森田健二", "adopted-by", "高橋勇", "1932", reg), fact("森田健二", "married-to", "高橋ハル", "1932", reg),
                fact("森田健二", "sex", "male", "", reg), fact("高橋勇", "sex", "male", "", reg)), List.of());
        List<FamilyNameQuestions.Question> how = about(store, "name-change-how", "森田健二");
        assertEquals(1, how.size(), how.toString());
        assertTrue(how.get(0).text().contains("come into the 高橋 family") || how.get(0).text().contains("come into"), "no name of his carries 高橋: how he came into the family is the family's to say: " + how.get(0).text());
    }

    @Test
    void aStepParentsFamilyNameIsNotAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "森田勇は森田健二の継父である。";
        FamilyNameKindFromWordsTest.file(store, "file:///family/notes.txt", List.of(fact("森田勇", "step-parent-of", "森田健二", "", q), fact("森田勇", "sex", "male", "", q), fact("森田健二", "sex", "male", "", q)),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "旧姓", "1905", "森田健二（旧姓 遠藤健二）は1905年生。")));
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Name now = FamilyNameHistory.of(g).names(g.nodeIdOf("森田健二")).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("unknown", now.kind(), "not worked out: a step-parent gives no name by itself");
        assertEquals(List.of(), about(store, "name-change-how", "森田健二"), "but how the name came is what a step-parent is: nothing is asked");
    }

    @Test
    void anEntryWrittenByANameAloneIsNeverAnotherPersonOfAWholeName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        // a book writes somebody as "Morita" alone; the same book writes the priest as "Father Kenji Morita", which the library filed on that entry
        FamilyNameKindFromWordsTest.file(store, "file:///family/book.txt", List.of(fact("Morita", "occupation", "shopkeeper", "", "Old Morita kept the shop by the river.")), List.of());
        Graph.alias(store, "Morita", List.of("Father Kenji Morita"));
        String q = "Father Kenji Morita's account of the mission";
        FamilyNameKindFromWordsTest.file(store, "file:///family/mission.txt", List.of(fact("Kenji Morita", "lived-in", "Leeds", "", "Kenji Morita lived in Leeds.")),
                List.of(new FamilyAccount.NameRead("Kenji Morita", "Father Kenji Morita", "Morita", "Kenji", List.of(), "unknown", "", "", q)));
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("Morita"), g.nodeIdOf("Father Kenji Morita"), "the written form leads to the entry of the name alone");
        assertEquals(List.of(), about(store, "one-person", "Kenji Morita"), "no question whether Kenji Morita is \"Morita\": that entry is what the book wrote by the word");
    }
}
