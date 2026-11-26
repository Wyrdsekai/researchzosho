package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code researchzosho phone} (2026-11-25), with a stand-in for tailscale whose serve config the test writes: on puts the door on port 8443
 * of the machine's address, over the tailnet or through Funnel; another program's handler on 8443 is said and nothing is changed; off takes
 * the phone's own handler alone and leaves another program's, and share's on 443; share and phone side by side, each on its own port;
 * pair, list, cap and remove as the person reads them.
 */
class PhoneCliTest {
    static final String RUNNING = "{\"BackendState\":\"Running\",\"Self\":{\"DNSName\":\"homebox.tail1234.ts.net.\"}}";

    String home; Share.Runner runner; Function<String, String> which; BooleanSupplier service;
    final List<String> ran = new ArrayList<>();
    /** The serve config the stand-in reports; each serve or funnel command the test expects changes it by hand below. */
    String config = "{}";

    @BeforeEach void standIns(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        runner = Share.runner; which = Share.which; service = Share.serviceRunning;
        Share.which = p -> p.equals("tailscale") ? "/usr/bin/tailscale" : null;
        Share.serviceRunning = () -> true;
        Share.runner = (cmd, t) -> {
            String line = String.join(" ", cmd); ran.add(line);
            if (line.endsWith("status --json") && !line.contains("serve")) return new Video.Result(0, RUNNING);
            if (line.endsWith("serve status --json")) return new Video.Result(0, config);
            if (line.contains("--bg") || line.endsWith(" off")) return new Video.Result(0, "ok");
            return new Video.Result(1, "unexpected: " + line);
        };
    }

    @AfterEach void back() { Share.runner = runner; Share.which = which; Share.serviceRunning = service; System.setProperty("user.home", home); Config.invalidate(); }

