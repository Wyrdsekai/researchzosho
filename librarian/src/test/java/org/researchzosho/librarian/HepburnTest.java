package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reading in kana as a name is spelt in Latin letters (0.7.5): what a comic's translator is given beside a printed reading, and what a
 * name's rendering is held to. A long o and a long u short, as readers of a name expect; a long mark in a kanji's reading after the e
 * row as ei; and the two spellings a program cannot tell apart from kana both taken.
 */
class HepburnTest {
    @Test void aNameIsSpeltAsItsReadersExpect() {
        assertEquals("Saito", Hepburn.of("さいとう", true));
        assertEquals("Teito", Hepburn.of("ていと", true));
        assertEquals("Ono", Hepburn.of("おおの", true));
        assertEquals("Yuko", Hepburn.of("ゆうこ", true));
        assertEquals("Kyoko", Hepburn.of("きょうこ", true));
        assertEquals("Koichi", Hepburn.of("こういち", true));
        assertEquals("Junichiro", Hepburn.of("じゅんいちろう", true));
        assertEquals("Shinichi", Hepburn.of("しんいち", true));
        assertEquals("Hattori", Hepburn.of("はっとり", true));
        assertEquals("Matcha", Hepburn.of("まっちゃ", true));
        assertEquals("Tokyo", Hepburn.of("トウキョウ", true), "katakana as hiragana");
        assertEquals("Inoue", Hepburn.of("いのうえ", true), "an う before え after the o row is its own syllable, not a long o");
        assertEquals("Onoue", Hepburn.of("おのうえ", true));
        assertEquals("", Hepburn.of("", true)); assertEquals("", Hepburn.of("123", true)); assertEquals("", Hepburn.of(null, false));
    }

    @Test void aLongMarkInAKanjisReadingAfterTheERowIsEi() {
        assertEquals("Teidai", Hepburn.of("テーダイ", true), "the reading printed beside 帝大: えい, written with a long mark");
        assertEquals("Tedai", Hepburn.of("テーダイ", false), "beside a word written in kana the mark only lengthens");
        assertEquals("Kohi", Hepburn.of("コーヒー", false));
        assertEquals("Toru", Hepburn.of("トール", true), "after the o row the long vowel is written short, as おう is");
        assertEquals("Fan", Hepburn.of("ファン", false)); assertEquals("Vikutoru", Hepburn.of("ヴィクトル", false)); assertEquals("Che", Hepburn.of("チェ", false));
        assertEquals("Satou", Hepburn.letterByLetter("さとう")); assertEquals("Oono", Hepburn.letterByLetter("おおの")); assertEquals("Tedai", Hepburn.letterByLetter("テーダイ"));
    }

    @Test void aRenderingHoldsAReadingInEitherSpellingAndInTwoPartsInEitherOrder() {
        assertTrue(Hepburn.held("Dr. Saito", "さいとう", true));
        assertTrue(Hepburn.held("Saitou-sensei", "さいとう", true), "the letter-by-letter spelling is never refused for the short one");
        assertTrue(Hepburn.held("Saitō", "さいとう", true), "a macron is the same letter");
        assertTrue(Hepburn.held("Teito University", "ていと", true));
        assertTrue(Hepburn.held("Teidai", "テーダイ", true)); assertTrue(Hepburn.held("Tedai", "テーダイ", true));
        assertTrue(Hepburn.held("Hideo Saito", "さいとうひでお", true), "a family name and a given name turned round");
        assertTrue(Hepburn.held("Inoue", "いのうえ", true));
        assertFalse(Hepburn.held("Yamada", "さいとう", true));
        assertFalse(Hepburn.held("Eternal University", "ていと", true), "a name translated by its meaning does not hold its reading");
        assertFalse(Hepburn.held("anything", "", true));
    }
}
