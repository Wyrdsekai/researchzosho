package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** How names are written in Latin letters, by the rules of their language: rule files, not a list of names. */
class NameSpellingsTest {

    @AfterEach
    void forget() { NameSpellings.forget(); }

    /** The romaji key as the program computed it before the rules moved to a file: the Japanese file must give the same key. */
    private static String oldLatinKey(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        n = Normalizer.normalize(n, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        n = n.replaceAll("(?<=\\p{L})(?:['’‘ʼ`´]|-)(?=\\p{L})", "");
        List<String> words = new ArrayList<>();
        for (String w : n.split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            words.add(w.replace("ou", "o").replace("oo", "o").replace("uu", "u").replaceAll("oh(?![aeiouy])", "o"));
        }
        words.sort(null);
        return String.join(" ", words);
    }

    /** The romaji spellings as the program wrote them before: the Japanese file's styles must give the same list. */
    private static List<String> oldSpellings(String withMacrons) {
        Set<String> out = new LinkedHashSet<>();
        String s = withMacrons.strip();
        out.add(s);
        out.add(s.replace("ō", "o").replace("ū", "u").replace("Ō", "O").replace("Ū", "U").replace("ā", "a").replace("ē", "e").replace("ī", "i").replace("Ā", "A").replace("Ē", "E").replace("Ī", "I"));
        out.add(s.replace("ō", "ou").replace("ū", "uu").replace("Ō", "Ou").replace("Ū", "Uu"));
        out.add(s.replace("ō", "oh").replace("ū", "u").replace("Ō", "Oh").replace("Ū", "U"));
        out.add(s.replace("ō", "oo").replace("ū", "uu").replace("Ō", "Oo").replace("Ū", "Uu"));
        if (s.matches("(?s).*['’‘ʼ`´].*")) for (String x : new ArrayList<>(out)) out.add(x.replaceAll("['’‘ʼ`´]", ""));
        return new ArrayList<>(out);
    }

    private static final List<String> ROMAJI = List.of("Endō", "Endoh", "Endou", "Endoo", "Endo", "Ōuchi", "Oouchi", "Ohashi", "Ōhashi", "Ken'ichi Endō",
            "Ken-ichi Endoh", "Kenichi Endou", "Shōichi Morita", "Yūko", "Yuuko", "Yuko", "Kōno Tarō", "Kouno Tarou", "Sato", "Satoh", "Satō",
            "Ōno", "Ohno", "Oono", "Mary Ellis", "Hart, John", "Hattori", "Shimbashi", "Shinbashi", "Kyūshū", "Ryōko", "Tōkyō", "Osaka", "Ōsaka",
            "Kōda Hanako", "Morita, Rosa Haruko (Endoh)", "O'Hara", "Jean-Luc", "Takanōchi", "Takanouchi");

    @Test
    void theJapaneseRulesGiveTheKeysAndTheSpellingsTheProgramGaveBefore() {
        NameSpellings.Language ja = NameSpellings.of("ja");
        assertNotNull(ja);
        for (String s : ROMAJI) assertEquals(oldLatinKey(s), ja.key(s), s);
        for (String s : List.of("Kōda", "Endō Ken'ichi", "Ōuchi Yūko", "Kyūshū", "Tarō", "Satō Ā", "Ken’ichi", "Mary Ellis"))
            assertEquals(oldSpellings(s), ja.spellings(s), s);
        assertEquals(ja.key("Endō"), ja.key("Endoh"));
        assertEquals(ja.key("Endō"), ja.key("Endou"));
        assertEquals(ja.key("Endō"), ja.key("Endo"));
        assertNotEquals(ja.key("Ohashi"), ja.key("Oashi"), "oh before a vowel is a syllable of its own");
    }

    @Test
    void aPersonsOwnRulesAddToTheProgramsAndAStyleOfTheSameNameReplacesIt(@TempDir Path tmp) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            Config.invalidate();
            NameSpellings.forget();
            Path dir = Config.home().resolve("spellings");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("ja.txt"), "# the Kunrei way some old records write\nfold shi: si\nstyle kunrei: shi=si chi=ti tsu=tu\n");
            NameSpellings.Language ja = NameSpellings.of("ja");
            assertEquals(ja.key("Shibata"), ja.key("Sibata"), "the person's fold rule");
            assertEquals(ja.key("Endō"), ja.key("Endoh"), "and the program's still hold");
            assertTrue(ja.spellings("Shibata Tsuneo").contains("Sibata Tuneo"), ja.spellings("Shibata Tsuneo").toString());
            // a language only the person has rules for
            Files.writeString(dir.resolve("xx.txt"), "language: xx\nname: Test\nmarks: þ\nfold th: þ\n");
            NameSpellings.forget();
            assertTrue(NameSpellings.all().stream().anyMatch(l -> l.code().equals("xx")));
            assertEquals(List.of("xx"), NameSpellings.languagesOf(List.of("Þórr")).stream().map(NameSpellings.Language::code).filter(c -> c.equals("xx")).toList());
            assertEquals(NameSpellings.of("xx").key("Þórr"), NameSpellings.of("xx").key("Thorr"));
        } finally {
            System.setProperty("user.home", real);
            Config.invalidate();
        }
    }

    @Test
    void aNameInLatinLettersAloneHasNoLanguageAndKanaIsJapanese() {
        assertTrue(NameSpellings.languagesOf(List.of("John Lee", "Mary Ellis")).isEmpty(), "an English Lee is no Korean Yi");
        assertTrue(NameSpellings.languagesOf(List.of("Kenji Endo", "えんどう けんじ")).stream().anyMatch(l -> l.code().equals("ja")));
    }
}
