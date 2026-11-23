package org.researchzosho.librarian;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What the comic tests stand on, so that none of them needs the comic helper, its models, a network or a real comic: pages drawn here,
 * archives made of them, and a stand-in for the helper's process that speaks its JSON lines ({@code comic/serve.py}'s: the ready line
 * with the languages it reads, script, read with its language, cut, join, set, picture, quit, an error answered with the process
 * alive) from a script the test writes. The stand-in is a class of the tests ({@link ComicStandInProcess}) run by the test's own
 * Java.
 */
final class ComicStandIn {
    private ComicStandIn() { }

    static final ObjectMapper M = new ObjectMapper();

    // ---- a manifest of the test's own ----

    private static String entry(String name, String kind, String title, String said, String more, String path, long size) {
        String rev = "0123456789abcdef0123456789abcdef01234567";
        return "{\"name\": \"" + name + "\", \"kind\": \"" + kind + "\", \"title\": \"" + title + "\", \"said\": \"" + said + "\", " + more + "\"revision\": \"" + rev + "\", \"licence\": \"stand-in\", \"licence_said\": \"" + (kind.equals("font") ? "SIL Open Font License 1.1" : "Apache 2.0") + "\", "
                + "\"files\": [{\"path\": \"" + path + "\", \"url\": \"https://example.invalid/" + rev + "/" + path + "\", \"size\": " + size + ", \"sha256\": \"" + "ab".repeat(32) + "\"}]}";
    }

    /**
     * A manifest as the helper's grows to for every language: the detector, the reader of Japanese, a reader for Chinese, one for
     * Korean and one for the alphabets, patterns for two languages, a word list (a kind of its own), the two alphabet fonts and a
     * font for each of Japanese, simplified Chinese, traditional Chinese and Korean. Its names, sizes and files are the test's own, so that what the program says of a
     * manifest (the question's sizes, the list of pieces, the order of the fonts) is held to a manifest that does not change when the
     * shipped one does.
     */
    static final String GROWN = "{\"models\": [" + String.join(",\n", List.of(
            entry("detector", "model", "stand-in-detector", "the model that finds the text and the speech balloons on a page", "", "detector/detector.onnx", 40_000_000L),
            entry("reader", "model", "stand-in-reader-ja", "the model that reads Japanese lettering", "", "reader-ja/model.bin", 400_000_000L),
            entry("reader-zh", "model", "stand-in-reader-zh", "the model that reads Chinese lettering", "", "reader-zh/model.onnx", 80_000_000L),
            entry("reader-ko", "model", "stand-in-reader-ko", "the model that reads Korean lettering", "", "reader-ko/model.onnx", 12_000_000L),
            entry("reader-alphabets", "model", "stand-in-reader-alphabets", "the model that reads lettering in the Latin, Cyrillic and Greek alphabets", "", "reader-alphabets/model.onnx", 8_000_000L),
            entry("hyphenation-en-us", "hyphenation", "", "the places where a word may be broken at a line's end", "\"language\": \"en\", ", "hyphenation/hyph-en-us.pat.txt", 30_000L),
            entry("hyphenation-ru", "hyphenation", "", "the places where a word may be broken at a line's end", "\"language\": \"ru\", ", "hyphenation/hyph-ru.pat.txt", 60_000L),
            entry("words-chinese", "words", "stand-in-words-zh", "the words of Chinese, so that no line ends inside one", "\"language\": \"zh\", ", "words/chinese.txt", 8_000_000L),
            entry("font-latin", "font", "Stand-in Latin Bold", "the lettering font for the Latin alphabet", "\"script\": \"latin\", ", "fonts/Latin-Bold.ttf", 50_000L),
            entry("font-cyrillic", "font", "Stand-in Cyrillic Bold", "the lettering font for the Cyrillic and Greek alphabets", "\"script\": \"cyrillic greek\", ", "fonts/Cyrillic-Bold.ttf", 90_000L),
            entry("font-japanese", "font", "Stand-in Sans JP Bold", "the lettering font for Japanese", "\"script\": \"japanese\", ", "fonts/SansJP-Bold.otf", 16_000_000L),
            entry("font-chinese-simplified", "font", "Stand-in Sans SC Bold", "the lettering font for Chinese in simplified characters", "\"script\": \"chinese-simplified\", ", "fonts/SansSC-Bold.otf", 16_000_000L),
            entry("font-chinese-traditional", "font", "Stand-in Sans TC Bold", "the lettering font for Chinese in traditional characters", "\"script\": \"chinese-traditional\", ", "fonts/SansTC-Bold.otf", 16_000_000L),
            entry("font-korean", "font", "Stand-in Sans KR Bold", "the lettering font for Korean", "\"script\": \"korean\", ", "fonts/SansKR-Bold.otf", 16_000_000L))) + "]}";

