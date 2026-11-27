package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.tools.Fetch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hiring system that answers "later" (429 too many requests, 503 busy) is asked again after the wait it names, and a board whose system
 * still says "later" is kept, never taken for no board: on 2026-11-26 Workable answered 429 during a career search, four employers' boards
 * were dropped, and the search then paid for a web search by each employer's name.
 */
class CareerLaterTest {

    static final ObjectMapper M = new ObjectMapper();
    final Map<String, Deque<Fetch.Result>> answers = new HashMap<>();
    final List<String> asked = new ArrayList<>(), searches = new ArrayList<>();
    final List<Long> waited = new ArrayList<>();
    BiFunction<String, Map<String, String>, Fetch.Result> getWas;
    LongConsumer sleepWas;

    static Fetch.Result answer(String url, int status, String body, String retryAfter) {
        return new Fetch.Result(url, status, body.getBytes(StandardCharsets.UTF_8), "application/json", retryAfter);
    }

    void answers(String url, Fetch.Result... rs) { answers.put(url, new ArrayDeque<>(List.of(rs))); }

    @BeforeEach void standIns() {
        CareerSources.reset();
        CareerSources.POLITE_MS = 0;
        getWas = CareerSources.GET; sleepWas = CareerSources.SLEEP;
        CareerSources.RAW = CareerSources::fetch;   // the library's own read, with only the network stood in
        CareerSources.RAW_PAGE = CareerSources::fetchPage;
        CareerSources.GET = (url, headers) -> {
            asked.add(url);
            Deque<Fetch.Result> q = answers.get(url);
            if (q == null || q.isEmpty()) return answer(url, 404, "", "");
            return q.size() > 1 ? q.poll() : q.peek();
        };
        CareerSources.SLEEP = waited::add;
        CareerSources.SEARCH = q -> { searches.add(q); return List.of(); };
    }

    @AfterEach void back() {
        CareerSources.GET = getWas; CareerSources.SLEEP = sleepWas;
        CareerSources.RAW = CareerSources::fetch; CareerSources.RAW_PAGE = CareerSources::fetchPage; CareerSources.SEARCH = CareerSources::search;
        CareerSources.POLITE_MS = 1000; CareerSources.reset();
    }

    static final String WIDGET = "https://apply.workable.com/api/v1/widget/accounts/civica-uk-ltd-1";

