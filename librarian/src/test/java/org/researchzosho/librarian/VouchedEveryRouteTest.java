package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.mcp.McpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library under an account service answers a request by the statement the service signed for it, and by nothing else. Every route is
 * asked with no statement, with one made for another library, with one whose minute has passed and with one signed by another key: each
 * is refused with the statement's own sentence, before anything of the request is read. The same request with a good statement is not
 * refused with any of those sentences, so a route that fails for a reason of its own does not pass for one that refuses. The health
 * check is the one address open without a statement. The routes are every POST /v1 route, the GET routes, every MCP tool, every page
 * address the pages know, the agent addresses, and two hundred addresses nothing serves.
 */
class VouchedEveryRouteTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String SERVICE = "http://accounts.test";
    /** What a refusal for the statement's sake says, one phrase for each way a statement fails. */
    static final String NONE = "carries no statement", OTHER = "made for another library", EXPIRED = "has expired", FORGED = "does not check against the account service's key";
    static final List<String> SENTENCES = List.of(NONE, OTHER, EXPIRED, FORGED, "by another account service");
    /** The routes a body with an unknown op would send to a model or start a night's work with: not asked with a good statement here. */
    static final Set<String> NOT_ASKED_WHEN_GOOD = Set.of("/v1/help", "/v1/jobs/J-0001", "/v1/crews/run", "/v1/research");

    @AfterEach void reset() { Proxy.OVERRIDE = null; }

    record Ask(String method, String path, String body) { }

    static List<Ask> everything() {
        List<Ask> all = new ArrayList<>();
        for (String r : LibrarianDaemon.v1Routes()) all.add(new Ask("POST", "/v1/" + r.replace("{id}", "J-0001"), "{\"op\":\"no-such-op\"}"));
        for (String g : List.of("/v1/status", "/v1/jobs", "/v1/jobs/J-0001", "/v1/resources", "/v1/resources/templates", "/v1/resource?uri=library%3A%2F%2Fx")) all.add(new Ask("GET", g, ""));
        all.add(new Ask("POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        for (JsonNode t : McpServer.allTools()) all.add(new Ask("POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + t.path("name").asText() + "\",\"arguments\":{\"op\":\"no-such-op\"}}}"));
        List<String> pages = new ArrayList<>(List.of("/", "/keep", "/past", "/updates", "/connect", "/add", "/translate", "/search", "/ask", "/entry/F-0001", "/read", "/subjects", "/changes", "/questions", "/dossier", "/suggest",
                "/inbox", "/jobs", "/jobs/J-0001", "/research", "/help", "/sources", "/login", "/login/code", "/login/oidc", "/login/oidc/callback", "/login/passkey", "/login/passkey/options", "/logout", "/join", "/account",
                "/account/passkey", "/account/passkey/options", "/explain", "/download", "/exports/x.pdf", "/images/x.png", ImagesPage.PATH, "/map", "/chat", "/chat/runs", "/chat/live", "/remove", "/settings", "/owned",
                SeeksPage.PATH, PerspectivesPage.PATH, SubmitPage.PATH, ForecastsPage.PATH, BridgesPage.PATH, LaunchPage.PATH, DbPage.PATH));
        for (Profile p : Profiles.known()) pages.addAll(p.pagePaths());
        for (String p : pages) { assertTrue(Pages.isPage(p), p + " is a page"); all.add(new Ask("GET", p, "")); all.add(new Ask("POST", p, "op=x")); }
        for (String other : List.of("/static/" + Pages.MERMAID_PATH.substring(Pages.MERMAID_PATH.lastIndexOf('/') + 1), "/favicon.ico", "/favicon.png", A2a.CARD, A2a.PATH)) { all.add(new Ask("GET", other, "")); all.add(new Ask("POST", other, "{}")); }
        Random r = new Random(4649);
        for (int i = 0; i < 200; i++) { StringBuilder p = new StringBuilder(); for (int s = 1 + r.nextInt(3); s > 0; s--) { p.append('/'); for (int c = 1 + r.nextInt(8); c > 0; c--) p.append((char) ('a' + r.nextInt(26))); } all.add(new Ask(i % 2 == 0 ? "GET" : "POST", p.toString(), i % 2 == 0 ? "" : "{}")); }
        return all;
    }

    static HttpResponse<String> send(HttpClient c, String base, Ask a, String statement, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + a.path()));
        if (statement != null) b.header(Statement.HEADER, statement);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        if (a.method().equals("GET")) b.GET(); else b.header("Content-Type", a.path().startsWith("/v1/") || a.path().equals("/rpc") ? "application/json" : "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(a.body()));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static boolean forTheStatement(HttpResponse<String> r) { return r.statusCode() == 403 && SENTENCES.stream().anyMatch(r.body()::contains); }

    @Test
    void everyRouteRefusesAStatementThatIsNotGoodAndTakesOneThatIs(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        KeyPair service = Statement.newKey(), stranger = Statement.newKey();
        Vouching v = new Vouching(SERVICE, kid -> kid.equals("k1") ? service.getPublic() : null);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, v);
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = "http://127.0.0.1:" + d.port(), here = store.identity().id();
        long now = System.currentTimeMillis() / 1000;
        try {
            String good = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "a1", "Ann", Statement.KEEPER, Statement.SESSION));
            Map<String, String> bad = new LinkedHashMap<>();
            bad.put(OTHER, Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, "lib_another", "a1", "Ann", Statement.KEEPER, Statement.SESSION)));
            bad.put(EXPIRED, Statement.sign(service.getPrivate(), "k1", new Statement.Claims(SERVICE, here, "a1", "Ann", Statement.KEEPER, Statement.SESSION, now - 600, now - 540)));
            bad.put(FORGED, Statement.sign(stranger.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "a1", "Ann", Statement.KEEPER, Statement.SESSION)));
            List<Ask> all = everything();
            assertTrue(all.size() > 450, "the list is whole: " + all.size());
            List<String> faults = new ArrayList<>();
            int asked = 0, taken = 0;
            for (Ask a : all) {
                HttpResponse<String> none = send(c, base, a, null);
                if (none.statusCode() != 403 || !none.body().contains(NONE)) faults.add(a.method() + " " + a.path() + " with no statement: " + none.statusCode() + " " + clip(none.body()));
                for (Map.Entry<String, String> b : bad.entrySet()) {
                    HttpResponse<String> r = send(c, base, a, b.getValue());
                    if (r.statusCode() != 403 || !r.body().contains(b.getKey())) faults.add(a.method() + " " + a.path() + " with a statement that is " + b.getKey() + ": " + r.statusCode() + " " + clip(r.body()));
                }
                asked++;
                if (NOT_ASKED_WHEN_GOOD.contains(a.path()) || a.body().contains("library_help") || a.body().contains("library_research") || a.body().contains("library_crews") || a.path().equals("/logout")) continue;
                HttpResponse<String> ok = send(c, base, a, good);
                if (forTheStatement(ok)) faults.add(a.method() + " " + a.path() + " with a good statement was refused for the statement: " + clip(ok.body()));
                taken++;
            }
            assertTrue(faults.isEmpty(), faults.size() + " fault(s):\n" + String.join("\n", faults.subList(0, Math.min(20, faults.size()))));
            assertTrue(taken > 400, "the good statement was tried on " + taken + " of " + asked);
            // the one address open without a statement
            HttpResponse<String> health = c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
            assertEquals(here, M.readTree(health.body()).path("library_id").asText());
            // and a good statement really is taken: the library says which one it is
            HttpResponse<String> status = send(c, base, new Ask("GET", "/v1/status", ""), good);
            assertEquals(200, status.statusCode(), status.body());
            assertEquals(here, M.readTree(status.body()).path("library_id").asText());
        } finally { d.stop(); }
    }

    static String clip(String s) { return s.length() > 160 ? s.substring(0, 160) + "…" : s; }

    @Test
    void nothingButTheStatementSaysWhoIsAsking(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ApiKeys.Minted own = ApiKeys.mint(store, Accounts.OWNER, "a key of the library's own", Patrons.Level.write);   // made before the library went under a service
        Accounts.create(store, Accounts.OWNER, "The Keeper", "a long password 1");
        String session = Accounts.newSession(store, Accounts.OWNER, "a browser");
        Patrons.set(store, Patrons.loginDid("ann@example.org"), "Ann", Patrons.Level.write);
        KeyPair service = Statement.newKey();
        Vouching v = new Vouching(SERVICE, kid -> kid.equals("k1") ? service.getPublic() : null);
        Proxy.OVERRIDE = "127.0.0.1";   // the service is the library's trusted proxy: its forwarded address is believed, and no name it could put in a header
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1, -1, v);
        HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            Ask status = new Ask("GET", "/v1/status", ""), page = new Ask("GET", "/", "");
            for (Ask a : List.of(status, page)) {
                assertTrue(forTheStatement(send(c, base, a, null, "Authorization", "Bearer " + own.secret())), "a key of the library's own opens nothing");
                assertTrue(forTheStatement(send(c, base, a, null, "Cookie", Pages.COOKIE + "=" + session)), "a session of the library's own opens nothing");
                assertTrue(forTheStatement(send(c, base, a, null, "Tailscale-User-Login", "ann@example.org", "Tailscale-User-Name", "Ann")), "a name in a relay's header, from the trusted address, opens nothing");
                assertTrue(forTheStatement(send(c, base, a, null, "Cf-Access-Authenticated-User-Email", "ann@example.org")), "nor the other relay's");
            }
            // on the library's own computer, by its own name, a browser is not the keeper either
            WebAccess.OVERRIDE = Boolean.TRUE;
            try { assertTrue(forTheStatement(send(c, base, page, null))); } finally { WebAccess.OVERRIDE = null; }
            // a statement gives the level it names and no more: a reader reads and is refused a write, in a sentence of its own
            String here = store.identity().id();
            String reader = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "a2", "Ben", Statement.READ, Statement.SESSION));
            String writer = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "a3", "Cat", Statement.WRITE, Statement.SESSION));
            String readKey = Statement.sign(service.getPrivate(), "k1", Statement.Claims.now(SERVICE, here, "a3", "Cat", Statement.WRITE, Statement.KEY_READ));
            Ask add = new Ask("POST", "/v1/frontier", "{\"op\":\"add\",\"question\":\"What did the reader ask for at the desk on the first day?\"}");
            assertEquals(200, send(c, base, status, reader).statusCode());
            HttpResponse<String> refused = send(c, base, add, reader);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("may read this library and not write"), refused.body());
            assertEquals(200, send(c, base, add, writer).statusCode(), "a writer writes");
            HttpResponse<String> key = send(c, base, add, readKey);
            assertEquals(403, key.statusCode()); assertTrue(key.body().contains("This key reads only"), key.body());
            // the library's own sign-in pages are not served: the account service answers at those addresses
            assertEquals(404, send(c, base, new Ask("GET", "/login", ""), writer).statusCode());
            // nobody who comes through the service is the person at the machine
            JsonNode me = M.readTree(send(c, base, status, writer).body());
            assertFalse(me.toString().contains("\"did\":\"person\""), me.toString());
            assertEquals(Patrons.Level.write, Hosted.of(store, Hosted.DID + "a3").level());
            assertFalse(Hosted.keeper(store, new Patrons.Patron(Hosted.DID + "a3", "Cat", "proxy")));
        } finally { d.stop(); }
        assertFalse(Hosted.any(), "the daemon stopped: nothing of this process is a hosted library");
    }
}
