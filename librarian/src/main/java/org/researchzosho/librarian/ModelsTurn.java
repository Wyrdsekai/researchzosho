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

    /** The words of the domain: a model, the default, a host or a key, the library as the subject, a run's id. */
    static final Pattern MODEL = Pattern.compile("(?i)\\b(?:models?|llms?)\\b|モデル");
    static final Pattern DEFAULT = Pattern.compile("(?i)\\bdefault\\b|\\b(?:main|primary) model\\b|既定|デフォルト");
    static final Pattern HOSTED = Pattern.compile("(?i)\\b(?:openrouter|anthropic|openai|gemini|nebius|huggingface|hosted|local(?:ly)?|server)\\b|ローカル|サーバ");
    static final Pattern KEY = Pattern.compile("(?i)\\b(?:api )?keys?\\b|キー");
    static final Pattern OURS = Pattern.compile("(?i)\\b(?:we|you|us|our|the library|this (?:chat|library|conversation)|the (?:chat|run|runs))\\b|\\bJ-\\d{3,5}\\b|使|利用");
    static final Pattern USING = Pattern.compile("(?i)\\b(?:use|uses|using|used|run|runs|running|ran|talk|talks|talking|set|switch|switched|pick|picked|chose|chosen|have|has|got|available|installed|configured|set up|there)\\b|使|設定|ある|あります");
    /** A research ask that names a model for its run ("find out …, with deepseek") is the model's, with the run's settings read from the words. */
    static final Pattern RESEARCH = Pattern.compile("(?i)\\b(?:find out|research|investigate|look into|dig into|follow up)\\b|調べて|研究");
    /** "is kimi set up", "does gemma26 answer", "is deepseek available": an entry asked about by its state. */
    static final Pattern SETUP = Pattern.compile("(?i)\\b(?:set up|setup|configured|installed|available|ready|working|answers?|answering|there)\\b|設定|使える|動いて");
    static final Pattern JOB = Pattern.compile("\\bJ-\\d{3,5}\\b");
    static final Pattern ADDRESS = Pattern.compile("(?i)https?://[^\\s\"'」]+");
    /** A thing's model ("a model of telescope", "the standard model"), not a model the library runs on. */
    static final Pattern THING = Pattern.compile("(?i)\\bmodels? of\\b|\\bstandard model\\b|\\bbusiness model\\b|\\bscale model\\b|\\brole model\\b");

    /** A change asked for: "the default (model) should be X", "use X as the default", "switch the default to X", "make X the default", and the Japanese forms. */
    static final Pattern[] CHANGE = {
        Pattern.compile("(?i)^(?:the |our )?(?:default|main|primary)(?: model| entry)? (?:should|must|ought to|needs to|has to) (?:be|become) (?<x>.+)$"),
        Pattern.compile("(?i)^(?:please |can you |could you |would you )?(?:use|take) (?<x>.+?) (?:as|for) (?:the |our )?(?:default|main|primary)(?: model| entry)?(?: from now on)?$"),
        Pattern.compile("(?i)^(?:please |can you |could you |would you )?(?:switch|change|move|set|point) (?:the |our )?(?:default|main|primary)(?: model| entry)? (?:to|over to|at) (?<x>.+)$"),
        Pattern.compile("(?i)^(?:please |can you |could you |would you )?make (?<x>.+?) (?:the |our )?(?:default|main|primary)(?: model| entry)?(?: from now on)?$"),
        Pattern.compile("^(?:既定|デフォルト|メイン)の?モデル(?:は|を)(?<x>.+?)(?:にして|にする|に変えて|に切り替えて|に設定して|であるべき|がいい|にしよう)(?:ください)?$"),
        Pattern.compile("^(?<x>.+?)を(?:既定|デフォルト)(?:の?モデル)?(?:にして|にする|に設定して|に変えて|に切り替えて)(?:ください)?$"),
        Pattern.compile("^(?:既定|デフォルト)(?:の?モデル)?を(?<x>.+?)に(?:して|変えて|切り替えて|設定して)(?:ください)?$"),
    };

    /** Whether the words are about the models the library runs on: a model word with the default, a host, a key, an entry or the library as the subject; the default with an entry or a host; an entry with a key, a run or a use. */
    static boolean about(String words) {
        if (words == null) return false;
        String w = TranslateTurn.withoutAttachment(words).strip();
        if (w.isEmpty() || w.startsWith("/") || RESEARCH.matcher(w).find()) return false;   // a command (/model kimi) is the chat's own, taken before any turn
        if (changeTarget(w.replaceAll("[\\s.!?。！？]+$", "")) != null) return true;   // "make X the default" is this turn's whatever X is: it resolves X or asks
        boolean model = MODEL.matcher(w).find(), dflt = DEFAULT.matcher(w).find(), hosted = HOSTED.matcher(w).find(), key = KEY.matcher(w).find();
        boolean named = !namedEntries(w).isEmpty() || !namedHosts(w).isEmpty();
        boolean ours = OURS.matcher(w).find(), using = USING.matcher(w).find(), job = JOB.matcher(w).find();
        if (THING.matcher(w).find() && !dflt && !named) return false;
        if (model && (dflt || hosted || key || named || (ours && using))) return true;
        if (dflt && (named || hosted || model)) return true;
        return named && (key || job || SETUP.matcher(w).find() || (ours && using));
    }

    /** The entry the words ask to make the default, as they put it, or null when the words ask for no change. */
    static String changeTarget(String w) {
        for (Pattern p : CHANGE) { Matcher m = p.matcher(w); if (m.matches()) return m.group("x").strip(); }
        return null;
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
        StringBuilder b = new StringBuilder();
        Set<String> jobs = new LinkedHashSet<>();
        Matcher j = JOB.matcher(w); while (j.find()) jobs.add(j.group());
        for (String id : jobs) b.append(run(store, id, w)).append("\n\n");   // "why did J-0309 use openrouter": the run's own record first
        for (ModelEntries.Entry e : namedEntries(w)) {   // "is kimi set up", "does deepseek have a key": that entry first
            if (e.name().equals(ModelEntries.DEFAULT)) continue;
            b.append(e.name()).append(" is set up: ").append(ModelEntries.where(e)).append("; ").append(ModelEntries.keyText(e)).append('.')
             .append(ModelEntries.isDefault(e) ? " It is the default." : " It is not the default; say \"make " + e.name() + " the default\" to make it so, or /model " + e.name() + " for this chat alone.").append("\n\n");
        }
        return b.append(state()).toString();
    }

    /** The models as they stand: the default by name with its model, host and address; the other entries one a line, each with its key. */
    static String state() {
        Map<String, ModelEntries.Entry> all = ModelEntries.all();
        ModelEntries.Entry d = all.get(ModelEntries.DEFAULT);
        StringBuilder b = new StringBuilder("The default is the entry named default: ").append(ModelEntries.where(d)).append(". A run or a chat that names no model uses it.");
        List<ModelEntries.Entry> others = new ArrayList<>();
        for (ModelEntries.Entry e : all.values()) if (!e.name().equals(ModelEntries.DEFAULT)) others.add(e);
        if (others.isEmpty()) return b.append(" It is the only entry. Another is added with: researchzosho models add deepseek openrouter deepseek/deepseek-v4.1-flash, then researchzosho models key openrouter <key>; or on the Settings page under Models.").toString();
        b.append("\nThe other entries:");
        for (ModelEntries.Entry e : others) b.append('\n').append(e.name()).append(": ").append(ModelEntries.where(e)).append("; ").append(ModelEntries.keyText(e)).append(ModelEntries.isDefault(e) ? "; the same model and address as the default" : "").append('.');
        b.append("\nTo change the default, say \"make ").append(others.get(0).name()).append(" the default\" with the name of the entry you mean; /model and a name switches this chat alone.");
        return b.toString();
    }

    /** A run's record: the entry and address it ran on, how it ended and, when it failed, the error's first line as it stands. */
    static String run(LibraryStore store, String id, String words) throws IOException {
        ObjectNode j = new Jobs(store, x -> "").get(id);
        if (j == null) return "The library has no research run " + id + ".";
        RunProgress.View v = RunProgress.of(Jobs.view(j));
        ModelEntries.Entry e = ranOn(v.model(), v.drive());
        StringBuilder b = new StringBuilder("Research run ").append(id);
        if (e != null) b.append(" ran on the entry ").append(e.name()).append(": ").append(ModelEntries.where(e));
        else if (!v.drive().isEmpty()) b.append(" ran on the model server at ").append(v.drive()).append(", which no entry names now");
        else b.append(" has not started on any model yet");
        b.append(". ");
        String elapsed = RunProgress.elapsed(v.elapsedSeconds());
        switch (v.state()) {
            case "done" -> b.append("It is done after ").append(elapsed).append(v.report().isEmpty() ? "." : "; its report is [" + v.report() + "].");
            case "failed" -> b.append("It ended as failed after ").append(elapsed).append(v.result().isBlank() ? "." : " with this error: \"" + firstLine(v.result()) + "\".");
            case "stopped" -> b.append("It was stopped after ").append(elapsed).append(v.result().isBlank() ? "." : ": \"" + firstLine(v.result()) + "\".");
            case "queued" -> b.append("It is waiting to start.");
            case Jobs.OFFERED -> b.append("It is waiting for your answer.");
            default -> b.append("It is running, ").append(elapsed).append(" so far.");
        }
        for (String h : namedHosts(words)) if (e == null || !h.equalsIgnoreCase(e.host())) b.append(" It did not run on ").append(h).append('.');   // "why are we using openrouter": it was not
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
        String clean = x.strip().replaceAll("(?i)^(?:the |our |to |a |an )+", "").replaceAll("(?i)\\s+(?:model|entry|one|please)$", "").replaceAll("^[\"“「]|[\"”」]$", "").strip();
        List<ModelEntries.Entry> hits = ModelEntries.resolveAll(clean);
        if (hits.isEmpty()) { Matcher a = ADDRESS.matcher(clean); if (a.find()) { ModelEntries.Entry e = ModelEntries.onDrive(a.group()); if (e != null) hits = List.of(e); } }
        if (hits.size() == 1) return makeDefault(store, patron, hits.get(0));
        if (hits.size() > 1) return "\"" + clean + "\" could be any of these: " + listed(hits) + ". Which one? Say \"make " + suggest(hits) + " the default\" with the name of the one you mean. Nothing was changed.";
        List<String> hosts = namedHosts(clean);
        if (!hosts.isEmpty()) {
            List<ModelEntries.Entry> on = ModelEntries.onHost(hosts.get(0));
            if (on.size() == 1 && ModelEntries.isDefault(on.get(0))) return "No model entry is named \"" + clean + "\". " + on.get(0).name() + " is the only entry on " + hosts.get(0) + " and it is the default already. Nothing was changed.";
            return "No model entry is named \"" + clean + "\". On " + hosts.get(0) + ": " + listed(on) + ". Which one? Say \"make " + suggest(on) + " the default\" with the name of the one you mean. Nothing was changed.";
        }
        return ModelEntries.noMatch(clean) + " Nothing was changed.";
    }

    /** "default (qwen3.8-27b at http://homebox:8212; the default now), 27b-gpu1 (qwen3.8-27b at http://homebox:8213), kimi (moonshotai/kimi-k3 on openrouter.ai)". */
    static String listed(List<ModelEntries.Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (ModelEntries.Entry e : entries) parts.add(e.name() + " (" + e.model() + (e.hosted() ? " on " + e.host() : " at " + e.drive()) + (ModelEntries.isDefault(e) ? "; the default now" : "") + ")");
        return String.join(", ", parts);
    }

    /** The name to say in the example: the first that is not the default already. */
    static String suggest(List<ModelEntries.Entry> entries) {
        for (ModelEntries.Entry e : entries) if (!ModelEntries.isDefault(e)) return e.name();
        return entries.get(0).name();
    }

    static String makeDefault(LibraryStore store, Patrons.Patron patron, ModelEntries.Entry e) throws IOException {
        ModelEntries.Entry d = ModelEntries.defaultEntry();
        if (ModelEntries.isDefault(e)) return (e.name().equals(ModelEntries.DEFAULT) ? "The entry named default" : e.name()) + " is the default already: " + ModelEntries.where(d) + ". Nothing changed.";
        if (!Patrons.mayWrite(store, patron)) return "Only the person who keeps this library and those who may write to it change the default. The keeper would run: researchzosho model use " + e.drive() + " " + e.model();
        if (e.hosted() && !ModelEntries.keyKept(e)) return ModelEntries.noKey(e) + " Nothing was changed.";
        String before = ModelEntries.where(d);
        ModelEntries.useAsDefault(e);
        synchronized (Pages.DRIVE_UP) { Pages.DRIVE_UP[1] = 0; }   // the pages ask the new server whether it answers, not the old one's cached answer
        return "The default is now " + e.name() + ": " + ModelEntries.where(e) + ". It was " + before + ". Runs and chats that name no model use it from now on; a run going now finishes on the model it started with.";
    }
}
