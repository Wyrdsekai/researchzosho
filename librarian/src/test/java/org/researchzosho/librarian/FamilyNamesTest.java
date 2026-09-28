package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** One person written several ways has a key in common; what tells two people apart is never thrown away. */
class FamilyNamesTest {

    private static boolean same(String a, String b) { return !Collections.disjoint(FamilyNames.keys(a), FamilyNames.keys(b)); }

    @Test
    void theWaysOneNameIsWritten() {
        assertTrue(same("Hisa Endō", "Endo Hisa"), "order and accents");
        assertTrue(same("Endo, Hisa", "Hisa Endo"), "an index writes the family name first, with a comma");
        assertTrue(same("Kenjirō (Hisa) Endō", "Endo Hisa") && same("Kenjirō (Hisa) Endō", "Kenjiro Endo"), "another given name in brackets");
        assertTrue(same("森田 一郎", "森田一郎") && same("髙橋正一", "高橋正一"), "a space, and the old form of a character");
    }

    @Test
    void whatTellsTwoPeopleApartIsKept() {
        assertFalse(same("Robert Hale, Jr.", "Robert Hale, Sr."));
        assertFalse(same("Robert Eugene Hale", "Robert Hale"), "a middle name only one of them has");
        assertTrue(FamilyNames.keys("John Ellis (born 1851)").isEmpty() && FamilyNames.keys("John Ellis (his son)").isEmpty() && FamilyNames.keys("森田勇（二代）").isEmpty(), "a note in brackets: the name only matches itself");
        assertTrue(FamilyNames.keys("John").isEmpty() && FamilyNames.keys("源次").isEmpty(), "one word, or a given name alone, is nobody in particular");
    }

    @Test
    void otherNamesThatAreSafeToKeep() {
        assertTrue(FamilyNames.keepAsOtherName("森田源次", "Hisaji Morita") && FamilyNames.keepAsOtherName("森田源次", "もりた ひさじ") && FamilyNames.keepAsOtherName("森田源次", "遠藤源次"));
        assertFalse(FamilyNames.keepAsOtherName("森田源次", "John"), "one word of a name is shared by strangers");
        assertFalse(FamilyNames.keepAsOtherName("森田源次", "my father") || FamilyNames.keepAsOtherName("森田源次", "her uncle's wife"), "how the teller is related to somebody is not a name");
        assertFalse(FamilyNames.keepAsOtherName("森田源次", "源次") || FamilyNames.keepAsOtherName("森田源次", "森田"), "a part of the person's own name");
    }
}
