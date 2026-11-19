package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every use is closed, and what it came to told (0.8.0 review, round A): a library keeps the end of a use until the service takes it,
 * across its own restart; the service closes a use nobody will end (a library killed in the middle of a chat turn) as failed; what a use
 * came to is written to the outbox in the same step as its close, or the use stays open; and what was counted under a use after it
 * closed (a call that began before) is told again with the use's whole.
 */
class UseEndingTest {
    AccountStore store; HttpServer up;
    final String serviceWas = ServiceAccess.service;

    @AfterEach void stop() throws Exception {
        if (store != null) store.close();
        if (up != null) up.stop(0);
        ServiceAccess.service = serviceWas;
        ServiceUses.ENDING.set(null);
        ServiceUses.RETRY_MS = 2_000;
    }

    /** A spending answer that writes down what it is told, and fails to when told to. */
    static final class Told implements Spending {
        final List<String> told = new CopyOnWriteArrayList<>();
        volatile boolean fail;
        @Override public Answer mayStart(Use use) { return new Answer(true, "", -1, -1); }
        @Override public void spent(Use use, Actual actual) throws IOException { if (fail) throw new IOException("the outbox could not be written"); told.add(use.id() + "=" + actual.micros()); }
        @Override public void spentAgain(Use use, Actual actual) { told.add(use.id() + "~" + actual.micros()); }
    }

    AccountStore.Use use(String id, String job, long openedAt) throws IOException {
        AccountStore.Use u = new AccountStore.Use(id, "acct_ann", "lib_ann", job.isEmpty() ? SpendPurse.CHAT : SpendPurse.RESEARCH, "", "operator", job, 0, 0, -1, -1, "open", "", openedAt, 0, 0);
        store.addUse(u);
        return u;
    }

    void spend(String use, long micros) throws IOException {
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_ann", use, "model", "operator", "default", 100, 0, 10, 1, 0, micros, false)));
    }

    @Test
    void whatAUseCameToIsWrittenWithItsCloseOrItStaysOpen(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        Told t = new Told();
        use("use_chat1", "", System.currentTimeMillis());
        spend("use_chat1", 40_000);
        t.fail = true;
        assertThrows(IOException.class, () -> Uses.end(store, t, "use_chat1", "done"));
        assertEquals("open", store.use("use_chat1").state(), "not closed without what it came to: its end is taken again");
        t.fail = false;
        assertEquals("done", Uses.end(store, t, "use_chat1", "done").state());
        assertEquals(List.of("use_chat1=40000"), t.told);
        assertNull(Uses.end(store, t, "use_chat1", "done"), "closed once");
    }

    @Test
    void theServiceClosesTheUsesNobodyWillEndAndTellsWhatCameLater(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        Told t = new Told();
        long now = System.currentTimeMillis(), hour = 3_600_000L;
        use("use_turn_lost", "", now - 3 * hour);   // a chat turn whose library was killed three hours ago
        spend("use_turn_lost", 10_000);
        use("use_turn_now", "", now - 60_000);   // a turn going on now
        use("use_run_going", "job_1", now - 3 * hour);   // a run that goes on, and spends
        spend("use_run_going", 5_000);
        use("use_run_lost", "job_2", now - 30 * hour);   // a run whose job is gone: two days of nothing
        store.addUsage(List.of(new AccountStore.Usage(now - 29 * hour, "acct_ann", "lib_ann", "use_run_lost", "model", "operator", "default", 1, 0, 1, 1, 0, 1_000, false)));
        assertEquals(2, Uses.sweep(store, t, now));
        assertEquals("failed", store.use("use_turn_lost").state());
        assertEquals("failed", store.use("use_run_lost").state());
        assertEquals("open", store.use("use_turn_now").state());
        assertEquals("open", store.use("use_run_going").state());
        assertTrue(t.told.containsAll(List.of("use_turn_lost=10000", "use_run_lost=1000")), t.told.toString());

        // a call that began before its use ended is counted after: the use's whole is told again, once
        Uses.end(store, t, "use_turn_now", "done");
        spend("use_turn_now", 7_000);
        Uses.sweep(store, t, System.currentTimeMillis());
        assertEquals(7_000, store.use("use_turn_now").actualMicros());
        assertTrue(t.told.contains("use_turn_now~7000"), t.told.toString());
        int before = t.told.size();
        Uses.sweep(store, t, System.currentTimeMillis());
        assertEquals(before, t.told.size(), "told once");
    }

