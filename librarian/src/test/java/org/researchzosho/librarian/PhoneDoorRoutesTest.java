package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone door answers its own table and nothing else (2026-11-25). Every route the library has, taken from the code (the pages' list
 * and each field's pages, the protocol's routes, MCP, A2A, the account service's own calls, the static files and scripts, the icon, the
 * language switch, the found pictures), each with six methods, unpaired and paired, and in the usual disguises, is asked of the door:
 * each answer is one of the door's routes or the plain 404. The same paths asked of the library's own port are answered there, so a walk
 * that asked nothing, or asked the wrong port, cannot pass.
 */
class PhoneDoorRoutesTest {
    static final String NOT_FOUND = "Not found.\n";

    /** Every route of the library, from the code itself. */
    static Set<String> everyRoute() {
        Set<String> all = new LinkedHashSet<>(Pages.PAGE_PATHS);
        for (String p : Pages.PAGE_PREFIXES) all.add(p + "I-0001-a-report");
        for (Profile p : Profiles.known()) all.addAll(p.pagePaths());
        for (String r : LibrarianDaemon.v1Routes()) all.add("/v1/" + r.replace("{id}", "J-0001"));
        all.addAll(List.of("/v1/status", "/v1/jobs", "/v1/resources", "/v1/resources/templates", "/v1/resource", "/rpc", "/healthz", A2a.CARD, A2a.PATH,
                Held.STILL_YOURS, Held.NIGHT, "/favicon.ico", "/favicon.png", "/static/mermaid-11.17.2.min.js", "/static/README.md", Pages.FOUND_PATH + "?u=https://example.org/a.png"));
        for (String s : Pages.SCRIPTS.keySet()) all.add("/static/rz-" + s + ".en.js");
        return all;
    }

