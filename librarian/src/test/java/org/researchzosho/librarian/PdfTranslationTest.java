package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.Translation.Plan;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A translation of a PDF (0.7.3) says what the reader found before it runs and first when it ends, and does not start on a document whose
 * text cannot be read: a scan with no model that sees is refused with the reason and the way to set one; a typed page and a scanned one
 * with a stand-in for the model that sees are planned with the page read by eye, and translated with its words.
 */
class PdfTranslationTest {

    @BeforeEach void standInTheScorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void restore() {
        PdfReading.useEyes(null);
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
    }

    /** The scanned page of {@link PdfReadingTest#typedAndScanned()} twice over: a PDF with no text at all. */
    static byte[] scansOnly() throws Exception {
        try (PDDocument src = Loader.loadPDF(PdfReadingTest.typedAndScanned()); PDDocument doc = new PDDocument()) {
            doc.importPage(src.getPage(1)); doc.importPage(src.getPage(1));
            ByteArrayOutputStream out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }

    @Test
    void aPdfWhoseTextCannotBeReadIsNotTranslatedAndTheRefusalSaysWhyAndWhatWouldMakeItReadable(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        PdfReading.useEyes(() -> null);
        Path scans = tmp.resolve("scan.pdf"); Files.write(scans, scansOnly());
        Translation.Refused r = assertThrows(Translation.Refused.class, () -> Translation.plan(store, scans, "ja", "", "quick", "stand-in", ""));
        assertTrue(r.getMessage().startsWith("No text can be read from \"scan.pdf\" as it is. Pages 1–2 are pictures of pages, with no text in the file."), r.getMessage());
        assertTrue(r.getMessage().contains("Reading pages 1–2 needs a model that sees, and none is set") && r.getMessage().contains("researchzosho models add"), r.getMessage());
        assertTrue(r.in(Chrome.in("ja")).startsWith("「scan.pdf」からは、今のままではテキストを読めません。"), r.in(Chrome.in("ja")));
        // a typed page and a scanned one, no model that sees: half the pages cannot be read, so it is not translated
        Path half = tmp.resolve("half.pdf"); Files.write(half, PdfReadingTest.typedAndScanned());
        Translation.Refused h = assertThrows(Translation.Refused.class, () -> Translation.plan(store, half, "ja", "", "quick", "stand-in", ""));
        assertTrue(h.getMessage().startsWith("\"half.pdf\" is not translated as it is: 1 of its 2 pages cannot be read now. One of its 2 pages reads by its text."), h.getMessage());
    }

    @Test
    void aPdfWithAScannedPageIsPlannedWithThePageReadByEyeAndItsSummarySaysSoFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        AtomicInteger asked = new AtomicInteger();
        PdfReading.useEyes(() -> PdfReadingTest.eyes("stand-in eyes", asked, "The scanned page tells of the keeper.\n\nIts second paragraph ends here."));
        Path in = tmp.resolve("clock.pdf"); Files.write(in, PdfReadingTest.typedAndScanned());
        Plan plan = Translation.plan(store, in, "ja", "", "quick", "stand-in", "");
        assertEquals("One of its 2 pages reads by its text. Page 2 is a picture of a page, with no text in the file. Page 2 is read by eye by stand-in eyes; reading by eye has not been timed on that model yet.", plan.readingSaid(Chrome.ENGLISH));
        assertEquals(0, asked.get(), "a plan reads no page by eye");
        assertTrue(plan.quickSeconds() >= Translation.EYE_GUESS, "the time counts the page read by eye: " + plan.quickSeconds());
        TranslationTest.Script sc = new TranslationTest.Script();
        Outcome o = Translation.run(store, in, "ja", "", "quick", null, null, TranslationTest.seats(sc), null, () -> false);
        assertEquals(1, asked.get());
        assertTrue(o.summary().startsWith("One page of the PDF was read by its text. Page 2 was read by eye by stand-in eyes.\n\nTranslated "), o.summary());
        assertTrue(o.summary(Chrome.in("ja")).startsWith("PDFのうち1ページをテキストから読みました。2ページは stand-in eyes が目で読みました。"), o.summary(Chrome.in("ja")));
        String out = Files.readString(o.note());
        assertTrue(out.contains("- Read: One page of the PDF was read by its text. Page 2 was read by eye by stand-in eyes. (2 pages: text 1; eye (no-text) 2)"), out);
    }
}
