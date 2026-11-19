package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in to the account service and the account page (0.8.0 review, round C): what the Keycloak test found (a refusal said by its
 * cause, a refused sign-in back on the sign-in page, the provider's sentences in the person's language, the address of an account made
 * through the provider, signing out at the provider, the accounts list); proving it is you again before a key, a key of your own or a
 * deletion (SEC 7); a sign-in finished only in the browser that began it (SEC 4); the small ones (SEC 12: a use's end needs the key the
 * service gave with it, a join link visited binds nothing, a note shows only when the service wrote it, signing out is a POST); an
 * address shown only for one's own acts (SEC 10); a password lock by name and address (SEC 11); and an archive taken in bringing none of
 * what an export leaves out (SEC 9).
 */
class AccountSignInTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    AccountStore store; AccountService svc; Path root; OidcTest.Stub stub;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        if (stub != null) stub.stop();
        AccountService.SIGNUP_OVERRIDE = null;
        Oidc.OVERRIDE = null; Oidc.forget();
        AccountPages.PROVEN_MS = 5 * 60_000L;
        Chrome.clear();
        for (String k : new String[]{AccountService.ROOT, Proxy.SETTING}) Config.set(k, "");
    }

    void start(Path tmp, boolean provider) throws Exception {
        root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        if (provider) { stub = new OidcTest.Stub(); Oidc.OVERRIDE = OidcTest.settings(stub, "example.org"); }
    }

    /** A browser: its cookies, kept as the service sets them. */
    final class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        String forwarded;
        HttpResponse<String> get(String path) throws Exception { return keep(c.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString())); }
        HttpResponse<String> post(String path, String body) throws Exception {
            return keep(c.send(req(path).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()));
        }
        HttpRequest.Builder req(String path) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : svc.url() + path)).header("Sec-Fetch-Site", "same-origin");
            if (!cookies.isEmpty()) b.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
            if (forwarded != null) b.header("X-Forwarded-For", forwarded);
            return b;
        }
        HttpResponse<String> keep(HttpResponse<String> r) {
            for (String v : r.headers().allValues("Set-Cookie")) {
                String kv = v.substring(0, v.indexOf(';') < 0 ? v.length() : v.indexOf(';'));
                int eq = kv.indexOf('=');
                String name = kv.substring(0, eq), value = kv.substring(eq + 1);
                if (value.isEmpty() || v.contains("Max-Age=0")) cookies.remove(name); else cookies.put(name, value);
            }
            return r;
        }
        String session() { return cookies.get(AccountService.COOKIE); }
        /** Through the provider: the sign-in begun, the provider's answer brought back; the callback's answer. */
        HttpResponse<String> signInThroughProvider(String extra) throws Exception {
            HttpResponse<String> go = get("/login/oidc" + extra);
            assertEquals(303, go.statusCode(), go.body());
            Map<String, String> q = OidcTest.query(go.headers().firstValue("Location").orElse(""));
            stub.nonce = q.get("nonce");
            return get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String location(HttpResponse<String> r) { return r.headers().firstValue("Location").orElse(""); }
    /** Where a page that goes on by itself sends the browser (its meta refresh), unescaped. */
    static String onwards(HttpResponse<String> r) {
        Matcher m = Pattern.compile("http-equiv=\"refresh\" content=\"0;url=([^\"]+)\"").matcher(r.body());
        return m.find() ? m.group(1).replace("&amp;", "&") : "";
    }

    // ---- the Keycloak test's six ----

    @Test
    void aRefusalSaysItsOwnCause(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.SIGNUP_OVERRIDE = "anyone";
        IOException unverified = assertThrows(IOException.class, () -> AccountPages.fromProvider(svc, new Oidc.Proven(stub.issuer, ProviderBindingTest.claims("s-1", "carol@example.org", false)), null, "addr|203.0.113.9"));
        assertFalse(unverified.getMessage().contains("makes one for you"), "not the sentence for a service where accounts are made by hand: " + unverified.getMessage());
        assertTrue(unverified.getMessage().contains("did not vouch for your mail address"), unverified.getMessage());
        AccountService.SIGNUP_OVERRIDE = "domain";
        IOException elsewhere = assertThrows(IOException.class, () -> AccountPages.fromProvider(svc, new Oidc.Proven(stub.issuer, ProviderBindingTest.claims("s-2", "fay@elsewhere.net", true)), null, "addr|203.0.113.9"));
        assertTrue(elsewhere.getMessage().contains("elsewhere.net") && !elsewhere.getMessage().contains("makes one for you"), elsewhere.getMessage());
        AccountService.SIGNUP_OVERRIDE = "invited";
        IOException invited = assertThrows(IOException.class, () -> AccountPages.fromProvider(svc, new Oidc.Proven(stub.issuer, ProviderBindingTest.claims("s-3", "dee@example.org", true)), null, "addr|203.0.113.9"));
        assertTrue(invited.getMessage().contains("makes one for you"), "a service where accounts are made by hand says so: " + invited.getMessage());
    }

    @Test
    void theProvidersSentencesAreSaidInThePersonsLanguage(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        Chrome.here("fr");
        Chrome.MISSING.clear();
        IOException e = assertThrows(IOException.class, () -> Oidc.proven(Map.of("state", "made-up", "code", "code-1")));
        assertTrue(e.getMessage().startsWith("Cette connexion n'a pas été commencée ici"), "said in the person's language, from the language file: " + e.getMessage());
        assertTrue(Chrome.MISSING.isEmpty(), "nothing the sign-in said is missing from the language file: " + Chrome.MISSING);
    }

    @Test
    void aRefusedSignInGoesBackToTheSignInPageAndOnlyNotesTheServiceWroteAreShown(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        Browser b = new Browser();
        HttpResponse<String> go = b.get("/login/oidc");
        String state = OidcTest.query(location(go)).get("state");
        HttpResponse<String> back = b.get("/login/oidc/callback?error=access_denied&state=" + state);
        assertEquals(303, back.statusCode(), "not a page at the callback's address, where a reload says it was not started here: " + back.body());
        assertTrue(location(back).startsWith("/login?"), location(back));
        HttpResponse<String> page = b.get(location(back));
        assertTrue(page.body().contains("did not sign you in"), page.body());
        // a note in an address someone else wrote is not shown
        HttpResponse<String> forged = b.get("/login?note=" + enc("Your account is closed. Pay at evil.example to open it."));
        assertFalse(forged.body().contains("evil.example"), "a note the service did not write");
    }

    @Test
    void anAccountMadeThroughTheProviderIsWrittenDownWithItsAddressAndTheListSaysHowItSignsIn(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.SIGNUP_OVERRIDE = "anyone";
        Browser b = new Browser();
        HttpResponse<String> back = b.signInThroughProvider("");
        assertEquals(303, back.statusCode(), back.body());
        AccountStore.Account a = store.accountByVerifiedMail("ann@example.org");
        assertNotNull(a);
        AccountStore.Audit made = store.audits(a.id(), "account_made", 0, Long.MAX_VALUE, 5).get(0);
        assertEquals("127.0.0.1", made.address(), "where the account was made from");
        AccountsCliTest.Ran r = AccountsCliTest.run();
        assertTrue(r.out().contains("(signs in through Example Workspace)") && !r.out().contains("has not chosen a password yet"), r.out());
    }

    @Test
    void signingOutOfASessionFromTheProviderSignsOutThereTooOrSaysItMayNot(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.SIGNUP_OVERRIDE = "anyone";
        stub.endSession = true;
        Browser b = new Browser();
        assertEquals(303, b.signInThroughProvider("").statusCode());
        String session = b.session();
        HttpResponse<String> asked = b.get("/logout");
        assertEquals(200, asked.statusCode(), "a GET asks, with a button");
        assertTrue(asked.body().contains("method=\"post\" action=\"/logout\""), asked.body());
        assertNotNull(store.session(session), "a GET signs nobody out");
        HttpResponse<String> out = b.post("/logout", "");
        assertEquals(200, out.statusCode(), "a page that goes on by itself: a form's answer may not redirect to another site (form-action 'self')");
        String onwards = onwards(out);
        assertTrue(onwards.startsWith(stub.issuer + "/logout?"), onwards);
        Map<String, String> q = OidcTest.query(onwards);
        assertEquals("client-1", q.get("client_id"));
        assertEquals(svc.address() + "/login", q.get("post_logout_redirect_uri"));
        assertTrue(q.getOrDefault("id_token_hint", "").split("\\.").length == 3, "the identity token of this sign-in");
        assertNull(store.session(session));
        // a provider that names no place to sign out (Google): the page says so
        stub.endSession = false; Oidc.forget();
        b = new Browser();
        assertEquals(303, b.signInThroughProvider("").statusCode());
        out = b.post("/logout", "");
        assertTrue(location(out).startsWith("/login?"), location(out));
        assertTrue(b.get(location(out)).body().contains("You may still be signed in to Example Workspace on this computer."));
    }

    // ---- SEC 7: proving it is you again ----

    @Test
    void aKeyAKeyOfYourOwnAndADeletionAskForThePasswordAgain(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        store.setPassword(ann.account().id(), Passwords.hash("ann's long password"));
        Browser b = new Browser();
        assertEquals(303, b.post("/login", "name=" + enc("Ann Example") + "&password=" + enc("ann's long password")).statusCode());
        AccountPages.PROVEN_MS = 0;   // the sign-in was longer ago than this
        String lib = ann.library().id();
        for (String form : List.of("op=key-new&library=" + lib + "&level=read&label=script", "op=own-key&host=api.openai.com&secret=a-key-of-her-own",
                "op=library-let-go&library=" + lib + "&confirm=yes", "op=delete-account&confirm=" + enc("Ann Example"))) {
            HttpResponse<String> r = b.post("/account", form);
            assertEquals(200, r.statusCode(), form);
            assertTrue(r.body().contains("name=\"prove_password\""), "asks for the password first: " + form);
        }
        assertTrue(store.keys(ann.account().id()).isEmpty() && ann.library().active() && store.account(ann.account().id()).active(), "nothing done");
        assertTrue(store.library(lib).active());
        HttpResponse<String> wrong = b.post("/account", "op=key-new&library=" + lib + "&level=read&label=script&prove_password=" + enc("not it"));
        assertTrue(wrong.body().contains("name=\"prove_password\"") && store.keys(ann.account().id()).isEmpty(), "a wrong password does nothing");
        HttpResponse<String> right = b.post("/account", "op=key-new&library=" + lib + "&level=read&label=script&prove_password=" + enc("ann's long password"));
        assertTrue(right.statusCode() == 200 || right.statusCode() == 303, right.body());
        assertEquals(1, store.keys(ann.account().id()).size(), "done once proved");
    }

    @Test
    void theShowItIsYouPageNeverCarriesAKeyBackAndAsksForItAgain(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        Config.set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());   // the key that seals, in this test's folder
        try { provedOnceWithTheKeyAskedAgain(); } finally { Config.set(OwnKeys.FILE, ""); OwnKeys.forget(); }
    }

    private void provedOnceWithTheKeyAskedAgain() throws Exception {
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        store.setPassword(ann.account().id(), Passwords.hash("ann's long password"));
        Browser b = new Browser();
        assertEquals(303, b.post("/login", "name=" + enc("Ann Example") + "&password=" + enc("ann's long password")).statusCode());
        AccountPages.PROVEN_MS = 0;
        String key = "her-own-model-key-q7Zt3", end = key.substring(key.length() - 8);
        // the own-key form, posted: the page that asks for the password holds no part of the key, and asks for the key again
        HttpResponse<String> asked = b.post("/account", "op=own-key&host=brave&secret=" + enc(key));
        assertEquals(200, asked.statusCode());
        assertTrue(asked.body().contains("name=\"prove_password\""), asked.body());
        assertFalse(asked.body().contains(key) || asked.body().contains(end), "the key is not sent back to the browser: " + asked.body());
        assertTrue(asked.body().contains("<input type=\"password\" name=\"secret\""), "it is asked for again, as a password is: " + asked.body());
        assertTrue(asked.body().contains("Your key, again"), asked.body());
        // a wrong password: the page again, still without it
        HttpResponse<String> wrong = b.post("/account", "op=own-key&host=brave&secret=" + enc(key) + "&prove_password=" + enc("not it"));
        assertTrue(wrong.body().contains("That is not your password.") && !wrong.body().contains(key) && !wrong.body().contains(end), wrong.body());
        assertNull(store.ownKey(ann.account().id(), "brave"), "nothing kept yet");
        // both typed: done in one step
        HttpResponse<String> done = b.post("/account", "op=own-key&host=brave&secret=" + enc(key) + "&prove_password=" + enc("ann's long password"));
        assertTrue(done.statusCode() == 200 || done.statusCode() == 303, done.body());
        assertNotNull(store.ownKey(ann.account().id(), "brave"), "kept once proved");
        // a new key, and the box for it left empty on the proof page: asked again, and the kept key is not taken away
        String sealed = store.ownKey(ann.account().id(), "brave").sealed();
        HttpResponse<String> next = b.post("/account", "op=own-key&host=brave&secret=" + enc("her-next-model-key-w2Kx9"));
        assertTrue(next.body().contains("name=\"" + AccountPages.ASKED_AGAIN + "\" value=\"secret\"") && !next.body().contains("w2Kx9"), next.body());
        HttpResponse<String> empty = b.post("/account", "op=own-key&host=brave&secret=&" + AccountPages.ASKED_AGAIN + "=secret&prove_password=" + enc("ann's long password"));
        assertTrue(empty.body().contains("Type your key again as well.") && empty.body().contains("<input type=\"password\" name=\"secret\""), empty.body());
        assertNotNull(store.ownKey(ann.account().id(), "brave"), "not taken away");
        assertEquals(sealed, store.ownKey(ann.account().id(), "brave").sealed(), "nor changed");
    }

    @Test
    void someoneWithoutAPasswordProvesItBySigningInAgainAtTheProvider(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.SIGNUP_OVERRIDE = "anyone";
        Browser b = new Browser();
        assertEquals(303, b.signInThroughProvider("").statusCode());
        AccountStore.Account a = store.accountByVerifiedMail("ann@example.org");
        String lib = store.librariesKeptBy(a.id()).get(0).id();
        AccountPages.PROVEN_MS = 0;
        HttpResponse<String> asked = b.post("/account", "op=key-new&library=" + lib + "&level=read&label=script");
        assertTrue(asked.body().contains("/login/oidc?again=1"), "the way to prove it: " + asked.body());
        AccountPages.PROVEN_MS = 5 * 60_000L;
        HttpResponse<String> go = b.get("/login/oidc?again=1");
        Map<String, String> q = OidcTest.query(location(go));
        assertEquals("0", q.get("max_age")); assertEquals("login", q.get("prompt"));
        stub.nonce = q.get("nonce"); stub.authTime = System.currentTimeMillis() / 1000 - 3600;   // the provider did not ask again
        HttpResponse<String> back = b.get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        assertTrue(b.get(location(back)).body().contains("did not ask you to sign in again"), location(back));
        store.sessionProvenAt(b.session(), 0);
        assertTrue(b.post("/account", "op=key-new&library=" + lib + "&level=read&label=script").body().contains("/login/oidc?again=1"), "still not proved");
        go = b.get("/login/oidc?again=1");
        q = OidcTest.query(location(go));
        stub.nonce = q.get("nonce"); stub.authTime = System.currentTimeMillis() / 1000;
        back = b.get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        assertTrue(location(back).startsWith("/account"), location(back));
        b.post("/account", "op=key-new&library=" + lib + "&level=read&label=script");
        assertEquals(1, store.keys(a.id()).size(), "proved at the provider, the key is made");
    }

    // ---- SEC 4: the sign-in finished in the browser that began it ----

    @Test
    void aSignInBegunInOneBrowserIsNotFinishedInAnother(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.SIGNUP_OVERRIDE = "anyone";
        Browser mine = new Browser(), theirs = new Browser();
        HttpResponse<String> go = mine.get("/login/oidc");
        Map<String, String> q = OidcTest.query(location(go));
        stub.nonce = q.get("nonce");
        HttpResponse<String> lured = theirs.get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        assertNull(theirs.session(), "the other browser is signed in to nobody");
        assertFalse(lured.statusCode() == 303 && location(lured).equals("/"), location(lured));
        HttpResponse<String> own = mine.get("/login/oidc/callback?code=code-1&state=" + q.get("state"));
        assertEquals("/", location(own), "the browser that began it finishes it");
        assertNotNull(mine.session());
    }

    // ---- SEC 12 ----

    @Test
    void aUsesEndAndStateNeedTheKeyTheServiceGaveWithIt(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Use u = Uses.start(store, svc.spending(), ann.account().id(), ann.library().id(), SpendPurse.RESEARCH, "", "operator", 0, 0, "");
        HttpResponse<String> state = c.send(HttpRequest.newBuilder(URI.create(svc.url() + Uses.STATE)).POST(HttpRequest.BodyPublishers.ofString("{\"use\":\"" + u.id() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, state.statusCode(), "a use's state, to anyone on this machine who names it: " + state.body());
        HttpResponse<String> end = c.send(HttpRequest.newBuilder(URI.create(svc.url() + Uses.END)).POST(HttpRequest.BodyPublishers.ofString("{\"use\":\"" + u.id() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, end.statusCode(), end.body());
        assertTrue(store.use(u.id()).open(), "still open");
        String key = svc.useKey(u.id());
        end = c.send(HttpRequest.newBuilder(URI.create(svc.url() + Uses.END)).POST(HttpRequest.BodyPublishers.ofString("{\"use\":\"" + u.id() + "\",\"key\":\"" + key + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, end.statusCode(), end.body());
        assertFalse(store.use(u.id()).open());
    }

    @Test
    void aJoinLinkVisitedBindsNoProviderSignInUntilThePersonActsOnIt(@TempDir Path tmp) throws Exception {
        start(tmp, true);
        AccountService.Made bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String code = store.newInvite(bo.account().id(), "the test", AccountService.JOIN_DAYS);
        Browser b = new Browser();
        HttpResponse<String> page = b.get("/join?code=" + enc(code));
        assertEquals(200, page.statusCode());
        assertNull(b.cookies.get(AccountPages.JOIN), "a page opened from a link someone sent binds nothing");
        HttpResponse<String> chose = b.post("/join", "code=" + enc(code) + "&op=provider");
        assertEquals(200, chose.statusCode());
        assertEquals("/login/oidc", onwards(chose), "on to the provider by itself, as a form's answer may not redirect there");
        assertEquals(code, b.cookies.get(AccountPages.JOIN), "the person chose to sign in with the provider from this page");
    }

    // ---- SEC 10, SEC 11 ----

    @Test
    void recentActivityShowsAnAddressOnlyForTheAccountsOwnActs(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        AccountActs.audit(store, new AccountActs.By(ann.account().id(), "session", "203.0.113.5"), "member_removed", bo.account().id(), ann.library().id(), null);
        AccountActs.audit(store, new AccountActs.By(bo.account().id(), "password", "198.51.100.7"), "signin", bo.account().id(), "", null);
        String page = AccountPages.accountPage(svc, new AccountService.Who(bo.account(), "session", null, store.newSession(bo.account().id(), "", "a browser", 1)), null, "");
        assertTrue(page.contains("198.51.100.7"), "where the account itself signed in from");
        assertFalse(page.contains("203.0.113.5"), "not where another account acted from");
    }

    @Test
    void wrongPasswordsFromElsewhereDoNotLockThePersonOut(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        Config.set(Proxy.SETTING, "127.0.0.1");
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        store.setPassword(ann.account().id(), Passwords.hash("ann's long password"));
        Browser mallory = new Browser(); mallory.forwarded = "203.0.113.5";
        for (int i = 0; i < Accounts.TRIES; i++) mallory.post("/login", "name=" + enc("Ann Example") + "&password=" + enc("guess " + i));
        Browser own = new Browser(); own.forwarded = "198.51.100.7";
        HttpResponse<String> r = own.post("/login", "name=" + enc("Ann Example") + "&password=" + enc("ann's long password"));
        assertEquals(303, r.statusCode(), "the person signs in from their own address: " + r.body());
    }

    // ---- SEC 9 ----

    @Test
    void anArchiveTakenInBringsNoneOfWhatAnExportLeavesOut(@TempDir Path tmp) throws Exception {
        start(tmp, false);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountStore.Library made = AccountService.newLibrary(store, root, ann.account(), "Harbour notes");
        Files.createDirectories(LibraryPlaces.library(made).resolve("raw"));
        Files.writeString(LibraryPlaces.library(made).resolve("raw").resolve("a.md"), "a page\n");
        Path exported = tmp.resolve("exported.rzlib.zip");
        try (var o = Files.newOutputStream(exported)) { LibraryArchive.write(o, LibraryPlaces.folder(made), made, ""); }
        AccountsCli.places(store, made, "delete", null, true, out(new ByteArrayOutputStream()));   // gone from the service: taken in again from its archive
        // the same archive with what an export never carries put in by hand: another person's account and keys, a job's work, a lock
        Map<String, String> planted = Map.of("library/catalog/accounts.json", "{\"accounts\":[{\"name\":\"Mallory\"}]}", "library/catalog/keys.json", "{\"keys\":[]}",
                "library/catalog/patrons.md", "mallory: write\n", "library/catalog/jobs/work/j1/state.json", "{}", "library/catalog/serve.lock", "1\n");
        Path crafted = tmp.resolve("crafted.rzlib.zip");
        long extraBytes = 0; for (String v : planted.values()) extraBytes += v.getBytes(StandardCharsets.UTF_8).length;
        try (ZipFile z = new ZipFile(exported.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(crafted))) {
            for (var e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                byte[] data = z.getInputStream(en).readAllBytes();
                if (en.getName().equals(LibraryArchive.MANIFEST)) {
                    JsonNode m = Statement.M.readTree(data);
                    ((ObjectNode) m).put("files", m.path("files").asLong() + planted.size()).put("bytes", m.path("bytes").asLong() + extraBytes);
                    data = Statement.M.writeValueAsBytes(m);
                }
                out.putNextEntry(new ZipEntry(en.getName())); out.write(data); out.closeEntry();
            }
            for (Map.Entry<String, String> p : planted.entrySet()) { out.putNextEntry(new ZipEntry(p.getKey())); out.write(p.getValue().getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        AccountsCli.TakenIn in = AccountsCli.takeIn(store, crafted, ann.account(), AccountActs.By.cli());
        Path lib = LibraryPlaces.library(in.taken().library());
        assertTrue(Files.isRegularFile(lib.resolve("raw").resolve("a.md")), "the library's own files come");
        for (String p : planted.keySet()) assertFalse(Files.exists(lib.resolve(p.substring("library/".length()))), "not taken in: " + p);
    }

    static String said(ByteArrayOutputStream o) { return o.toString(StandardCharsets.UTF_8); }
    static PrintStream out(ByteArrayOutputStream o) { return new PrintStream(o, true, StandardCharsets.UTF_8); }
    static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
}
