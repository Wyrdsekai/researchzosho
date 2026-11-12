package org.researchzosho.librarian;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chrome in the person's language: every fixed sentence the pages emit has a Japanese entry (every page rendered in Japanese on a
 * stand-in library, and no text fell back to English); which language a request gets, in order; three pages read in Japanese; the
 * command line's home sentences in Japanese. No network, no model, nothing of the box: the suite speaks English by its own property.
 */
class ChromeTest {

    static HttpResponse<String> get(HttpClient c, String url, String cookie, String acceptLanguage) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        if (acceptLanguage != null) b.header("Accept-Language", acceptLanguage);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Finding f(String id, String title, String body) {
        return new Finding(id, title, List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's own page")), List.of(), null, body);
    }

    private Supplier<Researcher.Drive> drives;
    @BeforeEach void standIns() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; Career.DRIVE = () -> null; }
    @AfterEach void back() { Explain.DRIVES = drives; Career.DRIVE = Explain::drive; Chrome.clear(); }

    /** A library with something on every page: claims, a report and its run, an open question, a kept search, a home search, a job seeker, two videos, a reader, a request to be let in, a run waiting. */
    static LibraryStore stocked(Path tmp) throws Exception {
        LibraryStore store = VideoRecallTest.storeWithTwoVideos(tmp);
        long now = System.currentTimeMillis();
        store.write(f("F-0001-gears", "The gears were cut by hand", "The museum states the **gears** were cut with files.\n"));
        store.write(f("F-0002-teeth", "Tooth profiles measured by CT", "CT scans show triangular teeth.\n"));
        store.write(new Investigation("I-0001-which-book-on-eclipses", "Which book on eclipses for a beginner?", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of("F-0001-gears"), List.of("Which edition is the fullest?"), "## Answer\n\nA table of three.\n"));
        PastFieldsTest.done(new Jobs(store, j -> ""), "J-0001", "", "Which book on eclipses for a beginner?", "I-0001-which-book-on-eclipses", now);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- gears--cutting — how the gears were made\n");
        store.frontier("gap person", "Who measured the tooth profiles first, and with what instrument?");
        Serials.add(store, "eclipse-news", "new books on eclipses", 7);
        Home.Search s = Home.start(store, "Ada Example");
        Home.setWant(store, s, "place", "decided", "Somerville, MA", "you", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "you", "");
        Home.addKeepOut(store, s, "Broadway");
        try { Career.provide(store, "I want to keep leading a team, in Chicago.", "First Person", false); } catch (Exception ignored) { }   // the reading needs a model; the person's page stands without it
        Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
        Patrons.set(store, "did:key:reader", "A Reader", Patrons.Level.read);
        AccessRequests.request(store, "did:key:web-aaaaaa", "Someone Asking", "let me read, please", Passwords.hash("a-long-enough-password"));
        ObjectNode args = new ObjectMapper().createObjectNode(); args.put("question", "What did the museum's catalogue say about the dividing plate?"); args.put("mode", "broad");
        new Jobs(store, j -> "").submit("research", "did:key:me", args);
        return store;
    }

    static final List<String> PAGES = List.of("/", "/past", "/updates", "/connect", "/keep", "/settings", "/search?q=gears", "/ask?q=gears", "/entry/F-0001-gears", "/entry/I-0001-which-book-on-eclipses",
            "/entry/I-0001-which-book-on-eclipses?beside=1", "/subjects", "/changes", "/dossier", "/questions", "/questions?show=all&type=check", "/inbox", "/add", "/sources", "/jobs", "/jobs/J-0001", "/research", "/research?q=Who+measured+the+tooth+profiles",
            "/explain", "/help", "/map", "/login", "/join?code=nope", "/account", "/owned", "/suggest", "/homes", "/homes?search=ada-example", "/career", "/career?seeker=first-person", "/video", "/video?q=tea", "/patents", "/chat?new=1", "/no-such-page",
            "/seeks", "/perspectives", "/submit", "/forecasts", "/bridges", "/launch", "/db", "/books", "/podcasts", "/images");

