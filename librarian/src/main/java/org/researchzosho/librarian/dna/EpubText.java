package org.researchzosho.librarian.dna;

import org.researchzosho.tools.Entities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The whole text of an EPUB, chapter by chapter, for the DNA's measured and labelled layers (0.6.0). The spine's documents in order when
 * the package document can be read, else every HTML entry by name; each document as plain text with a line break at every block tag and
 * the head, scripts, styles and drawings left out. The same cut the measurement tooling made, so a feature counted here equals the one
 * counted there. The record a loose file carries in its own package document is read too, for a book that is not in a Calibre library.
 */
public final class EpubText {
    private EpubText() { }

    static final Pattern ROOTFILE = Pattern.compile("full-path=\"([^\"]+)\"");
    static final Pattern ITEM_ID_HREF = Pattern.compile("<item[^>]*?id=\"([^\"]+)\"[^>]*?href=\"([^\"]+)\"");
    static final Pattern ITEM_HREF_ID = Pattern.compile("<item[^>]*?href=\"([^\"]+)\"[^>]*?id=\"([^\"]+)\"");
    static final Pattern ITEMREF = Pattern.compile("<itemref[^>]*?idref=\"([^\"]+)\"");
    static final Pattern DOC_NAME = Pattern.compile("(?i).*\\.(x?html?|xml)$");
    static final Pattern HTML_NAME = Pattern.compile("(?i).*\\.(x?html?)$");

