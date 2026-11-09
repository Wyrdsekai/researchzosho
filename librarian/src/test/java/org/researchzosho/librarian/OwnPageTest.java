package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.StandInMcp;
import org.researchzosho.tools.SearchTally;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A form posted to the library's pages is carried out only when the browser says it came from the library's own page, the address it
 * sees, which behind a trusted proxy is the forwarded one; a program's call with a key is no form, and the protocol's routes are as they were.
 */
class OwnPageTest {
    @TempDir Path tmp;
    String realHome;
    LibraryStore store;
    LibrarianDaemon d;
    HttpClient c;
    String base;

    @BeforeEach void start() throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        base = "http://127.0.0.1:" + d.port();
    }

    @AfterEach void stop() {
        d.stop(); McpConnections.closeAll();
        Proxy.OVERRIDE = null; WebAccess.OVERRIDE = null; WebAccess.LOCAL_OVERRIDE = null; SearchTally.override = null;
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    HttpResponse<String> post(String path, String form, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String[] with(String[] headers, String... more) { List<String> all = new ArrayList<>(List.of(headers)); all.addAll(List.of(more)); return all.toArray(new String[0]); }

    static String length() { Config.invalidate(); return Config.get(ReportOptions.LENGTH_SETTING); }

    static void refused(HttpResponse<String> r, String why) {
        assertEquals(403, r.statusCode(), why + ": " + r.body());
        assertEquals(OwnPage.REFUSED, r.body().strip(), why);
    }

    @Test
    void aFormFromTheLibrarysOwnPageIsCarriedOutAndOneFromAnotherSiteIsNot() throws Exception {
        refused(post("/settings", "name=report_length&value=short", "Origin", "https://elsewhere.example"), "a form from another site");
        assertNull(length(), "and nothing was set");
        HttpResponse<String> own = post("/settings", "name=report_length&value=short", "Origin", base);
        assertEquals(303, own.statusCode(), own.body());
        assertEquals("short", length(), "a form from the library's own page is carried out");
        refused(post("/settings", "name=report_length&value=long", "Origin", "http://127.0.0.1:" + (d.port() == 65535 ? 1 : d.port() + 1)), "the same computer at another port is another site");
        refused(post("/settings", "name=report_length&value=long", "Origin", "null"), "a page that hides where it is");
        // the browser's own word, where it gives it: another site, the same site at another name (another library on the same domain), or the page itself
        refused(post("/settings", "name=report_length&value=long", "Sec-Fetch-Site", "cross-site"), "the browser says another site");
        refused(post("/settings", "name=report_length&value=long", "Sec-Fetch-Site", "same-site"), "a neighbour on the same domain is another site too");
        assertEquals("short", length());
        assertEquals(303, post("/settings", "name=report_length&value=medium", "Sec-Fetch-Site", "same-origin").statusCode());
        assertEquals("medium", length());
        // every form, not the Keep page's alone: the chat's stop, the questions, the research page
        refused(post("/chat", "op=stop&session=x", "Origin", "https://elsewhere.example"), "the chat");
        refused(post("/questions", "op=park&id=Q-1", "Origin", "https://elsewhere.example"), "the open questions");
        refused(post("/research", "question=" + URLEncoder.encode("What is a dividing plate?", StandardCharsets.UTF_8), "Origin", "https://elsewhere.example"), "the research page");
        // a read is never asked
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(base + "/settings")).header("Sec-Fetch-Site", "cross-site").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void withNoOriginThePageTheFormWasOnCountsAndWithNeitherItIsAProgram() throws Exception {
        assertEquals(303, post("/settings", "name=report_length&value=short", "Referer", base + "/settings?note=x").statusCode(), "a same-origin page, named in Referer");
        assertEquals("short", length());
        refused(post("/settings", "name=report_length&value=long", "Referer", "https://elsewhere.example/a-page"), "a page on another site, named in Referer");
        assertEquals("short", length());
        assertEquals(303, post("/settings", "name=report_length&value=medium").statusCode(), "neither: no browser's form from another site, so a program, as before");
        assertEquals("medium", length());
    }

    @Test
    void behindATrustedProxyTheLibrarysAddressIsTheForwardedHostAndScheme() throws Exception {
        String[] proxied = {"X-Forwarded-For", "203.0.113.9", "X-Forwarded-Host", "lib.example.org", "X-Forwarded-Proto", "https"};
        Proxy.OVERRIDE = "127.0.0.1/32";
        HttpResponse<String> r = post("/settings", "name=report_length&value=short", with(proxied, "Origin", "https://lib.example.org"));
        assertEquals(303, r.statusCode(), "the browser at the address the proxy forwards: " + r.body());
        assertEquals("short", length());
        refused(post("/settings", "name=report_length&value=long", with(proxied, "Origin", "http://lib.example.org")), "the same name over plain http is another site");
        refused(post("/settings", "name=report_length&value=long", with(proxied, "Origin", base)), "behind the proxy the browser is at the forwarded address, not the one the proxy connected to");
        assertEquals(303, post("/settings", "name=report_length&value=medium", "X-Forwarded-For", "203.0.113.9", "X-Forwarded-Host", "lib.example.org:443", "X-Forwarded-Proto", "https",
                "Origin", "https://lib.example.org").statusCode(), "the port https goes to by itself is the same address");
        assertEquals(303, post("/settings", "name=report_length&value=long", "X-Forwarded-For", "203.0.113.9", "X-Forwarded-Proto", "http", "Origin", base).statusCode(),
                "a proxy that forwards no host: the Host it passes on is the address");
        assertEquals("long", length());
        Proxy.OVERRIDE = "";
        refused(post("/settings", "name=report_length&value=short", with(proxied, "Origin", "https://lib.example.org")), "from no trusted proxy, the forwarded host means nothing");
        assertEquals("long", length());
    }

    @Test
    void aProgramsCallWithAKeyIsNoFormAndTheProtocolIsAsItWas() throws Exception {
        ApiKeys.Minted k = ApiKeys.mint(store, Accounts.OWNER, "a script", Patrons.Level.write);
        HttpResponse<String> api = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/bookmarks")).header("Authorization", "Bearer " + k.secret()).header("Content-Type", "application/json")
                .header("Origin", "https://elsewhere.example").POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"https://example.com/a\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertNotEquals(403, api.statusCode(), api.body());
        assertFalse(api.body().contains(OwnPage.REFUSED), api.body());
        HttpResponse<String> status = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/status")).header("Authorization", "Bearer " + k.secret()).header("Sec-Fetch-Site", "cross-site").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode(), status.body());
        HttpResponse<String> page = post("/settings", "name=report_length&value=short", "Authorization", "Bearer " + k.secret(), "Origin", "https://elsewhere.example");
        assertEquals(303, page.statusCode(), "a page asked with a key is a program's call: a browser never adds a key on its own: " + page.body());
        assertEquals("short", length());
    }

    @Test
    void withTheSignInOnABrowserOnThisComputerIsStillAskedWhereTheFormCameFrom() throws Exception {
        WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = Boolean.TRUE;   // a browser on this computer is the keeper, with no sign-in
        List<String> words = new ArrayList<>();
        for (String w : StandInMcp.command()) words.add("\"" + w + "\"");
        String add = "op=mcp-add&name=notes&command=" + URLEncoder.encode(String.join(" ", words), StandardCharsets.UTF_8);
        refused(post("/keep", add, "Origin", "https://elsewhere.example"), "a page elsewhere, opened on the keeper's computer, starts no program here");
        assertNull(McpServers.named(store, "notes"));
        refused(post("/settings", "name=report_length&value=short", "Origin", "https://elsewhere.example"), "nor changes a setting");
        assertNull(length());
        assertEquals(303, post("/settings", "name=report_length&value=short", "Origin", base).statusCode(), "the keeper's own page does");
        assertEquals("short", length());
    }
}
