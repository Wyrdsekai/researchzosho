package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An exported file downloads whatever its name is written in. A header carries ASCII alone, and the server refuses the whole reply for
 * one letter outside it: a translation of a document named in Japanese answered "Illegal character found in header" and could not be
 * downloaded at all.
 */
class ExportDownloadNameTest {

    String home;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    @Test void aFileNamedInJapaneseKoreanOrRussianIsServedUnderItsOwnName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path dir = Export.exportsDir(store); Files.createDirectories(dir);
        ApiKeys.Minted key = ApiKeys.mint(store, Accounts.OWNER, "the test", Patrons.Level.read);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HttpClient c = HttpClient.newHttpClient();
        try {
            for (String name : new String[]{"図書館の百年-2.en.pdf", "図書館の百年.en.translation.md", "도서관의 역사.en.md", "История библиотеки (1).en.docx", "café “notes”.en.txt", "plain-name.en.pdf"}) {
                byte[] body = ("the file " + name).getBytes(StandardCharsets.UTF_8);
                Files.write(dir.resolve(name), body);
                String at = "http://127.0.0.1:" + d.port() + "/exports/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(at)).header("Authorization", "Bearer " + key.secret()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, r.statusCode(), name + ": " + new String(r.body(), StandardCharsets.UTF_8));
                assertArrayEquals(body, r.body(), name);
                String h = r.headers().firstValue("Content-Disposition").orElse("");
                assertTrue(h.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7F), "the header is ASCII: " + h);
                if (name.chars().allMatch(ch -> ch < 0x7F)) assertEquals("attachment; filename=\"" + name + "\"", h, "a plain name as it always was");
                else {
                    assertTrue(h.startsWith("attachment; filename=\""), h);
                    String own = h.substring(h.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length());
                    assertEquals(name.replace("\"", ""), URLDecoder.decode(own.replace("+", "%2B"), StandardCharsets.UTF_8), "the name itself, percent-encoded: " + h);
                }
            }
        } finally { d.stop(); }
    }

    @Test void theHeaderForAName() {
        assertEquals("attachment; filename=\"report.pdf\"", Pages.disposition("report.pdf"));
        assertEquals("attachment; filename=\"document_-2.en.pdf\"; filename*=UTF-8''%E5%9B%B3%E6%9B%B8%E9%A4%A8-2.en.pdf", Pages.disposition("図書館-2.en.pdf"));
        assertEquals("attachment; filename=\"caf_ notes.txt\"; filename*=UTF-8''caf%C3%A9%20notes.txt", Pages.disposition("café notes.txt"));
        assertEquals("attachment; filename=\"a b.txt\"", Pages.disposition("a \"b\".txt".replace("\" ", " ").replace("\"", "")), "quotes never reach the header");
        assertEquals("attachment; filename=\"download\"", Pages.disposition(""));
        assertFalse(Pages.disposition("x\r\nSet-Cookie: a=b.pdf").contains("\n"), "no line break reaches a header");
    }
}
