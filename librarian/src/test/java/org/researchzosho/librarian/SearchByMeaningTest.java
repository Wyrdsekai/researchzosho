package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The standing of search by meaning is said in words: off, on, configured and not answering (the silent case), and not known yet. The
 * look at the server is made off the request: a status answers at once from the last look, whatever the server does (2026-11-26: with
 * the server down the status made the look itself and the first one of each minute took 6.2 seconds).
 */
class SearchByMeaningTest {

    private static final ObjectMapper M = new ObjectMapper();
    private final Supplier<String> realSetting = SearchByMeaning.setting;
    private final Predicate<String> realAnswers = SearchByMeaning.answers;
    private final LongSupplier realClock = KeptLook.NOW;

    @BeforeEach
    void quiet() { SearchByMeaning.settle(); SearchByMeaning.reprobe(); }   // a look an earlier test started ends before this one stands anything in

    @AfterEach
    void restore() { SearchByMeaning.settle(); SearchByMeaning.setting = realSetting; SearchByMeaning.answers = realAnswers; KeptLook.NOW = realClock; SearchByMeaning.reprobe(); }

    private static String word(LibraryProtocol p) throws Exception { return p.status(M.createObjectNode()).get("search_by_meaning").asText(); }

    @Test
    void theFourStandings(@TempDir Path tmp) throws Exception {
        SearchByMeaning.setting = () -> null;
        SearchByMeaning.Standing yet = SearchByMeaning.standing();
        assertEquals(SearchByMeaning.NOT_YET, yet.state(), "before the first look has ended nothing is known");
        assertNull(yet.lookedAt());
        assertTrue(yet.sentence().startsWith("search by meaning: not known yet"), yet.sentence());
        SearchByMeaning.reprobe();
        SearchByMeaning.Standing off = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals("off", off.state());
        assertTrue(off.sentence().contains("by words only") && off.sentence().contains("researchzosho embed start"), off.sentence());

        SearchByMeaning.reprobe();
        SearchByMeaning.setting = () -> "http://embed.example:8080";
        SearchByMeaning.answers = base -> false;
        SearchByMeaning.Standing silent = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals("not answering", silent.state());
        assertTrue(silent.sentence().startsWith("search by meaning: OFF") && silent.sentence().contains("http://embed.example:8080") && silent.sentence().contains("by words only"), silent.sentence());

        SearchByMeaning.reprobe();
        SearchByMeaning.answers = base -> true;
        SearchByMeaning.Standing on = SearchByMeaning.standingNow(Duration.ofSeconds(60));
        assertEquals("on", on.state());
        assertTrue(on.sentence().startsWith("search by meaning: on"), on.sentence());
        assertNotNull(on.lookedAt());

        // the wire carries the same word, and when the look that found it ended
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode status = new LibraryProtocol(store).status(M.createObjectNode());
        assertEquals("on", status.get("search_by_meaning").asText());
        assertEquals(on.lookedAt().toString(), status.path("search_by_meaning_looked_at").asText());
    }

    @Test
    void aStatusAnswersAtOnceWhileTheLookWaitsAndOneLookServesThemAll(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        AtomicInteger looks = new AtomicInteger();
        CountDownLatch held = new CountDownLatch(1), began = new CountDownLatch(1);   // the look runs on a thread of its own
        SearchByMeaning.setting = () -> "http://127.0.0.1:9";
        SearchByMeaning.answers = base -> { looks.incrementAndGet(); began.countDown(); try { held.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return false; };
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            // forty statuses from eight callers at once, while the look is held until the test lets it go: each says what is known
            List<Future<String>> words = new ArrayList<>();
            for (int i = 0; i < 40; i++) words.add(callers.submit(() -> word(p)));
            for (Future<String> w : words) assertEquals(SearchByMeaning.NOT_YET, w.get(60, TimeUnit.SECONDS), "a status says what is known and does not wait on the look");
            assertTrue(began.await(60, TimeUnit.SECONDS), "the look began");   // and is held: no other can begin
            assertEquals(1, looks.get(), "one look serves every status that came while it was under way");
            assertEquals("", p.status(M.createObjectNode()).path("search_by_meaning_looked_at").asText(), "no look has ended: no time is said");
        } finally { held.countDown(); callers.shutdownNow(); }
        SearchByMeaning.settle();
        assertEquals("not answering", word(p), "the status after the look says what it found");
        for (int i = 0; i < 5; i++) word(p);
        SearchByMeaning.settle();   // a look one of them started, wrongly, would have ended and counted by now
        assertEquals(1, looks.get(), "within the minute no status starts another look");

        // a minute later: the old word at once, and one new look behind it
        long minuteOn = System.currentTimeMillis() + 61_000;
        KeptLook.NOW = () -> minuteOn;
        CountDownLatch again = new CountDownLatch(1), beganAgain = new CountDownLatch(1);
        SearchByMeaning.answers = base -> { looks.incrementAndGet(); beganAgain.countDown(); try { again.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return true; };
        try {
            for (int i = 0; i < 10; i++) assertEquals("not answering", word(p), "the last look's word while the new one is under way");
            assertTrue(beganAgain.await(60, TimeUnit.SECONDS), "the new look began");
            assertEquals(2, looks.get(), "the first status of the new minute started one look, and the others none");
        } finally { again.countDown(); }
        SearchByMeaning.settle();
        assertEquals("on", word(p), "the server answers now, and the status says so after the look");
    }

    @Test
    void aServerThatTakesTheConnectionAndNeverAnswersDoesNotHoldTheStatus(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            silent.setSoTimeout(60_000);   // a look that never comes fails the test rather than holding it
            String at = "http://127.0.0.1:" + silent.getLocalPort();
            SearchByMeaning.setting = () -> at;   // the look itself is the real one: the embeddings call, with the look's own bounds
            assertEquals(SearchByMeaning.NOT_YET, word(p));
            try (Socket held = silent.accept()) {   // the look has connected, and its question is held here
                for (int i = 0; i < 3; i++) assertEquals(SearchByMeaning.NOT_YET, word(p), "a status that waited for the look would say what it found");
            }   // the server closes the connection without a word
        }
        SearchByMeaning.settle();
        assertEquals("not answering", word(p), "a server that never answered is said as not answering");
        assertFalse(p.status(M.createObjectNode()).path("search_by_meaning_looked_at").asText().isEmpty());
    }

    @Test
    void theHelpSaysWhatIsNotKnownYet(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        CountDownLatch held = new CountDownLatch(1);
        SearchByMeaning.setting = () -> "http://127.0.0.1:9";
        SearchByMeaning.answers = base -> { try { held.await(120, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return false; };
        try {
            String st = Help.state(store);
            assertTrue(st.contains("Whether search by meaning answers is not known yet: the library asks when it starts and once a minute after that."), st);
            String ja = Help.state(store, Chrome.in("ja"));
            assertTrue(ja.contains("意味による検索が応答するかどうかは、まだわかりません。"), ja);
        } finally { held.countDown(); }
    }
}
