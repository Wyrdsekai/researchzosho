package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.drive.DriveClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who does what in a translation job, as every place that shows the job says it: for a comic who reads its pages (the comic helper, on
 * this machine or on the one that serves the heavy helpers, and the translator a second time where a run does that); who translates (the entry, its model and where it runs, a hosted one with its estimate), who reads at the reviewed and thorough tiers, where each scorer scores or why it is left out, the tier,
 * the notes, the format, the languages and the person's direction.
 *
 * <p>The facts, never a sentence, are kept in the job's record: when it is filed, from its plan ({@code args.who}); when it starts, the
 * seats as the run made them ({@code ran}); when it ends, what a hosted seat cost ({@code spent}). Every surface builds its words from
 * {@link #facts}, so the command line, the pages, the chat and the API say the same thing, and a restart changes none of it. A job filed
 * before the facts were kept is said as far as its args allow: the entry named for the translator ({@code model_entry}) and the one named
 * for the reader ({@code judge}).
 */
public final class TranslationWho {

    private TranslationWho() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** Where a scorer stands for a job: on this machine, at another machine's address, or left out and why. */
    /** Who reads a comic's pages, as a fact: the comic helper; on this machine, or (0.7.6) on the machine {@code pages_on} names, the one that serves the library's heavy helpers. */
    static final String COMIC_HELPER = "comic-helper";
    static final String HERE = "here", AT = "at", DOWN = "down", NOT_LOADED = "not loaded", REFUSED = "refused", AWAY = "away", OFF = "off", NOT_INSTALLED = "not installed";

    // ---- the facts ----

    /**
     * A seat as a fact: the entry's name ("" when the seat is no named entry), the model, whether it is a hosted model and then its host,
     * else the machine it runs on ("" for this one).
     */
    static ObjectNode seat(String entry, String drive, String model, boolean hosted) {
        ObjectNode o = M.createObjectNode();
        String e = entry == null || entry.equals(ModelEntries.DEFAULT) ? "" : entry;
        o.put("entry", e.equals(model) ? "" : e);
        o.put("model", model == null ? "" : model);
        o.put("hosted", hosted);
        String host = DriveClient.hostOf(drive == null ? "" : drive);
        if (hosted) o.put("host", host == null ? "" : host);
        else o.put("machine", host == null || thisMachine(host) ? "" : host);
        return o;
    }

    static ObjectNode seat(ModelEntries.Entry e) { return seat(e.name(), e.drive(), e.model(), e.hosted()); }

    /** A seat that is no named entry: the drive and the model, hosted when the drive is a hosted API. */
    static ObjectNode seatAt(String drive, String model) {
        ModelEntries.Entry named = Translation.entryFor(drive, model);
        return named != null ? seat(named) : seat("", drive, model, new ModelEntries.Entry("", drive, model, null, "", 0, "").hosted());
    }

    /** Whether a host is this machine. */
    static boolean thisMachine(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]") || h.equals("0.0.0.0");
    }

    /**
     * The facts kept when a job is filed, from its plan: the translator, the reader (kept at every tier, for a chat whose question the
     * person answers with another tier; said at reviewed and thorough), each with a hosted model's estimate, and where the scorers stand.
     */
    public static ObjectNode filed(Translation.Plan plan, String modelEntry) {
        ObjectNode o = M.createObjectNode();
        String[] seat = Translation.configuredSeat();
        String tDrive = seat[0], tModel = seat[1];
        ObjectNode t;
        ModelEntries.Entry te = plan.translator().isEmpty() ? null : ModelEntries.get(plan.translator());
        if (te != null) { t = seat(te); tDrive = te.drive(); tModel = te.model(); }
        else if (modelEntry != null && modelEntry.strip().equalsIgnoreCase(Auto.NAME)) { t = M.createObjectNode(); t.put("entry", Auto.NAME); t.put("model", ""); t.put("hosted", false); }
        else t = seatAt(tDrive, tModel);
        if (plan.translatorHosted()) { t.put("estimate_dollars", cents(plan.translatorCost())); t.put("priced", te != null && te.prices() != null); t.put("tokens", plan.translatorTokens()); }
        o.set("translator", t);
        ObjectNode r;
        ModelEntries.Entry re = plan.judge().isEmpty() ? null : ModelEntries.get(plan.judge());
        if (re != null) r = re.drive().equals(tDrive) && re.model().equals(tModel) ? same(re.name()) : seat(re);
        else {
            String[] js = Researcher.judgeSeat(tDrive, tModel);   // the library's judge seat, else the translator's model in fresh calls
            r = js[0].equals(tDrive) && js[1].equals(tModel) ? same("") : seatAt(js[0], js[1]);
        }
        if (plan.judgeHosted() && Translation.reads(plan.quality())) { r.put("estimate_dollars", cents(plan.judgeCost())); r.put("priced", re != null && re.prices() != null); r.put("tokens", plan.judgeTokens()); r.put("reply_tokens", plan.judgeReplyTokens()); }
        o.set("reader", r);
        o.set("scorers", plan.comic() != null ? M.createArrayNode() : scorers(plan.quality(), plan.placed()));   // a comic's balloons are not scored
        if (plan.comic() != null) o.put("pages", COMIC_HELPER);   // who reads the pages: the comic helper
        if (plan.comic() != null && !plan.comic().helperOn().isEmpty()) o.put("pages_on", plan.comic().helperOn());   // on the machine that serves the heavy helpers, not this one
        if (plan.comic() != null && plan.comic().second().equals(ComicTranslation.TWICE)) o.put("second_reading", true);   // and the translator, each balloon's lettering a second time
        o.put("from", plan.from());
        o.put("as", plan.as());
        return o;
    }

    /** Dollars to the hundredth of a cent, as kept; said to the cent. */
    static double cents(double d) { return Math.round(d * 10000) / 10000.0; }

    private static ObjectNode same(String entry) { ObjectNode o = M.createObjectNode(); o.put("same", true); if (entry != null && !entry.isEmpty()) o.put("entry", entry); return o; }

    /** The seats as a run made them: the translator, and the reader at the tiers that read. */
    static ObjectNode ran(Translation.Seats seats, String driveUrl, String quality) {
        ObjectNode o = M.createObjectNode();
        o.set("translator", seats.translatorEntry() != null ? seat(seats.translatorEntry()) : seatAt(driveUrl, seats.translatorModel()));
        if (Translation.reads(quality)) {
            if (seats.sameModel()) o.set("reader", same(""));
            else if (seats.judgeEntry() != null) o.set("reader", seat(seats.judgeEntry()));
            else { String[] js = Researcher.judgeSeat(driveUrl, seats.translatorModel()); o.set("reader", seatAt(js[0], js[1])); }
        }
        return o;
    }

    /** Where each scorer stands for a run of tier {@code q}, from the plan's places: none for the quick tier. */
    static ArrayNode scorers(String q, List<Translation.Placed> placed) {
        ArrayNode out = M.createArrayNode();
        if (Translation.QUICK.equals(q)) return out;
        for (Helpers.Helper h : List.of(Helpers.Helper.LABSE, Helpers.Helper.METRICX)) {
            String name = h == Helpers.Helper.LABSE ? "LaBSE" : "MetricX-24";
            ObjectNode s = out.addObject(); s.put("name", name);
            if (TranslationScores.off(h)) { s.put("where", OFF); continue; }   // its own setting, or RESEARCHZOSHO_SCORERS=off for both
            Translation.Placed p = null;
            for (Translation.Placed x : placed) if (x.name().equals(name)) p = x;
            if (p != null) {
                switch (p.state()) {
                    case Translation.Placed.AT -> s.put("where", AT);
                    case Translation.Placed.DOWN -> s.put("where", DOWN);
                    case Translation.Placed.NOT_LOADED -> { s.put("where", NOT_LOADED); s.put("why", p.why()); }
                    case Translation.Placed.REFUSED -> s.put("where", REFUSED);
                    default -> s.put("where", AWAY);
                }
                if (!p.address().isEmpty()) s.put("address", p.address());
            } else if (h == Helpers.Helper.METRICX && !TranslationScores.metricxInstalled()) s.put("where", NOT_INSTALLED);
            else s.put("where", HERE);
        }
        return out;
    }

    /**
     * What a translation job does, as the API gives it ({@code translation} in {@code library_job}) and every surface says it: the tier, the
     * notes, the languages, the format and the direction as filed; the translator and the reader, as the run made them once it started,
     * else as filed, else as the args name them; each hosted seat's estimate and, once the job ended, its cost; where the scorers stand; and
     * {@code said}, the sentences in English.
     */
    public static ObjectNode facts(JsonNode job) {
        JsonNode a = job.path("args"), who = a.path("who"), ran = job.path("ran"), spent = job.path("spent");
        ObjectNode o = M.createObjectNode();
        String q = Translation.tierOf(a);
        o.put("quality", q);
        o.put("notes", a.path("notes").asText(Translation.NOTES_OFF));
        String from = a.path("from").asText(who.path("from").asText(""));
        o.put("from", from.isEmpty() ? ran.path("from").asText("") : from);   // the person's word, else what the plan found, else what the run found at its start (a comic whose plan could not look at its pages)
        o.put("to", a.path("to").asText(""));
        o.put("as", a.path("as").asText("").isEmpty() ? who.path("as").asText("") : a.path("as").asText(""));
        if (!a.path("direction").asText("").isEmpty()) o.put("direction", a.path("direction").asText());
        ObjectNode t = seatFact(ran.path("translator"), who.path("translator"));
        if (t == null) { t = M.createObjectNode(); t.put("entry", a.path("model_entry").asText("")); t.put("model", ""); }   // filed before the facts were kept
        spentOn(t, spent, "translator");
        o.set("translator", t);
        if (Translation.reads(q)) {
            ObjectNode r = seatFact(ran.path("reader"), who.path("reader"));
            if (r == null) { r = M.createObjectNode(); r.put("entry", a.path("judge").asText("")); r.put("model", ""); }
            spentOn(r, spent, "reader");
            o.set("reader", r);
        }
        if (who.path("scorers").isArray()) o.set("scorers", who.path("scorers").deepCopy());
        if (who.hasNonNull("pages")) o.put("pages", who.path("pages").asText());
        // where the comic helper reads the pages: as the run found it once it started, else as the plan knew it when the job was filed
        String on = ran.has("pages_on") ? ran.path("pages_on").asText("") : who.path("pages_on").asText("");
        if (!on.isEmpty()) o.put("pages_on", on);
        // whether the translator reads the lettering a second time: as the run found it once it has read the pages, else as the plan knew it when the job was filed
        if (ran.has("second_reading") ? ran.path("second_reading").asBoolean(false) : who.path("second_reading").asBoolean(false)) o.put("second_reading", true);
        o.put("said", said(o, Chrome.ENGLISH));
        o.put("short", shortSaid(o, Chrome.ENGLISH));
        return o;
    }

    /**
     * What a seat's calls came to, onto its facts: the tokens its server counted, sent and written; whether the entry had prices; the
     * dollars only when it had. A job recorded before the tokens were kept carries its dollars alone.
     */
    private static void spentOn(ObjectNode s, JsonNode spent, String seat) {
        if (spent.has(seat + "_priced")) s.put("priced", spent.path(seat + "_priced").asBoolean());
        if (spent.has(seat + "_sent")) { s.put("sent_tokens", spent.path(seat + "_sent").asLong()); s.put("written_tokens", spent.path(seat + "_written").asLong()); }
        if (spent.has(seat + "_dollars") && s.path("priced").asBoolean(true)) s.put("cost_dollars", spent.path(seat + "_dollars").asDouble());
    }

    /** The seat as the run made it, with the filed estimate when it is the same seat; else as filed; null when neither is kept. */
    private static ObjectNode seatFact(JsonNode ran, JsonNode filed) {
        if (ran.isObject()) {
            ObjectNode s = ran.deepCopy();
            boolean sameSeat = filed.isObject() && filed.path("entry").asText("").equals(s.path("entry").asText("")) && filed.path("model").asText("").equals(s.path("model").asText(""))
                    && filed.path("same").asBoolean(false) == s.path("same").asBoolean(false);
            if (sameSeat) for (String k : List.of("estimate_dollars", "priced", "tokens", "reply_tokens")) if (filed.has(k)) s.set(k, filed.get(k));
            return s;
        }
        return filed.isObject() ? ((ObjectNode) filed).deepCopy() : null;
    }

    // ---- the words ----

    /** What the job does, in a few sentences: who translates and who reads, where the scores are made, the tier and the languages, the notes, the direction. */
    public static String said(JsonNode f, Chrome c) { return said(f, c, true); }

    /** The same; {@code costs} false leaves out a hosted seat's estimate and cost, for an answer whose plan says them already. */
    public static String said(JsonNode f, Chrome c, boolean costs) {
        return said(f, c, costs, true);
    }

    /** The same; {@code scores} false leaves out where the scores are made, said by the plan in the same answer. */
    public static String said(JsonNode f, Chrome c, boolean costs, boolean scores) {
        List<String> out = new ArrayList<>();
        String q = f.path("quality").asText(Translation.DEFAULT);
        out.add(seatsSaid(f, c, costs, scores));
        out.add(tierSaid(f, c));
        out.add(COMIC_HELPER.equals(f.path("pages").asText("")) && !Translation.QUICK.equals(q) ? c.t("What the checks still doubt is listed in the note beside the translation, by page and balloon; the pages hold the translation alone.")
                : c.t(Translation.notesSaid(f.path("notes").asText(Translation.NOTES_OFF), q)));
        if (!f.path("direction").asText("").isEmpty()) out.add(c.t("In the direction given: \"{0}\".", f.path("direction").asText()));
        return PdfReading.sentences(c, out);
    }

    /** Who translates and who reads, and where the scores are made: the first sentences of {@link #said}, for a reply that says the rest its own way. */
    public static String seatsSaid(JsonNode f, Chrome c) { return seatsSaid(f, c, true); }

    /** The same; {@code costs} false leaves out a hosted seat's estimate and cost, said elsewhere in the same answer. */
    public static String seatsSaid(JsonNode f, Chrome c, boolean costs) { return seatsSaid(f, c, costs, true); }

    /** The same; {@code scores} false leaves out where the scores are made, for an answer whose plan says it ({@link #planSaysScores}). */
    public static String seatsSaid(JsonNode f, Chrome c, boolean costs, boolean scores) {
        List<String> out = new ArrayList<>();
        if (COMIC_HELPER.equals(f.path("pages").asText(""))) {
            String on = f.path("pages_on").asText("");
            boolean twice = f.path("second_reading").asBoolean(false);
            if (on.isEmpty()) out.add(twice ? c.t("The pages are read by the comic helper on this machine, and the translator reads the lettering a second time.") : c.t("The pages are read by the comic helper on this machine."));
            else out.add(twice ? c.t("The pages are read by the comic helper on {0}, and the translator reads the lettering a second time.", on) : c.t("The pages are read by the comic helper on {0}.", on));
        }
        String t = seatSaid(f.path("translator"), c, costs);
        JsonNode r = f.path("reader");
        if (!r.isObject()) out.add(c.t("Translated by {0}.", t));
        else if (r.path("same").asBoolean(false)) out.add(c.t("Translated by {0}; read by the same model in fresh calls.", t));
        else out.add(c.t("Translated by {0}; read by {1}.", t, seatSaid(r, c, costs)));
        String sc = scores ? scorersSaid(f.path("scorers"), c) : "";
        if (!sc.isEmpty()) out.add(c.t("Scores: {0}.", sc));
        return PdfReading.sentences(c, out);
    }

    /** Whether a plan's sentence says where the scores are made: a scorer elsewhere or left out, or what setting them up takes; then an answer that says the plan says it once. */
    public static boolean planSaysScores(Translation.Plan plan, Chrome c) {
        return !plan.placed().isEmpty() || !TranslationScores.setupIn(plan.setupSaid(), c).isEmpty();
    }

    /** The meaning beside "by {0}" in a list's line: the model that translates, never who wrote a thing. */
    static final String BY = "a translation job's line in a list of runs: the model that translates it";

    /** Who translates and who reads, in a few words, for a list's line: "by local, read by reader-b". */
    public static String shortSaid(JsonNode f, Chrome c) {
        String t = nameOf(f.path("translator"), c);
        JsonNode r = f.path("reader");
        if (!r.isObject()) return c.tc("by {0}", BY, t);
        if (r.path("same").asBoolean(false)) return c.t("by {0}, read by the same model", t);
        return c.t("by {0}, read by {1}", t, nameOf(r, c));
    }

    private static String nameOf(JsonNode s, Chrome c) {
        String entry = s.path("entry").asText(""), model = s.path("model").asText("");
        if (entry.equalsIgnoreCase(Auto.NAME) && model.isEmpty()) return c.t("the model the library picks when the run starts");
        return !entry.isEmpty() ? entry : !model.isEmpty() ? model : c.t("the library's model");
    }

    /** A seat in words: the entry, its model and where it runs; a hosted one as a paid call with its estimate, and its cost once the job ended. */
    static String seatSaid(JsonNode s, Chrome c) { return seatSaid(s, c, true); }

    static String seatSaid(JsonNode s, Chrome c, boolean costs) {
        String entry = s.path("entry").asText(""), model = s.path("model").asText(""), machine = s.path("machine").asText(""), host = s.path("host").asText("");
        boolean hosted = s.path("hosted").asBoolean(false);
        String base;
        if (model.isEmpty()) base = nameOf(s, c);   // filed before the facts were kept: the entry's name alone, else the library's model
        else if (hosted) base = entry.isEmpty() ? c.t("{0}, hosted at {1}", model, host) : c.t("{0} ({1}, hosted at {2})", entry, model, host);
        else if (machine.isEmpty()) base = entry.isEmpty() ? c.t("{0} on this machine", model) : c.t("{0} ({1} on this machine)", entry, model);
        else base = entry.isEmpty() ? c.t("{0} on {1}", model, machine) : c.t("{0} ({1} on {2})", entry, model, machine);
        if (!hosted || !costs) return base;
        // an entry with no prices: a paid call at prices it does not state, with the tokens its server counted once the job ended; never a dollar figure
        if (s.has("priced") && !s.path("priced").asBoolean()) return s.has("written_tokens")
                ? c.t("{0}, a paid call at prices the entry does not state: {1} tokens sent and {2} written", base, c.number(s.path("sent_tokens").asLong()), c.number(s.path("written_tokens").asLong()))
                : c.t("{0}, a paid call at prices the entry does not state", base);
        boolean estimated = s.has("estimate_dollars") && s.path("priced").asBoolean(s.path("estimate_dollars").asDouble() > 0), cost = s.has("cost_dollars");
        double est = s.path("estimate_dollars").asDouble(), spent = s.path("cost_dollars").asDouble();
        if (estimated) return cost ? c.t("{0}, a paid call: about {1} at its list prices; it cost {2}", base, Spend.dollars(est), Spend.dollars(spent)) : c.t("{0}, a paid call: about {1} at its list prices", base, Spend.dollars(est));
        if (cost) return c.t("{0}, a paid call; it cost {1}", base, Spend.dollars(spent));
        return c.t("{0}, a paid call at prices the entry does not state", base);
    }

    /** Where each scorer scores, or why it is left out: the clauses joined as the language joins them; "" when the job keeps no such fact (the quick tier, or a job filed before). */
    static String scorersSaid(JsonNode scorers, Chrome c) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : scorers) {
            String name = s.path("name").asText(""), at = s.path("address").asText("");
            out.add(switch (s.path("where").asText("")) {
                case HERE -> c.t("{0} on this machine", name);
                case AT -> c.t("{0} at {1}", name, at);
                case DOWN -> c.t("{0} at {1}, which did not answer when the job was filed", name, at);
                case NOT_LOADED -> c.t("{0} at {1}, which had not loaded it when the job was filed ({2})", name, at, s.path("why").asText(""));
                case REFUSED -> c.t("{0} left out: {1} refuses this library's secret", name, at);
                case OFF -> c.t("{0} left out: it is off", name);
                case NOT_INSTALLED -> c.t("{0} left out: it is not installed", name);
                default -> c.t("{0} left out: this machine runs no heavy helper and no address names one", name);
            });
        }
        return Translation.clauses(c, out.toArray(new String[0]));
    }

    /** The tier, the languages and the format: "Reviewed, from Japanese into English, as epub." */
    static String tierSaid(JsonNode f, Chrome c) {
        String tier = c.t(f.path("quality").asText(Translation.DEFAULT)), from = f.path("from").asText(""), to = c.languageName(f.path("to").asText("")), as = f.path("as").asText("");
        tier = tier.isEmpty() ? tier : tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1);
        if (from.isEmpty()) return as.isEmpty() ? c.t("{0}, into {1}.", tier, to) : c.t("{0}, into {1}, as {2}.", tier, to, as);
        return as.isEmpty() ? c.t("{0}, from {1} into {2}.", tier, c.languageName(from), to) : c.t("{0}, from {1} into {2}, as {3}.", tier, c.languageName(from), to, as);
    }
}
