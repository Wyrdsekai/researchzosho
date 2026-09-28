package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the names of each language are written in Latin letters: spellings records use for one name compare as one, names that differ stay
 * two, and a name in its own script is written the ways records write it. The examples are public figures and common family names.
 */
class NameSpellingsLanguagesTest {

    private static void one(String code, String... forms) {
        NameSpellings.Language l = NameSpellings.of(code);
        assertNotNull(l, code);
        for (String f : forms) assertEquals(l.key(forms[0]), l.key(f), code + ": " + forms[0] + " and " + f);
    }

    private static void two(String code, String a, String b) {
        NameSpellings.Language l = NameSpellings.of(code);
        assertNotEquals(l.key(a), l.key(b), code + ": " + a + " and " + b + " are two names");
    }

    @Test
    void korean() {
        one("ko", "Lee", "Yi", "Rhee", "I");
        one("ko", "Park", "Pak", "Bak");
        one("ko", "Kim", "Gim");
        one("ko", "Choi", "Choe");
        one("ko", "Jung", "Jeong", "Chung");
        one("ko", "Yoon", "Yun");
        one("ko", "Kang", "Gang");
        one("ko", "Kim Min-jun", "Gim Minjun");
        one("ko", "Park Chung-hee", "Bak Jeonghui", "Pak Chŏng-hŭi");
        two("ko", "Kim", "Kang");
        two("ko", "Park", "Pang");
        two("ko", "Jung", "Jun");
        // Hangul in Latin letters: the Revised Romanization first, then McCune-Reischauer with its breves and without
        List<String> kim = NameSpellings.of("ko").spellings("김 민준");
        assertEquals("Gim Minjun", kim.get(0), kim.toString());
        assertTrue(kim.contains("Kim Minjun"), kim.toString());
        List<String> park = NameSpellings.of("ko").spellings("박 정희");
        assertEquals(List.of("Bak Jeonghui", "Pak Chŏnghŭi", "Pak Chonghui"), park);
    }

    @Test
    void russian() {
        one("ru", "Tchaikovsky", "Chaikovsky", "Tschaikowski", "Chaykovskiy");
        one("ru", "Rachmaninoff", "Rakhmaninov");
        one("ru", "Gorbachev", "Gorbatschow");
        one("ru", "Yeltsin", "Eltsine");
        two("ru", "Ivanov", "Ivanova");
        two("ru", "Petrov", "Petrenko");
        List<String> tchaikovsky = NameSpellings.of("ru").spellings("Чайковский");
        assertEquals("Chaykovskiy", tchaikovsky.get(0), "BGN/PCGN first: " + tchaikovsky);
        assertTrue(tchaikovsky.containsAll(List.of("Tschaikowski", "Tchaïkovski", "Chaikovsky")), "the German, French and English ways: " + tchaikovsky);
        List<String> yeltsin = NameSpellings.of("ru").spellings("Ельцин");
        assertTrue(yeltsin.containsAll(List.of("Yeltsin", "Jelzin", "Eltsine")), yeltsin.toString());
    }

    @Test
    void greek() {
        one("el", "Papadopoulos", "Papadopulos");
        one("el", "Christos", "Hristos", "Khristos");
        one("el", "Georgios", "Yeorgios");
        one("el", "Giorgos", "Yorgos");
        two("el", "Georgios", "Giorgos");
        assertEquals("Papadopoulos", NameSpellings.of("el").spellings("Παπαδόπουλος").get(0));
    }

    @Test
    void germanAndNordic() {
        one("de", "Müller", "Mueller", "Muller");
        one("de", "Schröder", "Schroeder");
        one("de", "Strauß", "Strauss");
        two("de", "Müller", "Möller");
        two("de", "Quelle", "Qulle");
        assertTrue(NameSpellings.of("de").spellings("Müller").containsAll(List.of("Mueller", "Muller")));
        one("nordic", "Sørensen", "Soerensen", "Sorensen");
        one("nordic", "Ångström", "Aangstroem", "Angstrom");
        one("nordic", "Þór", "Thor");
        two("nordic", "Hansen", "Hanson");
        assertTrue(NameSpellings.of("nordic").spellings("Sørensen").containsAll(List.of("Soerensen", "Sorensen")));
    }

    @Test
    void chinese() {
        one("zh", "Zhang", "Chang", "Cheung");
        one("zh", "Mao Zedong", "Mao Tse-tung", "Mao Tsetung");
        one("zh", "Deng Xiaoping", "Teng Hsiao-p'ing", "Teng Hsiao-ping");
        one("zh", "Xi Jinping", "Hsi Chin-p'ing");
        one("zh", "Chen", "Ch'en", "Chan");
        one("zh", "Huang", "Wong");
        one("zh", "Lin", "Lim", "Lam");
        one("zh", "Xu", "Hsü", "Hsu");
        two("zh", "Zhang", "Zhong");
        two("zh", "Li", "Lin");
        two("zh", "Huang", "Wang");
        List<String> deng = NameSpellings.of("zh").spellings("Deng Xiaoping");
        assertTrue(deng.contains("Teng Hsiao-p'ing") || deng.contains("Teng Hsiao-ping"), "the Wade-Giles way for a search: " + deng);
        assertTrue(NameSpellings.languagesOf(List.of("Zhāng Wěi")).stream().anyMatch(l -> l.code().equals("zh")), "pinyin with its tone marks is Chinese");
    }

    @Test
    void aPersonsScriptsChooseTheLanguagesAndLatinLettersAloneChooseNone() {
        assertEquals(List.of("ko"), NameSpellings.languagesOf(List.of("김민준", "Kim Minjun")).stream().map(NameSpellings.Language::code).toList());
        assertEquals(List.of("ru"), NameSpellings.languagesOf(List.of("Чайковский")).stream().map(NameSpellings.Language::code).toList());
        assertTrue(NameSpellings.languagesOf(List.of("Hans Müller")).stream().anyMatch(l -> l.code().equals("de")));
        assertTrue(NameSpellings.languagesOf(List.of("John Lee", "Peter Chung")).isEmpty(), "an English Lee is no Korean Yi");
    }
}
