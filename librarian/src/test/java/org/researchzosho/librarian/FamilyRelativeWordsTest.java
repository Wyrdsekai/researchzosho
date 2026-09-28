package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A relative the account does not name is written with the account's own words for them: "my grandmother" is "<teller>'s grandmother". The
 * 27B copied the prompt's old example word for word and filed Mary Ellis as the child of "the writer of hale-letter.txt's mother's father"
 * from "Notes on my great-grandmother". A person written only by relation words is kept only when those words stand in the fact's quote:
 * each step's word (mother and father), or one word for all of it (grandfather). Otherwise the fact is left out, and the read says so.
 * Invented names only.
 */
class FamilyRelativeWordsTest {

    static final String TITLE = "Notes on my great-grandmother, written from the family Bible and her letters.";
    static final String BORN = "Mary Ellis was born Mary Hale in York in 1850.";

    @Test
    void thePromptShowsTheAccountsOwnWordsForARelativeAndNoChainToCopy() {
        String p = FamilyAccount.prompt("text", "the writer of notes.txt", new GenealogyProfile().predicates());
        assertTrue(p.contains("A relative the account does not name is written with the account's own words for them, after the person they belong to: \"my grandmother\" is \"the writer of notes.txt's grandmother\"."), p);
        assertTrue(p.contains("A relative the account names is written by that name"), p);
        assertFalse(p.contains("mother's father"), "the example the model copied word for word is gone");
    }

    @Test
    void aRelativeMadeUpFromWordsTheQuoteDoesNotCarryIsLeftOutAndSaidSo(@TempDir Path tmp) throws Exception {
        String reply = """
                {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "also": ["Mary Hale"]}],
                 "facts": [{"subject": "Mary Ellis", "relation": "born-in", "object": "York", "date": "1850", "quote": "%s"},
                           {"subject": "Mary Ellis", "relation": "child-of", "object": "the writer of hale-letter.txt's mother's father", "date": "", "quote": "%s"}]}
                """.formatted(BORN, TITLE);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(TITLE + "\n\n" + BORN + "\n", reply);
        assertTrue(r.facts().stream().noneMatch(f -> f.object().startsWith("the writer of")), r.facts().toString());
        assertTrue(r.dropped().contains("\"Mary Ellis is a child of the writer of hale-letter.txt's mother's father\" was left out, because the words given for it (\""
                + Acquisitions.compress(TITLE, 60) + "\") do not speak of a mother's father."), r.dropped().toString());
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.file(store, r, "file:///family/hale-letter.txt", "the writer of hale-letter.txt");
        Graph g = FamilyPeople.view(store);
        assertTrue(g.nodes().stream().noneMatch(n -> n.label().startsWith("the writer of")), "my great-grandmother is Mary Ellis, no new person: "
                + g.nodes().stream().map(Graph.Node::label).toList());
    }

    @Test
    void aRelativeWrittenWithTheQuotesOwnWordsIsKept() {
        String grand = "My grandfather was born in York in 1820.", steps = "Mary's mother's father was a printer.", one = "Mary's grandfather lived in York.", ja = "祖父は1820年に広島で生まれた。";
        String reply = """
                {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary"}],
                 "facts": [{"subject": "the writer of notes.txt's grandfather", "relation": "born-in", "object": "York", "date": "1820", "quote": "%s"},
                           {"subject": "Mary Ellis's mother's father", "relation": "occupation", "object": "printer", "date": "", "quote": "%s"},
                           {"subject": "Mary Ellis's mother's father", "relation": "lived-in", "object": "York", "date": "", "quote": "%s"},
                           {"subject": "the writer of notes.txt's father's father", "relation": "born-in", "object": "広島", "date": "1820", "quote": "%s"}]}
                """.formatted(grand, steps, one, ja);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(grand + " " + steps + " " + one + "\n\n" + ja + "\n", reply);
        assertTrue(r.facts().stream().anyMatch(f -> f.subject().equals("the writer of notes.txt's grandfather")), "the account's own word: " + r.facts() + r.dropped());
        assertTrue(r.facts().stream().anyMatch(f -> f.subject().equals("Mary Ellis's mother's father") && f.relation().equals("occupation")), "each step's word: " + r.dropped());
        assertTrue(r.facts().stream().anyMatch(f -> f.subject().equals("Mary Ellis's mother's father") && f.relation().equals("lived-in")), "one word for both steps: " + r.dropped());
        assertTrue(r.facts().stream().anyMatch(f -> f.subject().equals("the writer of notes.txt's father's father")), "祖父: " + r.dropped());
    }

