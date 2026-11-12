package org.researchzosho.librarian;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.Declined;
import org.researchzosho.librarian.PdfLayout.Box;
import org.researchzosho.librarian.PdfLayout.Glyph;
import org.researchzosho.librarian.PdfLayout.PageIn;
import org.researchzosho.librarian.PdfReading.Check;
import org.researchzosho.librarian.PdfReading.Way;
import org.researchzosho.tools.DocText;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reader of PDFs (0.7.3): the sensor that tells a page read by its text from one that needs the eye, decided from measurements
 * alone; the preflight's sentences; a PDF of a typed page and a scanned one read with a stand-in for the model that sees, with none, and
 * with one that declines; and every caller (the shelves' text, a translation's units) getting the same reading.
 */
class PdfReadingTest {

    @AfterEach void restore() { PdfReading.useEyes(null); }

    static List<Glyph> line(String text, double x, double baseline, double size) { return PdfLayoutTest.line(text, x, baseline, size); }

    static Check check(List<Glyph> g, List<Box> pictures, double ink) { return PdfReading.check(PdfLayout.page(new PageIn(1, 595, 842, g, pictures)), () -> ink); }

    static final Box WHOLE = new Box(0, 0, 595, 842);

    @Test
    void theSensorTellsEachKindOfPageFromItsMeasurementsAlone() {
        List<Glyph> prose = new ArrayList<>();
        for (int i = 0; i < 6; i++) prose.addAll(line("The clock in the square was wound every week by hand.", 72, 100 + i * 12, 10));
        assertEquals(Way.TEXT, check(prose, List.of(), 0).way());
        // a picture over the whole page and no glyph: a picture of a page; nothing at all: blank; ink and no glyph: drawn
        assertEquals(PdfReading.NO_TEXT, check(List.of(), List.of(WHOLE), 0).why());
        assertEquals(Way.BLANK, check(List.of(), List.of(), 0).way());
        assertEquals(PdfReading.DRAWN, check(List.of(), List.of(), 0.05).why());
        // glyphs whose characters are not mapped (a Type 3 font that gives control characters): garbled
        List<Glyph> garbled = new ArrayList<>();
        for (Glyph x : prose) garbled.add(new Glyph(x.text().isBlank() ? x.text() : "\u0007", x.x0(), x.y0(), x.x1(), x.y1(), x.size(), x.axis(), false, false));
        Check gc = check(garbled, List.of(), 0);
        assertEquals(PdfReading.GARBLED, gc.why());
        assertTrue(gc.stats().badShare() > 0.9, gc.stats().toString());
        // a scan with the file's own recognised text drawn invisibly over it: judged like any text layer, read when it reads well
        List<Glyph> hidden = new ArrayList<>();
        for (Glyph x : prose) hidden.add(x.hidden());
        assertEquals(Way.OCR, check(hidden, List.of(WHOLE), 0).way());
        List<Glyph> junk = new ArrayList<>();
        for (int i = 0; i < 6; i++) for (Glyph x : line("Trg_in1ng ,·'I ?ea 0 41am hy~5 Fig- 6' 0 c( tw#r ;l'e", 72, 100 + i * 12, 10)) junk.add(x.hidden());
        assertEquals(PdfReading.OCR_POOR, check(junk, List.of(WHOLE), 0).why());
        List<Glyph> sparse = new ArrayList<>();
        for (Glyph x : line("Patented 1876.", 72, 100, 10)) sparse.add(x.hidden());
        assertEquals(PdfReading.OCR_SPARSE, check(sparse, List.of(WHOLE), 0).why());
        // lines that came out as paragraphs of their own, none ending a sentence: the order could not be worked out
        List<Glyph> broken = new ArrayList<>();
        for (int i = 0; i < 8; i++) broken.addAll(line("one column read across the next one and the next", i % 2 == 0 ? 40 : 330, 100 + i * 40, 10));
        assertEquals(PdfReading.ORDER, check(broken, List.of(), 0).why());
    }