    @Test void aBoardThatSaysLaterIsAskedAgainAfterTheWaitItNames() {
        answers(WIDGET, answer(WIDGET, 429, "Too many requests", "7"), answer(WIDGET, 200, CareerSourcesTest.recorded("workable-widget.json"), ""));
        Found f = CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of());
        assertEquals(2, f.openings().size(), "read on the second ask");
        assertEquals(List.of(7000L), waited, "it waited the seven seconds Workable asked for");
        assertEquals(2, asked.stream().filter(WIDGET::equals).count());
        // a 503 from another system is asked again the same way, and a wait longer than half a minute is not waited out whole
        String gh = "https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true";
        answers(gh, answer(gh, 503, "busy", "600"), answer(gh, 200, "{\"jobs\": [{\"id\": 1, \"title\": \"Staff Engineer\", \"location\": {\"name\": \"Remote\"}, \"absolute_url\": \"https://job-boards.greenhouse.io/acme/jobs/1\"}]}", ""));
        waited.clear();
        assertEquals(1, CareerSources.read(new Feed("greenhouse", "acme", "t"), "Acme", List.of()).openings().size());
        assertEquals(List.of(CareerSources.LATER_MAX_MS), waited);
    }

    @Test void aBoardWhoseSystemStillSaysLaterIsKeptAndNoOtherBoardIsSearchedFor() throws Exception {
        answers(WIDGET, answer(WIDGET, 429, "Too many requests", ""));
        Found f = CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of());
        assertNotNull(f, "not taken for no board");
        assertEquals("workable:civica-uk-ltd-1", f.feed().label()); assertTrue(f.openings().isEmpty());
        assertTrue(f.note().startsWith("Workable asked the library to come back later"), f.note());
        assertEquals(List.of(CareerSources.LATER_DEFAULT_MS, CareerSources.LATER_DEFAULT_MS), waited, "asked twice more, ten seconds apart, when it names no wait");
        // the career search reads the company's board kept from before: it keeps it, and searches the web for no other
        ObjectNode c = M.createObjectNode().put("name", "Civica");
        c.putObject("feed").put("kind", "workable").put("token", "civica-uk-ltd-1").put("how", "t");
        c.putArray("anchors").add("Birmingham");
        CareerCut.Cut cut = new CareerCut.Cut(List.of("Payroll"), List.of(), List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(), 0, 0, "you", "", "");
        Found read = CareerFind.readCompany(c, cut);
        assertEquals("workable:civica-uk-ltd-1", read.feed().label());
        assertTrue(searches.isEmpty(), "no web search for the employer's name: " + searches);
        assertTrue(c.has("feed"), "the board stays the company's");
    }

    @Test void theSitesOtherRequestsWaitAsLongAsItAsked() {
        answers(WIDGET, answer(WIDGET, 429, "Too many requests", "20"), answer(WIDGET, 200, CareerSourcesTest.recorded("workable-widget.json"), ""));
        CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of());   // it asked once for twenty seconds, then answered
        String other = "https://apply.workable.com/api/v1/widget/accounts/another";
        answers(other, answer(other, 200, "{\"name\": \"Another\", \"jobs\": []}", ""));
        waited.clear();
        CareerSources.read(new Feed("workable", "another", "t"), "Another", List.of());
        assertTrue(waited.isEmpty() || waited.get(0) <= 20_000, "the site's next request waits no longer than it named: " + waited);
        // one that still says "later" after being asked again rests: aSiteThatStillSaysLaterRestsAndTheSearchWaitsOnItNoMore (0.8.4)
    }

    @Test void aSiteThatStillSaysLaterRestsAndTheSearchWaitsOnItNoMore() {
        // seeker 34's paid search, 2026-11-26: Workable answered 429 to every account asked, each costing two waits of thirty seconds
        answers(WIDGET, answer(WIDGET, 429, "Too many requests", "60"));
        String other = "https://apply.workable.com/api/v1/widget/accounts/another-account";
        answers(other, answer(other, 200, CareerSourcesTest.recorded("workable-widget.json"), ""));
        Found f = CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of());
        assertTrue(f.note().startsWith("Workable asked the library to come back later"), f.note());
        assertEquals(List.of(CareerSources.LATER_MAX_MS), waited, "it asked for a minute: one wait of the most a search waits, then no second");
        assertEquals(2, asked.stream().filter(WIDGET::equals).count());
        waited.clear(); asked.clear();
        Found next = CareerSources.read(new Feed("workable", "another-account", "t"), "Another", List.of());
        assertTrue(asked.stream().noneMatch(u -> u.contains("apply.workable.com/api")), "no request to a site that rests: " + asked);
        assertTrue(waited.isEmpty(), "and no wait for it: " + waited);
        assertTrue(next.note().startsWith("Workable asked the library to come back later"), "its employer keeps its board, read next time: " + next.note());
    }

    @Test void inASearchASiteThatSaidLaterIsAnsweredLaterAtOnceAndItsCandidatesAreCheckedOnceAtTheEnd() {
        // seeker 34's paid search and seeker 58's free one, 2026-11-26: Workable answered 429 to everything; each ask cost a minute (0.8.4)
        String mlabs = "https://apply.workable.com/api/v1/widget/accounts/mlabs";
        answers(mlabs, answer(mlabs, 429, "Too many requests", "40"));
        CareerSources.SEARCH = q -> List.of(new CareerSources.Hit("Product Security Engineer - MLabs", "https://apply.workable.com/mlabs/j/0A1B2C3D4E/"));
        CareerSources.Wanted w = new CareerSources.Wanted(t -> true, p -> true, 60);
        List<CareerSources.Discovered> ds = CareerSources.wanting(w, () -> CareerSources.discover(List.of("\"Security Engineer\""), List.of(), 5, t -> true,
                d -> CareerSources.stillListed(d, List.of()), new int[2]));
        assertEquals(2, asked.stream().filter(mlabs::equals).count(), "asked once in the search (its board's name and its list are one ask), once more at the end: " + asked);
        assertEquals(1, waited.size(), "one wait in the whole search, at the end: " + waited);
        assertTrue(waited.get(0) <= CareerSources.LATER_SEARCH_MS, waited.toString());
        assertEquals(1, ds.size());
        assertFalse(ds.get(0).checked(), "still \"later\" at the end: carried unchecked");
        // a site whose time is past what the search may still wait is not waited for at all
        CareerSources.reset(); asked.clear(); waited.clear();
        String trail = "https://apply.workable.com/api/v1/widget/accounts/trailofbits";
        answers(trail, answer(trail, 429, "Too many requests", "600"));
        CareerSources.SEARCH = q -> List.of(new CareerSources.Hit("Security Engineer 1 - Trail of Bits", "https://apply.workable.com/trailofbits/j/9F8E7D6C5B/"));
        List<CareerSources.Discovered> far = CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60), () -> CareerSources.discover(List.of("\"Security Engineer\""), List.of(), 5,
                t -> true, d -> CareerSources.stillListed(d, List.of()), new int[2]));
        assertTrue(waited.isEmpty(), "no wait: " + waited);
        assertEquals(1, asked.stream().filter(trail::equals).count(), "its board's name and its list are one ask, and nothing more: " + asked);
        assertFalse(far.get(0).checked());
    }

    @Test void inASearchASiteThatSaysLaterAgainRestsTwiceAsLong() {
        answers(WIDGET, answer(WIDGET, 429, "Too many requests", "10"));
        CareerSources.Wanted w = new CareerSources.Wanted(t -> true, p -> true, 60);
        CareerSources.wanting(w, () -> CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of()));
        long first = w.laterSites().get("apply.workable.com") - System.currentTimeMillis();
        CareerSources.reset();   // its time has come
        CareerSources.wanting(w, () -> CareerSources.read(new Feed("workable", "civica-uk-ltd-1", "t"), "Civica", List.of()));
        long second = w.laterSites().get("apply.workable.com") - System.currentTimeMillis();
        assertTrue(first > 8_000 && first <= 10_000 && second > 18_000 && second <= 20_000, "ten seconds, then twenty: " + first + ", " + second);
        assertTrue(waited.isEmpty(), "and the search waited for neither: " + waited);
    }

    @Test void inASearchTheSameAskIsMadeOnceForABoardsNameAndItsList() {
        answers(WIDGET, answer(WIDGET, 200, CareerSourcesTest.recorded("workable-widget.json"), ""));
        CareerSources.SEARCH = q -> List.of(new CareerSources.Hit("Senior Developer - Civica", "https://apply.workable.com/civica-uk-ltd-1/j/0A1B2C3D4E/"));
        CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60), () -> CareerSources.discover(List.of("\"Developer\""), List.of(), 5, t -> true,
                d -> CareerSources.stillListed(d, List.of()), new int[2]));
        assertEquals(1, asked.stream().filter(WIDGET::equals).count(), "hiring() asks the account for its name, stillListed() for its list: one answer serves both: " + asked);
    }

    @Test void aBoardGuessedByNameWhoseSystemSaidLaterIsNotKnownYetNeverNone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String guess = "https://apply.workable.com/api/v1/widget/accounts/acmewidgets";
        answers(guess, answer(guess, 429, "Too many requests", "60"));
        CareerLookups.Run run = CareerLookups.begin(store);
        try {
            Found f = CareerSources.wanting(new CareerSources.Wanted(t -> true, p -> true, 60), () -> CareerSources.openingsByName("Acme Widgets", List.of()));
            assertNull(f.feed());
            assertTrue(f.note().startsWith("its board is not known yet"), f.note());
            assertEquals("", CareerLookups.noBoard("Acme Widgets"), "nothing is kept of it: the next search looks again");
        } finally { CareerLookups.end(run); }
        assertTrue(waited.isEmpty(), waited.toString());
    }

    @Test void oneSitesWaitHoldsUpNoOtherSite() throws Exception {
        CareerSources.SLEEP = ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        CareerSources.POLITE_MS = 800;
        String a = "https://apply.workable.com/api/v1/widget/accounts/x", b = "https://boards-api.greenhouse.io/v1/boards/y/jobs?content=true";
        answers(a, answer(a, 200, "{}", "")); answers(b, answer(b, 200, "{\"jobs\": []}", ""));
        CareerSources.get(a);   // the site's last request: its next waits 0.8 s
        Thread slow = new Thread(() -> CareerSources.get(a));
        slow.start();
        Thread.sleep(100);
        long t0 = System.currentTimeMillis();
        CareerSources.get(b);
        long took = System.currentTimeMillis() - t0;
        slow.join();
        assertTrue(took < 400, "another site's request went while the first site's waited: " + took + " ms");
    }

    @Test void aWaitIsReadAsSecondsOrADate() {
        assertEquals(7000, CareerSources.laterMs("7"));
        assertEquals(CareerSources.LATER_DEFAULT_MS, CareerSources.laterMs(""));
        assertEquals(CareerSources.LATER_MAX_MS, CareerSources.laterMs("3600"));
        assertEquals(CareerSources.LATER_DEFAULT_MS, CareerSources.laterMs("soon"));
        assertEquals(1000, CareerSources.laterMs("Wed, 21 Oct 2015 07:28:00 GMT"), "a day already past: a second");
    }
}
