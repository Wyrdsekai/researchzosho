package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every language file of the interface's words is whole: the keys of the Japanese file, no more and no fewer, each value with every
 * {n} of its key and none empty; every text the chat's own turns ask for by a literal, and every sentence the program makes outside a
 * literal (the helplines, the scorers' setup, the counts of the pictures, the choices' names), is a key of each. It passes with
 * Japanese alone and checks each language file dropped in beside it, with no change here.
 */
class ChromeFilesTest {
    static final ObjectMapper M = new ObjectMapper();
    static final Pattern PLACE = Pattern.compile("\\{\\d+\\}");

    /** A language file as it is shipped, or null when there is none. */
    static Map<String, String> file(String code) throws Exception {
        try (InputStream in = Chrome.class.getResourceAsStream("chrome/" + code + ".json")) {
            if (in == null) return null;
            JsonNode j = M.readTree(in);
            Map<String, String> m = new LinkedHashMap<>();
            j.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : null));
            return m;
        }
    }

    /** The languages with a file. */
    static List<String> present() throws Exception {
        List<String> out = new ArrayList<>();
        for (String c : Chrome.FILES) if (file(c) != null) out.add(c);
        return out;
    }

    @Test
    void everyFileHasTheKeysOfTheJapaneseOneEveryPlaceAndNoEmptyValue() throws Exception {
        Map<String, String> ja = file("ja");
        assertNotNull(ja, "the Japanese file is the reference");
        List<String> codes = present();
        List<String> expected = new ArrayList<>(List.of(Chrome.EN)); expected.addAll(codes);
        assertEquals(expected, Chrome.available(), "the switch offers English and each language that has a file");
        List<String> faults = new ArrayList<>();
        for (String code : codes) {
            Map<String, String> f = file(code);
            for (String k : ja.keySet()) if (!f.containsKey(k)) faults.add(code + ": missing " + k);
            for (String k : f.keySet()) if (!ja.containsKey(k)) faults.add(code + ": a key the Japanese file has not " + k);
            for (var e : f.entrySet()) {
                String v = e.getValue();
                if (v == null || (v.isBlank() && !e.getKey().isBlank())) { faults.add(code + ": empty " + e.getKey()); continue; }
                Set<String> want = new TreeSet<>(), got = new TreeSet<>();
                Matcher m = PLACE.matcher(e.getKey()); while (m.find()) want.add(m.group());
                m = PLACE.matcher(v); while (m.find()) got.add(m.group());
                if (!got.containsAll(want)) { Set<String> lost = new TreeSet<>(want); lost.removeAll(got); faults.add(code + ": " + lost + " not in the value of " + e.getKey()); }
                if (!want.containsAll(got)) { Set<String> more = new TreeSet<>(got); more.removeAll(want); faults.add(code + ": " + more + " in the value but not the key " + e.getKey()); }
            }
        }
        assertTrue(faults.isEmpty(), faults.size() + " fault(s):\n" + String.join("\n", faults.subList(0, Math.min(80, faults.size()))));
    }

    /** The classes whose sentences the chat says: its own turns, its commands, and what they say through. */
    static final List<String> CHAT = List.of("TranslateTurn", "ModelsTurn", "ReadInTurn", "ImagesTurn", "Images", "Librarian", "ChatCommands", "Glossary", "ModelEntries",
            "Translation", "TranslationScores", "TranslationLengths", "ComicTranslation", "ComicHelper", "TranslationWho", "ContentOffer", "LikenessHelper", "CrisisHelp", "Chrome", "RunProgress", "RunLimits", "Explain", "McpSources", "HomeCard", "Accounts", "Fields", "Help");

    @Test
    void everyTextTheChatAsksForByALiteralIsInEveryFile() throws Exception {
        Path main = GenealogyBoundaryTest.mainJava().resolve("org/researchzosho/librarian");
        Map<String, Map<String, String>> constants = new LinkedHashMap<>();   // a class's name to its String constants
        try (Stream<Path> all = Files.walk(GenealogyBoundaryTest.mainJava())) {
            for (Path p : all.filter(x -> x.toString().endsWith(".java")).toList()) { Map<String, String> c = new LinkedHashMap<>(); constants(Files.readString(p), c); constants.put(p.getFileName().toString().replace(".java", ""), c); }
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String name : CHAT) keys.addAll(asked(Files.readString(main.resolve(name + ".java")), name, constants));
        assertTrue(keys.size() > 300, "the scan reads the calls: " + keys.size());
        for (String code : present()) {
            Map<String, String> f = file(code);
            List<String> lacking = new ArrayList<>();
            for (String k : keys) if (!f.containsKey(k)) lacking.add(k);
            assertTrue(lacking.isEmpty(), code + " lacks " + lacking.size() + " text(s) the chat says:\n" + String.join("\n", lacking));
        }
    }

    @Test
    void theSentencesMadeOutsideALiteralAreInEveryFile() throws Exception {
        List<String> made = new ArrayList<>(CrisisHelp.sentences());
        made.addAll(TranslationScores.SETUP_SENTENCES);
        for (String head : List.of("{0} of {1} is like it", "{0} of {1} are like it", "{0} of {1} is the same", "{0} of {1} are the same", "{0} of {1} is the same {2}", "{0} of {1} are the same {2}"))
            for (String tail : List.of(".", ", {3} unsure.")) made.add(head + tail);
        made.addAll(List.of(Translation.QUICK, Translation.CHECKED, Translation.REVIEWED, Translation.THOROUGH, "yes", "no", "later", "none of them", "unsure", "none",
                "person", "place", "thing", "animal", "statue", "painting", "coin", "ship", "look and subject", "mood and look", "your words", "the description's words, since you gave none",
                "the kind of thing asked for, since the picture's description gave no words", Vision.Probe.word(null), Vision.Probe.word(true), Vision.Probe.word(false), "the host's own console",
                "today", "yesterday", "English", "Japanese", "Chinese", "Korean", "German", "French", "Spanish", "Portuguese", "Italian", "Russian"));
        for (Profile p : Profiles.known()) {
            if (!p.chatCommandHelp().isBlank()) made.add(p.chatCommandHelp().strip());
            if (!p.offer().isBlank()) made.add(p.offer());
            if (!p.choice().isBlank()) made.add(p.choice());   // the research page's box for the field
            made.add(p.description());   // what the field does, in /help's list of the fields that are on
            made.add(p.name() + " ⟦" + Fields.NAME_MEANING + "⟧");   // a field's name inside a sentence
        }
        made.addAll(RunProgress.resumedSentences());
        for (McpServers.Preset p : McpServers.presets()) made.add(p.title());   // what each preset MCP server is, on the keeper's page
        made.addAll(List.of(TranslationScores.LABSE_NEEDS, TranslationScores.METRICX_NEEDS));
        made.addAll(Languages.NAMES.values());   // a language's name inside a sentence (Chrome.languageName), for every language the program tells apart
        for (String code : present()) {
            Map<String, String> f = file(code);
            List<String> lacking = new ArrayList<>();
            for (String k : made) if (!f.containsKey(k)) lacking.add(k);
            assertTrue(lacking.isEmpty(), code + " lacks:\n" + String.join("\n", lacking));
        }
    }

    // ---------------------------------------------------------------- reading the calls

    static final Pattern LIT = Pattern.compile("\"((?:[^\"\\\\\\n]++|\\\\.)*+)\"");

    /** The String constants of a source, by name: a literal, or literals joined with +. */
    static void constants(String src, Map<String, String> out) {
        Matcher m = Pattern.compile("(?:static final String|,)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*((?:\"(?:[^\"\\\\\\n]++|\\\\.)*+\"\\s*+\\+?\\s*+)++)[,;]").matcher(src);
        while (m.find()) { StringBuilder b = new StringBuilder(); Matcher l = LIT.matcher(m.group(2)); while (l.find()) b.append(unescape(l.group(1))); out.putIfAbsent(m.group(1), b.toString()); }
    }

    /**
     * The keys a source asks for: the literals of the first argument of each t(…) call (a choice between two included), of the second and
     * third of n(…), the English and its meaning of tc(…), the sentence of a stage(…), and constants named there.
     */
    static Set<String> asked(String src, String self, Map<String, Map<String, String>> constants) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?<![\\w$\\\\])(t|n|tc|stage)\\(").matcher(src);
        while (m.find()) {
            List<Arg> args = args(src, m.end());
            if (m.group(1).equals("tc")) {
                if (args.size() >= 2 && args.get(0).literals().size() == 1) {
                    Matcher meaning = LIT.matcher(args.get(1).text().strip());
                    String said = meaning.matches() ? unescape(meaning.group(1)) : constants.getOrDefault(self, Map.of()).get(args.get(1).text().strip().replaceAll("^\\w+\\.", ""));
                    if (said != null) out.add(unescape(args.get(0).literals().get(0)) + " ⟦" + said + "⟧");
                }
                continue;
            }
            List<Arg> pick = m.group(1).equals("n") ? args.subList(Math.min(1, args.size()), Math.min(3, args.size())) : m.group(1).equals("stage") ? args.subList(Math.min(1, args.size()), Math.min(2, args.size())) : args.subList(0, Math.min(1, args.size()));
            for (Arg a : pick) {
                if (a.text().replaceAll("\"(?:[^\"\\\\]++|\\\\.)*+\"", "").contains("+")) continue;   // a key made of pieces at run time: its sentences are listed with the made ones
                for (String lit : a.literals()) { String k = unescape(lit); if (!k.isBlank() && k.matches("(?s).*\\p{L}.*")) out.add(k); }
                if (a.literals().isEmpty()) {   // a constant, of this class or named with its class
                    Matcher c = Pattern.compile("^\\s*(?:(\\w+)\\.)?([A-Z][A-Z0-9_]{2,})\\s*$").matcher(a.text());
                    if (c.find()) { String v = constants.getOrDefault(c.group(1) == null ? self : c.group(1), Map.of()).get(c.group(2)); if (v != null) out.add(v); }
                }
            }
        }
        return out;
    }

    /** One argument of a call: its text, and the literals that stand in it at its own level (not inside a call within it). */
    record Arg(String text, List<String> literals) { }

    /** The arguments of the call whose list starts at {@code from}, split at its own commas; strings, characters and comments skipped over. */
    static List<Arg> args(String s, int from) {
        List<Arg> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        List<String> lits = new ArrayList<>();
        int depth = 1;
        ArrayDeque<Boolean> calls = new ArrayDeque<>();   // for each open parenthesis: whether it is a call's (its literals are that call's) or a grouping's (they are the argument's)
        for (int j = from; j < s.length(); ) {
            char ch = s.charAt(j);
            if (s.startsWith("\"\"\"", j)) { int k = s.indexOf("\"\"\"", j + 3); j = k < 0 ? s.length() : k + 3; continue; }
            if (ch == '"') { Matcher l = LIT.matcher(s); if (l.find(j) && l.start() == j) { cur.append(l.group()); if (!calls.contains(true)) lits.add(l.group(1)); j = l.end(); continue; } }
            if (ch == '\'') { int k = s.charAt(j + 1) == '\\' ? s.indexOf('\'', j + 3) : j + 2; cur.append(s, j, k + 1); j = k + 1; continue; }
            if (s.startsWith("//", j)) { int k = s.indexOf('\n', j); j = k < 0 ? s.length() : k; continue; }
            if (s.startsWith("/*", j)) { j = s.indexOf("*/", j) + 2; continue; }
            if (ch == '(' || ch == '[' || ch == '{') { depth++; String before = cur.toString().stripTrailing(); calls.push(ch != '(' || (!before.isEmpty() && (Character.isLetterOrDigit(before.charAt(before.length() - 1)) || before.endsWith(">")))); }
            else if (ch == ')' || ch == ']' || ch == '}') { if (--depth == 0) { out.add(new Arg(cur.toString(), lits)); return out; } if (!calls.isEmpty()) calls.pop(); }
            else if (ch == ',' && depth == 1) { out.add(new Arg(cur.toString(), lits)); cur.setLength(0); lits = new ArrayList<>(); j++; continue; }
            cur.append(ch); j++;
        }
        return out;
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> b.append('\n'); case 't' -> b.append('\t');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                default -> b.append(n);
            }
        }
        return b.toString();
    }
}
