package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company's state (docs/DESIGN_CAREER.md, step 4), by the dossier builder, as designed: fit is judged with the company's dossier
 * beside the posting, never from the posting's words alone. Two tiers of one mechanism. The company card is the organisation field's own
 * research run, bounded to a few minutes and pointed at what the wants ask: what it builds and shipped lately, how many people and the
 * trend, stage and last round, where it is, who leads it and its engineering, hiring, departures, layoffs, news. The full dossier is the
 * same run with the full brief and no cap, on demand before an application. Both land on the same shelves, so a card grows into a
 * dossier, every claim with its source and its class. The company's website anchors the question, so a namesake's facts never land
 * on it. The headcount the cut uses is read from the claims.
 */
public final class CareerCompany {
    private CareerCompany() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String CARDS_KEY = "RESEARCHZOSHO_CAREER_CARDS";
    /** How many company cards a search, or a night, files: each is a bounded research run on the library's model. */
    static int cardsPerRun() { return Math.max(0, Math.min(20, Config.getInt(CARDS_KEY, 3))); }
    public static final int CARD_MINUTES = 8;
    static final int CARD_TURNS = 12, CARD_DAYS = 30;

    static String anchored(String name, String site) { return site == null || site.isBlank() ? name : name + " (" + site + ")"; }

    /** The card's question: the company as it is today, in the terms the wants use. */
    static String cardQuestion(String name, String site) {
        return "What is " + anchored(name, site) + ", the company, today: what it builds and shipped lately, how many people work there and the trend, its stage and its "
                + "last funding round, where it is based and whether it hires remotely, who leads it and its engineering, its hiring, departures and layoffs this year, and its news?";
    }

    /** Three questions, the fewest that answer the wants: size and stage; the work, the place and the leaders; the year's hiring and news. */
    static List<String> cardSubQuestions(String name, String site) {
        String n = anchored(name, site);
        return List.of("How many people work at " + n + " now and a year ago, and what stage is it at: founded when, its last funding round and when, by its own site, its LinkedIn page, the press or filings?",
                "What does " + n + " build or sell, where is it based, does it hire remotely, and who leads it and its engineering?",
                "Hiring, departures, layoffs and news at " + n + " this year, and what the press says of it.");
    }

    /** The full dossier's question: the organisation field's whole brief. */
    static String fullQuestion(String name, String site) { return "Who is " + anchored(name, site) + "? A full dossier: its history, power and governance, customers and rivals, money, people, decisions and what to expect."; }

    /**
     * Files the company's card, or its full dossier, as a research run in the organisation field, and notes the run on the company's record.
     * A sentence for the person.
     */
    public static String file(LibraryStore store, Career.Seeker s, ObjectNode c, boolean full, String patronDid) throws IOException {
        String name = c.path("name").asText(), site = c.path("site").asText("");
        JsonNode had = c.path("card");
        if (had.isObject() && !full && !had.path("job_id").asText("").isEmpty() && had.path("built").asText("").isEmpty()) return "The card of " + name + " is already filed as " + had.path("job_id").asText() + "; the runs page shows how far it has come.";
        ObjectNode args = M.createObjectNode();
        args.putObject("patron").put("did", patronDid == null || patronDid.isBlank() ? "person" : patronDid).put("name", "career").put("runtime", "career");
        args.put("question", full ? fullQuestion(name, site) : cardQuestion(name, site)).put("field", "organization").put("title", (full ? "Dossier: " : "Company card: ") + name);
        if (!full) {
            args.put("max_minutes", CARD_MINUTES).put("max_turns", CARD_TURNS);
            ArrayNode subs = args.putArray("sub_questions"); for (String q : cardSubQuestions(name, site)) subs.add(q);
        }
        JsonNode r = new LibraryProtocol(store).research(args);
        String id = r.path("job_id").asText("");
        c.putObject("card").put("job_id", id).put("filed", LocalDate.now().toString()).put("full", full).put("built", "");
        Career.log(store, s, (full ? "The full dossier of " : "The company card of ") + name + " is filed as " + id + ".");
        return (full ? "The full dossier of " : "The company card of ") + name + " is filed as " + id + ": a research run on the library's model" + (full ? ", with no time cap" : ", about " + CARD_MINUTES + " minutes")
                + ". The runs page shows how far it has come; once it lands, the company's openings are read again with it, and the dossier is on the Dossiers page.";
    }

