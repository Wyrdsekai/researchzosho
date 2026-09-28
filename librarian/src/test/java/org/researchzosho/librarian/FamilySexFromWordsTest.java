package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A person's sex, from the text's own words. The model gives `sex` with a person; the reader keeps it only where a word that says it (she,
 * her, wife, widow, daughter, 妻, 娘; he, his, husband, son, 夫, 長男) stands in a sentence the model quoted for that person's own facts, the
 * facts whose subject is that person. A sentence with words for both says nothing; words for the other sex elsewhere, or a parent claim's
 * father or son of the other sex, leave it out. Never from a name. The sex is filed as the other sex facts are, so the tree, the questions'
 * words and the GEDCOM export use it. Invented names only.
 */
class FamilySexFromWordsTest {

    static final String TITLE = "Notes on my great-grandmother, written from the family Bible and her letters.";
    static final String BORN = "Mary Ellis was born Mary Hale in York in 1850.";
    static final String FIRST = "She married Tom Ellis, a printer, in York in 1875.";
    static final String TOM = "Tom died in 1886.";
    static final String SECOND = "In 1890 she married John Hart, a widower.";
    static final String RUTH = "Hale's daughter Ruth, Mary's younger sister, never married.";
    static final String TEXT = TITLE + "\n\n" + BORN + " " + FIRST + " " + TOM + " " + SECOND + "\n\n" + RUTH + "\n";

    /** What the model gave: a sex for everybody, as a model does that goes by names too. */
    static final String REPLY = """
            {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "also": ["Mary Hale"], "sex": "female"},
                        {"name": "Tom Ellis", "family": "Ellis", "given": "Tom", "sex": "male"},
                        {"name": "John Hart", "family": "Hart", "given": "John", "sex": "female"},
                        {"name": "Ruth", "family": "Hale", "given": "Ruth", "sex": "female"}],
             "facts": [{"subject": "Mary Ellis", "relation": "born-in", "object": "York", "date": "1850", "quote": "%s"},
                       {"subject": "Mary Ellis", "relation": "married-to", "object": "Tom Ellis", "date": "1875", "quote": "%s"},
                       {"subject": "Tom Ellis", "relation": "occupation", "object": "printer", "date": "", "quote": "%s"},
                       {"subject": "Tom Ellis", "relation": "died-on", "object": "1886", "date": "", "quote": "%s"},
                       {"subject": "Mary Ellis", "relation": "married-to", "object": "John Hart", "date": "1890", "quote": "%s"},
                       {"subject": "Ruth", "relation": "sibling-of", "object": "Mary Ellis", "date": "", "quote": "%s"}]}
            """.formatted(BORN, FIRST, FIRST, TOM, SECOND, RUTH);

    static List<FamilyAccount.Fact> sexes(FamilyAccount.Read r) { return r.facts().stream().filter(f -> f.relation().equals("sex")).toList(); }

