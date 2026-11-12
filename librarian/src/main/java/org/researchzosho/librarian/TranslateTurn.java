package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * "Translate this file into Japanese", said in the chat with a file attached, or naming a file on the shelves or a path: the library's own
 * turn, no model asked. The ask, the tier, the notes and "this file" are read in the person's own language ({@link ChatWords}: "traduis ce
 * fichier en japonais, vérifié"), and every sentence the turn says is in the language the chat speaks ({@link Chrome}); the phrases that
 * name a model ("translated by kimi", "checked by kimi") are read in English. The file is cut and counted, the time is said first ("about 2 minutes quick, 6 checked"), and the translation
 * runs as a job on the person's word: which tier when the words did not say, a yes when they did and the run is long. "Checked" in the
 * words is the checked tier; "as a PDF" or "as epub" the output format; "with the notes at the end" (訳注は末尾に) lists what the checks still
 * doubt at the end of the document, "no notes" (訳注なし) keeps it to the note beside it, and without either the setting decides. A line for the glossary ("render Acme Corp as アクメ社 in
 * Japanese", "glossary add …") is kept across documents ({@link Glossary}).
 */
final class TranslateTurn {
    private TranslateTurn() { }

    private static final ObjectMapper M = new ObjectMapper();
    static final String FIELD = "translate";
    /** A run this long or longer is not started without a yes, when the tier was named in the words. */
    static final long ASK_FIRST_SECONDS = 5 * 60;

    /**
     * What a place in an ask takes: the document named (as little as will do), a language by any of its names, the output format. The
     * asks themselves are the words files' ("translate.ask": "translate this file into Japanese", "translate saros notes.md to German as a
     * PDF, checked", "このファイルを日本語に翻訳して", "traduis ce fichier en japonais", "переведи этот файл на японский").
     */
    static Map<String, String> places() { return Map.of("what", ".+?", "lang", ChatWords.languageNames(), "term", ".+?", "rendering", ".+?"); }
    static final Map<String, String> FORMAT = Map.of("fmt", "(?:md|markdown|txt|text|pdf|docx|word|epub)(?![\\p{IsLatin}\\p{N}])");
    /** "glossary add Acme Corp ja アクメ社": the command form, in any language; "render Acme Corp as アクメ社 in Japanese" and its kind are the words files' ("glossary.add"). */
    static final Pattern GLOSSARY_ADD = Pattern.compile("(?i)^\\s*glossary\\s+add\\s+(?:\"(?<term>[^\"]+)\"|(?<term2>\\S+))\\s+(?<lang>[\\p{L}-]+)\\s+(?:\"(?<rendering>[^\"]+)\"|(?<rendering2>.+?))\\s*$");

    /**
     * A translation asked for: what, the language code, the output format ("" for the input's), the tier ("" when not said), the
     * translator's model entry ("" for the model this chat talks to), the reader ("" for the library's judge seat, auto, or an entry's
     * name), and where what the checks still doubt goes (end, off; "" when not said, for the setting's choice).
     */
    record Ask(String what, String lang, String as, String quality, String translator, String judge, String notes, String from) {
        Ask(String what, String lang, String as, String quality) { this(what, lang, as, quality, "", "", "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge) { this(what, lang, as, quality, translator, judge, "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge, String notes) { this(what, lang, as, quality, translator, judge, notes, ""); }
    }

    /** What a {lang} place takes where a person names the language the document is written in. */
    static Map<String, String> fromPlaces() { return Map.of("lang", ChatWords.sourceLanguageNames()); }

    /** The language a person says the document is written in, as its code, and their words without the saying of it; NONE when they say none. */
    record From(String code, String rest) { static final From NONE = new From("", ""); }

    /**
     * The saying of it in the words, in any language's phrase ("from German", 「ドイツ語から」, "aus dem Deutschen", "язык оригинала —
     * польский": the words files' translate.from). A phrase counts only where what stands in its place is a language the program
     * knows, and only that phrase is taken out; a stop the saying leaves behind when it was a sentence of its own goes with it.
     */
    static From fromSaid(String words) {
        String w = ChatWords.nfkc(words);
        List<String> phrases = new ArrayList<>(), patterns = new ArrayList<>();
        for (String e : ChatWords.entries("translate.from")) { List<String> l = ChatWords.isPattern(e) ? patterns : phrases; if (!l.contains(e)) l.add(e); }
        phrases.sort(Comparator.comparingInt(String::length).reversed());   // the longest first, whatever file it is from: "depuis le {lang}" before "du {lang}"
        phrases.addAll(patterns);
        for (String e : phrases) {
            Pattern p;
            try { p = ChatWords.isPattern(e) ? ChatWords.pattern(e, null) : ChatWords.pattern("re:(?iu)[\\s,;、，]*" + ChatWords.phrase(e, fromPlaces()), null); }
            catch (PatternSyntaxException x) { continue; }
            List<String> groups = ChatWords.groups(p.pattern());
            Matcher m = p.matcher(w);
            while (m.find()) {
                String code = groups.contains("lang") && m.group("lang") != null ? ChatWords.sourceLanguage(m.group("lang"))
                        : groups.contains("stem") && m.group("stem") != null ? ChatWords.sourceLanguageByStem(m.group("stem")) : "";
                if (code.isEmpty()) continue;
                String rest = ChatWords.tidied(w.substring(0, m.start()) + " " + w.substring(m.end()));
                rest = rest.replaceAll("^[.。:：—–-]+\\s*", "").replaceAll("([.。!?！？])[\\s.。:：—–-]+$", "$1").replaceAll("[\\s:：—–-]+$", "");
                return new From(code, ChatWords.tidied(rest));
            }
        }
        return From.NONE;
    }

    static String fromIn(String words) { return fromSaid(words).code(); }

    /** Where the person's words put what the checks still doubt: end, off, or "" when they say neither (the setting's choice). */
    static String notesIn(String words) {
        String w = " " + withoutAttachment(words);   // "no notes" is read before "notes at the end": "without the notes at the end" holds both
        if (ChatWords.has("notes.off", w)) return Translation.NOTES_OFF;
        if (ChatWords.has("notes.end", w)) return Translation.NOTES_END;
        return "";
    }

    /** The words without what they say about the notes: "checked, with the notes at the end" → "checked". */
    static String withoutNotes(String words) { return ChatWords.without(words, "notes.off", "notes.end"); }

    /** The tier the words name, in any language ("thorough", "確認付き", "vérifié"), or "" when they name none. */
    static String tierIn(String words) { return ChatWords.tier(words); }

    /** Whether the words only point at the file posted with them: "this file", "it", "ce fichier", "このファイル". */
    static boolean pointsAtTheFile(String words) { return ChatWords.thisFile(words); }

    /** What the turn says when the ask names no file it can find. */
    static final String WHICH_FILE = "Which file? Attach it to this message (\"this file\" with it is enough: the ask is kept), name a document on the shelves by its title, or give its path on the library's machine. The formats read: txt, md, pdf, docx, epub.";

    /** The chat's words to have the notes at the end, which {@link #notesIn} reads back. */
    static final String NOTES_OFF_ASKED = "What the checks still doubt is listed in the note beside the translation; to have it numbered at the end of the document too, add \"with the notes at the end\" to your answer.",
            NOTES_END_ASKED = "What the checks still doubt is listed in the note beside the translation and numbered at the end of the document; add \"no notes\" to your answer to keep the document to the translation alone.",
            NOTES_HOW = "To have them numbered at the end of the document too, say \"with the notes at the end\" when you ask for a translation.";

    /** The chat's question about the tier, in the words the person answers it with. */
    static final String TIER_QUESTION = "Checked, quick, reviewed or thorough? Checked, the default, adds the program's checks and scores to one pass; quick is the one pass alone; reviewed adds a second reading by the judge seat of the flagged units and a sample of the rest; thorough reads every unit.";

    /** The chat's question about the tier, ending with where the doubts go as filed and the words that change it. */
    static String tierQuestion(String notes) {
        return t(TIER_QUESTION) + " " + t(Translation.NOTES_END.equals(notes) ? NOTES_END_ASKED : NOTES_OFF_ASKED);
    }

    /** Where a run filed with {@code notes} at {@code quality} puts what the checks still doubt, in the person's language; with off, the words that ask for the end. */
    static String notesFiled(String notes, String quality) {
        String said = Translation.notesSaid(notes, quality);
        return t(said) + (said.equals(Translation.NOTES_OFF_SAID) ? " " + t(NOTES_HOW) : "");
    }

    private static String t(String en, Object... args) { return Chrome.now().t(en, args); }

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
    /** The sentence the page puts under the words for a file posted with them ("I attached X; it is on the shelves as Y"): taken off before the words are read, so it stays in English, the one form it is read in. */
    static final Pattern ATTACHED = Pattern.compile("(?s)\\s*\\n\\s*I attached .*$");
    static final Pattern SHELVED_AS = Pattern.compile("it is on the shelves as (\\S+?\\.md)");

    /** The ask in the words, or null when they are not one. */
    static Ask parse(String words) { return parse(words, true); }

    /** The same; with {@code readFrom} false no phrase is read as the language the document is written in: a document's own name may hold one ("Notes from German class"). */
    static Ask parse(String words, boolean readFrom) {
        if (words == null) return null;
        words = withoutAttachment(words);
        From said = readFrom ? fromSaid(words) : From.NONE;   // the language the person says it is written in wins over the program's reading of it
        String from = said.code();
        if (!from.isEmpty()) words = said.rest();   // out before the ask is read, so the language translated into is never taken from it
        Map<String, String> m = ChatWords.ask("translate.ask", words, places(), true, g -> !ChatWords.language(g.getOrDefault("lang", "")).isEmpty() && g.get("what") != null);
        if (m == null) return null;
        String what = m.get("what"), lang = ChatWords.language(m.get("lang")), rest = m.getOrDefault("rest", "");
        // the notes, read from the whole of the words ("訳注付き" holds the 訳 the Japanese pattern ends at), then taken out of the rest
        String notes = notesIn(words);
        if (!notes.isEmpty()) { what = withoutNotes(what); rest = " " + withoutNotes(rest); }
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
        String quality = tierIn(both);
        if (quality.isEmpty() && !verb.isEmpty()) quality = switch (verb) { case "thorough" -> Translation.THOROUGH; case "reviewed", "careful", "full", "second" -> Translation.REVIEWED; case "quick" -> Translation.QUICK; default -> Translation.CHECKED; };
        String as = "";
        Map<String, String> a = ChatWords.find("format.as", rest + " " + what, FORMAT);
        if (a != null && a.get("fmt") != null) { String f = a.get("fmt").toLowerCase(Locale.ROOT); as = switch (f) { case "markdown" -> "md"; case "text" -> "txt"; case "word" -> "docx"; default -> f; }; what = ChatWords.without(what, FORMAT, "format.as"); }
        what = READER.matcher(what).replaceAll(" ");
        what = TRANSLATOR.matcher(what).replaceAll(" ").replaceAll("\\s*,(\\s*,)+", ",").replaceAll("[\\s,]+$", "").replaceAll("\\s{2,}", " ").strip();
        what = what.replaceAll("(?i)\\s*,?\\s*\\b(checked|quick|reviewed|thorough)\\b\\s*$", "").replaceAll("(?i)^(?:please\\s+)?(?:have|get)\\s+", "").replaceAll("(?i)\\s+as\\s+(?:an?\\s+)?(md|markdown|txt|text|pdf|docx|word|epub)\\s*$", "").replaceAll("^[\"“「]|[\"”」]$", "").strip();
        // a tier said after the file with no comma, where the language names the file last ("traduci in inglese questo file con controllo"):
        // taken off the name when what is left is the file posted, or the tier's phrase is more than a word (an adjective alone may be the name's: "la guida rapida")
        String bare = ChatWords.withoutAtEnd(what, "tier.quick", "tier.checked", "tier.reviewed", "tier.thorough"), whole = ChatWords.nfkc(what).strip();
        if (!bare.isEmpty() && bare.length() < whole.length() && (pointsAtTheFile(bare) || whole.substring(bare.length()).strip().replaceAll("^[,;、，]\\s*", "").matches("(?s).*\\s.*"))) what = bare;
        return new Ask(what, lang, as, quality, translator, judge, notes, from);
    }

    /** Whether the words name a language the library translates into, in any language's word for it. */
    static boolean namesALanguage(String w) { return ChatWords.namesALanguage(w); }

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
    static String noEntry(String words) { return ModelEntries.noMatch(words, Chrome.now()); }

    /** Whether the words ask for a translation of a file: the page that takes an upload keeps the file for the run then. */
    static boolean asksTranslation(String words) { Ask a = parse(words); return a != null; }

    /** Whether the words only point at the file posted with them while a translation ask waits for its file ("this file" after "Which file?"). */
    static boolean answersWhichFile(String words, Librarian.Session session) {
        return session != null && words != null && pointsAtTheFile(withoutAttachment(words).strip()) && session.translateAsk() != null;
    }

    /** Words about translating a document that the turn could not read as an ask ("translate", "翻訳", "traduis", "übersetze"): never the model's to improvise on. */
    static boolean mentions(String w) { return ChatWords.has("translate.mention", w); }

    /** The turn's own answer to words about a translation it could not read: what it understood, and the shapes it reads. */
    static String couldNotRead(String w, boolean fileThere) {
        Chrome c = Chrome.now();
        String lang = ChatWords.languageIn(w);
        String head = lang.isEmpty() ? c.t("I read this as a translation ask but could not tell which language to translate into.")
                : fileThere ? c.t("I read this as a translation ask but could not tell which document of the ones named.") : c.t("I read this as a translation ask but could not tell which document.");
        return head + " " + c.t("Say it as \"translate this file into {0}\" with the file attached, or name a document on the shelves by its title, or give its path on the library's machine; \"checked\", \"reviewed\" or \"thorough\" picks the tier, \"translated by <model>\" and \"checked by <model>\" the models, \"with the notes at the end\" lists what the checks still doubt at the end of the document.", c.languageName(lang.isEmpty() ? "ja" : lang))
                + " " + c.t("The formats read: txt, md, pdf, docx, epub.") + " " + ModelEntries.canRunNow(c);
    }

    /** The reply, or null when the words are not such an ask nor a glossary line. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        if (words == null) return null;
        String w = withoutAttachment(words).strip();
        String attachedId = attachedId(words);
        boolean writer = Patrons.mayWrite(store, patron);
        Chrome c = Chrome.now();
        if (ChatWords.ask("glossary.list", w, Map.of()) != null) return Glossary.describe(store, "", c);
        Matcher g = GLOSSARY_ADD.matcher(w);
        Map<String, String> said = g.matches() ? Map.of("term", group(g, "term", "term2"), "rendering", group(g, "rendering", "rendering2"), "lang", g.group("lang"))
                : ChatWords.ask("glossary.add", w, places(), false, x -> !ChatWords.language(x.getOrDefault("lang", "")).isEmpty() && x.get("term") != null && x.get("rendering") != null);
        if (said != null) {
            String code = ChatWords.language(said.get("lang"));
            if (!Translate.LANGUAGES.containsKey(code)) return null;
            if (!writer) return c.t("Only the person who keeps this library and those who may write to it change its glossary.");
            return Glossary.add(store, said.get("term").strip().replaceAll("^[\"“「«„]|[\"”」»“]$", ""), code, said.get("rendering").strip().replaceAll("^[\"“「«„]|[\"”」»“]$", ""), c);
        }
        Ask ask = parse(w);
        if (ask == null && session != null && pointsAtTheFile(w) && (attachedId != null || (session.att != null && session.att.hasNonNull("path")))) {   // "this file", with the file, after "Which file?": the file posted is the one
            Ask kept = session.translateAsk();
            if (kept != null) ask = new Ask("this file", kept.lang(), kept.as(), kept.quality(), kept.translator(), kept.judge(), kept.notes(), kept.from());
        }
        boolean fileThere = attachedId != null || (session != null && session.att != null);
        if (ask == null && mentions(w) && (fileThere || w.toLowerCase(Locale.ROOT).matches("(?s).*\\b(this|that|the|my) (file|epub|pdf|docx?|document|book|text|notes?)\\b.*") || namesALanguage(w))) return couldNotRead(w, fileThere);
        if (ask == null) return null;
        String name = c.languageName(ask.lang());
        if (!writer) return c.t("Only the person who keeps this library and those who may write to it have documents translated: the translation goes onto the shelves.");
        Path file = resolve(store, session, patron, ask.what());
        if (file == null && attachedId == null && !ask.from().isEmpty()) {   // the phrase may belong to the document's own name: read the words without it
            Ask plain = parse(w, false);
            Path named = plain == null ? null : resolve(store, session, patron, plain.what());
            if (named != null) { ask = plain; file = named; name = c.languageName(ask.lang()); }
        }
        if (file == null && attachedId != null) file = fromShelves(store, attachedId);
        if (file == null) {
            if (session != null) session.translateAsk(ask);   // kept for the file that comes next
            return c.t(WHICH_FILE);
        }
        if (session != null) session.translateAsk(null);
        if (!Translation.anyModel()) return c.t(Translation.NO_MODEL);
        String model = Translation.configuredSeat()[1];
        // the translator: the entry the words name, else the model this chat talks to (/model), else the library's default; the reader: the words, else the judge seat
        String translator = session.model(), judge = "";
        if (!ask.translator().isEmpty()) { ModelEntries.Entry e = ModelEntries.resolve(ask.translator()); if (e == null) return noEntry(ask.translator()); translator = e.name(); }
        if (translator.equalsIgnoreCase(ModelEntries.DEFAULT)) translator = "";
        if (ask.judge().equalsIgnoreCase(Auto.NAME)) judge = Auto.NAME;
        else if (!ask.judge().isEmpty()) { ModelEntries.Entry e = ModelEntries.resolve(ask.judge()); if (e == null) return noEntry(ask.judge()); judge = e.name(); }
        Translation.Plan plan;
        try { plan = Translation.plan(store, file, ask.lang(), ask.as(), ask.quality().isEmpty() ? Translation.DEFAULT : ask.quality(), model, translator, judge, ask.from()); }
        catch (Translation.Refused e) { return e.in(c); }
        catch (IOException e) { return e.getMessage(); }
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode a = Translation.args(file, file.getFileName().toString(), ask.lang(), plan.as(), plan.quality(), null, null, "chat", judge, translator, "", ask.notes(), ask.from());
        if (plan.reading() != null) a.set("pdf_preflight", plan.reading().json());   // the job's page says what the reader found
        String notes = a.path("notes").asText(Translation.NOTES_OFF);   // as filed: the words' choice, else the setting's; what the reply says comes from here
        String read = plan.readingSaid(c);
        String head = (read.isEmpty() ? "" : read + (c.code.equals("ja") || c.code.equals("zh") ? "" : " ")) + c.t(plan.units() == 1 ? "\"{0}\" has {1} unit with words ({2} characters), written in {3}. Into {4} as {5}: {6}." : "\"{0}\" has {1} units with words ({2} characters), written in {3}. Into {4} as {5}: {6}.",
                file.getFileName(), c.number(plan.units()), c.number(plan.chars()), c.languageName(plan.from()), name, plan.as(), plan.said(c));
        if (!translator.isEmpty()) head += " " + c.t("Translated by {0}.", translator);
        if (!judge.isEmpty()) head += " " + (judge.equals(Auto.NAME) ? c.t("Checked by the strongest other model the library has.") : c.t("Checked by {0}.", judge));
        long until = System.currentTimeMillis() + Librarian.OFFER_MINUTES * 60_000L;
        if (ask.quality().isEmpty()) {
            String question = tierQuestion(notes);
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of(Translation.CHECKED, Translation.QUICK, Translation.REVIEWED, Translation.THOROUGH), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + question + " " + c.t("({0}; the choice starts it)", job);
        }
        if (plan.seconds() >= ASK_FIRST_SECONDS) {
            String question = c.t("That is {0}. Go ahead? (yes/no)", plan.saidFor(c));
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of("yes", "no"), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + notesFiled(notes, plan.quality()) + " " + question + " (" + job + ")";
        }
        String job = jobs.submit(Translation.JOB_KIND, patron == null ? "" : patron.did(), a);
        session.watch(job);
        return head + " " + c.t("Translating it now, {0} ({1}): {2}. This chat says when it is done; the run's page has the file, its note and where both are on the shelves.", c.t(plan.quality()), job, Translation.duration(plan.seconds(), c)) + " " + notesFiled(notes, plan.quality());
    }

    private static String group(Matcher m, String a, String b) { String v = null; try { v = m.group(a); } catch (IllegalArgumentException ignored) { } if (v == null) try { v = m.group(b); } catch (IllegalArgumentException ignored) { } return v == null ? "" : v.strip(); }

    /**
     * The file the words mean: the one attached to this turn or an earlier one ("this file"); a path on the library's machine, from the
     * keeper; else a document on the shelves by its title, read where it lies when its file is still there, else from the shelved text.
     */
    static Path resolve(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String what) throws IOException {
        String w = what == null ? "" : what.strip();
        boolean thisOne = w.isEmpty() || pointsAtTheFile(w);
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
