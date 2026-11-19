package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An identity provider's sign-in lands only in an account that is its own (0.8.0 review, round A): an address typed by someone who
 * lets a name into a library is never an account's mail, and a provider's verified address finds an account only by an address that
 * was verified (the operator set it, or the provider said it). A password changed ends the account's other sessions.
 */
class ProviderBindingTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        AccountService.SIGNUP_OVERRIDE = null;
        Config.set(AccountService.ROOT, "");
    }

    static ObjectNode claims(String sub, String mail, boolean verified) { return Statement.M.createObjectNode().put("sub", sub).put("email", mail).put("email_verified", verified).put("name", "Victoria Example"); }

    HttpResponse<String> form(String path, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin");
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String cookie(HttpResponse<String> r) {
        String s = r.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(AccountService.COOKIE + "=") && !v.startsWith(AccountService.COOKIE + "=;")).findFirst().orElseThrow();
        return s.substring(0, s.indexOf(';'));
    }

    @Test
    void aTypedAddressIsNeverTheMailAProviderSignInFinds(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.SIGNUP_OVERRIDE = "invited";

        // someone lets a stranger's address into their library, and chooses the new account's password through its link
        AccountService.Made mallory = AccountService.newAccount(store, root, "Mallory Example", "", false, "the test");
        AccountPages.LetIn in = AccountPages.letIn(svc, mallory.library(), mallory.account(), "victim@example.org", "read", new AccountActs.By(mallory.account().id(), "session", ""));
        String code = in.link().substring(in.link().indexOf("code=") + 5);
        assertEquals(303, form("/join", null, "code=" + enc(code) + "&op=new&name=" + enc("victim@example.org") + "&password=" + enc("correct-horse-battery") + "&again=" + enc("correct-horse-battery")).statusCode());
        AccountStore.Account made = store.accountByLogin("victim@example.org");
        assertEquals("", made.mail(), "the address typed as a name is not the new account's mail");

        // the address's owner signs in through the provider for the first time: not into that account
        IOException refused = assertThrows(IOException.class, () -> AccountPages.fromProvider(svc, new Oidc.Proven("https://idp.example", claims("idp-subject-42", "victim@example.org", true)), null, "addr|203.0.113.9"));
        assertTrue(refused.getMessage().contains("No account here is yours yet"), refused.getMessage());
        assertNull(store.boundAccount("https://idp.example", "idp-subject-42"));
        AccountService.SIGNUP_OVERRIDE = "anyone";
        AccountStore.Account own = AccountPages.fromProvider(svc, new Oidc.Proven("https://idp.example", claims("idp-subject-42", "victim@example.org", true)), null, "addr|203.0.113.9");
        assertNotEquals(made.id(), own.id(), "an account of their own");
        assertEquals("victim@example.org", own.mail());

        // an address the operator set is found, and so is one the provider verified at an earlier sign-in
        AccountService.SIGNUP_OVERRIDE = "invited";
        AccountService.Made ops = AccountService.newAccount(store, root, "Olga Example", "olga@example.org", false, "the command line");
        assertEquals(ops.account().id(), AccountPages.fromProvider(svc, new Oidc.Proven("https://idp.example", claims("idp-subject-7", "olga@example.org", true)), null, "addr|203.0.113.9").id());
        assertEquals(own.id(), AccountPages.fromProvider(svc, new Oidc.Proven("https://other-idp.example", claims("other-subject", "victim@example.org", true)), null, "addr|203.0.113.9").id(),
                "the address the first provider verified finds the account from a second");
        // an address a provider does not vouch for finds nothing
        assertThrows(IOException.class, () -> AccountPages.fromProvider(svc, new Oidc.Proven("https://idp.example", claims("idp-subject-8", "olga@example.org", false)), null, "addr|203.0.113.9"));
    }

    @Test
    void aPasswordChangedEndsTheAccountsOtherSessions(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        Config.set(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        store.setPassword(ann.account().id(), Passwords.hash("ann's long password"));
        String login = "name=" + enc("Ann Example") + "&password=" + enc("ann's long password");
        String here = cookie(form("/login", null, login)), there = cookie(form("/login", null, login));
        assertEquals(2, store.sessions(ann.account().id()));
        HttpResponse<String> changed = form("/account", here, "op=password&current=" + enc("ann's long password") + "&password=" + enc("a new long password") + "&again=" + enc("a new long password"));
        assertEquals(303, changed.statusCode(), changed.body());
        assertTrue(changed.headers().firstValue("Location").orElse("").contains(enc("Your password is changed.")), changed.headers().map().toString());
        assertEquals(1, store.sessions(ann.account().id()), "the other browser is signed out");
        assertNotNull(store.session(here.substring(here.indexOf('=') + 1)), "this one stays signed in");
        assertNull(store.session(there.substring(there.indexOf('=') + 1)));
    }
}
