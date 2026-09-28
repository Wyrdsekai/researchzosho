package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.records.RecordSources;

import static org.junit.jupiter.api.Assertions.*;

/** Era years are arithmetic: the date stays as the source wrote it and the western year is worked out, never guessed. */
class FamilyDateTest {

    private static int year(String s) { FamilyDate d = FamilyDate.parse(s); return d == null ? 0 : d.year(); }

    @Test
    void eraYearsInEveryWayTheyAreWritten() {
        assertEquals(1907, year("明治40年"));
        assertEquals(1907, year("明治四十年三月"));
        assertEquals(1907, year("明治４０年"), "full-width digits");
        assertEquals(1926, year("昭和元年"));
        assertEquals(1912, year("大正元年"));
        assertEquals(1945, year("昭和二十年八月"));
        assertEquals(1886, year("Meiji 19"));
        assertEquals(1928, year("Shōwa 3"));
        assertEquals(1907, year("M40.3.5"));
        assertEquals(1928, year("S.3"));
        assertEquals(1867, year("慶応3年"));
        assertEquals(1840, year("天保十一年"));
        assertEquals(1853, year("嘉永6年"), "the year the black ships came");
        assertEquals(1860, year("安政7年"));
        assertEquals(1845, year("弘化2年"));
        assertEquals(1863, year("文久三年"));
        assertEquals(2019, year("令和元年"));
        assertEquals(1889, year("明治弐拾弐年"), "the formal numerals a register uses");
        assertEquals(1892, year("明治廿五年"));
        assertEquals(1880, year("明治拾参年"));
    }

    @Test
    void aYearThatCannotBeIsNoYearAndWordsAreNotEraLetters() {
        assertNull(FamilyDate.parse("明治50年"), "Meiji ended in its 45th year");
        assertNull(FamilyDate.parse("some time in his youth"));
        assertNull(FamilyDate.parse("room 12"), "the m of 'room' is not Meiji");
        assertEquals(1907, year("born 1907 in Hiroshima"));
    }

    @Test
    void aLetterAndANumberAreAnEraYearOnlyWhereTheyAreWrittenAsADate() {
        assertEquals(2020, year("born 2020 on an M1 Mac"), "a four-digit year wins over what only looks like era shorthand");
        assertEquals(0, year("S3 bucket"), "a storage bucket is no date");
        assertEquals(0, year("the H2 database"));
        assertEquals(1907, year("M40"));
        assertEquals(1907, year("about M40"));
        assertEquals(1928, year("S3年"));
        assertEquals(1990, year("H2/4/1"));
        assertEquals(2020, RecordSources.lived("Who is Tom Hale (born 2020 on an M1 Mac)?")[0], "the years a record search is held to come from the four-digit year");
        assertNotEquals(1868, FamilyDate.bornOf("Who is Tom Hale, born 2020 on an M1 Mac?").year());
    }

    @Test
    void theShorthandIsADateBesideTheWordsADateCarriesInJapaneseToo() {
        // a family's own records write about, before and after in Japanese, and born or died after the date
        for (String[] c : new String[][]{{"M40頃", "1907", "about"}, {"M40ごろ", "1907", "about"}, {"約M40", "1907", "about"}, {"M40 頃", "1907", "about"}, {"M40(頃)", "1907", "about"},
                {"S20以前", "1945", "before"}, {"S20まで", "1945", "before"}, {"S20以降", "1945", "after"}, {"S20 before", "1945", "before"}, {"S20 or before", "1945", "before"},
                {"S3生まれ", "1928", ""}, {"H3没", "1991", ""}, {"S3・5・1", "1928", ""}, {"M40, 3, 5", "1907", ""}, {"S3 5 1", "1928", ""}, {"M40?", "1907", ""}}) {
            FamilyDate d = FamilyDate.parse(c[0]);
            assertNotNull(d, c[0]);
            assertEquals(Integer.parseInt(c[1]), d.year(), c[0]);
            assertEquals(c[2], d.qualifier(), c[0]);
        }
        assertEquals(0, year("S3 bucket"), "a word that is no date's still makes it no date");
        assertEquals(0, year("an M1 Mac"));
    }