    /** English the pages used to show inside a translated sentence, made in code: none may stand in a page in another language. */
    static final List<String> ENGLISH_MADE = List.of(" checked, the default; quick ", "(a first guess: nothing has been timed on this model yet)");

    /**
     * English the pages showed in every language until 2026-11-10, built from literals that never went through the words, or a sentence
     * the protocol says in English for a program: none may stand in a page in another language.
     */
    static final List<String> ENGLISH_LEFT = List.of("Access is open", "a month the library stops at", "Every line is a claim; a dashed line", "a person, a place, a work, a subject", "A person or an organisation",
            "Everything the library holds on one subject", "To request a dossier on someone new", "No dossiers yet.", "Family history (genealogy mode)", "pornography, and gore", "is running here", "This library holds",
            "Fields that are on:", "Its documentation:", "Nothing is imported yet.", "Shows and episodes on a subject", "No show is followed yet.", "Follow a show below, or from a terminal", "Podcasts reports",
            "What you liked, read against the long tail", "No Calibre library is set", "Books reports", "No preferences file yet", "The store holds", "Passkeys need an address", "Passkeys need an https address",
            "The passkey was not accepted", "No passkey was used", "Exa, a search engine for the web", "Tavily, a search engine for the web", ">The model server<", ">Your own search service<", "The map hit an error",
            "Names the map knows best", "Type a name: a person");

    @Test
    void everyPageRenderedInEveryLanguageFallsBackOnNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = stocked(tmp);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        Chrome.MISSING.clear();
        List<String> failed = new ArrayList<>();
        List<String> codes = Chrome.available().subList(1, Chrome.available().size());   // every language with a file: Japanese, and each one dropped in beside it
        assertTrue(codes.contains("ja"));
        try {
            for (String code : codes) {
                for (String path : PAGES) {
                    var r = get(c, base + path, "rz_token=" + token, code);
                    if (r.statusCode() >= 500) failed.add(code + " " + path + " → " + r.statusCode() + ": " + r.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").substring(0, Math.min(300, r.body().length())));
                    else if (r.statusCode() == 200 && !r.body().contains("<html lang=\"" + code + "\">")) failed.add(path + " is not in " + code);
                    else if (r.statusCode() == 200) {
                        for (String en : ENGLISH_MADE) if (r.body().contains(en)) failed.add(code + " " + path + " shows English made in code: " + en);
                        for (String en : ENGLISH_LEFT) if (r.body().contains(en)) failed.add(code + " " + path + " shows English that never went through the words: " + en);
                    }
                }
                var visitor = get(c, base + "/login", null, code);   // the sign-in page as a visitor sees it, with the ask-to-be-let-in form
                assertTrue(visitor.body().contains("<html lang=\"" + code + "\">"), visitor.body());
            }
        } finally { d.stop(); }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
        List<String> missing = new ArrayList<>(new TreeSet<>(Chrome.MISSING));
        assertTrue(missing.isEmpty(), missing.size() + " text(s) a language file lacks (code<tab>text):\n" + String.join("\n", missing));
    }

