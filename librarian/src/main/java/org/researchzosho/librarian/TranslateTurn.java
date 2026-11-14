package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
     * name), where what the checks still doubt goes (end, off; "" when not said, for the setting's choice), the language the person says
     * the document is written in, their direction for the translation's voice ("" for none), in their own words, and {@code leftover}: the
     * words of the ask nothing of these took ("" for none), which the chat's model is asked about ({@link #readDirection}).
     */
    record Ask(String what, String lang, String as, String quality, String translator, String judge, String notes, String from, String direction, String leftover) {
        Ask(String what, String lang, String as, String quality) { this(what, lang, as, quality, "", "", "", "", "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge) { this(what, lang, as, quality, translator, judge, "", "", "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge, String notes) { this(what, lang, as, quality, translator, judge, notes, "", "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge, String notes, String from) { this(what, lang, as, quality, translator, judge, notes, from, "", ""); }
        Ask(String what, String lang, String as, String quality, String translator, String judge, String notes, String from, String direction) { this(what, lang, as, quality, translator, judge, notes, from, direction, ""); }
        /** The same ask with the direction the chat's model read in the leftover words joined to the one the phrases found, and nothing left over. */
        Ask read(String found) { return new Ask(what, lang, as, quality, translator, judge, notes, from, found == null || found.isEmpty() ? direction : direction.isEmpty() ? found : direction + "; " + found, ""); }
    }

    /** The person's direction for a translation's voice, as the chat read it, and the words without it. */
    record Direction(String text, String rest) { static final Direction NONE = new Direction("", ""); }

    /**
     * The direction the words give for the translation's voice, tone and wording, in any language's phrase (the words files'
     * translate.direction: "as if you were Shakespeare", "this is a comedy, so keep things whimsical", 「シェイクスピア風に」, "im Stil von
     * Goethe"), each phrase the text of its {@code direction} group. Taken out of the words before the ask is read, so a word of it is never
     * read as the file, the tier or a model ("as if written by Dickens" names no translator); a part of the ask a phrase ran into at its end
     * ("…, reviewed", "…, with the notes at the end") stays in the ask. Two phrases are kept in the order they stand, joined by a semicolon.
     */
    static Direction directionSaid(String words) {
        String w = ChatWords.nfkc(words == null ? "" : words);
        List<int[]> spans = new ArrayList<>();   // each {where the words lose it, where the direction begins, where it ends}
        for (String e : ChatWords.entries("translate.direction")) {
            Pattern p;
            try { p = ChatWords.pattern(e, null); } catch (PatternSyntaxException x) { continue; }
            if (!ChatWords.groups(p.pattern()).contains("direction")) continue;
            Matcher m = p.matcher(w);
            while (m.find()) {
                int begins = m.start("direction"), ends = begins + askTrimmed(m.group("direction")).length(), cut = Math.min(m.start(), begins);   // a marker before it ("direction:") goes with it
                if (ends <= begins) continue;
                boolean merged = false;
                for (int[] s : spans) if (cut < s[2] && ends > s[0]) { s[0] = Math.min(s[0], cut); s[1] = Math.min(s[1], begins); s[2] = Math.max(s[2], ends); merged = true; break; }
                if (!merged) spans.add(new int[]{cut, begins, ends});
            }
        }
        if (spans.isEmpty()) return Direction.NONE;
        spans.sort(Comparator.comparingInt(s -> s[0]));
        List<String> said = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        int at = 0;
        for (int[] s : spans) {
            if (s[0] < at) continue;
            String d = w.substring(s[1], s[2]).strip().replaceAll("[\\s,;:、，。.!?！？]+$", "");
            if (!d.isEmpty()) said.add(d);
            rest.append(w, at, s[0]).append(' ');
            at = s[2];
        }
        rest.append(w.substring(at));
        if (said.isEmpty()) return Direction.NONE;
        String r = ChatWords.tidied(rest.toString()).replaceAll("\\s+([,;.。、，!?！？])", "$1").replaceAll("([,;、，])\\s*([.。!?！？])", "$2").replaceAll("[,;、，]+\\s*$", "").strip();
        return new Direction(String.join("; ", said), r);
    }

    /**
     * A direction's phrase without the parts of the ask it ran into at its end, each between commas: a tier ("…, reviewed"), where the
     * notes go, a format, the reader ("…, checked by kimi").
     */
    static String askTrimmed(String phrase) {
        String d = phrase;
        while (true) {
            Matcher last = Pattern.compile("[,;、，。！？]\\s*([^,;、，。！？]*)$|(?:[.!?])\\s+([^,;、，。！？.!?]*)$").matcher(d);
            if (!last.find() || !askPart(last.group(1) != null ? last.group(1) : last.group(2))) return d;
            d = d.substring(0, last.start());
        }
    }

    /** The same at the phrase's start too ("reviewed, for a ten-year-old"): for words the chat's model copied out, which may run into the ask on either side. */
    static String askTrimmedBothEnds(String phrase) {
        String d = askTrimmed(phrase);
        while (true) {
            Matcher first = Pattern.compile("^([^,;、，。！？]*)(?:[,;、，。！？]|[.!?](?=\\s))\\s*").matcher(d);
            if (!first.find() || !askPart(first.group(1))) return d.strip();
            d = d.substring(first.end());
        }
    }

    /** Whether a part between commas is the ask's own and no part of a direction: a tier, where the notes go, a format, the reader named. */
    static boolean askPart(String raw) {
        String part = raw.strip().replaceAll("^(?i)(?:and|und|et|y|e|и)\\s+", "").replaceAll("[\\s.。!?！？]+$", "");
        String bare = part.replaceAll("(?<=\\p{IsHan}|\\p{IsKatakana})\\p{IsHiragana}{1,2}$", "");   // 「簡易で」: the tier with the particle after it
        return part.isEmpty() || READER.matcher(part).matches()
                || ChatWords.whole("tier.quick", part) || ChatWords.whole("tier.checked", part) || ChatWords.whole("tier.reviewed", part) || ChatWords.whole("tier.thorough", part)
                || ChatWords.whole("tier.quick", bare) || ChatWords.whole("tier.checked", bare) || ChatWords.whole("tier.reviewed", bare) || ChatWords.whole("tier.thorough", bare)
                || (!notesIn(part).isEmpty() && withoutNotes(" " + part).replaceAll("[\\p{P}\\s]+", "").isEmpty())
                || (ChatWords.find("format.as", part, FORMAT) != null && ChatWords.without(part, FORMAT, "format.as").replaceAll("[\\p{P}\\s]+", "").isEmpty());
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
    static boolean pointsAtTheFile(String words) { return ChatWords.thisFile(words) || THIS_COMIC.matcher(ChatWords.nfkc(words).strip().replaceAll("[.!?。！？]+$", "").strip()).matches(); }

    /**
     * A comic in the person's words, read in English and Japanese in this version: the file pointed at as one ("this manga", "this
     * webtoon", この漫画), a sentence that says it is one ("this is a manga", "it's a bande dessinée", これは漫画です), and one that says
     * it is not ("it is not a comic", "it is a document", 漫画ではありません). The words for one: manga, manhwa, manhua, webtoon and web
     * comic, comic and comic book and comic strip, graphic novel, bande dessinée; 漫画, マンガ, コミック, ウェブトゥーン, 縦読み漫画.
     * A CBZ, a CBR or a ZIP of page pictures is a comic by itself; the words decide for a PDF, and say otherwise for an archive that
     * only looks like one.
     */
    static final String COMIC_WORD = "(?:manga|manhwa|manhua|webtoons?|web\\s*comics?|comic(?:\\s+(?:book|strip))?|comics|graphic\\s+novel|bandes?\\s+dessin[ée]es?)", COMIC_JA = "(?:縦読み漫画|漫画|マンガ|まんが|コミック|ウェブトゥーン|ウェブコミック)";
    static final Pattern THIS_COMIC = Pattern.compile("(?i)(?:(?:this|that|the|my)(?:\\s+attached)?\\s+(?:" + COMIC_WORD + "|volume|chapter|cbz|cbr|zip)|(?:この|その|添付の)(?:" + COMIC_JA + "|巻))"),
            NAMES_A_COMIC = Pattern.compile("(?i)\\b" + COMIC_WORD + "\\b|" + COMIC_JA),
            IS_NOT_A_COMIC = Pattern.compile("(?i)[,;、。]?\\s*(?:(?:\\b(?:this|that|it)(?:\\s+(?:file|one|pdf))?\\s*(?:is|'s)\\s+not\\s+an?\\s+" + COMIC_WORD + "\\b|\\b(?:this|that|it)(?:\\s+(?:file|one|pdf))?\\s*(?:is|'s)\\s+a\\s+document\\b|\\bnot\\s+(?:as\\s+)?an?\\s+" + COMIC_WORD + "\\b|\\bas\\s+a\\s+document\\b)"
                    + "|(?:(?:これ|それ|このファイル)は)?" + COMIC_JA + "(?:では|じゃ)(?:ない|ありません)(?:です)?|(?:(?:これ|それ|このファイル)は)?文書(?:です|だ|として))[.。]?"),
            IS_A_COMIC = Pattern.compile("(?i)[,;、。]?\\s*(?:(?:\\b(?:this|that|it)(?:\\s+(?:file|one|pdf))?\\s*(?:is|'s)\\s+an?\\s+" + COMIC_WORD + "\\b|\\bas\\s+an?\\s+" + COMIC_WORD + "\\b)"
                    + "|(?:(?:これ|それ|このファイル)は)?" + COMIC_JA + "(?:です|だよ|だ|なので|として))[.。]?");

    /** What the words say of the file: on (it is a comic), off (it is not), or "" when they say neither. */
    static String comicIn(String words, String what) {
        String w = ChatWords.nfkc(words == null ? "" : words);
        if (IS_NOT_A_COMIC.matcher(w).find()) return "off";
        if (IS_A_COMIC.matcher(w).find()) return "on";
        return what != null && THIS_COMIC.matcher(ChatWords.nfkc(what).strip()).matches() && NAMES_A_COMIC.matcher(ChatWords.nfkc(what)).find() ? "on" : "";
    }

    /**
     * The way a comic's pages are read, in the person's words: "it reads right to left", "reads from right to left", "right-to-left",
     * 右から左に読みます, and the same from the left. An edition of a manga in English, French or Korean keeps its pages running from
     * the right, and nothing on a page says so: the person does.
     */
    static final Pattern READS_RTL = Pattern.compile("(?i)[,;、。.]?\\s*[(（]?\\s*(?:\\b(?:and\\s+)?(?:it|this|that|this\\s+one|its\\s+pages|the\\s+pages)\\s+)?(?:\\b(?:reads?|is\\s+read|are\\s+read|runs?|read\\s+it)\\s+)?(?:\\bfrom\\s+)?\\bright[\\s-]+to[\\s-]+left\\b\\s*[)）]?[.。]?|[、。]?[(（]?(?:(?:これ|それ|ページ)は)?右から左(?:に|へ)?(?:読みます|読んでください|読んで|読む|です)?[)）]?[。]?"),
            READS_LTR = Pattern.compile("(?i)[,;、。.]?\\s*[(（]?\\s*(?:\\b(?:and\\s+)?(?:it|this|that|this\\s+one|its\\s+pages|the\\s+pages)\\s+)?(?:\\b(?:reads?|is\\s+read|are\\s+read|runs?|read\\s+it)\\s+)?(?:\\bfrom\\s+)?\\bleft[\\s-]+to[\\s-]+right\\b\\s*[)）]?[.。]?|[、。]?[(（]?(?:(?:これ|それ|ページ)は)?左から右(?:に|へ)?(?:読みます|読んでください|読んで|読む|です)?[)）]?[。]?"),
            MANGA = Pattern.compile("(?i)\\bmangas?\\b");

    /** The way the words say the comic's pages are read: rtl, ltr, or "" when they say neither. */
    static String readsIn(String words) {
        String w = ChatWords.nfkc(words == null ? "" : words);
        return READS_RTL.matcher(w).find() ? ComicTranslation.RTL : READS_LTR.matcher(w).find() ? ComicTranslation.LTR : "";
    }

    /** The words without what they say of the way the pages are read, the brackets it stood in with it: what is left is the ask. */
    static String withoutReads(String words) {
        String w = READS_RTL.matcher(ChatWords.nfkc(words)).replaceAll(" ");
        return READS_LTR.matcher(w).replaceAll(" ").replaceAll("[(（]\\s*[)）]", " ").replaceAll("\\s+([,;.])", "$1").replaceAll("\\s{2,}", " ").strip();
    }

    /**
     * Whether the words call the file a manga, in English: "this manga", "it is a manga". A manga's pages run from the right in
     * whatever language it is lettered, so the word says the way for a comic that is not Japanese. The Japanese words for a comic
     * (漫画, マンガ) say nothing of it: they are what any comic is called.
     */
    static boolean mangaSaid(String words, String what) {
        String w = ChatWords.nfkc(words == null ? "" : words);
        if (IS_NOT_A_COMIC.matcher(w).find()) return false;
        Matcher m = IS_A_COMIC.matcher(w);
        while (m.find()) if (MANGA.matcher(m.group()).find()) return true;
        return what != null && THIS_COMIC.matcher(ChatWords.nfkc(what).strip()).matches() && MANGA.matcher(what).find();
    }

    /** The words without the sentence that says the file is a comic or is not: what is left is the ask. */
    static String withoutComic(String words) {
        String w = IS_NOT_A_COMIC.matcher(ChatWords.nfkc(words)).replaceAll(" ");
        return IS_A_COMIC.matcher(w).replaceAll(" ").replaceAll("\\s{2,}", " ").strip();
    }

    /** What the turn says when the ask names no file it can find. */
    static final String WHICH_FILE = "Which file? Attach it to this message (\"this file\" with it is enough: the ask is kept), name a document on the shelves by its title, or give its path on the library's machine. The formats read: txt, md, pdf, docx, epub.";

    /** The chat's words to have the notes at the end, which {@link #notesIn} reads back. */
    static final String NOTES_OFF_ASKED = "What the checks still doubt is listed in the note beside the translation; to have it numbered at the end of the document too, add \"with the notes at the end\" to your answer.",
            NOTES_END_ASKED = "What the checks still doubt is listed in the note beside the translation and numbered at the end of the document; add \"no notes\" to your answer to keep the document to the translation alone.",
            NOTES_HOW = "To have them numbered at the end of the document too, say \"with the notes at the end\" when you ask for a translation.";

    /** The chat's question about the tier, in the words the person answers it with. */
    static final String TIER_QUESTION = "Checked, quick, reviewed or thorough? Checked, the default, adds the program's checks and scores to one pass; quick is the one pass alone; reviewed adds a second reading by the judge seat of the flagged units and a sample of the rest; thorough reads every unit.";

    /** The chat's question about the tier, ending with where the doubts go as filed and the words that change it. */
    /** The same question for a comic, whose tiers check balloons and fit them, and whose doubts are listed in its note alone: a comic has no end to number them at. */
    static final String COMIC_TIER_QUESTION = "Checked, quick, reviewed or thorough? Checked, the default, adds the program's checks, a sample translated back and the fit of each line to its balloon; quick reads, translates and letters; reviewed adds a second reading of the flagged balloons and a sample of the rest; thorough reads every balloon.",
            COMIC_DOUBTS = "What the checks still doubt is listed in the note beside the translation, by page and balloon; the pages hold the translation alone.";

    /** Where a comic's run at {@code quality} lists what the checks still doubt. */
    static String comicDoubts(String quality) { return t(Translation.QUICK.equals(Translation.tier(quality)) ? Translation.notesSaid(Translation.NOTES_OFF, quality) : COMIC_DOUBTS); }

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
        Direction directed = directionSaid(words);   // the person's direction for the voice, out before anything else is read from the words
        if (!directed.text().isEmpty()) words = directed.rest();
        From said = readFrom ? fromSaid(words) : From.NONE;   // the language the person says it is written in wins over the program's reading of it
        String from = said.code();
        if (!from.isEmpty()) words = said.rest();   // out before the ask is read, so the language translated into is never taken from it
        Map<String, String> m = ChatWords.ask("translate.ask", words, places(), true, g -> !ChatWords.language(g.getOrDefault("lang", "")).isEmpty() && g.get("what") != null);
        if (m == null) return null;
        String what = m.get("what"), lang = ChatWords.language(m.get("lang")), rest = m.getOrDefault("rest", "");
        // the words around the ask that its frame does not hold: before it ("For my daughter, I'd like …"), between its parts (「…を子供向けに英語に…」), after the language;
        // read with the notes phrase out, which may straddle the frame (「日本語に訳注付きで翻訳して」 holds the 訳 the pattern ends at)
        String notesSaid = notesIn(words);
        Map<String, String> framed = notesSaid.isEmpty() ? m : ChatWords.ask("translate.ask", withoutNotes(words), places(), true, g -> !ChatWords.language(g.getOrDefault("lang", "")).isEmpty() && g.get("what") != null);
        if (framed == null) framed = m;
        String around = String.join("\n", framed.getOrDefault("pre", ""), framed.getOrDefault("mid", "") + framed.getOrDefault("mid2", ""), framed.getOrDefault("rest", ""));
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
        return new Ask(what, lang, as, quality, translator, judge, notes, from, directed.text(), leftover(around));
    }

    /**
     * What the words around an ask still hold once everything the program reads in them is taken out: the reader and the translator
     * named, where the notes go, the format, the tier. "" when nothing of substance is left: only courtesy and the ask's own small words
     * (the words files' translate.filler: please, bitte, お願いします, して), punctuation, or nothing. The words are kept as the person wrote
     * them, for the sentence that says they were not used.
     */
    static String leftover(String around) {
        // piece by piece between stops, so a part of the ask taken out never joins the words on either side of it
        List<String> said = new ArrayList<>();
        boolean cjk = true;
        for (String piece : ChatWords.nfkc(around == null ? "" : around).split("[,;、，。!?！？\\n]+|\\.(?=\\s|$)")) {
            String w = " " + piece + " ";
            w = READER.matcher(w).replaceAll(" ");
            w = TRANSLATOR.matcher(w).replaceAll(" ");
            w = withoutNotes(w);
            w = " " + ChatWords.without(w, FORMAT, "format.as") + " ";
            w = ChatWords.without(w, "tier.quick", "tier.checked", "tier.reviewed", "tier.thorough");
            // without the courtesy and the ask's own small words at its edges ("して" after 「子供向けに」, "please" before); one or two kana
            // alone are a particle the ask's frame left ("確認付きで" without its tier: "で"), not something said
            String p = edgesTrimmed(w.replaceAll("\\s+", " ").strip().replaceAll("^[\\p{P}\\s]+|[\\p{P}\\s]+$", ""));
            if (!p.matches("(?s).*\\p{L}.*") || p.matches("\\p{IsHiragana}{1,2}")) continue;
            said.add(p);
            if (!p.matches("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}ー・\\p{N}]+")) cjk = false;
        }
        return String.join(cjk ? "、" : ", ", said);
    }

    /** A piece of words without the courtesy and small words (translate.filler) at its start and its end, as often as they stand there. */
    static String edgesTrimmed(String piece) {
        String p = piece;
        boolean changed = true;
        while (changed && !p.isEmpty()) {
            changed = false;
            for (String e : ChatWords.entries("translate.filler")) {
                Pattern f;
                try { f = ChatWords.pattern(e, null); } catch (PatternSyntaxException x) { continue; }
                Matcher a = f.matcher(p);
                if (a.lookingAt() && a.end() > 0) { p = p.substring(a.end()).replaceAll("^[\\p{P}\\s]+", ""); changed = true; }
                Matcher b = f.matcher(p);
                int start = -1, end = -1;
                while (b.find()) { start = b.start(); end = b.end(); }
                if (end == p.length() && start >= 0 && end > start) { p = p.substring(0, start).replaceAll("[\\p{P}\\s]+$", ""); changed = true; }
            }
        }
        return p.strip();
    }

    /** What the chat's model is asked when an ask holds words nothing else took: the part that says how the translation should read, copied, or NONE. */
    static final String DIRECTION_QUESTION = "You read a person's request to have a document translated. Copy out, exactly as the person wrote it, the part of the request that says how "
            + "the translation should read: its voice, its tone, its style, who it is for, or something to take care with, such as puns. Answer with those words alone, "
            + "character for character, or with NONE when the request says nothing of the kind.";

    /** The chat's model, asked one question: its answer, "" when it gave none; an exception when it could not be asked. A test stands it in. */
    @FunctionalInterface interface DirectionReader { String read(String entry, String system, String message) throws Exception; }
    static volatile DirectionReader DIRECTION_READER = TranslateTurn::askTheChatModel;
    /** How long the chat waits for that answer. */
    static final long READ_SECONDS = 60;

    /** The question put to the model the chat talks to: the entry named for this conversation, else the library's. */
    static String askTheChatModel(String entry, String system, String message) throws Exception {
        ModelEntries.Entry e = entry == null || entry.isBlank() || entry.equalsIgnoreCase(ModelEntries.DEFAULT) ? null : ModelEntries.get(entry);
        String[] seat = Translation.configuredSeat();
        Researcher.Drive d = Researcher.calmDrive(e == null ? seat[0] : e.drive(), e == null ? seat[1] : e.model());
        ArrayNode m = M.createArrayNode();
        m.addObject().put("role", "system").put("content", system);
        m.addObject().put("role", "user").put("content", message);
        return CompletableFuture.supplyAsync(() -> d.classify(m, 400)).get(READ_SECONDS, TimeUnit.SECONDS);
    }

    /** What the model's reading came to: the person's own words it copied out, or none; {@code failed} when it could not be asked or gave no answer. */
    record Read(String span, boolean failed) { static final Read NONE = new Read(null, false), FAILED = new Read(null, true); }

    /**
     * The leftover words read by the chat's model: it copies out the part of {@code message} that says how the translation should read, or
     * answers NONE. Its answer is taken only when it is, folded (NFKC, every run of white space one space), a contiguous part of the person's
     * message, within the direction's bound, holding a word of the leftover and not the document's name: the model picks out the person's
     * words, it never writes a direction of its own. Anything else is none. No answer, or no model, is {@link Read#FAILED}.
     */
    static Read readDirection(String entry, String message, Ask ask) {
        String answer;
        try { answer = DIRECTION_READER.read(entry, DIRECTION_QUESTION, message); }
        catch (Exception x) { return Read.FAILED; }
        if (answer == null || answer.isBlank()) return Read.FAILED;
        String a = folded(answer).replaceAll("^[\"“”「」『』'‘’«»]+|[\"“”「」『』'‘’«»]+$", "").replaceAll("[\\s.。,、;:!?！？]+$", "").strip();
        if (a.isEmpty() || a.equalsIgnoreCase("none")) return Read.NONE;
        if (!folded(message).contains(a) || a.codePointCount(0, a.length()) > Translation.DIRECTION_MAX) return Read.NONE;   // not the person's own words
        a = askTrimmedBothEnds(a).replaceAll("[\\s.。,、;:!?！？]+$", "").strip();   // a span that ran into the ask ("for a ten-year-old, reviewed"): the ask's parts stay the ask's
        if (a.isEmpty()) return Read.NONE;
        String what = folded(ask.what());
        if (!what.isEmpty() && a.contains(what)) return Read.NONE;   // the ask itself, not a part of it
        boolean shares = false;
        for (String t : folded(ask.leftover()).split("[^\\p{L}\\p{N}]+")) if (t.codePointCount(0, t.length()) >= 2 && (a.contains(t) || t.contains(a))) { shares = true; break; }
        return shares ? new Read(a, false) : Read.NONE;   // a part of the words nothing else took, never the language or the tier
    }

    static String folded(String s) { return ChatWords.nfkc(s == null ? "" : s).replaceAll("\\s+", " ").strip(); }

    /** Said when the chat's model could not be asked about words nothing else took: which words were not used, and how to give them as a direction. */
    static final String NOT_USED = "These words of yours were not used: \"{0}\". To have them guide the translation, ask again with them after \"direction:\".";

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
        String spoken = withoutAttachment(words).strip();
        String asked = withoutReads(spoken);   // "it reads right to left" is about the comic's pages, not part of the ask
        String w = mentions(asked) || pointsAtTheFile(withoutComic(asked)) ? withoutComic(asked) : asked;   // "this is a manga" beside an ask is about the file, not part of the ask
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
            if (kept != null) ask = new Ask("this file", kept.lang(), kept.as(), kept.quality(), kept.translator(), kept.judge(), kept.notes(), kept.from(), kept.direction());
        }
        boolean fileThere = attachedId != null || (session != null && session.att != null);
        if (ask == null && mentions(w) && (fileThere || w.toLowerCase(Locale.ROOT).matches("(?s).*\\b(this|that|the|my) (file|epub|pdf|docx?|document|book|text|notes?)\\b.*") || namesALanguage(w))) return couldNotRead(w, fileThere);
        if (ask == null) return null;
        String name = c.languageName(ask.lang());
        if (!writer) return c.t("Only the person who keeps this library and those who may write to it have documents translated: the translation goes onto the shelves.");
        // words nothing took: the chat's model copies out the part that says how the translation should read; when it cannot be asked, the reply says which words were not used
        String unused = "";
        if (!ask.leftover().isEmpty()) {
            Read r = readDirection(session == null ? "" : session.model(), w, ask);
            if (r.failed()) unused = " " + c.t(NOT_USED, ask.leftover());
            ask = ask.read(r.span());
        }
        Path file = resolve(store, session, patron, ask.what());
        if (file == null && attachedId == null && !ask.from().isEmpty()) {   // the phrase may belong to the document's own name: read the words without it
            Ask plain = parse(w, false);
            Path named = plain == null ? null : resolve(store, session, patron, plain.what());
            if (named != null) { ask = plain; file = named; name = c.languageName(ask.lang()); }
        }
        if (file == null && attachedId != null) file = fromShelves(store, attachedId);
        if (file == null) {
            if (session != null) session.translateAsk(ask);   // kept for the file that comes next
            return c.t(WHICH_FILE) + unused;
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
        // the way a comic's pages are read: the words' own ("it reads right to left"), else that they call it a manga, else what the program finds
        String reads = readsIn(spoken);
        if (reads.isEmpty() && mangaSaid(spoken, ask.what())) reads = ComicTranslation.READS_MANGA;
        try { plan = Translation.plan(store, file, ask.lang(), ask.as(), ask.quality().isEmpty() ? Translation.DEFAULT : ask.quality(), model, translator, judge, ask.from(), comicIn(spoken, ask.what()), reads).withDirection(Translation.direction(ask.direction())); }
        catch (Translation.Refused e) { return e.in(c); }
        catch (IOException e) { return e.getMessage(); }
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode a = Translation.args(file, file.getFileName().toString(), plan, null, null, "chat", judge, translator, "", ask.notes(), ask.from());
        if (plan.reading() != null) a.set("pdf_preflight", plan.reading().json());   // the job's page says what the reader found
        String notes = a.path("notes").asText(Translation.NOTES_OFF);   // as filed: the words' choice, else the setting's; what the reply says comes from here
        String read = plan.readingSaid(c);
        boolean directed = !plan.direction().isEmpty();   // the direction the chat took from the words is shown in a question before the job starts
        // a yes-or-no question before the job carries the time asked, the direction and a paid cost; the sentence above it says the rest, once
        boolean asksYes = !ask.quality().isEmpty() && (plan.seconds() >= ASK_FIRST_SECONDS || directed);
        boolean comic = plan.comic() != null, needsHelper = comic && plan.comic().helper().equals(ComicTranslation.NEEDS);
        String head = comic ? c.t(plan.comic().pages() == 1 ? "\"{0}\" is a comic of {1} page, {2}. Into {3} as {4}: {5}." : "\"{0}\" is a comic of {1} pages, {2}. Into {3} as {4}: {5}.",
                file.getFileName(), c.number(plan.comic().pages()), plan.comic().lettering(c), name, plan.as(), asksYes || needsHelper ? plan.besideSaid(c) : plan.withDirection("").said(c))
                : (read.isEmpty() ? "" : read + (c.code.equals("ja") || c.code.equals("zh") ? "" : " ")) + c.t(plan.units() == 1 ? "\"{0}\" has {1} unit with words ({2} characters), written in {3}. Into {4} as {5}: {6}." : "\"{0}\" has {1} units with words ({2} characters), written in {3}. Into {4} as {5}: {6}.",
                file.getFileName(), c.number(plan.units()), c.number(plan.chars()), c.languageName(plan.from()), name, plan.as(), asksYes ? plan.besideSaid(c) : plan.withDirection("").said(c));   // a direction is said in the question that follows, which a directed job always asks
        // who translates, who reads at the tiers that read, where the scores are made: from the facts the job keeps, as every view of it says them
        head += " " + TranslationWho.seatsSaid(TranslationWho.facts(Translation.JOB_DRAFT.apply(a)), c, false, !TranslationWho.planSaysScores(plan, c));   // a hosted model's cost, and the scorers' places when the plan names them, are the plan's to say
        long until = System.currentTimeMillis() + Librarian.OFFER_MINUTES * 60_000L;
        if (needsHelper) {   // the comic helper is not set up: what a set-up fetches, and the job starts on the person's yes, setting it up first; the tier is the one said, or the default
            a.put("comic_setup", "yes");
            String question = ComicHelper.questionSaid(c) + " " + (directed ? c.t("In the direction you gave: \"{0}\".", plan.direction()) + " " : "") + c.t("Set the comic helper up and translate the comic? (yes/no)");
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of("yes", "no"), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + question + " (" + job + ")" + unused;
        }
        if (ask.quality().isEmpty()) {
            String question = (directed ? c.t("In the direction you gave: \"{0}\".", plan.direction()) + " " : "") + (comic ? t(COMIC_TIER_QUESTION) + " " + t(COMIC_DOUBTS) : tierQuestion(notes));
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of(Translation.CHECKED, Translation.QUICK, Translation.REVIEWED, Translation.THOROUGH), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + question + " " + c.t("({0}; the choice starts it)", job) + unused;
        }
        if (asksYes) {
            String question = c.t("That is {0}. Go ahead? (yes/no)", plan.askedSaid(c));
            String job = jobs.submitOffered(Translation.JOB_KIND, patron == null ? "" : patron.did(), a, new Jobs.Offer(null, true, List.of()).withChoices(List.of("yes", "no"), question), until);
            session.offer(job, Librarian.CHOICE, question);
            session.watch(job);
            return head + " " + (comic ? comicDoubts(plan.quality()) : notesFiled(notes, plan.quality())) + " " + question + " (" + job + ")" + unused;
        }
        String job = jobs.submit(Translation.JOB_KIND, patron == null ? "" : patron.did(), a);
        session.watch(job);
        return head + " " + c.t("Translating it now, {0} ({1}): {2}. This chat says when it is done; the run's page has the file, its note and where both are on the shelves.", c.t(plan.quality()), job, Translation.duration(plan.seconds(), c)) + " " + (comic ? comicDoubts(plan.quality()) : notesFiled(notes, plan.quality())) + unused;
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
