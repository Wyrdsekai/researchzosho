package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Files posted with a form in the browser: read into a folder of their own for the one request, and dropped once the page has read them. */
public final class Uploads {
    private Uploads() { }

    /** The largest post a page takes, in bytes, all its files together: a book-length PDF fits, a folder of papers fits; a disk image does not. */
    public static final int MAX = 64 << 20;
    /** The most files one post may carry: a folder of papers, chosen at once; a bigger folder goes in at the command line. */
    public static final int MAX_FILES = 500;
    public static final String DIR = "researchzosho-upload-";

    /** One file the browser posted: where this server wrote it, its own name, and its path inside the folder it came from ("" for a file chosen on its own). */
    public record Uploaded(Path path, String name, String rel) { }

    /** A text field named like the keys this parser makes for a file is not read: a posted field cannot pose as a file the server wrote. */
    private static final Pattern FILE_KEY = Pattern.compile("^[A-Za-z0-9_-]+(\\.\\d+)?\\.(path|name|rel)$");

    /**
     * A multipart form, as a browser posts one with files in it: the text fields as they are, and each file written under {@code dir}
     * with its own name (odd characters replaced), named in the map as {@code <field>.path} and {@code <field>.name}; the second file of
     * the same field as {@code <field>.2.path} and {@code <field>.2.name}, and so on ({@link #files} reads them back in order). A file
     * from a folder chosen whole arrives with its path inside that folder; it is written under {@code dir} at that path, with {@code
     * <field>.rel} saying it, so the folder is on disk as it was chosen. {@code dir} is a folder made for this one request; the page
     * that takes the files removes it with {@link #drop(Map)}. More than {@link #MAX_FILES} files is refused, and nothing is left on disk.
     */
    public static Map<String, String> multipart(byte[] body, String contentType, Path dir) throws IOException {
        return multipart(new ByteArrayInputStream(body), contentType, dir, Long.MAX_VALUE, "");
    }

    /** The setting that says how large a file the Translate form takes, in megabytes; unset, {@link #TRANSLATE_MB}. */
    public static final String TRANSLATE_SETTING = "RESEARCHZOSHO_TRANSLATE_UPLOAD_MB";
    /** A comic's volume is often several hundred megabytes; a post is read as a stream and written straight to disk, so its size costs disk and no memory. */
    public static final int TRANSLATE_MB = 512;
    /** The longest text field a form may carry: a chat message, a pasted document; a file goes in a file's part. */
    static final int MAX_FIELD = 16 << 20;
    /** How much of a post that is over its limit is still read and dropped, and for how long, so that the browser is done sending and shows the answer; past either, the connection ends. */
    static final long DRAIN_BYTES = 4L << 30, DRAIN_MILLIS = 60_000;

    /** The key under which the server hands a page the sentence a post was refused with (too large); a posted field of that name is dropped. */
    public static final String REFUSED = "upload.refused";

    /** The most the Translate form's post may be, in bytes. */
    public static long translateLimit() { return Math.max(1, Config.getInt(TRANSLATE_SETTING, TRANSLATE_MB)) * (1L << 20); }

    /** What a post to the Translate form larger than that is answered with. */
    public static String translateOverSaid() {
        return "The file is larger than the Translate form takes, " + (translateLimit() >> 20) + " MB. Translate it at the command line on the machine that runs the library (researchzosho translate <file> --to <language>), or raise what the form takes there: researchzosho settings set translate_upload_mb <megabytes>.";
    }

    /** A Content-Length header as a number of bytes; -1 when there is none or it is no number (a post sent in chunks). */
    public static long declared(String contentLength) {
        try { return contentLength == null ? -1 : Long.parseLong(contentLength.strip()); } catch (NumberFormatException e) { return -1; }
    }

    /** The rest of a post read and dropped, within {@link #DRAIN_BYTES} and {@link #DRAIN_MILLIS}: nothing of it is kept. */
    public static void drain(InputStream in) {
        byte[] buf = new byte[1 << 16];
        long dropped = 0, until = System.currentTimeMillis() + DRAIN_MILLIS;
        try { for (int n; dropped < DRAIN_BYTES && System.currentTimeMillis() < until && (n = in.read(buf)) >= 0; ) dropped += n; } catch (IOException ignored) { }
    }