    static final String NOTES = "the writer of notes.txt";

    static FamilyAccount.Read readNotes(String text, String reply, FamilyAccount.YesNo judge) {
        return FamilyAccount.read(text, NOTES, new GenealogyProfile().predicates(), prompt -> reply, List.of(), null, judge);
    }

    static boolean has(FamilyAccount.Read r, String subject, String relation) {
        return r.facts().stream().anyMatch(f -> f.subject().equals(subject) && f.relation().equals(relation));
    }

    static String unsaid(String label, String quote) { return FamilyAccount.relationUnsaid(new FamilyAccount.Fact(NOTES + "'s " + label, "born-in", "York", "", quote)); }

    /**
     * f-prompt-1: the relation words must stand in the quote of one fact about the relative, not of every one. The sentences after the first
     * say "she", "he" or, in Japanese, nothing at all, and their facts are the relative's too.
     */
    @Test
    void laterSentencesThatSaySheHeOrNothingKeepTheirFacts() {
        String en = "My grandmother was born in Cork in 1880. She came to Leeds in 1900 and worked in a mill. She died in Leeds in 1950.";
        String g = NOTES + "'s grandmother";
        FamilyAccount.Read r = readNotes(en + "\n", """
                {"people": [{"name": "%1$s"}],
                 "facts": [{"subject": "%1$s", "relation": "born-in", "object": "Cork", "date": "1880", "quote": "My grandmother was born in Cork in 1880."},
                           {"subject": "%1$s", "relation": "migrated-to", "object": "Leeds", "date": "1900", "quote": "She came to Leeds in 1900 and worked in a mill."},
                           {"subject": "%1$s", "relation": "occupation", "object": "mill worker", "date": "", "quote": "She came to Leeds in 1900 and worked in a mill."},
                           {"subject": "%1$s", "relation": "died-in", "object": "Leeds", "date": "1950", "quote": "She died in Leeds in 1950."}]}
                """.formatted(g), null);
        for (String rel : List.of("born-in", "migrated-to", "occupation", "died-in")) assertTrue(has(r, g, rel), rel + " is the grandmother's: " + r.facts() + " " + r.dropped());

        String ja = "祖父は明治30年に甲府で生まれた。大正3年に東京へ移り、昭和25年に亡くなった。";
        String gf = NOTES + "'s grandfather";
        r = readNotes(ja + "\n", """
                {"people": [{"name": "%1$s"}],
                 "facts": [{"subject": "%1$s", "relation": "born-in", "object": "甲府", "date": "明治30年", "quote": "祖父は明治30年に甲府で生まれた。"},
                           {"subject": "%1$s", "relation": "migrated-to", "object": "東京", "date": "大正3年", "quote": "大正3年に東京へ移り、昭和25年に亡くなった。"},
                           {"subject": "%1$s", "relation": "died-on", "object": "昭和25年", "date": "昭和25年", "quote": "大正3年に東京へ移り、昭和25年に亡くなった。"}]}
                """.formatted(gf), null);
        for (String rel : List.of("born-in", "migrated-to", "died-on")) assertTrue(has(r, gf, rel), rel + ", a sentence with no subject: " + r.facts() + " " + r.dropped());

        String son = "Tom Ellis's son went to sea in 1901. He died at Hull in 1930.";
        r = readNotes(son + "\n", """
                {"people": [{"name": "Tom Ellis", "family": "Ellis", "given": "Tom"}, {"name": "Tom Ellis's son"}],
                 "facts": [{"subject": "Tom Ellis's son", "relation": "child-of", "object": "Tom Ellis", "date": "", "quote": "Tom Ellis's son went to sea in 1901."},
                           {"subject": "Tom Ellis's son", "relation": "died-in", "object": "Hull", "date": "1930", "quote": "He died at Hull in 1930."}]}
                """, null);
        assertTrue(has(r, "Tom Ellis's son", "died-in"), "he: " + r.facts() + " " + r.dropped());
    }

