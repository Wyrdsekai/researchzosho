package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone's turn is let go before its answer is written (2026-11-25: a busy machine answered the next message "A reply is still being
 * written", because the turn was let go only after the answer had gone). Keyed on states, not on time: after each answer is written, the
 * door's thread waits until the next message has been checked for a turn going, which is the moment the old order lost.
 */
class PhoneTurnTest {

    @Test
    void theNextMessageSentTheMomentAnAnswerArrivesNeverFindsTheTurnStillHeld(@TempDir Path tmp) throws Exception {
        String asks = PhoneDoor.ASKS;
        Object lock = new Object();
        AtomicInteger checks = new AtomicInteger();
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            // the chat's model is a paid one and the phone may use none: every turn is the library's own line, so no model is asked and
            // hundreds of turns stay cheap; each turn still takes the phone's turn, answers and lets it go
            ModelEntries.Entry sonnet = PhoneCapTest.paid(s, "sonnet", "3/15");
            ModelEntries.put(ModelEntries.DEFAULT, sonnet.drive(), sonnet.model(), "3/15", null, null, null);
            Phones.cap("pixel", 0);
            PhoneDoor.ASKS = "100000 per hour";
            // the page opens a conversation with New conversation, which makes the phone's folder; each message here names one of its own
            assertEquals(200, s.post(s.door(), "/m/new", cookie, "").statusCode());
            PhonePage.TURN_TAKEN = taken -> { synchronized (lock) { checks.incrementAndGet(); lock.notifyAll(); } };
            PhonePage.ANSWERED = () -> {
                synchronized (lock) {
                    int seen = checks.get();
                    long end = System.currentTimeMillis() + 10_000;   // only a bound: the next message always comes in this test
                    try { while (checks.get() == seen && System.currentTimeMillis() < end) lock.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            };
            int n = 300;
            List<String> refused = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (i == n - 1) PhonePage.ANSWERED = () -> { };   // nothing comes after the last one
                HttpResponse<String> r = s.post(s.door(), "/m/ask", cookie, PhoneStandIns.form("session", "C-2026-11-25-00-00-00-" + i, "say", "hello " + i));
                if (r.statusCode() != 200) refused.add(i + ": " + r.statusCode() + " " + r.body());
            }
            assertTrue(refused.isEmpty(), refused.size() + " of " + n + " were refused, the first: " + (refused.isEmpty() ? "" : refused.get(0)));
            assertEquals(n, checks.get(), "every message was checked for a turn going");
            assertTrue(s.lastReply(cookie, "C-2026-11-25-00-00-00-" + (n - 1)).contains("This phone may not use a paid model"), "the last turn was the library's own line");
        } finally {
            PhonePage.TURN_TAKEN = taken -> { };
            PhonePage.ANSWERED = () -> { };
            PhoneDoor.ASKS = asks;
        }
    }
}
