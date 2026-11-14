package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerCut.Verdict;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Opening;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The career search (docs/DESIGN_CAREER.md, step 3): the companies to look at, their openings, the cut, and the model's reading of the
 * openings nearest to what the person wants. The companies are the ones the person named, the ones they kept from an earlier search,
 * and the library's proposals from what they want and their working life; the openings come from {@link CareerSources}, the cut from
 * {@link CareerCut}. All of it is kept under {@code career/<person>/search/}: the companies, every opening read with the days it was
 * first and last seen, the readings, the person's reactions, and a record of each search, so a change to the reading can be judged
 * again from what was kept without reading the web again.
 */
public final class CareerFind {
    private CareerFind() { }

    private static final ObjectMapper M = new ObjectMapper();

    public static final String COMPANIES_KEY = "RESEARCHZOSHO_CAREER_COMPANIES", READINGS_KEY = "RESEARCHZOSHO_CAREER_READINGS", WATCH_READINGS_KEY = "RESEARCHZOSHO_CAREER_WATCH_READINGS";
    /** How many companies a search looks at, those the person named and kept first. */
    static int companiesMax() { return Math.max(1, Math.min(300, Config.getInt(COMPANIES_KEY, 60))); }
    /** How many openings that pass the model reads in one search. */
    static int readingsMax() { return Math.max(0, Math.min(400, Config.getInt(READINGS_KEY, 40))); }
    /** How many new openings the model reads each night for a person who watches. */
    static int watchReadingsMax() { return Math.max(0, Math.min(100, Config.getInt(WATCH_READINGS_KEY, 10))); }
    /** At most this many openings from one company are read before a further one from any. */
    static final int PER_COMPANY = 3;
    /** The most companies the model proposes in one search: it cannot know who is hiring, and asked for more it names the famous. */
    static final int PROPOSALS_MAX = 5;
    /** The companies read at once: each needs a web search, and the search services answer a few at a time. */
    static volatile int THREADS = 3;

    static Path dir(LibraryStore store, Career.Seeker s) { return s.dir(store).resolve("search"); }

    // ---------------------------------------------------------------- what is kept

    static ObjectNode readObject(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return M.createObjectNode();
        JsonNode j = M.readTree(f.toFile());
        return j.isObject() ? (ObjectNode) j : M.createObjectNode();
    }

