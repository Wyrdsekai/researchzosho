package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name a source writes in kana alone, for a person filed in characters, is a reading of one of their names, never a name of its own: it goes
 * with the name in characters it stands beside in the source's words, or with the name the person is filed under, raises no question of how
 * it came, and two readings of one name are the question of which is right. A kana name of a person filed in kana stays a name.
 */
class FamilyKanaReadingTest {

    private static String id(LibraryStore store, String person) throws Exception { return FamilyPeople.view(store).nodeIdOf(person); }

    private static List<FamilyNameHistory.Name> names(LibraryStore store, String person) throws Exception { return FamilyNameHistory.of(FamilyPeople.view(store)).names(id(store, person)); }

    private static List<FamilyNameQuestions.Question> about(LibraryStore store, String person, String kind) throws Exception {
        String id = id(store, person);
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals(kind) && q.people().contains(id)).toList();
    }

    @Test
    void aReadingInKanaGoesWithTheNameThePersonIsFiledUnderAndTwoReadingsAreTheReadingQuestionOnly(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q1 = "森田健二（もりた けんじ）は1905年に小樽で生まれた。", q2 = "家中の者は健二を「けんぞう」と読んでいた。";
        FamilyNameKindFromWordsTest.file(store, "file:///family/morita.txt", List.of(FamilyNameKindFromWordsTest.fact("森田健二", "born-in", "小樽", q1)),
                List.of(new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of("もりた けんじ"), "unknown", "", "", q1),
                        new FamilyAccount.NameRead("森田健二", "けんぞう", "", "", List.of(), "unknown", "", "", q2)));
        List<FamilyNameHistory.Name> ns = names(store, "森田健二");
        assertEquals(1, ns.size(), "the kana is a reading, not a name of its own: " + ns);
        assertTrue(ns.get(0).texts().containsAll(List.of("森田健二", "もりた けんじ", "けんぞう")), ns.get(0).toString());
        assertEquals(2, ns.get(0).claims().size(), "the reading's claim is one of the name's: " + ns.get(0).claims());
        assertEquals(List.of(), about(store, "森田健二", "name-change-how"), "nothing asks how a reading came");
        List<FamilyNameQuestions.Question> reading = about(store, "森田健二", "reading");
        assertEquals(1, reading.size(), "two readings of one name: which is right");
        assertTrue(reading.get(0).text().contains("もりた けんじ") && reading.get(0).text().contains("けんぞう"), reading.get(0).text());
    }

    @Test
    void aReadingGoesWithTheNameInCharactersItStandsBesideInTheWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q1 = "森田健二は遠藤家に生まれた。旧姓 遠藤健二。", q2 = "遠藤健二（えんどう けんじ）";
        FamilyNameKindFromWordsTest.file(store, "file:///family/book.txt", List.of(FamilyNameKindFromWordsTest.fact("森田健二", "lived-in", "小樽", q1)),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "旧姓", "", q1),
                        new FamilyAccount.NameRead("森田健二", "えんどう けんじ", "", "", List.of(), "unknown", "", "", q2)));
        List<FamilyNameHistory.Name> ns = names(store, "森田健二");
        assertEquals(2, ns.size(), ns.toString());
        FamilyNameHistory.Name endo = ns.stream().filter(n -> n.written().equals("遠藤健二")).findFirst().orElseThrow();
        assertTrue(endo.texts().contains("えんどう けんじ"), "beside 遠藤健二 in the words, so a reading of it: " + endo);
        assertEquals("birth", endo.kind(), "the reading changes nothing else of the name");
        assertFalse(ns.stream().anyMatch(n -> n.written().equals("えんどう けんじ")), ns.toString());
        // how the family name changed from 遠藤 to 森田 is still the family's to say; the reading raises nothing of its own
        assertTrue(about(store, "森田健二", "name-change-how").stream().noneMatch(q -> q.text().contains("えんどう けんじ")), about(store, "森田健二", "name-change-how").toString());
        assertEquals(List.of(), about(store, "森田健二", "reading"), "one reading of each name: nothing to ask");
    }

    @Test
    void aKanaNameOfAPersonFiledInKanaStaysAName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        String q = "妻 アイ（旧姓 遠藤）";
        FamilyNameKindFromWordsTest.file(store, "file:///family/register.txt", List.of(FamilyNameKindFromWordsTest.fact("アイ", "married-to", "森田健二", q)),
                List.of(new FamilyAccount.NameRead("アイ", "アイ", "", "アイ", List.of(), "unknown", "", "", q)));
        List<FamilyNameHistory.Name> ns = names(store, "アイ");
        assertEquals(1, ns.size(), ns.toString());
        assertEquals("アイ", ns.get(0).written());
        assertFalse(ns.get(0).claims().isEmpty(), "her name, with its claim: " + ns.get(0));
        // a religious name in kana, said to be one, is a name of its own for a person filed in characters too
        LibraryStore store2 = FamilyNameKindFromWordsTest.store(tmp.resolve("two"));
        String q2 = "健二の洗礼名はパウロ。";
        FamilyNameKindFromWordsTest.file(store2, "file:///family/parish.txt", List.of(FamilyNameKindFromWordsTest.fact("森田健二", "lived-in", "小樽", "森田健二は小樽に住んだ。")),
                List.of(new FamilyAccount.NameRead("森田健二", "パウロ", "", "", List.of(), "religious", "洗礼名", "", q2)));
        List<FamilyNameHistory.Name> ns2 = names(store2, "森田健二");
        assertTrue(ns2.stream().anyMatch(n -> n.written().equals("パウロ") && n.kind().equals("religious")), "a name the words call a religious name is a name: " + ns2);
    }
}
