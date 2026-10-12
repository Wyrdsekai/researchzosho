package org.researchzosho.librarian;

import org.apache.commons.codec.binary.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * As much of a mail reader as the library needs: a message's headers with their encoded words decoded, its plain text and
 * its HTML, and the messages of a Thunderbird or Unix mailbox file one at a time. Nothing is kept beyond the message in hand,
 * so a mailbox of several gigabytes is read in a few megabytes of memory.
 */
final class Mime {
    private Mime() { }

    /** One message: the headers that matter (lower-case names), its plain text and its HTML, either possibly empty. */
    record Message(Map<String, String> headers, String text, String html) {
        String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");
    private static final Pattern BETWEEN_WORDS = Pattern.compile("\\?=\\s+=\\?");

    /** An RFC 2047 header: {@code =?UTF-8?B?...?=} and {@code =?ISO-2022-JP?Q?...?=} words become text; anything else stays. */
    static String decodeHeader(String raw) {
        if (raw == null || !raw.contains("=?")) return raw == null ? "" : raw;
        String s = BETWEEN_WORDS.matcher(raw).replaceAll("?==?");   // whitespace between two encoded words is not part of the text
        Matcher m = ENCODED_WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String cs = m.group(1), kind = m.group(2).toUpperCase(Locale.ROOT), body = m.group(3);
            int star = cs.indexOf('*'); if (star > 0) cs = cs.substring(0, star);   // a language tag after the charset
            byte[] bytes;
            if (kind.equals("B")) bytes = Base64.decodeBase64(body);
            else bytes = quotedPrintable(body.replace('_', ' ').getBytes(StandardCharsets.US_ASCII));
            m.appendReplacement(out, Matcher.quoteReplacement(new String(bytes, charset(cs))));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Charset charset(String name) {
        if (name == null || name.isBlank()) return StandardCharsets.UTF_8;
        String n = name.strip().replace("\"", "");
        try { return Charset.forName(n); } catch (Exception e) { }
        String l = n.toLowerCase(Locale.ROOT);
        if (l.contains("jis")) { try { return Charset.forName("ISO-2022-JP"); } catch (Exception e) { } }
        if (l.contains("sjis") || l.contains("shift")) { try { return Charset.forName("Shift_JIS"); } catch (Exception e) { } }
        if (l.startsWith("utf")) return StandardCharsets.UTF_8;
        return StandardCharsets.ISO_8859_1;
    }

    /** The message in these bytes: headers, then the first plain-text part and the first HTML part, decoded. */
    static Message parse(byte[] raw) {
        int split = headerEnd(raw);
        Map<String, String> headers = headers(new String(raw, 0, split, StandardCharsets.ISO_8859_1));
        byte[] body = split < raw.length ? java.util.Arrays.copyOfRange(raw, skipBlank(raw, split), raw.length) : new byte[0];
        String[] found = {"", ""};
        part(headers.getOrDefault("content-type", "text/plain"), headers.getOrDefault("content-transfer-encoding", ""), body, found, 0);
        return new Message(headers, found[0], found[1]);
    }

    private static int headerEnd(byte[] raw) {
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n' && i + 1 < raw.length && raw[i + 1] == '\n') return i + 1;
            if (raw[i] == '\n' && i + 2 < raw.length && raw[i + 1] == '\r' && raw[i + 2] == '\n') return i + 1;
        }
        return raw.length;
    }

    private static int skipBlank(byte[] raw, int at) {
        while (at < raw.length && (raw[at] == '\n' || raw[at] == '\r')) at++;
        return at;
    }

