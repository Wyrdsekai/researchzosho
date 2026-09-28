package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The page where somebody in the family says which of the people the web shows under a name is their relative. */
class WhoPageTest {

    @Test
    void thePageAsksTakesTheAnswerAndGoesOnToTheNextPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田まり", "", List.of("Mari Morita"))), List.of(
                new FamilyAccount.Fact("森田まり", "child-of", "森田正一", "", "q"), new FamilyAccount.Fact("森田まり", "born-on", "1975", "", "q"),
                new FamilyAccount.Fact("森田正一", "died-on", "1990", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        List<FamilyIdentity.Page> web = List.of(new FamilyIdentity.Page("https://books.example/mari", "Mari Morita <author>", "Co-author of a book on bridges. Born 1975."),
                new FamilyIdentity.Page("https://films.example/name/1", "Mari Morita | Actress", "An actress."));
        Graph g = Graph.build(store);
        FamilyIdentity.find(store, g, "森田まり", q -> web, p -> "rows 1: an author of a book on bridges\nrows 2: an actress");
        FamilyIdentity.find(store, g, "森田正一", q -> web, null);
        Patrons.set(store, "did:key:me", "Me", Patrons.Level.write);
        String token = Patrons.issueToken(store, "did:key:me");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + d.port();
        try {
            WebAccess.OVERRIDE = Boolean.TRUE;   // the sign-in is on: the page acts on the library, and a reader who has not signed in may only read
            assertEquals(403, PagesTest.get(c, base + "/who", null).statusCode());
            assertFalse(PagesTest.get(c, base + "/inbox", null).body().contains("questions about your family"), "a reader who has not signed in is not told who waits");
            String cookie = PagesTest.post(c, base + "/login", "token=" + token, null).headers().firstValue("set-cookie").orElseThrow().split(";")[0];

            assertTrue(PagesTest.get(c, base + "/inbox", cookie).body().contains("2 questions about your family are") , "the inbox says how many wait and links to the page");
            // the tree: a reader who has not signed in sees everybody the owner sees, and every name is offered
            String treeOut = PagesTest.get(c, base + "/tree", null).body();
            assertTrue(treeOut.contains("/tree?focus=%E6%A3%AE%E7%94%B0%E3%81%BE%E3%82%8A") && !treeOut.contains("not shown to readers"), "a reader sees the living too: " + treeOut);
            String treeIn = PagesTest.get(c, base + "/tree", cookie).body();
            assertTrue(treeIn.contains("/tree?focus=%E6%A3%AE%E7%94%B0%E3%81%BE%E3%82%8A") && !treeIn.contains("not shown to readers"), "signed in, the owner's own living family is there: " + treeIn);
            assertTrue(PagesTest.get(c, base + "/tree?focus=" + Pages.enc("森田まり"), cookie).body().contains("<svg"), "and the tree draws around a living person");
            String home = PagesTest.get(c, base + "/", cookie).body();
            assertTrue(home.contains("<a href=\"/tree\">Family tree</a><a href=\"/who\">Who is who</a>") && home.contains("<h2>Your family</h2>") && home.contains("2 questions are"), "a library that holds a family shows both pages in the menu and on the home page: " + home);
            String page = PagesTest.get(c, base + "/who", cookie).body();
            assertTrue(page.contains("<h2>森田まり</h2>") && page.contains("Question 1 of 2"), "the person whose pages agree with the family comes first: " + page);
            assertTrue(page.contains("an author of a book on bridges") && page.contains("facts from your library: <strong>1975</strong>") && page.contains("href=\"https://books.example/mari\""), page);
            assertTrue(page.contains("Mari Morita &lt;author&gt;"), "what a web page calls itself is text, never markup");

            // the button without a tick: asked to tick first, nothing recorded
            var noTick = PagesTest.post(c, base + "/who", "person=" + Pages.enc("森田まり") + "&do=confirmed", cookie);
            assertEquals(303, noTick.statusCode());
            assertTrue(noTick.headers().firstValue("location").orElse("").contains("show=pick"));
            assertEquals("open", FamilyIdentity.read(store, "森田まり").state());

            // something told, then the tick
            PagesTest.post(c, base + "/who", "person=" + Pages.enc("森田まり") + "&do=told&told=" + Pages.enc("She builds bridges in Osaka."), cookie);
            assertEquals(List.of("She builds bridges in Osaka."), FamilyIdentity.read(store, "森田まり").told());
            var ticked = PagesTest.post(c, base + "/who", "person=" + Pages.enc("森田まり") + "&c1=on&do=confirmed", cookie);
            assertEquals("/who", ticked.headers().firstValue("location").orElse(""));
            FamilyIdentity.Question said = FamilyIdentity.read(store, "森田まり");
            assertEquals("confirmed", said.state());
            assertTrue(FamilyIdentity.forQuestion(said).contains("She builds bridges in Osaka.") && FamilyIdentity.forQuestion(said).contains("https://books.example/mari"));

            // on to the next person; then nobody waits, and an answer can still be changed
            String next = PagesTest.get(c, base + "/who", cookie).body();
            assertTrue(next.contains("<h2>森田正一</h2>") && next.contains("Question 1 of 1"), next);
            PagesTest.post(c, base + "/who", "person=" + Pages.enc("森田正一") + "&do=none", cookie);
            String done = PagesTest.get(c, base + "/who", cookie).body();
            assertTrue(done.contains("There are no questions waiting for you") && done.contains("confirmed: an author of a book on bridges"), done);
            assertTrue(PagesTest.get(c, base + "/who?person=" + Pages.enc("森田まり"), cookie).body().contains("Your earlier answer for this person"));
            assertFalse(PagesTest.get(c, base + "/inbox", cookie).body().contains("questions about your family"));
        } finally { d.stop(); WebAccess.OVERRIDE = null; }
    }
}
