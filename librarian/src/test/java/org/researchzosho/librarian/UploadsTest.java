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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A multipart form as a browser posts it: the text fields as they are, the file written under its own name, the folder dropped after. */
class UploadsTest {
    @Test void theFieldsAreReadAndTheFileIsWrittenUnderItsOwnName(@TempDir Path tmp) throws Exception {
        String b = "----WebKitFormBoundaryabc123";
        byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34, 0x0a, (byte) 0xff, 0x00, 0x0d, 0x0a, 0x2d, 0x2d};   // binary with CRLF and dashes inside
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
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
}
