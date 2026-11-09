package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An archive of documents added as a folder is: each kind unpacks, an entry that would land outside the folder is skipped and counted, a
 * link and an archive inside the archive are not opened, a bomb stops early in one sentence, and the folder is removed after.
 */
class ArchivesTest {

    // ---- archives built in memory: no file of the box, no tar program ----

    /** A zip of the entries in order; a name ending in "/" is a folder. */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); if (e.getValue() != null) z.write(e.getValue()); z.closeEntry(); }
        }
        return out.toByteArray();
    }

    /** One tar entry: its name, its bytes (null for a folder or a link), and its type: '0' a file, '5' a folder, '2' a symbolic link, '1' a hard link. */
    record TarEntry(String name, byte[] bytes, char type, String linkTo) {
        static TarEntry file(String name, String text) { return new TarEntry(name, text.getBytes(StandardCharsets.UTF_8), '0', ""); }
        static TarEntry dir(String name) { return new TarEntry(name, null, '5', ""); }
        static TarEntry link(String name, String to) { return new TarEntry(name, null, '2', to); }
        static TarEntry hard(String name, String to) { return new TarEntry(name, null, '1', to); }
    }

    /** A ustar archive of the entries, as tar writes one: 512-byte headers, the data padded, two empty blocks at the end. */
    static byte[] tar(List<TarEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (TarEntry e : entries) {
            byte[] h = new byte[512];
            put(h, 0, e.name()); put(h, 100, "0000644"); put(h, 108, "0000000"); put(h, 116, "0000000");
            int size = e.bytes() == null ? 0 : e.bytes().length;
            put(h, 124, String.format("%011o", size)); put(h, 136, "00000000000");
            Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = (byte) e.type();
            put(h, 157, e.linkTo()); put(h, 257, "ustar"); h[263] = '0'; h[264] = '0';
            int sum = 0; for (byte b : h) sum += b & 0xff;
            put(h, 148, String.format("%06o", sum)); h[154] = 0; h[155] = ' ';
            out.writeBytes(h);
            if (size > 0) { out.writeBytes(e.bytes()); out.writeBytes(new byte[(512 - size % 512) % 512]); }
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static void put(byte[] h, int at, String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); System.arraycopy(b, 0, h, at, Math.min(b.length, 100)); }

    static byte[] gz(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) { g.write(plain); }
        return out.toByteArray();
    }

    static final String A = "# On gears\n\nThe Antikythera gears were cut by hand with files; the teeth are triangular.\n";
    static final String B = "The front dial shows the zodiac and the Egyptian calendar; the back dials show the Metonic and Saros cycles.\n";

    /** A folder with a folder inside it, as each kind of archive: things/a.md, things/sub/b.txt. */
    static Map<String, byte[]> plain() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("things/", null); m.put("things/a.md", A.getBytes(StandardCharsets.UTF_8)); m.put("things/sub/", null); m.put("things/sub/b.txt", B.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    static List<TarEntry> plainTar() { return List.of(TarEntry.dir("things/"), TarEntry.file("things/a.md", A), TarEntry.dir("things/sub/"), TarEntry.file("things/sub/b.txt", B)); }

    /** The zip the brief names: a folder with a nested folder, one entry that climbs out, one absolute, and an archive inside. */
    static byte[] trickyZip() throws IOException {
        Map<String, byte[]> m = plain();
        m.put("../evil.txt", "This file must never land beside the folder.\n".getBytes(StandardCharsets.UTF_8));
        m.put("/abs.txt", "Nor this one at the root.\n".getBytes(StandardCharsets.UTF_8));
        m.put("things/inner.zip", zip(Map.of("x.txt", "inside".getBytes(StandardCharsets.UTF_8))));
        return zip(m);
    }

    static List<Path> under(Path dir) throws IOException { try (Stream<Path> s = Files.walk(dir)) { return s.filter(Files::isRegularFile).sorted().toList(); } }

    // ---- the tests ----

    @Test void eachKindOfArchiveUnpacksAFolderWithAFolderInside(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> kinds = new LinkedHashMap<>();
        kinds.put("things.zip", zip(plain())); kinds.put("things.tar", tar(plainTar())); kinds.put("things.tgz", gz(tar(plainTar()))); kinds.put("things.tar.gz", gz(tar(plainTar())));
        for (var k : kinds.entrySet()) {
            assertTrue(Archives.isArchive(k.getKey()), k.getKey());
            assertEquals("things", Archives.stem(k.getKey()));
            Path archive = Files.write(tmp.resolve(k.getKey()), k.getValue());
            Path root = Files.createDirectories(tmp.resolve("out-" + k.getKey()));
            Archives.Unpacked u = Archives.unpack(archive, root);
            assertEquals(2, u.files(), k.getKey() + ": " + u);
            assertEquals(List.of(), u.leftOut(), k.getKey()); assertEquals("", u.stopped());
            assertEquals(A, Files.readString(root.resolve("things/a.md")), k.getKey());
            assertEquals(B, Files.readString(root.resolve("things/sub/b.txt")), k.getKey());
            assertEquals(A.length() + B.length(), u.bytes());
        }
        assertFalse(Archives.isArchive("report.docx"), "a Word file is a zip inside, and a document"); assertFalse(Archives.isArchive("book.epub")); assertFalse(Archives.isArchive("notes.gz"));
    }

    @Test void anEntryThatClimbsOutAnAbsoluteOneALinkAndAnArchiveInsideAreSkippedAndCounted(@TempDir Path tmp) throws Exception {
        Path archive = Files.write(tmp.resolve("things.zip"), trickyZip());
        Path root = Files.createDirectories(tmp.resolve("out"));
        Archives.Unpacked u = Archives.unpack(archive, root);
        assertEquals(2, u.files(), u.toString());
        assertEquals(List.of(root.resolve("things/a.md"), root.resolve("things/sub/b.txt")), under(root), "only the folder's own documents are on disk");
        assertFalse(Files.exists(tmp.resolve("evil.txt")) || Files.exists(Path.of("/abs.txt")) || Files.exists(root.resolve("abs.txt")), "nothing landed outside the folder");
        assertEquals(3, u.leftOut().size(), u.leftOut().toString());
        assertTrue(u.leftOut().get(0).equals("\"../evil.txt\": its path points outside the folder, skipped"), u.leftOut().get(0));
        assertTrue(u.leftOut().get(1).equals("\"/abs.txt\": its path points outside the folder, skipped"), u.leftOut().get(1));
        assertTrue(u.leftOut().get(2).equals("\"things/inner.zip\": an archive inside the archive, not opened"), u.leftOut().get(2));
        // a tar's links, of both kinds, and a Windows path
        Path t = Files.write(tmp.resolve("links.tar"), tar(List.of(TarEntry.file("ok/good.txt", B), TarEntry.link("ok/link.txt", "/etc/hostname"), TarEntry.hard("ok/hard.txt", "ok/good.txt"), TarEntry.file("C:\\Users\\x.txt", A), TarEntry.file("ok/../../up.txt", A))));
        Path r2 = Files.createDirectories(tmp.resolve("out2"));
        Archives.Unpacked u2 = Archives.unpack(t, r2);
        assertEquals(1, u2.files(), u2.toString());
        assertEquals(List.of(r2.resolve("ok/good.txt")), under(r2));
        assertFalse(Files.isSymbolicLink(r2.resolve("ok/link.txt")) || Files.exists(r2.resolve("ok/link.txt")), "no link is ever made");
        assertEquals(List.of("\"ok/link.txt\": a link, skipped", "\"ok/hard.txt\": a link, skipped", "\"C:\\Users\\x.txt\": its path points outside the folder, skipped", "\"ok/../../up.txt\": its path points outside the folder, skipped"), u2.leftOut());
        // an entry's name is untrusted text: shown with its control characters replaced, and cut short
        Path t3 = Files.write(tmp.resolve("odd.zip"), zip(Map.of("../x\u001b[31m\n.txt", "a".getBytes(StandardCharsets.UTF_8), "../" + "y".repeat(300), "b".getBytes(StandardCharsets.UTF_8))));
        Archives.Unpacked u3 = Archives.unpack(t3, Files.createDirectories(tmp.resolve("out3")));
        assertEquals(0, u3.files());
        for (String line : u3.leftOut()) { assertFalse(line.contains("\u001b") || line.contains("\n"), line); assertTrue(line.length() < 170, line); }
    }

    @Test void aBombStopsEarlyInOneSentenceAndThenNothingIsAdded(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) many.put("many/" + i + ".txt", ("note " + i + "\n").getBytes(StandardCharsets.UTF_8));
        Path archive = Files.write(tmp.resolve("many.zip"), zip(many));
        Archives.Unpacked u = Archives.unpack(archive, Files.createDirectories(tmp.resolve("out")), 10, Archives.MAX_BYTES);
        assertEquals("Unpacking stopped at 10 entries: the library takes at most that many from one archive.", u.stopped());
        assertEquals(10, u.files(), "what came before the cap is on disk, and no more");
        Map<String, byte[]> big = new LinkedHashMap<>();
        big.put("big/zeros.txt", new byte[3000]); big.put("big/more.txt", new byte[3000]);
        Path b = Files.write(tmp.resolve("big.tgz"), gz(tar(List.of(TarEntry.file("big/zeros.txt", "0".repeat(3000)), TarEntry.file("big/more.txt", "0".repeat(3000))))));
        Archives.Unpacked u2 = Archives.unpack(b, Files.createDirectories(tmp.resolve("out2")), Archives.MAX_ENTRIES, 4000);
        assertEquals("Unpacking stopped at 4 KB unpacked: the library takes at most that much from one archive.", u2.stopped());
        assertTrue(u2.bytes() > 4000 && u2.bytes() <= 6000, "stopped as soon as the cap was passed: " + u2.bytes());
        // the real caps are the documented ones
        assertEquals(5_000, Archives.MAX_ENTRIES); assertEquals(512L << 20, Archives.MAX_BYTES);
        // through the add, the archive is refused in one sentence and nothing is on the shelves
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path bomb = Files.write(tmp.resolve("bomb.zip"), zip(many));
        Archives.Added ad = addWith(store, bomb, 10);
        assertTrue(ad.outcome() == null && ad.line().startsWith("Not added: Unpacking stopped at 10 entries"), ad.line());
        assertTrue(ad.line().contains("Split the archive, or unpack it yourself and add the folder at the command line"), ad.line());
        assertEquals(0, raws(store).size(), "nothing went onto the shelves");
    }

    /** {@link Archives#add} with a small entry cap, by the same steps. */
    static Archives.Added addWith(LibraryStore store, Path archive, int maxEntries) throws IOException {
        Path tmp = Files.createTempDirectory(Archives.DIR);
        try {
            Path root = Files.createDirectories(tmp.resolve(Archives.stem(archive.getFileName().toString())));
            Archives.Unpacked u = Archives.unpack(archive, root, maxEntries, Archives.MAX_BYTES);
            String coll = Corpus.nameFor(root);
            if (!u.stopped().isEmpty()) return new Archives.Added(archive.getFileName().toString(), coll, u, null);
            return new Archives.Added(archive.getFileName().toString(), coll, u, Corpus.addFolder(store, root, coll, true, false));
        } finally { Uploads.remove(tmp); }
    }

    /** The captures on the shelves. */
    static List<Path> raws(LibraryStore store) throws IOException {
        if (!Files.isDirectory(store.rawDir())) return List.of();
        try (Stream<Path> s = Files.list(store.rawDir())) { return s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList(); }
    }

    static List<Path> unpackFolders() throws IOException {
        Path t = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> s = Files.list(t)) { return s.filter(p -> p.getFileName().toString().startsWith(Archives.DIR)).toList(); }
    }

    @Test void anArchiveAddedIsShelvedUnderACollectionNamedForItAndItsFolderIsRemoved(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path archive = Files.write(tmp.resolve("Antikythera things.zip"), trickyZip());
        List<Path> before = unpackFolders();
        Archives.Added ad = Archives.add(store, archive, "");
        assertEquals(before, unpackFolders(), "the folder the archive was unpacked into is gone");
        assertEquals("antikythera-things", ad.collection(), "named for the archive");
        assertNotNull(ad.outcome());
        assertEquals(2, ad.outcome().added()); assertEquals(0, ad.outcome().skipped());
        assertEquals("From Antikythera things.zip: 2 added, 0 unchanged, 0 skipped of 2 document(s), under the collection antikythera-things; 3 entries of the archive were left out.", ad.line());
        assertEquals(3, ad.problems().size());
        List<Path> raws = raws(store);
        assertEquals(2, raws.size());
        for (Path r : raws) assertEquals("antikythera-things", RawCapture.collectionOf(r));
        assertFalse(new LibrarianIndex(store, Embeddings.none()).searchIn("Saros Metonic back dials", 5, null, "antikythera-things").isEmpty(), "found inside the collection");
        assertTrue(Files.exists(archive), "the archive itself is never touched");
        // a collection given wins, and a second add of the same archive adds nothing twice under the same name only by text, since the folder is new each time
        Archives.Added again = Archives.add(store, archive, "gears");
        assertEquals("gears", again.collection());
        // the archive's own name is untrusted too: the folder it unpacks into is named safely
        Path odd = Files.write(tmp.resolve("..things.zip"), zip(plain()));
        assertEquals("things", Archives.add(store, odd, "").collection());
    }

    @Test void theCommandLineAddsAnArchiveAndSaysWhatCameOfIt(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        Path archive = Files.write(home.resolve("things.zip"), trickyZip());
        List<Path> before = unpackFolders();
        String out = cli(home, "add", archive.toString());
        assertEquals(before, unpackFolders(), "no folder of this run is left");
        assertTrue(out.startsWith("From things.zip: 2 added, 0 unchanged, 0 skipped of 2 document(s), under the collection things; 3 entries of the archive were left out.\n"), out);
        assertTrue(out.contains("  \"../evil.txt\": its path points outside the folder, skipped\n") && out.contains("  \"things/inner.zip\": an archive inside the archive, not opened\n"), out);
        assertTrue(out.contains("ask them: researchzosho research ask"), out);
        assertEquals(2, raws(store).size());
        String coll = cli(home, "add", archive.toString(), "--collection", "mine");
        assertTrue(coll.contains("under the collection mine"), coll);
    }

    /** The command line as a person runs it, the library under a home of this test's own. */
    static String cli(Path home, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            List<String> args = new ArrayList<>(List.of("researchzosho")); args.addAll(List.of(words));
            LibrarianCli.run(args.toArray(new String[0]), "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void whatIsNotAnArchiveIsSaidSo(@TempDir Path tmp) throws Exception {
        Path notZip = Files.writeString(tmp.resolve("notes.zip"), "just words, not a zip");
        IOException e = assertThrows(IOException.class, () -> Archives.unpack(notZip, Files.createDirectories(tmp.resolve("o1"))));
        assertTrue(e.getMessage().startsWith("not a zip the library can open"), e.getMessage());
        Path notTar = Files.writeString(tmp.resolve("notes.tar"), "x".repeat(600));
        IOException e2 = assertThrows(IOException.class, () -> Archives.unpack(notTar, Files.createDirectories(tmp.resolve("o2"))));
        assertTrue(e2.getMessage().startsWith("not a tar the library can open"), e2.getMessage());
    }
}
