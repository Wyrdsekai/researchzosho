package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.Translation.Flag;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The number check and a month written by its name: 10月 is "October" in English and "Oktober" in German, so a translation that writes the
 * month by its name lacks no number; a day, a year or a count it lacks is still flagged, and a month name in the source with its number in
 * a 月 date in the translation is clean as before.
 */
class TranslationMonthsTest {

    static List<String> numberFlags(String source, String translation, String to) {
        return Translation.mechanical(0, source, translation, Map.of(), Set.of(), null, null, "", to).stream().filter(f -> f.kind().equals("number")).map(Flag::words).toList();
    }

    @Test void aMonthWrittenByItsNameIsNoMissingNumber() {
        assertEquals(List.of(), numberFlags("2025年10月12日の土曜日、京都で祭りが開かれた。", "On Saturday, October 12, 2025, a festival was held in Kyoto.", "en"));
        assertEquals(List.of(), numberFlags("2025年10月12日の土曜日、京都で祭りが開かれた。", "On Saturday 12 Oct 2025 a festival was held in Kyoto.", "en"));
        assertEquals(List.of(), numberFlags("2025年10月12日の土曜日、京都で祭りが開かれた。", "On Saturday, 12. Oktober 2025, fand in Kyoto ein Fest statt.", "de"));
        assertEquals(List.of(), numberFlags("The meeting is on 2025-10-12 in Leeds.", "La réunion a lieu le 12 octobre 2025 à Leeds.", "fr"));
        // the reverse: the month's name in the source, its number in a 月 date in the translation, clean as it was
        assertEquals(List.of(), numberFlags("On Saturday, October 12, 2025, a festival was held in Kyoto.", "2025年10月12日の土曜日、京都で祭りが開かれた。", "ja"));
    }

    @Test void aDayAYearOrACountTheTranslationLacksIsStillFlagged() {
        assertEquals(List.of("12"), numberFlags("2025年10月12日の土曜日、京都で祭りが開かれた。", "In October 2025 a festival was held in Kyoto.", "en"), "the day");
        assertEquals(List.of("2025"), numberFlags("2025年10月12日に300人が集まった。", "About 300 people gathered on October 12.", "en"), "the year");
        assertEquals(List.of("300"), numberFlags("2025年10月12日に300人が集まった。", "Many people gathered on October 12, 2025.", "en"), "a count");
        assertEquals(List.of("10"), numberFlags("2025年10月12日に祭りがあった。", "There was a festival on the 12th, 2025.", "en"), "the month, when no name stands for it");
        assertEquals(List.of("10"), numberFlags("10人が集まった。", "October brought people together.", "en"), "a count is no month, whatever word stands in the translation");
        assertEquals(List.of("5"), numberFlags("2025年5月3日に会った。", "Non ci siamo mai incontrati il 3 2025.", "it"), "Italian \"mai\" is never, not May");
        assertEquals(List.of("3"), numberFlags("2025年3月1日に海へ行った。", "El 1 de 2025 fuimos al mar.", "es"), "a short form alone is another word: the sea");
        assertEquals(List.of("10"), numberFlags("2025年10月12日に祭りがあった。", "On October 12, 2025, there was a festival.", ""), "no language said: no month names read");
    }
}
