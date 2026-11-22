package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every language file of the interface's words is whole: the keys of the Japanese file, no more and no fewer, each value with every
 * {n} of its key and none empty; every text any class of the program asks for by a literal, and every sentence the program makes outside
 * a literal (the helplines, the scorers' setup, the counts of the pictures, the choices' names), is a key of each. It passes with
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

    /** What marks a class that calls the language files: Chrome itself, Pages' t, n and tc, a run's stage sentence. */
    static final Pattern USES = Pattern.compile("\\bChrome\\b|Pages\\.(?:t|n|tc)\\(|RunProgress\\.stage\\(|import static org\\.researchzosho\\.librarian\\.Pages\\.");

    /** The classes that reach the language files without naming them, and how: each is read as the others are. */
    static final Map<String, String> THROUGH = Map.of(
            "Explain", "its progress.stage(phrase, sentence, …) hands each sentence to the page that shows the reading, which says it through the language files");

    /** How many calls of a class ask the language files by a key the scan cannot read, and where those texts are kept instead. */
    record Unread(int calls, String why) { }

    /**
     * Every class that asks the language files for a text by something other than a literal or a constant the scan reads: a word of the
     * library's data or of a table, a delegate handing on its caller's key, a sentence made of pieces. A class that gains or loses such a
     * call fails the test until its line here is said again, so no call goes unread in silence. The words of data reach the files through
     * the made list of theSentencesMadeOutsideALiteralAreInEveryFile, or through ChromeTest, which renders the pages in every language and
     * fails on any word a file lacks (its PAGES).
     */
    static final Map<String, Unread> UNREAD = Map.ofEntries(
            Map.entry("CareerPage", new Unread(4, "a dimension's label, a state's word (Career.STATE_WORDS) and a section's title (SECTIONS): words of tables; /career is among ChromeTest's pages")),
            Map.entry("ChatCommands", new Unread(7, "its own t delegate; a field's chat help (the made list); a level of access (read, write, deny), a key of its own")),
            Map.entry("Chrome", new Unread(6, "the language files' own methods: t, tc and n hand on their caller's key; a language's name (Languages.NAMES and the ten, the made list)")),
            Map.entry("Collection", new Unread(1, "why a picture was set aside, from its own table REASON_SAID")),
            Map.entry("CrisisHelp", new Unread(1, "the helplines' sentences: CrisisHelp.sentences(), the made list")),
            Map.entry("DossierPage", new Unread(4, "a dossier's kind, a facet's title from the table of facets and a source class's label: words of tables; /dossier is among ChromeTest's pages")),
            Map.entry("Fields", new Unread(1, "a field's name with its meaning: the made list, for every field")),
            Map.entry("ForecastsPage", new Unread(1, "a forecast's outcome word; /forecasts is among ChromeTest's pages")),
            Map.entry("Help", new Unread(5, "a field's name, description, chat help and menu labels (the made list, and /help among ChromeTest's pages); the pages' names, literals in the list beside the call")),
            Map.entry("HomeCard", new Unread(1, "a run's state as its badge says it, a word stored with the card")),
            Map.entry("HomePage", new Unread(4, "a state's word (Career.STATE_WORDS), a dimension's placeholder and a tier (HomeCut.TIERS): words of tables; /homes is among ChromeTest's pages")),
            Map.entry("Images", new Unread(2, "a verdict's word; the count of the pictures made of a head and a tail, every combination in the made list")),
            Map.entry("ImagesPage", new Unread(1, "a verdict's word; /images is among ChromeTest's pages")),
            Map.entry("ImagesTurn", new Unread(3, "a kind of thing, an aspect to match and where the words came from: literals of the switch beside each call, and the made list")),
            Map.entry("Librarian", new Unread(9, "its own t delegate; runs(…) hands on its callers' literals; a choice's label and a field's offer (the made list); a job's state")),
            Map.entry("LibraryProtocol", new Unread(1, "Hosted.SETTING, which names Config.NOT_WRITABLE: a constant of another class, not a literal")),
            Map.entry("MapPage", new Unread(2, "a name's kind (KINDS) and a claim's state, from the lists beside the calls; /map is among ChromeTest's pages")),
            Map.entry("McpPage", new Unread(2, "a preset MCP server's title: the made list, for every preset")),
            Map.entry("ModelEntries", new Unread(2, "what a model sees (its seesText) and the word for where a key comes from")),
            Map.entry("Pages", new Unread(46, "its t, n and tc delegates hand on their callers' keys; the rest are words of the library's data and tables (a claim's kind and confidence, a run's state and kind, a tier, a reaction, a field's offer and choice, a level of access), on the pages ChromeTest renders")),
            Map.entry("PatentsProfile", new Unread(1, "a patents page's title, handed on from its caller's literal")),
            Map.entry("PdfReading", new Unread(2, "why a page was not read, from its own table (MANY)")),
            Map.entry("RunProgress", new Unread(5, "a run started again or resumed, made of its reason: RunProgress.resumedSentences(), the made list")),
            Map.entry("SeeksPage", new Unread(1, "a seek's state; /seeks is among ChromeTest's pages")),
            Map.entry("SourcesPage", new Unread(2, "a source's state and a rule's kind; /sources is among ChromeTest's pages")),
            Map.entry("TranslateTurn", new Unread(5, "its own t delegate; the notes' sentences of Translation (notesSaid and its constants) and a tier's name (the made list)")),
            Map.entry("TranslationScores", new Unread(4, "its setup sentences (SETUP_SENTENCES) and what LaBSE and MetricX need, made with their sizes: the made list")),
            Map.entry("TranslationWho", new Unread(2, "the notes' sentence of Translation.notesSaid and a tier's name (the made list)")),
            Map.entry("VideoRecallPage", new Unread(1, "a kind of thing with its article, \"a \" and person, thing or place")));

    /**
     * Every class of the program that calls the language files is read (until 0.8.1 only the chat's were, and 89 sentences of the model
     * server, the hosted models' places, the MCP page and the translation notices reached no file while the suite stayed green): every
     * text it asks for by a literal is a key of each file; every call the scan cannot read is in UNREAD; and a class not read passes no
     * literal where a key goes.
     */
    @Test
    void everyTextTheProgramAsksForByALiteralIsInEveryFile() throws Exception {
        Map<String, String> read = new TreeMap<>(), others = new TreeMap<>();   // a class's simple name to its source
        Map<String, Map<String, String>> constants = new LinkedHashMap<>();   // a class's name to its String constants
        List<String> faults = new ArrayList<>();
        try (Stream<Path> all = Files.walk(GenealogyBoundaryTest.mainJava())) {
            for (Path p : all.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String name = p.getFileName().toString().replace(".java", ""), src = Files.readString(p);
                Map<String, String> c = new LinkedHashMap<>(); constants(src, c); constants.put(name, c);
                Map<String, String> into = USES.matcher(src).find() || THROUGH.containsKey(name) ? read : others;
                if (into.put(name, src) != null && into == read) faults.add("two classes are named " + name + ": the scan tells the classes it reads apart by their simple names");
            }
        }
        Map<String, List<String>> askedBy = new TreeMap<>();   // a text to the classes that ask for it
        Map<String, List<String>> unread = new TreeMap<>();
        for (var e : read.entrySet()) {
            Scan s = asked(e.getValue(), e.getKey(), constants);
            for (String k : s.keys()) askedBy.computeIfAbsent(k, x -> new ArrayList<>()).add(e.getKey());
            if (!s.unread().isEmpty()) unread.put(e.getKey(), s.unread());
        }
        assertTrue(read.size() > 100 && askedBy.size() > 3000, "the scan reads the classes and their calls: " + read.size() + " classes, " + askedBy.size() + " texts");
        for (String name : THROUGH.keySet()) if (!read.containsKey(name)) faults.add("THROUGH names " + name + ", which is not a class of the program any more");
        Set<String> named = new TreeSet<>(unread.keySet()); named.addAll(UNREAD.keySet());
        for (String name : named) {
            int got = unread.getOrDefault(name, List.of()).size(), said = UNREAD.containsKey(name) ? UNREAD.get(name).calls() : 0;
            if (got != said) faults.add(name + " asks the language files " + got + " time(s) by a key the scan cannot read, and UNREAD says " + said
                    + ": ask by a literal, or say in UNREAD where these texts are kept:\n    " + String.join("\n    ", unread.getOrDefault(name, List.of())));
        }
        for (var e : others.entrySet()) {
            Set<String> passed = asked(e.getValue(), e.getKey(), constants).keys();
            if (!passed.isEmpty()) faults.add(e.getKey() + " passes a text where a key goes but is not read: if it reaches the language files, name it in THROUGH: " + passed);
        }
        assertTrue(faults.isEmpty(), faults.size() + " fault(s):\n" + String.join("\n", faults));
        for (String code : present()) {
            Map<String, String> f = file(code);
            List<String> lacking = new ArrayList<>();
            for (var e : askedBy.entrySet()) if (!f.containsKey(e.getKey())) lacking.add(e.getKey() + "    (asked by " + String.join(", ", e.getValue()) + ")");
            assertTrue(lacking.isEmpty(), code + " lacks " + lacking.size() + " text(s) the program asks for by a literal:\n" + String.join("\n", lacking));
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

    /**
     * The String constants of a source, by name: a literal, or literals joined with +. The comma or semicolon that ends one is looked at, not
     * taken, so that the next of a list ({@code A = "…", B = "…"}) is read too (until 0.8.1 the second of every such list was not).
     */
    static void constants(String src, Map<String, String> out) {
        Matcher m = Pattern.compile("(?:static final String|,)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*((?:\"(?:[^\"\\\\\\n]++|\\\\.)*+\"\\s*+\\+?\\s*+)++)(?=[,;])").matcher(src);
        while (m.find()) { StringBuilder b = new StringBuilder(); Matcher l = LIT.matcher(m.group(2)); while (l.find()) b.append(unescape(l.group(1))); out.putIfAbsent(m.group(1), b.toString()); }
    }

    static final Pattern CALL = Pattern.compile("(?<![\\w$\\\\])(t|n|tc|stage)\\(");
    /** What stands before a method's declaration rather than a call: a type's name ("static String t(String en, …)"). */
    static final Pattern DECLARED = Pattern.compile("(?:^|[\\s(,])(?!(?:return|else|case|yield|throw|new|assert)\\b)[A-Za-z_][\\w<>\\[\\].?]*\\s+$");
    static final Pattern CONSTANT = Pattern.compile("^\\s*(?:(\\w+)\\.)?([A-Z][A-Z0-9_]+)\\s*$");

    /** What a source asks the language files for: the texts read, and each call whose key cannot be read ("line 85: t(sec[1]"). */
    record Scan(Set<String> keys, List<String> unread) { }

    /**
     * The keys a source asks for: the literals of the first argument of each t(…) call (a choice between two included), of the second and
     * third of n(…), the English and its meaning of tc(…), the sentence of a stage(…), and constants named there. A call inside a string or a
     * comment, or a method's own declaration, is not a call. A key made of pieces at run time, or by anything else the scan cannot read, is
     * written down as unread, never passed over.
     */
    static Scan asked(String src, String self, Map<String, Map<String, String>> constants) {
        Set<String> out = new LinkedHashSet<>();
        List<String> unread = new ArrayList<>();
        boolean[] code = code(src);
        Matcher m = CALL.matcher(src);
        while (m.find()) {
            if (!code[m.start()] || DECLARED.matcher(src.substring(Math.max(0, m.start() - 60), m.start())).find()) continue;
            List<Arg> args = args(src, m.end());
            String at = "line " + (src.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1) + ": " + m.group(1) + "(";
            if (m.group(1).equals("tc")) {
                if (args.size() < 2) continue;
                Matcher meaning = LIT.matcher(args.get(1).text().strip());
                String said = meaning.matches() ? unescape(meaning.group(1)) : constant(args.get(1), self, constants);
                if (args.get(0).literals().size() == 1 && !pieces(args.get(0)) && said != null) out.add(unescape(args.get(0).literals().get(0)) + " ⟦" + said + "⟧");
                else unread.add(at + args.get(0).text().strip());
                continue;
            }
            List<Arg> pick = m.group(1).equals("n") ? args.subList(Math.min(1, args.size()), Math.min(3, args.size())) : m.group(1).equals("stage") ? args.subList(Math.min(1, args.size()), Math.min(2, args.size())) : args.subList(0, Math.min(1, args.size()));
            for (Arg a : pick) {
                if (pieces(a)) { unread.add(at + a.text().strip()); continue; }   // a key made of pieces at run time
                if (!a.literals().isEmpty()) { for (String lit : a.literals()) { String k = unescape(lit); if (!k.isBlank() && k.matches("(?s).*\\p{L}.*")) out.add(k); } continue; }
                String v = constant(a, self, constants);   // a constant, of this class or named with its class
                if (v != null) out.add(v); else unread.add(at + a.text().strip());
            }
        }
        return new Scan(out, unread);
    }

    /** Whether an argument is put together at run time: a + outside its literals. */
    static boolean pieces(Arg a) { return a.text().replaceAll("\"(?:[^\"\\\\]++|\\\\.)*+\"", "").contains("+"); }

    /** The value of a constant an argument names, of this class or named with its class; null when it names none the scan reads. */
    static String constant(Arg a, String self, Map<String, Map<String, String>> constants) {
        Matcher c = CONSTANT.matcher(a.text());
        return c.find() ? constants.getOrDefault(c.group(1) == null ? self : c.group(1), Map.of()).get(c.group(2)) : null;
    }

    /** For each character of a source, whether it is code: false inside a string, a text block, a character literal or a comment. */
    static boolean[] code(String s) {
        boolean[] code = new boolean[s.length()];
        Arrays.fill(code, true);
        for (int i = 0; i < s.length(); ) {
            int end;
            if (s.startsWith("\"\"\"", i)) { int k = s.indexOf("\"\"\"", i + 3); end = k < 0 ? s.length() : k + 3; }
            else if (s.charAt(i) == '"') { int j = i + 1; while (j < s.length() && s.charAt(j) != '"' && s.charAt(j) != '\n') j += s.charAt(j) == '\\' ? 2 : 1; end = Math.min(j + 1, s.length()); }
            else if (s.charAt(i) == '\'') { int k = i + 1 < s.length() && s.charAt(i + 1) == '\\' ? s.indexOf('\'', i + 3) : i + 2; end = Math.min((k < 0 ? s.length() - 1 : k) + 1, s.length()); }
            else if (s.startsWith("//", i)) { int k = s.indexOf('\n', i); end = k < 0 ? s.length() : k; }
            else if (s.startsWith("/*", i)) { int k = s.indexOf("*/", i + 2); end = k < 0 ? s.length() : k + 2; }
            else { i++; continue; }
            for (int j = i; j < end; j++) code[j] = false;
            i = end;
        }
        return code;
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
