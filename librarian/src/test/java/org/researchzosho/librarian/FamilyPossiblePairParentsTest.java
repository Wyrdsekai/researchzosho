package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two parents of one child, of one sex, whom the link pass holds as possibly one person (two names one character apart): their children are
 * not asked which is the birth parent. The pair is asked about once, as one person or two, with the children they share and what the pass
 * found; "one person" joins them as the owner's word. Two parents the pass does not hold as a pair are asked about as before.
 */
class FamilyPossiblePairParentsTest {

    private static final String REGISTER = "file:///family/register.txt", TREE = "https://www.geni.com/people/Kenji-Morita/6000000000000000001";

    private static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    /** Two sources: a register writes the father 森田健二, a tree writes him 森田健治, one character apart, over the same children and years. */
    private static LibraryStore pair(Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        String reg = "森田健二（1848年生、1919年没）の子：一郎、花子。";
        FamilyNameKindFromWordsTest.file(store, REGISTER, List.of(fact("森田一郎", "child-of", "森田健二", "", reg), fact("森田花子", "child-of", "森田健二", "", reg),
                fact("森田健二", "sex", "male", "", reg), fact("森田健二", "born-on", "1848", "", reg), fact("森田健二", "died-on", "1919", "", reg)), List.of());
        String tree = "Geni: 森田健治, born 1848, died 1919; children 森田一郎 and 森田花子.";
        FamilyNameKindFromWordsTest.file(store, TREE, List.of(fact("森田一郎", "child-of", "森田健治", "", tree), fact("森田花子", "child-of", "森田健治", "", tree),
                fact("森田健治", "sex", "male", "", tree), fact("森田健治", "born-on", "1848", "", tree), fact("森田健治", "died-on", "1919", "", tree)), List.of());
        return store;
    }

    private static List<FamilyNameQuestions.Question> kind(LibraryStore store, String kind) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind)).toList();
    }

    @Test
    void twoFathersThePassHoldsAsPossiblyOneManAreAskedAboutOnceAsOnePersonAndNotAsBirthFather(@TempDir Path tmp) throws Exception {
        LibraryStore store = pair(tmp);
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyLinks.current(store).links().stream().anyMatch(l -> !l.joins()), "the pass holds the two as a possible link: " + FamilyLinks.current(store).links());
        assertNotEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("森田健治"), "two people until the family says otherwise");
        assertEquals(List.of(), kind(store, "birth-or-adoptive"), "no child is asked which is the birth father");
        List<FamilyNameQuestions.Question> one = kind(store, "one-person");
        assertEquals(1, one.size(), "the pair, once: " + one);
        FamilyNameQuestions.Question q = one.get(0);
        assertTrue(q.people().containsAll(List.of(g.nodeIdOf("森田健二"), g.nodeIdOf("森田健治"))), q.people().toString());
        assertTrue(q.text().contains("are written as the father of the same 2 children (") && q.text().contains("Are they one person?"), q.text());
        assertTrue(q.text().contains("one character"), "what the pass found: " + q.text());
        assertTrue(q.text().contains("both born in 1848") && q.text().contains("both died in 1919"), "what else agrees: " + q.text());
        assertFalse(q.text().contains("until the family says otherwise"), "the verdict is the family's to give here: " + q.text());
        // "one person" joins them as the owner's word, and nothing is left to ask
        FamilyNameQuestions.answer(store, q.code(), "one", "", "Ann");
        Graph after = FamilyPeople.view(store);
        assertEquals(after.nodeIdOf("森田健二"), after.nodeIdOf("森田健治"), "joined");
        assertEquals(List.of(), FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("one-person") || x.kind().equals("birth-or-adoptive")).toList());
    }

    /** The owner's great-great-grandfather is outside close family; his son, the great-grandfather, is inside it. */
    @Test
    void thePairQuestionIsAskedWhenAChildTheyShareIsCloseFamilyThoughTheTwoAreNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = pair(tmp);
        FamilyCloseFamilyTest.file(store, "told://link-note/https://example.org/ichiro", FamilyCloseFamilyTest.OWNER,
                List.of(fact("森田一郎", "relative-of", FamilyCloseFamilyTest.OWNER, "", "About 森田一郎: my father's father's father")), List.of());
        Graph g = FamilyPeople.view(store);
        FamilyClose.Close close = FamilyClose.of(g);
        assertTrue(close.close(g.nodeIdOf("森田一郎")) && !close.close(g.nodeIdOf("森田健二")) && !close.close(g.nodeIdOf("森田健治")), "the son is close family, the two fathers one generation beyond it");
        List<FamilyNameQuestions.Question> one = kind(store, "one-person");
        assertEquals(1, one.size(), "asked, as the question about the son would have been: " + FamilyNameQuestions.notAsked(store, Set.of(g.nodeIdOf("森田健二"))));
        assertTrue(one.get(0).text().startsWith("森田健二 and 森田健治 are both your father's father's father's father. ") || one.get(0).text().startsWith("森田健治 and 森田健二 are both your father's father's father's father. "), one.get(0).text());
        assertEquals(List.of(), kind(store, "birth-or-adoptive"));
    }

    @Test
    void twoFathersThePassDoesNotHoldAsAPairAreAskedAboutAsBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        FamilyPeople.holdsAFamily(store);
        String reg = "森田健二の長男 一郎。", tree = "Geni: 森田一郎 is a child of 遠藤三郎.";
        FamilyNameKindFromWordsTest.file(store, REGISTER, List.of(fact("森田一郎", "child-of", "森田健二", "", reg), fact("森田健二", "sex", "male", "", reg)), List.of());
        FamilyNameKindFromWordsTest.file(store, TREE, List.of(fact("森田一郎", "child-of", "遠藤三郎", "", tree), fact("遠藤三郎", "sex", "male", "", tree)), List.of());
        assertTrue(FamilyLinks.current(store).links().stream().noneMatch(l -> !l.joins()), "no possible link between two names that share nothing");
        List<FamilyNameQuestions.Question> birth = kind(store, "birth-or-adoptive");
        assertEquals(2, birth.size(), "which of the two is the birth father, asked of each: " + birth);
        assertTrue(birth.stream().allMatch(q -> q.text().contains("has two fathers written as birth parents")), birth.toString());
        assertEquals(List.of(), kind(store, "one-person"));
    }
}
