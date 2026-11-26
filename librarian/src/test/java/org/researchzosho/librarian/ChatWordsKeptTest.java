package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What ChatWords keeps from the words files between calls changes no reading (2026-11-25). The files are read once, so a meaning's
 * entries, a phrase's expression, the expressions an ask is tried with and the names of their groups are worked out once and kept; with
 * {@link ChatWords#KEEP} off, all of it is worked out again at every call, as it was before. Every phrase of every words file is read
 * here, both ways, by the readers the chat reads that meaning's words with (an ask's phrase filled with its file's words, as a person
 * types it), and every result is compared. Worked out again at every call, the readings are about ten seconds of processor time: they
 * are read on up to four threads, each reading on its own.
 */
class ChatWordsKeptTest {
    @AfterEach void back() { ChatWords.KEEP = true; }

    /** A reader of a person's words, by its name: what it makes of them. */
    record Reader(String name, Function<String, Object> read) { }

    static final String[] TIERS = {"tier.quick", "tier.checked", "tier.reviewed", "tier.thorough"};

    /** The readers the words of {@code meaning} meet in the chat: the turn that reads them, and the short readers every message meets. */
    static List<Reader> readersOf(String meaning) {
        List<Reader> r = new ArrayList<>();
        if (meaning.startsWith("tier.")) { r.add(new Reader("tier", ChatWords::tier)); r.add(new Reader("withoutAtEnd tiers", x -> ChatWords.withoutAtEnd(x, TIERS))); r.add(new Reader("without tiers", x -> ChatWords.without(x, TIERS))); }
        else if (meaning.startsWith("notes.")) { r.add(new Reader("notesIn", TranslateTurn::notesIn)); r.add(new Reader("withoutNotes", TranslateTurn::withoutNotes)); r.add(new Reader("longest notes", x -> ChatWords.longest(x, "notes.off", "notes.end"))); }
        else if (List.of("yes", "yes.alone", "no", "none", "hint.yes_no").contains(meaning)) { r.add(new Reader("yesWord", ChatWords::yesWord)); r.add(new Reader("isNo", ChatWords::isNo)); r.add(new Reader("isNone", ChatWords::isNone)); }
        else if (meaning.startsWith("this_")) { r.add(new Reader("pointsAtTheFile", TranslateTurn::pointsAtTheFile)); r.add(new Reader("this_report", x -> ChatWords.whole("this_report", x))); }
        else if (meaning.startsWith("lang.")) { r.add(new Reader("language", ChatWords::language)); r.add(new Reader("languageIn", ChatWords::languageIn)); }
        else if (meaning.equals("translate.ask")) r.add(new Reader("parse", TranslateTurn::parse));
        else if (meaning.equals("translate.from")) r.add(new Reader("fromSaid", TranslateTurn::fromSaid));
        else if (meaning.equals("translate.direction")) r.add(new Reader("directionSaid", TranslateTurn::directionSaid));
        else if (meaning.equals("translate.filler")) r.add(new Reader("leftover", TranslateTurn::leftover));
        else if (meaning.startsWith("translate.")) r.add(new Reader(meaning, x -> ChatWords.has(meaning, x)));
        else if (meaning.equals("format.as")) { r.add(new Reader("find format.as", x -> ChatWords.find("format.as", x, TranslateTurn.FORMAT))); r.add(new Reader("without format.as", x -> ChatWords.without(x, TranslateTurn.FORMAT, "format.as"))); }
        else if (meaning.startsWith("glossary.")) r.add(new Reader(meaning, x -> ChatWords.ask(meaning, x, TranslateTurn.places())));
        else if (meaning.equals("read_in.ask")) r.add(new Reader("readIn", ReadInTurn::parse));
        else if (meaning.equals("dossier.named")) r.add(new Reader("dossier.named", x -> ChatWords.ask("dossier.named", x, ReadInTurn.places())));
        else if (meaning.equals("pictures.same")) r.add(new Reader("pictures.same", ImagesTurn::same));
        else if (meaning.startsWith("pictures.")) r.add(new Reader(meaning, x -> ChatWords.ask(meaning, x, ImagesTurn.places())));
        else if (meaning.startsWith("kind.")) r.add(new Reader("whichOf kind.", x -> ChatWords.whichOf("kind.", x)));
        else if (meaning.equals("models.change")) r.add(new Reader("models.changeTarget", ModelsTurn::changeTarget));
        else if (meaning.startsWith("models.")) r.add(new Reader("models.about", ModelsTurn::about));
        else if (meaning.startsWith("go")) r.add(new Reader("go-ahead", GoAhead::isPhrase));
        else { r.add(new Reader("tier", ChatWords::tier)); r.add(new Reader("yesWord", ChatWords::yesWord)); }
        return r;
    }

    /** One reading: the meaning, the words read, and the reader. */
    record Reading(String meaning, String words, Reader reader) { String read() { return String.valueOf(reader.read().apply(words)); } }

