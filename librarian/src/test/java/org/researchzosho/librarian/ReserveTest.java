package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls made at once cannot spend past the month's cap (0.8.0 review, round B, BILL 11): each call that costs puts aside what it may
 * cost (what it sends, and the most it may write, at the entry's prices) against its account's month and its use's ceiling, before it
 * is passed on, and gives it back when what it cost is written. Ten calls at once under a month of $1.00 spend about that, not ten
 * times as much.
 */
class ReserveTest {
    final List<String> set = new ArrayList<>();
    AccountStore store; NodeGates gates; HttpServer up;

    void set(String key, String value) throws Exception { Config.set(key, value); set.add(key); }

    @AfterEach void back() throws Exception {
        for (String k : set) Config.set(k, "");
        NodeGates.LANES = true;
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (up != null) up.stop(0);
    }

    @Test
    void callsAtOnceDoNotSpendPastTheMonthsCap(@TempDir Path tmp) throws Exception {
        CountDownLatch all = new CountDownLatch(1);
        up = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 20);
        up.setExecutor(Executors.newFixedThreadPool(16));
        up.createContext("/", x -> {
            x.getRequestBody().readAllBytes();
            try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }   // every call answered after the others began
            byte[] b = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":100000,\"completion_tokens\":10000}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        up.start();
        String upstream = "http://127.0.0.1:" + up.getAddress().getPort();
        set("RESEARCHZOSHO_DRIVE", upstream); set("RESEARCHZOSHO_MODEL", "stand-in");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "3/15");   // a call: 100,000 read and 10,000 written, $0.45
        NodeGates.LANES = false;
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.setCap("acct_ann", Caps.MONTH_DOLLARS, 1_000_000);   // $1.00 a month
        gates = NodeGates.of(store);
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), "acct_ann", "lib_acct_ann", SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(u);
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", upstream));
        gates.rewrite(env, u.library());
        String text = "x".repeat(400_000);   // about 100,000 tokens sent
        String body = "{\"model\":\"stand-in\",\"max_tokens\":10000,\"messages\":[{\"role\":\"user\",\"content\":\"" + text + "\"}]}";
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        List<Integer> answers = new CopyOnWriteArrayList<>();
        List<Thread> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) calls.add(Thread.ofPlatform().start(() -> {
            try { answers.add(c.send(HttpRequest.newBuilder(URI.create(env.get("RESEARCHZOSHO_DRIVE") + "/v1/chat/completions")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()); }
            catch (Exception e) { answers.add(-1); }
        }));
        for (Thread t : calls) t.join();
        long spent = store.useSpentBy(u.id(), "operator");
        long passed = answers.stream().filter(a -> a == 200).count(), refused = answers.stream().filter(a -> a == 402).count();
        assertTrue(refused > 0, "some were refused: " + answers);
        assertTrue(spent <= 1_000_000 + 250_000 + 450_000, "the month's $1.00, its margin, and one call's worth at most; spent " + spent + " in " + passed + " calls");
    }
}
