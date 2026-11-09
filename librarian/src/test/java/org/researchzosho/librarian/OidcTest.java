package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sign-in through an identity provider, against a stub provider inside the test: discovery, keys, the code for a token, the checks, the account. */
class OidcTest {
    private static final ObjectMapper M = new ObjectMapper();

    /** A provider in miniature: discovery, a key set, and a token endpoint that mints an identity token for the nonce the test tells it. */
    static final class Stub {
        final HttpServer s; final KeyPair rsa; final String issuer; volatile String nonce = "", email = "ann@example.org", name = "Ann Example", sub = "sub-ann"; volatile boolean verified = true; volatile String lastVerifier = "";
        Stub() throws Exception {
            rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + s.getAddress().getPort();
            s.createContext("/.well-known/openid-configuration", x -> json(x, "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/auth\",\"token_endpoint\":\"" + issuer + "/token\",\"jwks_uri\":\"" + issuer + "/keys\"}"));
            s.createContext("/keys", x -> {
                RSAPublicKey pk = (RSAPublicKey) rsa.getPublic();
                json(x, "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\"" + b64(pk.getModulus().toByteArray()) + "\",\"e\":\"" + b64(pk.getPublicExponent().toByteArray()) + "\"}]}");
            });
            s.createContext("/token", x -> {
                Map<String, String> f = new HashMap<>();
                for (String kv : new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) { int eq = kv.indexOf('='); if (eq > 0) f.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)); }
                lastVerifier = f.getOrDefault("code_verifier", "");
                if (!"code-1".equals(f.get("code")) || lastVerifier.isEmpty() || !"client-1".equals(f.get("client_id"))) { json(x, 400, "{\"error\":\"invalid_grant\"}"); return; }
                long now = System.currentTimeMillis() / 1000;
                json(x, "{\"id_token\":\"" + token("{\"iss\":\"" + issuer + "\",\"aud\":\"client-1\",\"sub\":\"" + sub + "\",\"exp\":" + (now + 300) + ",\"iat\":" + now + ",\"nonce\":\"" + nonce + "\",\"email\":\"" + email + "\",\"email_verified\":" + verified + ",\"name\":\"" + name + "\"}") + "\",\"token_type\":\"Bearer\"}");
            });
            s.start();
        }
        String token(String claims) {
            try {
                String head = b64("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)), body = b64(claims.getBytes(StandardCharsets.UTF_8));
                Signature sig = Signature.getInstance("SHA256withRSA"); sig.initSign(rsa.getPrivate()); sig.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
                return head + "." + body + "." + b64(sig.sign());
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
        static void json(HttpExchange x, String body) throws IOException { json(x, 200, body); }
        static void json(HttpExchange x, int code, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(code, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        }
        void stop() { s.stop(0); }
    }

    @AfterEach void reset() { Oidc.OVERRIDE = null; Oidc.forget(); WebAccess.OVERRIDE = null; }

    static Map<String, String> settings(Stub stub, String domain) {
        Map<String, String> m = new HashMap<>();
        m.put(Oidc.ISSUER, stub.issuer); m.put(Oidc.CLIENT_ID, "client-1"); m.put(Oidc.CLIENT_SECRET, "secret-1"); m.put(Oidc.ALLOWED_DOMAIN, domain); m.put(Oidc.NAME, "Example Workspace");
        return m;
    }

    static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        for (String kv : URI.create(url).getRawQuery().split("&")) { int eq = kv.indexOf('='); q.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)); }
        return q;
    }

    @Test
    void aPersonSignsInThroughTheProviderAndIsAReaderHere(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Stub stub = new Stub();
        Oidc.OVERRIDE = settings(stub, "example.org");
        WebAccess.OVERRIDE = Boolean.TRUE;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> login = c.send(HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(login.body().contains("Sign in with Example Workspace") && login.body().contains("href=\"/login/oidc\""), "the button, named by the setting");
            HttpResponse<String> go = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, go.statusCode());
            String to = go.headers().firstValue("Location").orElse("");
            assertTrue(to.startsWith(stub.issuer + "/auth?"), to);
            Map<String, String> q = query(to);
            assertEquals("code", q.get("response_type")); assertEquals("client-1", q.get("client_id")); assertEquals("S256", q.get("code_challenge_method"));
            assertEquals(base + "/login/oidc/callback", q.get("redirect_uri"));
            assertTrue(q.get("scope").contains("openid") && q.get("state").length() > 20 && q.get("nonce").length() > 20 && q.get("code_challenge").length() > 20, to);
            stub.nonce = q.get("nonce");
            HttpResponse<String> back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=" + q.get("state"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, back.statusCode(), back.body());
            assertEquals("/", back.headers().firstValue("Location").orElse(""));
            String cookie = back.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.startsWith(Pages.COOKIE + "="), cookie);
            assertFalse(stub.lastVerifier.isEmpty(), "the code verifier went to the token endpoint");
            String secret = cookie.substring(Pages.COOKIE.length() + 1, cookie.indexOf(';'));
            Accounts.Account a = Accounts.bySession(store, secret);
            assertNotNull(a); assertEquals("Ann Example", a.name()); assertEquals(Patrons.loginDid("ann@example.org"), a.did());
            assertEquals(Patrons.Level.read, Patrons.byDid(store, a.did()).level(), "a first sign-in from an allowed domain is a reader");
            assertEquals(a.did(), Oidc.boundDid(store, stub.issuer, "sub-ann"));
            HttpResponse<String> acct = c.send(HttpRequest.newBuilder(URI.create(base + "/account")).header("Cookie", Pages.COOKIE + "=" + secret).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, acct.statusCode()); assertTrue(acct.body().contains("Ann Example"));
            // the same person again, with a new mail at the provider: the binding by issuer and subject holds, no second account
            stub.email = "ann.e@example.org";
            go = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc")).GET().build(), HttpResponse.BodyHandlers.ofString());
            q = query(go.headers().firstValue("Location").orElse("")); stub.nonce = q.get("nonce");
            back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=" + q.get("state"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, back.statusCode(), back.body());
            assertEquals(1, Accounts.list(store).size());
            // a state the library never made is refused with a sentence
            back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=made-up")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, back.statusCode()); assertTrue(back.body().contains("not started here"), back.body());
        } finally { d.stop(); stub.stop(); }
    }

    @Test
    void aMailOutsideTheAllowedDomainsIsRefusedAndAListedReaderIsBoundByMail(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Stub stub = new Stub();
        Oidc.OVERRIDE = settings(stub, "example.org");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            stub.email = "bob@elsewhere.net"; stub.sub = "sub-bob"; stub.name = "Bob";
            HttpResponse<String> go = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc")).GET().build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> q = query(go.headers().firstValue("Location").orElse("")); stub.nonce = q.get("nonce");
            HttpResponse<String> back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=" + q.get("state"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, back.statusCode()); assertTrue(back.body().contains("elsewhere.net are not let in"), back.body());
            assertTrue(Accounts.list(store).isEmpty());
            // the keeper listed Bob by mail: his provider sign-in binds to that reader, whatever the domain
            Patrons.set(store, Patrons.loginDid("bob@elsewhere.net"), "Bob", Patrons.Level.write);
            go = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc")).GET().build(), HttpResponse.BodyHandlers.ofString());
            q = query(go.headers().firstValue("Location").orElse("")); stub.nonce = q.get("nonce");
            back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=" + q.get("state"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, back.statusCode(), back.body());
            assertEquals(Patrons.loginDid("bob@elsewhere.net"), Oidc.boundDid(store, stub.issuer, "sub-bob"));
            // an unverified mail binds nothing new
            stub.email = "carol@example.org"; stub.sub = "sub-carol"; stub.verified = false;
            go = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc")).GET().build(), HttpResponse.BodyHandlers.ofString());
            q = query(go.headers().firstValue("Location").orElse("")); stub.nonce = q.get("nonce");
            back = c.send(HttpRequest.newBuilder(URI.create(base + "/login/oidc/callback?code=code-1&state=" + q.get("state"))).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, back.statusCode()); assertTrue(back.body().contains("verified mail"), back.body());
        } finally { d.stop(); stub.stop(); }
    }

    @Test
    void theTokenChecksEachClaimAndTakesAnEcKeyToo() throws Exception {
        Stub stub = new Stub();
        try {
            Oidc.OVERRIDE = settings(stub, "example.org");
            Oidc.Discovery d = Oidc.discovery();
            long now = System.currentTimeMillis() / 1000;
            String good = "{\"iss\":\"" + stub.issuer + "\",\"aud\":[\"other\",\"client-1\"],\"sub\":\"s\",\"exp\":" + (now + 60) + ",\"nonce\":\"n1\"}";
            assertEquals("s", Oidc.verify(stub.token(good), d, "n1").path("sub").asText());
            assertTrue(assertThrows(Exception.class, () -> Oidc.verify(stub.token(good.replace("n1", "n2")), d, "n1")).getMessage().contains("nonce"));
            assertTrue(assertThrows(Exception.class, () -> Oidc.verify(stub.token(good.replace("client-1", "someone")), d, "n1")).getMessage().contains("another client"));
            assertTrue(assertThrows(Exception.class, () -> Oidc.verify(stub.token(good.replace("\"exp\":" + (now + 60), "\"exp\":" + (now - 600))), d, "n1")).getMessage().contains("expired"));
            assertTrue(assertThrows(Exception.class, () -> Oidc.verify(stub.token(good.replace(stub.issuer, "http://elsewhere.invalid")), d, "n1")).getMessage().contains("not from"));
            String t = stub.token(good);
            assertTrue(assertThrows(Exception.class, () -> Oidc.verify(t.substring(0, t.length() - 4) + "AAAA", d, "n1")).getMessage().contains("signature"));
            // an EC key, as some providers publish, read from its point and checked in the token's own signature format
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); KeyPair ec = g.generateKeyPair();
            ECPublicKey pk = (ECPublicKey) ec.getPublic();
            byte[] xb = pk.getW().getAffineX().toByteArray(), yb = pk.getW().getAffineY().toByteArray();
            ObjectNode jwk = M.createObjectNode(); jwk.put("kty", "EC"); jwk.put("crv", "P-256"); jwk.put("x", Stub.b64(unsigned(xb))); jwk.put("y", Stub.b64(unsigned(yb)));
            PublicKey read = Oidc.publicKey(jwk, "ES256");
            String head = Stub.b64("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)), body = Stub.b64("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format"); sig.initSign(ec.getPrivate()); sig.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
            Signature check = Signature.getInstance("SHA256withECDSAinP1363Format"); check.initVerify(read); check.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
            assertTrue(check.verify(sig.sign()));
        } finally { stub.stop(); }
    }

    static byte[] unsigned(byte[] b) { if (b.length == 33 && b[0] == 0) { byte[] o = new byte[32]; System.arraycopy(b, 1, o, 0, 32); return o; } return b; }

    @Test
    void serveSaysWhenTheSettingsAreHalfMade() {
        Map<String, String> m = new HashMap<>(); m.put(Oidc.ISSUER, "https://accounts.example.org");
        Oidc.OVERRIDE = m;
        assertTrue(Oidc.halfConfigured().contains(Oidc.CLIENT_ID));
        assertFalse(Oidc.configured());
        m.put(Oidc.CLIENT_ID, "c"); assertTrue(Oidc.configured()); assertEquals(null, Oidc.halfConfigured());
        assertEquals("accounts.example.org", Oidc.name());
    }
}
