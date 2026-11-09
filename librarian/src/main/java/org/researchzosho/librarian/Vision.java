package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a model sees pictures, measured rather than assumed: a 16×16 square of one colour, drawn here, goes to the model as a picture
 * with the question what colour it is. A model that sees names the colour; a server that drops the picture leaves the model guessing,
 * and a hosted model that takes no pictures answers with an error. A local model is asked twice, a red square then a blue one, because
 * both calls are free and a blind guess cannot land twice; a hosted model is asked once, the red square, one small paid call. The answer
 * is kept on the entry ({@code sees}), so the question is asked once, not on every picture.
 */
public final class Vision {
    private Vision() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** What the probe found: {@code sees} true or false, the model's words, and why the probe read them that way. */
    public record Probe(Boolean sees, String said, String why) {
        /** The three words the models list prints. */
        public static String word(Boolean sees) { return sees == null ? "not probed for pictures" : sees ? "sees images" : "does not see images"; }
        public String word() { return word(sees); }
    }

    /** The question every square is sent with. */
    public static final String QUESTION = "What colour is this square? Answer with one word.";

    /** The drive for an entry's server and model; a test stands one in that answers from the picture's bytes. */
    static volatile BiFunction<String, String, Researcher.Drive> DRIVES = (drive, model) -> Researcher.watched(drive, model, "eyes", Declines.toCrewsLog(null, "vision"));

    /** The drives made, by entry: one client an entry a process, not one a picture (a command printed the client's context-window line nine times, 2026-11-08); emptied when the factory is swapped. */
    private static final Map<String, Researcher.Drive> MADE = new ConcurrentHashMap<>();
    private static volatile BiFunction<String, String, Researcher.Drive> MADE_BY = null;

    /** The one drive for an entry's server and model, made on the first ask and kept. */
    static Researcher.Drive drive(ModelEntries.Entry e) {
        BiFunction<String, String, Researcher.Drive> f = DRIVES;
        synchronized (MADE) { if (MADE_BY != f) { MADE.clear(); MADE_BY = f; } }
        return MADE.computeIfAbsent(e.drive() + "|" + e.model(), k -> f.apply(e.drive(), e.model()));
    }

    private static final Pattern RED = Pattern.compile("(?i)\\b(red|crimson|scarlet|赤|赤色)\\b|赤");
    private static final Pattern BLUE = Pattern.compile("(?i)\\b(blue|navy|azure|cobalt|青|青色)\\b|青");

    /** The probe of one entry: red, then for a local entry blue as well. */
    public static Probe probe(ModelEntries.Entry e) {
        Researcher.Drive d;
        try { d = drive(e); }
        catch (RuntimeException x) { return new Probe(false, "", "no connection could be made to " + e.drive() + ": " + x.getMessage()); }
        String red = ask(d, square(255, 0, 0));
        if (!RED.matcher(red).find()) {
            String problem = DriveClient.lastClassifyProblem();
            return new Probe(false, red, red.isBlank() ? "it gave no answer to a picture" + (problem == null || problem.isBlank() ? "" : " (" + problem + ")") : "it called a red square \"" + cut(red) + "\"");
        }
        if (e.hosted()) return new Probe(true, red, "it named the red square");
        String blue = ask(d, square(0, 0, 255));
        if (!BLUE.matcher(blue).find()) return new Probe(false, red + " / " + blue, "it named the red square but called a blue one \"" + cut(blue) + "\": a guess, not a look");
        return new Probe(true, red + " / " + blue, "it named a red square and a blue one");
    }

    /** One square to one drive: the model's words, "" when it gave none. */
    static String ask(Researcher.Drive d, byte[] png) {
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", QUESTION);
        content.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl(png, "image/png"));
        DriveClient.clearClassifyProblem();
        try { String s = d.classify(msgs, 12); return s == null ? "" : s.strip(); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (RuntimeException unanswered) { return ""; }
    }

    /** The question two squares are sent with, for whether an entry takes two pictures in one call. */
    public static final String TWO_QUESTION = "Two squares follow. What colour is the first square, and what colour is the second? Answer with two words, in order.";

    /** The answer, by entry (its server and model), to whether it takes two pictures in one call; a test's override stands for every entry. */
    static final Map<String, Boolean> TWO = new ConcurrentHashMap<>();
    static volatile Boolean TWO_IMAGES = null;

    /**
     * Whether an entry takes two pictures in one call, measured once a process for a local entry: a red square and a blue one together,
     * asked for both colours in order — the 27B on its server answered "red blue" and "blue red" (2026-11-08). A hosted entry is not
     * probed, since the call is paid: it is taken to, and a judgement that comes back unreadable falls back to words. A model that takes one
     * picture at a time has the reference described in words instead ({@link Images#like}).
     */
    public static boolean seesTwo(ModelEntries.Entry e) {
        Boolean over = TWO_IMAGES;
        if (over != null) return over;
        if (e == null) return false;
        if (e.hosted()) return true;
        return TWO.computeIfAbsent(e.drive() + "|" + e.model(), k -> probeTwo(e));
    }

    static boolean probeTwo(ModelEntries.Entry e) {
        Researcher.Drive d;
        try { d = drive(e); } catch (RuntimeException x) { return false; }
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", TWO_QUESTION);
        content.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl(square(255, 0, 0), "image/png"));
        content.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl(square(0, 0, 255), "image/png"));
        String said;
        try { said = d.classify(msgs, 16); } catch (Stopping.Requested stop) { throw stop; } catch (RuntimeException unanswered) { return false; }
        if (said == null) return false;
        Matcher r = RED.matcher(said), b = BLUE.matcher(said);
        return r.find() && b.find() && r.start() < b.start();   // both named, in order: the second picture was seen, not guessed
    }

    /** A 16×16 PNG of one colour. */
    public static byte[] square(int r, int g, int b) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        int rgb = (r << 16) | (g << 8) | b;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { ImageIO.write(img, "png", out); } catch (IOException e) { throw new UncheckedIOException(e); }
        return out.toByteArray();
    }

    /** The picture as the message carries it. */
    public static String dataUrl(byte[] bytes, String contentType) {
        String type = contentType == null || contentType.isBlank() ? "image/png" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String cut(String s) { String t = s.replaceAll("\\s+", " ").strip(); return t.length() > 60 ? t.substring(0, 60) + "…" : t; }
}
