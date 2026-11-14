package org.researchzosho.librarian;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A reading in kana as a name is spelt in Latin letters: Hepburn as a passport writes it, with no macron. A Japanese comic prints the
 * reading of a name beside it (furigana); the translation of one into a language of the Latin alphabet gives the translator this
 * spelling with the reading, and accepts a name's rendering only when it holds it ({@link ComicTranslation.Pair#hepburn}). A comic
 * in another language prints no readings, and a translation into Chinese, Korean, Cyrillic or Greek letters is held to no spelling:
 * nothing here is asked of either.
 *
 * <p>A long o and a long u are written short (さとう Sato, おおの Ono, ゆうこ Yuko), as readers of a name expect; えい stays ei (ていと
 * Teito). A long mark (ー) after a kana of the e row, in the reading of a word written in kanji, is that same えい: the reading テーダイ
 * printed beside 帝大 is Teidai, where a letter-by-letter spelling gives Tedai; beside a word written in kana (a loanword) the mark only
 * lengthens, and is dropped. One thing kana alone cannot say is where a name's parts meet: いのうえ is i-no-ue, not a long o. The one
 * case that is common in names is kept apart (an う after the o row and before え is its own syllable: Inoue, Onoue); for the rest
 * {@link #held} takes a rendering that holds either spelling, the short one or the letter-by-letter one, and the translation's note lists
 * every reading with the spelling given, for a person to settle one that came out wrong.
 */
final class Hepburn {
    private Hepburn() { }

    private static final Map<Character, String> KANA = Map.ofEntries(
            Map.entry('あ', "a"), Map.entry('い', "i"), Map.entry('う', "u"), Map.entry('え', "e"), Map.entry('お', "o"),
            Map.entry('か', "ka"), Map.entry('き', "ki"), Map.entry('く', "ku"), Map.entry('け', "ke"), Map.entry('こ', "ko"),
            Map.entry('さ', "sa"), Map.entry('し', "shi"), Map.entry('す', "su"), Map.entry('せ', "se"), Map.entry('そ', "so"),
            Map.entry('た', "ta"), Map.entry('ち', "chi"), Map.entry('つ', "tsu"), Map.entry('て', "te"), Map.entry('と', "to"),
            Map.entry('な', "na"), Map.entry('に', "ni"), Map.entry('ぬ', "nu"), Map.entry('ね', "ne"), Map.entry('の', "no"),
            Map.entry('は', "ha"), Map.entry('ひ', "hi"), Map.entry('ふ', "fu"), Map.entry('へ', "he"), Map.entry('ほ', "ho"),
            Map.entry('ま', "ma"), Map.entry('み', "mi"), Map.entry('む', "mu"), Map.entry('め', "me"), Map.entry('も', "mo"),
            Map.entry('や', "ya"), Map.entry('ゆ', "yu"), Map.entry('よ', "yo"),
            Map.entry('ら', "ra"), Map.entry('り', "ri"), Map.entry('る', "ru"), Map.entry('れ', "re"), Map.entry('ろ', "ro"),
            Map.entry('わ', "wa"), Map.entry('ゐ', "i"), Map.entry('ゑ', "e"), Map.entry('を', "o"), Map.entry('ん', "n"),
            Map.entry('が', "ga"), Map.entry('ぎ', "gi"), Map.entry('ぐ', "gu"), Map.entry('げ', "ge"), Map.entry('ご', "go"),
            Map.entry('ざ', "za"), Map.entry('じ', "ji"), Map.entry('ず', "zu"), Map.entry('ぜ', "ze"), Map.entry('ぞ', "zo"),
            Map.entry('だ', "da"), Map.entry('ぢ', "ji"), Map.entry('づ', "zu"), Map.entry('で', "de"), Map.entry('ど', "do"),
            Map.entry('ば', "ba"), Map.entry('び', "bi"), Map.entry('ぶ', "bu"), Map.entry('べ', "be"), Map.entry('ぼ', "bo"),
            Map.entry('ぱ', "pa"), Map.entry('ぴ', "pi"), Map.entry('ぷ', "pu"), Map.entry('ぺ', "pe"), Map.entry('ぽ', "po"), Map.entry('ゔ', "vu"));
    /** A kana with a small one after it that makes one sound of a word from abroad: ふぁ fa, てぃ ti, うぃ wi, ゔぁ va, しぇ she… */
    private static final Map<String, String> PAIRS = Map.ofEntries(
            Map.entry("ふぁ", "fa"), Map.entry("ふぃ", "fi"), Map.entry("ふぇ", "fe"), Map.entry("ふぉ", "fo"), Map.entry("てぃ", "ti"), Map.entry("でぃ", "di"), Map.entry("とぅ", "tu"), Map.entry("どぅ", "du"),
            Map.entry("うぃ", "wi"), Map.entry("うぇ", "we"), Map.entry("うぉ", "wo"), Map.entry("ゔぁ", "va"), Map.entry("ゔぃ", "vi"), Map.entry("ゔぇ", "ve"), Map.entry("ゔぉ", "vo"),
            Map.entry("しぇ", "she"), Map.entry("じぇ", "je"), Map.entry("ちぇ", "che"), Map.entry("つぁ", "tsa"), Map.entry("つぃ", "tsi"), Map.entry("つぇ", "tse"), Map.entry("つぉ", "tso"));

    /** Katakana as hiragana, the long mark kept. */
    private static String hiragana(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c);
        return b.toString();
    }

    /** The spelling of a reading, its first letter a capital; {@code ofKanji}: the reading belongs to a word written in kanji, so a long mark after the e row is えい. "" for a reading with no kana in it. */
    static String of(String reading, boolean ofKanji) { return spelt(reading, ofKanji, true); }

    /** The same letter by letter: every う and every お written, a long mark dropped (さとう Satou, おおの Oono, テーダイ Tedai). */
    static String letterByLetter(String reading) { return spelt(reading, false, false); }

    private static String spelt(String reading, boolean ofKanji, boolean shortLongVowels) {
        String s = hiragana(reading == null ? "" : reading.strip());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (c == 'っ') {   // the next consonant doubled; t before ch
                String n = next == 0 ? "" : PAIRS.getOrDefault("" + next + (i + 2 < s.length() ? s.charAt(i + 2) : ' '), KANA.getOrDefault(next, ""));
                if (!n.isEmpty() && "aiueo".indexOf(n.charAt(0)) < 0) out.append(n.startsWith("ch") ? 't' : n.charAt(0));
                continue;
            }
            if (c == 'ー') {   // a long mark: えい in a kanji's reading after the e row; else only length, which is not written
                if (shortLongVowels && ofKanji && out.length() > 0 && out.charAt(out.length() - 1) == 'e') out.append('i');
                continue;
            }
            String pair = PAIRS.get("" + c + next);
            if (pair != null) { out.append(pair); i++; continue; }
            String r = KANA.get(c);
            if (r == null) continue;   // a small kana on its own, a mark, anything that is no kana
            if ((next == 'ゃ' || next == 'ゅ' || next == 'ょ') && r.endsWith("i")) {   // きゃ kya, しゃ sha, じゅ ju
                String base = r.substring(0, r.length() - 1), v = next == 'ゃ' ? "a" : next == 'ゅ' ? "u" : "o";
                out.append(base.equals("sh") || base.equals("ch") || base.equals("j") ? base + v : base + "y" + v);
                i++; continue;
            }
            if (shortLongVowels && out.length() > 0) {
                char last = out.charAt(out.length() - 1);
                if (c == 'う' && last == 'u') continue;                       // ゆうこ Yuko
                if (c == 'う' && last == 'o' && next != 'え') continue;       // さとう Sato; いのうえ stays Inoue
                if (c == 'お' && last == 'o') continue;                       // おおの Ono
            }
            out.append(r);
        }
        if (out.length() == 0) return "";
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    /** Letters only, lower case, accents folded: what two spellings are compared by. */
    static String letters(String s) { return Translation.plainName(s == null ? "" : s).replaceAll("[^a-z]", ""); }

    /**
     * Whether a rendering in Latin letters holds a reading: its spelling whole, the short one or the letter-by-letter one (Sato or Satou,
     * Inoue or Inoe never refused for the other), or the spelling in two parts in either order, as a family name and a given name are
     * set apart or turned round. False for a reading that spells nothing.
     */
    static boolean held(String rendering, String reading, boolean ofKanji) {
        String e = letters(rendering);
        Set<String> spellings = new LinkedHashSet<>();
        for (String r : new String[]{of(reading, ofKanji), letterByLetter(reading), of(reading, !ofKanji)}) { String l = r.toLowerCase(Locale.ROOT); if (!l.isEmpty()) spellings.add(l); }
        for (String r : spellings) {
            if (e.contains(r)) return true;
            for (int k = 2; k < r.length() - 1; k++) if (e.contains(r.substring(0, k)) && e.contains(r.substring(k))) return true;
        }
        return false;
    }
}
