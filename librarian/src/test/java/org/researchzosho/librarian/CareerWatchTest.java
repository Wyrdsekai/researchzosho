package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.CareerProfile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The nightly watch: the companies read again from their feeds, what is new cut and read, one inbox line, and "new since you last looked". */
class CareerWatchTest {

    static final ObjectMapper M = new ObjectMapper();
    final CareerFindTest search = new CareerFindTest();

    @BeforeEach void standIns() { search.standIns(); }
    @AfterEach void back() { search.back(); }

    @Test void theWatchReadsTheListAgainKeepsWhatIsNewAndSaysSoOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Career.Seeker s = search.seeker(store, tmp);
        CareerFind.run(store, s, x -> { }, () -> false);
        assertEquals("no watch due", CareerWatch.nightly(store, true, () -> false));
        assertTrue(CareerWatch.set(store, s, true).startsWith("The library will watch every night"));
        CareerWatch.looked(store, s);
        assertTrue(CareerWatch.fresh(store, s, CareerFind.view(store, s)).isEmpty(), "what the person has seen is not new");
        assertEquals("", CareerWatch.inboxNote(store));
        Thread.sleep(1100);   // the first-seen times are to the second

        // a new opening on Formlabs' feed, read straight from the feed the search found: no web search tonight
        search.searches.clear();
        search.pages.put("https://boards-api.greenhouse.io/v1/boards/formlabs/jobs?content=true", search.formlabs(true).replace("]}",
                ",\n{\"title\": \"Head of Hardware Engineering\", \"location\": {\"name\": \"Boston, MA\"}, \"absolute_url\": \"https://formlabs.example/jobs/4\", \"first_published\": \""
                        + LocalDate.now() + "T08:00:00Z\", \"content\": \"&lt;p&gt;Printers.&lt;/p&gt;\"}]}"));
        String night = CareerWatch.nightly(store, true, () -> false);
        assertTrue(night.matches("(?s).*The watch read \\d+ companies again: 1 new opening passes what you want, and the library read 1 of them\\..*"), night);
        List<CareerFind.Row> fresh = CareerWatch.fresh(store, s, CareerFind.view(store, s));
        assertEquals(List.of("Head of Hardware Engineering"), fresh.stream().map(r -> r.opening().title()).toList());
        assertTrue(fresh.get(0).read(), "the new one was read tonight");
        assertTrue(CareerWatch.inboxNote(store).contains("1 new opening for ") && CareerWatch.inboxNote(store).contains("since you last looked, at 1 company"), CareerWatch.inboxNote(store));
        assertEquals("no watch due", CareerWatch.nightly(store, true, () -> false), "once a day: tonight's watch has run");

        // the page shows it under New since you last looked; opening the page by one who may write marks it seen
        CareerProfile cp = new CareerProfile();
        String page = cp.page(store, Patrons.Patron.PERSON, "/career/openings", "GET", Map.of("seeker", s.slug()), Map.of()).body();
        assertTrue(page.contains("<h2 id=\"new\">New since you last looked</h2>") && page.contains("Head of Hardware Engineering") && page.contains(">Stop watching</button>"), page);
        assertTrue(CareerWatch.fresh(store, s, CareerFind.view(store, s)).isEmpty(), "seen now");
        assertEquals("", CareerWatch.inboxNote(store));

        assertTrue(cp.chatReply(store, Patrons.Patron.PERSON, "terminal", "/career watch off").startsWith("The nightly watch is off."));
        assertFalse(CareerWatch.on(store, s));
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "p").put("runtime", "cli");
        assertTrue(new LibraryProtocol(store).career(a.put("op", "watch").put("seeker", s.name()).put("on", true)).path("summary").asText().startsWith("The library will watch every night"));
        assertTrue(CareerWatch.on(store, s));
    }
}
