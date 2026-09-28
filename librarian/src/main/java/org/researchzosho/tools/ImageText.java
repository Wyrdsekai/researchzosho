package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Base64;

import java.awt.Color;
import org.researchzosho.Config;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;
/**
 * The writing in a picture, read by the model: a photographed register page, a scanned letter, a headstone. The
 * library's own model does it when it reads images (a llama.cpp server started with the model's mmproj file, or any
 * hosted model that takes pictures); there is no separate OCR program to install. A character the model cannot read
 * comes back as □, never as a guess, because a family register's one wrong character is another person.
 */
public final class ImageText {

    private ImageText() { }

    /** A picture in, its writing out; "" when the model gave nothing. */
    public interface Reader { String read(byte[] png, String hint); }

    static final int LONGEST_SIDE = 2400;

    static final String ASK = "Transcribe the writing in this picture exactly. Every character as it is written, in its own script and its old "
            + "character forms, line by line from the top; for vertical Japanese or Chinese, the right-hand column first. Keep a table as "
            + "lines with its cells separated by ' | '. Write □ for each character you cannot read. Only the transcription: no translation, "
            + "no summary, no comment. If the picture has no writing, answer with one line that starts PHOTO: and says what it shows.";

    /** What the model is asked to do, in the few words the decline judge reads beside its reply. */
    static final String STEP = "transcribe the writing in a picture";

    private static volatile Reader reader = null;

    /** Tests and a host with its own reader set one; null restores the live one. */
    public static void use(Reader r) { reader = r; }

    static Reader current() { Reader r = reader; return r != null ? r : LIVE; }

