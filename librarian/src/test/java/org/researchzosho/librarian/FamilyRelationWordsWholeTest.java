package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Relation words count as whole words with their modifiers. A grandfather is no parent: "my mother's grandfather" files no parent-of, and
 * with no grandparent relation in the vocabulary the fact is filed as relative-of with its words. A stepfather, a father-in-law, a godfather,
 * an adoptive or a foster parent file their own relations, never a birth parent. "Her father's younger brother" is a brother of the father,
 * so it files no sibling of a third person. "X's father Y" still files a parent.
 */
class FamilyRelationWordsWholeTest {

    private static FamilyAccount.Read read(String text, String answer) {
        return FamilyAccount.read(text, "the owner of this library", new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : answer);
    }

    private static String fact(String s, String r, String o, String quote) {
        return "{\"subject\": \"" + s + "\", \"relation\": \"" + r + "\", \"object\": \"" + o + "\", \"date\": \"\", \"quote\": \"" + quote + "\"}";
    }

    private static List<String> triples(FamilyAccount.Read r) { return r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList(); }

    @Test
    void theWordsKindsAreWholeWordsWithTheirModifiers() {
        assertEquals("grand", FamilyKin.kinds("Tom Hale - my mother's grandfather. also written T. Hale").instead(), "the mother is a step of the chain, the grandfather no parent");
        assertEquals("", FamilyKin.kinds("Tom Hale - my mother's father").instead(), "the father at the end of the chain is a parent");
        assertEquals("", FamilyKin.kinds("Ann Hale's father Tom Hale kept a shop.").instead());
        assertEquals("step", FamilyKin.kinds("his stepfather Tom Hale").instead());
        assertEquals("step", FamilyKin.kinds("his step father Tom Hale").instead());
        assertEquals("in-law", FamilyKin.kinds("her father-in-law Tom Hale").instead());
        assertEquals("god", FamilyKin.kinds("his godfather Tom Hale").instead());
        assertEquals("adoptive", FamilyKin.kinds("his adoptive father Tom Hale").instead());
        assertEquals("foster", FamilyKin.kinds("her foster mother Ann Hale").instead());
        assertEquals("grand", FamilyKin.kinds("森田勇 - 母の祖父").instead());
        assertEquals("step", FamilyKin.kinds("継父の森田勇").instead());
        assertEquals("in-law", FamilyKin.kinds("義父の森田勇").instead());
        assertEquals("adoptive", FamilyKin.kinds("養父の森田勇").instead());
        assertEquals("", FamilyKin.kinds("his grandfather and his father, Tom Hale").instead(), "a plain parent word beside a grandparent word: the words say a parent too");
        assertEquals("", FamilyKin.kinds("Tom Hale was born in Leeds.").instead(), "no relation word says nothing");
        assertEquals(-1, FamilyKin.siblingWords("Tom Hale - my mother's uncle (her father's younger brother)"));
        assertEquals(-1, FamilyKin.siblingWords("森田勇 - 母の父の弟"));
        assertEquals(-1, FamilyKin.siblingWords("Ned Hale, Ann Hale's mother's sister"));
        assertEquals(1, FamilyKin.siblingWords("Tom Hale - my mother's brother"), "the account's own relative, whom the account may name");
        assertEquals(1, FamilyKin.siblingWords("森田勇 - 母の兄"));
        assertEquals(1, FamilyKin.siblingWords("his younger brother Tom Hale"));
        assertEquals(1, FamilyKin.siblingWords("Tom Hale, her father's cousin, and her brother Ned"), "one brother word stands on its own");
        assertEquals(0, FamilyKin.siblingWords("Tom Hale kept a shop."));
        assertEquals("", FamilyKin.parentByWords("Tom Hale - my mother's grand father", List.of("Tom Hale"), List.of("the owner of this library's mother"), "the owner of this library"), "grand father in two words is no father");
    }

