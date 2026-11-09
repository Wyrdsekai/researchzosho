package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A picture placed in a report (0.7.0): a writer puts {@code ![title](/images/<file>)} on a line of its own under the paragraph that
 * cites it, and the page showed it 160 px wide, the size of a tile ("why are the images in the report so small", the keeper, 2026-11-08).
 * Now a picture on a line of its own, in a report's body or a chat reply, is at reading size, with its title under it, and is a link that
 * opens the whole file in a new tab; a picture among other words, or indented under a list item as the chat's lists of candidates are,
 * stays small, and so do the tiles of the Pictures page.
 */
class ReportPicturesTest {

    String home; Images.Fetcher fetcher;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); fetcher = Images.FETCHER; Images.FETCHER = url -> new Images.Fetched(Vision.square(0, 0, 255), "image/png"); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); Images.FETCHER = fetcher; }

    static final String FIGURE = "<figure class=\"fig\"><a href=\"/images/ab12.png\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"figure\" src=\"/images/ab12.png\" alt=\"A diagram of the gear train\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a><figcaption>A diagram of the gear train</figcaption></figure>";

    @Test
    void aPictureOnALineOfItsOwnIsAtReadingSizeAndALinkToTheWholeFile() {
        assertEquals("<p>The gearing is a train of four.</p>" + FIGURE + "<p>The last wheel turns the pointer.</p>",
                Pages.md("The gearing is a train of four.\n![A diagram of the gear train](/images/ab12.png)\nThe last wheel turns the pointer."), "directly under the paragraph, as the writer is told to place it");
        assertEquals("<p>The gearing is a train of four.</p>" + FIGURE + "<p>The last wheel turns the pointer.</p>",
                Pages.md("The gearing is a train of four.\n\n![A diagram of the gear train](/images/ab12.png)\n\nThe last wheel turns the pointer."), "or with blank lines around it");
        assertEquals("<ul><li>one</li></ul>" + FIGURE, Pages.md("- one\n![A diagram of the gear train](/images/ab12.png)"), "a list closes before it");
        assertEquals("<figure class=\"fig\"><a href=\"https://pics.example/p.png\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"figure\" src=\"https://pics.example/p.png\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a></figure>",
                Pages.md("![](https://pics.example/p.png)"), "a picture of the web, with no title: no caption");
        String title = Pages.md("![Tom &amp; Jerry's \"gears\" <b>](/images/x.png)");
        assertTrue(title.contains("alt=\"Tom &amp; Jerry's &quot;gears&quot; &lt;b&gt;\"") && title.contains("<figcaption>Tom &amp; Jerry's &quot;gears&quot; &lt;b&gt;</figcaption>"), title);
        assertEquals("<p>![t](//evil.example/x)</p>", Pages.md("![t](//evil.example/x)"), "a protocol-relative address is no picture");
        assertEquals("<p>![t](javascript:alert(1))</p>", Pages.md("![t](javascript:alert(1))"));
    }

    @Test
    void aPictureAmongWordsOrUnderAListItemStaysSmall() {
        String candidates = Pages.md("2 pictures found.\n\n1. \"T\" from host.example, 768×401, found through Brave.\n   https://host.example/page\n   ![T](https://imgs.example/t.jpg)\n\n2. \"U\" from u.example.\n   https://u.example/p\n   ![U](https://imgs.example/u.jpg)\n");
        assertTrue(candidates.contains("<img class=\"thumb\" src=\"https://imgs.example/t.jpg\" alt=\"T\" loading=\"lazy\" referrerpolicy=\"no-referrer\">") && candidates.contains("<img class=\"thumb\" src=\"https://imgs.example/u.jpg\""), candidates);
        assertFalse(candidates.contains("class=\"figure\""), "the chat's candidates are tiles: " + candidates);
        assertTrue(Pages.md("See ![t](/images/a.png) here.").contains("<p>See <img class=\"thumb\" src=\"/images/a.png\" alt=\"t\""), "a picture in a sentence");
        assertTrue(Pages.md("| a | b |\n|---|---|\n| ![t](/images/a.png) | x |").contains("<td><img class=\"thumb\" src=\"/images/a.png\""), "a picture in a table cell");
    }

    @Test
    void theChatsPlacedPictureIsAtReadingSizeAndItsCandidatesSmall() {
        assertEquals(FIGURE + "<p>Picture: the gear train, judged &quot;a diagram of the gear train&quot;: yes — it shows the four wheels (kept as A-0001).</p>",
                Pages.prose("![A diagram of the gear train](/images/ab12.png)\n\nPicture: the gear train, judged \"a diagram of the gear train\": yes — it shows the four wheels (kept as A-0001)."));
        String list = Pages.prose("2 pictures found.\n\n1. \"T\" from host.example.\n   https://host.example/page\n   ![T](https://imgs.example/t.jpg)\n\nSay \"keep 1\" to keep it.");
        assertEquals("<p>2 pictures found.</p><p>1. &quot;T&quot; from host.example.<br>   https://host.example/page<br>   <img class=\"thumb\" src=\"https://imgs.example/t.jpg\" alt=\"T\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></p><p>Say &quot;keep 1&quot; to keep it.</p>", list);
        assertEquals("<p>![t](//evil.example/x)</p>", Pages.prose("![t](//evil.example/x)"));
    }

    @Test
    void aReportsPageShowsThePictureAtReadingSizeAndAllowsAWebPictureForEitherForm(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(new Investigation("I-0010-gears", "How were the gears cut?", Finding.State.accepted, "model:test", "2026-11-08T00:00:00Z", List.of(), List.of(),
                "## Answer\n\nThe gearing is a train of four.\n![A diagram of the gear train](/images/ab12.png)\nThe last wheel turns the pointer.\n"));
        store.write(new Investigation("I-0011-web", "Which gear?", Finding.State.accepted, "model:test", "2026-11-08T00:00:00Z", List.of(), List.of(), "## Answer\n\n![The crown wheel](https://pics.example/crown.png)\n"));
        store.write(new Investigation("I-0012-inline", "Which wheel?", Finding.State.accepted, "model:test", "2026-11-08T00:00:00Z", List.of(), List.of(), "## Answer\n\nSee ![the wheel](https://pics.example/wheel.png) here.\n"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> own = c.send(HttpRequest.newBuilder(URI.create(base + "/entry/I-0010-gears")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, own.statusCode(), own.body());
            assertTrue(own.body().contains("<div class=\"body\"><h3>Answer</h3><p>The gearing is a train of four.</p>" + FIGURE + "<p>The last wheel turns the pointer.</p></div>"), own.body());
            assertTrue(own.body().contains(".figure{display:block;max-width:100%;max-height:60vh;width:auto;height:auto;") && own.body().contains(".thumb{display:block;max-width:160px;max-height:160px;"), "reading size beside the tiles' size: " + own.body());
            assertEquals("default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'", own.headers().firstValue("Content-Security-Policy").orElse(""), "a picture of this library widens nothing");
            for (String id : new String[]{"I-0011-web", "I-0012-inline"}) {
                HttpResponse<String> web = c.send(HttpRequest.newBuilder(URI.create(base + "/entry/" + id)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, web.statusCode(), web.body());
                assertEquals("default-src 'none'; img-src 'self' https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'", web.headers().firstValue("Content-Security-Policy").orElse(""), id + ": a picture from the web is allowed over https, at reading size or small");
            }
        } finally { d.stop(); }
    }

    @Test
    void thePicturesPageKeepsItsTiles(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Images.Kept k = Images.keep(store, new Images.Found("https://a.example/blue.png", "https://a.example/", "A blue square", "Brave", 0, 0, ""), "", "");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpResponse<String> page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + ImagesPage.PATH)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("<img class=\"thumb\" src=\"" + k.url() + "\" alt=\"A blue square\""), "a tile of the Pictures page is small, as it was: " + page.body());
            assertFalse(page.body().contains("class=\"figure\""), page.body());
        } finally { d.stop(); }
    }
}
