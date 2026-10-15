package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signing in with an account (0.6.1): the owner on the library's own computer needs no sign-in and makes their account on the account
 * page; from anywhere else, name and password; the account page's changes; a passkey made and used by an authenticator the test plays;
 * the one-time code as a second step; too many wrong tries; the terminal's account and reader commands; share asking for the owner's
 * account first.
 */
class SignInTest {

    static final ObjectMapper M = new ObjectMapper();
    Boolean signIn, local, keyboard; BufferedReader input;

    @BeforeEach void on() { signIn = WebAccess.OVERRIDE; local = WebAccess.LOCAL_OVERRIDE; keyboard = Interaction.OVERRIDE; input = Interaction.INPUT; WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false; }
    @AfterEach void off() { WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; Interaction.OVERRIDE = keyboard; Interaction.INPUT = input; }

    static HttpClient client() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(); }
    static HttpResponse<String> get(HttpClient c, String url, String cookie) throws Exception { return LetPeopleInTest.get(c, url, cookie); }
    static HttpResponse<String> post(HttpClient c, String url, String form, String cookie) throws Exception { return LetPeopleInTest.post(c, url, form, cookie); }
    static HttpResponse<String> postJson(HttpClient c, String url, String json, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static String cookieOf(HttpResponse<String> r) { String s = LetPeopleInTest.setCookie(r, Pages.COOKIE); return s == null ? null : Pages.COOKIE + "=" + LetPeopleInTest.value(s); }

    static LibraryStore lib(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.setDefault(store, Patrons.Level.deny);
        return store;
    }

    @Test
    void theOwnerOnTheLibrarysOwnComputerNeedsNoSignInAndMakesTheirAccountThere(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // from elsewhere (the setting says sign in), a visitor
            assertTrue(get(c, base + "/", null).body().contains("<a href=\"/login\">sign in</a>"));
            assertEquals(403, get(c, base + "/account", null).statusCode());
            // on the library's own computer, the owner, with the settings and the account page, and no cookie
            WebAccess.LOCAL_OVERRIDE = true;
            var home = get(c, base + "/", null);
            assertTrue(home.body().contains("you · <a href=\"/account\">account</a>") && home.body().contains("href=\"/settings\""), home.body().substring(0, 1500));
            assertNull(cookieOf(home));
            var account = get(c, base + "/account", null);
            assertTrue(account.body().contains("Make my account") && account.body().contains("researchzosho account set"), account.body());
            assertTrue(post(c, base + "/account", "op=make&name=Owner+Example&password=owner-secret-1&again=owner-secret-2", null).headers().firstValue("Location").orElse("").contains("differ"));
            var made = post(c, base + "/account", "op=make&name=Owner+Example&password=owner-secret-1&again=owner-secret-1", null);
            assertEquals(303, made.statusCode());
            assertTrue(made.headers().firstValue("Location").orElse("").contains("Your%20account%20is%20made"));
            Accounts.Account o = Accounts.owner(store);
            assertEquals("Owner Example", o.name()); assertTrue(o.owner());
            var page = get(c, base + "/account", null);
            assertTrue(page.body().contains("You sign in as <b>Owner Example</b>, with password. You keep this library.") && page.body().contains("Add a passkey") && page.body().contains("Set up the one-time code"), page.body());
            assertFalse(page.body().contains("Current password"), "on the library's own computer no password is asked for a change");
            // through the shared door, never the owner without signing in
            d.openDoor(0);
            String door = "http://127.0.0.1:" + d.doorPort();
            assertEquals(403, get(c, door + "/account", null).statusCode());
            assertTrue(get(c, door + "/", null).body().contains("Sign in with your name and password"));
            // from elsewhere, the name and password: the owner, a session in the cookie, and the owner's pages
            WebAccess.LOCAL_OVERRIDE = false;
            var signed = post(c, base + "/login", "name=owner+example&password=owner-secret-1", null);
            assertEquals(303, signed.statusCode(), signed.body());
            String cookie = cookieOf(signed);
            assertNotNull(cookie);
            var mine = get(c, base + "/", cookie);
            assertTrue(mine.body().contains("Owner Example · <a href=\"/account\">account</a>") && mine.body().contains("href=\"/settings\""), mine.body().substring(0, 1500));
            assertTrue(get(c, base + "/settings", cookie).body().contains("<h2>Readers</h2>"));
            assertEquals(1, Accounts.owner(store).sessions().size());
            assertTrue(get(c, base + "/account", cookie).body().contains("Current password"), "from elsewhere a change asks for the current password");
            // a change of password: the wrong current one refused, the right one taken; the name changed too
            assertTrue(post(c, base + "/account", "op=password&name=Owner+Example&current=nope&password=owner-secret-2&again=owner-secret-2", cookie).headers().firstValue("Location").orElse("").contains("not%20right"));
            assertEquals(303, post(c, base + "/account", "op=password&name=The+Owner&current=owner-secret-1&password=owner-secret-2&again=owner-secret-2", cookie).statusCode());
            assertNotNull(Accounts.signIn(store, "The Owner", "owner-secret-2"));
            assertNull(Accounts.signIn(store, "Owner Example", "owner-secret-2"));
            // sign out everywhere: the cookie is dead
            assertEquals(303, post(c, base + "/account", "op=signout-all", cookie).statusCode());
            assertTrue(get(c, base + "/", cookie).body().contains("<a href=\"/login\">sign in</a>"), "the session is gone, the browser is a visitor again");
            assertEquals(0, Accounts.owner(store).sessions().size());
            // too many wrong tries: the page says to wait
            for (int i = 0; i < Accounts.TRIES; i++) assertEquals(403, post(c, base + "/login", "name=The+Owner&password=wrong-" + i, null).statusCode());
            var waited = post(c, base + "/login", "name=The+Owner&password=owner-secret-2", null);
            assertEquals(429, waited.statusCode());
            assertTrue(waited.body().contains("Too many tries"), waited.body());
            Accounts.cleared("pw:the owner");
        } finally { d.stop(); }
    }

    @Test
    void aPasskeyIsMadeOnTheAccountPageAndSignsInWithNothingToType(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Accounts.create(store, Accounts.OWNER, "Owner Example", "owner-secret-1");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port(), origin = base;
        try {
            String cookie = cookieOf(post(c, base + "/login", "name=Owner+Example&password=owner-secret-1", null));
            assertEquals(403, postJson(c, base + "/account/passkey/options", "", null).statusCode(), "a visitor makes no passkey");
            // the options the page's script hands the browser
            JsonNode opt = M.readTree(postJson(c, base + "/account/passkey/options", "", cookie).body());
            assertEquals("127.0.0.1", opt.path("rp").path("id").asText()); assertEquals("owner example", opt.path("user").path("name").asText());
            assertEquals("none", opt.path("attestation").asText()); assertEquals(-7, opt.path("pubKeyCredParams").get(0).path("alg").asInt());
            // the authenticator the test plays: a P-256 key, a credential id, the attestation object as the browser would send it
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            byte[] credId = "credential-one".getBytes(StandardCharsets.UTF_8);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(credId);
            byte[] authData = authData("127.0.0.1", (byte) 0x41, 0, credId, (ECPublicKey) kp.getPublic());
            String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\"" + opt.path("challenge").asText() + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}";
            String made = "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"label\":\"Mozilla/5.0 (X11; Linux x86_64) Firefox/131.0\",\"response\":{\"clientDataJSON\":\"" + b64(clientData.getBytes(StandardCharsets.UTF_8)) + "\",\"attestationObject\":\"" + b64(cbor(Map.of("fmt", "none", "attStmt", Map.of(), "authData", authData))) + "\"}}";
            var kept = postJson(c, base + "/account/passkey", made, cookie);
            assertEquals(200, kept.statusCode(), kept.body());
            Accounts.Passkey k = Accounts.owner(store).passkeys().get(0);
            assertEquals(id, k.id()); assertEquals(-7, k.alg()); assertEquals("127.0.0.1", k.rp()); assertEquals("Firefox on Linux", k.label());
            assertTrue(get(c, base + "/account", cookie).body().contains("Firefox on Linux") && Accounts.owner(store).ways().equals("password, a passkey"));
            // the same challenge again is refused: it was used
            assertEquals(400, postJson(c, base + "/account/passkey", made, cookie).statusCode());
            // signing in: the options, the assertion signed by the authenticator, the session
            JsonNode so = M.readTree(postJson(c, base + "/login/passkey/options", "", null).body());
            assertEquals("127.0.0.1", so.path("rpId").asText());
            byte[] aData = authData("127.0.0.1", (byte) 0x01, 7, null, null);
            String cd = "{\"type\":\"webauthn.get\",\"challenge\":\"" + so.path("challenge").asText() + "\",\"origin\":\"" + origin + "\"}";
            String assertion = assertion(id, aData, cd, kp);
            var in = postJson(c, base + "/login/passkey", assertion, null);
            assertEquals(200, in.statusCode(), in.body());
            String pk = cookieOf(in);
            assertNotNull(pk, "signed in by the passkey");
            assertTrue(get(c, base + "/", pk).body().contains("Owner Example · <a href=\"/account\">account</a>"));
            assertEquals(7, Accounts.owner(store).passkeys().get(0).count());
            // a counter that goes backwards (a copied key), a wrong origin, an unknown passkey, a signature by another key: all refused
            so = M.readTree(postJson(c, base + "/login/passkey/options", "", null).body());
            assertEquals(403, postJson(c, base + "/login/passkey", assertion(id, authData("127.0.0.1", (byte) 0x01, 5, null, null), "{\"type\":\"webauthn.get\",\"challenge\":\"" + so.path("challenge").asText() + "\",\"origin\":\"" + origin + "\"}", kp), null).statusCode());
            so = M.readTree(postJson(c, base + "/login/passkey/options", "", null).body());
            var wrongOrigin = postJson(c, base + "/login/passkey", assertion(id, authData("127.0.0.1", (byte) 0x01, 9, null, null), "{\"type\":\"webauthn.get\",\"challenge\":\"" + so.path("challenge").asText() + "\",\"origin\":\"https://evil.example\"}", kp), null);
            assertEquals(403, wrongOrigin.statusCode()); assertTrue(wrongOrigin.body().contains("evil.example"), wrongOrigin.body());
            so = M.readTree(postJson(c, base + "/login/passkey/options", "", null).body());
            KeyPair other = g.generateKeyPair();
            assertEquals(403, postJson(c, base + "/login/passkey", assertion(id, authData("127.0.0.1", (byte) 0x01, 9, null, null), "{\"type\":\"webauthn.get\",\"challenge\":\"" + so.path("challenge").asText() + "\",\"origin\":\"" + origin + "\"}", other), null).statusCode());
            so = M.readTree(postJson(c, base + "/login/passkey/options", "", null).body());
            assertTrue(postJson(c, base + "/login/passkey", assertion("bm90LWhlcmU", authData("127.0.0.1", (byte) 0x01, 9, null, null), "{\"type\":\"webauthn.get\",\"challenge\":\"" + so.path("challenge").asText() + "\",\"origin\":\"" + origin + "\"}", kp), null).body().contains("not known here"));
            // removed from the account page: it signs in no more
            assertEquals(303, post(c, base + "/account", "op=passkey-remove&id=" + id, cookie).statusCode());
            assertTrue(Accounts.owner(store).passkeys().isEmpty());
        } finally { d.stop(); }
    }

    @Test
    void theOneTimeCodeIsASecondStepAfterThePassword(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Patrons.set(store, "did:key:ann", "Ann Example", Patrons.Level.read);
        Accounts.create(store, "did:key:ann", "Ann Example", "ann-secret-1");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = client();
        String base = "http://127.0.0.1:" + d.port();
        try {
            String cookie = cookieOf(post(c, base + "/login", "name=Ann+Example&password=ann-secret-1", null));
            var setup = get(c, base + "/account?code=1", cookie);
            Matcher m = Pattern.compile("name=\"secret\" value=\"([A-Z2-7]+)\"").matcher(setup.body());
            assertTrue(m.find() && setup.body().contains("<svg xmlns") && setup.body().contains("otpauth") == false, "the secret as letters and a QR code; the address itself only inside the code: " + setup.body().substring(0, 300));
            String secret = m.group(1);
            assertTrue(setup.body().contains("<code>" + secret + "</code>"));
            assertTrue(post(c, base + "/account", "op=code-on&secret=" + secret + "&code=000000", cookie).headers().firstValue("Location").orElse("").contains("not%20the%20one"), "a wrong first code turns nothing on");
            String code = Totp.code(secret, System.currentTimeMillis() / 1000 / Totp.PERIOD);
            assertEquals(303, post(c, base + "/account", "op=code-on&secret=" + secret + "&code=" + code, cookie).statusCode());
            assertTrue(Accounts.byDid(store, "did:key:ann").secondStep());
            // signing in: the password alone is half a sign-in; the code finishes it
            var half = post(c, base + "/login", "name=Ann+Example&password=ann-secret-1", null);
            assertEquals(200, half.statusCode());
            assertNull(cookieOf(half), "no session yet");
            String halfCookie = LetPeopleInTest.setCookie(half, SignIn.HALF);
            assertNotNull(halfCookie); assertTrue(half.body().contains("One more step"));
            String hc = SignIn.HALF + "=" + LetPeopleInTest.value(halfCookie);
            assertEquals(403, post(c, base + "/login/code", "code=" + code, hc).statusCode(), "the code used to turn the step on is spent");
            long step = System.currentTimeMillis() / 1000 / Totp.PERIOD + 1;   // the next code, a step ahead, which the window allows
            var done = post(c, base + "/login/code", "code=" + Totp.code(secret, step), hc);
            assertEquals(303, done.statusCode(), done.body());
            String signed = cookieOf(done);
            assertNotNull(signed);
            assertTrue(get(c, base + "/", signed).body().contains("Ann Example · <a href=\"/account\">account</a>"));
            assertEquals(403, post(c, base + "/login/code", "code=" + Totp.code(secret, step), hc).statusCode(), "the half sign-in is spent");
            assertEquals(303, post(c, base + "/account", "op=code-off", signed).statusCode());
            assertFalse(Accounts.byDid(store, "did:key:ann").secondStep());
            assertEquals(303, post(c, base + "/login", "name=Ann+Example&password=ann-secret-1", null).statusCode(), "without the step, the password signs in at once");
        } finally { d.stop(); }
    }

    @Test
    void theTerminalMakesTheOwnersAccountAndGivesAReaderALinkOrAPassword(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Interaction.OVERRIDE = false;
        assertTrue(Accounts.setupOwner(store, List.of()).startsWith("No account for the person who keeps the library yet. Make it with: researchzosho account set"));
        assertNull(Accounts.owner(store));
        Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("Owner Example\nshort\nowner-secret-1\nowner-secret-2\nowner-secret-1\nowner-secret-1\n"));
        PrintStream o = System.out; ByteArrayOutputStream bo = new ByteArrayOutputStream(); System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
        String said;
        try { said = Accounts.setupOwner(store, List.of()); } finally { System.setOut(o); }
        assertEquals("Your account is made: sign in as \"Owner Example\" with that password, from any phone or computer that can reach the library.", said);
        String out = bo.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("at least 8 characters") && out.contains("The two differ"), out);
        assertNotNull(Accounts.signIn(store, "Owner Example", "owner-secret-1"));
        assertTrue(Accounts.setupOwner(store, List.of("--name", "The Owner", "--password", "owner-secret-9")).startsWith("Your account is changed"));
        assertNotNull(Accounts.signIn(store, "the owner", "owner-secret-9"));
        // a reader: invited, then a new link by name, then a password typed by the owner
        Patrons.set(store, "did:key:ann", "Ann Example", Patrons.Level.read);
        PrintStream e = System.err; ByteArrayOutputStream be = new ByteArrayOutputStream(); bo.reset();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        int rc1, rc2, rc3, rc4;
        try {
            rc1 = LibrarianCli.patron(store, new String[]{"librarian", "reader", "link", "ann example"});
            rc2 = LibrarianCli.patron(store, new String[]{"librarian", "reader", "password", "Ann Example", "ann-secret-5"});
            rc3 = LibrarianCli.patron(store, new String[]{"librarian", "reader", "link", "Nobody Here"});
            rc4 = LibrarianCli.patron(store, new String[]{"librarian", "reader", "list"});
        } finally { System.setOut(o); System.setErr(e); }
        out = bo.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc4);
        assertTrue(out.contains("you: \"The Owner\" — signs in with password") && out.contains("- did:key:ann — Ann Example — read — signs in with password"), "the list says how each signs in: " + out);
        assertEquals(0, rc1, be.toString(StandardCharsets.UTF_8));
        assertTrue(out.contains("Ann Example's old password, passkeys and sign-ins are gone. Send them this link; it works once, for 7 days") && out.contains("/join?code=R-0001."), out);
        assertEquals(0, rc2); assertTrue(out.contains("Ann Example signs in with that password now"), out);
        assertNotNull(Accounts.signIn(store, "Ann Example", "ann-secret-5"));
        assertEquals(2, rc3); assertTrue(be.toString(StandardCharsets.UTF_8).contains("Nobody Here is not on the readers list"));
        // an invite for a name in use says so and names the link command
        IOException dup = assertThrows(IOException.class, () -> Invites.make(store, "ann example", Patrons.Level.read));
        assertTrue(dup.getMessage().contains("researchzosho reader link \"ann example\""), dup.getMessage());
    }

    @Test
    void shareAsksForTheOwnersAccountBeforeTheFirstOpen(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        String realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); org.researchzosho.Config.invalidate();
        PrintStream o = System.out; ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            Interaction.OVERRIDE = false;
            String hint = Share.ownerStep(store, new PrintStream(bo, true, StandardCharsets.UTF_8));
            assertTrue(hint.startsWith("No account for the person who keeps the library yet."), "from a script, the line for the end of the outcome: " + hint);
            Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("Owner Example\nowner-secret-1\nowner-secret-1\n"));
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            assertEquals("", Share.ownerStep(store, System.out));
            String out = bo.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("First, your own account") && out.contains("Your account is made"), out);
            assertNotNull(Accounts.signIn(store, "Owner Example", "owner-secret-1"));
            assertEquals("", Share.ownerStep(store, System.out), "made once");
        } finally { System.setOut(o); System.setProperty("user.home", realHome); org.researchzosho.Config.invalidate(); }
    }

    // ---- the authenticator the tests play ----

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    /** Authenticator data: rpIdHash, flags, counter, and with a credential the attested data (aaguid, id, COSE key). */
    static byte[] authData(String rpId, byte flags, int counter, byte[] credId, ECPublicKey pub) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)));
        out.write(flags);
        out.write(new byte[]{(byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter});
        if (credId != null) {
            out.write(new byte[16]);
            out.write(new byte[]{(byte) (credId.length >>> 8), (byte) credId.length});
            out.write(credId);
            Map<Object, Object> cose = new LinkedHashMap<>();
            cose.put(1L, 2L); cose.put(3L, -7L); cose.put(-1L, 1L); cose.put(-2L, fixed(pub.getW().getAffineX().toByteArray())); cose.put(-3L, fixed(pub.getW().getAffineY().toByteArray()));
            out.write(cbor(cose));
        }
        return out.toByteArray();
    }

    static byte[] fixed(byte[] b) { byte[] o = new byte[32]; int n = Math.min(32, b.length); System.arraycopy(b, b.length - n, o, 32 - n, n); return o; }

    /** The assertion JSON the page's script would post, signed over authenticatorData || sha256(clientDataJSON). */
    static String assertion(String id, byte[] authData, String clientData, KeyPair kp) throws Exception {
        byte[] cd = clientData.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(kp.getPrivate());
        s.update(authData); s.update(MessageDigest.getInstance("SHA-256").digest(cd));
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"response\":{\"clientDataJSON\":\"" + b64(cd) + "\",\"authenticatorData\":\"" + b64(authData) + "\",\"signature\":\"" + b64(s.sign()) + "\",\"userHandle\":null}}";
    }

    /** Just enough CBOR writing for the test's authenticator: integers, bytes, text, maps. */
    static byte[] cbor(Object v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, v);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object v) {
        if (v instanceof Long l) { if (l >= 0) head(out, 0, l); else head(out, 1, -1 - l); }
        else if (v instanceof Integer i) write(out, (long) i);
        else if (v instanceof byte[] b) { head(out, 2, b.length); out.writeBytes(b); }
        else if (v instanceof String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); head(out, 3, b.length); out.writeBytes(b); }
        else if (v instanceof Map<?, ?> m) { head(out, 5, m.size()); for (var e : m.entrySet()) { write(out, e.getKey()); write(out, e.getValue()); } }
        else throw new IllegalArgumentException(String.valueOf(v));
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
        if (n < 24) out.write((major << 5) | (int) n);
        else if (n < 256) { out.write((major << 5) | 24); out.write((int) n); }
        else if (n < 65536) { out.write((major << 5) | 25); out.write((int) (n >>> 8)); out.write((int) n); }
        else { out.write((major << 5) | 26); out.write((int) (n >>> 24)); out.write((int) (n >>> 16)); out.write((int) (n >>> 8)); out.write((int) n); }
    }
}
