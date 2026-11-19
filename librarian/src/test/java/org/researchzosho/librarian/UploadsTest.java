package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
/** A multipart form as a browser posts it: the text fields as they are, the file written under its own name, the folder dropped after. */
class UploadsTest {
    @Test void theFieldsAreReadAndTheFileIsWrittenUnderItsOwnName(@TempDir Path tmp) throws Exception {
        String b = "----WebKitFormBoundaryabc123";
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34, 0x0a, (byte) 0xff, 0x00, 0x0d, 0x0a, 0x2d, 0x2d};   // binary with CRLF and dashes inside
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"op\"\r\n\r\nprovide\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nSøren Example\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"C:\\\\Users\\\\me\\\\my resume (2026).pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(pdf);
        body.writeBytes(("\r\n--" + b + "\r\nContent-Disposition: form-data; name=\"empty\"; filename=\"\"\r\nContent-Type: application/octet-stream\r\n\r\n\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Path dir = Uploads.newFolder();   // a folder as the server makes one for a post
        Map<String, String> form = Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, dir);
        assertEquals("provide", form.get("op")); assertEquals("Søren Example", form.get("name"));
        assertEquals("my resume (2026).pdf", form.get("file.name"), "the file's own name, the folders stripped");
        Path f = Path.of(form.get("file.path"));
        assertEquals(dir, f.getParent()); assertTrue(Arrays.equals(pdf, Files.readAllBytes(f)), "the bytes as posted, CRLF and dashes included");
        assertFalse(form.containsKey("empty.path"), "a file box left empty gives nothing");
        Uploads.drop(form);
        assertFalse(Files.exists(dir), "the upload's folder is gone after the page read the file");
        assertFalse(Uploads.isUpload(f), "and the server no longer knows it as one of its own");
        Path keep = Files.createDirectories(tmp.resolve("mine")); Path k = Files.writeString(keep.resolve("a.txt"), "x");
        Uploads.drop(Map.of("file.path", k.toString()));
        assertTrue(Files.exists(k), "only a folder the server made for an upload is removed");
    }

