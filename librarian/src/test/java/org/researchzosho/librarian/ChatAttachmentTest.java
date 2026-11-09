package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file chosen in the chat's composer (0.7.0): onto the shelves by the Add page's own path, the turn says so, and a reader's file is not
 * kept. Several files in one message, as the page's script posts them whether they came by the button, a drop or a paste: a part named
 * "file" each, every one shelved and said, the first ten of more than ten.
 */
class ChatAttachmentTest {

    static HttpResponse<String> post(HttpClient c, String url, String boundary, byte[] body, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (cookie != null) b.header("Cookie", cookie);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A browser's multipart body: the fields, then one file. */
    static byte[] multipart(String boundary, String session, String say, String fileName, String text) {
        StringBuilder s = new StringBuilder();
        for (String[] f : new String[][]{{"page", "1"}, {"session", session}, {"say", say}}) s.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(f[0]).append("\"\r\n\r\n").append(f[1]).append("\r\n");
        s.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\nContent-Type: text/plain\r\n\r\n").append(text).append("\r\n--").append(boundary).append("--\r\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A browser's multipart body with several files under the one field, and an archive among them. */
    static byte[] multipart(String boundary, String session, String say, Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] f : new String[][]{{"page", "1"}, {"session", session}, {"say", say}}) out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (var e : files.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + e.getKey() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(e.getValue()); out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    @Test
    void severalFilesAndAnArchiveSentWithOneTurnEachGoOntoTheShelvesAndTheTurnSaysWhatCameOfEach(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("one.txt", "The Saros cycle is 223 synodic months.\n".repeat(4).getBytes(StandardCharsets.UTF_8));
            files.put("two.txt", "The Metonic cycle is 235 synodic months.\n".repeat(4).getBytes(StandardCharsets.UTF_8));
            files.put("things.zip", ArchivesTest.trickyZip());
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "What do my notes say?", files), null);
            assertEquals(200, r.statusCode(), r.body());
            String frag = r.body();
            assertTrue(frag.contains("<p class=\"att\"><b>@one.txt</b> on the shelves · <a href=\"/entry/") && frag.contains("<p class=\"att\"><b>@two.txt</b> on the shelves · <a href=\"/entry/"), frag);
            assertTrue(frag.contains("<p class=\"att\"><b>@things.zip</b> on the shelves · From things.zip: 2 added, 0 unchanged, 0 skipped of 2 document(s), under the collection things; 3 entries of the archive were left out.</p>"), "the archive's chip says what came of it: " + frag);
            assertTrue(frag.contains("<p>I attached things.zip. From things.zip: 2 added, 0 unchanged, 0 skipped of 2 document(s), under the collection things; 3 entries of the archive were left out. &quot;../evil.txt&quot;: its path points outside the folder, skipped."), "and the words carry the entries left out, their names escaped: " + frag);
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var user = again.messages().stream().filter(m -> m.path("role").asText().equals("user")).findFirst().orElseThrow();
            String content = user.path("content").asText();
            assertTrue(content.startsWith("What do my notes say?") && content.contains("I attached one.txt; it is on the shelves as ") && content.contains("I attached two.txt; it is on the shelves as ") && content.contains("I attached things.zip. From things.zip: 2 added"), content);
            assertEquals(3, user.path("att").path("files").size(), user.toString());
            assertEquals("one.txt", user.path("att").path("name").asText(), "the first file is where it always was");
            // the composer takes several, and the page's script lists each as a chip
            String page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("<input type=\"file\" name=\"file\" id=\"chat-file\" multiple hidden>"), page);
            assertTrue(Pages.CHAT_PAGE_JS.contains("for(var i=0;i<picked.length;i++)att.appendChild(chipOf(i))"), Pages.CHAT_PAGE_JS);
            assertEquals(ArchivesTest.unpackFolders(), ArchivesTest.unpackFolders(), "no unpack folder lingers");
        } finally { d.stop(); }
    }

    /**
     * The path a person takes to have a file translated, through the real chat post: the ask with the file in one turn; the ask alone,
     * the library's "Which file?", and "this file" with the file; words about a translation the turn cannot read. The reply is the
     * library's own every time: the daemon's model is unreachable here, so a turn handed to it would not answer this way
     * (the keeper's three attempts of 2026-11-07, each caught by a different hole none of the unit tests walked).
     */
    @Test
    void aTranslationAskedForThroughTheChatWithItsFileIsTheLibrarysOwnTurnEveryWay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            String note = "The Saros cycle is 223 synodic months.\n\nIt is about 18 years and 11 days.\n";
            // the ask as a person says it, with the file: the library's turn, the tier asked
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "I would like this document translated into Japanese", "saros-note.txt", note), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("has 2 units with words") && r.body().contains("Into Japanese as txt") && r.body().contains("Checked, quick, reviewed or thorough?"), r.body());
            assertFalse(r.body().contains("I attached saros-note.txt; it is on the shelves as"), "a file posted with the ask is kept for the run, not shelved first: " + r.body());
            // the tier chosen; then the ask alone, then "this file" with the file: the kept ask runs
            r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "checked", Map.of()), null);
            assertTrue(r.body().contains("Taken: checked."), r.body());
            r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "translate the missing thing into German, quick", Map.of()), null);   // a file posted earlier in the conversation counts as "this file", so the ask names a document that is nowhere
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("Which file? Attach it to this message"), r.body());
            r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "this file", "saros-note.txt", note), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("Into German as txt") && r.body().contains("Translating it now, quick"), r.body());
            // words about a translation the turn cannot read, with a file: the turn's own question, never the model's guess
            r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "can you do a translation of this thing, something good", "saros-note.txt", note), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("I read this as a translation ask but could not tell which language to translate into"), r.body());
        } finally { d.stop(); Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow; }
    }

    /**
     * Two files dropped onto the page and sent with one message, as the page's script posts them (a part named "file" each): both on the
     * shelves, a chip each and a sentence each in the words, in the order they came. The page carries the drop cue, the note under the
     * chips, an Attach the keyboard reaches and the hint with the limits; the script takes a drop, a paste and the button into one list,
     * shows each file as a chip with its size and its own ×, and sends each as a part of its own.
     */
    @Test
    void twoFilesDroppedOnOneTurnBothGoOntoTheShelvesAndTheTurnSaysEach(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("saros.txt", "The Saros cycle is 223 synodic months.\n".repeat(4).getBytes(StandardCharsets.UTF_8));
            files.put("metonic.txt", "The Metonic cycle is 235 synodic months.\n".repeat(4).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "What do these two notes say?", files), null);
            assertEquals(200, r.statusCode(), r.body());
            String frag = r.body();
            int c1 = frag.indexOf("<p class=\"att\"><b>@saros.txt</b> on the shelves · <a href=\"/entry/"), c2 = frag.indexOf("<p class=\"att\"><b>@metonic.txt</b> on the shelves · <a href=\"/entry/");
            assertTrue(c1 >= 0 && c2 > c1, "a chip each, in the order they came: " + frag);
            assertTrue(frag.contains("I attached saros.txt; it is on the shelves as ") && frag.contains("I attached metonic.txt; it is on the shelves as "), "and a sentence each in the turn: " + frag);
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var user = again.messages().stream().filter(m -> m.path("role").asText().equals("user")).findFirst().orElseThrow();
            String content = user.path("content").asText();
            int w1 = content.indexOf("I attached saros.txt; it is on the shelves as "), w2 = content.indexOf("I attached metonic.txt; it is on the shelves as ");
            assertTrue(content.startsWith("What do these two notes say?") && w1 > 0 && w2 > w1, content);
            assertEquals(2, user.path("att").path("files").size(), user.toString());
            for (JsonNode f : user.path("att").path("files")) {
                assertTrue(f.path("kept").asBoolean(), f.toString());
                assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(base + "/entry/" + f.path("id").asText())).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "each is an entry on the shelves: " + f);
            }
            // the page: the drop cue, the note under the chips, an Attach the keyboard reaches, the input that still takes several, the hint with the limits
            String page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("<div class=\"dropcue\" id=\"chat-drop\" hidden>Drop to attach</div>"), page);
            assertTrue(page.contains("<p class=\"note small\" id=\"chat-att-note\" hidden></p>") && page.contains("<label class=\"btn quiet\" tabindex=\"0\" role=\"button\"") && page.contains("<input type=\"file\" name=\"file\" id=\"chat-file\" multiple hidden>"), page);
            assertTrue(page.contains("up to 10 in one message and 64 MB together"), page);
            // the script: a drop, a paste and the button feed one list; a chip each with its size and its own ×; each file a part of its own, named file
            String js = Pages.CHAT_PAGE_JS;
            for (String need : new String[]{"addEventListener('dragenter',", "addEventListener('drop',", "add(ev.dataTransfer.files)", "addEventListener('paste',", "add(cd.files)", "add(fi.files)",
                    "fd.append('file',files[i].file,files[i].name)", "z.textContent=size(p.file.size)", "picked.splice(i,1)", "MAXN=10,MAXB=" + (64 << 20), "'" + Pages.OVER_FILES + "'", "'" + Pages.OVER_BYTES + "'"})
                assertTrue(js.contains(need), need + " is not in the script");
            assertTrue(Pages.chatPageJs().contains("'Only the first 10 files were taken; one message takes up to 10. Send the others with the next message.'"), "the English page says the limit with its number");
        } finally { d.stop(); }
    }

    /** Eleven files in one message: the first ten go onto the shelves, the eleventh is not taken, and the words and its chip say so. */
    @Test
    void elevenFilesInOneTurnTheFirstTenAreTakenAndTheEleventhIsSaidNotTaken(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (int i = 1; i <= 11; i++) files.put(String.format("note-%02d.txt", i), ("Note " + i + ": the cycle is " + (200 + i) + " months long.\n").repeat(3).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "What do these notes say?", files), null);
            assertEquals(200, r.statusCode(), r.body());
            String frag = r.body();
            for (int i = 1; i <= 10; i++) assertTrue(frag.contains("<p class=\"att\"><b>@" + String.format("note-%02d.txt", i) + "</b> on the shelves · <a href=\"/entry/"), "note " + i + " is on the shelves: " + frag);
            assertEquals(10, frag.split(Pattern.quote("on the shelves · <a href=\"/entry/"), -1).length - 1, "ten and no more: " + frag);
            String why = "One message takes up to 10 files; this one was past the tenth and was not taken. Send it with the next message.";
            assertTrue(frag.contains("<p class=\"att\"><b>@note-11.txt</b> not kept · " + why + "</p>"), "the eleventh's chip says why: " + frag);
            assertTrue(frag.contains("I attached note-11.txt, which was not kept: " + why), "and so do the words: " + frag);
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var user = again.messages().stream().filter(m -> m.path("role").asText().equals("user")).findFirst().orElseThrow();
            assertEquals(11, user.path("att").path("files").size(), user.toString());
            assertEquals(10, user.path("att").path("files").findValues("kept").stream().filter(JsonNode::asBoolean).count(), user.toString());
            assertEquals("note-01.txt", user.path("att").path("name").asText(), "the first file is where it always was");
        } finally { d.stop(); }
    }

    /** A translation ask with two files: the first is the one to translate, kept for the run as with one file; the second goes onto the shelves and the turn says so. */
    @Test
    void aTranslationAskWithTwoFilesTakesTheFirstAndShelvesTheSecond(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        Translation.DRIVE_ANSWERS = url -> true; Translation.ANY_MODEL = () -> true;
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("saros-note.txt", "The Saros cycle is 223 synodic months.\n\nIt is about 18 years and 11 days.\n".getBytes(StandardCharsets.UTF_8));
            files.put("metonic.txt", "The Metonic cycle is 235 synodic months.\n".repeat(4).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "I would like this document translated into Japanese", files), null);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("has 2 units with words") && r.body().contains("Into Japanese as txt") && r.body().contains("Checked, quick, reviewed or thorough?"), "the first file is the one to translate: " + r.body());
            assertFalse(r.body().contains("I attached saros-note.txt"), "the file the ask took needs no sentence: " + r.body());
            assertTrue(r.body().contains("<p class=\"att\"><b>@metonic.txt</b> on the shelves · <a href=\"/entry/") && r.body().contains("I attached metonic.txt; it is on the shelves as "), "the second is shelved and said: " + r.body());
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var user = again.messages().stream().filter(m -> m.path("role").asText().equals("user")).findFirst().orElseThrow();
            assertEquals("saros-note.txt", user.path("att").path("name").asText(), user.toString());
            assertEquals(2, user.path("att").path("files").size(), user.toString());
        } finally { d.stop(); Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow; }
    }

    @Test
    void aFileSentWithATurnGoesOntoTheShelvesAndTheTurnSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            Librarian.Session s = Librarian.Session.open(store);
            HttpResponse<String> r = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", s.id, "What does my note say?", "saros-note.txt", "The Saros cycle is 223 synodic months, about 18 years and 11 days.\n"), null);
            assertEquals(200, r.statusCode(), r.body());
            String frag = r.body();
            assertTrue(frag.contains("<p class=\"att\"><b>@saros-note.txt</b> on the shelves · <a href=\"/entry/"), "the turn carries the file as a chip: " + frag);
            assertTrue(frag.contains("I attached saros-note.txt; it is on the shelves as "), "and says so in words the Librarian reads: " + frag);
            String raw = frag.replaceAll("(?s).*on the shelves · <a href=\"/entry/([^\"]+)\">the entry</a>.*", "$1");
            assertFalse(raw.isBlank() || raw.contains("<"), raw);
            assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(base + "/entry/" + raw)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "the file is an entry on the shelves");
            Librarian.Session again = Librarian.Session.resume(store, s.id);
            var user = again.messages().stream().filter(m -> m.path("role").asText().equals("user")).findFirst().orElseThrow();
            assertEquals("saros-note.txt", user.path("att").path("name").asText(), user.toString());
            assertTrue(user.path("content").asText().startsWith("What does my note say?") && user.path("content").asText().contains("I attached saros-note.txt; it is on the shelves as " + raw), user.toString());
            // the composer offers the file, and the page's script sends it as a form with the file
            String page = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("enctype=\"multipart/form-data\"") && page.contains("<input type=\"file\" name=\"file\" id=\"chat-file\"") && page.contains("id=\"chat-att\""), page);
            assertTrue(Pages.CHAT_PAGE_JS.contains("new FormData(f)") && Pages.CHAT_PAGE_JS.contains("chat-file"), Pages.CHAT_PAGE_JS);
            // a reader may not add to the shelves: told, and the file is not kept
            Patrons.set(store, "did:key:zReader", "a reader", Patrons.Level.read);
            String cookie = Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zReader");
            Patrons.Patron reader = new Patrons.Patron("did:key:zReader", "a reader", "web");
            Librarian.Session theirs = Librarian.Session.open(store, reader);
            HttpResponse<String> r2 = post(c, base + "/chat", "----rzboundary", multipart("----rzboundary", theirs.id, "Read this", "theirs.txt", "Some words.\n"), cookie);
            assertEquals(200, r2.statusCode(), r2.body());
            assertTrue(r2.body().contains("the file was not kept") && !r2.body().contains("on the shelves as"), r2.body());
        } finally { d.stop(); }
    }
}