    @Test
    void thePreflightSaysThePagesByTheirTextThePagesThatNeedTheEyeAndHowToSetOne() {
        List<Glyph> prose = new ArrayList<>();
        for (int i = 0; i < 6; i++) prose.addAll(line("The clock in the square was wound every week by hand.", 72, 100 + i * 12, 10));
        List<PdfLayout.Page> pages = PdfLayout.document(List.of(new PageIn(1, 595, 842, prose, List.of()), new PageIn(2, 595, 842, List.of(), List.of(WHOLE)), new PageIn(3, 595, 842, List.of(), List.of(WHOLE))));
        List<Check> checks = new ArrayList<>();
        for (PdfLayout.Page p : pages) checks.add(PdfReading.check(p, () -> 0.0));
        PdfReading.Preflight pre = new PdfReading.Preflight(3, checks, List.of(), "en");
        assertEquals(List.of(2, 3), pre.eyePages());
        String none = pre.said(Chrome.ENGLISH, null, 0);
        assertTrue(none.startsWith("One of its 3 pages reads by its text. Pages 2–3 are pictures of pages, with no text in the file."), none);
        assertTrue(none.contains("Reading pages 2–3 needs a model that sees, and none is set: without one, the other pages are read and these are named as not read.") && none.contains("researchzosho models add"), none);
        String timed = pre.said(Chrome.ENGLISH, "a seeing model", 45);
        assertTrue(timed.contains("Pages 2–3 are read by eye by a seeing model, which takes about 2 minutes."), timed);
        assertTrue(pre.said(Chrome.ENGLISH, "a seeing model", 0).contains("Pages 2–3 are read by eye by a seeing model; reading by eye has not been timed on that model yet."));
        // a page that cannot be drawn is said apart, and the pages read by eye are named: no count that the list above contradicts
        PdfReading.Preflight part = new PdfReading.Preflight(3, checks, List.of(3), "en");
        String p3 = part.said(Chrome.ENGLISH, "a seeing model", 45);
        assertTrue(p3.contains("Pages 2–3 are pictures of pages") && p3.contains("Page 3 cannot be drawn on this machine") && p3.contains("Page 2 is read by eye by a seeing model, which takes under a minute."), p3);
        String ja = pre.said(Chrome.in("ja"), null, 0);
        assertTrue(ja.contains("2–3") && !ja.contains("needs a model that sees"), "the sentences in the person's language: " + ja);
        assertEquals("3 pages: text 1; eye (no-text) 2–3", pre.line());
        assertEquals("text", pre.json().path("checks").get(0).path("way").asText());
        assertEquals("1, 3–5 and 9", PdfReading.pages(List.of(1, 3, 4, 5, 9)));
    }

