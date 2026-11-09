package org.researchzosho.librarian;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * An archive of documents (zip, tar, tar.gz, tgz) added as a folder is: unpacked into a folder of the library's own, shelved through the
 * corpus path under one collection, and the folder removed after. An archive is told by its name, never by its bytes: a Word file and an
 * EPUB are zips inside, and they are documents.
 *
 * <p>What is inside an archive is untrusted. An entry whose path would land outside the folder (absolute, a drive letter, a {@code ..}
 * anywhere in it) is skipped and counted, as is a link; an archive inside the archive is not opened; the unpacking stops at
 * {@link #MAX_ENTRIES} entries or {@link #MAX_BYTES} unpacked, in one sentence, and then nothing is added. An entry's name is shown in a
 * sentence with its control characters replaced and cut short, and is never run or resolved as a path of this machine.
 */
public final class Archives {
    private Archives() { }

    /** The most entries one archive may hold; a folder of papers has hundreds, a zip bomb has millions. */
    public static final int MAX_ENTRIES = 5_000;
    /** The most an archive may unpack to, in bytes; a folder of papers compresses little, a zip bomb unpacks without end. */
    public static final long MAX_BYTES = 512L << 20;
    public static final String DIR = "researchzosho-unpack-";

    /** True for a name the library opens as an archive: .zip, .tar, .tar.gz, .tgz. */
    public static boolean isArchive(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz");
    }

    /** An archive's name without its ending: "things" from things.tar.gz. */
    public static String stem(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : new String[]{".tar.gz", ".tgz", ".tar", ".zip"}) if (n.endsWith(e)) return name.substring(0, name.length() - e.length());
        return name;
    }

    /** What came out of an archive: the files written, their bytes, the entries left out and why, and the sentence when the unpacking stopped at a cap ("" when it did not). */
    public record Unpacked(int files, long bytes, List<String> leftOut, String stopped) { }

    /** An archive added: its collection, what the unpacking found, and the corpus path's outcome (null when the unpacking stopped and nothing was added). */
    public record Added(String archive, String collection, Unpacked unpacked, Corpus.Outcome outcome) {
        /** One line a person reads: the counts, the collection, and how many entries were left out. */
        public String line() {
            if (outcome == null) return "Not added: " + unpacked.stopped() + " Split the archive, or unpack it yourself and add the folder at the command line on the machine that runs the library.";
            return "From " + archive + ": " + outcome.line() + ", under the collection " + collection
                    + (unpacked.leftOut().isEmpty() ? "" : "; " + unpacked.leftOut().size() + (unpacked.leftOut().size() == 1 ? " entry of the archive was" : " entries of the archive were") + " left out") + ".";
        }
        /** Every entry left out at the unpacking and every document skipped at the shelving, each with why. */
        public List<String> problems() {
            List<String> out = new ArrayList<>(unpacked.leftOut());
            if (outcome != null) out.addAll(outcome.problems());
            return out;
        }
    }

    /**
     * Unpack an archive into a folder of the library's own and shelve what came out as the folder's documents, under {@code collection}
     * (the archive's own name when blank). The folder is removed after, whatever happened. The archive itself is never changed.
     */
    public static Added add(LibraryStore store, Path archive, String collection) throws IOException {
        String name = archive.getFileName().toString();
        List<String> safe = Uploads.segments(stem(name));
        String stem = safe.isEmpty() ? "archive" : safe.get(safe.size() - 1);
        Path tmp = Files.createTempDirectory(DIR);
        try {
            Path root = Files.createDirectories(tmp.resolve(stem));
            Unpacked u = unpack(archive, root);
            String coll = collection == null || collection.isBlank() ? Corpus.nameFor(root) : collection.strip();
            if (!u.stopped().isEmpty()) return new Added(name, coll, u, null);
            return new Added(name, coll, u, Corpus.addFolder(store, root, coll, true, false));
        } finally { Uploads.remove(tmp); }
    }

    /** Unpack an archive, told by its name, into {@code root}; what is written is what {@link Unpacked} says, and no more. */
    public static Unpacked unpack(Path archive, Path root) throws IOException { return unpack(archive, root, MAX_ENTRIES, MAX_BYTES); }

    /** The same with the caps given: a test stands in a small cap for the real one, which no test should have to fill. */
    static Unpacked unpack(Path archive, Path root, int maxEntries, long maxBytes) throws IOException {
        String n = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        Sink sink = new Sink(root, maxEntries, maxBytes);
        if (n.endsWith(".zip")) return zip(archive, sink);
        if (n.endsWith(".tar")) return tar(new BufferedInputStream(Files.newInputStream(archive)), sink);
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return tar(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive))), sink);
        throw new IOException(n + " is not an archive the library opens (zip, tar, tar.gz, tgz)");
    }

    // ---- the folder the entries land in, and what is refused ----

    /** Where the entries are written; every rule about an entry is here, whichever kind of archive it came from. */
    static final class Sink {
        final Path root, real;
        final int maxEntries; final long maxBytes;
        int entries, files; long bytes;
        final List<String> leftOut = new ArrayList<>();
        String stopped = "";

        Sink(Path root, int maxEntries, long maxBytes) throws IOException { this.root = root; this.real = root.toRealPath(); this.maxEntries = maxEntries; this.maxBytes = maxBytes; }

        /** An entry's name as a sentence may carry it: control characters replaced, cut short, in quotes. */
        static String shown(String name) {
            String s = name == null ? "" : name.replaceAll("\\p{Cntrl}", "?");
            if (s.length() > 120) s = s.substring(0, 119) + "…";
            return "\"" + s + "\"";
        }

        /**
         * The parts of an entry's path inside the folder, each made safe, or null when the path would land outside it: absolute, a drive
         * letter, a {@code ..} anywhere. Empty parts and {@code .} are dropped.
         */
        static List<String> safe(String name) {
            String f = name == null ? "" : name.replace('\\', '/');
            if (f.startsWith("/") || f.matches("^[A-Za-z]:.*")) return null;
            List<String> out = new ArrayList<>();
            for (String s : f.split("/")) {
                if (s.isEmpty() || s.equals(".")) continue;
                if (s.equals("..")) return null;
                String own = s.replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_").strip();
                if (own.isEmpty() || own.matches("\\.+")) return null;
                out.add(own);
            }
            return out.isEmpty() ? null : out;
        }

        /**
         * One entry: {@code kind} is 'f' for a file, 'd' for a folder, 'l' for a link of any sort; {@code data} is the file's bytes, read to
         * the end. False when the unpacking must stop, with {@link #stopped} saying why.
         */
        boolean take(String name, char kind, InputStream data) throws IOException {
            if (++entries > maxEntries) { stopped = "Unpacking stopped at " + String.format(Locale.ROOT, "%,d", maxEntries) + " entries: the library takes at most that many from one archive."; return false; }
            String shown = shown(name);
            List<String> segs = safe(name);
            if (segs == null) { leftOut.add(shown + ": its path points outside the folder, skipped"); return true; }
            if (kind == 'l') { leftOut.add(shown + ": a link, skipped"); return true; }
            Path target = root;
            for (String s : segs) target = target.resolve(s);
            try {
                if (kind == 'd') { Files.createDirectories(target); return true; }
                if (isArchive(segs.get(segs.size() - 1))) { leftOut.add(shown + ": an archive inside the archive, not opened"); return true; }
                Files.createDirectories(target.getParent());
                if (!target.getParent().toRealPath().startsWith(real)) { leftOut.add(shown + ": its path points outside the folder, skipped"); return true; }
                if (Files.isDirectory(target)) { leftOut.add(shown + ": a file named as a folder is, skipped"); return true; }
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = data.read(buf)) > 0) {
                        bytes += r;
                        if (bytes > maxBytes) { stopped = "Unpacking stopped at " + Corpus.human(maxBytes) + " unpacked: the library takes at most that much from one archive."; return false; }
                        out.write(buf, 0, r);
                    }
                }
                files++;
            } catch (IOException e) { leftOut.add(shown + ": could not be written (" + e.getClass().getSimpleName() + ")"); }
            return true;
        }

        Unpacked done() { return new Unpacked(files, bytes, List.copyOf(leftOut), stopped); }
    }

    // ---- zip ----

    static Unpacked zip(Path archive, Sink sink) throws IOException {
        int read = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                read++;
                if (!sink.take(e.getName(), e.isDirectory() ? 'd' : 'f', z)) break;
                z.closeEntry();
            }
        } catch (ZipException x) {
            if (read == 0) throw new IOException("not a zip the library can open: " + x.getMessage());
            sink.leftOut.add("the rest of the archive could not be read (" + x.getMessage() + ")");
        }
        if (read == 0 && sink.stopped.isEmpty()) throw new IOException("not a zip the library can open, or an empty one");
        return sink.done();
    }

    // ---- tar, plain or gzipped: ustar headers, GNU long names, pax paths ----

    static Unpacked tar(InputStream in0, Sink sink) throws IOException {
        int read = 0;
        try (InputStream in = in0) {
            byte[] h = new byte[512];
            String longName = null, paxPath = null;
            long paxSize = -1;
            while (fill(in, h)) {
                if (allZero(h)) break;
                read++;
                long size = size(h);
                if (size < 0) { if (read == 1) throw new IOException("not a tar the library can open"); sink.leftOut.add("the rest of the archive could not be read (a header is not a tar header)"); break; }
                char type = h[156] == 0 ? '0' : (char) h[156];
                String name = str(h, 0, 100);
                String prefix = str(h, 257, 6).startsWith("ustar") ? str(h, 345, 155) : "";
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (paxPath != null) { name = paxPath; paxPath = null; } else if (longName != null) { name = longName; longName = null; }
                if (paxSize >= 0) { size = paxSize; paxSize = -1; }
                boolean on = true;
                switch (type) {
                    case 'L' -> longName = readString(in, size);   // GNU: the next entry's name
                    case 'x' -> { for (String[] kv : pax(readString(in, size))) { if (kv[0].equals("path")) paxPath = kv[1]; else if (kv[0].equals("size")) { try { paxSize = Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { } } } }
                    case '5' -> { on = sink.take(name, 'd', InputStream.nullInputStream()); in.skipNBytes(size); }
                    case '0', '7' -> { Limited data = new Limited(in, size); on = sink.take(name, 'f', data); if (on) data.drain(); }
                    case '1', '2' -> { on = sink.take(name, 'l', InputStream.nullInputStream()); in.skipNBytes(size); }
                    default -> in.skipNBytes(size);   // 'K' (a long link name), 'g' (global pax), devices, fifos
                }
                if (!on) break;
                in.skipNBytes((512 - size % 512) % 512);
            }
        } catch (EOFException e) { sink.leftOut.add("the archive ends before its last entry does"); }
        if (read == 0 && sink.stopped.isEmpty()) throw new IOException("not a tar the library can open, or an empty one");
        return sink.done();
    }

    /** True when the block was filled; false at a clean end of the stream. */
    static boolean fill(InputStream in, byte[] h) throws IOException {
        int n = in.readNBytes(h, 0, h.length);
        if (n == 0) return false;
        if (n < h.length) throw new EOFException();
        return true;
    }

    static boolean allZero(byte[] h) { for (byte b : h) if (b != 0) return false; return true; }

    static String str(byte[] h, int at, int len) {
        int end = at;
        while (end < at + len && h[end] != 0) end++;
        return new String(h, at, end - at, StandardCharsets.UTF_8);
    }

    /** The size field: octal, or GNU's base-256 when its first bit is set; -1 when it is neither. */
    static long size(byte[] h) {
        if ((h[124] & 0x80) != 0) { long v = 0; for (int i = 125; i < 136; i++) v = (v << 8) | (h[i] & 0xff); return v; }
        String s = str(h, 124, 12).strip();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return -1; }
    }

    static String readString(InputStream in, long size) throws IOException {
        if (size > 1 << 20) throw new IOException("a header longer than a megabyte");
        byte[] b = in.readNBytes((int) size);
        if (b.length < size) throw new EOFException();
        int end = b.length; while (end > 0 && b[end - 1] == 0) end--;
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    /** The records of a pax header, "len key=value\n" each: {key, value}. */
    static List<String[]> pax(String s) {
        List<String[]> out = new ArrayList<>();
        int pos = 0;
        while (pos < s.length()) {
            int sp = s.indexOf(' ', pos); if (sp < 0) break;
            int len; try { len = Integer.parseInt(s.substring(pos, sp)); } catch (NumberFormatException e) { break; }
            if (len <= 0 || pos + len > s.length()) break;
            String rec = s.substring(sp + 1, pos + len);
            int eq = rec.indexOf('=');
            if (eq > 0) out.add(new String[]{rec.substring(0, eq), rec.substring(eq + 1).replaceAll("\n$", "")});
            pos += len;
        }
        return out;
    }

    /** A stream that ends after {@code size} bytes of the one under it, which it never closes. */
    static final class Limited extends InputStream {
        private final InputStream in; private long left;
        Limited(InputStream in, long size) { this.in = in; this.left = size; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = in.read(); if (b < 0) throw new EOFException(); left--; return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException();
            left -= n; return n;
        }
        /** Reads past whatever of the entry was not taken. */
        void drain() throws IOException { if (left > 0) { in.skipNBytes(left); left = 0; } }
    }
}