    /** A stand-in for the service's end of a use: it refuses the first {@code refused} ends told it, takes the rest, and writes down what it took. */
    String standIn(int refused, AtomicInteger asked, List<String> taken) throws IOException {
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext(Uses.END, x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = asked.incrementAndGet();
            byte[] b = (n <= refused ? "{\"error\":\"the service is starting\"}" : "{\"closed\":true}").getBytes(StandardCharsets.UTF_8);
            if (n > refused) taken.add(body);
            x.sendResponseHeaders(n <= refused ? 503 : 200, b.length); x.getResponseBody().write(b); x.close();
        });
        up.start();
        return "http://127.0.0.1:" + up.getAddress().getPort();
    }

    /**
     * What the suite's run met (2026-11-18): an earlier test ran a library in this same process under a service of its own, which is gone
     * now, and left an end it could not tell. That end goes on being told to the service it was for, on its own back-off; it is never
     * told to another service, and it holds back no other end.
     */
    @Test
    void anEndLeftForAServiceThatIsGoneHoldsBackNoOtherEnd(@TempDir Path tmp) throws Exception {
        ServiceUses.RETRY_MS = 200;
        ServiceUses.ENDING.set(null);
        ServiceAccess.service = "http://127.0.0.1:1";   // the earlier service: nobody there now
        ServiceUses.end("use_left_behind", "done");
        Thread.sleep(1_700);   // told and refused a few times: its back-off has grown
        AtomicInteger asked = new AtomicInteger();
        List<String> taken = new CopyOnWriteArrayList<>();
        ServiceAccess.service = standIn(4, asked, taken);
        ServiceUses.end("use_turn_1", "done");
        long until = System.currentTimeMillis() + 20_000;
        while (taken.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50);
        Thread.sleep(4_000);   // longer than the end left behind waits between its tries now: it would have been told here by now
        assertEquals(1, taken.size(), "taken in the end, after " + asked.get() + " asks: " + taken);
        assertTrue(taken.get(0).contains("use_turn_1"), "this service is told its own end, and no other: " + taken);
        assertEquals(5, asked.get(), "four refused, the fifth taken, nothing else asked");
    }

    @Test
    void aLibraryKeepsAUsesEndUntilTheServiceTakesIt(@TempDir Path tmp) throws Exception {
        AtomicInteger asked = new AtomicInteger();
        List<String> taken = new CopyOnWriteArrayList<>();
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        up.createContext(Uses.END, x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = asked.incrementAndGet();
            byte[] b = (n <= 4 ? "{\"error\":\"the service is starting\"}" : "{\"closed\":true}").getBytes(StandardCharsets.UTF_8);
            if (n > 4) taken.add(body);
            x.sendResponseHeaders(n <= 4 ? 503 : 200, b.length); x.getResponseBody().write(b); x.close();
        });
        up.start();
        ServiceAccess.service = "http://127.0.0.1:" + up.getAddress().getPort();
        Path dir = tmp.resolve("library").resolve("catalog").resolve(ServiceUses.ENDING_DIR);
        ServiceUses.RETRY_MS = 200;
        ServiceUses.ENDING.set(dir);
        ServiceUses.end("use_turn_1", "done");
        long until = System.currentTimeMillis() + 20_000;
        while (taken.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100);
        assertEquals(1, taken.size(), "taken in the end, after " + asked.get() + " asks");
        assertTrue(taken.get(0).contains("use_turn_1") && taken.get(0).contains("done"), taken.get(0));
        Thread.sleep(300);
        assertTrue(!Files.isDirectory(dir) || Files.list(dir).findAny().isEmpty(), "nothing kept once taken");

        // the library stopped before the service took it: the end is in its folder, and is sent when it starts again
        ServiceAccess.service = "http://127.0.0.1:1";   // nobody there
        ServiceUses.end("use_turn_2", "stopped");
        Thread.sleep(500);
        assertTrue(Files.isRegularFile(dir.resolve("use_turn_2.json")), "kept in the library's folder");
        ServiceAccess.service = "http://127.0.0.1:" + up.getAddress().getPort();   // the library starts again, under its service (as LibrarianDaemon does: the address, then the ends)
        ServiceUses.forget();   // a new process knows nothing of the end but its folder
        ServiceUses.keepEndsIn(dir.getParent());
        until = System.currentTimeMillis() + 10_000;
        while (taken.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(100);
        assertTrue(taken.size() == 2 && taken.get(1).contains("use_turn_2") && taken.get(1).contains("stopped"), taken.toString());
    }
}
