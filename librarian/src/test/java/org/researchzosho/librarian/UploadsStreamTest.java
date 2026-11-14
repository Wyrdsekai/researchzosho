package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A form with files read as it arrives (0.7.5): a file's part goes straight to its file, the bytes are counted as they pass, and a post
 * over its limit is refused with nothing left on disk. Before, the whole post was read into memory first, which is why a page took 64 MB
 * and no more; the Translate form takes a comic's volume, several hundred megabytes.
 */
class UploadsStreamTest {
    static final String B = "----rz-stream";
    static final String TYPE = "multipart/form-data; boundary=" + B;

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] fieldHead(String name) { return ascii("--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"); }
    static byte[] fileHead(String name, String filename) { return ascii("--" + B + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n"); }

    /** A stream of {@code n} bytes made as they are read, none of them kept: byte k is a function of k, so the file written can be checked without holding it. */
    static final class Made extends InputStream {
        private final long n; private long at;
        final CRC32 crc = new CRC32();
        Made(long n) { this.n = n; }
        static byte of(long k) { return (byte) ((k * 31 + (k >>> 9)) & 0xff); }
        @Override public int read() { if (at >= n) return -1; byte b = of(at++); crc.update(b); return b & 0xff; }
        @Override public int read(byte[] buf, int off, int len) {
            if (at >= n) return -1;
            int m = (int) Math.min(len, n - at);
            for (int i = 0; i < m; i++) buf[off + i] = of(at + i);
            crc.update(buf, off, m); at += m;
            return m;
        }
    }

    /** One stream after another, a few bytes at a read when {@code drip} is set: a marker then falls across two reads. */
    static InputStream post(boolean drip, InputStream... parts) {
        InputStream all = new SequenceInputStream(Collections.enumeration(List.of(parts)));
        if (!drip) return all;
        return new InputStream() {
            @Override public int read() throws IOException { return all.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { return all.read(b, off, Math.min(len, 7)); }
        };
    }

    static InputStream bytes(byte[] b) { return new ByteArrayInputStream(b); }

    @Test void theSameFormIsReadTheSameWayWhateverTheSizeOfEachRead(@TempDir Path tmp) throws Exception {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) data[i] = Made.of(i);
        // a file whose bytes hold what looks like the beginning of the boundary, and line ends: none of it ends the part
        byte[] tricky = ascii("line one\r\n--" + B.substring(0, 8) + "\r\n\r\n--not the boundary\r\n-");
        for (boolean drip : new boolean[]{false, true}) {
            Path dir = tmp.resolve("researchzosho-upload-" + drip);
            Map<String, String> form = Uploads.multipart(post(drip, bytes(fieldHead("to")), bytes(ascii("English\r\n")), bytes(fileHead("file", "volume.cbz")), bytes(data), bytes(ascii("\r\n")),
                    bytes(fileHead("file", "notes.txt")), bytes(tricky), bytes(ascii("\r\n")), bytes(fileHead("file", "empty.txt")), bytes(ascii("\r\n")),
                    bytes(fieldHead("direction")), bytes(ascii("terse, urgent — 緊迫\r\n")), bytes(ascii("--" + B + "--\r\n"))), TYPE, dir, 1 << 20, "over");
            assertEquals("English", form.get("to")); assertEquals("terse, urgent — 緊迫", form.get("direction"), "a field after the files is read too, in UTF-8");
            List<Uploads.Uploaded> files = Uploads.files(form, "file");
            assertEquals(2, files.size(), "a file's part with nothing in it is no file: " + form);
            assertEquals("volume.cbz", files.get(0).name()); assertArrayEquals(data, Files.readAllBytes(files.get(0).path()));
            assertArrayEquals(tricky, Files.readAllBytes(files.get(1).path()), "what looks like the boundary inside a file stays in the file");
        }
        // the form read from bytes in memory, as every test before this one gives it, is the same reader
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        whole.writeBytes(fieldHead("to")); whole.writeBytes(ascii("English\r\n")); whole.writeBytes(fileHead("file", "a.txt")); whole.writeBytes(ascii("hello\r\n")); whole.writeBytes(ascii("--" + B + "--\r\n"));
        Map<String, String> form = Uploads.multipart(whole.toByteArray(), TYPE, tmp.resolve("researchzosho-upload-bytes"));
        assertEquals("English", form.get("to")); assertEquals("hello", Files.readString(Uploads.files(form, "file").get(0).path()));
        // a post that ends inside a file keeps nothing of that file
        Path cut = tmp.resolve("researchzosho-upload-cut");
        Map<String, String> ended = Uploads.multipart(post(false, bytes(fieldHead("to")), bytes(ascii("English\r\n")), bytes(fileHead("file", "half.cbz")), bytes(data)), TYPE, cut, 1 << 20, "over");
        assertEquals("English", ended.get("to")); assertTrue(Uploads.files(ended, "file").isEmpty()); assertFalse(Files.exists(cut.resolve("half.cbz")));
    }

    @Test void aPostOverItsLimitIsRefusedAsTheBytesPassAndNothingIsLeftOnDisk(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("researchzosho-upload-over"));
        Made big = new Made(3 << 20);
        InputStream rest = post(false, bytes(fieldHead("to")), bytes(ascii("English\r\n")), bytes(fileHead("file", "volume.cbz")), big, bytes(ascii("\r\n--" + B + "--\r\n")));
        ProtocolError e = assertThrows(ProtocolError.class, () -> Uploads.multipart(rest, TYPE, dir, 1 << 20, "The file is larger than the Translate form takes, 1 MB."));
        assertEquals("The file is larger than the Translate form takes, 1 MB.", e.getMessage()); assertEquals("invalid_args", e.code);
        assertFalse(Files.exists(dir), "what was written before the refusal is gone");
        assertEquals(-1, rest.read(), "the rest of the post was read and dropped, so the browser is done sending and shows the answer");
        // the limit is the setting's, 512 MB when it is unset, and the refusal names it and the command line
        assertEquals(512L << 20, Uploads.translateLimit());
        assertTrue(Uploads.translateOverSaid().startsWith("The file is larger than the Translate form takes, 512 MB. Translate it at the command line") && Uploads.translateOverSaid().contains("researchzosho settings set translate_upload_mb"), Uploads.translateOverSaid());
        assertEquals(700_000_000L, Uploads.declared(" 700000000 ")); assertEquals(-1, Uploads.declared(null)); assertEquals(-1, Uploads.declared("chunked"));
        // a text field as long as a file is not held in memory either
        Path field = Files.createDirectories(tmp.resolve("researchzosho-upload-field"));
        assertThrows(ProtocolError.class, () -> Uploads.multipart(post(false, bytes(fieldHead("words")), new Made(Uploads.MAX_FIELD + 10L), bytes(ascii("\r\n--" + B + "--\r\n"))), TYPE, field, Long.MAX_VALUE, "over"));
    }

    /** The post is larger than the whole heap of the Java that reads it: 160 MB through a reader with 48 MB. */
    @Test void aFileLargerThanTheReadersWholeMemoryIsWrittenStraightToDisk(@TempDir Path tmp) throws Exception {
        long size = 160L << 20;
        Path dir = Files.createDirectories(tmp.resolve("researchzosho-upload-big")), said = tmp.resolve("said.txt");
        Set<String> cp = new LinkedHashSet<>();
        for (Class<?> c : List.of(UploadsStreamTest.class, Uploads.class)) cp.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        for (String jar : ComicStandIn.jsonClassPath().split(File.pathSeparator)) cp.add(jar);
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-Xmx48m", "-cp", String.join(File.pathSeparator, cp), Big.class.getName(), dir.toString(), String.valueOf(size)));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(said.toFile()).start();
        assertTrue(p.waitFor(180, TimeUnit.SECONDS), "the reader ended");
        String out = Files.readString(said).strip();
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.endsWith("to=English size=" + size + " same=true after=yes"), "the file is whole on disk, the fields before and after it are read, in a Java whose memory is under a third of it: " + out);
    }

    /** The reader in a Java of its own: a post with one file of the size asked, made as it is read; says what came of it in one line. */
    static final class Big {
        public static void main(String[] args) throws Exception {
            long size = Long.parseLong(args[1]);
            Made made = new Made(size);
            Map<String, String> form = Uploads.multipart(post(false, bytes(fieldHead("to")), bytes(ascii("English\r\n")), bytes(fileHead("file", "volume.cbz")), made, bytes(ascii("\r\n")),
                    bytes(fieldHead("after")), bytes(ascii("yes\r\n")), bytes(ascii("--" + B + "--\r\n"))), TYPE, Path.of(args[0]), Long.MAX_VALUE, "");
            Path file = Path.of(form.get("file.path"));
            CRC32 crc = new CRC32();
            byte[] buf = new byte[1 << 16];
            try (InputStream in = Files.newInputStream(file)) { for (int n; (n = in.read(buf)) >= 0; ) crc.update(buf, 0, n); }
            System.out.println("heap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB to=" + form.get("to") + " size=" + Files.size(file) + " same=" + (crc.getValue() == made.crc.getValue()) + " after=" + form.get("after"));
        }
    }
}