    /** Whether the shelves hold anything on the company: a card, or more. */
    public static boolean known(LibraryStore store, String name) {
        try { return !Dossier.gather(store, name).claims().isEmpty(); } catch (RuntimeException | IOException e) { return false; }
    }

    /**
     * A card filed and now landed: the company's record says when, so readings made before it are made again, and the headcount the
     * card's claims give is kept for the cut. The run's state is read from the runs catalogue, cheaply; the shelves are read once, when
     * the run has ended. True when the record changed.
     */
    static boolean landed(LibraryStore store, ObjectNode c) {
        JsonNode card = c.path("card");
        if (!card.isObject() || !card.path("built").asText("").isEmpty()) return false;
        String id = card.path("job_id").asText("");
        if (!id.isEmpty()) {
            try {
                ObjectNode job = new Jobs(store, j -> { throw new IllegalStateException("read only"); }).get(id);
                String st = job == null ? "" : job.path("state").asText("");
                if (st.equals("running") || st.equals("queued") || st.equals(Jobs.OFFERED)) return false;
                if (!st.isEmpty() && !st.equals("done")) ((ObjectNode) card).put("failed", st);
            } catch (IOException e) { return false; }
        }
        if (!known(store, c.path("name").asText())) return false;
        ((ObjectNode) card).put("built", LocalDate.now().toString()).remove("failed");
        c.put("shelves", true);
        CareerSize.People p = people(store, c.path("name").asText());
        if (p != null) { c.remove("people_none_at"); c.putObject("people").put("low", p.low()).put("high", p.high()).put("source", p.source()).put("at", p.at()); }
        String st = stage(store, c.path("name").asText());
        if (!st.isEmpty() && c.path("stage").asText("").isEmpty()) c.put("stage", st).put("stage_by", "its company card");
        return true;
    }

