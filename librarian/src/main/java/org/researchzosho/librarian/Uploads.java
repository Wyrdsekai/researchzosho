package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** A file posted with a form in the browser: read into a folder of its own for the one request, and dropped once the page has read it. */
public final class Uploads {
    private Uploads() { }

    /** The largest upload a page takes, in bytes: a book-length PDF fits; a disk image does not. */
    public static final int MAX = 64 << 20;
    public static final String DIR = "researchzosho-upload-";

    /**
     * A multipart form, as a browser posts one with a file in it: the text fields as they are, and each file written under {@code dir}
     * with its own name (the last part of it, odd characters replaced), named in the map as {@code <field>.path} and {@code <field>.name}.
     * {@code dir} is a folder made for this one request; the page that takes the file removes it with {@link #drop(Map)}.
     */
    public static Map<String, String> multipart(byte[] body, String contentType, Path dir) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        int bi = contentType == null ? -1 : contentType.indexOf("boundary=");
        if (bi < 0) return m;
        String boundary = contentType.substring(bi + 9).trim();
        int semi = boundary.indexOf(';'); if (semi > 0) boundary = boundary.substring(0, semi).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() > 1) boundary = boundary.substring(1, boundary.length() - 1);
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1), close = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), blank = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int start = pos + delim.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') break;   // the closing delimiter
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') start += 2;
            int hdrEnd = indexOf(body, blank, start); if (hdrEnd < 0) break;
            String headers = new String(body, start, hdrEnd - start, StandardCharsets.ISO_8859_1);
            int dataStart = hdrEnd + 4, next = indexOf(body, close, dataStart); if (next < 0) break;
            String name = param(headers, "name"), filename = param(headers, "filename");
            if (name != null) {
                if (filename != null) {
                    String own = filename.replaceAll("^.*[\\\\/]", "").replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_").strip();
                    if (!own.isEmpty() && next > dataStart) {
                        Files.createDirectories(dir);
                        Path f = dir.resolve(own);
                        Files.write(f, Arrays.copyOfRange(body, dataStart, next));
                        m.put(name + ".path", f.toString()); m.put(name + ".name", own);
                    }
                } else m.put(name, new String(body, dataStart, next - dataStart, StandardCharsets.UTF_8));
            }
            pos = next + 2;
        }
        return m;
    }

    /** A parameter of the part's Content-Disposition line: name="…" or filename="…"; null when absent. */
    static String param(String headers, String key) {
        var mm = Pattern.compile("(?i)" + key + "=\"([^\"]*)\"").matcher(headers);
        return mm.find() ? mm.group(1) : null;
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** True for a file this server wrote from a browser's upload: in a folder of its own, named for an upload, directly under the system's temp folder; nothing else on the machine qualifies. */
    public static boolean isUpload(Path file) {
        Path dir = file == null ? null : file.toAbsolutePath().normalize().getParent();
        if (dir == null || dir.getFileName() == null || !dir.getFileName().toString().startsWith(DIR)) return false;
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        return tmp.equals(dir.getParent());
    }

    /** Removes the folder an upload was written to, once the page has read the file; only a folder this server made for an upload. */
    public static void drop(Map<String, String> form) {
        for (var e : form.entrySet()) {
            if (!e.getKey().endsWith(".path")) continue;
            try {
                Path dir = Path.of(e.getValue()).getParent();
                if (dir != null && dir.getFileName() != null && dir.getFileName().toString().startsWith(DIR)) {
                    try (var s = Files.list(dir)) { for (Path f : s.toList()) Files.deleteIfExists(f); }
                    Files.deleteIfExists(dir);
                }
            } catch (IOException | RuntimeException ignored) { /* a leftover temp folder costs nothing */ }
        }
    }

}
