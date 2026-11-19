package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the setting off, which is how it ships, ResearchZosho is one library and one keeper as it always was. The switch is on only when
 * the setting says on. A library on its own takes no notice of a statement: a header that claims to be one, and a caller that claims a
 * hosted account's id, change no answer. Its keeper still does everything of the machine's that a hosted keeper may not. Nothing of the
 * account service is made on disk.
 */
class AccountsOffTest {
    static final ObjectMapper M = new ObjectMapper();

    @AfterEach void reset() throws Exception { WebAccess.OVERRIDE = null; Config.set(AccountService.SETTING, ""); }

    @Test
    void theSwitchIsOnOnlyWhenTheSettingSaysOn() throws Exception {
        assertFalse(AccountService.on(), "off as shipped");
        for (String v : new String[]{"off", "true", "yes", "1", "accounts", ""}) { Config.set(AccountService.SETTING, v); assertFalse(AccountService.on(), "\"" + v + "\" is not on"); }
        Config.set(AccountService.SETTING, "on"); assertTrue(AccountService.on());
        Config.set(AccountService.SETTING, "ON"); assertTrue(AccountService.on());
        assertTrue(Settings.named("accounts") != null && Settings.named("accounts").choices().equals(List.of("on", "off")));
    }

    static HttpResponse<String> send(HttpClient c, HttpRequest.Builder b) throws Exception { return c.send(b.build(), HttpResponse.BodyHandlers.ofString()); }

    /**
     * An answer as it is compared: without the moment it was made, and without the chat page's new conversation, whose name is the
     * second it was opened in (two pages asked a second apart differ there, header or not).
     */
    static String same(String body) { return body.replaceAll("\"at\":\"[^\"]*\"", "").replaceAll("name=\"session\" value=\"[^\"]*\"", "name=\"session\""); }

    @Test
    void aLibraryOnItsOwnTakesNoNoticeOfAStatement(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        WebAccess.OVERRIDE = Boolean.TRUE;   // the sign-in is on, so a caller with nothing is not the keeper
        Patrons.setDefault(store, Patrons.Level.read);   // and whoever is not listed reads at most
        ApiKeys.Minted keeper = ApiKeys.mint(store, Accounts.OWNER, "the keeper's own key", Patrons.Level.write);
        Path beside = tmp.resolve("a-file-of-the-keepers.txt");
        Files.writeString(beside, "A note.\n\nThe keeper's own file, read by its path as it always was.\n");
        Path accountsBefore = AccountService.root();
        boolean rootWasThere = Files.exists(accountsBefore);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            assertFalse(Hosted.on(store)); assertFalse(Hosted.any());
            // a statement nobody asked for: signed, for this library, naming a keeper
            KeyPair anyone = Statement.newKey();
            String statement = Statement.sign(anyone.getPrivate(), "k1", Statement.Claims.now("http://accounts.test", store.identity().id(), "a1", "Ann", Statement.KEEPER, Statement.SESSION));
            String add = "{\"op\":\"add\",\"question\":\"What did the first reader ask for at the desk on the day the library opened?\"}";
            for (String[] ask : new String[][]{{"GET", "/v1/status", ""}, {"POST", "/v1/frontier", add}, {"POST", "/v1/settings", "{\"op\":\"list\"}"}, {"GET", "/", ""}, {"GET", "/keep", ""}}) {
                HttpRequest.Builder plain = HttpRequest.newBuilder(URI.create(base + ask[1])), claimed = HttpRequest.newBuilder(URI.create(base + ask[1])).header(Statement.HEADER, statement);
                if (ask[0].equals("POST")) { plain.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ask[2])); claimed.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(ask[2])); }
                HttpResponse<String> without = send(c, plain), with = send(c, claimed);
                assertEquals(without.statusCode(), with.statusCode(), ask[0] + " " + ask[1]);
                assertEquals(same(without.body()), same(with.body()), ask[0] + " " + ask[1] + ": the same answer with the header as without");
            }
            assertEquals(403, send(c, HttpRequest.newBuilder(URI.create(base + "/v1/frontier")).header(Statement.HEADER, statement).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(add))).statusCode(), "and that answer is no");
            // a caller that names a hosted account's id, with a level beside it, is refused as any unproved id is
            HttpResponse<String> named = send(c, HttpRequest.newBuilder(URI.create(base + "/v1/frontier")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"add\",\"question\":\"A question nobody may file this way, however it is dressed?\",\"patron\":{\"did\":\"did:rz:a1\",\"name\":\"Ann\",\"vouch\":{\"level\":\"write\",\"keeper\":true}}}")));
            assertEquals(403, named.statusCode(), named.body());
            assertTrue(named.body().contains("needs a bearer token"), named.body());
            // the keeper, by their own key, does what is the machine's: every setting is listed, the models are theirs, a file is read by its path
            JsonNode settings = M.readTree(send(c, HttpRequest.newBuilder(URI.create(base + "/v1/settings")).header("Authorization", "Bearer " + keeper.secret()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"list\"}"))).body());
            assertTrue(settings.path("settings").size() > 80 && settings.has("config_file"), "every setting, and where the file is");
            assertEquals(200, send(c, HttpRequest.newBuilder(URI.create(base + "/v1/models")).header("Authorization", "Bearer " + keeper.secret()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"list\"}"))).statusCode());
            HttpResponse<String> byPath = send(c, HttpRequest.newBuilder(URI.create(base + "/v1/add")).header("Authorization", "Bearer " + keeper.secret()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"" + beside + "\"}")));
            assertEquals(200, byPath.statusCode(), byPath.body());
            JsonNode tools = M.readTree(send(c, HttpRequest.newBuilder(URI.create(base + "/rpc")).header("Authorization", "Bearer " + keeper.secret()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))).body()).path("result").path("tools");
            assertTrue(tools.toString().contains("library_share") && tools.toString().contains("library_models") && tools.toString().contains("library_db"), "the tools of the machine's are offered, as always");
            // the places that act on the machine take everything
            Config.set("RESEARCHZOSHO_JUDGE_MODEL", "x"); Config.set("RESEARCHZOSHO_JUDGE_MODEL", "");
        } finally { d.stop(); }
        assertEquals(rootWasThere, Files.exists(accountsBefore), "nothing of the account service was made on disk");
    }
}
