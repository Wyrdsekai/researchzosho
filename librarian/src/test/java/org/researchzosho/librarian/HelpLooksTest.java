package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Help says at once what the library last found of the model, the search engine and Docker, and looks again off the request (0.8.4).
 * It made the three looks while the person waited, at every ask: 7 seconds with the two servers giving no connection, and never an
 * answer with them holding the question and Docker hung (2026-11-26).
 */
class HelpLooksTest {

    static final String CLOSED = "http://127.0.0.1:1";   // refused at once: no connection
    private final Predicate<String> realModel = Help.MODEL_ANSWERS, realSearx = Help.SEARX_ANSWERS;
    private final Supplier<String> realOwn = Help.OWN_SEARX;
    private final Searx.Runner realRunner = Searx.runner;
    private final LongSupplier realClock = KeptLook.NOW;

    @BeforeEach
    void quiet() { Help.forgetLooks(); }

    @AfterEach
    void restore() {
        Help.MODEL_ANSWERS = realModel; Help.SEARX_ANSWERS = realSearx; Help.OWN_SEARX = realOwn; Searx.runner = realRunner; KeptLook.NOW = realClock;
        Help.forgetLooks(); Config.provide(Map.of());
    }

    /** How long a held look waits to be let go, and how long a test waits for what it asserts: far beyond what a busy machine needs. */
    static final int HOLD_S = 120, WAIT_S = 60;

    private static boolean held(CountDownLatch l) { try { return !l.await(HOLD_S, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; } }

    @Test
    void helpAnswersAtOnceWhileEveryLookIsHeldAndOneLookServesEveryAsk(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.provide(Map.of("RESEARCHZOSHO_DRIVE", "http://model.example:8080", "RESEARCHZOSHO_SEARXNG", "http://search.example:8888"));
        AtomicInteger models = new AtomicInteger(), searches = new AtomicInteger(), dockers = new AtomicInteger();
        CountDownLatch model = new CountDownLatch(1), search = new CountDownLatch(1), docker = new CountDownLatch(1);
        CountDownLatch modelBegan = new CountDownLatch(1), searchBegan = new CountDownLatch(1), dockerBegan = new CountDownLatch(1);   // each look runs on a thread of its own
        Help.MODEL_ANSWERS = b -> { models.incrementAndGet(); modelBegan.countDown(); held(model); return false; };
        Help.SEARX_ANSWERS = b -> { searches.incrementAndGet(); searchBegan.countDown(); held(search); return false; };
        Help.OWN_SEARX = () -> { dockers.incrementAndGet(); dockerBegan.countDown(); held(docker); return "stopped"; };
        ExecutorService askers = Executors.newFixedThreadPool(4);
        try {
            // twelve asks from four askers at once, while each look is held until the test lets it go: each answers, saying what is not known yet
            List<Future<String>> said = new ArrayList<>();
            for (int i = 0; i < 12; i++) said.add(askers.submit(() -> Help.state(store)));
            for (Future<String> f : said) {
                String st = f.get(WAIT_S, TimeUnit.SECONDS);
                assertTrue(st.contains(" at http://model.example:8080; whether it answers is not known yet: the library is looking now, so ask again in a few seconds."), st);
                assertTrue(st.contains("Whether SearXNG at http://search.example:8888 answers searches is not known yet"), st);
                assertFalse(st.contains("With only Wikipedia's search"), "what is not known is not taken for down: " + st);
            }
            assertTrue(modelBegan.await(WAIT_S, TimeUnit.SECONDS) && searchBegan.await(WAIT_S, TimeUnit.SECONDS), "the two looks began");   // and are held: no other can begin
            assertEquals(1, models.get(), "one look at the model for every ask while it was under way");
            assertEquals(1, searches.get(), "one look at the search engine");
            assertEquals(0, dockers.get(), "Docker is not asked while nothing says the search engine is down");
            model.countDown(); search.countDown();
            Help.settleLooks();
            String st = Help.state(store);
            assertTrue(st.contains("and it does not answer now: research runs and the chat need it. Last looked at ") && st.contains("; ask again in a few seconds for a fresh look."), st);
            assertTrue(st.contains("No SearXNG answers searches at http://search.example:8888; whether the library's own is installed is not known yet."), "the look at Docker is under way now: " + st);
            assertTrue(dockerBegan.await(WAIT_S, TimeUnit.SECONDS), "the look at Docker began");
            assertEquals(1, dockers.get());
            for (int i = 0; i < 5; i++) Help.state(store);
            assertEquals(1, dockers.get(), "one look at Docker while it is under way");
            docker.countDown();
            Help.settleLooks();
            st = Help.state(store);
            assertTrue(st.contains("the library's own is installed but stopped, and researchzosho search start starts it."), "the result changes after the look: " + st);
            assertTrue(Help.lookup(store, "how do I update").contains(" at http://model.example:8080, and it does not answer now. Last looked at "), "the short state says the same");
        } finally { model.countDown(); search.countDown(); docker.countDown(); askers.shutdownNow(); }
    }

    @Test
    void aModelThatCameBackIsSeenWhenTheyAskAgainAFewSecondsLater(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.provide(Map.of("RESEARCHZOSHO_DRIVE", "http://model.example:8080", "RESEARCHZOSHO_SEARXNG", CLOSED));
        boolean[] up = {false};
        Help.MODEL_ANSWERS = b -> up[0];
        Help.state(store); Help.settleLooks();
        assertTrue(Help.state(store).contains("and it does not answer now"));
        up[0] = true;   // the person starts the model, and asks again some seconds later
        long later = System.currentTimeMillis() + Help.MODEL_AGAIN_MS + 1_000;
        KeptLook.NOW = () -> later;
        Help.state(store);
        Help.settleLooks();
        String st = Help.state(store);
        assertTrue(st.contains(" at http://model.example:8080, and it answers."), st);
    }

    @Test
    void theRealLooksAtServersThatTakeTheConnectionAndNeverAnswerDoNotHoldHelp(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Searx.runner = cmd -> { throw new IOException("Cannot run program \"docker\": error=2, No such file or directory"); };   // no Docker
        String m, s;
        try (ServerSocket model = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); ServerSocket search = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            model.setSoTimeout(WAIT_S * 1000); search.setSoTimeout(WAIT_S * 1000);   // a look that never comes fails the test rather than holding it
            m = "http://127.0.0.1:" + model.getLocalPort(); s = "http://127.0.0.1:" + search.getLocalPort();
            Config.provide(Map.of("RESEARCHZOSHO_DRIVE", m, "RESEARCHZOSHO_SEARXNG", s));
            Help.state(store);   // the two looks start: the model's models list and a search, each with its own bounds
            try (Socket a = model.accept(); Socket b = search.accept()) {   // both have connected, and their questions are held here
                for (int i = 0; i < 3; i++) {   // Help says what is not known while the looks wait: one that waited for a look would say what it found
                    String st = Help.state(store);
                    assertTrue(st.contains("whether it answers is not known yet") && st.contains("answers searches is not known yet"), st);
                }
            }   // the servers close the connections without a word
        }   // and stop listening: a client that asks again is refused
        Help.settleLooks();
        Help.state(store); Help.settleLooks();   // the look at Docker, now that the search engine is known not to answer
        String st = Help.state(store);
        assertTrue(st.contains(m + ", and it does not answer now"), st);
        assertTrue(st.contains("No SearXNG answers searches at " + s + "; researchzosho search start installs the library's own."), "no Docker: answered at once, as absent: " + st);
    }

