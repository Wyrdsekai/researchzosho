package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.researchzosho.librarian.AccountsCliTest.Ran;
import static org.researchzosho.librarian.AccountsCliTest.cli;

/**
 * What a real check of several accounts with a person's own Gemini key found (2026-11-18, one paid call): a look at the model server
 * through the node's gate carried only the operator's key, so with own keys alone the library read its model as down; the thinking
 * Gemini bills as output was not counted; and a machine that only serves accounts could not add a model entry, nor set the drive by
 * the word {@code models add} takes. The key in the proof page and the containers of another install are pinned in AccountSignInTest
 * and ModelsIdleTest.
 */
class GeminiCheckTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<String> set = new ArrayList<>();
    AccountStore store; NodeGates gates; HttpServer server;

    void set(String key, String value) throws IOException { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        OwnKeys.forget();
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (server != null) server.stop(0);
    }

    @Test
    void aLookAtTheModelServerCarriesTheKeepersOwnKeyWhereTheServiceHasNone(@TempDir Path tmp) throws Exception {
        set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
        // a server like Gemini's: its model list answers only a call with a key
        List<String> seen = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            seen.add(x.getRequestMethod() + " " + x.getRequestURI().getPath() + " " + auth);
            byte[] b = (auth == null ? "[{\"error\":{\"code\":404}}]" : "{\"object\":\"list\",\"data\":[{\"id\":\"stand-in\"}]}").getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(auth == null ? 404 : 200, b.length); x.getResponseBody().write(b); x.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        set("RESEARCHZOSHO_DRIVE", url); set("RESEARCHZOSHO_MODEL", "stand-in");   // and no key of the operator's for it
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        AccountStore.Account ann = store.createAccount("Ann Example", "", false), ben = store.createAccount("Ben Example", "", false);
        store.addLibrary("lib_ann", "ann-example", "Ann Example", ann.id(), tmp.resolve("ann").toString());
        store.addLibrary("lib_ben", "ben-example", "Ben Example", ben.id(), tmp.resolve("ben").toString());
        OwnKeys.set(store, ann.id(), new OwnKeys.Host("127.0.0.1", OwnKeys.MODEL, "the stand-in"), "anns-own-model-key");
        gates = NodeGates.of(store);
        Map<String, String> annEnv = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", url)), benEnv = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", url));
        gates.rewrite(annEnv, "lib_ann"); gates.rewrite(benEnv, "lib_ben");
        HttpResponse<String> look = c.send(HttpRequest.newBuilder(URI.create(annEnv.get("RESEARCHZOSHO_DRIVE") + "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, look.statusCode(), "the keeper's library sees its model: " + look.body() + " " + seen);
        assertEquals("GET /v1/models Bearer anns-own-model-key", seen.get(seen.size() - 1), "with its keeper's own key, a list costing nothing");
        HttpResponse<String> his = c.send(HttpRequest.newBuilder(URI.create(benEnv.get("RESEARCHZOSHO_DRIVE") + "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, his.statusCode(), "another library's keeper has no key of his own: nobody else's is used");
        assertEquals("GET /v1/models null", seen.get(seen.size() - 1));
        // where the service has a key for the host, a look carries it, as before
        set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(annEnv.get("RESEARCHZOSHO_DRIVE") + "/v1/models")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("GET /v1/models Bearer the-operators-model-key", seen.get(seen.size() - 1));
    }

    static long out(String reply, String type) { return NodeGates.usage(reply.getBytes(StandardCharsets.UTF_8), type)[2]; }

    @Test
    void theThinkingAModelBillsAsOutputIsCountedOnce() {
        // Gemini through its OpenAI door: the thinking is in the total and not in completion_tokens (849 of 2,203 on the check)
        assertEquals(1000, out("{\"usage\":{\"prompt_tokens\":1203,\"completion_tokens\":151,\"total_tokens\":2203}}", "application/json"));
        assertEquals(1000, out("data: {\"choices\":[{\"delta\":{\"content\":\"The\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":1203,\"completion_tokens\":151,\"total_tokens\":2203}}\n\ndata: [DONE]\n", "text/event-stream"), "streamed");
        // OpenAI: completion_tokens holds the reasoning already, and says it again in its details
        assertEquals(500, out("{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":500,\"total_tokens\":600,\"completion_tokens_details\":{\"reasoning_tokens\":400}}}", "application/json"));
        // OpenAI's responses: output_tokens holds it
        assertEquals(500, out("{\"usage\":{\"input_tokens\":100,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens\":500,\"output_tokens_details\":{\"reasoning_tokens\":300},\"total_tokens\":600}}", "application/json"));
        // Anthropic: output_tokens holds the thinking
        assertEquals(500, out("{\"type\":\"message\",\"usage\":{\"input_tokens\":100,\"output_tokens\":500}}", "application/json"));
        // a server that says no total, and an embedding: as before
        assertEquals(151, out("{\"usage\":{\"prompt_tokens\":1203,\"completion_tokens\":151}}", "application/json"));
        assertEquals(0, out("{\"usage\":{\"prompt_tokens\":12,\"total_tokens\":12}}", "application/json"));
    }

    @Test
    void aMachineThatOnlyServesAccountsAddsModelEntriesAndSetsTheDriveByItsWord(@TempDir Path tmp) throws Exception {
        Path none = tmp.resolve("no-library-here");
        set("RESEARCHZOSHO_LIBRARY", none.toString());
        set(AccountService.ROOT, tmp.resolve("accounts").toString());
        set(AccountService.SETTING, "");
        try {
            assertEquals(0, cli("settings", "set", "accounts", "on").code());
            // what the service's own start says to run, run
            Ran add = cli("models", "add", "flash", "gemini", "gemini-flash-latest", "--prices", "0.3/2.5/0.03");
            assertEquals(0, add.code(), add.out() + add.err());
            assertFalse(add.out().contains("no library at"), add.out());
            assertTrue(add.out().startsWith("flash"), add.out());
            Ran list = cli("models", "list");
            assertEquals(0, list.code(), list.out() + list.err());
            assertTrue(list.out().contains("flash"), list.out());
            assertEquals(0, cli("models", "remove", "flash").code());
            assertFalse(cli("models", "list").out().contains("gemini-flash-latest"), "removed");
            // the drive by the word models add takes
            Ran drive = cli("settings", "set", "drive", "gemini");
            assertEquals("drive is set. (https://generativelanguage.googleapis.com/v1beta/openai)\n", drive.out(), drive.err());
            assertEquals("drive is an address and must start with http:// or https://, for example " + Settings.named("drive").example() + ".\n", cli("settings", "set", "drive", "not-a-host-word").err(), "any other word, as before");
            assertEquals(0, cli("settings", "unset", "drive").code());
            assertFalse(Files.exists(none), "no library is made by any of it");
        } finally { Config.set(AccountService.SETTING, ""); ModelEntries.remove("flash"); Config.set("RESEARCHZOSHO_DRIVE", ""); }
    }
}
