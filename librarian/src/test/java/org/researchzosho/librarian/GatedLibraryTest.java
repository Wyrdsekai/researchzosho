package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library of the account service, a process of its own, does its real work through the node's model gate (0.8.0, stage 3, step 2):
 * a research run with its readers, a chat turn, a question sharpened on a page, a translation. Every call it makes to the model carries a use the service let start,
 * so the gate refuses none; every call is counted against that use; and what a use came to is what its calls spent.
 */
class GatedLibraryTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; AccountStore store; AccountService svc; Path root;

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); }
        finally { for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), Scheduler.NIGHTS)) Config.set(k, ""); }
    }

    HttpResponse<String> post(String key, String path, String body, String type) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What the library said of a call that costs made with nothing it is part of, and where it was made. */
    void said(AccountService.Made ann) throws IOException {
        for (String line : Files.readAllLines(LibraryPlaces.log(Path.of(store.library(ann.library().id()).folder())))) if (line.contains("nothing the service let start")) System.out.println("[gated] library: " + line);
    }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(100); }
    }

    @Test
    void everyCallOfALibrarysRealWorkCarriesItsUseAndIsCounted(@TempDir Path tmp) throws Exception {
        model = new StandInModel();
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in");
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        Config.set(Scheduler.NIGHTS, "off");
        root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        NodeGates gates = NodeGates.of(store);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret(), acct = ann.account().id();

        // a research run: the run and its readers
        HttpResponse<String> r = post(key, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit?\"}", "application/json");
        assertEquals(200, r.statusCode(), r.body());
        String job = M.readTree(r.body()).path("job_id").asText();
        AccountStore.Use run = store.uses(acct, 0).stream().filter(u -> u.job().equals(job)).findFirst().orElseThrow();
        try { until("the run ended and its use closed", 180_000, () -> { try { return !store.use(run.id()).open(); } catch (IOException e) { return false; } }); }
        finally { said(ann); }
        AccountStore.Use ran = store.use(run.id());
        System.out.println("[gated] the research run made " + gates.passed.get() + " calls through the gate; the gate refused " + gates.refusedNoUse.get() + " for having no use; it came to " + Caps.dollars(ran.actualMicros()));
        assertTrue(gates.passed.get() > 3, "the run asked the model through the gate: " + gates.passed.get());
        assertTrue(ran.actualMicros() > 0, "what its calls spent is what it came to");

        // the library's own process reaches the web through its web gate on this machine's loopback (stage 3): the gate refuses this machine
        String proxyPort = Files.readString(LibraryPlaces.folder(store.library(ann.library().id())).resolve(ChildLibraries.WEB_GATE_PORT)).strip();
        try (Socket gate = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(proxyPort))) {
            gate.getOutputStream().write("CONNECT 127.0.0.1:22 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            String status = new String(gate.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
            assertEquals("HTTP/1.1 403", status, "the library's web gate, which refuses this machine");
        }

        // a chat turn, through the page as a browser sends it
        long before = gates.passed.get();
        post(key, "/chat", "say=" + "When was the lighthouse lit?", "application/x-www-form-urlencoded");
        until("the chat turn's calls", 60_000, () -> gates.passed.get() > before);
        AccountStore.Use turn = store.uses(acct, 0).stream().filter(u -> u.kind().equals(SpendPurse.CHAT)).findFirst().orElseThrow();
        until("the chat turn's use closed", 60_000, () -> { try { return !store.use(turn.id()).open(); } catch (IOException e) { return false; } });

        // a question sharpened on the research page: the work goes on after the page is answered, under a use of its own
        long asked = gates.passed.get();
        post(key, "/research", "sharpen=1&question=" + URLEncoder.encode("When was the lighthouse of the harbour first lit?", StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
        until("the sharpening's calls", 60_000, () -> gates.passed.get() > asked);
        until("the sharpening's own use, closed when it ended", 120_000, () -> { try { return store.uses(acct, 0).stream().anyMatch(u -> u.kind().equals(ServiceUses.REQUEST_KIND) && !u.open() && u.actualMicros() > 0); } catch (IOException e) { return false; } });

        // a translation of a document of the library's own
        add(key, CheckpointTest.longDoc(Files.createDirectories(tmp.resolve("docs"))));
        String raw;
        try (Stream<Path> s = Files.list(LibraryPlaces.library(store.library(ann.library().id())).resolve("raw"))) { raw = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md")).findFirst().orElseThrow(); }
        r = post(key, "/v1/translate", "{\"raw\":\"" + raw + "\",\"to\":\"ja\",\"from\":\"en\",\"quality\":\"quick\"}", "application/json");
        assertEquals(200, r.statusCode(), r.body());
        String tjob = M.readTree(r.body()).path("job_id").asText();
        AccountStore.Use tr = store.uses(acct, 0).stream().filter(u -> u.job().equals(tjob)).findFirst().orElseThrow();
        until("the translation ended and its use closed", 180_000, () -> { try { return !store.use(tr.id()).open(); } catch (IOException e) { return false; } });
        assertTrue(store.use(tr.id()).actualMicros() > 0, "the translation's calls counted against it");

        // no call of all of it came without its use, and every call the model answered was counted
        said(ann);
        assertEquals(0, gates.refusedNoUse.get(), "every call carried its use");
        long counted = store.month(acct, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.kind().equals("model")).mapToLong(AccountStore.MonthTotal::quantity).sum();
        assertEquals(model.calls.get(), counted, "every call the model answered is a row of the account's month");
        System.out.println("[gated] all told: " + model.calls.get() + " calls answered, " + counted + " counted, " + gates.refusedNoUse.get() + " refused for no use");
    }

    /**
     * A real page of the web, added by its address in a library of the process form (run when RZ_REAL_WEB=on; it reaches the internet):
     * the fetch goes through the library's web gate and is counted against the use the add asked for, with its bytes.
     */
    @Test
    void aPageAddedByItsAddressIsFetchedThroughTheWebGateAndCounted(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue("on".equals(System.getenv("RZ_REAL_WEB")), "reaches the internet: RZ_REAL_WEB=on");
        model = new StandInModel();
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret(), acct = ann.account().id();
        HttpResponse<String> r = post(key, "/v1/add", "{\"url\":\"https://example.org/\"}", "application/json");
        AccountStore.Use add = store.uses(acct, 0).stream().filter(u -> u.kind().equals(CapsSpending.ADD)).findFirst().orElseThrow();
        // the request is counted when its connection closes (a client keeps one open a moment for the next request to the same site)
        until("the fetch counted", 60_000, () -> { try { return store.month(acct, AccountStore.month(System.currentTimeMillis())).stream().anyMatch(t -> t.kind().equals("fetch")); } catch (IOException e) { return false; } });
        List<AccountStore.MonthTotal> m = store.month(acct, AccountStore.month(System.currentTimeMillis()));
        System.out.println("[real web] add answered " + r.statusCode() + ": " + r.body().substring(0, Math.min(200, r.body().length())) + "; the month: " + m);
        assertEquals(200, r.statusCode(), r.body());
        AccountStore.MonthTotal f = m.stream().filter(t -> t.kind().equals("fetch")).findFirst().orElseThrow();
        assertTrue(f.quantity() >= 1 && f.bytes() > 500, "the page's request and its bytes: " + f);
        assertEquals(CapsSpending.ADD, add.kind(), "asked for before the page was fetched");
    }

    /**
     * A person's own key and the spending plug-in's answer, through real libraries (stage 3, step 4): Ann's chat turn goes to the model on
     * her own key, counted as hers; the plug-in, a stand-in, refuses her research run with its own sentence, which she is told; and what
     * her chat turn came to reaches the plug-in from the outbox.
     */
    @Test
    void aPersonsOwnKeyAndThePluginsAnswerReachARealLibrary(@TempDir Path tmp) throws Exception {
        List<String> spent = new CopyOnWriteArrayList<>();
        HttpServer plugin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plugin.createContext("/rz/may-start", x -> {
            JsonNode c = PluginSpendingTest.claims(new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII));
            boolean research = c.path("use").path("kind").asText().equals(SpendPurse.RESEARCH);
            byte[] b = (research ? "{\"yes\":false,\"said\":\"Your plan has no research left this month.\"}" : "{\"yes\":true,\"ceiling_dollars\":-1,\"ceiling_minutes\":-1}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        plugin.createContext("/rz/spent", x -> { spent.add(PluginSpendingTest.claims(new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII)).path("use").path("kind").asText()); x.sendResponseHeaders(204, -1); x.close(); });
        plugin.start();
        try {
            model = new StandInModel();
            Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
            Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
            Config.set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
            Config.set(PluginSpending.SETTING, "http://127.0.0.1:" + plugin.getAddress().getPort() + "/rz");
            root = tmp.resolve("accounts");
            store = AccountStore.open(root.resolve("accounts.db").toString());
            svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
            AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
            String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret(), acct = ann.account().id();
            OwnKeys.set(store, acct, new OwnKeys.Host("127.0.0.1", OwnKeys.MODEL, "the stand-in"), "anns-own-model-key");

            post(key, "/chat", "say=" + URLEncoder.encode("When was the lighthouse lit?", StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
            assertEquals("Bearer anns-own-model-key", model.lastAuthorization, "her turn went to the model on her own key");
            List<AccountStore.MonthTotal> m = store.month(acct, AccountStore.month(System.currentTimeMillis()));
            assertTrue(m.stream().anyMatch(t -> t.kind().equals("model") && t.payer().equals("own") && t.micros() > 0), "counted as hers: " + m);
            assertTrue(m.stream().noneMatch(t -> t.kind().equals("model") && t.payer().equals("operator")), "and none of it as the operator's: " + m);
            AccountStore.Use turn = store.uses(acct, 0).stream().filter(u -> u.kind().equals(SpendPurse.CHAT)).findFirst().orElseThrow();
            assertEquals("own", turn.payer(), "the use is hers as it starts");

            HttpResponse<String> r = post(key, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit?\"}", "application/json");
            assertEquals(429, r.statusCode(), r.body());
            assertTrue(r.body().contains("Your plan has no research left this month."), "the plug-in's sentence is what she is told: " + r.body());

            until("what her chat turn came to reached the plug-in", 30_000, () -> spent.contains(SpendPurse.CHAT));
        } finally {
            plugin.stop(0);
            for (String k : List.of(OwnKeys.FILE, PluginSpending.SETTING)) Config.set(k, "");
            OwnKeys.forget();
        }
    }

    HttpResponse<String> add(String key, Path doc) throws Exception {
        String b = "----rz" + System.nanoTime();
        HttpResponse<String> added = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/add")).header("Authorization", "Bearer " + key).header("Content-Type", "multipart/form-data; boundary=" + b)
                .POST(HttpRequest.BodyPublishers.ofString("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"gears.md\"\r\nContent-Type: text/markdown\r\n\r\n" + Files.readString(doc) + "\r\n--" + b + "--\r\n")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, added.statusCode(), added.body());
        return added;
    }
}
