package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Translate form's offer to set the scorers up. It stood inside the translate form as a form of its own: a browser ends the outer
 * form where the inner one closes, so the "Translate the file" button after it belonged to no form and did nothing, on every machine
 * where a scorer was missing; and it was made on a machine that runs no heavy helper itself and has its scores made on another one.
 */
class TranslateFormOfferTest {
    String home;
    BooleanSupplier up, comicSetUp;
    Function<Consumer<String>, String> comicInstall;
    LibraryStore store;

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); Helpers.forget();
        up = TranslationScores.LABSE_UP; TranslationScores.LABSE_UP = () -> true;   // LaBSE answers here; this home has no MetricX-24, which is what is missing
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        comicSetUp = ComicHelper.INSTALLED; comicInstall = ComicHelper.INSTALL;   // the comic helper is not set up in this home either
    }

    @AfterEach void restore() {
        TranslationScores.LABSE_UP = up; Helpers.forget();
        ComicHelper.INSTALLED = comicSetUp; ComicHelper.INSTALL = comicInstall;
        System.setProperty("user.home", home); Config.invalidate();
    }

    /** No form opens while another is open, and each one is closed. */
    static void noFormInsideAForm(String html) {
        int depth = 0;
        Matcher m = Pattern.compile("<(/?)form\\b").matcher(html);
        while (m.find()) {
            if (m.group(1).isEmpty()) {
                assertEquals(0, depth, "a form opens inside a form, which ends the outer one in a browser: …" + html.substring(Math.max(0, m.start() - 160), Math.min(html.length(), m.start() + 160)) + "…");
                depth++;
            } else depth--;
        }
        assertEquals(0, depth, "every form is closed");
    }

    @Test void theOfferIsAFormOfItsOwnAfterTheTranslateFormAndTheTranslateButtonStaysInItsForm() {
        String form = Pages.translateForm(store);
        assertTrue(form.contains("Set them up now") && form.contains("MetricX-24"), "this machine may run a scorer and has no MetricX-24, so the offer is made: " + form);
        noFormInsideAForm(form);
        int opens = form.indexOf("enctype=\"multipart/form-data\""), closes = form.indexOf("</form>", opens);
        String translate = form.substring(opens, closes);
        assertTrue(translate.contains("Translate the file"), "the button that files the translation is inside the translate form");
        assertFalse(translate.contains("value=\"scorers\""), "the set-up's own field is not in the translate form: it would turn a Translate into a set-up");
        assertTrue(form.indexOf("Set them up now") > closes, "the offer stands after the translate form");
    }

    @Test void aMachineThatRunsNoHeavyHelperOrHasItsScoresMadeElsewhereIsOfferedNothing() throws IOException {
        Config.set(Helpers.LOCAL, "off");
        String off = Pages.translateForm(store);
        assertFalse(off.contains("Set them up now"), "local_helpers off: nothing is set up on this machine");
        assertTrue(off.contains("Translate the file"));
        noFormInsideAForm(off);

        Config.set(Helpers.LOCAL, "on"); Config.set(Helpers.HELPERS, "http://127.0.0.1:1"); Helpers.forget();   // a helpers machine is named for the scores
        assertEquals("", TranslationScores.missingSaid(), "a library whose scores are made on another machine misses nothing by having no MetricX-24 of its own");
        String elsewhere = Pages.translateForm(store);
        assertFalse(elsewhere.contains("Set them up now"), "nothing to set up here: " + elsewhere);
        noFormInsideAForm(elsewhere);
    }

    @Test void noPageThatShowsTheFormHasAFormInsideAForm() throws Exception {
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            for (String page : List.of("/keep", "/translate", "/", "/jobs")) {
                String h = KeepPageTest.get(c, base + page, null).body();
                noFormInsideAForm(h);
                if (page.equals("/keep") || page.equals("/translate")) assertTrue(h.contains("Set them up now") && h.contains("Translate the file"), page + " shows the translate form with the offer: " + page);
                if (page.equals("/keep") || page.equals("/translate")) assertTrue(h.contains("Set the comic helper up") && h.contains("name=\"comic\""), page + " shows the comic helper's offer too, and the form's choice for a comic");
            }
        } finally { d.stop(); }
    }

    /** The translate form itself: from its opening tag to its closing one. */
    static String translatePart(String form) {
        int opens = form.indexOf("enctype=\"multipart/form-data\""), closes = form.indexOf("</form>", opens);
        return form.substring(opens, closes);
    }

    @Test void theComicHelpersOfferIsAFormOfItsOwnAfterTheTranslateFormAndIsNotMadeWhereItCouldNotRun() throws Exception {
        String form = Pages.translateForm(store);
        noFormInsideAForm(form);
        assertTrue(form.contains("A comic is translated too (manga, manhwa, manhua, a webtoon, a comic book, a graphic novel, a bande dessinée): a CBZ, a CBR or a ZIP of page pictures, or a PDF you say is one, up to 512 MB."), form);
        String translate = translatePart(form);
        assertTrue(translate.contains("<option value=\"on\">this file is a comic (manga, a webtoon, a graphic novel)</option><option value=\"off\">this file is a document</option>") && translate.contains("<option value=\"cbz\">CBZ</option>"), "the choices for a comic are in the translate form");
        assertTrue(translate.contains("<select name=\"from\""), "and the choice of the language it is lettered in");
        assertFalse(translate.contains("value=\"comic-helper\""), "the set-up's own field is not in the translate form");
        int offer = form.indexOf("Set the comic helper up");
        assertTrue(offer > form.indexOf("</form>", form.indexOf("enctype=\"multipart/form-data\"")), "the offer stands after the translate form");
        assertTrue(form.contains("Reading a comic&#39;s pages needs the comic helper, which is not set up on this machine yet.") || form.contains("Reading a comic's pages needs the comic helper, which is not set up on this machine yet."), "the offer says what every surface says: " + form);
        assertTrue(form.contains("researchzosho translate comics status --list names each piece with its licence"), form);
        // set up: no offer
        ComicHelper.INSTALLED = () -> true;
        assertFalse(Pages.translateForm(store).contains("Set the comic helper up"));
        ComicHelper.INSTALLED = () -> false;
        // a machine that runs no heavy helper, and a helper switched off: no offer where it could not run
        Config.set(Helpers.LOCAL, "off");
        String off = Pages.translateForm(store);
        assertFalse(off.contains("Set the comic helper up"), "local_helpers off: nothing is set up on this machine"); noFormInsideAForm(off);
        Config.set(Helpers.LOCAL, "on"); Config.set(ComicHelper.SETTING, "off");
        assertFalse(Pages.translateForm(store).contains("Set the comic helper up"), "the comic helper is off");
        Config.set(ComicHelper.SETTING, "on");
        // the button starts the set-up in the background and the page says so; while it runs, the line says it runs and offers nothing
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean setUp = new AtomicBoolean();
        ComicHelper.INSTALLED = setUp::get;
        ComicHelper.INSTALL = say -> { try { go.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } setUp.set(true); return "ready"; };
        String started = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-helper"));
        assertTrue(started.contains("<p class=\"notice\">The comic helper is being set up in the background; a comic can be translated when it is done.</p>"), started);
        assertFalse(started.contains("Set the comic helper up"), "no second offer while one runs"); noFormInsideAForm(started);
        go.countDown(); ComicHelper.awaitSetup();
        String done = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-helper"));
        assertTrue(done.contains("<p class=\"notice\">The comic helper is set up.</p>") && !done.contains("Set the comic helper up"), done);
    }

    @Test void aComicSentWhereTheHelperIsNotSetUpIsKeptAndOneButtonSaysYesAndTranslatesIt(@TempDir Path tmp) throws Exception {
        Predicate<String> answers = Translation.DRIVE_ANSWERS; Translation.SeatMaker seats = Translation.SEATS; BooleanSupplier any = Translation.ANY_MODEL;
        try {
            Translation.DRIVE_ANSWERS = d -> true; Translation.ANY_MODEL = () -> true;
            Translation.SEATS = (drive, model, judge, back) -> ComicTranslationTest.seats(new ComicTranslationTest.Model()).withBack(false);
            Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:1"); Config.set("RESEARCHZOSHO_MODEL", "stand-in");
            ComicHelper.INSTALLED = () -> false;
            Path dir = Files.createTempDirectory(Uploads.DIR);
            Path cbz = dir.resolve("volume.cbz"); ComicStandIn.volume(cbz, 3);
            Map<String, String> form = new LinkedHashMap<>(Map.of("file.path", cbz.toString(), "file.name", "volume.cbz", "to", "en", "quality", "quick", "direction", "terse, urgent", "notes_box", "1"));
            String asked = Pages.translatePost(store, Patrons.Patron.PERSON, form);
            noFormInsideAForm(asked);
            assertTrue(asked.contains("<p class=\"notice\">volume.cbz is a comic, and it is kept here; it is not translated yet.</p>"), asked);
            assertTrue(asked.contains("<input type=\"hidden\" name=\"op\" value=\"comic-go\"><input type=\"hidden\" name=\"kept\" value=\"volume.cbz\">") && asked.contains("<button>Set it up and translate the comic</button>"), asked);
            assertTrue(asked.contains("name=\"to\" value=\"en\"") && asked.contains("name=\"quality\" value=\"quick\"") && asked.contains("name=\"direction\" value=\"terse, urgent\""), "the button carries the form's choices: " + asked);
            assertTrue(asked.indexOf("comic-go") < asked.indexOf("enctype=\"multipart/form-data\""), "the notice and its button stand before the translate form, outside it");
            assertFalse(asked.contains("Filed as"), "nothing is filed without the yes");
            assertFalse(Files.exists(dir), "the posted copy is dropped; the library keeps its own");
            assertTrue(new Jobs(store, j -> "").active().isEmpty(), "no job yet");
            // the button: the job is filed with the yes, and sets the helper up itself before it reads the pages
            String filed = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-go", "kept", "volume.cbz", "to", "en", "quality", "quick", "direction", "terse, urgent", "notes_box", "1"));
            noFormInsideAForm(filed);
            assertTrue(filed.contains("Filed as") && filed.contains("volume.cbz: a comic of 3 pages, the language of its lettering not read yet. Into English as cbz, quick:"), "with no helper to look with, the language is the run's to find: " + filed);
            assertTrue(filed.contains("the language of its lettering is found on a sample of its pages when the run starts"), filed);
            assertEquals(1, new Jobs(store, j -> "").active().size());
            JsonNode args = new Jobs(store, j -> "").get("J-0001").path("args");
            assertEquals("yes", args.path("comic_setup").asText()); assertEquals("on", args.path("comic").asText()); assertEquals("terse, urgent", args.path("direction").asText());
            assertFalse(args.has("from"), "no language was chosen on the form");
            // the form's choice names the language the comic is lettered in: the offer's button carries it, and the job is filed with it
            Path again = Files.createTempDirectory(Uploads.DIR);
            Path manhwa = again.resolve("manhwa.cbz"); ComicStandIn.volume(manhwa, 2);
            String offer = Pages.translatePost(store, Patrons.Patron.PERSON, new LinkedHashMap<>(Map.of("file.path", manhwa.toString(), "file.name", "manhwa.cbz", "to", "en", "from", "ko", "quality", "quick", "notes_box", "1")));
            assertTrue(offer.contains("name=\"from\" value=\"ko\""), "the button carries the language chosen: " + offer);
            String korean = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-go", "kept", "manhwa.cbz", "to", "en", "from", "ko", "quality", "quick", "notes_box", "1"));
            assertTrue(korean.contains("manhwa.cbz: a comic of 2 pages, its lettering read as Korean. Into English as cbz, quick:"), korean);
            assertEquals("ko", new Jobs(store, j -> "").get("J-0002").path("args").path("from").asText());
            // a comic said to be in the language asked for is refused on the page, in one sentence, and nothing is filed
            String same = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-go", "kept", "manhwa.cbz", "to", "en", "from", "en", "quality", "quick", "notes_box", "1"));
            assertTrue(same.contains("<p class=\"notice\">&quot;manhwa.cbz&quot; is written in English already.</p>") || same.contains("<p class=\"notice\">\"manhwa.cbz\" is written in English already.</p>"), same);
            // the form itself: a choice for the language the file is written in, found by itself unless chosen; and the words a person has for a comic
            String page = Pages.translateForm(store);
            assertTrue(page.contains("<select name=\"from\" aria-label=\"the language it is written in\"><option value=\"\">its own language is found by itself</option>") && page.contains("<option value=\"ko\">it is written in Korean</option>") && page.contains("<option value=\"zh\">it is written in Chinese</option>"), page);
            assertTrue(page.contains("A comic is translated too (manga, manhwa, manhua, a webtoon, a comic book, a graphic novel, a bande dessinée)") && page.contains("this file is a comic (manga, a webtoon, a graphic novel)"), page);
            noFormInsideAForm(page);
            // and a choice for the way a comic's pages are read, beside the languages: found from the pages unless chosen; the offer's button carries it, and the job is filed with it
            assertTrue(page.contains("<select name=\"reads\" aria-label=\"the way its pages are read\"><option value=\"\">which way it reads is found from the pages</option><option value=\"rtl\">it reads from right to left (a manga)</option><option value=\"ltr\">it reads from left to right</option></select>"), page);
            assertTrue(page.indexOf("name=\"from\"") < page.indexOf("name=\"reads\"") && page.indexOf("name=\"reads\"") < page.indexOf("name=\"as\""), "beside the language choices");
            Path third = Files.createTempDirectory(Uploads.DIR);
            Path edition = third.resolve("edition.cbz"); ComicStandIn.volume(edition, 2);
            String carried = Pages.translatePost(store, Patrons.Patron.PERSON, new LinkedHashMap<>(Map.of("file.path", edition.toString(), "file.name", "edition.cbz", "to", "de", "from", "en", "reads", "rtl", "quality", "quick", "notes_box", "1")));
            assertTrue(carried.contains("name=\"reads\" value=\"rtl\""), "the button carries the way chosen: " + carried);
            String read = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-go", "kept", "edition.cbz", "to", "de", "from", "en", "reads", "rtl", "quality", "quick", "notes_box", "1"));
            assertTrue(read.contains("edition.cbz: a comic of 2 pages, its lettering read as English. Into German as cbz, quick:") && read.contains("; its pages are read from the right to the left, as you said; "), read);
            assertEquals("rtl", new Jobs(store, j -> "").get("J-0003").path("args").path("reads").asText());
            assertFalse(new Jobs(store, j -> "").get("J-0002").path("args").has("reads"), "nothing chosen, nothing kept as said");
            // a name that is no file kept here, or one that climbs out, files nothing
            for (String bad : List.of("gone.cbz", "../lib/catalog/config", "/etc/hostname", ".hidden")) assertTrue(Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("op", "comic-go", "kept", bad, "to", "en")).contains("That file is no longer kept here; choose it again."), bad);
            assertEquals(3, new Jobs(store, j -> "").active().size());
        } finally { Translation.DRIVE_ANSWERS = answers; Translation.SEATS = seats; Translation.ANY_MODEL = any; }
    }

    /** A form with one file of {@code bytes} zero bytes, as a browser posts it. */
    static HttpRequest post(String url, int bytes, String field, String value) {
        String b = "----rz-offer";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"volume.cbz\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(new byte[bytes]);
        body.writeBytes(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "multipart/form-data; boundary=" + b).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    @Test void aFileLargerThanTheFormTakesIsSaidOnThePageAboveTheFormAndNothingIsKept() throws Exception {
        Config.set(Uploads.TRANSLATE_SETTING, "1");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> r = c.send(post("http://127.0.0.1:" + d.port() + "/translate", 3 << 20, "to", "en"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), "the page answers, with the sentence on it: " + r.body().substring(0, Math.min(300, r.body().length())));
            assertTrue(r.body().contains("<p class=\"notice\">The file is larger than the Translate form takes, 1 MB. Translate it at the command line on the machine that runs the library (researchzosho translate &lt;file&gt; --to &lt;language&gt;), or raise what the form takes there: researchzosho settings set translate_upload_mb &lt;megabytes&gt;.</p>"), r.body());
            assertTrue(r.body().contains("Translate the file"), "the form is there to try again");
            noFormInsideAForm(r.body());
            try (Stream<Path> kept = Files.exists(Translation.inbox(store)) ? Files.list(Translation.inbox(store)) : Stream.empty()) { assertEquals(0, kept.count(), "nothing of the file is kept"); }
            // a field that poses as the server's own word is dropped: the page says nothing was chosen, not the words sent
            HttpResponse<String> posed = c.send(post("http://127.0.0.1:" + d.port() + "/translate", 0, Uploads.REFUSED, "a notice of the sender's own"), HttpResponse.BodyHandlers.ofString());
            assertFalse(posed.body().contains("a notice of the sender's own"), posed.body()); assertTrue(posed.body().contains("Choose a file to translate."));
            // under the limit the same post goes through to the form's own handling: a file of zeros is no document and no comic, and the page says so
            Config.set(Uploads.TRANSLATE_SETTING, "8");
            HttpResponse<String> under = c.send(post("http://127.0.0.1:" + d.port() + "/translate", 3 << 20, "to", "en"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, under.statusCode()); assertFalse(under.body().contains("is larger than the Translate form takes"), "three megabytes are taken when the form takes eight");
            // another page's post over its own 64 MB is answered as before
            assertEquals(64 << 20, Uploads.MAX);
        } finally { d.stop(); Config.set(Uploads.TRANSLATE_SETTING, ""); }
    }
}