    @Test
    void aGrandfatherFilesNoParentAndKeepsItsWordsAsARelative() {
        String text = "Tom Hale - my mother's grandfather. also written T. Hale\nNed Hale - my mother's uncle (her father's younger brother)\n";
        FamilyAccount.Read r = read(text, "{\"people\": [], \"facts\": [" + fact("Tom Hale", "parent-of", "the owner of this library's mother", "Tom Hale - my mother's grandfather. also written T. Hale")
                + ", " + fact("Ned Hale", "sibling-of", "Tom Hale", "Ned Hale - my mother's uncle (her father's younger brother)") + "]}");
        List<String> t = triples(r);
        assertEquals(List.of("Tom Hale | relative-of | the owner of this library's mother", "Ned Hale | relative-of | Tom Hale"), t);
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("\"Tom Hale is a parent of the owner of this library's mother\" " + FamilyAccount.AS_THE_WORDS_SAY) && d.contains("say a grandparent, which is no parent")), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("\"Ned Hale is a brother or sister of Tom Hale\" " + FamilyAccount.AS_THE_WORDS_SAY) && d.contains("say a brother or sister of a relative")), r.dropped().toString());
        assertTrue(GenealogyProfile.droppedSaid(r.dropped()).contains("with another relation than the model gave, because the text's own words say which relation it is"));
        // the model's own relative-of and a sibling of the described father stay as they are
        FamilyAccount.Read same = read(text, "{\"people\": [], \"facts\": [" + fact("Tom Hale", "relative-of", "the owner of this library's mother", "Tom Hale - my mother's grandfather. also written T. Hale")
                + ", " + fact("Ned Hale", "sibling-of", "the owner of this library's mother's father", "Ned Hale - my mother's uncle (her father's younger brother)") + "]}");
        assertEquals(List.of("Tom Hale | relative-of | the owner of this library's mother", "Ned Hale | sibling-of | the owner of this library's mother's father"), triples(same));
        assertTrue(same.dropped().stream().noneMatch(d -> d.contains(FamilyAccount.AS_THE_WORDS_SAY)), same.dropped().toString());
    }

    @Test
    void aStepAnInLawAGodAnAdoptiveAndAFosterParentFileTheirOwnRelations() {
        String text = "Ann Hale was brought up in Leeds. Her stepfather Tom Hale kept a shop; her father-in-law Ken Ellis was a printer; her godfather Ned Hart was a rector. "
                + "Ruth Hale's adoptive father Isamu Morita farmed at Sendai, and her foster mother Mary Ellis kept the books. 森田ハルの養父森田勇は津に住んだ。";
        FamilyAccount.Read r = read(text, "{\"people\": [], \"facts\": [" + fact("Tom Hale", "parent-of", "Ann Hale", "Her stepfather Tom Hale kept a shop; her father-in-law Ken Ellis was a printer; her godfather Ned Hart was a rector.")
                + ", " + fact("Ann Hale", "child-of", "Ken Ellis", "her father-in-law Ken Ellis was a printer")
                + ", " + fact("Ned Hart", "parent-of", "Ann Hale", "her godfather Ned Hart was a rector")
                + ", " + fact("Isamu Morita", "parent-of", "Ruth Hale", "Ruth Hale's adoptive father Isamu Morita farmed at Sendai")
                + ", " + fact("Ruth Hale", "child-of", "Mary Ellis", "her foster mother Mary Ellis kept the books")
                + ", " + fact("森田勇", "parent-of", "森田ハル", "森田ハルの養父森田勇は津に住んだ。") + "]}");
        List<String> t = triples(r);
        assertTrue(t.stream().noneMatch(x -> x.contains("| parent-of |") || x.contains("| child-of |")), "no birth parent: " + t);
        assertTrue(t.contains("Ken Ellis | parent-in-law-of | Ann Hale"), t.toString());
        assertTrue(t.contains("Ned Hart | godparent-of | Ann Hale"), t.toString());
        assertTrue(t.contains("Ruth Hale | adopted-by | Isamu Morita"), t.toString());
        assertTrue(t.contains("Ruth Hale | foster-child-of | Mary Ellis"), t.toString());
        assertTrue(t.contains("森田ハル | adopted-by | 森田勇"), t.toString());
        assertTrue(t.contains("Tom Hale | relative-of | Ann Hale"), "the first quote names three kinds at once: no parent by birth, and the kind not settled: " + t);
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("Tom Hale is a parent of Ann Hale") && d.contains("more than one kind of relative")), r.dropped().toString());
        assertEquals("several", FamilyKin.kinds("her stepfather Tom and her godfather Ned").instead());
        // one kind alone files that kind
        FamilyAccount.Read one = read("Her stepfather Tom Hale kept a shop.", "{\"people\": [], \"facts\": [" + fact("Tom Hale", "parent-of", "Ann Hale", "Her stepfather Tom Hale kept a shop.") + "]}");
        assertEquals(List.of("Tom Hale | step-parent-of | Ann Hale"), triples(one));
    }

    @Test
    void aPlainParentOrSiblingWordStillFilesAsBefore() {
        String text = "Ann Hale's father Tom Hale kept a shop in Leeds. His younger brother Ned Hale was a printer. Tom Hale - my mother's father. Ken Ellis - my mother's brother. my mother Ruth Ellis was born in 1948.";
        FamilyAccount.Read r = read(text, "{\"people\": [], \"facts\": [" + fact("Tom Hale", "parent-of", "Ann Hale", "Ann Hale's father Tom Hale kept a shop in Leeds.")
                + ", " + fact("Ned Hale", "sibling-of", "Tom Hale", "His younger brother Ned Hale was a printer.")
                + ", " + fact("Tom Hale", "parent-of", "the owner of this library's mother", "Tom Hale - my mother's father.")
                + ", " + fact("Ken Ellis", "sibling-of", "Ruth Ellis", "Ken Ellis - my mother's brother.") + "]}");
        assertEquals(List.of("Tom Hale | parent-of | Ann Hale", "Ned Hale | sibling-of | Tom Hale", "Tom Hale | parent-of | the owner of this library's mother", "Ken Ellis | sibling-of | Ruth Ellis"), triples(r),
                "my mother's brother is a brother of the mother the account names");
        assertTrue(r.dropped().stream().noneMatch(d -> d.contains(FamilyAccount.AS_THE_WORDS_SAY)), r.dropped().toString());
    }
}
