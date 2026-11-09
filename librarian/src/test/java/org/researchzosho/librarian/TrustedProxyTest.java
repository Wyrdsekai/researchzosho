package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proxy in front of the library: whose forwarded headers are believed, the Secure cookie, and how long a sign-in lasts. */
class TrustedProxyTest {

    @Test
    void anAddressOrANetworkMatchesAndANameNever() throws Exception {
        assertTrue(Proxy.matches(InetAddress.getByName("10.1.2.3"), "10.0.0.0/8"));
        assertFalse(Proxy.matches(InetAddress.getByName("11.0.0.1"), "10.0.0.0/8"));
        assertTrue(Proxy.matches(InetAddress.getByName("127.0.0.1"), "127.0.0.1"));
        assertTrue(Proxy.matches(InetAddress.getByName("::1"), "::1/128"));
        assertFalse(Proxy.matches(InetAddress.getByName("127.0.0.1"), "::1"), "a four-byte address is not a sixteen-byte one");
        assertFalse(Proxy.matches(InetAddress.getByName("127.0.0.1"), "proxy.internal"), "a name is not looked up");
        assertFalse(Proxy.matches(InetAddress.getByName("127.0.0.1"), "127.0.0.1/x"));
        assertTrue(Proxy.trusted(InetAddress.getByName("172.16.5.5"), "10.0.0.0/8, 172.16.0.0/12"));
        assertFalse(Proxy.trusted(InetAddress.getByName("172.16.5.5"), ""));
    }

    @Test
    void behindATrustedProxyTheForwardedHeadersAreBelievedAndNowhereElse(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Accounts.create(store, Accounts.OWNER, "The Keeper", "a long enough password");
        String did = Patrons.loginDid("alice@example.com");
        Patrons.set(store, did, "Alice", Patrons.Level.read);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            // not trusted: the headers mean nothing; the cookie is not Secure on plain http
            Proxy.OVERRIDE = "";
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Forwarded-Proto", "https").POST(HttpRequest.BodyPublishers.ofString("name=The+Keeper&password=a+long+enough+password")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = r.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.startsWith(Pages.COOKIE + "="), r.statusCode() + " " + cookie);
            assertFalse(cookie.contains("Secure"), cookie);
            assertTrue(cookie.contains("Max-Age=31536000"), cookie);
            HttpResponse<String> a = c.send(HttpRequest.newBuilder(URI.create(base + "/account")).header("Tailscale-User-Login", "alice@example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(a.statusCode() == 200 && a.body().contains("Alice"), "a relay's identity header on the main port, from no trusted proxy, signs nobody in");
            // trusted: the proxy's word on https makes the cookie Secure, and the relay's identity header signs the listed reader in
            Proxy.OVERRIDE = "127.0.0.1/32";
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "203.0.113.9").POST(HttpRequest.BodyPublishers.ofString("name=The+Keeper&password=a+long+enough+password")).build(), HttpResponse.BodyHandlers.ofString());
            cookie = r.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.contains("; Secure"), cookie);
            a = c.send(HttpRequest.newBuilder(URI.create(base + "/account")).header("Tailscale-User-Login", "alice@example.com").header("X-Forwarded-For", "203.0.113.9").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, a.statusCode(), a.body());
            assertTrue(a.body().contains("Alice"), "signed in as the listed reader the proxy named");
        } finally { Proxy.OVERRIDE = null; d.stop(); }
    }

    /** The server keeps one map of marks for every request it serves; the daemon gives each request its own, so a request from a trusted proxy marks no other. */
    @Test
    void aRequestFromNoTrustedProxyIsNotBelievedAfterOneThatWas(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Accounts.create(store, Accounts.OWNER, "The Keeper", "a long enough password");
        Patrons.set(store, Patrons.loginDid("alice@example.com"), "Alice", Patrons.Level.read);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            Proxy.OVERRIDE = "127.0.0.1/32";
            HttpResponse<String> a = c.send(HttpRequest.newBuilder(URI.create(base + "/account")).header("Tailscale-User-Login", "alice@example.com").header("X-Forwarded-For", "203.0.113.9").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(a.statusCode() == 200 && a.body().contains("Alice"), "through the trusted proxy, the reader it names: " + a.statusCode());
            Proxy.OVERRIDE = "";
            a = c.send(HttpRequest.newBuilder(URI.create(base + "/account")).header("Tailscale-User-Login", "alice@example.com").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(a.statusCode() == 200 && a.body().contains("Alice"), "the next request, from no trusted proxy, signs nobody in by the same header");
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/login")).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Forwarded-Proto", "https").POST(HttpRequest.BodyPublishers.ofString("name=The+Keeper&password=a+long+enough+password")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = r.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(cookie.startsWith(Pages.COOKIE + "=") && !cookie.contains("Secure"), "nor is its word on https believed: " + cookie);
        } finally { Proxy.OVERRIDE = null; d.stop(); }
    }

    @Test
    void aProxyOnThisMachineNeverPassesForThePersonWhoKeepsTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = Boolean.TRUE;   // sign-in on; a browser on this computer is the keeper
        try {
            Proxy.OVERRIDE = "";
            String visitor = "Keep is the page of the person who keeps this library";
            HttpResponse<String> home = c.send(HttpRequest.newBuilder(URI.create(base + "/keep")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, home.statusCode());
            assertTrue(home.body().contains("id=\"connection\"") && !home.body().contains(visitor), "from this computer, on the main port, the keeper's own page");
            Proxy.OVERRIDE = "127.0.0.1/32";
            home = c.send(HttpRequest.newBuilder(URI.create(base + "/keep")).header("X-Forwarded-For", "203.0.113.9").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(home.body().contains(visitor), "through a proxy on this computer, a browser elsewhere is a visitor, not the keeper");
            home = c.send(HttpRequest.newBuilder(URI.create(base + "/keep")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(home.body().contains(visitor), "a trusted proxy that names nobody is not this computer either");
        } finally { Proxy.OVERRIDE = null; WebAccess.OVERRIDE = null; WebAccess.LOCAL_OVERRIDE = null; d.stop(); }
    }

    @Test
    void aSignInLastsTheDaysTheSettingSays(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Accounts.create(store, Accounts.OWNER, "The Keeper", "a long enough password");
        String secret = Accounts.newSession(store, Accounts.OWNER, "a test browser");
        assertNotNull(Accounts.bySession(store, secret));
        Accounts.SESSION_DAYS_OVERRIDE = 30;
        try {
            assertNotNull(Accounts.bySession(store, secret), "made today");
            String made = LocalDate.now().minusDays(31).toString();
            Path f = Accounts.file(store);
            Files.writeString(f, Files.readString(f, StandardCharsets.UTF_8).replace("\"made\" : \"" + LocalDate.now() + "\"", "\"made\" : \"" + made + "\""), StandardCharsets.UTF_8);
            assertNull(Accounts.bySession(store, secret), "older than the setting: signed out");
            assertEquals(30, Accounts.sessionDays());
        } finally { Accounts.SESSION_DAYS_OVERRIDE = null; }
    }
}
