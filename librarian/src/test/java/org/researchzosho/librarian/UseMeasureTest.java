package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A measurement, not a check the suite runs (it takes about ten minutes and a browser): what one person's ordinary use asks of the
 * account service, against the defaults of limit.pages and limit.api (0.8.0, stage 4). Through the real service, with each library a
 * process of its own, and a stand-in model slow enough that a research run goes on throughout:
 * (a) a headless Chrome with two tabs of the chat page and two of the run's page while a research run goes, for five minutes, with a
 * chat turn written in one of them meanwhile; every request each tab made, as Chrome reports it;
 * (b) a program over MCP, as a host's model uses the library: a research run filed and followed with library_job until it is done,
 * polled every three seconds, and polled as the job's poll_after says;
 * (c) the first load of the web app in a fresh browser: every request the page made.
 * The service's own files (/static/, the icon) are not counted against the limits and are said apart. Runs when RZ_MEASURE_USE names
 * a Chrome to drive (RZ_MEASURE_USE=/usr/bin/google-chrome).
 */
class UseMeasureTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; AccountStore store; AccountService svc; Process chrome; Cdp cdp;

    @AfterEach void stop() throws Exception {
        try {
            if (cdp != null) cdp.ws.abort();
            if (chrome != null) { chrome.destroy(); if (!chrome.waitFor(20, TimeUnit.SECONDS)) chrome.destroyForcibly(); }   // the browser this test started, by its own handle
            if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close();
        } finally {
            for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), Scheduler.NIGHTS,
                    RateLimits.Limit.API.setting(), RateLimits.Limit.PAGES.setting(), RateLimits.Limit.ADDRESS.setting())) Config.set(k, "");
        }
    }

    /** Chrome's debugging protocol over one connection to the browser, its tabs as flat sessions; every request each tab sends, kept. */
    static final class Cdp {
        final WebSocket ws; final AtomicInteger ids = new AtomicInteger();
        final Map<Integer, CompletableFuture<JsonNode>> waiting = new ConcurrentHashMap<>();
        /** Each tab's requests: its session, and per request when (ms) and the address. */
        final Map<String, List<String[]>> sent = new ConcurrentHashMap<>();
        Cdp(HttpClient http, String url) throws Exception {
            StringBuilder part = new StringBuilder();
            ws = http.newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    part.append(data);
                    if (last) {
                        try {
                            JsonNode m = M.readTree(part.toString());
                            if (m.has("id")) { CompletableFuture<JsonNode> f = waiting.remove(m.get("id").asInt()); if (f != null) f.complete(m); }
                            else if ("Network.requestWillBeSent".equals(m.path("method").asText())) {
                                JsonNode r = m.path("params").path("request");
                                sent.computeIfAbsent(m.path("sessionId").asText(), k -> Collections.synchronizedList(new ArrayList<>()))
                                        .add(new String[]{String.valueOf(System.currentTimeMillis()), r.path("method").asText() + " " + r.path("url").asText()});
                            }
                        } catch (IOException ignored) { }
                        part.setLength(0);
                    }
                    w.request(1);
                    return null;
                }
            }).get(10, TimeUnit.SECONDS);
        }
        JsonNode call(String session, String method, ObjectNode params) throws Exception {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> f = new CompletableFuture<>();
            waiting.put(id, f);
            ObjectNode m = M.createObjectNode(); m.put("id", id); m.put("method", method); if (params != null) m.set("params", params); if (session != null) m.put("sessionId", session);
            ws.sendText(M.writeValueAsString(m), true).get(10, TimeUnit.SECONDS);
            JsonNode r = f.get(60, TimeUnit.SECONDS);
            if (r.has("error")) throw new IllegalStateException(method + ": " + r.get("error"));
            return r.path("result");
        }
        /** A new tab, in a browser context (null: the default one), its requests recorded from its first; signed in with the cookie. */
        String tab(String context, String base, String cookie) throws Exception {
            ObjectNode p = M.createObjectNode().put("url", "about:blank");
            if (context != null) p.put("browserContextId", context);
            String target = call(null, "Target.createTarget", p).path("targetId").asText();
            String session = call(null, "Target.attachToTarget", M.createObjectNode().put("targetId", target).put("flatten", true)).path("sessionId").asText();
            sent.put(session, Collections.synchronizedList(new ArrayList<>()));
            call(session, "Network.enable", M.createObjectNode());
            call(session, "Page.enable", M.createObjectNode());
            call(session, "Network.setCookie", M.createObjectNode().put("name", AccountService.COOKIE).put("value", cookie).put("url", base));
            return session;
        }
        void go(String session, String url) throws Exception { call(session, "Page.navigate", M.createObjectNode().put("url", url)); }
        String eval(String session, String js) throws Exception {
            JsonNode r = call(session, "Runtime.evaluate", M.createObjectNode().put("expression", js).put("returnByValue", true));
            return r.path("result").path("value").asText(r.path("result").path("description").asText(""));
        }
    }

    /** A request the limits count: anything but the service's own files. */
    static boolean counted(String line) { String u = line.substring(line.indexOf(' ') + 1); return !u.contains("/static/") && !u.contains("/favicon."); }

    /** Requests per minute over a window, counted ones and the service's own files apart: the minute's numbers, from its start. */
    static String perMinute(List<String[]> all, long from, int minutes) {
        StringBuilder b = new StringBuilder();
        for (int m = 0; m < minutes; m++) {
            long a = from + m * 60_000L, z = a + 60_000L; int n = 0, files = 0;
            synchronized (all) { for (String[] r : all) { long t = Long.parseLong(r[0]); if (t >= a && t < z) { if (counted(r[1])) n++; else files++; } } }
            b.append(m == 0 ? "" : ", ").append(n).append(files > 0 ? " (+" + files + " files)" : "");
        }
        return b.toString();
    }

    static Map<String, Integer> byPath(List<String[]> all, long from, long to) {
        Map<String, Integer> m = new TreeMap<>();
        synchronized (all) { for (String[] r : all) { long t = Long.parseLong(r[0]); if (t < from || t >= to) continue; String u = URI.create(r[1].substring(r[1].indexOf(' ') + 1)).getPath(); String k = r[1].substring(0, r[1].indexOf(' ')) + " " + (u.startsWith("/static/") ? "/static/…" : u.startsWith("/jobs/") ? "/jobs/<id>" : u); m.merge(k, 1, Integer::sum); } }
        return m;
    }

    JsonNode rpc(String key, int id, String method, String params) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\"," + (id > 0 ? "\"id\":" + id + "," : "") + "\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/rpc")).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 200 || r.statusCode() == 202, r.statusCode() + " " + r.body());
        return r.body().isBlank() ? M.createObjectNode() : M.readTree(r.body());
    }

    static JsonNode toolJson(JsonNode env) throws IOException { return M.readTree(env.path("result").path("content").get(0).path("text").asText("{}")); }

    /** A host following a run over MCP: what it asks, per minute, until the run is done; every {@code everyMs}, or as poll_after says when 0. */
    String host(String key, long everyMs) throws Exception {
        List<Long> at = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        rpc(key, 1, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"a host\",\"version\":\"1\"}}"); at.add(System.currentTimeMillis());
        rpc(key, 0, "notifications/initialized", null); at.add(System.currentTimeMillis());
        rpc(key, 2, "tools/list", null); at.add(System.currentTimeMillis());
        JsonNode filed = toolJson(rpc(key, 3, "tools/call", "{\"name\":\"library_research\",\"arguments\":{\"question\":\"When was the harbour's lighthouse first lit, and by whom?\"}}")); at.add(System.currentTimeMillis());
        String job = filed.path("job_id").asText();
        assertTrue(!job.isEmpty(), filed.toString());
        String state = ""; List<Integer> said = new ArrayList<>(); int id = 4;
        while (System.currentTimeMillis() - t0 < 15 * 60_000L) {
            JsonNode j = toolJson(rpc(key, id++, "tools/call", "{\"name\":\"library_job\",\"arguments\":{\"job_id\":\"" + job + "\"}}")).path("job"); at.add(System.currentTimeMillis());
            state = j.path("state").asText();
            if (state.equals("done") || state.equals("failed") || state.equals("stopped")) break;
            int wait = j.path("poll_after").asInt(0); said.add(wait);
            Thread.sleep(everyMs > 0 ? everyMs : Math.max(1, wait) * 1000L);
        }
        long span = System.currentTimeMillis() - t0;
        int peak = 0; for (long s = t0; s < t0 + span; s += 5_000) { long a = s; int n = (int) at.stream().filter(t -> t >= a && t < a + 60_000).count(); peak = Math.max(peak, n); }
        return at.size() + " calls in " + span / 1000 + " s until " + state + ", the busiest minute " + peak + ", poll_after said " + said.stream().distinct().toList();
    }

    @Test
    void onePersonsOrdinaryUseAgainstTheDefaults(@TempDir Path tmp) throws Exception {
        String browser = System.getenv("RZ_MEASURE_USE");
        Assumptions.assumeTrue(browser != null && !browser.isBlank(), "a measurement: RZ_MEASURE_USE=<a Chrome to drive>");
        model = new StandInModel(); model.delayMs = 20_000;   // a research run goes on throughout the five minutes
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        for (RateLimits.Limit l : List.of(RateLimits.Limit.API, RateLimits.Limit.PAGES, RateLimits.Limit.ADDRESS)) Config.set(l.setting(), "off");   // what is asked, all of it, never refused here
        Path root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1); store.setCap("*", Caps.MONTH_DOLLARS, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "a host").secret(), cookie = store.newSession(ann.account().id(), "", "a browser", 1);
        HttpResponse<String> filed = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/research")).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"When was the lighthouse of the harbour first lit, and by whom?\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String job = M.readTree(filed.body()).path("job_id").asText();
        assertTrue(!job.isEmpty(), filed.body());

        // the browser: a profile of its own, gone with the test's folder
        Path profile = Files.createDirectories(tmp.resolve("chrome-profile"));
        chrome = new ProcessBuilder(browser, "--headless=new", "--remote-debugging-port=0", "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank")
                .redirectErrorStream(true).redirectOutput(tmp.resolve("chrome.log").toFile()).start();
        Path port = profile.resolve("DevToolsActivePort");
        for (int i = 0; i < 200 && !Files.exists(port); i++) Thread.sleep(100);
        List<String> lines = Files.readAllLines(port);
        cdp = new Cdp(c, "ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip());

        // (c) the first load, in a browser context of its own with nothing kept: every request the page made in twenty seconds
        String fresh = cdp.call(null, "Target.createBrowserContext", M.createObjectNode()).path("browserContextId").asText();
        String first = cdp.tab(fresh, svc.url(), cookie);
        long c0 = System.currentTimeMillis();
        cdp.go(first, svc.url() + "/");
        Thread.sleep(20_000);
        List<String[]> load = cdp.sent.get(first);
        Map<String, Integer> loaded = byPath(load, c0, c0 + 20_000);
        int loadCounted = 0, loadFiles = 0; for (Map.Entry<String, Integer> e : loaded.entrySet()) if (counted(e.getKey())) loadCounted += e.getValue(); else loadFiles += e.getValue();
        System.out.println("[use] (c) the first load: " + loadCounted + " requests counted, " + loadFiles + " of the service's own files: " + loaded);
        cdp.call(null, "Target.disposeBrowserContext", M.createObjectNode().put("browserContextId", fresh));

        // (a) two tabs of the chat page and two of the run's page, for five minutes; a chat turn in the first after a minute
        String chat1 = cdp.tab(null, svc.url(), cookie), chat2 = cdp.tab(null, svc.url(), cookie), run1 = cdp.tab(null, svc.url(), cookie), run2 = cdp.tab(null, svc.url(), cookie);
        long a0 = System.currentTimeMillis();
        cdp.go(chat1, svc.url() + "/"); cdp.go(chat2, svc.url() + "/"); cdp.go(run1, svc.url() + "/jobs/" + job); cdp.go(run2, svc.url() + "/jobs/" + job);
        Thread.sleep(60_000);
        String sent = cdp.eval(chat1, "(function(){var t=document.querySelector('textarea[name=say]');if(!t)return 'no box';t.value='What did the lighthouse keeper write in the first log?';t.form.requestSubmit();return 'sent'})()");
        assertEquals("sent", sent);
        long turn0 = System.currentTimeMillis(), turn1 = 0;
        while (System.currentTimeMillis() - a0 < 5 * 60_000L) {
            Thread.sleep(1_000);
            if (turn1 == 0 && "done".equals(cdp.eval(chat1, "document.querySelector('p.thinking')||document.querySelector('.turn.lib.live')?'busy':'done'"))) turn1 = System.currentTimeMillis();
        }
        long turnMs = (turn1 == 0 ? System.currentTimeMillis() : turn1) - turn0;
        long live; synchronized (cdp.sent.get(chat1)) { live = cdp.sent.get(chat1).stream().filter(r -> r[1].contains("/chat/live")).count(); }
        System.out.println("[use] (a) per minute, from the first: the first chat tab " + perMinute(cdp.sent.get(chat1), a0, 5) + "; the second " + perMinute(cdp.sent.get(chat2), a0, 5)
                + "; the run's page " + perMinute(cdp.sent.get(run1), a0, 5) + " and " + perMinute(cdp.sent.get(run2), a0, 5));
        List<String[]> all = new ArrayList<>(); for (String s : List.of(chat1, chat2, run1, run2)) all.addAll(cdp.sent.get(s));
        all.sort((x, y) -> Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0])));
        int peak = 0; for (long s = a0; s + 60_000 <= a0 + 5 * 60_000L; s += 5_000) { long a = s; int n = (int) all.stream().filter(r -> Long.parseLong(r[0]) >= a && Long.parseLong(r[0]) < a + 60_000 && counted(r[1])).count(); peak = Math.max(peak, n); }
        System.out.println("[use] (a) all four tabs: " + perMinute(all, a0, 5) + "; the busiest minute (any 60 s) " + peak + "; by path over the five minutes " + byPath(all, a0, a0 + 5 * 60_000L));
        System.out.println("[use] (a) the chat turn took " + turnMs / 1000 + " s and its page asked /chat/live " + live + " times: " + (turnMs > 0 ? String.format("%.1f", live * 60_000.0 / turnMs) : "-") + " a minute while a reply is written");
        System.out.println("[use] (a) the run was " + M.readTree(c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/jobs/" + job)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("state").asText() + " at the end of the five minutes");
        c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/v1/jobs/" + job)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"stop\"}")).build(), HttpResponse.BodyHandlers.ofString());

        // (b) a host over MCP: a run filed and followed until done, every three seconds, then as poll_after says
        model.delayMs = 300;
        System.out.println("[use] (b) every 3 s: " + host(key, 3_000));
        System.out.println("[use] (b) as poll_after says: " + host(key, 0));
    }
}