    /**
     * Every phrase of every file under its meaning, as written; a translate.ask phrase filled with its file's words for "this file" and
     * for Japanese (English in Japanese's own file), and a translate.from phrase with its file's name for German, as a person types them.
     */
    static List<Reading> readings() {
        Set<List<String>> seen = new LinkedHashSet<>();
        List<Reading> out = new ArrayList<>();
        for (var f : ChatWords.files().entrySet()) {
            Map<String, List<String>> w = f.getValue();
            List<String> file = ChatWordsTest.plain(w, "this_file"), into = ChatWordsTest.plain(w, "lang." + (f.getKey().equals("ja") ? "en" : "ja")), german = ChatWordsTest.plain(w, "lang.de");
            for (var e : w.entrySet()) for (String x : e.getValue()) {
                String words = x;
                if (e.getKey().equals("translate.ask") && !ChatWords.isPattern(x) && !file.isEmpty() && !into.isEmpty()) words = x.replace("{what}", file.get(0)).replace("{lang}", into.get(0));
                if (e.getKey().equals("translate.from") && !ChatWords.isPattern(x) && !german.isEmpty()) words = x.replace("{lang}", german.get(0));
                if (!seen.add(List.of(e.getKey(), words))) continue;
                for (Reader r : readersOf(e.getKey())) out.add(new Reading(e.getKey(), words, r));
            }
        }
        return out;
    }

    /** Every phrase's expression, with each set of places the chat fills phrases with, and with none: the same kept as worked out again. */
    @Test
    void everyPhrasesExpressionIsTheSameWithEachPlaceTheChatGivesIt() {
        List<Map<String, String>> places = new ArrayList<>(List.of(Map.of(), TranslateTurn.places(), TranslateTurn.fromPlaces(), TranslateTurn.FORMAT, ImagesTurn.places(), ImagesTurn.sameKind(), ReadInTurn.places()));
        places.add(0, null);
        Set<String> entries = new LinkedHashSet<>();
        for (var f : ChatWords.files().values()) for (List<String> l : f.values()) entries.addAll(l);
        List<String> faults = new ArrayList<>();
        for (Map<String, String> p : places) for (String e : entries) {
            ChatWords.KEEP = false;
            String phrase = ChatWords.isPattern(e) ? "" : ChatWords.phrase(e, p), expression = expression(e, p);
            ChatWords.KEEP = true;
            String keptPhrase = ChatWords.isPattern(e) ? "" : ChatWords.phrase(e, p), keptExpression = expression(e, p);
            if (!phrase.equals(keptPhrase) || !expression.equals(keptExpression)) faults.add(e + " with " + (p == null ? "no places" : p.keySet()) + ": " + phrase + " / kept: " + keptPhrase);
        }
        assertEquals(List.of(), faults, faults.size() + " expression(s) differ");
    }

    /** An entry's expression with these places, and the names of its groups, as a string; why it does not compile, when it does not. */
    static String expression(String entry, Map<String, String> places) {
        try { Pattern p = ChatWords.pattern(entry, places); return p.pattern() + " " + ChatWords.groups(p.pattern()); }
        catch (RuntimeException e) { return "not compiled: " + e.getClass().getSimpleName(); }
    }

    /** Every reading's result, read on up to four threads (each reading is on its own: the readers keep nothing of one reading for the next). */
    static List<String> read(List<Reading> all) throws Exception {
        String[] out = new String[all.size()];
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> parts = new ArrayList<>();
            for (int t = 0; t < threads; t++) { int first = t; parts.add(pool.submit(() -> { for (int i = first; i < out.length; i += threads) out[i] = all.get(i).read(); })); }
            for (Future<?> f : parts) f.get();
        } finally { pool.shutdownNow(); }
        return List.of(out);
    }

    @Test
    void everyPhraseOfEveryFileIsReadTheSameWithWhatIsKeptAsWithNothingKept() throws Exception {
        List<Reading> all = readings();
        Set<String> phrases = new LinkedHashSet<>();
        for (Reading r : all) phrases.add(r.meaning() + "\u0000" + r.words());
        int entries = 0;
        for (var f : ChatWords.files().values()) for (List<String> l : f.values()) entries += l.size();
        assertTrue(phrases.size() > 3000 && entries > 4000, "the instrument: every phrase of every file is read: " + phrases.size() + " phrases of " + entries + " entries");
        ChatWords.KEEP = false;
        long t0 = System.nanoTime();
        List<String> before = read(all);
        long t1 = System.nanoTime();
        ChatWords.KEEP = true;
        List<String> kept = read(all);
        long t2 = System.nanoTime();
        System.out.println("[kept] " + phrases.size() + " phrases, " + all.size() + " readings: " + (t1 - t0) / 1_000_000 + " ms worked out at every call, " + (t2 - t1) / 1_000_000 + " ms with what is kept");
        List<String> faults = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) if (!before.get(i).equals(kept.get(i))) faults.add(all.get(i).reader().name() + "(\"" + all.get(i).words() + "\"), " + all.get(i).meaning() + ": " + before.get(i) + " / kept: " + kept.get(i));
        assertEquals(List.of(), faults, faults.size() + " reading(s) differ");
    }
}