    @Test
    void theSexTheModelGivesIsKeptWhereThePersonsOwnSentencesSayIt(@TempDir Path tmp) throws Exception {
        FamilyAccount.Read r = FamilyReaderNamesTest.read(TEXT, REPLY);
        List<FamilyAccount.Fact> sex = sexes(r);
        FamilyAccount.Fact mary = sex.stream().filter(f -> f.subject().equals("Mary Ellis")).findFirst().orElseThrow(() -> new AssertionError("no sex for Mary Ellis: " + sex));
        assertEquals("female", mary.object());
        assertEquals(FIRST, mary.quote(), "the sentence that says it, a fact of her own; the one that also speaks of a widower says nothing");
        assertTrue(sex.stream().anyMatch(f -> f.subject().equals("Ruth") && f.object().equals("female")), "a daughter and a sister: " + sex);
        assertTrue(sex.stream().noneMatch(f -> f.subject().equals("Tom Ellis")), "his own sentences say she, which is Mary; his name says nothing: " + sex);
        assertTrue(sex.stream().noneMatch(f -> f.subject().equals("John Hart")), "\"she married John Hart\" is Mary's sentence, not his: " + sex);

        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.file(store, r, "file:///family/hale-letter.txt", "the writer of hale-letter.txt");
        Graph g = FamilyPeople.view(store);
        var filed = FamilyKin.sexes(g);
        assertEquals("female", FamilyKin.sexOf(filed, g.nodeIdOf("Mary Ellis")));
        assertEquals("female", FamilyKin.sexOf(filed, g.nodeIdOf("Ruth")));
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("sex") && f.triple().subject().equals("Mary Ellis")).findFirst().orElseThrow();
        assertEquals("family-account", claim.writer(), "filed as the other sex facts are");
        String ged = Gedcom.export(store, "Mary Ellis");
        String record = Arrays.stream(ged.split("\n(?=0 )")).filter(x -> x.contains(" INDI") && x.contains("1 NAME Mary ")).findFirst().orElseThrow(() -> new AssertionError(ged));
        assertTrue(record.contains("\n1 SEX F"), "the export writes her sex: " + record);
    }

    @Test
    void wordsForBothSexesOfOnePersonOrAParentWordOfTheOtherSexLeaveItOut() {
        String school = "He went to the village school in York.";
        String reply = """
                {"people": [{"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "sex": "female"}, {"name": "Ann Hale", "family": "Hale", "given": "Ann", "sex": "male"}],
                 "facts": [{"subject": "Mary Ellis", "relation": "married-to", "object": "Tom Ellis", "date": "1875", "quote": "%s"},
                           {"subject": "Mary Ellis", "relation": "lived-in", "object": "York", "date": "", "quote": "%s"},
                           {"subject": "Ann Hale", "relation": "parent-of", "object": "Mary Ellis", "date": "", "quote": "Ann Hale, her mother, came from York."}]}
                """.formatted(FIRST, school);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(FIRST + " " + school + " Ann Hale, her mother, came from York.\n", reply);
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("Mary Ellis")), "she in one sentence of hers, he in another: " + sexes(r));
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("Ann Hale") && f.object().equals("male")), "the parent claim calls Ann a mother: " + sexes(r));
    }

    @Test
    void aSonOrADaughterInAParentsSentenceIsTheChildsWord() {
        String q = "Tom Hale's daughter Mary was born in York in 1880.";
        String reply = """
                {"people": [{"name": "Tom Hale", "family": "Hale", "given": "Tom", "sex": "female"}, {"name": "Mary Hale", "family": "Hale", "given": "Mary", "sex": "female"}],
                 "facts": [{"subject": "Tom Hale", "relation": "parent-of", "object": "Mary Hale", "date": "", "quote": "%s"},
                           {"subject": "Mary Hale", "relation": "born-in", "object": "York", "date": "1880", "quote": "%s"}]}
                """.formatted(q, q);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(q + "\n", reply);
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("Tom Hale")), "the daughter is Mary, and the model's word for Tom is not borne out: " + sexes(r));
        // her daughter's word is filed from the parent claim, as before, and once
        List<FamilyAccount.Fact> filed = sexes(FamilyAccount.withSexes(r));
        assertEquals(1, filed.stream().filter(f -> f.subject().equals("Mary Hale") && f.object().equals("female")).count(), filed.toString());
        assertTrue(filed.stream().noneMatch(f -> f.subject().equals("Tom Hale")), filed.toString());
    }

    @Test
    void japaneseWordsSayItToo() {
        String born = "森田健二は1905年に遠藤正一の次男として生まれた。", work = "彼は森田家の店で働いた。", wife = "ハル（森田ハル）は勇の娘で、1932年に健二と結婚した。";
        String reply = """
                {"people": [{"name": "森田健二", "family": "森田", "given": "健二", "sex": "male"}, {"name": "森田ハル", "family": "森田", "given": "ハル", "sex": "female"},
                            {"name": "遠藤正一", "family": "遠藤", "given": "正一", "sex": "male"}],
                 "facts": [{"subject": "森田健二", "relation": "born-on", "object": "1905", "date": "1905", "quote": "%s"},
                           {"subject": "森田健二", "relation": "occupation", "object": "森田家の店", "date": "", "quote": "%s"},
                           {"subject": "森田ハル", "relation": "married-to", "object": "森田健二", "date": "1932", "quote": "%s"}]}
                """.formatted(born, work, wife);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(born + work + wife + "\n", reply);
        assertTrue(sexes(r).stream().anyMatch(f -> f.subject().equals("森田健二") && f.object().equals("male")), "次男, 彼: " + sexes(r));
        assertTrue(sexes(r).stream().anyMatch(f -> f.subject().equals("森田ハル") && f.object().equals("female")), "娘: " + sexes(r));
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("遠藤正一")), "no sentence of his own: " + sexes(r));
    }

    /**
     * f-married-sex-other-persons-words: a word counts for a person only when it is about them. The "She" of a sentence that names Tom is
     * not Tom's, a wife in a marriage sentence is the other one's, and Tom's sister is Ann.
     */
    @Test
    void anotherPersonsPronounOrKinWordSaysNothingOfThisPerson() {
        String wrong = REPLY.replace("{\"name\": \"Tom Ellis\", \"family\": \"Ellis\", \"given\": \"Tom\", \"sex\": \"male\"}", "{\"name\": \"Tom Ellis\", \"family\": \"Ellis\", \"given\": \"Tom\", \"sex\": \"female\"}");
        assertNotEquals(REPLY, wrong);
        FamilyAccount.Read r = FamilyReaderNamesTest.read(TEXT, wrong);
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("Tom Ellis")), "the She that marries Tom is Mary: " + sexes(r));
        assertTrue(sexes(r).stream().anyMatch(f -> f.subject().equals("Mary Ellis") && f.object().equals("female")), "and it is still hers: " + sexes(r));

        String second = "In 1890 she married John Hart.";
        r = FamilyReaderNamesTest.read(second + "\n", """
                {"people": [{"name": "John Hart", "family": "Hart", "given": "John", "sex": "female"}, {"name": "Mary Ellis", "family": "Ellis", "given": "Mary", "sex": "female"}],
                 "facts": [{"subject": "John Hart", "relation": "married-to", "object": "Mary Ellis", "date": "1890", "quote": "%s"}]}
                """.formatted(second));
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("John Hart")), "the husband as the subject of the marriage: " + sexes(r));

        String sister = "Tom Ellis's sister Ann was born in York in 1852.";
        r = FamilyReaderNamesTest.read(sister + "\n", """
                {"people": [{"name": "Tom Ellis", "family": "Ellis", "given": "Tom", "sex": "female"}, {"name": "Ann Ellis", "family": "Ellis", "given": "Ann", "sex": "female"}],
                 "facts": [{"subject": "Tom Ellis", "relation": "sibling-of", "object": "Ann Ellis", "date": "", "quote": "%s"}]}
                """.formatted(sister));
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("Tom Ellis")), "the sister is Ann: " + sexes(r));

        String born = "勇は1875年に長男として生まれた。", married = "勇は1900年に妻ハルと結婚した。";
        r = FamilyReaderNamesTest.read(born + married + "\n", """
                {"people": [{"name": "森田勇", "family": "森田", "given": "勇", "sex": "male"}, {"name": "森田ハル", "family": "森田", "given": "ハル", "sex": "female"}],
                 "facts": [{"subject": "森田勇", "relation": "born-on", "object": "1875", "date": "1875", "quote": "%s"},
                           {"subject": "森田勇", "relation": "married-to", "object": "森田ハル", "date": "1900", "quote": "%s"}]}
                """.formatted(born, married));
        assertTrue(sexes(r).stream().anyMatch(f -> f.relation().equals("sex") && f.object().equals("male") && f.quote().equals(born)), "長男 is his; the 妻 is ハル: " + sexes(r));
    }

    /** f-married-sex-from-name: a character inside a name is no word about anybody: the 夫 that ends a given name. Invented name. */
    @Test
    void aWordInsideANameSaysNothing() {
        String his = "山田X夫は1930年に生まれた。", hers = "ハルは森田勇の娘として1935年に生まれた。", moved = "ハルは1955年に山田X夫と甲府へ移った。";
        FamilyAccount.Read r = FamilyReaderNamesTest.read(his + hers + moved + "\n", """
                {"people": [{"name": "山田X夫", "family": "山田", "given": "X夫", "sex": "male"}, {"name": "森田ハル", "family": "森田", "given": "ハル", "also": ["ハル"], "sex": "female"},
                            {"name": "森田勇", "family": "森田", "given": "勇"}],
                 "facts": [{"subject": "山田X夫", "relation": "born-on", "object": "1930", "date": "1930", "quote": "%s"},
                           {"subject": "森田ハル", "relation": "born-on", "object": "1935", "date": "1935", "quote": "%s"},
                           {"subject": "森田ハル", "relation": "migrated-to", "object": "甲府", "date": "1955", "quote": "%s"}]}
                """.formatted(his, hers, moved));
        assertTrue(sexes(r).stream().noneMatch(f -> f.subject().equals("山田X夫")), "his only word is his name: " + sexes(r));
        assertTrue(sexes(r).stream().anyMatch(f -> f.object().equals("female") && f.quote().equals(hers)), "娘 is hers, and the 夫 of his name says nothing against it: " + sexes(r));
    }

    /** f-married-daughter-words: a daughter by her birth order, an adopted daughter, a bride, and a son-in-law say a sex as 長男 does. */
    @Test
    void theJapaneseWordsForADaughterAndASonInLawSayIt() {
        assertEquals("female", FamilyKin.sexInWords("ハルは森田勇の長女として生まれた。"));
        assertEquals("female", FamilyKin.sexInWords("ハルは次女として生まれた。"));
        assertEquals("female", FamilyKin.sexInWords("ハルは遠藤家の養女となった。"));
        assertEquals("female", FamilyKin.sexInWords("ハルは森田家に嫁いだ。"));
        assertEquals("male", FamilyKin.sexInWords("健二は森田家の婿となった。"));
        assertEquals("male", FamilyKin.sexInWords("森田勇は三男として生まれた。"));
    }

    @Test
    void aSentenceSaysASexOnlyWithWordsForOneOfTheTwo() {
        assertEquals("female", FamilyKin.sexInWords("She married Tom Ellis, a printer."));
        assertEquals("", FamilyKin.sexInWords("In 1890 she married John Hart, a widower."));
        assertEquals("male", FamilyKin.sexInWords("Mr Hart was a printer."));
        assertEquals("", FamilyKin.sexInWords("Mary Hale was born in York in 1850."), "a name says nothing");
        assertEquals("male", FamilyKin.sexInWords("彼は1905年に生まれた。"));
        assertEquals("female", FamilyKin.sexInWords("彼女は1910年に生まれた。"));
        assertEquals("", FamilyKin.sexInWords("父母は広島に住んだ。"), "父母 is the parents");
        assertEquals("", FamilyKin.sexInWords("健二は弟子を取った。"), "弟子 is a pupil, no brother");
        assertEquals("female", FamilyKin.sexInWords("森田夫人は店を営んだ。"));
    }
}