    /** A PDF of two pages: a typed one, and a scan (a picture over the whole page with dark lines on it, no text). */
    static byte[] typedAndScanned() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage typed = new PDPage(PDRectangle.A4), scanned = new PDPage(PDRectangle.A4);
            doc.addPage(typed); doc.addPage(scanned);
            try (PDPageContentStream cs = new PDPageContentStream(doc, typed)) {
                cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11); cs.newLineAtOffset(72, 750);
                for (String l : List.of("The typed page tells how the clock in the square", "was wound every week by the keeper of the hall.", "It ends here.")) { cs.showText(l); cs.newLineAtOffset(0, -14); }
                cs.endText();
            }
            BufferedImage img = new BufferedImage(300, 420, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 300, 420); g.setColor(Color.BLACK);
            for (int y = 40; y < 380; y += 14) g.fillRect(30, y, 240, 5);
            g.dispose();
            PDImageXObject x = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, scanned)) { cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight()); }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    static PdfReading.Eyes eyes(String name, AtomicInteger asked, String said) {
        return new PdfReading.Eyes() {
            @Override public String name() { return name; }
            @Override public String read(byte[] png, String ask) {
                asked.incrementAndGet();
                assertTrue(png.length > 1000 && ask.contains("Transcribe all the writing on this page") && ask.contains("untranslated"), ask);
                return said;
            }
        };
    }

    @Test
    void aScannedPageIsReadByEyeWhenAModelSeesAndNamedAsNotReadWhenNoneDoes() throws Exception {
        byte[] pdf = typedAndScanned();
        // no model that sees: the typed page is read, the scan is named at its place, nothing made up for it
        PdfReading.useEyes(() -> null);
        PdfReading.Read blind = PdfReading.read(pdf, PdfReading.Options.reading(30));
        assertEquals(List.of(2), blind.preflight().eyePages());
        assertTrue(blind.text().startsWith("The typed page tells how the clock in the square was wound every week by the keeper of the hall. It ends here."), blind.text());
        assertTrue(blind.text().endsWith("[Page 2 was not read: it is a picture of text and no model that sees is set.]"), blind.text());
        assertEquals("One page of the PDF was read by its text. Page 2 was not read: it is a picture of text and no model that sees is set.", blind.said(Chrome.ENGLISH));
        // a stand-in for the model that sees: the page drawn and transcribed, its paragraphs the page's
        AtomicInteger asked = new AtomicInteger();
        PdfReading.useEyes(() -> eyes("stand-in eyes", asked, "The scanned page has a heading\n\nIts first paragraph is\nset on two lines.\n\nIts second paragraph ends here."));
        PdfReading.Read seen = PdfReading.read(pdf, PdfReading.Options.reading(30));
        assertEquals(1, asked.get());
        assertEquals(List.of(2), seen.byEye());
        assertTrue(seen.text().endsWith("The scanned page has a heading\n\nIts first paragraph is set on two lines.\n\nIts second paragraph ends here."), seen.text());
        assertTrue(seen.said(Chrome.ENGLISH).contains("Page 2 was read by eye by stand-in eyes."), seen.said(Chrome.ENGLISH));
        // every caller reads the same: the shelves' text and a translation's units
        DocText.Doc d = DocText.convert(pdf, "clock.pdf");
        assertEquals("pdf", d.kind());
        assertTrue(d.text().contains("Its second paragraph ends here.") && d.reading() != null, d.text());
        DocUnits.Doc u = DocUnits.cut(pdf, "clock.pdf");
        assertTrue(u.units().stream().anyMatch(x -> x.kind().equals(DocUnits.HEADING) && x.text().equals("The scanned page has a heading")), u.units().toString());
        assertTrue(u.units().stream().anyMatch(x -> x.kind().equals(DocUnits.PARA) && x.text().equals("Its first paragraph is set on two lines.")), u.units().toString());
        assertEquals(1, asked.get(), "the document read once is not read by eye again");
        // the preflight reads nobody: no page drawn, no model asked
        PdfReading.useEyes(() -> eyes("other eyes", asked, "never"));
        assertEquals(List.of(2), PdfReading.preflight(pdf).eyePages());
        assertEquals(1, asked.get());
    }

    @Test
    void aPageTheModelDeclinedIsSaidAtItsPlaceAndTheOthersAreRead() throws Exception {
        byte[] pdf = typedAndScanned();
        PdfReading.useEyes(() -> new PdfReading.Eyes() {
            @Override public String name() { return "declining eyes"; }
            @Override public String read(byte[] png, String ask) { throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS); }
        });
        PdfReading.Read r = PdfReading.read(pdf, PdfReading.Options.reading(30));
        assertTrue(r.text().startsWith("The typed page") && r.text().endsWith("[Page 2 was not read: the model that sees declined to read it.]"), r.text());
        assertTrue(r.byEye().isEmpty());
    }

    @Test
    void aPageReadByEyeIsKeptByTheJobsMemorySoThatAResumedJobDoesNotReadItAgain() throws Exception {
        byte[] pdf = typedAndScanned();
        AtomicInteger asked = new AtomicInteger();
        PdfReading.useEyes(() -> eyes("memo eyes", asked, "Read once."));
        Map<String, String> kept = new HashMap<>();
        PdfReading.PageMemo memo = new PdfReading.PageMemo() { @Override public String get(String k) { return kept.get(k); } @Override public void put(String k, String t) { kept.put(k, t); } };
        List<int[]> told = new ArrayList<>();
        PdfReading.Options o = PdfReading.Options.reading(Integer.MAX_VALUE).with(memo, (done, total) -> told.add(new int[]{done, total}), () -> false);
        PdfReading.read(pdf, o);
        PdfReading.Read again = PdfReading.read(pdf, o);
        assertEquals(1, asked.get(), "the second run takes the page from the memory");
        assertTrue(again.text().endsWith("Read once."));
        assertEquals(1, told.get(0)[0]); assertEquals(1, told.get(0)[1]);
    }

    @Test
    void aTranscriptionWithAParagraphToALineIsCutByLineAndSpacesAtAColumnsEndAreTakenOut() {
        List<PdfLayout.Block> b = PdfReading.transcribed("時計は町の広場に立ち、人々に時を知らせてきた。その針は長い間止まって いたが、修理されて動き始めた。\n修理には二十年の月日がかかり、部品は二十九個が新しく作られて、古い部品は資料館にある。", 3);
        assertEquals(List.of("時計は町の広場に立ち、人々に時を知らせてきた。その針は長い間止まっていたが、修理されて動き始めた。", "修理には二十年の月日がかかり、部品は二十九個が新しく作られて、古い部品は資料館にある。"), b.stream().map(PdfLayout.Block::text).toList());
        List<PdfLayout.Block> c = PdfReading.transcribed("A heading\n\nThe first paragraph was\nprinted on two lines.", 1);
        assertEquals(List.of(PdfLayout.HEADING, PdfLayout.PARA), c.stream().map(PdfLayout.Block::kind).toList());
        assertEquals("The first paragraph was printed on two lines.", c.get(1).text());
        assertTrue(PdfReading.transcribed("(no writing)", 1).isEmpty());
    }

    @Test
    void theDocumentsPageAndATranslationJobsPageSayWhatTheReaderFound(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        PdfReading.useEyes(() -> null);
        Path f = tmp.resolve("clock.pdf"); Files.write(f, typedAndScanned());
        Path raw = Corpus.addFile(store, f, "", false);
        assertTrue(Corpus.lastReading().startsWith("One of its 2 pages reads by its text. Page 2 is a picture of a page"), Corpus.lastReading());
        assertTrue(RawCapture.field(raw, "pdf_reading").startsWith("2 pages: text 1; eye (no-text) 2"), RawCapture.field(raw, "pdf_reading"));
        LibraryProtocol lp = new LibraryProtocol(store);
        String page = Pages.entry(store, lp, Librarian.person(), raw.getFileName().toString());
        assertTrue(page.contains("One of its 2 pages reads by its text. Page 2 is a picture of a page, with no text in the file."), page);
        // a translation filed with its plan's preflight (as every surface files one): its page says the same sentence
        ObjectNode a = Translation.args(f, "clock.pdf", "ja", "md", "quick", null, null, "web", "", "", "", "");
        a.set("pdf_preflight", PdfReading.preflight(Files.readAllBytes(f)).json());
        String id = new Jobs(store, j -> "").submit(Translation.JOB_KIND, "", a);
        String job = Pages.job(store, lp, Librarian.person(), id, Map.of());
        assertTrue(job.contains("One of its 2 pages reads by its text."), job);
    }

    @Test
    void theSentencesReadRightForOnePageOrManyAndForOnePictureOrMany() {
        PdfLayout.Stats none = null;
        Check typedOne = new Check(1, Way.TEXT, "", none, "ltr", 1), typedTwo = new Check(2, Way.TEXT, "", none, "ltr", 2), typedThree = new Check(3, Way.TEXT, "", none, "ltr", 1);
        assertTrue(new PdfReading.Preflight(1, List.of(typedOne), List.of(), "en").said(Chrome.ENGLISH, null, 0).contains("One picture on page 1 may hold writing of its own; it is kept as a picture on the shelves and its writing is not read."));
        assertTrue(new PdfReading.Preflight(1, List.of(typedTwo), List.of(), "en").said(Chrome.ENGLISH, "eyes", 0).contains("2 pictures on page 2 may hold writing of their own; the model that sees describes them, quoting their writing, when they are kept on the shelves."));
        assertTrue(new PdfReading.Preflight(2, List.of(typedTwo, typedThree), List.of(), "en").said(Chrome.ENGLISH, null, 0).contains("3 pictures on pages 2–3 may hold writing of their own"));
        // the pages not read: one page's reason, or the same reason for several
        PdfReading.Preflight pre = new PdfReading.Preflight(3, List.of(), List.of(), "en");
        Map<Integer, String> one = new LinkedHashMap<>(); one.put(2, PdfReading.NOT_READ_NO_EYES);
        Map<Integer, String> two = new LinkedHashMap<>(); two.put(2, PdfReading.NOT_READ_DECLINED); two.put(3, PdfReading.NOT_READ_DECLINED);
        assertEquals("Page 2 was not read: it is a picture of text and no model that sees is set.", new PdfReading.Read(pre, List.of(), List.of(), "", one, 0, 0, "").said(Chrome.ENGLISH));
        assertEquals("Pages 2–3 were not read: the model that sees declined to read them.", new PdfReading.Read(pre, List.of(), List.of(), "", two, 0, 0, "").said(Chrome.ENGLISH));
        for (String why : PdfReading.MANY.keySet()) assertNotEquals(why, PdfReading.MANY.get(why));
    }

    @Test
    void aTitleThatIsAFilesNameGivesWayToTheTitleOnThePage() {
        PdfLayout.Block title = new PdfLayout.Block(PdfLayout.TITLE, "The Town Clock", 1, new Box(0, 0, 1, 1), true, 1, 18, 1, new Box(0, 0, 1, 1));
        PdfLayout.Page p = new PdfLayout.Page(1, 595, 842, "ltr", List.of(title), null);
        PdfReading.Preflight pre = new PdfReading.Preflight(1, List.of(), List.of(), "en");
        assertEquals("The Town Clock", new PdfReading.Read(pre, List.of(p), List.of(), "", Map.of(), 0, 0, "cover-v12.indd").title());
        assertEquals("A Proper Title", new PdfReading.Read(pre, List.of(p), List.of(), "", Map.of(), 0, 0, "A Proper Title").title());
    }
}
