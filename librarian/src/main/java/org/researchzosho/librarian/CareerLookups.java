package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.WebSearchTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * What the career search knows of each employer, for the whole library, and what it spends to learn it (0.8.4). A search reads every
 * employer on a person's list. For one with no board yet it searches the web for the employer's careers page by its name, and for
 * one it reads it looks up its website and its size, which the cut needs when the person decided a size. Those look-ups were most of
 * what a career search spent: 752 of the 856 paid searches of twelve searches measured on 2026-11-26, 33 to 114 a search, for facts
 * that hold from one week to the next. So:
 *
 * <ul>
 * <li>the same words in one search are asked once;</li>
 * <li>what was learnt of an employer is kept in {@code career/employers.json}, each fact with the day it was learnt: its board, or that
 *     a search found none the library can read, for {@link #BOARD_DAYS} days; its website and its size, or that none was found, for
 *     {@link #SITE_DAYS} and {@link #SIZE_DAYS}; a kept board that does not answer is looked up again at once; what the person set
 *     stays until they change it;</li>
 * <li>a look-up asks the free engine first ({@link WebSearchTool#lookup}), a paid place only for what the free one leaves empty;</li>
 * <li>one search makes at most {@link #PAID_MOST} paid look-ups; an employer it could not look up then is looked up by the next.</li>
 * </ul>
 */
public final class CareerLookups {
    private CareerLookups() { }

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * How long a kept board serves, and that a search found none: a month. A board moves when an employer changes hiring systems, and a
     * careers page is opened or closed; a month is the watch's horizon, and a kept board that no longer answers is looked up at once.
     */
    static final int BOARD_DAYS = 30;
    /** How long a kept website serves, and that none was confirmed: three months. A company's website seldom changes. */
    static final int SITE_DAYS = 90;
    /** How long a kept size serves, and that none was found: three months. The cut sets a company aside only at twice or half the size decided, so a size a quarter old cuts the same. */
    static final int SIZE_DAYS = 90;
    /**
     * The most paid look-ups one search makes. With the free engine running a look-up costs nothing paid (it answered every one of 623
     * look-ups measured); without it, twenty cover the employers a list holds first (those the person named, the research's, the
     * lists'), and the rest are looked up by the next search, while what this one learnt is kept.
     */
    public static final int PAID_MOST = 20;

    /** The kept facts' file: one for the library, beside the people's folders. */
    static Path file(LibraryStore store) { return Career.dir(store).resolve("employers.json"); }

    private static final Object LOCK = new Object();

    // ---------------------------------------------------------------- one search's look-ups

    /** One search's look-ups: the words asked so far and their answers, the kept boards that did not answer, its own count of paid searches, and what it did. */
    public static final class Run {
        final LibraryStore store;
        final SearchTally.Work paid;
        final Map<String, CompletableFuture<Answer>> asked = new ConcurrentHashMap<>();
        final Set<String> failed = ConcurrentHashMap.newKeySet();
        final AtomicInteger searched = new AtomicInteger(), kept = new AtomicInteger(), waiting = new AtomicInteger();
        volatile ObjectNode cache;
        volatile boolean ended;
        private Run outer;

        private Run(LibraryStore store, SearchTally.Work paid) { this.store = store; this.paid = paid; }

        /** The look-ups' web searches, by the service that answered. */
        public Map<String, Integer> calls() { return paid.calls(); }
        /** The look-ups' searches through a paid place. */
        public int paid() { return paid.paid(); }
        /** The look-ups that went to the web. */
        public int searched() { return searched.get(); }
        /** The look-ups answered by what the library kept, with no search. */
        public int kept() { return kept.get(); }
        /** The employers not looked up, because the search's paid look-ups were made and no free engine answered. */
        public int waiting() { return waiting.get(); }
    }

    private static final InheritableThreadLocal<Run> RUN = new InheritableThreadLocal<>();

    /** The look-ups of a search begun on this thread and the threads it starts, counted inside the search's own count when it keeps one. */
    public static Run begin(LibraryStore store) {
        Run r = new Run(store, SearchTally.within("this career search's look-ups of employers", PAID_MOST));
        r.outer = current();
        RUN.set(r);
        return r;
    }

    /** The look-ups are over; this thread goes back to the look-ups it was part of, when it was part of some. */
    public static void end(Run r) {
        if (r == null) return;
        r.ended = true;
        if (RUN.get() != r) return;
        Run back = r.outer;
        while (back != null && back.ended) back = back.outer;
        if (back == null) RUN.remove(); else RUN.set(back);
    }

    static Run current() { Run r = RUN.get(); return r == null || r.ended ? null : r; }

    /**
     * A look-up's web search: asked once in a search however many readers ask it, the free engine first. Within a search the paid
     * places answer at most {@link #PAID_MOST} look-ups; past them a look-up gives what the free engine gave, or nothing.
     */
    static List<Hit> search(String words) {
        Run r = current();
        if (r == null) { List<Hit> hits = orNone(WebSearchTool.lookup(() -> CareerSources.searched(words))); noted(new Answer(hits, false)); return hits; }
        String k = words.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        CompletableFuture<Answer> mine = new CompletableFuture<>(), had = r.asked.putIfAbsent(k, mine);
        if (had != null) { Answer a = had.join(); noted(a); return a.hits(); }
        Answer a = new Answer(List.of(), false);   // a search that throws (a stop) answered nothing
        try {
            List<Hit> hits = orNone(SearchTally.during(r.paid, () -> WebSearchTool.lookup(() -> CareerSources.searched(words))));
            r.searched.incrementAndGet();
            boolean starved = hits.isEmpty() && r.paid.paidMost() >= 0 && r.paid.paid() >= r.paid.paidMost();   // nothing, with the paid look-ups made: not asked, rather than found empty
            if (starved) r.waiting.incrementAndGet();
            a = new Answer(hits, starved);
            return hits;
        } finally { noted(a); mine.complete(a); }
    }

    /** A look-up's answer as the search keeps it: the hits, and whether it went unasked for want of a paid look-up. */
    record Answer(List<Hit> hits, boolean starved) { }

    /** On this thread: how many look-ups came back empty (no engine answered, the search was stopped, or the paid look-ups were used), and how many of those for want of a paid look-up. */
    private static final ThreadLocal<int[]> EMPTY = ThreadLocal.withInitial(() -> new int[2]);

    private static void noted(Answer a) { if (a.hits().isEmpty()) { EMPTY.get()[0]++; if (a.starved()) EMPTY.get()[1]++; } }

    /**
     * A look-up's span on this thread: whether one of its searches came back empty, so that nothing is kept as not found that no search
     * answered, and whether that was for want of a paid look-up. A search that answers gives hits: the free engine gave some to every
     * one of 623 look-ups measured, so an empty answer means no engine answered.
     */
    record Asked(int emptyAt, int starvedAt) {
        boolean unanswered() { return EMPTY.get()[0] > emptyAt; }
        boolean starved() { return EMPTY.get()[1] > starvedAt; }
    }

    static Asked asking() { return new Asked(EMPTY.get()[0], EMPTY.get()[1]); }

    private static List<Hit> orNone(List<Hit> hits) { return hits == null ? List.of() : hits; }

    /**
     * A search's searches in one sentence for the person, as its summary and its log end: how many, through which engine, how many
     * paid against the most a search pays for, and what looking the employers up took.
     */
    public static String line(SearchTally.Work w, Run r) {
        Map<String, Integer> by = w.calls();
        int all = 0;
        for (int n : by.values()) all += n;
        List<String> engines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : by.entrySet()) engines.add(e.getKey() + " " + e.getValue());
        StringBuilder b = new StringBuilder("Web searches this time: ").append(all == 0 ? "none" : all + ", " + w.paid() + " of them paid (" + String.join(", ", engines) + ")");
        b.append("; a career search pays for at most ").append(w.paidMost()).append('.');
        if (r != null) {
            int looked = 0;
            for (int n : r.calls().values()) looked += n;
            if (looked > 0 || r.kept() > 0) {
                b.append(" Looking up the employers took ").append(looked == 0 ? "no search" : looked + (looked == 1 ? " search, " : " searches, ") + r.paid() + " paid (at most " + PAID_MOST + ")");
                if (r.kept() > 0) b.append(looked == 0 ? ": " : ", and ").append("what the library already knew of the employers answered ").append(r.kept()).append(r.kept() == 1 ? " look-up" : " look-ups");
                b.append('.');
            }
            if (r.waiting() > 0) b.append(' ').append(r.waiting()).append(r.waiting() == 1 ? " look-up was" : " look-ups were").append(" not made, since the paid ones were used and no free search engine answered; the next search makes them.");
            if (r.paid() > 0 && by.getOrDefault("SearXNG", 0) == 0) b.append(" With a free search engine of the library's own (researchzosho search start), the look-ups cost nothing.");
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- what is kept

    /** All that is kept, by the employer's key; empty when nothing is. */
    static ObjectNode all(LibraryStore store) {
        synchronized (LOCK) {
            try {
                Path f = file(store);
                if (!Files.isRegularFile(f)) return M.createObjectNode();
                JsonNode j = M.readTree(f.toFile());
                return j instanceof ObjectNode o ? o : M.createObjectNode();
            } catch (IOException e) { return M.createObjectNode(); }
        }
    }

    /** What is kept of an employer, a copy; null when nothing is, or no library is at hand. */
    static ObjectNode of(LibraryStore store, String name) {
        String k = CareerSearch.key(name);
        if (store == null || k.isEmpty()) return null;
        Run r = current();
        ObjectNode all;
        if (r != null && r.store == store) {   // the search's copy: read once, replaced whole by each change, never changed in place
            all = r.cache;
            if (all == null) { ObjectNode loaded = all(store); synchronized (r) { if (r.cache == null) r.cache = loaded; all = r.cache; } }
        } else all = all(store);
        JsonNode e = all.get(k);
        return e instanceof ObjectNode o ? o.deepCopy() : null;
    }

    /** What is kept of an employer in the library of the search on this thread; null when nothing is, or no search is on. */
    static ObjectNode of(String name) { Run r = current(); return r == null ? null : of(r.store, name); }

    /**
     * The name what is kept of an employer is under: its own, or, when nothing is kept under it, the name of the employer kept with the
     * same board, so that two spellings of one employer meet (a Workday site's short name, "Kcura", and the hiring organization its
     * postings name, "Relativity").
     */
    static String nameOf(String name, Feed board) {
        Run r = current();
        if (r == null || board == null || board.kind().isEmpty() || of(r.store, name) != null) return name;
        ObjectNode all = r.cache;
        if (all == null) { of(r.store, name); all = r.cache; }
        if (all == null) return name;
        for (Map.Entry<String, JsonNode> e : all.properties()) {
            JsonNode b = e.getValue().path("board");
            if ((b.path("kind").asText("") + ":" + b.path("token").asText("")).equals(board.label()) && !e.getValue().path("name").asText("").isBlank()) return e.getValue().path("name").asText();
        }
        return name;
    }

    /** An employer's own board, read in this search, kept with what is kept of it when none is, so that its other spelling finds it; never a board found through another organisation, nor a hiring system's own demonstration board. */
    static void boardSeen(String name, Feed f) {
        if (f == null || f.kind().isEmpty() || f.kind().equals("jobposting") || f.kind().equals("links") || (f.how() != null && f.how().contains("found through")) || CareerSystems.ownBoard(f, name)) return;
        ObjectNode e = of(name);
        if (e != null && e.path("board").isObject()) return;
        change(name, x -> { if (!x.path("board").isObject()) x.putObject("board").put("kind", f.kind()).put("token", f.token()).put("how", f.how() == null ? "" : f.how()).put("at", today()); });
    }

    /** Changes what is kept of an employer: read again, changed, written whole, so two searches at once keep each other's facts. */
    static void change(LibraryStore store, String name, Consumer<ObjectNode> how) {
        String k = CareerSearch.key(name);
        if (store == null || k.isEmpty()) return;
        synchronized (LOCK) {
            ObjectNode all = all(store);
            ObjectNode e = all.get(k) instanceof ObjectNode o ? o : all.putObject(k);
            e.put("name", name.strip());
            how.accept(e);
            if (e.size() <= 1) all.remove(k);
            try {
                Path f = file(store), tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.createDirectories(f.getParent());
                Files.writeString(tmp, M.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) { /* what is kept saves searches; a search never fails for it */ }
            Run r = current();
            if (r != null && r.store == store) synchronized (r) { r.cache = all; }
        }
    }

    private static void change(String name, Consumer<ObjectNode> how) { Run r = current(); if (r != null) change(r.store, name, how); }

    static boolean fresh(String day, int days) {
        try { return !day.isEmpty() && !LocalDate.parse(day).isBefore(LocalDate.now().minusDays(days)); } catch (Exception e) { return false; }
    }

    static boolean byYou(JsonNode e, String what) { for (JsonNode x : e.path("by_you")) if (x.asText().equals(what)) return true; return false; }

    private static void setByYou(ObjectNode e, String what, boolean on) {
        List<String> now = new ArrayList<>();
        for (JsonNode x : e.path("by_you")) if (!x.asText().equals(what)) now.add(x.asText());
        if (on) now.add(what);
        if (now.isEmpty()) e.remove("by_you"); else { ArrayNode a = e.putArray("by_you"); now.forEach(a::add); }
    }

    static String today() { return LocalDate.now().toString(); }

    // ---------------------------------------------------------------- the board

    /** The board kept for an employer while it serves and has answered in this search; null when none. */
    static Feed board(String name) {
        ObjectNode e = of(name);
        if (e == null || !e.path("board").isObject()) return null;
        JsonNode b = e.path("board");
        if (!byYou(e, "board") && !fresh(b.path("at").asText(""), BOARD_DAYS)) return null;
        Feed f = new Feed(b.path("kind").asText(""), b.path("token").asText(""), b.path("how").asText(""));
        Run r = current();
        if (f.kind().isEmpty() || (r != null && r.failed.contains(f.label()))) return null;
        return f;
    }

    /** The day a search for the employer's careers page found nothing the library can read, while that serves; "" when it does not. */
    static String noBoard(String name) {
        ObjectNode e = of(name);
        if (e == null || e.path("board").isObject()) return "";
        String day = e.path("board_none").asText("");
        return fresh(day, BOARD_DAYS) ? day : "";
    }

    /**
     * What a search found for an employer's name: its board, kept with the day and the words of its kind its careers page carried (a
     * hospital's, a school's), which the portals' check reads again when the kept board is read; or that none was found. A board the
     * person set stays.
     */
    static void boardFound(String name, Found f, String page) {
        if (f == null) return;
        Run r = current();
        if (r != null && f.feed() != null) r.failed.remove(f.feed().label());
        List<String> kinds = new ArrayList<>();
        String t = " " + (page == null ? "" : page).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9']+", " ").replace("'", "") + " ";
        for (List<String> family : CareerPortals.KIND_FAMILIES) for (String w : family) if (t.contains(" " + w.replace("'", "") + " ") && !kinds.contains(w)) kinds.add(w);
        if (f.feed() != null && CareerSystems.ownBoard(f.feed(), name)) return;   // a hiring system's own demonstration board is nobody's
        change(name, e -> {
            if (byYou(e, "board") && e.path("board").isObject()) return;
            if (f.feed() != null) {
                ObjectNode b = e.putObject("board").put("kind", f.feed().kind()).put("token", f.feed().token()).put("how", f.feed().how()).put("at", today());
                if (!kinds.isEmpty()) { ArrayNode a = b.putArray("page_words"); kinds.forEach(a::add); }
                e.remove("board_none");
            } else {
                e.remove("board");
                e.put("board_none", today());
            }
        });
    }

    /** The words of its kind the careers page of a kept board carried, for the portals' check; "" when none were kept. */
    static String pageWords(String name) {
        ObjectNode e = of(name);
        List<String> out = new ArrayList<>();
        if (e != null) for (JsonNode w : e.path("board").path("page_words")) out.add(w.asText());
        return String.join(" ", out);
    }

    /** A board, kept or the person's list's, that did not answer in this search: not read again in it, and the employer is looked up again. */
    static void boardFailed(Feed f) { Run r = current(); if (r != null && f != null) r.failed.add(f.label()); }

    /** One more look-up answered by what was kept, with no search. */
    static void fromKept() { Run r = current(); if (r != null) r.kept.incrementAndGet(); }

    // ---------------------------------------------------------------- the website and the size

    /** The website kept for an employer while it serves: the host; "-" when a look-up confirmed none lately; "" when nothing is kept. */
    static String site(String name) {
        ObjectNode e = of(name);
        if (e == null) return "";
        if (!e.path("site").asText("").isEmpty() && (byYou(e, "site") || fresh(e.path("site_at").asText(""), SITE_DAYS))) return e.path("site").asText();
        return fresh(e.path("site_none").asText(""), SITE_DAYS) ? "-" : "";
    }

    static void siteFound(String name, String site) {
        change(name, e -> {
            if (byYou(e, "site")) return;
            if (site == null || site.isBlank()) { e.remove("site"); e.remove("site_at"); e.put("site_none", today()); }
            else { e.put("site", site).put("site_at", today()); e.remove("site_none"); }
        });
    }

    /** The size kept for an employer while it serves; null when none is kept. {@link #noPeople} says whether a look-up found none lately. */
    static CareerSize.People people(String name) {
        ObjectNode e = of(name);
        if (e == null || !e.path("people").isObject()) return null;
        JsonNode p = e.path("people");
        if (!byYou(e, "people") && !fresh(p.path("at").asText(""), SIZE_DAYS)) return null;
        return new CareerSize.People(p.path("low").asInt(), p.path("high").asInt(), p.path("source").asText(""), p.path("at").asText(""));
    }

    static boolean noPeople(String name) { ObjectNode e = of(name); return e != null && !e.path("people").isObject() && fresh(e.path("people_none").asText(""), SIZE_DAYS); }

    static void peopleFound(String name, CareerSize.People p) {
        change(name, e -> {
            if (byYou(e, "people")) return;
            if (p == null) { e.remove("people"); e.put("people_none", today()); }
            else { e.putObject("people").put("low", p.low()).put("high", p.high()).put("source", p.source()).put("at", p.at()); e.remove("people_none"); }
        });
    }

    // ---------------------------------------------------------------- what the person sees and sets

    /** What the library keeps of an employer, in a sentence for the person; "" when it keeps nothing. */
    public static String said(LibraryStore store, String name) {
        ObjectNode e = of(store, name);
        if (e == null) return "";
        List<String> parts = new ArrayList<>();
        JsonNode b = e.path("board");
        if (b.isObject()) parts.add("its board, " + new Feed(b.path("kind").asText(""), b.path("token").asText(""), "").system() + " (" + b.path("kind").asText("") + ":" + b.path("token").asText("") + "), "
                + (byYou(e, "board") ? "as you set it" : "learnt " + b.path("at").asText("") + (fresh(b.path("at").asText(""), BOARD_DAYS) ? "" : ", to be looked up again")));
        else if (!e.path("board_none").asText("").isEmpty()) parts.add("that a search on " + e.path("board_none").asText() + " found no board it can read" + (fresh(e.path("board_none").asText(), BOARD_DAYS) ? ", so it is not searched for again before " + LocalDate.parse(e.path("board_none").asText()).plusDays(BOARD_DAYS + 1) : ""));
        if (!e.path("site").asText("").isEmpty()) parts.add("its website, " + e.path("site").asText() + (byYou(e, "site") ? ", as you set it" : ", learnt " + e.path("site_at").asText("")));
        else if (!e.path("site_none").asText("").isEmpty()) parts.add("that no website of its own was confirmed on " + e.path("site_none").asText());
        JsonNode p = e.path("people");
        if (p.isObject()) parts.add(new CareerSize.People(p.path("low").asInt(), p.path("high").asInt(), "", "").words() + (byYou(e, "people") ? ", as you set it" : " (" + p.path("source").asText("") + ", read " + p.path("at").asText("") + ")"));
        else if (!e.path("people_none").asText("").isEmpty()) parts.add("that no size was found on " + e.path("people_none").asText());
        if (parts.isEmpty()) return "";
        return "The library keeps of " + e.path("name").asText(name) + ": " + Career.and(parts) + ".";
    }

    /**
     * The person's word on what is kept of an employer, from the board command: the address of its board or of its careers page, its
     * website, its size, or that all of it is forgotten. A sentence on what was set; "" when nothing was asked.
     */
    public static String correct(LibraryStore store, String name, String address, String site, int people, boolean forget) {
        List<String> said = new ArrayList<>();
        if (forget) return forget(store, name) ? "The library forgot what it kept of " + name + "; the next search looks it up again." : "The library kept nothing of " + name + ".";
        if (address != null && !address.isBlank()) {
            Feed f = boardAt(address.strip(), name);
            if (f == null) said.add("Nothing the library can read is at " + address.strip() + ": no hiring system's board, and no job list on the page; nothing was changed");
            else { setBoard(store, name, f); said.add("The library reads " + name + "'s openings from " + f.system() + " (" + address.strip() + ") in every search from now on"); }
        }
        if (site != null && !site.isBlank()) {
            String host = CareerSize.site(site.contains("://") ? site.strip() : "https://" + site.strip());
            if (host.isEmpty()) said.add(site.strip() + " is not a website's address; nothing was changed");
            else { setSite(store, name, host); said.add("The library takes " + host + " as " + name + "'s website"); }
        }
        if (people > 0) { setPeople(store, name, people); said.add("The library takes " + name + " as " + new CareerSize.People(people, people, "", "").words()); }
        return said.isEmpty() ? "" : String.join(". ", said) + ".";
    }

    /** The board at an address the person gave: a hiring system's board, else one its page links to, else the job data or the job links on the page; null when none. */
    static Feed boardAt(String address, String name) {
        List<Feed> direct = CareerSources.boardsIn(address, "the address you gave");
        if (!direct.isEmpty()) return direct.get(0);
        String page = CareerSources.get(address);
        if (page == null) return null;
        List<Feed> linked = CareerSources.boardsIn(page, "linked from the page you gave, " + address);
        if (!linked.isEmpty()) return linked.get(0);
        Feed portal = CareerPortals.feedOfPage(address, page);
        if (portal != null) return portal;
        if (!CareerSources.jobPostings(page, address, name).isEmpty()) return new Feed("jobposting", address, "the job data on the page you gave");
        if (CareerSources.jobLinks(page, address, name).size() >= CareerSources.LINKS_MIN) return new Feed("links", address, "the job links on the page you gave");
        return null;
    }

    /** The person's word on an employer's board: kept until they change it, and read in every search. */
    public static void setBoard(LibraryStore store, String name, Feed f) {
        change(store, name, e -> { e.putObject("board").put("kind", f.kind()).put("token", f.token()).put("how", f.how()).put("at", today()); e.remove("board_none"); setByYou(e, "board", true); });
    }

    /** The person's word on an employer's website: the host. */
    public static void setSite(LibraryStore store, String name, String site) {
        change(store, name, e -> { e.put("site", site).put("site_at", today()); e.remove("site_none"); setByYou(e, "site", true); });
    }

    /** The person's word on an employer's size: about this many people. */
    public static void setPeople(LibraryStore store, String name, int people) {
        change(store, name, e -> { e.putObject("people").put("low", people).put("high", people).put("source", "you").put("at", today()); e.remove("people_none"); setByYou(e, "people", true); });
    }

    /** Forgets what is kept of an employer: the next search looks it up again. True when something was kept. */
    public static boolean forget(LibraryStore store, String name) {
        boolean had = of(store, name) != null;
        change(store, name, e -> { for (String f : List.copyOf(iterable(e))) if (!f.equals("name")) e.remove(f); });
        return had;
    }

    private static List<String> iterable(ObjectNode e) { List<String> out = new ArrayList<>(); e.fieldNames().forEachRemaining(out::add); return out; }
}
