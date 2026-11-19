package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account page's month and own keys, and the operator's page (0.8.0, stage 3, step 4): a person sees this month by kind against
 * each cap and what is left, a line when some counts were worked out from sizes, and their own keys, which they keep and take away
 * there and never see again; whoever runs the service sees every account's month and changes the caps; nobody else opens that page.
 * With RZ_SCREENSHOTS naming a folder, the two pages are written there as they were served, to be looked at.
 */
class AccountMonthPagesTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        for (String k : List.of(OwnKeys.FILE, "RESEARCHZOSHO_DRIVE")) Config.set(k, "");
        OwnKeys.forget();
    }

    HttpResponse<String> get(String cookie, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Cookie", AccountService.COOKIE + "=" + cookie).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String cookie, String path, String form) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Cookie", AccountService.COOKIE + "=" + cookie).header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @Test
    void aPersonSeesTheirMonthAndKeepsTheirOwnKeysAndTheOperatorSeesEveryAccount(@TempDir Path tmp) throws Exception {
        Config.set(OwnKeys.FILE, tmp.resolve("keys").resolve("own.key").toString());
        Config.set("RESEARCHZOSHO_DRIVE", "https://openrouter.ai/api/v1");   // the service's model is an API on the internet: a person may bring a key for it
        Path root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made olive = AccountService.newAccount(store, root, "Olive Operator", "", true, "the test"), ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String a = ann.account().id(), lib = ann.library().id();
        long now = System.currentTimeMillis();
        store.addUsage(List.of(
                new AccountStore.Usage(now, a, lib, "use_1", "model", "operator", "default", 9_000, 0, 1_000, 1, 40_000, 150_000, false),
                new AccountStore.Usage(now, a, lib, "use_1", "model", "operator", "default", 9_000, 0, 1_000, 1, 40_000, 150_000, false),
                new AccountStore.Usage(now, a, lib, "use_1", "model", "operator", "default", 9_000, 0, 1_000, 1, 40_000, 100_000, true),
                new AccountStore.Usage(now, a, lib, "use_1", "search", "operator", "brave", 0, 0, 0, 12, 90_000, 60_000, false),
                new AccountStore.Usage(now, a, lib, "use_1", "fetch", "operator", "", 0, 0, 0, 40, 2_000_000, 0, false),
                new AccountStore.Usage(now, a, lib, "use_2", "speech", "operator", "whisper", 0, 0, 0, 125, 0, 0, false),
                new AccountStore.Usage(now, a, lib, "use_3", "model", "own", "default", 4_000, 0, 500, 1, 9_000, 250_000, false),
                new AccountStore.Usage(now, a, lib, "", "storage", "operator", "", 0, 0, 0, 300, 300_000_000L, 0, false)));
        String annCookie = store.newSession(a, "", "a test browser", 30), oliveCookie = store.newSession(olive.account().id(), "", "a test browser", 30);

        HttpResponse<String> page = get(annCookie, "/account");
        assertEquals(200, page.statusCode());
        String p = page.body();
        for (String shown : List.of("This month", "The service's model and searches", "$0.46", "$5.00", "$4.54", "Searches", ">12<", ">300<", ">288<", "Pages fetched", ">40<", ">3000<", ">2960<",
                "Stored in your libraries", "300 MB", "2.0 GB", "1.7 GB", "Speech turned into text", "3 min", "On your own keys", "$0.25",
                "One of this month's 4 model calls was counted from its size", "The month starts again on", "A job may cost at most $1.50.", "A job may take at most 30 minutes.",
                "Jobs going at once: at most 1.", "Use your own keys (optional)", "openrouter.ai", "Brave Search", "not set"))
            assertTrue(p.contains(shown), "the account page shows " + shown);
        assertFalse(p.contains("Every account's month"), "the operator's page is not offered to a person who does not run the service");
        assertTrue(p.contains("<details class=\"fold\"><summary>"), "the own keys folded away while none is set");

        // a key of her own for the search place: kept sealed, never shown again
        HttpResponse<String> kept = post(annCookie, "/account", "op=own-key&host=brave&secret=" + enc("anns-own-brave-key-123"));
        assertEquals(303, kept.statusCode());
        assertFalse(store.ownKey(a, "brave").sealed().contains("anns-own-brave-key-123"));
        assertEquals("anns-own-brave-key-123", OwnKeys.keyFor(store, a, "brave"));
        p = get(annCookie, "/account").body();
        assertFalse(p.contains("anns-own-brave-key-123"), "never shown again");
        assertTrue(p.contains("set today"), "the page says it is set, and when");
        assertTrue(p.contains("<details class=\"fold\" open>"), "and the section is open while one is");
        post(annCookie, "/account", "op=own-key&host=brave&secret=");
        assertNull(store.ownKey(a, "brave"), "taken away");
        assertTrue(post(annCookie, "/account", "op=own-key&host=nowhere.example&secret=x").headers().firstValue("Location").orElse("").contains(enc("That is not a place this service has keys for.")));

        // the operator's page: whoever runs the service, nobody else
        assertEquals(403, get(annCookie, AccountPages.OPERATOR).statusCode());
        HttpResponse<String> op = get(oliveCookie, AccountPages.OPERATOR);
        assertEquals(200, op.statusCode());
        for (String shown : List.of("Every account's month", "Olive Operator", "Ann Example", "Bo Example", "$0.46 / $5.00", "12 / 300", "40 / 3000", "<span class=\"nw\">300 MB / 2.0 GB</span>", "The caps", "dollars a month",
                "An empty value, or the word default, puts the account back on the service's cap."))
            assertTrue(op.body().contains(shown), "the operator's page shows " + shown);
        assertTrue(get(oliveCookie, "/account").body().contains("Every account's month, and the caps"), "and the account page of whoever runs the service leads there");
        post(oliveCookie, AccountPages.OPERATOR, "op=caps&cap.searches=500&cap.month_dollars=" + enc("12.50") + "&cap.fetches=&cap.jobs_at_once=&cap.job_minutes=&cap.job_dollars=&cap.storage_mb=");
        assertEquals(500L, store.cap("*", Caps.SEARCHES)); assertEquals(12_500_000L, store.cap("*", Caps.MONTH_DOLLARS));
        post(oliveCookie, AccountPages.OPERATOR, "op=account-cap&account=" + a + "&name=job_dollars&value=" + enc("2,25"));
        assertEquals(2_250_000L, store.cap(a, Caps.JOB_DOLLARS), "a decimal comma, as most languages write it");
        post(oliveCookie, AccountPages.OPERATOR, "op=account-cap&account=" + a + "&name=job_dollars&value=");
        assertNull(store.cap(a, Caps.JOB_DOLLARS), "an empty value: the service's again");
        post(oliveCookie, AccountPages.OPERATOR, "op=account-cap&account=" + a + "&name=fetches&value=10");
        assertEquals(10L, store.cap(a, Caps.FETCHES));
        assertTrue(get(annCookie, "/account").body().contains(">10<"), "Ann's page has her own cap");
        HttpResponse<String> bad = post(oliveCookie, AccountPages.OPERATOR, "op=account-cap&account=" + a + "&name=fetches&value=lots");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains(enc("A cap is a number, dollars as 5, 5.00 or 5,00, or off.")));
        post(oliveCookie, AccountPages.OPERATOR, "op=account-cap&account=" + a + "&name=fetches&value=default");
        assertNull(store.cap(a, Caps.FETCHES), "back to the service's");

        String dir = System.getenv("RZ_SCREENSHOTS");
        if (dir != null && !dir.isBlank()) {
            OwnKeys.set(store, a, OwnKeys.host("openrouter.ai"), "a-key-for-the-picture");
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, "account.html"), get(annCookie, "/account").body());
            Files.writeString(Path.of(dir, "operator.html"), get(oliveCookie, AccountPages.OPERATOR + "?note=" + enc("2 caps of the service's are changed.")).body());
        }
    }
}
