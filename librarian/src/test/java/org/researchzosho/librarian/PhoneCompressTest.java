package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone door's answers compressed (2026-11-25, the owner: "why not compress them anyways?"): a text answer over a kilobyte goes
 * gzipped to a browser that takes gzip and inflates to exactly the bytes it had; without gzip, or with gzip refused, nothing changes; the
 * icons and a short error go as they are; every text answer says Vary; every other header is as it was. And the reason it is safe: no
 * answer of the door holds the pairing code or the cookie's value. Nothing starts but the stand-ins' door.
 */
class PhoneCompressTest {

    @Test
    void anAcceptEncodingTakesGzipOnlyWithAQAboveNought() {
        for (String yes : new String[]{"gzip", "GZIP", "gzip, deflate, br", "br;q=1.0, gzip;q=0.5", "x-gzip", "*", "deflate, *;q=0.2"})
            assertTrue(PhoneDoor.takesGzip(yes), yes);
        for (String no : new String[]{null, "", "identity", "br", "deflate", "gzip;q=0", "gzip; q=0.0", "gzip;q=0, *", "*;q=0", "gzip;q=nonsense"})
            assertFalse(PhoneDoor.takesGzip(no), String.valueOf(no));
        assertTrue(PhoneDoor.text("application/json; charset=utf-8") && PhoneDoor.text("text/html; charset=utf-8") && PhoneDoor.text("text/javascript; charset=utf-8")
                && PhoneDoor.text("application/manifest+json") && PhoneDoor.text("image/svg+xml") && PhoneDoor.text("text/plain; charset=utf-8"));
        assertFalse(PhoneDoor.text("image/png") || PhoneDoor.text("image/jpeg") || PhoneDoor.text("application/octet-stream"));
    }

