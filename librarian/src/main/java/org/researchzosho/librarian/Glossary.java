package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The library's glossary for translations: a term and the rendering chosen for it in a language, kept across documents in
 * catalog/glossary.json, read on every translation of a document, grown by the person (researchzosho glossary add, a line in the chat,
 * the protocol). A term in the glossary is rendered as the glossary says wherever it occurs; the translation note says which entries
 * applied. The names and recurring terms a model fixes for one document are not kept here: they are the document's own.
 */
public final class Glossary {
    private Glossary() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** One entry: the term as it stands in a source, the language code, the rendering in it, the day it was added. */
    public record Entry(String term, String lang, String rendering, String added) {
        public String line() { return line(Chrome.ENGLISH); }
        public String line(Chrome c) { return term + " → " + rendering + " (" + c.languageName(lang) + ")"; }
    }

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("glossary.json"); }

    /** Every entry, in the order added. */
    public static List<Entry> list(LibraryStore store) {
        List<Entry> out = new ArrayList<>();
        Path f = file(store);
        if (!Files.isRegularFile(f)) return out;
        try {
            JsonNode j = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
            for (JsonNode e : j.path("entries")) {
                String term = e.path("term").asText("").strip(), lang = e.path("lang").asText("").strip(), rendering = e.path("rendering").asText("").strip();
                if (!term.isEmpty() && !lang.isEmpty() && !rendering.isEmpty()) out.add(new Entry(term, lang, rendering, e.path("added").asText("")));
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** The entries for one language: term → rendering. */
    public static Map<String, String> forLanguage(LibraryStore store, String lang) {
        String code = Translate.code(lang);
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry e : list(store)) if (e.lang().equals(code)) out.put(e.term(), e.rendering());
        return out;
    }

    /** Adds an entry, or replaces the one for the same term and language; what was done, in a sentence. */
    public static String add(LibraryStore store, String term, String lang, String rendering) throws IOException { return add(store, term, lang, rendering, Chrome.ENGLISH); }

    /** The same, what was done said in {@code c}'s language. */
    public static String add(LibraryStore store, String term, String lang, String rendering, Chrome c) throws IOException {
        String t = term == null ? "" : term.strip(), code = Translate.code(lang), r = rendering == null ? "" : rendering.strip();
        if (t.isEmpty() || code.isEmpty() || r.isEmpty()) throw ProtocolError.invalidArgs("A glossary entry is a term, a language and its rendering: researchzosho glossary add \"Acme Corp\" ja \"アクメ社\".");
        List<Entry> all = list(store);
        boolean replaced = all.removeIf(e -> e.term().equals(t) && e.lang().equals(code));
        all.add(new Entry(t, code, r, LocalDate.now().toString()));
        write(store, all);
        String name = c.english() ? Translate.LANGUAGES.getOrDefault(code, code) : c.languageName(code);
        return (replaced ? c.t("The glossary now renders \"{0}\" as \"{1}\" in {2} (it had another rendering before).", t, r, name) : c.t("The glossary renders \"{0}\" as \"{1}\" in {2} from now on.", t, r, name)) + " " + c.t("Every translation into {0} uses it.", name);
    }

    /** Removes the entry for a term and a language; false when there was none. */
    public static boolean remove(LibraryStore store, String term, String lang) throws IOException {
        String t = term == null ? "" : term.strip(), code = Translate.code(lang);
        List<Entry> all = list(store);
        boolean removed = all.removeIf(e -> e.term().equals(t) && e.lang().equals(code));
        if (removed) write(store, all);
        return removed;
    }

    private static void write(LibraryStore store, List<Entry> all) throws IOException {
        ObjectNode j = M.createObjectNode();
        ArrayNode a = j.putArray("entries");
        for (Entry e : all) a.addObject().put("term", e.term()).put("lang", e.lang()).put("rendering", e.rendering()).put("added", e.added());
        Files.createDirectories(file(store).getParent());
        AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
    }

    /** The entries, one a line, for a person; a sentence when there are none. */
    public static String describe(LibraryStore store, String lang) { return describe(store, lang, Chrome.ENGLISH); }

    /** The same, in {@code c}'s language. */
    public static String describe(LibraryStore store, String lang, Chrome c) {
        List<Entry> all = list(store);
        String code = lang == null || lang.isBlank() ? "" : Translate.code(lang);
        List<String> lines = new ArrayList<>();
        for (Entry e : all) if (code.isEmpty() || e.lang().equals(code)) lines.add(e.line(c));
        if (lines.isEmpty()) return code.isEmpty() ? c.t("The glossary is empty. researchzosho glossary add \"<term>\" <language> \"<rendering>\" fixes how a term is rendered in every translation.") : c.t("The glossary has no entry for {0}.", c.english() ? Translate.LANGUAGES.getOrDefault(code, code) : c.languageName(code));
        return c.t(lines.size() == 1 ? "The glossary ({0} entry):" : "The glossary ({0} entries):", lines.size()) + "\n" + String.join("\n", lines);
    }
}