    @Test
    void aBirthIsReadFromTheBirthAloneNotFromTheDeathAfterItOrThePlace() {
        assertEquals(1928, FamilyDate.bornOf("Who is Tom Hale, born S3, died 2020?").year(), "the death year is not the birth");
        assertArrayEquals(new int[]{1928, 2020}, RecordSources.lived("Who is Tom Hale, born S3, died 2020?"));
        assertEquals(1928, FamilyDate.bornOf("Who is Tom Hale, born S3, Osaka?").year());
        assertEquals(1928, FamilyDate.bornOf("Who is Tom Hale, born S3 Osaka?").year());
        assertEquals(1928, FamilyDate.bornOf("Who is Tom Hale, born S3?").year());
        assertArrayEquals(new int[]{1907, 1975}, RecordSources.lived("Who is Tom Hale, born M40 in Kyoto; died S50?"));
        assertEquals(1902, RecordSources.lived("Who is 森田清 (born M40頃 in 伊勢)?")[0], "about 1907 starts five years before");
        assertEquals(2020, FamilyDate.bornOf("Who is Tom Hale, born 2020 on an M1 Mac?").year());
        assertEquals(1850, FamilyDate.bornOf("Who was born 12 March, 1850 in Leeds?").year());
    }

    @Test
    void qualifiersAndWhatAClaimShows() {
        assertEquals("明治40年頃 (about 1907)", FamilyDate.parse("明治40年頃").shown());
        assertEquals("about 1885", FamilyDate.parse("about 1885").shown());
        assertEquals("about 1906", FamilyDate.parse("around 1906").shown());
        assertEquals("before 1900", FamilyDate.parse("before 1900").shown());
        assertEquals("昭和20年以降 (after 1945)", FamilyDate.parse("昭和20年以降").shown());
        assertEquals("1907", FamilyDate.parse("1907").shown());
        assertEquals("March 1907 (1907)", FamilyDate.parse("March 1907").shown());
    }

    private static int[] span(String s) { FamilyDate d = FamilyDate.parse(s); return new int[]{d.earliest(), d.latest()}; }

    @Test
    void aDateIsARangeOfYears() {
        assertArrayEquals(new int[]{1850, 1860}, span("BET 1850 AND 1860"), "the first year used to win");
        assertArrayEquals(new int[]{1850, 1860}, span("BET 3 MAR 1850 AND 5 JUN 1860"));
        assertArrayEquals(new int[]{1850, 1860}, span("FROM 1850 TO 1860"));
        assertArrayEquals(new int[]{1850, 1860}, span("between 1850 and 1860"));
        assertArrayEquals(new int[]{1850, 1860}, span("1850-1860"));
        assertArrayEquals(new int[]{1850, 1851}, span("1851 or 1850"));
        assertArrayEquals(new int[]{1750, 1751}, span("1750/51"), "the old style year and the new one");
        assertArrayEquals(new int[]{1799, 1800}, span("1799/00"));
        assertArrayEquals(new int[]{1907, 1907}, span("1907-03-05"), "a day of a month is not a range");
        assertArrayEquals(new int[]{1850 - FamilyDate.ABOUT_YEARS, 1850 + FamilyDate.ABOUT_YEARS}, span("ABT 1850"));
        assertArrayEquals(new int[]{1845, 1855}, span("EST 1850"), "estimated is about");
        assertArrayEquals(new int[]{1845, 1855}, span("CAL 1850"), "and so is calculated");
        assertArrayEquals(new int[]{1845, 1855}, span("ca. 1850"));
        assertArrayEquals(new int[]{-FamilyDate.OPEN, 1850}, span("BEF 1850"));
        assertArrayEquals(new int[]{1850, FamilyDate.OPEN}, span("after 1850"));
        assertArrayEquals(new int[]{1902, 1912}, span("明治40年頃"));
        assertEquals("between 1850 and 1860", FamilyDate.parse("BET 1850 AND 1860").shown());
        assertEquals("1750/51 (between 1750 and 1751)", FamilyDate.parse("1750/51").shown());
        assertEquals("1850 or before", FamilyDate.parse("BEF 1850").phrase());
        assertEquals("in 1850 or before", FamilyDate.parse("BEF 1850").in());
        assertEquals("about 1850", FamilyDate.parse("ABT 1850").in());
        assertNull(FamilyDate.parse("BEF 1850").centre(), "a before date names an edge, not a middle");
        assertTrue(FamilyDate.apart(FamilyDate.parse("1840"), FamilyDate.parse("about 1850"), 2));
        assertFalse(FamilyDate.apart(FamilyDate.parse("1853"), FamilyDate.parse("about 1850"), 2));
    }

