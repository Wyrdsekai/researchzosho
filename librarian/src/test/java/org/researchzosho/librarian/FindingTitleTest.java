package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A claim's title is read alone, in a list, by people and by later research runs: it says whom the claim is about. */
class FindingTitleTest {

    private static String titled(String title, String subject) { return Finding.titled(title, new Finding.Triple(subject, "has authored works", "none found")); }

    @Test
    void aTitleThatNamesNobodyGetsItsSubjectAndOneThatDoesIsLeftAlone() {
        assertEquals("Endo Genzaburo (1872-1945): Absence of Authored Works", titled("Absence of Authored Works", "Endo Genzaburo (1872-1945)"));
        assertEquals("Endō Genzaburō's years at the railway", titled("Endō Genzaburō's years at the railway", "Endo Genzaburo (1872-1945)"));
        assertEquals("Genzaburo left the railway in 1911", titled("Genzaburo left the railway in 1911", "Endo Genzaburo"));
        assertEquals("髙橋源三郎は1911年に退職した", titled("髙橋源三郎は1911年に退職した", "髙橋 源三郎"));
        assertEquals("髙橋源三郎: 著作なし", titled("著作なし", "髙橋源三郎"));
        assertEquals("The gears were cut by hand", titled("The gears were cut by hand", "the Antikythera gears"));
        assertEquals("No title to change", Finding.titled("No title to change", null));
    }
}
