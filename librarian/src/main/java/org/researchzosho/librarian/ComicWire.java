package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a comic's pictures travel between a library and the machine that serves its comic helper (0.7.6). A request and its reply are
 * each one body of "parts": one line of JSON (UTF-8, ending in a line feed), then the bytes of each file its {@code files} list names,
 * in that order, each as it is: {@code "files": [{"name": "p003.jpg", "size": 482113}, …]}. The pictures are never written as text
 * (a web comic's strip comes back as tens of megabytes of pieces), and the side that reads a body writes each file to the disk as it
 * comes, holding no picture in its memory.
 *
 * <p>The limits, the same on both sides, chosen from the largest picture of the material the helper was measured on: a web comic's
 * strip of 2480 by 24,473 pixels, 18.5 MB as the JPEG it came as; cut, its 7 pieces are 32.3 MB as PNG (the largest 6.3 MB), and joined
 * again it is 8.5 MB as JPEG. {@link #PICTURE_MOST} is three and a half times that strip; {@link #BODY_MOST} eight times its pieces
 * together, for a join of a longer strip; {@link #FILES_MOST} the pieces of a strip 100 times as tall as it is wide; {@link #LINE_MOST}
 * hundreds of times a page's reading, which is tens of kilobytes. A body past one is refused before it is read.
 */
final class ComicWire {
    private ComicWire() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The type a body of parts is sent as. */
    static final String TYPE = "application/x-researchzosho-parts";
    static final long PICTURE_MOST = 64L << 20, BODY_MOST = 256L << 20, LINE_MOST = 16L << 20;
    static final int FILES_MOST = 500;

    /** A body read: its JSON (without its list of files), and the files it carried, in order, each under a folder of its own so that two of one name stay two. */
    record Body(ObjectNode json, List<Path> files) {
        /** The file of a name, or null. */
        Path named(String name) { for (Path f : files) if (f.getFileName().toString().equals(name)) return f; return null; }
    }

    /** A body over one of the limits, or one that is no body of parts: said in a sentence. */
    static final class Refused extends IOException {
        final boolean tooLarge;
        Refused(String message, boolean tooLarge) { super(message); this.tooLarge = tooLarge; }
    }

    /**
     * Whether a name may be a part's: a file's own name, of letters (any alphabet), digits, spaces and . _ - ( ) + , ~ [ ] ' , not
     * beginning with a dot, at most 160 characters. Nothing else is ever a name on the other side's disk.
     */
    static boolean safeName(String n) {
        return n != null && n.length() <= 160 && !n.startsWith(".") && !n.contains("..") && n.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M} ._()+,~\\[\\]'-]*");
    }

    /** The name a file is sent by: its own when that is a safe one, else {@code fallback} with the file's own ending, so the format a helper writes by the ending stays the same. */
    static String nameFor(Path file, String fallback) {
        String n = file.getFileName() == null ? "" : file.getFileName().toString();
        if (safeName(n)) return n;
        String end = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return fallback + (end.matches("[a-z0-9]{1,5}") ? "." + end : "");
    }

    /** What a body of these files would break of the limits, in a sentence; "" when it keeps them. {@code where}: the machine, for the sentence. */
    static String over(List<Path> files, String where) throws IOException {
        if (files.size() > FILES_MOST) return files.size() + " pictures in one request is more than the " + FILES_MOST + " the comic helper on " + where + " takes";
        long all = 0;
        for (Path f : files) {
            if (!Files.isRegularFile(f)) return "there is no picture " + f.getFileName() + " to send to the comic helper on " + where;
            long n = Files.size(f);
            if (n > PICTURE_MOST) return "the picture " + f.getFileName() + " is " + mb(n) + ", more than the " + mb(PICTURE_MOST) + " a picture the comic helper on " + where + " takes";
            all += n;
        }
        if (all > BODY_MOST) return "the pictures of one request are " + mb(all) + " together, more than the " + mb(BODY_MOST) + " the comic helper on " + where + " takes at once";
        return "";
    }

    static String mb(long bytes) { return (bytes + (1L << 20) - 1 >> 20) + " MB"; }

    /** The JSON line of a body, with the list of its files (their names and sizes) put in. */
    static byte[] line(ObjectNode json, List<Path> files, List<String> names) throws IOException {
        ObjectNode j = json.deepCopy();
        ArrayNode list = j.putArray("files");
        for (int i = 0; i < files.size(); i++) list.addObject().put("name", names.get(i)).put("size", Files.size(files.get(i)));
        return (j.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A request's body as the HTTP client sends it: the line, then each file as it lies on the disk. */
    static HttpRequest.BodyPublisher publisher(ObjectNode json, List<Path> files, List<String> names) throws IOException {
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofByteArray(line(json, files, names)));
        for (Path f : files) parts.add(HttpRequest.BodyPublishers.ofFile(f));
        return HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
    }

    /** The length of that body. */
    static long length(ObjectNode json, List<Path> files, List<String> names) throws IOException {
        long n = line(json, files, names).length;
        for (Path f : files) n += Files.size(f);
        return n;
    }

    /** Writes a body: the line, then each file. */
    static void write(OutputStream out, ObjectNode json, List<Path> files, List<String> names) throws IOException {
        out.write(line(json, files, names));
        for (Path f : files) Files.copy(f, out);
    }

    /**
     * Reads a body from {@code in}: its line (at most {@link #LINE_MOST}), then each file its list names, written to
     * {@code dir/<its place>/<its name>}. Refused, before any file is written, when the list breaks a limit or names a file by a name
     * that is not a safe one; refused when the body ends before its last file or goes on after it.
     */
    static Body read(InputStream raw, Path dir) throws IOException {
        InputStream in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 1 << 16);   // the line is read a byte at a time
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        for (int b; ; ) {
            b = in.read();
            if (b < 0) throw new Refused("the request ended before its first line did", false);
            if (b == '\n') break;
            if (head.size() >= LINE_MOST) throw new Refused("the first line of the request is longer than " + mb(LINE_MOST), true);
            head.write(b);
        }
        JsonNode parsed;
        try { parsed = M.readTree(head.toByteArray()); } catch (IOException e) { throw new Refused("the first line of the request is no JSON", false); }
        if (!(parsed instanceof ObjectNode json)) throw new Refused("the first line of the request is no JSON object", false);
        JsonNode list = json.remove("files");
        List<String> names = new ArrayList<>(); List<Long> sizes = new ArrayList<>();
        if (list != null && !list.isArray()) throw new Refused("files is a list", false);
        if (list != null) for (JsonNode f : list) { names.add(f.path("name").asText("")); sizes.add(f.path("size").asLong(-1)); }
        if (names.size() > FILES_MOST) throw new Refused(names.size() + " pictures in one request is more than the " + FILES_MOST + " taken", true);
        long all = 0;
        for (int i = 0; i < names.size(); i++) {
            if (!safeName(names.get(i))) throw new Refused("a file of the request has a name that is not taken: " + names.get(i).replaceAll("[^\\p{L}\\p{N}._ -]", "?"), false);
            long n = sizes.get(i);
            if (n < 0) throw new Refused("a file of the request has no size", false);
            if (n > PICTURE_MOST) throw new Refused("the picture " + names.get(i) + " is " + mb(n) + ", more than the " + mb(PICTURE_MOST) + " a picture taken", true);
            all += n;
        }
        if (all > BODY_MOST) throw new Refused("the pictures of the request are " + mb(all) + " together, more than the " + mb(BODY_MOST) + " taken at once", true);
        List<Path> files = new ArrayList<>();
        byte[] buf = new byte[1 << 16];
        for (int i = 0; i < names.size(); i++) {
            Path f = Files.createDirectories(dir.resolve(String.valueOf(i))).resolve(names.get(i));
            try (OutputStream o = Files.newOutputStream(f)) {
                for (long left = sizes.get(i); left > 0; ) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new Refused("the request ended inside its file " + names.get(i), false);
                    o.write(buf, 0, n); left -= n;
                }
            }
            files.add(f);
        }
        if (in.read() >= 0) throw new Refused("the request goes on after its last file", false);
        return new Body(json, List.copyOf(files));
    }

    /** The names of files, each as {@link #nameFor} gives it ({@code fallback} with its place); two of one name stay two, each read into a folder of its own. */
    static List<String> names(List<Path> files, String fallback) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) out.add(nameFor(files.get(i), fallback + (i + 1)));
        return out;
    }
}
