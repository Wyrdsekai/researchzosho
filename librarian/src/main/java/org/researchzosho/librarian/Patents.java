package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.records.Ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The candidate pool for prior art. The old pool was the first twenty records of an office's search for a query the model wrote: a live
 * run found the class of animal feeding bowls and read twenty of its 1,272 publications of 2012 to 2022, never asked for the page's
 * other eighty or for a second page though both were there, and gave the rest of its searches to an engine that refused it
 * (2026-11-05). Words in a rule did not make a model page. So the model gives only the classes it found, the years, and the invention's
 * features as the words that would name each in a title or an abstract, and the program does the rest: it ENUMERATES every publication
 * of each class in the years from the European Patent Office's own records, page by page to the end, splitting the years where a query
 * runs past the two thousand records the office gives of one, and says the office's own count; it keeps one line a patent family, the
 * office's family, with the other members on it; it counts which features each family's titles and abstracts name, in any language
 * given, and orders by more features named, then by the rarer feature, then by the older, with no popularity in it anywhere: no count
 * of citations, no size of a family, no rank of an engine. The whole pool is kept under a key; the reading pass then reads the
 * abstracts, and the claims of what survives are read from the office's own full text.
 */
public final class Patents {
    private Patents() { }
    static final ObjectMapper M = new ObjectMapper();

    /** Families in one page of the answer; the setting for the most new pages one enumeration fetches, and its default. */
    public static final int SHOWN = 40;
    public static final String PAGES = "RESEARCHZOSHO_PATENTS_PAGES";
    public static final int PAGES_DEFAULT = 150;
    /** The enumeration's own ceiling, and what a run's deadline must keep for the reading that follows. */
    public static final long ENUMERATE_MS = 20 * 60_000L, KEEP_FOR_READING_MS = 18 * 60_000L;
    /** How many days a page of the office's records is kept before it is fetched again. */
    static final int PAGE_DAYS = 30;
    /** The most features and the most words of one feature that are read; the width of one family's line. */
    static final int FEATURES_MOST = 8, WORDS_MOST = 16, LINE = 118;

    static int pagesPerCall() { return Math.max(1, Config.getInt(PAGES, PAGES_DEFAULT)); }

    /** One feature of the invention: the phrase as given, its name (the first of its words), and the words and synonyms that would name it. */
    public record Feature(String phrase, String name, List<String> words) {
        static Feature of(String given) {
            String g = given == null ? "" : given.strip().replaceAll("\\s+", " ");
            if (g.isEmpty()) return null;
            List<String> words = new ArrayList<>();
            // "folds flat (foldable, collapsible)" and "folds flat: foldable, collapsible" are the name and then its other words
            for (String w : g.split("[,;|、，／/()（）:：]| or ")) { String t = w.strip().replaceAll("^[\"“”'`]+|[\"“”'`.]+$", "").strip(); if (!t.isEmpty() && !words.contains(t) && words.size() < WORDS_MOST) words.add(t); }
            return words.isEmpty() ? null : new Feature(g, words.get(0), words);
        }
    }

    /** What the model gives: the classes, the years, the features, the numbers already rejected, and the page of a pool already made. Nothing else: never a query. */
    public record Ask(List<String> classes, int fromYear, int toYear, List<Feature> features, Set<String> exclude, int page) {
        public static Ask of(JsonNode args) {
            List<String> classes = new ArrayList<>();
            JsonNode c = args.path("classes");
            if (c.isArray()) { for (JsonNode x : c) addClass(classes, x.asText("")); } else for (String x : c.asText("").split("[,;|\\n]")) addClass(classes, x);
            List<Feature> features = new ArrayList<>();
            JsonNode f = args.path("features");
            if (f.isArray()) { for (JsonNode x : f) addFeature(features, x); } else for (String x : f.asText("").split("[;|\\n]")) addFeature(features, M.getNodeFactory().textNode(x));
            Set<String> exclude = new LinkedHashSet<>();
            JsonNode ex = args.path("exclude");
            if (ex.isArray()) { for (JsonNode x : ex) exclude.addAll(numbers(x.asText(""))); } else for (String x : ex.asText("").split("[,;\\n]")) exclude.addAll(numbers(x));
            int to = args.path("to_year").asInt(0), from = args.path("from_year").asInt(0);
            if (to <= 0) to = LocalDate.now().getYear();
            if (from <= 0) from = to - 20;   // the twenty years before the date that matters, when the ask gives none
            if (from > to) { int t = from; from = to; to = t; }
            return new Ask(classes, from, to, features, exclude, Math.max(1, args.path("page").asInt(1)));
        }
        static void addClass(List<String> classes, String given) {
            String s = symbol(given);
            if (!s.isEmpty() && !classes.contains(s) && classes.size() < 6) classes.add(s);
        }
        static void addFeature(List<Feature> features, JsonNode x) {
            String given = x.isObject() ? x.path("name").asText("") + (x.path("words").isArray() ? ", " + String.join(", ", strings(x.path("words"))) : x.has("words") ? ", " + x.path("words").asText("") : "") : x.asText("");
            Feature f = Feature.of(given);
            if (f != null && features.size() < FEATURES_MOST && features.stream().noneMatch(o -> o.phrase().equalsIgnoreCase(f.phrase()))) features.add(f);
        }
        /** The key of the classes and the years: the pool of publications is theirs, whatever the features. */
        String classesKey() { List<String> c = new ArrayList<>(classes); c.sort(Comparator.naturalOrder()); return String.join(",", c) + "|" + fromYear + "-" + toYear; }
        /** The key the kept pool is filed under: the classes, the years and the features, never the page or what is excluded. */
        String key() { return hash(classesKey() + "|" + featuresKey(features)); }
    }

