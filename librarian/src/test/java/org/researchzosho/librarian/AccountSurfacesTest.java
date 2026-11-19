package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3's surfaces, through the service and real libraries (0.8.0, stage 3, step 5): an account's month is the same at the service's
 * usage call, at a library's spend call and over MCP, and the numbers are the store's; a refusal is the same sentence in the API, over
 * MCP and on the pages; and a research run its cap stops waits, its work kept, is refused again while the cap holds, and resumes and
 * finishes once the cap is raised.
 */
class AccountSurfacesTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    StandInModel model; AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); }
        finally { for (String k : List.of("RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_MODEL", ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), Scheduler.NIGHTS)) Config.set(k, "");
            Config.set(RateLimits.Limit.API.setting(), ""); }
    }

    HttpResponse<String> post(String key, String path, String body, String type) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(String key, String path, String body) throws Exception { return M.readTree(post(key, path, body, "application/json").body()); }

    JsonNode tool(String key, String name, String args) throws Exception {
        return json(key, "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + args + "}}");
    }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(200); }
    }

    void start(Path tmp, long delayMs) throws Exception {
        model = new StandInModel();
        model.delayMs = delayMs;
        Config.set("RESEARCHZOSHO_DRIVE", model.url()); Config.set("RESEARCHZOSHO_MODEL", "stand-in"); Config.set(Scheduler.NIGHTS, "off");
        Config.set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        Config.set(RateLimits.Limit.API.setting(), "off");   // the run is asked after several times a second, as a program on a public service may not; the limits are RateLimitsTest's
        store = AccountStore.open(tmp.resolve("accounts").resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1);
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
    }

    long operatorMicros(String account) throws IOException {
        return store.month(account, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.payer().equals("operator")).mapToLong(AccountStore.MonthTotal::micros).sum();
    }

    @Test
    void theMonthAndEveryRefusalAreTheSameOnEverySurface(@TempDir Path tmp) throws Exception {
        start(tmp, 0);
        Path root = tmp.resolve("accounts");
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret(), acct = ann.account().id();
        post(key, "/chat", "say=" + URLEncoder.encode("When was the lighthouse lit?", StandardCharsets.UTF_8), "application/x-www-form-urlencoded");

        JsonNode usage = M.readTree(c.send(HttpRequest.newBuilder(URI.create(svc.url() + AccountService.USAGE)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        JsonNode spend = json(key, "/v1/spend", "{}").path("account_month");
        JsonNode mcp = tool(key, "library_spend", "{}");
        assertEquals(usage.path("rows"), spend.path("rows"), "the library's spend call gives the service's month");
        assertEquals(operatorMicros(acct), usage.path("rows").get(0).path("used_value").asLong(), "and its numbers are the store's");
        assertTrue(operatorMicros(acct) > 0);
        String text = usage.path("text").asText();
        assertTrue(text.startsWith("The service's model and searches: $0."), text);
        assertTrue(mcp.path("result").path("content").get(0).path("text").asText().contains(text.substring(0, text.indexOf('\n'))), "and over MCP: " + mcp);

        // a refusal before a use starts: the same sentence in the API, over MCP, on the research page and in the chat
        store.setCap(acct, Caps.MONTH_DOLLARS, 0);
        String said = CapsSpending.monthUsed(0);
        HttpResponse<String> api = post(key, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit?\"}", "application/json");
        assertEquals(429, api.statusCode(), api.body());
        assertEquals(said, M.readTree(api.body()).path("error").path("message").asText());
        JsonNode viaMcp = tool(key, "library_research", "{\"question\":\"When was the lighthouse of the harbour first lit?\"}");
        assertTrue(viaMcp.toString().contains(said), "over MCP: " + viaMcp);
        String page = post(key, "/research", "question=" + URLEncoder.encode("When was the lighthouse of the harbour first lit?", StandardCharsets.UTF_8), "application/x-www-form-urlencoded").body();
        assertTrue(page.contains(Pages.esc(said)), "on the research page");
        String chat = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/chat")).header("Authorization", "Bearer " + key).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("say=" + URLEncoder.encode("And who lit it?", StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString()).body();
        String conversation = chat.contains(Pages.esc(said)) ? chat : c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/")).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(conversation.contains(Pages.esc(said)), "in the chat");
    }

    @Test
    void aRunItsCapStopsWaitsWithItsWorkKeptAndResumesWhenTheCapIsRaised(@TempDir Path tmp) throws Exception {
        start(tmp, 250);
        Path root = tmp.resolve("accounts");
        AccountService.Made bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String key = store.mintKey(bo.account().id(), bo.library().id(), "write", "the test").secret(), acct = bo.account().id();
        store.setCap(acct, Caps.MONTH_DOLLARS, 100_000);   // $0.10: the run spends that in a few dozen calls
        JsonNode filed = json(key, "/v1/research", "{\"question\":\"When was the lighthouse of the harbour first lit, and by whom?\"}");
        String job = filed.path("job_id").asText();
        assertTrue(!job.isEmpty(), filed.toString());
        until("the cap stopped the run", 240_000, () -> { try { return Jobs.CAPPED.equals(json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job").path("state").asText()); } catch (Exception e) { return false; } });
        JsonNode capped = json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job");
        long callsAtCap = model.calls.get(), spentAtCap = operatorMicros(acct);
        System.out.println("[cap] stopped after " + callsAtCap + " calls, $" + spentAtCap / 1e6 + " spent of $0.10 (the gate's hard ceiling is $0.35); it says: " + capped.path("waiting").asText() + " / " + capped.path("resumed").asText());
        assertTrue(spentAtCap >= 100_000 && spentAtCap < 350_000, "stopped at its cap, past it by at most the call in flight: " + spentAtCap);
        assertTrue(capped.path("resumed").asText().startsWith("Stopped by its cap"), capped.toString());
        Thread.sleep(2_000);
        assertEquals(callsAtCap, model.calls.get(), "nothing more is asked while it waits");

        // resumed while the cap holds: the service's sentence, in the API and over MCP; the run waits as it was
        HttpResponse<String> again = post(key, "/v1/job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}", "application/json");
        assertEquals(429, again.statusCode(), again.body());
        assertEquals(CapsSpending.monthUsed(100_000), M.readTree(again.body()).path("error").path("message").asText());
        assertTrue(tool(key, "library_job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}").toString().contains(CapsSpending.monthUsed(100_000)));
        String runs = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/jobs")).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(runs.contains("name=\"op\" value=\"resume\"><input type=\"hidden\" name=\"job_id\" value=\"" + job + "\""), "the runs page offers its resume");
        String onPage = post(key, "/jobs", "op=resume&job_id=" + job, "application/x-www-form-urlencoded").body();
        assertTrue(onPage.contains(Pages.esc(CapsSpending.monthUsed(100_000))), "and its button is told the same sentence");
        assertEquals(Jobs.CAPPED, json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job").path("state").asText());

        // the cap raised: it resumes where it stopped, and finishes
        store.setCap(acct, Caps.MONTH_DOLLARS, -1);
        HttpResponse<String> resumed = post(key, "/v1/job", "{\"op\":\"resume\",\"job_id\":\"" + job + "\"}", "application/json");
        assertEquals(200, resumed.statusCode(), resumed.body());
        until("the run finished", 300_000, () -> { try { String st = json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job").path("state").asText(); return st.equals("done") || st.equals("failed"); } catch (Exception e) { return false; } });
        JsonNode done = json(key, "/v1/job", "{\"job_id\":\"" + job + "\"}").path("job");
        System.out.println("[cap] resumed and " + done.path("state").asText() + " after " + model.calls.get() + " calls in all; it says: " + done.path("resumed").asText());
        assertEquals("done", done.path("state").asText(), done.toString());
        assertTrue(done.path("resumed").asText().startsWith("Resumed after its cap stopped it"), done.toString());
        assertEquals(2, store.uses(acct, 0).stream().filter(u -> u.job().equals(job) && !u.state().equals("refused")).count(), "two uses, one each side of the cap");
        assertEquals(3, store.uses(acct, 0).stream().filter(u -> u.job().equals(job) && u.state().equals("refused")).count(), "and the three resumes the cap refused (API, MCP, page), written down as refused");
    }
}
