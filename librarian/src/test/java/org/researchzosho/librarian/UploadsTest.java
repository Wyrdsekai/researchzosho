package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Path dir = tmp.resolve("researchzosho-upload-x");
        Map<String, String> form = Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, dir);
        assertEquals("provide", form.get("op")); assertEquals("Søren Example", form.get("name"));
        assertEquals("my resume (2026).pdf", form.get("file.name"), "the file's own name, the folders stripped");
        Path f = Path.of(form.get("file.path"));
        assertEquals(dir, f.getParent()); assertTrue(Arrays.equals(pdf, Files.readAllBytes(f)), "the bytes as posted, CRLF and dashes included");
        assertFalse(form.containsKey("empty.path"), "a file box left empty gives nothing");
        Uploads.drop(form);
        assertFalse(Files.exists(dir), "the upload's folder is gone after the page read the file");
        Path keep = Files.createDirectories(tmp.resolve("mine")); Path k = Files.writeString(keep.resolve("a.txt"), "x");
        Uploads.drop(Map.of("file.path", k.toString()));
        assertTrue(Files.exists(k), "only a folder the server made for an upload is removed");
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
        Path dir = Files.createTempDirectory(Uploads.DIR);
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

    @Test void moreFilesThanThePageTakesIsRefusedAndNothingIsLeftOnDisk() throws Exception {
        String b = "----rz";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i <= Uploads.MAX_FILES; i++) part(body, b, "file", "n" + i + ".txt", "x");
        body.writeBytes(("--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Path dir = Files.createTempDirectory(Uploads.DIR);
        try {
            ProtocolError e = assertThrows(ProtocolError.class, () -> Uploads.multipart(body.toByteArray(), "multipart/form-data; boundary=" + b, dir));
            assertTrue(e.getMessage().startsWith("More than 500 files in one go."), e.getMessage());
            assertFalse(Files.exists(dir), "what was written before the refusal is gone");
        } finally { Uploads.remove(dir); }
    }
}
