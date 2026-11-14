package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A number the source writes with a multiple in characters and the translation writes out is the same number (0.7.5): 8千人 and
 * "8,000 people", 24万 and "240,000", 3万8千円 and "38,000 yen", 7割 and "70%". Read live on a comic's first real run: eleven balloons
 * stood in doubt for "the number 8 is not in the translation" beside a translation that said 8,000. A number left out or changed is
 * still missing.
 */
class TranslationMultiplesTest {
    static List<String> lacked(String source, String translation) {
        List<String> missing = new ArrayList<>();
        List<String> tr = Translation.numbers(translation);
        for (String n : Translation.numbers(source)) if (!tr.remove(n)) missing.add(n);
        TranslationMultiples.excused(source, translation, missing);
        return missing;
    }

    @Test void aNumberWrittenWithAMultipleAndWrittenOutIsTheSameNumber() {
        assertEquals(List.of(), lacked("８千本．．．", "8,000..."));
        assertEquals(List.of(), lacked("その８千本のうち８０本が今日の分です！", "80 of those 8,000 are for today!"));
        assertEquals(List.of(), lacked("この町には２４万の人が住んでいる", "240,000 people live in this town."));
        assertEquals(List.of(), lacked("家賃はなんと３万８千円", "The rent is 38,000 yen"), "the parts written in a row are one number");
        assertEquals(List.of(), lacked("家賃は3.8万円", "38,000 yen a month"));
        assertEquals(List.of(), lacked("家賃が１０万円以下の部屋が約７割を占める", "about 70% of the rooms cost less than 100,000 yen a month"), "割 is tenths, written as a percentage");
        assertEquals(List.of(), lacked("切符は８万円だった", "Die Fahrkarte kostete 80.000 Yen."), "as another language groups its digits");
        assertEquals(List.of(), lacked("人口は１億２千万人", "a population of 120,000,000"));
        assertEquals(List.of(), lacked("3千万円", "30 million yen"), "digits before the language's word for a million");
        assertEquals(List.of(), lacked("3.8万人", "38 thousand people")); assertEquals(List.of(), lacked("８万円", "80k yen")); assertEquals(List.of(), lacked("２４万人", "240 тысяч человек")); assertEquals(List.of(), lacked("3千万円", "30 Millionen Yen"));
    }

    @Test void aNumberLeftOutOrChangedIsStillMissing() {
        assertEquals(List.of("8"), lacked("８千人が集まる", "Thousands gather."));
        assertEquals(List.of("8"), lacked("８千人が集まる", "9,000 gather."));
        assertEquals(List.of("24"), lacked("２４万の本", "2,400 books"), "a wrong multiple is a wrong number");
        assertEquals(List.of("24"), lacked("２４万の本", "240 books"), "digits before a word that is no thousand or million are only digits");
        assertEquals(List.of("24"), lacked("２４万の本", "24,000 books"));
        assertEquals(List.of("2"), lacked("その後２年間", "the next few years"), "a number with no multiple is not this class's to excuse");
        assertEquals(List.of("3"), lacked("3万8千円", "8,000 yen"), "one part written out, the other lost");
        List<String> none = new ArrayList<>(); TranslationMultiples.excused(null, "x", none); TranslationMultiples.excused("８千", null, none); assertTrue(none.isEmpty());
    }

    /** A document through the whole run: the unit that writes 24万 out as 240,000 is not flagged, the one that loses 8千 is, and its revision clears it. */
    @Test void aDocumentsCheckTakesTheNumberWrittenOutAndStillFlagsTheOneThatIsLost(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("doctors.md");
        Files.writeString(in, "# 町の数字\n\nこの町には２４万の人が住んでいる。\n\n毎年８千人が港を訪れる。\n\n灯台守の月給は３万８千円である。\n", StandardCharsets.UTF_8);
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.translate = t -> t.contains("２４万") ? "240,000 people live in this town." : t.contains("８千人") ? "Thousands visit the harbour every year." : t.contains("３万８千円") ? "The lighthouse keeper's monthly pay is 38,000 yen." : t.contains("町の数字") ? "The town in numbers"
                // the sample translated back, which writes the numbers out its own way: the same check reads it
                : t.contains("240,000") ? "この町には240,000人が住んでいる。" : t.contains("8,000") ? "毎年8,000人が港を訪れる。" : t.contains("38,000") ? "灯台守の月給は38,000円である。" : "町の数字";
        sc.revise = t -> t.contains("８千人") ? "Every year 8,000 people visit the harbour." : "revised";
        Translation.Outcome o = Translation.run(store, in, "en", "", "", null, null, TranslationTest.seats(sc), null, () -> false);
        List<Translation.Flag> numbers = o.flags().stream().filter(f -> f.kind().equals("number")).toList();
        assertEquals(1, numbers.size(), "only the unit that lost its number, and nothing from the back-translation that writes 24万 as 240,000: " + o.flags());
        assertEquals("program", numbers.get(0).by()); assertTrue(o.backTranslated() >= 3, "the units were translated back and compared: " + o.backTranslated());
        assertEquals("8", numbers.get(0).words()); assertTrue(numbers.get(0).revised() && !numbers.get(0).stands(), "and the revision that writes 8,000 clears it: " + numbers);
        String out = Files.readString(o.output());
        assertTrue(out.contains("240,000 people") && out.contains("8,000 people") && out.contains("38,000 yen"), out);
    }

    @Test void theProgramsCheckCountsItPresentInADocumentAndInAComicAlike() {
        assertTrue(Translation.mechanical(0, "この町には２４万の人が住んでいる", "240,000 people live in this town.", Map.of(), "en", "").isEmpty());
        List<Translation.Flag> lost = Translation.mechanical(0, "この町には２４万の人が住んでいる", "Many people live in this town.", Map.of(), "en", "");
        assertEquals(1, lost.size()); assertEquals("number", lost.get(0).kind()); assertEquals("24", lost.get(0).words());
    }
}
