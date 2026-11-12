package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.PdfLayout.Block;
import org.researchzosho.librarian.PdfLayout.Box;
import org.researchzosho.librarian.PdfLayout.Glyph;
import org.researchzosho.librarian.PdfLayout.Page;
import org.researchzosho.librarian.PdfLayout.PageIn;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The layout of a PDF's page from its glyphs alone (0.7.3): made-up pages of glyphs, no font, no file. Each kind of page the reader must
 * read as a person reads it: two columns with a running head and page numbers; Japanese set vertically, with a title, a name set with
 * wide spacing, ruby, a note's mark, a number set across its column and a horizontal caption; right-to-left lines with a number and
 * brackets, from a PDF that maps its glyphs to what they mean and from one that maps them to the shapes drawn; a superscript and small
 * capitals.
 */
class PdfLayoutTest {

    /** A horizontal line of glyphs, each character half its size wide (a CJK character its whole size), from {@code x} on {@code baseline}. */
    static List<Glyph> line(String text, double x, double baseline, double size) {
        List<Glyph> out = new ArrayList<>();
        double at = x;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i); String s = new String(Character.toChars(c)); i += Character.charCount(c);
            double w = PdfLayout.cjk(c) ? size : 0.5 * size;
            out.add(Glyph.h(s, at, baseline, size, w));
            at += w;
        }
        return out;
    }

    /** A vertical column of glyphs from {@code top} down, one em each, centred on {@code centre}. */
    static List<Glyph> column(String text, double centre, double top, double size) {
        List<Glyph> out = new ArrayList<>();
        double at = top;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i); String s = new String(Character.toChars(c)); i += Character.charCount(c);
            out.add(Glyph.v(s, centre, at, size));
            at += size;
        }
        return out;
    }

    static List<String> texts(Page p) { List<String> out = new ArrayList<>(); for (Block b : p.blocks()) if (!b.kind().equals(PdfLayout.PICTURE)) out.add(b.text()); return out; }

    @Test
    void twoColumnsAreReadColumnByColumnWithTheirRunningHeadAndPageNumbersTakenOut() {
        List<PageIn> pages = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            List<Glyph> g = new ArrayList<>();
            g.addAll(line("The Quarterly of Clocks", 230, 40, 9));
            g.addAll(line(String.valueOf(n), 296, 815, 9));
            // the left column: a paragraph with a word hyphenated at a line's end, then an indented one that runs into the right column
            double y = 120;
            for (String l : List.of("The first paragraph of page " + n + " starts here", "and runs on for a while until a hyphen-", "ated word is joined at the line's end.")) { g.addAll(line(l, 72, y, 10)); y += 12; }
            g.addAll(line("A second paragraph begins indented and", 82, y, 10)); y += 12;
            g.addAll(line("runs on to the end of the left column", 72, y, 10));
            // the right column, on the same baselines: the second paragraph goes on without an indent, a third is indented
            y = 120;
            for (String l : List.of("and over the gutter into the right one,", "where it ends after a while, then stops.")) { g.addAll(line(l, 310, y, 10)); y += 12; }
            g.addAll(line("A third paragraph is short and plain.", 320, y, 10)); y += 12;
            for (String l : List.of("It has two more lines of text in it to", "make the gutter between the columns.")) { g.addAll(line(l, 310, y, 10)); y += 12; }
            pages.add(new PageIn(n, 595, 842, g, List.of()));
        }
        List<Page> read = PdfLayout.document(pages);
        Page first = read.get(0);
        assertEquals("ltr", first.direction());
        assertEquals(List.of("The first paragraph of page 1 starts here and runs on for a while until a hyphenated word is joined at the line's end.",
                "A second paragraph begins indented and runs on to the end of the left column and over the gutter into the right one, where it ends after a while, then stops.",
                "A third paragraph is short and plain. It has two more lines of text in it to make the gutter between the columns."), texts(first), "the left column whole before the right, the paragraph joined over the gutter, the hyphen taken out");
        for (Page p : read) for (String t : texts(p)) assertFalse(t.contains("Quarterly") || t.matches("\\d"), "the running head and the page number are furniture: " + t);
        assertEquals(2, first.stats().furniture());
    }

    @Test
    void japaneseSetVerticallyIsReadColumnByColumnFromTheRightWithItsRubyLeftOutAndItsMarksKept() {
        List<Glyph> g = new ArrayList<>();
        g.addAll(column("古い時計の話", 340, 90, 17));                                  // the title
        for (int i = 0; i < 4; i++) g.add(Glyph.v("時計同好".substring(i, i + 1), 275, 370 + i * 38, 12.8));   // a name set with wide spacing
        for (Glyph x : column("はじめに", 219, 107, 9.2)) g.add(x.bolded());          // a heading
        double s = 9.2;
        // the body: columns of 46 characters, 17 points apart from x = 185 leftward; a paragraph starts one character down
        String p1 = "時計は町の広場に立ち、人々に時を知らせてきた。その針は長い間止まっていたが、近所の人々が集まって修理を始め、ようやく動き始めた。町の人々は毎朝その音を聞いて一日を始める";
        String p2 = "修理には二十年の月日がかかり、部品は";
        String p2rest = "個が新しく作られた。古い部品は資料館にある。";
        g.addAll(column(p1.substring(0, 45), 185, 61.65 + s, s));
        List<Glyph> c2 = column(p1.substring(45), 168, 61.65, s);
        g.addAll(c2);
        double last = c2.get(c2.size() - 1).y0();   // a note's mark beside the paragraph's last character, on its right; then the full stop
        double mx = 168 + s / 2 + 2.3;
        g.add(Glyph.v("（", mx, last, 4.6)); g.add(Glyph.h("1", mx - 1.15, last + 4.6 + 0.8 * 4.6, 4.6, 2.3)); g.add(Glyph.v("）", mx, last + 9.2, 4.6));
        g.add(Glyph.v("。", 168, last + s, s));
        List<Glyph> c3 = column(p2, 151, 61.65 + s, s);
        g.addAll(c3);
        double after = c3.get(c3.size() - 1).y1();
        g.add(Glyph.h("2", 151 - 2.3, after + 0.8 * 4.6 + 2, 4.6, 2.3)); g.add(Glyph.h("9", 151, after + 0.8 * 4.6 + 2, 4.6, 2.3));   // 縦中横: two digits across the column
        g.addAll(column(p2rest, 151, after + s, s));
        // ruby beside 時計 at the top of the first column, on its right
        for (int i = 0; i < 3; i++) g.add(Glyph.v("とけい".substring(i, i + 1), 185 + s / 2 + 2.3, 61.65 + s + i * 6.1, 4.6));
        g.addAll(column("一七", 21, 442, 8));                                         // the page number at the margin
        g.addAll(line("Figure 1 The clock", 40, 560, 8));                            // a horizontal caption at the bottom left
        Page p = PdfLayout.page(new PageIn(17, 420, 595, g, List.of()));
        assertEquals("vertical", p.direction());
        List<String> t = texts(p);
        assertEquals("古い時計の話", t.get(0), t.toString());
        assertEquals(PdfLayout.TITLE, p.blocks().get(0).kind(), "the largest heading of the first page is its title");
        assertEquals("時計同好", t.get(1), "a name set with wide spacing is one line: " + t);
        assertEquals("はじめに", t.get(2));
        assertEquals(PdfLayout.HEADING, p.blocks().get(2).kind());
        assertEquals(p1 + "（1）。", t.get(3), "two columns one paragraph, the ruby left out, the note's mark at its place: " + t);
        assertEquals(p2 + "29" + p2rest, t.get(4), "the digits set across the column are read in it: " + t);
        assertEquals("Figure 1 The clock", t.get(t.size() - 1), "a horizontal caption at the page's left is read after the columns to its right: " + t);
        assertFalse(t.stream().anyMatch(x -> x.contains("とけい") || x.equals("一七")), "no ruby, no page number: " + t);
        assertTrue(t.stream().noneMatch(x -> x.codePointCount(0, x.length()) == 1), "no paragraph of one character: " + t);
        assertEquals(1, p.stats().ruby());
        assertEquals(1, p.stats().marks());
    }

    @Test
    void aRightToLeftLineIsGivenInTheOrderItsReaderReadsItWhicheverWayThePdfMapsItsBrackets() {
        // the line שלום עולם (2024) drawn right to left: left to right on the page it is "(2024) םלוע םולש"
        String drawnAsShapes = "(2024) םלוע םולש";      // a PDF that maps each bracket glyph to the shape drawn
        String drawnAsMeant = ")2024( םלוע םולש";       // one that maps it to the character it means
        for (String drawn : List.of(drawnAsShapes, drawnAsMeant)) {
            List<Glyph> g = new ArrayList<>(line(drawn, 360, 100, 10));
            g.addAll(line(".דומעב ינש טפשמ והז", 345, 112, 10));   // the next line, flush right with the first
            Page p = PdfLayout.page(new PageIn(1, 595, 842, g, List.of()));
            assertEquals("rtl", p.direction());
            assertEquals("שלום עולם (2024) זהו משפט שני בעמוד.", texts(p).get(0), "drawn as " + drawn);
        }
        // two numbers drawn with a space between are one number: 6 000 stays 6 000
        Page n = PdfLayout.page(new PageIn(1, 595, 842, line("שיא 6 000 רפסמ", 300, 100, 10), List.of()));
        assertEquals("מספר 6 000 איש", texts(n).get(0));
    }

    @Test
    void aSuperscriptIsWrittenAsOneAndSmallCapitalsStayInTheirWord() {
        List<Glyph> g = new ArrayList<>(line("Clocks were wound by hand", 72, 100, 10));
        double end = g.get(g.size() - 1).x1();
        g.add(Glyph.h("1", end, 96.5, 6.5, 3.25));                     // a note's number, raised and small
        g.addAll(line(" every week.", end + 3.25, 100, 10));
        g.add(Glyph.h("Y", 72, 130, 10, 6)); g.addAll(line("ALE", 78, 130, 7)); g.addAll(line(" Law Journal.", 88.5, 130, 10));
        Page p = PdfLayout.page(new PageIn(1, 595, 842, g, List.of()));
        String all = String.join(" ", texts(p));
        assertTrue(all.contains("by hand¹ every week."), all);
        assertTrue(all.contains("YALE Law Journal."), "small capitals on the line's baseline join their capital: " + all);
    }

    @Test
    void aDiagramsLabelsAreGatheredAndATitlesLinesAreOneHeading() {
        List<Glyph> g = new ArrayList<>(line("A Study of Small Clocks and the Way", 150, 80, 18));
        g.addAll(line("They Keep Time", 244.5, 102, 18));
        String[] labels = {"Wheel", "Gear", "Pin", "Arm", "Hand", "Dial", "Spring", "Case", "Key"};
        for (int i = 0; i < labels.length; i++) g.addAll(line(labels[i], 100 + (i % 3) * 40, 300 + (i / 3) * 20, 8));
        for (int i = 0; i < 4; i++) g.addAll(line("The body of the page is set in ten point type and runs on.", 72, 500 + i * 12, 10));
        Page p = PdfLayout.page(new PageIn(1, 595, 842, g, List.of()));
        assertEquals("A Study of Small Clocks and the Way They Keep Time", p.blocks().get(0).text());
        assertEquals(PdfLayout.TITLE, p.blocks().get(0).kind());
        assertTrue(p.blocks().stream().anyMatch(b -> b.kind().equals(PdfLayout.FIGURE) && b.text().contains("Wheel") && b.text().contains("Key")), "the labels of a drawing are one block of its own: " + texts(p));
        assertTrue(p.blocks().stream().noneMatch(b -> b.kind().equals(PdfLayout.PARA) && b.text().equals("Pin")));
    }

    @Test
    void aWholeLineInJapaneseAndAWholeLineInEnglishAreTwoParagraphs() {
        List<Glyph> g = new ArrayList<>(line("町の時計の記録", 72, 100, 12));
        g.addAll(line("Records of the Town Clock", 72, 115, 12));
        g.addAll(line("時計はBERTの論文に", 72, 160, 12));
        g.addAll(line("出てこない。", 72, 175, 12));
        Page p = PdfLayout.page(new PageIn(1, 595, 842, g, List.of()));
        assertEquals(List.of("町の時計の記録", "Records of the Town Clock", "時計はBERTの論文に出てこない。"), texts(p), "a line in one script with a word of another is still one paragraph");
        assertEquals("cjk", PdfLayout.script("町の時計"));
        assertEquals("mixed", PdfLayout.script("時計はBERT"));
    }

    @Test
    void theBoxesOfPicturesStandInTheOrderAndTheLargestOneIsMeasured() {
        List<Glyph> g = new ArrayList<>(line("Text above the picture ends here.", 72, 100, 10));
        g.addAll(line("Text below the picture ends too.", 72, 400, 10));
        Page p = PdfLayout.page(new PageIn(1, 595, 842, g, List.of(new Box(72, 150, 400, 350))));
        assertEquals(List.of(PdfLayout.PARA, PdfLayout.PICTURE, PdfLayout.PARA), p.blocks().stream().map(Block::kind).toList());
        assertEquals(328.0 * 200 / (595 * 842), p.stats().pictureCover(), 1e-6);
    }
}
