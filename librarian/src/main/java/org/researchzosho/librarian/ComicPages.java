package org.researchzosho.librarian;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A comic's pages, out of what holds them and back into it: a CBZ or a ZIP of page pictures, a folder of them, a CBR or RAR through a
 * program this machine has ({@code bsdtar}, {@code 7z} or {@code unrar}), and a PDF whose pages are pictures: a page that is one
 * picture is taken as that picture, any other is drawn by the PDF reader ({@link #pagePicture}). The pages come in the natural order of their names ({@code page2} before {@code page10}), a folder inside the archive read
 * through in its place. Nothing of an archive is trusted: its entries are written by {@link Archives.Sink}, which lets no path leave
 * the folder, opens no archive inside the archive, takes no link, and stops at a count and a size.
 *
 * <p>What a comic is, for the translation: an archive or a folder of page pictures is one by itself; a PDF is a document unless the
 * person says it is a comic, or the comic helper, when it is set up already, finds speech balloons on a sample of its pages
 * ({@link ComicTranslation}).
 */
final class ComicPages {
    private ComicPages() { }

    static final String CBZ = "cbz", ZIP = "zip", CBR = "cbr", RAR = "rar", FOLDER = "folder", PDF = "pdf";
    /** The most pages one volume may hold, and the most it may unpack to: a volume has a few hundred pages and a few hundred megabytes. */
    static final int MAX_PAGES = 5_000;
    static final long MAX_BYTES = 4L << 30;
    /** A page picture by its name: the formats the comic helper opens. */
    static final Pattern PICTURE = Pattern.compile("(?i).+\\.(jpe?g|png|webp|bmp)$");
    /** What an archive carries beside its pages and is passed over without a word: a reader's own file of the volume's details, a system's leftovers. */
    static final Pattern BESIDE = Pattern.compile("(?i)(^|.*/)(__MACOSX/.*|\\.[^/]*|thumbs\\.db|desktop\\.ini|comicinfo\\.xml|[^/]*\\.(txt|nfo|sfv|md5|url|json|xml))$");

    /**
     * One page: its number from 1, its name as the archive or the folder has it (the page's own number for a PDF), and the picture's
     * file. A piece of a web comic's strip is a page too: it carries the strip's number and name, its own place among the strip's
     * pieces ({@code part} of {@code parts}, from 1) and where its top edge lies in the strip; a page that is no piece has no parts.
     */
    record Page(int number, String name, Path file, int part, int parts, int top) {
        Page(int number, String name, Path file) { this(number, name, file, 0, 0, 0); }
        /** The picture's format by its file's ending: jpg, png, webp, bmp. */
        String format() { String n = file.getFileName().toString().toLowerCase(Locale.ROOT); String e = n.substring(n.lastIndexOf('.') + 1); return e.equals("jpeg") ? "jpg" : e; }
        boolean piece() { return parts > 0; }
        /** The page as a person names it after the word page: "3", or "3, part 2 of 7". */
        String label() { return piece() ? number + ", part " + part + " of " + parts : String.valueOf(number); }
    }

    /** A volume opened: what held it, its pages in order, and what of it was left out and why (a file that is no picture and no leftover, a page that could not be drawn). */
    record Volume(String container, List<Page> pages, List<String> leftOut) { }

    /** Names in the order a person counts them: a run of digits by its number, the rest without regard to capitals. */
    static final Comparator<String> NATURAL = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int i2 = i, j2 = j;
                while (i2 < a.length() && Character.isDigit(a.charAt(i2))) i2++;
                while (j2 < b.length() && Character.isDigit(b.charAt(j2))) j2++;
                String na = a.substring(i, i2).replaceFirst("^0+(?=.)", ""), nb = b.substring(j, j2).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
                i = i2; j = j2; continue;
            }
            int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
            if (c != 0) return c;
            i++; j++;
        }
        int c = (a.length() - i) - (b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    };

    // ---- what a file is ----

    /**
     * What holds the pages, told by the file's own first bytes and only then by its name: {@link #FOLDER}, {@link #PDF}, {@link #CBZ} (a zip
     * that is neither a Word file nor an EPUB), {@link #CBR}; null for anything else. A file named .cbz that is a RAR inside, as many are, is a CBR.
     */
    static String container(Path in) {
        if (in == null) return null;
        if (Files.isDirectory(in)) return FOLDER;
        if (!Files.isRegularFile(in)) return null;
        byte[] head = new byte[8];
        int n;
        try (InputStream s = Files.newInputStream(in)) { n = s.readNBytes(head, 0, 8); } catch (IOException e) { return null; }
        if (n >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return PDF;
        if (n >= 7 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!' && head[4] == 0x1A && head[5] == 0x07) return CBR;
        if (n >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5) && (head[3] == 4 || head[3] == 6)) return CBZ;
        return null;
    }

    /** Whether a name inside an archive or a folder is a page: a picture, and not a system's leftover. */
    static boolean page(String name) {
        String n = name.replace('\\', '/');
        return PICTURE.matcher(n).matches() && !BESIDE.matcher(n).matches() && !n.contains("__MACOSX/");
    }

    /**
     * The names of the pages a file holds, in order, without unpacking it: a zip's from its own list, a folder's from the folder, a RAR's
     * from the program that opens it. Empty for a zip or a folder with no page picture in it; an IOException when it cannot be listed (a
     * RAR on a machine with no program for it says which to install).
     */
    static List<String> names(Path in) throws IOException {
        String kind = container(in);
        List<String> out = new ArrayList<>();
        if (kind == null || kind.equals(PDF)) return out;
        if (kind.equals(FOLDER)) {
            try (Stream<Path> all = Files.walk(in, 4)) {
                for (Path p : (Iterable<Path>) all.limit(4L * MAX_PAGES)::iterator) {
                    String rel = in.relativize(p).toString().replace('\\', '/');
                    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && page(rel)) out.add(rel);
                }
            }
        } else if (kind.equals(CBZ)) {
            for (String n : zipNames(in)) if (page(n)) out.add(n);
        } else {
            for (String n : rarNames(in)) if (page(n)) out.add(n);
        }
        out.sort(NATURAL);
        return out;
    }

    /** Every file's name in a zip, as its own list gives them: read as UTF-8, and as the old DOS code page when the names are not UTF-8 (an archive made on an older system). */
    static List<String> zipNames(Path in) throws IOException {
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("IBM437"))) {
            List<String> out = new ArrayList<>();
            try (ZipFile z = new ZipFile(in.toFile(), cs)) {
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) { ZipEntry x = e.nextElement(); if (!x.isDirectory()) out.add(x.getName()); }
                return out;
            } catch (ZipException | IllegalArgumentException notThisWay) {
                if (cs != StandardCharsets.UTF_8) throw new IOException(in.getFileName() + " is not a zip this program can open: " + notThisWay.getMessage());
            }
        }
        return List.of();
    }

    /**
     * Whether a file is a comic by itself: a zip (whatever its name says: .cbz, .zip), a RAR or a folder that holds page pictures, and for
     * a zip or a folder nothing that makes it another kind of document (a Word file and an EPUB are zips too, and never get here:
     * {@link DocUnits#format} knows them first). At least one page, and at least half of what it holds is pages. A PDF is never one by
     * itself. A RAR this machine cannot list is taken for one by its kind alone, so that the plan can say what to install.
     */
    static boolean byItself(Path in) {
        String kind = container(in);
        if (kind == null || kind.equals(PDF)) return false;
        if (kind.equals(CBR)) return true;
        try {
            if (kind.equals(CBZ)) {
                List<String> all = zipNames(in);
                if (all.contains("word/document.xml") || all.contains("META-INF/container.xml") || all.stream().anyMatch(n -> n.endsWith(".opf"))) return false;
                long pages = all.stream().filter(ComicPages::page).count(), other = all.stream().filter(n -> !page(n) && !BESIDE.matcher(n.replace('\\', '/')).matches()).count();
                return pages > 0 && pages >= other;
            }
            int pages = 0, other = 0;
            try (Stream<Path> all = Files.walk(in, 4)) {
                for (Path p : (Iterable<Path>) all.limit(4L * MAX_PAGES)::iterator) {
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                    String rel = in.relativize(p).toString().replace('\\', '/');
                    if (page(rel)) pages++; else if (!BESIDE.matcher(rel).matches()) other++;
                }
            }
            return pages > 0 && pages >= other;
        } catch (IOException | RuntimeException e) { return false; }
    }

    // ---- the pages out ----

    /**
     * The volume opened into {@code work}, a folder of this job's own: a zip's and a RAR's entries unpacked under {@code work/in}, a PDF's
     * pages drawn into it as PNG, a folder's pictures left where they are. The pages are the pictures, in the natural order of their names.
     */
    static Volume open(Path in, Path work) throws IOException {
        String kind = container(in);
        if (kind == null) throw new IOException(in.getFileName() + " is not a comic this program opens: a CBZ, a CBR, a ZIP or a folder of page pictures, or a PDF.");
        List<String> leftOut = new ArrayList<>();
        Path root;
        if (kind.equals(FOLDER)) root = in;
        else {
            root = work.resolve("in");
            Checkpoint.deleteTree(root);
            Files.createDirectories(root);
            if (kind.equals(PDF)) return pdf(in, root);
            if (kind.equals(CBZ)) {
                Archives.Unpacked u = unzip(in, root);
                if (!u.stopped().isEmpty()) throw new IOException(u.stopped().replace("the library takes", "a comic's translation takes"));
                leftOut.addAll(u.leftOut());
            } else unrar(in, root);
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> all = Files.walk(root, 6)) {
            for (Path p : (Iterable<Path>) all.limit(4L * MAX_PAGES)::iterator) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (page(rel)) names.add(rel);
                else if (!BESIDE.matcher(rel).matches()) leftOut.add("\"" + rel + "\": not a page picture (jpg, png, webp or bmp), left out");
            }
        }
        names.sort(NATURAL);
        if (names.size() > MAX_PAGES) throw new IOException(in.getFileName() + " holds " + names.size() + " pages; a comic's translation takes at most " + MAX_PAGES + " in one volume.");
        List<Page> pages = new ArrayList<>();
        for (String n : names) pages.add(new Page(pages.size() + 1, n, root.resolve(n)));
        return new Volume(kind, pages, leftOut);
    }

    /** A zip's entries under {@code root}, by {@link Archives.Sink}'s rules; its names read as UTF-8, and as the old DOS code page when they are not. */
    static Archives.Unpacked unzip(Path in, Path root) throws IOException {
        try { return Archives.zip(in, new Archives.Sink(root, 4 * MAX_PAGES, MAX_BYTES), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException | IOException notUtf8) {
            Checkpoint.deleteTree(root);
            Files.createDirectories(root);
            try { return Archives.zip(in, new Archives.Sink(root, 4 * MAX_PAGES, MAX_BYTES), Charset.forName("IBM437")); }
            catch (IllegalArgumentException e) { throw new IOException(in.getFileName() + " is not a zip this program can open: " + e.getMessage()); }
        }
    }

    // ---- a RAR, through a program the machine has ----

    /** The programs that open a RAR, in the order tried: each with the arguments that list an archive's names and those that unpack it into a folder. */
    record RarTool(String name, List<String> list, List<String> unpack) { }

    static final List<RarTool> RAR_TOOLS = List.of(
            new RarTool("bsdtar", List.of("bsdtar", "-tf", "{in}"), List.of("bsdtar", "-xf", "{in}", "-C", "{dir}")),
            new RarTool("7z", List.of("7z", "l", "-ba", "-slt", "{in}"), List.of("7z", "x", "-y", "-o{dir}", "{in}")),
            new RarTool("unrar", List.of("unrar", "lb", "{in}"), List.of("unrar", "x", "-y", "-o+", "{in}", "{dir}/")));

    /** What is said when no such program is on the machine. */
    static final String NO_RAR_TOOL = "A CBR is a RAR archive, and this machine has no program that opens one. Install one of bsdtar (the package libarchive-tools), 7z (p7zip) or unrar, and ask again; or make the volume a CBZ, which needs none.";

    /** The first of the programs that this machine has: it starts and says its name. A test stands the runner in. */
    static RarTool rarTool() {
        for (RarTool t : RAR_TOOLS) {
            try { Searx.Result r = Searx.runner.run(List.of(t.name(), t.name().equals("7z") ? "i" : "--version")); if (r.code() == 0 || (t.name().equals("unrar") && r.out().toLowerCase(Locale.ROOT).contains("unrar"))) return t; }
            catch (Exception notHere) { /* the next one */ }
        }
        return null;
    }

    private static List<String> filled(List<String> args, Path in, Path dir) {
        List<String> out = new ArrayList<>();
        for (String a : args) out.add(a.replace("{in}", in.toAbsolutePath().toString()).replace("{dir}", dir == null ? "" : dir.toAbsolutePath().toString()));
        return out;
    }

    static List<String> rarNames(Path in) throws IOException {
        RarTool t = rarTool();
        if (t == null) throw new IOException(NO_RAR_TOOL);
        Searx.Result r;
        try { r = Searx.runner.run(filled(t.list(), in, null)); } catch (Exception e) { throw new IOException(t.name() + " could not list " + in.getFileName() + ": " + e.getMessage()); }
        if (r.code() != 0) throw new IOException(t.name() + " could not list " + in.getFileName() + ": " + TranslationScores.last(r.out()));
        List<String> out = new ArrayList<>();
        for (String line : r.out().split("\\R")) {
            String n = t.name().equals("7z") ? (line.startsWith("Path = ") ? line.substring(7) : "") : line;
            if (!n.isBlank()) out.add(n.strip().replace('\\', '/'));
        }
        return out;
    }

    /** A RAR unpacked under {@code root} by the machine's own program; what it wrote is then read as a folder is, links never followed. */
    static void unrar(Path in, Path root) throws IOException {
        RarTool t = rarTool();
        if (t == null) throw new IOException(NO_RAR_TOOL);
        Searx.Result r;
        try { r = Searx.runner.run(filled(t.unpack(), in, root)); } catch (Exception e) { throw new IOException(t.name() + " could not unpack " + in.getFileName() + ": " + e.getMessage()); }
        if (r.code() != 0) throw new IOException(t.name() + " could not unpack " + in.getFileName() + ": " + TranslationScores.last(r.out()));
    }

    // ---- a PDF whose pages are pictures ----

    /** The most dots an inch a comic's page is drawn at: a page 240 points long is still drawn to its 2,000 pixels. */
    static final float DRAWN_MOST = 600;
    /** The longest side, in pixels, a page of pictures alone is drawn at their own resolution to; a longer one is drawn as any other page. */
    static final double EXACT_MOST = 12_000;

    /**
     * A PDF's page as a picture file in {@code dir}, named {@code stem} and its kind's ending; null when it cannot be drawn on this
     * machine. A page that is one picture ({@link PdfGlyphs#onlyPicture}) is that picture at its own pixels, with nothing resampled:
     * a JPEG as the file it is in the PDF (when it is plain: three colours or grey, no mask), any other picture written as a PNG.
     * A page that holds pictures alone, all at one resolution ({@link PdfGlyphs#picturesAloneDpi}: a scan with the sheet's margin
     * round it, a page in two halves, a cover in strips), is drawn at that resolution, a pixel of the picture a pixel of the page.
     * Any other page is drawn with its long side about 2,000 pixels. Read live (2026-11-14): at a fixed number of dots an inch a
     * scanned page lost the pixels its scan held, and a cover made of 1,627 strips of picture came out with a light line at every
     * seam, which a page drawn larger than its strips are does not show.
     */
    static Path pagePicture(PDDocument doc, byte[] bytes, int p, Path dir, String stem) throws IOException {
        PDPage page = doc.getPage(p - 1);
        try {
            PDImageXObject own = PdfGlyphs.onlyPicture(page);
            if (own != null) {
                List<COSName> filters = own.getStream().getFilters();
                int colours = own.getColorSpace().getNumberOfComponents();
                String space = own.getColorSpace().getName();
                boolean plain = (colours == 3 || colours == 1) && !space.equals(COSName.INDEXED.getName()) && !space.equals(COSName.SEPARATION.getName()) && !space.equals(COSName.DEVICEN.getName());
                if (plain && filters.size() == 1 && COSName.DCT_DECODE.equals(filters.get(0)) && own.getSoftMask() == null && own.getMask() == null && own.getDecode() == null) {
                    Path f = dir.resolve(stem + ".jpg");
                    try (InputStream raw = own.getStream().getCOSObject().createRawInputStream()) { Files.copy(raw, f, StandardCopyOption.REPLACE_EXISTING); }
                    if (ImageIO.read(f.toFile()) != null) return f;
                    Files.deleteIfExists(f);   // a JPEG this machine's reader does not open: the picture as the PDF's own reader decodes it
                }
                BufferedImage image = own.getImage();
                if (image != null) { Path f = dir.resolve(stem + ".png"); ImageIO.write(image, "png", f.toFile()); return f; }
            }
            // pictures alone at one resolution (a scan with the sheet's margin round it, a cover in strips): drawn at that resolution, a pixel a pixel
            float own1 = PdfGlyphs.undecodable(page).isEmpty() ? PdfGlyphs.picturesAloneDpi(page) : 0;
            double longest = own1 * Math.max(page.getCropBox().getWidth(), page.getCropBox().getHeight()) / 72.0;
            if (own1 > 0 && longest >= 400 && longest <= EXACT_MOST) {
                BufferedImage exact = PdfGlyphs.renderExact(doc, p, own1 * 1.0001f);   // a hair over: a side of 1,754.0 pixels is not cut to 1,753 where the sum comes to 1,753.9999
                if (exact != null) { Path f = dir.resolve(stem + ".png"); ImageIO.write(exact, "png", f.toFile()); return f; }
            }
        } catch (IOException | RuntimeException e) { /* the page is drawn instead */ }
        byte[] png = PdfReading.drawn(doc, bytes, p, !PdfGlyphs.undecodable(page).isEmpty(), DRAWN_MOST);
        if (png == null) return null;
        Path f = dir.resolve(stem + ".png");
        Files.write(f, png);
        return f;
    }

    /** The size each page of a PDF is shown at, in points (width, height), by the page's number from 1; empty when the file cannot be opened. */
    static Map<String, float[]> pagePoints(Path in) {
        Map<String, float[]> out = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDPage page = doc.getPage(p - 1);
                PDRectangle box = page.getCropBox();
                boolean turned = page.getRotation() % 180 != 0;
                out.put(String.valueOf(p), new float[]{turned ? box.getHeight() : box.getWidth(), turned ? box.getWidth() : box.getHeight()});
            }
        } catch (IOException | RuntimeException e) { out.clear(); }
        return out;
    }

    /** The pages of a PDF, each a picture ({@link #pagePicture}: the page's own picture, or the page drawn about 2,000 pixels long), written as p0001, p0002… */
    static Volume pdf(Path in, Path root) throws IOException {
        byte[] bytes = Files.readAllBytes(in);
        List<Page> pages = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int n = doc.getNumberOfPages();
            if (n > MAX_PAGES) throw new IOException(in.getFileName() + " holds " + n + " pages; a comic's translation takes at most " + MAX_PAGES + " in one volume.");
            for (int p = 1; p <= n; p++) {
                Path f = pagePicture(doc, bytes, p, root, String.format(Locale.ROOT, "p%04d", p));
                if (f == null) { leftOut.add("page " + p + ": it could not be drawn on this machine, left out"); continue; }
                pages.add(new Page(pages.size() + 1, String.valueOf(p), f));
            }
        }
        return new Volume(PDF, pages, leftOut);
    }

    /** The most one sampled picture may weigh: a page is a few megabytes, a web comic's strip some tens. */
    static final long MAX_SAMPLE_BYTES = 256L << 20;

    /**
     * Up to {@code n} of a comic's pictures, spread through it past its first, for a look before any work (which language is the
     * lettering in): a folder's own files; a zip's entries, each written to {@code dir} under a name of this program's own, never the
     * entry's; a PDF's pages drawn into {@code dir}. Empty for a RAR, which is unpacked whole or not at all, and for a file that holds
     * no page: the run looks then, on the pages it has unpacked.
     */
    static List<Path> sample(Path in, Path dir, int n) throws IOException {
        String kind = container(in);
        List<Path> out = new ArrayList<>();
        if (kind == null || kind.equals(CBR)) return out;
        if (kind.equals(PDF)) {
            byte[] bytes = Files.readAllBytes(in);
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                for (int p : spread(doc.getNumberOfPages(), n)) {
                    Path f = pagePicture(doc, bytes, p + 1, dir, "s" + (out.size() + 1));
                    if (f != null) out.add(f);
                }
            }
            return out;
        }
        List<String> names = names(in);
        List<String> chosen = new ArrayList<>();
        for (int i : spread(names.size(), n)) chosen.add(names.get(i));
        if (kind.equals(FOLDER)) { for (String name : chosen) out.add(in.resolve(name)); return out; }
        for (Charset cs : List.of(StandardCharsets.UTF_8, Charset.forName("IBM437"))) {
            try (ZipFile z = new ZipFile(in.toFile(), cs)) {
                for (String name : chosen) {
                    ZipEntry e = z.getEntry(name);
                    if (e == null || e.isDirectory() || e.getSize() > MAX_SAMPLE_BYTES) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    Path f = dir.resolve(String.format(Locale.ROOT, "s%d%s", out.size() + 1, lower.substring(lower.lastIndexOf('.'))));
                    try (InputStream from = z.getInputStream(e)) { byte[] bytes = from.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_SAMPLE_BYTES + 1)); if (bytes.length > MAX_SAMPLE_BYTES) continue; Files.write(f, bytes); }
                    out.add(f);
                }
                return out;
            } catch (ZipException | IllegalArgumentException notThisWay) { out.clear(); }
        }
        return out;
    }

    /** {@code n} places among {@code count}, from 0, spread through them past the first: for three of thirty, the 8th, the 15th and the 23rd; all of them when there are no more than {@code n}. */
    static List<Integer> spread(int count, int n) {
        List<Integer> out = new ArrayList<>();
        for (int k = 1; k <= n && count > 0; k++) { int i = count <= n ? k - 1 : Math.min(count - 1, (int) Math.round(k * count / (n + 1.0))); if (i < count && !out.contains(i)) out.add(i); }
        return out;
    }

    /** How many pages a PDF has, or 0 when it cannot be opened. */
    static int pdfPages(Path in) {
        try (PDDocument doc = Loader.loadPDF(in.toFile())) { return doc.getNumberOfPages(); } catch (IOException | RuntimeException e) { return 0; }
    }

    /** How many pages a file holds, for a plan: by its names, or the PDF's own count. */
    static int count(Path in) throws IOException { return PDF.equals(container(in)) ? pdfPages(in) : names(in).size(); }

    // ---- the pages back ----

    /**
     * A CBZ of the pictures given, each under the name given (a page's own name in the volume it came from, so a reader shows them in
     * the same order). A picture is compressed already: the entries are stored as they are.
     */
    static void writeCbz(List<Path> pictures, List<String> names, Path out) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            z.setLevel(0);
            for (int i = 0; i < pictures.size(); i++) {
                z.putNextEntry(new ZipEntry(names.get(i).replace('\\', '/')));
                try (InputStream p = new BufferedInputStream(Files.newInputStream(pictures.get(i)))) { p.transferTo(z); }
                z.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * A PDF of the pictures given, a page each: what a PDF that came in as pages of pictures goes out as. A page is as large as
     * {@code points} says (width and height in points: the size the page had in the PDF that came in), and as large as its picture
     * at 150 dots an inch where that names none (a comic that came in as pictures and goes out as a PDF). A JPEG is put in as it is. Any other picture goes in as a JPEG of high quality: a page drawn from a PDF is a
     * picture taken of it, and kept without loss six such pages weighed 14 MB where the PDF they came from weighed 4 (read on a real
     * run). Only a picture with transparency is kept in the PDF's own lossless kind.
     */
    static void writePdf(List<Path> pictures, Path out) throws IOException { writePdf(pictures, out, null); }

    static void writePdf(List<Path> pictures, Path out, List<float[]> points) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".part");
        try (PDDocument doc = new PDDocument()) {
            for (int k = 0; k < pictures.size(); k++) {
                Path picture = pictures.get(k);
                float[] size = points != null && k < points.size() ? points.get(k) : null;
                String name = picture.getFileName().toString().toLowerCase(Locale.ROOT);
                PDImageXObject image;
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")) { try (InputStream in = new BufferedInputStream(Files.newInputStream(picture))) { image = JPEGFactory.createFromStream(doc, in); } }
                else {
                    BufferedImage read = ImageIO.read(picture.toFile());
                    if (read == null) throw new IOException("The page " + picture.getFileName() + " is in a format this program cannot put into a PDF; ask for the translation as cbz (--as cbz).");
                    image = read.getColorModel().hasAlpha() ? LosslessFactory.createFromImage(doc, read) : JPEGFactory.createFromImage(doc, read, 0.9f);
                }
                float w = size != null ? size[0] : image.getWidth() * 72f / 150f, h = size != null ? size[1] : image.getHeight() * 72f / 150f;
                PDPage page = new PDPage(new PDRectangle(w, h));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) { cs.drawImage(image, 0, 0, w, h); }
            }
            doc.save(tmp.toFile());
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * A file hashed as it is read, never held whole: a page's key in a job's kept work (the page read once is not read again), and a
     * volume's, which may be hundreds of megabytes. A folder: its pages' names and sizes.
     */
    static String sha(Path file) throws IOException {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            if (Files.isDirectory(file)) { for (String n : names(file)) d.update((n + " " + Files.size(file.resolve(n)) + "\n").getBytes(StandardCharsets.UTF_8)); }
            else try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) { byte[] buf = new byte[1 << 16]; int r; while ((r = in.read(buf)) > 0) d.update(buf, 0, r); }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
