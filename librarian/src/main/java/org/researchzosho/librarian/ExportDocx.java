package org.researchzosho.librarian;

import org.researchzosho.librarian.ExportDoc.Block;
import org.researchzosho.librarian.ExportDoc.Code;
import org.researchzosho.librarian.ExportDoc.Heading;
import org.researchzosho.librarian.ExportDoc.Image;
import org.researchzosho.librarian.ExportDoc.Item;
import org.researchzosho.librarian.ExportDoc.Mark;
import org.researchzosho.librarian.ExportDoc.Para;
import org.researchzosho.librarian.ExportDoc.Rule;
import org.researchzosho.librarian.ExportDoc.Span;
import org.researchzosho.librarian.ExportDoc.Table;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Word file of an export (0.7.0), written here as the zip of XML parts a .docx is, with no library: the document with its headings,
 * paragraphs, bold and italic, links the reader clicks, bulleted and numbered lists, a source's own line with its number hanging, tables
 * and code in a fixed-width font; the styles those use, the numbering of the lists, the links' addresses, and a footer with the page's
 * number. It opens in Word, LibreOffice, Pages and Google Docs.
 */
final class ExportDocx {
    private ExportDocx() { }

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String PKG = "http://schemas.openxmlformats.org/package/2006/relationships";

    /** The .docx of a document. */
    static byte[] write(ExportDoc d) throws IOException {
        Body body = new Body();
        for (Block b : d.blocks) body.block(b);
        boolean cjk = d.lang.equals("ja") || d.lang.equals("zh") || d.lang.equals("ko");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", contentTypes());
            put(z, "_rels/.rels", "<Relationships xmlns=\"" + PKG + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"" + PKG + "/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "<Relationship Id=\"rId3\" Type=\"" + R + "/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>");
            put(z, "docProps/core.xml", "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                    + "xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>" + xml(d.title) + "</dc:title><dc:creator>ResearchZosho</dc:creator>"
                    + "<dc:language>" + xml(d.lang) + "</dc:language><dcterms:created xsi:type=\"dcterms:W3CDTF\">" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "</dcterms:created></cp:coreProperties>");
            put(z, "docProps/app.xml", "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ResearchZosho</Application></Properties>");
            put(z, "word/document.xml", "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\" xmlns:wp=\"" + WP + "\" xmlns:a=\"" + A + "\" xmlns:pic=\"" + PIC + "\"><w:body>" + body.xml
                    + "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
            put(z, "word/styles.xml", styles(d.lang, cjk));
            put(z, "word/numbering.xml", numbering(body.lists));
            put(z, "word/settings.xml", "<w:settings xmlns:w=\"" + W + "\"><w:defaultTabStop w:val=\"567\"/><w:characterSpacingControl w:val=\"doNotCompress\"/>"
                    + "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>");
            put(z, "word/footer1.xml", "<w:ftr xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\"><w:p><w:pPr><w:pStyle w:val=\"Footer\"/></w:pPr>"
                    + (d.footer.isBlank() ? "" : run(Span.plain(d.footer + " · "), null)) + run(Span.plain("page "), null)
                    + "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple>" + run(Span.plain(" of "), null)
                    + "<w:fldSimple w:instr=\" NUMPAGES \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>");
            StringBuilder rels = new StringBuilder("<Relationships xmlns=\"" + PKG + "\">"
                    + "<Relationship Id=\"rIdStyles\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rIdNumbering\" Type=\"" + R + "/numbering\" Target=\"numbering.xml\"/>"
                    + "<Relationship Id=\"rIdSettings\" Type=\"" + R + "/settings\" Target=\"settings.xml\"/>"
                    + "<Relationship Id=\"rIdFooter\" Type=\"" + R + "/footer\" Target=\"footer1.xml\"/>");
            for (var e : body.links.entrySet())
                rels.append("<Relationship Id=\"").append(e.getValue()).append("\" Type=\"").append(R).append("/hyperlink\" Target=\"").append(xml(ExportPdf.ascii(e.getKey()))).append("\" TargetMode=\"External\"/>");
            int k = 0;
            for (var e : body.media.entrySet()) {   // the pictures the document shows, each a part under word/media and a relationship the drawing names
                k++;
                rels.append("<Relationship Id=\"rIdImg").append(k).append("\" Type=\"").append(R).append("/image\" Target=\"media/").append(e.getKey()).append("\"/>");
                z.putNextEntry(new ZipEntry("word/media/" + e.getKey())); z.write(e.getValue()); z.closeEntry();
            }
            put(z, "word/_rels/document.xml.rels", rels.append("</Relationships>").toString());
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String xmlText) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + xmlText).getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing", A = "http://schemas.openxmlformats.org/drawingml/2006/main", PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    private static String contentTypes() {
        String o = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + o + "document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"" + o + "styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"" + o + "numbering+xml\"/>"
                + "<Override PartName=\"/word/settings.xml\" ContentType=\"" + o + "settings+xml\"/>"
                + "<Override PartName=\"/word/footer1.xml\" ContentType=\"" + o + "footer+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>";
    }

    /** The document's body as XML, the links it points at, and the numbered lists it holds (each numbered from its own first number). */
    private static final class Body {
        final StringBuilder xml = new StringBuilder();
        final Map<String, String> links = new LinkedHashMap<>();
        final Map<String, byte[]> media = new LinkedHashMap<>();   // the pictures shown, by the name of their part under word/media; the k-th is rIdImg<k>
        final List<Integer> lists = new ArrayList<>();   // the first number of each numbered list, in order; its numId is 2 + its index
        boolean firstHeading = true, inNumbered = false;

        void block(Block b) {
            if (!(b instanceof Item i && i.mark() == Mark.NUMBER)) inNumbered = false;
            switch (b) {
                case Heading h -> {
                    String style = h.level() == 1 && firstHeading ? "Title" : "Heading" + Math.min(3, Math.max(1, h.level() - 1));
                    firstHeading = false;
                    xml.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>").append(runs(h.spans())).append("</w:p>");
                }
                case Para p -> xml.append("<w:p>").append(p.small() ? "<w:pPr><w:pStyle w:val=\"Facts\"/></w:pPr>" : "").append(runs(p.spans())).append("</w:p>");
                case Item i -> item(i);
                case Table t -> table(t);
                case Code c -> {
                    for (int i = 0; i < c.lines().size(); i++)
                        xml.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/>").append(i == c.lines().size() - 1 ? "<w:spacing w:after=\"160\"/>" : "").append("</w:pPr>")
                           .append(run(new Span(c.lines().get(i), false, false, true, null), null)).append("</w:p>");
                }
                case Rule r -> xml.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"A0A0A0\"/></w:pBdr></w:pPr></w:p>");
                case Image i -> image(i);
            }
        }

        /** The most a picture takes on the page, in EMUs: six inches wide, four and a half tall; a pixel is 9525 EMU (96 to the inch). */
        static final long PICTURE_W = 5_486_400L, PICTURE_H = 4_114_800L, EMU_PER_PIXEL = 9_525L;

        /**
         * A picture the library holds, embedded as Word embeds one (PNG or JPEG as they are; GIF, BMP and TIFF written again as PNG) with its
         * caption under it; one the library does not hold, or one Java cannot read (WebP), leaves its caption in its place.
         */
        void image(Image i) {
            byte[] b = i.bytes(); String ext = b == null ? null : Images.extension("", b);
            int[] wh = b == null ? new int[]{0, 0} : Images.dimensions(b);
            if (b != null && ext != null && !ext.equals("png") && !ext.equals("jpg")) {
                try { b = DocPictures.png(ImageIO.read(new ByteArrayInputStream(b))); ext = "png"; } catch (IOException | RuntimeException e) { b = null; }
            }
            if (b == null || ext == null || wh[0] <= 0 || wh[1] <= 0) { caption("[" + i.caption() + "]"); return; }
            String name = "image" + (media.size() + 1) + "." + (ext.equals("jpg") ? "jpeg" : "png");
            media.put(name, b);
            int n = media.size();
            long cx = wh[0] * EMU_PER_PIXEL, cy = wh[1] * EMU_PER_PIXEL;
            double scale = Math.min(1.0, Math.min((double) PICTURE_W / cx, (double) PICTURE_H / cy));
            cx = Math.round(cx * scale); cy = Math.round(cy * scale);
            String alt = xml(i.alt() == null ? "" : i.alt());
            xml.append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
               .append("<wp:docPr id=\"").append(n).append("\" name=\"Picture ").append(n).append("\" descr=\"").append(alt).append("\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
               .append("<a:graphic><a:graphicData uri=\"").append(PIC).append("\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(n).append("\" name=\"").append(name).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
               .append("<pic:blipFill><a:blip r:embed=\"rIdImg").append(n).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
               .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
            caption(i.caption());
        }

        /** A picture's caption, set as the line of facts is. */
        void caption(String words) { xml.append("<w:p><w:pPr><w:pStyle w:val=\"Facts\"/></w:pPr>").append(run(new Span(words, false, true, false, null), null)).append("</w:p>"); }

        void item(Item i) {
            switch (i.mark()) {
                case BULLET -> xml.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"").append(i.depth()).append("\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>")
                        .append(runs(i.spans())).append("</w:p>");
                case NUMBER -> {
                    if (!inNumbered) { lists.add(i.number()); inNumbered = true; }
                    xml.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"").append(i.depth()).append("\"/><w:numId w:val=\"").append(1 + lists.size()).append("\"/></w:numPr></w:pPr>")
                            .append(runs(i.spans())).append("</w:p>");
                }
                case SOURCE -> xml.append("<w:p><w:pPr><w:pStyle w:val=\"Source\"/></w:pPr>").append(run(Span.bold("[" + i.number() + "]"), null)).append("<w:r><w:tab/></w:r>")
                        .append(runs(i.spans())).append("</w:p>");
            }
        }

        void table(Table t) {
            int cols = 0;
            for (List<List<Span>> row : t.rows()) cols = Math.max(cols, row.size());
            if (cols == 0) return;
            // each column as wide as its longest cell wants, up to a line, and never narrower than its longest word; twips of the 9638 between the margins
            double[] want = new double[cols], least = new double[cols];
            for (List<List<Span>> row : t.rows()) for (int c = 0; c < row.size(); c++) {
                String text = ExportDoc.plain(row.get(c)).strip();
                want[c] = Math.max(want[c], Math.min(60, Math.max(3, text.length())) * 105 + 220);
                for (String w : text.split("\\s+")) least[c] = Math.max(least[c], Math.min(24, Math.max(3, w.length())) * 105 + 220);
            }
            double avail = 9638, sumW = 0, sumL = 0;
            for (int c = 0; c < cols; c++) { sumW += want[c]; sumL += least[c]; }
            int[] tw = new int[cols];
            for (int c = 0; c < cols; c++)
                tw[c] = (int) (sumW <= avail ? want[c] : sumL >= avail ? least[c] * avail / sumL : least[c] + (avail - sumL) * (want[c] - least[c]) / Math.max(1, sumW - sumL));
            xml.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"Grid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblLayout w:type=\"autofit\"/><w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>");
            for (int c = 0; c < cols; c++) xml.append("<w:gridCol w:w=\"").append(tw[c]).append("\"/>");
            xml.append("</w:tblGrid>");
            for (int r = 0; r < t.rows().size(); r++) {
                boolean head = t.header() && r == 0;
                List<List<Span>> row = t.rows().get(r);
                xml.append("<w:tr>").append(head ? "<w:trPr><w:tblHeader/></w:trPr>" : "");
                for (int c = 0; c < cols; c++) {
                    List<Span> cell = c < row.size() ? row.get(c) : List.of();
                    if (head) { List<Span> b = new ArrayList<>(); for (Span s : cell) b.add(new Span(s.text(), true, s.italic(), s.code(), s.href())); cell = b; }
                    xml.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(tw[c]).append("\" w:type=\"dxa\"/>").append(head ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EFEFED\"/>" : "")
                       .append("</w:tcPr><w:p><w:pPr><w:pStyle w:val=\"Cell\"/></w:pPr>").append(runs(cell)).append("</w:p></w:tc>");
                }
                xml.append("</w:tr>");
            }
            xml.append("</w:tbl><w:p><w:pPr><w:pStyle w:val=\"Cell\"/></w:pPr></w:p>");   // a table is followed by a paragraph, as Word writes it
        }

        /** The runs of some spans, a link's runs inside its hyperlink. */
        String runs(List<Span> spans) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < spans.size(); ) {
                Span s = spans.get(i);
                if (s.href() == null) { b.append(run(s, null)); i++; continue; }
                String id = links.computeIfAbsent(s.href(), k -> "rIdLink" + (links.size() + 1));
                b.append("<w:hyperlink r:id=\"").append(id).append("\" w:history=\"1\">");
                while (i < spans.size() && s.href().equals(spans.get(i).href())) { b.append(run(spans.get(i), "Hyperlink")); i++; }
                b.append("</w:hyperlink>");
            }
            return b.toString();
        }
    }

    /** One run: its character style, bold, italic, code; tabs and line breaks as Word writes them. */
    static String run(Span s, String style) {
        StringBuilder rp = new StringBuilder();
        if (style != null) rp.append("<w:rStyle w:val=\"").append(style).append("\"/>");
        else if (s.code()) rp.append("<w:rStyle w:val=\"CodeChar\"/>");
        if (s.code() && style != null) rp.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>");
        if (s.bold()) rp.append("<w:b/>");
        if (s.italic()) rp.append("<w:i/>");
        StringBuilder b = new StringBuilder("<w:r>");
        if (rp.length() > 0) b.append("<w:rPr>").append(rp).append("</w:rPr>");
        String[] parts = s.text().split("\n", -1);
        for (int p = 0; p < parts.length; p++) {
            if (p > 0) b.append("<w:br/>");
            String[] tabs = parts[p].split("\t", -1);
            for (int t = 0; t < tabs.length; t++) {
                if (t > 0) b.append("<w:tab/>");
                if (!tabs[t].isEmpty()) b.append("<w:t xml:space=\"preserve\">").append(xml(tabs[t])).append("</w:t>");
            }
        }
        return b.append("</w:r>").toString();
    }

    /** Text as XML may carry it: the five marks escaped, and the characters XML forbids (controls, unpaired surrogates, U+FFFE/FFFF) left out. */
    static String xml(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i); i += Character.charCount(cp);
            boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (!ok) continue;
            switch (cp) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.appendCodePoint(cp);
            }
        }
        return b.toString();
    }

    private static String styles(String lang, boolean cjk) {
        // Latin text keeps a Latin language, so Word and LibreOffice break its words at spaces; Japanese, Chinese and Korean go in eastAsia
        String east = switch (lang) { case "ja" -> "ja-JP"; case "zh" -> "zh-CN"; case "ko" -> "ko-KR"; default -> ""; };
        String l = "<w:lang w:val=\"" + (lang.equals("en") || cjk ? "en-GB" : xml(lang)) + "\"" + (cjk ? " w:eastAsia=\"" + east + "\"" : "") + "/>";
        StringBuilder b = new StringBuilder("<w:styles xmlns:w=\"" + W + "\">");
        b.append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\" w:cs=\"Georgia\"/><w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/>").append(l)
         .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>");
        b.append(para("Normal", "Normal", null, "", "", true));
        b.append(para("Title", "Title", "Normal", "<w:keepNext/><w:spacing w:after=\"160\"/>", "<w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/>", false));
        b.append(para("Heading1", "heading 1", "Normal", "<w:keepNext/><w:spacing w:before=\"280\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/>", "<w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/>", false));
        b.append(para("Heading2", "heading 2", "Normal", "<w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/>", "<w:b/><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/>", false));
        b.append(para("Heading3", "heading 3", "Normal", "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"40\"/><w:outlineLvl w:val=\"2\"/>", "<w:b/><w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/>", false));
        b.append(para("Facts", "Facts", "Normal", "<w:spacing w:after=\"160\"/>", "<w:i/><w:color w:val=\"5C5C5C\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>", false));
        b.append(para("ListParagraph", "List Paragraph", "Normal", "<w:spacing w:after=\"40\"/><w:ind w:left=\"720\"/>", "", false));
        b.append(para("Source", "Source", "Normal", "<w:tabs><w:tab w:val=\"left\" w:pos=\"680\"/></w:tabs><w:spacing w:after=\"40\"/><w:ind w:left=\"680\" w:hanging=\"680\"/>", "", false));
        b.append(para("Cell", "Table text", "Normal", "<w:spacing w:after=\"0\"/>", "<w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>", false));
        b.append(para("Code", "Code", "Normal", "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F0F0EE\"/><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>", "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/>", false));
        b.append(para("Footer", "footer", "Normal", "<w:spacing w:after=\"0\"/>", "<w:color w:val=\"6E6E6E\"/><w:sz w:val=\"16\"/><w:szCs w:val=\"16\"/>", false));
        b.append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:rPr><w:color w:val=\"1A479E\"/><w:u w:val=\"single\"/></w:rPr></w:style>");
        b.append("<w:style w:type=\"character\" w:styleId=\"CodeChar\"><w:name w:val=\"Code Char\"/><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:color w:val=\"3D3329\"/></w:rPr></w:style>");
        b.append("<w:style w:type=\"table\" w:styleId=\"Grid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>");
        for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) b.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"ADADAD\"/>");
        b.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>");
        return b.append("</w:styles>").toString();
    }

    private static String para(String id, String name, String basedOn, String pPr, String rPr, boolean isDefault) {
        return "<w:style w:type=\"paragraph\"" + (isDefault ? " w:default=\"1\"" : "") + " w:styleId=\"" + id + "\"><w:name w:val=\"" + name + "\"/>"
                + (basedOn == null ? "" : "<w:basedOn w:val=\"" + basedOn + "\"/>") + "<w:qFormat/>"
                + (pPr.isEmpty() ? "" : "<w:pPr>" + pPr + "</w:pPr>") + (rPr.isEmpty() ? "" : "<w:rPr>" + rPr + "</w:rPr>") + "</w:style>";
    }

    /** The bullets (list 1) and one numbering per numbered list, each starting at its own first number. */
    private static String numbering(List<Integer> lists) {
        StringBuilder b = new StringBuilder("<w:numbering xmlns:w=\"" + W + "\">");
        String[] bullets = {"•", "◦", "▪", "–"};
        b.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        for (int l = 0; l < 4; l++) b.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(bullets[l])
                .append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append(360 + 360 * l).append("\" w:hanging=\"280\"/></w:pPr></w:lvl>");
        b.append("</w:abstractNum><w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        String[] fmt = {"decimal", "lowerLetter", "lowerRoman", "decimal"};
        for (int l = 0; l < 4; l++) b.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(fmt[l]).append("\"/><w:lvlText w:val=\"%").append(l + 1)
                .append(".\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append(400 + 360 * l).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        b.append("</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>");
        for (int i = 0; i < lists.size(); i++)
            b.append("<w:num w:numId=\"").append(2 + i).append("\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"").append(Math.max(0, lists.get(i))).append("\"/></w:lvlOverride></w:num>");
        return b.append("</w:numbering>").toString();
    }
}