    /** A file named in Japanese: browsers write the name as UTF-8 in the part's header, and it arrives as it was written, not as mojibake. */
    @Test void aFileNamedInJapaneseKeepsItsName(@TempDir Path tmp) throws Exception {
        String b = "----WebKitFormBoundaryjp1";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, b, "file", "時計の本 第1巻.pdf", "%PDF-1.4");
        part(body, b, "folder", "資料/古い時計/目録.txt", "目録");
        body.writeBytes(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, tmp.resolve("researchzosho-upload-jp"));
        assertEquals("時計の本 第1巻.pdf", form.get("file.name"));
        assertTrue(form.get("file.path").endsWith("時計の本 第1巻.pdf"), form.get("file.path"));
        assertEquals("資料/古い時計/目録.txt", form.get("folder.rel"));
        // an old client's name that is not UTF-8 is read as ISO-8859-1; a name given as filename*=UTF-8'' is decoded
        byte[] latin1 = "filename=\"caf\u00e9.pdf\"".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("caf\u00e9.pdf", Uploads.param(Uploads.headers(latin1, 0, latin1.length), "filename"));
        assertEquals("時計.pdf", Uploads.param("form-data; name=\"file\"; filename*=UTF-8''%E6%99%82%E8%A8%88.pdf", "filename"));
        assertEquals("file", Uploads.param("form-data; filename=\"a.pdf\"; name=\"file\"", "name"), "name is not read out of filename");
        Uploads.drop(form);
    }

    static void part(ByteArrayOutputStream body, String b, String field, String filename, String text) {
        body.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"" + (filename == null ? "" : "; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream") + "\r\n\r\n" + text + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test void severalFilesOfOneFieldAreEachWrittenAndAFolderChosenWholeKeepsItsPaths() throws Exception {
        String b = "----rz";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, b, "collection", null, "notes");
        part(body, b, "file", "a.txt", "first");
        part(body, b, "file", "b.txt", "second");
        part(body, b, "file", "a.txt", "third, of the same name");
        part(body, b, "folder", "thesis/sub/x.md", "deep");
        part(body, b, "folder", "thesis/y.txt", "shallow");
        part(body, b, "folder", "thesis/../../escape.txt", "climbs");
        part(body, b, "file.path", null, "/etc/hostname");   // a posted text field cannot pose as a file the server wrote
        part(body, b, "file.2.name", null, "posed.txt");
        body.writeBytes(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Path dir = Uploads.newFolder();
        try {
            Map<String, String> form = Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, dir);
            assertEquals("notes", form.get("collection"));
            var files = Uploads.files(form, "file");
            assertEquals(3, files.size(), form.toString());
            assertEquals("a.txt", files.get(0).name()); assertEquals("b.txt", files.get(1).name()); assertEquals("a (2).txt", files.get(2).name(), "a second file of one name keeps both");
            assertEquals("first", Files.readString(files.get(0).path())); assertEquals("third, of the same name", Files.readString(files.get(2).path()));
            assertEquals("", files.get(0).rel(), "a file chosen on its own has no path inside a folder");
            assertEquals(dir, files.get(0).path().getParent());
            assertEquals("b.txt", form.get("file.2.name"), "the parser's own key, not the posted field's");
            assertFalse(form.containsValue("/etc/hostname"), form.toString());
            var folder = Uploads.files(form, "folder");
            assertEquals(3, folder.size());
            assertEquals("thesis/sub/x.md", folder.get(0).rel()); assertEquals(dir.resolve("thesis/sub/x.md"), folder.get(0).path()); assertEquals("deep", Files.readString(folder.get(0).path()));
            assertEquals("thesis/y.txt", folder.get(1).rel()); assertEquals(dir.resolve("thesis/y.txt"), folder.get(1).path());
            assertEquals("thesis/escape.txt", folder.get(2).rel(), ".. is dropped; nothing climbs out"); assertEquals(dir.resolve("thesis/escape.txt"), folder.get(2).path());
            assertFalse(Files.exists(dir.getParent().resolve("escape.txt")));
            assertTrue(Uploads.isUpload(folder.get(0).path()) && Uploads.isUpload(dir.resolve("thesis")), "a file at any depth of an upload, and the folder chosen whole, are the upload's");
            assertFalse(Uploads.isUpload(dir.getParent().resolve("x.txt")));
            Uploads.drop(form);
            assertFalse(Files.exists(dir), "the whole upload, folders and all, is gone after the page read it");
        } finally { Uploads.remove(dir); }
    }

    /**
     * Only a file this server saved from an upload is a form's file: a path a form names anywhere else on the machine is not read back,
     * and a plain form, as a page without a file posts it, carries no field named like a file's at all (0.7.8).
     */
    @Test void onlyAFileThisServerSavedIsAFormsFileAndAPlainFormCarriesNoFieldNamedLikeOne(@TempDir Path tmp) throws Exception {
        Path elsewhere = Files.writeString(tmp.resolve("on the machine.txt"), "a file of the machine's");
        Path lookalike = Files.writeString(Files.createDirectories(tmp.resolve(Uploads.DIR + "x")).resolve("a.txt"), "in a folder named like an upload's, somewhere else");
        Path dir = Uploads.newFolder();
        try {
            Path saved = Files.writeString(dir.resolve("mine.txt"), "posted");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("file.path", elsewhere.toString()); form.put("file.name", "taken.txt");
            form.put("file.2.path", saved.toString()); form.put("file.2.name", "mine.txt");
            form.put("file.3.path", lookalike.toString()); form.put("file.3.name", "a.txt");
            var files = Uploads.files(form, "file");
            assertEquals(1, files.size(), files.toString());
            assertEquals(saved, files.get(0).path()); assertEquals("mine.txt", files.get(0).name());
            assertTrue(Uploads.files(Map.of("file.path", elsewhere.toString(), "file.name", "taken.txt"), "file").isEmpty(), "a path a form names is no file of the form's");
            Map<String, String> plain = Pages.form("to=fr&file.path=" + URLEncoder.encode(elsewhere.toString(), StandardCharsets.UTF_8) + "&file.name=taken.txt&folder.2.rel=a%2Fb&file.2.path=%2Fetc%2Fhostname&say=file.path", "application/x-www-form-urlencoded");
            assertEquals(Map.of("to", "fr", "say", "file.path"), plain, "the fields a page reads are kept; the ones named like a file's keys are not read");
        } finally { Uploads.remove(dir); }
    }

    /**
     * The server knows the folders it made for uploads; it does not recognise one by its name or by where it lies (0.8.0). A folder
     * somebody else made directly under the temporary folder, named as an upload's is, holds no file of a form's and is not removed
     * when a form names a path in it; nor is a folder of that name anywhere else. A post that brings no file, or is refused, leaves
     * no folder behind and nothing on the server's list.
     */
    @Test void theServerKnowsItsOwnUploadFoldersAndNoOtherByItsName(@TempDir Path tmp) throws Exception {
        Path others = Files.createTempDirectory(Uploads.DIR);   // not the server's: made beside its own, with the same kind of name
        Path elsewhere = Files.createDirectories(tmp.resolve(Uploads.DIR + "y"));
        try {
            Path theirs = Files.writeString(others.resolve("a.txt"), "somebody else's"), far = Files.writeString(elsewhere.resolve("b.txt"), "named alike, somewhere else");
            assertFalse(Uploads.isUpload(theirs), "a folder under the temporary folder with an upload's name is not the server's by that");
            assertFalse(Uploads.isUpload(far));
            assertTrue(Uploads.files(Map.of("file.path", theirs.toString(), "file.name", "a.txt"), "file").isEmpty(), "and no file in it is a form's");
            Uploads.drop(Map.of("file.path", theirs.toString(), "more.path", far.toString()));
            assertTrue(Files.exists(theirs) && Files.exists(far), "a path a form names is not removed: only a folder the server made for an upload is");
        } finally { Uploads.remove(others); }
        String b = "----rz";
        int before = Uploads.known();
        // a post with a text field and no file: nothing is kept
        Map<String, String> plain = Uploads.posted(new ByteArrayInputStream(("--" + b + "\r\nContent-Disposition: form-data; name=\"to\"\r\n\r\nfr\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8)), "multipart/form-data; boundary=" + b, 1 << 20, "too large");
        assertEquals(Map.of("to", "fr"), plain); assertEquals(before, Uploads.known(), "a post that brought no file leaves nothing on the list");
        // a post with a file: its folder is the server's own until the page has read it
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, b, "file", "notes.txt", "what was posted");
        body.writeBytes(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = Uploads.posted(new ByteArrayInputStream(body.toByteArray()), "multipart/form-data; boundary=" + b, 1 << 20, "too large");
        Path saved = Path.of(form.get("file.path"));
        assertTrue(Uploads.isUpload(saved) && Files.readString(saved).equals("what was posted")); assertEquals(before + 1, Uploads.known());
        assertEquals(1, Uploads.files(form, "file").size());
        Uploads.drop(form);
        assertFalse(Files.exists(saved.getParent())); assertFalse(Uploads.isUpload(saved)); assertEquals(before, Uploads.known());
        // a post over what the page takes: refused, and its folder is gone with it
        ProtocolError over = assertThrows(ProtocolError.class, () -> Uploads.posted(new ByteArrayInputStream(body.toByteArray()), "multipart/form-data; boundary=" + b, 40, "too large"));
        assertEquals("too large", over.getMessage()); assertEquals(before, Uploads.known(), "a refused post leaves nothing on the list");
    }

    @Test void moreFilesThanThePageTakesIsRefusedAndNothingIsLeftOnDisk() throws Exception {
        String b = "----rz";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i <= Uploads.MAX_FILES; i++) part(body, b, "file", "n" + i + ".txt", "x");
        body.writeBytes(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Path dir = Uploads.newFolder();
        try {
            ProtocolError e = assertThrows(ProtocolError.class, () -> Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, dir));
            assertTrue(e.getMessage().startsWith("More than 500 files in one go."), e.getMessage());
            assertFalse(Files.exists(dir), "what was written before the refusal is gone");
        } finally { Uploads.remove(dir); }
    }
}