    @Test
    void aTextAnswerGoesCompressedToABrowserThatTakesGzipAndInflatesToTheSameBytesWithTheSameHeaders(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            s.ask(cookie, "", "Tell me about this: " + "the ferry across the sound, its piers and its timetable. ".repeat(40));
            for (String path : new String[]{"/m", "/m/phone.js", "/m/conv"}) {
                HttpResponse<byte[]> plain = ask(s, path, cookie, null), zipped = ask(s, path, cookie, "gzip, deflate, br");
                assertEquals(200, plain.statusCode(), path);
                assertTrue(plain.body().length > PhoneDoor.COMPRESS_FROM, path + " is long enough to be compressed: " + plain.body().length);
                assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").orElse(""), path);
                assertEquals(String.valueOf(zipped.body().length), zipped.headers().firstValue("Content-Length").orElse(""), "the compressed length: " + path);
                assertTrue(zipped.body().length < plain.body().length / 2, path + ": " + plain.body().length + " -> " + zipped.body().length);
                byte[] inflated = new GZIPInputStream(new ByteArrayInputStream(zipped.body())).readAllBytes();
                if (path.equals("/m/conv")) assertEquals(strip(plain.body()), strip(inflated), "the same conversation");   // its times are written at each ask
                else assertArrayEquals(plain.body(), inflated, "inflates to exactly the bytes it had: " + path);
                assertEquals("Accept-Encoding", plain.headers().firstValue("Vary").orElse(""), "Vary also when it went plain: " + path);
                assertEquals("Accept-Encoding", zipped.headers().firstValue("Vary").orElse(""));
                assertEquals(others(plain), others(zipped), "every other header as it was: " + path);
                assertTrue(zipped.headers().firstValue("Content-Security-Policy").orElse("").equals(PhoneDoor.POLICY), "the policy above all");
                // gzip refused, or not asked for: as before
                for (String no : new String[]{"gzip;q=0", "identity"}) {
                    HttpResponse<byte[]> r = ask(s, path, cookie, no);
                    assertTrue(r.headers().firstValue("Content-Encoding").isEmpty(), path + " " + no);
                    if (!path.equals("/m/conv")) assertArrayEquals(plain.body(), r.body(), path + " " + no);
                }
            }
        }
    }

    @Test
    void theIconsAndAShortErrorGoAsTheyAre(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            for (String icon : PhonePage.ICONS.keySet()) {
                HttpResponse<byte[]> plain = ask(s, icon, null, null), asked = ask(s, icon, null, "gzip");
                assertTrue(asked.headers().firstValue("Content-Encoding").isEmpty() && asked.headers().firstValue("Vary").isEmpty(), icon);
                assertArrayEquals(plain.body(), asked.body(), icon);
            }
            HttpResponse<byte[]> refused = ask(s, "/m/runs", null, "gzip");   // a paired route without a pairing: a few bytes of JSON
            assertEquals(403, refused.statusCode());
            assertTrue(refused.headers().firstValue("Content-Encoding").isEmpty() && refused.body().length < PhoneDoor.COMPRESS_FROM);
            assertEquals("Accept-Encoding", refused.headers().firstValue("Vary").orElse(""), "a text answer, which another time could be long");
            HttpResponse<byte[]> missing = ask(s, "/m/nothing-here", null, "gzip");
            assertEquals(404, missing.statusCode());
            assertEquals("Not found.\n", new String(missing.body(), StandardCharsets.UTF_8));
            assertTrue(missing.headers().firstValue("Content-Encoding").isEmpty());
            HttpResponse<byte[]> back = ask(s, "/m/h/abcdefghijklmnopqrstuv", null, "gzip");
            assertEquals(302, back.statusCode());
            assertTrue(back.headers().firstValue("Content-Encoding").isEmpty() && back.body().length == 0, "the way back has no body to compress");
        }
    }

    @Test
    void noAnswerOfTheDoorHoldsThePairingCodeOrTheCookie(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Phones.Made made = Phones.pairingCode("pixel");
            String code = made.code();
            List<String> bodies = new ArrayList<>();
            bodies.add(body(ask(s, "/m/pair?c=" + code, null, "gzip")));   // the pairing page, opened with the code in its address
            HttpResponse<String> paired = s.post(s.door(), "/m/pair", null, "c=" + code);
            assertEquals(200, paired.statusCode());
            bodies.add(paired.body());
            String set = paired.headers().firstValue("Set-Cookie").orElseThrow(), cookie = set.substring(set.indexOf('=') + 1, set.indexOf(';'));
            bodies.add(s.post(s.door(), "/m/pair", null, "c=" + code).body());   // the same code again: refused
            for (String path : new String[]{"/m", "/m/pair?c=" + code, "/m/phone.js", "/m/app.webmanifest", "/m/state", "/m/conv", "/m/live", "/m/runs", "/m/read", "/m/read/I-0001-nothing"})
                bodies.add(body(ask(s, path, cookie, "gzip")));
            s.ask(cookie, "", "My pairing code was " + "x".repeat(4) + "; what is on the shelves?");
            for (String route : new String[]{"/m/new", "/m/seen"}) bodies.add(s.post(s.door(), route, cookie, "").body());
            bodies.add(body(ask(s, "/m/conv", cookie, "gzip")));
            for (String b : bodies) {
                assertFalse(b.contains(code), "the pairing code is in an answer: " + b.substring(0, Math.min(300, b.length())));
                assertFalse(b.contains(cookie), "the cookie's value is in an answer: " + b.substring(0, Math.min(300, b.length())));
            }
            assertTrue(bodies.size() > 12);
        }
    }

    private static HttpResponse<byte[]> ask(PhoneStandIns s, String path, String cookie, String acceptEncoding) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.door() + path)).GET();
        if (cookie != null) b.header("Cookie", PhoneDoor.COOKIE + "=" + cookie);
        if (acceptEncoding != null) b.header("Accept-Encoding", acceptEncoding);
        return s.c.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** An answer's text, inflated when it came compressed. */
    private static String body(HttpResponse<byte[]> r) throws Exception {
        byte[] b = r.headers().firstValue("Content-Encoding").orElse("").equals("gzip") ? new GZIPInputStream(new ByteArrayInputStream(r.body())).readAllBytes() : r.body();
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Every header but the ones compression changes and the date. */
    private static Map<String, List<String>> others(HttpResponse<?> r) {
        Map<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        r.headers().map().forEach((k, v) -> { if (!Arrays.asList("content-encoding", "content-length", "date").contains(k.toLowerCase())) m.put(k, v); });
        return m;
    }

    /** A conversation's HTML without the times it is written with. */
    private static String strip(byte[] b) { return new String(b, StandardCharsets.UTF_8).replaceAll("\\d+(\\.\\d+)? s\\b", "").replaceAll("\\d{1,2}:\\d{2}", ""); }
}