    /**
     * The same form read as it arrives: a file's part goes straight to its file under {@code dir}, a text field is kept in memory (up
     * to {@link #MAX_FIELD}), and the bytes are counted as they pass. A post of more than {@code limit} bytes is refused with {@code
     * over} as its sentence: what was written is removed, and the rest of the post is read and dropped so the browser can show the
     * answer. Nothing of a file is held in memory beyond one buffer, whatever its size.
     */
    public static Map<String, String> multipart(InputStream in, String contentType, Path dir, long limit, String over) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        int bi = contentType == null ? -1 : contentType.indexOf("boundary=");
        if (bi < 0) return m;
        String boundary = contentType.substring(bi + 9).trim();
        int semi = boundary.indexOf(';'); if (semi > 0) boundary = boundary.substring(0, semi).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) boundary = boundary.substring(1, boundary.length() - 1);
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1), close = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), blank = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Integer> count = new LinkedHashMap<>();
        int[] files = {0};
        Body body = new Body(in, limit);
        try {
            if (!body.skipPast(delim)) return m;
            while (true) {
                if (!body.need(2) || body.startsWith('-', '-')) break;   // the closing delimiter, or the end
                if (body.startsWith('\r', '\n')) body.skip(2);
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                if (body.copyUntil(blank, head, 1 << 16) < 0) break;
                String headers = headers(head.toByteArray(), 0, head.size());
                String name = param(headers, "name"), filename = param(headers, "filename");
                List<String> segs = name != null && filename != null ? segments(filename) : List.of();
                if (!segs.isEmpty()) {
                    Path[] file = {null};
                    OutputStream[] out = {null};
                    OutputStream lazy = new OutputStream() {   // the file is made when its first byte comes: a part with nothing in it is no file
                        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                        @Override public void write(byte[] b, int off, int len) throws IOException {
                            if (len <= 0) return;
                            if (out[0] == null) {
                                if (++files[0] > MAX_FILES) throw new TooMany();
                                Path f = dir;
                                for (String s : segs) f = f.resolve(s);
                                Files.createDirectories(f.getParent());
                                file[0] = unused(f);
                                out[0] = Files.newOutputStream(file[0]);
                            }
                            out[0].write(b, off, len);
                        }
                    };
                    long n;
                    try { n = body.copyUntil(close, lazy, Long.MAX_VALUE); } finally { if (out[0] != null) out[0].close(); }
                    if (n < 0) { if (file[0] != null) Files.deleteIfExists(file[0]); break; }   // the post ended inside the file: it is not kept
                    if (file[0] != null) {
                        int k = count.merge(name, 1, Integer::sum);
                        String key = k == 1 ? name : name + "." + k;
                        m.put(key + ".path", file[0].toString()); m.put(key + ".name", file[0].getFileName().toString());
                        if (segs.size() > 1) m.put(key + ".rel", String.join("/", segs));
                    }
                } else {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    if (body.copyUntil(close, text, MAX_FIELD) < 0) break;
                    if (name != null && filename == null && !FILE_KEY.matcher(name).matches()) m.put(name, text.toString(StandardCharsets.UTF_8));
                }
            }
        } catch (TooMany e) {
            remove(dir); drain(in);
            throw ProtocolError.invalidArgs("More than " + MAX_FILES + " files in one go. The page takes up to " + MAX_FILES + " at once; a bigger folder goes in at the command line on the machine that runs the library, or as an archive.");
        } catch (Over e) {
            remove(dir); drain(in);
            throw ProtocolError.invalidArgs(over == null || over.isEmpty() ? "The post is larger than the page takes." : over);
        }
        return m;
    }

    /** More files than a post may carry. */
    private static final class TooMany extends IOException { }
    /** More bytes than the post may be, or a text field longer than one may be. */
    private static final class Over extends IOException { }

    /** A post's body as it arrives, one buffer of it in memory: the bytes counted, a marker looked for across the buffer's edge. */
    private static final class Body {
        private final InputStream in;
        private final long limit;
        private long taken;
        private byte[] buf = new byte[1 << 16];
        private int pos, end;
        private boolean ended;

        Body(InputStream in, long limit) { this.in = in; this.limit = limit; }

        /** More of the post into the buffer, what is unread kept at its start; false when the post has ended. */
        private boolean fill() throws IOException {
            if (ended) return false;
            if (pos > 0) { System.arraycopy(buf, pos, buf, 0, end - pos); end -= pos; pos = 0; }
            if (end == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);   // a marker longer than the buffer: never with a boundary of a line's length
            int n = in.read(buf, end, buf.length - end);
            if (n < 0) { ended = true; return false; }
            taken += n;
            if (taken > limit) throw new Over();
            end += n;
            return true;
        }

        /** Whether {@code n} bytes are there to look at. */
        boolean need(int n) throws IOException { while (end - pos < n) if (!fill()) return false; return true; }
        boolean startsWith(char a, char b) { return end - pos >= 2 && buf[pos] == (byte) a && buf[pos + 1] == (byte) b; }
        void skip(int n) { pos += n; }

        private int find(byte[] needle) {
            outer:
            for (int i = pos; i <= end - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) if (buf[i + j] != needle[j]) continue outer;
                return i;
            }
            return -1;
        }

        /** Everything up to and with the marker passed over; false when the post ends first. */
        boolean skipPast(byte[] needle) throws IOException { return copyUntil(needle, null, Long.MAX_VALUE) >= 0; }

        /**
         * The bytes before the marker written to {@code out} (dropped when it is null) and the marker passed over: how many bytes
         * they were, or -1 when the post ended before the marker. More than {@code most} of them is a post over its limit.
         */
        long copyUntil(byte[] needle, OutputStream out, long most) throws IOException {
            long copied = 0;
            while (true) {
                int at = find(needle);
                int upTo = at >= 0 ? at : Math.max(pos, end - (needle.length - 1));   // without the marker in sight, the last bytes may be its beginning
                if (upTo > pos) {
                    copied += upTo - pos;
                    if (copied > most) throw new Over();
                    if (out != null) out.write(buf, pos, upTo - pos);
                    pos = upTo;
                }
                if (at >= 0) { pos = at + needle.length; return copied; }
                if (!fill()) return -1;
            }
        }
    }

    /**
     * The parts of a posted file's name, each made safe: a file chosen on its own gives one; a file from a folder chosen whole gives the
     * folder's name and the way down to the file (browsers write that path with forward slashes). A name with backslashes is a whole
     * Windows path from an old browser, of which only the file's own name is kept. ".." and "." are dropped; nothing can climb out.
     */
    static List<String> segments(String filename) {
        String f = filename.contains("\\") ? filename.substring(filename.lastIndexOf('\\') + 1) : filename;
        List<String> out = new ArrayList<>();
        for (String s : f.split("/")) {
            String own = s.replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_").strip();
            if (own.isEmpty() || own.equals(".") || own.equals("..") || own.matches("\\.+")) continue;
            out.add(own);
        }
        return out;
    }

    /** The path itself, or the same name with " (2)", " (3)"… before the extension when a file is there already. */
    static Path unused(Path f) {
        if (!Files.exists(f)) return f;
        String n = f.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; ; i++) { Path g = f.resolveSibling(stem + " (" + i + ")" + ext); if (!Files.exists(g)) return g; }
    }

    /** The files posted under one field name, in the order they came; empty when none. */
    public static List<Uploaded> files(Map<String, String> form, String field) {
        List<Uploaded> out = new ArrayList<>();
        for (int n = 1; ; n++) {
            String key = n == 1 ? field : field + "." + n;
            String path = form.get(key + ".path");
            if (path == null || path.isBlank()) break;
            out.add(new Uploaded(Path.of(path), form.getOrDefault(key + ".name", Path.of(path).getFileName().toString()), form.getOrDefault(key + ".rel", "")));
        }
        return out;
    }

    /**
     * A part's headers as text: browsers write a file's name in them as UTF-8 (a Japanese name arrived as mojibake while they were read
     * as ISO-8859-1); a header that is not UTF-8, from an old client, is read as ISO-8859-1.
     */
    static String headers(byte[] body, int start, int length) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try { return d.decode(ByteBuffer.wrap(body, start, length)).toString(); }
        catch (CharacterCodingException e) { return new String(body, start, length, StandardCharsets.ISO_8859_1); }
    }

    /**
     * A parameter of the part's Content-Disposition line: name="…" or filename="…" (the quote and the line breaks a browser writes as
     * %22, %0D and %0A given back), else filename*=UTF-8''… as RFC 5987 writes it; null when absent. "name" is not read out of "filename".
     */
    static String param(String headers, String key) {
        var mm = Pattern.compile("(?i)(?<![\\w*])" + key + "=\"([^\"]*)\"").matcher(headers);
        if (mm.find()) return mm.group(1).replace("%22", "\"").replace("%0D", "\r").replace("%0A", "\n");
        var ext = Pattern.compile("(?i)(?<![\\w*])" + key + "\\*=([A-Za-z0-9_-]+)'[^']*'([^;\\r\\n]+)").matcher(headers);
        if (ext.find()) { try { return URLDecoder.decode(ext.group(2).replace("+", "%2B"), Charset.forName(ext.group(1))); } catch (RuntimeException e) { return null; } }
        return null;
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** The folder this server made for the upload a path lies in, at any depth: named for an upload, directly under the system's temp folder. Null when the path is not inside one. */
    static Path uploadDirOf(Path file) {
        if (file == null) return null;
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        for (Path p = file.toAbsolutePath().normalize().getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().startsWith(DIR) && tmp.equals(p.getParent())) return p;
        }
        return null;
    }

    /** True for a file or a folder this server wrote from a browser's upload: inside a folder of its own, named for an upload, directly under the system's temp folder; nothing else on the machine qualifies. */
    public static boolean isUpload(Path file) { return uploadDirOf(file) != null; }

    /** Removes the folders the uploads were written to, once the page has read the files; only a folder this server made for an upload, and everything under it. */
    public static void drop(Map<String, String> form) {
        for (var e : form.entrySet()) {
            if (!e.getKey().endsWith(".path")) continue;
            try {
                Path dir = Path.of(e.getValue()).getParent();
                Path up = dir;
                while (up != null && (up.getFileName() == null || !up.getFileName().toString().startsWith(DIR))) up = up.getParent();
                if (up != null) remove(up);
            } catch (RuntimeException ignored) { /* a leftover temp folder costs nothing */ }
        }
    }

    /** Removes a folder and everything under it, following no link; nothing when it is not there. */
    static void remove(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }
        } catch (IOException ignored) { /* a leftover temp folder costs nothing */ }
    }

}