    /** f-prompt-2: a kin word counts only standing alone, never as a part of a longer one: a great-grandmother is not a grandmother. */
    @Test
    void aKinWordThatIsPartOfALongerOneDoesNotCount() {
        String[][] other = {
                {"grandmother", "My great-grandmother was born in York in 1850."}, {"grandmother", "My great grandmother was born in York in 1850."},
                {"great-grandmother", "My great-great-grandmother was born in York in 1800."}, {"mother", "My step-mother was born in York."},
                {"mother", "My mother-in-law was born in York."}, {"mother", "My god-mother was born in York."}, {"father", "My father-in-law was born in York."},
                {"son", "My step-son was born in York."}, {"grandmother", "曾祖母は大正3年に生まれた。"}, {"grandfather", "高祖父は明治3年に生まれた。"},
                {"aunt", "大叔母は明治30年に生まれた。"}, {"grandson", "曾孫は2001年に生まれた。"}, {"daughter", "孫娘は大正3年に生まれた。"},
                {"child", "花子は大正3年に甲府で生まれた。"}, {"wife", "兄嫁は大正3年に甲府で生まれた。"}, {"brother", "兄嫁は大正3年に甲府で生まれた。"},
                {"parent", "親戚は甲府に住んでいた。"}};
        for (String[] c : other) assertNotNull(unsaid(c[0], c[1]), c[0] + " is not said by \"" + c[1] + "\"");
        // the title of the real letter, with the model writing the new example's word: left out, and no judge is asked, because the words say another relative
        String reply = """
                {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "also": ["Mary Hale"]}],
                 "facts": [{"subject": "Mary Ellis", "relation": "born-in", "object": "York", "date": "1850", "quote": "%s"},
                           {"subject": "Mary Ellis", "relation": "child-of", "object": "the writer of hale-letter.txt's grandmother", "date": "", "quote": "%s"}]}
                """.formatted(BORN, TITLE);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(TITLE + "\n\n" + BORN + "\n", reply, q -> 1.0);
        assertTrue(r.facts().stream().noneMatch(f -> f.object().startsWith("the writer of")), r.facts().toString());
    }

    /**
     * f-prompt-3: the words for one of a pair count for the pair and the other way round (parents for a father and a mother, a wife for a
     * spouse), one word counts where the quote spells out its chain (my father's mother, a grandmother), and the Japanese words count as well.
     * Words no list knows are put to the judge once, which says whether they speak of that relative.
     */
    @Test
    void theWordsForAPairTheChainAndTheJapaneseWordsCount() {
        String[][] said = {
                {"father", "My parents married in York in 1950."}, {"mother", "My parents married in York in 1950."},
                {"father", "両親は昭和25年に甲府で結婚した。"}, {"mother", "両親は昭和25年に甲府で結婚した。"},
                {"father", "父母は昭和25年に甲府で結婚した。"}, {"mother", "父母は昭和25年に甲府で結婚した。"},
                {"grandfather", "祖父母は明治40年に結婚した。"}, {"grandmother", "祖父母は明治40年に結婚した。"},
                {"grandfather", "My grandparents came from Cork in 1880."}, {"grandmother", "My father's mother was born in York."},
                {"spouse", "My wife was born in York."}, {"wife", "My spouse was born in York."}, {"grandmother", "おばあちゃんは大正3年に生まれた。"},
                {"grandfather", "おじいさんは明治30年に生まれた。"}, {"uncle", "おじさんは東京で生まれた。"}, {"great-uncle", "大叔父は明治30年に生まれた。"},
                {"great-great-grandfather", "高祖父は明治3年に生まれた。"}, {"great-grandson", "曾孫は2001年に生まれた。"}, {"grandfather", "祖父は明治30年に甲府で生まれた。"}};
        for (String[] c : said) assertNull(unsaid(c[0], c[1]), c[0] + " is said by \"" + c[1] + "\"");

        String parents = "My parents married in York in 1950.";
        FamilyAccount.Read r = readNotes(parents + "\n", """
                {"people": [{"name": "%1$s's father"}, {"name": "%1$s's mother"}],
                 "facts": [{"subject": "%1$s's father", "relation": "married-to", "object": "%1$s's mother", "date": "1950", "quote": "%2$s"}]}
                """.formatted(NOTES, parents), null);
        assertTrue(has(r, NOTES + "'s father", "married-to"), "my parents: " + r.dropped());

        String de = "Mein Großvater wurde 1880 geboren.";
        String reply = """
                {"people": [{"name": "%1$s's grandfather"}],
                 "facts": [{"subject": "%1$s's grandfather", "relation": "born-on", "object": "1880", "date": "1880", "quote": "%2$s"}]}
                """.formatted(NOTES, de);
        assertTrue(has(readNotes(de + "\n", reply, q -> q.contains("grandfather") && q.contains(de) ? 1.0 : 0.0), NOTES + "'s grandfather", "born-on"), "the judge says the words speak of him");
        FamilyAccount.Read no = readNotes(de + "\n", reply, q -> 0.0);
        assertFalse(has(no, NOTES + "'s grandfather", "born-on"), "the judge says they do not: " + no.facts());
        assertTrue(no.dropped().stream().anyMatch(d -> d.contains("do not speak of a grandfather")), no.dropped().toString());
    }