    static synchronized void write(Path f, JsonNode o) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        M.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), o);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The companies on the list, in their order: each with its name, who put it there and why, its state, its feed and what reading it gave. */
    public static List<ObjectNode> companies(LibraryStore store, Career.Seeker s) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode c : readObject(dir(store, s).resolve("companies.json")).path("companies")) if (c.isObject()) out.add((ObjectNode) c);
        return out;
    }

    public static void saveCompanies(LibraryStore store, Career.Seeker s, List<ObjectNode> cs) throws IOException {
        ObjectNode o = M.createObjectNode(); ArrayNode a = o.putArray("companies"); cs.forEach(a::add);
        write(dir(store, s).resolve("companies.json"), o);
    }

    /**
     * The openings kept: each one's particulars by its short name, without its words, which are kept apart ({@link #texts}) because only
     * the readings need them and a page reads the particulars every time; and what the mentions of the things kept out were worked out
     * against, so a change in that list works them out again.
     */
    record Kept(Map<String, ObjectNode> all, String mentionsBasis) { }

    static Kept kept(LibraryStore store, Career.Seeker s) throws IOException {
        ObjectNode root = readObject(dir(store, s).resolve("openings.json"));
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        root.path("openings").properties().forEach(e -> { if (e.getValue().isObject()) out.put(e.getKey(), (ObjectNode) e.getValue()); });
        return new Kept(out, root.path("mentions_basis").asText(""));
    }

    /** Every opening read, by its short name. */
    static Map<String, ObjectNode> openings(LibraryStore store, Career.Seeker s) throws IOException { return kept(store, s).all(); }

    static void saveOpenings(LibraryStore store, Career.Seeker s, Map<String, ObjectNode> all, String mentionsBasis) throws IOException {
        ObjectNode o = M.createObjectNode().put("mentions_basis", mentionsBasis); ObjectNode m = o.putObject("openings"); all.forEach(m::set);
        write(dir(store, s).resolve("openings.json"), o);
    }

    /** The openings' own words, by short name. */
    static Map<String, String> texts(LibraryStore store, Career.Seeker s) throws IOException {
        Map<String, String> out = new HashMap<>();
        readObject(dir(store, s).resolve("texts.json")).path("texts").properties().forEach(e -> out.put(e.getKey(), e.getValue().asText("")));
        return out;
    }

    static void saveTexts(LibraryStore store, Career.Seeker s, Map<String, String> texts) throws IOException {
        ObjectNode o = M.createObjectNode(); ObjectNode m = o.putObject("texts"); texts.forEach(m::put);
        write(dir(store, s).resolve("texts.json"), o);
    }

    /** What the list of things kept out is, as the mentions are worked out against it. */
    static String mentionsBasis(List<String> keepOut) { return CareerSources.id("out|" + String.join("\n", keepOut)); }

    /** Each opening's mentions of the things kept out, worked out again from its words. */
    static void remention(Map<String, ObjectNode> all, Map<String, String> texts, List<String> keepOut) {
        for (ObjectNode o : all.values()) {
            List<String> m = CareerCut.mentions(texts.getOrDefault(o.path("id").asText(), ""), keepOut);
            if (m.isEmpty()) o.remove("mentions"); else { ArrayNode a = o.putArray("mentions"); m.forEach(a::add); }
        }
    }

    static List<String> mentionsOf(ObjectNode o) { List<String> out = new ArrayList<>(); for (JsonNode x : o.path("mentions")) out.add(x.asText()); return out; }

    /** The particulars and the words of what was read, saved together, with the mentions worked out against the list kept out now. */
    static void saveAll(LibraryStore store, Career.Seeker s, Map<String, ObjectNode> all, Map<String, String> texts) throws IOException {
        List<String> keepOut = CareerSearch.keepOut(store, s);
        remention(all, texts, keepOut);
        saveTexts(store, s, texts);
        saveOpenings(store, s, all, mentionsBasis(keepOut));
    }

    static ObjectNode readings(LibraryStore store, Career.Seeker s) throws IOException { return readObject(dir(store, s).resolve("readings.json")); }
    static ObjectNode reactions(LibraryStore store, Career.Seeker s) throws IOException { return readObject(dir(store, s).resolve("reactions.json")); }

    static Opening of(ObjectNode j) {
        return new Opening(j.path("id").asText(), j.path("company").asText(), j.path("title").asText(), j.path("location").asText(), j.path("remote").asBoolean(false), j.path("url").asText(),
                j.path("posted").asText(), j.path("posted_note").asText(), j.path("source").asText(), j.path("text").asText(), j.path("detail").asText(), j.path("pay").asText());
    }

    static ObjectNode json(Opening o) {
        return M.createObjectNode().put("id", o.id()).put("company", o.company()).put("title", o.title()).put("location", o.location()).put("remote", o.remote()).put("url", o.url())
                .put("posted", o.posted()).put("posted_note", o.postedNote()).put("source", o.source()).put("detail", o.detail()).put("pay", o.pay());
    }

    /**
     * What a read brought, into what is kept: a new opening dated today, one seen again marked so; when the read was a company's whole
     * list, its openings that are no longer on it are marked gone today.
     */
    static void merge(Map<String, ObjectNode> all, Map<String, String> texts, List<Opening> got, String listed, boolean whole, String today) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        synchronized (all) {
            Set<String> seen = new HashSet<>();
            for (Opening o : got) {
                if (o.title().isBlank()) continue;
                seen.add(o.id());
                if (!o.text().isBlank()) texts.put(o.id(), o.text());
                ObjectNode was = all.get(o.id()), j = json(o);
                if (was != null) j.put("first_seen", was.path("first_seen").asText(today)).put("first_seen_at", was.path("first_seen_at").asText(""));
                else j.put("first_seen", today).put("first_seen_at", now);
                j.put("last_seen", today).put("listed", listed);
                all.put(o.id(), j);
            }
            if (whole && !listed.isEmpty())
                for (ObjectNode o : all.values())
                    if (o.path("listed").asText().equals(listed) && !seen.contains(o.path("id").asText()) && o.path("gone").asText("").isEmpty()) o.put("gone", today);
        }
    }

    // ---------------------------------------------------------------- the search

    /** Where a search tells how far it has come: a sentence for the runs page. */
    public interface Progress { void at(String sentence); }

    /** The search, as the library's runs run it: the cut, the companies, their openings, the open listings, the readings. A sentence on what it found. */
    public static String run(LibraryStore store, Career.Seeker s, Progress progress, BooleanSupplier stop) throws IOException { return run(store, s, progress, stop, ""); }

    /**
     * As above; {@code vary} is a change for this search only, in the person's words: the run's cut, the field and the keep-outs come from
     * the standing record with the words applied, kept with the run's record and never as the person's decisions.
     */
    public static String run(LibraryStore store, Career.Seeker s, Progress progress, BooleanSupplier stop, String vary) throws IOException {
        String today = LocalDate.now().toString();
        CareerVariation.Variation var = null;
        if (vary != null && !vary.isBlank()) {
            progress.at("Reading what you said for this search");
            var = CareerVariation.read(store, s, vary);
            Career.log(store, s, var.said());
            for (String n : var.named()) CareerSearch.addCompany(store, s, n, "what you said for this search");
        }
        readFirst(store, s, progress);
        progress.at("Making the cut from what you want");
        Cut cut = var == null ? CareerCut.current(store, s) : CareerCut.make(store, s, var.wants(), var.keepOut(), "this search", false);
        progress.at("Drawing how seats are filled for you");
        CareerShape.Shape shape = CareerShape.current(store, s);   // how seats for this work in this place are filled: what the search reads and what it skips
        Career.log(store, s, CareerShape.logLine(shape));
        if (shape.has("referral")) {
            List<String> names = new ArrayList<>();
            for (ObjectNode n : CareerSearch.named(store, s)) names.add(n.path("name").asText(""));
            Career.log(store, s, "Seats are filled through people who know people, so the names you gave are the way in" + (names.isEmpty() ? "; you have named nobody yet. Name the people and the employers you know, in the interview or with career company and a name, and the search keeps them first." : ": " + Career.and(names) + ". The search keeps them first."));
        }
        if (shape.has("appointment")) Career.log(store, s, "Seats are filled by rounds and appointments, so the search reads where the institutions are listed and the openings of those on the list, and the watch keeps reading them.");
        progress.at("Gathering the companies of your place from the public records");
        int known = 0, readCompanies = 0;
        try {
            known = CareerCompanies.refresh(store, s, cut, stop);
            if (known > 0 && var == null) { progress.at("Reading the companies of your place against what you want"); readCompanies = CareerCompanies.read(store, s, cut, 60, stop); }   // the readings are against what the person decided; a change for one search leaves them
        } catch (RuntimeException e) { Career.log(store, s, "The companies of your place could not be gathered: " + e.getMessage()); }
        progress.at("Choosing the companies to look at");
        List<ObjectNode> list = companyList(store, s, cut, shape, progress, stop, var, true);
        List<ObjectNode> look = new ArrayList<>();
        for (ObjectNode c : list) if (lookedAt(c)) look.add(c);
        Map<String, ObjectNode> all = openings(store, s);
        Map<String, String> texts = texts(store, s);
        AtomicInteger done = new AtomicInteger();
        progress.at("Reading the companies' openings, 0 of " + look.size() + " done");
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, THREADS));
        DriveClient.Purse purse = DriveClient.purse();   // the job's: the readers' model calls go on the ledger with the job's own
        try {
            List<Future<?>> fs = new ArrayList<>();
            List<String> towns = towns(cut, List.of(Career.location(store, s)));
            for (ObjectNode c : look) fs.add(pool.submit(() -> {
                DriveClient.useFor(purse);
                if (stop.getAsBoolean()) return null;
                String name = c.path("name").asText();
                Found f = readCompany(c, cut, towns);
                sizeOf(store, c, f, today);
                synchronized (c) {
                    c.put("read_at", today).put("openings", f.openings().size()).put("note", f.note());
                    stillListed(c, f);
                    if (f.feed() != null) c.putObject("feed").put("kind", f.feed().kind()).put("token", f.feed().token()).put("how", f.feed().how()).put("system", f.feed().system());
                    else c.remove("feed");
                }
                merge(all, texts, f.openings(), name, f.feed() != null && f.note().isEmpty(), today);
                if (c.has("posting")) merge(all, texts, List.of(of((ObjectNode) c.get("posting"))), name, false, today);   // the posting a portfolio board gave, read from its page
                progress.at("Reading the companies' openings, " + done.incrementAndGet() + " of " + look.size() + " done");
                return null;
            }));
            for (Future<?> f : fs) { try { f.get(); } catch (Exception e) { /* one company's failure is its own note, never the search's */ } }
        } finally { pool.shutdownNow(); }
        saveCompanies(store, s, list);
        try {   // what the cards and the sites said of the companies of the place goes back to their record, and those are read again with it
            Set<String> learned = new LinkedHashSet<>(CareerCompanies.learn(store, s, list));
            learned.addAll(CareerCompanies.learnFromShelves(store, s));   // and the cards of companies the list no longer carries
            if (!learned.isEmpty() && !stop.getAsBoolean()) { progress.at("Reading again the companies of your place whose size or stage a card gave"); CareerCompanies.read(store, s, cut, learned.size(), stop, learned); }
        } catch (RuntimeException e) { Career.log(store, s, "The sizes the cards gave could not be written back to the companies of your place: " + e.getMessage()); }
        int hn = 0, usa = 0;
        if (!stop.getAsBoolean()) {
            if (shape.reads(CareerShape.READS_HN)) {   // a software and startup board: read for the trades it carries, skipped for the others
                progress.at("Reading the openings in Hacker News's Who is hiring");
                List<Opening> h = CareerSources.hackerNews();
                hn = h.size();
                merge(all, texts, h, "", false, today);
            }
            if (CareerSources.usaJobsReady() && shape.reads(CareerShape.READS_USAJOBS)) {
                progress.at("Reading USAJobs");
                List<Opening> u = CareerSources.usaJobs(cut.titles());
                usa = u.size();
                merge(all, texts, u, "", false, today);
            }
        }
        saveAll(store, s, all, texts);
        try { scoreSources(store, s, list, cut); } catch (RuntimeException e) { Career.log(store, s, "The sources' score could not be kept: " + e.getMessage()); }
        View v = view(store, s, cut, var == null ? null : var.keepOut());   // by this run's cut: the standing one, or the one made for this search
        int cards = stop.getAsBoolean() ? 0 : CareerCompany.fileForPassing(store, s, v, "person");
        if (cards > 0) progress.at("Filed " + cards + (cards == 1 ? " company card" : " company cards") + ", research runs that follow this one");
        int read = stop.getAsBoolean() ? 0 : readSome(store, s, v, readingsMax(), progress, stop);
        v = view(store, s, cut, var == null ? null : var.keepOut());
        snapshot(store, s, v, cut, var == null ? "" : var.words());
        String said = summary(v, look.size(), hn, usa, read) + (known > 0 ? " The public records give " + known + " employers of your place; the model read " + readCompanies + " of them against what you want this time." : "");
        Career.log(store, s, said);
        return said + (stop.getAsBoolean() ? " The search was stopped before it finished." : "");
    }

    /** The keep-outs with the person's current employers added, those they did not name themselves, so every source's names and every opening are judged against them as against a keep-out. */
    static List<String> withCurrent(List<String> keepOut, List<String> employers, Set<String> named) {
        List<String> out = new ArrayList<>(keepOut);
        for (String e : employers) if (!named.contains(CareerSearch.key(e)) && out.stream().noneMatch(k -> k.equalsIgnoreCase(e))) out.add(e);
        return out;
    }

    /**
     * A search with something handed over but no reading written yet writes the reading first and drafts what the person wants from it,
     * as the page's button would: the guesses the cut and the search run on until the person decides. A reading with no draft gets its
     * draft. True when something was written.
     */
    static boolean readFirst(LibraryStore store, Career.Seeker s, Progress progress) throws IOException {
        boolean noReading = Career.readingKept(store, s).isBlank(), noDraft = Career.wantsKept(store, s).path("dimensions").isEmpty();
        if (noReading && Career.evidence(store, s).isEmpty()) return false;
        if (!noReading && !noDraft) return false;
        progress.at(noReading ? "Reading your working life first, since no reading was written yet" : "Drafting what you want from the reading first");
        if (noReading && Career.reading(store, s).isBlank()) return false;
        Career.wants(store, s);
        Career.log(store, s, (noReading ? "The search wrote the reading of your working life first, since none was written yet, and drafted what you want from it" : "The search drafted what you want from the reading first")
                + ": the guesses it runs on until you decide. Decide the topics on your page, or answer the reading's questions, and the next search cuts by them.");
        return true;
    }

    /** A company's openings: from the feed an earlier search found, when it still answers; else found again through a search for its careers page. */
    static Found readCompany(ObjectNode c, Cut cut) { return readCompany(c, cut, towns(cut, List.of())); }

    /** The towns an institution's openings are cut to: the places decided and those around them, else the library's guess, else the record's own. */
    static List<String> towns(Cut cut, List<String> recordPlace) {
        List<String> out = new ArrayList<>(cut.places());
        for (String n : cut.nearby()) if (!out.contains(n)) out.add(n);
        if (out.isEmpty()) out.addAll(cut.preferPlaces());
        if (out.isEmpty()) for (String p : recordPlace) for (String w : p.split("\\s*,\\s*")) if (!w.isBlank() && !out.contains(w.strip())) out.add(w.strip());
        return out;
    }

    static Found readCompany(ObjectNode c, Cut cut, List<String> towns) {
        String name = c.path("name").asText();
        List<String> anchors = new ArrayList<>();
        for (JsonNode a : c.path("anchors")) if (!a.asText("").isBlank()) anchors.add(a.asText());
        JsonNode feed = c.path("feed");
        if (feed.isObject()) {
            Found f = CareerSources.read(new CareerSources.Feed(feed.path("kind").asText(), feed.path("token").asText(), feed.path("how").asText()), name, cut.titles());
            if (f != null && (anchors.isEmpty() || f.feed() == null || CareerSources.anchored(f, anchors))) return f;
            if (f != null) synchronized (c) {   // a board kept from before that names none of the record's words: a namesake's, with the site and the size that came from it
                c.remove("feed"); c.remove("site"); c.remove("site_at");
                if (!c.path("people").path("source").asText("").startsWith("its company card")) c.remove("people");
            }
        }
        if (c.path("site").asText("").isEmpty() && c.path("from").asText("").equals("sources"))   // an institution a register listed, with no site of its own: through the organisation it belongs to when its own name gives nothing
            return CareerPortals.find(name, towns, cut.titles(), anchors);
        return CareerSources.openings(name, cut.titles(), anchors);
    }

    /**
     * A company found hiring for a posting the web search turned up: when the company's own board, read just now, no longer lists that
     * posting, the search engine's copy was stale, and the reason the company is on the list says so rather than claiming a hiring.
     */
    static void stillListed(ObjectNode c, Found f) {
        String why = c.path("why").asText("");
        int at = why.indexOf(", found in a search of the hiring sites.");
        if (!why.startsWith("It is hiring for ") || at < 0 || f.feed() == null) return;
        String posting = why.substring("It is hiring for ".length(), at), k = CareerSearch.key(posting);
        for (Opening o : f.openings()) { String t = CareerSearch.key(o.title()); if (!t.isEmpty() && (t.contains(k) || k.contains(t))) return; }
        c.put("why", "A search of the hiring sites found its page for " + posting + ", but its own board no longer lists that opening.");
    }

    /** The company's website and its size, looked up once a month: the size is tied to the site, so a namesake's never passes for the company's. */
    static void sizeOf(LibraryStore store, ObjectNode c, Found f, String today) {
        String name = c.path("name").asText();
        if (c.path("site").asText("").isEmpty() && f.feed() != null) {
            String site = CareerSize.findSite(name, f.feed());
            synchronized (c) { c.put("site", site).put("site_at", today); }
        }
        CareerSize.People fromCard = CareerCompany.people(store, name);   // the dossier's claims first: each with its source and its class
        if (c.path("stage").asText("").isEmpty()) { String st = CareerCompany.stage(store, name); if (!st.isEmpty()) synchronized (c) { c.put("stage", st).put("stage_by", "its company card"); } }
        if (fromCard != null) { synchronized (c) { c.remove("people_none_at"); c.putObject("people").put("low", fromCard.low()).put("high", fromCard.high()).put("source", fromCard.source()).put("at", fromCard.at()); } return; }
        String site = c.path("site").asText("");
        if (site.isEmpty() || (c.has("people") && !monthOld(c.path("people").path("at").asText(""))) || (c.has("people_none_at") && !monthOld(c.path("people_none_at").asText("")))) return;
        CareerSize.People p = CareerSize.lookup(name, site);
        synchronized (c) {
            if (p == null) { c.put("people_none_at", today); return; }
            c.remove("people_none_at");
            c.putObject("people").put("low", p.low()).put("high", p.high()).put("source", p.source()).put("at", p.at());
        }
    }

    static boolean monthOld(String day) { try { return LocalDate.parse(day).isBefore(LocalDate.now().minusDays(30)); } catch (Exception e) { return true; } }

    /** What the library knows of a company on the list, for the cut: its size and its board's opening count. */
    static CareerCut.Company companyOf(ObjectNode c) {
        if (c == null) return null;
        CareerSize.People p = c.has("people") ? new CareerSize.People(c.path("people").path("low").asInt(), c.path("people").path("high").asInt(), c.path("people").path("source").asText(""), c.path("people").path("at").asText("")) : null;
        return new CareerCut.Company(p, c.has("feed") ? c.path("openings").asInt(0) : 0);
    }

    /** Whether a company on the list is looked at by a search: not dropped, not kept out, and not one of the library's that gave way. */
    static boolean lookedAt(ObjectNode c) { String st = c.path("state").asText(""); return !st.equals("dropped") && !st.equals("out") && !st.equals("gone"); }

    /**
     * The companies a search looks at: the person's named ones and kept ones, the library's earlier ones that still have an opening
     * passing, then the boards, the hiring sites and the model's proposals to fill the rest; a company of the library's with nothing
     * passing gives way. Dropped companies stay on the list as dropped, so they are never proposed again; companies kept out are never
     * on it.
     */
    static List<ObjectNode> companyList(LibraryStore store, Career.Seeker s, Cut cut) throws IOException { return companyList(store, s, cut, CareerShape.current(store, s)); }

    /** As above, for a shape already drawn: the boards and the hiring sites are read only when seats for this work are filled through them. */
    static List<ObjectNode> companyList(LibraryStore store, Career.Seeker s, Cut cut, CareerShape.Shape shape) throws IOException { return companyList(store, s, cut, shape, sentence -> { }, () -> false, null, false); }

    /**
     * As above, within a run: the research of the employers in a decided field says where it is through {@code progress} and stops with the
     * run; with {@code var}, the topics and the keep-outs are the run's own, from a change for this search only.
     */
    static List<ObjectNode> companyList(LibraryStore store, Career.Seeker s, Cut cut, CareerShape.Shape shape, Progress progress, BooleanSupplier stop, CareerVariation.Variation var, boolean research) throws IOException {
        List<ObjectNode> was = companies(store, s), out = new ArrayList<>();
        List<String> now = Career.currentEmployers(store, s);   // where the person works now: not an employer to look at, whatever names it, unless they name it themselves
        Set<String> named = new HashSet<>();
        for (ObjectNode c : was) if (c.path("by").asText("").equals("you")) named.add(CareerSearch.key(c.path("name").asText()));
        for (String n : CareerSearch.companies(store, s)) named.add(CareerSearch.key(n));   // the companies they asked the search to look at, listed or not yet
        List<String> keepOut = withCurrent(var == null ? CareerSearch.keepOut(store, s) : var.keepOut(), now, named);
        if (!now.isEmpty() && now.stream().noneMatch(e -> named.contains(CareerSearch.key(e)))) Career.log(store, s, Career.and(now) + (now.size() == 1 ? " is" : " are") + " where you work now, so the search leaves " + (now.size() == 1 ? "it" : "them") + " out. Name it with career company and the name if you mean it.");
        Map<String, ObjectNode> byKey = new LinkedHashMap<>();
        for (ObjectNode c : was) byKey.put(CareerSearch.key(c.path("name").asText()), c);
        for (String n : CareerSearch.companies(store, s)) {
            if (keptOut(n, keepOut)) continue;
            ObjectNode c = byKey.get(CareerSearch.key(n));
            if (c == null) c = M.createObjectNode().put("name", n).put("first_listed", LocalDate.now().toString());
            c.put("by", "you").put("why", "You named it.");
            if (c.path("state").asText("").equals("out")) c.put("state", "");
            out.add(c);
        }
        Map<String, int[]> passing = view(store, s).perCompany();   // a company the library listed stays while an opening of its passes: the list is pruned by results, not rebuilt
        for (ObjectNode c : was) {
            if (out.contains(c)) continue;
            String st = c.path("state").asText("");
            if (keptOut(c.path("name").asText(), keepOut)) continue;
            if (fromCompanies(c) && !st.equals("kept") && !st.equals("dropped")) continue;   // one of the companies of the place: listed again below while the reading says to approach, never gone for want of openings
            int[] pc = passing.get(c.path("name").asText());
            boolean passes = pc != null && pc[1] > 0;
            if (st.equals("kept") || st.equals("dropped")) out.add(c);
            else if (passes) { if (st.equals("gone")) c.put("state", ""); out.add(c); }
            else { c.put("state", "gone"); out.add(c); }   // gave way: kept with what is known of it, its size among that, and not looked at until an opening of its passes again
        }
        for (Map.Entry<String, int[]> e : passing.entrySet()) {   // a company that left the list while its openings stayed and still pass: back on it, with the board its openings were read from
            String name = e.getKey();
            if (name.isEmpty() || e.getValue()[1] == 0 || keptOut(name, keepOut) || out.stream().anyMatch(c -> c.path("name").asText().equals(name))) continue;
            ObjectNode c = M.createObjectNode().put("name", name).put("by", "library").put("why", "Its openings passed an earlier search.").put("state", "").put("first_listed", LocalDate.now().toString());
            for (ObjectNode o : openings(store, s).values()) {
                if (!o.path("listed").asText("").equals(name)) continue;
                String src = o.path("source").asText("");
                int colon = src.indexOf(':');
                if (colon > 0 && !src.startsWith("hn:") && !src.startsWith("board:") && !src.equals("usajobs")) {
                    CareerSources.Feed fd = new CareerSources.Feed(src.substring(0, colon), src.substring(colon + 1), "read in an earlier search");
                    c.putObject("feed").put("kind", fd.kind()).put("token", fd.token()).put("how", fd.how()).put("system", fd.system());
                    break;
                }
            }
            out.add(c);
        }
        long looked = out.stream().filter(CareerFind::lookedAt).count();
        int room = (int) Math.max(0, companiesMax() - looked);
        Set<String> listed = new HashSet<>(), feeds = new HashSet<>();
        for (ObjectNode c : out) { listed.add(CareerSearch.key(c.path("name").asText())); if (c.has("feed")) feeds.add(c.path("feed").path("kind").asText() + ":" + c.path("feed").path("token").asText()); }
        Predicate<String> titleOk = title -> CareerCut.judge(cut, CareerSources.opening("", title, "", false, "about:" + title, "", "", "", "", "", ""), keepOut, List.of()).passes();
        if (room > 0) {   // the employers listed where such employers are listed for this seeker: the library's sources for the field, place and occupation, and the pages the model's searches find
            try {
                Map<String, CareerCompanies.Want> wants = var == null ? CareerCompanies.wantsOf(store, s) : CareerCompanies.wantsOf(var.wants());
                CareerCompanies.Want role = wants.get("role"), kind = wants.get("kind of employer"), domain = wants.get("domain");
                String latest = Career.latestTitle(store, s);
                String occupation = role != null && role.decided() ? role.text() : !latest.isEmpty() ? latest : !cut.titles().isEmpty() ? String.join(" ", cut.titles()) : String.join(" ", cut.preferTitles());   // what they decided, else the record's latest title as a phrase, else the cut's words, else its guess; never a question
                String employerKind = kind != null && kind.decided() ? kind.text() : "";
                String field = domain != null && domain.decided() ? domain.text() : "";   // the area they decided to work in names the employers to look for, beside the occupation
                if (research && !field.isEmpty() && !stop.getAsBoolean()) {   // the employers in that field, by the library's own research, in a person's own search run: the run that answers every question; each checked by its hiring page below
                    String placeFor = CareerTargets.placeWords(cut).isEmpty() ? Career.location(store, s) : CareerTargets.placeWords(cut);
                    for (CareerEmployers.Named n : CareerEmployers.find(store, s, field, occupation, placeFor, progress, stop)) {
                        String k = CareerSearch.key(n.name());
                        if (k.isEmpty() || listed.contains(k) || keptOut(n.name(), keepOut)) continue;
                        ObjectNode c = byKey.containsKey(k) ? byKey.get(k) : M.createObjectNode().put("name", n.name()).put("first_listed", LocalDate.now().toString());
                        c.put("by", "library").put("from", "research").put("state", "").put("why", "Named by the library's research of the employers in the field of " + field + (n.where().isEmpty() ? "" : ", " + n.where()) + (n.why().isEmpty() ? "" : ": " + n.why()) + ".");
                        if (!n.site().isEmpty() && c.path("site").asText("").isEmpty()) c.put("site", n.site());
                        listed.add(k); out.add(c);
                        if (--room == 0) break;
                    }
                }
                if (room > 0) {
                CareerTargets.Found tf = CareerTargets.find(store, s, cut, occupation, employerKind, field, Career.location(store, s), Math.max(1, room / 3), () -> false);
                for (String note : tf.notes()) Career.log(store, s, "Where such employers are listed: " + note + ".");
                for (Map.Entry<String, String> e : tf.employers().entrySet()) {
                    String name = e.getKey(), k = CareerSearch.key(name);
                    if (k.isEmpty() || listed.contains(k) || keptOut(name, keepOut)) continue;
                    String[] v = e.getValue().split("\t", 2);   // the source's id, and for a register its record of the employer
                    Sources.Source src = Sources.get(store, v[0]);
                    ObjectNode c = byKey.containsKey(k) ? byKey.get(k) : M.createObjectNode().put("name", name).put("first_listed", LocalDate.now().toString());
                    c.put("by", "library").put("from", "sources").put("state", "").put("why", src != null && src.kind().equals("register") && v.length > 1 ? "On " + v[1] + "."
                            : "Listed on " + (src == null ? v[0] : src.address() + " (" + src.id() + ", a " + src.kind() + ")") + ", where such employers are listed for you" + (v.length > 1 ? ": " + v[1] : "") + ".");
                    listed.add(k); out.add(c);
                    if (--room == 0) break;
                }
                }
            } catch (RuntimeException e) { Career.log(store, s, "The sources for your place could not be read this time: " + e.getMessage()); }
        }
        if (room > 0) {   // the companies of the place the model read as worth approaching: on the list first among the library's, their boards read for openings
            for (ObjectNode e : CareerCompanies.standing(store, s, cut)) {
                if (!e.path("reading").path("approach").asText("").equals("yes")) continue;
                String name = e.path("name").asText(), k = CareerSearch.key(name);
                if (k.isEmpty() || listed.contains(k) || keptOut(name, keepOut)) continue;
                ObjectNode old = byKey.get(k);   // listed before: its card, feed, size and stage stay with it
                ObjectNode c = old != null ? old : M.createObjectNode().put("name", name).put("first_listed", LocalDate.now().toString());
                c.put("by", "library").put("from", "companies").put("state", "").put("why", "Among the companies of your place, worth approaching: " + e.path("reading").path("fits").asText(""));
                if (!e.path("site").asText("").isEmpty() && c.path("site").asText("").isEmpty()) c.put("site", e.path("site").asText());
                if (e.path("people").asInt(0) > 0 && !c.has("people")) c.putObject("people").put("low", e.path("people").asInt()).put("high", e.path("people").asInt()).put("source", e.path("people_by").asText("")).put("at", LocalDate.now().toString());
                if (!e.path("stage").asText("").isEmpty() && c.path("stage").asText("").isEmpty()) c.put("stage", e.path("stage").asText()).put("stage_by", "the public records");
                ArrayNode anchors = c.putArray("anchors");   // the record's own words, which a board found for the name must carry to be taken as its own
                for (String a : CareerCompanies.anchors(e)) anchors.add(a);
                listed.add(k);
                out.add(c);
                if (--room == 0) break;
            }
        }
        if (room > 0 && shape.reads(CareerShape.READS_PORTFOLIO) && (!cut.places().isEmpty() || cut.remote().equals("only"))) {   // the portfolio boards for the place: the small companies there, which a web search does not reach; software and startup seats
            CareerBoards.Found bf = CareerBoards.find(store, cut.places(), !cut.remote().isEmpty() && !cut.remote().equals("no"), titleOk, Math.max(1, room / 2));
            for (CareerSources.Discovered d : bf.companies()) {
                String k = CareerSearch.key(d.company());
                if (k.isEmpty() || listed.contains(k) || keptOut(d.company(), keepOut) || (d.feed() != null && !feeds.add(d.feed().label()))) continue;
                CareerBoards.Known kn = bf.known().get(k);
                ObjectNode c = M.createObjectNode().put("name", d.company()).put("by", "library").put("why", Career.cap(d.feed() != null ? d.feed().how() : "on a portfolio board, hiring for " + d.posting()) + ".")
                        .put("state", "").put("first_listed", byKey.containsKey(k) ? byKey.get(k).path("first_listed").asText(LocalDate.now().toString()) : LocalDate.now().toString());
                if (d.feed() != null) c.putObject("feed").put("kind", d.feed().kind()).put("token", d.feed().token()).put("how", d.feed().how()).put("system", d.feed().system());
                if (kn != null && !kn.stage().isEmpty()) c.put("stage", kn.stage()).put("stage_by", kn.board() + "'s portfolio board");
                CareerSources.Opening p = bf.postings().get(k);
                if (p != null) c.set("posting", json(p));
                listed.add(k);
                out.add(c);
                if (--room == 0) break;
            }
            if (!bf.notes().isEmpty()) Career.log(store, s, "The portfolio boards read, those known for your place and those read for any place: " + String.join("; ", bf.notes()) + ".");
        }
        if (room > 0 && shape.posted()) {   // companies hiring now for the titles wanted, found on the hiring systems' own pages: evidence before the model's proposals; only where seats are posted openly
            int share = Math.max(1, room * 2 / 3);   // the model's proposals keep a third of the room
            for (CareerSources.Discovered d : CareerSources.discover(searchTerms(cut), searchPlaces(cut), share, titleOk, d -> CareerSources.stillListed(d, cut.titles()))) {
                String k = CareerSearch.key(d.company());
                if (k.isEmpty() || listed.contains(k) || d.feed() == null || !feeds.add(d.feed().label()) || keptOut(d.company(), keepOut)) continue;
                ObjectNode c = M.createObjectNode().put("name", d.company()).put("by", "library").put("why", "It is hiring for " + CareerSources.clip(d.posting(), 120) + ", found in a search of the hiring sites.")
                        .put("state", "").put("first_listed", byKey.containsKey(k) ? byKey.get(k).path("first_listed").asText(LocalDate.now().toString()) : LocalDate.now().toString());
                c.putObject("feed").put("kind", d.feed().kind()).put("token", d.feed().token()).put("how", d.feed().how()).put("system", d.feed().system());
                listed.add(k);
                out.add(c);
                room--;
                if (--share == 0) break;
            }
        }
        room = Math.min(room, PROPOSALS_MAX);   // the model's choice is the weakest source: a few, for the person to try, never the list
        if (room > 0) {
            List<ObjectNode> pool = new ArrayList<>();   // what the model may choose among: companies a record listed in the place that are not on the list; it never names one from nothing
            for (ObjectNode e : CareerCompanies.standing(store, s, cut)) {
                String k = CareerSearch.key(e.path("name").asText());
                if (!k.isEmpty() && !listed.contains(k) && !keptOut(e.path("name").asText(), keepOut)) pool.add(e);
            }
            List<ObjectNode> proposed;
            try { proposed = propose(store, s, room, out, keepOut, pool); }
            catch (RuntimeException e) { proposed = List.of(); Career.log(store, s, "The model did not answer for the library's choice among the companies of your place, so this search looks only at the companies you named and kept."); }
            for (ObjectNode p : proposed) {
                String k = CareerSearch.key(p.path("name").asText());
                if (k.isEmpty() || listed.contains(k) || keptOut(p.path("name").asText(), keepOut)) continue;
                ObjectNode old = byKey.get(k);
                if (old != null && old.has("feed")) p.set("feed", old.get("feed"));
                if (old != null) p.put("first_listed", old.path("first_listed").asText(LocalDate.now().toString()));
                listed.add(k);
                out.add(p);
                if (--room == 0) break;
            }
        }
        saveCompanies(store, s, out);
        return out;
    }

    /** What the discovery searches for: each title word; or, when a title must show two sides, a word of each. */
    static List<String> searchTerms(Cut cut) {
        List<String> a = CareerCut.shortForms(!cut.titles().isEmpty() ? cut.titles() : cut.preferTitles()), b = cut.titles().isEmpty() ? List.of() : CareerCut.shortForms(cut.titlesAlso());   // the abbreviations, which the job pages carry
        List<String> out = new ArrayList<>();
        String area = !cut.areas().isEmpty() ? cut.areas().get(0) : cut.preferTitles().isEmpty() ? "" : cut.preferTitles().get(0);   // a rank word searched with the area: "VP" "Engineering"
        if (b.isEmpty()) for (String w : a.subList(0, Math.min(3, a.size()))) out.add("\"" + w + "\"" + (CareerCut.GENERIC.matcher(w).matches() && !area.isEmpty() && !cut.titles().contains(area) ? " \"" + area + "\"" : ""));
        else for (String x : a.subList(0, Math.min(2, a.size()))) for (String y : b.subList(0, Math.min(2, b.size()))) out.add("\"" + x + "\" \"" + y + "\"");
        return out;
    }


    /** Where the discovery searches: only a place the person decided, with remote when remote counts; a guessed place never narrows what is found. */
    static List<String> searchPlaces(Cut cut) {
        List<String> out = new ArrayList<>(cut.places().subList(0, Math.min(2, cut.places().size())));
        if ((cut.remote().equals("fine") && !out.isEmpty()) || cut.remote().equals("only")) out.add("Remote");
        return out;
    }

    /**
     * What each source's employers survived, kept with the source: an employer with an opening that passes, or liked, survived; one cut by
     * its size, dropped or passed on fell; the rest say nothing yet. A source whose employers keep falling goes doubtful and is not read first.
     */
    static void scoreSources(LibraryStore store, Career.Seeker s, List<ObjectNode> list, Cut cut) throws IOException {
        Map<String, int[]> passing = view(store, s).perCompany();
        Map<String, int[]> bySource = new LinkedHashMap<>();
        for (ObjectNode c : list) {
            if (!c.path("from").asText("").equals("sources")) continue;
            Matcher m = Pattern.compile("\\((S-\\d{4}),").matcher(c.path("why").asText(""));
            if (!m.find()) continue;
            int[] tally = bySource.computeIfAbsent(m.group(1), k -> new int[2]);
            int[] pc = passing.get(c.path("name").asText(""));
            String st = c.path("state").asText("");
            CareerSize.People p = c.has("people") ? new CareerSize.People(c.path("people").path("low").asInt(), c.path("people").path("high").asInt(), "", "") : null;
            boolean cutBySize = p != null && cut.maxPeople() > 0 && p.low() > 2L * cut.maxPeople();
            boolean nothing = !c.has("feed") && c.path("site").asText("").isEmpty() && !c.has("people") && !c.has("card") && c.has("read_at");   // read, and no board, site, size or card was found for the name: it may not be an employer at all
            if ((pc != null && pc[1] > 0) || st.equals("kept")) tally[0]++;
            else if (cutBySize || st.equals("dropped") || st.equals("out") || nothing) tally[1]++;
        }
        for (Map.Entry<String, int[]> e : bySource.entrySet()) {
            Sources.Source src = Sources.get(store, e.getKey());
            if (src == null || src.kind().equals("board")) continue;   // a board's yields are seats, judged by the cut; the score is for the lists of employers
            Sources.score(store, e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
    }

    /** Whether a company is on the list as one of the companies of the place (the reading's, not an opening's). */
    static boolean fromCompanies(ObjectNode c) { return c.path("from").asText("").equals("companies") || c.path("from").asText("").equals("sources") || c.path("why").asText("").startsWith("Among the companies of your place"); }

    static boolean keptOut(String company, List<String> keepOut) {
        for (String k : keepOut) if (CareerSearch.key(company).equals(CareerSearch.key(k)) || CareerCut.has(company, k)) return true;
        return false;
    }

    /** The library's proposals: companies the model knows that fit what the person wants, each with its reason; raw answer kept beside them. */
    /**
     * A few more companies for the person to try, chosen by the model among {@code pool}: the companies the public records list in the place
     * that the search has not put on the list. The model disambiguates among what a record found; it never names a company from its own
     * memory, which gave a physicist in Pune the largest American companies. An empty pool proposes nothing.
     */
    static List<ObjectNode> propose(LibraryStore store, Career.Seeker s, int n, List<ObjectNode> listed, List<String> keepOut, List<ObjectNode> pool) throws IOException {
        if (pool.isEmpty()) { Career.log(store, s, "The library proposes nothing beyond what the records list: no company of your place is left for the model to choose among."); return List.of(); }
        StringBuilder choices = new StringBuilder();
        Map<String, String> poolKeys = new HashMap<>();
        for (ObjectNode e : pool.subList(0, Math.min(pool.size(), 60))) {
            String name = e.path("name").asText();
            poolKeys.put(CareerSearch.key(name), name);
            choices.append("- ").append(name).append(e.path("city").asText("").isEmpty() ? "" : ", " + e.path("city").asText()).append(e.path("people").asInt(0) > 0 ? ", " + e.path("people").asInt() + " people" : "")
                   .append(e.path("stage").asText("").isEmpty() ? "" : ", " + e.path("stage").asText()).append(e.path("about").asText("").isEmpty() ? "" : ": " + CareerSources.clip(e.path("about").asText(), 160)).append('\n');
        }
        StringBuilder wants = new StringBuilder();
        for (JsonNode d : Career.wantsKept(store, s).path("dimensions")) {
            String st = d.path("state").asText("open");
            wants.append("- ").append(d.path("dimension").asText()).append(": ").append(st.equals("decided") ? "decided, " + d.path("want").asText("") : st.equals("leaning") ? "a guess, " + d.path("want").asText("") : "open").append('\n');
        }
        StringBuilder roles = new StringBuilder();
        for (JsonNode e : Career.evidence(store, s)) if (e.path("kind").asText().equals("role")) roles.append("- ").append(CareerSources.clip(e.path("text").asText(""), 300)).append('\n');
        List<String> on = new ArrayList<>(), dropped = new ArrayList<>();
        for (ObjectNode c : listed) (c.path("state").asText("").equals("dropped") ? dropped : on).add(c.path("name").asText());
        String reading = CareerSources.clip(Career.readingKept(store, s), 12_000);
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "A person is looking for work. Propose up to " + n + " companies, at most, for the library to look at for openings, chosen ONLY among THE COMPANIES OF THEIR PLACE below, "
                + "which the public records list and the search has not put on the list. Today is " + LocalDate.now() + ".\n\n"
                + "Choose the ones that fit everything they DECIDED, as the records describe them: their size, their stage, the kind of work and "
                + "the role. When they decided a small size, choose small companies, and fewer of them: a short list that fits is the aim, and an "
                + "empty one is a fair answer. Use their GUESSES to choose among the companies that fit, and add one or two that answer a guess in a "
                + "different way, for them to try. Write each name exactly as it stands below. Leave out the companies already on the "
                + "list, the ones they keep out or dropped, and the one they work for now. The library checks each one's hiring pages.\n\n"
                + "For each, one plain sentence on why it fits what they decided. Answer with a JSON array and nothing else: "
                + "[{\"company\": \"…\", \"why\": \"…\"}]\n\n"
                + "THE COMPANIES OF THEIR PLACE\n" + choices
                + "\nWHAT THEY WANT\n" + wants
                + "\nWHAT THEY KEEP OUT\n" + (keepOut.isEmpty() ? "(nothing)" : "- " + String.join("\n- ", keepOut))
                + "\n\nALREADY ON THE LIST\n" + (on.isEmpty() ? "(none)" : String.join(", ", on))
                + "\n\nDROPPED\n" + (dropped.isEmpty() ? "(none)" : String.join(", ", dropped))
                + "\n\nTHEIR ROLES\n" + (roles.isEmpty() ? "(none given)\n" : roles)
                + "\nTHE LIBRARY'S READING OF THEIR WORKING LIFE\n" + (reading.isBlank() ? "(none yet)" : reading));
        String said = Career.DRIVE.get().classify(msgs, Math.min(6000, 120 * n + 400));
        Files.createDirectories(dir(store, s));
        Files.writeString(dir(store, s).resolve("proposals.txt"), "# the model's proposals, " + LocalDate.now() + "\n" + (said == null ? "" : said), StandardCharsets.UTF_8);
        List<ObjectNode> out = new ArrayList<>();
        Set<String> whys = new HashSet<>();
        for (JsonNode p : Career.parseArray(said)) {
            String said1 = p.path("company").asText(p.path("name").asText("")).strip(), why = p.path("why").asText("").strip();
            String name = poolKeys.get(CareerSearch.key(said1));   // a name not among the companies of the place is the model's own and is not taken
            if (name == null || name.isEmpty()) continue;
            if (!why.isEmpty() && !whys.add(CareerSearch.key(why))) continue;   // the same sentence for a second company is a list copied, not a reason: left out
            out.add(M.createObjectNode().put("name", name).put("by", "library").put("from", "chosen").put("why", why).put("state", "").put("first_listed", LocalDate.now().toString()));
        }
        return out;
    }

    // ---------------------------------------------------------------- what passes, and the readings

    /** One opening as the page and the chat show it: the opening, the cut's verdict, its rank, its reading and the person's reaction. */
    public record Row(Opening opening, ObjectNode kept, Verdict verdict, int rank, JsonNode reading, JsonNode reaction) {
        public boolean read() { return reading != null && !reading.isMissingNode() && !reading.isNull(); }
        public String reactionWord() { return reaction == null || reaction.isMissingNode() ? "" : reaction.path("reaction").asText(""); }
    }

    /** Everything a page needs: the cut, the companies, the passing openings in order with what was read, the counts, the last search's day. */
    public record View(Cut cut, List<ObjectNode> companies, List<Row> passing, int openings, int gone, Map<String, int[]> perCompany, String searched, List<Row> aside) {
        public View(Cut cut, List<ObjectNode> companies, List<Row> passing, int openings, int gone, Map<String, int[]> perCompany, String searched) { this(cut, companies, passing, openings, gone, perCompany, searched, List.of()); }
    }

    public static View view(LibraryStore store, Career.Seeker s) throws IOException { return view(store, s, null, null); }

    /** As above, by the cut and the keep-outs given, a run's own, instead of the standing ones; null for the standing ones. */
    public static View view(LibraryStore store, Career.Seeker s, Cut given, List<String> keepOutGiven) throws IOException {
        Cut cut = given != null ? given : CareerCut.kept(store, s);
        if (cut == null) cut = Cut.NONE;
        List<ObjectNode> cs = companies(store, s);
        Set<String> named = new HashSet<>(), kept = new HashSet<>(), field = new HashSet<>();
        for (ObjectNode c : cs) {
            if (c.path("by").asText().equals("you")) named.add(CareerSearch.key(c.path("name").asText()));
            if (c.path("state").asText().equals("kept")) kept.add(CareerSearch.key(c.path("name").asText()));
            if (c.path("from").asText("").equals("research") || c.path("reading").path("approach").asText("").equals("yes")) field.add(CareerSearch.key(c.path("name").asText()));   // in the field the person decided: before the rest
        }
        for (String n : CareerSearch.companies(store, s)) named.add(CareerSearch.key(n));   // the companies they asked the search to look at, listed or not yet
        Set<String> dropped = new HashSet<>();
        Map<String, ObjectNode> byName = new HashMap<>();
        for (ObjectNode c : cs) { if (!lookedAt(c)) dropped.add(CareerSearch.key(c.path("name").asText())); byName.put(CareerSearch.key(c.path("name").asText()), c); }
        List<String> keepOut = withCurrent(keepOutGiven != null ? keepOutGiven : CareerSearch.keepOut(store, s), Career.currentEmployers(store, s), named);   // where they work now: set aside, with the keep-outs' reason
        ObjectNode rd = readings(store, s), rx = reactions(store, s);
        Kept held = kept(store, s);
        Map<String, ObjectNode> all = held.all();
        if (!all.isEmpty() && !held.mentionsBasis().equals(mentionsBasis(keepOut))) {   // the list kept out changed since: worked out again, once
            remention(all, texts(store, s), keepOut);
            saveOpenings(store, s, all, mentionsBasis(keepOut));
        }
        LocalDate today = LocalDate.now();
        List<Opening> passing = new ArrayList<>();
        List<Row> aside = new ArrayList<>();
        Map<String, Verdict> verdicts = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        Map<String, int[]> per = new LinkedHashMap<>();
        int gone = 0;
        for (ObjectNode j : all.values()) {
            if (!j.path("gone").asText("").isEmpty()) { gone++; continue; }
            Opening o = of(j);
            String listed = j.path("listed").asText("");
            if (!listed.isEmpty() && dropped.contains(CareerSearch.key(listed))) continue;
            int[] pc = per.computeIfAbsent(listed.isEmpty() ? "" : listed, k -> new int[2]);
            pc[0]++;
            CareerCut.Company co = companyOf(byName.get(CareerSearch.key(listed.isEmpty() ? o.company() : listed)));
            Verdict v = CareerCut.judge(cut, o, keepOut, mentionsOf(j), co, 0);
            if (!v.passes()) { aside.add(new Row(o, j, v, 0, rd.get(o.id()), rx.get(o.id()))); continue; }   // set aside by what the person decided: shown closed, with the reason
            pc[1]++;
            passing.add(o); verdicts.put(o.id(), v); rank.put(o.id(), CareerCut.rank(cut, o, v, named, kept, today, co, field));
        }
        List<Row> rows = new ArrayList<>();
        for (Opening o : CareerCut.ordered(passing, rank)) rows.add(new Row(o, all.get(o.id()), verdicts.get(o.id()), rank.get(o.id()), rd.get(o.id()), rx.get(o.id())));
        String searched = "";
        for (ObjectNode c : cs) if (c.path("read_at").asText("").compareTo(searched) > 0) searched = c.path("read_at").asText();
        aside.sort(Comparator.comparing((Row r) -> r.opening().company(), String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.opening().title(), String.CASE_INSENSITIVE_ORDER));
        return new View(cut, cs, rows, all.size() - gone, gone, per, searched, aside);
    }

    /**
     * The model reads up to {@code n} of the passing openings nobody has read yet, in the reading order: nearest first, at most three from
     * one company before a further one from any. Each reading is kept as it is written. The number read.
     */
    static int readSome(LibraryStore store, Career.Seeker s, View v, int n, Progress progress, BooleanSupplier stop) throws IOException { return readSome(store, s, v, n, progress, stop, null); }

    /** As above, among {@code only} when it is given: the watch reads the openings that are new tonight. */
    static int readSome(LibraryStore store, Career.Seeker s, View v, int n, Progress progress, BooleanSupplier stop, Set<String> only) throws IOException {
        List<Opening> order = new ArrayList<>();
        List<Opening> passing = new ArrayList<>();
        for (Row r : v.passing()) if (r.reactionWord().isEmpty() && (only == null || only.contains(r.opening().id()))) passing.add(r.opening());
        Set<String> readAlready = new HashSet<>();
        Set<String> carded = new HashSet<>();
        boolean changed = false;
        for (ObjectNode c : v.companies()) { if (CareerCompany.landed(store, c)) changed = true; if (!c.path("card").path("built").asText("").isEmpty()) carded.add(CareerSearch.key(c.path("name").asText())); }
        if (changed) { saveCompanies(store, s, v.companies()); v = view(store, s); }   // a card landed: its headcount now cuts and orders
        for (Row r : v.passing()) if (r.read() && !(carded.contains(CareerSearch.key(r.opening().company())) && !r.reading().path("with_card").asBoolean(false))) readAlready.add(r.opening().id());   // a reading made before the company's card landed is made again with it
        for (Opening o : CareerCut.readingOrder(passing, passing.size(), PER_COMPANY)) if (!readAlready.contains(o.id())) order.add(o);
        if (order.size() > n) order = order.subList(0, n);
        if (order.isEmpty()) return 0;
        String wants = wantsText(store, s);
        List<String> keepOut = CareerSearch.keepOut(store, s);
        String examples = examples(v);   // what they liked and passed on before, in their words: the judge reads the next opening with those in view
        Map<String, String> why = new HashMap<>(), about = new HashMap<>();
        for (ObjectNode c : v.companies()) {
            why.put(CareerSearch.key(c.path("name").asText()), c.path("why").asText(""));
            List<String> facts = new ArrayList<>();
            String card = CareerCompany.about(store, c.path("name").asText());
            if (!card.isBlank()) facts.add("from its dossier on the shelves:\n" + card);
            if (c.has("people")) facts.add(new CareerSize.People(c.path("people").path("low").asInt(), c.path("people").path("high").asInt(), c.path("people").path("source").asText(""), c.path("people").path("at").asText("")).words()
                    + " (by " + c.path("people").path("source").asText("") + ", read " + c.path("people").path("at").asText("") + ")");
            if (!c.path("stage").asText("").isEmpty()) facts.add("stage: " + c.path("stage").asText() + " (by " + c.path("stage_by").asText("the library") + ")");
            if (c.has("read_at")) facts.add(c.path("openings").asInt(0) + (c.path("openings").asInt(0) == 1 ? " opening" : " openings") + " on its own board on " + c.path("read_at").asText());
            if (!facts.isEmpty()) about.put(CareerSearch.key(c.path("name").asText()), String.join("; ", facts));
        }
        Map<String, String> texts = texts(store, s);
        int i = 0, read = 0;
        for (Opening o : order) {
            if (stop.getAsBoolean()) break;
            progress.at("Reading the openings nearest to what you want, " + i + " of " + order.size() + " read");
            i++;
            String t = texts.getOrDefault(o.id(), "");
            if (t.isBlank()) { t = CareerSources.detail(o); if (!t.isBlank()) texts.put(o.id(), t); }
            Opening full = o.withText(t);
            ObjectNode r = reading(full, wants, keepOut, why.getOrDefault(CareerSearch.key(o.company()), ""), about.getOrDefault(CareerSearch.key(o.company()), ""), examples);
            r.put("with_card", carded.contains(CareerSearch.key(o.company())));
            ObjectNode rd = readings(store, s);
            rd.set(o.id(), r);
            write(dir(store, s).resolve("readings.json"), rd);
            if (!r.path("failed").asBoolean(false)) read++;
        }
        saveTexts(store, s, texts);
        progress.at("Reading the openings nearest to what you want, " + i + " of " + order.size() + " read");
        return read;
    }

    /** What the person wants, as the readings are given it: each topic decided, a guess, or open. */
    static String wantsText(LibraryStore store, Career.Seeker s) throws IOException {
        StringBuilder b = new StringBuilder();
        for (JsonNode d : Career.wantsKept(store, s).path("dimensions")) {
            String st = d.path("state").asText("open");
            b.append("- ").append(d.path("dimension").asText()).append(": ").append(st.equals("decided") ? "decided: " + d.path("want").asText("") : st.equals("leaning") ? "a guess: " + d.path("want").asText("") : "open").append('\n');
        }
        return b.toString();
    }

    /** The person's reactions so far as examples for the judge: the openings they liked first, then the ones they passed on, each with their why; "" when none. */
    static String examples(View v) {
        List<String> liked = new ArrayList<>(), passed = new ArrayList<>();
        for (Row r : v.passing()) {
            String w = r.reactionWord(); if (w.isEmpty()) continue;
            String line = "- " + r.opening().title() + " at " + r.opening().company() + (r.reaction().path("why").asText("").isBlank() ? "" : " — " + r.reaction().path("why").asText(""));
            (w.equals("like") ? liked : passed).add(line);
        }
        if (liked.isEmpty() && passed.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        if (!liked.isEmpty()) b.append("Openings they liked, with why:\n").append(String.join("\n", liked.subList(0, Math.min(6, liked.size())))).append('\n');
        if (!passed.isEmpty()) b.append("Openings they passed on, with why:\n").append(String.join("\n", passed.subList(0, Math.min(6, passed.size())))).append('\n');
        return b.toString().strip();
    }

    static ObjectNode reading(Opening o, String wants, List<String> keepOut, String whyListed, String aboutCompany) { return reading(o, wants, keepOut, whyListed, aboutCompany, ""); }

    /** One opening read against what the person wants: what fits, what goes against it, what the opening does not say; their reactions so far as examples. */
    static ObjectNode reading(Opening o, String wants, List<String> keepOut, String whyListed, String aboutCompany, String examples) {
        ArrayNode msgs = M.createArrayNode();
        String opening = "Title: " + o.title() + "\nCompany: " + o.company() + "\nPlace: " + (o.location().isBlank() ? "not given" : o.location()) + (o.remote() ? " (remote)" : "")
                + "\nPosted: " + (o.posted().isBlank() ? (o.postedNote().isBlank() ? "not given" : o.postedNote()) : o.posted()) + (o.pay().isBlank() ? "" : "\nPay: " + o.pay())
                + "\n\n" + (o.text().isBlank() ? "(the opening's own words could not be read; only its title and place are known)" : o.text());
        msgs.addObject().put("role", "user").put("content",
                "Today is " + LocalDate.now() + ". A person is looking for work. Below is what they want, topic by topic, each decided (their own word), "
                + "a guess (the library's, from their working life, not yet confirmed) or open; what they keep out; and one job opening. Write a short "
                + "reading of the opening for them, in three lines and nothing else:\n"
                + "Fits: what in the opening answers what they want, quoting its words where they settle a point.\n"
                + "Against: what in it goes against what they want or keep out.\n"
                + "Unknown: what they decided or guessed that the opening does not say.\n"
                + "Each line one or two plain sentences; a line with nothing to say says nothing. Treat a guess as a guess, as in \"if the guess is right\". "
                + "Read sizes, stages and pay only from the opening's own words and from what the library knows of the company, saying which.\n\n"
                + "WHAT THEY WANT\n" + wants
                + "\nWHAT THEY KEEP OUT\n" + (keepOut.isEmpty() ? "(nothing)" : "- " + String.join("\n- ", keepOut))
                + (whyListed.isBlank() ? "" : "\n\nWHY THE COMPANY IS ON THEIR LIST\n" + whyListed)
                + (aboutCompany.isBlank() ? "" : "\n\nWHAT THE LIBRARY KNOWS OF THE COMPANY, apart from the opening\n" + aboutCompany)
                + (examples == null || examples.isBlank() ? "" : "\n\nWHAT THEY LIKED AND PASSED ON BEFORE, in their own words: read the opening with these in view, the liked ones showing what fits them\n" + examples)
                + "\n\n" + Fence.rule("the opening, as the company wrote it") + "\n" + Fence.wrap("OPENING", opening));
        ObjectNode r = M.createObjectNode().put("at", LocalDate.now().toString()).put("text_id", CareerSources.id(o.text()));
        String said;
        try { said = Career.DRIVE.get().prose(msgs, 700); } catch (RuntimeException e) { said = null; }
        if (said == null || said.isBlank()) return r.put("failed", true).put("raw", "");
        r.put("raw", said.strip());
        for (String line : said.strip().split("\n")) {
            String l = line.strip().replaceFirst("^[-*•]\\s*", "").replaceAll("^\\*\\*([A-Za-z]+):?\\*\\*:?", "$1:");
            for (String part : List.of("Fits", "Against", "Unknown"))
                if (l.regionMatches(true, 0, part + ":", 0, part.length() + 1)) r.put(part.toLowerCase(Locale.ROOT), l.substring(part.length() + 1).strip());
        }
        if (!r.has("fits") && !r.has("against") && !r.has("unknown")) r.put("unshaped", true);
        return r;
    }

    /** A record of the search as it ended: the cut, the companies and what each gave, the openings that passed and the ones read. */
    static void snapshot(LibraryStore store, Career.Seeker s, View v, Cut cut) throws IOException { snapshot(store, s, v, cut, ""); }

    /** As above; {@code vary} is the change for this search only, kept with the record beside the run's own cut. */
    static void snapshot(LibraryStore store, Career.Seeker s, View v, Cut cut, String vary) throws IOException {
        ObjectNode o = M.createObjectNode().put("at", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()).put("cut", CareerCut.summary(cut));
        if (vary != null && !vary.isBlank()) o.put("vary", vary);
        o.set("cut_json", CareerCut.json(cut));
        ArrayNode cs = o.putArray("companies");
        for (ObjectNode c : v.companies()) cs.addObject().put("name", c.path("name").asText()).put("by", c.path("by").asText()).put("from", c.path("from").asText("")).put("openings", c.path("openings").asInt(0))
                .put("system", c.path("feed").path("system").asText("")).put("note", c.path("note").asText("")).put("looked", lookedAt(c)).put("state", c.path("state").asText(""));
        ArrayNode ps = o.putArray("passing"), rs = o.putArray("read");
        for (Row r : v.passing()) { ps.add(r.opening().id()); if (r.read()) rs.add(r.opening().id()); }
        write(dir(store, s).resolve("runs").resolve(o.path("at").asText().replace(":", "") + ".json"), o);
    }

    static String summary(View v, int looked, int hn, int usa, int read) {
        int readable = 0;
        for (ObjectNode c : v.companies()) if (lookedAt(c) && c.has("feed")) readable++;
        Set<String> at = new HashSet<>();
        for (Row r : v.passing()) at.add(CareerSearch.key(r.opening().company()));
        int research = 0;
        for (ObjectNode c : v.companies()) if (lookedAt(c) && c.path("from").asText("").equals("research")) research++;
        StringBuilder b = new StringBuilder("The search looked at " + looked + (looked == 1 ? " employer" : " employers") + (research > 0 ? ", " + research + " of them in your field by the library's research," : "") + " and could read the openings of " + (readable == 0 && looked > 0 ? "none of them" : readable + " of them"));
        if (hn > 0) b.append(", and ").append(hn).append(" posts in Hacker News's Who is hiring");
        if (usa > 0) b.append(", and ").append(usa).append(" federal openings on USAJobs");
        b.append(". ").append(v.passing().size()).append(v.passing().size() == 1 ? " opening fits" : " openings fit").append(" what you decided, at ").append(at.size()).append(at.size() == 1 ? " employer" : " employers").append('.');
        if (readable == 0 && looked > 0) b.append(" Their hiring pages are built in the browser or kept by a hiring system the library does not read yet; the list names each employer for you to open, and the address of a careers page you give is tried next time.");
        if (read > 0) b.append(" The library read ").append(read).append(" of them nearest to what you want.");
        return b.toString();
    }

    // ---------------------------------------------------------------- the person's reactions

    /** The person likes or passes on an opening, with why in their own words; kept, logged, and an empty word takes the reaction back. */
    public static String react(LibraryStore store, Career.Seeker s, String id, String reaction, String why) throws IOException {
        Map<String, ObjectNode> all = openings(store, s);
        ObjectNode o = find(all, id);
        if (o == null) throw new IllegalArgumentException("There is no opening " + id + ". The openings list gives each one's short name in brackets.");
        String r = reaction == null ? "" : reaction.strip().toLowerCase(Locale.ROOT);
        if (!List.of("like", "pass", "").contains(r)) throw new IllegalArgumentException("A reaction is like or pass.");
        ObjectNode rx = reactions(store, s);
        String name = o.path("title").asText() + " at " + o.path("company").asText();
        if (r.isEmpty()) { rx.remove(o.path("id").asText()); write(dir(store, s).resolve("reactions.json"), rx); return "Taken back: your reaction to " + name + "."; }
        rx.set(o.path("id").asText(), M.createObjectNode().put("reaction", r).put("why", why == null ? "" : why.strip()).put("at", LocalDate.now().toString())
                .put("title", o.path("title").asText()).put("company", o.path("company").asText()));
        write(dir(store, s).resolve("reactions.json"), rx);
        Career.log(store, s, (r.equals("like") ? "You liked " : "You passed on ") + name + (why == null || why.isBlank() ? "." : ": " + why.strip()));
        return (r.equals("like") ? "Liked: " : "Passed on: ") + name + ".";
    }

    /** An opening by its short name, or by the first letters of it when they name only one. */
    static ObjectNode find(Map<String, ObjectNode> all, String id) {
        String k = id == null ? "" : id.strip().replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        if (k.length() < 4) return null;
        if (all.containsKey(k)) return all.get(k);
        ObjectNode one = null;
        for (Map.Entry<String, ObjectNode> e : all.entrySet()) if (e.getKey().startsWith(k)) { if (one != null) return null; one = e.getValue(); }
        return one;
    }

    /**
     * A company's state on the list: kept (looked at in every search, and watched), dropped (never looked at or proposed again; its
     * openings leave the lists), or back to neither. One not on the list yet is put there, by the person.
     */
    public static String companyState(LibraryStore store, Career.Seeker s, String name, String state) throws IOException {
        String n = name == null ? "" : name.strip(), st = state == null ? "" : state.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) throw new IllegalArgumentException("Say which company.");
        if (!List.of("kept", "dropped", "").contains(st)) throw new IllegalArgumentException("A company is kept, dropped, or neither.");
        List<ObjectNode> cs = companies(store, s);
        ObjectNode c = null;
        for (ObjectNode x : cs) if (CareerSearch.key(x.path("name").asText()).equals(CareerSearch.key(n))) c = x;
        if (c == null) { c = M.createObjectNode().put("name", n).put("by", "you").put("why", "You kept it from the openings.").put("first_listed", LocalDate.now().toString()); cs.add(c); }
        c.put("state", st);
        saveCompanies(store, s, cs);
        String shown = c.path("name").asText();
        Career.log(store, s, switch (st) { case "kept" -> "You kept " + shown + " on the list."; case "dropped" -> "You dropped " + shown + " from the list."; default -> shown + " is back on the list, neither kept nor dropped."; });
        return switch (st) {
            case "kept" -> shown + " is kept: every search looks at it, and the daily watch reads its openings.";
            case "dropped" -> shown + " is dropped: no search looks at it or proposes it again, and its openings leave the lists. To take it back, keep it, or undo on the openings page.";
            default -> shown + " is back on the list.";
        };
    }

    /** A company kept out from the openings list: it joins what the search keeps out, and leaves the list. */
    public static String keepOut(LibraryStore store, Career.Seeker s, String name) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) throw new IllegalArgumentException("Say which company.");
        CareerSearch.addKeepOut(store, s, n, "the openings list");
        List<ObjectNode> cs = companies(store, s);
        for (ObjectNode c : cs) if (CareerSearch.key(c.path("name").asText()).equals(CareerSearch.key(n))) c.put("state", "out");
        saveCompanies(store, s, cs);
        return n + " is kept out: no search shows it again. To take that back, take it off what the search keeps out on the Career page.";
    }

    // ---------------------------------------------------------------- the search as a run

    /** Files the search as one of the library's runs; the runs page shows how far it has come. A sentence for the person. */
    public static String file(LibraryStore store, Career.Seeker s, String patronDid, String kind) throws IOException { return file(store, s, patronDid, kind, ""); }

    /** As above; {@code vary} is a change for this search only, in the person's words, kept with the job and read when the run starts. */
    public static String file(LibraryStore store, Career.Seeker s, String patronDid, String kind, String vary) throws IOException {
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        for (ObjectNode j : jobs.active()) if (j.path("kind").asText().startsWith("career-") && j.path("args").path("seeker").asText().equals(s.slug()))
            return "A search for " + s.name() + " is already " + (j.path("state").asText().equals("running") ? "running" : "waiting to start") + " (" + j.path("job_id").asText() + "); the runs page shows how far it has come.";
        ObjectNode args = M.createObjectNode().put("seeker", s.slug());
        if (vary != null && !vary.isBlank()) args.put("vary", vary.strip());
        String id = jobs.submit(kind, patronDid, args);
        String what = kind.equals("career-read") ? "The library will read " + readingsMax() + " more of the openings that pass, nearest first, about a minute each."
                : "The search looks at up to " + companiesMax() + " employers, those you named first, reads their openings, keeps the ones that fit what you decided, and has the model read the "
                + readingsMax() + " nearest to what you want. On this library's model that takes about half an hour to three quarters of an hour.";
        return what + (vary != null && !vary.isBlank() ? " For this search only: " + vary.strip() + "; what you decided stands as it is." : "") + " It is filed as " + id + "; the runs page shows how far it has come, and the Career page's openings list fills as it goes."
                + (Share.serviceRunning.getAsBoolean() ? "" : " The library's service is not running, so it starts when the service does: researchzosho service start.");
    }

    /** A search's job, by the library's runs: the search itself, or more readings. */
    public static String runJob(LibraryStore store, ObjectNode job, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException {
        Career.Seeker s = Career.seeker(store, job.path("args").path("seeker").asText(""));
        if (s == null) throw new IllegalArgumentException("The person this search was for is no longer in the library.");
        Progress p = sentence -> progress.accept(M.createObjectNode().put("phase", sentence).put("at", Instant.now().toString()));
        return job.path("kind").asText().equals("career-read") ? more(store, s, p, stop) : run(store, s, p, stop, job.path("args").path("vary").asText(""));
    }

    /** The model reads more of the openings that pass, nearest first; a sentence on how many. */
    public static String more(LibraryStore store, Career.Seeker s, Progress p, BooleanSupplier stop) throws IOException {
        int n = readSome(store, s, view(store, s), readingsMax(), p, stop);
        String said = n == 0 ? "There were no more openings to read: every one that passes has been read or has your reaction." : "The library read " + n + " more of the openings that pass.";
        Career.log(store, s, said);
        return said;
    }

    // ---------------------------------------------------------------- the openings in words, for the chat and the command line

    public static String text(LibraryStore store, Career.Seeker s, String how, int show) throws IOException {
        View v = view(store, s);
        if (v.companies().isEmpty() && v.openings() == 0) return "There has been no search for " + s.name() + " yet. Type " + how + " search to start one.";
        StringBuilder b = new StringBuilder();
        long read = v.passing().stream().filter(Row::read).count();
        Set<String> at = new HashSet<>();
        for (Row r : v.passing()) at.add(CareerSearch.key(r.opening().company()));
        Map<String, ObjectNode> byName = new HashMap<>();
        for (ObjectNode c : v.companies()) byName.put(CareerSearch.key(c.path("name").asText()), c);
        b.append("Openings for ").append(s.name()).append(v.searched().isEmpty() ? "" : ", searched " + v.searched()).append(": ").append(v.passing().size())
                .append(v.passing().size() == 1 ? " passes" : " pass").append(" what you want, at ").append(at.size()).append(at.size() == 1 ? " company" : " companies")
                .append("; the library read ").append(read).append(".\nWhat counts as a fit: ").append(CareerCut.summary(v.cut())).append(".\n");
        if (CareerCut.stale(store, s)) b.append("What you want has changed since the cut was made; type ").append(how).append(" cut again to make it again from what you want, with no new search.\n");
        int n = 0;
        for (Row r : v.passing()) {
            if (!r.read() || !r.reactionWord().isEmpty()) continue;
            if (n++ >= show) break;
            b.append('\n').append(line(r)).append('\n');
            for (String part : List.of("fits", "against", "unknown")) if (r.reading().has(part)) b.append("   ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(": ").append(r.reading().path(part).asText()).append('\n');
            if (r.reading().path("unshaped").asBoolean(false)) b.append("   ").append(r.reading().path("raw").asText().replace("\n", "\n   ")).append('\n');
            String listed = r.kept() == null ? "" : r.kept().path("listed").asText("");
            ObjectNode co = byName.get(CareerSearch.key(listed.isEmpty() ? r.opening().company() : listed));
            String needs = co == null ? "" : CareerCompany.needs(co);
            if (!needs.isEmpty()) b.append("   ").append(needs).append(needs.startsWith("The ") || needs.startsWith("Even") ? "." : needs.startsWith("No card") ? ": " + how + " card " + co.path("name").asText() + ", or " + how + " dossier " + co.path("name").asText() + " for the full run." : ": " + how + " dossier " + co.path("name").asText() + ".").append('\n');
        }
        long more = v.passing().stream().filter(r -> r.read() && r.reactionWord().isEmpty()).count() - Math.min(n, show);
        if (more > 0) b.append("\n…and ").append(more).append(" more read, on the Career page's openings list.\n");
        b.append("\nTo react, type ").append(how).append(" like or ").append(how).append(" pass, then the short name in brackets and why. ").append(how)
                .append(" keep or ").append(how).append(" drop and a company's name keeps it or drops it. The Career page lists them all: /career/openings.");
        return b.toString();
    }

    /** One opening on a line: its short name, title, company, place, and when it was posted. */
    static String line(Row r) {
        Opening o = r.opening();
        String place = o.location().isBlank() ? (o.remote() ? "remote" : "place not given") : o.location() + (o.remote() && !CareerSources.remote(o.location()) ? ", remote" : "");
        String when = !o.posted().isBlank() ? "posted " + o.posted() : !o.postedNote().isBlank() ? "posted " + o.postedNote() : "no posting date";
        return "[" + o.id() + "] " + o.title() + ", " + o.company() + " (" + place + "; " + when + ")";
    }
}
