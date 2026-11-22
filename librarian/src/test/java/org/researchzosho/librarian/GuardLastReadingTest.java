package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.LookupsSeen;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.mcp.McpClient;
import org.researchzosho.mcp.StandInHttp;
import org.researchzosho.tools.Addresses;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.Outbound;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.WebFetchTool;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard of 0.8.1 after its last reading: the points a reader of the finished code left open, each stated as the behaviour that
 * should hold and tried. Two things are held to equally: what must not leave does not leave, and what a person ordinarily does is
 * not refused. The sites are listeners on the loopback that keep what they are asked; no test asks a host outside this machine, and
 * every test that expects nothing to arrive, to be looked up or to be drawn first sees the thing it counts.
 *
 * <p>Each test prints one line that begins {@code [last]} with what it counted, before it asserts.
 */
class GuardLastReadingTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR";

    @TempDir Path tmp;
    String realHome;
    HttpServer site, other;
    String base, elsewhere, n;
    /** What each listener was asked: the site the person's pages are on, and another door of the same machine. */
    final List<String> asked = new CopyOnWriteArrayList<>(), askedElsewhere = new CopyOnWriteArrayList<>();
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        n = Long.toHexString(System.nanoTime());
        site = listener(asked);
        other = listener(askedElsewhere);
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        elsewhere = "http://127.0.0.1:" + other.getAddress().getPort();
        GivenAddressesTest.loopback(true);
        ContentJudge.use(new ContentJudge(null, m -> "no"));
        store = new LibraryStore(tmp.resolve("home").resolve("researchzosho-library")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        GuardReviewTest.aNewProcess();
    }

    @AfterEach void down() throws Exception {
        ContentJudge.use(null); GivenAddressesTest.loopback(false);
        site.stop(0); other.stop(0);
        Outbound.noStandIns();
        GuardReviewTest.aNewProcess();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static HttpServer listener(List<String> keeps) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> {
            keeps.add(x.getRequestURI().toString());
            byte[] b = ("<html><head><title>A page</title></head><body><p>The ferry ran from 1911 to 1968. This is the page at " + x.getRequestURI().getRawPath() + ".</p></body></html>").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        return s;
    }

    static String q(String s) { return M.valueToTree(s).toString(); }
    static JsonNode args(String json) { try { return M.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); } }
    static ObjectNode tool(String name, String argsJson) { try { return LibrarianChatTest.tool(name, argsJson); } catch (Exception e) { throw new IllegalStateException(e); } }

    /** One turn of the chat in a conversation of its own: the person's words, then the model makes these calls, one a message. What the library told it for each. */
    List<String> chat(String person, ObjectNode... calls) throws Exception { return chatIn(Librarian.Session.open(store), person, calls); }

    /** The same turn in a conversation given: what the library told the model for each call of this turn. */
    List<String> chatIn(Librarian.Session s, String person, ObjectNode... calls) throws Exception {
        int before = s.messages().size();
        Researcher.Drive model = new Researcher.Drive() {
            int round;
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode offered, int maxTokens, String toolChoice) {
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant");
                if (round >= calls.length || "none".equals(toolChoice)) return m.put("content", "Done.");
                m.put("content", "");
                ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "call_" + round); c.put("type", "function");
                c.putObject("function").put("name", calls[round].get("tool").asText()).put("arguments", calls[round].path("args").toString());
                round++;
                return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 32_000; }
        };
        new Librarian(store, model, Librarian.person(), s).say(person);
        List<String> told = new ArrayList<>();
        List<ObjectNode> all = s.messages();
        for (ObjectNode m : all.subList(Math.min(before, all.size()), all.size())) if ("tool".equals(m.path("role").asText())) told.add(m.path("content").asText());
        return told;
    }

    static String said(String told) { return told.startsWith("NOT OPENED: ") ? "not opened" : told.startsWith(GivenWords.NOT_LOOKED_UP) ? "not looked up" : told.startsWith("source: ") ? "read" : told.substring(0, Math.min(told.length(), 120)).replace('\n', ' '); }

    // ---- 1. a host read off its letters, and the JVM's resolver

    /**
     * Texts of the kinds a host can be written in, made by a small generator over {@code letters}: groups with colons between them
     * as an address of the newer kind has them (too many, too long, a doubled colon twice, four numbers at the end, some over
     * 255), runs of the letters with colons and full stops among them, a zone after a percent sign, brackets that close or do not,
     * a blank before and after.
     */
    static List<String> hostTexts(long seed, int count, String letters) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            StringBuilder b = new StringBuilder();
            if (out.size() % 3 == 0) {
                int groups = r.nextInt(11);
                for (int g = 0; g < groups; g++) {
                    if (g > 0) b.append(r.nextInt(6) == 0 ? "::" : ":");
                    for (int k = r.nextInt(6); k > 0; k--) b.append(letters.charAt(r.nextInt(letters.length())));
                }
                if (r.nextInt(4) == 0) b.append(r.nextInt(2) == 0 ? ":" : "::").append(r.nextInt(300)).append('.').append(r.nextInt(300)).append('.').append(r.nextInt(300)).append('.').append(r.nextInt(300));
                if (r.nextInt(8) == 0) b.insert(0, "::");
            } else {
                for (int k = 1 + r.nextInt(out.size() % 2 == 0 ? 6 : 40); k > 0; k--) b.append(r.nextInt(4) == 0 ? (r.nextInt(3) == 0 ? '.' : ':') : letters.charAt(r.nextInt(letters.length())));
            }
            String t = b.toString();
            switch (r.nextInt(8)) { case 0 -> t += "%"; case 1 -> t += "%eth0"; case 2 -> t += "%25" + r.nextInt(9); case 3 -> t = t.substring(0, t.length() / 2) + "%" + t.substring(t.length() / 2); default -> { } }
            switch (r.nextInt(8)) { case 0 -> t = "[" + t + "]"; case 1 -> t = "[" + t; case 2 -> t = t + "]"; case 3 -> t = " " + t + " "; default -> { } }
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /**
     * No text handed to the readers of a host is handed on to a resolver: not to {@link Addresses#numeric}, {@link Addresses#literal}
     * or {@code Pages.thisMachine}, not when a page makes a link of an address, and not when the person's words, a text from outside or
     * a call's arguments are read for addresses. A name that is looked up is read by the name server of its domain, with whatever
     * stands in it, so a page being written and a text being read must ask none. The last reading of 0.8.1 said the texts that hold a
     * colon and are no address ("AB:CD", nine groups, a zone after a percent sign, a colon alone) go on to the resolver through
     * {@code numeric}'s last step. Tried with those and with nine thousand texts of the generator, hexadecimal letters and all
     * letters, on the JVM that runs the suite, whose resolver the suite watches ({@link LookupsSeen}; here every lookup this thread
     * makes is written down and answered "no such host", so nothing is asked of a name server).
     *
     * <p>What {@code literal} says is written as an address is handed by its two callers (the fetcher and the webhooks of a library
     * in a container) to the JDK to be read: such a text must be one the JDK reads off its letters too. The control: a name handed to
     * the JDK by this test is seen by the watch.
     */
    @Test
    void noTextReadForAnAddressIsHandedToAResolver() throws Exception {
        Thread me = Thread.currentThread();
        Predicate<String> byThisThread = h -> Thread.currentThread() == me;
        LookupsSeen.watch(byThisThread);
        try { InetAddress.getByName("ab.cd"); } catch (UnknownHostException expected) { /* the watch answers no such host */ }
        assertEquals(List.of("ab.cd"), LookupsSeen.seen(), "the watch sees a lookup this thread makes (the control): a zero below means none was made");
        List<String> reviewers = List.of("AB:CD", "1:2:3:4:5:6:7:8:9", "::1%x", "1:2%3", ":", "::", "::1%", "::1%0", "1:2", "a:", ":a", "1.2.3.4:5", "::1.2.3.999", "1::2::3", "12345::1", "::g", "[::1", "::1]", "[[::1]]", " ::1 ",
                "%::1", "::1%%25", ":::", "1:::2", "a.b:c", ".:", ":.", "::1.2.3", "::1.2.3.4.5", "::0x1.2.3.4", "0:0:0:0:0:0:0:0:0", "::ffff:999.1.1.1", "ghost:x", "host.example:80", "%:", "G::", "zz:zz");
        List<String> texts = new ArrayList<>(reviewers);
        texts.addAll(hostTexts(81, 6000, "0123456789abcdefABCDEF"));
        texts.addAll(hostTexts(82, 3000, "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-"));
        Map<String, Consumer<String>> readings = new LinkedHashMap<>();
        AtomicInteger addresses = new AtomicInteger(), written = new AtomicInteger();
        readings.put("Addresses.numeric", t -> { if (Addresses.numeric(t) != null) addresses.incrementAndGet(); });
        readings.put("Addresses.literal", t -> { if (Addresses.literal(t)) written.incrementAndGet(); });
        readings.put("Pages.thisMachine", Pages::thisMachine);
        readings.put("a page that makes a link of an address", t -> { Pages.linkable("http://" + t + "/x?d=" + CANARY); Pages.md("See [the office](http://" + t + "/x) and http://" + t + "/y for it.\n"); });
        readings.put("the person's words read for addresses", t -> GivenAddresses.personSaid("open " + t + " and tell me what it says, or http://" + t + "/page"));
        readings.put("a text from outside read for addresses", t -> GivenAddresses.received("The record is at http://" + t + "/page, and at " + t + "."));
        readings.put("a call's arguments read for addresses", t -> { GivenAddresses.unopened("web_fetch", args("{\"url\":" + q(t) + "}")); GivenAddresses.unopened("notes.read", args("{\"link\":" + q(t) + ",\"words\":" + q("see " + t) + "}")); Outbound.of(args("{\"query\":" + q(t) + "}")).names(t); });
        readings.put("what literal calls an address, read by the JDK as its callers have it read", t -> { if (Addresses.literal(t)) { try { InetAddress.getAllByName(t); } catch (UnknownHostException | RuntimeException noAddress) { /* refused off its letters */ } } });
        Config.set(Addresses.THROUGH_GATE, "on");
        readings.put("the fetcher of a library in a container, whose names are its gate's to look up", t -> { URI u; try { u = URI.create("http://" + t + "/"); } catch (IllegalArgumentException noAddress) { return; } Fetch.refusal(u); });
        Map<String, Set<String>> lookedUp = new LinkedHashMap<>();
        for (Map.Entry<String, Consumer<String>> reading : readings.entrySet()) {
            LookupsSeen.watch(byThisThread);
            for (String t : texts) reading.getValue().accept(t);
            lookedUp.put(reading.getKey(), new TreeSet<>(LookupsSeen.seen()));
        }
        Config.set(Addresses.THROUGH_GATE, "");
        StringBuilder counted = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : lookedUp.entrySet()) counted.append("; ").append(e.getKey()).append(": ").append(e.getValue().size()).append(e.getValue().isEmpty() ? "" : " " + e.getValue().stream().limit(6).toList());
        System.out.println("[last] 1 names handed to a resolver, of " + texts.size() + " texts (" + reviewers.size() + " written by hand, the rest generated; " + addresses.get() + " read as addresses, " + written.get() + " called written as one) on Java " + Runtime.version() + counted);
        for (Map.Entry<String, Set<String>> e : lookedUp.entrySet()) assertEquals(Set.of(), e.getValue(), e.getKey() + ": a text was handed to a resolver");
        // and the readings still read what is an address, so that a zero is not a reader that reads nothing
        assertTrue(Addresses.numeric("::1").isLoopbackAddress() && Addresses.numeric("[::1%eth0]").isLoopbackAddress() && Addresses.numeric("::ffff:127.0.0.1").isLoopbackAddress(), "this machine, as numbers");
        assertEquals("192.0.2.20", Addresses.numeric("::ffff:192.0.2.20").getHostAddress());
        assertNotNull(Addresses.numeric("2001:db8:0:1:2:3:4:5"));
        for (String no : List.of("AB:CD", "1:2:3:4:5:6:7:8:9", "1:2%3", ":", "ghost:x", "host.example:80", "999.1.1.1")) assertNull(Addresses.numeric(no), no + " is no address");
        for (String yes : List.of("192.0.2.20", "[::1]", "::1", "fe80::1%eth0", "[2001:db8::77]", "::ffff:192.0.2.20")) assertTrue(Addresses.literal(yes), yes + " is written as an address");
        for (String no : List.of("example.org", "ghost:x", "host.example:80", "AB:CD", "%:", "999.1.1.1", " ::1 ", "", ":")) assertFalse(Addresses.literal(no), "\"" + no + "\" is not written as an address");
        assertTrue(addresses.get() > 100 && written.get() > 100, "the generator writes addresses too: " + addresses.get() + ", " + written.get());
    }

    // ---- 2. the head of a saved copy

    static int headLines(Path raw) throws Exception {
        List<String> lines = Files.readAllLines(raw, StandardCharsets.UTF_8);
        assertEquals("---", lines.get(0));
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).equals("---")) return i - 1;
        throw new IllegalStateException("the head does not close: " + raw);
    }

    /**
     * Whether a saved copy's text is word for word from outside or a seeing model's reading is said by a line of its head
     * ({@code read_by_eye}, {@code comic}, {@code pdf_reading}), and {@link RawCapture#verbatim} reads at most 64 lines of a head.
     * The head is written by {@code capture} and {@code link}: four lines always, a collection, a publication date or the three
     * lines of a linked file, and then one line for each name its caller passes. No caller makes those names from what it read:
     * each passes a fixed handful (counted on 2026-11-21 over the 39 calls: at most 3 for a copy that can be word for word, a head
     * of 9 lines; at most 16 for a kept picture, a head of 21). So the line that says whose words a copy holds always stands within
     * what is read. Here a head is written through the real writers with every name any caller passes, the telling line last, and
     * the copy is told as a seeing model's each time; the same head without that line is word for word (the control). What the
     * reading makes of a line past the sixty-fourth, in a file written by hand, is printed beside it.
     */
    @Test
    void theLineThatSaysWhoseWordsACopyHoldsStandsWithinWhatIsReadOfItsHead() throws Exception {
        List<String> names = List.of("kind", "image", "page", "source", "found", "width", "height", "bytes", "hash", "caption", "verdict", "verdict_why", "verdict_model", "criteria", "judged", "within", "job", "gallery",
                "translation_of", "language", "translated_from", "quality", "pdf_sensor");
        Map<String, String> telling = new LinkedHashMap<>();
        telling.put(RawCapture.BY_EYE, "pages 1-3");
        telling.put("comic", "cbz");
        telling.put("pdf_reading", "12 pages, 3 of them pictures of text; read by eye by the-seeing-model: 4-6");
        Map<String, Integer> heads = new LinkedHashMap<>();
        Map<String, Boolean> told = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, String> line : telling.entrySet()) {
            Map<String, String> more = new LinkedHashMap<>();
            for (String name : names) more.put(name, "a value of one line for " + name);
            Path without = RawCapture.capture(store, "https://papers-" + n + ".example.org/scan-" + (i++), "The words of the scan, as they were read.", "A scan", "researchzosho-add", "papers", "2026-01-02", more);
            more.put(line.getKey(), line.getValue());
            Path with = RawCapture.capture(store, "https://papers-" + n + ".example.org/scan-" + (i++), "The words of the scan, as they were read.", "A scan", "researchzosho-add", "papers", "2026-01-02", more);
            heads.put(line.getKey() + " last, through capture", headLines(with));
            told.put(line.getKey() + " last, through capture", !RawCapture.verbatim(with));
            assertTrue(RawCapture.verbatim(without), "the same head without the line is a copy word for word (the control): " + Files.readString(without).lines().limit(8).toList());
        }
        Path file = tmp.resolve("scan-" + n + ".pdf"), plain = tmp.resolve("letter-" + n + ".txt");
        Files.writeString(file, "a scan"); Files.writeString(plain, "a letter");
        Path linked = RawCapture.link(store, file, "The words of the scan.", "A scan", "corpus:papers", "papers", Map.of(RawCapture.BY_EYE, "pages 1-3"));
        Path linkedPlain = RawCapture.link(store, plain, "The words of the letter.", "A letter", "corpus:papers", "papers", Map.of());
        heads.put(RawCapture.BY_EYE + " last, through link", headLines(linked));
        told.put(RawCapture.BY_EYE + " last, through link", !RawCapture.verbatim(linked));
        assertTrue(RawCapture.verbatim(linkedPlain), "a linked file with no such line is word for word (the control)");
        // the limit is real: past it the line is not read
        StringBuilder long_ = new StringBuilder("---\nurl: https://papers-" + n + ".example.org/by-hand\ntitle: By hand\nfetched_at: 2026-01-02T00:00:00Z\nfetched_by: researchzosho-add\n");
        for (int k = 0; k < 70; k++) long_.append("filler_").append((char) ('a' + k % 26)).append(": x\n");
        Path byHand = store.rawDir().resolve("2026-01-02-byhand" + n + ".md");
        Files.writeString(byHand, long_ + RawCapture.BY_EYE + ": pages 1-3\n---\nThe words.\n");
        boolean pastTheLimitIsRead = !RawCapture.verbatim(byHand);
        System.out.println("[last] 2 the head of a saved copy, in lines: " + heads + " (a caller's longest: 9 for a copy that can be word for word, 21 for a kept picture; 64 are read); told as a seeing model's: " + told
                + "; a line past the sixty-fourth of a head written by hand is read: " + pastTheLimitIsRead);
        for (Map.Entry<String, Integer> e : heads.entrySet()) assertTrue(e.getValue() < 64 && e.getValue() >= (e.getKey().endsWith("link") ? 9 : 30), "a head longer than any caller writes, and within what is read: " + e);
        for (Map.Entry<String, Boolean> e : told.entrySet()) assertTrue(e.getValue(), "the copy is told as a seeing model's: " + e.getKey());
    }

    // ---- 3. found pictures and the reader

    static HttpResponse<String> post(String url, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A found picture is passed on to the reader whose own page showed it, whichever thread wrote the page and whatever that thread
     * did before. Two readers are signed in to one library, and the library's threads serve them one after the other: each gets
     * the pictures of their own pages and not the other's. Then a page is written with no request behind it, as a job or an export
     * would write one: once on a thread of the library's that wrote a reader's page just before (reached through a call of the
     * protocol, which is no page), once on a thread that never served anybody. Such a page has no reader, and its pictures are
     * passed on to nobody: not to the reader that thread served last, and not to every reader. The controls: each reader's own
     * picture is passed on, one thread did write pages of both readers, and the page with no request did write the picture's tag.
     */
    @Test
    void aFoundPictureIsItsReadersWhicheverThreadWroteThePage() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        Images.Fetcher real = Images.FOUND_FETCHER;
        Images.FOUND_FETCHER = url -> new Images.Fetched(Vision.square(200, 40, 40), "image/png");
        var engines = Images.ENGINES;
        String host = "https://img-" + n + ".example.org/";
        Map<Thread, Set<String>> served = new ConcurrentHashMap<>();   // a thread of the library's → the readers whose pages it wrote
        Map<Thread, String> servedLast = new ConcurrentHashMap<>();
        Map<String, String> withNoRequest = new ConcurrentHashMap<>();   // the picture of a page written with no request → the reader its thread served last
        List<String> tags = new CopyOnWriteArrayList<>();
        Images.ENGINES = () -> List.of(new Images.Engine("stand-in", (words, count) -> {
            Thread t = Thread.currentThread();
            if (words.startsWith("job")) {
                String last = servedLast.get(t);
                if (last == null) return List.of();   // a thread that wrote no page yet: asked again
                String picture = host + words + ".png";
                tags.add(Pages.prose("![a picture](" + picture + ")", Set.of(picture)));   // a page written here, outside any request for a page
                withNoRequest.put(picture, last);
                return List.of();
            }
            String who = words.substring(0, 3);
            served.computeIfAbsent(t, k -> ConcurrentHashMap.newKeySet()).add(who); servedLast.put(t, who);
            return List.of(new Images.Found(host + words + ".png", host + words + ".html", "The ferry", "stand-in", 800, 600, "", "", ""));
        }));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String at = "http://127.0.0.1:" + d.port();
        try {
            Map<String, String> cookie = new LinkedHashMap<>();
            cookie.put("ann", GuardReviewPagesTest.signedIn(store, at, "Ann Example", Patrons.Level.write, "ann-secret-1"));
            cookie.put("ben", GuardReviewPagesTest.signedIn(store, at, "Ben Example", Patrons.Level.write, "ben-secret-1"));
            // the two readers' own pages of found pictures, one after the other, until one thread has written pages of both
            Map<String, List<String>> shown = new LinkedHashMap<>(Map.of("ann", new ArrayList<>(), "ben", new ArrayList<>()));
            for (int round = 0; round < 12 && served.values().stream().noneMatch(s -> s.size() == 2); round++) for (String who : List.of("ann", "ben")) {
                String words = who + "-" + round + "-" + n, picture = host + words + ".png";
                String page = GuardFixesPagesTest.ask("GET", at + ImagesPage.PATH + "?q=" + words, null, "Cookie", cookie.get(who), "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document").body();
                assertTrue(page.contains("/found?u=" + GuardChecksPagesTest.enc(picture)), who + "'s page shows the found picture: " + GuardChecksPagesTest.seen(page).strip().replaceAll("\\s+", " "));
                shown.get(who).add(picture);
            }
            boolean oneThreadBoth = served.values().stream().anyMatch(s -> s.size() == 2);
            Map<String, Integer> got = new LinkedHashMap<>();
            for (String who : List.of("ann", "ben")) for (String whose : List.of("ann", "ben")) {
                Set<Integer> statuses = new TreeSet<>();
                for (String picture : shown.get(whose)) statuses.add(GuardChecksPagesTest.get(at + "/found?u=" + GuardChecksPagesTest.enc(picture), "Cookie", cookie.get(who)).statusCode());
                got.put(who + " asks for a picture of " + (who.equals(whose) ? "their own page" : whose + "'s page"), statuses.size() == 1 ? statuses.iterator().next() : -1);
            }
            // a page with no request behind it, on a thread of the library's that wrote a reader's page before
            for (int tries = 0; tries < 40 && withNoRequest.isEmpty(); tries++) {
                HttpResponse<String> r = post(at + "/v1/images", "{\"op\":\"find\",\"words\":\"job-" + tries + "-" + n + "\"}", "Cookie", cookie.get("ann"));
                assertEquals(200, r.statusCode(), "the call of the protocol is answered: " + r.body());
            }
            // and on a thread that never served anybody
            String nobodys = host + "nobody-" + n + ".png";
            Thread own = new Thread(() -> tags.add(Pages.prose("![a picture](" + nobodys + ")", Set.of(nobodys))), "a-job");
            own.start(); own.join();
            Map<String, String> jobs = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : withNoRequest.entrySet()) for (String who : List.of("ann", "ben"))
                jobs.put("written on a thread that last served " + e.getValue() + ", asked for by " + who, String.valueOf(GuardChecksPagesTest.get(at + "/found?u=" + GuardChecksPagesTest.enc(e.getKey()), "Cookie", cookie.get(who)).statusCode()));
            for (String who : List.of("ann", "ben")) jobs.put("written on a thread that served nobody, asked for by " + who, String.valueOf(GuardChecksPagesTest.get(at + "/found?u=" + GuardChecksPagesTest.enc(nobodys), "Cookie", cookie.get(who)).statusCode()));
            System.out.println("[last] 3 found pictures and their reader: " + got + "; one thread wrote pages of both readers: " + oneThreadBoth + "; a page written with no request: " + jobs);
            assertTrue(oneThreadBoth, "one thread of the library wrote pages of both readers (the control for the threads): " + served.values());
            assertFalse(withNoRequest.isEmpty(), "a page was written with no request on a thread that served a reader before (the control)");
            assertEquals(2, tags.stream().filter(t -> t.contains("src=\"/found?u=")).count(), "and both such pages wrote the picture's tag (the control): " + tags);
            for (String who : List.of("ann", "ben")) assertEquals(200, got.get(who + " asks for a picture of their own page"), "their own page's picture (the control): " + got);
            assertEquals(404, got.get("ann asks for a picture of ben's page"), got.toString());
            assertEquals(404, got.get("ben asks for a picture of ann's page"), got.toString());
            for (Map.Entry<String, String> e : jobs.entrySet()) assertEquals("404", e.getValue(), "a page with no request has no reader, and its picture is passed on to nobody: " + e.getKey());
        } finally { d.stop(); Images.FOUND_FETCHER = real; Images.ENGINES = engines; WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    // ---- 4. a keeper's MCP server whose answers give addresses

    static McpClient.Tool mcpTool(String name, String words, String schema) throws Exception { return new McpClient.Tool(name, "", words, (ObjectNode) M.readTree(schema), null); }

    /**
     * A keeper may say of an MCP server that its answers name sources ({@code mcp links <name> on}): then the addresses in an
     * answer may be opened. An answer that only repeats an address its own call sent gives nothing: the address came from the
     * model, not from outside, however the server wrote it back (as it was sent, with a scheme put before it, joined from two
     * arguments, with its escapes read, in other capitals, with a slash or a fragment after it, as a link of its own beside the
     * words). Tried through the chat with a stand-in server that repeats its arguments, where the model then opens what it sent
     * (nothing arrives at the listener), and with answers written here and handed through the same reading a call's answer gets.
     * The controls: an address the server itself names is given, with the switch on and not with it off; and an address the person
     * gave arrives at the listener.
     */
    @Test
    void anMcpServersAnswerThatRepeatsItsCallsOwnAddressGivesNothing() throws Exception {
        SearchTally.override = tmp.resolve("tally.json");
        try (StandInHttp notes = new StandInHttp(null, null, null)) {
            String text = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}", id = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}";
            for (String name : List.of("finds", "computes")) {
                McpServers.Server s = new McpServers.Server();
                s.name = name; s.transport = "http"; s.url = notes.url(); s.links = name.equals("finds");
                s.tools = new ArrayList<>(List.of(mcpTool("search_notes", "Searches the notes by their words and returns the matching notes with their addresses.", text), mcpTool("get_note", "Returns one note in full by its id.", id)));
                McpServers.put(store, s);
            }
            // through the chat: the stand-in repeats the query's words and the id it was sent
            String given = base + "/given-by-the-person-" + n, inWords = base + "/sent-in-the-query-" + n + "?d=" + CANARY, bare = "127.0.0.1:" + site.getAddress().getPort() + "/sent-as-the-id-" + n + "?d=" + CANARY;
            List<String> told = chat("Search the notes for the ferry and get the note in full by its id; then read " + given + " for me.",
                    tool("finds.search_notes", "{\"query\":" + q("the ferry, as at " + inWords) + "}"),
                    tool("finds.get_note", "{\"id\":" + q(bare) + "}"),
                    tool("open", "{\"address\":" + q(inWords) + "}"),
                    tool("open", "{\"address\":" + q("http://" + bare) + "}"),
                    tool("open", "{\"address\":" + q(given) + "}"));
            assertEquals(5, told.size(), told.toString());
            boolean bareOpens = GivenAddresses.unopened("open", args("{\"address\":" + q(bare) + "}")) == null;   // the same address as the model sent it, without a scheme
            assertTrue(told.get(0).contains(inWords) && told.get(1).contains(bare), "the server's answers repeat what the calls sent, and the model reads them (the instrument): " + told.subList(0, 2));
            boolean serversOwn = GivenAddresses.given("https://notes.example.org/tote-canvas"), serversLink = GivenAddresses.given("https://notes.example.org/handles");
            List<String> arrivedWithIt = asked.stream().filter(a -> a.contains(CANARY)).toList();
            // the same reading, with answers written here: what a server may make of an address it was sent
            Instrument finds = McpSources.instruments(store, Patrons.Patron.PERSON).stream().filter(x -> x.name().equals("finds.get_note")).findFirst().orElseThrow();
            Instrument computes = McpSources.instruments(store, Patrons.Patron.PERSON).stream().filter(x -> x.name().equals("computes.get_note")).findFirst().orElseThrow();
            record Repeat(String how, String callArgs, String answer, List<String> links, String address) { }
            String h = "collector-" + n + ".example.net";
            List<Repeat> repeats = List.of(
                    new Repeat("as it was sent, inside a sentence", "{\"id\":" + q("see https://a." + h + "/x?d=" + CANARY) + "}", "Here is https://a." + h + "/x?d=" + CANARY + " as you asked.", List.of(), "https://a." + h + "/x?d=" + CANARY),
                    new Repeat("sent without a scheme, written back with one", "{\"id\":" + q("b." + h + "/x?d=" + CANARY) + "}", "Here is https://b." + h + "/x?d=" + CANARY + " as you asked.", List.of(), "https://b." + h + "/x?d=" + CANARY),
                    new Repeat("joined from two arguments", "{\"id\":" + q("c." + h) + ",\"part\":" + q("/records/of-the-year?d=" + CANARY) + "}", "The note is kept at https://c." + h + "/records/of-the-year?d=" + CANARY, List.of(), "https://c." + h + "/records/of-the-year?d=" + CANARY),
                    new Repeat("sent with escapes, written back read", "{\"id\":" + q("d." + h + "%2Fx%3Fd%3D" + CANARY) + "}", "See https://d." + h + "/x?d=" + CANARY + " for it.", List.of(), "https://d." + h + "/x?d=" + CANARY),
                    new Repeat("in other capitals, a slash and a fragment after it", "{\"id\":" + q("E." + h.toUpperCase() + "/x/" + CANARY) + "}", "See https://e." + h + "/x/" + CANARY + "/#top for it.", List.of(), "https://e." + h + "/x/" + CANARY),
                    new Repeat("as a link of its own beside the words", "{\"id\":" + q("f." + h + "/x?d=" + CANARY) + "}", "One note matches.", List.of("https://f." + h + "/x?d=" + CANARY), "https://f." + h + "/x?d=" + CANARY),
                    new Repeat("the host alone", "{\"id\":" + q("g." + h) + "}", "The note is at https://g." + h + "/ now.", List.of(), "https://g." + h + "/"));
            Map<String, Boolean> givenByARepeat = new LinkedHashMap<>();
            for (Repeat r : repeats) {
                ObjectNode a = (ObjectNode) args(r.callArgs());
                String answer = McpSources.observation(store, "finds", "get_note", McpSources.locator("finds", "get_note", a), new McpClient.Result(r.answer(), false, r.links(), null), a);
                assertTrue(answer.contains(r.address().replaceAll("/$", "")) || answer.contains(r.address()), "the model reads the address in the answer (the instrument): " + answer);
                GivenAddresses.gave(finds.fromOutside(a), store, answer, GivenAddresses.Own.of(a));
                givenByARepeat.put(r.how(), GivenAddresses.given(r.address()));
            }
            // the controls: an address the answer brings itself, by the keeper's word
            ObjectNode asks = (ObjectNode) args("{\"id\":\"N-12\"}");
            String brought = "https://gazette-" + n + ".example.org/1968/ferry", computed = "https://sums-" + n + ".example.org/1968/ferry";
            GivenAddresses.gave(finds.fromOutside(asks), store, McpSources.observation(store, "finds", "get_note", McpSources.locator("finds", "get_note", asks), new McpClient.Result("The note cites " + brought + " for it.", false, List.of(), null), asks), GivenAddresses.Own.of(asks));
            GivenAddresses.gave(computes.fromOutside(asks), store, McpSources.observation(store, "computes", "get_note", McpSources.locator("computes", "get_note", asks), new McpClient.Result("The note cites " + computed + " for it.", false, List.of(), null), asks), GivenAddresses.Own.of(asks));
            System.out.println("[last] 4 a keeper's MCP server with its links on: in the chat the answers repeated the query's address and the id; the model then opened them: " + told.subList(2, 4).stream().map(GuardLastReadingTest::said).toList() + ", and the id as it sent it may be opened: " + bareOpens
                    + ", requests holding them at the listener: " + arrivedWithIt.size() + "; the address the person gave: " + said(told.get(4)) + "; the server's own two addresses given: " + serversOwn + ", " + serversLink
                    + "; given by an answer that repeats its call's address " + givenByARepeat + "; an address the answer brings itself: with the switch on " + GivenAddresses.given(brought) + ", off " + GivenAddresses.given(computed));
            assertTrue(serversOwn && serversLink, "what the server names itself is given, the switch being on (the control)");
            assertEquals("read", said(told.get(4)), "the address the person gave is read (the control): " + told.get(4));
            assertEquals(1, asked.stream().filter(x -> x.contains("/given-by-the-person-" + n)).count(), "and the listener sees it once");
            for (int k = 2; k <= 3; k++) assertEquals("not opened", said(told.get(k)), "an address the call itself sent is not opened because the answer repeated it: " + told.get(k));
            assertFalse(bareOpens, "nor is it as the model sent it, without a scheme");
            assertEquals(List.of(), arrivedWithIt, "and nothing of it arrives");
            for (Map.Entry<String, Boolean> e : givenByARepeat.entrySet()) assertFalse(e.getValue(), "an answer that repeats its call's address gave it: " + e.getKey());
            assertTrue(GivenAddresses.given(brought), "an address the answer brings itself is given, the switch being on (the control)");
            assertFalse(GivenAddresses.given(computed), "and is not with the switch off");
        } finally { McpConnections.closeAll(); McpConnections.ADDRESS = UnaryOperator.identity(); SearchTally.override = null; }
    }

    // ---- 5. the front page and the robots file of a site, and a port

    /** What a call of {@code web_fetch} for this address is answered before anything is asked: "opens as <the address requested>", or "not opened". */
    static String opening(String address) {
        JsonNode a = args("{\"url\":" + q(address) + "}");
        return GivenAddresses.unopened("web_fetch", a) != null ? "not opened" : "opens as " + GivenAddresses.asGiven(a).path("url").asText();
    }

    /**
     * The front page and the robots file of a site open when an address on that site was given. A site is a host at a port: the
     * port the address was given with, which is the scheme's own when none was written (written out, {@code :443} after https and
     * {@code :80} after http, it is the same door). A port nobody gave is another program's door on that machine, and neither form
     * opens it. Tried for a site given on the usual port, for one given at a port of its own, and for one given with the scheme's
     * own port written out; then with two listeners of this machine, where what arrives is counted. The controls: the forms open
     * at the port that was given, and the listener that was given sees its robots file asked for.
     */
    @Test
    void aSitesFrontPageAndRobotsFileOpenAtThePortThatWasGivenAndNoOther() throws Exception {
        String usual = "gazette-" + n + ".example.org", own = "media-" + n + ".example.org", written = "archive-" + n + ".example.org", plain = "printer-" + n + ".example.org";
        GivenAddresses.personSaid("Read https://" + usual + "/1968/ferry and http://" + own + ":8096/web/index.html and https://" + written + ":443/a/b and http://" + plain + ":80/status for me.");
        Map<String, String> got = new LinkedHashMap<>();
        for (String a : List.of(
                "https://" + usual + "/robots.txt", "https://" + usual + "/", "https://" + usual + ":443/robots.txt", "https://" + usual + ":8080/robots.txt", "https://" + usual + ":8443/", "http://" + usual + ":8080/robots.txt", "https://" + usual + ":80/robots.txt",
                "http://" + own + ":8096/robots.txt", "http://" + own + ":8096/", "http://" + own + "/robots.txt", "http://" + own + ":80/", "http://" + own + ":8097/robots.txt", "https://" + own + "/robots.txt",
                "https://" + written + "/robots.txt", "https://" + written + ":443/robots.txt", "https://" + written + "/", "https://" + written + ":8443/robots.txt",
                "http://" + plain + "/robots.txt", "http://" + plain + ":80/", "http://" + plain + ":8080/robots.txt")) got.put(a, opening(a));
        // two doors of this machine: the person gave a page on one
        List<String> told = chat("What does " + base + "/the-page-" + n + " say, and may a crawler read that site?",
                tool("open", "{\"address\":" + q(elsewhere + "/robots.txt") + "}"), tool("open", "{\"address\":" + q(elsewhere + "/") + "}"),
                tool("open", "{\"address\":" + q(base + "/robots.txt") + "}"), tool("open", "{\"address\":" + q(base + "/the-page-" + n) + "}"));
        System.out.println("[last] 5 the front page and the robots file, by the port: " + got + "; two doors of this machine, a page given on one: the other door's robots file and front page " + told.subList(0, 2).stream().map(GuardLastReadingTest::said).toList()
                + " (asked there: " + askedElsewhere + "), the given door's " + said(told.get(2)) + " (asked there: " + asked + ")");
        // the usual port
        assertEquals("opens as https://" + usual + "/robots.txt", got.get("https://" + usual + "/robots.txt"), "the control");
        assertEquals("opens as https://" + usual + "/", got.get("https://" + usual + "/"));
        assertEquals("opens as https://" + usual + "/robots.txt", got.get("https://" + usual + ":443/robots.txt"), "the scheme's own port, written out, is the same door");
        for (String a : List.of("https://" + usual + ":8080/robots.txt", "https://" + usual + ":8443/", "http://" + usual + ":8080/robots.txt", "https://" + usual + ":80/robots.txt")) assertEquals("not opened", got.get(a), "a port nobody gave: " + a);
        // a port of its own
        assertEquals("opens as http://" + own + ":8096/robots.txt", got.get("http://" + own + ":8096/robots.txt"), "the control");
        assertEquals("opens as http://" + own + ":8096/", got.get("http://" + own + ":8096/"));
        for (String a : List.of("http://" + own + "/robots.txt", "http://" + own + ":80/", "http://" + own + ":8097/robots.txt", "https://" + own + "/robots.txt")) assertEquals("not opened", got.get(a), "the site was given at 8096, and this is another door: " + a);
        // the scheme's own port written out when it was given
        assertEquals("opens as https://" + written + "/robots.txt", got.get("https://" + written + "/robots.txt"), "given with :443 written out, asked without it: the same door");
        assertEquals("opens as https://" + written + "/robots.txt", got.get("https://" + written + ":443/robots.txt"));
        assertEquals("opens as https://" + written + "/", got.get("https://" + written + "/"));
        assertEquals("not opened", got.get("https://" + written + ":8443/robots.txt"));
        assertEquals("opens as http://" + plain + "/robots.txt", got.get("http://" + plain + "/robots.txt"), "given with :80 written out, asked without it: the same door");
        assertEquals("opens as http://" + plain + "/", got.get("http://" + plain + ":80/"));
        assertEquals("not opened", got.get("http://" + plain + ":8080/robots.txt"));
        // what arrives
        assertEquals(List.of("not opened", "not opened"), told.subList(0, 2).stream().map(GuardLastReadingTest::said).toList(), "the other door of the machine: " + told.subList(0, 2));
        assertEquals(List.of(), List.copyOf(askedElsewhere), "and nothing arrives there");
        assertEquals("read", said(told.get(2)), "the robots file of the door that was given is read (the control): " + told.get(2));
        assertTrue(asked.contains("/robots.txt") && asked.contains("/the-page-" + n), "and its listener sees both requests: " + asked);
    }

    /**
     * An ordinary page given with its scheme's own port written out ({@code https://host:443/a/b}) is the page asked without it,
     * and the other way round: the same door of the same machine. A port that is not the scheme's own is another program's door,
     * and stays another address in both directions; so is :443 after http and :80 after https. Tried by the check before a call is
     * carried out, and by the check of the request itself ({@code Outbound}): a request it lets through goes on to be looked up,
     * and the suite's resolver answers that the name does not exist, so nothing leaves; a request it refuses is never looked up.
     * The controls: each site opens at the port it was given at, and the resolver sees a name that is asked.
     */
    @Test
    void anOrdinaryPageGivenWithItsSchemesOwnPortWrittenOutIsThePageWithout() throws Exception {
        String a = "written-" + n + ".example.org", b = "plain-" + n + ".example.org", c = "insecure-" + n + ".example.org", d = "own-door-" + n + ".example.org";
        GivenAddresses.personSaid("Read https://" + a + ":443/reports/1968 and https://" + b + "/reports/1968 and http://" + c + ":80/reports/1968 and https://" + d + ":8443/reports/1968 for me.");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("https://" + a + "/reports/1968", "opens");            // given with :443, asked without
        expected.put("https://" + a + ":443/reports/1968/", "opens");
        expected.put("https://" + b + ":443/reports/1968", "opens");        // given without, asked with :443
        expected.put("http://" + c + "/reports/1968", "opens");             // http's own port, both ways
        expected.put("http://" + c + ":80/reports/1968", "opens");
        expected.put("https://" + d + ":8443/reports/1968", "opens");       // the control: the port it was given at
        expected.put("https://" + a + ":8443/reports/1968", "not opened");
        expected.put("https://" + b + ":8443/reports/1968", "not opened");
        expected.put("https://" + a + ":80/reports/1968", "not opened");
        expected.put("http://" + b + ":443/reports/1968", "not opened");
        expected.put("https://" + c + ":8080/reports/1968", "not opened");
        expected.put("https://" + d + "/reports/1968", "not opened");
        expected.put("https://" + d + ":443/reports/1968", "not opened");
        Map<String, String> before = new LinkedHashMap<>(), atTheRequest = new LinkedHashMap<>();
        Map<String, List<String>> lookedUpWhenRefused = new LinkedHashMap<>();
        Set<String> lookedUp = new TreeSet<>();
        HttpClient client = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        for (String asked : expected.keySet()) {
            before.put(asked, opening(asked).startsWith("opens") ? "opens" : "not opened");
            LookupsSeen.watch(h -> h.endsWith(n + ".example.org"));
            String outcome;
            try (Outbound.Call call = Outbound.call("web_fetch", args("{\"url\":" + q(asked) + "}"))) {
                client.send(HttpRequest.newBuilder(URI.create(asked)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
                outcome = "answered";
            } catch (Outbound.NotOpened refused) { outcome = "not opened"; }
            catch (IOException letThrough) { outcome = "opens"; }   // let through: the name went on to be looked up, and no such host was the answer (the JDK's client says it as a ConnectException; it keeps that answer a while and asks the resolver no more for that name)
            catch (Exception e) { outcome = "failed otherwise: " + e; }
            List<String> seen = LookupsSeen.seen();
            lookedUp.addAll(seen);
            if (outcome.equals("not opened")) lookedUpWhenRefused.put(asked, seen);
            atTheRequest.put(asked, outcome);
        }
        System.out.println("[last] 5b an ordinary page and the scheme's own port: before the call " + before + "; at the request " + atTheRequest + "; names looked up " + lookedUp + "; looked up for a request refused " + lookedUpWhenRefused);
        assertEquals(expected, before, "the check before a call is carried out");
        assertEquals(expected, atTheRequest, "the check of the request itself");
        assertEquals(new TreeSet<>(List.of(a, b, c, d)), lookedUp, "the resolver saw each site whose page opens (the control), and no other");
        for (Map.Entry<String, List<String>> e : lookedUpWhenRefused.entrySet()) assertEquals(List.of(), e.getValue(), "a request refused is not looked up: " + e.getKey());
    }

    // ---- 5c. "www." before a host, added or left off

    /**
     * The request for {@code asked} in a call of {@code web_fetch} that holds it, through the guard: "opens" (let through: the name
     * went on to be looked up, and the suite's resolver said there is no such host), "answered" (a listener of this machine
     * answered), "not opened", or "no host" (java.net.URI reads no host in it, and the JDK's client sends nothing). The names
     * looked up that end in {@code watched} are added to {@code lookedUp}.
     */
    static String atTheRequest(HttpClient client, String asked, String watched, List<String> lookedUp) {
        LookupsSeen.watch(h -> h.endsWith(watched));
        String outcome;
        try (Outbound.Call call = Outbound.call("web_fetch", args("{\"url\":" + q(asked) + "}"))) {
            client.send(HttpRequest.newBuilder(URI.create(GivenAddresses.requested(asked))).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
            outcome = "answered";
        } catch (Outbound.NotOpened refused) { outcome = "not opened"; }
        catch (IOException letThrough) { outcome = "opens"; }
        catch (IllegalArgumentException unsupported) { outcome = "no host"; }
        catch (Exception e) { outcome = "failed otherwise: " + e; }
        lookedUp.addAll(LookupsSeen.seen());
        return outcome;
    }

    /**
     * A host with "www." before it and the same host without it are one given address, both ways (the acceptance pass of 0.8.1:
     * the person typed nps.gov/stli, the model opened https://www.nps.gov/stli, and the library refused it as an address nobody
     * gave). What is requested is a spelling that was given, never the model's: given without "www." and asked with it, the
     * request goes to the host without it; given with it and asked without, to the host with it. Tried for a page, a front page
     * and a robots file, given and asked with a scheme and without, first by the check before a call is carried out (what the
     * model is told, and the address the tool is handed), then by the check of the request itself, where the name the request
     * went to is read off the suite's resolver (each case is a site of its own, so each name is looked up once). When both
     * spellings were given (the person typed one, a search returned the other), each is asked as written. The controls, each
     * refused and looked up nowhere: another first label (m., www2., wwww., en., www.www.), "www." before another path or with a
     * query, and "www." before a host of a single label or one written as numbers, whose plain forms open.
     */
    @Test
    void aHostWithAndWithoutWwwIsTheSameGivenAddressAndIsAskedAsItWasGiven() throws Exception {
        String at = "-" + n + ".example.org";
        String[] h = new String[12];
        for (int i = 1; i < h.length; i++) h[i] = "w" + i + at;
        int port = site.getAddress().getPort();
        String local = "http://localhost:" + port + "/page-" + n, numbers = "http://127.0.0.1:" + port + "/page-" + n;
        GivenAddresses.personSaid("Open " + h[1] + "/stli and " + h[2] + "/stli, then https://www." + h[3] + "/stli and www." + h[4] + "/stli; also " + h[5] + "/stli, https://www." + h[6] + "/stli, http://"
                + h[7] + "/stli, www." + h[8] + "/stli and https://" + h[9] + "/stli; and " + h[10] + "/stli and " + h[11] + "/stli. And " + local + " and " + numbers + ".");
        GivenAddresses.received("1. The park: https://www." + h[10] + "/stli\n2. The news: https://www." + h[11] + "/news");   // as a search gives them: the other spelling of two of the sites
        Map<String, String> expected = new LinkedHashMap<>();
        // a page given without "www.", asked with it; given with it, asked without
        expected.put("https://www." + h[1] + "/stli", "opens as https://" + h[1] + "/stli");
        expected.put("www." + h[2] + "/stli", "opens as https://" + h[2] + "/stli");
        expected.put("https://" + h[3] + "/stli", "opens as https://www." + h[3] + "/stli");
        expected.put(h[4] + "/stli", "opens as https://www." + h[4] + "/stli");
        // a front page and a robots file of such a site
        expected.put("https://www." + h[5] + "/", "opens as https://" + h[5] + "/");
        expected.put(h[6], "opens as https://www." + h[6] + "/");
        expected.put("http://www." + h[7] + "/robots.txt", "opens as http://" + h[7] + "/robots.txt");
        expected.put("https://" + h[8] + "/robots.txt", "opens as https://www." + h[8] + "/robots.txt");
        expected.put("www." + h[9] + "/robots.txt", "opens as https://" + h[9] + "/robots.txt");
        // both spellings given: each asked as written
        expected.put("https://www." + h[10] + "/stli", "opens as https://www." + h[10] + "/stli");
        expected.put("https://" + h[10] + "/stli", "opens as https://" + h[10] + "/stli");
        expected.put("https://www." + h[11] + "/", "opens as https://www." + h[11] + "/");
        expected.put("https://" + h[11] + "/robots.txt", "opens as https://" + h[11] + "/robots.txt");
        // the controls
        for (String other : List.of("m.", "www2.", "wwww.", "en.", "www.www.")) expected.put("https://" + other + h[1] + "/stli", "not opened");
        expected.put("https://m." + h[5] + "/", "not opened");
        expected.put("https://www." + h[1] + "/stlj", "not opened");
        expected.put("https://www." + h[1] + "/stli?page=2", "not opened");
        expected.put(local, "opens as " + local);
        expected.put(numbers, "opens as " + numbers);
        expected.put(local.replace("://", "://www."), "not opened");
        expected.put(numbers.replace("://", "://www."), "not opened");
        Map<String, String> before = new LinkedHashMap<>(), atTheRequest = new LinkedHashMap<>(), wanted = new LinkedHashMap<>();
        Map<String, List<String>> lookedUp = new LinkedHashMap<>(), lookedUpWanted = new LinkedHashMap<>();
        HttpClient client = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String asked = e.getKey();
            before.put(asked, opening(asked));
            List<String> names = new ArrayList<>();
            atTheRequest.put(asked, atTheRequest(client, asked, at, names));
            lookedUp.put(asked, names);
            boolean opens = e.getValue().startsWith("opens as ");
            String as = opens ? e.getValue().substring("opens as ".length()) : null;
            wanted.put(asked, !opens ? "not opened" : as.contains(at) ? "opens" : "answered");
            lookedUpWanted.put(asked, opens && as.contains(at) ? List.of(URI.create(as).getHost()) : List.of());
        }
        wanted.put(numbers.replace("://", "://www."), "no host");   // a last label of digits: java.net.URI reads no host in it, and the JDK's client will not send it
        assertEquals(null, URI.create(numbers.replace("://", "://www.")).getHost(), "java.net.URI reads no host in www. before four numbers");
        System.out.println("[last] 5c www. before a host, added or left off: before the call " + before + "; at the request " + atTheRequest + "; the names the requests went to " + lookedUp + "; the listener was asked " + asked);
        assertEquals(expected, before, "the check before a call is carried out, and the address the tool is handed");
        assertEquals(wanted, atTheRequest, "the check of the request itself");
        assertEquals(lookedUpWanted, lookedUp, "each request went to the host as it was given, never to the model's spelling; a request refused is looked up nowhere");
        assertEquals(List.of("/page-" + n, "/page-" + n), List.copyOf(asked), "the plain forms of the controls reach the listener (the control of the controls)");
    }

    /**
     * The tester's case in the chat, in a conversation saved to a file and opened again: the person typed an address without "www."
     * on an earlier turn, and the model opens it with "www." before it now. The open is not refused, the tool is handed the address
     * as the person typed it, and the request goes to that host (here the suite's resolver says it does not exist, so the tool
     * answers that it cannot reach it, and nothing is asked anywhere else: the stand-in for the web archives is asked nothing). The
     * control: the same turn in a conversation of its own, where nobody gave the address, is refused and looks nothing up.
     */
    @Test
    void aConversationOpenedAgainOpensTheWwwFormOfAnAddressThePersonTypedWithout() throws Exception {
        String host = "park-" + n + ".example.org", typed = host + "/stli", id = "C-2026-11-22-09-30-00";
        ObjectNode open = tool("open", "{\"address\":" + q("https://www." + typed) + "}");
        Map<String, Object> archives = new LinkedHashMap<>();
        Outbound.standIn("127.0.0.1:" + other.getAddress().getPort());   // the stand-in for the web archives, which the tools are written for
        for (String f : List.of("WAYBACK", "ARCHIVE_TODAY", "COMMON_CRAWL", "IA", "IA_FTS")) archives.put(f, HostileToolsTest.archive(f, elsewhere + "/" + f.toLowerCase()));
        try {
            // the control: nobody gave the address in this conversation
            GuardReviewTest.aNewProcess();
            LookupsSeen.watch(x -> x.endsWith(n + ".example.org"));
            List<String> alone = chatIn(Librarian.Session.open(store), "Try once more, please.", open);
            List<String> seenAlone = LookupsSeen.seen();
            // the conversation as a file, opened as a new process opens it
            GuardReviewTest.aNewProcess();
            Path chats = store.root().resolve("catalog").resolve("chat");
            Files.createDirectories(chats);
            Files.writeString(chats.resolve(id + ".jsonl"), M.createObjectNode().put("role", "user").put("content", "open " + typed).put("at", 1795300000000L) + "\n"
                    + M.createObjectNode().put("role", "assistant").put("content", "I could not open it.") + "\n", StandardCharsets.UTF_8);
            Librarian.Session s = Librarian.Session.resume(store, id);
            assertNotNull(s, "the conversation is found by its id");
            LookupsSeen.watch(x -> x.endsWith(n + ".example.org"));
            List<String> told = chatIn(s, "Try once more, please.", open);
            List<String> seen = LookupsSeen.seen();
            System.out.println("[last] 5c the tester's case in a conversation opened again: " + said(told.get(0)) + " (" + told.get(0).replace('\n', ' ') + "), names looked up " + seen + "; the same turn with no earlier conversation: " + said(alone.get(0)) + ", looked up " + seenAlone + "; the archives' stand-in was asked " + askedElsewhere);
            assertEquals("not opened", said(alone.get(0)), "nobody gave the address in a conversation of its own (the control): " + alone);
            assertEquals(List.of(), seenAlone, "and nothing is looked up for it");
            assertFalse(told.get(0).startsWith("NOT OPENED"), "the address the person typed opens with www. before it: " + told.get(0));
            assertTrue(told.get(0).contains("cannot resolve " + host + " ") && told.get(0).contains("https://" + typed) && !told.get(0).contains("www." + host), "the tool was handed the address as the person typed it: " + told.get(0));
            assertEquals(Set.of(host), Set.copyOf(seen), "the request went to the host the person typed, and to no other");
            assertEquals(List.of(), List.copyOf(askedElsewhere), "nothing else was asked");
        } finally {
            for (Map.Entry<String, Object> e : archives.entrySet()) HostileToolsTest.archive(e.getKey(), e.getValue());
        }
    }

    // ---- 6. the vault after an upgrade

    /** Every file of a vault, by its path under it, with what it holds. */
    static Map<String, String> files(Path vault) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (var s = Files.walk(vault)) { for (Path p : s.filter(Files::isRegularFile).toList()) out.put(vault.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8)); }
        return out;
    }

    /**
     * A vault written by 0.8.0 held each note as the records give it, and its manifest the sums of those texts; 0.8.1 writes a note
     * for a viewer ({@code Export.forAViewer}: no picture of the web, no tag), so at the first refresh after the upgrade most notes
     * have a new form. A note the person changed in their editor is theirs, and stays byte for byte as they left it, at every
     * refresh; a note of the library's that nobody touched is brought to its new form once, and the second refresh writes nothing.
     * A note the person changed whose record is gone stays too; one nobody touched goes. Here the vault is written as 0.8.0 wrote
     * it, from the same records ({@link Vault#notes}), and two of its notes are edited by hand. The controls: the edited note's new
     * form differs from what the person left and from what 0.8.0 wrote (a refresh that writes whenever the sum differs, as the code
     * before this reading did, writes over it), the untouched notes that change do change, and a person who puts the library's text
     * back has the note brought to the new form like the others.
     */
    @Test
    void aVaultWrittenBy080KeepsWhatThePersonEditedAndBringsTheRestToTheNewFormOnce() throws Exception {
        String at = "-" + n + ".example.org";
        RawCapture.capture(store, "https://gazette" + at + "/1968/ferry", "The ferry ran from 1911 to 1968.\n\n![The ferry at the north pier](https://gazette" + at + "/1968/ferry.png)\n\nThe company's notice read: <a href=\"https://gazette" + at + "/1968/notice\">the route is closed</a>.\n", "The ferry", "researchzosho-add", "");
        RawCapture.capture(store, "https://almanac" + at + "/timetable", "The 1968 timetable had three crossings a day.\n\n![The 1968 timetable](https://almanac" + at + "/timetable-1968.png)\n", "The timetable", "researchzosho-add", "");
        RawCapture.capture(store, "https://bridge" + at + "/report", "The bridge over Kestrel Sound opened on 30 September 1968. <img src=\"https://bridge" + at + "/seal.png\" alt=\"seal\"> the seal of the board.\n", "The bridge", "researchzosho-add", "");
        RawCapture.capture(store, "https://plain" + at + "/note", "A plain note: the pier was rebuilt in 1971.\n", "The pier", "researchzosho-add", "");
        Path goneEdited = RawCapture.capture(store, "https://gone-edited" + at + "/a", "A page the library let go, with a picture: ![a gull](https://gone-edited" + at + "/gull.png)\n", "Let go, edited", "researchzosho-add", "");
        Path goneUntouched = RawCapture.capture(store, "https://gone-untouched" + at + "/a", "A page the library let go: ![a tern](https://gone-untouched" + at + "/tern.png)\n", "Let go, untouched", "researchzosho-add", "");
        // the vault as 0.8.0 wrote it
        Path vault = tmp.resolve("vault-" + n);
        Map<String, String> as080 = Vault.notes(store);
        StringBuilder manifest = new StringBuilder("# what researchzosho wrote here: path \\t sha256 — a note a person changed is left alone\n");
        for (Map.Entry<String, String> e : as080.entrySet()) {
            Path p = vault.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            manifest.append(e.getKey()).append('\t').append(Vault.sha256(e.getValue())).append('\n');
        }
        Files.writeString(vault.resolve(".researchzosho-vault.tsv"), manifest.toString(), StandardCharsets.UTF_8);
        Map<String, String> notes = new LinkedHashMap<>();   // what each note is called here → its path in the vault
        for (String path : as080.keySet()) for (String[] k : List.of(new String[]{"ferry", "The ferry"}, new String[]{"timetable", "The timetable"}, new String[]{"bridge", "The bridge"}, new String[]{"pier", "The pier"}, new String[]{"gone, edited", "Let go, edited"}, new String[]{"gone, untouched", "Let go, untouched"}))
            if (path.startsWith("Sources/") && as080.get(path).contains("title: \"" + k[1] + "\"")) notes.put(k[0], path);
        assertEquals(6, notes.size(), "every source has its note: " + as080.keySet());
        // the person edits two notes in their editor and writes one of their own
        String timetable = notes.get("timetable"), goneNote = notes.get("gone, edited");
        String edited = as080.get(timetable) + "\nMy own remark: the winter timetable had four crossings; check the 1967 edition.\n";
        String editedGone = as080.get(goneNote).replace("A page the library let go", "A page I keep") + "\nKeep this one.\n";
        Files.writeString(vault.resolve(timetable), edited, StandardCharsets.UTF_8);
        Files.writeString(vault.resolve(goneNote), editedGone, StandardCharsets.UTF_8);
        Files.writeString(vault.resolve("My notes.md"), "# My notes\n\n![a picture of mine](https://example.org/mine.png)\n", StandardCharsets.UTF_8);
        // and the library lets two of its sources go
        Files.delete(goneEdited); Files.delete(goneUntouched);
        Map<String, String> before = files(vault);
        Vault.Outcome first = Vault.generate(store, vault);
        Map<String, String> once = files(vault);
        Vault.Outcome second = Vault.generate(store, vault);
        Map<String, String> twice = files(vault);
        Map<String, String> state = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : notes.entrySet()) {
            String p = e.getValue(), now = once.get(p);
            state.put(e.getKey(), now == null ? "gone" : now.equals(before.get(p)) ? "as it was" : now.equals(Export.forAViewer(as080.get(p))) ? "in the new form" : "written otherwise");
        }
        state.put("the person's own note", once.get("My notes.md") != null && once.get("My notes.md").equals(before.get("My notes.md")) ? "as it was" : "changed");
        List<String> changedByTheSecond = new ArrayList<>();
        for (String p : new TreeSet<>(once.keySet())) if (!once.get(p).equals(twice.get(p))) changedByTheSecond.add(p);
        for (String p : twice.keySet()) if (!once.containsKey(p)) changedByTheSecond.add(p);
        // the person puts the library's text of the timetable back: it is the library's again, and is brought to the new form
        Files.writeString(vault.resolve(timetable), as080.get(timetable), StandardCharsets.UTF_8);
        Vault.Outcome third = Vault.generate(store, vault);
        String putBack = Files.readString(vault.resolve(timetable), StandardCharsets.UTF_8);
        System.out.println("[last] 6 a vault written by 0.8.0, two notes edited by hand, refreshed by this version: " + state + "; the first refresh " + first + ", the second " + second
                + ", files the second changed " + changedByTheSecond + "; the timetable's text put back by the person, then refreshed: " + (putBack.equals(Export.forAViewer(as080.get(timetable))) ? "in the new form" : "as it was") + " (" + third + ")");
        // the controls: the new form differs where it should, so that "as it was" is a refresh that chose not to write
        for (String k : List.of("ferry", "timetable", "bridge", "gone, edited", "gone, untouched")) assertFalse(Export.forAViewer(as080.get(notes.get(k))).equals(as080.get(notes.get(k))), "the note's new form differs from what 0.8.0 wrote (the control): " + k);
        assertFalse(Export.forAViewer(as080.get(timetable)).equals(edited), "and from what the person left (the control)");
        assertEquals(as080.get(notes.get("pier")), Export.forAViewer(as080.get(notes.get("pier"))), "a note with nothing to change has the same form (the control)");
        // what the person wrote stays as they left it
        assertEquals(edited, once.get(timetable), "the note the person edited, after the first refresh");
        assertEquals(edited, twice.get(timetable), "and after the second");
        assertEquals(editedGone, twice.get(goneNote), "the edited note whose record the library let go stays");
        assertEquals(before.get("My notes.md"), twice.get("My notes.md"), "the person's own note");
        // the library's own notes: brought to the new form once, and then left
        for (String k : List.of("ferry", "bridge")) assertEquals("in the new form", state.get(k), k);
        assertEquals("as it was", state.get("pier"));
        assertEquals("gone", state.get("gone, untouched"), "a note of the library's that nobody touched and whose record is gone");
        assertEquals(List.of(), changedByTheSecond, "the second refresh changes no file");
        assertEquals(0, second.written(), second.toString());
        assertEquals(Export.forAViewer(as080.get(timetable)), putBack, "a note the person put back to the library's text is the library's again");
    }

    // ---- 7. a city with a full stop in its name

    /**
     * A complete street address a person wrote is looked up, in the spellings people use: a full stop inside the city's name after
     * a short form (St.Louis, Ft.Worth, Washington D.C.), with a space after it or none; the District of Columbia with its letters
     * after the city's name, repeated as the state or not; the state by its letters or its name, with full stops or a ZIP code; and
     * the city and the state with no comma between them, as the post office writes an address. Each is typed by the person and
     * asked for by the model as typed, in the chat, and the lookup's stand-in sees it. The controls: what could make a host stays
     * turned away before anything is asked, and an address nobody gave is not looked up.
     */
    @Test
    void aCompleteStreetAddressIsLookedUpInTheSpellingsPeopleWrite() throws Exception {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("1600 Pennsylvania Avenue NW, Washington D.C., DC 20500", List.of("1600 Pennsylvania Avenue NW", "Washington D.C.", "DC"));
        expected.put("1600 Pennsylvania Avenue NW, Washington, D.C. 20500", List.of("1600 Pennsylvania Avenue NW", "Washington", "DC"));
        expected.put("1600 Pennsylvania Ave NW, Washington, DC", List.of("1600 Pennsylvania Ave NW", "Washington", "DC"));
        expected.put("1600 Pennsylvania Avenue NW, Washington D.C. 20500", List.of("1600 Pennsylvania Avenue NW", "Washington", "DC"));
        expected.put("1600 Pennsylvania Ave NW, Washington DC", List.of("1600 Pennsylvania Ave NW", "Washington", "DC"));
        expected.put("1600 Pennsylvania Avenue NW, Washington, District of Columbia", List.of("1600 Pennsylvania Avenue NW", "Washington", "DC"));
        expected.put("1200 Market St, St.Louis, MO", List.of("1200 Market St", "St.Louis", "MO"));
        expected.put("1200 Market St, St. Louis, MO 63103", List.of("1200 Market St", "St. Louis", "MO"));
        expected.put("200 Texas St, Ft.Worth, TX", List.of("200 Texas St", "Ft.Worth", "TX"));
        expected.put("200 Texas St, Ft. Worth, Texas 76102", List.of("200 Texas St", "Ft. Worth", "TX"));
        expected.put("1 Roosevelt Sq N, Mt.Vernon, N.Y. 10550", List.of("1 Roosevelt Sq N", "Mt.Vernon", "NY"));
        expected.put("301 River Park Dr, E. St. Louis, IL", List.of("301 River Park Dr", "E. St. Louis", "IL"));
        expected.put("710 E Mullan Ave, Coeur d'Alene, ID 83814", List.of("710 E Mullan Ave", "Coeur d'Alene", "ID"));
        expected.put("101 N Main St, Winston-Salem, NC", List.of("101 N Main St", "Winston-Salem", "NC"));
        expected.put("901 Bagby St, Houston TX 77002", List.of("901 Bagby St", "Houston", "TX"));
        expected.put("414 E 12th St, Kansas City Missouri", List.of("414 E 12th St", "Kansas City", "MO"));
        expected.put("350 5th Ave, New York NY 10118-0110", List.of("350 5th Ave", "New York", "NY"));
        Map<String, List<String>> read = new LinkedHashMap<>();
        Map<String, String> inTheChat = new LinkedHashMap<>();
        List<String> lookedUp = new CopyOnWriteArrayList<>();
        Instruments.RecordsReader real = Instruments.RECORDS;
        // the lookup itself, up to where it would ask the network: what it does not read as street, city and state it answers with its own sentence, and asks nothing
        Instruments.RECORDS = (st, address) -> { if (Instruments.streetParts(address) == null) return Instruments.recordsLive(st, address); lookedUp.add(address); return "the assessor's values and the permits on the street."; };
        try {
            for (String a : expected.keySet()) {
                String[] parts = Instruments.streetParts(a);
                read.put(a, parts == null ? null : List.of(parts));
                GuardReviewTest.aNewProcess();
                List<String> told = chat("What do the public records say about " + a + "?", tool("records", "{\"address\":" + q(a) + "}"));
                inTheChat.put(a, lookedUp.contains(a) ? "looked up" : said(told.get(0)));
            }
            // the controls
            GuardReviewTest.aNewProcess();
            List<String> nobodys = chat("What do the public records say about 1200 Market St, St.Louis, MO?", tool("records", "{\"address\":\"1202 Market St, St.Louis, MO\"}"), tool("records", "{\"address\":\"1200 Market St, Ft.Worth, TX\"}"));
            Map<String, Object> turnedAway = new LinkedHashMap<>();
            for (String made : List.of("1 Main St, collector.example.net, MA", "1 Main St, Spring.field, MA", "1 Main St, collector.example.net MA", "1 Main St, Springfield, collector.example.net/x?d=" + CANARY, "1 Main St, a/b, MA", "1 Main St, Springfield", "1 Main St, Washington",
                    "1 Main St, West Virginia", "901 Bagby St, Houston 77002", "1 Main St, Spring:field TX", "1 Main St, x@y.example TX")) turnedAway.put(made, Instruments.streetParts(made) == null ? "no address" : List.of(Instruments.streetParts(made)));
            long looked = inTheChat.values().stream().filter("looked up"::equals).count();
            System.out.println("[last] 7 street addresses as people write them: read as street, city, state " + read + "; in the chat, typed by the person and asked for as typed: " + looked + " of " + expected.size() + " looked up " + inTheChat
                    + "; an address nobody gave: " + nobodys.stream().map(GuardLastReadingTest::said).toList() + "; what could make a host, or is no whole address: " + turnedAway);
            for (Map.Entry<String, List<String>> e : expected.entrySet()) assertEquals(e.getValue(), read.get(e.getKey()), "read as street, city, state: " + e.getKey());
            for (Map.Entry<String, String> e : inTheChat.entrySet()) assertEquals("looked up", e.getValue(), "a complete address the person wrote is looked up: " + e.getKey());
            assertEquals(List.of("not looked up", "not looked up"), nobodys.stream().map(GuardLastReadingTest::said).toList(), "the house next door and another city, which nobody gave (the control): " + nobodys);
            for (Map.Entry<String, Object> e : turnedAway.entrySet()) assertEquals("no address", e.getValue(), "turned away before anything is asked: " + e.getKey());
        } finally { Instruments.RECORDS = real; }
    }

    // ---- 8. a conversation saved by 0.8.0, opened by this version

    /**
     * A conversation the released 0.8.0 saved, continued after the upgrade. The file was written by 0.8.0 itself (2026-11-21, its
     * own scratch library, a scripted model and a stand-in search and site on the loopback): the person asked about the ferry, the
     * model searched the web, which answered two addresses, opened the first, and replied with an address it wrote itself. Its
     * answers carry none of the marks this version writes down beside an answer (which call it answers, whether it came from
     * outside), so this version reads them by their place and their call's id. Here the site's host and port in it are this test's
     * listener. The person asks for the other result and types an address of their own; the model opens the other result (opened:
     * the search outside gave it), its own address from the earlier reply (not opened: a model's words give none), and the typed
     * one (opened). The controls: the file is of the old form (no marks); the same turn in a conversation of its own, without the
     * earlier one, does not open the other result; and the listener sees what is opened.
     */
    @Test
    void aConversationSavedBy080GivesWhatItsSearchFoundAndNotWhatItsModelWrote() throws Exception {
        String fixture;
        try (var in = GuardLastReadingTest.class.getResourceAsStream("conversation-0.8.0.jsonl")) { assertNotNull(in, "the conversation 0.8.0 saved"); fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        String at = "127.0.0.1:" + site.getAddress().getPort(), id = "C-2026-11-21-12-42-31";
        String other = base + "/almanac/ferry-timetable", models = base + "/made-by-the-model/summary?of=the-notes", typed = base + "/timetable/1968-" + n;
        String person = "Open the other result of that search, and also " + typed + " please.";
        List<JsonNode> lines = new ArrayList<>();
        for (String line : fixture.split("\n")) if (!line.isBlank()) lines.add(M.readTree(line.replace("{site}", at)));
        long marked = lines.stream().filter(m -> "tool".equals(m.path("role").asText()) && (m.has(GivenAddresses.OUTSIDE) || m.has(GivenAddresses.CALL))).count();
        boolean otherInTheSearch = lines.stream().anyMatch(m -> "search".equals(m.path("name").asText()) && m.path("content").asText().contains(other));
        boolean modelsInTheReply = lines.stream().anyMatch(m -> "assistant".equals(m.path("role").asText()) && m.path("content").asText().contains(models));
        // the control: the same turn with no earlier conversation
        GuardReviewTest.aNewProcess();
        List<String> alone = chatIn(Librarian.Session.open(store), person, tool("open", "{\"address\":" + q(other) + "}"), tool("open", "{\"address\":" + q(models) + "}"), tool("open", "{\"address\":" + q(typed) + "}"));
        List<String> askedAlone = List.copyOf(asked);
        asked.clear();
        // the conversation 0.8.0 saved, opened by this version as a new process would open it
        GuardReviewTest.aNewProcess();
        Path chats = store.root().resolve("catalog").resolve("chat");
        Files.createDirectories(chats);
        Files.writeString(chats.resolve(id + ".jsonl"), fixture.replace("{site}", at), StandardCharsets.UTF_8);
        Librarian.Session s = Librarian.Session.resume(store, id);
        assertNotNull(s, "the conversation is found by its id");
        int kept = s.messages().size();
        List<String> told = chatIn(s, person, tool("open", "{\"address\":" + q(other) + "}"), tool("open", "{\"address\":" + q(models) + "}"), tool("open", "{\"address\":" + q(typed) + "}"));
        List<String> savedNow = Files.readAllLines(chats.resolve(id + ".jsonl"), StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        boolean oldLinesAsTheyWere = savedNow.size() > kept && String.join("\n", savedNow.subList(0, kept)).equals(fixture.replace("{site}", at).strip());
        System.out.println("[last] 8 a conversation 0.8.0 saved (" + lines.size() + " messages, answers marked: " + marked + "), continued by this version: the other result of its search, the model's own address, an address the person typed: "
                + told.stream().map(GuardLastReadingTest::said).toList() + ", asked at the site " + asked + "; the same turn with no earlier conversation " + alone.stream().map(GuardLastReadingTest::said).toList() + ", asked " + askedAlone
                + "; the 0.8.0 lines kept as they were: " + oldLinesAsTheyWere);
        // the controls
        assertEquals(0, marked, "the file is of 0.8.0's form: no answer carries this version's marks");
        assertTrue(otherInTheSearch && modelsInTheReply, "the search's answer holds the other result, and the model's reply its own address");
        assertEquals(List.of("not opened", "not opened", "read"), alone.stream().map(GuardLastReadingTest::said).toList(), "with no earlier conversation only the typed address opens: " + alone);
        assertEquals(List.of("/timetable/1968-" + n), askedAlone);
        // continued after the upgrade
        assertEquals(List.of("read", "not opened", "read"), told.stream().map(GuardLastReadingTest::said).toList(), "the other result opens, the model's own address does not, the typed one does: " + told);
        assertEquals(List.of("/almanac/ferry-timetable", "/timetable/1968-" + n), List.copyOf(asked), "and the site sees those two and nothing else");
        assertTrue(oldLinesAsTheyWere, "the lines 0.8.0 wrote stay as they were, the new turn after them");
    }

    // ---- 9. a model nobody serves, met inside a tool

    /** A run's tools: the real fetch, except that the address that holds {@code refusedAt} is answered as a router answers a model nobody serves; and a search that is answered the same. */
    static Researcher.Tools toolsWhoseModelNobodyServes(String refusedAt, String sentence, Runnable met) {
        return new Researcher.Tools() {
            @Override public List<Tool> web(String focus) { return web(focus, ContentPolicy.defaults()); }
            @Override public List<Tool> web(String focus, ContentPolicy policy) {
                Tool fetch = new WebFetchTool().focus(focus).policy(policy);
                Tool guarded = new Tool() {
                    @Override public String name() { return fetch.name(); }
                    @Override public String description() { return fetch.description(); }
                    @Override public ObjectNode parametersSchema(ObjectMapper j) { return fetch.parametersSchema(j); }
                    @Override public boolean fromOutside(JsonNode a) { return fetch.fromOutside(a); }
                    @Override public Set<String> addressArguments(JsonNode a) { return fetch.addressArguments(a); }
                    @Override public String execute(JsonNode a) throws Exception {
                        if (a.path("url").asText("").contains(refusedAt)) { met.run(); throw new DriveClient.NotServed(sentence); }   // the page check's model, asked inside the tool
                        return fetch.execute(a);
                    }
                };
                Tool search = new Tool() {
                    @Override public String name() { return "web_search"; }
                    @Override public String description() { return "Searches the web."; }
                    @Override public ObjectNode parametersSchema(ObjectMapper j) { ObjectNode s = j.createObjectNode(); s.put("type", "object"); s.putObject("properties").putObject("query").put("type", "string"); return s; }
                    @Override public boolean fromOutside(JsonNode a) { return true; }
                    @Override public String execute(JsonNode a) { met.run(); throw new DriveClient.NotServed(sentence); }
                };
                return List.of(search, guarded);
            }
            @Override public BooleanSupplier exhausted() { return () -> false; }
        };
    }

    /**
     * A run whose model has nobody left to serve it ends with the sentence that says so, also when the first to meet the router's
     * answer is a tool that asks the model by itself: the run ends there, and the model is not asked again. Tried where the
     * writer's tool meets it (a page fetched to verify) and where a reader's tool does (a search). The control: the same run, with
     * a model that is served, reads its page, writes its report, and asks the model after the tool as it should.
     */
    @Test
    void aModelNobodyServesMetInsideAToolEndsTheRunThere() throws Exception {
        String sentence = "Every provider that serves the-model through OpenRouter is based or runs in a place this library keeps away from, so nothing was sent.";
        String read = base + "/read-by-a-reader-" + n, checked = base + "/checked-by-the-writer-" + n;
        Map<String, String> ended = new LinkedHashMap<>();
        Map<String, Integer> askedAfter = new LinkedHashMap<>();
        for (String where : List.of("no tool (the control)", "the writer's tool", "a reader's tool")) {
            AtomicInteger askedOf = new AtomicInteger(), whenMet = new AtomicInteger(-1);
            ResearcherTest.ScriptedDrive model = new ResearcherTest.ScriptedDrive() {
                { criticWantsMore = false; }
                @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
                    askedOf.incrementAndGet();
                    List<String> names = names(tools);
                    if (names.equals(List.of("done"))) return call("done", M.createObjectNode().put("summary", "closed"));
                    int turn = assistantTurns(history) + 1;
                    if (names.contains("write_section")) return switch (turn) {
                        case 1 -> call("web_fetch", M.createObjectNode().put("url", checked));   // a page no reader read, fetched to verify
                        case 2 -> call("write_section", M.createObjectNode().put("heading", "Answer").put("text", "The ferry ran from 1911 to 1968 (" + read + ")."));
                        default -> call("done", M.createObjectNode().put("summary", "written"));
                    };
                    return switch (turn) {
                        case 1 -> where.startsWith("a reader") ? call("web_search", M.createObjectNode().put("query", "kestrel sound ferry")) : call("web_fetch", M.createObjectNode().put("url", read));
                        case 2 -> call("note", M.createObjectNode().put("claim", "the ferry ran from 1911 to 1968").put("source", read).put("quote", "The ferry ran from 1911 to 1968."));
                        default -> call("done", M.createObjectNode().put("summary", "from the page"));
                    };
                }
            };
            Researcher.Ask ask = new Researcher.Ask("What do " + read + " and " + checked + " say about the ferry?", "depth", 30, List.of("what do the pages say?"), "both", List.of());
            String how;
            try {
                new Researcher(model, model, toolsWhoseModelNobodyServes(where.startsWith("the writer") ? "/checked-by-the-writer-" : "/a-page-nobody-asks-for-", sentence, () -> whenMet.set(askedOf.get())), s -> { }, 1, store).run(ask, "");
                how = "a report";
            } catch (DriveClient.NotServed e) { how = "the sentence: " + e.getMessage(); }
            catch (RuntimeException e) { how = e.getClass().getSimpleName() + ": " + e.getMessage(); }
            ended.put(where, how);
            askedAfter.put(where, whenMet.get() < 0 ? -1 : askedOf.get() - whenMet.get());
            if (where.startsWith("no tool")) assertEquals(1, asked.stream().filter(x -> x.contains("/checked-by-the-writer-" + n)).count(), "the control's writer fetched its page, and the listener saw it: " + asked);
        }
        System.out.println("[last] 9 a run whose model nobody serves, met inside a tool: the run ended with " + ended + "; times the model was asked after the tool met it (-1: no tool met it): " + askedAfter);
        assertEquals("a report", ended.get("no tool (the control)"), "a run whose model is served writes its report, the writer's fetch and all");
        assertEquals(-1, askedAfter.get("no tool (the control)"));
        for (String where : List.of("the writer's tool", "a reader's tool")) {
            assertEquals("the sentence: " + sentence, ended.get(where), "the run ends with the sentence, met in " + where);
            assertEquals(0, askedAfter.get(where), "and the model is not asked again after " + where + " met it");
        }
    }

    // ---- 10. the exclamation mark in a file written for another program

    /**
     * In Markdown written for another program a picture's mark is left out, and a writer's own exclamation mark stays where it can
     * begin no picture. The last reading said the mark that should stay is dropped, for two texts. Each is read here by the
     * rule Markdown itself has: in the first the mark before the bracket does begin a picture (an address in round brackets
     * follows), and in the second it does too once the text defines the reference it names; left in, a reader of the file would
     * ask the address. So both are written without it, and with no definition anywhere the second keeps its mark. Three more: a
     * mark with a space before the bracket, a picture of the library's own after an exclamation, and a line of code.
     */
    @Test
    void anExclamationMarkStaysWhereItCanBeginNoPicture() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        // the last reading's two
        expected.put("Wow!![not a picture](x)\n", "Wow[not a picture](x)\n");
        expected.put("What a crossing![1] The ferry left at noon.\n\n[1]: https://example.org/gazette\n", "What a crossing[1] The ferry left at noon.\n\n[1]: https://example.org/gazette\n");
        // the same sentence in a text that defines no reference: the mark is the writer's own, and stays
        expected.put("What a crossing![1] The ferry left at noon.\n\nSources: 1. the harbour gazette of 1968.\n", "What a crossing![1] The ferry left at noon.\n\nSources: 1. the harbour gazette of 1968.\n");
        // three more
        expected.put("It runs again! [The gazette](https://example.org/g) says so, and so does the almanac!\n", "It runs again! [The gazette](https://example.org/g) says so, and so does the almanac!\n");
        expected.put("What a view!\n\n![The ferry](/images/0123456789abcdef.png)\n\nAnd what a crossing!\n", "What a view!\n\n![The ferry](/images/0123456789abcdef.png)\n\nAnd what a crossing!\n");
        expected.put("The check is `if (!seen[0] && !done) return;` and it holds![2]\n", "The check is `if (!seen[0] && !done) return;` and it holds![2]\n");
        // and a picture of the web is written as the link it holds, whatever stands before it
        expected.put("Hurrah![The gazette](https://example.org/g.png) says so.\n", "Hurrah[The gazette](https://example.org/g.png) says so.\n");
        Map<String, String> got = new LinkedHashMap<>();
        for (String text : expected.keySet()) got.put(text, Export.forAViewer(text));
        int kept = 0;
        for (Map.Entry<String, String> e : got.entrySet()) if (e.getKey().equals(e.getValue())) kept++;
        System.out.println("[last] 10 the exclamation mark in a file for another program: " + kept + " of " + got.size() + " texts written as they stand; each as written: " + got.toString().replace("\n", "\\n"));
        for (Map.Entry<String, String> e : expected.entrySet()) assertEquals(e.getValue(), got.get(e.getKey()), "written for another program: " + e.getKey().replace("\n", "\\n"));
    }
}