    /** f-married-relation-informal: the everyday words for a parent or a grandparent count for the word the model writes. */
    @Test
    void theEverydayWordsCount() {
        String[][] said = {
                {"mother", "Mum was born in York in 1920."}, {"mother", "My mum was born in York in 1920."}, {"father", "Dad was a printer all his life."},
                {"grandmother", "Granny was born in Leeds."}, {"grandmother", "Grandma Hale was born in Leeds."}, {"grandmother", "おばあちゃんは甲府で生まれた。"},
                {"grandfather", "じいちゃんは甲府で生まれた。"}};
        for (String[] c : said) assertNull(unsaid(c[0], c[1]), c[0] + " is said by \"" + c[1] + "\"");
    }

    /**
     * f-prompt-4: a label with a word that says which side (maternal, paternal) or a plural is a relative written by relation words too, and
     * checked; the prompt asks for the relation words in English, the words the library knows a relative by.
     */
    @Test
    void aLabelWithASideOrAPluralIsCheckedAndThePromptAsksForEnglishWords() {
        assertEquals(List.of("grandfather"), FamilyAccount.relationWords("Tom Ellis's maternal grandfather"));
        assertEquals(List.of("grandmother"), FamilyAccount.relationWords(NOTES + "'s paternal grandmother"));
        assertEquals(List.of("parent"), FamilyAccount.relationWords("Tom Ellis's parents"));
        String p = FamilyAccount.prompt("text", NOTES, new GenealogyProfile().predicates());
        assertTrue(p.contains("The relation words are English words, whatever the language of the account: 祖母 is grandmother, 父 is father."), p);
        String reply = """
                {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "also": ["Mary Hale"]}],
                 "facts": [{"subject": "Mary Ellis", "relation": "born-in", "object": "York", "date": "1850", "quote": "%s"},
                           {"subject": "Mary Ellis", "relation": "child-of", "object": "the writer of hale-letter.txt's maternal grandfather", "date": "", "quote": "%s"}]}
                """.formatted(BORN, TITLE);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(TITLE + "\n\n" + BORN + "\n", reply);
        assertTrue(r.facts().stream().noneMatch(f -> f.object().startsWith("the writer of")), r.facts().toString());
    }

    @Test
    void theRelationWordsOfALabel() {
        assertEquals(List.of("mother", "father"), FamilyAccount.relationWords("the writer of hale-letter.txt's mother's father"));
        assertEquals(List.of("son"), FamilyAccount.relationWords("Endo's son"));
        assertEquals(List.of("great-grandmother"), FamilyAccount.relationWords("the writer of notes.txt's great-grandmother"));
        assertEquals(List.of("son"), FamilyAccount.relationWords("森田勇's eldest son"));
        assertEquals(List.of(), FamilyAccount.relationWords("Mary Ellis"));
        assertEquals(List.of(), FamilyAccount.relationWords("John Ellis (his son)"), "a bracket that tells two people apart is no relative written by relation");
        assertEquals("grandfather", FamilyAccount.oneWord(List.of("mother", "father")));
        assertEquals("great-grandmother", FamilyAccount.oneWord(List.of("father", "mother", "mother")));
        assertEquals("uncle", FamilyAccount.oneWord(List.of("father", "brother")));
        assertNull(FamilyAccount.oneWord(List.of("wife", "father")));
    }

    @Test
    void aRelativeWrittenOnlyByRelationWordsIsNeverTakenForANameToSearch() {
        // the labels a read makes for a relative the account does not name, in the forms the model writes them: never searched as a name
        for (String label : List.of("森田健二's 父", "Tom Ellis's maternal grandfather", "Tom Ellis's step-mother", "Tom Ellis's parents", "Tom Ellis's children",
                "Tom Ellis's eldest son", "Tom Ellis's half-brother", "Tom Ellis's mother-in-law", "the writer of notes.txt's おばあちゃん"))
            assertTrue(FamilyQuestions.placeholder(label), label);
        for (String name : List.of("Tom Ellis", "森田健二", "Mary Hale", "遠藤正一", "Endō Shōichi", "ハル"))
            assertFalse(FamilyQuestions.placeholder(name), name);
    }
}