    /** The chapters as plain text, spine order first. */
    public static List<String> chapters(Path epub) throws IOException {
        try (ZipFile z = new ZipFile(epub.toFile())) {
            Set<String> names = new TreeSet<>();
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) names.add(e.nextElement().getName());
            List<String> docs = new ArrayList<>();
            try {
                String cont = read(z, "META-INF/container.xml");
                Matcher m = ROOTFILE.matcher(cont);
                if (!m.find()) throw new IOException("no rootfile");
                String opf = m.group(1), opfx = read(z, opf);
                int slash = opf.lastIndexOf('/');
                String base = slash < 0 ? "" : opf.substring(0, slash);
                Map<String, String> items = new HashMap<>();
                Matcher im = ITEM_ID_HREF.matcher(opfx); while (im.find()) items.put(im.group(1), im.group(2));
                im = ITEM_HREF_ID.matcher(opfx); while (im.find()) items.put(im.group(2), im.group(1));
                Matcher sm = ITEMREF.matcher(opfx);
                while (sm.find()) {
                    String href = items.get(sm.group(1));
                    if (href == null) continue;
                    String p = Paths.get(base, Entities.decode(href).split("#")[0]).normalize().toString().replace('\\', '/');
                    if (names.contains(p) && DOC_NAME.matcher(p).matches()) docs.add(p);
                }
            } catch (Exception e) { docs.clear(); }
            if (docs.isEmpty()) for (String n : names) if (HTML_NAME.matcher(n).matches()) docs.add(n);
            List<String> out = new ArrayList<>();
            for (String d : docs) {
                try { out.add(text(read(z, d))); } catch (Exception ignored) { }
            }
            return out;
        }
    }

    static String read(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) throw new IOException("no entry " + name);
        try (InputStream in = z.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    static final Set<String> SKIP = Set.of("script", "style", "head", "title", "svg", "math");
    static final Set<String> BLOCK = Set.of("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "article", "hr");

    /**
     * HTML as text: the data between tags with its entities decoded, a line break at the start and the end of every block tag, nothing
     * from inside the skipped elements; comments, declarations and processing instructions dropped. A self-closed tag opens and closes.
     */
    public static String text(String html) {
        StringBuilder out = new StringBuilder();
        int skip = 0, i = 0, n = html.length();
        while (i < n) {
            int lt = html.indexOf('<', i);
            if (lt < 0) { if (skip == 0) out.append(Entities.decode(html.substring(i))); break; }
            if (lt > i && skip == 0) out.append(Entities.decode(html.substring(i, lt)));
            if (html.startsWith("<!--", lt)) { int end = html.indexOf("-->", lt + 4); i = end < 0 ? n : end + 3; continue; }
            if (html.startsWith("<![CDATA[", lt)) { int end = html.indexOf("]]>", lt + 9); i = end < 0 ? n : end + 3; continue; }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) { int end = html.indexOf('>', lt); i = end < 0 ? n : end + 1; continue; }
            // a tag: to its closing >, quoted attribute values stepped over
            int j = lt + 1; char q = 0;
            while (j < n) { char c = html.charAt(j); if (q != 0) { if (c == q) q = 0; } else if (c == '"' || c == '\'') q = c; else if (c == '>') break; j++; }
            if (j >= n) { break; }
            String tag = html.substring(lt + 1, j);
            boolean end = tag.startsWith("/"), selfClosed = tag.endsWith("/");
            String body = tag.substring(end ? 1 : 0, selfClosed ? tag.length() - 1 : tag.length()).strip();
            int sp = 0; while (sp < body.length() && !Character.isWhitespace(body.charAt(sp)) && body.charAt(sp) != '/') sp++;
            String name = body.substring(0, sp).toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                if (end) endTag(name, out, skip); else { skip = startTag(name, out, skip); if (selfClosed) skip = endTagCounted(name, out, skip); }
                if (end && SKIP.contains(name) && skip > 0) skip--;
            }
            i = j + 1;
        }
        return out.toString();
    }

    private static int startTag(String name, StringBuilder out, int skip) {
        if (SKIP.contains(name)) return skip + 1;
        if (BLOCK.contains(name)) out.append('\n');
        return skip;
    }

    private static void endTag(String name, StringBuilder out, int skip) {
        if (SKIP.contains(name)) return;   // the count is lowered by the caller
        if (BLOCK.contains(name)) out.append('\n');
    }

    private static int endTagCounted(String name, StringBuilder out, int skip) {
        if (SKIP.contains(name)) return skip > 0 ? skip - 1 : skip;
        if (BLOCK.contains(name)) out.append('\n');
        return skip;
    }

    /** What a loose EPUB says about itself: title, authors, year, language, subjects, ISBN; the file name when its package document has none. */
    public static Map<String, Object> record(Path epub) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("title", ""); rec.put("authors", ""); rec.put("year", ""); rec.put("language", ""); rec.put("tags", new ArrayList<String>()); rec.put("isbn", "");
        try (ZipFile z = new ZipFile(epub.toFile())) {
            Matcher m = ROOTFILE.matcher(read(z, "META-INF/container.xml"));
            if (m.find()) {
                String x = read(z, m.group(1));
                rec.put("title", first(x, "title"));
                List<String> creators = new ArrayList<>();
                Matcher c = Pattern.compile("<(?:dc:)?creator[^>]*>(.*?)</(?:dc:)?creator>", Pattern.DOTALL).matcher(x);
                while (c.find()) creators.add(Entities.decode(c.group(1)).strip());
                rec.put("authors", String.join(" & ", creators));
                String d = first(x, "date"); rec.put("year", d.matches("\\d{4}.*") ? d.substring(0, 4) : "");
                String lang = first(x, "language"); rec.put("language", lang.length() > 3 ? lang.substring(0, 3) : lang);
                List<String> tags = new ArrayList<>();
                Matcher s = Pattern.compile("<(?:dc:)?subject[^>]*>(.*?)</(?:dc:)?subject>", Pattern.DOTALL).matcher(x);
                while (s.find() && tags.size() < 12) tags.add(Entities.decode(s.group(1)).strip());
                rec.put("tags", tags);
                Matcher isbn = Pattern.compile("<(?:dc:)?identifier[^>]*(?:scheme|id)=\"?[^\">]*ISBN[^\">]*\"?[^>]*>([^<]+)<", Pattern.CASE_INSENSITIVE).matcher(x);
                if (isbn.find()) rec.put("isbn", isbn.group(1).strip());
            }
        } catch (Exception ignored) { }
        if (((String) rec.get("title")).isEmpty()) {
            String name = epub.getFileName().toString().replaceFirst("(?i)\\.epub$", "");
            String[] parts = name.split("\\s+-\\s+", 2);
            rec.put("title", parts[0].replace('_', ' ').strip());
            if (parts.length > 1) rec.put("authors", parts[1].replaceFirst("_\\d+$", "").replace('_', ' ').strip());
        }
        return rec;
    }

    static String first(String x, String tag) {
        Matcher m = Pattern.compile("<(?:dc:)?" + tag + "[^>]*>(.*?)</(?:dc:)?" + tag + ">", Pattern.DOTALL).matcher(x);
        return m.find() ? Entities.decode(m.group(1)).strip() : "";
    }
}
