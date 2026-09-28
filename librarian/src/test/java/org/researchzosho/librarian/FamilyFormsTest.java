package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The written forms of one name: every romaji spelling, both orders, old and new characters, and scripts meet only through a reading a source gave. */
class FamilyFormsTest {

    @Test
    void aLongVowelIsWrittenEveryWayARecordWritesIt() {
        assertEquals(List.of("Endō", "Endo", "Endou", "Endoh", "Endoo"), FamilyForms.spellings("Endō"));
        assertEquals("endō kenji", FamilyForms.hepburn("えんどう けんじ"));
        assertEquals("ōsaka", FamilyForms.hepburn("おおさか"));
        assertEquals("yūbin", FamilyForms.hepburn("ユウビン"), "katakana read as kana");
        assertEquals("matcha", FamilyForms.hepburn("まっちゃ"));
        List<String> r = FamilyForms.romaji("えんどう けんじ");
        assertTrue(r.contains("endo kenji") && r.contains("endou kenji") && r.contains("endoh kenji"), r.toString());
        for (String spelt : List.of("Endo", "Endou", "Endoh", "Endoo", "ENDŌ")) assertEquals("endo", FamilyForms.latinKey(spelt), spelt);
        assertEquals("ohayo", FamilyForms.latinKey("Ōhayō"));
        assertEquals(FamilyForms.latinKey("Ohayou"), FamilyForms.latinKey("Oohayoo"), "oh before a vowel is a syllable of its own: Ohayou is Ōhayō");
    }

    @Test
    void bothOrdersAndEveryFormOfOneName() {
        List<String> forms = FamilyForms.forms("遠藤健二", List.of("えんどう けんじ", "Endō Kenji"));
        for (String f : List.of("遠藤健二", "えんどう けんじ", "Endō Kenji", "Endo Kenji", "Kenji Endo", "Kenji Endō", "Endou Kenji", "Kenji Endoh"))
            assertTrue(forms.contains(f), f + " is among " + forms);
        assertTrue(FamilyForms.forms("高橋正一", List.of()).contains("髙橋正一"), "the old characters are a form too");
        assertTrue(FamilyForms.forms("髙橋正一", List.of()).contains("高橋正一"), "and the modern ones");
        assertEquals("Kenji Morita", FamilyForms.otherOrder("Morita, Kenji"));
    }

    @Test
    void aRomajiFormNeverBecomesAFormInCharacters() {
        for (String f : FamilyForms.forms("Endo Kenji", List.of("Kenji Endou"))) assertNotEquals("han", FamilyForms.script(f), f);
        assertFalse(FamilyForms.sameForm("遠藤", "Endo"), "no reading is worked out for characters: 遠藤 and 円藤 are both えんどう");
        assertFalse(FamilyForms.sameForm("遠藤健二", "えんどう けんじ"));
    }

    @Test
    void formsMeetAcrossScriptsOnlyThroughAReadingASourceGave() {
        assertTrue(FamilyForms.sameForm("髙橋", "高橋"), "old and new characters are one family name");
        assertTrue(FamilyForms.sameForm("もりた けんじ", "モリタケンジ"), "hiragana and katakana, with or without the space");
        assertTrue(FamilyForms.sameForm("Morita Kenji", "Kenji Morita"), "both orders");
        assertTrue(FamilyForms.sameForm("もりた けんじ", "Kenji Morita"), "kana meet romaji by rule");
        assertTrue(FamilyForms.sameForm("森田健二", "Morita Kenji", List.of("もりた けんじ")), "characters meet romaji through the reading a source gave");
        assertFalse(FamilyForms.sameForm("森田健二", "Morita Kenji", List.of("えんどう けんじ")), "and only through that reading");
        assertFalse(FamilyForms.sameForm("遠藤健二", "森田健二"), "two names are two names");
        assertFalse(FamilyForms.sameForm("Endo Kenji", "Morita Kenji"));
    }

    /** names-5: two forms written alike in any script are forms of one name: Cyrillic, Hangul, Greek as much as Latin letters. */
    @Test
    void aNameInAnyScriptIsAFormOfItself() {
        for (String n : List.of("Хейл", "Мэри Хейл", "메리 헤일", "Μαίρη Χέιλ", "Χέιλ"))
            assertTrue(FamilyForms.sameForm(n, n), n);
        assertTrue(FamilyForms.sameForm("Мэри Хейл", "Хейл  мэри"), "case, spacing and order as for Latin letters");
        assertFalse(FamilyForms.sameForm("Хейл", "Эллис"));
        assertFalse(FamilyForms.sameForm("Хейл", "Hale"), "two scripts meet only through a reading a source gave");
    }

    /** names-6: the romaji long-vowel rule is for romaji: English family names that differ by ou, oo or oh are two names. */
    @Test
    void theLongVowelRuleIsForRomajiNotForEnglishNames() {
        for (String[] p : new String[][]{{"Toom", "Tom"}, {"Joohn", "John"}, {"Moore", "Mohre"}, {"Ruuth", "Ruth"}})
            assertFalse(FamilyForms.sameForm(p[0], p[1]), p[0] + " and " + p[1] + " are two names");
        assertTrue(FamilyForms.sameForm("Endō", "Endou"), "a long vowel written with its mark is romaji, and meets its other spellings");
        assertTrue(FamilyForms.sameForm("えんどう", "Endou"), "and so is a form a kana reading gives");
        assertTrue(FamilyForms.sameForm("Endo", "Endou", true), "a form of a name that has a Japanese form is romaji");
        assertEquals("und-Latn", FamilyForms.lang("Mary Hale"), "a name in Latin letters is not tagged as Japanese by itself");
        assertEquals("ja-Latn", FamilyForms.lang("Endō Kenji", true));
    }

    /** names-12: the Hepburn spellings of ん before a vowel (n' or n-) meet the kana reading and the spelling without the mark. */
    @Test
    void theHepburnApostropheSpellingMeetsItsReading() {
        assertTrue(FamilyForms.sameForm("Gen'in Endō", "えんどう げんいん"));
        assertTrue(FamilyForms.sameForm("Gen-in Endo", "えんどう げんいん"));
        assertTrue(FamilyForms.sameForm("Gen'in Endo", "Genin Endo"));
        assertTrue(FamilyForms.sameForm("Gen’in Endō", "Genin Endou"));
        assertEquals("gen'in", FamilyForms.hepburn("げんいん"), "Hepburn writes ん before a vowel with an apostrophe");
        List<String> r = FamilyForms.romaji("げんいん");
        assertTrue(r.contains("gen'in") && r.contains("genin"), "and a search uses both spellings: " + r);
    }

    /** names-13: every older form of a character is a searchable form, not only the first one. */
    @Test
    void everyOlderFormOfACharacterIsAForm() {
        List<String> forms = FamilyForms.forms("辺", List.of());
        assertTrue(forms.contains("邉") && forms.contains("邊"), forms.toString());
    }
}
