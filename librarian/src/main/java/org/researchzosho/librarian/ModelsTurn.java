package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words about the models the library runs on, said in the chat — which is the default, why a run used one, which are set up, which have a
 * key, "make deepseek the default" — are the library's own turn, answered from its entries and its settings with no model asked. On
 * 2026-11-08 the chat's model, asked which model was the default, answered from a month-old conversation over the fresh tool result in
 * the same turn: an entry that no longer existed, a default that never was, a rule for the default that is not the library's, and a
 * rebuild's error read as a usage cap. The library holds the one true answer to a question about its own setup. A change happens only on
 * a name that resolves to one entry; a name that matches nothing or several is asked about, and nothing changes.
 */
final class ModelsTurn {
    private ModelsTurn() { }

    /**
     * The words of the domain, in every language ({@link ChatWords}): a model ("models.model"), the default, a host, a key, the library
     * as the subject, a use, a research ask (which names a model for its run and is the model's: "find out …, with deepseek"), the state of
     * an entry ("is kimi set up"), a thing's model ("the standard model"), and a change asked for ("make X the default", "mets X par défaut").
     */
    static final Pattern JOB = Pattern.compile("\\bJ-\\d{3,5}\\b");
    static final Pattern ADDRESS = Pattern.compile("(?i)https?://[^\\s\"'」]+");

    /** Whether the words are about the models the library runs on: a model word with the default, a host, a key, an entry or the library as the subject; the default with an entry or a host; an entry with a key, a run or a use. */
    static boolean about(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        if (w.isEmpty() || w.startsWith("/") || ChatWords.has("models.research", w)) return false;   // a command (/model kimi) is the chat's own, taken before any turn
        if (changeTarget(w.replaceAll("[\\s.!?。！？]+$", "")) != null) return true;   // "make X the default" is this turn's whatever X is: it resolves X or asks
        boolean model = ChatWords.has("models.model", w), dflt = ChatWords.has("models.default", w), hosted = ChatWords.has("models.hosted", w), key = ChatWords.has("models.key", w);
        boolean named = !namedEntries(w).isEmpty() || !namedHosts(w).isEmpty();
        boolean ours = ChatWords.has("models.ours", w), using = ChatWords.has("models.using", w), job = JOB.matcher(w).find();
        if (ChatWords.has("models.thing", w) && !dflt && !named) return false;
        if (model && (dflt || hosted || key || named || (ours && using))) return true;
        if (dflt && (named || hosted || model)) return true;
        return named && (key || job || ChatWords.has("models.setup", w) || (ours && using));
    }

    /** The entry the words ask to make the default, as they put it, or null when the words ask for no change. */
    static String changeTarget(String w) {
        Map<String, String> m = ChatWords.ask("models.change", w, Map.of("x", ".+?"));
        return m == null || m.get("x") == null ? null : m.get("x").strip();
    }

