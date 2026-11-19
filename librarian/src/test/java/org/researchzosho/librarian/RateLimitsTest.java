package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Main;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account service's rate limits (0.8.0, stage 4, step 2), with two processes of one service on one store: this test's own, and a
 * second it starts as `researchzosho serve`. The frequent limits (an account's API calls and pages, an address's requests with no
 * account) are each process's share of the limit, so the two together hold the whole of it; the rare acts (failed sign-ins, join links,
 * changes to a password, new accounts) are counted in the store, so the two read the same count. Each limit refuses with 429, Retry-After
 * and its sentence, and lets go when its time has passed. Behind a trusted proxy the forwarded address counts, an IPv6 address by its
 * network. A limit an account reached is in the audit log at most once a minute. A limit set off lets everything through, the defaults
 * hold when nothing is set, and the single-person program has none.
 */
class RateLimitsTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    long seen = RateLimits.SEEN_MS;
    AccountStore store; AccountService one; Process two; String twoUrl; Path root; OidcTest.Stub stub;
    final List<String> set = new ArrayList<>();

    @AfterEach void stop() throws Exception {
        try {
            if (two != null) { two.destroy(); if (!two.waitFor(30, TimeUnit.SECONDS)) two.destroyForcibly(); }   // the process this test started, by its own handle
            if (one != null) one.stop();
            if (store != null) store.close();
            if (stub != null) stub.s.stop(0);
        } finally {
            RateLimits.SEEN_MS = seen; Proxy.OVERRIDE = null; Oidc.OVERRIDE = null; Oidc.forget(); AccountService.SIGNUP_OVERRIDE = null;
            for (String k : set) Config.set(k, "");
            Config.set(AccountService.ROOT, "");
        }
    }

    void setting(String key, String value) throws IOException { Config.set(key, value); set.add(key); }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(100); }
    }

    /** The first process here, and the second as `researchzosho serve` on the same store with the same settings, which it reads from its environment. */
    void start(Path tmp, Map<String, String> settings) throws Exception {
        root = tmp.resolve("one");
        Config.set(AccountService.ROOT, root.toString());
        for (Map.Entry<String, String> e : settings.entrySet()) setting(e.getKey(), e.getValue());
        RateLimits.SEEN_MS = 300;   // this process counts the second soon after it starts
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        one = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        int port; try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        Path home = Files.createDirectories(tmp.resolve("two-home"));
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (!a.startsWith("-Xmx") && !a.startsWith("-Duser.home=") && !a.startsWith("-javaagent") && !a.startsWith("-agentlib")) cmd.add(a);
        cmd.addAll(List.of("-Xmx256m", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"), Main.class.getName(), "serve", "--host", "127.0.0.1", "--port", String.valueOf(port)));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("RESEARCHZOSHO_") && !k.equals("RESEARCHZOSHO_CTX"));
        env.put("RESEARCHZOSHO_HOME", home.toString());
        env.put(AccountService.SETTING, "on");
        env.put(AccountService.STORE, tmp.resolve("accounts.db").toString());
        env.put(AccountService.ROOT, tmp.resolve("two").toString());
        env.put(AccountService.SERVICE, one.url());
        env.put(Node.OWN, "off"); env.put(Scheduler.NIGHTS, "off");
        env.put("RESEARCHZOSHO_UPDATE", "off"); env.put("RESEARCHZOSHO_LOCAL_HELPERS", "off"); env.put("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:1");
        env.putAll(settings);
        if (Proxy.OVERRIDE != null) env.put(Proxy.SETTING, Proxy.OVERRIDE);
        if (Oidc.OVERRIDE != null) { env.putAll(Oidc.OVERRIDE); env.put(AccountService.SIGNUP, AccountService.SIGNUP_OVERRIDE); }
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(tmp.resolve("two.log").toFile())).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        two = pb.start();
        twoUrl = "http://127.0.0.1:" + port;
        until("the second process answers", 60_000, () -> { try { return send(twoUrl, "/healthz", Map.of()).statusCode() == 200; } catch (Exception e) { return false; } });
        until("the first counts the second", 10_000, () -> one.limits.processes() == 2);
    }

    HttpResponse<String> send(String base, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        headers.forEach(b::header);
        return c.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String base, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static long retryAfter(HttpResponse<String> r) { return Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow(() -> new AssertionError("no Retry-After: " + r.statusCode()))); }

    /** What a burst of requests sent in turn to the two processes came to: how many each let through, and the last refusal of each. */
    record Burst(int[] ok, HttpResponse<String>[] refused, long ms) { int total() { return ok[0] + ok[1]; } }

    interface Ask { HttpResponse<String> to(String base) throws Exception; }

    @SuppressWarnings("unchecked")
    Burst burst(int n, Ask ask) throws Exception {
        int[] ok = new int[2]; HttpResponse<String>[] refused = new HttpResponse[2];
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            HttpResponse<String> r = ask.to(i % 2 == 0 ? one.url() : twoUrl);
            if (r.statusCode() == 429) refused[i % 2] = r;
            else { assertTrue(r.statusCode() < 400 || r.statusCode() == 403, r.statusCode() + " " + r.body()); ok[i % 2]++; }
        }
        return new Burst(ok, refused, System.currentTimeMillis() - t0);
    }

    /** A shared limit of 10 a window over two processes: 5 at each, and no more than the window's pace adds while the burst lasted. */
    static void heldInShares(Burst b, String what) {
        long more = b.ms() * 2 * 5 / 20_000;   // the tokens the two buckets gain at 10 per 20 seconds while the burst lasts
        assertTrue(b.total() >= 10 && b.total() <= 10 + more, what + ": " + b.total() + " let through in " + b.ms() + " ms, of 16");
        for (int i = 0; i < 2; i++) assertTrue(b.ok()[i] >= 5 && b.ok()[i] <= 5 + more, what + ": process " + (i + 1) + " let " + b.ok()[i] + " through");
        assertNotNull(b.refused()[0], what); assertNotNull(b.refused()[1], what);
    }

    @Test
    void aLimitIsWrittenAsANumberAPeriodOrOffAndSaysItsWaitInWords() throws Exception {
        assertEquals(new RateLimits.Rule(120, 60_000, 30), RateLimits.parse("120 per minute, burst 30"));
        assertEquals(new RateLimits.Rule(10, 600_000, 10), RateLimits.parse("10 per 10 minutes"));
        assertEquals(new RateLimits.Rule(5, 3_600_000, 5), RateLimits.parse("5 per hour"));
        assertEquals(new RateLimits.Rule(3, 4_000, 3), RateLimits.parse("3 per 4 seconds"));
        assertNull(RateLimits.parse("lots")); assertNull(RateLimits.parse("0 per minute")); assertNull(RateLimits.parse("120 a minute"));
        Settings.Setting api = Settings.named("limit.api");
        assertEquals("RESEARCHZOSHO_LIMIT_API", api.key());
        assertEquals("", Settings.refuse(api, "off")); assertEquals("", Settings.refuse(api, "60 per minute, burst 10"));
        assertTrue(Settings.refuse(api, "lots").contains("for example 120 per minute, burst 30"), Settings.refuse(api, "lots"));
        for (RateLimits.Limit l : RateLimits.Limit.values()) assertNotNull(Settings.named("limit." + l.word), l.word);
        assertEquals("1 second", RateLimits.in(1)); assertEquals("40 seconds", RateLimits.in(40)); assertEquals("119 seconds", RateLimits.in(119));
        assertEquals("2 minutes", RateLimits.in(120)); assertEquals("8 minutes", RateLimits.in(475)); assertEquals("60 minutes", RateLimits.in(3600)); assertEquals("2 hours", RateLimits.in(7200));
        assertEquals("Too many requests from this account. Try again in 40 seconds.", RateLimits.said(RateLimits.Limit.API, true, 40));
        assertEquals("Too many failed sign-ins from this address. Try again in 8 minutes.", RateLimits.said(RateLimits.Limit.SIGNIN, false, 475));
        assertEquals("2001:db8:1:2:0:0:0:0/64", RateLimits.counted(InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:dddd")));
        assertEquals("203.0.113.5", RateLimits.counted(InetAddress.getByName("203.0.113.5")));
    }

    @Test
    void theFrequentLimitsAreEachProcesssShareAndTheTwoTogetherHoldTheWholeOne(@TempDir Path tmp) throws Exception {
        Proxy.OVERRIDE = "127.0.0.1";   // the router in front of both processes: the address it forwards counts
        String window = "10 per 20 seconds";
        start(tmp, Map.of(RateLimits.Limit.API.setting(), window, RateLimits.Limit.PAGES.setting(), window, RateLimits.Limit.ADDRESS.setting(), window));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "read", "a script").secret();
        String session = store.newSession(ann.account().id(), "", "a browser", 1);
        Map<String, String> program = Map.of("Authorization", "Bearer " + key, "X-Forwarded-For", "192.0.2.10"), browser = Map.of("Cookie", AccountService.COOKIE + "=" + session, "Accept", "text/html");

        // ---- a program: 16 calls, in turn at each process; 10 go through, 5 at each ----
        Burst api = burst(16, base -> send(base, AccountService.USAGE, program));
        heldInShares(api, "the API");
        HttpResponse<String> no = api.refused()[1];
        JsonNode err = M.readTree(no.body()).path("error");
        assertEquals("rate_limited", err.path("code").asText(), no.body());
        assertTrue(err.path("message").asText().matches("Too many requests from this account\\. Try again in \\d+ seconds?\\."), no.body());
        long wait = retryAfter(no);
        assertTrue(wait >= 1 && wait <= 5 && err.path("retry_after").asLong() == wait, "about 4 s, a fifth of the window at each process's half pace: " + wait);
        // an MCP call is told the same as a JSON-RPC error, which the host's model reads
        HttpResponse<String> mcp = post(one.url(), "/rpc", program, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"library_status\",\"arguments\":{}}}");
        JsonNode env = M.readTree(mcp.body());
        assertEquals(200, mcp.statusCode(), mcp.body()); assertEquals(7, env.path("id").asInt());
        assertEquals(-32008, env.path("error").path("code").asInt()); assertEquals("rate_limited", env.path("error").path("data").path("code").asText());
        assertTrue(env.path("error").path("message").asText().startsWith("Too many requests from this account. Try again in "), mcp.body());
        assertTrue(env.path("error").path("data").path("retry_after").asLong() >= 1 && retryAfter(mcp) >= 1);
        System.out.println("[limits] the API, " + api.total() + " of 16 in " + api.ms() + " ms (" + api.ok()[0] + " + " + api.ok()[1] + "); a program is told " + no.statusCode() + ", Retry-After " + wait + ": " + no.body() + "\n[limits] an MCP call is told " + mcp.statusCode() + ": " + mcp.body());

        // ---- a browser: the pages, 10 of 16, and a refused page says so on a page of its own ----
        Burst pages = burst(16, base -> send(base, "/account", browser));
        heldInShares(pages, "the pages");
        HttpResponse<String> page = pages.refused()[0];
        assertTrue(page.body().contains("Wait a moment") && page.body().matches("(?s).*Too many requests from this account\\. Try again in \\d+ seconds?\\..*") && page.body().contains("href=\"/account\""), page.body());
        assertTrue(retryAfter(page) >= 1);
        System.out.println("[limits] the pages, " + pages.total() + " of 16 (" + pages.ok()[0] + " + " + pages.ok()[1] + "); a browser is shown " + page.statusCode() + ": " + page.body().substring(page.body().indexOf("<main>"), page.body().indexOf("</main>") + 7));

        // ---- no account: by the address the router forwards, 10 of 16; another address is not held by it ----
        Map<String, String> fromA = Map.of("X-Forwarded-For", "198.51.100.7, 203.0.113.5", "Accept", "text/html");   // the last entry is the router's own
        Burst anon = burst(16, base -> send(base, "/login", fromA));
        heldInShares(anon, "requests with no account");
        assertTrue(anon.refused()[1].body().matches("(?s).*Too many requests from this address\\. Try again in \\d+ seconds?\\..*") && anon.refused()[1].body().contains("name=\"password\""), "the sign-in page, with the sentence");
        for (String base : new String[]{one.url(), twoUrl}) assertEquals(200, send(base, "/login", Map.of("X-Forwarded-For", "203.0.113.6")).statusCode(), "another address");
        // an IPv6 address counts by its network: the whole of 2001:db8:1:2::/64 together, its neighbour apart
        int let = 0;
        for (int i = 0; i < 12; i++) if (send(i % 2 == 0 ? one.url() : twoUrl, "/login", Map.of("X-Forwarded-For", "2001:db8:1:2::" + (i + 1))).statusCode() == 200) let++;
        assertTrue(let >= 10 && let <= 11, "one network's addresses share its limit: " + let);
        assertEquals(429, send(twoUrl, "/login", Map.of("X-Forwarded-For", "2001:db8:1:2:ffff::99")).statusCode());
        assertEquals(200, send(twoUrl, "/login", Map.of("X-Forwarded-For", "2001:db8:1:3::1")).statusCode(), "the next network");

        // ---- each lets go once its wait has passed, at both processes ----
        Thread.sleep(Math.max(retryAfter(api.refused()[0]), retryAfter(api.refused()[1])) * 1000 + 300);
        for (String base : new String[]{one.url(), twoUrl}) assertEquals(200, send(base, AccountService.USAGE, program).statusCode(), base);
        Thread.sleep(Math.max(retryAfter(pages.refused()[0]), retryAfter(pages.refused()[1])) * 1000 + 300);
        for (String base : new String[]{one.url(), twoUrl}) assertEquals(200, send(base, "/account", browser).statusCode(), base);
        Thread.sleep(Math.max(retryAfter(anon.refused()[0]), retryAfter(anon.refused()[1])) * 1000 + 300);
        for (String base : new String[]{one.url(), twoUrl}) assertEquals(200, send(base, "/login", fromA).statusCode(), base);

        // ---- the audit log: the account's limits reached, once a minute at most, whichever process and limit ----
        List<AccountStore.Audit> hits = store.audits(ann.account().id(), 0, 100).stream().filter(a -> a.act().equals("limit_reached")).toList();
        assertFalse(hits.isEmpty(), "written down");
        Set<Long> minutes = new HashSet<>();
        for (AccountStore.Audit h : hits) assertTrue(minutes.add(h.at() / 60_000), "two entries in one minute: " + hits);
        assertTrue(hits.size() <= 2, "the refusals spanned a minute or two: " + hits);
        AccountStore.Audit first = hits.get(hits.size() - 1);
        assertEquals("{\"limit\":\"api\"}", first.detail()); assertEquals("key", first.via()); assertEquals("192.0.2.10", first.address(), "the address the router forwarded");
        // the person reads it among their own activity
        assertTrue(send(one.url(), "/account", browser).body().contains("too many requests: some were refused"));
    }

    @Test
    void theRareActsAreCountedInTheStoreSoBothProcessesReadOneCount(@TempDir Path tmp) throws Exception {
        stub = new OidcTest.Stub();
        Oidc.OVERRIDE = OidcTest.settings(stub, "example.org");
        AccountService.SIGNUP_OVERRIDE = "anyone";
        String window = "3 per 4 seconds";
        Map<String, String> settings = new HashMap<>(Map.of(RateLimits.Limit.SIGNIN.setting(), window, RateLimits.Limit.JOIN.setting(), window, RateLimits.Limit.CHANGES.setting(), window,
                RateLimits.Limit.SIGNUP.setting(), "2 per 4 seconds", RateLimits.Limit.ADDRESS.setting(), "off", RateLimits.Limit.PAGES.setting(), "off"));
        start(tmp, settings);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        store.setPassword(ann.account().id(), Passwords.hash("ann's long password"));
        Map<String, String> form = Map.of("Content-Type", "application/x-www-form-urlencoded");

        // ---- failed sign-ins from one address, three names at the two processes; the fourth is refused before its password is read ----
        String[] names = {"Nobody One", "Nobody Two", "Ann Example"};
        for (int i = 0; i < 3; i++) assertEquals(403, post(i % 2 == 0 ? one.url() : twoUrl, "/login", form, "name=" + enc(names[i]) + "&password=wrong").statusCode());
        HttpResponse<String> signin = post(twoUrl, "/login", form, "name=" + enc("Ann Example") + "&password=" + enc("ann's long password"));
        assertEquals(429, signin.statusCode(), "the right password, refused all the same");
        assertTrue(signin.body().matches("(?s).*Too many failed sign-ins from this address\\. Try again in \\d seconds?\\..*"), signin.body());
        assertTrue(signin.headers().allValues("Set-Cookie").stream().noneMatch(h -> h.startsWith(AccountService.COOKIE + "=") && !h.startsWith(AccountService.COOKIE + "=;")), "not signed in");
        assertEquals(429, post(one.url(), "/login", form, "name=" + enc("Nobody Three") + "&password=wrong").statusCode(), "the first reads the same count");
        Thread.sleep(retryAfter(signin) * 1000 + 300);
        HttpResponse<String> in = post(one.url(), "/login", form, "name=" + enc("Ann Example") + "&password=" + enc("ann's long password"));
        assertEquals(303, in.statusCode(), "let go: " + in.body());
        String session = in.headers().allValues("Set-Cookie").stream().filter(h -> h.startsWith(AccountService.COOKIE + "=")).findFirst().orElseThrow();
        Map<String, String> ann1 = Map.of("Cookie", session.substring(0, session.indexOf(';')), "Content-Type", "application/x-www-form-urlencoded");
        assertTrue(store.audits(ann.account().id(), 0, 50).stream().anyMatch(a -> a.act().equals("limit_reached") && a.detail().equals("{\"limit\":\"signin\"}") && a.address().equals("127.0.0.1")),
                "the account whose name was tried is told");

        // ---- join links opened from one address: the fourth is refused before its code is looked at ----
        String code = store.newInvite(AccountService.newAccount(store, root, "Bo Example", "", false, "the test").account().id(), "the test", 7);
        for (int i = 0; i < 3; i++) assertEquals(403, send(i % 2 == 0 ? one.url() : twoUrl, "/join?code=not-a-code-" + i, Map.of("Accept", "text/html")).statusCode());
        HttpResponse<String> join = send(twoUrl, "/join?code=" + code, Map.of("Accept", "text/html"));
        assertEquals(429, join.statusCode(), "a good code, refused all the same");
        assertTrue(join.body().matches("(?s).*Too many join links opened from this address\\. Try again in \\d seconds?\\..*") && join.body().contains("Try again"), join.body());
        Thread.sleep(retryAfter(join) * 1000 + 300);
        HttpResponse<String> opened = send(one.url(), "/join?code=" + code, Map.of("Accept", "text/html"));
        assertEquals(200, opened.statusCode(), "let go"); assertTrue(opened.body().contains("Choose a password"));

        // ---- changes to a password, a wrong current one counted: the fourth is refused on the account page ----
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post(i % 2 == 0 ? one.url() : twoUrl, "/account", ann1, "op=password&current=wrong&password=" + enc("a new long password") + "&again=" + enc("a new long password"));
            assertEquals(303, r.statusCode(), r.body());
        }
        HttpResponse<String> change = post(twoUrl, "/account", ann1, "op=password&current=" + enc("ann's long password") + "&password=" + enc("a new long password") + "&again=" + enc("a new long password"));
        assertEquals(429, change.statusCode());
        assertTrue(change.body().contains("Too many changes to this account") && change.body().matches("(?s).*password and keys\\. Try again in \\d seconds?\\..*") && change.body().contains("Delete this account"), "on the account page itself: " + change.body());
        assertTrue(Passwords.check(store.account(ann.account().id()).passwordHash(), "ann's long password"), "nothing changed");
        Thread.sleep(retryAfter(change) * 1000 + 300);
        assertEquals(303, post(one.url(), "/account", ann1, "op=password&current=" + enc("ann's long password") + "&password=" + enc("a new long password") + "&again=" + enc("a new long password")).statusCode());
        assertTrue(Passwords.check(store.account(ann.account().id()).passwordHash(), "a new long password"), "let go: changed");

        // ---- people one account invites into its libraries: two, then the third is refused and makes no link ----
        int accounts = store.accounts().size();
        for (int i = 0; i < 2; i++) assertEquals(200, post(i % 2 == 0 ? one.url() : twoUrl, "/account", ann1, "op=member-let-in&library=" + ann.library().id() + "&name=" + enc("New Name " + i) + "&level=read").statusCode());
        HttpResponse<String> made = post(twoUrl, "/account", ann1, "op=member-let-in&library=" + ann.library().id() + "&name=" + enc("New Name 2") + "&level=read");
        assertEquals(429, made.statusCode());
        assertTrue(made.body().matches("(?s).*Too many people invited by this account just now\\. Try again in \\d seconds?\\..*"), made.body());
        assertEquals(2, store.invitations(ann.library().id()).size(), "the third made nothing");
        Thread.sleep(retryAfter(made) * 1000 + 300);
        assertEquals(200, post(one.url(), "/account", ann1, "op=member-let-in&library=" + ann.library().id() + "&name=" + enc("New Name 2") + "&level=read").statusCode());
        assertEquals(3, store.invitations(ann.library().id()).size(), "let go");
        assertEquals(accounts, store.accounts().size(), "an invitation makes no account");

        // ---- sign-ups through the provider from one address: two, then the third is refused on the sign-in page ----
        accounts = store.accounts().size();
        HttpResponse<String> third = null;
        for (int i = 0; i < 3; i++) {
            stub.sub = "sub-" + i; stub.email = "person" + i + "@example.org"; stub.name = "Person " + i;
            HttpResponse<String> go = send(i % 2 == 0 ? one.url() : twoUrl, "/login/oidc", Map.of());
            stub.nonce = OidcTest.query(go.headers().firstValue("Location").orElse("")).get("nonce");
            String begun = go.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(AccountPages.SIGNIN + "=")).map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElseThrow();   // the browser that began it
            HttpResponse<String> back = send(i % 2 == 0 ? twoUrl : one.url(), "/login/oidc/callback?code=code-1&state=" + OidcTest.query(go.headers().firstValue("Location").orElse("")).get("state"), Map.of("Cookie", begun));
            if (i < 2) assertEquals(303, back.statusCode(), back.body()); else third = back;
        }
        assertEquals(429, third.statusCode());
        assertTrue(third.body().matches("(?s).*Too many new accounts from this address\\. Try again in \\d seconds?\\..*"), third.body());
        assertEquals(accounts + 2, store.accounts().size(), "the third made no account");
        Thread.sleep(retryAfter(third) * 1000 + 300);
        HttpResponse<String> go = send(one.url(), "/login/oidc", Map.of());
        stub.nonce = OidcTest.query(go.headers().firstValue("Location").orElse("")).get("nonce");
        String begun = go.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(AccountPages.SIGNIN + "=")).map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElseThrow();
        assertEquals(303, send(twoUrl, "/login/oidc/callback?code=code-1&state=" + OidcTest.query(go.headers().firstValue("Location").orElse("")).get("state"), Map.of("Cookie", begun)).statusCode(), "let go");
        assertEquals(accounts + 3, store.accounts().size());
    }

    @Test
    void aLimitSetOffLetsEverythingThroughTheDefaultsHoldAndOnePersonsLibraryHasNone(@TempDir Path tmp) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        one = AccountService.start(store, "127.0.0.1", 0, issuer -> new Nodes(store, issuer, null));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        Map<String, String> program = Map.of("Authorization", "Bearer " + store.mintKey(ann.account().id(), ann.library().id(), "read", "a script").secret());
        // nothing set: 120 a minute, up to 30 at once
        int let = 0; long t0 = System.currentTimeMillis();
        for (int i = 0; i < 40; i++) if (send(one.url(), AccountService.USAGE, program).statusCode() == 200) let++;
        long more = (System.currentTimeMillis() - t0) * 2 / 1000;
        assertTrue(let >= 30 && let <= 30 + more + 1, "the default burst: " + let);
        // off: none
        setting(RateLimits.Limit.API.setting(), "off");
        for (int i = 0; i < 60; i++) assertEquals(200, send(one.url(), AccountService.USAGE, program).statusCode(), "off, at " + i);
        // a library of one's own, with every limit as low as it goes: nothing is counted
        for (RateLimits.Limit l : RateLimits.Limit.values()) setting(l.setting(), "1 per hour");
        LibraryStore mine = new LibraryStore(tmp.resolve("mine")); mine.init();
        LibrarianDaemon d = LibrarianDaemon.start(mine, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try { for (int i = 0; i < 20; i++) assertEquals(200, send(d.url(), "/v1/status", Map.of()).statusCode(), "the single-person program, at " + i); }
        finally { d.stop(); }
    }
}
