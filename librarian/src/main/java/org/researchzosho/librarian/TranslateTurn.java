package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Translate this file into Japanese", said in the chat with a file attached, or naming a file on the shelves or a path: the library's own
 * turn, no model asked. The file is cut and counted, the time is said first ("about 2 minutes quick, 6 checked"), and the translation
 * runs as a job on the person's word: which tier when the words did not say, a yes when they did and the run is long. "Checked" in the
 * words is the checked tier; "as a PDF" or "as epub" the output format. A line for the glossary ("render Acme Corp as アクメ社 in
 * Japanese", "glossary add …") is kept across documents ({@link Glossary}).
 */
final class TranslateTurn {
    private TranslateTurn() { }

    private static final ObjectMapper M = new ObjectMapper();
    static final String FIELD = "translate";
    /** A run this long or longer is not started without a yes, when the tier was named in the words. */
    static final long ASK_FIRST_SECONDS = 5 * 60;

    /** "translate this file into Japanese", "translate saros notes.md to German as a PDF, checked", "please translate it into French". */
    static final Pattern EN = Pattern.compile("(?i)^\\s*(?:please\\s+)?translate\\s+(?<what>.+?)\\s+(?:into|to|in)\\s+(?<lang>[\\p{L}-]+)(?<rest>\\b.*)$");
    /** "このファイルを日本語に翻訳して", "それを英語に訳して、確認付きで". */
    static final Pattern JA = Pattern.compile("^\\s*(?<what>.+?)を(?<lang>日本語|英語|中国語|韓国語|ドイツ語|フランス語|スペイン語|ポルトガル語|イタリア語|ロシア語)に(?:翻訳|訳)(?<rest>.*)$");
    static final Map<String, String> JA_NAMES = Map.ofEntries(Map.entry("日本語", "ja"), Map.entry("英語", "en"), Map.entry("中国語", "zh"), Map.entry("韓国語", "ko"), Map.entry("ドイツ語", "de"), Map.entry("フランス語", "fr"), Map.entry("スペイン語", "es"), Map.entry("ポルトガル語", "pt"), Map.entry("イタリア語", "it"), Map.entry("ロシア語", "ru"));
    /** The words for the file posted with the turn: "this file", "this epub", "the attached document", "it" (the turn of 2026-11-07 that said "this epub" was asked "Which file?"). */
    static final Pattern THIS = Pattern.compile("(?i)^(?:(?:this|that|the|my)(?:\\s+attached)?(?:\\s+(?:one|file|epub|pdf|docx?|document|doc|book|text|notes?|manuscript|attachment))?|it|attached|the attachment|これ|それ|このファイル|この文書|この本|添付ファイル|添付)$");
    static final Pattern AS = Pattern.compile("(?i)\\bas\\s+(?:an?\\s+)?(md|markdown|txt|text|pdf|docx|word|epub)\\b|(md|markdown|txt|pdf|docx|word|epub)で");
    /** "glossary add Acme Corp ja アクメ社", "render Acme Corp as アクメ社 in Japanese", "用語集：Acme Corp → アクメ社（日本語）". */
    static final Pattern GLOSSARY_ADD = Pattern.compile("(?i)^\\s*glossary\\s+add\\s+(?:\"(?<term>[^\"]+)\"|(?<term2>\\S+))\\s+(?<lang>[\\p{L}-]+)\\s+(?:\"(?<rendering>[^\"]+)\"|(?<rendering2>.+?))\\s*$");
    static final Pattern GLOSSARY_EN = Pattern.compile("(?i)^\\s*(?:in\\s+the\\s+glossary,?\\s+|for\\s+the\\s+glossary,?\\s+)?(?:render|translate|write|put)\\s+(?<term>.+?)\\s+as\\s+(?<rendering>.+?)\\s+in\\s+(?<lang>[\\p{L}-]+)\\s*(?:from now on)?\\s*[.!]?\\s*$");
    static final Pattern GLOSSARY_JA = Pattern.compile("^\\s*用語集\\s*[：:]\\s*(?<term>.+?)\\s*(?:→|は)\\s*(?<rendering>.+?)\\s*[（(](?<lang>日本語|英語|中国語|韓国語|ドイツ語|フランス語|スペイン語|ポルトガル語|イタリア語|ロシア語)[)）]\\s*$");
    static final Pattern GLOSSARY_LIST = Pattern.compile("(?i)^\\s*(?:(?:show|list)\\s+(?:me\\s+)?(?:the\\s+)?glossary|glossary(?:\\s+list)?|what(?:'s| is) in the glossary\\??|用語集(?:を見せて|を表示|一覧)?)\\s*[.?]?\\s*$");

    /**
     * A translation asked for: what, the language code, the output format ("" for the input's), the tier ("" when not said), the
     * translator's model entry ("" for the model this chat talks to), the reader ("" for the library's judge seat, auto, or an entry's name).
     */
    record Ask(String what, String lang, String as, String quality, String translator, String judge) {
        Ask(String what, String lang, String as, String quality) { this(what, lang, as, quality, "", ""); }
    }

    /**
     * The reader named, as people say it: "checked by kimi", "read by the strongest other model", "have Kimi K3 do a thorough check",
     * "with deepseek checking". The verb or the adjective before "check" is the tier when no tier word stands on its own.
     */
    static final Pattern READER = Pattern.compile("(?i)\\b(?:(?<v1>checked|read|reviewed|judged|verified)\\s+by\\s+(?<n1>the\\s+strongest\\s+other\\s+model|[\\w.-]+(?:\\s+[\\w.-]+){0,2})"
            + "|(?:have|having|let|get|with)\\s+(?<n2>[\\w.-]+(?:\\s+[\\w.-]+){0,2}?)\\s+(?:do|doing|run|running|make|making|perform)?\\s*(?:a|an|the)?\\s*(?<v2>thorough|careful|full|quick|second|)\\s*(?:check|checking|review|reviewing|read|reading|verification)\\b)");
    /** The translator named: "translated by kimi", "using DeepSeek V4.1 Flash", "via gemma26", or a plain "by kimi" once the reader's phrase is out. */
    static final Pattern TRANSLATOR = Pattern.compile("(?i)\\b(?:translated\\s+(?:by|using|with|via|on)|using|via|by)\\s+(?<n>[\\w.-]+(?:\\s+[\\w.-]+){0,2})");
    /** Words a name never ends in: what follows a name in a sentence. */
    static final Set<String> NAME_STOP = Set.of("and", "to", "into", "in", "do", "doing", "for", "as", "with", "then", "the", "a", "an", "please", "quick", "checked", "reviewed", "thorough", "check", "it", "this", "that", "have", "having", "model", "models");
    /** The sentence the page puts under the words for a file posted with them ("I attached X; it is on the shelves as Y"): taken off before the words are read. */
    static final Pattern ATTACHED = Pattern.compile("(?s)\\s*\\n\\s*I attached .*$");
    static final Pattern SHELVED_AS = Pattern.compile("it is on the shelves as (\\S+?\\.md)");
    /** Words that only point at the file posted with them: the answer to "Which file?". */
    static final Pattern BARE = Pattern.compile("(?i)^\\s*(?:here(?: it is| you go)?|attached|(?:this|that|the)(?: one| file| epub| pdf| document| book| text)?|it)\\s*[.!]?\\s*$");
    /** "<lead> <what> translated into <lang> …": "I would like this epub translated into English", "have it translated to Japanese". */
    static final Pattern EN_PASSIVE = Pattern.compile("(?i)^\\s*(?:.*?\\b(?:like|want|need|have|get|could you|can you|would you|please)\\s+)?(?<what>.+?)\\s+translated\\s+(?:into|to|in)\\s+(?<lang>[\\p{L}-]+)(?<rest>\\b.*)$");
    /** "an English translation of <what>". */
    static final Pattern EN_OF = Pattern.compile("(?i)^\\s*(?:.*?\\b(?:like|want|need|make|give me|please|get)\\s+)?(?:an?\\s+)?(?<lang>[\\p{L}-]+)\\s+translation\\s+of\\s+(?<what>.+?)(?<rest>(?:,|;|\\.(?=\\s|$)).*)?$");

    /** The ask in the words, or null when they are not one. */
    static Ask parse(String words) {
        if (words == null) return null;
        words = withoutAttachment(words);
        Matcher m = EN.matcher(words.strip());
        String what = null, lang = null, rest = null;
        for (Pattern p : new Pattern[]{EN, EN_PASSIVE, EN_OF}) {
            m = p.matcher(words.strip());
            if (!m.matches()) continue;
            String code = Translate.code(m.group("lang"));
            if (!Translate.LANGUAGES.containsKey(code)) continue;
            what = m.group("what"); lang = code; rest = m.group("rest") == null ? "" : m.group("rest");
            break;
        }
        if (what == null) { m = JA.matcher(words.strip()); if (!m.matches()) return null; what = m.group("what"); lang = JA_NAMES.get(m.group("lang")); rest = m.group("rest"); }
        // the models named: the reader's phrase first ("checked by kimi"), so its verb is not read as the tier; then what "by" is left names the translator
        String judge = "", translator = "", verb = "", both = what + " " + rest;
        Matcher r = READER.matcher(both);
        if (r.find()) {
            String n = r.group("n1") != null ? r.group("n1") : r.group("n2");
            verb = (r.group("v1") != null ? r.group("v1") : r.group("v2") == null || r.group("v2").isEmpty() ? "checked" : r.group("v2")).toLowerCase(Locale.ROOT);
            judge = n.toLowerCase(Locale.ROOT).startsWith("the strongest") || n.equalsIgnoreCase(Auto.NAME) ? Auto.NAME : trimName(n);
            both = r.replaceAll(" ");
        }
        Matcher tr = TRANSLATOR.matcher(both);
        if (tr.find()) translator = trimName(tr.group("n"));
        String all = both.toLowerCase(Locale.ROOT);
        String quality = all.contains("thorough") || all.contains("全文査読") || all.contains("徹底") ? Translation.THOROUGH : all.contains("reviewed") || all.contains("査読付き") || all.contains("レビュー付き") ? Translation.REVIEWED : all.contains("checked") || all.contains("確認付き") || all.contains("チェック付き") || all.contains("確認あり") ? Translation.CHECKED : all.contains("quick") || all.contains("簡易") ? Translation.QUICK : "";
        if (quality.isEmpty() && !verb.isEmpty()) quality = switch (verb) { case "thorough" -> Translation.THOROUGH; case "reviewed", "careful", "full", "second" -> Translation.REVIEWED; case "quick" -> Translation.QUICK; default -> Translation.CHECKED; };
        String as = "";
        Matcher a = AS.matcher(rest + " " + what);
        if (a.find()) { String f = (a.group(1) != null ? a.group(1) : a.group(2)).toLowerCase(Locale.ROOT); as = switch (f) { case "markdown" -> "md"; case "text" -> "txt"; case "word" -> "docx"; default -> f; }; }
        what = READER.matcher(what).replaceAll(" ");
        what = TRANSLATOR.matcher(what).replaceAll(" ").replaceAll("\\s*,(\\s*,)+", ",").replaceAll("[\\s,]+$", "").replaceAll("\\s{2,}", " ").strip();
        what = what.replaceAll("(?i)\\s*,?\\s*\\b(checked|quick|reviewed|thorough)\\b\\s*$", "").replaceAll("(?i)^(?:please\\s+)?(?:have|get)\\s+", "").replaceAll("(?i)\\s+as\\s+(?:an?\\s+)?(md|markdown|txt|text|pdf|docx|word|epub)\\s*$", "").replaceAll("^[\"“「]|[\"”」]$", "").strip();
        return new Ask(what, lang, as, quality, translator, judge);
    }

    /** Whether the words name a language the library translates into. */
    static boolean namesALanguage(String w) {
        String lw = w.toLowerCase(Locale.ROOT);
        for (String name : Translate.LANGUAGES.values()) if (lw.matches("(?s).*\\b" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b.*")) return true;
        return false;
    }

    static String withoutAttachment(String words) { return words == null ? "" : ATTACHED.matcher(words).replaceFirst(""); }

    /** The shelf entry the page's attachment sentence names, or null. */
    static String attachedId(String words) { if (words == null) return null; Matcher m = SHELVED_AS.matcher(words); return m.find() ? m.group(1) : null; }

    /** A document on the shelves by its entry: its file where it lies when it is still there, else the shelved text as Markdown. */
    static Path fromShelves(LibraryStore store, String id) throws IOException {
        Path raw = RawCapture.find(store, id);
        if (raw == null) return null;
        String[] parts = RawCapture.read(raw);
        if (parts[0].startsWith("file://")) { Path f = Path.of(parts[0].substring(7)); if (Files.isRegularFile(f)) return f; }
        Path copy = Translation.inbox(store).resolve((parts[1].isBlank() ? "document" : parts[1]).replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_") + ".md");
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, (parts[1].isBlank() ? "" : "# " + parts[1] + "\n\n") + parts[2], StandardCharsets.UTF_8);
        return copy;
    }

    /** A name as captured, without the words of the sentence that followed it ("kimi k3 do" → "kimi k3"). */
    static String trimName(String n) {
        List<String> parts = new ArrayList<>(List.of(n.strip().split("\\s+")));
        while (!parts.isEmpty() && NAME_STOP.contains(parts.get(parts.size() - 1).toLowerCase(Locale.ROOT).replaceAll("[.,;:]+$", ""))) parts.remove(parts.size() - 1);
        return String.join(" ", parts).replaceAll("[.,;:]+$", "");
    }

    /** No entry matches the words: what can run now, how the model named is added and keyed, and the way with what is here. */
    static String noEntry(String words) { return ModelEntries.noMatch(words); }

    /** Whether the words ask for a translation of a file: the page that takes an upload keeps the file for the run then. */
    static boolean asksTranslation(String words) { Ask a = parse(words); return a != null; }

    /** Whether the words only point at the file posted with them while a translation ask waits for its file ("this file" after "Which file?"). */
    static boolean answersWhichFile(String words, Librarian.Session session) {
        return session != null && words != null && BARE.matcher(withoutAttachment(words).strip()).matches() && session.translateAsk() != null;
    }

    /** Words about translating a document that the turn could not read as an ask: never the model's to improvise on. */
    static final Pattern MENTIONS = Pattern.compile("(?i)\\btranslat|翻訳|訳して");

    /** The turn's own answer to words about a translation it could not read: what it understood, and the shapes it reads. */
    static String couldNotRead(String w, boolean fileThere) {
        String lang = "";
        for (var e : Translate.LANGUAGES.entrySet()) if (w.toLowerCase(Locale.ROOT).matches("(?s).*\\b" + Pattern.quote(e.getValue().toLowerCase(Locale.ROOT)) + "\\b.*")) { lang = e.getValue(); break; }
        StringBuilder b = new StringBuilder("I read this as a translation ask but could not tell ");
        b.append(lang.isEmpty() ? "which language to translate into" : "which document" + (fileThere ? " of the ones named" : "")).append(". Say it as \"translate this file into ").append(lang.isEmpty() ? "Japanese" : lang).append("\" with the file attached, or name a document on the shelves by its title, or give its path on the library's machine");
        b.append("; \"checked\", \"reviewed\" or \"thorough\" picks the tier, \"translated by <model>\" and \"checked by <model>\" the models. The formats read: txt, md, pdf, docx, epub. ").append(ModelEntries.canRunNow());
        return b.toString();
    }

    /** The reply, or null when the words are not such an ask nor a glossary line. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        if (words == null) return null;
        String w = withoutAttachment(words).strip();
        String attachedId = attachedId(words);
        boolean writer = Patrons.mayWrite(store, patron);
        if (GLOSSARY_LIST.matcher(w).matches()) return Glossary.describe(store, "");
        Matcher g = GLOSSARY_ADD.matcher(w);
        if (!g.matches()) g = GLOSSARY_EN.matcher(w);
        if (!g.matches()) g = GLOSSARY_JA.matcher(w);
        if (g.matches()) {
            String term = group(g, "term", "term2"), rendering = group(g, "rendering", "rendering2"), lang = g.group("lang");
            String code = JA_NAMES.containsKey(lang) ? JA_NAMES.get(lang) : Translate.code(lang);
            if (!Translate.LANGUAGES.containsKey(code)) return null;
            if (!writer) return "Only the person who keeps this library and those who may write to it change its glossary.";
            return Glossary.add(store, term.replaceAll("^[\"“「]|[\"”」]$", ""), code, rendering.replaceAll("^[\"“「]|[\"”」]$", ""));
        }
        Ask ask = parse(w);
        if (ask == null && session != null && BARE.matcher(w).matches() && (attachedId != null || (session.att != null && session.att.hasNonNull("path")))) {   // "this file", with the file, after "Which file?": the file posted is the one
            Ask kept = session.translateAsk();
            if (kept != null) ask = new Ask("this file", kept.lang(), kept.as(), kept.quality(), kept.translator(), kept.judge());
        }
        boolean fileThere = attachedId != null || (session != null && session.att != null);
        if (ask == null && MENTIONS.matcher(w).find() && (fileThere || w.toLowerCase(Locale.ROOT).matches("(?s).*\\b(this|that|the|my) (file|epub|pdf|docx?|document|book|text|notes?)\\b.*") || namesALanguage(w))) return couldNotRead(w, fileThere);
        if (ask == null) return null;
        String name = ReadIn.name(ask.lang());
        if (!writer) return "Only the person who keeps this library and those who may write to it have documents translated: the translation goes onto the shelves.";
        Path file = resolve(store, session, patron, ask.what());
        if (file == null && attachedId != null) file = fromShelves(store, attachedId);
        if (file == null) {
            if (session != null) session.translateAsk(ask);   // kept for the file that comes next
            return "Which file? Attach it to this message (\"this file\" with it is enough: the ask is kept), name a document on the shelves by its title, or give its path on the library's machine. The formats read: txt, md, pdf, docx, epub.";
        }
        if (session != null) session.translateAsk(null);
        if (!Translation.anyModel()) return Translation.NO_MODEL;
        String model = Translation.configuredSeat()[1];
        // the translator: the entry the words name, else the model this chat talks to (/model), else the library's default; the reader: the words, else the judge seat
        String translator = session.model(), judge = "";
        if (!ask.translator().isEmpty()) { ModelEntries.Entry e = ModelEntries.resolve(ask.translator()); if (e == null) return noEntry(ask.translator()); translator = e.name(); }
        if (translator.equalsIgnoreCase(ModelEntries.DEFAULT)) translator = "";
        if (ask.judge().equalsIgnoreCase(Auto.NAME)) judge = Auto.NAME;
        else if (!ask.judge().isEmpty()) { ModelEntries.Entry e = ModelEntries.resolve(ask.judge()); if (e == null) return noEntry(ask.judge()); judge = e.name(); }
        Translation.Plan plan;
        try { plan = Translation.plan(store, file, ask.lang(), ask.as(), ask.quality().isEmpty() ? Translation.DEFAULT : ask.quality(), model, translator, judge); }
        catch (IOException e) { return e.getMessage(); }
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode a = Translation.args(file, file.getFileName().toString(), ask.lang(), plan.as(), plan.quality(), null, null, "chat", judge, translator, "");
        String head = "\"" + file.getFileName() + "\" has " + plan.units() + (plan.units() == 1 ? " unit" : " units") + " with words (" + String.format(Locale.ROOT, "%,d", plan.chars()) + " characters), written in " + ReadIn.name(plan.from()) + ". Into " + name + " as " + plan.as() + ": " + plan.said() + ".";
        if (!translator.isEmpty() || !judge.isEmpty()) head += (translator.isEmpty() ? "" : " Translated by " + translator + ".") + (judge.isEmpty() ? "" : judge.equals(Auto.NAME) ? " Checked by the strongest other model the library has." : " Checked by " + judge + ".");
        long until = System.currentTimeMillis() + Librarian.OFFER_MINUTES * 60_000L;
        if (ask.quality().isEmpty()) {
            String question = "Checked, quick, reviewed or thorough? Checked, the default, adds the program's checks and scores to one pass; quick is the one pass alone; reviewed adds a second reading by the judge seat of the flagged units and a sample of the rest; thorough reads every unit. What it cannot settle is marked.";
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of(Translation.CHECKED, Translation.QUICK, Translation.REVIEWED, Translation.THOROUGH), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + question + " (" + job + "; the choice starts it)";
        }
        if (plan.seconds() >= ASK_FIRST_SECONDS) {
            String question = "That is " + plan.saidFor() + ". Go ahead? (yes/no)";
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of("yes", "no"), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + question + " (" + job + ")";
        }
        String job = jobs.submit(Translation.JOB_KIND, patron == null ? "" : patron.did(), a);
        session.watch(job);
        return head + " Translating it now, " + plan.quality() + " (" + job + "): " + Translation.duration(plan.seconds()) + ". This chat says when it is done; the run's page has the file, its note and where both are on the shelves.";
    }

    private static String group(Matcher m, String a, String b) { String v = null; try { v = m.group(a); } catch (IllegalArgumentException ignored) { } if (v == null) try { v = m.group(b); } catch (IllegalArgumentException ignored) { } return v == null ? "" : v.strip(); }

    /**
     * The file the words mean: the one attached to this turn or an earlier one ("this file"); a path on the library's machine, from the
     * keeper; else a document on the shelves by its title, read where it lies when its file is still there, else from the shelved text.
     */
    static Path resolve(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String what) throws IOException {
        String w = what == null ? "" : what.strip();
        boolean thisOne = w.isEmpty() || THIS.matcher(w).matches();
        if (session != null) {
            if (session.att != null && session.att.hasNonNull("path") && (thisOne || session.att.path("name").asText("").equalsIgnoreCase(w))) return Path.of(session.att.get("path").asText());
            if (thisOne) for (int i = session.messages.size() - 1; i >= 0; i--) { JsonNode att = session.messages.get(i).path("att"); if (att.hasNonNull("path") && Files.isRegularFile(Path.of(att.get("path").asText()))) return Path.of(att.get("path").asText()); }
        }
        if (thisOne) return null;
        if (w.matches("^(/|~/|[A-Za-z]:\\\\|\\./|\\.\\./).*") || w.contains("/") || w.contains("\\")) {
            if (patron == null || !patron.person()) return null;   // a path on the machine is the keeper's to name
            Path p = Path.of(w.replaceFirst("^~", System.getProperty("user.home")));
            return Files.isRegularFile(p) ? p : null;
        }
        // a document on the shelves, by its title
        List<LibrarianIndex.Hit> hits;
        try { hits = new LibrarianIndex(store).search(w, 5, null, "raw"); } catch (Exception e) { hits = List.of(); }
        for (LibrarianIndex.Hit h : hits) {
            Path raw = RawCapture.find(store, h.id());
            if (raw == null) continue;
            String[] parts = RawCapture.read(raw);
            String title = parts[1], locator = parts[0], lw = w.toLowerCase(Locale.ROOT);
            String head = parts[2].substring(0, Math.min(400, parts[2].length())).toLowerCase(Locale.ROOT);   // a document named by its heading, when the shelf's title is its file name
            if (!title.toLowerCase(Locale.ROOT).contains(lw) && !locator.toLowerCase(Locale.ROOT).endsWith("/" + lw) && !locator.toLowerCase(Locale.ROOT).endsWith("/" + lw.replace(' ', '_')) && !head.contains(lw)) continue;
            if (locator.startsWith("file://")) { Path f = Path.of(locator.substring(7)); if (Files.isRegularFile(f)) return f; }
            // the file is gone or was never here: the shelved text, as Markdown, is what there is
            Path copy = Translation.inbox(store).resolve((title.isBlank() ? "document" : title).replaceAll("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]", "_") + ".md");
            Files.createDirectories(copy.getParent());
            Files.writeString(copy, (title.isBlank() ? "" : "# " + title + "\n\n") + parts[2], StandardCharsets.UTF_8);
            return copy;
        }
        return null;
    }
}
