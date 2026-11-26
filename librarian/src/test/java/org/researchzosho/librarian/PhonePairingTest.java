package org.researchzosho.librarian;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pairing a phone (2026-11-25): a code good once and for minutes, the same answer for a wrong, a used and a run-out code, a limit on wrong
 * codes per sender and for all, the sender read by the configured relay's header; the cookie's attributes; what is kept on disk (no secret,
 * no code); a pairing that outlives a restart of the service and the door's off and on; a removal that holds at the next request; one
 * browser per pairing, and the old home-screen address only a way back to the page; a request from another site, the share origin among
 * them, refused with the phone's cookie on it; each door deaf to
 * the other's cookie, and no cookie in the log; the Settings page's Phone section held as the page is.
 */
class PhonePairingTest {

    static String code(HttpResponse<String> r) { return r.statusCode() + " " + r.body(); }

    @Test
    void aCodeIsGoodOnceForItsMinutesAndAWrongOneIsAnsweredTheSameAndLimited(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Phones.Made m = Phones.pairingCode("pixel");
            assertEquals(22, m.code().length(), "16 random bytes");
            HttpResponse<String> ok = s.post(s.door(), "/m/pair", null, "c=" + m.code());
            assertEquals(200, ok.statusCode(), code(ok));
            String set = ok.headers().firstValue("Set-Cookie").orElse("");
            assertTrue(set.matches(PhoneDoor.COOKIE + "=[A-Za-z0-9_-]{43}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=34560000"), set);
            HttpResponse<String> again = s.post(s.door(), "/m/pair", null, "c=" + m.code());
            assertEquals(403, again.statusCode());
            String said = M(again).path("error").asText();
            assertTrue(said.contains("This pairing link is not good any more"), said);
            Phones.CODE_MS = 50;
            try { Phones.Made late = Phones.pairingCode("late"); Thread.sleep(120); assertEquals(said, M(s.post(s.door(), "/m/pair", null, "c=" + late.code())).path("error").asText(), "a run-out code: the same answer"); }
            finally { Phones.CODE_MS = 10 * 60_000L; }
            assertEquals(said, M(s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16))).path("error").asText(), "a code never made: the same answer");
            // the limit: ten wrong codes in ten minutes a sender (here three are spent already), then 429 even for a good code
            for (int i = 0; i < 7; i++) assertEquals(403, s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16)).statusCode());
            Phones.Made good = Phones.pairingCode("good");
            HttpResponse<String> held = s.post(s.door(), "/m/pair", null, "c=" + good.code());
            assertEquals(429, held.statusCode(), code(held));
            assertTrue(M(held).path("error").asText().contains("Too many wrong pairing links from here"), held.body());
            assertTrue(held.headers().firstValue("Retry-After").isPresent());
            assertEquals("", Phones.usedBy(good.code()), "a code asked after the limit is not spent");
        }
    }

    @Test
    void theSenderIsTheConfiguredRelaysHeaderAndAllSendersTogetherHaveALimitToo(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Config.set(PhoneDoor.KIND, "public");   // Tailscale: X-Forwarded-For, which its proxy sets to the connection's own address
            for (int i = 0; i < 10; i++) s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "X-Forwarded-For", "203.0.113.7");
            assertEquals(429, s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "X-Forwarded-For", "203.0.113.7").statusCode());
            assertEquals(403, s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "X-Forwarded-For", "203.0.113.8").statusCode(), "another sender has a count of its own");
            assertEquals(429, s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "X-Forwarded-For", "203.0.113.7", "CF-Connecting-IP", "198.51.100.1").statusCode(),
                    "behind Tailscale a Cloudflare header changes nobody's count");
            Config.set(PhoneDoor.KIND, "own");   // an own relay: Cloudflare's header, unless the person named another
            assertEquals(403, s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "X-Forwarded-For", "203.0.113.7", "CF-Connecting-IP", "198.51.100.1").statusCode());
            // all senders together: fifty wrong codes in ten minutes, then pairing waits for everyone
            for (int i = 0; i < 60; i++) s.post(s.door(), "/m/pair", null, "c=" + Phones.random(16), "CF-Connecting-IP", "198.51.100." + (10 + i));
            Phones.Made good = Phones.pairingCode("good");
            assertEquals(429, s.post(s.door(), "/m/pair", null, "c=" + good.code(), "CF-Connecting-IP", "192.0.2.200").statusCode(), "a flood of wrong codes holds pairing up for everyone, and never opens it");
            assertTrue(PhoneStandIns.read(s.store.root().resolve("catalog").resolve("crews.log")).contains("pairing is closed for a while"));
        }
    }

    static JsonNode M(HttpResponse<String> r) throws Exception { return PhoneStandIns.M.readTree(r.body()); }

    @Test
    void whatIsKeptIsHashesInTheHomeAndAPairingOutlivesARestartAndTheDoorsOffAndOn(@TempDir Path tmp) throws Exception {
        String cookie;
        Path file;
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            cookie = s.pair("pixel");
            file = Phones.file();
            String kept = Files.readString(file);
            assertFalse(kept.contains(cookie), "the secret is not kept");
            assertTrue(kept.contains(Phones.hash(cookie)), "its hash is");
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertEquals(file, Config.home().resolve("phones.json"));
            assertTrue(Config.OWN_NAMES.contains("phones.json") && Config.OWN_NAMES.contains("phones.lock"), "a move out of ~/.codezaiku carries it");
            // off and on: the door closes and opens; the pairing stays
            s.d.closePhoneDoor(); assertEquals(-1, s.d.phoneDoorPort());
            s.d.openPhoneDoor(0);
            assertTrue(s.get(s.door(), "/m/state", cookie).body().contains("\"paired\":true"));
        }
        // the service restarted: another daemon, the same home and library
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:9", "stand-in", -1);
        try {
            d.openPhoneDoor(0);
            HttpResponse<String> st = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.phoneDoorPort() + "/m/state")).header("Cookie", PhoneDoor.COOKIE + "=" + cookie).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(st.body().contains("\"paired\":true") && st.body().contains("\"phone\":\"pixel\""), st.body());
            assertTrue(st.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=34560000"), "set again at every open: 400 days from now");
        } finally { d.stop(); System.setProperty("user.home", home); Config.invalidate(); }
    }

    @Test
    void aRemovedPhoneIsOutAtItsNextRequestAndAnOldHomeScreenAddressOnlyLeadsBackToThePage(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String safari = s.pair("iphone");
            // the home-screen code is gone (2026-11-25): nothing makes one, and one an earlier version made pairs nothing
            assertEquals(404, s.post(s.door(), "/m/home", safari, "").statusCode());
            Phones.Phone p = Phones.list().get(0);
            String old = Phones.random(16);
            ObjectNode kept = (ObjectNode) PhoneStandIns.M.readTree(Files.readString(Phones.file()));
            ((ArrayNode) kept.path("codes")).addObject().put("hash", Phones.hash(old)).put("until", System.currentTimeMillis() + 600_000).put("name", p.name()).put("phone", p.id()).put("used", "").put("cap", p.cap());
            Files.writeString(Phones.file(), kept.toString());
            // an icon an earlier version put on a home screen at /m/h/<code>: a plain way back to /m, with or without the cookie,
            // which spends nothing, sets nothing and reads nothing of the phone
            for (String who : new String[]{null, safari}) {
                for (String path : new String[]{"/m/h/" + old, "/m/h/x"}) {
                    HttpResponse<String> r = s.get(s.door(), path, who);
                    assertEquals(302, r.statusCode(), path + " " + code(r));
                    assertEquals("/m", r.headers().firstValue("Location").orElse(""));
                    assertTrue(r.headers().firstValue("Set-Cookie").isEmpty() && r.body().isEmpty());
                }
            }
            assertEquals("", Phones.usedBy(old), "the old code is not spent by its address");
            HttpResponse<String> spent = s.post(s.door(), "/m/pair", null, "c=" + old);
            assertEquals(403, spent.statusCode(), code(spent));
            assertEquals(1, Phones.list().size());
            assertEquals(1, Phones.list().get(0).keys().size(), "one browser per pairing");
            // removed: out at the next request
            assertNotNull(Phones.remove("iphone"));
            assertTrue(s.get(s.door(), "/m/state", safari).body().contains("\"paired\":false"));
            assertEquals(403, s.get(s.door(), "/m/runs", safari).statusCode());
            assertEquals(403, s.post(s.door(), "/m/ask", safari, "say=hello").statusCode());
            assertEquals(302, s.get(s.door(), "/m/h/" + old, safari).statusCode(), "and its old icon still lands on the page, which says it is not paired");
        }
    }

    @Test
    void aRequestFromAnotherSiteWithThePhonesCookieIsRefusedTheShareOriginToo(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            // the change asked here is "I have looked at the runs" (POST /m/seen), which writes the time it was asked
            // a form or a script on another site: the browser says so, or sends no page header
            for (String[] h : new String[][]{{"Sec-Fetch-Site", "cross-site"}, {"Sec-Fetch-Site", "same-site"}, {PhoneDoor.HEADER, null}, {"Sec-Fetch-Site", "none"}}) {
                HttpResponse<String> r = s.post(s.door(), "/m/seen", cookie, "", h[0], h[1]);
                assertEquals(403, r.statusCode(), String.join("=", h) + " " + code(r));
                assertEquals("This came from another page, so it was not done.\n", r.body());
            }
            // a browser that sends no Sec-Fetch-Site: the Origin must be this door's own address, port and all; share's (443) is another
            String phoneAt = "homebox.tail1234.ts.net:8443";
            assertEquals(403, s.post(s.door(), "/m/seen", cookie, "", "Sec-Fetch-Site", null, "Origin", "https://homebox.tail1234.ts.net", "X-Forwarded-Host", phoneAt).statusCode(), "the share origin, same site and not the same origin");
            assertEquals(403, s.post(s.door(), "/m/seen", cookie, "", "Sec-Fetch-Site", null, "Origin", "https://elsewhere.example", "X-Forwarded-Host", phoneAt).statusCode());
            assertEquals(403, s.post(s.door(), "/m/seen", cookie, "", "Sec-Fetch-Site", null).statusCode(), "no word at all on where it came from");
            assertEquals("", Phones.list().get(0).seen(), "nothing was done");
            // the controls: the page's own post, either way the browser says it
            assertEquals(200, s.post(s.door(), "/m/seen", cookie, "").statusCode());
            assertFalse(Phones.list().get(0).seen().isEmpty(), "the control did it");
            assertEquals(200, s.post(s.door(), "/m/seen", cookie, "", "Sec-Fetch-Site", null, "Origin", "https://" + phoneAt, "X-Forwarded-Host", phoneAt).statusCode());
            // a preflight, which a script of another site needs for the page's header, is never answered
            HttpResponse<String> pre = s.c.send(HttpRequest.newBuilder(URI.create(s.door() + "/m/ask")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "https://elsewhere.example").header("Access-Control-Request-Method", "POST").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, pre.statusCode());
            assertTrue(pre.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        }
    }

    @Test
    void eachDoorIsDeafToTheOthersCookieAndNoCookieIsLogged(@TempDir Path tmp) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger("ROOT");
        ListAppender<ILoggingEvent> logged = new ListAppender<>(); logged.start(); root.addAppender(logged);
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = false;   // the pages ask for a sign-in, and this machine's browser is not the keeper by itself
            String cookie = s.pair("pixel");
            Accounts.create(s.store, Accounts.OWNER, "Owner", "owner-secret-1");
            String session = Accounts.newSession(s.store, Accounts.OWNER, "the test's browser");
            // the phone's cookie at the library's own port: a visitor's request, no more
            HttpResponse<String> settings = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("Cookie", PhoneDoor.COOKIE + "=" + cookie).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, settings.statusCode(), "the phone's cookie is nobody at the web app");
            HttpResponse<String> signed = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("Cookie", Pages.COOKIE + "=" + session).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, signed.statusCode(), "the control: the owner's own sign-in opens it");
            // the web app's sign-in at the phone door: not a pairing
            assertTrue(s.get(s.door(), "/m/state", null).body().contains("\"paired\":false"));
            HttpResponse<String> state = s.c.send(HttpRequest.newBuilder(URI.create(s.door() + "/m/state")).header("Cookie", Pages.COOKIE + "=" + session).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(state.body().contains("\"paired\":false"), state.body());
            assertEquals(403, s.c.send(HttpRequest.newBuilder(URI.create(s.door() + "/m/runs")).header("Cookie", Pages.COOKIE + "=" + session).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            // a request the door cannot answer is logged by its path, never its cookie
            s.get(s.door(), "/m/read/I-9999-nothing", cookie);
            for (ILoggingEvent e : logged.list) { String line = e.getFormattedMessage(); assertFalse(line.contains(cookie) || line.contains(session), line); }
        } finally { root.detachAppender(logged); WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    @Test
    void theSettingsPagesPhoneSectionIsTheKeepersAsThePageIs(@TempDir Path tmp) throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE; String proxy = Proxy.OVERRIDE;
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Config.set(PhoneDoor.KIND, "tailnet"); Config.set(PhoneDoor.ADDRESS, "https://homebox.tail1234.ts.net:8443");
            WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = true;   // sign-in on; a browser on this machine is the keeper (web.local)
            Proxy.OVERRIDE = "127.0.0.1";   // the stand-in for the network: a request through a proxy on this machine is never the machine's own browser
            String form = PhoneStandIns.form("op", "phone-pair", "name", "pixel");
            // from the network: the page is refused, and so is its button's post; no code is made
            HttpResponse<String> page = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("X-Forwarded-For", "192.0.2.10").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, page.statusCode());
            HttpResponse<String> pair = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("X-Forwarded-For", "192.0.2.10").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, pair.statusCode());
            assertTrue(Phones.read().codes().isEmpty(), "nothing was made from the network");
            assertFalse(pair.body().contains("/m/pair?c=") || page.body().contains("Pair a phone"));
            // on the machine: the section, the button, the code shown once as its QR code, the list with its Remove
            Proxy.OVERRIDE = null;
            HttpResponse<String> mine = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mine.statusCode());
            assertTrue(mine.body().contains("id=\"phone\"") && mine.body().contains("value=\"phone-pair\""), "the section and its button");
            HttpResponse<String> made = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, made.statusCode(), code(made));
            assertTrue(made.body().contains("https://homebox.tail1234.ts.net:8443/m/pair?c=") && made.body().contains("<svg"), "the link and its QR code");
            assertEquals(1, Phones.read().codes().size());
            s.pair("pixel 2");
            String listed = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(listed.contains("pixel 2") && listed.contains("value=\"phone-remove\""), "the list");
            // the network does not see the list either
            Proxy.OVERRIDE = "127.0.0.1";
            String fromNet = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("X-Forwarded-For", "192.0.2.10").build(), HttpResponse.BodyHandlers.ofString()).body();
            assertFalse(fromNet.contains("pixel 2"));
            HttpResponse<String> rm = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("X-Forwarded-For", "192.0.2.10").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(PhoneStandIns.form("op", "phone-remove", "id", "pixel 2"))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rm.statusCode());
            assertEquals(1, Phones.list().size(), "nor removes a phone");
        } finally { WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; Proxy.OVERRIDE = proxy; }
    }

    @Test
    void theNameIsCleanAndFreeAndTheCapWordsAreRead() throws Exception {
        assertEquals("phone", Phones.cleanName("  \n "));
        assertEquals("my phone", Phones.cleanName("my phone"));
        assertEquals(40, Phones.cleanName("x".repeat(80)).length());
        assertEquals(Phones.NO_LIMIT, Phones.capOf("none"));
        assertEquals(Phones.NO_LIMIT, Phones.capOf("-1"));
        assertEquals(2.5, Phones.capOf("$2.50"));
        assertEquals(0.0, Phones.capOf("0"));
        assertNull(Phones.capOf("lots"));
        assertNull(Phones.capOf("-5"));
        assertEquals("no limit", Phones.capSaid(-1, Chrome.ENGLISH));
        assertEquals("no paid model", Phones.capSaid(0, Chrome.ENGLISH));
        assertEquals("$10.00 a month", Phones.capSaid(10, Chrome.ENGLISH));
    }
}