    /** Folded header lines joined, names lower-cased, encoded words decoded; the first value of a repeated name wins. */
    static Map<String, String> headers(String head) {
        Map<String, String> out = new LinkedHashMap<>();
        String name = null; StringBuilder value = new StringBuilder();
        for (String line : head.split("\r?\n")) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) { value.append(' ').append(line.strip()); continue; }
            if (name != null) out.putIfAbsent(name, decodeHeader(value.toString().strip()));
            name = null; value.setLength(0);
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            value.append(line.substring(colon + 1));
        }
        if (name != null) out.putIfAbsent(name, decodeHeader(value.toString().strip()));
        return out;
    }

    private static void part(String contentType, String encoding, byte[] body, String[] found, int depth) {
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/") && depth < 8) {
            String boundary = param(contentType, "boundary");
            if (boundary.isEmpty()) return;
            for (byte[] piece : pieces(body, boundary)) {
                int split = headerEnd(piece);
                Map<String, String> h = headers(new String(piece, 0, split, StandardCharsets.ISO_8859_1));
                byte[] inner = split < piece.length ? java.util.Arrays.copyOfRange(piece, skipBlank(piece, split), piece.length) : new byte[0];
                part(h.getOrDefault("content-type", "text/plain"), h.getOrDefault("content-transfer-encoding", ""), inner, found, depth + 1);
            }
            return;
        }
        if (!type.equals("text/plain") && !type.equals("text/html")) return;
        int slot = type.equals("text/plain") ? 0 : 1;
        if (!found[slot].isEmpty()) return;
        byte[] decoded = decode(body, encoding);
        found[slot] = new String(decoded, charset(param(contentType, "charset")));
    }

    static String param(String contentType, String name) {
        Matcher m = Pattern.compile("(?i)[;\\s]" + name + "\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))").matcher(contentType);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(3)) : "";
    }

    private static byte[] decode(byte[] body, String encoding) {
        String e = encoding.strip().toLowerCase(Locale.ROOT);
        try {
            if (e.equals("base64")) return Base64.decodeBase64(new String(body, StandardCharsets.US_ASCII));
            if (e.equals("quoted-printable")) return quotedPrintable(body);
        } catch (Exception ignored) { }
        return body;
    }

    /**
     * Quoted-printable, decoded as the mail format means it: {@code =XX} is a byte, {@code =} at a line's end is a soft break
     * and goes with its line end, everything else stays — line ends included. (commons-codec's decoder is the strict one,
     * which drops bare line ends and so folds a whole mail into one line.)
     */
    static byte[] quotedPrintable(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        for (int i = 0; i < body.length; i++) {
            byte b = body[i];
            if (b != '=') { out.write(b); continue; }
            if (i + 1 < body.length && body[i + 1] == '\n') { i += 1; continue; }
            if (i + 2 < body.length && body[i + 1] == '\r' && body[i + 2] == '\n') { i += 2; continue; }
            if (i + 2 < body.length) {
                int hi = Character.digit(body[i + 1], 16), lo = Character.digit(body[i + 2], 16);
                if (hi >= 0 && lo >= 0) { out.write((hi << 4) + lo); i += 2; continue; }
            }
            out.write(b);   // a stray '=': kept as it is
        }
        return out.toByteArray();
    }

    private static List<byte[]> pieces(byte[] body, String boundary) {
        List<byte[]> out = new ArrayList<>();
        byte[] mark = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int start = -1;
        for (int i = 0; i <= body.length - mark.length; i++) {
            if ((i == 0 || body[i - 1] == '\n') && matches(body, i, mark)) {
                if (start >= 0) out.add(java.util.Arrays.copyOfRange(body, start, i > 0 && body[i - 1] == '\n' ? (i > 1 && body[i - 2] == '\r' ? i - 2 : i - 1) : i));
                int lineEnd = i + mark.length;
                boolean last = lineEnd + 1 < body.length && body[lineEnd] == '-' && body[lineEnd + 1] == '-';
                while (lineEnd < body.length && body[lineEnd] != '\n') lineEnd++;
                start = Math.min(lineEnd + 1, body.length);
                i = lineEnd;
                if (last) { start = -1; break; }
            }
        }
        return out;
    }

    private static boolean matches(byte[] a, int at, byte[] mark) {
        for (int j = 0; j < mark.length; j++) if (a[at + j] != mark[j]) return false;
        return true;
    }

    /** HTML as lines of text: table rows and block elements end a line, cells are separated by tabs, scripts and styles are dropped. */
    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(tr|p|div|li|h[1-6]|table|ul|ol)>", "\n").replaceAll("(?i)</t[dh]>", "\t");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&yen;", "¥");
        s = s.replaceAll("&#(\\d+);", "").replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) { String t = line.strip(); if (!t.isEmpty()) out.append(t).append('\n'); }
        return out.toString();
    }

    /** True for a Unix or Thunderbird mailbox file: it starts with a {@code From } separator line. */
    static boolean isMbox(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(5);
            return head.length == 5 && new String(head, StandardCharsets.ISO_8859_1).equals("From ");
        } catch (IOException e) { return false; }
    }

    /** Each message of a mailbox file in turn, as its raw bytes; the file is streamed, never held whole. */
    static void mbox(Path file, Consumer<byte[]> each) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            ByteArrayOutputStream current = null;
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int b;
            while (true) {
                b = in.read();
                if (b >= 0) line.write(b);
                if (b == '\n' || b < 0) {
                    byte[] l = line.toByteArray(); line.reset();
                    if (l.length >= 5 && l[0] == 'F' && l[1] == 'r' && l[2] == 'o' && l[3] == 'm' && l[4] == ' ') {
                        if (current != null && current.size() > 0) each.accept(current.toByteArray());
                        current = new ByteArrayOutputStream(4096);
                    } else if (current != null) {
                        current.write(l, 0, l.length);
                    }
                    if (b < 0) break;
                }
            }
            if (current != null && current.size() > 0) each.accept(current.toByteArray());
        }
    }
}
