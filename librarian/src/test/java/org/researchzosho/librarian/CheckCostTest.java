package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.Judge;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.PageCheck;
import org.researchzosho.tools.WebFetchTool;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the page check costs: a page added at the terminal is converted once, and the text checked is the text saved; a page read again
 * in the same run is not asked about again; a server that does not keep to the typed judge's grammar is remembered, so it is not sent
 * the typed request before every question; and a check asked inside a request waits tens of seconds, not the drive's five minutes.
 * Placeholder pages and pictures only.
 */
class CheckCostTest {

    static final ObjectMapper M = new ObjectMapper();

    @TempDir Path home;

    @AfterEach void back() { PageCheck.useGetter(null); ImageText.use(null); ContentJudge.use(null); ContentJudge.REQUEST_TIMEOUT = Duration.ofSeconds(30); }

    static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void aPictureAddedAtTheTerminalIsReadOnceAndTheTextCheckedIsTheTextSaved() throws Exception {
        byte[] picture = png();
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, picture, "image/png"));
        AtomicInteger reads = new AtomicInteger();
        ImageText.use((png, hint) -> "PLACEHOLDER WRITING " + reads.incrementAndGet());
        Path h = home.resolve("h");
        LibraryStore lib = new LibraryStore(h.resolve("researchzosho-library")); lib.init();
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", h.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { LibrarianCli.run(new String[]{"librarian", "add", "https://example.org/placeholder-register.png"}, "http://127.0.0.1:1", "m"); }
        finally { System.setOut(was); System.setProperty("user.home", realHome); }
        assertEquals(1, reads.get(), "read once by the model that reads pictures: " + out);
        Path raw = RawCapture.find(lib, "https://example.org/placeholder-register.png");
        assertNotNull(raw, out.toString());
        assertTrue(RawCapture.read(raw)[2].contains("PLACEHOLDER WRITING 1"), "the text saved is the text the check read");
    }

    @Test
    void aPageReadAgainInTheSameRunIsNotAskedAboutAgain() throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        String html = "<html><head><title>A placeholder</title></head><body><main><p>" + "Placeholder words about the placeholder gears. ".repeat(20) + "</p></main></body></html>";
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, html.getBytes(StandardCharsets.UTF_8), "text/html"));
        List<String> asked = new CopyOnWriteArrayList<>();
        ContentJudge counting = new ContentJudge(null, messages -> { asked.add(messages.get(0).path("content").asText()); return "no"; });
        ContentPolicy run = ContentPolicy.run(List.of(), counting, store);
        WebFetchTool tool = new WebFetchTool().policy(run);
        assertTrue(tool.execute(M.readTree("{\"url\":\"https://example.org/placeholder\",\"find\":\"gears\"}")).contains("placeholder gears"));
        int once = asked.size();
        assertEquals(3, once, "the two default questions and the always-dropped one");
        assertTrue(tool.execute(M.readTree("{\"url\":\"https://example.org/placeholder\",\"find\":\"words\"}")).contains("placeholder gears"));
        assertEquals(once, asked.size(), "the same page with the same text: the verdict already reached");
        // a page whose text changed is asked about again
        PageCheck.useGetter((url, timeout, lists) -> new Fetch.Result(url, 200, html.replace("gears", "wheels").getBytes(StandardCharsets.UTF_8), "text/html"));
        tool.execute(M.readTree("{\"url\":\"https://example.org/placeholder\",\"find\":\"wheels\"}"));
        assertEquals(once * 2, asked.size());
    }

    @Test
    void aServerThatDoesNotKeepToTheGrammarIsRememberedPerDrive() {
        String key = "http://127.0.0.1:9 placeholder-model-" + System.nanoTime();
        AtomicInteger sent = new AtomicInteger();
        Judge ignoring = new Judge("m", body -> {
            sent.incrementAndGet();
            ObjectNode r = M.createObjectNode();
            r.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", "The");
            return r;
        }, key);
        assertFalse(ignoring.noul("placeholder", "Placeholder question?").ran());
        assertFalse(Judge.grammarWorks(key), "remembered");
        assertFalse(ignoring.noul("placeholder", "Placeholder question?").ran());
        assertEquals(1, sent.get(), "the typed request is not sent again");
        // a server that refuses the typed request itself is remembered too; a passing failure is not
        String refusing = "http://127.0.0.1:9 refusing-" + System.nanoTime(), busy = "http://127.0.0.1:9 busy-" + System.nanoTime();
        new Judge("m", body -> { throw new IllegalStateException("HTTP 400: unknown field grammar"); }, refusing).noul("placeholder", "Placeholder question?");
        new Judge("m", body -> { throw new IllegalStateException("HTTP 503: loading the model"); }, busy).noul("placeholder", "Placeholder question?");
        assertFalse(Judge.grammarWorks(refusing));
        assertTrue(Judge.grammarWorks(busy));
        // and the drive's judges ask one word from then on
        DriveClient d = new DriveClient("http://127.0.0.1:9", "placeholder-model-drive-" + System.nanoTime());
        assertTrue(d.givesTokenProbabilities());
        new Judge("m", body -> { throw new IllegalStateException("HTTP 422: grammar is not supported"); }, d.decisionKey()).noul("placeholder", "Placeholder question?");
        assertFalse(d.givesTokenProbabilities());
    }

    @Test
    void aCheckAskedInsideARequestWaitsTensOfSecondsNotTheDrivesFiveMinutes() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", x -> { try { Thread.sleep(5_000); } catch (InterruptedException ignored) { } x.close(); });
        slow.setExecutor(Executors.newCachedThreadPool());
        slow.start();
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:" + slow.getAddress().getPort());
            ContentJudge.use(null);
            ContentJudge.REQUEST_TIMEOUT = Duration.ofSeconds(1);
            long t0 = System.currentTimeMillis();
            ContentJudge.Reading r = ContentJudge.configured().ask("placeholder", "Placeholder question?");
            long took = System.currentTimeMillis() - t0;
            assertFalse(r.judged());
            assertTrue(took < 4_500, "the request's limit, not the drive's: " + took + " ms");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
            slow.stop(0);
        }
    }
}
