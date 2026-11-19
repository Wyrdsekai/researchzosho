package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spending plug-in (0.8.0, stage 3, step 4), against a stand-in that answers yes, refuses, answers late, fails and answers what
 * is not an answer: each call is a JWT the service's published key checks; whatever is not a 200 with a yes or a no within the time
 * is a no; what was spent waits in the outbox and is sent again until it is taken, in the order it was written.
 */
class PluginSpendingTest {
    AccountStore store; HttpServer plugin; PluginSpending spending; PluginSpending.Sender sender;
    volatile String mode = "yes";
    final AtomicInteger spentFailures = new AtomicInteger();
    final List<String> mayStart = new CopyOnWriteArrayList<>(), spentTried = new CopyOnWriteArrayList<>(), spentTaken = new CopyOnWriteArrayList<>();
    String url;
    long answerWas = PluginSpending.ANSWER_MS, backoffWas = PluginSpending.BACKOFF_MS, mostWas = PluginSpending.BACKOFF_MOST_MS;

    @BeforeEach void start(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        plugin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plugin.createContext("/rz/may-start", x -> {
            String token = new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
            mayStart.add(token);
            String m = mode;
            if (m.equals("late")) { try { Thread.sleep(1_500); } catch (InterruptedException e) { return; } }
            int code = m.equals("fail") ? 500 : 200;
            String body = switch (m) {
                case "yes", "late" -> "{\"yes\":true,\"said\":\"About $0.40; $3.10 left on your plan.\",\"ceiling_dollars\":2.5,\"ceiling_minutes\":20}";
                case "no" -> "{\"yes\":false,\"said\":\"Your plan has no research left this month.\"}";
                case "no-said" -> "{\"yes\":false}";
                case "unbounded" -> "{\"yes\":true,\"ceiling_dollars\":-1}";
                case "garbage" -> "ok";
                default -> "{\"error\":\"down\"}";
            };
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            try { x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); } catch (IOException gone) { /* the service stopped waiting */ }
            x.close();
        });
        plugin.createContext("/rz/spent", x -> {
            String token = new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII);
            String jti = claims(token).path("jti").asText();
            spentTried.add(jti);
            int code = jti.startsWith("use_refused") ? 400 : spentFailures.getAndDecrement() > 0 ? 503 : 200;
            if (code == 200) spentTaken.add(jti);
            x.sendResponseHeaders(code, -1); x.close();
        });
        plugin.start();
        url = "http://127.0.0.1:" + plugin.getAddress().getPort() + "/rz";
        spending = new PluginSpending(store, url, "http://accounts.test");
        PluginSpending.ANSWER_MS = 500; PluginSpending.BACKOFF_MS = 50; PluginSpending.BACKOFF_MOST_MS = 400;
    }

    @AfterEach void stop() {
        if (sender != null) sender.stop();
        plugin.stop(0); store.close();
        PluginSpending.ANSWER_MS = answerWas; PluginSpending.BACKOFF_MS = backoffWas; PluginSpending.BACKOFF_MOST_MS = mostWas;
    }

    static JsonNode claims(String token) {
        try { return Statement.M.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** The check a plug-in makes: the signature, ES256 over the first two parts, by the key the service publishes under the token's kid. */
    boolean signedByTheService(String token) throws Exception {
        String[] p = token.split("\\.");
        JsonNode header = Statement.M.readTree(Base64.getUrlDecoder().decode(p[0]));
        PublicKey key = null;
        for (String jwk : store.publicJwks()) { JsonNode j = Statement.M.readTree(jwk); if (j.path("kid").asText().equals(header.path("kid").asText())) key = Statement.fromJwk(j); }
        if (key == null || !header.path("alg").asText().equals("ES256")) return false;
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(key);
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        return s.verify(Base64.getUrlDecoder().decode(p[2]));
    }

    Spending.Use use(String id) { return new Spending.Use(id, "acct_ann", "lib_ann", SpendPurse.RESEARCH, "default", "operator", 400_000, 6, "job_1"); }

    @Test
    void whateverIsNotAYesOrANoInTimeIsANo() throws Exception {
        Spending.Answer a = spending.mayStart(use("use_1"));
        assertTrue(a.yes());
        assertEquals(2_500_000, a.ceilingMicros()); assertEquals(20, a.ceilingMinutes());
        assertEquals("About $0.40; $3.10 left on your plan.", a.said(), "what the plug-in says is what the person is told");
        String token = mayStart.get(0);
        assertTrue(signedByTheService(token), "a plug-in checks the call by the service's published key");
        JsonNode c = claims(token);
        assertEquals("http://accounts.test", c.path("iss").asText()); assertEquals(url, c.path("aud").asText()); assertEquals("may_start", c.path("kind").asText());
        assertEquals("use_1", c.path("jti").asText()); assertEquals("acct_ann", c.path("use").path("account").asText()); assertEquals(0.4, c.path("use").path("estimate_dollars").asDouble(), 1e-9);
        assertTrue(c.path("exp").asLong() > c.path("iat").asLong());
        String forged = token.substring(0, token.lastIndexOf('.') + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        assertFalse(signedByTheService(forged), "and a call it did not sign fails that check");

        mode = "no";
        a = spending.mayStart(use("use_2"));
        assertFalse(a.yes()); assertEquals("Your plan has no research left this month.", a.said());
        mode = "no-said";
        assertEquals("Whoever runs this service does not let this start now.", spending.mayStart(use("use_3")).said());
        for (String m : List.of("late", "fail", "garbage")) {
            mode = m;
            long t0 = System.currentTimeMillis();
            a = spending.mayStart(use("use_" + m));
            assertFalse(a.yes(), m + ": a no");
            assertEquals(ServiceUses.couldNotAsk(), a.said(), m);
            assertTrue(System.currentTimeMillis() - t0 < 1_400, m + ": not waited for past its time");
        }
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { closed = s.getLocalPort(); }
        assertFalse(new PluginSpending(store, "http://127.0.0.1:" + closed + "/rz", "http://accounts.test").mayStart(use("use_x")).yes(), "nothing there: a no");
    }

    @Test
    void whatWasSpentIsSentInOrderUntilItIsTaken() throws Exception {
        spentFailures.set(2);
        for (String id : List.of("use_a", "use_b", "use_c")) {
            store.addUse(new AccountStore.Use(id, "acct_ann", "lib_ann", SpendPurse.RESEARCH, "default", "operator", "", 400_000, 6, -1, -1, "open", "", System.currentTimeMillis(), 0, 0));
            store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_ann", id, "model", "operator", "default", 100, 0, 50, 1, 600, 120_000, false),
                    new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_ann", id, "search", "operator", "brave", 0, 0, 0, 1, 900, 5_000, false)));
            Uses.end(store, spending, id, "done");
        }
        assertEquals(3, store.outboxSize(), "written down before anything is sent");
        sender = spending.sender("test");
        long end = System.currentTimeMillis() + 15_000;
        while (store.outboxSize() > 0 && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals(0, store.outboxSize(), "every one taken in the end");
        assertEquals(List.of("use_a", "use_b", "use_c"), spentTaken, "in the order they were written");
        assertEquals(List.of("use_a", "use_a", "use_a", "use_b", "use_c"), spentTried, "the first sent again until taken; the next waited behind it");
    }

    /** A spent the plug-in refuses again and again (a 4xx: it will never take it) is set aside for the operator, and the rest go on (round C, BILL 12). */
    @Test
    void aSpentRefusedOverAndOverIsSetAsideAndTheRestGoOn() throws Exception {
        for (String id : List.of("use_refused", "use_after")) {
            store.addUse(new AccountStore.Use(id, "acct_ann", "lib_ann", SpendPurse.RESEARCH, "default", "operator", "", 400_000, 6, -1, -1, "open", "", System.currentTimeMillis(), 0, 0));
            Uses.end(store, spending, id, "done");
        }
        sender = spending.sender("test");
        long end = System.currentTimeMillis() + 15_000;
        while (!spentTaken.contains("use_after") && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(spentTaken.contains("use_after"), "what comes after it is not held back for ever: tried " + spentTried);
        assertEquals(1, store.outboxParked().size(), "set aside, shown to the operator");
    }

    /** An account removed is told to the plug-in, by its id (round C, BILL 12). */
    @Test
    void anAccountRemovedIsToldToThePlugIn() throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        plugin.createContext("/rz/account-deleted", x -> { told.add(claims(new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII)).path("account").asText()); x.sendResponseHeaders(200, -1); x.close(); });
        AccountActs.toldRemoved(store, "acct_gone");
        sender = spending.sender("test");
        long end = System.currentTimeMillis() + 10_000;
        while (told.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals(List.of("acct_gone"), told);
    }

    /** A use on the person's own key, which the plug-in leaves without a ceiling, keeps one on what the operator pays inside it (round C, BILL 13). */
    @Test
    void aUseOnThePersonsOwnKeyKeepsACeilingOnWhatTheOperatorPays() throws Exception {
        mode = "unbounded";
        Spending.Answer a = spending.mayStart(new Spending.Use("use_own", "acct_ann", "lib_ann", SpendPurse.RESEARCH, "default", "own", 0, 0, "job_1"));
        assertTrue(a.yes());
        assertEquals(1_000_000, a.ceilingMicros(), "a dollar of the operator's, unless the setting says otherwise");
        Spending.Answer op = spending.mayStart(use("use_op"));
        assertEquals(-1, op.ceilingMicros(), "the operator's own use: as the plug-in says");
    }

    @Test
    void whatASpentCallCarries() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        plugin.removeContext("/rz/spent");
        plugin.createContext("/rz/spent", x -> { bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII)); x.sendResponseHeaders(204, -1); x.close(); });
        store.addUse(new AccountStore.Use("use_s", "acct_ann", "lib_ann", SpendPurse.CHAT, "default", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0));
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_ann", "use_s", "model", "operator", "default", 100, 0, 50, 1, 600, 120_000, true),
                new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_ann", "use_s", "model", "own", "default", 10, 0, 5, 1, 60, 30_000, false)));
        Uses.end(store, spending, "use_s", "done");
        sender = spending.sender("test");
        long end = System.currentTimeMillis() + 10_000;
        while (bodies.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(signedByTheService(bodies.get(0)));
        JsonNode c = claims(bodies.get(0));
        assertEquals("spent", c.path("kind").asText()); assertEquals("use_s", c.path("jti").asText()); assertEquals("chat", c.path("use").path("kind").asText());
        assertEquals(0.15, c.path("actual").path("dollars").asDouble(), 1e-9, "what the use came to, both payers");
        assertTrue(c.path("actual").path("estimated").asBoolean());
        assertEquals(2, c.path("actual").path("totals").size(), "by kind and payer");
    }
}