    /** The manifest {@link #GROWN} without what a release added for the other languages: the detector, the Japanese reader, the patterns and the two alphabet fonts, as a helper set up before it holds. */
    static String before() throws IOException {
        ObjectNode all = (ObjectNode) M.readTree(GROWN);
        ArrayNode kept = M.createArrayNode();
        for (var e : all.path("models")) { String n = e.path("name").asText(); if (!n.startsWith("reader-") && !n.startsWith("words-") && !n.startsWith("font-japanese") && !n.startsWith("font-chinese") && !n.startsWith("font-korean")) kept.add(e); }
        return M.createObjectNode().set("models", kept).toString();
    }

    /** The program reads {@link #GROWN} as its manifest from now on; {@code ComicHelper.manifestFrom(null)} puts the build's back. */
    static void grown() { ComicHelper.manifestFrom(() -> GROWN.getBytes(StandardCharsets.UTF_8)); }

    // ---- pages drawn by the test ----

    /** A page of three panels on grey with white oval balloons, and its number drawn large so that no two pages are the same picture. No lettering of any comic: the stand-in's script says what a page "reads". */
    static BufferedImage page(int number, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
        g.setStroke(new BasicStroke(4f));
        int[][] panels = {{40, 40, width - 80, height * 2 / 5}, {40, height * 2 / 5 + 80, width / 2 - 60, height * 3 / 5 - 120}, {width / 2 + 20, height * 2 / 5 + 80, width / 2 - 60, height * 3 / 5 - 120}};
        for (int[] p : panels) { g.setColor(new Color(172, 172, 172)); g.fillRect(p[0], p[1], p[2], p[3]); g.setColor(Color.BLACK); g.drawRect(p[0], p[1], p[2], p[3]); }
        for (int[] b : balloons(width, height)) { g.setColor(Color.WHITE); g.fillOval(b[0], b[1], b[2] - b[0], b[3] - b[1]); g.setColor(Color.BLACK); g.drawOval(b[0], b[1], b[2] - b[0], b[3] - b[1]); }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, height / 12)); g.setColor(new Color(90, 90, 90));
        g.drawString(String.valueOf(number), width / 2 + 60, height - 140);
        g.dispose();
        return img;
    }

    /** The boxes of the three balloons {@link #page} draws: [x1, y1, x2, y2] each, the first on the right as a manga page reads. */
    static int[][] balloons(int width, int height) {
        return new int[][]{{width * 6 / 10, 90, width * 6 / 10 + width / 4, 90 + height / 4}, {width / 6, 110, width / 6 + width / 4, 110 + height / 4}, {width / 8, height / 2 + 60, width / 8 + width / 4, height / 2 + 60 + height / 4}};
    }

    /** A web comic's strip: one tall picture of {@code panels} panels on a coloured ground, each with one white balloon and its number, so that no two pieces of it are the same picture. */
    static BufferedImage strip(int width, int panels) {
        int panel = width * 3 / 2;
        BufferedImage img = new BufferedImage(width, panel * panels, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(236, 230, 214)); g.fillRect(0, 0, width, panel * panels);
        g.setStroke(new BasicStroke(3f));
        for (int k = 0; k < panels; k++) {
            int top = k * panel;
            g.setColor(new Color(150 + 12 * (k % 5), 170, 190)); g.fillRect(20, top + 20, width - 40, panel - 40);
            g.setColor(Color.WHITE); g.fillOval(width / 5, top + 50, width * 3 / 5, panel / 3); g.setColor(Color.BLACK); g.drawOval(width / 5, top + 50, width * 3 / 5, panel / 3);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, width / 6)); g.setColor(new Color(70, 70, 70));
            g.drawString(String.valueOf(k + 1), width / 2 - width / 20, top + panel - 60);
        }
        g.dispose();
        return img;
    }

    /** The box of the balloon in a strip's panel (from 0), in the panel's own piece: [x1, y1, x2, y2]. */
    static int[] stripBalloon(int width) { int panel = width * 3 / 2; return new int[]{width / 5, 50, width * 4 / 5, 50 + panel / 3}; }

    static byte[] png(BufferedImage img) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray(); }
    static byte[] jpg(BufferedImage img) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray(); }

    /** An archive of the entries given, name then bytes, in the order given. */
    static Path zip(Path file, Object... namesAndBytes) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndBytes.length; i += 2) { z.putNextEntry(new ZipEntry((String) namesAndBytes[i])); z.write((byte[]) namesAndBytes[i + 1]); z.closeEntry(); }
        }
        return file;
    }

    /** A volume of {@code pages} drawn pages as a CBZ, named 1.png, 2.png … 10.png (so only the natural order of names is the page order); the pages' bytes come back in order. */
    static List<byte[]> volume(Path cbz, int pages) throws IOException {
        List<byte[]> out = new ArrayList<>(); List<Object> entries = new ArrayList<>();
        for (int p = pages; p >= 1; p--) out.add(0, png(page(p, 500, 700)));   // written to the archive last page first: the archive's own order is not the page order
        for (int p = pages; p >= 1; p--) { entries.add(p + ".png"); entries.add(out.get(p - 1)); }
        zip(cbz, entries.toArray());
        return out;
    }

    // ---- what the stand-in "reads" on a page ----

    /** A block as {@code comic/read.py} gives one: its id, kind (speech, outside, sound), text, box, and for speech a balloon's outline. */
    static ObjectNode block(String id, String kind, String text, int[] box) {
        ObjectNode b = M.createObjectNode();
        b.put("id", id); ArrayNode bx = b.putArray("box"); for (int v : box) bx.add(v);
        b.put("score", 0.95); b.put("kind", kind);
        if (kind.equals("speech")) {
            ObjectNode bal = b.putObject("balloon"); bal.put("found", "balloon"); bal.put("closed", true); ArrayNode o = bal.putArray("outline"); for (int[] pt : new int[][]{{box[0], box[1]}, {box[2], box[1]}, {box[2], box[3]}, {box[0], box[3]}}) o.addArray().add(pt[0]).add(pt[1]);
            bal.putArray("paper").add(255).add(255).add(255); bal.putArray("ink").add(0).add(0).add(0);   // the balloon's own colours, as the helper gives them
        }
        else b.putNull("balloon");
        b.put("vertical", false);
        b.put("text", text); b.putArray("readings"); b.putArray("numbers");
        if (kind.equals("sound")) b.putObject("sound").put("why", "lettering joins the outline"); else b.putNull("sound");
        b.put("glyph_px", 30.0); b.put("cell_px", 40.0); b.putArray("look");
        return b;
    }

    /** The block as one lettered in columns. */
    static ObjectNode columns(ObjectNode block) { block.put("vertical", true); return block; }

    /** A reading printed beside a word of a block (furigana): the word, its place in the block's text, the reading. */
    static ObjectNode reading(ObjectNode block, String base, String reading) {
        int at = block.path("text").asText().indexOf(base);
        ObjectNode r = ((ArrayNode) block.get("readings")).addObject();
        r.put("base", base); r.put("start", at); r.put("end", at + base.length()); r.put("reading", reading); r.putNull("note");
        return block;
    }

    /** A page as {@code comic/read.py} gives one, from its blocks in reading order. */
    static ObjectNode read(int width, int height, ObjectNode... blocks) {
        ObjectNode p = M.createObjectNode();
        p.put("image", "page"); p.put("width", width); p.put("height", height); p.put("dialogue_glyph_px", 30.0);
        ArrayNode order = p.putArray("order"), bs = p.putArray("blocks");
        for (ObjectNode b : blocks) { order.add(b.path("id").asText()); bs.add(b); }
        return p;
    }

    /** Tells the stand-in what it reads on the picture with these bytes: kept in {@code script} under the picture's hash, as the stand-in looks it up. */
    static void says(Path script, byte[] picture, ObjectNode read) throws IOException {
        Files.createDirectories(script);
        Files.writeString(script.resolve(PdfReading.sha(picture) + ".json"), read.toString(), StandardCharsets.UTF_8);
    }

    /** The same for a picture the test cannot hash beforehand, a piece the stand-in cuts from a strip: by the file's name (ep01_002.png). */
    static void saysNamed(Path script, String fileName, ObjectNode read) throws IOException {
        Files.createDirectories(script);
        Files.writeString(script.resolve("name-" + fileName + ".json"), read.toString(), StandardCharsets.UTF_8);
    }

    // ---- the stand-in process ----


    /** What is lettered on a block's picture as the stand-in cut it: the text its pixels carry, read from the picture as a call's data address holds it. What a model that sees would read there. */
    static String lettered(String dataUrl) {
        try {
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1))));
            byte[] all = new byte[crop.getWidth() * 3];
            for (int i = 0; i < crop.getWidth(); i++) { int rgb = crop.getRGB(i, 0); all[3 * i] = (byte) (rgb >>> 16); all[3 * i + 1] = (byte) (rgb >>> 8); all[3 * i + 2] = (byte) rgb; }
            int n = ((all[0] & 0xff) << 24) | ((all[1] & 0xff) << 16) | ((all[2] & 0xff) << 8) | (all[3] & 0xff);
            return new String(all, 4, n, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * The command that starts the stand-in with {@code script} as its script folder: the test's own Java, a class path of the stand-in's
     * compiled class and the JSON library, the stand-in's class. A short-lived process: the compiler's first tier and one collector
     * thread are enough for it, and leave the processors to the suite.
     */
    static List<String> command(Path dir, Path script) throws IOException {
        Files.createDirectories(script);
        return List.of(ProcessHandle.current().info().command().orElse("java"), "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-cp", classPath(), ComicStandInProcess.class.getName(), script.toString());
    }

    /** The stand-in's class path: the folder of its compiled class, then the JSON library's three jars. */
    static String classPath() {
        try { return Path.of(ComicStandInProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator + jsonClassPath(); }
        catch (URISyntaxException e) { throw new IllegalStateException(e); }
    }

    /** Where the JSON library's three jars are: found from the classes themselves, whatever started this test. */
    static String jsonClassPath() {
        Set<String> jars = new LinkedHashSet<>();
        try { for (Class<?> c : List.of(ObjectMapper.class, JsonFactory.class, JsonCreator.class)) jars.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()); }
        catch (URISyntaxException e) { throw new IllegalStateException(e); }
        return String.join(File.pathSeparator, jars);
    }

    /** The helper's processes of this test that are alive now. */
    static List<ProcessHandle> running() {
        return ProcessHandle.current().descendants().filter(p -> p.isAlive() && p.info().commandLine().orElse("").contains("ComicStandInProcess")).toList();
    }

    private static final Pattern OP = Pattern.compile("\"op\":\\s*\"(\\w+)\"");

    /** The requests of one op the stand-in was sent, in order, each as its line (the first 400 characters of it): what language a read named, which pictures a look took. */
    static List<String> requests(Path script, String op) throws IOException {
        List<String> out = new ArrayList<>();
        Path log = script.resolve("requests.log");
        if (!Files.exists(log)) return out;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) { Matcher m = OP.matcher(line); if (m.find() && m.group(1).equals(op)) out.add(line); }
        return out;
    }

    /** The requests the stand-in was sent, in order, as their op names: script, read, cut, join, set, picture, quit. */
    static List<String> asked(Path script) throws IOException {
        List<String> out = new ArrayList<>();
        Path log = script.resolve("requests.log");
        if (!Files.exists(log)) return out;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) { Matcher m = OP.matcher(line); if (m.find()) out.add(m.group(1)); }
        return out;
    }
}
