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
 * alive) from a script the test writes. The stand-in is one Java file run by the test's own Java, as the stand-ins of MetricX-24's
 * process are.
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

    /**
     * The stand-in's source. It answers as the helper does: a ready line that lists the languages it reads; {@code read} with the page
     * the script holds for the picture's hash, or for its file's name (a default page of one speech balloon in Japanese when it holds
     * none; an error for a file that is no picture, and for a language it has no reader for), the language asked for, the direction
     * read in (the order asked for; else rtl for Japanese and for a Chinese page whose balloons are mostly in columns, ltr otherwise) and whether most balloons
     * run in columns written into the page it answers; {@code script} with the
     * writing system of the letters those pages hold (kana: japanese; hangul: korean; han alone: chinese; else the alphabet with most
     * letters; none for no letters), whether most blocks run in columns, the share of the letters in it and the blocks' texts;
     * {@code cut} for a picture three times as tall as wide or taller, into pieces one and a half times as tall as wide, written as PNG
     * under {@code out}, each cut said to fall in a gutter (the second through a balloon when the script folder holds
     * {@code cut-through-balloon}), and {@code strip} false for any other picture; {@code join} by drawing the pieces at their tops into one
     * picture of the size given, in the format of {@code out}'s ending, the request kept as {@code <out>.join.json}; {@code set} by
     * copying the page to {@code out} and reporting each speech block set at 28 px, or at 9 px when its text is longer than the block's
     * {@code fits} (60 when the block names none), each other kind left as art; {@code vertical} said of a Chinese or a Japanese
     * block asked for in columns, and of a Chinese, Japanese or Korean block how many lines end inside a phrase (the block's own
     * {@code inside_phrase}, 0 when it names none) and, when the script folder holds {@code no-phrases}, that there is nothing to
     * tell a phrase by; the texts and the strings to keep whole that it was given kept beside the output as {@code <out>.set.json}, and a speech block
     * whose own {@code drawn_into_art} is true left as art with the helper's reason for it; {@code crops} with a PNG for each speech
     * block asked for (every one when none is named; none for a block whose own {@code no_crop} is true), a picture that carries what
     * is lettered there in its pixels, the block's own {@code lettered} or else its text ({@link #lettered} reads it back, as a model
     * that sees would read the balloon); {@code picture} by copying the page, the boxes kept as {@code <out>.boxes.json}; {@code quit}. Files in
     * the script folder steer it: {@code die-once} ends the process at the next read and is removed; {@code die-always} ends it at every
     * read; {@code hang} makes a read never answer; {@code no-crops} makes it a helper that has no {@code crops}; {@code page-px} holds the one size every page is set at in place of 28 (a page held down by its original lettering: a block whose text fits is set at it and says 28 as the size its own text would take); {@code languages} holds, with spaces between, the languages the ready line lists
     * in place of its own. Every request is appended to {@code requests.log} there.
     */
    static final String SOURCE = """
            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.fasterxml.jackson.databind.node.ObjectNode;
            import java.io.BufferedReader;
            import java.io.InputStreamReader;
            import java.io.PrintStream;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardCopyOption;
            import java.nio.file.StandardOpenOption;
            import java.security.MessageDigest;
            import java.util.HexFormat;
            import java.util.LinkedHashMap;
            import java.util.LinkedHashSet;
            import java.util.Map;
            import java.util.Set;
            import java.awt.Graphics2D;
            import java.awt.image.BufferedImage;
            import javax.imageio.ImageIO;

            public class ComicStandInProcess {
                static final ObjectMapper M = new ObjectMapper();

                /** What the script says the picture holds: by its hash, then by its file's name; null when it says nothing. */
                static ObjectNode said(Path script, Path p) throws Exception {
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
                    for (Path f : new Path[]{script.resolve(sha + ".json"), script.resolve("name-" + p.getFileName() + ".json")}) if (Files.exists(f)) return (ObjectNode) M.readTree(Files.readString(f, StandardCharsets.UTF_8));
                    return null;
                }

                public static void main(String[] a) throws Exception {
                    Path script = Path.of(a[0]);
                    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
                    String reads = Files.exists(script.resolve("languages")) ? Files.readString(script.resolve("languages"), StandardCharsets.UTF_8).strip() : "ja en zh ko de fr es pt it ru nl pl sv uk el";
                    ObjectNode ready = M.createObjectNode(); ready.put("ready", true); ready.put("version", "stand-in"); ready.putObject("models").put("detector", "stand-in").put("reader", "stand-in"); ready.put("load_s", 0.0);
                    var langs = ready.putArray("languages"); for (String l : reads.split("\\\\s+")) if (!l.isEmpty()) langs.add(l);
                    out.println(ready);
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.isBlank()) continue;
                        Files.writeString(script.resolve("requests.log"), line.length() > 400 ? line.substring(0, 400) + "\\n" : line + "\\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        JsonNode req; ObjectNode r = M.createObjectNode();
                        try { req = M.readTree(line); } catch (Exception e) { r.putNull("id"); r.put("ok", false); r.putObject("error").put("type", "ValueError").put("message", "not JSON"); out.println(r); continue; }
                        r.set("id", req.get("id"));
                        String op = req.path("op").asText("");
                        try {
                            if (op.equals("quit")) { r.put("ok", true); r.put("bye", true); out.println(r); return; }
                            if (op.equals("read")) {
                                if (Files.exists(script.resolve("hang"))) Thread.sleep(600_000);
                                if (Files.exists(script.resolve("die-always"))) System.exit(7);
                                if (Files.deleteIfExists(script.resolve("die-once"))) System.exit(7);
                                var pages = r.putArray("pages");
                                for (JsonNode im : req.path("images")) {
                                    Path p = Path.of(im.asText());
                                    var img = ImageIO.read(p.toFile());
                                    if (img == null) throw new IllegalArgumentException("cannot identify image file " + p.getFileName());
                                    ObjectNode page = said(script, p);
                                    if (page == null) {
                                        page = M.createObjectNode(); page.put("dialogue_glyph_px", 30.0); page.putArray("order").add("b01");
                                        ObjectNode b = page.putArray("blocks").addObject(); b.put("id", "b01"); b.put("kind", "speech"); b.put("text", "こんにちは"); b.put("score", 0.9);
                                        var box = b.putArray("box"); box.add(60).add(100).add(200).add(300);
                                        ObjectNode bal = b.putObject("balloon"); bal.put("found", "balloon"); bal.put("closed", true);
                                        var o = bal.putArray("outline"); o.addArray().add(60).add(100); o.addArray().add(200).add(100); o.addArray().add(200).add(300); o.addArray().add(60).add(300);
                                        b.putArray("readings"); b.putArray("numbers"); b.putNull("sound"); b.put("glyph_px", 30.0); b.put("cell_px", 40.0); b.putArray("look");
                                    }
                                    page.put("image", p.getFileName().toString()); page.put("width", img.getWidth()); page.put("height", img.getHeight());
                                    String language = req.path("language").asText("ja");
                                    if (!reads.contains(language)) throw new IllegalArgumentException("no reader for the language '" + language + "': the helper reads " + reads);
                                    int balloons = 0, columns = 0;
                                    for (JsonNode b : page.path("blocks")) if (b.path("kind").asText("").equals("speech")) { balloons++; if (b.path("vertical").asBoolean(false)) columns++; }
                                    boolean vertical = balloons > 0 && columns * 2 > balloons;
                                    page.put("language", language); page.put("vertical", vertical);
                                    String order = req.path("order").asText("");
                                    if (!order.isEmpty() && !order.equals("rtl") && !order.equals("ltr")) throw new IllegalArgumentException("order is rtl or ltr, not " + order);
                                    page.put("direction", !order.isEmpty() ? order : language.equals("ja") || (language.equals("zh") && vertical) ? "rtl" : "ltr");   // the order the page was read in: the one asked for, else the helper's own
                                    pages.add(page);
                                }
                                r.put("ok", true); out.println(r); continue;
                            }
                            if (op.equals("script")) {
                                Map<String, Integer> by = new LinkedHashMap<>();
                                int letters = 0, blocks = 0, vertical = 0;
                                var texts = r.putArray("texts");
                                for (JsonNode im : req.path("images")) {
                                    Path p = Path.of(im.asText());
                                    if (ImageIO.read(p.toFile()) == null) throw new IllegalArgumentException("cannot identify image file " + p.getFileName());
                                    ObjectNode page = said(script, p);
                                    if (page == null) { page = M.createObjectNode(); page.putArray("blocks").addObject().put("text", "こんにちは").put("vertical", true); }
                                    for (JsonNode b : page.path("blocks")) {
                                        String text = b.path("text").asText("");
                                        blocks++; if (b.path("vertical").asBoolean(false)) vertical++;
                                        if (texts.size() < 40) texts.add(text);
                                        for (int i = 0; i < text.length(); ) {
                                            int c = text.codePointAt(i); i += Character.charCount(c);
                                            if (!Character.isLetter(c)) continue;
                                            letters++;
                                            Character.UnicodeScript s = Character.UnicodeScript.of(c);
                                            String k = s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA ? "kana" : s == Character.UnicodeScript.HAN ? "han" : s == Character.UnicodeScript.HANGUL ? "korean"
                                                    : s == Character.UnicodeScript.LATIN ? "latin" : s == Character.UnicodeScript.CYRILLIC ? "cyrillic" : s == Character.UnicodeScript.GREEK ? "greek" : "other";
                                            by.merge(k, 1, Integer::sum);
                                        }
                                    }
                                }
                                String found = "none"; int most = 0;
                                if (by.getOrDefault("kana", 0) > 0) { found = "japanese"; most = by.get("kana") + by.getOrDefault("han", 0); }
                                else for (String k : new String[]{"korean", "han", "latin", "cyrillic", "greek"}) if (by.getOrDefault(k, 0) > most) { most = by.get(k); found = k.equals("han") ? "chinese" : k; }
                                r.put("ok", true); r.put("script", found); r.put("vertical", blocks > 0 && vertical * 2 > blocks); r.put("share", letters == 0 ? 0.0 : Math.round(100.0 * most / letters) / 100.0);
                                out.println(r); continue;
                            }
                            if (op.equals("cut")) {
                                Path image = Path.of(req.path("image").asText()), to = Path.of(req.path("out").asText());
                                BufferedImage img = ImageIO.read(image.toFile());
                                if (img == null) throw new IllegalArgumentException("cannot identify image file " + image.getFileName());
                                int w = img.getWidth(), h = img.getHeight();
                                r.put("ok", true); r.put("strip", h >= 3 * w); r.put("width", w); r.put("height", h);
                                var pieces = r.putArray("pieces");
                                if (h >= 3 * w) {
                                    Files.createDirectories(to);
                                    String stem = image.getFileName().toString().replaceFirst("\\\\.[^.]+$", "");
                                    int step = w * 3 / 2, n = 0;
                                    for (int top = 0; top < h; top += step) {
                                        int height = Math.min(step, h - top);
                                        Path f = to.resolve(String.format("%s_%03d.png", stem, ++n));
                                        ImageIO.write(img.getSubimage(0, top, w, height), "png", f.toFile());
                                        pieces.addObject().put("file", f.toString()).put("top", top).put("height", height);
                                    }
                                    var through = r.putArray("cut_through"); for (int k = 1; k < n; k++) through.add(k == 2 && Files.exists(script.resolve("cut-through-balloon")) ? "balloon" : "gutter");
                                }
                                out.println(r); continue;
                            }
                            if (op.equals("join")) {
                                Path to = Path.of(req.path("out").asText());
                                BufferedImage whole = new BufferedImage(req.path("width").asInt(), req.path("height").asInt(), BufferedImage.TYPE_INT_RGB);
                                Graphics2D g = whole.createGraphics();
                                for (JsonNode piece : req.path("pieces")) {
                                    BufferedImage one = ImageIO.read(Path.of(piece.path("file").asText()).toFile());
                                    if (one == null) throw new IllegalArgumentException("cannot identify image file " + Path.of(piece.path("file").asText()).getFileName());
                                    g.drawImage(one, 0, piece.path("top").asInt(), null);
                                }
                                g.dispose();
                                String name = to.getFileName().toString().toLowerCase();
                                if (!ImageIO.write(whole, name.endsWith(".png") ? "png" : name.endsWith(".bmp") ? "bmp" : "jpg", to.toFile())) throw new IllegalArgumentException("cannot write " + to.getFileName());
                                Files.writeString(Path.of(to + ".join.json"), req.toString(), StandardCharsets.UTF_8);
                                r.put("ok", true); r.put("out", to.toString()); out.println(r); continue;
                            }
                            if (op.equals("set")) {
                                Path image = Path.of(req.path("image").asText()), to = Path.of(req.path("out").asText());
                                Files.copy(image, to, StandardCopyOption.REPLACE_EXISTING);
                                ObjectNode kept = M.createObjectNode(); kept.set("texts", req.path("texts")); kept.put("font", req.path("font").asText("")); kept.set("more_fonts", req.path("more_fonts")); kept.set("keep", req.path("keep"));
                                Files.writeString(Path.of(to + ".set.json"), kept.toString(), StandardCharsets.UTF_8);
                                ObjectNode report = r.putObject("report"); report.put("page_px", Files.exists(script.resolve("page-px")) ? Integer.parseInt(Files.readString(script.resolve("page-px")).strip()) : 28); ObjectNode blocks = report.putObject("blocks");
                                for (JsonNode b : req.path("page").path("blocks")) {
                                    JsonNode t = req.path("texts").path(b.path("id").asText());
                                    if (t.isMissingNode()) continue;
                                    ObjectNode rec = blocks.putObject(b.path("id").asText()); rec.put("language", t.path("lang").asText("en"));
                                    String kind = b.path("kind").asText(""), text = t.path("text").asText("");
                                    if (kind.equals("sound")) { rec.put("set", false); rec.put("why_not", "a sound word: left as art"); continue; }
                                    if (!kind.equals("speech")) { rec.put("set", false); rec.put("why_not", "text outside a balloon"); continue; }
                                    if (b.path("drawn_into_art").asBoolean(false)) { rec.put("set", false); rec.put("why_not", "the lettering is drawn into the art: left as art"); continue; }   // as the page's own script says of the block
                                    int pageSize = Files.exists(script.resolve("page-px")) ? Integer.parseInt(Files.readString(script.resolve("page-px")).strip()) : 28;   // the page's one size, held down by the original lettering when the script folder says so
                                    int own = text.length() > b.path("fits").asInt(60) ? 9 : 28, px = Math.min(own, pageSize);   // the size the block's own text takes in its balloon, and the one it is set at
                                    rec.put("set", true); rec.put("px", px); rec.put("page_px", pageSize); rec.put("own_size", px != pageSize); rec.put("largest_px", own); rec.putArray("lines").add(text.toUpperCase()); rec.put("hyphenations", b.path("hyphenations").asInt(0));   // the words broken at a line's end, as the page's own script says of the block
                                    String lang = t.path("lang").asText("en").toLowerCase();
                                    boolean cjk = lang.startsWith("zh") || lang.startsWith("ja") || lang.startsWith("ko");
                                    if (t.path("vertical").asBoolean(false) && cjk && !lang.startsWith("ko")) rec.put("vertical", true);   // said only of a block set in columns; Korean is set in lines whatever is asked
                                    if (cjk) rec.put("inside_phrase", b.path("inside_phrase").asInt(0));
                                    if (b.has("keep_broken")) rec.set("keep_broken", b.path("keep_broken"));   // the kept strings a line of the block ends inside, as the page's own script says
                                    if (cjk && Files.exists(script.resolve("no-phrases"))) rec.put("phrases", "the budoux package is not installed: a line may end inside a phrase as readily as after it");
                                }
                                r.put("ok", true); r.put("out", to.toString()); out.println(r); continue;
                            }
                            if (op.equals("crops")) {
                                if (Files.exists(script.resolve("no-crops"))) throw new IllegalArgumentException("unknown op crops: script, read, cut, join, set, picture or quit");
                                Path image = Path.of(req.path("image").asText()), to = Path.of(req.path("out").asText());
                                if (ImageIO.read(image.toFile()) == null) throw new IllegalArgumentException("cannot identify image file " + image.getFileName());
                                Files.createDirectories(to);
                                Set<String> ids = new LinkedHashSet<>(); for (JsonNode id : req.path("ids")) ids.add(id.asText());
                                var crops = r.putArray("crops");
                                for (JsonNode b : req.path("page").path("blocks")) {
                                    String id = b.path("id").asText("");
                                    if (!b.path("kind").asText("").equals("speech") || (!ids.isEmpty() && !ids.contains(id)) || b.path("no_crop").asBoolean(false)) continue;
                                    // the picture of a block carries its lettering: what stands on the page (the block's own "lettered", else what was read), as bytes in its pixels
                                    byte[] text = b.path("lettered").asText(b.path("text").asText("")).getBytes(StandardCharsets.UTF_8);
                                    byte[] all = new byte[4 + text.length];
                                    all[0] = (byte) (text.length >>> 24); all[1] = (byte) (text.length >>> 16); all[2] = (byte) (text.length >>> 8); all[3] = (byte) text.length;
                                    System.arraycopy(text, 0, all, 4, text.length);
                                    int w = Math.max(1, (all.length + 2) / 3);
                                    BufferedImage crop = new BufferedImage(w, 1, BufferedImage.TYPE_INT_RGB);
                                    for (int i = 0; i < w; i++) { int rgb = 0; for (int k = 0; k < 3; k++) { int at = 3 * i + k; rgb = (rgb << 8) | (at < all.length ? all[at] & 0xff : 0); } crop.setRGB(i, 0, rgb); }
                                    Path f = to.resolve(id + ".png");
                                    ImageIO.write(crop, "png", f.toFile());
                                    crops.addObject().put("id", id).put("file", f.toString()).putArray("size").add(w).add(1);
                                }
                                r.put("ok", true); out.println(r); continue;
                            }
                            if (op.equals("picture")) {
                                Path image = Path.of(req.path("image").asText()), to = Path.of(req.path("out").asText());
                                Files.copy(image, to, StandardCopyOption.REPLACE_EXISTING);
                                Files.writeString(Path.of(to + ".boxes.json"), req.path("boxes").toString(), StandardCharsets.UTF_8);
                                r.put("ok", true); r.put("out", to.toString()); r.putArray("size").add(650).add(910); out.println(r); continue;
                            }
                            throw new IllegalArgumentException("unknown op " + op + ": script, read, cut, join, set, crops, picture or quit");
                        } catch (Exception e) {
                            r.put("ok", false); r.putObject("error").put("type", e.getClass().getSimpleName()).put("message", String.valueOf(e.getMessage())); out.println(r);
                        }
                    }
                }
            }
            """;

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

    /** The command that starts the stand-in with {@code script} as its script folder: the test's own Java, its own class path (for the JSON library), the one source file. */
    static List<String> command(Path dir, Path script) throws IOException {
        Files.createDirectories(script);
        Path source = dir.resolve("ComicStandInProcess.java");
        if (!Files.exists(source)) Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        return List.of(ProcessHandle.current().info().command().orElse("java"), "-cp", jsonClassPath(), source.toString(), script.toString());
    }

    /** Where the JSON library's three jars are, for the stand-in's class path: found from the classes themselves, whatever started this test. */
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
