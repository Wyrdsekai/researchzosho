package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Census tract's figures on a home's card (0.7.0): read once with a key, said in words, and the line without one. */
class HomeCensusTest {

    @AfterEach void back() { HomeCensus.KEY_VALUE = () -> Config.get(HomeCensus.KEY); HomeCensus.FETCH = url -> HomeSensors.FETCH.apply(url); }

    @Test void theTractsFiguresAreReadOnceAndSaidInWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeCensus.KEY_VALUE = () -> "test-key";
        List<String> asked = new ArrayList<>();
        HomeCensus.FETCH = url -> { asked.add(url); return url.contains("/2024/") ? null   // the newest survey is not out yet: the year before answers
                : "[[\"B19013_001E\",\"B01002_001E\",\"B25003_002E\",\"B25003_003E\",\"B25077_001E\",\"B25064_001E\",\"state\",\"county\",\"tract\"],[\"85200\",\"36.4\",\"1200\",\"800\",\"650000\",\"-666666666\",\"25\",\"025\",\"070101\"]]"; };
        HomeCensus.Figures f = HomeCensus.tract(store, "25025070101");
        assertNotNull(f);
        assertEquals(2023, f.year()); assertEquals(85200, f.income()); assertEquals(60, f.ownersShare()); assertEquals(650000, f.value());
        assertEquals(0, f.rent(), "the survey's mark for no estimate is no figure");
        assertEquals(2, asked.size());
        assertTrue(asked.get(1).contains("/2023/acs/acs5?get=B19013_001E,") && asked.get(1).contains("&for=tract:070101&in=state:25%20county:025&key=test-key"), asked.get(1));
        assertEquals("Census tract 25025070101 (American Community Survey 2019-2023): median household income $85,200; median age 36.4; 60 % of the homes owned, 40 % rented; median home value $650,000.", HomeCensus.line("25025070101", f));
        assertNotNull(HomeCensus.tract(store, "25025070101"));
        assertEquals(2, asked.size(), "kept: the second look reads the file, not the Bureau");
        HomeCensus.KEY_VALUE = () -> null;
        assertEquals("Census tract 25025070101: the tract's figures (income, age, owners and renters, home value, rent) need a Census key (RESEARCHZOSHO_CENSUS_KEY), free from api.census.gov/data/key_signup.html; not set.", HomeCensus.line("25025070101", null));
        assertNull(HomeCensus.tract(store, "25025070101"), "no key: nothing is asked");
        HomeCensus.KEY_VALUE = () -> "test-key";
        HomeCensus.FETCH = url -> null;
        assertEquals("Census tract 25025070102: the Census Bureau's survey did not answer for it.", HomeCensus.line("25025070102", HomeCensus.tract(store, "25025070102")));
    }
}
