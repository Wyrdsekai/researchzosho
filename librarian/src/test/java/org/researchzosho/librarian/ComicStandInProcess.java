package org.researchzosho.librarian;

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

/**
 * The stand-in for the comic helper's process ({@code comic/serve.py}), which {@link ComicStandIn#command} starts for the comic tests.
 * It answers as the helper does: a ready line that lists the languages it reads; {@code read} with the page
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
 * is lettered there in its pixels, the block's own {@code lettered} or else its text ({@link ComicStandIn#lettered} reads it back, as a model
 * that sees would read the balloon); {@code picture} by copying the page, the boxes kept as {@code <out>.boxes.json}; {@code quit}. Files in
 * the script folder steer it: {@code die-once} ends the process at the next read and is removed; {@code die-always} ends it at every
 * read; {@code hang} makes a read never answer; {@code no-crops} makes it a helper that has no {@code crops}; {@code page-px} holds the one size every page is set at in place of 28 (a page held down by its original lettering: a block whose text fits is set at it and says 28 as the size its own text would take); {@code languages} holds, with spaces between, the languages the ready line lists
 * in place of its own. Every request is appended to {@code requests.log} there.
 *
 * <p>A class of the tests, compiled with them: a start is the start of a Java program, and no longer a compile of its source as well
 * (a source file run by the launcher was compiled again at every start, about two seconds each under the suite's load, and the comic
 * tests start it about a hundred times).
 */
public final class ComicStandInProcess {
    private ComicStandInProcess() { }

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
        var langs = ready.putArray("languages"); for (String l : reads.split("\\s+")) if (!l.isEmpty()) langs.add(l);
        out.println(ready);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Files.writeString(script.resolve("requests.log"), line.length() > 400 ? line.substring(0, 400) + "\n" : line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
                        String stem = image.getFileName().toString().replaceFirst("\\.[^.]+$", "");
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
