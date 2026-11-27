package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cap a newly paired phone gets when its pairing names none (2026-11-26): {@code researchzosho phone cap --default} sets it and says
 * it; a pairing from the command and from the Settings page's form takes it, and {@code --cap} at pairing wins; the phones already paired,
 * and a code already shown, keep theirs; {@code phone status}, {@code phone list} and the Settings page say it. It lives in phones.json
 * beside the phones (0600): a file 0.8.3 wrote reads as $10 and keeps its phones, and the settings registry, the config file and the chat's
 * tools hold nothing of it.
 */
class PhoneCapDefaultTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String ADDRESS = "https://homebox.tail1234.ts.net:8443";
    static final Pattern CODE = Pattern.compile("/m/pair\\?c=([A-Za-z0-9_-]{22})");

    @TempDir Path tmp;
    String home; Share.Runner runner; Function<String, String> which; BooleanSupplier service;
    LibraryStore store;

    @BeforeEach void scratch() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        QuietHome.quiet(tmp.resolve("home"));
        runner = Share.runner; which = Share.which; service = Share.serviceRunning;
        Share.which = p -> null; Share.serviceRunning = () -> true;
        Share.runner = (cmd, t) -> new Video.Result(1, "no machine tool runs in this test: " + String.join(" ", cmd));
        Config.set(PhoneDoor.KIND, "tailnet"); Config.set(PhoneDoor.ADDRESS, ADDRESS);   // the door as phone on leaves it, without Tailscale
        store = new LibraryStore(tmp.resolve("lib")); store.init();
    }

    @AfterEach void back() { Share.runner = runner; Share.which = which; Share.serviceRunning = service; System.setProperty("user.home", home); Config.invalidate(); }

    Share.Outcome phone(String... args) throws Exception { return PhoneCli.run(store, List.of(args), new PrintStream(new ByteArrayOutputStream())); }

    static String code(Share.Outcome o) { Matcher m = CODE.matcher(o.text()); assertTrue(m.find(), o.text()); return m.group(1); }

    /** The phone a pairing's code makes, as if its Pair button were pressed. */
    static Phones.Phone redeemed(Share.Outcome pairing) throws Exception { assertEquals(0, pairing.code(), pairing.text()); return Phones.redeem(code(pairing)).phone(); }

    static Map<String, Double> caps() throws Exception { Map<String, Double> m = new LinkedHashMap<>(); for (Phones.Phone p : Phones.list()) m.put(p.name(), p.cap()); return m; }

    static Set<String> fields(JsonNode o) { Set<String> s = new TreeSet<>(); o.fieldNames().forEachRemaining(s::add); return s; }

    @Test
    void aPairingThatNamesNoCapGetsTheDefaultAndCapAtPairingWins() throws Exception {
        Share.Outcome first = phone("pair", "first", "--no-wait");
        assertTrue(first.text().contains("cap on paid models: $10.00 a month"), first.text());
        assertEquals(10.0, redeemed(first).cap(), "nobody set a default: ten dollars, as before");
        Share.Outcome set = phone("cap", "--default", "none");
        assertEquals(0, set.code(), set.text());
        assertTrue(set.text().contains("no limit") && set.text().contains("Phones already paired keep the cap they have") && set.text().contains("researchzosho phone cap <name>"), set.text());
        Share.Outcome open = phone("pair", "open", "--no-wait");
        assertTrue(open.text().contains("cap on paid models: no limit"), "the pairing says the cap the phone gets: " + open.text());
        assertEquals(Phones.NO_LIMIT, redeemed(open).cap());
        Share.Outcome five = phone("pair", "five", "--cap", "5", "--no-wait");
        assertTrue(five.text().contains("cap on paid models: $5.00 a month"), five.text());
        assertEquals(5.0, redeemed(five).cap(), "--cap at pairing wins over the default");
        assertEquals(0.0, redeemed(phone("pair", "zero", "--cap", "0", "--no-wait")).cap(), "and so does --cap 0");
        assertEquals(0, phone("cap", "--default", "0").code());
        Share.Outcome none = phone("pair", "no paid", "--no-wait");
        assertTrue(none.text().contains("cap on paid models: no paid model"), none.text());
        assertEquals(0.0, redeemed(none).cap());
        assertEquals(0, phone("cap", "--default", "$2.50").code());
        assertEquals(2.5, redeemed(phone("pair", "some", "--no-wait")).cap());
        assertEquals(Phones.NO_LIMIT, redeemed(phone("pair", "own", "--cap", "none", "--no-wait")).cap(), "--cap none wins over a default of $2.50");
    }

    @Test
    void changingTheDefaultLeavesThePairedPhonesAndACodeAlreadyShownAsTheyWere() throws Exception {
        redeemed(phone("pair", "ten", "--no-wait"));
        redeemed(phone("pair", "twenty", "--cap", "20", "--no-wait"));
        redeemed(phone("pair", "open", "--cap", "none", "--no-wait"));
        Share.Outcome shown = phone("pair", "waiting", "--no-wait");
        assertTrue(shown.text().contains("$10.00 a month"), shown.text());
        Map<String, Double> before = caps();
        assertEquals(Map.of("ten", 10.0, "twenty", 20.0, "open", Phones.NO_LIMIT), before);
        assertEquals(0, phone("cap", "--default", "0").code());
        assertEquals(before, caps(), "the phones already paired keep theirs");
        assertEquals(10.0, Phones.redeem(code(shown)).phone().cap(), "a code already shown pairs at the cap it said");
        assertEquals(0, phone("cap", "--default", "none").code());
        Map<String, Double> now = caps();
        assertEquals(Map.of("ten", 10.0, "twenty", 20.0, "open", Phones.NO_LIMIT, "waiting", 10.0), now);
        assertEquals(0, phone("cap", "ten", "3").code());
        assertEquals(3.0, caps().get("ten"), "one phone's own cap is that phone's alone");
        assertTrue(phone("cap", "--default").text().contains(": no limit ("), "and the default stays where it was set");
    }

    @Test
    void theDefaultsWordsAndTheBareFlagSaysIt() throws Exception {
        Share.Outcome said = phone("cap", "--default");
        assertEquals(0, said.code(), said.text());
        assertTrue(said.text().contains("Cap on paid models for a newly paired phone: $10.00 a month (researchzosho phone cap --default changes it).")
                && said.text().contains("Phones already paired keep the cap they have"), said.text());
        for (String w : List.of("none", "unlimited", "-1", "NONE")) {
            assertEquals(0, phone("cap", "--default", w).code(), w);
            assertTrue(phone("cap", "--default").text().contains("newly paired phone: no limit ("), w);
            assertEquals(0, phone("cap", "--default", "10").code());
            assertTrue(phone("cap", "--default").text().contains("newly paired phone: $10.00 a month ("), "and back to a figure after " + w);
        }
        assertEquals(0, phone("cap", "--default", "0").code());
        assertTrue(phone("cap", "--default").text().contains("newly paired phone: no paid model ("));
        Share.Outcome twelve = phone("cap", "--default", "12");
        assertTrue(twelve.text().contains("$12.00 a month") && !twelve.text().contains("refused or stopped"), twelve.text());
        assertTrue(phone("cap", "--default", "none").text().contains("Nothing from such a phone is refused or stopped for spending"), "no limit says what it means");
        assertEquals(0, phone("cap", "--default", "12").code());
        byte[] file = Files.readAllBytes(Phones.file());
        for (String bad : List.of("lots", "-5", "1e3", "ten", "12.345")) {
            Share.Outcome o = phone("cap", "--default", bad);
            assertEquals(2, o.code(), bad + ": " + o.text());
            assertTrue(o.text().contains("dollars a month") && o.text().contains("none"), o.text());
        }
        assertEquals(2, phone("cap", "--default", "5", "6").code(), "one value at most");
        assertArrayEquals(file, Files.readAllBytes(Phones.file()), "a wrong word changes nothing");
        assertTrue(phone("cap", "--default").text().contains("newly paired phone: $12.00 a month ("));
    }

    @Test
    void aFileOf083ReadsAsTenDollarsKeepsItsPhonesThroughARewriteAndStays0600() throws Exception {
        Files.createDirectories(Phones.file().getParent());
        long until = System.currentTimeMillis() + 5 * 60_000L;
        String key = "a".repeat(64), waiting = "b".repeat(64);
        // as 0.8.3 writes it (Phones.write at 96c48fe1): phones and codes, nothing else
        String old = "{\n  \"phones\" : [ {\n    \"id\" : \"p0a1b2c3d\",\n    \"name\" : \"pixel\",\n    \"paired\" : \"2026-11-25T10:00:00Z\",\n    \"used\" : \"2026-11-25T10:00:00Z\",\n"
                + "    \"seen\" : \"\",\n    \"cap\" : 20.0,\n    \"keys\" : [ {\n      \"hash\" : \"" + key + "\",\n      \"since\" : \"2026-11-25T10:00:00Z\",\n      \"how\" : \"qr\"\n    } ]\n  } ],\n"
                + "  \"codes\" : [ {\n    \"hash\" : \"" + waiting + "\",\n    \"until\" : " + until + ",\n    \"name\" : \"tablet\",\n    \"phone\" : \"\",\n    \"used\" : \"\",\n    \"cap\" : 10.0\n  } ]\n}\n";
        Files.writeString(Phones.file(), old, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(Phones.file(), PosixFilePermissions.fromString("rw-r--r--"));
        assertTrue(phone("cap", "--default").text().contains("newly paired phone: $10.00 a month ("), "a 0.8.3 file has no default: ten dollars");
        assertEquals(10.0, redeemed(phone("pair", "second", "--no-wait")).cap(), "as 0.8.3 gave");
        Files.setPosixFilePermissions(Phones.file(), PosixFilePermissions.fromString("rw-r--r--"));   // the pairing wrote it 0600 already: loosen it again for the default's own write
        assertEquals(0, phone("cap", "--default", "none").code());
        JsonNode after = M.readTree(Files.readString(Phones.file(), StandardCharsets.UTF_8));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Phones.file())), "the file is the owner's alone after the change");
        assertEquals(-1.0, after.path("default_cap").asDouble(0), after.toString());
        JsonNode pixel = null, tablet = null;
        for (JsonNode p : after.path("phones")) if (p.path("name").asText().equals("pixel")) pixel = p;
        for (JsonNode c : after.path("codes")) if (c.path("name").asText().equals("tablet")) tablet = c;
        assertTrue(pixel != null && tablet != null, after.toString());
        assertEquals("p0a1b2c3d", pixel.path("id").asText());
        assertEquals(20.0, pixel.path("cap").asDouble(), "the old phone keeps its cap");
        assertEquals(key, pixel.path("keys").get(0).path("hash").asText(), "and its browser");
        assertEquals(10.0, tablet.path("cap").asDouble(), "a code 0.8.3 showed keeps the cap it said");
        // what 0.8.3 reads of this file: phones and codes by these names, each with the fields it reads; the default is one field more at the top, which it reads past
        assertEquals(Set.of("codes", "default_cap", "phones"), fields(after));
        for (JsonNode p : after.path("phones")) assertEquals(Set.of("cap", "id", "keys", "name", "paired", "seen", "used"), fields(p));
        for (JsonNode c : after.path("codes")) assertEquals(Set.of("cap", "hash", "name", "phone", "until", "used"), fields(c));
        // a field this version does not know (as default_cap is to 0.8.3): read past, the phones kept whole, and left out when the file is written again
        ObjectNode more = (ObjectNode) after.deepCopy(); more.putObject("later").put("anything", 1);
        Files.writeString(Phones.file(), more.toString(), StandardCharsets.UTF_8);
        assertEquals(Map.of("pixel", 20.0, "second", 10.0), caps());
        assertEquals(0, phone("cap", "pixel", "25").code());
        JsonNode again = M.readTree(Files.readString(Phones.file(), StandardCharsets.UTF_8));
        assertEquals(Map.of("pixel", 25.0, "second", 10.0), caps());
        assertEquals(key, Phones.list().stream().filter(p -> p.name().equals("pixel")).findFirst().orElseThrow().keys().get(0).hash());
        assertFalse(again.has("later"), "an unknown field does not survive a rewrite: harmless, it is never read");
        assertEquals(-1.0, again.path("default_cap").asDouble(0), "the default survives every other change of the file");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Phones.file())));
    }

    @Test
    void aPhoneCalledDefaultIsAPhoneAndNoPhoneIsCalledDashDashDefault() throws Exception {
        Phones.Phone p = redeemed(phone("pair", "default", "--no-wait"));
        assertEquals("default", p.name());
        Share.Outcome o = phone("cap", "default", "5");
        assertEquals(0, o.code(), o.text());
        assertTrue(o.text().startsWith("default: the cap on paid models is now $5.00 a month"), o.text());
        assertTrue(phone("cap", "--default").text().contains("newly paired phone: $10.00 a month ("), "the library's default did not move");
        assertEquals(0, phone("cap", "--default", "7").code());
        assertEquals(5.0, caps().get("default"), "and setting the default leaves the phone called default as it was");
        assertEquals("default", Phones.cleanName("--default"), "a name never starts with a dash");
        assertEquals("x", Phones.cleanName(" - x"));
        assertEquals("phone", Phones.cleanName("--"));
        assertEquals("a-b", Phones.cleanName("a-b"));
        assertEquals(2, phone("pair", "--default", "--no-wait").code(), "the command takes --default for a flag, as it takes any --word");
    }

    @Test
    void statusAndListSayTheDefault() throws Exception {
        assertTrue(phone("status").text().contains("cap for a newly paired phone: $10.00 a month (researchzosho phone cap --default changes it)"), phone("status").text());
        String empty = phone("list").text();
        assertTrue(empty.contains("No phone is paired") && empty.contains("Cap on paid models for a newly paired phone: $10.00 a month (researchzosho phone cap --default changes it)."), empty);
        assertEquals(0, phone("cap", "--default", "none").code());
        redeemed(phone("pair", "pixel", "--cap", "4", "--no-wait"));
        assertTrue(phone("status").text().contains("cap for a newly paired phone: no limit (researchzosho phone cap --default changes it)"), phone("status").text());
        String list = phone("list").text();
        assertTrue(list.contains("pixel  paired ") && list.contains("cap $4.00 a month") && list.contains("Cap on paid models for a newly paired phone: no limit (researchzosho phone cap --default changes it)."), list);
    }

    @Test
    void theSettingsPagesPairingTakesTheDefaultSaysTheCapAndTheSectionSaysTheDefault() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        try (PhoneStandIns s = new PhoneStandIns(tmp.resolve("web"))) {
            Config.set(PhoneDoor.KIND, "tailnet"); Config.set(PhoneDoor.ADDRESS, ADDRESS);
            WebAccess.OVERRIDE = Boolean.TRUE; WebAccess.LOCAL_OVERRIDE = true;   // sign-in on; a browser on this machine is the keeper
            String page = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("Cap on paid models for a newly paired phone: $10.00 a month (researchzosho phone cap --default changes it)."), "before any pairing, the section says what a new phone gets");
            assertEquals(0, PhoneCli.run(s.store, List.of("cap", "--default", "none"), new PrintStream(new ByteArrayOutputStream())).code());
            page = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("Cap on paid models for a newly paired phone: no limit (researchzosho phone cap --default changes it)."), "the section follows the default");
            HttpResponse<String> made = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin")
                    .POST(HttpRequest.BodyPublishers.ofString(PhoneStandIns.form("op", "phone-pair", "name", "pixel"))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, made.statusCode(), made.body());
            assertTrue(made.body().contains(ADDRESS + "/m/pair?c=") && made.body().contains("<svg"), "the link and its QR code");
            assertTrue(made.body().contains("Its cap on paid models: no limit (researchzosho phone cap changes it)."), "the pairing says the cap the phone gets");
            List<Phones.Code> codes = Phones.read().codes();
            assertEquals(1, codes.size());
            assertEquals("pixel", codes.get(0).name());
            assertEquals(Phones.NO_LIMIT, codes.get(0).cap(), "the form's pairing takes the library's default");
            HttpResponse<String> dashes = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/settings")).header("Content-Type", "application/x-www-form-urlencoded").header("Sec-Fetch-Site", "same-origin")
                    .POST(HttpRequest.BodyPublishers.ofString(PhoneStandIns.form("op", "phone-pair", "name", "--default"))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dashes.statusCode());
            assertEquals(List.of("pixel", "default"), Phones.read().codes().stream().map(Phones.Code::name).toList(), "no phone is called --default, from the form either");
        } finally { WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    @Test
    void theRegistryTheConfigFileAndTheChatsToolsHoldNothingOfIt() throws Exception {
        Path config = Config.userConfigPath();
        byte[] before = Files.readAllBytes(config);
        String listed = Settings.list(M).toString();
        assertEquals(0, phone("cap", "--default", "none").code());
        assertArrayEquals(before, Files.readAllBytes(config), "the default is not written to the config file");
        assertEquals(listed, Settings.list(M).toString(), "nor shown among the settings");
        assertTrue(Files.readString(Phones.file(), StandardCharsets.UTF_8).contains("\"default_cap\""), "it is in phones.json");
        // the settings tool (the chat's library_settings, MCP's and /rpc's; the Settings page's form reads the same list), under the names a model would try
        LibraryProtocol p = new LibraryProtocol(store);
        byte[] phones = Files.readAllBytes(Phones.file());
        for (String name : List.of("phone_cap_default", "phone.cap.default", "default_cap", "phone_default_cap", "phones.default_cap", "phone_cap", "cap", "phone.cap")) {
            assertNull(Settings.named(name), name);
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.settings((ObjectNode) M.readTree("{\"op\":\"set\",\"name\":\"" + name + "\",\"value\":\"0\",\"patron\":{\"did\":\"person\",\"name\":\"\",\"runtime\":\"cli\"}}")));
            assertTrue(e.getMessage().contains("No setting is named"), e.getMessage());
        }
        assertArrayEquals(phones, Files.readAllBytes(Phones.file()), "nothing of phones.json moved");
        assertEquals(before.length, Files.readAllBytes(config).length);
        // in the program: the default is set in one place, phone cap --default, which only the terminal's own verb reaches; and only Phones
        // writes phones.json (Config names it among the program's own files, for the move to a folder of its own)
        Map<String, List<String>> calls = new LinkedHashMap<>();
        Pattern setter = Pattern.compile("Phones\\.setDefaultCap\\("), verb = Pattern.compile("PhoneCli\\.(?:run|cap|defaultCap)\\("), named = Pattern.compile("phones\\.json");
        try (Stream<Path> all = Files.walk(GenealogyBoundaryTest.mainJava())) {
            for (Path f : all.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8), n = f.getFileName().toString();
                if (setter.matcher(src).find()) calls.computeIfAbsent("setter", k -> new ArrayList<>()).add(n);
                if (verb.matcher(src).find() && !n.equals("PhoneCli.java")) calls.computeIfAbsent("verb", k -> new ArrayList<>()).add(n);
                if (named.matcher(src).find()) calls.computeIfAbsent("file", k -> new ArrayList<>()).add(n);
            }
        }
        assertEquals(List.of("PhoneCli.java"), calls.get("setter"), "the default's one setter: " + calls);
        assertEquals(List.of("LibrarianCli.java"), calls.get("verb"), "researchzosho phone, the terminal's verb, is the one way to it: " + calls);
        assertEquals(List.of("Config.java", "Phones.java"), calls.get("file"), calls.toString());
    }
}
