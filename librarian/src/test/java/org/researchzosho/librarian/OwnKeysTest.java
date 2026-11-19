package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person's own keys (0.8.0, stage 3, step 4): kept sealed in the store, with the key that seals them in a file of its own outside
 * the accounts' folder (0600); the node's gate uses a person's key for their work at that host, counted as theirs and free of the
 * operator's caps; when the sealing key is lost, the keys cannot be opened, the person is told to set theirs again, and the work goes
 * on with the service's keys.
 */
class OwnKeysTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<String> set = new ArrayList<>();
    StandInModel model; AccountStore store; NodeGates gates;

    void set(String key, String value) throws IOException { Config.set(key, value); set.add(key); }

    @AfterEach void stop() throws Exception {
        for (String k : set) Config.set(k, "");
        OwnKeys.forget();
        if (gates != null) gates.close();
        if (store != null) store.close();
        if (model != null) model.close();
    }

    AccountStore.Use use(String account) throws IOException {
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), account, "lib_" + account, SpendPurse.RESEARCH, "", "operator", "", 0, 0, -1, -1, "open", "", System.currentTimeMillis(), 0, 0);
        store.addUse(u);
        return u;
    }

    long micros(String account, String payer) throws IOException {
        return store.month(account, AccountStore.month(System.currentTimeMillis())).stream().filter(t -> t.payer().equals(payer)).mapToLong(AccountStore.MonthTotal::micros).sum();
    }

    @Test
    void theSealingKeyIsAFileOfItsOwnAndAKeyOpensOnlyForItsAccountAndHost(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keys").resolve("own.key");
        set(OwnKeys.FILE, file.toString());
        OwnKeys.Kek k = OwnKeys.kek();
        assertTrue(Files.exists(file));
        try { assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "readable by whoever runs the service alone"); }
        catch (UnsupportedOperationException notPosix) { /* a system without these permissions */ }
        String sealed = OwnKeys.seal(k, "acct_a", "api.example.org", "sk-the-persons-own");
        assertFalse(sealed.contains("the-persons-own"), "sealed, not written as it is");
        assertEquals("sk-the-persons-own", OwnKeys.open(k, new AccountStore.OwnKey("acct_a", "api.example.org", OwnKeys.MODEL, sealed, k.id(), 0)));
        assertNull(OwnKeys.open(k, new AccountStore.OwnKey("acct_b", "api.example.org", OwnKeys.MODEL, sealed, k.id(), 0)), "another account's row does not open it");
        assertNull(OwnKeys.open(k, new AccountStore.OwnKey("acct_a", "api.other.org", OwnKeys.MODEL, sealed, k.id(), 0)), "nor another host's");
        OwnKeys.forget();
        set(OwnKeys.FILE, AccountService.root().resolve("own.key").toString());
        assertThrows(IOException.class, OwnKeys::kek, "never inside the accounts' folder, where the libraries and the store's copies are");
    }

    @Test
    void aPersonsOwnKeyCarriesTheirWorkAtTheGateAndALostSealingKeyLosesOnlyTheirKeys(@TempDir Path tmp) throws Exception {
        set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
        model = new StandInModel();
        set("RESEARCHZOSHO_DRIVE", model.url()); set("RESEARCHZOSHO_MODEL", "stand-in"); set("RESEARCHZOSHO_API_KEY", "the-operators-model-key");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        Map<String, String> env = new HashMap<>(Map.of("RESEARCHZOSHO_DRIVE", model.url()));
        gates.rewrite(env, "lib_acct_ann");
        String body = "{\"model\":\"stand-in\",\"messages\":[{\"role\":\"user\",\"content\":\"When was the lighthouse lit?\"}]}";
        AccountStore.Use u = use("acct_ann");
        // the operator's month is spent: the service's key is refused, the person's own key is not
        store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), "acct_ann", "lib_acct_ann", "", "model", "operator", "default", 1, 0, 1, 1, 0, 300_000, false)));
        store.setCap("acct_ann", Caps.MONTH_DOLLARS, 0);
        HttpRequest call = HttpRequest.newBuilder(URI.create(env.get("RESEARCHZOSHO_DRIVE") + "/v1/chat/completions")).header(Gated.USE, u.id()).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(402, c.send(call, HttpResponse.BodyHandlers.ofString()).statusCode(), "on the service's key, past the month");
        OwnKeys.set(store, "acct_ann", new OwnKeys.Host("127.0.0.1", OwnKeys.MODEL, "the stand-in"), "the-persons-own-key");
        assertFalse(store.ownKey("acct_ann", "127.0.0.1").sealed().contains("the-persons-own-key"), "the store holds it sealed");
        HttpResponse<String> r = c.send(call, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("Bearer the-persons-own-key", model.lastAuthorization, "the gate sent the person's own key");
        assertTrue(micros("acct_ann", "own") > 0, "counted as theirs");
        assertEquals(300_000, micros("acct_ann", "operator"), "and not against the operator's dollars");
        set(ModelEntries.key(ModelEntries.DEFAULT, "PRICES"), "1/2/0.5");
        assertEquals("own", OwnKeys.payerFor(store, "acct_ann", ""), "a use on the default entry is theirs as it starts");

        // the sealing key lost: a new one is made; the person's key cannot be opened, so the work goes on the service's key, and they are told
        OwnKeys.forget();
        set(OwnKeys.FILE, tmp.resolve("keys").resolve("a-new-one.key").toString());
        assertNull(OwnKeys.keyFor(store, "acct_ann", "127.0.0.1"));
        assertTrue(OwnKeys.lost(store.ownKey("acct_ann", "127.0.0.1")));
        assertEquals("operator", OwnKeys.payerFor(store, "acct_ann", ""));
        store.unsetCap("acct_ann", Caps.MONTH_DOLLARS);
        r = c.send(call, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("Bearer the-operators-model-key", model.lastAuthorization, "nothing else breaks: the service's key carries it");
        StringBuilder page = new StringBuilder();
        AccountStore.Account ann = store.createAccount("Ann Example", "", false);
        store.setOwnKey(ann.id(), "brave", OwnKeys.SEARCH, store.ownKey("acct_ann", "127.0.0.1").sealed(), store.ownKey("acct_ann", "127.0.0.1").kek());
        AccountPages.ownKeys(page, store, ann);
        assertTrue(page.toString().contains("It can no longer be read: set it again."), "the person is told on their account page");
        OwnKeys.set(store, ann.id(), OwnKeys.host("brave"), "a-brave-key");
        assertEquals("a-brave-key", OwnKeys.keyFor(store, ann.id(), "brave"), "set again, it opens");
    }

    @Test
    void aSearchOnThePersonsOwnKeyIsTheirsAndFreeOfTheServicesSearches(@TempDir Path tmp) throws Exception {
        set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
        set("RESEARCHZOSHO_BRAVE_KEY", "the-operators-brave-key");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        gates = NodeGates.of(store);
        List<String> seen = new ArrayList<>();
        HttpServer place = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        place.createContext("/", x -> { seen.add(x.getRequestHeaders().getFirst("X-Subscription-Token")); byte[] b = "{}".getBytes(); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        place.start();
        Map<String, String> bases = new HashMap<>(NodeGates.BASES);
        try {
            NodeGates.BASES.put("brave", "http://127.0.0.1:" + place.getAddress().getPort());
            Map<String, String> env = new HashMap<>();
            gates.rewrite(env, "lib_acct_bo");
            AccountStore.Use u = use("acct_bo");
            store.setCap("acct_bo", Caps.SEARCHES, 0);
            HttpRequest search = HttpRequest.newBuilder(URI.create(env.get(NodeGates.SEARCH_GATE) + "/brave/res/v1/web/search?q=lighthouse")).header(Gated.USE, u.id()).GET().build();
            assertEquals(402, c.send(search, HttpResponse.BodyHandlers.ofString()).statusCode(), "the service's searches are used up");
            OwnKeys.set(store, "acct_bo", OwnKeys.host("brave"), "bos-own-brave-key");
            assertEquals(200, c.send(search, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(List.of("bos-own-brave-key"), seen);
            assertEquals(5_000, micros("acct_bo", "own"), "at the place's price, as theirs");
        } finally { place.stop(0); NodeGates.BASES.clear(); NodeGates.BASES.putAll(bases); }
    }
}
