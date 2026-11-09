package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The language a person reads reports in. Set, every report written in another language opens translated into it by the model, the
 * first time a few minutes, and the translation is kept until the report changes; unset, a report is read as it was written. Kept per
 * person under catalog/read-in/: the person who keeps the library and those who write share one, a reader has their own, as the last look
 * at Updates is kept. The language the library WRITES its reports in is the setting {@code language}, in {@link Settings}.
 */
public final class ReadIn {
    private ReadIn() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** Where the person's language is kept, or null for a visitor the library cannot tell from the next. */
    static Path file(LibraryStore store, Patrons.Patron patron) {
        String key = Patrons.mayWrite(store, patron) ? "keeper" : patron == null || patron.did().isEmpty() ? "" : Patrons.sha256(patron.did()).substring(0, 16);
        return key.isEmpty() ? null : store.root().resolve("catalog").resolve("read-in").resolve(key + ".json");
    }

    /** The code of the language the person reads reports in ("ja"), or "" when none is set. */
    public static String of(LibraryStore store, Patrons.Patron patron) {
        Path f = file(store, patron);
        if (f == null || !Files.isRegularFile(f)) return "";
        try { return Translate.code(M.readTree(Files.readString(f, StandardCharsets.UTF_8)).path("language").asText("")); } catch (IOException e) { return ""; }
    }

    /** Sets the person's language, or unsets it with a blank; what was done, in a sentence for them. */
    public static String set(LibraryStore store, Patrons.Patron patron, String language) throws IOException {
        Path f = file(store, patron);
        if (f == null) throw ProtocolError.forbidden("A language to read in is kept for a person the library knows: sign in, or name yourself.");
        String code = language == null ? "" : Translate.code(language);
        if (code.isEmpty()) { Files.deleteIfExists(f); return "You read every report as it was written now."; }
        Files.createDirectories(f.getParent());
        ObjectNode j = M.createObjectNode(); j.put("language", code);
        AtomicWrite.text(f, j.toString() + "\n");
        return "You read every report in " + name(code) + " now. One written in another language opens translated by the model, the first time after a few minutes; the cites and the sources stand as they are.";
    }

    /** A language's name for a person, from its code or its name: ja → Japanese; one the library has no name for, as given. */
    public static String name(String language) {
        String c = Translate.code(language);
        return Translate.LANGUAGES.containsKey(c) ? Translate.LANGUAGES.get(c) : c.length() > 2 ? Character.toUpperCase(c.charAt(0)) + c.substring(1) : c;
    }
}