    /** The entries the words name outright: an entry's name, its model id or the id's last part, as a word of the words. */
    static List<ModelEntries.Entry> namedEntries(String w) {
        List<ModelEntries.Entry> out = new ArrayList<>();
        String lw = w.toLowerCase(Locale.ROOT);
        for (ModelEntries.Entry e : ModelEntries.all().values()) {
            String tail = e.model().substring(e.model().lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            boolean byName = !e.name().equals(ModelEntries.DEFAULT) && hasWord(lw, e.name().toLowerCase(Locale.ROOT));
            if (byName || hasWord(lw, e.model().toLowerCase(Locale.ROOT)) || hasWord(lw, tail)) out.add(e);
        }
        return out;
    }

    /** The hosts the words name, by the host itself (homebox, openrouter.ai) or its word (openrouter): each once, as the entries spell it. */
    static List<String> namedHosts(String w) {
        Set<String> out = new LinkedHashSet<>();
        String lw = w.toLowerCase(Locale.ROOT);
        for (ModelEntries.Entry e : ModelEntries.all().values()) {
            String h = e.host().toLowerCase(Locale.ROOT);
            if (h.isEmpty()) continue;
            String first = h.contains(".") ? h.substring(0, h.indexOf('.')) : h;
            if (hasWord(lw, h) || hasWord(lw, ModelEntries.hostWord(h)) || (first.length() >= 4 && hasWord(lw, first))) out.add(e.host());
        }
        return new ArrayList<>(out);
    }

    /** Whether a name stands as a word of the words: not inside another Latin word or number, so "deepseekを既定にして" names deepseek and "gemma26" does not name gemma. */
    static boolean hasWord(String lw, String word) {
        return !word.isEmpty() && Pattern.compile("(?<![\\p{IsLatin}\\p{N}])" + Pattern.quote(word) + "(?![\\p{IsLatin}\\p{N}])").matcher(lw).find();
    }

    /** The reply, or null when the words are not about the models. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        if (!about(words)) return null;
        String w = TranslateTurn.withoutAttachment(words).strip().replaceAll("[\\s.!?。！？]+$", "");
        String x = changeTarget(w);
        if (x != null) return change(store, patron, x);
        Chrome c = Chrome.now();
        StringBuilder b = new StringBuilder();
        Set<String> jobs = new LinkedHashSet<>();
        Matcher j = JOB.matcher(w); while (j.find()) jobs.add(j.group());
        for (String id : jobs) b.append(run(store, id, w)).append("\n\n");   // "why did J-0309 use openrouter": the run's own record first
        for (ModelEntries.Entry e : namedEntries(w)) {   // "is kimi set up", "does deepseek have a key": that entry first
            if (e.name().equals(ModelEntries.DEFAULT)) continue;
            b.append(c.t("{0} is set up: {1}; {2}.", e.name(), ModelEntries.where(e, c), ModelEntries.keyText(e, c))).append(' ')
             .append(ModelEntries.isDefault(e) ? c.t("It is the default.") : c.t("It is not the default; say \"make {0} the default\" to make it so, or /model {0} for this chat alone.", e.name())).append("\n\n");
        }
        return b.append(state()).toString();
    }

    /** The models as they stand: the default by name with its model, host and address; the other entries one a line, each with its key. */
    static String state() {
        Chrome c = Chrome.now();
        Map<String, ModelEntries.Entry> all = ModelEntries.all();
        ModelEntries.Entry d = all.get(ModelEntries.DEFAULT);
        StringBuilder b = new StringBuilder(c.t("The default is the entry named default: {0}. A run or a chat that names no model uses it.", ModelEntries.where(d, c)));
        List<ModelEntries.Entry> others = new ArrayList<>();
        for (ModelEntries.Entry e : all.values()) if (!e.name().equals(ModelEntries.DEFAULT)) others.add(e);
        if (others.isEmpty()) return b.append(" ").append(c.t("It is the only entry. Another is added with: researchzosho models add deepseek openrouter deepseek/deepseek-v4.1-flash, then researchzosho models key openrouter <key>; or on the Settings page under Models.")).toString();
        b.append("\n").append(c.t("The other entries:"));
        for (ModelEntries.Entry e : others) b.append('\n').append(ModelEntries.isDefault(e) ? c.t("{0}: {1}; {2}; the same model and address as the default.", e.name(), ModelEntries.where(e, c), ModelEntries.keyText(e, c)) : c.t("{0}: {1}; {2}.", e.name(), ModelEntries.where(e, c), ModelEntries.keyText(e, c)));
        b.append("\n").append(c.t("To change the default, say \"make {0} the default\" with the name of the entry you mean; /model and a name switches this chat alone.", others.get(0).name()));
        return b.toString();
    }

    /** A run's record: the entry and address it ran on, how it ended and, when it failed, the error's first line as it stands. */
    static String run(LibraryStore store, String id, String words) throws IOException {
        Chrome c = Chrome.now();
        ObjectNode j = new Jobs(store, x -> "").get(id);
        if (j == null) return c.t("The library has no research run {0}.", id);
        RunProgress.View v = RunProgress.of(Jobs.view(j));
        ModelEntries.Entry e = ranOn(v.model(), v.drive());
        StringBuilder b = new StringBuilder(e != null ? c.t("Research run {0} ran on the entry {1}: {2}.", id, e.name(), ModelEntries.where(e, c))
                : !v.drive().isEmpty() ? c.t("Research run {0} ran on the model server at {1}, which no entry names now.", id, v.drive()) : c.t("Research run {0} has not started on any model yet.", id)).append(' ');
        String elapsed = RunProgress.elapsed(v.elapsedSeconds());
        switch (v.state()) {
            case "done" -> b.append(v.report().isEmpty() ? c.t("It is done after {0}.", elapsed) : c.t("It is done after {0}; its report is [{1}].", elapsed, v.report()));
            case "failed" -> b.append(v.result().isBlank() ? c.t("It ended as failed after {0}.", elapsed) : c.t("It ended as failed after {0} with this error: \"{1}\".", elapsed, firstLine(v.result())));
            case "stopped" -> b.append(v.result().isBlank() ? c.t("It was stopped after {0}.", elapsed) : c.t("It was stopped after {0}: \"{1}\".", elapsed, firstLine(v.result())));
            case "queued" -> b.append(c.t("It is waiting to start."));
            case Jobs.OFFERED -> b.append(c.t("It is waiting for your answer."));
            default -> b.append(c.t("It is running, {0} so far.", elapsed));
        }
        for (String h : namedHosts(words)) if (e == null || !h.equalsIgnoreCase(e.host())) b.append(' ').append(c.t("It did not run on {0}.", h));   // "why are we using openrouter": it was not
        return b.toString();
    }

    /** The entry a run ran on: the one it named, else the one at the address the run's record carries; null when neither says. */
    static ModelEntries.Entry ranOn(String modelEntry, String drive) {
        ModelEntries.Entry e = modelEntry == null || modelEntry.isEmpty() || modelEntry.equalsIgnoreCase(Auto.NAME) ? null : ModelEntries.get(modelEntry);
        return e != null ? e : ModelEntries.onDrive(drive);
    }

    /** An error's first line, whitespace closed up, cut to 160 characters. */
    static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip();
        int nl = line.indexOf('\n'); if (nl > 0) line = line.substring(0, nl);
        return Acquisitions.compress(line, 160);
    }