    static LibraryStore lib(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    Share.Outcome phone(LibraryStore store, String... args) throws Exception { return PhoneCli.run(store, List.of(args), new PrintStream(new ByteArrayOutputStream())); }

    static String web(String host, String path, String proxy) { return "\"" + host + "\":{\"Handlers\":{\"" + path + "\":{\"Proxy\":\"" + proxy + "\"}}}"; }

    @Test
    void onPutsTheDoorOnPort8443OverTheTailnetOrPublicAndAnotherProgramsHandlerThereIsSaid(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Share.Outcome o = phone(store, "on");
        assertEquals(0, o.code(), o.text());
        assertTrue(ran.contains("/usr/bin/tailscale serve --bg --https=8443 4652"), ran.toString());
        assertEquals("tailnet", PhoneDoor.kind());
        assertEquals("https://homebox.tail1234.ts.net:8443", PhoneDoor.address());
        assertTrue(o.text().contains("on your tailnet at https://homebox.tail1234.ts.net:8443") && o.text().contains("researchzosho phone pair"), o.text());
        ran.clear();
        o = phone(store, "on", "--public");
        assertTrue(ran.contains("/usr/bin/tailscale funnel --bg --https=8443 4652") && o.text().contains("public at https://homebox.tail1234.ts.net:8443"), ran + " " + o.text());
        assertEquals("public", PhoneDoor.kind());
        // another program on 8443: said, nothing run, nothing set
        Config.set(PhoneDoor.KIND, "off");
        config = "{\"TCP\":{\"8443\":{\"HTTPS\":true}},\"Web\":{" + web("homebox.tail1234.ts.net:8443", "/", "http://127.0.0.1:3000") + "}}";
        ran.clear();
        o = phone(store, "on");
        assertEquals(1, o.code());
        assertTrue(o.text().contains("already serves something on port 8443") && o.text().contains("https://homebox.tail1234.ts.net:8443/ → http://127.0.0.1:3000") && o.text().contains("Nothing was changed"), o.text());
        assertTrue(ran.stream().noneMatch(l -> l.contains("--bg")), ran.toString());
        assertEquals("off", PhoneDoor.kind());
        // another program on another path of 8443 is another program on 8443 too
        config = "{\"Web\":{" + web("homebox.tail1234.ts.net:8443", "/other", "http://127.0.0.1:3000") + "}}";
        assertEquals(1, phone(store, "on").code());
        // what other programs serve on other ports is theirs: share on 443, another on 10000
        config = "{\"TCP\":{\"443\":{\"HTTPS\":true},\"10000\":{\"HTTPS\":true}},\"Web\":{" + web("homebox.tail1234.ts.net:443", "/", "http://127.0.0.1:4651") + "," + web("homebox.tail1234.ts.net:10000", "/", "http://127.0.0.1:9000") + "}}";
        assertEquals(0, phone(store, "on").code());
    }

    @Test
    void offTakesThePhonesOwnHandlerAloneAndShareAndPhoneStandSideBySide(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        assertEquals(0, phone(store, "on", "--public").code());
        // the phone's handler on 8443, share's on 443, another program's on a path of 443
        config = "{\"TCP\":{\"443\":{\"HTTPS\":true},\"8443\":{\"HTTPS\":true}},\"Web\":{\"homebox.tail1234.ts.net:443\":{\"Handlers\":{\"/\":{\"Proxy\":\"http://127.0.0.1:4651\"},\"/other\":{\"Proxy\":\"http://127.0.0.1:3000\"}}}," + web("homebox.tail1234.ts.net:8443", "/", "http://127.0.0.1:4652")
                + "},\"AllowFunnel\":{\"homebox.tail1234.ts.net:443\":true,\"homebox.tail1234.ts.net:8443\":true}}";
        ran.clear();
        Share.Outcome o = phone(store, "off");
        assertEquals(0, o.code(), o.text());
        assertEquals(List.of("/usr/bin/tailscale serve status --json", "/usr/bin/tailscale serve --https=8443 --set-path=/ off"), ran, "the phone's own mount on its own port, and nothing on 443");
        assertEquals("off", PhoneDoor.kind());
        assertTrue(o.text().contains("The paired phones stay paired"), o.text());
        // a handler on 8443 that is not the door's (someone changed it since): nothing taken off
        Config.set(PhoneDoor.KIND, "public");
        config = "{\"Web\":{" + web("homebox.tail1234.ts.net:8443", "/", "http://127.0.0.1:3000") + "}}";
        ran.clear();
        assertTrue(phone(store, "off").text().contains("does not serve the phone door on port 8443 now, so nothing was taken off"));
        assertTrue(ran.stream().noneMatch(l -> l.endsWith(" off")), ran.toString());
        // share beside the phone: share on 443 as always, the phone's address unchanged
        Config.set(PhoneDoor.KIND, "public"); Config.set(PhoneDoor.ADDRESS, "https://homebox.tail1234.ts.net:8443");
        config = "{\"Web\":{" + web("homebox.tail1234.ts.net:8443", "/", "http://127.0.0.1:4652") + "}}";
        Share.askOwnerAccount = false;
        try {
            ran.clear();
            Share.Outcome sh = Share.share(store, List.of("--tailscale"), new PrintStream(new ByteArrayOutputStream()));
            assertEquals(0, sh.code(), sh.text());
            assertTrue(ran.contains("/usr/bin/tailscale funnel --bg 4651"), ran.toString());
            assertEquals("https://homebox.tail1234.ts.net:8443", PhoneDoor.address(), "share on or off never moves the phone's address");
            assertEquals("public", PhoneDoor.kind());
            // share off: its own mount on 443, and the phone's handler on 8443 stays
            config = "{\"Web\":{" + web("homebox.tail1234.ts.net:443", "/", "http://127.0.0.1:4651") + "," + web("homebox.tail1234.ts.net:8443", "/", "http://127.0.0.1:4652") + "}}";
            ran.clear();
            Share.share(store, List.of("off"), new PrintStream(new ByteArrayOutputStream()));
            assertTrue(ran.contains("/usr/bin/tailscale serve --https=443 --set-path=/ off") && ran.stream().noneMatch(l -> l.contains("8443")), ran.toString());
            // another program's handler on 443: share says it and changes nothing
            Config.set(Share.SHARE, "off");
            config = "{\"Web\":{" + web("homebox.tail1234.ts.net:443", "/other", "http://127.0.0.1:3000") + "}}";
            ran.clear();
            Share.Outcome refused = Share.share(store, List.of("--tailscale"), new PrintStream(new ByteArrayOutputStream()));
            assertEquals(1, refused.code(), refused.text());
            assertTrue(refused.text().contains("already serves something on port 443") && ran.stream().noneMatch(l -> l.contains("--bg")), refused.text() + ran);
        } finally { Share.askOwnerAccount = true; }
    }

    @Test
    void pairShowsTheCodeAndTheLinkAndListCapRemoveSayWhatTheyDid(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Share.Outcome off = phone(store, "pair", "--no-wait");
        assertEquals(1, off.code());
        assertTrue(off.text().contains("The phone door is off") && off.text().contains("researchzosho phone on"), off.text());
        assertTrue(Phones.read().codes().isEmpty(), "no code made while nobody could reach it");
        phone(store, "on");
        Share.Outcome o = phone(store, "pair", "pixel", "--no-wait");
        assertEquals(0, o.code(), o.text());
        assertTrue(o.text().contains("https://homebox.tail1234.ts.net:8443/m/pair?c=") && o.text().contains("█") && o.text().contains("works once, for 10 minutes") && o.text().contains("cap on paid models: $10.00 a month"), o.text());
        String code = o.text().replaceAll("(?s).*/m/pair\\?c=([A-Za-z0-9_-]{22}).*", "$1");
        assertEquals("", Phones.usedBy(code));
        Phones.Paired p = Phones.redeem(code);
        String list = phone(store, "list").text();
        assertTrue(list.contains("pixel  paired ") && list.contains("1 browser") && list.contains("cap $10.00 a month"), list);
        assertTrue(phone(store, "status").text().contains("phone: on your tailnet at https://homebox.tail1234.ts.net:8443") && phone(store, "status").text().contains("paired phones: 1"));
        assertTrue(phone(store, "remove", "pixel").text().startsWith("Removed: pixel."));
        assertEquals(null, Phones.bySecret(p.secret()), "out at once");
        assertTrue(phone(store, "remove", "pixel").text().contains("No phone is called pixel"));
        assertTrue(phone(store, "list").text().contains("No phone is paired"));
        assertEquals(2, phone(store, "sideways").code());
        // the QR code the terminal shows is the link itself, module for module
        boolean[][] m = QrCode.modules("https://homebox.tail1234.ts.net:8443/m/pair?c=" + code, -1);
        String qr = PhoneCli.terminalQr("https://homebox.tail1234.ts.net:8443/m/pair?c=" + code);
        assertEquals((m.length + 4 + 1) / 2, qr.lines().count(), "two rows of modules a line, with the quiet zone");
        assertFalse(qr.isBlank());
    }

    @Test
    void anOwnRelayNeedsItsAddressAndSaysWhichHeaderNamesTheSender(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        assertEquals(2, phone(store, "on", "--own-relay").code());
        assertEquals(2, phone(store, "on", "--own-relay", "--address", "http://plain.example.org").code(), "https only");
        Share.Outcome o = phone(store, "on", "--own-relay", "--address", "https://phone.example.org/");
        assertEquals(0, o.code(), o.text());
        assertEquals("own", PhoneDoor.kind()); assertEquals("https://phone.example.org", PhoneDoor.address());
        assertTrue(o.text().contains("CF-Connecting-IP"), o.text());
        assertTrue(ran.isEmpty(), "no relay is started for the person: " + ran);
        o = phone(store, "on", "--own-relay", "--address", "https://phone.example.org", "--sender", "X-Real-IP");
        assertTrue(o.text().contains("read from X-Real-IP"));
        assertEquals("X-Real-IP", Config.get(PhoneDoor.SENDER));
    }
}
