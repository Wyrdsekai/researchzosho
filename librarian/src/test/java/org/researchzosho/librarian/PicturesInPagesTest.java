package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.DocText;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.SiteList;
import org.researchzosho.tools.WebFetchTool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures in web pages: every picture's alt text and a figure's caption stay in the page's text; the page lists its content pictures,
 * leaving out the site's furniture (a logo, an icon, a tracking pixel, an advert, a decorative picture), so that the model can have one
 * read, through the same path as the person's own pictures; a picture is never written to disk; and the one question a picture is asked
 * is the narrow always-dropped one. Every picture here is a plain grey square.
 */
class PicturesInPagesTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String PAGE = "https://example.org/placeholder-article";

    static final String HTML = """
            <html><head><title>A placeholder article</title></head><body>
            <header><img src="/static/site-logo.png" alt="Placeholder Site"></header>
            <main>
            <h1>The placeholder gears</h1>
            <p>The gears were cut by hand, and the article shows them. The text runs on for long enough to be the page's own content, so the
            reader takes the main part of the page as the page, not the menus around it. More placeholder words follow here.</p>
            <figure><img src="/images/gear-train.jpg" alt="A train of four placeholder gears on a bench" width="640" height="480">
            <figcaption>The gear train, as it was found.</figcaption></figure>
            <p>A second picture sits in the text: <img src="https://cdn.example.org/pics/dial.png" alt="The front dial of the placeholder mechanism"> and the text goes on.</p>
            <img src="/icons/share.png" alt="Share this" width="16" height="16">
            <img src="https://tracker.example.org/p.gif?id=1" width="1" height="1" alt="">
            <img class="advert-slot" src="/ads/placeholder-ad.jpg" alt="Buy placeholder gears">
            <img src="/images/divider.jpg" alt="">
            <img role="presentation" src="/images/ornament.jpg" alt="An ornament">
            <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" alt="inline">
            <img src="/images/diagram.svg" alt="A placeholder diagram in SVG">
            </main>
            <footer><img src="/static/footer-badge.png" alt="Placeholder badge"></footer>
            </body></html>
            """;

    static byte[] greySquare() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) img.setRGB(x, y, 0x808080);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    @TempDir Path home;
    LibraryStore store;
    final Map<String, byte[]> web = new HashMap<>();
    final List<String> fetched = new CopyOnWriteArrayList<>();
    final List<String> pageQuestions = new CopyOnWriteArrayList<>();
    final List<String> pictureQuestions = new CopyOnWriteArrayList<>();
    final List<String> readers = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() throws Exception {
        store = new LibraryStore(home.resolve("lib")); store.init();
        web.put(PAGE, HTML.getBytes(StandardCharsets.UTF_8));
        web.put("https://example.org/images/gear-train.jpg", greySquare());
        PageCheck.useGetter((url, timeout, lists) -> {
            String left = lists.leftOut(url);   // the same check Fetch makes at every hop
            if (left != null) throw new Fetch.LeftOut(url, left);
            fetched.add(url);
            byte[] b = web.get(url);
            return b == null ? new Fetch.Result(url, 404, new byte[0], "") : new Fetch.Result(url, 200, b, "");
        });
        ContentJudge.use(new ContentJudge(null, messages -> { pageQuestions.add(messages.get(0).path("content").asText()); return "no"; }));
        ImageText.useChecker((png, question) -> { pictureQuestions.add(question); return new ContentJudge.Reading(ContentJudge.Verdict.NO, 0.0, "test"); });
        ImageText.use((png, hint) -> { readers.add(hint); return "PHOTO: a grey square, a placeholder picture"; });
    }

    @AfterEach void back() { PageCheck.useGetter(null); ImageText.use(null); ImageText.useChecker(null); ContentJudge.use(null); }

    @Test
    void altTextAndCaptionsStayInTheTextAndTheFurnitureGoes() {
        String text = DocText.convert(HTML.getBytes(StandardCharsets.UTF_8), PAGE).text();
        assertTrue(text.contains("[picture: A train of four placeholder gears on a bench]"), text);
        assertTrue(text.contains("The gear train, as it was found."), "the caption: " + text);
        assertTrue(text.contains("A second picture sits in the text: [picture: The front dial of the placeholder mechanism] and the text goes on."), text);
        assertTrue(text.contains("[picture: A placeholder diagram in SVG]"), "a picture this build cannot read keeps its alt text: " + text);
        // every picture's alt text is kept where it stands, whatever the picture is named or however it is drawn
        for (String alt : new String[]{"Share this", "Buy placeholder gears", "An ornament", "inline"})
            assertTrue(text.contains("[picture: " + alt + "]"), alt + " in: " + text);
        // the site's header and footer are not the page's main part, and their pictures go with them
        for (String outside : new String[]{"Placeholder Site", "Placeholder badge"})
            assertFalse(text.contains(outside), outside + " in: " + text);
    }

    @Test
    void aPictureDrawnLargeOrCaptionedIsContentWhateverItsFileIsNamed() {
        String html = """
                <html><head><title>A placeholder archive page</title></head><body><main>
                <p>A page of the placeholder archive, with the pictures it holds. The text runs on for long enough to be the page's own content,
                so the reader takes the main part of the page as the page. More placeholder words follow here, and more after them.</p>
                <img src="/scans/placeholder_ad_1890.jpg" alt="A placeholder newspaper advertisement from 1890" width="600" height="400">
                <figure><img src="/scans/banner-1910.jpg" alt="A placeholder parade banner"><figcaption>The parade banner of 1910.</figcaption></figure>
                <img src="/img/share-button.png" alt="Share">
                </main></body></html>
                """;
        List<WebFetchTool.Picture> pics = WebFetchTool.pictures(html, PAGE);
        assertEquals(List.of("https://example.org/scans/placeholder_ad_1890.jpg", "https://example.org/scans/banner-1910.jpg"), pics.stream().map(WebFetchTool.Picture::url).toList(),
                "drawn large, or in a figure with a caption: listed; the share button, which only its name gives away, is not");
        String text = DocText.convert(html.getBytes(StandardCharsets.UTF_8), PAGE).text();
        for (String alt : new String[]{"A placeholder newspaper advertisement from 1890", "A placeholder parade banner", "Share"})
            assertTrue(text.contains("[picture: " + alt + "]"), alt + " in: " + text);
    }

    @Test
    void thePageListsItsContentPicturesAndSkipsTheFurniture() {
        List<WebFetchTool.Picture> pics = WebFetchTool.pictures(HTML, PAGE);
        assertEquals(List.of(
                new WebFetchTool.Picture("https://example.org/images/gear-train.jpg", "A train of four placeholder gears on a bench", "The gear train, as it was found."),
                new WebFetchTool.Picture("https://cdn.example.org/pics/dial.png", "The front dial of the placeholder mechanism", "")), pics,
                "the logo, the icon, the tracking pixel, the advert, the decorative and hidden pictures, the inline one and the SVG are not listed");
    }

    @Test
    void theModelSeesTheListAndHasAPictureReadThroughTheVisionPathWithNothingOnDisk() throws Exception {
        ContentPolicy run = ContentPolicy.run(List.of(), null, store);
        String page = new WebFetchTool().policy(run).execute(M.readTree("{\"url\":\"" + PAGE + "\"}"));
        assertTrue(page.contains("PICTURES IN THIS PAGE:\n1. alt text: A train of four placeholder gears on a bench; caption: The gear train, as it was found.; address: https://example.org/images/gear-train.jpg\n2. alt text: The front dial"), page);
        assertTrue(page.indexOf("PICTURES IN THIS PAGE") < page.indexOf("<<<END SOURCE TEXT"), "the list is inside the fence: the page wrote it");
        assertTrue(page.endsWith("The page has 2 picture(s), listed at the end of its text. To have one read, call web_fetch with its address."), page);
        assertEquals(List.of(PAGE), fetched, "listing the pictures downloads none of them");
        int pageChecks = pageQuestions.size();

        String read = new WebFetchTool().policy(run).execute(M.readTree("{\"url\":\"https://example.org/images/gear-train.jpg\"}"));
        assertTrue(read.contains("PHOTO: a grey square, a placeholder picture"), "the model that reads pictures read it: " + read);
        assertEquals(1, readers.size(), "read once, through the reader the person's own pictures go through");
        assertEquals(List.of(ImageText.CHILD_QUESTION), pictureQuestions, "the one narrow question, before it was read");
        assertEquals(pageChecks, pageQuestions.size(), "none of the page questions is asked about a picture");
        assertEquals("Does this image appear to show a child in a sexual context?", ImageText.CHILD_QUESTION);
        byte[] png = greySquare();
        try (Stream<Path> files = Files.walk(home)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                byte[] b = Files.readAllBytes(f);
                assertFalse(b.length >= 4 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G', "a picture on disk: " + f);
                assertFalse(new String(b, StandardCharsets.ISO_8859_1).contains(new String(png, 8, 24, StandardCharsets.ISO_8859_1)), "picture bytes on disk: " + f);
            }
        }
        assertNotNull(RawCapture.find(store, "https://example.org/images/gear-train.jpg"), "what the model read of it is kept, as text");
    }

    @Test
    void aPictureTheNarrowCheckAnswersYesToIsNeverReadOrKept() throws Exception {
        ImageText.useChecker((png, question) -> { pictureQuestions.add(question); return new ContentJudge.Reading(ContentJudge.Verdict.YES, 1.0, "test"); });
        ContentPolicy run = ContentPolicy.run(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO), null, store);
        String out = new WebFetchTool().policy(run).execute(M.readTree("{\"url\":\"https://example.org/images/gear-train.jpg\"}"));
        assertEquals("ERROR: https://example.org/images/gear-train.jpg was left out of this research (sexual content involving a child). Read another source.", out);
        assertEquals(List.of(), readers, "never read");
        assertNull(RawCapture.find(store, "https://example.org/images/gear-train.jpg"));
        assertEquals(List.of("left out: sexual content involving a child — https://example.org/images/gear-train.jpg"), run.leftOut().stream().map(ContentPolicy.LeftOut::line).toList());
    }

    @Test
    void aPictureOnTheSiteListIsNeverDownloaded() throws Exception {
        SiteList.use(SiteList.of("test list", "placeholder-adult.example"));
        web.put("https://placeholder-adult.example/p.jpg", greySquare());
        ContentPolicy run = ContentPolicy.run(List.of(), null, store);
        String out = new WebFetchTool().policy(run).execute(M.readTree("{\"url\":\"https://placeholder-adult.example/p.jpg\"}"));
        assertEquals(List.of("left out: pornography or gore (the site is on the list of pornography, shock and gore sites: test list) — https://placeholder-adult.example/p.jpg"),
                run.leftOut().stream().map(ContentPolicy.LeftOut::line).toList(), "the report says whose list it was");
        assertTrue(out.startsWith("ERROR: https://placeholder-adult.example/p.jpg was left out of this research, because its site is on the list of pornography, shock and gore sites."), out);
        assertEquals(List.of(), fetched, "not downloaded");
        assertEquals(List.of(), pictureQuestions);
        // the real fetch makes the same check before it opens a connection
        PageCheck.useGetter(null);
        assertThrows(Fetch.LeftOut.class, () -> PageCheck.fetch("https://placeholder-adult.example/p.jpg", Duration.ofSeconds(5), ContentPolicy.run(List.of(), null, store), ""));
    }

    @Test
    void aWebpPictureIsToldApartAndSaidPlainly() {
        byte[] webp = new byte[32];
        byte[] head = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, webp, 0, head.length);
        assertTrue(ImageText.isImage(webp) && ImageText.isWebp(webp));
        DocText.Doc doc = DocText.convert(webp, "https://example.org/p.webp");
        assertEquals("", doc.text(), "never read as text");
        assertTrue(doc.kind().contains("WebP"), doc.kind());
        assertEquals(new ArrayList<>(), readers, "not sent to the model: this build cannot open it");
    }
}
