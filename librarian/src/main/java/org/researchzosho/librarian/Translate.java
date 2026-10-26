package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.drive.Declined;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dossier read in another language. The pieces of prose the dossier page and the printout are built from — the reports' sections,
 * the claims' titles, the analysis, the open questions — are collected by running both builders with a collecting {@link Words}, translated
 * by the model (short lines in numbered batches, long texts whole, all with a glossary of the recurring terms fixed once), checked piece by
 * piece so that every cite, claim id and address stands as it was, a piece whose translation would lose one kept in the original, and the
 * map is kept in dossiers/&lt;slug&gt;.&lt;lang&gt;.json with the hash of the pieces it was made from, made again when the dossier has changed.
 * The page and the printout are then built by the same code with the map: the same look, the words translated. The claims themselves
 * keep their own language on the shelves: a claim quotes its source.
 */
public final class Translate {
    private Translate() { }

    private static final ObjectMapper M = new ObjectMapper();
    /** The words a page or a printout is built with: each piece of prose passes through it. The identity builds the original. */
    @FunctionalInterface public interface Words { String tr(String text); }
    public static final Words AS_IS = s -> s;
    /** The library's model; a test puts a stand-in here. */
    static Supplier<Researcher.Drive> DRIVE = Explain::drive;
    /** The languages the page offers by name; the command takes any two-letter code. */
    public static final Map<String, String> LANGUAGES = Map.of("en", "English", "ja", "Japanese", "zh", "Chinese", "ko", "Korean", "de", "German", "fr", "French", "es", "Spanish", "pt", "Portuguese", "it", "Italian", "ru", "Russian");
    private static final Pattern CITE = Pattern.compile("\\[\\d+\\]"), CLAIM_ID = Pattern.compile("F-\\d{4}[0-9A-Za-z-]*"), URL = Pattern.compile("https?://\\S+");
    /** A section is sent in pieces this long at most, so an answer fits a model's slot. */
    static final int PIECE = 6000;

    public record Translated(String subject, String lang, String markdown, boolean fresh, int pieces, int kept, String from, int glossary, Map<String, String> words) {
        public String summary() {
            String name = LANGUAGES.getOrDefault(lang, lang);
            return "The dossier of " + subject + " in " + name + (fresh ? ": " + pieces + (pieces == 1 ? " piece" : " pieces") + " translated" + (glossary == 0 ? "" : " with a glossary of " + glossary + (glossary == 1 ? " term" : " terms") + " fixed once") + (kept == 0 ? "" : ", " + kept + " kept in the original (a cite, a claim id or an address would have been lost)") : ", as translated on " + from + "; the dossier has not changed since") + ".";
        }
        /** The words the page and the printout are built with: each piece as translated, or as it was. */
        public Words lookup() { return s -> words.getOrDefault(s, s); }
    }

    /** A language as a person names it: "English", "en", "日本語", "Japanese" → en, ja; anything else lower-cased as given. */
    public static String code(String s) {
        String t = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        for (var e : LANGUAGES.entrySet()) if (t.equals(e.getKey()) || t.equals(e.getValue().toLowerCase(Locale.ROOT))) return e.getKey();
        return switch (t) { case "日本語", "jp" -> "ja"; case "中文", "chinese" -> "zh"; case "한국어" -> "ko"; case "deutsch" -> "de"; case "français" -> "fr"; case "español" -> "es"; case "português" -> "pt"; case "italiano" -> "it"; case "русский" -> "ru"; case "英語" -> "en"; default -> t; };
    }

