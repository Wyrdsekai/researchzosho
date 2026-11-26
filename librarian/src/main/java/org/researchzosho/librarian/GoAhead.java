package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The person's go-ahead in the chat (2026-11-25): what the chat's model starts or writes in answer to one message is done without the
 * library's question ({@link Librarian#waitsForYes}), when the person's own typed words say so. The owner: "a /go thing as well as an
 * explicit chat statement. like 'just go' or 'execute now' or 'no confirmation needed'"; no standing mode.
 *
 * <p>What makes one, read here by the program and never judged by a model: a message that begins with {@code /go} and a request, or a
 * message whose first or last sentence, whole, is one of the phrases of {@link ChatWords} ({@code go}, every language's). Only the words
 * the surface hands over as typed are read ({@link Librarian#say(String, String)}): never the conversation, a tool's result, the model's
 * words, a file attached or a button of the page. A sentence inside quotation marks, on a line that starts with {@code >} or in a
 * fenced block is quoted, not said, and counts for nothing.
 *
 * <p>What it reaches, the three limits ({@link Librarian}'s turn applies them call by call): this one message and the turn it starts; a
 * call of starting work or writing, or a change of a setting, the sharing, a model entry or key, the sources, or a drop, only when the
 * person's own message names what is changed ({@link #named}); and only the calls the model makes before anything was read in the turn.
 */
final class GoAhead {
    private GoAhead() { }

    /** The command form: {@code /go} and the request. */
    static final String SLASH = "/go";

    /** How a go-ahead was given: the command, or a phrase as the message's first or last sentence. */
    enum How { SLASH, PHRASE; String said() { return this == SLASH ? GoAhead.SLASH : "phrase"; } }

    /**
     * A go-ahead read from what the person typed. {@code request}: the words the model hears (the command's words after {@code /go}; a
     * phrase's message as typed). {@code only}: the message is the phrase and nothing else, which answers a question that waits as a yes.
     */
    record Given(How how, String request, boolean only, List<String> sentences) {
        Given(How how, String request, boolean only) { this(how, request, only, List.of()); }
    }

    /** The go-ahead in the person's typed words, or null when there is none. {@code /go} alone is none: the chat says how it is used. */
    static Given read(String typed) {
        if (typed == null) return null;
        String w = Normalizer.normalize(typed, Normalizer.Form.NFKC).strip();
        if (w.isEmpty()) return null;
        Matcher slash = SLASH_FORM.matcher(w);
        if (slash.matches()) return new Given(How.SLASH, slash.group(1).strip(), false);
        if (w.regionMatches(true, 0, SLASH, 0, SLASH.length()) && (w.length() == SLASH.length() || Character.isWhitespace(w.charAt(SLASH.length())))) return null;   // /go alone
        List<Sentence> s = sentences(w);
        if (s.isEmpty()) return null;
        Sentence first = s.get(0), last = s.get(s.size() - 1);
        boolean byFirst = !first.quoted() && isPhrase(first.text()), byLast = !last.quoted() && isPhrase(last.text());
        if (!byFirst && !byLast) return null;
        List<String> said = new ArrayList<>();
        if (byFirst) said.add(first.text()); if (byLast && !said.contains(last.text())) said.add(last.text());
        return new Given(How.PHRASE, w, s.size() == 1, List.copyOf(said));
    }

    /** "/go" and a request after a space: the request is the rest. */
    private static final Pattern SLASH_FORM = Pattern.compile("(?is)^/go\\s+(\\S.*)$");

    /** The words of a sentence, with its closing stops taken off, are one of the phrases: whole, never a phrase inside a longer sentence. */
    static boolean isPhrase(String sentence) {
        String s = Normalizer.normalize(sentence == null ? "" : sentence, Normalizer.Form.NFKC).strip().replaceAll("[\\s.!?。！？…]+$", "").strip();
        if (s.isEmpty()) return false;
        for (String e : ChatWords.entries("go")) if (ChatWords.pattern(e, null).matcher(s).matches()) return true;
        return false;
    }

    /** One sentence of a message, and whether any of it stands inside a quotation, a quoted line or a fenced block. */
    record Sentence(String text, boolean quoted) { }

    /** The quotation marks that open and close a quotation, in pairs: a quoted sentence is not the person's own. */
    private static final String[][] QUOTES = {{"\"", "\""}, {"“", "”"}, {"„", "“"}, {"„", "”"}, {"«", "»"}, {"「", "」"}, {"『", "』"}, {"‹", "›"}};

    /** The sentences of a message, cut at a full stop, an exclamation or a question mark (with a space after them in spaced writing) and at a line's end. */
    static List<Sentence> sentences(String text) {
        String t = text == null ? "" : text;
        boolean[] quoted = new boolean[t.length()];
        // a fenced block, ``` to ```, or to the end
        Matcher f = Pattern.compile("(?s)```.*?(?:```|$)").matcher(t);
        while (f.find()) for (int i = f.start(); i < f.end(); i++) quoted[i] = true;
        // a line that starts with > (a quoted message, an e-mail's quoted text)
        Matcher q = Pattern.compile("(?m)^[ \\t]*>.*$").matcher(t);
        while (q.find()) for (int i = q.start(); i < q.end(); i++) quoted[i] = true;
        // a pair of quotation marks, opened and closed; an opener with no closer quotes nothing
        for (String[] pair : QUOTES) {
            int from = 0;
            while (true) {
                int open = t.indexOf(pair[0], from);
                if (open < 0) break;
                int close = t.indexOf(pair[1], open + pair[0].length());
                if (close < 0) break;
                for (int i = open; i < close + pair[1].length(); i++) quoted[i] = true;
                from = close + pair[1].length();
            }
        }
        List<Sentence> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= t.length(); i++) {
            boolean end = i == t.length() || t.charAt(i) == '\n' || t.charAt(i) == '\r';
            int cut = i;
            if (!end) {
                char c = t.charAt(i);
                if ("。！？".indexOf(c) >= 0) { end = true; cut = i + 1; }
                else if (".!?…".indexOf(c) >= 0) {
                    int j = i; while (j < t.length() && ".!?…".indexOf(t.charAt(j)) >= 0) j++;
                    while (j < t.length() && "\"”»」』)’'".indexOf(t.charAt(j)) >= 0) j++;   // a closing mark stays with its sentence
                    if (j == t.length() || Character.isWhitespace(t.charAt(j))) { end = true; cut = j; }
                }
            }
            if (!end) continue;
            add(out, t, quoted, start, cut);
            start = cut;
            if (cut > i) i = cut - 1;
        }
        return out;
    }

    private static void add(List<Sentence> out, String t, boolean[] quoted, int start, int end) {
        if (end <= start) return;
        String s = t.substring(start, end);
        if (!s.matches("(?s).*[\\p{L}\\p{N}].*")) return;   // a stop or a mark alone is no sentence
        boolean q = false;
        for (int i = start; i < end; i++) if (quoted[i] && !Character.isWhitespace(t.charAt(i))) { q = true; break; }
        out.add(new Sentence(s.strip(), q));
    }

    // ---------------------------------------------------------------- what a go-ahead reaches

    /** What a held call is to a go-ahead: done; done only when the person's message names what it changes; never done without the question. */
    enum Kind { WORK, NAMED, NEVER }

    /** The kind of a call {@link Librarian#waitsForYes} holds. */
    static Kind kind(String name, JsonNode args) {
        String op = op(args);
        return switch (name) {
            case "library_settings", "library_share", "library_models", "library_sources", "library_serials" -> Kind.NAMED;
            case "library_owned" -> op.equals("forget") || op.equals("recalls") && !text(args, "hit").isEmpty() ? Kind.NAMED : Kind.WORK;   // an import is a file of this machine, named by its path below; a recall put away is named by its id
            case "library_seeks" -> op.equals("drop") ? Kind.NAMED : Kind.WORK;
            case "library_frontier" -> op.equals("drop") ? Kind.NAMED : Kind.WORK;
            case "library_inbox" -> op.equals("retire") ? Kind.NAMED : Kind.WORK;
            case "library_bridges" -> op.equals("dismiss") || op.equals("settings") ? Kind.NAMED : Kind.WORK;
            // a run's job: stopping one is a drop, named by its id; letting in what the library leaves out, a run's choice, the runner's pause: only the person's yes
            case "library_job" -> op.equals("stop") ? Kind.NAMED : Kind.NEVER;
            default -> Kind.WORK;
        };
    }

    /**
     * Whether the person's own message names what the call changes (a {@link Kind#NAMED} call), and every file or folder of this machine
     * the call names by its path (any call). {@code words}: the person's typed words, the request. A page read in an earlier turn sits in
     * the conversation, and a change the message did not mention may be the page's wish riding the go-ahead.
     */
    static boolean named(LibraryStore store, String name, JsonNode args, String words) {
        if (!pathsNamed(args, words)) return false;
        if (kind(name, args) != Kind.NAMED) return true;
        String op = op(args);
        return switch (name) {
            case "library_settings" -> settingNamed(args, words);
            case "library_share" -> switch (op) {
                case "tailscale" -> holds(words, "tailscale") || holds(words, "tailnet");
                case "cloudflare" -> holds(words, "cloudflare") && allHeld(words, args.path("mail").asText("").split("[,\\s]+"));
                case "public" -> ChatWords.has("go.public", words);
                case "off" -> ChatWords.has("go.share", words);
                default -> false;
            };
            case "library_models" -> switch (op) {
                case "add" -> (holds(words, text(args, "name")) || holds(words, text(args, "model")))
                        && (!text(args, "drive").matches("(?i)^https?://.*") || holds(words, text(args, "drive")));
                case "remove", "test" -> holds(words, text(args, "name"));
                case "key" -> holds(words, text(args, "value")) && holds(words, text(args, "host"));
                default -> false;
            };
            case "library_sources" -> switch (op) {
                case "add" -> holds(words, text(args, "address"));
                case "remove", "restore" -> holds(words, text(args, "id")) || holds(words, sourceAddress(store, text(args, "id")));
                default -> false;
            };
            case "library_serials" -> switch (op) {
                case "add" -> holds(words, text(args, "query"));
                case "every" -> (holds(words, text(args, "name")) || holds(words, serialQuery(store, text(args, "name")))) && holds(words, text(args, "every_days"));
                default -> holds(words, text(args, "name")) || holds(words, serialQuery(store, text(args, "name")));
            };
            case "library_bridges" -> op.equals("dismiss") ? allGiven(words, args, "id", "question")
                    : allGiven(words, args, "area", "sources", "reach", "toward", "away", "since", "per_night", "via");
            case "library_seeks" -> holds(words, text(args, "group"));
            case "library_owned" -> op.equals("recalls") ? holds(words, text(args, "hit")) : holds(words, text(args, "file")) || holds(words, fileName(text(args, "file")));
            case "library_frontier" -> allGiven(words, args, "question", "id");
            case "library_inbox" -> { List<String> ids = new ArrayList<>(); for (JsonNode i : args.path("ids")) ids.add(i.asText("")); if (!text(args, "id").isEmpty()) ids.add(text(args, "id")); if (!text(args, "report").isEmpty()) ids.add(text(args, "report")); yield !ids.isEmpty() && allHeld(words, ids.toArray(new String[0])); }
            case "library_job" -> holds(words, text(args, "job_id"));
            default -> false;
        };
    }

    /** A setting: the value it is set to stands in the message, and for a number, a switch word or one of its choices, the setting's name too; an unset, its name. */
    static boolean settingNamed(JsonNode args, String words) {
        if (op(args).equals("read_in")) { String lang = text(args, "value"); return !lang.isEmpty() && (languageSaid(words, lang) || holds(words, lang)); }   // the person's own reading language: the language, in any of the ten languages' words
        String given = text(args, "name");
        Settings.Setting s = Settings.named(given);
        String key = s == null ? given : s.key(), shortName = s == null ? given.toLowerCase(Locale.ROOT) : s.shortName();
        boolean nameSaid = holds(words, key) || holds(words, shortName) || holds(words, shortName.replace('_', ' ')) || holds(words, shortName.replace('_', '.')) || holds(words, shortName.replace('_', '-'));
        String value = args.path("value").isValueNode() ? args.path("value").asText("").strip() : "";
        if (op(args).equals("unset") || value.isEmpty()) return nameSaid;
        boolean valueSaid = holds(words, value) || languageSaid(words, value);
        String v = value.toLowerCase(Locale.ROOT);
        // a choice of this setting and of no other names it by itself ("APA" is the citation style's alone), so a message in any language
        // names it; a number, a switch word or a choice several settings share (low, high) needs the setting's name too
        boolean ownChoice = s != null && s.choices().contains(v) && Settings.KNOWN.stream().filter(o -> o.choices().contains(v)).count() == 1 && !SWITCHES.contains(v);
        boolean says = v.matches("\\d+(\\.\\d+)?") || SWITCHES.contains(v) || (s != null && s.choices().contains(v) && !ownChoice);
        return valueSaid && (!says || nameSaid);
    }

    /** Values that say nothing of which setting they are for. */
    static final Set<String> SWITCHES = Set.of("on", "off", "true", "false", "yes", "no", "auto", "none", "open", "closed");

    /** A value that names a language, named in the message in any of the ten languages' words. */
    static boolean languageSaid(String words, String value) {
        String code = ChatWords.language(value);
        if (code.isEmpty()) return false;
        for (String n : ChatWords.entries("lang." + code)) if (!ChatWords.isPattern(n) && holds(words, n)) return true;
        String english = Translate.LANGUAGES.get(code);
        return english != null && holds(words, english);
    }

    /** Every file or folder of this machine the call names by its path stands in the message: the path, or the file's own name with its ending. */
    static boolean pathsNamed(JsonNode args, String words) {
        for (String p : machinePaths(args)) if (!holds(words, p) && !(fileName(p).matches("(?s).+\\.[\\p{L}\\p{N}]{1,8}") && holds(words, fileName(p)))) return false;
        return true;
    }

    /** The paths of this machine a call names: path, file, a file:// url or address, and pictures by a path. A file the person attached is theirs, named by the page. */
    static List<String> machinePaths(JsonNode args) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (String k : List.of("url", "address")) { String v = text(args, k); if (v.matches("(?is)^file:.*")) out.add(v.replaceFirst("(?i)^file:(//)?", "")); }
        for (String k : List.of("path", "file")) {
            String v = text(args, k);
            if (v.isEmpty() || v.matches("(?i)^https?://.*")) continue;
            try { if (!Uploads.isUpload(Path.of(v).toAbsolutePath().normalize())) out.add(v); } catch (RuntimeException e) { out.add(v); }
        }
        if (Librarian.localPicture(args)) for (String k : List.of("images", "image")) {
            JsonNode v = args.path(k);
            List<JsonNode> all = new ArrayList<>(); if (v.isTextual()) all.add(v); else for (JsonNode x : v) all.add(x);
            for (JsonNode x : all) { String t = x.asText("").strip(); if (t.startsWith("/") || t.startsWith("~") || t.matches("(?i)^[a-z]:[\\\\/].*")) out.add(t); }
        }
        return out;
    }

    /**
     * Whether the call reads one of this message's attached files in, named by the identity the page gave it (the entry it was shelved
     * as, its file under the library, the kept copy an ask took): the only call a go-ahead reaches in a turn whose message carried files,
     * since the files' sentences, their titles among them, are the documents' words. A run about the file, a watch, a check that files a
     * run, or anything else is not this.
     */
    static boolean readsInAttached(LibraryStore store, String name, JsonNode args, Set<String> attached) {
        if (attached == null || attached.isEmpty() || args == null) return false;
        if (!Set.of("library_add", "library_absorb", "library_check", "library_meeting", "library_reading", "library_bookmarks", "library_items", "library_survey").contains(name)) return false;
        if (args.path("verify").asBoolean(false) || args.path("watch").asBoolean(false)) return false;
        if (name.equals("library_items") && !text(args, "as").isEmpty() && !text(args, "as").equalsIgnoreCase("none")) return false;
        if (name.equals("library_survey") && !op(args).isEmpty() && !op(args).equals("survey")) return false;
        String path = text(args, "path");
        if (path.isEmpty()) return false;
        for (String a : attached) {
            if (a.isBlank()) continue;
            if (path.equals(a)) return true;
            try {
                Path p = Path.of(path).toAbsolutePath().normalize();
                if (p.equals(Path.of(a).toAbsolutePath().normalize())) return true;
                if (store != null && !a.contains("/") && p.equals(store.rawDir().resolve(a).toAbsolutePath().normalize())) return true;
            } catch (RuntimeException notAPath) { /* not this one */ }
        }
        return false;
    }

    /**
     * Whether a call the go-ahead carried out answers only with the library's account of what it did (a run filed, a setting set, a source
     * added): its result brings nothing read into the turn, so the go-ahead goes on reaching the model's next calls. Every other call
     * that was carried out reads: the shelves hold pages and documents, and a fetch brings a page.
     */
    static boolean ownResult(String name, JsonNode args) {
        String op = op(args);
        return switch (name) {
            case "library_research", "library_translate", "library_settings", "library_sources", "library_serials", "library_share", "library_submit", "library_tell",
                 "library_frontier", "library_inbox", "library_seeks", "library_owned" -> !(name.equals("library_owned") && op.equals("import"));
            case "library_models" -> op.equals("remove") || op.equals("key");
            case "library_job" -> op.equals("stop");
            case "library_bridges" -> Set.of("accept", "dismiss", "settings").contains(op);
            case "library_dossier" -> Set.of("watch", "score").contains(op);
            case "library_images" -> op.equals("collect");
            default -> false;
        };
    }

    /**
     * Whether a call that only reads returns nothing but what the person or the program wrote, so that reading it brings nothing into the
     * turn that could be a page's or a document's wish (2026-11-25, measured: the 27B lists the settings before every setting, and the go-ahead
     * never reached one). These four: library_settings op=list, the settings and their values (set by the person, by the program's defaults,
     * or on the person's yes), the program's own words on each key's service, the use the library counted and its own sentence on the last
     * key check; library_models op=list, the entries the person made, their prices as numbers the program read and the program's words;
     * library_status, the counts, the version and the library's own states; library_help, the program's own documentation for its version
     * and the library's state in the program's words. Not the sources, the saved searches, the open questions, the inbox, a run's job or
     * the sharing's status: what those return can hold what a run found on a page, or another program's answer.
     */
    static boolean ownReading(String name, JsonNode args) {
        String op = op(args);
        return switch (name) {
            case "library_settings", "library_models" -> op.isEmpty() || op.equals("list");
            case "library_status", "library_help" -> true;
            default -> false;
        };
    }

    /**
     * Whether the sentence that gave the go-ahead stands inside what the call would keep (a note's text, a claim, a question): then the
     * phrase is more likely the last line of something pasted to be kept ("Save this email from Sam as a note: … Just go.", 2026-11-25,
     * measured on the 27B) than the person's own word, and the call is asked. It only ever asks more.
     */
    static boolean pasted(Given g, JsonNode args) {
        if (g == null || g.how() != How.PHRASE || args == null) return false;
        List<String> kept = new ArrayList<>();
        texts(args, kept);
        for (String sentence : g.sentences()) {
            String p = fold(sentence).replaceAll("[\\s.!?。！？…]+$", "");
            if (p.isEmpty()) continue;
            for (String k : kept) if (fold(k).contains(p)) return true;
        }
        return false;
    }

    private static void texts(JsonNode n, List<String> out) {
        if (n == null) return;
        if (n.isTextual()) { out.add(n.asText()); return; }
        for (JsonNode x : n) texts(x, out);
    }

    // ---------------------------------------------------------------- the words of a message, compared

    /**
     * Whether the person's words hold a value: case, width and spaces folded, a path from the home (~/…) read as the home's. A value that
     * begins or ends in a letter of writing that spaces its words (Latin, Cyrillic, Greek) or a digit stands there as a whole word: the
     * character beside it is no such letter, no digit and no underscore. A space, a mark, or a letter of writing that does not space its
     * words bounds it alike, so J-0003 is named in "J-0003の調査", "停止J-0003。" and "J-0003을", and never in "J-00031" or "XJ-0003"
     * (2026-11-25, measured: the 27B's Japanese, Chinese and Korean put an id straight against the next word).
     */
    static boolean holds(String words, String value) {
        if (words == null || value == null) return false;
        String v = fold(value).replaceAll("/+$", "");
        if (v.isEmpty()) return false;
        String w = fold(words.replaceAll("(?<![\\w.~/-])~(?=/)", System.getProperty("user.home").replace("\\", "/")));
        String left = wordChar(v.codePointAt(0)) ? "(?<![" + WORD + "])" : "", right = wordChar(v.codePointBefore(v.length())) ? "(?![" + WORD + "])" : "";
        return Pattern.compile(left + Pattern.quote(v) + right).matcher(w).find();
    }

    /** The characters a whole word of spaced writing is made of, as a class: its letters, the digits, the underscore. */
    private static final String WORD = "\\p{IsLatin}\\p{IsCyrillic}\\p{IsGreek}\\p{Nd}_";

    /** Whether a character is one of {@link #WORD}'s. */
    static boolean wordChar(int cp) {
        if (cp == '_' || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) return true;
        if (!Character.isLetter(cp)) return false;
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.LATIN || sc == Character.UnicodeScript.CYRILLIC || sc == Character.UnicodeScript.GREEK;
    }

    static boolean allHeld(String words, String... values) {
        boolean any = false;
        for (String v : values) { if (v == null || v.isBlank()) continue; any = true; if (!holds(words, v.strip())) return false; }
        return any;
    }

    /** Every one of these arguments the call gives stands in the message; at least one is given. */
    static boolean allGiven(String words, JsonNode args, String... keys) {
        List<String> vs = new ArrayList<>();
        for (String k : keys) { JsonNode v = args.path(k); if (v.isValueNode() && !v.asText("").isBlank()) vs.add(v.asText()); }
        return allHeld(words, vs.toArray(new String[0]));
    }

    static String fold(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip(); }

    static String op(JsonNode args) { return args == null ? "" : args.path("op").asText("").strip().toLowerCase(Locale.ROOT).replace('-', '_'); }

    static String text(JsonNode args, String key) { JsonNode v = args == null ? null : args.get(key); return v == null || !v.isValueNode() ? "" : v.asText("").strip(); }

    static String fileName(String path) { String p = path == null ? "" : path.replaceAll("[\\\\/]+$", ""); int at = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')); return at < 0 ? p : p.substring(at + 1); }

    private static String sourceAddress(LibraryStore store, String id) {
        try { Sources.Source s = store == null || id.isEmpty() ? null : Sources.get(store, id); return s == null ? "" : s.address(); } catch (Exception e) { return ""; }
    }

    private static String serialQuery(LibraryStore store, String slug) {
        try { if (store != null) for (Serials.Shelf s : Serials.shelves(store)) if (s.slug().equalsIgnoreCase(slug)) return s.query(); } catch (Exception e) { /* none */ }
        return "";
    }

    // ---------------------------------------------------------------- the line under the reply

    /** What stood before a change, for the line that says how to undo it: a setting's value, a saved search, a model entry, the sharing. */
    record Before(String value, Serials.Shelf serial, ModelEntries.Entry entry, String share) { }

    static Before before(LibraryStore store, String name, JsonNode args) { return before(store, name, args, null); }

    /** The same, {@code patron} the person whose own reading language a change of it would change. */
    static Before before(LibraryStore store, String name, JsonNode args, Patrons.Patron patron) {
        try {
            return switch (name) {
                case "library_settings" -> {
                    if (op(args).equals("read_in")) yield new Before(patron == null ? "" : ReadIn.of(store, patron), null, null, null);
                    Settings.Setting s = Settings.named(text(args, "name")); yield new Before(s == null ? null : Config.get(s.key()), null, null, null);
                }
                case "library_serials" -> { for (Serials.Shelf s : Serials.shelves(store)) if (s.slug().equalsIgnoreCase(text(args, "name"))) yield new Before(null, s, null, null); yield new Before(null, null, null, null); }
                case "library_models" -> new Before(null, null, text(args, "name").isEmpty() ? null : ModelEntries.all().get(text(args, "name").toLowerCase(Locale.ROOT)), null);
                case "library_share" -> new Before(null, null, null, Share.kind());
                default -> new Before(null, null, null, null);
            };
        } catch (Exception e) { return new Before(null, null, null, null); }
    }

    /** A run's id in a result. */
    static final Pattern JOB = Pattern.compile("\\bJ-\\d{3,}\\b");
    /** A claim's or a report's id in a result, as /remove takes it. */
    static final Pattern ENTRY = Pattern.compile("\"(?:id|claim_id|investigation)\"\\s*:\\s*\"([FI]-\\d{4}-[A-Za-z0-9_-]+)\"");

    /** How the person undoes what was done without the question: the same step a yes would have left them, in their language. */
    static String undo(String name, JsonNode args, String result, Before before) {
        Chrome c = Chrome.now();
        String op = op(args), r = result == null ? "" : result;
        Set<String> jobs = new LinkedHashSet<>(); Matcher jm = JOB.matcher(r); while (jm.find()) jobs.add(jm.group());
        if (!jobs.isEmpty() && !name.equals("library_job")) return c.t("To undo: stop {0} (its stop button among the runs on the Chat page, or researchzosho research stop {0}).", String.join(", ", jobs));
        String cmd = switch (name) {
            case "library_settings" -> {
                if (op.equals("read_in")) { String old = before == null || before.value() == null ? "" : before.value(); yield "researchzosho settings read-in" + (old.isEmpty() ? "" : " " + old); }   // the person's own reading language, as it was
                Settings.Setting s = Settings.named(text(args, "name"));
                if (s == null) yield null;
                if (s.kind() == Settings.Kind.secret) yield "\u0001researchzosho settings set " + s.shortName();
                String old = before == null ? null : before.value();
                yield old == null || old.isBlank() ? "researchzosho settings unset " + s.shortName() : "researchzosho settings set " + s.shortName() + " " + (old.matches(".*\\s.*") ? "\"" + old + "\"" : old);
            }
            case "library_sources" -> { String id = text(args, "id"); if (id.isEmpty()) { Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(r); id = m.find() ? m.group(1) : ""; } yield id.isEmpty() ? null : (op.equals("remove") ? "/sources restore " : "/sources remove ") + id; }
            case "library_serials" -> {
                String n = text(args, "name");
                Serials.Shelf was = before == null ? null : before.serial();
                yield switch (op) {
                    case "add" -> "/serials remove " + n;
                    case "remove" -> was == null ? null : "/serials add " + n + " " + was.query() + " " + was.everyDays();
                    case "park" -> "/serials unpark " + n;
                    case "unpark" -> "/serials park " + n;
                    case "every" -> was == null ? null : "/serials every " + n + " " + was.everyDays();
                    default -> null;
                };
            }
            case "library_share" -> op.equals("off") ? (before == null || before.share() == null || before.share().equals("off") ? null : "researchzosho share" + switch (before.share()) { case "tailscale" -> " --tailnet"; case "cloudflare" -> " --cloudflare"; default -> " --tailscale"; }) : "/share off";
            case "library_models" -> {
                String n = text(args, "name");
                ModelEntries.Entry was = before == null ? null : before.entry();
                yield switch (op) {
                    case "add" -> was == null ? "researchzosho models remove " + n : "researchzosho models set " + n + (was.drive() == null ? "" : " --address " + was.drive()) + (was.model() == null ? "" : " --model " + was.model());
                    case "remove" -> was == null ? null : "researchzosho models add " + n + " " + was.drive() + " " + was.model();
                    case "key" -> "\u0001researchzosho models key " + text(args, "host");
                    default -> null;
                };
            }
            case "library_owned" -> op.equals("forget") ? "researchzosho owned import " + text(args, "file") : null;
            case "library_seeks", "library_job", "library_bridges" -> op.equals("drop") || op.equals("stop") || op.equals("dismiss") ? "" : null;
            default -> null;
        };
        if (cmd == null) {   // a claim or a report written: removed as one
            Matcher em = ENTRY.matcher(r);
            if (em.find()) cmd = "/remove " + em.group(1) + " --yes";
        }
        if (cmd == null) return c.t("No one command undoes this.");
        if (cmd.isEmpty()) return c.t("This cannot be undone.");
        if (cmd.startsWith("\u0001")) return c.t("To undo: {0} with the earlier value, which is not shown here.", cmd.substring(1));
        return c.t("To undo: {0}.", cmd);
    }
}
