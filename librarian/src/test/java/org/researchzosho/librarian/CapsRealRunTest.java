package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real run of two accounts at their caps (0.8.0, stage 3, step 5), not a check the suite runs: the account service and its libraries
 * as real processes (RZ_REAL_CAPS=on), or in real containers of the image RZ_REAL_CONTAINER names; a stand-in model (no paid model), a
 * stand-in search place, and three real pages of the web for the fetches. Ann's research runs into her small dollar cap and stops
 * after its call, kept; her searches stop at their cap and the run goes on without them; she is refused a resume while the cap holds
 * and resumes and finishes when it is raised. Bo's chat is timed meanwhile; his fetches reach their cap; his storage at its cap
 * refuses an add and allows a read. The account page and `accounts usage` are read against the store.
 */
class CapsRealRunTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; HttpServer place; AccountStore store; AccountService svc;
    final Map<String, String> bases = new HashMap<>(NodeGates.BASES);

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); if (place != null) place.stop(0); }
        finally {
            NodeGates.BASES.clear(); NodeGates.BASES.putAll(bases);
            for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), Scheduler.NIGHTS, "RESEARCHZOSHO_BRAVE_KEY", ContainerLibraries.IMAGE)) Config.set(k, "");
            Config.set(RateLimits.Limit.API.setting(), "");
        }
    }

    @Test void inProcesses(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue("on".equals(System.getenv("RZ_REAL_CAPS")), "a real run: RZ_REAL_CAPS=on");
        run(tmp, null);
    }

    @Test void inContainers(@TempDir Path tmp) throws Exception {
        String image = System.getenv("RZ_REAL_CONTAINER");
        Assumptions.assumeTrue(image != null && !image.isBlank(), "a real run in containers: RZ_REAL_CONTAINER=<image>");
        run(tmp, image);
    }

    HttpResponse<String> post(String key, String path, String body, String type) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(String key, String path, String body) throws Exception { return M.readTree(post(key, path, body, "application/json").body()); }
    String state(String key, String job) { try { return json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job").path("state").asText(); } catch (Exception e) { return ""; } }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(250); }
    }

    long total(String account, String kind, boolean dollars) throws IOException {
        long n = 0;
        for (AccountStore.MonthTotal t : store.month(account, AccountStore.month(System.currentTimeMillis())))
            if ((kind == null || t.kind().equals(kind)) && t.payer().equals("operator")) n += dollars ? t.micros() : t.quantity();
        return n;
    }

    void say(String form, String line) { System.out.println("[real caps " + form + "] " + line); }

    void run(Path tmp, String image) throws Exception {
        String form = image == null ? "process" : "container";
        model = new StandInModel();
        model.delayMs = 250;
        AtomicInteger searched = new AtomicInteger();
        place = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        place.createContext("/", x -> { x.getRequestBody().readAllBytes(); searched.incrementAndGet(); byte[] b = "{\"web\":{\"results\":[]}}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        place.start();
        NodeGates.BASES.put("brave", "http://127.0.0.1:" + place.getAddress().getPort());
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        Config.set("RESEARCHZOSHO_BRAVE_KEY", "a-stand-in-places-key");
        Config.set(RateLimits.Limit.API.setting(), "off");   // the run is asked after several times a second, as a program on a public service may not; the limits are RateLimitsTest's
        if (image != null) Config.set(ContainerLibraries.IMAGE, image);
        Path root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> image == null ? new ChildLibraries(store, self) : new ContainerLibraries(store, self));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the run"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the run");
        String a = ann.account().id(), b = bo.account().id();
        String ak = store.mintKey(a, ann.library().id(), "write", "the run").secret(), bk = store.mintKey(b, bo.library().id(), "write", "the run").secret();
        store.setCap(a, Caps.MONTH_DOLLARS, 100_000); store.setCap(a, Caps.SEARCHES, 2);
        store.setCap(b, Caps.FETCHES, 2);
        post(bk, "/chat", "say=hello", "application/x-www-form-urlencoded");   // Bo's library started

        // Ann's research into her caps; Bo's chat timed meanwhile
        JsonNode filed = json(ak, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit, and by whom?\"}");
        String job = filed.path("job_id").asText();
        assertTrue(!job.isEmpty(), filed.toString());
        List<Long> turns = new ArrayList<>();
        for (int i = 0; i < 3 && !Jobs.CAPPED.equals(state(ak, job)); i++) {
            Thread.sleep(2_000);
            long t0 = System.currentTimeMillis();
            HttpResponse<String> r = post(bk, "/chat", "say=" + URLEncoder.encode("When was it lit? (turn " + i + ")", StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
            assertTrue(r.statusCode() < 400, r.statusCode() + " " + r.body());
            turns.add(System.currentTimeMillis() - t0);
        }
        say(form, "Bo's chat turns while Ann's run went (a call takes 250 ms at the stand-in): " + turns + " ms");
        until("Ann's run stopped at her cap", 300_000, () -> Jobs.CAPPED.equals(state(ak, job)));
        JsonNode capped = json(ak, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job");
        long spent = total(a, null, true), calls = model.calls.get();
        say(form, "Ann's run stopped at her cap: $" + spent / 1e6 + " of $0.10 spent (the gate's hard ceiling $0.35), " + calls + " model calls so far; searches made " + total(a, "search", false)
                + " of her cap 2 (the place was asked " + searched.get() + " times); it says: \"" + capped.path("waiting").asText() + "\" and \"" + capped.path("resumed").asText() + "\"");
        assertTrue(spent >= 100_000 && spent < 350_000, "stopped past its cap by at most a call or two: " + spent);
        assertEquals(2, total(a, "search", false), "her searches stopped at their cap");
        HttpResponse<String> again = post(ak, "/v1/job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}", "application/json");
        say(form, "Ann resumes while the cap holds: " + again.statusCode() + " \"" + M.readTree(again.body()).path("error").path("message").asText() + "\"");
        assertEquals(429, again.statusCode());
        store.setCap(a, Caps.MONTH_DOLLARS, 1_000_000);   // whoever runs the service raises it to $1.00
        HttpResponse<String> resumed = post(ak, "/v1/job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}", "application/json");
        assertEquals(200, resumed.statusCode(), resumed.body());
        until("Ann's run finished", 600_000, () -> { String st = state(ak, job); return st.equals("done") || st.equals("failed"); });
        JsonNode done = json(ak, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job");
        say(form, "the cap raised to $1.00, Ann resumed: " + done.path("state").asText() + ", \"" + done.path("resumed").asText() + "\"; " + model.calls.get() + " model calls in all; $" + total(a, null, true) / 1e6 + " spent this month");
        assertEquals("done", done.path("state").asText(), done.toString());

        // Bo's fetches to their cap: two pages of the web added, the third refused
        for (String page : List.of("https://example.org/", "https://example.com/")) {
            HttpResponse<String> r = post(bk, "/v1/add", "{\"url\":\"" + page + "\"}", "application/json");
            assertEquals(200, r.statusCode(), r.body());
        }
        long fetchesNow = total(b, "fetch", false), backgroundNow = total(b, "background", false);
        assertEquals(2, fetchesNow, "Bo's two fetches counted as each connection opened, against his adds' uses (his libraries' own meanwhile: " + backgroundNow + ")");
        HttpResponse<String> third = post(bk, "/v1/add", "{\"url\":\"https://example.net/\"}", "application/json");
        say(form, "Bo's fetches: " + total(b, "fetch", false) + " of his cap 2 (his libraries' own connections meanwhile, written as background: " + total(b, "background", false) + "); a third page: " + third.statusCode() + " \"" + M.readTree(third.body()).path("error").path("message").asText() + "\"");
        assertEquals(429, third.statusCode(), third.body());
        assertEquals(CapsSpending.fetchesUsed(2), M.readTree(third.body()).path("error").path("message").asText());

        // Bo's storage at its cap: an add refused, a read allowed
        store.setCap(b, Caps.FETCHES, -1);
        new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_run", root).measureWhenDue();
        until("Bo's library measured", 30_000, () -> { try { return store.lastStorage(bo.library().id()) != null; } catch (IOException e) { return false; } });
        long bytes = store.lastStorage(bo.library().id())[1];
        store.setCap(b, Caps.STORAGE_MB, 0);
        HttpResponse<String> add = post(bk, "/v1/add", "{\"url\":\"https://example.net/\"}", "application/json");
        HttpResponse<String> read = post(bk, "/v1/search", "{\"query\":\"Example Domain\"}", "application/json");
        say(form, "Bo's libraries hold " + bytes + " bytes, his storage cap set to 0 MB: an add " + add.statusCode() + " \"" + M.readTree(add.body()).path("error").path("message").asText() + "\"; a search " + read.statusCode()
                + " with " + M.readTree(read.body()).path("hits").size() + " hits");
        assertEquals(429, add.statusCode(), add.body());
        assertEquals(CapsSpending.storageFull(0), M.readTree(add.body()).path("error").path("message").asText());
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(M.readTree(read.body()).path("hits").size() > 0, "reading goes on");

        // the account page and `accounts usage` against the store
        for (AccountService.Made who : List.of(ann, bo)) {
            String id = who.account().id(), cookie = store.newSession(id, "", "the run", 1);
            String page = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/account")).header("Cookie", AccountService.COOKIE + "=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("<td data-l=\"used\">([^<]*)</td>").matcher(page);
            List<String> used = new ArrayList<>();
            while (m.find()) used.add(m.group(1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AccountsCli.usage(store, List.of(who.account().name()), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
            String cli = out.toString(StandardCharsets.UTF_8);
            long dollars = total(id, null, true), searches = total(id, "search", false), fetches = total(id, "fetch", false), mb = (store.storedBytes(id) + 999_999) / 1_000_000;
            say(form, who.account().name() + ": the store says $" + String.format("%.2f", dollars / 1e6) + ", " + searches + " searches, " + fetches + " fetches, " + mb + " MB; the page's used column " + used
                    + "; accounts usage: " + cli.lines().skip(1).limit(4).map(String::strip).toList());
            assertEquals(List.of(Caps.dollars(dollars), String.valueOf(searches), String.valueOf(fetches), Caps.size(mb)), used.subList(0, 4), "the page shows the store's numbers");
            assertTrue(cli.contains(Caps.dollars(dollars) + " of ") && cli.contains(searches + " searches of") && cli.contains(fetches + " pages fetched of") && cli.contains(Caps.size(mb) + " stored of"), cli);
        }
    }
}