    /** The language a text is written in, by its letters: kana make it Japanese, hangul Korean, han alone Chinese, anything else English. */
    public static String languageOf(String text) {
        if (text == null) return "en";
        int letters = 0, kana = 0, han = 0, hangul = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(c);
            if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA) kana++;
            else if (sc == Character.UnicodeScript.HAN) han++;
            else if (sc == Character.UnicodeScript.HANGUL) hangul++;
        }
        if (letters == 0) return "en";
        if ((kana + han) * 5 > letters) return kana > 0 ? "ja" : "zh";
        if (hangul * 5 > letters) return "ko";
        return "en";
    }

    static String slug(String subject) { return subject.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", ""); }

    /** Where a translation is kept: dossiers/&lt;slug&gt;.&lt;lang&gt;.json under the library. */
    public static Path file(LibraryStore store, String subject, String lang) { return store.root().resolve("dossiers").resolve(slug(subject) + "." + code(lang) + ".json"); }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte x : h) b.append(String.format("%02x", x)); return b.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The pieces of prose the page and the printout are built from, in order, each once: what a translation translates. */
    static List<String> collect(LibraryStore store, String subject) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        Words c = t -> { if (t != null && !t.isBlank()) seen.add(t); return t; };
        DossierPage.render(store, subject, c, null);
        Dossier.of(store, subject, c);
        return new ArrayList<>(seen);
    }

    static String hashOf(List<String> pieces) { return sha256(String.join("\n\u0001", pieces)); }

    /** The translation kept for the dossier as it stands now, or null when there is none or the dossier has changed since. */
    public static Translated current(LibraryStore store, String subject, String lang) throws IOException {
        String label = Dossier.of(store, subject).path("subject").asText(subject);
        return current(store, label, code(lang), collect(store, label));
    }

    private static Translated current(LibraryStore store, String label, String lang, List<String> pieces) throws IOException {
        Path f = file(store, label, lang);
        if (!Files.exists(f)) return null;
        JsonNode j; try { j = M.readTree(Files.readString(f, StandardCharsets.UTF_8)); } catch (IOException e) { return null; }
        if (!j.path("from").asText("").equals(hashOf(pieces))) return null;
        Map<String, String> words = new LinkedHashMap<>();
        j.path("words").fields().forEachRemaining(e -> words.put(e.getKey(), e.getValue().asText()));
        String markdown = Dossier.of(store, label, t -> words.getOrDefault(t, t)).path("markdown").asText();
        return new Translated(label, lang, markdown, false, 0, 0, j.path("day").asText(""), j.path("glossary").size(), words);
    }

    /** The dossier in {@code lang}: the translation kept when the dossier has not changed, else made now by {@code drive} and kept. */
    public static Translated dossierIn(LibraryStore store, String subject, String lang, Researcher.Drive drive) throws IOException {
        String code = code(lang);
        ObjectNode d = Dossier.of(store, subject);
        String label = d.path("subject").asText(subject);
        if (!d.path("found").asBoolean(false)) throw ProtocolError.notFound("The library holds nothing on " + subject + " yet. A research run starts it: researchzosho research ask \"Who is " + subject + "?\"");
        List<String> pieces = collect(store, label);
        Translated kept = current(store, label, code, pieces);
        if (kept != null) return kept;
        if (drive == null) throw ProtocolError.invalidArgs("Reading the dossier in " + LANGUAGES.getOrDefault(code, code) + " needs a model to translate it, and none is answering. Set the drive, then ask again.");
        String language = LANGUAGES.getOrDefault(code, code);
        Map<String, String> glossary = glossary(drive, terms(String.join("\n", pieces)), language, label);   // the recurring terms, rendered once, the same in every piece
        Map<String, String> words = new LinkedHashMap<>();
        int keptOriginal = 0;
        List<String> shortOnes = new ArrayList<>();
        for (String piece : pieces) {
            if (piece.length() > 400 || piece.contains("\n")) {   // a text: whole, in pieces of a slot's size
                StringBuilder t = new StringBuilder(); boolean whole = true;
                for (String part : pieces(piece)) { String tr = translate(drive, part, language, label, glossary); if (tr == null || tr.isBlank() || !keeps(part, tr)) { whole = false; break; } t.append(tr.strip()).append("\n\n"); }
                if (whole) words.put(piece, t.toString().strip()); else keptOriginal++;
            } else shortOnes.add(piece);
        }
        for (List<String> batch : batches(shortOnes)) {   // the lines: numbered, a batch a call
            List<String> out = translateLines(drive, batch, language, label, glossary);
            for (int i = 0; i < batch.size(); i++) { String tr = out.get(i); if (tr == null || tr.isBlank() || !keeps(batch.get(i), tr)) keptOriginal++; else words.put(batch.get(i), tr); }
        }
        String today = LocalDate.now().toString();
        ObjectNode j = M.createObjectNode();
        j.put("subject", label); j.put("lang", code); j.put("day", today); j.put("from", hashOf(pieces));
        ObjectNode g = j.putObject("glossary"); for (var e : glossary.entrySet()) g.put(e.getKey(), e.getValue());
        ObjectNode w = j.putObject("words"); for (var e : words.entrySet()) w.put(e.getKey(), e.getValue());
        Path f = file(store, label, code);
        Files.createDirectories(f.getParent());
        AtomicWrite.text(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        String markdown = Dossier.of(store, label, t -> words.getOrDefault(t, t)).path("markdown").asText();
        return new Translated(label, code, markdown, true, pieces.size(), keptOriginal, today, glossary.size(), words);
    }

    /** The short pieces in batches of at most twenty-five lines and four thousand characters. */
    static List<List<String>> batches(List<String> lines) {
        List<List<String>> out = new ArrayList<>(); List<String> cur = new ArrayList<>(); int chars = 0;
        for (String l : lines) {
            if (!cur.isEmpty() && (cur.size() >= 25 || chars + l.length() > 4000)) { out.add(cur); cur = new ArrayList<>(); chars = 0; }
            cur.add(l); chars += l.length();
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    /** The lines translated as numbered lines, one call: the translation of each by its number, or null where the model gave none. */
    static List<String> translateLines(Researcher.Drive drive, List<String> lines, String language, String subject, Map<String, String> glossary) {
        List<String> out = new ArrayList<>(); for (int i = 0; i < lines.size(); i++) out.add(null);
        StringBuilder u = new StringBuilder("LINES:\n"); for (int i = 0; i < lines.size(); i++) u.append(i + 1).append(". ").append(lines.get(i)).append('\n');
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You translate the numbered lines of a library's dossier of " + subject + " into " + language + ": a claim's title, a question, a line of a list. "
                + "Answer with the same numbers, one line each, the translation after the number, in order, and nothing else. Keep exactly as they stand: every citation in square brackets such as [12], "
                + "every claim id such as F-0012-x, every address beginning http, every date and every number. Give a name of a person, an organisation or a place in the form a " + language
                + " reader would use, with the original in parentheses the first time it appears in a line." + renderings(glossary, u.toString()));
        messages.addObject().put("role", "user").put("content", u.toString());
        String answer;
        try (var step = Declines.step("translate " + lines.size() + " lines of the dossier of " + subject + " into " + language)) { answer = drive.prose(messages, Math.min(8000, Math.max(700, u.length() + 300))); }
        catch (Declined x) { return out; }
        if (answer == null) return out;
        Pattern numbered = Pattern.compile("^\\s*(\\d+)[.)]\\s*(.*)$");
        for (String line : answer.split("\n")) { Matcher m = numbered.matcher(line); if (!m.matches()) continue; int n = Integer.parseInt(m.group(1)); if (n >= 1 && n <= lines.size() && out.get(n - 1) == null) out.set(n - 1, m.group(2).strip()); }
        return out;
    }

    /** The dossier's sections: the head, then one per `## ` heading. */
    static List<String> sections(String markdown) {
        List<String> out = new ArrayList<>();
        for (String s : markdown.split("(?m)^(?=## )")) if (!s.isBlank()) out.add(s);
        return out;
    }

    /** A section in pieces of at most {@link #PIECE} characters, cut between paragraphs or lines. */
    static List<String> pieces(String section) {
        List<String> out = new ArrayList<>();
        if (section.length() <= PIECE) { out.add(section); return out; }
        StringBuilder cur = new StringBuilder();
        for (String para : section.split("\n\n")) {
            if (para.length() > PIECE) {   // one long paragraph (a list): by lines
                for (String line : para.split("\n")) { if (cur.length() + line.length() + 1 > PIECE && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } cur.append(line).append('\n'); }
                cur.append('\n'); continue;
            }
            if (cur.length() + para.length() + 2 > PIECE && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            cur.append(para).append("\n\n");
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Whether a translation keeps every cite, claim id and address of its original, each as many times. */
    static boolean keeps(String original, String translation) {
        for (Pattern p : new Pattern[]{CITE, CLAIM_ID, URL}) if (!all(p, original).equals(all(p, translation))) return false;
        return true;
    }
    private static List<String> all(Pattern p, String s) { List<String> out = new ArrayList<>(); Matcher m = p.matcher(s); while (m.find()) out.add(m.group()); Collections.sort(out); return out; }

    /** The names and terms that recur in a dossier (twice or more), up to eighty by frequency: what the glossary fixes once. */
    static List<String> terms(String markdown) {
        Map<String, Integer> count = new LinkedHashMap<>();
        String lang = languageOf(markdown);
        if (lang.equals("ja") || lang.equals("zh")) {
            Matcher m = CrossLinks.NAME_JA.matcher(markdown);
            while (m.find()) { String t = m.group(); if (t.length() >= 3 && !CrossLinks.STOP_JA.contains(t)) count.merge(t, 1, Integer::sum); }
            Matcher q = Pattern.compile("「([^」\\n]{2,30})」").matcher(markdown);
            while (q.find()) count.merge(q.group(1), 1, Integer::sum);
        } else {
            Matcher m = CrossLinks.NAME.matcher(markdown);
            while (m.find()) { String t = m.group(); if (t.length() >= 4 && !CrossLinks.STOP.contains(t) && !CLAIM_ID.matcher(t).find()) count.merge(t, 1, Integer::sum); }
            Matcher q = Pattern.compile("\"([^\"\\n]{3,40})\"").matcher(markdown);
            while (q.find()) count.merge(q.group(1), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> all = new ArrayList<>(count.entrySet());
        all.removeIf(e -> e.getValue() < 2 || URL.matcher(e.getKey()).find());
        all.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (var e : all) { if (out.size() >= 80) break; out.add(e.getKey()); }
        return out;
    }

    /** The rendering of each term in {@code language}, fixed once by the model: term → rendering, for the terms it answered for. */
    static Map<String, String> glossary(Researcher.Drive drive, List<String> terms, String language, String subject) {
        Map<String, String> out = new LinkedHashMap<>();
        if (terms.isEmpty()) return out;
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You fix the " + language + " rendering of the names and terms that recur in a library's dossier of " + subject + ", so that every "
                + "section of its translation uses the same words. For each term give the form a " + language + " reader would use: for a person, an organisation, an office, a policy or a "
                + "place the established " + language + " name where one exists, else a faithful rendering. Answer with one line a term: the term, a tab, its rendering, in the order given. "
                + "Nothing else.");
        messages.addObject().put("role", "user").put("content", "TERMS:\n" + String.join("\n", terms));
        String answer;
        try (var step = Declines.step("fix the " + language + " glossary of the dossier of " + subject)) { answer = drive.prose(messages, Math.min(4000, 40 * terms.size() + 200)); }
        catch (Declined x) { return out; }
        if (answer == null) return out;
        for (String line : answer.split("\n")) {
            String[] parts = line.split("\t|\s+→\s+|\s+->\s+|\s+—\s+", 2);
            if (parts.length < 2) continue;
            String term = parts[0].strip().replaceFirst("^[-*\\d.)\\s]+", ""), rendering = parts[1].strip();
            if (terms.contains(term) && !rendering.isEmpty() && !rendering.equals(term)) out.put(term, rendering);
        }
        return out;
    }

    static String translate(Researcher.Drive drive, String text, String language, String subject) { return translate(drive, text, language, subject, Map.of()); }

    static String translate(Researcher.Drive drive, String text, String language, String subject, Map<String, String> glossary) {
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You translate one part of a library's dossier of " + subject + " into " + language + ". Translate every heading and every "
                + "sentence into " + language + ". Keep exactly as they stand: every citation in square brackets such as [12], every claim id such as F-0012-x whether in brackets or "
                + "parentheses, every address beginning http, every date and every number, and the markdown itself (the # headings, the - lists, the bold). Give a name of a person, "
                + "an organisation or a place in the form a " + language + " reader would use, with the original in parentheses the first time it appears. Write the translation "
                + "and nothing else: no preface, no note." + renderings(glossary, text));
        messages.addObject().put("role", "user").put("content", text);
        int max = Math.min(8000, Math.max(700, text.length() + 300));
        try (var step = Declines.step("translate the dossier of " + subject + " into " + language)) { return drive.prose(messages, max); }
        catch (Declined x) { return null; }
    }

    /** The glossary's renderings for the terms this piece carries, as a line for the prompt; nothing when none. */
    static String renderings(Map<String, String> glossary, String text) {
        StringBuilder b = new StringBuilder();
        for (var e : glossary.entrySet()) if (text.contains(e.getKey())) b.append(b.length() == 0 ? " Render these terms as given, the same every time: " : "; ").append(e.getKey()).append(" → ").append(e.getValue());
        return b.length() == 0 ? "" : b.append('.').toString();
    }

    /** The id of a translate job queued or running for this subject and language, or null. */
    public static String pending(Jobs jobs, String subject, String lang) throws IOException {
        if (jobs == null) return null;
        String code = code(lang);
        for (ObjectNode j : jobs.active()) if ("translate".equals(j.path("kind").asText()) && j.path("args").path("subject").asText("").equalsIgnoreCase(subject) && code(j.path("args").path("lang").asText("")).equals(code)) return j.path("job_id").asText();
        return null;
    }
}
