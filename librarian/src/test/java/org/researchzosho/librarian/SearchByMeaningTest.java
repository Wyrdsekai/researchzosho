package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The standing of search by meaning is said in words: off, on, or configured and not answering — the silent case. */
class SearchByMeaningTest {

    private final Supplier<String> realSetting = SearchByMeaning.setting;
    private final Predicate<String> realAnswers = SearchByMeaning.answers;

    @AfterEach
    void restore() { SearchByMeaning.setting = realSetting; SearchByMeaning.answers = realAnswers; SearchByMeaning.reprobe(); }

    @Test
    void theThreeStandings(@TempDir Path tmp) throws Exception {
        SearchByMeaning.reprobe();
        SearchByMeaning.setting = () -> null;
        SearchByMeaning.Standing off = SearchByMeaning.standing();
        assertEquals("off", off.state());
        assertTrue(off.sentence().contains("by words only") && off.sentence().contains("researchzosho embed start"), off.sentence());

        SearchByMeaning.setting = () -> "http://embed.example:8080";
        SearchByMeaning.answers = base -> false;
        SearchByMeaning.Standing silent = SearchByMeaning.standing();
        assertEquals("not answering", silent.state());
        assertTrue(silent.sentence().startsWith("search by meaning: OFF") && silent.sentence().contains("http://embed.example:8080") && silent.sentence().contains("by words only"), silent.sentence());

        SearchByMeaning.reprobe();
        SearchByMeaning.answers = base -> true;
        SearchByMeaning.Standing on = SearchByMeaning.standing();
        assertEquals("on", on.state());
        assertTrue(on.sentence().startsWith("search by meaning: on"), on.sentence());

        // the wire carries the same word
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode status = new LibraryProtocol(store).status(new ObjectMapper().createObjectNode());
        assertEquals("on", status.get("search_by_meaning").asText());
    }

    @Test
    void theProbeIsNotRepeatedWithinAMinute() {
        SearchByMeaning.reprobe();
        int[] probes = {0};
        SearchByMeaning.setting = () -> "http://example.org:1";
        SearchByMeaning.answers = base -> { probes[0]++; return false; };
        SearchByMeaning.standing(); SearchByMeaning.standing(); SearchByMeaning.standing();
        assertEquals(1, probes[0], "one probe serves a minute of status calls");
    }
}
