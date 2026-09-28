package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.HangingServer;
import org.researchzosho.Stopping;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A page fetch and a search that a site never finishes answering end at a stop within seconds, and at their time limit without one. */
class FetchStopTest {

    @BeforeEach void loopback() { Fetch.allowLoopback = true; }
    @AfterEach void back() { Fetch.allowLoopback = false; WebSearchTool.endpointOverride = null; }

    static Object[] stoppedWhileHanging(HangingServer server, Callable<Object> call) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Object> ended = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try { ended.set(Stopping.within(stop::get, () -> { try { return call.call(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } })); }
            catch (Throwable e) { ended.set(e); }
        });
        t.start();
        assertTrue(server.awaitHung(10), "the request reached the site");
        long asked = System.nanoTime();
        stop.set(true);
        t.join(10_000);
        assertFalse(t.isAlive(), "the fetch ended after the stop");
        return new Object[]{ended.get(), (System.nanoTime() - asked) / 1_000_000};
    }

    @Test
    void aStopEndsAPageFetchWhoseSiteNeverFinishes() throws Exception {
        for (HangingServer.Mode mode : HangingServer.Mode.values()) {
            try (HangingServer s = new HangingServer(mode)) {
                Object[] r = stoppedWhileHanging(s, () -> Fetch.get(s.url() + "/placeholder-page", Duration.ofMinutes(2)));
                assertInstanceOf(Stopping.Requested.class, r[0], mode + ": " + r[0]);
                assertTrue((long) r[1] < 3_000, mode + ": " + r[1] + " ms");
                assertTrue(s.awaitClosed(5), mode + ": the connection was closed");
            }
        }
    }

    @Test
    void aPageWhoseBodyStopsComingEndsWhenNothingHasComeForTheFetchesTimeLimit() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY)) {
            long t0 = System.nanoTime();
            Exception e = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(Exception.class, () -> Fetch.get(s.url() + "/placeholder-page", Duration.ofSeconds(2))));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
            assertTrue(String.valueOf(e.getMessage()).contains("sent nothing for 2 seconds"), String.valueOf(e));
        }
    }

    @Test
    void aStopEndsASearchInFlight() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.SILENT)) {
            WebSearchTool.endpointOverride = s.url();
            WebSearchTool search = new WebSearchTool();
            Object[] r = stoppedWhileHanging(s, () -> search.execute(new ObjectMapper().createObjectNode().put("query", "placeholder gears")));
            assertInstanceOf(Stopping.Requested.class, r[0], "a stop is no unreachable search backend: " + r[0]);
            assertTrue((long) r[1] < 3_000, r[1] + " ms");
        }
    }
}
