package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.VideoText;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's storyboards: the small frames YouTube makes of every video for the seek bar's preview, one about every five seconds (every
 * ten in a long video, one a second in a short one), laid out on sheets of tiles. yt-dlp lists them as the "sb" formats and fetches a
 * video's as one file of a few hundred kilobytes, where a small copy of the video is tens of megabytes. They are fetched once through
 * the helper and kept in its folder as the sheets come, WebP as YouTube serves them (a tenth of the PNG they would be), opened by
 * ffmpeg when read, and cut into tiles, each with its second.
 */
final class Storyboards {
    private Storyboards() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** A video's sheets as kept: the grid of each, the frames a second, the video's length in seconds, and the sheet files in order. */
    record Sheets(int rows, int columns, double fps, double duration, List<Path> files) {
        /** The seconds between one frame and the next, as a person would say it. */
        int gap() { return Math.max(1, (int) Math.round(1 / fps)); }
        /**
         * The seconds from one frame to the next as YouTube takes them: a whole number (one, two, five, ten) when the rate yt-dlp
         * reports is within a quarter second of one. yt-dlp's rate is the count of frames over the video's length, which drifts by
         * up to a frame over a video (a tile at 333 seconds by the rate was at 340 in the video); the frames themselves are taken at
         * whole seconds.
         */
        double step() { double s = 1 / fps, whole = Math.round(s); return Math.abs(s - whole) < 0.25 && whole >= 1 ? whole : s; }
    }

