package org.researchzosho.librarian;

import java.io.IOException;
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
        Map<String, String> m = new LinkedHashMap<>();
        int bi = contentType == null ? -1 : contentType.indexOf("boundary=");
        if (bi < 0) return m;
        String boundary = contentType.substring(bi + 9).trim();
        int semi = boundary.indexOf(';'); if (semi > 0) boundary = boundary.substring(0, semi).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) boundary = boundary.substring(1, boundary.length() - 1);
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1), close = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), blank = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Integer> count = new LinkedHashMap<>();
        int files = 0;
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int start = pos + delim.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break;   // the closing delimiter
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;
            int hdrEnd = indexOf(body, blank, start); if (hdrEnd < 0) break;
            String headers = headers(body, start, hdrEnd - start);
            int dataStart = hdrEnd + 4, next = indexOf(body, close, dataStart); if (next < 0) break;
            String name = param(headers, "name"), filename = param(headers, "filename");
            if (name != null) {
                if (filename != null) {
                    List<String> segs = segments(filename);
                    if (!segs.isEmpty() && next > dataStart) {
                        if (++files > MAX_FILES) { remove(dir); throw ProtocolError.invalidArgs("More than " + MAX_FILES + " files in one go. The page takes up to " + MAX_FILES + " at once; a bigger folder goes in at the command line on the machine that runs the library, or as an archive."); }
                        Path f = dir;
                        for (String s : segs) f = f.resolve(s);
                        Files.createDirectories(f.getParent());
                        f = unused(f);
                        Files.write(f, Arrays.copyOfRange(body, dataStart, next));
                        int n = count.merge(name, 1, Integer::sum);
                        String key = n == 1 ? name : name + "." + n;
                        m.put(key + ".path", f.toString()); m.put(key + ".name", f.getFileName().toString());
                        if (segs.size() > 1) m.put(key + ".rel", String.join("/", segs));
                    }
                } else if (!FILE_KEY.matcher(name).matches()) m.put(name, new String(body, dataStart, next - dataStart, StandardCharsets.UTF_8));
            }
            pos = next + 2;
        }
        return m;
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
