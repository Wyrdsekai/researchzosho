package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measurement, not a check the suite runs (it takes minutes): what one account's long research run does to another account's chat
 * turn, with the two lanes and without them. A stand-in model of two slots that takes two seconds a call, as llama.cpp says on its
 * props page; account A's three runs going, one in each of three libraries of A's; account B's chat turn timed while they go, eight
 * times each way, with what each of B's calls found at the server and how many calls of A's were answered meanwhile. Runs when
 * RZ_MEASURE_LANES=on.
 */
class LanesMeasureTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); }
        finally { NodeGates.LANES = true; for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", Scheduler.NIGHTS)) Config.set(k, ""); }
    }

    HttpResponse<String> post(String key, String path, String body, String type) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void withTheLanes(@TempDir Path tmp) throws Exception { measure(tmp, true); }

    @Test void withoutTheLanes(@TempDir Path tmp) throws Exception { measure(tmp, false); }

    /** The lanes with no slot kept ({@code entry.<name>.kept 0}): a person's call still goes before any background call that waits. */
    @Test void withNoSlotKept(@TempDir Path tmp) throws Exception {
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "KEPT"), "0");
        try { measure(tmp, true); } finally { Config.set(ModelEntries.key(ModelEntries.DEFAULT, "KEPT"), ""); }
    }

    long modelCalls(String account) throws IOException {
        return store.month(account, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.kind().equals("model")).mapToLong(AccountStore.MonthTotal::quantity).sum();
    }

    void measure(Path tmp, boolean lanes) throws Exception {
        Assumptions.assumeTrue("on".equals(System.getenv("RZ_MEASURE_LANES")), "a measurement: RZ_MEASURE_LANES=on");
        NodeGates.LANES = lanes;
        model = new StandInModel();
        model.slots = 2; model.delayMs = 2_000;
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        Path root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1); store.setCap("*", Caps.MONTH_DOLLARS, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made a = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), b = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String kb = store.mintKey(b.account().id(), b.library().id(), "write", "t").secret();
        List<String> ka = new ArrayList<>();   // A keeps three libraries, each with a run going: more calls at once than the server has slots
        ka.add(store.mintKey(a.account().id(), a.library().id(), "write", "t").secret());
        for (int k = 2; k <= 3; k++) ka.add(store.mintKey(a.account().id(), AccountService.newLibrary(store, root, a.account(), "Ann's library " + k).id(), "write", "t").secret());
        post(kb, "/chat", "say=hello", "application/x-www-form-urlencoded");   // B's library started, its first turn not timed
        for (int k = 0; k < 3; k++) {
            HttpResponse<String> r = post(ka.get(k), "/v1/research", "{\"question\":\"When was lighthouse " + k + " of the harbour first lit, and by whom?\"}", "application/json");
            assertEquals(200, r.statusCode(), r.body());
        }
        Thread.sleep(15_000);   // A's runs under way, their readers at the model
        model.atArrival.clear();
        long aBefore = modelCalls(a.account().id()), t0 = System.currentTimeMillis();
        List<Long> turns = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long s0 = System.currentTimeMillis();
            HttpResponse<String> r = post(kb, "/chat", "say=" + URLEncoder.encode("When was it lit? (timed turn " + i + ")", StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
            assertTrue(r.statusCode() == 200 || r.statusCode() == 303, r.statusCode() + " " + r.body());   // the page answers a turn with the conversation, or sends the browser to it
            turns.add(System.currentTimeMillis() - s0);
            Thread.sleep(3_000);
        }
        long window = System.currentTimeMillis() - t0, aCalls = modelCalls(a.account().id()) - aBefore;
        System.out.println("[lanes " + (lanes ? "on, kept " + Config.get(ModelEntries.key(ModelEntries.DEFAULT, "KEPT"), "1") : "off") + "] a 2-slot server, 2 s a call. B's chat turns while A's three runs went: " + turns + " ms"
                + "; what B's calls found at the server: " + model.atArrival
                + "; A's calls answered meanwhile: " + aCalls + " in " + window / 1000 + " s (" + String.format(Locale.ROOT, "%.1f", aCalls * 60_000.0 / window) + " a minute)"
                + "; the server never had more than " + model.most.get() + " calls at once");
    }
}