    @Test
    void everyRouteOfTheLibraryIsNotFoundAtThePhoneDoorAndFoundAtItsOwnPort(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            Set<String> routes = everyRoute();
            assertTrue(routes.size() > 90, "the walk takes the library's routes from the code: " + routes.size());
            List<String> wrong = new ArrayList<>();
            int asked = 0;
            for (String path : routes) {
                for (String method : List.of("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS")) {
                    for (String who : new String[]{null, cookie}) {
                        String raw = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
                        if (PhoneDoor.routeOf(method, raw) != null) continue;   // the door's own: asked below
                        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.door() + path)).method(method, method.equals("GET") || method.equals("HEAD") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString("{}"));
                        b.header(PhoneDoor.HEADER, "1").header("Sec-Fetch-Site", "same-origin");
                        if (who != null) b.header("Cookie", PhoneDoor.COOKIE + "=" + who);
                        HttpResponse<String> r = s.c.send(b.build(), HttpResponse.BodyHandlers.ofString());
                        asked++;
                        boolean plain = r.statusCode() == 404 && (method.equals("HEAD") ? r.body().isEmpty() : r.body().equals(NOT_FOUND));
                        if (!plain) wrong.add(method + " " + path + (who == null ? "" : " (paired)") + " → " + r.statusCode() + " " + r.body().substring(0, Math.min(120, r.body().length())));
                    }
                }
            }
            assertTrue(wrong.isEmpty(), wrong.size() + " route(s) the door answered:\n" + String.join("\n", wrong));
            assertTrue(asked > 1000, "asked " + asked);
            // the control: the same library answers them on its own port
            int answered = 0;
            for (String path : routes) { HttpResponse<String> r = s.get(s.main(), path, null); if (r.statusCode() != 404 || !r.body().equals(NOT_FOUND)) answered++; }
            assertTrue(answered > routes.size() * 9 / 10, "the library's own port answers its routes: " + answered + " of " + routes.size());
            assertEquals(200, s.get(s.main(), "/healthz", null).statusCode());
            assertEquals(200, s.get(s.main(), "/v1/status", null).statusCode());
            // and the door's own table answers at the door
            assertEquals(200, s.get(s.door(), "/m", null).statusCode());
            assertEquals(200, s.get(s.door(), "/m/phone.js", null).statusCode());
            HttpResponse<String> manifest = s.get(s.door(), "/m/app.webmanifest", null);   // anyone: a browser asks it without the cookie
            assertEquals(200, manifest.statusCode());
            assertEquals("application/manifest+json", manifest.headers().firstValue("Content-Type").orElse(""));
            assertTrue(manifest.headers().firstValue("Content-Security-Policy").orElse("").contains("manifest-src 'self'"));
            for (String icon : PhonePage.ICONS.keySet()) {
                HttpResponse<byte[]> r = s.c.send(HttpRequest.newBuilder(URI.create(s.door() + icon)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertTrue(r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").equals("image/png") && r.body().length > 1000, icon);
            }
            assertEquals(404, s.get(s.main(), "/m/app.webmanifest", null).statusCode(), "the manifest is the door's alone");
            assertTrue(s.get(s.door(), "/m/state", cookie).body().contains("\"paired\":true"));
            assertEquals(403, s.get(s.door(), "/m/runs", null).statusCode(), "a paired route without a pairing");
        }
    }

    @Test
    void disguisesOfTheLibrarysAddressesAndClimbsOutOfTheTwoNamedRoutesAreThePlainNotFound(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            // a picture outside the library's pictures, and the files a climb would aim at: none may come back
            Path canary = s.store.root().resolve("catalog").resolve("canary.png");
            Files.write(canary, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 'C', 'A', 'N', 'A', 'R', 'Y'});
            Config.set("RESEARCHZOSHO_CANARY_SETTING", "canary-value-1234");
            List<String> paths = List.of("//settings", "/m/../settings", "/m/%2e%2e/settings", "/M", "/m/", "/m;x", "/m/static/../../v1/status", "/m/phone.js/../../settings", "/./settings", "/m//state",
                    "/m/images/../canary.png", "/m/images/..%2fcanary.png", "/m/images/%2e%2e/canary.png", "/m/images/%2e%2e%2fcatalog%2fcanary.png", "/m/images/..\\canary.png",
                    "/m/images//etc/passwd", "/m/images/%2Fetc%2Fpasswd", "/m/images/canary.png%00.png", "/m/images/C:%5Cwindows",
                    "/m/read/../config", "/m/read/%2e%2e/config", "/m/read/..%2fphones.json", "/m/read/I-0001-a/../../config", "/m/read/phones.json", "/m/read/config",
                    "/m/read/I-0001-x%00", "/m/read/%2Fetc%2Fpasswd", "/m/read/F-0001-..",
                    "/m/app.webmanifest/../../settings", "/m/app.webmanifest/", "/m/app.webmanifest%00", "/m/icon-512.png/../../v1/status", "/m/icon-../config", "/m/manifest.json", "/m/h/..%2fsettings", "/m/h/x/../../settings", "/m/home");
            List<String> wrong = new ArrayList<>();
            for (String p : paths) {
                for (String who : new String[]{null, cookie}) {
                    String r = raw(s.d.phoneDoorPort(), "GET " + p + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" + (who == null ? "" : "Cookie: " + PhoneDoor.COOKIE + "=" + who + "\r\n") + "\r\n");
                    // the door's plain 404, or the JDK's own refusal of an address it cannot read (//…, a backslash), made before any handler
                    // runs. The JDK's words differ by version: 400 "Bad request URI" where this was written; Temurin 25.0.2 on a Mac (2026-11-26)
                    // answered a backslash with 400 "URISyntaxException thrown", and //settings, read as a host with no path, with 404 "No context
                    // found for request". Any of them is a refusal that served nothing; the checks below still look for the library's own bytes.
                    String end = r.stripTrailing();
                    boolean plain = (r.startsWith("HTTP/1.1 404") && (r.endsWith("\r\n\r\n" + NOT_FOUND) || end.endsWith("</h1>No context found for request")))
                            || (r.startsWith("HTTP/1.1 400") && (end.endsWith("Bad request URI") || end.endsWith("</h1>URISyntaxException thrown")));
                    if (!plain || r.contains("CANARY") || r.contains("canary-value") || r.contains("root:")) wrong.add(p + (who == null ? "" : " (paired)") + " → " + r.lines().findFirst().orElse("") + " … " + r.substring(Math.max(0, r.length() - 80)));
                }
            }
            // and with a NUL and a backslash written as they are, which no HTTP client sends
            for (String p : new String[]{"/m/images/a\u0000b", "/m/read/I-0001\\..\\config"}) {
                String r = raw(s.d.phoneDoorPort(), "GET " + p + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nCookie: " + PhoneDoor.COOKIE + "=" + cookie + "\r\n\r\n");
                if (!(r.startsWith("HTTP/1.1 404") || r.startsWith("HTTP/1.1 400")) || r.contains("CANARY") || r.contains("canary-value")) wrong.add(p.replace("\u0000", "\\0") + " → " + r.lines().findFirst().orElse(""));
            }
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
            // the control: a picture the library keeps comes through the door, and a report by its id
            Images.Kept k = Images.keepGiven(s.store, Files.readAllBytes(canary), "image/png", "kept.png", "", "");
            String name = Path.of(k.file()).getFileName().toString();
            HttpResponse<byte[]> pic = s.c.send(HttpRequest.newBuilder(URI.create(s.door() + "/m/images/" + name)).header("Cookie", PhoneDoor.COOKIE + "=" + cookie).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, pic.statusCode(), name);
            assertTrue(new String(pic.body(), StandardCharsets.ISO_8859_1).contains("CANARY"), "the kept picture itself comes through");
            assertEquals(403, s.get(s.door(), "/m/images/" + name, null).statusCode(), "and only for a paired phone");
        }
    }

    /** One request written as it is, on a socket of its own; the whole answer. */
    static String raw(int port, String request) throws Exception {
        try (Socket so = new Socket("127.0.0.1", port)) {
            so.setSoTimeout(5000);
            OutputStream o = so.getOutputStream(); o.write(request.getBytes(StandardCharsets.ISO_8859_1)); o.flush();
            InputStream in = so.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theDoorsRoutesAreTheTableAndAnUnknownMethodOrSpellingIsNone() {
        for (String[] ok : new String[][]{{"GET", "/m"}, {"GET", "/m/pair"}, {"GET", "/m/phone.js"}, {"GET", "/m/icon.png"}, {"GET", "/m/state"}, {"POST", "/m/pair"},
                {"GET", "/m/conv"}, {"POST", "/m/ask"}, {"GET", "/m/live"}, {"POST", "/m/stop"}, {"POST", "/m/new"}, {"GET", "/m/runs"}, {"POST", "/m/runs/stop"}, {"POST", "/m/seen"},
                {"GET", "/m/read"}, {"GET", "/m/read/I-0001-a-report"}, {"GET", "/m/images/a.png"}, {"GET", "/m/h/abcdefghijklmnopqrstuv"}, {"GET", "/m/h/short"},
                {"GET", "/m/app.webmanifest"}, {"GET", "/m/icon-192.png"}, {"GET", "/m/icon-512.png"}, {"GET", "/m/icon-512-maskable.png"}})
            assertTrue(PhoneDoor.routeOf(ok[0], ok[1]) != null, ok[0] + " " + ok[1]);
        for (String[] no : new String[][]{{"HEAD", "/m"}, {"PUT", "/m/ask"}, {"GET", "/m/ask"}, {"POST", "/m/state"}, {"POST", "/m/home"}, {"GET", "/m/h/"}, {"GET", "/m/h/a/b"}, {"GET", "/m/h/a.b"}, {"POST", "/m/h/abcdefghijklmnopqrstuv"}, {"GET", "/m/read/../x"}, {"GET", "/m/images/.."},
                {"GET", "/m/images/a..b"}, {"GET", "/m/images/%2e"}, {"GET", "/m/read/I-0001-a/b"}, {"GET", "/static/rz-chat.en.js"}, {"GET", "/"}, {"GET", "/settings"},
                {"POST", "/m/app.webmanifest"}, {"GET", "/m/manifest.json"}, {"GET", "/m/icon-1024.png"}, {"GET", "/m/icon-192.png/"}})
            assertFalse(PhoneDoor.routeOf(no[0], no[1]) != null, no[0] + " " + no[1]);
        for (String icon : PhonePage.ICONS.keySet()) assertEquals("icon", PhoneDoor.routeOf("GET", icon), "every icon the page names is a door route: " + icon);
        assertEquals(18, PhoneDoor.ROUTES.size(), "eighteen named routes; the page answers at two addresses, the icons at four");
        assertEquals("back", PhoneDoor.routeOf("GET", "/m/h/abcdefghijklmnopqrstuv"), "an old home-screen address is only a way back to /m");
        assertFalse(PhoneDoor.ROUTES.containsKey("home"), "nothing makes a home-screen code");
    }
}
