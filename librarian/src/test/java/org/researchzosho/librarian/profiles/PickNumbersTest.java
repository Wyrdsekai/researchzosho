package org.researchzosho.librarian.profiles;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The numbers a person types to pick whom to research from the plan. What cannot be read is asked again, never a crash after the
 * who-is-who sitting: a number too long for the program, a range the wrong way round, a number not on the list. A range may be written
 * with the Japanese range marks and with spaces.
 */
class PickNumbersTest {

    @Test
    void numbersListsAndRanges() {
        assertEquals(List.of(1, 3), GenealogyProfile.chosen("1,3", 5));
        assertEquals(List.of(1, 3), GenealogyProfile.chosen("1、3", 5));
        assertEquals(List.of(1, 3), GenealogyProfile.chosen("１，３", 5), "full-width digits and comma");
        assertEquals(List.of(2, 3, 4), GenealogyProfile.chosen("2-4", 5));
        assertEquals(List.of(2, 3, 4), GenealogyProfile.chosen("2 - 4", 5), "spaces around the dash");
        assertEquals(List.of(2, 3, 4), GenealogyProfile.chosen("2～4", 5), "the full-width tilde");
        assertEquals(List.of(2, 3, 4), GenealogyProfile.chosen("2〜4", 5), "the wave dash");
        assertEquals(List.of(1, 2, 3, 5), GenealogyProfile.chosen("5, 1 〜 3", 5));
        assertEquals(List.of(1, 2, 3, 4, 5), GenealogyProfile.chosen("all", 5));
        assertEquals(List.of(), GenealogyProfile.chosen("", 5));
        assertEquals(List.of(), GenealogyProfile.chosen(null, 5));
        assertEquals(List.of(), GenealogyProfile.chosen("none", 5));
    }

    @Test
    void whatCannotBeReadIsAskedAgain() {
        assertNull(GenealogyProfile.chosen("11111111111", 4), "a number too long is not a crash");
        assertNull(GenealogyProfile.chosen("1-99999999999", 4));
        assertNull(GenealogyProfile.chosen("9", 4), "not on the list");
        assertNull(GenealogyProfile.chosen("0", 4));
        assertNull(GenealogyProfile.chosen("4-2", 4), "a range the wrong way round");
        assertNull(GenealogyProfile.chosen("the first two", 4));
        assertNull(GenealogyProfile.chosen("1,,x", 4));
    }
}
