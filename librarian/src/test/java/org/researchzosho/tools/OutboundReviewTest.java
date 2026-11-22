package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.GivenAddresses;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.drive.DriveClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request guard ({@link Outbound}) after a second reading of 0.8.1: what a reader of the code said of it, each stated as the
 * behaviour that should hold and tried. Stand-ins on the loopback only; every test that expects a request not to be made first sees
 * one arrive on the same listener.
 */
class OutboundReviewTest {
    static final ObjectMapper M = new ObjectMapper();

    @TempDir Path tmp;
    String realHome;

    @BeforeEach void up() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
    }

    @AfterEach void down() { Outbound.noStandIns(); System.setProperty("user.home", realHome); Config.invalidate(); }

    static JsonNode args(String json) { try { return M.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); } }

    /** A key and a certificate for {@code localhost}, made by the JDK's own keytool for this test alone: the server's context and the client's, which trusts that certificate and no other. */
    static SSLContext[] tls(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path store = dir.resolve("stand-in.p12");
        String pass = "p" + Long.toHexString(System.nanoTime());
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "standin", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", pass, "-keypass", pass).redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "the JDK's keytool made the stand-in's certificate: " + said);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) { ks.load(in, pass.toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(ks, pass.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(ks);
        SSLContext server = SSLContext.getInstance("TLS"); server.init(keys.getKeyManagers(), null, null);
        SSLContext client = SSLContext.getInstance("TLS"); client.init(null, trust.getTrustManagers(), null);
        return new SSLContext[]{server, client};
    }

    /**
     * A server that answers an https request by sending it on to plain http. The JDK's own client follows such a hop only when it was
     * built to follow always ({@code Redirect.ALWAYS}); built {@code NORMAL} it hands back the answer that sends on, and built
     * {@code NEVER} it follows nothing. While a model's call is carried out the guard follows the hops itself, so it has to do the same
     * for each setting: no more (a request that went out under TLS does not go on in the clear) and no less (a client built to follow
     * always still does). The control is the JDK's own client with no call, on the same two listeners.
     */
    @Test
    void aHopFromHttpsDownToHttpIsFollowedAsTheJdksOwnClientFollowsIt() throws Exception {
        SSLContext[] tls = tls(tmp.resolve("tls"));
        List<String> inTheClear = new CopyOnWriteArrayList<>();
        HttpServer plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plain.createContext("/", x -> { inTheClear.add(x.getRequestURI().toString()); byte[] b = "landed".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        plain.start();
        HttpsServer secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secure.setHttpsConfigurator(new HttpsConfigurator(tls[0]));
        secure.createContext("/", x -> {
            x.getResponseHeaders().add("Location", "http://127.0.0.1:" + plain.getAddress().getPort() + "/landed" + x.getRequestURI().getPath());
            x.sendResponseHeaders(302, -1); x.close();
        });
        secure.start();
        String n = Long.toHexString(System.nanoTime()), from = "https://localhost:" + secure.getAddress().getPort();
        try {
            Map<String, String> jdk = new LinkedHashMap<>(), guard = new LinkedHashMap<>(), guardLater = new LinkedHashMap<>(), listed = new LinkedHashMap<>();
            for (HttpClient.Redirect setting : HttpClient.Redirect.values()) {
                HttpClient http = Outbound.client(HttpClient.newBuilder().sslContext(tls[1]).followRedirects(setting).connectTimeout(Duration.ofSeconds(10)).build());
                // the JDK's own client, no call
                inTheClear.clear();
                HttpResponse<String> own = http.send(HttpRequest.newBuilder(URI.create(from + "/own-" + setting + "-" + n)).build(), HttpResponse.BodyHandlers.ofString());
                jdk.put(setting.name(), own.statusCode() + (inTheClear.isEmpty() ? ", not followed" : ", followed"));
                // in a call, an address that was given: the guard follows the hops
                String given = from + "/given-" + setting + "-" + n, givenLater = from + "/later-" + setting + "-" + n;
                GivenAddresses.received("The page: " + given + " and " + givenLater + " .");
                try (Outbound.Call c = Outbound.call("a_tool", args("{\"note\":\"ferry\"}"))) {
                    inTheClear.clear();
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(given)).build(), HttpResponse.BodyHandlers.ofString());
                    guard.put(setting.name(), r.statusCode() + (inTheClear.isEmpty() ? ", not followed" : ", followed"));
                    inTheClear.clear();
                    HttpResponse<String> later = http.sendAsync(HttpRequest.newBuilder(URI.create(givenLater)).build(), HttpResponse.BodyHandlers.ofString()).get(30, TimeUnit.SECONDS);
                    guardLater.put(setting.name(), later.statusCode() + (inTheClear.isEmpty() ? ", not followed" : ", followed"));
                }
                // in a call, a host of the program's list asked with the call's words
                Outbound.standIn("localhost:" + secure.getAddress().getPort());
                try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"ferry timetable\"}"))) {
                    inTheClear.clear();
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(from + "/search-" + setting + "-" + n + "?q=ferry+timetable")).build(), HttpResponse.BodyHandlers.ofString());
                    listed.put(setting.name(), r.statusCode() + (inTheClear.isEmpty() ? ", not followed" : ", followed"));
                }
                Outbound.noStandIns();
            }
            System.out.println("[review] A-621 a hop from https down to http, by the client's redirect setting: the JDK's own client " + jdk + "; the guard in a call, a given address " + guard + ", on the path that answers later " + guardLater + ", a listed host " + listed);
            assertEquals(Map.of("NEVER", "302, not followed", "NORMAL", "302, not followed", "ALWAYS", "200, followed"), jdk, "the JDK's own client, as its documentation says (the control, and the instrument: the hop arrives when it is followed)");
            assertEquals(jdk, guard, "in a call, a given address");
            assertEquals(jdk, guardLater, "in a call, on the path that answers later");
            assertEquals(jdk, listed, "in a call, a listed host");
        } finally { secure.stop(0); plain.stop(0); }
    }

    /**
     * A machine of the owner's settings, by a name of one word or by its numbers, is theirs with the port the setting names. The port a
     * scheme has by default is the same port written out or left off, in the setting and in the request alike; another port is
     * another service. The call's words here name each host, so that the settings alone decide (a machine of the settings that the
     * words do not name is asked at any port: the control at the end).
     */
    @Test
    void anOwnersHostWrittenWithItsDefaultPortOrAnotherIsTheirsAtThatPort() throws Exception {
        Config.set("RESEARCHZOSHO_DRIVE", "https://modelbox:443/v1");
        Config.set("RESEARCHZOSHO_SEARXNG", "http://searchbox");
        Config.set("RESEARCHZOSHO_EMBED", "http://192.0.2.44:6080/embed");
        Map<String, Boolean> asked = new LinkedHashMap<>();
        try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"are modelbox, searchbox and 192.0.2.44 answering\"}"))) {
            for (String a : List.of(
                    "https://modelbox:443/v1/chat/completions",   // as the program builds it from the setting (the control)
                    "https://modelbox/v1/chat/completions",       // the default port left off where the setting wrote it out
                    "http://searchbox/search?q=x",                // as the program builds it
                    "http://searchbox:80/search?q=x",             // the default port written out where the setting left it off
                    "http://192.0.2.44:6080/embed",               // another port, as set
                    "http://192.0.2.44/embed",                    // the same machine at the default port: another service
                    "http://192.0.2.44:6081/embed",               // and at a third port
                    "https://modelbox:8443/v1/chat/completions")) {
                boolean let;
                try { Outbound.check(URI.create(a)); let = true; } catch (Outbound.NotOpened e) { let = false; }
                asked.put(a, let);
            }
        }
        System.out.println("[review] A-422 an owner's host in the settings, asked while a call's words name it: " + asked);
        assertTrue(asked.get("https://modelbox:443/v1/chat/completions") && asked.get("http://searchbox/search?q=x") && asked.get("http://192.0.2.44:6080/embed"), "as the program builds each request from the setting (the control): " + asked);
        assertTrue(asked.get("https://modelbox/v1/chat/completions"), "a default port the setting wrote out, left off in the request");
        assertTrue(asked.get("http://searchbox:80/search?q=x"), "a default port the setting left off, written out in the request");
        assertTrue(!asked.get("http://192.0.2.44/embed") && !asked.get("http://192.0.2.44:6081/embed") && !asked.get("https://modelbox:8443/v1/chat/completions"), "another port than the setting's is another service: " + asked);
        // a machine of the settings that the call's words do not name is the owner's at whatever port the tool's own code asks
        try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"ferry timetable\"}"))) { Outbound.check(URI.create("http://192.0.2.44:6081/embed")); }
    }

    /**
     * A model server the owner chose that stands in no setting (given on a command line) is theirs because its client says so. It
     * is theirs while it is used: said at each request, it does not lapse while a run goes on; left unused, it stops being one of
     * the owner's hosts after {@code Outbound.OWNERS_FOR}, and is then asked as any host is. A model server that stands in the
     * settings is theirs by the settings, for as long as it stands there and no longer.
     */
    @Test
    void aModelServerTheOwnerStoppedUsingStopsBeingTheirs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> { x.getRequestBody().readAllBytes(); byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().add("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        server.start();
        long[] now = {System.currentTimeMillis()};
        LongSupplier real = Outbound.CLOCK;
        Outbound.CLOCK = () -> now[0];
        try {
            String n = Long.toHexString(System.nanoTime()), given = "oldbox" + n + ":8212", inUse = "127.0.0.1:" + server.getAddress().getPort();
            new DriveClient("http://" + given, "a-model");                 // as a command line's --drive makes one
            DriveClient used = new DriveClient("http://" + inUse, "a-model");
            Config.set("RESEARCHZOSHO_EMBED", "http://setbox" + n + ":8300");
            JsonNode names = args("{\"query\":\"is oldbox" + n + " or setbox" + n + " answering\"}");
            Map<String, Boolean> asked = new LinkedHashMap<>();
            try (Outbound.Call c = Outbound.call("search", names)) { asked.put("at once", let("http://" + given + "/v1/chat/completions")); }
            assertTrue(Outbound.ownersNow().contains(given) && Outbound.ownersNow().contains(inUse), "each client said its server (the control): " + Outbound.ownersNow());
            now[0] += Outbound.OWNERS_FOR - 60_000;
            try (Outbound.Call c = Outbound.call("search", names)) { asked.put("just within the time", let("http://" + given + "/v1/chat/completions")); }
            now[0] += 120_000;
            try (Outbound.Call c = Outbound.call("search", names)) { asked.put("after the time, unused", let("http://" + given + "/v1/chat/completions")); asked.put("a server of the settings, after the time", let("http://setbox" + n + ":8300/embed")); }
            boolean lapsed = !Outbound.ownersNow().contains(inUse);
            used.chat(M.createArrayNode().add(M.createObjectNode().put("role", "user").put("content", "ready?")), M.createArrayNode(), 16);
            boolean saidAgain = Outbound.ownersNow().contains(inUse);
            Config.set("RESEARCHZOSHO_EMBED", "http://otherbox" + n + ":8300");
            try (Outbound.Call c = Outbound.call("search", names)) { asked.put("a server taken out of the settings", let("http://setbox" + n + ":8300/embed")); }
            System.out.println("[review] D-146 a model server said by its client, asked while a call's words name it: " + asked + "; another one's said host lapsed unused: " + lapsed + ", and is said again when its client is used: " + saidAgain);
            assertTrue(asked.get("at once") && asked.get("just within the time"), "the owner's while it is in use (the control): " + asked);
            assertFalse(asked.get("after the time, unused"), "a server nobody used for the time is still one of the owner's hosts");
            assertTrue(asked.get("a server of the settings, after the time") && !asked.get("a server taken out of the settings"), "a server of the settings is theirs while it stands there: " + asked);
            assertTrue(lapsed && saidAgain, "a client says its server again at each request");
        } finally { Outbound.CLOCK = real; server.stop(0); }
    }

    /**
     * A call begins and ends on whatever its arguments hold: nothing, a number, lists in lists, a word of three hundred thousand
     * letters, escapes that are none, every mark. Beginning one reads the arguments in several ways (their words, their escapes,
     * the hosts they name), and none of that fails or takes long; ending one gives the thread back to the call it stood in. The
     * tool's own answer and the record of what was refused are written after it, so they depend on this.
     */
    @Test
    void aCallBeginsAndEndsOnWhateverItsArgumentsHold() throws Exception {
        List<String> odd = new ArrayList<>(List.of("null", "{}", "[]", "42", "\"\"", "{\"a\":null}", "{\"a\":[[[{\"b\":[1,2.5,true,null,\"x\"]}]]]}",
                "{\"q\":\"%zz %1 %4 &#xZZZZ; &#99999999999; &#x110000; \\\\u12 \\\\u00zz \\\\/ http:// https://[ http://%41/ ::: [::] @@ ..\"}", "{\"q\":\"\\ud83d\\ude00 \\u0000 \\ufffe ＨＴＴＰ：／／ｅｘａｍｐｌｅ．ｏｒｇ\"}"));
        odd.add("{\"q\":\"" + "a".repeat(300_000) + "\"}");
        odd.add("{\"q\":\"" + "a-".repeat(150_000) + "\"}");
        odd.add("{\"q\":\"" + "a.".repeat(150_000) + "\"}");
        odd.add("{\"q\":\"" + "%41".repeat(100_000) + "\"}");
        odd.add("{\"q\":\"" + "http://a/".repeat(30_000) + "\"}");
        List<String> failed = new ArrayList<>();
        long began = System.nanoTime();
        try (Outbound.Call outer = Outbound.call("outer", args("{\"q\":\"ferry\"}"))) {
            for (String a : odd) {
                try (Outbound.Call c = Outbound.call("a_tool", args(a))) { c.names("example.org"); c.spells("/a/long/path/that/is/not/there"); c.wrote("https://example.org/x"); }
                catch (RuntimeException | Error e) { failed.add((a.length() > 50 ? a.substring(0, 50) + "…" : a) + " → " + e); }
                assertEquals(outer, Outbound.current(), "the call it stood in has the thread back");
            }
            try (Outbound.Call none = Outbound.call(null, null)) { assertFalse(none.names("")); }
        }
        long ms = (System.nanoTime() - began) / 1_000_000;
        System.out.println("[review] D-2725 a call begun and ended on " + odd.size() + " kinds of arguments: failures " + failed.size() + " " + failed + ", " + ms + " ms in all");
        assertEquals(List.of(), failed);
        assertEquals(null, Outbound.current(), "and no call is left on the thread");
        assertTrue(ms < 30_000, "reading the arguments took " + ms + " ms");
    }

    /** The pattern that read a text's hosts until the second reading of 0.8.1: what {@code Outbound.dotted} must find, no more and no less. */
    static final Pattern DOTTED_AS_IT_WAS = Pattern.compile("(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?![a-z0-9-])");

    /**
     * The hosts a call's words name are read by hand now, and are the hosts the pattern found: tried on every text of up to eight
     * characters over the letters that matter (a letter, a digit, a dot, a hyphen, a space, a slash), on a hundred thousand longer
     * ones drawn by a fixed seed, and on the shapes a host hides in. A zero difference over these is the control that nothing the
     * guard knew as a named host is unknown to it now.
     */
    @Test
    void theHostsAWordsNameAreTheOnesThePatternFound() {
        char[] letters = {'a', 'b', '7', '.', '-', ' ', '/'};
        List<String> differ = new ArrayList<>();
        long tried = 0, hostsFound = 0;
        // every text of up to eight of these characters
        for (int length = 0; length <= 8; length++) {
            int[] at = new int[length];
            for (boolean more = true; more; ) {
                StringBuilder b = new StringBuilder();
                for (int i : at) b.append(letters[i]);
                tried++; hostsFound += compare(b.toString(), differ);
                more = false;
                for (int i = length - 1; i >= 0; i--) { if (++at[i] < letters.length) { more = true; break; } at[i] = 0; }
            }
        }
        Random r = new Random(20261121);
        char[] wider = "abcxyz0189..--  /:@_%é".toCharArray();
        for (int k = 0; k < 100_000; k++) {
            StringBuilder b = new StringBuilder();
            for (int i = r.nextInt(60); i > 0; i--) b.append(wider[r.nextInt(wider.length)]);
            tried++; hostsFound += compare(b.toString(), differ);
        }
        for (String shape : List.of("see collector.example.net/x?d=1 and www.example.org.", "a.b.c-", "-a.b.c", "a..b.c", "a.b..", "..a.b", "x.y-z.w", "x.-y.z.w", "x.y-.z.w", "one.two three.four", "1.2.3.4:8080", "https://[2001:db8::1]/a.b", "a.b.c.d.e.f.g.h", "a-.b", "a.b-", ".", "-", "a", "a.", ".a", "a.b"))
            { tried++; hostsFound += compare(shape, differ); }
        System.out.println("[review] the hosts a text names, read by hand and by the pattern as it was: " + tried + " texts, " + hostsFound + " hosts found by the pattern, differences " + differ.size() + " " + differ.stream().limit(5).toList());
        assertTrue(tried > 6_000_000 && hostsFound > 500_000, "the texts were tried and hold hosts (the control): " + tried + " / " + hostsFound);
        assertEquals(List.of(), differ.stream().limit(20).toList());
    }

    private static int compare(String text, List<String> differ) {
        List<String> byPattern = new ArrayList<>(), byHand = new ArrayList<>();
        Matcher m = DOTTED_AS_IT_WAS.matcher(text);
        while (m.find()) byPattern.add(m.group());
        Outbound.dotted(text, byHand::add);
        if (!byPattern.equals(byHand) && differ.size() < 50) differ.add("\"" + text + "\": the pattern " + byPattern + ", by hand " + byHand);
        return byPattern.size();
    }

    static boolean let(String address) { try { Outbound.check(URI.create(address)); return true; } catch (Outbound.NotOpened e) { return false; } }

    /**
     * This machine, in the spellings an address of it has. The guard's own rule for this machine ("at whatever port, when the call's
     * words do not name it") knows the spellings this program writes: localhost, 127.x.x.x, ::1. A model server or a search service
     * the owner set under any other spelling is theirs by the setting, with its port, and is asked during a call (ordinary use). The
     * same spelling at a port nobody set is held as any numbered host is: the stricter side, so no request goes where nobody chose.
     */
    @Test
    void thisMachineUnderAnotherSpellingIsAskedWhereTheOwnerSetItAndNowhereElse() throws Exception {
        List<String> spellings = List.of("[::1]", "[0:0:0:0:0:0:0:1]", "[::0:1]", "[0::1]", "[::ffff:127.0.0.1]", "[::127.0.0.1]", "127.0.0.1", "127.8.9.10", "localhost");
        Map<String, String> got = new LinkedHashMap<>();
        for (String host : spellings) {
            // with no setting that names it: only the guard's own rule for this machine speaks
            Config.set("RESEARCHZOSHO_SEARXNG", "http://searchbox.example.org");
            boolean unset, set;
            try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"ferry timetable\"}"))) {
                try { Outbound.check(URI.create("http://" + host + ":8900/search?q=ferry+timetable")); unset = true; } catch (Outbound.NotOpened e) { unset = false; }
            }
            // the owner's search service set under this spelling
            Config.set("RESEARCHZOSHO_SEARXNG", "http://" + host + ":8899");
            try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"ferry timetable\"}"))) {
                try { Outbound.check(URI.create("http://" + host + ":8899/search?q=ferry+timetable")); set = true; } catch (Outbound.NotOpened e) { set = false; }
            }
            got.put(host, (set ? "asked" : "REFUSED") + " where set, " + (unset ? "asked" : "refused") + " where nobody set it");
        }
        System.out.println("[review] A-181 this machine by its spellings, a search service the owner set there: " + got);
        for (Map.Entry<String, String> e : got.entrySet()) assertTrue(e.getValue().startsWith("asked where set"), "the owner's own service is refused under the spelling " + e.getKey());
        for (String own : List.of("[::1]", "[0:0:0:0:0:0:0:1]", "127.0.0.1", "127.8.9.10", "localhost")) assertTrue(got.get(own).endsWith("asked where nobody set it"), "this machine as the program writes it, at a port its own code chose: " + own);
        for (String other : List.of("[::0:1]", "[0::1]", "[::ffff:127.0.0.1]", "[::127.0.0.1]")) assertTrue(got.get(other).endsWith("refused where nobody set it"), "a spelling the program never writes is held as a numbered host is: " + other);
    }
}