    @Test
    void serversThatGiveNoConnectionAndADockerThatNeverAnswers(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.provide(Map.of("RESEARCHZOSHO_DRIVE", CLOSED, "RESEARCHZOSHO_SEARXNG", "http://127.0.0.1:2"));   // the real looks: refused at once
        CountDownLatch never = new CountDownLatch(1), began = new CountDownLatch(1);
        AtomicInteger dockers = new AtomicInteger();
        Searx.runner = cmd -> { dockers.incrementAndGet(); began.countDown(); never.await(HOLD_S, TimeUnit.SECONDS); return new Searx.Result(0, "true"); };   // a daemon that hangs
        Help.OWN_SEARX = () -> Searx.state(Duration.ofMillis(200));   // its bound, short for the test (the program gives five seconds)
        try {
            Help.state(store); Help.settleLooks();
            String st = Help.state(store);   // starts the look at Docker, which is cut at its bound: this ask says it is not known yet
            assertTrue(st.contains(CLOSED + ", and it does not answer now") && st.contains("No SearXNG answers searches at http://127.0.0.1:2; whether the library's own is installed is not known yet."), st);
            Help.settleLooks();
            st = Help.state(store);
            assertTrue(st.contains("No SearXNG answers searches at http://127.0.0.1:2; whether the library's own is installed is not known: Docker did not answer within 5 seconds."), st);
            assertTrue(began.await(WAIT_S, TimeUnit.SECONDS), "the docker command began");
            assertEquals(1, dockers.get(), "one docker command, cut at its bound");
            String ja = Help.state(store, Chrome.in("ja"));
            assertTrue(ja.contains("Docker が 5 秒以内に応答しませんでした。") && ja.contains("最後に確かめたのは "), ja);
        } finally { never.countDown(); }
    }
}
