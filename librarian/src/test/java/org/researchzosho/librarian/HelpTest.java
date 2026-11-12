package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Version;
import org.researchzosho.mcp.McpServer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HelpTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test
    void theProgramCarriesItsOwnDocumentationCutIntoSections() {
        List<String> docs = Help.docs();
        assertTrue(docs.contains("LIBRARIAN_HOWTOUSE.md") && docs.contains("README.md") && docs.contains("CHANGELOG.md"), docs.toString());
        assertFalse(docs.contains("RELEASING.md") || docs.contains("CONTRIBUTING.md"), "the maintainers' notes are not the person's help");
        assertTrue(Help.sections().size() > 50, "the documentation answers by section: " + Help.sections().size());
        assertTrue(Help.doc("README.md").contains("ResearchZosho"));
        assertEquals("", Help.doc("../../etc/passwd"), "only the documents the program carries, by their own names");
    }

    @Test
    void aDocumentIsCutAtItsHeadingsAndNotAtACommentInACodeBlock() {
        List<Help.Section> s = Help.split("X.md", "# Title\nIntro words, long enough to be a section of their own here.\n## Updating\nType researchzosho update now to bring the latest release.\n```\n# not a heading\n```\nmore words in the same section.\n## Tiny\nshort\n"
                + "## 0.6.6\n### Fixed\n- A run that waited for the model could not be stopped; now it can, within seconds.\n");
        assertEquals(List.of("Title", "Updating", "0.6.6: Fixed"), s.stream().map(Help.Section::heading).toList(), "a section too short to answer anything is left out, and one below the second level carries the heading above it");
        assertTrue(s.get(1).text().contains("# not a heading"));
    }

    @Test
    void aQuestionFindsTheSectionsThatAnswerIt() {
        List<Help.Section> found = Help.find("how do I update to the latest release", 3);
        assertTrue(found.stream().anyMatch(x -> x.heading().toLowerCase().contains("updat")), found.stream().map(Help.Section::heading).toList().toString());
        assertTrue(Help.find("the", 3).isEmpty(), "words that mean nothing find nothing");
    }

    @Test
    void itSaysWhatIsRunningWhatItHoldsAndWhereToAskWhatTheDocumentationDoesNotCover(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding(store.nextFindingId("A claim"), "A claim the library holds", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test",
                Instant.now().toString(), "2026-10-28", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/a", "n/a", "a page")), List.of(), null, "A claim the library holds.\n"));
        String st = Help.state(store);
        assertTrue(st.startsWith("ResearchZosho " + Version.string() + " is running here."), st);
        assertTrue(st.contains("This library holds claims: 1,") && st.contains("The model it talks to by default is") && st.contains("Web search:")
                && st.contains("Fields that are on:") && st.contains("career (") && st.contains("The chat's own commands:") && st.contains("/career (") && st.contains("The pages:"), st);
        assertTrue(st.contains("does not answer now"), "the test's drive is a closed port, and the state says so: " + st);
        assertTrue(st.contains("Web search: ") && (st.contains("answers searches") || st.contains("No SearXNG answers searches")), "the web search is said as runs meet it: " + st);
        String look = Help.lookup(store, "how do I update");
        assertTrue(look.startsWith("From its own documentation:") && look.contains("What is running here: ResearchZosho " + Version.string()), "/help and words: the answer first, a short state after it: " + look);
        String none = Help.answer(store, "xyzzyplugh frobnicate");
        assertTrue(none.contains("Its own documentation says nothing on \"xyzzyplugh frobnicate\"") && none.contains(Help.ISSUES), none);
        String how = Help.answer(store, "how do I update");
        assertTrue(how.contains("From its own documentation:") && how.contains("## LIBRARIAN_HOWTOUSE: ") && how.contains(Help.ISSUES), how);
        assertTrue(Help.answer(store, "").contains("Its documentation: "), "asked nothing, it lists what it carries");
    }

    /** The state paragraph's English, which the Help page and the chat's /help no longer show in another language. */
    static final List<String> HELP_ENGLISH = List.of("is running here", "This library holds", "Search by meaning is", "The model it talks to by default", "Named models it can also use", "Web search:", "SearXNG answers searches",
            "Wikipedia's search is the last resort", "With only Wikipedia's search", "Fields that are on", "The chat's own commands", "The pages:", "The fields' own pages", "Its documentation:", "From its own documentation",
            "What is running here", "If this does not answer it", "The model is ", "kinship relations", "citations from the record", "and words after it to look something up");

    @Test
    void theStateParagraphIsInThePersonsLanguageAndWhatAProgramReadsStaysEnglish(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Chrome ja = Chrome.in("ja");
        Chrome.MISSING.clear();
        String st = Help.state(store, ja), page = Help.lookup(store, "", ja), look = Help.lookup(store, "how do I update", ja);
        assertTrue(st.startsWith("ResearchZosho " + Version.string() + " がここで動いています。") && st.contains("このライブラリにあるもの：主張 0件、レポート 0件、ウェブから保存したページ 0件。"), st);
        assertTrue(st.contains("既定で話すモデルは") && st.contains("いまは応答しません") && st.contains("ウェブ検索：") && st.contains("有効な分野：") && st.contains("系譜（親族関係、GEDCOM の読み込みと書き出し）")
                && st.contains("チャット自身のコマンド：") && st.contains("ページ：ホーム、質問、"), "each sentence of the state in Japanese, a field by its name and what it does: " + st);
        for (String en : HELP_ENGLISH) assertFalse(st.contains(en) || page.contains(en), "\"" + en + "\" in the Japanese state: " + page);
        assertTrue(page.contains("説明書：") && page.contains("中のことなら何でも聞いてください。"), page);
        assertTrue(look.startsWith("説明書から：") && look.contains("ここで動いているもの：ResearchZosho " + Version.string() + " がここで動いています。") && look.contains("モデルは "), "the sections in their own words, the frame and the short state in Japanese: " + look);
        assertTrue(Chrome.MISSING.stream().noneMatch(m -> m.startsWith("ja\t")), "the Japanese file has every sentence of the state: " + Chrome.MISSING);
        // the page and the chat's own turn speak the person's language
        Chrome.here("ja");
        try {
            String body = HelpPage.body(store, "", "");
            assertTrue(body.contains("がここで動いています") && !body.contains("is running here"), body);
            Librarian.Session session = Librarian.Session.open(store, Patrons.Patron.PERSON);
            Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "/help how do I update"));
            String reply = CareerInterviewTest.lastReply(store, session.id);
            assertTrue(reply.startsWith("説明書から：") && reply.contains("ここで動いているもの："), reply);
        } finally { Chrome.clear(); }
        // what a program reads (library_help, the A2A card) stays English
        String wire = new LibraryProtocol(store).help(M.createObjectNode().put("question", "").set("patron", M.createObjectNode().put("did", "person").put("name", "tester").put("runtime", "test"))).path("summary").asText();
        assertTrue(wire.startsWith("ResearchZosho " + Version.string() + " is running here. This library holds claims: 0, write-ups: 0, pages kept from the web: 0.") && wire.contains("Its documentation: "), wire);
    }

    @Test
    void theChatTheProtocolMcpAndThePagesAllReachIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Librarian.TOOLS.contains("library_help"), "the chat can call it");
        boolean mcp = false; for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_help")) mcp = true;
        assertTrue(mcp, "a program using the library over MCP can ask it too");
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "tester").put("runtime", "test");
        JsonNode r = new LibraryProtocol(store).help(a.put("question", "how do I update"));
        assertEquals(Version.string(), r.path("version").asText());
        assertTrue(r.path("sections").size() > 0 && r.path("issues").asText().equals(Help.ISSUES) && r.path("summary").asText().contains("From its own documentation:"));
        Librarian.Session session = Librarian.Session.open(store, Patrons.Patron.PERSON);
        Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "/help how do I update"));
        assertTrue(CareerInterviewTest.lastReply(store, session.id).contains("From its own documentation:"), "/help and words in the browser chat, answered without the model");
        Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", session.id, "say", "/help"));
        assertTrue(CareerInterviewTest.lastReply(store, session.id).contains("- /help and some words, to look them up in the program's own documentation"));
        String page = HelpPage.body(store, "update", "");
        assertTrue(page.contains("Look it up") && page.contains("From its own documentation") && page.contains("<a href=\"/help?doc=LIBRARIAN_HOWTOUSE.md\">"), page);
        assertTrue(HelpPage.body(store, "", "README.md").contains("Back to Help"));
        assertTrue(HelpPage.body(store, "", "SECRET.md").startsWith("<p>There is no document called SECRET.md."));
        assertTrue(new Librarian(store, null, Patrons.Patron.PERSON, session).systemPrompt().contains("is answered with library_help, from the program's own documentation and its live state"),
                "the chat is told to answer questions about the program from it, not from memory");
    }

    @Test
    void anInstalledCopyFetchesItsOwnVersionsDocumentationOnceAndReadsItOffline(@TempDir Path tmp) throws Exception {
        Map<String, Path> was = Help.tree;
        Function<String, String> fetch = Help.FETCH;
        Supplier<String> ref = Help.REF;
        AtomicInteger asked = new AtomicInteger();
        try {
            Help.tree = null; Help.REF = () -> "v9.9.9";
            Help.FETCH = url -> {
                asked.incrementAndGet();
                if (url.equals("https://api.github.com/repos/Wyrdsekai/researchzosho/contents/docs?ref=v9.9.9"))
                    return "[{\"name\":\"LIBRARIAN_HOWTOUSE.md\",\"type\":\"file\"},{\"name\":\"RELEASING.md\",\"type\":\"file\"},{\"name\":\"brand\",\"type\":\"dir\"}]";
                if (url.equals("https://raw.githubusercontent.com/Wyrdsekai/researchzosho/v9.9.9/docs/LIBRARIAN_HOWTOUSE.md"))
                    return "# Using it\n## Updating\nType researchzosho update now to bring the latest release; it checks the release's checksums first.\n";
                if (url.equals("https://raw.githubusercontent.com/Wyrdsekai/researchzosho/v9.9.9/README.md")) return "# ResearchZosho\nA research library that keeps what it finds, with citations.\n";
                return null;
            };
            Help.forget();
            assertEquals(List.of("LIBRARIAN_HOWTOUSE.md", "README.md"), Help.docs(), "the release's own documents, the maintainers' notes left out");
            assertTrue(Help.find("update latest release", 3).get(0).heading().equals("Updating"));
            int first = asked.get();
            Help.forget(); Help.docs();
            assertEquals(first, asked.get(), "a release's documentation is fetched once: its tag does not change");
            Help.FETCH = url -> null;   // offline
            Help.forget();
            assertEquals(2, Help.docs().size(), "offline, the copy kept is read");
            Help.REF = () -> "v8.8.8";
            Help.forget();
            assertTrue(Help.docs().isEmpty());
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            String none = Help.lookup(store, "how do I update");
            assertTrue(none.startsWith("This copy has no documentation to hand yet.") && none.contains("https://github.com/Wyrdsekai/researchzosho/tree/v8.8.8/docs") && none.contains(Help.ISSUES), none);
        } finally {
            Help.tree = was; Help.FETCH = fetch; Help.REF = ref; Help.forget();
            for (String r : List.of("v9.9.9", "v8.8.8")) { Path k = Help.kept(r); if (Files.isDirectory(k)) try (var s = Files.walk(k)) { s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); } }
        }
    }
}