    /** One small frame of the video: its second and its picture. */
    record Tile(int seconds, BufferedImage image) {
        /** The tile as a PNG, scaled up in whole steps to at least {@code least} pixels wide, so that a model reads its small writing. */
        byte[] png(int least) throws IOException {
            int k = Math.max(1, (int) Math.ceil((double) least / image.getWidth()));
            BufferedImage out = new BufferedImage(image.getWidth() * k, image.getHeight() * k, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, 0, 0, out.getWidth(), out.getHeight(), null);
            g.dispose();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bo);
            return bo.toByteArray();
        }
    }

    static Path dir(Path work, String videoId) { return work.resolve("storyboards").resolve(videoId); }

    /** The sheets kept for a video in the helper's folder, WebP as they came or PNG from an earlier reading; null when none were kept. */
    static Sheets kept(String videoId, Path work) {
        Path dir = dir(work, videoId), kept = dir.resolve("sheets.json");
        if (!Files.isRegularFile(kept)) return null;
        try {
            JsonNode o = M.readTree(Files.readString(kept, StandardCharsets.UTF_8));
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < o.path("sheets").asInt(0); i++) for (String ext : new String[]{"webp", "png"}) { Path p = dir.resolve(String.format("sheet-%03d.%s", i, ext)); if (Files.isRegularFile(p)) { files.add(p); break; } }
            return files.isEmpty() ? null : new Sheets(o.path("rows").asInt(1), o.path("columns").asInt(1), o.path("fps").asDouble(0.2), o.path("duration").asDouble(0), files);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * The sheets of a video: the ones kept, else fetched now through the helper and kept; null when the video has no storyboard, which
     * is remembered so that it is not asked for again, or when they could not be had this time, which is not.
     */
    static Sheets fetch(String videoId, Path work, Video.Runner ffmpeg) {
        Sheets have = kept(videoId, work);
        if (have != null) return have;
        Path dir = dir(work, videoId);
        if (Files.isRegularFile(dir.resolve("none.txt"))) return null;
        try {
            Files.createDirectories(dir);
            Path mhtml = dir.resolve("sb.mhtml"), info = dir.resolve("sb.info.json");
            Video.Result r = Video.ytdlp(List.of("-f", "sb0", "--write-info-json", "--no-playlist", "-o", dir.resolve("sb.%(ext)s").toString(), VideoText.moment(videoId, 0).replace("&t=0s", "")), Duration.ofMinutes(5), Video.readPaceMs);
            String said = r.out() == null ? "" : r.out();
            if (r.code() != 0 || !Files.isRegularFile(mhtml) || !Files.isRegularFile(info)) {
                if (said.contains("Requested format is not available")) Files.writeString(dir.resolve("none.txt"), "YouTube has no storyboard for this video.\n", StandardCharsets.UTF_8);
                return null;
            }
            JsonNode j = M.readTree(Files.readString(info, StandardCharsets.UTF_8));
            JsonNode sb = null;
            for (JsonNode f : j.path("formats")) if (f.path("format_id").asText("").equals("sb0")) sb = f;
            int rows = sb == null ? 0 : sb.path("rows").asInt(0), columns = sb == null ? 0 : sb.path("columns").asInt(0);
            double fps = sb == null ? 0 : sb.path("fps").asDouble(0), duration = j.path("duration").asDouble(0);
            List<Path> files = new ArrayList<>();
            if (rows > 0 && columns > 0 && fps > 0) {
                List<byte[]> parts = images(Files.readAllBytes(mhtml));
                for (int i = 0; i < parts.size(); i++) {
                    Path file = keepSheet(parts.get(i), dir, i);
                    if (file != null) files.add(file);
                }
            }
            Files.deleteIfExists(info); Files.deleteIfExists(mhtml);
            if (files.isEmpty()) { Files.writeString(dir.resolve("none.txt"), "The storyboard could not be read.\n", StandardCharsets.UTF_8); return null; }
            ObjectNode o = M.createObjectNode().put("rows", rows).put("columns", columns).put("fps", fps).put("duration", duration).put("sheets", files.size());
            Files.writeString(dir.resolve("sheets.json"), o.toString() + "\n", StandardCharsets.UTF_8);
            return new Sheets(rows, columns, fps, duration, files);
        } catch (Exception e) { return null; }
    }

    /** The pictures in a yt-dlp storyboard file (an MHTML: the sheets as parts, each after its headers and a blank line), in order. */
    static List<byte[]> images(byte[] mhtml) {
        List<byte[]> out = new ArrayList<>();
        String head = new String(mhtml, 0, Math.min(mhtml.length, 4000), StandardCharsets.ISO_8859_1);
        Matcher b = Pattern.compile("boundary=\"?([^\";\\s]+)").matcher(head);
        if (!b.find()) return out;
        byte[] sep = ("--" + b.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlf = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), lf = "\n\n".getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(mhtml, sep, 0);
        while (at >= 0) {
            int next = indexOf(mhtml, sep, at + sep.length);
            int end = next < 0 ? mhtml.length : next;
            int blank = indexOf(mhtml, crlf, at), skip = 4;
            if (blank < 0 || blank > end) { blank = indexOf(mhtml, lf, at); skip = 2; }
            if (blank >= 0 && blank < end) {
                String headers = new String(mhtml, at, blank - at, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                if (headers.contains("content-type: image/")) {
                    int from = blank + skip, to = end;
                    while (to > from && (mhtml[to - 1] == '\r' || mhtml[to - 1] == '\n')) to--;
                    if (to > from) out.add(Arrays.copyOfRange(mhtml, from, to));
                }
            }
            at = next;
        }
        return out;
    }

    static int indexOf(byte[] in, byte[] what, int from) {
        outer:
        for (int i = Math.max(0, from); i <= in.length - what.length; i++) {
            for (int j = 0; j < what.length; j++) if (in[i + j] != what[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** A sheet kept as it came: a WebP as its own bytes, anything else this build opens written as a PNG; null when it is neither. */
    static Path keepSheet(byte[] image, Path dir, int index) {
        try {
            if (ImageText.isWebp(image)) { Path webp = dir.resolve(String.format("sheet-%03d.webp", index)); Files.write(webp, image); return webp; }
            BufferedImage in = ImageIO.read(new ByteArrayInputStream(image));
            if (in == null) return null;
            Path png = dir.resolve(String.format("sheet-%03d.png", index));
            ImageIO.write(in, "png", png.toFile());
            return Files.isRegularFile(png) ? png : null;
        } catch (Exception e) { return null; }
    }

    /** A kept sheet opened: a PNG by this build, a WebP by ffmpeg into a PNG beside it that is deleted once read; null when it cannot be opened. */
    static BufferedImage sheet(Path file, Video.Runner ffmpeg) throws IOException {
        if (!file.getFileName().toString().endsWith(".webp")) return ImageIO.read(file.toFile());
        Path png = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.webp$", ".opened.png"));
        try {
            try { ffmpeg.run(List.of("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", file.toString(), png.toString()), Duration.ofMinutes(1)); } catch (Exception e) { return null; }
            return Files.isRegularFile(png) ? ImageIO.read(png.toFile()) : null;
        } finally { Files.deleteIfExists(png); }
    }

    /** The tiles of the sheets in order, each with its second: the k-th tile of all is at k steps; the padding past the video's end is left out. */
    static List<Tile> tiles(Sheets s, Video.Runner ffmpeg) throws IOException {
        List<Tile> out = new ArrayList<>();
        int k = 0;
        for (Path file : s.files()) {
            BufferedImage sheet = sheet(file, ffmpeg);
            if (sheet == null) { k += s.rows() * s.columns(); continue; }
            int tw = sheet.getWidth() / s.columns(), th = sheet.getHeight() / s.rows();
            if (tw <= 0 || th <= 0) { k += s.rows() * s.columns(); continue; }
            for (int r = 0; r < s.rows(); r++) for (int c = 0; c < s.columns(); c++, k++) {
                int seconds = (int) Math.round(k * s.step());
                if (s.duration() > 0 && seconds > s.duration()) return out;
                out.add(new Tile(seconds, sheet.getSubimage(c * tw, r * th, tw, th)));
            }
        }
        return out;
    }

    /** Whether two tiles show the same picture, a still or a slide that stayed: no patch of a coarse grid over them differs by much. */
    static boolean same(BufferedImage a, BufferedImage b) { return same(a, b, 16); }

    /** The same, with the difference a patch may show and still count as the same picture: looser for a frame cut from the video against YouTube's own. */
    static boolean same(BufferedImage a, BufferedImage b, int tolerance) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int cols = 32, rows = 18;
        int pw = Math.max(1, a.getWidth() / cols), ph = Math.max(1, a.getHeight() / rows);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int x0 = c * pw, y0 = r * ph;
            if (x0 + pw > a.getWidth() || y0 + ph > a.getHeight()) continue;
            long sa = 0, sb = 0;
            for (int y = y0; y < y0 + ph; y++) for (int x = x0; x < x0 + pw; x++) { sa += gray(a.getRGB(x, y)); sb += gray(b.getRGB(x, y)); }
            if (Math.abs(sa - sb) / (pw * ph) >= tolerance) return false;
        }
        return true;
    }

    static int gray(int rgb) { return (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000; }
}