    static List<String> strings(JsonNode a) { List<String> o = new ArrayList<>(); for (JsonNode x : a) o.add(x.asText("")); return o; }

    /** What the features are kept and compared by: their phrases, in order. */
    static String featuresKey(List<Feature> features) { List<String> p = new ArrayList<>(); for (Feature f : features) p.add(f.phrase().toLowerCase(Locale.ROOT)); return String.join("|", p); }

    static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (Exception e) { return "0000000000000000"; }
    }

    private static final Pattern SYMBOL = Pattern.compile("([A-HY])\\s*(\\d{2})\\s*([A-Z])\\s*(\\d{1,4})\\s*/\\s*(\\d{2,6})");

    /** A classification symbol as the office writes it, A01K5/01, from the forms a person writes ("A01K 5/01", "a01k5/01"); "" when it is none, or a subclass with no group. */
    public static String symbol(String given) {
        Matcher m = SYMBOL.matcher(given == null ? "" : given.strip().toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) + m.group(2) + m.group(3) + m.group(4) + "/" + m.group(5) : "";
    }

    /** A publication number as the office writes it, from the forms a person writes it in ("US 2014/0202391 A1", "EP 3 991 548"): every form it may be kept under. */
    public static List<String> numbers(String given) {
        String g = given == null ? "" : given.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!g.matches("[A-Z]{2}[A-Z]{0,2}\\d{4,14}[A-Z]?\\d?")) return List.of();   // a design, a plant or a reissue has letters before its digits (USD655054S)
        List<String> out = new ArrayList<>(List.of(g));
        Matcher us = Pattern.compile("(US)(\\d{4})0(\\d{6})([A-Z]\\d?)?").matcher(g);   // the office drops the zero after the year of a US application
        if (us.matches()) out.add(us.group(1) + us.group(2) + us.group(3) + (us.group(4) == null ? "" : us.group(4)));
        return out;
    }

    /** One patent family in the pool: the office's family, its members oldest first, and which features its titles and abstracts name. */
    public static final class Family {
        public final String id;
        public final List<Ops.Publication> members = new ArrayList<>();
        public boolean[] named = new boolean[0];
        Family(String id) { this.id = id; }
        /** The member the line is written under: the oldest publication. */
        public Ops.Publication first() { return members.get(0); }
        public String title() { for (Ops.Publication p : members) if (!p.titleEn().isEmpty()) return p.titleEn(); for (Ops.Publication p : members) if (!p.titleOther().isEmpty()) return p.titleOther(); return ""; }
        /** The abstract read: in English from the oldest member that has one, else in the language it was filed in. */
        public String summary() { for (Ops.Publication p : members) if (!p.abstractEn().isEmpty()) return p.abstractEn(); for (Ops.Publication p : members) if (!p.abstractOther().isEmpty()) return p.abstractOther(); return ""; }
        public String applicant() { for (Ops.Publication p : members) if (!p.applicants().isEmpty()) return String.join(", ", p.applicants()); return ""; }
        /** The day that matters for prior art: the earliest priority any member claims, else the earliest filing, else the earliest publication. */
        public String earliest() {
            String d = "";
            for (Ops.Publication p : members) for (String x : List.of(p.priority(), p.filed(), p.published())) if (!x.isEmpty() && (d.isEmpty() || x.compareTo(d) < 0)) d = x;
            return d;
        }
        public int namedCount() { int n = 0; for (boolean b : named) if (b) n++; return n; }
        /** Every title and abstract of every member, for the words. */
        String text() { StringBuilder b = new StringBuilder(); for (Ops.Publication p : members) b.append(p.titleEn()).append(' ').append(p.titleOther()).append(' ').append(p.abstractEn()).append(' ').append(p.abstractOther()).append(' '); return b.toString(); }
        ObjectNode json() {
            ObjectNode o = M.createObjectNode().put("family", id).put("number", first().id()).put("link", first().link()).put("title", title()).put("applicant", applicant()).put("earliest", earliest()).put("published", first().published()).put("abstract", summary());
            ArrayNode n = o.putArray("named"); for (boolean b : named) n.add(b);
            ArrayNode m = o.putArray("members"); for (Ops.Publication p : members) m.add(Ops.json(p));
            return o;
        }
        static Family of(JsonNode o) {
            Family f = new Family(o.path("family").asText(""));
            for (JsonNode m : o.path("members")) f.members.add(Ops.publicationOf(m));
            f.named = new boolean[o.path("named").size()];
            for (int i = 0; i < f.named.length; i++) f.named[i] = o.path("named").get(i).asBoolean(false);
            return f.members.isEmpty() ? null : f;
        }
    }

    /** One class as it was enumerated: its title in the office's scheme, the office's count for the years, how many of them were taken, how many its subgroups hold besides, and the subgroups. */
    public record ClassCount(String symbol, String title, int total, int taken, int withSubgroups, List<String[]> under) { }

    /**
     * What came back: the pool best fit first (whole), each class with the office's count, how many publications and families, how many
     * families name how many features and how many name each, what was dropped, the notes, the key the pool is kept under, whether the
     * time or the page ceiling cut the enumeration, what it fetched, and the text for the model.
     */
    public record Result(List<Family> pool, List<ClassCount> classes, List<Feature> features, int fromYear, int toYear, int officeTotal, int publications, int excluded, int[] byNamed, int[] perFeature,
                         List<String> notes, int page, String key, boolean cut, int pagesFetched, long bytes, String text) {
        /** Whether this is the answer of a call that could not be made at all: no key, no class. */
        public boolean refused() { return key.isEmpty(); }
    }

    // ---- the run

    /** One enumeration at a time in a library: a second call waits for the first, since both would fetch the same pages of the same office. */
    private static final ConcurrentHashMap<String, ReentrantLock> ENUMERATING = new ConcurrentHashMap<>();

    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stop) { return run(store, ask, progress, stop, System.currentTimeMillis() + ENUMERATE_MS); }

    /**
     * The pool for an ask: read back from the pool kept today when it was whole, else enumerated until {@code untilMs} (epoch
     * milliseconds; 0 for none) from the pages kept under the library and the office for the rest.
     */
    public static Result run(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stopWhen, long untilMs) {
        if (ask.classes().isEmpty()) return said(ask, "Give the classification symbols the invention sits in (classes), like A47G19/22: a group with its slash, not a subclass alone. " + HOW_TO_FIND_A_CLASS);
        if (store != null) {
            Result kept = kept(store, ask);
            if (kept != null && !kept.cut()) return kept;   // a pool the time cut goes on from the pages kept, whatever page is asked for
        }
        if (!Ops.keyed()) return said(ask, Ops.missing());
        ReentrantLock lock = store == null ? new ReentrantLock() : ENUMERATING.computeIfAbsent(store.root().toAbsolutePath().normalize().toString(), k -> new ReentrantLock());
        try {
            long wait = untilMs > 0 ? Math.max(1_000, untilMs - System.currentTimeMillis()) : ENUMERATE_MS;
            if (!lock.tryLock(wait, TimeUnit.MILLISECONDS)) return said(ask, "Another enumeration of the patent office's records is running in this library and did not end in time; the same call with page=" + (ask.page() + 1) + " reads what it kept.");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return said(ask, "The enumeration was stopped before it began."); }
        try {
            if (store != null) { Result kept = kept(store, ask); if (kept != null && !kept.cut()) return kept; }   // the call that was waited for made it
            return enumerate(store, ask, progress, () -> stopWhen.getAsBoolean() || (untilMs > 0 && System.currentTimeMillis() > untilMs));
        } finally { lock.unlock(); }
    }

    /** How a class is found, in the words every surface says. */
    public static final String HOW_TO_FIND_A_CLASS = "A class is found from the office's own classification: search where=records source=epo-classification with the thing in plain words gives the groups its patents sit in and the subgroups under them, each with its title, and this call, given a group, names the subgroups under it; or it is read off a known patent, whose record lists its classes (patent_claims with its number prints them).";

    private static Result said(Ask ask, String sentence) {
        return new Result(List.of(), List.of(), ask.features(), ask.fromYear(), ask.toYear(), 0, 0, 0, new int[0], new int[0], List.of(sentence), ask.page(), "", false, 0, 0, sentence);
    }

    /** What an enumeration carries along: the publications by number, the counters, and when to stop. */
    private static final class State {
        final LibraryStore store; final BooleanSupplier stop; final Consumer<String> progress; final int cap = pagesPerCall();
        final Map<String, Ops.Publication> publications = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        int pagesNew = 0, pagesKept = 0; long bytes = 0; boolean cut = false; String refused = "";
        State(LibraryStore store, BooleanSupplier stop, Consumer<String> progress) { this.store = store; this.stop = stop; this.progress = progress; }
        void take(Ops.Page p) { for (Ops.Publication x : p.publications()) publications.putIfAbsent(x.id(), x); }
    }

    /** A span of days a class is asked for: years, one month, or one day, each as the office's query writes it, and the parts it splits into. */
    sealed interface Span permits Years, Month, Day { String query(); List<Span> parts(); String said(); }
    record Years(int from, int to) implements Span {
        @Override public String query() { return "pd within \"" + from + " " + to + "\""; }
        @Override public List<Span> parts() {
            List<Span> out = new ArrayList<>();
            if (from < to) { for (int y = from; y <= to; y++) out.add(new Years(y, y)); }
            else for (int m = 1; m <= 12; m++) out.add(new Month(from, m));
            return out;
        }
        @Override public String said() { return from == to ? String.valueOf(from) : from + " to " + to; }
    }
    record Month(int year, int month) implements Span {
        @Override public String query() { return "pd=" + String.format(Locale.ROOT, "%04d%02d", year, month); }
        @Override public List<Span> parts() { List<Span> out = new ArrayList<>(); YearMonth ym = YearMonth.of(year, month); for (int d = 1; d <= ym.lengthOfMonth(); d++) out.add(new Day(ym.atDay(d))); return out; }
        @Override public String said() { return String.format(Locale.ROOT, "%04d-%02d", year, month); }
    }
    record Day(LocalDate day) implements Span {
        @Override public String query() { return "pd=" + day.toString().replace("-", ""); }
        @Override public List<Span> parts() { return List.of(); }
        @Override public String said() { return day.toString(); }
    }

    /** The office's query for a class in a span: the model never writes one. */
    static String query(String symbol, Span span) { return "cpc=" + symbol + " and " + span.query(); }

    private static Result enumerate(LibraryStore store, Ask ask, Consumer<String> progress, BooleanSupplier stop) {
        State st = new State(store, stop, progress);
        List<ClassCount> classes = new ArrayList<>();
        int officeTotal = 0;
        for (String symbol : ask.classes()) {
            int before = st.publications.size();
            int total = -1;
            try { total = window(symbol, new Years(ask.fromYear(), ask.toYear()), st); }
            catch (Ops.Refused e) { st.refused = e.getMessage(); st.cut = true; }
            catch (IOException e) { st.refused = "The European Patent Office did not answer (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")."; st.cut = true; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); st.cut = true; }
            int taken = st.publications.size() - before;
            // what the class is called in the office's scheme, the classes under it, and what they hold besides: asked only of a class taken whole, and never what marks a pool as cut
            boolean whole = !st.cut && total >= 0;
            Ops.Scheme scheme = whole ? scheme(store, symbol) : null;
            int below = -1;
            if (whole) {
                try { Ops.Page p = page(symbol + "/low", new Years(ask.fromYear(), ask.toYear()), 1, 1, st); if (p != null) below = p.total(); }
                catch (IOException e) { below = -1; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                st.cut = false;
            }
            classes.add(new ClassCount(symbol, scheme == null ? "" : scheme.title(), total, taken, below, scheme == null ? List.of() : scheme.under()));
            if (total > 0) officeTotal += total;
            progress.accept(symbol + ": " + (total < 0 ? "not counted" : total + " at the office") + ", " + taken + " taken");
        }
        if (!st.refused.isEmpty()) st.notes.add(st.refused);
        // one line a family, the office's own family; a publication the office gives no family for is a family of its own
        Map<String, Family> families = new LinkedHashMap<>();
        for (Ops.Publication p : st.publications.values()) families.computeIfAbsent(p.family().isEmpty() ? "~" + p.id() : p.family(), Family::new).members.add(p);
        List<Family> all = new ArrayList<>(families.values());
        for (Family f : all) f.members.sort(Comparator.comparing(Ops.Publication::published).thenComparing(Ops.Publication::id));
        order(all, ask.features());
        if (st.cut && st.refused.isEmpty()) st.notes.add(st.pagesNew >= st.cap
                ? "This call fetched its " + st.cap + " new pages (" + PAGES + " raises it), so the pool is what was taken by then; the same call with page=" + (ask.page() + 1) + " goes on from there, and fetches nothing it has."
                : "The enumeration was cut by the time, so the pool is what was taken by then; the same call with page=" + (ask.page() + 1) + " goes on from there, and fetches nothing it has.");
        String week = Ops.allowanceSaid();
        st.notes.add(st.pagesNew == 0 ? "Read from the pages of the office's records kept in this library; nothing was fetched."
                : "Fetched " + st.pagesNew + (st.pagesNew == 1 ? " page" : " pages") + " of the office's records, " + Ops.megabytes(st.bytes) + " MB" + (st.pagesKept > 0 ? ", and read " + st.pagesKept + " kept in this library" : "") + (week.isEmpty() ? "." : "; " + week + "."));
        String key = LocalDate.now() + "-" + ask.key();
        // the pool is kept whole, with what this call excludes in it: another call may exclude other numbers
        if (store != null) keep(store, ask, result(all, classes, ask, officeTotal, st.publications.size(), 0, st.notes, 1, key, st.cut, st.pagesNew, st.bytes));
        return answer(all, classes, ask, officeTotal, st.publications.size(), st.notes, key, st.cut, st.pagesNew, st.bytes);
    }

    /** The answer to an ask from a whole pool: the families one of whose members is among the numbers rejected are dropped and counted, the page asked for is the text. */
    private static Result answer(List<Family> all, List<ClassCount> classes, Ask ask, int officeTotal, int publications, List<String> notes, String key, boolean cut, int pagesFetched, long bytes) {
        List<Family> pool = new ArrayList<>();
        int excluded = 0;
        for (Family f : all) {
            boolean out = false;
            for (Ops.Publication p : f.members) if (ask.exclude().contains(p.id()) || ask.exclude().contains(p.epodoc())) out = true;
            if (out) excluded++; else pool.add(f);
        }
        return result(pool, classes, ask, officeTotal, publications, excluded, notes, ask.page(), key, cut, pagesFetched, bytes);
    }

    /** One span of one class, to its end: split when the office counts more than it gives of one query; the office's count comes back, -1 when the time cut it before the first page. */
    private static int window(String symbol, Span span, State st) throws IOException, InterruptedException {
        Ops.Page first = page(symbol, span, 1, Ops.PAGE, st);
        if (first == null) return -1;
        int total = first.total(), end = Math.min(total, Ops.CEILING);
        if (total > Ops.CEILING) {
            List<Span> parts = span.parts();
            if (!parts.isEmpty()) {
                st.progress.accept(symbol + " " + span.said() + ": " + total + " at the office, more than the " + Ops.CEILING + " it gives of one query, so it is taken " + (span instanceof Years y && y.from() < y.to() ? "a year" : span instanceof Years ? "a month" : "a day") + " at a time");
                for (Span part : parts) window(symbol, part, st);
                return total;
            }
            st.notes.add("The office counts " + total + " publications of " + symbol + " on " + span.said() + " and gives the first " + Ops.CEILING + " of one day: the rest of that day was not taken, and a narrower class takes it in.");
        }
        st.take(first);
        for (int from = Ops.PAGE + 1; from <= end; from += Ops.PAGE) {
            Ops.Page p = page(symbol, span, from, Math.min(from + Ops.PAGE - 1, end), st);
            if (p == null) break;
            st.take(p);
        }
        return total;
    }

    /** One page of a class in a span: from the library when it is kept, else from the office while the time and the ceiling allow; null when they do not. */
    private static Ops.Page page(String symbol, Span span, int first, int last, State st) throws IOException, InterruptedException {
        String cql = query(symbol, span);
        Path file = st.store == null ? null : pagesDir(st.store).resolve(hash(cql + "|" + first + "-" + last) + ".json");
        Ops.Page kept = file == null ? null : readPage(file);
        if (kept != null) { st.pagesKept++; return kept; }
        if (st.stop.getAsBoolean() || st.pagesNew >= st.cap) { st.cut = true; return null; }
        if (Ops.weekUsed() > Ops.WEEK_STOP) { st.cut = true; st.refused = "Nine tenths of the European Patent Office's free allowance of four gigabytes a week are used, so the enumeration stopped; the allowance is given again at midnight UTC on Sunday."; return null; }
        Ops.Page p = Ops.search(cql, first, last);
        st.pagesNew++; st.bytes += p.bytes();
        if (file != null) writePage(file, cql, p);
        if (first == 1) st.progress.accept(symbol + " " + span.said() + ": " + p.total() + " at the office");
        return p;
    }

    static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("patents"); }
    static Path pagesDir(LibraryStore store) { return dir(store).resolve("pages"); }
    static Path poolsDir(LibraryStore store) { return dir(store).resolve("pools"); }

    private static Ops.Page readPage(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode o = M.readTree(file.toFile());
            LocalDate fetched = LocalDate.parse(o.path("fetched").asText("1970-01-01"));
            if (fetched.plusDays(PAGE_DAYS).isBefore(LocalDate.now())) return null;
            List<Ops.Publication> pubs = new ArrayList<>();
            for (JsonNode p : o.path("publications")) pubs.add(Ops.publicationOf(p));
            return new Ops.Page(o.path("total").asInt(0), o.path("first").asInt(1), o.path("last").asInt(0), pubs, 0);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static void writePage(Path file, String cql, Ops.Page p) {
        try {
            ObjectNode o = M.createObjectNode().put("query", cql).put("fetched", LocalDate.now().toString()).put("total", p.total()).put("first", p.first()).put("last", p.last());
            ArrayNode a = o.putArray("publications"); for (Ops.Publication x : p.publications()) a.add(Ops.json(x));
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** A class's title and subgroups from the office's scheme, kept under the library; null when the office does not say. */
    static Ops.Scheme scheme(LibraryStore store, String symbol) {
        Path file = store == null ? null : dir(store).resolve("scheme").resolve(symbol.replace('/', '_') + ".json");
        try {
            if (file != null && Files.isRegularFile(file)) {
                JsonNode o = M.readTree(file.toFile());
                List<String[]> under = new ArrayList<>(); for (JsonNode u : o.path("under")) under.add(new String[]{u.path(0).asText(""), u.path(1).asText("")});
                return new Ops.Scheme(symbol, o.path("title").asText(""), under);
            }
            Ops.Scheme s = Ops.scheme(symbol);
            if (s != null && file != null) {
                ObjectNode o = M.createObjectNode().put("symbol", symbol).put("title", s.title());
                ArrayNode u = o.putArray("under"); for (String[] x : s.under()) u.addArray().add(x[0]).add(x[1]);
                Files.createDirectories(file.getParent());
                Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            }
            return s;
        } catch (IOException | RuntimeException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    // ---- the machine's filter and order

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    static String fold(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT); }

    static boolean cjk(String s) { return s.codePoints().anyMatch(c -> c >= 0x2E80); }

    /** A word without its ending, so that foldable, folding, folded and folds are one word, and collapse and collapsible. Never shorter than three letters. */
    static String stem(String word) {
        for (String end : List.of("ingly", "edly", "ables", "ibles", "able", "ible", "ings", "ing", "ed", "es", "s", "ly", "e")) if (word.length() - end.length() >= 3 && word.endsWith(end)) return word.substring(0, word.length() - end.length());
        return word;
    }

    /** The stems of a text's words, and the text folded with its spaces out for the scripts written without them. */
    record Read(Set<String> stems, String packed) {
        static Read of(String text) {
            String f = fold(text);
            Set<String> stems = new LinkedHashSet<>();
            Matcher m = WORD.matcher(f);
            while (m.find()) stems.add(stem(m.group()));
            return new Read(stems, f.replaceAll("\\s+", ""));
        }
    }

    /** Whether a text names a feature: one of the feature's words stands in it, every word of a phrase by its stem, a word of a script without spaces as it is written. */
    static boolean names(Read text, Feature feature) {
        for (String w : feature.words()) {
            String f = fold(w).strip();
            if (f.isEmpty()) continue;
            if (cjk(f)) { if (text.packed().contains(f.replaceAll("\\s+", ""))) return true; continue; }
            boolean all = true, any = false;
            Matcher m = WORD.matcher(f);
            while (m.find()) { if (m.group().length() < 2) continue; any = true; if (!text.stems().contains(stem(m.group()))) { all = false; break; } }
            if (any && all) return true;
        }
        return false;
    }

    /**
     * The order, and nothing in it but fit and age: the families whose titles and abstracts name more features first; among those that
     * name as many, the ones naming the feature fewest families name, then the next fewest; then the older by the day that matters for
     * prior art; then the number. No count of citations, no size of a family, no engine's rank is read.
     */
    static void order(List<Family> pool, List<Feature> features) {
        int n = features.size();
        int[] named = new int[n];
        for (Family f : pool) {
            Read text = Read.of(f.text());
            f.named = new boolean[n];
            for (int i = 0; i < n; i++) if (names(text, features.get(i))) { f.named[i] = true; named[i]++; }
        }
        Map<Family, int[]> rarity = new LinkedHashMap<>();
        for (Family f : pool) {
            int[] r = new int[n]; Arrays.fill(r, Integer.MAX_VALUE);
            int k = 0;
            for (int i = 0; i < n; i++) if (f.named[i]) r[k++] = named[i];
            Arrays.sort(r);
            rarity.put(f, r);
        }
        pool.sort((a, b) -> {
            int c = Integer.compare(b.namedCount(), a.namedCount());
            if (c == 0) c = Arrays.compare(rarity.get(a), rarity.get(b));
            if (c == 0) c = a.earliest().compareTo(b.earliest());
            if (c == 0) c = a.first().id().compareTo(b.first().id());
            return c;
        });
    }

    private static Result result(List<Family> pool, List<ClassCount> classes, Ask ask, int officeTotal, int publications, int excluded, List<String> notes, int page, String key, boolean cut, int pagesFetched, long bytes) {
        int n = ask.features().size();
        int[] byNamed = new int[n + 1], perFeature = new int[n];
        for (Family f : pool) { byNamed[Math.min(n, f.namedCount())]++; for (int i = 0; i < n && i < f.named.length; i++) if (f.named[i]) perFeature[i]++; }
        Result r = new Result(pool, classes, ask.features(), ask.fromYear(), ask.toYear(), officeTotal, publications, excluded, byNamed, perFeature, notes, page, key, cut, pagesFetched, bytes, "");
        return new Result(pool, classes, ask.features(), ask.fromYear(), ask.toYear(), officeTotal, publications, excluded, byNamed, perFeature, notes, page, key, cut, pagesFetched, bytes, text(r, page));
    }

    // ---- what is kept

    static Path poolFile(LibraryStore store, Ask ask) { return poolsDir(store).resolve(LocalDate.now() + "-" + ask.key() + ".json"); }

    /** A kept pool by its key: the whole key (day and hash), or the hash alone for the newest pool under it; the newest of all when the key is empty; null when none is kept. */
    public static Path poolFileByKey(LibraryStore store, String key) {
        String k = key == null ? "" : key.strip().replaceAll("\\.json$", "");
        Path dir = poolsDir(store);
        if (!Files.isDirectory(dir)) return null;
        if (!k.isEmpty() && !k.matches("(\\d{4}-\\d{2}-\\d{2}-)?[0-9a-f]{16}")) return null;
        if (k.length() > 16) { Path f = dir.resolve(k + ".json"); return Files.isRegularFile(f) ? f : null; }
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(k.isEmpty() ? ".json" : "-" + k + ".json")).max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException e) { return null; }
    }

    static void keep(LibraryStore store, Ask ask, Result r) {
        try {
            ObjectNode o = M.createObjectNode().put("key", r.key()).put("made", LocalDate.now().toString()).put("from_year", ask.fromYear()).put("to_year", ask.toYear()).put("office_total", r.officeTotal()).put("publications", r.publications())
                    .put("excluded", r.excluded()).put("cut", r.cut()).put("pages_fetched", r.pagesFetched()).put("bytes", r.bytes());
            ArrayNode cs = o.putArray("classes");
            for (ClassCount c : r.classes()) { ObjectNode x = cs.addObject().put("symbol", c.symbol()).put("title", c.title()).put("total", c.total()).put("taken", c.taken()).put("with_subgroups", c.withSubgroups()); ArrayNode u = x.putArray("under"); for (String[] s : c.under()) u.addArray().add(s[0]).add(s[1]); }
            ArrayNode fs = o.putArray("features"); for (Feature f : ask.features()) fs.add(f.phrase());
            ArrayNode notes = o.putArray("notes"); for (String n : r.notes()) notes.add(n);
            ArrayNode pool = o.putArray("pool"); for (Family f : r.pool()) pool.add(f.json());
            Path file = poolFile(store, ask);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            // the whole pool on the shelves too, a line a family, so a later run finds it by its words
            StringBuilder text = new StringBuilder(head(r) + "\n");
            int i = 0;
            for (Family f : r.pool()) text.append(++i).append(". ").append(line(f, r.features(), 400)).append('\n');
            RawCapture.capture(store, "researchzosho://patents/" + r.key(), text.toString(), "Patents in " + classesSaid(r.classes()) + ", " + r.fromYear() + " to " + r.toYear(), "researchzosho-patents", "");
        } catch (IOException | RuntimeException ignored) { }
    }

    /** A kept pool as the reading pass takes it. */
    public record Kept(String key, List<Family> pool, List<Feature> features, List<ClassCount> classes, int fromYear, int toYear, int officeTotal, int publications, int excluded, boolean cut, List<String> notes) { }

    public static Kept read(Path poolFile) {
        try {
            JsonNode o = M.readTree(poolFile.toFile());
            List<Family> pool = new ArrayList<>(); for (JsonNode f : o.path("pool")) { Family x = Family.of(f); if (x != null) pool.add(x); }
            List<Feature> features = new ArrayList<>(); for (JsonNode f : o.path("features")) { Feature x = Feature.of(f.asText("")); if (x != null) features.add(x); }
            List<ClassCount> classes = new ArrayList<>();
            for (JsonNode c : o.path("classes")) { List<String[]> under = new ArrayList<>(); for (JsonNode u : c.path("under")) under.add(new String[]{u.path(0).asText(""), u.path(1).asText("")}); classes.add(new ClassCount(c.path("symbol").asText(""), c.path("title").asText(""), c.path("total").asInt(-1), c.path("taken").asInt(0), c.path("with_subgroups").asInt(-1), under)); }
            List<String> notes = new ArrayList<>(); for (JsonNode n : o.path("notes")) notes.add(n.asText(""));
            return new Kept(poolFile.getFileName().toString().replaceAll("\\.json$", ""), pool, features, classes, o.path("from_year").asInt(0), o.path("to_year").asInt(0), o.path("office_total").asInt(0), o.path("publications").asInt(0), o.path("excluded").asInt(0), o.path("cut").asBoolean(false), notes);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** The pool kept today for the same classes, years and features, at the page asked and without what is excluded now; null when none was kept. */
    static Result kept(LibraryStore store, Ask ask) {
        Path file = poolFile(store, ask);
        if (!Files.isRegularFile(file)) return null;
        Kept k = read(file);
        if (k == null) return null;
        List<String> notes = new ArrayList<>();
        for (String n : k.notes()) if (!n.startsWith("Fetched ") && !n.startsWith("Read from the pages") && !n.startsWith("Read back")) notes.add(n);
        notes.add("Read back from the pool kept today for the same classes, years and features; nothing was fetched.");
        return answer(k.pool(), k.classes(), ask, k.officeTotal(), k.publications(), notes, k.key(), k.cut(), 0, 0);
    }

    // ---- the text

    static String count(int n) { return String.format(Locale.US, "%,d", n); }

    static String classesSaid(List<ClassCount> classes) {
        List<String> out = new ArrayList<>();
        for (ClassCount c : classes) out.add(c.symbol() + (c.title().isEmpty() ? "" : " (" + Acquisitions.compress(c.title(), 60) + ")"));
        return String.join(", ", out);
    }

    /** A count of things in words a sentence can carry: "3 name all three", "31 two", "212 one", "594 none". */
    static String byNamedSaid(int[] byNamed) {
        int n = byNamed.length - 1;
        if (n <= 0) return "";
        List<String> parts = new ArrayList<>();
        for (int k = n; k >= 0; k--) {
            String how = k == n && n == 2 ? "both" : k == n && n > 1 ? "all " + Sift.few(n) : k == 0 ? "none" : Sift.few(k).equals(String.valueOf(k)) ? (k == 1 ? "one" : String.valueOf(k)) : Sift.few(k);
            parts.add(count(byNamed[k]) + (parts.isEmpty() ? (byNamed[k] == 1 ? " names " : " name ") : " ") + how);
        }
        return String.join(", ", parts);
    }

    /** The counts in one sentence: the office's own count, what was enumerated, in how many families, and how many name how many features. */
    static String head(Result r) {
        StringBuilder b = new StringBuilder("Patents in " + classesSaid(r.classes()) + ", published " + r.fromYear() + " to " + r.toYear() + ": the office counts " + count(r.officeTotal()) + (r.officeTotal() == 1 ? " publication" : " publications")
                + (r.classes().size() > 1 ? " (one in two of the classes is counted in each)" : "") + "; " + count(r.publications()) + (r.publications() == 1 ? " was" : " were") + " enumerated, in " + count(r.pool().size() + r.excluded()) + (r.pool().size() + r.excluded() == 1 ? " family" : " families") + ", one line a family.");
        int n = r.features().size();
        if (n > 0 && !r.pool().isEmpty()) {
            List<String> each = new ArrayList<>();
            for (int i = 0; i < n; i++) each.add(r.features().get(i).name() + ": " + count(r.perFeature()[i]));
            if (n == 1) b.append(" In their titles and abstracts ").append(count(r.byNamed()[1])).append(r.byNamed()[1] == 1 ? " names " : " name ").append("the feature (").append(r.features().get(0).name()).append(") and ").append(count(r.byNamed()[0])).append(r.byNamed()[0] == 1 ? " does not." : " do not.");
            else b.append(" In their titles and abstracts ").append(byNamedSaid(r.byNamed())).append(" of the ").append(Sift.few(n)).append(" features (").append(String.join("; ", each)).append(").");
        }
        if (r.excluded() > 0) b.append(" Left out: ").append(r.excluded()).append(" already rejected.");
        return b.toString();
    }

    /** One family's line, no longer than {@code width}: its oldest number, the day that matters, its title, its applicant, the features named, the other members. */
    static String line(Family f, List<Feature> features, int width) {
        List<String> named = new ArrayList<>();
        for (int i = 0; i < features.size() && i < f.named.length; i++) if (f.named[i]) named.add(Acquisitions.compress(features.get(i).name(), 16));
        List<String> others = new ArrayList<>();
        for (int i = 1; i < f.members.size(); i++) others.add(f.members.get(i).id());
        String tail = (named.isEmpty() ? "" : " · names: " + String.join(", ", named)) + (others.isEmpty() ? "" : " · also " + String.join(", ", others.subList(0, Math.min(2, others.size()))) + (others.size() > 2 ? " +" + (others.size() - 2) : ""));
        String head = f.first().id() + " · " + (f.earliest().isEmpty() ? "no day" : f.earliest());
        String applicant = f.applicant().isEmpty() ? "" : " · " + Acquisitions.compress(f.applicant(), 26);
        int room = Math.max(16, width - head.length() - tail.length() - applicant.length() - 6);
        String title = f.title().isEmpty() ? "(no title)" : Acquisitions.compress(f.title().replace("\n", " ").replace("\"", "'"), room);
        return head + " · \"" + title + "\"" + applicant + tail;
    }

    /** The literal next call the answer names. */
    static String next(String key) { return "patent_abstracts {\"from\": \"" + key + "\"}"; }

    /** The most characters of the text: a research worker's observation is cut in its middle past six thousand, and the middle here is the list. */
    static final int TEXT_MOST = 5_800;

    /** The text the model and the person read: the counts and the next step first, the page's lines fenced as the office's text, then what the classes hold besides. */
    static String text(Result r, int page) {
        StringBuilder head = new StringBuilder(head(r));
        head.append(" The pool is kept under the key ").append(r.key()).append(". Next, read it: ").append(next(r.key())).append(" reads every family's abstract, best fit first, twenty to a call, and says of each feature yes, maybe or no; then patent_claims {\"from\": \"").append(r.key()).append("\"} reads the claims of those that pass from the office's own full text.");
        for (String n : r.notes()) head.append(' ').append(n);
        head.append('\n');
        StringBuilder tail = new StringBuilder();
        int from = (page - 1) * SHOWN, to = Math.min(r.pool().size(), from + SHOWN);
        if (to < r.pool().size() && from < r.pool().size()) tail.append("The same call with page ").append(page + 1).append(" shows the next ").append(Math.min(SHOWN, r.pool().size() - to)).append(" without fetching anything. ");
        for (ClassCount c : r.classes()) {
            if (c.withSubgroups() > c.total() && c.total() >= 0) tail.append(c.symbol()).append(" itself holds ").append(count(c.total())).append("; with the subgroups under it the office counts ").append(count(c.withSubgroups())).append(", and a subgroup is a class of its own to give. ");
            if (!c.under().isEmpty()) {
                List<String> u = new ArrayList<>();
                for (String[] s : c.under().subList(0, Math.min(8, c.under().size()))) u.add(s[0] + (s[1].isEmpty() ? "" : " " + Acquisitions.compress(s[1], 48)));
                tail.append("Under ").append(c.symbol()).append(": ").append(String.join("; ", u)).append(c.under().size() > 8 ? "; and " + (c.under().size() - 8) + " more. " : ". ");
            }
        }
        StringBuilder b = new StringBuilder(head);
        if (r.pool().isEmpty()) b.append(r.officeTotal() > 0 || r.cut() ? "No family was taken into the pool." : "The office holds no publication in these classes and years. Check the class: " + HOW_TO_FIND_A_CLASS).append('\n');
        else if (from >= r.pool().size()) b.append("Page ").append(page).append(" is past the end: the pool has ").append(count(r.pool().size())).append(" families, ").append(SHOWN).append(" a page.\n");
        else {
            String open = "Families " + (from + 1) + " to " + to + " of " + count(r.pool().size()) + ", by how many features the title and abstract name, the rarer feature first, then the older; no citation count, family size or other popularity is in the order (the office's titles, fenced):\n" + Fence.open("PATENTS") + "\n";
            String close = Fence.close("PATENTS") + "\n" + Fence.rule("PATENTS") + "\n";
            // the lines as wide as the text's room allows, so that the whole page stands in what a worker is shown
            int room = TEXT_MOST - head.length() - tail.length() - open.length() - close.length();
            int width = Math.max(64, Math.min(LINE, room / (to - from) - 5));
            b.append(open);
            for (int i = from; i < to; i++) b.append(i + 1).append(". ").append(line(r.pool().get(i), r.features(), width)).append('\n');
            b.append(close);
        }
        return b.append(tail).toString().stripTrailing();
    }

    /** The result as the protocol answers it: the counts, the page's families, the key. */
    public static ObjectNode json(Result r) {
        ObjectNode o = M.createObjectNode();
        o.put("key", r.key()).put("from_year", r.fromYear()).put("to_year", r.toYear()).put("office_total", r.officeTotal()).put("publications", r.publications()).put("families", r.pool().size()).put("excluded", r.excluded())
                .put("page", r.page()).put("per_page", SHOWN).put("cut", r.cut()).put("pages_fetched", r.pagesFetched()).put("bytes_fetched", r.bytes());
        ArrayNode cs = o.putArray("classes");
        for (ClassCount c : r.classes()) cs.addObject().put("symbol", c.symbol()).put("title", c.title()).put("office_total", c.total()).put("taken", c.taken()).put("with_subgroups", c.withSubgroups());
        ArrayNode fs = o.putArray("features");
        for (int i = 0; i < r.features().size(); i++) fs.addObject().put("feature", r.features().get(i).phrase()).put("named_by", i < r.perFeature().length ? r.perFeature()[i] : 0);
        ArrayNode by = o.putArray("families_by_features_named"); for (int k : r.byNamed()) by.add(k);
        ArrayNode notes = o.putArray("notes"); for (String n : r.notes()) notes.add(n);
        ArrayNode a = o.putArray("patents");
        int from = (r.page() - 1) * SHOWN;
        for (int i = from; i < Math.min(r.pool().size(), from + SHOWN); i++) {
            Family f = r.pool().get(i);
            ObjectNode x = a.addObject().put("number", f.first().id()).put("link", f.first().link()).put("title", f.title()).put("applicant", f.applicant()).put("earliest", f.earliest()).put("published", f.first().published()).put("family", f.id);
            ArrayNode named = x.putArray("names"); for (int k = 0; k < r.features().size() && k < f.named.length; k++) if (f.named[k]) named.add(r.features().get(k).name());
            ArrayNode others = x.putArray("other_members"); for (int k = 1; k < f.members.size(); k++) others.add(f.members.get(k).id());
        }
        o.put("summary", r.text());
        return o;
    }

}
