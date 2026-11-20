package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.researchzosho.Config;
import org.researchzosho.Main;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two processes of one account service on one store (0.8.0): this test's own, and another this test starts as `researchzosho serve`.
 * A sign-in through the identity provider begun at one is ended at the other; the session it makes opens the account page at both; a
 * sign-out at one holds at the other at the next request; failed sign-ins are counted across both; and one scheduler runs between
 * them, the other taking over when it stops.
 */
@EnabledOnOs(value = OS.LINUX, disabledReason = "starts the accounts service as a second process, which runs on Linux only in 0.8.0 and refuses to start elsewhere")
class TwoServicesTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    long round = Scheduler.ROUND_MS;
    AccountStore store; AccountService one; Scheduler oneScheduler; Process two; OidcTest.Stub stub; Path tmp;
    String twoUrl;

    @AfterEach void stop() throws Exception {
        try {
            if (two != null) { two.destroy(); if (!two.waitFor(30, TimeUnit.SECONDS)) two.destroyForcibly(); }   // the process this test started, by its own handle
            if (oneScheduler != null) oneScheduler.stop();
            if (one != null) one.stop();
            if (store != null) store.close();
            if (stub != null) stub.s.stop(0);
        } finally { Scheduler.ROUND_MS = round; Oidc.OVERRIDE = null; Oidc.forget(); AccountService.SIGNUP_OVERRIDE = null; Config.set(AccountService.ROOT, ""); }
    }

    static void until(String what, long ms, BooleanSupplier is) {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { if (System.currentTimeMillis() > end) throw new AssertionError("not within " + ms + " ms: " + what); try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); } }
    }

    HttpResponse<String> get(String base, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "text/html");
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> signIn(String base, String name, String password) throws Exception {
        String form = "name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return c.send(HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded").header("Origin", base).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String cookie(HttpResponse<String> r) {
        for (String sc : r.headers().allValues("Set-Cookie")) if (sc.startsWith(AccountService.COOKIE + "=")) return sc.substring(0, sc.indexOf(';'));
        return null;
    }

    void start(Path dir) throws Exception {
        tmp = dir;
        Scheduler.ROUND_MS = 300;
        stub = new OidcTest.Stub();
        Map<String, String> oidc = OidcTest.settings(stub, "example.org");
        Oidc.OVERRIDE = oidc;
        AccountService.SIGNUP_OVERRIDE = "anyone";
        Config.set(AccountService.ROOT, tmp.resolve("one").toString());
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        Nodes[] nodes = new Nodes[1];
        one = AccountService.start(store, "127.0.0.1", 0, issuer -> nodes[0] = new Nodes(store, issuer, null));
        oneScheduler = new Scheduler(store, nodes[0], "the first service", ZonedDateTime::now);
        oneScheduler.start();
        // the second process of the same service: the same store, the same name for its libraries, a root and a port of its own, and no node
        int port; try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Path home = Files.createDirectories(tmp.resolve("two-home"));
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (!a.startsWith("-Xmx") && !a.startsWith("-Duser.home=") && !a.startsWith("-javaagent") && !a.startsWith("-agentlib")) cmd.add(a);
        cmd.addAll(List.of("-Xmx256m", "-Duser.home=" + home, "-Dresearchzosho.accounts.round_ms=300", "-cp", System.getProperty("java.class.path"), Main.class.getName(), "serve", "--host", "127.0.0.1", "--port", String.valueOf(port)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("RESEARCHZOSHO_") && !k.equals("RESEARCHZOSHO_CTX"));
        env.put("RESEARCHZOSHO_HOME", home.toString());
        env.put(AccountService.SETTING, "on");
        env.put(AccountService.STORE, tmp.resolve("accounts.db").toString());
        env.put(AccountService.ROOT, tmp.resolve("two").toString());
        env.put(AccountService.SERVICE, one.url());
        env.put(AccountService.SIGNUP, "anyone");
        env.put(Node.OWN, "off");
        env.put("RESEARCHZOSHO_UPDATE", "off"); env.put("RESEARCHZOSHO_LOCAL_HELPERS", "off"); env.put("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:1");
        env.putAll(oidc);
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(tmp.resolve("two.log").toFile())).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        two = pb.start();
        twoUrl = "http://127.0.0.1:" + port;
        until("the second process answers", 60_000, () -> { try { return get(twoUrl, "/healthz", null).statusCode() == 200; } catch (Exception e) { return false; } });
    }

    @Test
    void aSignInBegunAtOneEndsAtTheOtherAndASignOutAtOneHoldsAtTheOther(@TempDir Path dir) throws Exception {
        start(dir);
        stub.sub = "sub-ann"; stub.email = "ann@example.org"; stub.name = "Ann Example";
        // begun at the first: the provider is asked, with a state this process keeps in the store
        HttpResponse<String> go = get(one.url(), "/login/oidc", null);
        assertEquals(303, go.statusCode(), go.body());
        Map<String, String> q = OidcTest.query(go.headers().firstValue("Location").orElse(""));
        stub.nonce = q.get("nonce");
        // ended at the second, as a provider's answer reaches whichever process a router hands it to
        String begun = go.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(AccountPages.SIGNIN + "=")).map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElseThrow();   // the browser that began it
        HttpResponse<String> back = get(twoUrl, "/login/oidc/callback?code=code-1&state=" + q.get("state"), begun);
        assertEquals(303, back.statusCode(), back.body() + "\n" + Files.readString(tmp.resolve("two.log")));
        String session = cookie(back);
        assertTrue(session != null && session.length() > AccountService.COOKIE.length() + 10, "signed in by the second");
        assertEquals(1, store.accounts().size(), "an account made once");
        // the same answer again, at either: it was taken once
        HttpResponse<String> again = get(one.url(), "/login/oidc/callback?code=code-1&state=" + q.get("state"), begun);
        assertEquals(303, again.statusCode());
        assertTrue(again.headers().firstValue("Location").orElse("").startsWith("/login?note="), "refused, back on the sign-in page");
        assertNull(cookie(again), "nobody signed in");
        // the session opens the account page at both
        for (String base : new String[]{one.url(), twoUrl}) {
            HttpResponse<String> page = get(base, "/account", session);
            assertEquals(200, page.statusCode(), base); assertTrue(page.body().contains("Ann Example"), base);
        }
        // signed out at the first: the next request at the second is not signed in
        assertEquals(303, c.send(HttpRequest.newBuilder(URI.create(one.url() + "/logout")).header("Cookie", session).header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> after = get(twoUrl, "/account", session);
        assertEquals(303, after.statusCode(), "the second does not know the session any more");
        assertEquals("/login", after.headers().firstValue("Location").orElse(""));
    }

    @Test
    void failedSignInsAreCountedAcrossBothAndOneSchedulerRunsBetweenThem(@TempDir Path dir) throws Exception {
        start(dir);
        AccountStore.Account ann = store.createAccount("Ann Example", "", false);
        store.setPassword(ann.id(), Passwords.hash("ann's long password"));
        // ten wrong tries, five at each: the eleventh is refused at either, wherever the tries were made
        for (int i = 0; i < Accounts.TRIES; i++) assertEquals(403, signIn(i % 2 == 0 ? one.url() : twoUrl, "Ann Example", "not her password").statusCode());
        HttpResponse<String> refused = signIn(twoUrl, "Ann Example", "ann's long password");
        assertEquals(429, refused.statusCode(), refused.body());
        assertEquals(429, signIn(one.url(), "Ann Example", "ann's long password").statusCode());
        // one scheduler: the duty has one holder while both run, and the other takes it when that one stops
        until("one of the two holds the scheduler's duty", 10_000, () -> { try { return store.duty(Scheduler.DUTY) != null; } catch (IOException e) { return false; } });
        Set<String> holders = new HashSet<>();
        for (int i = 0; i < 20; i++) { holders.add(store.duty(Scheduler.DUTY).holder()); Thread.sleep(150); }
        assertEquals(1, holders.size(), "while both run, one holds it: " + holders);
        String first = holders.iterator().next();
        if (first.equals("the first service")) oneScheduler.stop(); else { two.destroy(); two.waitFor(30, TimeUnit.SECONDS); }
        until("the other one takes it", 15_000, () -> { try { return !store.duty(Scheduler.DUTY).holder().equals(first); } catch (IOException e) { return false; } });
    }
}