    @Test
    void anAgeInARecordIsABirthYearWorkedOut() {
        assertEquals(42, FamilyDate.age("42").years());
        assertEquals(42, FamilyDate.age("42y 3m").years());
        assertEquals(42, FamilyDate.age("42歳").years());
        assertEquals(73, FamilyDate.age("七十三歳").years());
        assertEquals(0, FamilyDate.age("3 months").years());
        assertEquals(0, FamilyDate.age("<1y").years());
        assertEquals(0, FamilyDate.age("INFANT").years());
        assertNull(FamilyDate.age("CHILD"), "under eight says too little");
        assertTrue(FamilyDate.age("享年73").counted() && FamilyDate.age("数え年5歳").counted());
        assertFalse(FamilyDate.age("満5歳").counted());
        assertEquals("between 1842 and 1843", FamilyDate.bornFrom(FamilyDate.age("42"), FamilyDate.parse("1885")).phrase(), "42 in 1885: before or after the birthday");
        assertEquals("between 1826 and 1828", FamilyDate.bornFrom(FamilyDate.age("享年七十三"), FamilyDate.parse("明治33年")).phrase(), "an age counted the old way reaches a year later");
        assertEquals("between 1884 and 1885", FamilyDate.bornFrom(FamilyDate.age("3 months"), FamilyDate.parse("1885")).phrase());
        assertEquals("1843 or before", FamilyDate.bornFrom(FamilyDate.age("42"), FamilyDate.parse("before 1885")).phrase(), "42 at a date before 1885");
    }

    @Test
    void aRegisterDateWrittenAgainstTheOneBeforeIt() {
        FamilyDate march = FamilyDate.parse("明治40年3月5日"), december = FamilyDate.parse("明治四十年十二月五日");
        assertTrue(FamilyDate.relative("同年五月一日") && !FamilyDate.relative("1907") && !FamilyDate.relative("some time later"));
        assertNull(FamilyDate.parse("同年五月一日"), "with nothing to work from it names no year");
        assertEquals(1907, FamilyDate.parse("同年五月一日", march).year());
        assertEquals(1907, FamilyDate.parse("同日", march).year());
        assertEquals(1908, FamilyDate.parse("翌年三月", march).year());
        assertEquals(1909, FamilyDate.parse("翌々年", march).year());
        assertEquals(1906, FamilyDate.parse("前年", march).year());
        assertEquals(1907, FamilyDate.parse("翌月", march).year());
        assertEquals(1908, FamilyDate.parse("翌月", december).year(), "the month after December is in the next year");
        assertEquals(1908, FamilyDate.parse("the following year", march).year());
        assertEquals(1907, FamilyDate.parse("1907", december).year(), "a date with its own year keeps it");
        assertEquals("明治四十年十二月五日", FamilyDate.lastIn("明治三十年生 明治四十年十二月五日髙橋源三郎ト婚姻届出 ").written(), "the last date before, and only the date");
        assertNull(FamilyDate.lastIn("長男正一出生"));
    }
}