    @Test
    void theLanguageIsChosenInOrder(@TempDir Path tmp) throws Exception {
        assertEquals(List.of("en", "ja"), Chrome.available().subList(0, 2), "English, then Japanese, then each language with a file");
        assertEquals("ja", Chrome.fromAccept("ja,en;q=0.5")); assertEquals("en", Chrome.fromAccept("en-US,en;q=0.9,ja;q=0.8")); assertEquals("ja", Chrome.fromAccept("sv-SE,sv;q=0.9,ja-JP;q=0.4"));
        assertNull(Chrome.fromAccept("sv")); assertNull(Chrome.fromAccept("")); assertNull(Chrome.fromAccept(null)); assertEquals("ja", Chrome.fromAccept("en;q=0.3, ja;q=0.7"));
        // a regional form is its language's file; Chinese in traditional characters is not the simplified file's, and the browser's next language is asked
        assertEquals("pt", Chrome.codeOf("pt-BR")); assertEquals("pt", Chrome.codeOf("pt_BR.UTF-8")); assertEquals("zh", Chrome.codeOf("zh-CN")); assertEquals("zh", Chrome.codeOf("zh-Hans"));
        assertEquals("zh", Chrome.codeOf("zh-Hans-HK")); assertEquals("ja", Chrome.codeOf("ja_JP.UTF-8")); assertEquals("ja", Chrome.codeOf("日本語")); assertEquals("de", Chrome.codeOf("Deutsch")); assertEquals("ru", Chrome.codeOf("Russian"));
        for (String t : List.of("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW", "zh_TW.UTF-8")) assertEquals(Chrome.TRADITIONAL, Chrome.codeOf(t), t);
        assertEquals("ja", Chrome.fromAccept("zh-TW,ja;q=0.8"), "zh-TW passes over the simplified file to the next language");
        for (String code : Chrome.available()) assertEquals(code, Chrome.fromAccept(code + "-XX," + code + ";q=0.9"), "every language with a file, from its regional form");
        if (Chrome.available().contains("zh")) { assertEquals("zh", Chrome.fromAccept("zh-CN")); assertEquals("zh", Chrome.fromAccept("zh-Hans")); assertEquals("zh", Chrome.fromAccept("zh-TW,zh;q=0.9")); }
        if (Chrome.available().contains("pt")) assertEquals("pt", Chrome.fromAccept("pt-BR,pt;q=0.9"));
        // the query, then the cookie, then the person's own language, then the browser's, then the library's, then English
        String realHome = System.getProperty("user.home");
        try {
            Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
            Patrons.Patron me = new Patrons.Patron("did:key:me", "Me", "web");
            assertEquals("en", Chrome.choose(store, me, null, null, null), "nothing says a language: English");
            assertEquals("en", Chrome.choose(store, me, null, null, "sv"), "a browser whose language the chrome is not written in: English");
            assertEquals("ja", Chrome.choose(store, me, null, null, "ja"), "the browser's");
            Config.set("RESEARCHZOSHO_LANGUAGE", "Japanese");
            assertEquals("ja", Chrome.choose(store, me, null, null, null), "the library writes its reports in Japanese: the pages too, with nothing nearer");
            assertEquals("en", Chrome.choose(store, me, null, null, "en-GB"), "the browser's language outranks the library's");
            ReadIn.set(store, me, "ja");
            assertEquals("ja", Chrome.choose(store, me, null, null, "en"), "the person's own language outranks the browser's");
            assertEquals("en", Chrome.choose(store, me, null, "en", "en"), "the cookie the switch set outranks the person's own");
            assertEquals("ja", Chrome.choose(store, me, "ja", "en", "en"), "the query outranks the cookie");
            assertEquals("en", Chrome.choose(store, me, "xx", "en", "ja"), "a language no file exists for is passed over");
            // the process's own language, for the terminal: the seam first, then the library's setting
            assertEquals("en", Chrome.fromEnvironment(), "the suite's property pins English whatever LANG the box has");
            System.setProperty(Chrome.PROPERTY, "ja");
            assertEquals("ja", Chrome.fromEnvironment());
            System.setProperty(Chrome.PROPERTY, "");
            assertEquals("ja", Chrome.fromEnvironment(), "with no seam, the library's language setting");
        } finally { System.setProperty(Chrome.PROPERTY, "en"); System.setProperty("user.home", realHome); Config.invalidate(); }
        // over http: the switch sets the cookie, and the cookie is read on the next load
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            var plain = get(c, base + "/past", null, null);
            assertTrue(plain.body().contains("<html lang=\"en\">") && plain.body().contains(">Past<"), plain.body());
            assertTrue(plain.body().contains("hreflang=\"ja\"") && plain.body().contains("日本語") && plain.body().contains("href=\"/past?lang=ja\""), "the switch offers Japanese: " + plain.body());
            // a small menu, not a row: the language in use, and each language with a file by its own name, English last
            String menu = plain.body().substring(plain.body().indexOf("<details class=\"lang-switch\">"), plain.body().indexOf("</details>", plain.body().indexOf("<details class=\"lang-switch\">")));
            for (String code : Chrome.available()) assertTrue(menu.contains("hreflang=\"" + code + "\" lang=\"" + code + "\"") && menu.contains(">" + Chrome.nativeName(code) + "</a>"), code + " in " + menu);
            assertTrue(menu.lastIndexOf("hreflang=\"en\"") > menu.lastIndexOf("hreflang=\"ja\""), menu);
            var switched = get(c, base + "/past?lang=ja", null, null);
            assertTrue(switched.body().contains("<html lang=\"ja\">"), switched.body());
            assertTrue(switched.headers().allValues("set-cookie").stream().anyMatch(v -> v.startsWith("rz_lang=ja;")), "the switch is kept in a cookie: " + switched.headers().map());
            assertTrue(switched.body().contains("href=\"/past?lang=en\"") && switched.body().contains("hreflang=\"ja\" lang=\"ja\" aria-current=\"true\""), switched.body());
            var kept = get(c, base + "/past", "rz_lang=ja", "en");
            assertTrue(kept.body().contains("<html lang=\"ja\">"), "the cookie outranks the browser's language: " + kept.body());
            var browser = get(c, base + "/past", null, "ja,en;q=0.5");
            assertTrue(browser.body().contains("<html lang=\"ja\">"), browser.body());
            var switchedBack = get(c, base + "/past?lang=en&q=x", "rz_lang=ja", "ja");
            assertTrue(switchedBack.body().contains("<html lang=\"en\">") && switchedBack.body().contains("href=\"/past?q=x&amp;lang=ja\""), "the switch keeps the page's own query: " + switchedBack.body());
        } finally { d.stop(); }
    }

    @Test
    void threePagesReadInJapanese(@TempDir Path tmp) throws Exception {
        LibraryStore store = stocked(tmp);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            String past = get(c, base + "/past", "rz_token=" + token, "ja").body();
            assertTrue(past.contains("<h2 id=\"reports\">レポート</h2>") && past.contains(">履歴<") && past.contains(">チャット<") && past.contains(">更新") && past.contains(">管理<") && past.contains("すべてのページ"), past);
            assertTrue(past.contains("に完了。 レポートを保存しました") || past.contains("レポートを保存しました"), "the report's line of state: " + past);
            assertTrue(past.contains("住まい探し：Ada Example") && past.contains("まだ検索していません。"), "the home search's row: " + past);
            String home = get(c, base + "/homes", "rz_token=" + token, "ja").body();
            assertTrue(home.contains("<h1>住まい探し</h1>") && home.contains(">決めたこと<") && home.contains("ライブラリにもっと伝える") && home.contains("いま検索"), home);
            assertTrue(home.contains("<td>場所</td>") && home.contains("<td>決定</td>"), "the topics and their states in Japanese: " + home);
            String login = get(c, base + "/login", null, "ja").body();
            assertTrue(login.contains("<h1>サインイン</h1>") && login.contains(">入室を申請する<") && login.contains("あなたの名前") && login.contains("パスキーでサインイン"), login);
            String chat = get(c, base + "/", "rz_token=" + token, "ja").body();
            assertTrue(chat.contains("司書に聞く…") && chat.contains(">会話<") && chat.contains("Enterで送信"), chat);
            assertTrue(chat.contains("textContent='司書が考えています…") , "the page's own script speaks Japanese too: " + chat);
            String english = get(c, base + "/past", "rz_token=" + token, null).body();
            assertTrue(english.contains("<h2 id=\"reports\">Reports</h2>") && english.contains("Home search: Ada Example"), "the same page in English, unchanged: " + english);
        } finally { d.stop(); }
    }

    @Test
    void theCommandLinesHomeSentencesFollowTheEnvironment(@TempDir Path tmp) throws Exception {
        Path h = tmp.resolve("h");
        LibraryStore lib = new LibraryStore(h.resolve("researchzosho-library")); lib.init();
        Home.Search s = Home.start(lib, "Ada Example");
        Home.setWant(lib, s, "place", "decided", "Somerville, MA", "you", "");
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", h.toString());
        QuietHome.quiet(h);
        Config.invalidate();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            System.setProperty(Chrome.PROPERTY, "ja");
            LibrarianCli.run(new String[]{"researchzosho", "home", "cut", "Ada Example"}, "http://127.0.0.1:1", "m");
            String ja = out.toString(StandardCharsets.UTF_8);
            assertTrue(ja.contains("条件に合うとは："), "the home command's fixed sentence in Japanese: " + ja);
            out.reset();
            System.setProperty(Chrome.PROPERTY, "en");
            LibrarianCli.run(new String[]{"researchzosho", "home", "cut", "Ada Example"}, "http://127.0.0.1:1", "m");
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("What counts as a fit: "), out.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(was); System.setProperty(Chrome.PROPERTY, "en"); Chrome.fromEnvironmentNow(); System.setProperty("user.home", realHome); Config.invalidate(); }
        assertTrue(Chrome.DEFAULT.english(), "the process speaks English again");
    }

    @Test
    void theWordsAndTheDatesInJapanese() {
        Chrome ja = Chrome.in("ja"), en = Chrome.in("en");
        assertEquals("履歴", ja.t("Past")); assertEquals("Past", en.t("Past"));
        assertEquals("3件の主張", ja.n(3, "{0} claim", "{0} claims")); assertEquals("1 claim", en.n(1, "{0} claim", "{0} claims")); assertEquals("2 claims", en.n(2, "{0} claim", "{0} claims"));
        assertEquals("2025年10月14日", ja.day("2025-10-14")); assertEquals("2025-10-14", en.day("2025-10-14")); assertEquals("not a day", ja.day("not a day"));
        assertEquals("2026年11月5日 10:22 UTC", ja.when("2026-11-05T10:22:07Z")); assertEquals("2026-11-05 10:22 UTC", en.when("2026-11-05T10:22:07Z"));
        assertTrue(ja.dayTime(Instant.now().toString()).startsWith("今日 ")); assertTrue(en.dayTime(Instant.now().toString()).startsWith("today, "));
        assertEquals("2025年10月14日", ja.dayTime("2025-10-14")); assertEquals("14 Oct 2025", en.dayTime("2025-10-14"));
        Chrome.MISSING.clear();
        assertEquals("No such text", ja.t("No such text"), "a text the file lacks is said in English");
        assertTrue(Chrome.MISSING.contains("ja\tNo such text"), "and the miss is written down");
        Chrome.MISSING.clear();
        assertEquals(Chrome.ENGLISH, Chrome.in("xx"), "a language with no file is English");
        assertEquals("日本語", Chrome.nativeName("ja")); assertEquals("English", Chrome.nativeName("en"));
        // the numbers and the list's stops as the language writes them
        assertEquals("11,979", ja.number(11979)); assertEquals("11,979", en.number(11979)); assertEquals("、", ja.sep()); assertEquals(", ", en.sep());
        // every language with a file writes a day and a number by its locale, and today in its own words
        for (String code : Chrome.available().subList(1, Chrome.available().size())) {
            Chrome c = Chrome.in(code);
            assertEquals(DateTimeFormatter.ofLocalizedPattern("yMMMd").withLocale(Locale.forLanguageTag(code)).format(LocalDate.of(2025, 10, 14)), c.day("2025-10-14"), code);
            assertEquals(NumberFormat.getIntegerInstance(Locale.forLanguageTag(code)).format(11979), c.number(11979), code);
            assertTrue(c.dayTime(Instant.now().toString()).startsWith(c.t("today") + " "), code);
        }
    }
}
