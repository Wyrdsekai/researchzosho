package org.researchzosho.drive.aws;

import org.junit.jupiter.api.Test;
import org.researchzosho.HangingServer;
import org.researchzosho.Stopping;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The request that goes to Amazon Bedrock ends as every other model call does: at a stop, within seconds, and at its time limit. */
class BedrockStopTest {

    @Test
    void aStopEndsTheRequestToBedrockInFlight() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.SILENT)) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Object> ended = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try { ended.set(Stopping.within(stop::get, () -> {
                    try { return Bedrock.real().send("POST", URI.create(s.url() + "/model/placeholder/converse"), Map.of("content-type", "application/json"), "{}".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)); }
                    catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
                })); } catch (Throwable e) { ended.set(e); }
            });
            t.start();
            assertTrue(s.awaitHung(10));
            long asked = System.nanoTime();
            stop.set(true);
            t.join(10_000);
            assertFalse(t.isAlive(), "the request ended after the stop");
            assertTrue((System.nanoTime() - asked) / 1_000_000 < 3_000);
            assertInstanceOf(Stopping.Requested.class, ended.get(), String.valueOf(ended.get()));
            assertTrue(s.awaitClosed(5), "the connection was closed");
        }
    }

    @Test
    void theTimeLimitEndsARequestBedrockNeverAnswers() throws Exception {
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY)) {
            long t0 = System.nanoTime();
            Exception e = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertThrows(Exception.class,
                    () -> Bedrock.real().send("POST", URI.create(s.url() + "/model/placeholder/converse"), Map.of(), "{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2))));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 8_000);
            assertTrue(String.valueOf(e.getMessage()).contains("within 2 seconds"), String.valueOf(e));
        }
    }
}