    /** The change: one entry named makes it the default; several or none are asked about and nothing changes. */
    static String change(LibraryStore store, Patrons.Patron patron, String x) throws IOException {
        Chrome c = Chrome.now();
        String clean = x.strip().replaceAll("(?i)^(?:the |our |to |a |an )+", "").replaceAll("(?i)\\s+(?:model|entry|one|please)$", "").replaceAll("^[\"“「]|[\"”」]$", "").strip();
        List<ModelEntries.Entry> hits = ModelEntries.resolveAll(clean);
        if (hits.isEmpty()) { Matcher a = ADDRESS.matcher(clean); if (a.find()) { ModelEntries.Entry e = ModelEntries.onDrive(a.group()); if (e != null) hits = List.of(e); } }
        if (hits.size() == 1) return makeDefault(store, patron, hits.get(0));
        if (hits.size() > 1) return c.t("\"{0}\" could be any of these: {1}. Which one? Say \"make {2} the default\" with the name of the one you mean. Nothing was changed.", clean, listed(hits), suggest(hits));
        List<String> hosts = namedHosts(clean);
        if (!hosts.isEmpty()) {
            List<ModelEntries.Entry> on = ModelEntries.onHost(hosts.get(0));
            if (on.size() == 1 && ModelEntries.isDefault(on.get(0))) return c.t("No model entry is named \"{0}\". {1} is the only entry on {2} and it is the default already. Nothing was changed.", clean, on.get(0).name(), hosts.get(0));
            return c.t("No model entry is named \"{0}\". On {1}: {2}. Which one? Say \"make {3} the default\" with the name of the one you mean. Nothing was changed.", clean, hosts.get(0), listed(on), suggest(on));
        }
        return ModelEntries.noMatch(clean, c) + " " + c.t("Nothing was changed.");
    }

    /** "default (qwen3.8-27b at http://homebox:8212; the default now), 27b-gpu1 (qwen3.8-27b at http://homebox:8213), kimi (moonshotai/kimi-k3 on openrouter.ai)". */
    static String listed(List<ModelEntries.Entry> entries) {
        List<String> parts = new ArrayList<>();
        Chrome c = Chrome.now();
        for (ModelEntries.Entry e : entries) parts.add(e.name() + " (" + (e.hosted() ? c.t("{0} on {1}", e.model(), e.host()) : c.t("{0} at {1}", e.model(), e.drive())) + (ModelEntries.isDefault(e) ? "; " + c.t("the default now") : "") + ")");
        return String.join(", ", parts);
    }

    /** The name to say in the example: the first that is not the default already. */
    static String suggest(List<ModelEntries.Entry> entries) {
        for (ModelEntries.Entry e : entries) if (!ModelEntries.isDefault(e)) return e.name();
        return entries.get(0).name();
    }

    static String makeDefault(LibraryStore store, Patrons.Patron patron, ModelEntries.Entry e) throws IOException {
        Chrome c = Chrome.now();
        ModelEntries.Entry d = ModelEntries.defaultEntry();
        if (ModelEntries.isDefault(e)) return e.name().equals(ModelEntries.DEFAULT) ? c.t("The entry named default is the default already: {0}. Nothing changed.", ModelEntries.where(d, c)) : c.t("{0} is the default already: {1}. Nothing changed.", e.name(), ModelEntries.where(d, c));
        if (!Patrons.mayWrite(store, patron)) return c.t("Only the person who keeps this library and those who may write to it change the default. The keeper would run: {0}", "researchzosho model use " + e.drive() + " " + e.model());
        if (e.hosted() && !ModelEntries.keyKept(e)) return ModelEntries.noKey(e, c) + " " + c.t("Nothing was changed.");
        String before = ModelEntries.where(d, c);
        ModelEntries.useAsDefault(e);
        synchronized (Pages.DRIVE_UP) { Pages.DRIVE_UP[1] = 0; }   // the pages ask the new server whether it answers, not the old one's cached answer
        return c.t("The default is now {0}: {1}. It was {2}. Runs and chats that name no model use it from now on; a run going now finishes on the model it started with.", e.name(), ModelEntries.where(e, c), before);
    }
}