    static final Reader LIVE = (png, hint) -> live(new DriveClient(Config.get("RESEARCHZOSHO_VISION_DRIVE", Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200")),
                Config.get("RESEARCHZOSHO_VISION_MODEL", Config.get("RESEARCHZOSHO_MODEL", "local-model")))).read(png, hint);

    /** The reader on {@code drive}. */
    public static Reader live(DriveClient drive) {
        return (png, hint) -> {
            ObjectMapper j = new ObjectMapper();
            ArrayNode msgs = j.createArrayNode();
            ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
            content.addObject().put("type", "text").put("text", ASK + (hint == null || hint.isBlank() ? "" : "\nThe file is called " + hint + "."));
            ObjectNode img = content.addObject().put("type", "image_url");
            img.putObject("image_url").put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            String text = drive.classify(msgs, 6000);
            DeclineJudge.of(drive).raise(drive.model(), STEP, text);   // the reply is the transcription itself: a decline in words is never saved as the picture's writing
            return text;
        };
    }

    public static boolean isImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        return (b[0] == (byte) 0xFF && b[1] == (byte) 0xD8)                                   // JPEG
                || (b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')          // PNG
                || (b[0] == 'G' && b[1] == 'I' && b[2] == 'F')                                 // GIF
                || (b[0] == 'B' && b[1] == 'M')                                                // BMP
                || (b[0] == 'I' && b[1] == 'I' && b[2] == 42) || (b[0] == 'M' && b[1] == 'M' && b[3] == 42)   // TIFF
                || isWebp(b);
    }

    /** A WebP picture: RIFF, four bytes of length, WEBP. Told apart so that it is said as a picture this build does not open, never read as text. */
    public static boolean isWebp(byte[] b) {
        return b != null && b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** A picture of more pixels than this is not opened: a decompression bomb says it is billions of pixels in a few bytes. */
    static final long MOST_PIXELS = 250_000_000L;
    /** A picture larger than this is decoded reading every n-th pixel, so that it takes about this many pixels of memory. */
    static final long DECODE_PIXELS = 16_000_000L;

    /** A picture that says it is larger than {@link #MOST_PIXELS}: refused before anything of it is decoded. */
    static final class TooLarge extends IOException {
        TooLarge(long w, long h) { super("the picture says it is " + w + " by " + h + " pixels, more than the " + (MOST_PIXELS / 1_000_000) + " million pixels this program opens"); }
    }

    /**
     * The picture as the model is sent it: a PNG, its longest side at most {@value #LONGEST_SIDE}, in memory only; null when this build
     * cannot open it. Its size is read from its header first: a picture over {@link #MOST_PIXELS} is refused, and a large one is decoded
     * at a fraction of its pixels, so that a picture cannot take the program's memory.
     */
    static byte[] png(byte[] image) throws Exception {
        BufferedImage in;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0) return null;
                if (w * h > MOST_PIXELS) throw new TooLarge(w, h);
                ImageReadParam param = reader.getDefaultReadParam();
                if (w * h > DECODE_PIXELS) {
                    int step = (int) Math.ceil(Math.sqrt((double) (w * h) / DECODE_PIXELS));
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                try { in = reader.read(0, param); }
                catch (OutOfMemoryError tooBig) { throw new IOException("the picture is too large to open in the memory this program has"); }
            } finally { reader.dispose(); }
        }
        if (in == null) return null;
        int w = in.getWidth(), h = in.getHeight(), longest = Math.max(w, h);
        BufferedImage out = in;
        if (longest > LONGEST_SIDE || in.getType() == BufferedImage.TYPE_CUSTOM || in.getColorModel().hasAlpha()) {
            double k = Math.min(1.0, (double) LONGEST_SIDE / longest);
            out = new BufferedImage(Math.max(1, (int) Math.round(w * k)), Math.max(1, (int) Math.round(h * k)), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.drawImage(in, 0, 0, out.getWidth(), out.getHeight(), null); g.dispose();
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bo);
        return bo.toByteArray();
    }

    /**
     * The one question asked of a picture fetched from the web, on the model that reads pictures, before it is read: whether it appears to
     * show a child in a sexual context. Nudity, art and adult pictures are not what it asks about. Only this: a picture from a site of
     * pornography cannot arrive while the site list is on, because the list is checked before the picture is downloaded.
     */
    public static final String CHILD_QUESTION = "Does this image appear to show a child in a sexual context?";

    /** The check on a picture: its PNG in, the judge's reading out. A test passes its own. */
    public interface Checker { ContentJudge.Reading check(byte[] png, String question); }

    private static volatile Checker checker = null;

    /** Tests and a host with its own check set one; null asks the model that reads pictures. */
    public static void useChecker(Checker c) { checker = c; }

    static final Checker LIVE_CHECK = (png, question) -> ContentJudge.vision().askImage("data:image/png;base64," + Base64.getEncoder().encodeToString(png), question);

    /**
     * {@link #CHILD_QUESTION} of a picture: the judge's reading, or null when the picture is one this build cannot open (it cannot be
     * read or kept either). The picture stays in memory: nothing of it is written to disk.
     */
    public static ContentJudge.Reading childCheck(byte[] image) {
        byte[] png;
        try { png = png(image); } catch (Exception e) { return null; }
        if (png == null) return null;
        Checker c = checker;
        return (c != null ? c : LIVE_CHECK).check(png, CHILD_QUESTION);
    }

    /** The picture's writing, or "" with {@code why} filled in when it could not be read. */
    public static String read(byte[] image, String hint, StringBuilder why) {
        Reading r = reading(image, hint);
        why.append(r.why());
        return r.text();
    }

    /** What reading a picture gave: its writing ("" when none), why there is none, and the model's decline when it declined to read it. */
    public record Reading(String text, String why, Declined declined) { }

    /** The picture's writing, read by the model; a decline is carried, said in {@code why}, and never asked again. */
    public static Reading reading(byte[] image, String hint) {
        StringBuilder why = new StringBuilder();
        Declined[] declined = {null};
        String text = read(image, hint, why, declined);
        return new Reading(text, why.toString(), declined[0]);
    }

    private static String read(byte[] image, String hint, StringBuilder why, Declined[] declined) {
        byte[] png;
        try {
            png = png(image);
            if (png == null) { why.append(isWebp(image) ? "the picture is a WebP picture, a format this build does not open (JPEG, PNG, GIF, BMP and TIFF are)" : "the picture's format is not one this build opens (JPEG, PNG, GIF, BMP and TIFF are)"); return ""; }
        } catch (Exception e) { why.append("the picture could not be opened (").append(e.getMessage()).append(")"); return ""; }
        String text;
        try { text = current().read(png, hint); }
        catch (Declined d) { declined[0] = d; why.append(d.statement("to read the writing in this picture")); return ""; }   // said, not asked again
        catch (Exception e) { text = ""; }
        if (text == null || text.isBlank()) { why.append("the model did not read the picture. It needs a model that reads images: a llama.cpp server started with the model's --mmproj file, or a hosted model that takes pictures (RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL name a separate one)"); return ""; }
        return text.strip();
    }
}