    /** "1,079 full-time employees", "about 40 people", "6 employees": a number, at most two words, then the word for people. */
    static final Pattern PEOPLE = Pattern.compile("(?i)(\\d{1,3}(?:,\\d{3})+|\\d{1,7})\\s*\\+?(?:\\s+[a-z-]+){0,2}?\\s+(?:employees|people|staff|headcount|workers|team members|FTEs?)\\b");
    /** "5 to 6 employees", "51-200 employees": the fewest and the most. */
    static final Pattern RANGE = Pattern.compile("(?i)(\\d{1,3}(?:,\\d{3})+|\\d{1,7})\\s*(?:-|–|to)\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,7})(?:\\s+[a-z-]+){0,2}?\\s+(?:employees|people|staff|headcount|workers|team members|FTEs?)\\b");
    static final Pattern HEADCOUNT_OF = Pattern.compile("(?i)(?:headcount|workforce|team|staff)\\s+(?:of|is|was|at|reached|grew to)\\s+(?:about|around|over|nearly|roughly)?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,7})");
    static final Pattern NUMBER_OR_RANGE = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{1,7})(?:\\s*(?:-|–|to)\\s*(\\d{1,3}(?:,\\d{3})+|\\d{1,7}))?");

    /** How many people work there, from the claims on the shelves: the latest dated claim that says it, with its source's class. */
    public static CareerSize.People people(LibraryStore store, String name) {
        Dossier.Gathered g;
        try { g = Dossier.gather(store, name); } catch (RuntimeException | IOException e) { return null; }
        CareerSize.People best = null; String bestDate = "";
        for (Finding f : g.claims()) {
            int lo = 0, hi = 0;
            Finding.Triple t = f.triple();   // the claim's own shape first: "BigCommerce | had full-time employees | 1,079", "Tristero | has headcount | 5-6"
            if (t != null && t.predicate() != null && t.predicate().toLowerCase(Locale.ROOT).matches(".*(employ|headcount|staff|workforce|people).*")) {
                Matcher n = NUMBER_OR_RANGE.matcher(t.object() == null ? "" : t.object());
                if (n.find()) { lo = CareerSize.number(n.group(1)); hi = n.group(2) == null ? lo : CareerSize.number(n.group(2)); }
            }
            if (lo == 0) {
                String text = f.title() + " " + f.body();
                Matcher r = RANGE.matcher(text);
                if (r.find()) { lo = CareerSize.number(r.group(1)); hi = CareerSize.number(r.group(2)); }
                else { Matcher m = PEOPLE.matcher(text); if (m.find()) lo = hi = CareerSize.number(m.group(1)); else { Matcher h = HEADCOUNT_OF.matcher(text); if (h.find()) lo = hi = CareerSize.number(h.group(1)); } }
            }
            if (lo <= 0 || hi < lo || hi > 5_000_000) continue;
            String date = f.validAsOf() == null ? "" : f.validAsOf();
            if (best == null || date.compareTo(bestDate) > 0) {
                String klass = f.sources().isEmpty() ? "a claim" : SourceClass.of(f.sources().get(0).locator(), SourceClass.Own.of(List.of(name), "organization")).label();
                best = new CareerSize.People(lo, hi, "its company card, " + klass + (date.isEmpty() ? "" : ", as of " + date), LocalDate.now().toString());
                bestDate = date;
            }
        }
        return best;
    }

    static final Pattern STAGE = Pattern.compile("(?i)\\b(pre-seed|seed(?: round| stage| funding)?|series [a-f](?:\\+| round)?|bootstrapped|publicly traded|public company|went public|ipo|acquired by|acquisition)\\b");

    /** The company's stage, from the latest dated claim on the shelves that names one: "series B", "seed", "public", "acquired"; "" when none. */
    public static String stage(LibraryStore store, String name) {
        Dossier.Gathered g;
        try { g = Dossier.gather(store, name); } catch (RuntimeException | IOException e) { return ""; }
        String best = "", bestDate = "";
        for (Finding f : g.claims()) {
            Matcher m = STAGE.matcher(f.title() + " " + f.body() + (f.triple() == null ? "" : " " + f.triple().predicate() + " " + f.triple().object()));
            if (!m.find()) continue;
            String date = f.validAsOf() == null ? "" : f.validAsOf();
            if (best.isEmpty() || date.compareTo(bestDate) > 0) {
                String w = m.group(1).toLowerCase(Locale.ROOT);
                best = w.startsWith("series") ? "series " + w.replaceAll("(?i)series ([a-f]).*", "$1").toUpperCase(Locale.ROOT) : w.startsWith("seed") ? "seed" : w.startsWith("pre") ? "pre-seed"
                        : w.startsWith("acqui") ? "acquired" : w.equals("bootstrapped") ? "bootstrapped" : "public";
                bestDate = date;
            }
        }
        return best;
    }

    /** What the shelves hold on the company, for the reading beside the posting: what it does, the latest events, the claims that give numbers; "" when nothing. */
    /**
     * What the company does, in its card's words: the dossier's "What they do" where a dossier page was written, else the card's claims
     * on what it builds, ships or offers, up to three; clipped; "" when no card has landed.
     */
    public static String whatTheyDo(LibraryStore store, String name) {
        try {
            String md = Dossier.of(store, name).path("markdown").asText("");
            String what = section(md, "What they do").replaceAll("\\s+", " ").strip();
            if (!what.isEmpty()) return CareerSources.clip(what, 400);
            List<String> said = new ArrayList<>();
            for (Finding f : Dossier.gather(store, name).claims()) {
                String text = (f.title() + ": " + f.body()).replaceAll("\\s+", " ").strip();
                if (text.matches("(?is).*\\b(build|builds|building|develop|develops|make|makes|ship|ships|shipped|offer|offers|provide|provides|sell|sells|platform|product|products|tool|tools|model|models|software|hardware|robot|robots|device|devices|service|services)\\b.*")
                        && !text.matches("(?is).*\\b(employees|headcount|raised|funding|series [a-e]|seed round|valuation|revenue|laid off|layoffs)\\b.*")) said.add(CareerSources.clip(text, 160));
                if (said.size() >= 3) break;
            }
            return CareerSources.clip(String.join(" ", said), 400);
        } catch (RuntimeException | IOException e) { return ""; }
    }

    public static String about(LibraryStore store, String name) {
        Dossier.Gathered g;
        try { g = Dossier.gather(store, name); } catch (RuntimeException | IOException e) { return ""; }
        if (g.claims().isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        String md;
        try { md = Dossier.of(store, name).path("markdown").asText(""); } catch (RuntimeException | IOException e) { md = ""; }
        String what = section(md, "What they do"), when = section(md, "Timeline");
        if (!what.isBlank()) b.append(CareerSources.clip(what, 1200)).append('\n');
        if (!when.isBlank()) { String[] lines = when.split("\\R"); int n = 0; for (int i = lines.length - 1; i >= 0 && n < 8; i--) if (lines[i].startsWith("- ") || lines[i].matches("^\\d{4}.*")) { b.append(lines[i]).append('\n'); n++; } }
        int numbers = 0;
        for (Finding f : g.claims()) {
            String text = (f.title() + " — " + f.body()).replaceAll("\\s+", " ");
            if (text.matches("(?is).*(employees|headcount|people work|staff of|funding|raised|series [a-e]|seed|founded|valuation|revenue|laid off|layoffs|acquired|leads? (its )?engineering|cto|ceo).*") && numbers++ < 10)
                b.append("- ").append(CareerSources.clip(text, 240)).append(f.validAsOf() == null || f.validAsOf().isEmpty() ? "" : " (as of " + f.validAsOf() + ")").append('\n');
        }
        return CareerSources.clip(b.toString(), 3000);
    }

    /** One "## Heading" section of a dossier's markdown, without its heading; "" when absent. */
    static String section(String md, String heading) {
        int at = md.indexOf("## " + heading);
        if (at < 0) return "";
        int from = md.indexOf('\n', at), to = md.indexOf("\n## ", from < 0 ? at : from);
        return from < 0 ? "" : md.substring(from + 1, to < 0 ? md.length() : to).strip();
    }

    /**
     * The cards to file after a search, or a night: for the companies on the list with an opening that passes, nearest first, then the
     * companies of the place the model read as worth approaching; with no card or one older than a month, up to the setting. The number filed.
     */
    public static int fileForPassing(LibraryStore store, Career.Seeker s, CareerFind.View v, String patronDid) throws IOException {
        int room = cardsPerRun();
        if (room == 0) return 0;
        List<ObjectNode> cs = CareerFind.companies(store, s);
        List<String> order = new ArrayList<>();
        for (CareerFind.Row r : v.passing()) { String listed = r.kept() == null ? "" : r.kept().path("listed").asText(""); if (!listed.isEmpty() && !order.contains(listed)) order.add(listed); }
        CareerCut.Cut cut = CareerCut.kept(store, s);
        for (ObjectNode e : CareerCompanies.standing(store, s, cut == null ? CareerCut.Cut.NONE : cut))   // then the companies of the place worth approaching, newest raise first
            if (e.path("reading").path("approach").asText("").equals("yes") && !order.contains(e.path("name").asText(""))) order.add(e.path("name").asText(""));
        int filed = 0;
        for (String name : order) {
            if (filed >= room) break;
            ObjectNode c = null; for (ObjectNode x : cs) if (x.path("name").asText().equals(name)) c = x;
            if (c == null || !CareerFind.lookedAt(c)) continue;
            landed(store, c);
            JsonNode card = c.path("card");
            boolean fresh = card.isObject() && card.path("failed").asText("").isEmpty() && (card.path("built").asText("").isEmpty() ? !CareerFind.monthOld(card.path("filed").asText("")) : !CareerFind.monthOld(card.path("built").asText("")));
            if (fresh) continue;
            if (!card.isObject() && known(store, name)) {   // on the shelves from before the cards: counted as built today
                c.putObject("card").put("job_id", "").put("filed", "").put("full", false).put("built", "");
                landed(store, c);
                continue;
            }
            try { file(store, s, c, false, patronDid); filed++; } catch (RuntimeException e) { Career.log(store, s, "The card of " + name + " could not be filed: " + e.getMessage()); }
        }
        CareerFind.saveCompanies(store, s, cs);
        return filed;
    }

    /** The night's step: the cards due for each person whose watch is on; a sentence for the housekeeping's log. */
    public static String nightly(LibraryStore store) throws IOException {
        int filed = 0, people = 0;
        for (Career.Seeker s : Career.seekers(store)) {
            if (!CareerWatch.on(store, s) || CareerFind.companies(store, s).isEmpty()) continue;
            people++;
            filed += fileForPassing(store, s, CareerFind.view(store, s), "person");
        }
        return people == 0 ? "nobody watches" : filed + (filed == 1 ? " card filed" : " cards filed");
    }

    /** The card's state in words, for the page: none, filed, running, built, with the day. */
    public static String state(LibraryStore store, ObjectNode c) {
        landed(store, c);
        JsonNode card = c.path("card");
        if (!card.isObject()) return c.path("shelves").asBoolean(false) ? "on the shelves" : "";
        if (!card.path("built").asText("").isEmpty()) return (card.path("full").asBoolean(false) ? "dossier built " : "card built ") + card.path("built").asText();
        if (!card.path("failed").asText("").isEmpty()) return "the run " + card.path("job_id").asText() + " " + card.path("failed").asText() + " and filed nothing; a search files the card again";
        return (card.path("full").asBoolean(false) ? "dossier filed " : "card filed ") + card.path("filed").asText() + " (" + card.path("job_id").asText() + ")";
    }

    /** What the card does not give, which a full run would: size, stage. */
    public static List<String> gaps(ObjectNode c) {
        List<String> g = new ArrayList<>();
        if (!c.has("people")) g.add("size");
        if (c.path("stage").asText("").isEmpty()) g.add("stage");
        return g;
    }

    /**
     * What the person should know of the company's record, in one sentence: that there is no card yet, that one is running, that the card
     * gives no size or stage and a full run would, or that even the full run found none; "" when the record says what the wants ask.
     */
    public static String needs(ObjectNode c) {
        JsonNode card = c.path("card");
        boolean full = card.path("full").asBoolean(false), built = card.isObject() && !card.path("built").asText("").isEmpty();
        if (card.isObject() && !built && !card.path("job_id").asText("").isEmpty() && card.path("failed").asText("").isEmpty()) return (full ? "The full dossier" : "The card") + " is running (" + card.path("job_id").asText() + ")";
        if (!onShelves(c)) return "No card yet";
        List<String> g = gaps(c);
        if (g.isEmpty()) return "";
        return (full && built ? "Even the full run found no " : "Needs a full run to get its ") + Career.and(g);
    }

    /** Whether the company's shelves hold a card or more, by the record; the shelves themselves are read only when a run lands. */
    public static boolean onShelves(ObjectNode c) { return c.path("shelves").asBoolean(false) || !c.path("card").path("built").asText("").isEmpty(); }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
